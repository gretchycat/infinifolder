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

    fun guessMimeType(name: String, isDirectory: Boolean): String {
        if (isDirectory) return MIME_TYPE_DIR
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "md", "csv", "tsv", "conf", "ini", "properties" -> "text/plain"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg", "svgz" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "jxl" -> "image/jxl"
            "tga", "icb", "vda", "vst" -> "image/x-tga"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "apk", "xapk", "apkm", "aab" -> "application/vnd.android.package-archive"
            "jar", "war", "ear", "aar" -> "application/java-archive"
            "love" -> "application/x-love-game"
            "ipa" -> "application/octet-stream"
            "zip", "cbz" -> "application/zip"
            "tar", "cbt" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            "bz2", "tbz", "tbz2" -> "application/x-bzip2"
            "xz", "txz" -> "application/x-xz"
            "7z", "cb7" -> "application/x-7z-compressed"
            "cpio" -> "application/x-cpio"
            "ar", "deb" -> "application/x-archive"
            "iso", "cdr" -> "application/x-iso9660-image"
            "img", "raw", "ima", "flp", "fat", "vfat", "exfat", "ext2", "ext3", "ext4", "esp", "efi", "mbr", "gpt", "apm", "dd", "dsk", "dump", "bin" -> "application/x-raw-disk-image"
            "lnk" -> "application/x-ms-shortcut"
            else -> try {
                android.webkit.MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            } catch (_: Throwable) {
                "application/octet-stream"
            }
        }
    }
}
