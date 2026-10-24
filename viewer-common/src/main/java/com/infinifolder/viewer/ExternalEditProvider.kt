package com.infinifolder.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.infinifolder.storage.ExternalEdits

/** Grantable URI for exactly one durable external working copy, never for the original source. */
class ExternalEditProvider : ContentProvider() {
    override fun onCreate() = true
    private fun session(uri: Uri) = ExternalEdits(requireNotNull(context)).let { store ->
        store to (uri.pathSegments.firstOrNull()?.let(store::find) ?: throw java.io.FileNotFoundException("External edit session not found"))
    }
    override fun getType(uri: Uri): String = session(uri).second.mime.ifBlank { "application/octet-stream" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val item = session(uri).second
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map {
            when (it) { OpenableColumns.DISPLAY_NAME -> item.name; OpenableColumns.SIZE -> item.working.length(); else -> null }
        }.toTypedArray()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (store, item) = session(uri)
        return store.openFile(item, mode)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
