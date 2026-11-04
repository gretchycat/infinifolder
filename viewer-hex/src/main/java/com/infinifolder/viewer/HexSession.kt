package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.ContentReplacement
import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.StorageRuntime
import io.github.gretchycat.infiniscribe.HexDocument
import io.github.gretchycat.infinistream.PositionalReadable
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All document/handle access happens on this retained session's single worker. */
internal class HexSession(val uri: Uri, val name: String) {
    val worker = Executors.newSingleThreadExecutor { Thread(it, "hex-document") }
    val cancelled = AtomicBoolean()
    @Volatile var busy = false
    @Volatile var directory: File? = null
    var document: HexDocument? = null
    var writable = false
    var revision: ContentRevision? = null
    private var owner: Closeable? = null
    private var staged: MediaSourceAdapter.StagedSource? = null

    private class FileReader(file: File) : PositionalReadable, Closeable {
        private val input = RandomAccessFile(file, "r")
        val length get() = input.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            input.seek(position); return input.read(buffer, offset, length)
        }
        override fun close() = input.close()
    }
    fun open(context: Context, restoredId: String?) {
        try { openDocument(context, restoredId) } catch (error: Exception) {
            document = null
            runCatching { owner?.close() }; owner = null
            runCatching { staged?.close() }; staged = null
            throw error
        }
    }
    private fun openDocument(context: Context, restoredId: String?) {
        val root = File(context.filesDir, "hex_edits")
        if (restoredId != null) {
            require(Regex("[a-f0-9-]{36}").matches(restoredId)) { "Invalid hex edit draft" }
            val folder = File(root, restoredId)
            if (File(folder, "session.properties").isFile) {
                val props = Properties().apply { File(folder, "session.properties").inputStream().use(::load) }
                require(props.getProperty("uri") == uri.toString()) { "Edit draft belongs to another file" }
                val input = FileReader(File(folder, "original.bin"))
                owner = input; document = HexDocument(input, input.length)
                revision = ContentRevision(input.length, props.getProperty("sha256") ?: error("Missing draft revision"))
                directory = folder
                val journal = File(folder, "changes.bin")
                if (journal.isFile) DataInputStream(journal.inputStream().buffered()).use { stream ->
                    val count = stream.readInt()
                    require(count in 0..1024 * 1024) { "Invalid edit journal" }
                    document!!.restoreEntries(sequence {
                        repeat(count) { yield(stream.readLong() to stream.readByte()) }
                    })
                    require(stream.read() == -1) { "Unexpected data in edit journal" }
                }
            } else if (folder.exists()) throw IOException("The saved edit draft is incomplete; its files have been retained")
        }
        writable = MediaSourceAdapter.isWritable(context, uri)
        if (document != null) return
        val positional = MediaSourceAdapter.openStreamingPositionalSource(context, uri)
        if (cancelled.get()) { positional?.close(); throw java.io.InterruptedIOException("Viewer load cancelled") }
        if (positional is PositionalReadable && positional.metadata.length != null) {
            owner = positional; document = HexDocument(positional, positional.metadata.length!!)
        } else {
            positional?.close()
            staged = MediaSourceAdapter.stageCancellable(context, uri, name, cancelled::get)
            val input = FileReader(staged!!.file)
            owner = input; document = HexDocument(input, input.length)
        }
    }
    /** Freeze original bytes on disk before editing; file size never determines RAM consumption. */
    fun snapshot(context: Context, progress: (Long) -> Unit) {
        if (directory != null) return
        val doc = checkNotNull(document)
        val folder = File(File(context.filesDir, "hex_edits"), UUID.randomUUID().toString())
        check(folder.mkdirs()) { "Cannot create edit snapshot" }
        val partial = File(folder, "original.partial")
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            partial.outputStream().use { output ->
                doc.writeTo(java.security.DigestOutputStream(output, digest), cancelled = cancelled::get, progress = { at ->
                    if (folder.usableSpace < 1024 * 1024) throw IOException("Not enough storage for the edit snapshot")
                    progress(at)
                })
                output.fd.sync()
            }
            val fingerprint = ContentRevision(doc.length, digest.digest().joinToString("") { "%02x".format(it) })
            check(partial.renameTo(File(folder, "original.bin"))) { "Cannot retain edit snapshot" }
            val props = Properties().apply { setProperty("uri", uri.toString()); setProperty("sha256", fingerprint.sha256) }
            File(folder, "session.properties").outputStream().use { props.store(it, "Hex edit original"); it.fd.sync() }
            val input = FileReader(File(folder, "original.bin"))
            owner?.close(); staged?.close(); staged = null
            owner = input; document = HexDocument(input, input.length); revision = fingerprint
            directory = folder
        } catch (error: Exception) { folder.deleteRecursively(); throw error }
    }
    fun persist() {
        val folder = directory ?: return
        val temporary = File(folder, "changes.partial")
        val changes = checkNotNull(document).changes()
        temporary.outputStream().use { raw ->
            val output = DataOutputStream(raw)
            output.writeInt(changes.size)
            changes.forEach { (offset, byte) -> output.writeLong(offset); output.writeByte(byte.toInt()) }
            output.flush(); raw.fd.sync()
        }
        check(temporary.renameTo(File(folder, "changes.bin"))) { "Cannot retain edited bytes" }
    }
    fun save(context: Context, destination: Uri = uri): Boolean {
        val folder = checkNotNull(directory)
        val file = File(folder, "replacement.bin")
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.outputStream().use { output ->
                checkNotNull(document).writeTo(java.security.DigestOutputStream(output, digest), cancelled = cancelled::get)
                output.fd.sync()
            }
            val fingerprint = ContentRevision(file.length(), digest.digest().joinToString("") { "%02x".format(it) })
            val provider = StorageRuntime.get(context).provider
            val ref = provider.refForUri(destination) ?: throw IOException("No storage provider owns this destination")
            val sameOriginal = destination == uri || ref == provider.refForUri(uri)
            when (val result = ContentReplacement.replaceFromFile(provider, ref, file, File(context.filesDir, "save_recovery"),
                if (sameOriginal) revision else null)) {
                is ProviderResult.Success -> {
                    if (sameOriginal) {
                        // Use the committed bytes as the new baseline. A provider's disposable
                        // read cache can retain the old metadata key after an in-place write.
                        val fresh = File(folder.parentFile, UUID.randomUUID().toString())
                        try {
                            check(fresh.mkdirs()) { "Cannot retain the new baseline" }
                            val baseline = File(fresh, "original.bin")
                            check(file.renameTo(baseline)) { "Cannot retain the saved bytes" }
                            val props = Properties().apply { setProperty("uri", uri.toString()); setProperty("sha256", fingerprint.sha256) }
                            File(fresh, "session.properties").outputStream().use { props.store(it, "Hex edit original"); it.fd.sync() }
                            val input = FileReader(baseline)
                            owner?.close(); owner = input
                            document = HexDocument(input, input.length); revision = fingerprint; directory = fresh
                            folder.deleteRecursively()
                        } catch (error: Exception) {
                            throw IOException("The file was saved, but the editor could not refresh its draft: ${error.message}. Reopen the file before saving again.", error)
                        }
                    }
                    return sameOriginal
                }
                is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                is ProviderResult.Unsupported -> throw IOException("Saving is unsupported for this destination")
            }
        } finally { file.delete() }
    }
    fun close(discard: Boolean) {
        try { owner?.close() } finally { staged?.close() }
        if (discard || document?.changedCount == 0) directory?.deleteRecursively()
    }
}
