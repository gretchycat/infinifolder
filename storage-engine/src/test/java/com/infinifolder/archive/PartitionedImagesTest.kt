package com.infinifolder.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Integration checks against the generated full disks, including fresh extraction handles. */
class PartitionedImagesTest {
    @get:Rule val temp = TemporaryFolder()
    private val expected = "Infinifolder fixture: browsing, extraction, copying and deletion.\n".toByteArray()
    private fun fixture(name: String): File = File("../filesystem-test-images/disks", name).also {
        check(it.isFile) { "Generate partitioned fixtures first: $it" }
    }
    private fun hash(file: File): ByteArray = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest()
    }
    private fun checkDisk(name: String, partitions: Int, minimumReadmes: Int) {
        val file = fixture(name)
        val before = hash(file)
        val reader = DiscImageReader(temp.newFolder())
        val entries = FileBlockDevice(RandomAccessFile(file, "r"), true, ownsFile = true).use { device ->
            reader.readEntries(file.name, { file.inputStream() }, file, device)
        }
        assertEquals(partitions, entries.count { it.isDirectory && '/' !in it.path })
        val readmes = entries.filter { it.path.endsWith("/README.txt") }
        assertTrue("Expected populated filesystems in $name", readmes.size >= minimumReadmes)
        for (entry in readmes) {
            FileBlockDevice(RandomAccessFile(file, "r"), true, ownsFile = true).use { fresh ->
                val data = reader.openEntryStream(file.name, entry.path, { file.inputStream() }, file, fresh).use { it.readBytes() }
                assertArrayEquals(entry.path, expected, data)
            }
        }
        assertArrayEquals("Browsing/extraction must leave source unchanged", before, hash(file))
    }
    @Test fun mbrWindowsVolumes() = checkDisk("mbr-windows.img", 2, 1)
    @Test fun gptMixedFilesystems() = checkDisk("gpt-mixed.img", 3, 2)
    @Test fun applePartitionMap() = checkDisk("apm-apple.img", 2, 2)
    @Test fun compressedFullDisk() = checkDisk("mbr-windows.img.gz", 2, 1)
}
