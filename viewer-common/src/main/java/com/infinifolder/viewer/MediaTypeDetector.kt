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
    FONT,
    EPUB,
    DOCUMENT,
    EBOOK,
    SPREADSHEET,
    ARCHIVE_OR_CONTAINER,
    UNSUPPORTED
}

object MediaTypeDetector {

    fun isGenericBinary(name:String,mimeType:String?):Boolean =
        com.infinifolder.mime.MimeRegistry.effective(name,mimeType).lowercase(Locale.ROOT) in
            setOf("application/octet-stream","application/binary","application/x-binary","binary/octet-stream")

    private val MARKDOWN_EXTENSIONS get() = io.github.gretchycat.infiniscribe.ScribeTextFormats.MARKDOWN_EXTENSIONS

    private val HTML_EXTENSIONS get() = io.github.gretchycat.infiniscribe.ScribeTextFormats.HTML_EXTENSIONS

    private val AUDIO_EXTENSIONS get() = io.github.gretchycat.infinistream.RuntimeCapabilities.library("sound").descriptors().flatMap { it.formats }.flatMap { it.extensions }.toSet() + ArchiveFormats.PLAYLIST_EXTENSIONS

    private val VIDEO_EXTENSIONS get() = io.github.gretchycat.infinistream.RuntimeCapabilities.library("video").descriptors().flatMap { it.formats }.flatMap { it.extensions }.toSet()

    private val TEXT_EXTENSIONS get() = io.github.gretchycat.infiniscribe.ScribeTextFormats.TEXT_EXTENSIONS

    private val TEXT_MIME_TYPES get() = io.github.gretchycat.infiniscribe.ScribeTextFormats.TEXT_MIME_TYPES

    private val AUDIO_MIME_TYPES get() = io.github.gretchycat.infinistream.RuntimeCapabilities.library("sound").descriptors().flatMap { it.formats }.flatMap { it.mimeTypes }.toSet()

    fun detect(name: String, mimeType: String? = null): MediaType {
        val safeMime = com.infinifolder.mime.MimeRegistry.effective(name, mimeType)
        val discovered=ViewerComponents.resolve(io.github.gretchycat.infinistream.CapabilityResource(
            io.github.gretchycat.infinistream.StreamMetadata(name=name,content=io.github.gretchycat.infinistream.ContentType(mimeType=safeMime))))
        discovered?.descriptor?.attributes?.get("mediaTypes")?.split(',')?.firstNotNullOfOrNull { value ->
            runCatching { MediaType.valueOf(value) }.getOrNull()
        }?.let { return it }

        val lowerName = name.lowercase(Locale.ROOT)
        val ext = lowerName.substringAfterLast('.', "")
        val lowerMime = safeMime.lowercase(Locale.ROOT).substringBefore(';').trim()

        if (io.github.gretchycat.infiniscribe.FontFormats.supports("", lowerMime)) return MediaType.FONT
        val documentKind=io.github.gretchycat.infiniscribe.DocumentFormats.forMime(lowerMime)
        if(documentKind!=null)return documentMediaType(documentKind)
        when {
            lowerMime == "application/pdf" -> return MediaType.PDF
            lowerMime == "application/epub+zip" -> return MediaType.EPUB
            lowerMime in setOf("text/csv", "text/tab-separated-values", "text/comma-separated-values", "text/x-comma-separated-values", "text/x-csv", "application/csv", "application/x-csv") -> return MediaType.SPREADSHEET
            lowerMime == "text/markdown" -> return MediaType.MARKDOWN
            lowerMime in setOf("text/html", "application/xhtml+xml") -> return MediaType.HTML
            lowerMime == "image/x-ansi" -> return MediaType.TEXT
            lowerMime in ImageFormats.animationMimeTypes -> return MediaType.IMAGE
            lowerMime.startsWith("image/") -> return MediaType.IMAGE
            // Some providers use the RealAudio MIME even for RealMedia videos.
            lowerMime in AUDIO_MIME_TYPES -> return MediaType.AUDIO
            lowerMime.startsWith("audio/") -> return if (ext in setOf("rm", "rmvb")) MediaType.VIDEO else MediaType.AUDIO
            lowerMime.startsWith("video/") -> return MediaType.VIDEO
            lowerMime.startsWith("text/") || lowerMime in TEXT_MIME_TYPES -> return when {
                ext in setOf("csv", "tsv") -> MediaType.SPREADSHEET
                ext in MARKDOWN_EXTENSIONS -> MediaType.MARKDOWN
                ext in HTML_EXTENSIONS -> MediaType.HTML
                else -> MediaType.TEXT
            }
        }

        io.github.gretchycat.infiniscribe.DocumentFormats.detect(name)?.let {return documentMediaType(it)}

        if (io.github.gretchycat.infiniscribe.FontFormats.supports(name, lowerMime)) return MediaType.FONT

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

    private fun documentMediaType(kind:io.github.gretchycat.infiniscribe.DocumentFormats.Kind)=when(kind) {
        io.github.gretchycat.infiniscribe.DocumentFormats.Kind.SHEET,
        io.github.gretchycat.infiniscribe.DocumentFormats.Kind.ODF_SHEET -> MediaType.SPREADSHEET
        io.github.gretchycat.infiniscribe.DocumentFormats.Kind.MOBI,
        io.github.gretchycat.infiniscribe.DocumentFormats.Kind.FB2 -> MediaType.EBOOK
        else -> MediaType.DOCUMENT
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
        MediaType.FONT,
        MediaType.EPUB,
        MediaType.DOCUMENT,
        MediaType.EBOOK,
        MediaType.SPREADSHEET -> true
        else -> false
    }

    fun isEditable(name:String,mimeType:String?=null):Boolean {
        val resource=io.github.gretchycat.infinistream.CapabilityResource(io.github.gretchycat.infinistream.StreamMetadata(
            name=name,content=io.github.gretchycat.infinistream.ContentType(mimeType=com.infinifolder.mime.MimeRegistry.effective(name,mimeType))))
        if(ViewerComponents.resolve(resource,edit=true)!=null)return true
        if(ViewerComponents.resolve(resource)!=null)return false
        return io.github.gretchycat.infiniscribe.DocumentFormats.detect(name,mimeType)==null && isEditable(detect(name,mimeType))
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
