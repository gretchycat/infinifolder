package com.infinifolder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import java.io.File
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream

/** Valid initial documents, distinct from formats that can only be read. */
object NewFileTemplates {
    data class Template(val label: String, val extension: String, val mime: String)
    val documents = listOf(
        Template("Text", "txt", "text/plain"), Template("Markdown", "md", "text/markdown"),
        Template("CSV", "csv", "text/csv"), Template("HTML", "html", "text/html"),
        Template("JSON", "json", "application/json"), Template("XML", "xml", "application/xml"),
        Template("SVG image", "svg", "image/svg+xml"), Template("PNG image", "png", "image/png"),
        Template("JPEG image", "jpg", "image/jpeg"), Template("WebP image", "webp", "image/webp"),
        Template("PDF document", "pdf", "application/pdf"), Template("EPUB book", "epub", "application/epub+zip")
    )
    val archives = listOf(
        Template("ZIP", "zip", "application/zip"), Template("7z", "7z", "application/x-7z-compressed"),
        Template("TAR", "tar", "application/x-tar"), Template("TAR + gzip", "tar.gz", "application/gzip"),
        Template("TAR + bzip2", "tar.bz2", "application/x-bzip2"), Template("TAR + XZ", "tar.xz", "application/x-xz"),
        Template("TAR + LZMA", "tar.lzma", "application/x-lzma"), Template("TAR + Zstandard", "tar.zst", "application/zstd"),
        Template("CPIO", "cpio", "application/x-cpio"), Template("AR", "ar", "application/x-archive")
    )
    fun write(template: Template, file: File) {
        when (template.extension) {
            "epub" -> ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, content: String, stored: Boolean = false) {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    val entry = java.util.zip.ZipEntry(name)
                    if (stored) {
                        entry.method = java.util.zip.ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = entry.size
                        entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip", true)
                entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                val identifier = java.util.UUID.randomUUID().toString()
                val modified = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
                entry("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:$identifier</dc:identifier><dc:title>Untitled</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="text"/></spine></package>""")
                entry("OEBPS/text.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Untitled</title></head><body><h1>Untitled</h1></body></html>""")
                entry("OEBPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="text.xhtml">Untitled</a></li></ol></nav></body></html>""")
            }
            "zip" -> ZipOutputStream(file.outputStream()).use { }
            "7z" -> SevenZOutputFile(file).use { }
            "cpio" -> CpioArchiveOutputStream(file.outputStream()).use { }
            "ar" -> file.writeText("!<arch>\n", Charsets.US_ASCII)
            "tar", "tar.gz", "tar.bz2", "tar.xz", "tar.lzma", "tar.zst" -> {
                val raw = file.outputStream()
                val output = try { when (template.extension) {
                    "tar.gz" -> GzipCompressorOutputStream(raw)
                    "tar.bz2" -> BZip2CompressorOutputStream(raw)
                    "tar.xz" -> XZCompressorOutputStream(raw)
                    "tar.lzma" -> LZMACompressorOutputStream(raw)
                    "tar.zst" -> ZstdCompressorOutputStream(raw)
                    else -> raw
                } } catch (e: Throwable) { raw.close(); throw e }
                TarArchiveOutputStream(output).use { }
            }
            "png", "jpg", "webp" -> {
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).drawColor(Color.WHITE)
                    val format = when (template.extension) {
                        "jpg" -> Bitmap.CompressFormat.JPEG
                        "webp" -> Bitmap.CompressFormat.WEBP
                        else -> Bitmap.CompressFormat.PNG
                    }
                    file.outputStream().use { check(bitmap.compress(format, 95, it)) { "Image encoding failed" } }
                } finally { bitmap.recycle() }
            }
            "pdf" -> {
                val pdf = PdfDocument()
                try {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                    page.canvas.drawColor(Color.WHITE); pdf.finishPage(page)
                    file.outputStream().use { pdf.writeTo(it) }
                } finally { pdf.close() }
            }
            else -> file.writeText(when (template.extension) {
                "html" -> "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>Untitled</title></head><body>\n</body></html>\n"
                "json" -> "{}\n"
                "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<document/>\n"
                "svg" -> "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\"></svg>\n"
                else -> ""
            })
        }
    }
}
