package com.infinifolder.archive

import android.net.Uri

object ArchiveUri {
    const val SCHEME = "archive"
    private const val AUTHORITY = "archive"
    private const val PARAM_SOURCE = "source"
    private const val PARAM_PATH = "path"

    fun build(sourceUri: Uri, entryPath: String, deleted: Boolean = false): Uri {
        val cleanPath = entryPath.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .appendQueryParameter(PARAM_SOURCE, sourceUri.toString())
            .appendQueryParameter(PARAM_PATH, cleanPath)
            .apply { if (deleted) appendQueryParameter("deleted", "1") }
            .build()
    }

    fun isDeletedView(uri: Uri): Boolean = isArchiveUri(uri) && uri.getQueryParameter("deleted") == "1"

    fun deletedFiles(sourceUri: Uri): Uri = build(sourceUri, "", deleted = true)

    fun isArchiveUri(uri: Uri): Boolean = uri.scheme == SCHEME

    fun getSourceUri(uri: Uri): Uri {
        require(isArchiveUri(uri)) { "Not an archive URI: $uri" }
        val sourceStr = uri.getQueryParameter(PARAM_SOURCE)
            ?: throw IllegalArgumentException("Archive URI missing 'source' parameter: $uri")
        return Uri.parse(sourceStr)
    }

    fun getEntryPath(uri: Uri): String {
        require(isArchiveUri(uri)) { "Not an archive URI: $uri" }
        return uri.getQueryParameter(PARAM_PATH).orEmpty()
    }

    fun toArchiveUri(sourceUri: Uri): Uri = build(sourceUri, "")
}
