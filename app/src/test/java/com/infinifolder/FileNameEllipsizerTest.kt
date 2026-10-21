package com.infinifolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameEllipsizerTest {

    // Monospace measurement stub: 10px per character
    private val monoMeasure: (String) -> Float = { it.length * 10f }

    @Test
    fun testFitsWithoutTruncation() {
        val result = FileNameEllipsizer.ellipsize("notes.txt", false, 120f, monoMeasure)
        assertEquals("notes.txt", result)
    }

    @Test
    fun testSimpleExtensionTruncation() {
        // 140px = 14 chars max -> "very_long_…pdf" (10 chars stem + 1 ellipsis + 3 ext = 14 chars)
        val result = FileNameEllipsizer.ellipsize(
            fullName = "very_long_document_name.pdf",
            isDirectory = false,
            maxWidthPx = 140f,
            measureText = monoMeasure
        )
        assertEquals("very_long_…pdf", result)
        assertTrue(monoMeasure(result) <= 140f)
    }

    @Test
    fun testCompoundDiscAndArchiveExtensionTruncation() {
        // Compound extension "iso.bz2" (7 chars) + "…" (1 char) = 8 chars tail
        // With 160px (16 chars max), stem gets 8 chars: "ubuntu-2…iso.bz2"
        val result = FileNameEllipsizer.ellipsize(
            fullName = "ubuntu-24.04.1-desktop-amd64.iso.bz2",
            isDirectory = false,
            maxWidthPx = 160f,
            measureText = monoMeasure
        )
        assertEquals("ubuntu-2…iso.bz2", result)
        assertTrue(monoMeasure(result) <= 160f)
    }

    @Test
    fun testShortcutPreservesExtensionAndBadge() {
        val result = FileNameEllipsizer.ellipsize(
            fullName = "super_long_backup_archive.tar.gz ↗",
            isDirectory = false,
            maxWidthPx = 180f,
            measureText = monoMeasure
        )
        assertEquals("super_lon…tar.gz ↗", result)
        assertTrue(monoMeasure(result) <= 180f)
    }

    @Test
    fun testDirectoryIgnoresDots() {
        val result = FileNameEllipsizer.ellipsize(
            fullName = "Project.v2.Final.Folder.Name",
            isDirectory = true,
            maxWidthPx = 120f,
            measureText = monoMeasure
        )
        assertEquals("Project.v2.…", result)
        assertTrue(monoMeasure(result) <= 120f)
    }
}
