package com.infinifolder.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SymbolicLinkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSymlinkToDirectoryDetectionAndFollowing() {
        val root = tempFolder.newFolder("root")
        val targetDir = File(root, "target_dir").apply { mkdir() }
        File(targetDir, "nested.txt").writeText("content inside target")

        val linkDir = File(root, "symlink_dir")
        Files.createSymbolicLink(linkDir.toPath(), targetDir.toPath())

        assertTrue("Files.isSymbolicLink should be true", Files.isSymbolicLink(linkDir.toPath()))
        val canonical = linkDir.canonicalFile
        assertTrue("Canonical target should be directory", canonical.isDirectory)
        assertEquals(targetDir.canonicalPath, canonical.canonicalPath)

        val targetFile = File(canonical, "nested.txt")
        assertTrue("Nested file should exist via canonical", targetFile.exists())
        assertEquals("content inside target", targetFile.readText())
    }

    @Test
    fun testSymlinkToFileDetection() {
        val root = tempFolder.newFolder("root")
        val targetFile = File(root, "target.png").apply { writeBytes(ByteArray(10)) }
        val linkFile = File(root, "link_to_image")
        Files.createSymbolicLink(linkFile.toPath(), targetFile.toPath())

        assertTrue(Files.isSymbolicLink(linkFile.toPath()))
        val canonical = linkFile.canonicalFile
        assertFalse(canonical.isDirectory)
        assertTrue(canonical.exists())
        assertEquals("target.png", canonical.name)
    }

    @Test
    fun testBrokenSymlinkDetection() {
        val root = tempFolder.newFolder("root")
        val nonExistent = File(root, "deleted.txt")
        val linkFile = File(root, "broken_link")
        Files.createSymbolicLink(linkFile.toPath(), nonExistent.toPath())

        assertTrue(Files.isSymbolicLink(linkFile.toPath()))
        val canonical = linkFile.canonicalFile
        assertFalse("Broken link target should not exist", canonical.exists())
    }
}
