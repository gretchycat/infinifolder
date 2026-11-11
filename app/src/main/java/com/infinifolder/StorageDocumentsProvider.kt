package com.infinifolder

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.storage.*
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Android's grant-checked storage surface, also usable by clients without InfiniSelect. */
class StorageDocumentsProvider : DocumentsProvider() {
    private val app get() = requireNotNull(context)
    private val runtime get() = StorageRuntime.get(app)
    private val records get() = app.getSharedPreferences("storage.documents", 0)
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        row(mapOf(Root.COLUMN_ROOT_ID to ROOT_ID, Root.COLUMN_DOCUMENT_ID to ROOT_ID,
            Root.COLUMN_TITLE to "Infinifolder", Root.COLUMN_SUMMARY to "Local, remote and container storage",
            Root.COLUMN_MIME_TYPES to "*/*", Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_IS_CHILD))
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (documentId == ROOT_ID) row(mapOf(Document.COLUMN_DOCUMENT_ID to ROOT_ID,
                Document.COLUMN_DISPLAY_NAME to "Infinifolder", Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
                Document.COLUMN_FLAGS to 0))
            else addDocument(documentId, entry(documentId))
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (parentDocumentId == ROOT_ID) {
            val roots = mutableListOf<Pair<Uri, String>>()
            if (runtime.local.hasAccess()) roots += runtime.local.rootUri to "Local storage"
            runtime.connections.list().forEach { connection ->
                runtime.registry.rootUri(connection)?.let { roots += it to connection.displayName }
            }
            app.contentResolver.persistedUriPermissions.filter { it.isReadPermission && it.uri.authority != AUTHORITY }
                .forEach { permission ->
                    if (android.provider.DocumentsContract.isTreeUri(permission.uri)) {
                        val id = android.provider.DocumentsContract.getTreeDocumentId(permission.uri)
                        roots += android.provider.DocumentsContract.buildDocumentUriUsingTree(permission.uri, id) to "Android storage"
                    }
                }
            roots += ContainerLinksProvider.ROOT to "Saved containers"
            roots.distinctBy { it.first }.forEach { (uri, name) ->
                val ref = runtime.provider.refForUri(uri) ?: return@forEach
                cursor.addDocument(register(uri, ROOT_ID), ResourceEntry(ref,
                    ResourceMetadata(name, Document.MIME_TYPE_DIR, null, null, true, canWrite = false), uri))
            }
        } else {
            val parent = entry(parentDocumentId)
            val uri = if (!parent.metadata.isDirectory && ArchiveFormats.isArchive(parent.metadata.name, parent.metadata.mimeType))
                ArchiveUri.toArchiveUri(parent.uri) else parent.uri
            val ref = runtime.provider.refForUri(uri) ?: throw FileNotFoundException("Storage source unavailable")
            when (val result = runtime.provider.list(ref)) {
                is ProviderResult.Success -> {
                    result.value.forEach { child -> cursor.addDocument(register(child.uri, parentDocumentId), child) }
                    cursor.extras = android.os.Bundle().apply {
                        putBoolean(android.provider.DocumentsContract.EXTRA_LOADING, result.isLoading)
                        result.notice?.let { putString(android.provider.DocumentsContract.EXTRA_INFO, it) }
                    }
                }
                is ProviderResult.Failure -> throw FileNotFoundException(result.message).apply { initCause(result.cause) }
                is ProviderResult.Unsupported -> throw FileNotFoundException("This source cannot list directories")
            }
        }
        val notification = android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
        cursor.setNotificationUri(app.contentResolver, notification)
        if (cursor.extras.getBoolean(android.provider.DocumentsContract.EXTRA_LOADING, false) && pending.add(parentDocumentId)) {
            refresh.schedule({
                pending.remove(parentDocumentId)
                app.contentResolver.notifyChange(notification, null)
            }, 1, java.util.concurrent.TimeUnit.SECONDS)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return true
        var current = documentId
        val visited = hashSetOf<String>()
        while (visited.add(current) && visited.size < 1024) {
            current = records.getString("$current.parent", null) ?: return false
            if (current == parentDocumentId) return true
        }
        return false
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val item = entry(documentId)
        if (item.metadata.isDirectory) throw FileNotFoundException("Cannot open a directory as a file")
        val descriptor = StorageDescriptor.open(app, runtime.provider, item.ref, mode)
        try { signal?.throwIfCanceled(); return descriptor }
        catch (error: Throwable) { descriptor.close(); throw error }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        StorageSafety.validateName(displayName)
        val parent = entry(parentDocumentId)
        val parentRef = if (!parent.metadata.isDirectory && ArchiveFormats.isArchive(parent.metadata.name, parent.metadata.mimeType))
            runtime.provider.refForUri(ArchiveUri.toArchiveUri(parent.uri)) ?: throw FileNotFoundException("Container unavailable")
            else parent.ref
        val result = if (mimeType == Document.MIME_TYPE_DIR) runtime.provider.createDirectory(parentRef, displayName)
            else runtime.provider.createFile(parentRef, displayName, mimeType)
        return register(result.valueOrThrow().uri, parentDocumentId)
    }

    override fun deleteDocument(documentId: String) {
        runtime.provider.delete(entry(documentId).ref).valueOrThrow()
        revokeDocumentPermission(documentId)
    }

    private fun entry(id: String): ResourceEntry {
        val source = records.getString("$id.uri", null)?.let(Uri::parse) ?: throw FileNotFoundException("Unknown document")
        val ref = runtime.provider.refForUri(source) ?: throw FileNotFoundException("Storage source unavailable")
        return runtime.provider.stat(ref).valueOrThrow()
    }

    private fun register(uri: Uri, parent: String): String = registerSource(app, uri, parent)

    private fun MatrixCursor.addDocument(id: String, item: ResourceEntry) {
        val m = item.metadata
        val container = !m.isDirectory && ArchiveFormats.isArchive(m.name, m.mimeType)
        val capabilityRef = if (container) runtime.provider.refForUri(ArchiveUri.toArchiveUri(item.uri)) else item.ref
        val capabilities = capabilityRef?.let { (runtime.provider.capabilities(it) as? ProviderResult.Success)?.value }
        var flags = 0
        if (!container && capabilities?.get(ResourceCapability.WRITE) == Support.SUPPORTED) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if ((m.isDirectory || container) && capabilities?.get(ResourceCapability.CREATE) == Support.SUPPORTED) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (capabilities?.get(ResourceCapability.DELETE) == Support.SUPPORTED) flags = flags or Document.FLAG_SUPPORTS_DELETE
        row(mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to m.name,
            Document.COLUMN_MIME_TYPE to if (m.isDirectory || container) Document.MIME_TYPE_DIR else m.mimeType,
            Document.COLUMN_SIZE to m.sizeBytes, Document.COLUMN_LAST_MODIFIED to m.modifiedAtMillis,
            Document.COLUMN_FLAGS to flags))
    }
    private fun MatrixCursor.row(values: Map<String, Any?>) { addRow(columnNames.map { values[it] }) }
    private fun <T> ProviderResult<T>.valueOrThrow(): T = when (this) {
        is ProviderResult.Success -> value
        is ProviderResult.Failure -> throw FileNotFoundException(message).apply { initCause(cause) }
        is ProviderResult.Unsupported -> throw FileNotFoundException("Unsupported storage operation: $capability")
    }
    companion object {
        const val AUTHORITY = "com.infinifolder.documents"
        const val ROOT_ID = "root"
        /** An Android reference for directory grouping; no directory grant is implied. */
        fun reference(context: android.content.Context, source: Uri): Uri =
            if (source.scheme == "content" && source.authority == AUTHORITY) source
            else android.provider.DocumentsContract.buildDocumentUri(AUTHORITY, registerSource(context, source, ROOT_ID))
        private fun registerSource(context: android.content.Context, uri: Uri, parent: String): String {
            require(uri.authority != AUTHORITY) { "A document cannot refer back to its own provider" }
            val id = MessageDigest.getInstance("SHA-256").digest("$parent\n$uri".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val records = context.getSharedPreferences("storage.documents", 0)
            if (!records.contains("$id.uri")) check(records.edit().putString("$id.uri", uri.toString())
                .putString("$id.parent", parent).commit()) { "Cannot register document" }
            return id
        }
        private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val refresh = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "StorageDocumentsRefresh").apply { isDaemon = true }
        }
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
