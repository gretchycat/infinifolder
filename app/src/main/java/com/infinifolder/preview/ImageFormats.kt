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
    /** Extensions for all supported decodable image formats. */
    val decodableExtensions: Set<String> = setOf(
        // JPEG (including Ultra HDR / JPEG_R) and PNG (including animated PNG still frames)
        "jpg", "jpeg", "jpe", "jfif", "pjpeg", "pjp", "png", "apng",
        // GIF, WebP, BMP, WBMP, ICO/CUR
        "gif", "webp", "bmp", "dib", "wbmp", "ico", "cur",
        // HEIF/HEIC and AVIF, including image sequences
        "heic", "heif", "heics", "heifs", "hif", "avif", "avifs",
        // DNG and camera RAW formats
        "dng", "cr2", "nef", "nrw", "arw", "rw2", "orf", "raf", "pef", "srw",
        // Vector formats (AndroidSVG)
        "svg", "svgz",
        // JPEG XL
        "jxl",
        // Truevision TGA
        "tga", "icb", "vda", "vst"
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImage(metadata: ResourceMetadata): Boolean =
        !metadata.isDirectory &&
            (metadata.mimeType.lowercase().startsWith("image/") || extension(metadata.name) in decodableExtensions)
}
