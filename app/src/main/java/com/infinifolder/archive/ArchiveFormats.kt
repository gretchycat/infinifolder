package com.infinifolder.archive

import java.util.Locale

object ArchiveFormats {
    private val DISC_BASE_EXTENSIONS = setOf(
        "iso", "cdr",
        "img", "raw", "ima", "flp",
        "fat", "vfat", "exfat",
        "ext2", "ext3", "ext4",
        "vdi", "qcow2", "vmdk", "vhd", "vhdx",
        "esp", "efi",
        "mbr", "gpt", "apm", "dd", "dsk", "dump", "bin"
    )

    private val COMPRESSION_SUFFIXES = listOf(
        "gz", "bz2", "bz", "xz", "zst", "lzma", "z"
    )

    private val DISC_COMPOUND_EXTENSIONS: List<String> = DISC_BASE_EXTENSIONS.flatMap { base ->
        COMPRESSION_SUFFIXES.map { comp -> ".$base.$comp" }
    }

    private val TAR_COMPOUND_EXTENSIONS = listOf(
        ".tar.gz", ".tar.bz2", ".tar.bz", ".tar.xz", ".tar.zst", ".tar.lzma", ".tar.z"
    )

    private val COMPOUND_EXTENSIONS = TAR_COMPOUND_EXTENSIONS + DISC_COMPOUND_EXTENSIONS

    private val SIMPLE_EXTENSIONS = setOf(
        "zip", "jar", "war", "ear", "apk", "xapk", "apkm", "aab", "aar", "love", "ipa", "cbz",
        "tar", "tgz", "tbz", "tbz2", "txz", "tzst", "tlz", "cbt",
        "7z", "cb7",
        "cpio",
        "ar", "deb"
    ) + DISC_BASE_EXTENSIONS

    private val PACKAGED_FORMAT_EXTENSIONS = setOf(
        "apk", "xapk", "apkm", "aab",
        "jar", "war", "ear", "aar",
        "love",
        "ipa"
    )

    private val PACKAGED_FORMAT_MIME_TYPES = setOf(
        "application/vnd.android.package-archive",
        "application/java-archive",
        "application/x-java-archive",
        "application/x-love-game"
    )

    private val DISC_MIME_TYPES = setOf(
        "application/x-iso9660-image",
        "application/x-cd-image",
        "application/x-raw-disk-image",
        "application/x-raw-disk-image-xz-compressed",
        "application/vnd.efi.iso",
        "application/vnd.efi.img",
        "application/x-virtualbox-vdi",
        "application/x-qemu-disk",
        "application/x-vmdk",
        "application/x-vhd",
        "application/x-vhdx"
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
    ) + DISC_MIME_TYPES

    fun isArchive(name: String, mimeType: String = ""): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        if (COMPOUND_EXTENSIONS.any { lowerName.endsWith(it) }) return true
        val ext = lowerName.substringAfterLast('.', "")
        if (SIMPLE_EXTENSIONS.contains(ext)) return true
        if (mimeType.isNotEmpty() && ARCHIVE_MIME_TYPES.contains(mimeType.lowercase(Locale.ROOT))) return true
        return false
    }

    fun isPackagedFormat(name: String, mimeType: String = ""): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        val ext = lowerName.substringAfterLast('.', "")
        if (PACKAGED_FORMAT_EXTENSIONS.contains(ext)) return true
        if (mimeType.isNotEmpty() && PACKAGED_FORMAT_MIME_TYPES.contains(mimeType.lowercase(Locale.ROOT))) return true
        return false
    }

    fun packageMimeType(name: String): String? {
        val lowerName = name.lowercase(Locale.ROOT)
        val ext = lowerName.substringAfterLast('.', "")
        return when (ext) {
            "apk", "xapk", "apkm", "aab" -> "application/vnd.android.package-archive"
            "jar", "war", "ear", "aar" -> "application/java-archive"
            "love" -> "application/x-love-game"
            "ipa" -> "application/octet-stream"
            else -> null
        }
    }

    fun isDefaultBrowsableArchive(name: String, mimeType: String = ""): Boolean {
        return isArchive(name, mimeType) && !isPackagedFormat(name, mimeType)
    }

    fun isDiscImage(name: String, mimeType: String = ""): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        if (DISC_COMPOUND_EXTENSIONS.any { lowerName.endsWith(it) }) return true
        val ext = lowerName.substringAfterLast('.', "")
        if (DISC_BASE_EXTENSIONS.contains(ext)) return true
        if (mimeType.isNotEmpty() && DISC_MIME_TYPES.contains(mimeType.lowercase(Locale.ROOT))) return true
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
