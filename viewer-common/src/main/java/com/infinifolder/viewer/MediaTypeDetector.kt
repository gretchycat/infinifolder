package com.infinifolder.viewer

import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.preview.ImageFormats
import java.util.Locale

enum class MediaType {
    IMAGE,
    TEXT,
    MARKDOWN,
    HTML,
    AUDIO,
    VIDEO,
    PDF,
    EPUB,
    SPREADSHEET,
    ARCHIVE_OR_CONTAINER,
    UNSUPPORTED
}

object MediaTypeDetector {

    private val MARKDOWN_EXTENSIONS = setOf(
        "md", "markdown", "mdown", "mkd", "mkdn"
    )

    private val HTML_EXTENSIONS = setOf(
        "html", "htm", "xhtml"
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "mid", "midi", "wma", "aiff", "aif", "alac"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m2ts", "ogv", "wmv", "flv", "m4v"
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "cfg", "conf", "ini", "properties", "sh", "bash", "zsh", "fish",
        "py", "kt", "kts", "java", "c", "cpp", "cc", "cxx", "h", "hpp", "cs", "go", "rs",
        "rb", "php", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json", "xml", "yaml", "yml",
        "toml", "sql", "env", "diff", "patch", "bat", "cmd", "ps1",
        "gradle", "lua", "r", "swift", "dart", "asm", "s", "pl", "pm", "tcl", "dockerfile",
        "makefile", "mk", "cmake", "gitignore", "gitattributes", "editorconfig", "bashrc", "zshrc", "profile"
    )

    private val TEXT_MIME_TYPES = setOf(
        "application/json", "application/xml", "application/x-yaml", "application/yaml",
        "application/javascript", "application/x-javascript", "application/x-sh",
        "application/x-csh", "application/x-python", "application/x-ruby"
    )

    fun detect(name: String, mimeType: String? = null): MediaType {
        val safeMime = com.infinifolder.mime.MimeRegistry.effective(name, mimeType)
        val lowerName = name.lowercase(Locale.ROOT)
        val ext = lowerName.substringAfterLast('.', "")
        val lowerMime = safeMime.lowercase(Locale.ROOT).substringBefore(';').trim()

        when {
            lowerMime == "application/pdf" -> return MediaType.PDF
            lowerMime == "application/epub+zip" -> return MediaType.EPUB
            lowerMime in setOf("text/csv", "text/tab-separated-values") -> return MediaType.SPREADSHEET
            lowerMime == "text/markdown" -> return MediaType.MARKDOWN
            lowerMime in setOf("text/html", "application/xhtml+xml") -> return MediaType.HTML
            lowerMime.startsWith("image/") -> return MediaType.IMAGE
            lowerMime.startsWith("audio/") -> return MediaType.AUDIO
            lowerMime.startsWith("video/") -> return MediaType.VIDEO
            lowerMime.startsWith("text/") || lowerMime in TEXT_MIME_TYPES -> return MediaType.TEXT
        }

        // 1. Packaged formats (e.g. apk) should not be treated as generic archives/media
        if (ArchiveFormats.isPackagedFormat(name, safeMime) && safeMime != "application/epub+zip") {
            return MediaType.UNSUPPORTED
        }

        // 2. PDF Documents
        if (ext == "pdf" || lowerMime == "application/pdf") {
            return MediaType.PDF
        }

        // 3. EPUB eBooks (check before generic zip archives)
        if (ext == "epub" || lowerMime == "application/epub+zip") {
            return MediaType.EPUB
        }

        // 4. Spreadsheets / Delimited Data
        if (ext == "csv" || ext == "tsv" || lowerMime == "text/csv" || lowerMime == "text/tab-separated-values") {
            return MediaType.SPREADSHEET
        }

        // 5. Archives and virtual container layers
        if (ArchiveFormats.isArchive(name, safeMime)) {
            return MediaType.ARCHIVE_OR_CONTAINER
        }

        // 6. Images
        val dummyMeta = ResourceMetadata(name, safeMime, null, null, false)
        if (ImageFormats.isImage(dummyMeta) || ext in ImageFormats.decodableExtensions) {
            return MediaType.IMAGE
        }

        // 7. Markdown
        if (ext in MARKDOWN_EXTENSIONS || lowerMime == "text/markdown" || lowerMime == "text/x-markdown") {
            return MediaType.MARKDOWN
        }

        // 8. HTML
        if (ext in HTML_EXTENSIONS || lowerMime == "text/html" || lowerMime == "application/xhtml+xml") {
            return MediaType.HTML
        }

        // 9. Audio
        if (ext in AUDIO_EXTENSIONS || lowerMime.startsWith("audio/")) {
            return MediaType.AUDIO
        }

        // 10. Video
        if (ext in VIDEO_EXTENSIONS || lowerMime.startsWith("video/")) {
            return MediaType.VIDEO
        }

        // 11. Plain Text & Code
        if (ext in TEXT_EXTENSIONS || lowerMime.startsWith("text/") || lowerMime in TEXT_MIME_TYPES) {
            return MediaType.TEXT
        }

        return MediaType.UNSUPPORTED
    }

    fun detect(entry: ResourceEntry): MediaType {
        if (entry.metadata.isDirectory) return MediaType.UNSUPPORTED
        return detect(entry.metadata.name, entry.metadata.mimeType)
    }

    fun isViewableInternally(name: String, mimeType: String? = null): Boolean = when (detect(name, mimeType)) {
        MediaType.IMAGE,
        MediaType.TEXT,
        MediaType.MARKDOWN,
        MediaType.HTML,
        MediaType.AUDIO,
        MediaType.VIDEO,
        MediaType.PDF,
        MediaType.EPUB,
        MediaType.SPREADSHEET -> true
        else -> false
    }

    fun isEditable(type: MediaType): Boolean = when (type) {
        MediaType.IMAGE,
        MediaType.TEXT,
        MediaType.MARKDOWN,
        MediaType.HTML,
        MediaType.SPREADSHEET -> true
        else -> false
    }
}
