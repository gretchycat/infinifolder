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
        "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "mid", "midi", "kar", "rmi", "wma", "ape", "aiff", "aif", "aifc", "alac",
        "au", "snd", "voc", "8svx", "svx", "16sv", "maud", "w64", "rf64", "caf",
        "avr", "ircam", "sph", "pvf", "wve", "htk", "adx", "amr", "awb", "gsm", "g722",
        "sf2", "sfz", "pat", "mod", "s3m", "xm", "it", "dro", "imf", "vgm", "vgz", "ym",
        "nsf", "spc", "sid", "dsf", "dff", "wv", "tta", "ra", "ac3", "eac3", "dts", "mlp", "thd",
        "mp2", "3ga", "m4b", "m4r", "mka", "weba", "cdda", "m3u", "m3u8", "pls", "xspf", "cue"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m2ts", "ogv", "wmv", "flv", "m4v", "rm", "rmvb"
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
            lowerMime in setOf("text/csv", "text/tab-separated-values", "text/comma-separated-values", "text/x-comma-separated-values", "text/x-csv", "application/csv", "application/x-csv") -> return MediaType.SPREADSHEET
            lowerMime == "text/markdown" -> return MediaType.MARKDOWN
            lowerMime in setOf("text/html", "application/xhtml+xml") -> return MediaType.HTML
            lowerMime.startsWith("image/") -> return MediaType.IMAGE
            // Some providers use the RealAudio MIME even for RealMedia videos.
            lowerMime.startsWith("audio/") -> return if (ext in setOf("rm", "rmvb")) MediaType.VIDEO else MediaType.AUDIO
            lowerMime.startsWith("video/") -> return MediaType.VIDEO
            lowerMime.startsWith("text/") || lowerMime in TEXT_MIME_TYPES -> return when {
                ext in setOf("csv", "tsv") -> MediaType.SPREADSHEET
                ext in MARKDOWN_EXTENSIONS -> MediaType.MARKDOWN
                ext in HTML_EXTENSIONS -> MediaType.HTML
                else -> MediaType.TEXT
            }
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

        // Providers can label RealMedia video as audio/x-pn-realaudio. Keep its
        // container extensions and MIME aliases on the actual video backend.
        if (ext in setOf("rm", "rmvb") || lowerMime in setOf(
                "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr",
                "application/x-pn-realmedia", "video/vnd.rn-realvideo", "video/x-pn-realvideo")) {
            return MediaType.VIDEO
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
