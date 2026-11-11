package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.asInputStream
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.BlockDeviceSource
import com.infinifolder.storage.BytePatchJournal
import com.infinifolder.storage.ContentReplacement
import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.storage.DiskCache
import com.infinifolder.storage.ProviderStreams
import com.infinifolder.storage.ViewerStorage
import com.infinifolder.storage.validateSeekableResult
import com.infinifolder.storage.openNativeSeekable
import io.github.gretchycat.infiniscribe.BinaryChunkReader
import io.github.gretchycat.infiniscribe.HexDocument
import io.github.gretchycat.infinistream.CancellationToken
import io.github.gretchycat.infinistream.PositionalReadable
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Source handles and durable differences belong to this retained, single-worker session. */
internal class HexSession(val uri: Uri, val name: String) {
    val worker = Executors.newSingleThreadExecutor { Thread(it, "hex-document") }
    val cancelled = AtomicBoolean()
    @Volatile var busy = false
    @Volatile var directory: File? = null
    var document: HexDocument? = null
    var writable = false
    @Volatile var recoveryPending = false
        private set
    private var owner: Closeable? = null
    private var sequential: BinaryChunkReader? = null
    val randomRead get() = sequential == null
    fun canRead(offset: Long) = sequential?.canRead(offset) ?: true
    val bufferedStart get() = sequential?.bufferedStart ?: 0L

    private class FileReader(file: File) : PositionalReadable, Closeable {
        private val input = RandomAccessFile(file, "r")
        val length get() = input.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            input.seek(position); return input.read(buffer, offset, length)
        }
        override fun close() = input.close()
    }

    fun open(context: Context, restoredId: String?) {
        try {
            val editsRoot = File(context.filesDir, "hex_edits")
            val interrupted = if (restoredId == null) editsRoot.listFiles().orEmpty().filter { candidate ->
                candidate.isDirectory && File(candidate, "merge-pending").isFile && runCatching {
                    Properties().apply { File(candidate, "session.properties").inputStream().use(::load) }
                        .getProperty("uri") == uri.toString()
                }.getOrDefault(false)
            } else emptyList()
            require(interrupted.size <= 1) { "Multiple interrupted byte merges exist for this source; recovery journals have been retained" }
            val folder = (restoredId ?: interrupted.singleOrNull()?.name)?.let {
                require(Regex("[a-f0-9-]{36}").matches(it)) { "Invalid hex edit draft" }
                File(editsRoot, it)
            }
            val propsFile = folder?.let { File(it, "session.properties") }
            val props = propsFile?.takeIf { it.isFile }?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
            }
            if (props != null) require(props.getProperty("uri") == uri.toString()) { "Edit draft belongs to another file" }
            else if (folder?.exists() == true) throw IOException("The edit draft is incomplete; its files have been retained")

            // Old snapshots are read only for compatibility; new sessions never create one.
            val legacy = folder?.let { File(it, "original.bin") }?.takeIf { props?.getProperty("version") == null && it.isFile }
            if (legacy != null) {
                val input = FileReader(legacy); owner = input; document = HexDocument(input, input.length)
            } else openSource(context)
            writable = MediaSourceAdapter.isWritable(context, uri)
            if (props != null && folder != null) {
                directory = folder
                val journal = File(folder, "differences.bin")
                if (journal.isFile) {
                    val doc = checkNotNull(document)
                    if (doc.lengthKnown && BytePatchJournal.length(journal) != doc.length)
                        throw IOException("The source length changed; edit differences retained")
                    BytePatchJournal.read(journal) { doc.restoreByteChange(it.offset, it.before, it.after) }
                    if (doc.changedCount == 0) File(folder, "merge-pending").delete()
                    else if (File(folder, "merge-pending").isFile) {
                        recoveryPending = true
                        DiagnosticLog.record("hex.merge.resume-required", "journal=${folder.name} changes=${doc.changedCount}")
                    }
                } else {
                    val old = File(folder, "changes.bin")
                    if (old.isFile && legacy != null) DataInputStream(old.inputStream().buffered()).use { input ->
                        val count = input.readInt(); require(count in 0..1024 * 1024) { "Invalid edit journal" }
                        document!!.restoreEntries(sequence { repeat(count) { yield(input.readLong() to input.readByte()) } })
                        require(input.read() == -1) { "Invalid edit journal" }
                    }
                }
            }
        } catch (error: Exception) {
            document = null; runCatching { owner?.close() }; owner = null; sequential = null
            throw error
        }
    }

    private fun openSource(context: Context) {
        val provider = ViewerStorage.get(context).provider
        val ref = provider.refForUri(uri) ?: throw IOException("No storage provider owns this source")
        when (val opened = validateSeekableResult(provider.openNativeSeekable(ref, false), false)) {
            is ProviderResult.Success -> {
                val source = try { BlockDeviceSource(opened.value) } catch (error: Exception) { opened.value.close(); throw error }
                owner = source; sequential = null; document = HexDocument(source, source.metadata.length!!)
            }
            is ProviderResult.Unsupported -> {
                val metadata = (provider.stat(ref) as? ProviderResult.Success)?.value?.metadata
                val source = ProviderStreams.openRead(provider, ref, metadata, uri.toString())
                val reader = BinaryChunkReader(source, CancellationToken { cancelled.get() })
                sequential = reader; owner = reader
                document = HexDocument.sequential(reader, metadata?.sizeBytes)
            }
            is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
        }
        if (cancelled.get()) throw java.io.InterruptedIOException("Viewer load cancelled")
        DiagnosticLog.record("hex.source", "access=${if (randomRead) "native-random" else "sequential-memory-window"} bytes=${document?.length?.takeIf { document?.lengthKnown == true }}")
    }

    /** Enabling edits creates metadata only. Original bytes are captured with each changed byte. */
    fun beginEditing(context: Context) {
        if (directory != null) return
        val doc = checkNotNull(document)
        require(doc.lengthKnown) { "Read to the end before editing a stream with unknown length" }
        val folder = File(File(context.filesDir, "hex_edits"), UUID.randomUUID().toString())
        check(folder.mkdirs()) { "Cannot create byte-diff journal" }
        try {
            val props = Properties().apply {
                setProperty("version", "2"); setProperty("uri", uri.toString()); setProperty("length", doc.length.toString())
            }
            File(folder, "session.properties").outputStream().use { props.store(it, "Hex byte differences"); it.fd.sync() }
            BytePatchJournal.write(File(folder, "differences.bin"), doc.length, emptySequence())
            directory = folder
        } catch (error: Exception) { folder.deleteRecursively(); throw error }
    }

    fun persist() {
        val folder = directory ?: return
        check(!File(folder, "merge-pending").exists()) { "Resume the interrupted save before changing its recovery differences" }
        val doc = checkNotNull(document)
        BytePatchJournal.write(File(folder, "differences.bin"), doc.length,
            doc.byteChanges().map { BytePatchJournal.Change(it.offset, it.before, it.after) })
    }

    fun mutate(change: (HexDocument) -> Unit) {
        val folder = checkNotNull(directory) { "Enable editing first" }
        check(!File(folder, "merge-pending").exists()) { "Resume the interrupted save before editing its recovery differences" }
        change(checkNotNull(document)); persist()
    }

    private fun freshInput(context: Context): java.io.InputStream {
        val provider = ViewerStorage.get(context).provider
        val ref = provider.refForUri(uri) ?: throw IOException("Source provider unavailable")
        return when (val opened = validateSeekableResult(provider.openNativeSeekable(ref, false), false)) {
            is ProviderResult.Success -> object : java.io.FilterInputStream(opened.value.asInputStream()) {
                override fun close() = opened.value.close()
            }
            is ProviderResult.Unsupported -> when (val stream = provider.openRead(ref)) {
                is ProviderResult.Success -> stream.value
                is ProviderResult.Failure -> throw IOException(stream.message, stream.cause)
                is ProviderResult.Unsupported -> throw IOException("Source is not readable")
            }
            is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
        }
    }

    fun save(context: Context, destination: Uri = uri): Boolean {
        beginEditing(context)
        val folder = checkNotNull(directory)
        val journal = File(folder, "differences.bin")
        val pending = File(folder, "merge-pending")
        val provider = ViewerStorage.get(context).provider
        val ref = provider.refForUri(destination) ?: throw IOException("Destination provider unavailable")
        val sameOriginal = destination == uri || ref == provider.refForUri(uri)
        if (pending.exists()) check(sameOriginal) { "Resume saving the original before exporting interrupted differences" }
        else persist()
        if (sameOriginal) {
            when (val opened = validateSeekableResult(provider.openNativeSeekable(ref, true), true)) {
                is ProviderResult.Success -> try {
                    opened.value.use { BytePatchJournal.merge(it, journal, pending, cancelled::get) }
                } finally { recoveryPending = pending.exists() }
                is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
                is ProviderResult.Unsupported -> saveSequential(context, destination, journal, sameOriginal)
            }
            // A crash before this durable clear leaves an idempotently resumable merge.
            BytePatchJournal.write(journal, checkNotNull(document).length, emptySequence())
            pending.delete()
            recoveryPending = false
            owner?.close(); owner = null; sequential = null; document = null; directory = null
            folder.deleteRecursively()
            try { openSource(context) } catch (error: Exception) {
                throw IOException("Bytes were saved, but the source could not be reopened: ${error.message}", error)
            }
        } else {
            val exported = when (val opened = validateSeekableResult(provider.openNativeSeekable(ref, true), true)) {
                is ProviderResult.Success -> opened.value.use { target ->
                    if (target.size == 0L && com.infinifolder.archive.DeviceCapability.TRUNCATE in target.capabilities) {
                        freshInput(context).use { BytePatchJournal.export(target, journal, it, cancelled::get) }; true
                    } else false
                }
                is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
                is ProviderResult.Unsupported -> false
            }
            if (!exported) saveSequential(context, destination, journal, sameOriginal)
        }
        return sameOriginal
    }

    private fun saveSequential(context: Context, destination: Uri, journal: File, sameOriginal: Boolean) {
        // The shared cache owns limits, reservations, eviction and the capacity exception/dialog.
        DiskCache.get(context).allocate("hex-replacement.bin", BytePatchJournal.length(journal)).use { lease ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            freshInput(context).use { input ->
                java.security.DigestInputStream(input, digest).use { original ->
                    lease.output().use { output -> BytePatchJournal.render(journal, original, output, cancelled::get,
                        verifyOriginal = sameOriginal) }
                }
            }
            RandomAccessFile(lease.file, "rw").use { it.fd.sync() }
            val revision = ContentRevision(lease.file.length(), digest.digest().joinToString("") { "%02x".format(it) })
            lease.complete()
            val provider = ViewerStorage.get(context).provider
            val ref = provider.refForUri(destination) ?: throw IOException("Destination provider unavailable")
            when (val result = ContentReplacement.replaceFromFile(provider, ref, lease.file,
                File(context.filesDir, "save_recovery"), if (sameOriginal) revision else null)) {
                is ProviderResult.Success -> Unit
                is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                is ProviderResult.Unsupported -> throw IOException("Saving is unsupported for this destination")
            }
        }
    }

    fun close(discard: Boolean) {
        owner?.close()
        if ((discard || document?.changedCount == 0) && directory?.let { !File(it, "merge-pending").exists() } == true)
            directory?.deleteRecursively()
    }
}
