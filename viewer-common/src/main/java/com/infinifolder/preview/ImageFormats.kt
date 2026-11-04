package com.infinifolder.preview

import com.infinifolder.model.ResourceMetadata

/**
 * Identifies resources that can be previewed as images.
 *
 * Supported formats:
 * - Android platform codecs (ImageDecoder/BitmapFactory): JPEG, PNG/APNG, GIF, WebP, BMP, WBMP,
 *   ICO/CUR, HEIF/HEIC, AVIF, DNG, and camera RAW (CR2, NEF, NRW, ARW, RW2, ORF, RAF, PEF, SRW).
 * - SVG & SVGZ (via AndroidSVG).
 * - JPEG XL (.jxl, via jxl-coder).
 * - Truevision TGA (.tga, .icb, .vda, .vst, via TgaDecoder).
 */
object ImageFormats {
    val animationMimeTypes: Set<String> get() = FlicDecoder.mimeTypes + "video/x-mng"
    /** Extensions for all supported decodable image formats. */
    val decodableExtensions: Set<String> get() = CanvasCapabilities.extensions

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Raster icon previews do not determine which viewer opens a text document. */
    fun hasDosTextPreview(metadata: ResourceMetadata): Boolean = !metadata.isDirectory &&
        (extension(metadata.name) in setOf("ans","ansi","asc","diz","nfo") || metadata.mimeType in setOf("image/x-ansi","text/x-ansi","text/x-diz","text/x-nfo","text/x-ascii-art"))

    fun isImage(metadata: ResourceMetadata): Boolean =
        !metadata.isDirectory &&
            (metadata.mimeType.lowercase().startsWith("image/") ||
                com.infinifolder.mime.MimeRegistry.normalize(metadata.mimeType) in animationMimeTypes ||
                extension(metadata.name) in decodableExtensions)
}
