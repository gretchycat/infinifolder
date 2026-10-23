package com.infinifolder.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FreeDesktopThemeTest {

    @Test
    fun testParseBreezeThemeIfAvailable() {
        val breezeDir = File("/data/data/com.termux/files/usr/share/icons/breeze")
        if (!breezeDir.isDirectory) return

        val indexFile = File(breezeDir, "index.theme")
        assertTrue(indexFile.isFile)

        val info = FreeDesktopThemeParser.parse(indexFile, breezeDir, isSystem = true)
        assertNotNull(info)
        assertEquals("Breeze", info!!.name)
        assertTrue(info.inherits.contains("hicolor"))
        assertTrue(info.directories.isNotEmpty())

        val theme = FreeDesktopIconTheme(info)
        val folderIcon = theme.findIconFile("folder", 32)
        assertNotNull("Folder icon should be found in Breeze", folderIcon)
        assertTrue(folderIcon!!.name.startsWith("folder"))
    }

    @Test
    fun testParseAdwaitaThemeIfAvailable() {
        val adwaitaDir = File("/data/data/com.termux/files/usr/share/icons/Adwaita")
        if (!adwaitaDir.isDirectory) return

        val indexFile = File(adwaitaDir, "index.theme")
        assertTrue(indexFile.isFile)

        val info = FreeDesktopThemeParser.parse(indexFile, adwaitaDir, isSystem = true)
        assertNotNull(info)
        assertEquals("Adwaita", info!!.name)
        assertTrue(info.directories.isNotEmpty())
    }

    @Test
    fun testParseSyntheticTheme() {
        val tempDir = File.createTempFile("synth_theme", "").apply { delete(); mkdirs() }
        try {
            val indexTheme = File(tempDir, "index.theme")
            indexTheme.writeText("""
                [Icon Theme]
                Name=SyntheticTest
                Comment=A synthetic test theme
                Inherits=hicolor,Adwaita
                Directories=scalable/mimetypes,48x48/places

                [scalable/mimetypes]
                Size=48
                MinSize=16
                MaxSize=256
                Type=Scalable
                Context=MimeTypes

                [48x48/places]
                Size=48
                Type=Fixed
                Context=Places
            """.trimIndent())

            val info = FreeDesktopThemeParser.parse(indexTheme, tempDir, isSystem = false)
            assertNotNull(info)
            assertEquals("SyntheticTest", info!!.name)
            assertEquals("A synthetic test theme", info.comment)
            assertEquals(listOf("hicolor", "Adwaita"), info.inherits)
            assertEquals(2, info.directories.size)

            val placesDir = info.directories.first { it.name == "48x48/places" }
            assertEquals(48, placesDir.size)
            assertEquals(DirectoryType.Fixed, placesDir.type)

            val mimeDir = info.directories.first { it.name == "scalable/mimetypes" }
            assertEquals(DirectoryType.Scalable, mimeDir.type)
            assertEquals(16, mimeDir.minSize)
            assertEquals(256, mimeDir.maxSize)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFolderCandidateOrder() {
        val candidates = IconThemeManager.buildFolderIconCandidates("Downloads")
        assertTrue(candidates.contains("folder-download") || candidates.contains("folder-downloads"))
        assertTrue(candidates.contains("folder"))
        assertEquals("folder-download", candidates.first())

        val genericCandidates = IconThemeManager.buildFolderIconCandidates("RandomFolder")
        assertEquals(listOf("folder", "inode-directory"), genericCandidates)
    }

    @Test
    fun testMimeCandidateResolution() {
        val candidates = IconThemeManager.buildMimeIconCandidates(
            com.infinifolder.model.ResourceMetadata("document.pdf", "application/pdf", null, null, false)
        )
        assertTrue(candidates.contains("application-pdf"))
    }
}
