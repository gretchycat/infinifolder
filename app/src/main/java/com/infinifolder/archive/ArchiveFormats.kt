package com.infinifolder.archive

import java.util.Locale

object ArchiveFormats {
    private val COMPOUND_EXTENSIONS = listOf(
        ".tar.gz", ".tar.bz2", ".tar.bz", ".tar.xz", ".tar.zst", ".tar.lzma", ".tar.z"
    )

    private val SIMPLE_EXTENSIONS = setOf(
        "zip", "jar", "apk", "aar", "cbz",
        "tar", "tgz", "tbz", "tbz2", "txz", "tzst", "tlz", "cbt",
        "7z", "cb7",
        "cpio",
        "ar", "deb"
    )

    private val ARCHIVE_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/java-archive",
        "application/vnd.android.package-archive",
        "application/x-tar",
        "application/tar",
        "application/x-gtar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/x-7z-compressed",
        "application/x-cpio",
        "application/x-archive",
        "application/x-debian-package"
    )

    fun isArchive(name: String, mimeType: String = ""): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        if (COMPOUND_EXTENSIONS.any { lowerName.endsWith(it) }) return true
        val ext = lowerName.substringAfterLast('.', "")
        if (SIMPLE_EXTENSIONS.contains(ext)) return true
        if (mimeType.isNotEmpty() && ARCHIVE_MIME_TYPES.contains(mimeType.lowercase(Locale.ROOT))) return true
        return false
    }

    fun archiveExtension(name: String): String {
        val lowerName = name.lowercase(Locale.ROOT)
        for (compound in COMPOUND_EXTENSIONS) {
            if (lowerName.endsWith(compound)) return compound.removePrefix(".")
        }
        return lowerName.substringAfterLast('.', "")
    }
}
