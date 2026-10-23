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
        assertEquals("iso", ArchiveFormats.archiveExtension("ubuntu.iso"))
        assertEquals("iso.gz", ArchiveFormats.archiveExtension("ubuntu.iso.gz"))
        assertEquals("iso.bz2", ArchiveFormats.archiveExtension("ubuntu.iso.bz2"))
        assertEquals("ext4.bz2", ArchiveFormats.archiveExtension("rootfs.ext4.bz2"))
    }

    @Test
    fun testPackagedFormats() {
        // Packaged formats are archives under the hood...
        assertTrue(ArchiveFormats.isArchive("game.love"))
        assertTrue(ArchiveFormats.isArchive("app.apk"))
        assertTrue(ArchiveFormats.isArchive("bundle.xapk"))
        assertTrue(ArchiveFormats.isArchive("library.jar"))
        assertTrue(ArchiveFormats.isArchive("server.war"))
        assertTrue(ArchiveFormats.isArchive("enterprise.ear"))
        assertTrue(ArchiveFormats.isArchive("ios.ipa"))

        // ...but they are packaged formats, NOT default browsable archives
        assertTrue(ArchiveFormats.isPackagedFormat("game.love"))
        assertTrue(ArchiveFormats.isPackagedFormat("app.apk"))
        assertTrue(ArchiveFormats.isPackagedFormat("bundle.xapk"))
        assertTrue(ArchiveFormats.isPackagedFormat("library.jar"))
        assertTrue(ArchiveFormats.isPackagedFormat("server.war"))
        assertTrue(ArchiveFormats.isPackagedFormat("enterprise.ear"))
        assertTrue(ArchiveFormats.isPackagedFormat("ios.ipa"))

        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("game.love"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("app.apk"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("bundle.xapk"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("library.jar"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("server.war"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("enterprise.ear"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("ios.ipa"))

        // Generic archives are default browsable
        assertTrue(ArchiveFormats.isDefaultBrowsableArchive("backup.zip"))
        assertTrue(ArchiveFormats.isDefaultBrowsableArchive("files.tar.gz"))
        assertTrue(ArchiveFormats.isDefaultBrowsableArchive("archive.7z"))
        assertFalse(ArchiveFormats.isPackagedFormat("backup.zip"))
        assertFalse(ArchiveFormats.isPackagedFormat("files.tar.gz"))
        assertFalse(ArchiveFormats.isPackagedFormat("archive.7z"))

        // MIME-based packaged format checks
        assertTrue(ArchiveFormats.isPackagedFormat("unknown", "application/vnd.android.package-archive"))
        assertTrue(ArchiveFormats.isPackagedFormat("unknown", "application/java-archive"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("unknown", "application/vnd.android.package-archive"))
        assertFalse(ArchiveFormats.isDefaultBrowsableArchive("unknown", "application/java-archive"))

        // Package MIME resolution
        assertEquals("application/vnd.android.package-archive", ArchiveFormats.packageMimeType("app.apk"))
        assertEquals("application/x-xapk", ArchiveFormats.packageMimeType("game.xapk"))
        assertEquals("application/java-archive", ArchiveFormats.packageMimeType("library.jar"))
        assertEquals("application/x-love-game", ArchiveFormats.packageMimeType("game.love"))
        assertNull(ArchiveFormats.packageMimeType("backup.zip"))

        // ArchiveMimeTypes resolution
        assertEquals("application/vnd.android.package-archive", ArchiveMimeTypes.guessMimeType("app.apk", false))
        assertEquals("application/java-archive", ArchiveMimeTypes.guessMimeType("library.jar", false))
        assertEquals("application/x-love-game", ArchiveMimeTypes.guessMimeType("game.love", false))
        assertEquals("application/zip", ArchiveMimeTypes.guessMimeType("backup.zip", false))
    }

    @Test
    fun testDiscImageFormats() {
        assertTrue(ArchiveFormats.isArchive("livecd.iso"))
        assertTrue(ArchiveFormats.isDiscImage("livecd.iso"))
        assertTrue(ArchiveFormats.isDiscImage("livecd.iso.gz"))
        assertTrue(ArchiveFormats.isDiscImage("livecd.iso.bz2"))
        assertTrue(ArchiveFormats.isDiscImage("floppy.vfat.gz"))
        assertTrue(ArchiveFormats.isDiscImage("sdcard.exfat.bz2"))
        assertTrue(ArchiveFormats.isDiscImage("system.ext2"))
        assertTrue(ArchiveFormats.isDiscImage("system.ext3.gz"))
        assertTrue(ArchiveFormats.isDiscImage("system.ext4.bz2"))
        assertTrue(ArchiveFormats.isDiscImage("disk.img.bz2"))
    }
}
