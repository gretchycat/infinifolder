package com.infinifolder.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

data class AppDocumentSource(
    val key: String,
    val authority: String,
    val title: String,
    val detail: String,
    val target: Uri?,
    val granted: Uri?
)

/** Discover apps that expose SAF documents. A media folder or launcher activity is not a document source. */
class AppDocumentSources(private val context: Context) {
    private val resolver = context.contentResolver

    companion object {
        private val systemStorageAuthorities = setOf(
            "com.android.externalstorage.documents", "com.android.providers.downloads.documents",
            "com.android.providers.media.documents"
        )
        fun isAppAuthority(authority: String?): Boolean = authority != null && authority !in systemStorageAuthorities
    }

    fun discover(): List<AppDocumentSource> {
        val packages = context.packageManager
        val labels = linkedMapOf<String, String>()
        packages.queryIntentContentProviders(Intent("android.content.action.DOCUMENTS_PROVIDER"), 0)
            .mapNotNull { it.providerInfo }.filter { it.enabled && it.exported }.forEach { provider ->
                provider.authority.orEmpty().split(';').filter { it.isNotBlank() && isAppAuthority(it) }.forEach {
                    labels[it] = provider.loadLabel(packages).toString()
                }
            }
        val grants = resolver.persistedUriPermissions.filter {
            it.isReadPermission && DocumentsContract.isTreeUri(it.uri) && isAppAuthority(it.uri.authority)
        }.map { it.uri }
        grants.forEach { grant ->
            val authority = requireNotNull(grant.authority)
            if (authority !in labels) labels[authority] = runCatching {
                packages.resolveContentProvider(authority, 0)?.loadLabel(packages)?.toString()
            }.getOrNull() ?: authority
        }
        return labels.flatMap { (authority, label) ->
            val matchingGrants = grants.filter { it.authority == authority }
                .distinctBy { DocumentsContract.getTreeDocumentId(it) }
            val roots = queryRoots(authority)
            if (roots.isNotEmpty()) {
                val usedGrants = mutableSetOf<Uri>()
                val sources = roots.map { (id, title) ->
                    val grant = matchingGrants.firstOrNull { canReadDocument(it, id) }
                    if (grant != null) usedGrants += grant
                    val document = DocumentsContract.buildDocumentUri(authority, id)
                    AppDocumentSource(key(authority, id), authority, title.ifBlank { label }, label, document,
                        grant?.let { DocumentsContract.buildDocumentUriUsingTree(it, id) })
                }
                sources + matchingGrants.filterNot { it in usedGrants }.map { savedSource(authority, label, it) }
            } else if (matchingGrants.isNotEmpty()) {
                matchingGrants.map { savedSource(authority, label, it) }
            } else {
                // Root metadata may require MANAGE_DOCUMENTS, which ordinary apps cannot hold.
                // Android's picker supplies the actual root URI after the user selects this app.
                listOf(AppDocumentSource(key(authority, null), authority, label,
                    "Choose this app in Android’s folder picker", null, null))
            }
        }.distinctBy { it.key }.sortedBy { it.title.lowercase() }
    }

    private fun savedSource(authority: String, label: String, tree: Uri): AppDocumentSource {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val name = runCatching {
            resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return AppDocumentSource(key(authority, id), authority, label, name ?: "Saved app folder", document, document)
    }

    private fun canReadDocument(tree: Uri, id: String): Boolean {
        if (DocumentsContract.getTreeDocumentId(tree) == id) return true
        return runCatching {
            resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    private fun queryRoots(authority: String): List<Pair<String, String>> = runCatching {
        buildList {
            resolver.query(DocumentsContract.buildRootsUri(authority),
                arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    add(id to cursor.getString(1).orEmpty())
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun key(authority: String, id: String?) =
        "app_provider_" + Uri.encode(authority) + (id?.let { "_" + Uri.encode(it) } ?: "")
}
