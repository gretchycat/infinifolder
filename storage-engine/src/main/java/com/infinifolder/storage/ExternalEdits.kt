package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.ops.FileOperations
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ExternalSourceConflict : IOException("The file changed at the source while we were working on it. Your edited copy has been kept.")

/** One durable working copy plus a source fingerprint. No version history; dirty sessions never expire. */
class ExternalEdits(private val context: Context) {
    private val root = File(context.applicationContext.filesDir, "external_edits").apply { mkdirs() }
    private val runtime get() = StorageRuntime.get(context)
    private val provider get() = runtime.provider
    data class Stamp(val revision: ContentRevision, val modified: Long?)
    data class Session(val directory: File, val ref: ResourceRef, val name: String, val mime: String, val baseline: Stamp) {
        val id get() = directory.name
        val working get() = File(directory, "working.bin")
    }
    private fun lock(id: String) = locks.computeIfAbsent(id) { Any() }
    private fun properties(session: Session) = Properties().apply {
        File(session.directory, "session.properties").inputStream().use(::load)
    }
    private fun writeProperties(session: Session, props: Properties) {
        val temp = File(session.directory, "session.properties.tmp")
        temp.outputStream().use { props.store(it, "External edit session"); it.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), File(session.directory, "session.properties").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun phase(session: Session, value: String) = synchronized(lock(session.id)) {
        val props = properties(session).apply { setProperty("phase", value) }
        writeProperties(session, props)
    }
    fun find(id: String): Session? {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        return runCatching {
            val directory = File(root, id)
            val props = Properties().apply { File(directory, "session.properties").inputStream().use(::load) }
            Session(directory, ResourceRef(props.getProperty("provider"), props.getProperty("source")),
                props.getProperty("name"), props.getProperty("mime"), Stamp(
                    ContentRevision(props.getProperty("size").toLong(), props.getProperty("sha256")),
                    props.getProperty("modified")?.toLongOrNull()))
        }.getOrNull()?.takeIf { it.working.isFile }
    }
    fun pending(): List<Session> = root.listFiles().orEmpty().mapNotNull { find(it.name) }.sortedByDescending { it.directory.lastModified() }
    private fun <T> result(value: ProviderResult<T>): T = when (value) {
        is ProviderResult.Success -> value.value
        is ProviderResult.Failure -> throw IOException(value.message, value.cause)
        else -> throw IOException("This storage provider does not support this operation")
    }
    private fun open(ref: ResourceRef): InputStream = result(provider.openRead(ref))
    private fun fingerprint(input: InputStream, message: String, total: Long?, progress: (String, Long, Long?) -> Unit): ContentRevision {
        var bytes = 0L
        var reported = 0L
        return object : java.io.FilterInputStream(input) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) bytes += count
                val now = android.os.SystemClock.uptimeMillis()
                if (now - reported >= 250) { reported = now; progress(message, bytes, total) }
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Check interrupted")
                return count
            }
        }.use(ContentRevision::read)
    }
    private fun stamp(ref: ResourceRef, message: String, progress: (String, Long, Long?) -> Unit): Stamp = runtime.archives.withFreshReads {
        val before = result(provider.stat(ref)).metadata
        if (before.isDirectory) throw IOException("External editing requires a file")
        progress(message, 0, before.sizeBytes)
        val revision = fingerprint(open(ref), message, before.sizeBytes, progress)
        // Metadata is a progress hint, never evidence of changed file content.
        Stamp(revision, before.modifiedAtMillis)
    }
    fun prepare(uri: Uri, progress: (String, Long, Long?) -> Unit): Session {
        val ref = provider.refForUri(uri) ?: throw IOException("No provider owns this file")
        val metadata = result(provider.stat(ref)).metadata
        if (metadata.isDirectory) throw IOException("Choose a file to open externally")
        val lease = DiskCache.get(context).allocate(metadata.name, metadata.sizeBytes)
        try {
            val before = stamp(ref, "Checking source before opening…", progress)
            lease.reserve(before.revision.size)
            progress("Preparing external working copy…", 0, before.revision.size)
            runtime.archives.withFreshReads { open(ref).use { input -> lease.output().use { output ->
                val buffer = ByteArray(128 * 1024)
                var bytes = 0L
                var reported = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Opening interrupted")
                    output.write(buffer, 0, count); bytes += count
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - reported >= 250) { reported = now; progress("Preparing external working copy…", bytes, before.revision.size) }
                }
            } } }
            val grabbed = lease.file.inputStream().use(ContentRevision::read)
            val after = stamp(ref, "Checking source after opening…", progress)
            if (before.revision != after.revision || grabbed != before.revision) throw ExternalSourceConflict()
            val directory = File(root, UUID.randomUUID().toString()).apply { if (!mkdir()) throw IOException("Cannot create working copy") }
            val magic = FileMagicDetector.detect(lease.file, peekCompressedPayload = true)
            val mime = com.infinifolder.mime.MimeRegistry.effective(metadata.name, metadata.mimeType, magic?.mimeType)
            val session = Session(directory, ref, metadata.name, mime, after)
            val props = Properties().apply {
                setProperty("provider", ref.providerId); setProperty("source", ref.documentId)
                setProperty("name", metadata.name); setProperty("mime", mime)
                setProperty("size", after.revision.size.toString()); setProperty("sha256", after.revision.sha256)
                after.modified?.let { setProperty("modified", it.toString()) }
                setProperty("phase", "editing")
            }
            writeProperties(session, props)
            java.nio.file.Files.move(lease.file.toPath(), session.working.toPath())
            session.working.setWritable(true, true)
            java.io.RandomAccessFile(session.working, "rw").use { it.fd.sync() }
            DiagnosticLog.record("external-edit.prepared", "session=${session.id} provider=${ref.providerId} bytes=${grabbed.size}")
            return session
        } catch (error: ExternalSourceConflict) {
            throw IOException("The source changed while preparing its working copy. Nothing was opened or written. Retry opening the file.", error)
        } finally { lease.close() }
    }
    fun uri(session: Session): Uri = Uri.Builder().scheme("content").authority(context.packageName + ".external-edit")
        .appendPath(session.id).appendPath(session.name).build()

    /** Provider writer accounting and the publish gate use the same lock. */
    fun openFile(session: Session, mode: String): android.os.ParcelFileDescriptor = synchronized(lock(session.id)) {
        val flags = android.os.ParcelFileDescriptor.parseMode(mode)
        val writes = flags and android.os.ParcelFileDescriptor.MODE_READ_WRITE != android.os.ParcelFileDescriptor.MODE_READ_ONLY
        if (properties(session).getProperty("phase") != "editing") throw IOException("Finish or reopen this edit session before opening its working file")
        if (writes) writers[session.id] = (writers[session.id] ?: 0) + 1
        try {
            android.os.ParcelFileDescriptor.open(session.working, flags, android.os.Handler(android.os.Looper.getMainLooper())) {
                if (writes) synchronized(lock(session.id)) { writers[session.id] = ((writers[session.id] ?: 1) - 1).coerceAtLeast(0) }
            }
        } catch (error: Throwable) {
            if (writes) writers[session.id] = ((writers[session.id] ?: 1) - 1).coerceAtLeast(0)
            throw error
        }
    }
    fun reopen(session: Session) = synchronized(lock(session.id)) {
        if (session.id in operations) throw IOException("This edit session is busy")
        phase(session, "editing")
    }

    /** Keep a working copy while a tool edits a separate temporary file. */
    fun reserveExternalTool(session: Session, workingPath: String? = null) = synchronized(lock(session.id)) {
        val props = properties(session).apply {
            setProperty("external_tool_pending", "true")
            workingPath?.let { setProperty("external_tool_path", it) }
        }
        writeProperties(session, props)
    }
    fun externalToolFile(session: Session): File? = synchronized(lock(session.id)) {
        properties(session).getProperty("external_tool_path")?.let(::File)
    }
    fun hasExternalTool(session: Session): Boolean = synchronized(lock(session.id)) {
        properties(session).getProperty("external_tool_pending") == "true"
    }
    fun releaseExternalTool(session: Session) = synchronized(lock(session.id)) {
        val props = properties(session).apply { remove("external_tool_pending"); remove("external_tool_path") }
        writeProperties(session, props)
    }
    /** Import a finished tool's shared file without exposing a partial private working copy. */
    fun importExternalToolFile(session: Session, external: File) = synchronized(lock(session.id)) {
        if (!hasExternalTool(session)) throw IOException("This Termux edit session is no longer active")
        if ((writers[session.id] ?: 0) > 0) throw IOException("The working copy is still open for writing")
        if (!external.isFile || java.nio.file.Files.isSymbolicLink(external.toPath()))
            throw IOException("The Termux working file is unavailable")
        val before = external.inputStream().use(ContentRevision::read)
        if (before.size > session.directory.usableSpace - 1024 * 1024)
            throw IOException("Not enough space to preserve the Termux edits. The working file is kept.")
        val staged = File(session.directory, "termux-import.tmp")
        try {
            external.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            if (staged.inputStream().use(ContentRevision::read) != before ||
                external.inputStream().use(ContentRevision::read) != before)
                throw IOException("The Termux working file changed while it was being imported. It has been kept.")
            java.nio.file.Files.move(staged.toPath(), session.working.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            releaseExternalTool(session)
        } finally { staged.delete() }
    }
    private fun requireExternalToolFinished(session: Session) {
        if (hasExternalTool(session)) throw IOException("The Termux editor may still be running. Finish it before reviewing this working copy.")
    }

    /** Returning from a read-only viewer needs no edit UI or source/network check. */
    fun reviewChanges(session: Session, progress: (String, Long, Long?) -> Unit): Boolean = synchronized(lock(session.id)) {
        requireExternalToolFinished(session)
        if (session.id in operations) throw IOException("This edit session is busy")
        val hasWriters = (writers[session.id] ?: 0) > 0
        if (!hasWriters) phase(session, "checking") // Block new writable handles during the check.
        try {
            val revision = fingerprint(session.working.inputStream(), "Checking for local edits…", session.working.length(), progress)
            val changed = revision != session.baseline.revision
            if (!changed && !hasWriters) discard(session)
            else if (!hasWriters) phase(session, "pending")
            DiagnosticLog.record("external-edit.review", "session=${session.id} changed=$changed writers=$hasWriters")
            // A program still holding a writable handle may save later. Keep its
            // session, but do not show save/conflict controls for unchanged bytes.
            changed
        } catch (error: Throwable) {
            if (!hasWriters) runCatching { phase(session, "pending") }
            throw error
        }
    }

    private fun localRevision(session: Session, progress: (String, Long, Long?) -> Unit): ContentRevision {
        synchronized(lock(session.id)) {
            if ((writers[session.id] ?: 0) > 0) throw IOException("The external program still has the file open for writing. Close it before finishing.")
            phase(session, "checking")
        }
        return fingerprint(session.working.inputStream(), "Checking for local edits…", session.working.length(), progress)
    }
    private fun freeze(session: Session, revision: ContentRevision? = null): Pair<File, ContentRevision> {
        val before = revision ?: localRevision(session) { _, _, _ -> }
        val snapshot = File(session.directory, "publish.bin")
        if (before.size > session.directory.usableSpace - 1024 * 1024) throw IOException("Not enough space to preserve the edited file for saving. Your working copy is kept.")
        session.working.inputStream().use { input -> snapshot.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
        if (snapshot.inputStream().use(ContentRevision::read) != before || session.working.inputStream().use(ContentRevision::read) != before)
            throw IOException("The external program changed the working copy while it was being checked. Close it and retry.")
        return snapshot to before
    }
    /** Unchanged local content never overwrites the source, even if the source changed. */
    fun finish(session: Session, overwrite: Boolean, progress: (String, Long, Long?) -> Unit): Boolean {
        requireExternalToolFinished(session)
        synchronized(lock(session.id)) { if (!operations.add(session.id)) throw IOException("This edit session is busy") }
        try {
            val edited = localRevision(session, progress)
            if (edited == session.baseline.revision) return false
            val (snapshot, _) = freeze(session, edited)
            val current = stamp(session.ref, "Checking source after external editing…", progress)
            if (!overwrite && current.revision != session.baseline.revision) throw ExternalSourceConflict()
            progress("Saving edited file to source…", 0, null)
            val replacement = runtime.archives.withFreshReads {
                ContentReplacement.replaceFromFile(provider, session.ref, snapshot,
                    File(context.filesDir, "save_recovery"), current.revision) {
                    // ContentReplacement compares fresh byte hashes immediately
                    // before native/stream publication. Timestamp changes alone
                    // do not turn that comparison into a conflict.
                    runtime.archives.invalidateReadCaches()
                }
            }
            if (replacement is ProviderResult.Failure && generateSequence(replacement.cause) { it.cause }.any { it is RevisionConflictException })
                throw ExternalSourceConflict()
            result(replacement)
            val saved = stamp(session.ref, "Verifying saved file…", progress)
            if (saved.revision != edited) throw IOException("The saved source no longer matches the edited copy. Your local edits are kept.")
            if (session.working.inputStream().use(ContentRevision::read) != edited)
                throw IOException("The external program made further edits during saving. The saved source contains the earlier copy; the latest working copy has been kept. Close the program and finish again.")
            // No history: callers remove the session after successful publication and URI revocation.
            phase(session, "saved")
            DiagnosticLog.record("external-edit.saved", "session=${session.id} bytes=${edited.size} overwrite=$overwrite")
            return true
        } catch (error: Throwable) {
            runCatching { phase(session, "pending") }
            DiagnosticLog.record("external-edit.pending", "session=${session.id}", error)
            throw error
        } finally { File(session.directory, "publish.bin").delete(); operations.remove(session.id) }
    }
    fun copy(session: Session, destination: Uri, name: String, progress: (String, Long, Long?) -> Unit): Unit {
        requireExternalToolFinished(session)
        synchronized(lock(session.id)) { if (!operations.add(session.id)) throw IOException("This edit session is busy") }
        var snapshot: File? = null
        try {
            StorageSafety.validateName(name)
            val frozen = freeze(session)
            snapshot = frozen.first
            val edited = frozen.second
            val sourceUri = Uri.fromFile(snapshot)
            val ref = provider.refForUri(sourceUri) ?: throw IOException("Cannot read saved edits")
            val source = ResourceEntry(ref, ResourceMetadata(name, session.mime, edited.size, snapshot.lastModified(), false), sourceUri)
            val warnings = mutableListOf<String>()
            val copied = FileOperations.batchTransfer(listOf(source), destination, false, provider,
                { provider.refForUri(it) ?: throw IOException("No provider owns destination") },
                { progress(it.message ?: "Saving edited copy…", it.currentItemBytes ?: it.bytes, it.currentItemTotalBytes ?: it.totalBytes) },
                { Thread.currentThread().isInterrupted }, onWarning = { warnings.add(it) }, verifyCopies = true)
            if (copied.size != 1 || warnings.isNotEmpty()) throw IOException("The copy requires review: ${warnings.joinToString("; ")}. Your edits are kept.")
            if (session.working.inputStream().use(ContentRevision::read) != edited)
                throw IOException("The external program made further edits during copying. The latest working copy has been kept.")
            phase(session, "saved")
        } finally { snapshot?.delete(); operations.remove(session.id) }
    }
    fun parent(session: Session): Uri? = result(provider.parent(session.ref))?.documentId?.let(Uri::parse)
    /** Discard a confirmed abandoned session, including its app-owned Termux copy. */
    fun discardUnfinished(session: Session) = synchronized(lock(session.id)) {
        if (session.id in operations) throw IOException("This edit session is busy")
        if ((writers[session.id] ?: 0) > 0) throw IOException("The external program still has this file open for writing")
        val toolFile = externalToolFile(session)
        if (toolFile != null && toolFile.exists()) {
            val owned = context.externalMediaDirs.any { media ->
                val root = File(media, ".termux-edits")
                val folder = File(root, session.id)
                !java.nio.file.Files.isSymbolicLink(root.toPath()) &&
                    !java.nio.file.Files.isSymbolicLink(folder.toPath()) &&
                    folder.canonicalFile.parentFile == root.canonicalFile &&
                    toolFile.canonicalFile.parentFile == folder.canonicalFile
            }
            if (!owned) throw IOException("The Termux working copy is outside the app's edit folder")
            if (!toolFile.delete()) throw IOException("Could not remove the Termux working copy")
            toolFile.parentFile?.delete()
        }
        if (hasExternalTool(session)) releaseExternalTool(session)
        discard(session)
    }
    fun discard(session: Session) = synchronized(lock(session.id)) {
        requireExternalToolFinished(session)
        if (session.id in operations) throw IOException("This edit session is busy")
        if ((writers[session.id] ?: 0) > 0) throw IOException("The external program still has this file open for writing")
        phase(session, "closed")
        context.revokeUriPermission(uri(session), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!session.directory.deleteRecursively()) throw IOException("Could not remove the working copy")
        writers.remove(session.id)
    }
    companion object {
        private val operations = ConcurrentHashMap.newKeySet<String>()
        private val locks = ConcurrentHashMap<String, Any>()
        private val writers = ConcurrentHashMap<String, Int>()
    }
}
