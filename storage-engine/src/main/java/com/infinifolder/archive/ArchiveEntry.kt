package com.infinifolder.archive

import android.net.Uri

data class ArchiveEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val mimeType: String,
    val isSymbolicLink: Boolean = false,
    val linkTarget: String? = null
)

class ArchiveTree(
    val sourceUri: Uri?,
    val entriesByParent: Map<String, List<ArchiveEntry>>,
    private val entriesByPath: Map<String, ArchiveEntry>
) {
    fun list(parentPath: String): List<ArchiveEntry> {
        val cleanParent = parentPath.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return entriesByParent[cleanParent]
            ?: entriesByParent.entries.firstOrNull { it.key.equals(cleanParent, ignoreCase = true) }?.value
            ?: emptyList()
    }

    fun getEntry(path: String): ArchiveEntry? {
        val cleanPath = path.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return entriesByPath[cleanPath]
            ?: entriesByPath.entries.firstOrNull { it.key.equals(cleanPath, ignoreCase = true) }?.value
    }
}

object ArchiveMimeTypes {
    const val MIME_TYPE_DIR = "vnd.android.document/directory"
    fun guessMimeType(name: String, isDirectory: Boolean): String = com.infinifolder.mime.MimeRegistry.guess(name, isDirectory)
}
