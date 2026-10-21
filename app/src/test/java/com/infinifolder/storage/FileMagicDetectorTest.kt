package com.infinifolder.storage

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

class FileMagicDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAmbiguityDetection() {
        assertTrue(FileMagicDetector.isAmbiguousOrUnknown("mystery_file", "application/octet-stream"))
        assertTrue(FileMagicDetector.isAmbiguousOrUnknown("data.bin", "application/octet-stream"))
        assertTrue(FileMagicDetector.isAmbiguousOrUnknown("dump.dat", "application/octet-stream"))
        assertTrue(FileMagicDetector.isAmbiguousOrUnknown("disk.raw", "application/octet-stream"))
        assertTrue(FileMagicDetector.isAmbiguousOrUnknown("archive.gz", "application/gzip"))
        assertFalse(FileMagicDetector.isAmbiguousOrUnknown("photo.png", "image/png"))
        assertFalse(FileMagicDetector.isAmbiguousOrUnknown("backup.tar.gz", "application/gzip"))
        assertFalse(FileMagicDetector.isAmbiguousOrUnknown("ubuntu.iso.bz2", "application/x-bzip2"))
    }

    @Test
    fun testImageMagicDetection() {
        val pngFile = tempFolder.newFile("test_png.bin")
        pngFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13))
        val pngResult = FileMagicDetector.detect(pngFile)
        assertNotNull(pngResult)
        assertEquals("image/png", pngResult!!.mimeType)
        assertEquals("PNG Image", pngResult.description)

        val jpegFile = tempFolder.newFile("test_jpeg.dat")
        jpegFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16))
        val jpegResult = FileMagicDetector.detect(jpegFile)
        assertNotNull(jpegResult)
        assertEquals("image/jpeg", jpegResult!!.mimeType)
        assertEquals("JPEG Image", jpegResult.description)

        val gifFile = tempFolder.newFile("test_gif")
        gifFile.writeBytes("GIF89a\u0001\u0000\u0001\u0000".toByteArray(StandardCharsets.ISO_8859_1))
        val gifResult = FileMagicDetector.detect(gifFile)
        assertNotNull(gifResult)
        assertEquals("image/gif", gifResult!!.mimeType)
    }

    @Test
    fun testDocumentAndArchiveMagicDetection() {
        val pdfFile = tempFolder.newFile("document_no_ext")
        pdfFile.writeBytes("%PDF-1.7\n%test\n".toByteArray(StandardCharsets.US_ASCII))
        val pdfResult = FileMagicDetector.detect(pdfFile)
        assertNotNull(pdfResult)
        assertEquals("application/pdf", pdfResult!!.mimeType)

        val zipFile = tempFolder.newFile("archive_renamed.dat")
        zipFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 20, 0, 0, 0))
        val zipResult = FileMagicDetector.detect(zipFile)
        assertNotNull(zipResult)
        assertEquals("application/zip", zipResult!!.mimeType)
        assertTrue(zipResult.isArchiveOrDiscImage)

        val szFile = tempFolder.newFile("backup.bin")
        szFile.writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))
        val szResult = FileMagicDetector.detect(szFile)
        assertNotNull(szResult)
        assertEquals("application/x-7z-compressed", szResult!!.mimeType)
        assertTrue(szResult.isArchiveOrDiscImage)
    }

    @Test
    fun testDiscImageMagicDetection() {
        val ext4File = tempFolder.newFile("rootfs.bin")
        val ext4Bytes = ByteArray(2048)
        // 0xEF53 at offset 1080
        ext4Bytes[1080] = 0x53.toByte()
        ext4Bytes[1081] = 0xEF.toByte()
        ext4File.writeBytes(ext4Bytes)
        val ext4Result = FileMagicDetector.detect(ext4File)
        assertNotNull(ext4Result)
        assertEquals("application/x-raw-disk-image", ext4Result!!.mimeType)
        assertEquals("ext2/ext3/ext4 Filesystem Image", ext4Result.description)
        assertTrue(ext4Result.isArchiveOrDiscImage)

        val isoFile = tempFolder.newFile("optical.raw")
        val isoBytes = ByteArray(32768 + 2048)
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, isoBytes, 32769, 5)
        isoFile.writeBytes(isoBytes)
        val isoResult = FileMagicDetector.detect(isoFile)
        assertNotNull(isoResult)
        assertEquals("application/x-iso9660-image", isoResult!!.mimeType)
        assertEquals("ISO 9660 Disc Image", isoResult.description)
        assertTrue(isoResult.isArchiveOrDiscImage)
    }

    @Test
    fun testCompressedPayloadPeek() {
        // A tar archive compressed with gzip, but named generic "archive.gz"
        val tarBytes = ByteArray(1024)
        System.arraycopy("ustar".toByteArray(StandardCharsets.US_ASCII), 0, tarBytes, 257, 5)

        val gzFile = tempFolder.newFile("archive.gz")
        gzFile.outputStream().use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                gzos.write(tarBytes)
            }
        }

        val peekResult = FileMagicDetector.detect(gzFile, peekCompressedPayload = true)
        assertNotNull(peekResult)
        assertEquals("application/x-tar", peekResult!!.mimeType)
        assertEquals("Gzip-compressed POSIX TAR Archive", peekResult.description)
        assertTrue(peekResult.isArchiveOrDiscImage)
    }

    @Test
    fun testTextScriptDetection() {
        val scriptFile = tempFolder.newFile("build")
        scriptFile.writeBytes("#!/bin/bash\necho 'Compiling project...'\n".toByteArray(StandardCharsets.UTF_8))
        val scriptResult = FileMagicDetector.detect(scriptFile)
        assertNotNull(scriptResult)
        assertEquals("text/plain", scriptResult!!.mimeType)
        assertTrue(scriptResult.description.contains("Executable Script"))

        val readmeFile = tempFolder.newFile("README")
        readmeFile.writeBytes("InfiniFolder is an advanced Android file manager.\n".toByteArray(StandardCharsets.UTF_8))
        val readmeResult = FileMagicDetector.detect(readmeFile)
        assertNotNull(readmeResult)
        assertEquals("text/plain", readmeResult!!.mimeType)
        assertEquals("UTF-8 Plain Text", readmeResult.description)
    }
}
