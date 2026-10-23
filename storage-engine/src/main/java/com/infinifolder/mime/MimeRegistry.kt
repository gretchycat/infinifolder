package com.infinifolder.mime

import java.util.Locale

/** Application identity and its packaging are separate parts of one classification. */
object MimeRegistry {
    data class PackageFormat(val mime: String, val container: String)
    val packages = mapOf(
        "apk" to PackageFormat("application/vnd.android.package-archive", "zip"),
        "xapk" to PackageFormat("application/x-xapk", "zip"),
        "apkm" to PackageFormat("application/vnd.apkm", "zip"),
        "aab" to PackageFormat("application/x-android-app-bundle", "zip"),
        "jar" to PackageFormat("application/java-archive", "zip"),
        "war" to PackageFormat("application/java-archive", "zip"),
        "ear" to PackageFormat("application/java-archive", "zip"),
        "aar" to PackageFormat("application/java-archive", "zip"),
        "love" to PackageFormat("application/x-love-game", "zip"),
        "ipa" to PackageFormat("application/x-ios-app", "zip"),
        "epub" to PackageFormat("application/epub+zip", "zip"),
        "docx" to PackageFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "zip"),
        "xlsx" to PackageFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "zip"),
        "pptx" to PackageFormat("application/vnd.openxmlformats-officedocument.presentationml.presentation", "zip"),
        "odt" to PackageFormat("application/vnd.oasis.opendocument.text", "zip"),
        "ods" to PackageFormat("application/vnd.oasis.opendocument.spreadsheet", "zip"),
        "odp" to PackageFormat("application/vnd.oasis.opendocument.presentation", "zip")
    )
    private val aliases = mapOf("application/x-java-archive" to "application/java-archive", "application/x-zip-compressed" to "application/zip",
        "application/x-gzip" to "application/gzip", "text/xml" to "application/xml", "text/x-markdown" to "text/markdown")
    private val extensions = mapOf(
        "txt" to "text/plain", "log" to "text/plain", "cfg" to "text/plain", "conf" to "text/plain", "ini" to "text/plain", "properties" to "text/plain",
        "md" to "text/markdown", "markdown" to "text/markdown", "mdown" to "text/markdown", "mkd" to "text/markdown", "mkdn" to "text/markdown",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values", "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "xml" to "application/xml", "json" to "application/json", "yaml" to "application/yaml", "yml" to "application/yaml",
        "ts" to "text/typescript", "sh" to "application/x-sh", "py" to "text/x-python", "kt" to "text/x-kotlin", "kts" to "text/x-kotlin", "lua" to "text/x-lua", "js" to "application/javascript",
        "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "svg" to "image/svg+xml", "svgz" to "image/svg+xml", "bmp" to "image/bmp", "ico" to "image/x-icon", "jxl" to "image/jxl", "tga" to "image/x-tga", "icb" to "image/x-tga", "vda" to "image/x-tga", "vst" to "image/x-tga",
        "avif" to "image/avif", "heic" to "image/heic", "heif" to "image/heif", "dng" to "image/x-adobe-dng",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac", "opus" to "audio/opus", "m4a" to "audio/mp4",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "zip" to "application/zip", "cbz" to "application/zip", "tar" to "application/x-tar", "cbt" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2", "tbz" to "application/x-bzip2", "tbz2" to "application/x-bzip2", "xz" to "application/x-xz", "txz" to "application/x-xz",
        "7z" to "application/x-7z-compressed", "cb7" to "application/x-7z-compressed", "cpio" to "application/x-cpio", "ar" to "application/x-archive", "deb" to "application/vnd.debian.binary-package",
        "iso" to "application/x-iso9660-image", "cdr" to "application/x-iso9660-image", "img" to "application/x-raw-disk-image", "raw" to "application/x-raw-disk-image", "bin" to "application/x-raw-disk-image",
        "vdi" to "application/x-virtualbox-vdi", "qcow2" to "application/x-qemu-disk", "vmdk" to "application/x-vmdk", "vhd" to "application/x-vhd", "vhdx" to "application/x-vhdx",
        "lnk" to "application/x-ms-shortcut", "iflink" to "application/vnd.infinifolder.shortcut+json"
    )
    fun normalize(mime: String?): String {
        val value = mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return aliases[value] ?: value.ifEmpty { "application/octet-stream" }
    }
    fun packageFormat(name: String, mime: String? = null): PackageFormat? {
        if (normalize(mime) == "vnd.android.document/directory") return null
        return packages[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
            ?: packages.values.firstOrNull { it.mime == normalize(mime) }
    }
    fun guess(name: String, directory: Boolean = false): String {
        if (directory) return "vnd.android.document/directory"
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return packages[extension]?.mime ?: extensions[extension] ?: runCatching {
            android.webkit.MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
        }.getOrNull()?.let(::normalize) ?: "application/octet-stream"
    }
    fun effective(name: String, supplied: String?, detected: String? = null): String {
        if (normalize(supplied) == "vnd.android.document/directory") return "vnd.android.document/directory"
        packageFormat(name, supplied)?.let { return it.mime }
        val stated = normalize(supplied)
        val sniffed = normalize(detected)
        if (sniffed != "application/octet-stream") return sniffed
        val inferred = guess(name)
        if (stated in setOf("application/octet-stream", "text/plain", "application/x-archive", "application/x-compressed")) {
            if (inferred != "application/octet-stream") return inferred
        }
        return stated
    }
    fun fallbackIcon(mimeType: String): String = when (val mime = normalize(mimeType)) {
        "vnd.android.document/directory" -> "📁"
        "application/vnd.android.package-archive" -> "🤖"
        "application/x-love-game" -> "🎮"
        "application/java-archive" -> "☕"
        "application/epub+zip" -> "📖"
        "application/pdf" -> "▤"
        "text/csv", "text/tab-separated-values" -> "▦"
        else -> when {
            mime.startsWith("image/") -> "🖼"
            mime.startsWith("audio/") -> "♫"
            mime.startsWith("video/") -> "▶"
            mime.startsWith("text/") -> "▤"
            packageFormat("", mime) != null || mime in setOf("application/zip", "application/x-tar", "application/gzip", "application/x-xz", "application/x-bzip2", "application/x-7z-compressed", "application/x-archive", "application/x-cpio") -> "📦"
            else -> "▧"
        }
    }

    fun iconCandidates(mimeType: String): List<String> {
        val mime = normalize(mimeType)
        val candidates = mutableListOf(mime.replace('/', '-'))
        when {
            mime == "application/vnd.android.package-archive" -> candidates.addAll(listOf("android-package-archive", "package-x-apk", "package-x-generic"))
            mime == "application/java-archive" -> candidates.addAll(listOf("application-x-java-archive", "java", "package-x-generic"))
            mime == "application/x-love-game" -> candidates.addAll(listOf("love", "package-x-generic"))
            mime == "application/pdf" -> candidates.add("application-pdf")
            mime.contains("wordprocessingml") || mime == "application/vnd.oasis.opendocument.text" -> candidates.add("x-office-document")
            mime.contains("spreadsheet") -> candidates.add("x-office-spreadsheet")
            mime.contains("presentation") -> candidates.add("x-office-presentation")
            mime.contains("disk") || mime.contains("iso9660") || mime.contains("vmdk") -> candidates.addAll(listOf("media-optical", "drive-harddisk"))
            mime in setOf("application/zip", "application/x-tar", "application/gzip", "application/x-xz", "application/x-bzip2", "application/x-7z-compressed") -> candidates.add("package-x-generic")
        }
        if (packageFormat("", mime) != null) candidates.add("package-x-generic")
        val category = mime.substringBefore('/')
        if (category in setOf("text", "image", "video", "audio", "font")) candidates.add("$category-x-generic")
        candidates.addAll(listOf("application-octet-stream", "unknown"))
        return candidates.distinct()
    }
}
