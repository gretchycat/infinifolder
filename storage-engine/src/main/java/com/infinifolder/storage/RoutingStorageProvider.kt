package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.archive.BlockDevice
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Common routing, normalization, error boundaries, and diagnostics for every backend. */
class RoutingStorageProvider(vararg providers: StorageProvider, private val uriResolver: ((Uri) -> Uri?)? = null) : StorageProvider, RandomAccessTransferRouter {
    override val id = "router"
    private val byId = providers.associateBy { it.id }.also { require(it.size == providers.size) { "Duplicate provider ID" } }

    /** Restart durable direct-to-storage torrent sessions after the process is recreated. */
    fun resumePendingTorrentTransfers(): Int {
        val sessions = (byId["torrent"] as? RandomAccessTransferSource)?.pendingResumeTransfers().orEmpty()
        val torrentSource = byId["torrent"] as? RandomAccessTransferSource
        var scheduled = 0
        for (session in sessions) {
            val label = "Resume torrent: ${session.incompleteName}"
            if (com.infinifolder.ops.AppOperations.coordinator.snapshots().any {
                    it.label == label && (it.state == com.infinifolder.ops.OperationState.RUNNING || it.state == com.infinifolder.ops.OperationState.QUEUED)
                }) continue
            if (torrentSource?.claimAutoResume(session.source, session.destination) == false) {
                DiagnosticLog.record("torrent.resume.auto-skip", "reason=already-attempted destination=${session.destination.providerId}")
                continue
            }
            val conflicts = setOf(
                "resource:${session.source.providerId}:${session.source.documentId}",
                "object:${session.destinationParent.providerId}:${session.destinationParent.documentId}:${session.finalName.lowercase(java.util.Locale.ROOT)}",
                "object:${session.destinationParent.providerId}:${session.destinationParent.documentId}:${session.incompleteName.lowercase(java.util.Locale.ROOT)}"
            )
            com.infinifolder.ops.AppOperations.coordinator.submit(label, null, 1, conflictKeys = conflicts) { update, cancelled ->
                val received = java.util.concurrent.atomic.AtomicLong()
                update(com.infinifolder.ops.OperationUpdate(currentItem = session.incompleteName,
                    message = "Resuming verified torrent pieces", indeterminate = true))
                when (val result = transferRandomAccess(session.source, session.destination, cancelled, onBytesWritten = { delta ->
                    val total = received.addAndGet(delta)
                    update(com.infinifolder.ops.OperationUpdate(bytes = total, currentItem = session.incompleteName,
                        message = "Downloading torrent", indeterminate = true))
                }, onPieceVerification = { verified, total ->
                    update(com.infinifolder.ops.OperationUpdate(bytes = received.get(), currentItem = session.incompleteName,
                        message = if (total > 0) "Verifying torrent pieces: $verified of $total" else "Checking torrent pieces…",
                        indeterminate = false, currentItemBytes = verified.toLong(), currentItemTotalBytes = total.toLong()))
                })) {
                    is ProviderResult.Success -> {
                        val destinationProvider = byId[session.destination.providerId]
                            ?: throw IOException("Destination storage is unavailable for torrent resume")
                        val renamed = destinationProvider.rename(session.destination, session.finalName)
                        if (renamed !is ProviderResult.Success) throw IOException(
                            "Torrent finished but could not rename the completed file: " +
                                ((renamed as? ProviderResult.Failure)?.message ?: "Rename unsupported"),
                            (renamed as? ProviderResult.Failure)?.cause
                        )
                        forgetResumeDestination(session.source, session.destinationParent, session.requestedName)
                        finishResume(session.source, renamed.value.ref)
                    }
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw IOException("Destination no longer supports random-access torrent resume")
                }
            }
            scheduled++
        }
        return scheduled
    }
    override fun close() { byId.values.forEach { it.close() } }
    override fun refForUri(uri: Uri): ResourceRef? {
        val resolved = if (uriResolver != null) uriResolver.invoke(uri) ?: return null else uri
        return byId.values.sortedBy { it.id == "saf" }.asSequence().mapNotNull { it.refForUri(resolved) }.firstOrNull()
    }
    private fun normalized(entry: ResourceEntry) = entry.copy(metadata = entry.metadata.copy(
        mimeType = MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType)))
    private fun <T> call(operation: String, ref: ResourceRef, action: (StorageProvider) -> ProviderResult<T>): ProviderResult<T> = StorageActivity.track(
        if (operation in setOf("batch.begin", "replace", "replace-file", "write", "mkdir", "create", "rename", "delete", "seekable-write"))
            StorageActivity.Kind.FILE_OPERATION else StorageActivity.Kind.LOADING
    ) {
        val result = try {
            byId[ref.providerId]?.let(action) ?: ProviderResult.Failure("Unknown provider: ${ref.providerId}")
        } catch (e: Exception) { ProviderResult.Failure(e.message ?: "$operation failed", e) }
        when (result) {
            is ProviderResult.Failure -> DiagnosticLog.record("storage.$operation.failed", "provider=${ref.providerId} ${result.message}", result.cause)
            is ProviderResult.Unsupported -> DiagnosticLog.record("storage.$operation.unsupported", "provider=${ref.providerId} capability=${result.capability}")
            is ProviderResult.Success -> DiagnosticLog.record("storage.$operation", "provider=${ref.providerId} loading=${result.isLoading}" + (result.notice?.let { " notice=$it" } ?: ""))
        }
        result
    }
    override fun beginWriteBatch(destination: ResourceRef, isCancelled: () -> Boolean): ProviderResult<StorageWriteBatch> =
        call("batch.begin", destination) { it.beginWriteBatch(destination, isCancelled) }
    override fun backingResource(ref: ResourceRef): ResourceRef? = byId[ref.providerId]?.backingResource(ref)
    override fun recoverySource(ref: ResourceRef): ResourceRef? = byId[ref.providerId]?.recoverySource(ref)
    override fun deviceIdentity(ref: ResourceRef): String? = byId[ref.providerId]?.deviceIdentity(ref)
    override fun capabilities(ref: ResourceRef): ProviderResult<ResourceCapabilities> = call("capabilities", ref) { it.capabilities(ref) }
    override fun fileSystemInfo(ref: ResourceRef): ProviderResult<FileSystemInfo> = call("filesystem", ref) { it.fileSystemInfo(ref) }
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = call("parent", ref) { it.parent(ref) }
    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = call("link", ref) { it.resolveLink(ref) }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = call("stat", ref) { backend ->
        when (val result = backend.stat(ref)) {
            is ProviderResult.Success -> result.copy(value = normalized(result.value))
            else -> result
        }
    }
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = call("list", parent) { backend ->
        when (val result = backend.list(parent)) {
            is ProviderResult.Success -> result.copy(value = result.value.map(::normalized))
            else -> result
        }
    }
    override fun openPartialRecovery(ref: ResourceRef): ProviderResult<RecoveryExport> =
        call("recovery.partial", ref) { it.openPartialRecovery(ref) }
    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> = call(if (writable) "seekable-write" else "seekable", ref) { backend ->
        val result = backend.openSeekable(ref, writable)
        if (result is ProviderResult.Success && writable && result.value.isReadOnly) {
            result.value.close()
            ProviderResult.Unsupported(ResourceCapability.WRITE)
        } else if (result is ProviderResult.Success) {
            val device = result.value
            result.copy(value = object : BlockDevice by device {
                override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int =
                    device.readAt(offset, buffer, dstOffset, length).also {
                        if (it > 0) StorageActivity.pulse(StorageActivity.Kind.LOADING)
                    }
            })
        } else result
    }
    override fun transferRandomAccess(
        source: ResourceRef,
        destination: ResourceRef,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (Int, Int) -> Unit
    ): ProviderResult<Long> = StorageActivity.track(StorageActivity.Kind.FILE_OPERATION) {
        val producer = byId[source.providerId] as? RandomAccessTransferSource
            ?: return@track ProviderResult.Unsupported(ResourceCapability.SEEK)
        val targetProvider = byId[destination.providerId]
            ?: return@track ProviderResult.Failure("Unknown destination provider: ${destination.providerId}")
        val targetResult = try { targetProvider.openTransferTarget(destination) }
            catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open random-write destination", e) }
        val target = when (targetResult) {
            is ProviderResult.Success -> targetResult.value
            is ProviderResult.Failure -> return@track ProviderResult.Failure(targetResult.message, targetResult.cause)
            is ProviderResult.Unsupported -> return@track ProviderResult.Unsupported(targetResult.capability)
        }
        if (target.isReadOnly || !target.capabilities.contains(com.infinifolder.archive.DeviceCapability.WRITE) ||
            !target.capabilities.contains(com.infinifolder.archive.DeviceCapability.TRUNCATE)) {
            runCatching { target.close() }
            return@track ProviderResult.Unsupported(ResourceCapability.WRITE)
        }
        try {
            producer.transferTo(source, destination, target, isCancelled, onBytesWritten, onPieceVerification)
        } catch (e: Exception) {
            ProviderResult.Failure(e.message ?: "Random-access transfer failed", e)
        } finally {
            try { target.close() }
            catch (e: Exception) { DiagnosticLog.record("transfer.random-access.target-close-failed", destination.documentId, e) }
        }
    }

    override fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? =
        (byId[source.providerId] as? RandomAccessTransferSource)?.findResumeDestination(source, destinationParent, requestedName)

    override fun rememberResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String, destination: ResourceRef, destinationName: String) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.rememberResumeDestination(source, destinationParent, requestedName, destination, destinationName)
    }

    override fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.forgetResumeDestination(source, destinationParent, requestedName)
    }

    override fun discardResumeState(source: ResourceRef, destination: ResourceRef) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.discardResumeState(source, destination)
    }

    override fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.migrateResumeState(source, oldDestination, newDestination)
    }

    override fun finishResume(source: ResourceRef, destination: ResourceRef) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.finishResume(source, destination)
    }
    override fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> = call("replace", ref) { it.replaceContent(ref, data) }
    override fun replaceContentFromFile(ref: ResourceRef, file: java.io.File): ProviderResult<Unit> =
        call("replace-file", ref) { it.replaceContentFromFile(ref, file) }
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = call("read", ref) { backend ->
        when (val result = backend.openRead(ref)) {
            is ProviderResult.Success -> result.copy(value = object : java.io.FilterInputStream(result.value) {
                override fun read(): Int = super.read().also { if (it >= 0) StorageActivity.pulse(StorageActivity.Kind.LOADING) }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
                    if (it > 0) StorageActivity.pulse(StorageActivity.Kind.LOADING)
                }
            })
            else -> result
        }
    }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = call("write", ref) { it.openWrite(ref) }
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = call("mkdir", parent) { it.createDirectory(parent, name) }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = call("create", parent) {
        it.createFile(parent, name, MimeRegistry.effective(name, mimeType))
    }
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = call("rename", ref) { it.rename(ref, name) }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = call("delete", ref) { backend ->
        val result = backend.delete(ref)
        if (result is ProviderResult.Success) {
            val torrent = byId["torrent"] as? RandomAccessTransferSource
            torrent?.pendingResumeTransfers()?.filter { it.destination == ref }?.forEach { session ->
                torrent.forgetResumeDestination(session.source, session.destinationParent, session.requestedName)
                torrent.discardResumeState(session.source, ref)
                DiagnosticLog.record("torrent.resume.destination-deleted", "destination=${ref.providerId}:${ref.documentId}")
            }
        }
        result
    }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = StorageTransfers.transfer(this, source, destination, false)
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = StorageTransfers.transfer(this, source, destination, true)
}
