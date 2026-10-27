package com.infinifolder.preview

import com.infinifolder.model.ResourceEntry

/**
 * Matches directory icon conventions:
 * 1. .icon.<format> (e.g. .icon.png, .icon.svg, .icon.jpg)
 * 2. cover.<format> (e.g. cover.jpg, cover.png, cover.webp)
 *
 * .icon.* has higher precedence than cover.*.
 */
object DirectoryIconResolver {
    fun isDirectoryIconFileName(name: String): Boolean {
        val lower = name.lowercase()
        return isIconName(lower) || isCoverName(lower)
    }

    /** Returns true if [name] is an explicit `.icon` or `.icon.<ext>` file. */
    fun isExplicitIconFileName(name: String): Boolean =
        isIconName(name.lowercase())

    /**
     * Determines the `.icon.<ext>` file name and MIME type to write inside a folder
     * when the user selects an image from the system image selector.
     */
    fun targetIconFileName(displayName: String?, mimeType: String?): Pair<String, String> {
        val extFromName = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in ImageFormats.decodableExtensions }
        val cleanMime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        val extFromMime = when (cleanMime) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            "image/jxl" -> "jxl"
            "image/x-tga", "image/targa" -> "tga"
            else -> cleanMime?.removePrefix("image/")?.takeIf { it in ImageFormats.decodableExtensions }
        }
        val ext = extFromName ?: extFromMime ?: "png"
        val resolvedMime = when {
            !cleanMime.isNullOrBlank() && cleanMime.startsWith("image/") && cleanMime != "image/*" -> cleanMime
            ext == "jpg" || ext == "jpeg" -> "image/jpeg"
            ext == "svg" || ext == "svgz" -> "image/svg+xml"
            else -> "image/$ext"
        }
        return ".icon.$ext" to resolvedMime
    }

    private fun isIconName(lower: String): Boolean =
        lower.startsWith(".icon.") || lower == ".icon"

    private fun isCoverName(lower: String): Boolean =
        lower.startsWith("cover.") || lower == "cover"

    fun priority(name: String): Int {
        val lower = name.lowercase()
        return if (isIconName(lower)) 0 else 1
    }

    /**
     * Generic finder that selects the best directory icon candidate from any list of items.
     */
    fun <T> findBest(items: List<T>, nameOf: (T) -> String, isImage: (T) -> Boolean): T? {
        return items
            .filter { item ->
                val name = nameOf(item)
                isDirectoryIconFileName(name) && isImage(item)
            }
            .minWithOrNull(
                compareBy(
                    { priority(nameOf(it)) },
                    { nameOf(it).lowercase() }
                )
            )
    }

    /**
     * Given a list of entries inside a directory, finds the best matching image entry to use as the directory icon.
     */
    fun findBestIcon(entries: List<ResourceEntry>): ResourceEntry? =
        findBest(entries, { it.metadata.name }, { isCandidateIconEntry(it) })

    private fun isCandidateIconEntry(entry: ResourceEntry): Boolean {
        val meta = entry.metadata
        if (meta.isDirectory) return false
        if (ImageFormats.isImage(meta)) return true
        // Remote and archive listings do not run magic byte detection during list(), so extensionless
        // `.icon` and `cover` files have generic/empty MIME types until decoded.
        val lower = meta.name.lowercase()
        val isExtensionlessCandidate = lower == ".icon" || lower == "cover"
        val hasGenericMime = meta.mimeType.isBlank() || meta.mimeType.equals("application/octet-stream", ignoreCase = true)
        return isExtensionlessCandidate && hasGenericMime
    }
}
