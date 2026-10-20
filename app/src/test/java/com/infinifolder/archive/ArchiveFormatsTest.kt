package com.infinifolder.archive

import org.junit.Assert.*
import org.junit.Test

class ArchiveFormatsTest {

    @Test
    fun testZipFormats() {
        assertTrue(ArchiveFormats.isArchive("test.zip"))
        assertTrue(ArchiveFormats.isArchive("test.ZIP"))
        assertTrue(ArchiveFormats.isArchive("app.apk"))
        assertTrue(ArchiveFormats.isArchive("library.jar"))
        assertTrue(ArchiveFormats.isArchive("library.aar"))
        assertTrue(ArchiveFormats.isArchive("comic.cbz"))
    }

    @Test
    fun testTarFormats() {
        assertTrue(ArchiveFormats.isArchive("archive.tar"))
        assertTrue(ArchiveFormats.isArchive("archive.TAR"))
        assertTrue(ArchiveFormats.isArchive("archive.tar.gz"))
        assertTrue(ArchiveFormats.isArchive("archive.tgz"))
        assertTrue(ArchiveFormats.isArchive("archive.tar.bz2"))
        assertTrue(ArchiveFormats.isArchive("archive.tbz2"))
        assertTrue(ArchiveFormats.isArchive("archive.tbz"))
        assertTrue(ArchiveFormats.isArchive("archive.tar.xz"))
        assertTrue(ArchiveFormats.isArchive("archive.txz"))
        assertTrue(ArchiveFormats.isArchive("archive.tar.zst"))
        assertTrue(ArchiveFormats.isArchive("archive.tzst"))
    }

    @Test
    fun test7zAndUnixFormats() {
        assertTrue(ArchiveFormats.isArchive("archive.7z"))
        assertTrue(ArchiveFormats.isArchive("archive.cb7"))
        assertTrue(ArchiveFormats.isArchive("archive.cpio"))
        assertTrue(ArchiveFormats.isArchive("archive.ar"))
        assertTrue(ArchiveFormats.isArchive("package.deb"))
    }

    @Test
    fun testMimeTypes() {
        assertTrue(ArchiveFormats.isArchive("unknown_file", "application/zip"))
        assertTrue(ArchiveFormats.isArchive("unknown_file", "application/x-tar"))
        assertTrue(ArchiveFormats.isArchive("unknown_file", "application/x-7z-compressed"))
        assertTrue(ArchiveFormats.isArchive("unknown_file", "application/x-gzip"))
    }

    @Test
    fun testNonArchives() {
        assertFalse(ArchiveFormats.isArchive("photo.jpg"))
        assertFalse(ArchiveFormats.isArchive("document.pdf"))
        assertFalse(ArchiveFormats.isArchive("video.mp4"))
        assertFalse(ArchiveFormats.isArchive("script.sh"))
        assertFalse(ArchiveFormats.isArchive("notes.txt"))
    }

    @Test
    fun testArchiveExtension() {
        assertEquals("tar.gz", ArchiveFormats.archiveExtension("backup.tar.gz"))
        assertEquals("tar.bz2", ArchiveFormats.archiveExtension("backup.tar.bz2"))
        assertEquals("tar.xz", ArchiveFormats.archiveExtension("backup.tar.xz"))
        assertEquals("zip", ArchiveFormats.archiveExtension("backup.zip"))
        assertEquals("7z", ArchiveFormats.archiveExtension("backup.7z"))
    }
}
