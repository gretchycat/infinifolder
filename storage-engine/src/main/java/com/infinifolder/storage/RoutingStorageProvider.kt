package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.archive.BlockDevice
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.*
import java.io.InputStream
import java.io.OutputStream

/** Common routing, normalization, error boundaries, and diagnostics for every backend. */
class RoutingStorageProvider(vararg providers: StorageProvider, private val uriResolver: ((Uri) -> Uri?)? = null) : StorageProvider {
    override val id = "router"
    private val byId = providers.associateBy { it.id }.also { require(it.size == providers.size) { "Duplicate provider ID" } }
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
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = call("delete", ref) { it.delete(ref) }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = StorageTransfers.transfer(this, source, destination, false)
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = StorageTransfers.transfer(this, source, destination, true)
}
