package com.infinifolder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.StorageRuntime
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Short-lived private-source to grantable-content URI bridge for selected VFS entries. */
class FileSelectionRelayProvider : ContentProvider() {
    private val preferences by lazy { requireNotNull(context).getSharedPreferences(PREFS, 0) }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = record(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val item = record(uri) ?: return null
        val columns = projection?.toList() ?: arrayListOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns.toTypedArray())
        cursor.addRow(columns.map { column -> when (column) {
            OpenableColumns.DISPLAY_NAME -> item.name
            OpenableColumns.SIZE -> item.size
            "mime_type" -> item.mime
            else -> null
        } })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Selected items are read-only")
        val item = record(uri) ?: throw FileNotFoundException("This selection link has expired")
        val context = requireNotNull(context)
        val (reader, writer) = try { ParcelFileDescriptor.createPipe() }
        catch (error: IOException) { throw FileNotFoundException(error.message ?: "Cannot open read pipe") }
        try {
            workers.execute {
                try {
                    val input = openSource(context, item.source)
                    input.use { source ->
                        ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } catch (error: Exception) {
                    runCatching { writer.closeWithError(error.message ?: "Source read failed") }
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
        private const val AUTHORITY_SUFFIX = ".selection"
        private val workers = ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { task -> Thread(task, "selected-file-stream").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )

        fun register(context: android.content.Context, source: Uri, name: String, mime: String, size: Long?): Uri {
            require(!name.contains('\u0000')) { "Selected filename is invalid" }
            val prefs = context.getSharedPreferences(PREFS, 0)
            val now = System.currentTimeMillis()
            synchronized(prefs) {
                val editor = prefs.edit()
                prefs.all.keys.mapNotNull { key -> key.substringBefore('.').takeIf { key.endsWith(".issued") } }
                    .distinct().forEach { token ->
                        if (now - prefs.getLong("$token.issued", now) > TOKEN_TTL_MS) remove(editor, token)
                    }
                val token = UUID.randomUUID().toString()
                editor.putString("$token.source", source.toString())
                    .putString("$token.name", name)
                    .putString("$token.mime", mime)
                    .putLong("$token.issued", now)
                if (size != null && size >= 0L) editor.putLong("$token.size", size) else editor.remove("$token.size")
                check(editor.commit()) { "Could not register the selected file" }
                val authority = context.packageName + AUTHORITY_SUFFIX
                return Uri.Builder().scheme("content").authority(authority).appendPath(token).build()
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
