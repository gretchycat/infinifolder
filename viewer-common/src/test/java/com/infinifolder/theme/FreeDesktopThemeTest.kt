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
    @Test
    fun missingIconsAndCyclicInheritanceFallBackWithoutCrashing() {
        val base = createTempDir(prefix = "icon-cycle-")
        try {
            val aDir = File(base, "a").apply { mkdirs() }
            val bDir = File(base, "b").apply { mkdirs() }
            lateinit var a: FreeDesktopIconTheme
            lateinit var b: FreeDesktopIconTheme
            a = FreeDesktopIconTheme(IconThemeInfo("a", "A", rootDir = aDir, inherits = listOf("b"))) { if (it == "b") b else null }
            b = FreeDesktopIconTheme(IconThemeInfo("b", "B", rootDir = bDir, inherits = listOf("a"))) { if (it == "a") a else null }
            assertEquals(null, a.findIconFile("missing", 32))
            assertEquals(null, a.findIconFile("missing", 32))
            File(bDir, "folder.svg").writeText("<svg/>")
            assertEquals("folder.svg", a.findIconFile("folder", 32)?.name)
        } finally { base.deleteRecursively() }
    }

    @Test
    fun scaledDirectoriesAreParsedAndRankedByPixelSize() {
        val dir = createTempDir(prefix = "icon-scale-")
        try {
            val index = File(dir, "index.theme")
            index.writeText("""
                [Icon Theme]
                Name=Scaled
                Directories=16x16/places
                ScaledDirectories=16x16@2/places
                [16x16/places]
                Size=16
                Type=Fixed
                [16x16@2/places]
                Size=16
                Scale=2
                Type=Fixed
            """.trimIndent())
            val info = FreeDesktopThemeParser.parse(index, dir)!!
            assertEquals(2, info.directories.size)
            assertEquals(0, info.directories.last().sizeDistance(32))
            assertEquals(16, info.directories.first().sizeDistance(32))
            for (d in info.directories) File(dir, d.name).apply { mkdirs(); File(this, "folder.png").writeBytes(byteArrayOf(1)) }
            assertEquals(File(dir, "16x16@2/places/folder.png"), FreeDesktopIconTheme(info).findIconFile("folder", 32))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun bundledDesktopThemesContainUsableFoldersAndMimeIcons() {
        val assets = File("../app/src/main/assets/icon-themes")
        for (id in listOf("Adwaita", "AdwaitaLegacy", "breeze", "breeze-dark", "Papirus")) {
            val archive = File(assets, "$id.zip")
            assertTrue("Missing bundled theme: $id", archive.isFile)
            java.util.zip.ZipFile(archive).use { zip ->
                assertNotNull("Missing index: $id", zip.getEntry("$id/index.theme"))
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue("Missing folder icon: $id", names.any { it.endsWith("/folder.svg") || it.endsWith("/folder.png") })
                assertTrue("Missing generic text icon: $id", names.any { it.endsWith("/text-x-generic.svg") || it.endsWith("/text-x-generic.png") })
                val temp = createTempDir(prefix = "bundled-theme-")
                try {
                    for (entry in zip.entries().asSequence()) {
                        if (entry.name == "$id/index.theme" || entry.name.endsWith("/folder.svg") ||
                            entry.name.endsWith("/folder.png") || entry.name.endsWith("/text-x-generic.svg") ||
                            entry.name.endsWith("/text-x-generic.png")) {
                            val target = File(temp, entry.name)
                            target.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                        }
                    }
                    val root = File(temp, id)
                    val info = FreeDesktopThemeParser.parse(File(root, "index.theme"), root)!!
                    val theme = FreeDesktopIconTheme(info)
                    assertNotNull("Folder not resolvable for $id", theme.findIconFile("folder", 48))
                    assertNotNull("Text icon not resolvable for $id", theme.findIconFile("text-x-generic", 48))
                } finally { temp.deleteRecursively() }
            }
        }
    }

}
