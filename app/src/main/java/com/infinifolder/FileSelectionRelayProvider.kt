package com.infinifolder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.SharedFileProvider
import com.infinifolder.storage.StorageRuntime
import com.infinifolder.storage.openNativeSeekable
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Short-lived private-source to grantable-content URI bridge for selected VFS entries. */
class FileSelectionRelayProvider : ContentProvider() {
    data class RegistrationRequest(val source: Uri, val name: String, val mime: String, val size: Long?)

    private val preferences by lazy { requireNotNull(context).getSharedPreferences(PREFS, 0) }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = record(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val item = record(uri) ?: return null
        val columns = projection?.toList() ?: arrayListOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val directoryArtwork = if (columns.any { it == SharedFileProvider.AUDIO_COVER_URI_COLUMN ||
                it == SharedFileProvider.AUDIO_DIRECTORY_URI_COLUMN || it == SharedFileProvider.AUDIO_DIRECTORY_NAME_COLUMN }) {
            requireReadGrant(uri)
            findSiblingDirectoryArtwork(item.source)?.let { directory ->
                val cover = directory.cover
                val coverUri = cover?.let {
                    val relay = register(requireNotNull(context), cover.uri, cover.metadata.name,
                        cover.metadata.mimeType, cover.metadata.sizeBytes)
                    grantToCallingPackages(relay)
                    relay
                }
                DirectoryArtwork(directory.uri?.let { StorageDocumentsProvider.reference(requireNotNull(context), it) }, directory.name, coverUri)
            }
        } else null
        val cursor = MatrixCursor(columns.toTypedArray())
        cursor.addRow(columns.map { column -> when (column) {
            OpenableColumns.DISPLAY_NAME -> item.name
            OpenableColumns.SIZE -> item.size
            "mime_type" -> item.mime
            SharedFileProvider.AUDIO_COVER_URI_COLUMN -> directoryArtwork?.coverUri?.toString()
            SharedFileProvider.AUDIO_DIRECTORY_URI_COLUMN -> directoryArtwork?.uri?.toString()
            SharedFileProvider.AUDIO_DIRECTORY_NAME_COLUMN -> directoryArtwork?.name
            else -> null
        } })
        return cursor
    }

    private fun requireReadGrant(uri: Uri) {
        val context = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && context.checkUriPermission(uri, Binder.getCallingPid(), uid,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Read access to the selected audio file is required to query its cover image")
        }
    }

    private data class DirectoryArtwork(
        val uri: Uri?,
        val name: String?,
        val coverUri: Uri?
    )

    private data class DirectoryArtworkCandidate(
        val uri: Uri,
        val name: String,
        val cover: com.infinifolder.model.ResourceEntry?
    )

    private fun findSiblingDirectoryArtwork(source: Uri): DirectoryArtworkCandidate? = runCatching {
        val runtime = StorageRuntime.get(requireNotNull(context))
        val ref = runtime.provider.refForUri(source) ?: return null
        val parent = (runtime.provider.parent(ref) as? ProviderResult.Success)?.value ?: return null
        val parentEntry = (runtime.provider.stat(parent) as? ProviderResult.Success)?.value ?: return null
        val cover = com.infinifolder.storage.StorageSafety.complete(runtime.provider.list(parent)).asSequence()
            .filter { !it.metadata.isDirectory && !it.metadata.isSymbolicLink && !it.metadata.isBrokenLink && it.metadata.canRead }
            .filter { it.metadata.name.startsWith("cover.", ignoreCase = true) }
            .filter {
                it.metadata.mimeType.startsWith("image/", ignoreCase = true) ||
                    com.infinifolder.mime.MimeRegistry.guess(it.metadata.name).startsWith("image/", ignoreCase = true)
            }
            .sortedWith(Comparator { left, right ->
                val byCaseInsensitiveName = left.metadata.name.compareTo(right.metadata.name, ignoreCase = true)
                if (byCaseInsensitiveName != 0) byCaseInsensitiveName else left.metadata.name.compareTo(right.metadata.name)
            })
            .firstOrNull()
        DirectoryArtworkCandidate(parentEntry.uri, parentEntry.metadata.name, cover)
    }.getOrNull()

    private fun grantToCallingPackages(uri: Uri) {
        val context = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        context.packageManager.getPackagesForUid(uid).orEmpty().forEach { packageName ->
            context.grantUriPermission(packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Selected items are read-only")
        val item = record(uri) ?: throw FileNotFoundException("This selection link has expired")
        val context = requireNotNull(context)
        val provider = StorageRuntime.get(context).provider
        val ref = provider.refForUri(item.source)
        if (ref != null) {
            when (val result = provider.openNativeSeekable(ref, false)) {
                is ProviderResult.Success -> {
                    return StorageDescriptor.open(context, provider, ref, "r", result)
                }
                is ProviderResult.Failure -> throw FileNotFoundException(result.message).apply { initCause(result.cause) }
                is ProviderResult.Unsupported -> Unit
            }
        }
        val (reader, writer) = try { ParcelFileDescriptor.createReliablePipe() }
        catch (error: IOException) { throw FileNotFoundException(error.message ?: "Cannot open read pipe") }
        try {
            workers.execute {
                val output = ParcelFileDescriptor.AutoCloseOutputStream(writer)
                try {
                    copySourceWithReconnect(context, item, output)
                    output.close()
                } catch (error: Exception) {
                    runCatching { writer.closeWithError(error.message ?: "Source read failed") }
                    runCatching { output.close() }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            runCatching { reader.close() }
            runCatching { writer.close() }
            throw FileNotFoundException("Too many selected files are being streamed")
        }
        return reader
    }

    private fun openSource(context: android.content.Context, source: Uri): java.io.InputStream {
        val runtime = StorageRuntime.get(context)
        val ref = runtime.provider.refForUri(source)
        if (ref != null) return when (val opened = runtime.provider.openRead(ref)) {
            is ProviderResult.Success -> opened.value
            is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
            is ProviderResult.Unsupported -> throw IOException("This storage provider cannot stream the selected file")
        }
        return when (source.scheme) {
            "content" -> context.contentResolver.openInputStream(source)
                ?: throw IOException("The selected content provider returned no stream")
            "file" -> File(source.path ?: throw IOException("Selected file URI has no path")).inputStream()
            else -> throw IOException("No storage provider can read the selected source")
        }
    }

    /**
     * Network sources can lose an established read channel while a viewer is still
     * consuming its content URI. Reopen the saved target URI and continue at the
     * delivered byte offset so clients do not need to reload their playlist.
     */
    private fun copySourceWithReconnect(context: android.content.Context, item: Record,
        output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var retries = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Selected-file relay cancelled")
            val input = try {
                if (retries == 0) openSourceAt(context, item.source, copied)
                else reopenSource(context, item, copied)
            } catch (error: Exception) {
                if (!retrySource(context, item, copied, retries, error)) throw error
                retries++
                pauseBeforeRetry(retries)
                continue
            }

            var sourceFailure: Exception? = null
            var reachedEnd = false
            var outputFailure: Exception? = null
            try {
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Selected-file relay cancelled")
                    val count = try {
                        input.read(buffer)
                    } catch (error: Exception) {
                        sourceFailure = error
                        break
                    }
                    if (count < 0) {
                        reachedEnd = true
                        break
                    }
                    if (count == 0) continue
                    try {
                        output.write(buffer, 0, count)
                    } catch (error: Exception) {
                        // A closed consumer pipe must not trigger a network reconnect loop.
                        outputFailure = error
                        break
                    }
                    copied += count
                }
            } finally {
                try {
                    input.close()
                } catch (closeError: Exception) {
                    when {
                        outputFailure != null -> outputFailure.addSuppressed(closeError)
                        sourceFailure != null -> sourceFailure.addSuppressed(closeError)
                        reachedEnd -> sourceFailure = closeError
                    }
                }
            }

            outputFailure?.let { throw it }
            if (sourceFailure == null && reachedEnd) {
                val expectedSize = item.size
                if (expectedSize == null || copied == expectedSize) return
                sourceFailure = if (copied < expectedSize) {
                    EOFException("Selected source ended at $copied bytes; expected $expectedSize")
                } else {
                    IOException("Selected source changed size while it was being streamed")
                }
            }
            val error = sourceFailure ?: IOException("Selected source stopped before end of file")
            if (!retrySource(context, item, copied, retries, error)) throw error
            retries++
            pauseBeforeRetry(retries)
        }
    }

    private fun reopenSource(context: android.content.Context, item: Record, offset: Long): InputStream {
        val runtime = StorageRuntime.get(context)
        val target = runtime.provider.refForUri(item.source)?.let {
            com.infinifolder.storage.NetworkConnectionStatus.target(it)
        }
        return if (target == null) openSourceAt(context, item.source, offset)
        else com.infinifolder.storage.NetworkConnectionStatus.reconnect(target.key) {
            openSourceAt(context, item.source, offset)
        }
    }

    /** Prefer a fresh positional handle for resume; sequential providers replay to the offset. */
    private fun openSourceAt(context: android.content.Context, source: Uri, offset: Long): InputStream {
        if (offset > 0L) {
            val runtime = StorageRuntime.get(context)
            val ref = runtime.provider.refForUri(source)
            if (ref != null && !com.infinifolder.archive.ArchiveUri.isArchiveUri(source)) {
                when (val result = com.infinifolder.storage.validateSeekableResult(
                    runtime.provider.openSeekable(ref, false), false)) {
                    is ProviderResult.Success -> {
                        val device = result.value
                        return object : InputStream() {
                            private var position = offset
                            private var closed = false

                            override fun read(): Int {
                                val one = ByteArray(1)
                                val count = read(one, 0, 1)
                                return if (count < 0) -1 else one[0].toInt() and 0xff
                            }

                            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                                if (closed) throw IOException("Selected source is closed")
                                if (len == 0) return 0
                                val count = device.readAt(position, bytes, off, len)
                                if (count <= 0) return -1
                                position += count
                                return count
                            }

                            override fun close() {
                                if (!closed) {
                                    closed = true
                                    device.close()
                                }
                            }
                        }
                    }
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> Unit
                }
            }
        }

        val input = openSource(context, source)
        if (offset == 0L) return input
        try {
            skipExactly(input, offset)
            return input
        } catch (error: Exception) {
            runCatching { input.close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(32 * 1024)
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Selected-file relay cancelled")
            val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Selected source ended before the reconnect offset")
            remaining -= read
        }
    }

    private fun retrySource(context: android.content.Context, item: Record, offset: Long,
        retries: Int, error: Exception): Boolean {
        if (retries >= MAX_SOURCE_RETRIES || !com.infinifolder.storage.NetworkConnectionStatus
                .isTransportFailure(error, error.message.orEmpty())) return false
        val providerId = runCatching { StorageRuntime.get(context).provider.refForUri(item.source)?.providerId }
            .getOrNull() ?: item.source.scheme.orEmpty()
        com.infinifolder.storage.DiagnosticLog.record("infiniselect.source.reconnect",
            "provider=$providerId name=${item.name} attempt=${retries + 1}/$MAX_SOURCE_RETRIES resumedBytes=$offset", error)
        return true
    }

    private fun pauseBeforeRetry(retries: Int) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (1L shl (retries - 1).coerceIn(0, 2)))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Selected-file reconnect cancelled").apply { initCause(error) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private data class Record(val source: Uri, val name: String, val mime: String, val size: Long?)

    private fun record(uri: Uri): Record? {
        if (uri.authority != authority || uri.pathSegments.size != 1) return null
        val token = uri.lastPathSegment ?: return null
        val now = System.currentTimeMillis()
        val prefs = preferences
        val issued = prefs.getLong("$token.issued", 0L)
        if (issued == 0L || now - issued > TOKEN_TTL_MS) {
            if (issued != 0L) remove(prefs, token)
            return null
        }
        val source = prefs.getString("$token.source", null)?.let(Uri::parse) ?: return null
        val name = prefs.getString("$token.name", null) ?: return null
        val mime = prefs.getString("$token.mime", null) ?: "application/octet-stream"
        val size = prefs.getLong("$token.size", -1L).takeIf { it >= 0L }
        return Record(source, name, mime, size)
    }

    companion object {
        private const val PREFS = "selected-file-relay"
        private const val TOKEN_TTL_MS = 24L * 60 * 60 * 1000
        private const val LAST_CLEANUP_KEY = "relay.last_cleanup"
        private const val CLEANUP_INTERVAL_MS = 5L * 60 * 1000
        private const val MAX_SOURCE_RETRIES = 3
        private const val RETRY_DELAY_MS = 400L
        private const val AUTHORITY_SUFFIX = ".selection"
        private val workers = ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { task -> Thread(task, "selected-file-stream").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )

        fun register(context: android.content.Context, source: Uri, name: String, mime: String, size: Long?): Uri {
            return registerAll(context, listOf(RegistrationRequest(source, name, mime, size))).single()
        }

        /** Register a batch with one preference commit; recursive selections can contain many files. */
        fun registerAll(context: android.content.Context, requests: List<RegistrationRequest>): List<Uri> {
            if (requests.isEmpty()) return emptyList()
            require(requests.all { !it.name.contains('\u0000') }) { "Selected filename is invalid" }
            val prefs = context.getSharedPreferences(PREFS, 0)
            val now = System.currentTimeMillis()
            synchronized(prefs) {
                val editor = prefs.edit()
                if (now - prefs.getLong(LAST_CLEANUP_KEY, 0L) >= CLEANUP_INTERVAL_MS) {
                    prefs.all.keys.asSequence()
                        .filter { it.endsWith(".issued") }
                        .map { it.removeSuffix(".issued") }
                        .forEach { token ->
                            if (now - prefs.getLong("$token.issued", now) > TOKEN_TTL_MS) remove(editor, token)
                        }
                    editor.putLong(LAST_CLEANUP_KEY, now)
                }
                val uris = requests.map { request ->
                    val token = UUID.randomUUID().toString()
                    editor.putString("$token.source", request.source.toString())
                        .putString("$token.name", request.name)
                        .putString("$token.mime", request.mime)
                        .putLong("$token.issued", now)
                    if (request.size != null && request.size >= 0L) editor.putLong("$token.size", request.size)
                    else editor.remove("$token.size")
                    Uri.Builder().scheme("content").authority(context.packageName + AUTHORITY_SUFFIX)
                        .appendPath(token).build()
                }
                check(editor.commit()) { "Could not register the selected file" }
                return uris
            }
        }

        private fun remove(prefs: android.content.SharedPreferences, token: String) {
            val editor = prefs.edit()
            remove(editor, token)
            editor.apply()
        }

        private fun remove(editor: android.content.SharedPreferences.Editor, token: String) {
            editor.remove("$token.source").remove("$token.name").remove("$token.mime")
                .remove("$token.size").remove("$token.issued")
        }
    }

    private val authority: String get() = requireNotNull(context).packageName + AUTHORITY_SUFFIX
}
