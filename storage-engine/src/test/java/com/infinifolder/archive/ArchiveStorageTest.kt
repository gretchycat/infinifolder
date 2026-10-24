package com.infinifolder.archive

import android.content.Context
import android.net.TestUri
import android.net.Uri
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveStorageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testZipVirtualTreeAndExtraction() {
        val zipFile = tempFolder.newFile("sample.zip")
        ZipArchiveOutputStream(zipFile).use { zos ->
            // Entry 1: Root file
            val e1 = ZipArchiveEntry("root_file.txt")
            val c1 = "Root file content".toByteArray()
            e1.size = c1.size.toLong()
            zos.putArchiveEntry(e1)
            zos.write(c1)
            zos.closeArchiveEntry()

            // Entry 2: File in deep subdirectory without explicit folder entries
            val e2 = ZipArchiveEntry("sub1/sub2/nested.txt")
            val c2 = "Nested file content".toByteArray()
            e2.size = c2.size.toLong()
            zos.putArchiveEntry(e2)
            zos.write(c2)
            zos.closeArchiveEntry()
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp"))
        val tree = reader.buildTree(null, zipFile.name, { zipFile.inputStream() }, zipFile)

        // Verify root listing contains "root_file.txt" and synthetic folder "sub1"
        val rootEntries = tree.list("")
        assertEquals(2, rootEntries.size)
        val sub1Dir = rootEntries.firstOrNull { it.name == "sub1" }
        assertNotNull(sub1Dir)
        assertTrue(sub1Dir!!.isDirectory)
        assertEquals("sub1", sub1Dir.path)

        val rootFile = rootEntries.firstOrNull { it.name == "root_file.txt" }
        assertNotNull(rootFile)
        assertFalse(rootFile!!.isDirectory)

        // Verify "sub1" contains synthetic "sub2"
        val sub1Entries = tree.list("sub1")
        assertEquals(1, sub1Entries.size)
        val sub2Dir = sub1Entries.first()
        assertTrue(sub2Dir.isDirectory)
        assertEquals("sub2", sub2Dir.name)
        assertEquals("sub1/sub2", sub2Dir.path)

        // Verify "sub1/sub2" contains "nested.txt"
        val sub2Entries = tree.list("sub1/sub2")
        assertEquals(1, sub2Entries.size)
        val nestedFile = sub2Entries.first()
        assertFalse(nestedFile.isDirectory)
        assertEquals("nested.txt", nestedFile.name)
        assertEquals("sub1/sub2/nested.txt", nestedFile.path)

        // Verify extraction to cache
        val cacheTarget = File(tempFolder.newFolder("cache"), "extracted_nested.txt")
        val extracted = reader.extractEntry(zipFile.name, "sub1/sub2/nested.txt", cacheTarget, { zipFile.inputStream() }, zipFile)
        assertTrue(extracted.exists())
        assertEquals("Nested file content", extracted.readText())
    }

    @Test
    fun testTarGzVirtualTreeAndExtraction() {
        val tarGzFile = tempFolder.newFile("sample.tar.gz")
        GzipCompressorOutputStream(tarGzFile.outputStream()).use { gzos ->
            TarArchiveOutputStream(gzos).use { tos ->
                val c1 = "Tar Gz Data".toByteArray()
                val e1 = TarArchiveEntry("folder/data.bin").apply { size = c1.size.toLong() }
                tos.putArchiveEntry(e1)
                tos.write(c1)
                tos.closeArchiveEntry()
            }
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_targz"))
        val tree = reader.buildTree(null, tarGzFile.name, { tarGzFile.inputStream() }, tarGzFile)

        val rootEntries = tree.list("")
        assertEquals(1, rootEntries.size)
        val folder = rootEntries.first()
        assertTrue(folder.isDirectory)
        assertEquals("folder", folder.name)

        val folderEntries = tree.list("folder")
        assertEquals(1, folderEntries.size)
        val fileEntry = folderEntries.first()
        assertFalse(fileEntry.isDirectory)
        assertEquals("data.bin", fileEntry.name)

        val cacheTarget = File(tempFolder.newFolder("cache_targz"), "extracted.bin")
        val extracted = reader.extractEntry(tarGzFile.name, "folder/data.bin", cacheTarget, { tarGzFile.inputStream() }, tarGzFile)
        assertTrue(extracted.exists())
        assertEquals("Tar Gz Data", extracted.readText())
    }

    @Test
    fun test7zVirtualTreeAndExtraction() {
        val szFile = tempFolder.newFile("sample.7z")
        SevenZOutputFile(szFile).use { szOut ->
            val c1 = "7z Hello World".toByteArray()
            val entry = szOut.createArchiveEntry(File("dummy.txt"), "hello/world.txt")
            szOut.putArchiveEntry(entry)
            szOut.write(c1)
            szOut.closeArchiveEntry()
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_7z"))
        val tree = reader.buildTree(null, szFile.name, { szFile.inputStream() }, szFile)

        val rootEntries = tree.list("")
        assertEquals(1, rootEntries.size)
        val helloDir = rootEntries.first()
        assertTrue(helloDir.isDirectory)
        assertEquals("hello", helloDir.name)

        val helloEntries = tree.list("hello")
        assertEquals(1, helloEntries.size)
        val worldFile = helloEntries.first()
        assertEquals("world.txt", worldFile.name)

        val cacheTarget = File(tempFolder.newFolder("cache_7z"), "world.txt")
        val extracted = reader.extractEntry(szFile.name, "hello/world.txt", cacheTarget, { szFile.inputStream() }, szFile)
        assertTrue(extracted.exists())
        assertEquals("7z Hello World", extracted.readText())
    }

    @Test
    fun testTarBz2AndTarXzExtraction() {
        val bz2File = tempFolder.newFile("sample.tar.bz2")
        org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bz2File.outputStream()).use { bzos ->
            TarArchiveOutputStream(bzos).use { tos ->
                val c = "Bzip2 Tar".toByteArray()
                val e = TarArchiveEntry("bz2_folder/test.txt").apply { size = c.size.toLong() }
                tos.putArchiveEntry(e)
                tos.write(c)
                tos.closeArchiveEntry()
            }
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_bz2"))
        val tree = reader.buildTree(null, bz2File.name, { bz2File.inputStream() }, bz2File)
        assertEquals(1, tree.list("").size)
        assertEquals("bz2_folder", tree.list("").first().name)
        val extractedBz2 = reader.extractEntry(bz2File.name, "bz2_folder/test.txt", File(tempFolder.newFolder("out_bz2"), "test.txt"), { bz2File.inputStream() }, bz2File)
        assertEquals("Bzip2 Tar", extractedBz2.readText())

        val xzFile = tempFolder.newFile("sample.tar.xz")
        org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(xzFile.outputStream()).use { xzos ->
            TarArchiveOutputStream(xzos).use { tos ->
                val c = "XZ Tar".toByteArray()
                val e = TarArchiveEntry("xz_folder/test.txt").apply { size = c.size.toLong() }
                tos.putArchiveEntry(e)
                tos.write(c)
                tos.closeArchiveEntry()
            }
        }
        val treeXz = reader.buildTree(null, xzFile.name, { xzFile.inputStream() }, xzFile)
        assertEquals(1, treeXz.list("").size)
        val extractedXz = reader.extractEntry(xzFile.name, "xz_folder/test.txt", File(tempFolder.newFolder("out_xz"), "test.txt"), { xzFile.inputStream() }, xzFile)
        assertEquals("XZ Tar", extractedXz.readText())
    }

    @Test
    fun testWindowsBackslashNormalization() {
        val zipFile = tempFolder.newFile("windows.zip")
        ZipArchiveOutputStream(zipFile).use { zos ->
            val e = ZipArchiveEntry("win\\folder\\sub\\file.txt")
            val c = "Windows path".toByteArray()
            e.size = c.size.toLong()
            zos.putArchiveEntry(e)
            zos.write(c)
            zos.closeArchiveEntry()
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_win"))
        val tree = reader.buildTree(null, zipFile.name, { zipFile.inputStream() }, zipFile)

        val root = tree.list("")
        assertEquals(1, root.size)
        assertEquals("win", root.first().name)
        assertTrue(root.first().isDirectory)

        val winChildren = tree.list("win")
        assertEquals(1, winChildren.size)
        assertEquals("folder", winChildren.first().name)

        val subChildren = tree.list("win/folder/sub")
        assertEquals(1, subChildren.size)
        assertEquals("file.txt", subChildren.first().name)
        assertFalse(subChildren.first().isDirectory)

        val extracted = reader.extractEntry(zipFile.name, "win/folder/sub/file.txt", File(tempFolder.newFolder("out_win"), "file.txt"), { zipFile.inputStream() }, zipFile)
        assertEquals("Windows path", extracted.readText())
    }

    @Test
    fun testCacheDirectoryStructuredFromVirtualRoot() {
        val zipFile = tempFolder.newFile("my_archive.zip")
        ZipArchiveOutputStream(zipFile).use { zos ->
            val e = ZipArchiveEntry("docs/guide.txt")
            val c = "Guide documentation".toByteArray()
            e.size = c.size.toLong()
            zos.putArchiveEntry(e)
            zos.write(c)
            zos.closeArchiveEntry()
        }

        val cacheBase = File(tempFolder.root, "infinifolder/cache")
        assertFalse(cacheBase.exists())

        // Verify cache directory creation
        if (!cacheBase.exists()) {
            cacheBase.mkdirs()
        }
        assertTrue(cacheBase.exists())

        // Virtual path structured as if from virtual root
        val virtualPath = "Internal shared storage/Downloads/my_archive.zip/docs/guide.txt"
        val targetFile = File(cacheBase, virtualPath)

        val reader = ArchiveReader(tempFolder.newFolder("temp_cache_test"))
        val extracted = reader.extractEntry(zipFile.name, "docs/guide.txt", targetFile, { zipFile.inputStream() }, zipFile)

        assertTrue(extracted.exists())
        assertEquals("Guide documentation", extracted.readText())

        val expectedLocation = File(cacheBase, "Internal shared storage/Downloads/my_archive.zip/docs/guide.txt")
        assertEquals(expectedLocation.canonicalPath, extracted.canonicalPath)
    }

    @Test
    fun testCaseInsensitiveMatchingAndSingleFileExtraction() {
        val zipFile = tempFolder.newFile("mixed_case.zip")
        ZipArchiveOutputStream(zipFile).use { zos ->
            // Entry 1: Mixed case file
            val e1 = ZipArchiveEntry("SubFolder/MyFile.TXT")
            val c1 = "Case sensitivity test".toByteArray()
            e1.size = c1.size.toLong()
            zos.putArchiveEntry(e1)
            zos.write(c1)
            zos.closeArchiveEntry()

            // Entry 2: Another file that should NOT be extracted
            val e2 = ZipArchiveEntry("SubFolder/OtherFile.BIN")
            val c2 = "Other file content".toByteArray()
            e2.size = c2.size.toLong()
            zos.putArchiveEntry(e2)
            zos.write(c2)
            zos.closeArchiveEntry()
        }

        val cacheBase = tempFolder.newFolder("test_cache")
        val reader = ArchiveReader(tempFolder.newFolder("temp_reader"))

        // Test openEntryStream reads directly in-memory without writing to cacheBase
        val inMemoryStream = reader.openEntryStream(zipFile.name, "subfolder/myfile.txt", { zipFile.inputStream() }, zipFile)
        val readInMemory = inMemoryStream.bufferedReader().readText()
        assertEquals("Case sensitivity test", readInMemory)
        // Ensure nothing was extracted to cacheBase
        assertEquals(0, cacheBase.listFiles()?.size ?: 0)

        // Test extractEntry only extracts the requested file, even with lowercase query
        val targetFile = File(cacheBase, "out/MyFile.TXT")
        val extracted = reader.extractEntry(zipFile.name, "subfolder/myfile.txt", targetFile, { zipFile.inputStream() }, zipFile)
        assertTrue(extracted.exists())
        assertEquals("Case sensitivity test", extracted.readText())

        // Verify OtherFile.BIN was NEVER extracted
        assertFalse(File(cacheBase, "out/OtherFile.BIN").exists())
    }

    private class TestContext(private val cache: File) : android.content.ContextWrapper(null) {
        override fun getCacheDir(): File = cache
    }

    private fun createMockContext(): Context {
        return TestContext(tempFolder.newFolder("context_cache"))
    }

    @Test
    fun testSftpStreamResolverForRemoteZip() {
        val zipFile = tempFolder.newFile("remote.zip")
        ZipArchiveOutputStream(zipFile).use { zos ->
            val e = ZipArchiveEntry("server/config.json")
            val c = "{\"port\": 22}".toByteArray()
            e.size = c.size.toLong()
            zos.putArchiveEntry(e)
            zos.write(c)
            zos.closeArchiveEntry()
        }

        val provider = ArchiveStorageProvider(
            context = createMockContext(),
            customCacheDir = tempFolder.newFolder("archive_provider_cache")
        )
        val sftpUri = TestUri("sftp://my-server-uuid/home/user/remote.zip")

        provider.streamResolver = { uri ->
            if (uri.toString() == sftpUri.toString()) zipFile.inputStream() else null
        }
        provider.metadataResolver = { uri ->
            if (uri.toString() == sftpUri.toString()) Pair(zipFile.length(), zipFile.lastModified()) else null
        }

        val tree = provider.getArchiveTree(sftpUri)
        val rootEntries = tree.list("")
        assertEquals(1, rootEntries.size)
        assertEquals("server", rootEntries.first().name)

        val serverEntries = tree.list("server")
        assertEquals(1, serverEntries.size)
        assertEquals("config.json", serverEntries.first().name)
    }

    @Test
    fun testLargeRemoteDiscImageSizeLimit() {
        val provider = ArchiveStorageProvider(
            context = createMockContext(),
            customCacheDir = tempFolder.newFolder("archive_provider_disc_cache")
        )
        val sftpVdiUri = TestUri("sftp://94e3778c-140e-43a5-bc2e-b1c18f76f6d2/home/gretchen/VirtualBox VMs/echoesbbs/echoesbbs-u.vdi")

        provider.streamResolver = { uri ->
            java.io.ByteArrayInputStream(ByteArray(0))
        }
        provider.metadataResolver = { uri ->
            // 20 GB file
            Pair(20L * 1024L * 1024L * 1024L, 1700000000000L)
        }

        try {
            provider.getArchiveTree(sftpVdiUri)
            fail("Expected IOException due to large remote disk image limit")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("too large to stream over network"))
            assertTrue(e.message!!.contains("echoesbbs-u.vdi"))
        }
    }
    @Test fun exactZipCaseWinsAndAmbiguousFallbackIsRefused() {
        val file = tempFolder.newFile("case-collision.zip")
        ZipArchiveOutputStream(file).use { zip ->
            for (name in listOf("Name.txt", "name.txt")) {
                zip.putArchiveEntry(ZipArchiveEntry(name)); zip.write(name.toByteArray()); zip.closeArchiveEntry()
            }
        }
        val reader = ArchiveReader(tempFolder.newFolder("case-reader"))
        for (name in listOf("Name.txt", "name.txt")) {
            assertEquals(name, reader.openEntryStream(file.name, name, { file.inputStream() }, file).use { String(it.readBytes()) })
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            reader.openEntryStream(file.name, "NAME.TXT", { file.inputStream() }, file).close()
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            reader.extractEntry(file.name, "NAME.TXT", tempFolder.newFile("ambiguous-output"), { file.inputStream() }, file)
        }
    }

}
