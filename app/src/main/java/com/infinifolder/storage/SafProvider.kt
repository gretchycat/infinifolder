package com.infinifolder.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.infinifolder.model.*
import java.io.InputStream
import java.io.OutputStream
import android.content.ContentValues
import java.io.FileNotFoundException
import java.io.IOException

class SafProvider(private val resolver: ContentResolver) : StorageProvider {
    override val id = "saf"

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val parentUri = Uri.parse(parent.documentId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri, documentId(parentUri)
        )
        val rows = mutableListOf<ResourceEntry>()
        val cursor = resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ), null, null, null)
        if (cursor == null) directoryQueryResult<ResourceEntry>(null) else cursor.use {
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: "Unnamed"
                val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId)
                rows += ResourceEntry(
                    ResourceRef(id, uri.toString()),
                    ResourceMetadata(name, mime, if (sizeCol < 0 || cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol),
                        if (modifiedCol < 0 || cursor.isNull(modifiedCol)) null else cursor.getLong(modifiedCol), folder), uri
                )
            }
            directoryQueryResult(rows, cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false),
                cursor.extras.getString(DocumentsContract.EXTRA_ERROR), cursor.extras.getString(DocumentsContract.EXTRA_INFO))
        }
    } catch (e: SecurityException) { ProviderResult.Failure("Access to this folder was revoked", e) }
      catch (e: Exception) { ProviderResult.Failure(e.message ?: "Could not read folder", e) }

    override fun openRead(ref: ResourceRef) = try { ProviderResult.Success(resolver.openInputStream(Uri.parse(ref.documentId))!!) }
        catch (e: Exception) { ProviderResult.Failure(e.message ?: "Could not open file", e) }
    override fun openWrite(ref: ResourceRef) = try { ProviderResult.Success(resolver.openOutputStream(Uri.parse(ref.documentId), "wt")!!) }
        catch (e: Exception) { ProviderResult.Failure(e.message ?: "Could not write file", e) }
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = create(parent, name, mimeType)
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(ref.documentId), name)
            ?: return ProviderResult.Failure("Provider did not rename the item")
        ProviderResult.Success(entry(renamed))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Rename failed", e) }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        if (DocumentsContract.deleteDocument(resolver, Uri.parse(ref.documentId))) ProviderResult.Success(Unit)
        else ProviderResult.Failure("Provider refused to delete the item")
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Delete failed", e) }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> {
        var created: Uri? = null
        return try {
            val src = Uri.parse(source.documentId)
            val folder = Uri.parse(destination.documentId)
            val metadata = entry(src)
            val newName = availableName(folder, metadata.metadata.name)
            created = DocumentsContract.createDocument(resolver, folder, metadata.metadata.mimeType, newName)
                ?: return ProviderResult.Failure("Provider could not create the destination")
            copyInto(src, created, metadata.metadata.isDirectory)
            ProviderResult.Success(ResourceRef(id, created.toString()))
        } catch (e: Exception) {
            val cleanup = try { created?.let { DocumentsContract.deleteDocument(resolver, it) } ?: true } catch (_: Exception) { false }
            val detail = if (cleanup) e.message ?: "Copy failed" else "Copy failed and the partial destination could not be removed"
            ProviderResult.Failure(detail, e)
        }
    }
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> {
        val copied = copy(source, destination)
        if (copied !is ProviderResult.Success) return copied
        return when (val deleted = delete(source)) {
            is ProviderResult.Success -> copied
            is ProviderResult.Failure -> ProviderResult.Failure("Copied successfully, but the original could not be deleted: ${deleted.message}", deleted.cause)
            is ProviderResult.Unsupported -> deleted
        }
    }
    private fun create(parent: ResourceRef, name: String, mime: String): ProviderResult<ResourceEntry> = try {
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.documentId), mime, name)
            ?: return ProviderResult.Failure("Provider could not create this item")
        ProviderResult.Success(entry(uri))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Create failed", e) }
    private fun entry(uri: Uri): ResourceEntry {
        val idValue = documentId(uri)
        val query = DocumentsContract.buildDocumentUriUsingTree(uri, idValue)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        resolver.query(query, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "Unnamed"; val mime = c.getString(1) ?: "application/octet-stream"
                val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(name, mime,
                    if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3), folder), uri)
            }
        }
        throw FileNotFoundException("Created document is unavailable")
    }
    private fun availableName(parent: Uri, original: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId(parent))
        val names = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            requireCompleteListing(c)
            while (c.moveToNext()) names += c.getString(0).orEmpty()
        } ?: throw IOException("The destination provider did not return a folder listing")
        if (original !in names) return original
        val dot = original.lastIndexOf('.').takeIf { it > 0 } ?: original.length
        val stem = original.substring(0, dot); val ext = original.substring(dot)
        var n = 2
        while ("$stem ($n)$ext" in names) n++
        return "$stem ($n)$ext"
    }
    private fun copyInto(source: Uri, destination: Uri, directory: Boolean) {
        if (!directory) {
            resolver.openInputStream(source).use { input ->
                resolver.openOutputStream(destination, "wt").use { output ->
                    requireNotNull(input).copyTo(requireNotNull(output), DEFAULT_BUFFER_SIZE)
                    output.flush()
                }
            }
            return
        }
        val sourceId = documentId(source)
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(source, sourceId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(sourceUri, sourceId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            requireCompleteListing(c)
            while (c.moveToNext()) {
                val idValue = c.getString(0); val name = c.getString(1) ?: "Unnamed"; val mime = c.getString(2) ?: "application/octet-stream"
                val childUri = DocumentsContract.buildDocumentUriUsingTree(sourceUri, idValue)
                val newUri = DocumentsContract.createDocument(resolver, destination, mime, name)
                    ?: throw FileNotFoundException("Could not create $name")
                copyInto(childUri, newUri, mime == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        } ?: throw FileNotFoundException("Could not list source folder")
    }
    private fun requireCompleteListing(cursor: android.database.Cursor) {
        val error = cursor.extras.getString(DocumentsContract.EXTRA_ERROR)
        if (!error.isNullOrBlank()) throw IOException("Could not read folder: $error")
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
            throw IOException("The storage provider is still loading this folder. Retry the transfer when it has finished.")
        }
    }
    private fun <T> unsupported(capability: ResourceCapability) = ProviderResult.Unsupported(capability)

    /** Return the selected document ID for descendants, or the root ID for a picker tree URI. */
    private fun documentId(uri: Uri): String = if (uri.pathSegments.firstOrNull() == "document" || uri.pathSegments.getOrNull(2) == "document") {
        DocumentsContract.getDocumentId(uri)
    } else {
        DocumentsContract.getTreeDocumentId(uri)
    }
}
