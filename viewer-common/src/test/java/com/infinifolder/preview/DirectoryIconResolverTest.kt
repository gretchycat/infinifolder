package com.infinifolder.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryIconResolverTest {
    private data class FileItem(val name: String, val isImage: Boolean)

    @Test
    fun matchesIconAndCoverNames() {
        assertTrue(DirectoryIconResolver.isDirectoryIconFileName(".icon.png"))
        assertTrue(DirectoryIconResolver.isDirectoryIconFileName(".ICON.JPG"))
        assertTrue(DirectoryIconResolver.isDirectoryIconFileName(".icon.svg"))
        assertTrue(DirectoryIconResolver.isDirectoryIconFileName("cover.jpg"))
        assertTrue(DirectoryIconResolver.isDirectoryIconFileName("Cover.PNG"))
        assertTrue(DirectoryIconResolver.isDirectoryIconFileName("cover.webp"))

        assertFalse(DirectoryIconResolver.isDirectoryIconFileName("coverage.png"))
        assertFalse(DirectoryIconResolver.isDirectoryIconFileName("album_cover.jpg"))
        assertFalse(DirectoryIconResolver.isDirectoryIconFileName("icon.png"))
        assertFalse(DirectoryIconResolver.isDirectoryIconFileName("my.icon.png"))
    }

    @Test
    fun prioritizesDotIconOverCover() {
        val cover = FileItem("cover.jpg", true)
        val dotIcon = FileItem(".icon.png", true)

        val best = DirectoryIconResolver.findBest(listOf(cover, dotIcon), { it.name }, { it.isImage })
        assertEquals(dotIcon, best)
    }

    @Test
    fun selectsCoverWhenNoDotIcon() {
        val cover = FileItem("cover.png", true)
        val other = FileItem("photo.png", true)

        val best = DirectoryIconResolver.findBest(listOf(other, cover), { it.name }, { it.isImage })
        assertEquals(cover, best)
    }

    @Test
    fun ignoresNonImageFiles() {
        val textCover = FileItem("cover.txt", false)
        val other = FileItem("readme.md", false)

        val best = DirectoryIconResolver.findBest(listOf(textCover, other), { it.name }, { it.isImage })
        assertNull(best)
    }

    @Test
    fun identifiesExplicitIconFilesOnly() {
        assertTrue(DirectoryIconResolver.isExplicitIconFileName(".icon.png"))
        assertTrue(DirectoryIconResolver.isExplicitIconFileName(".ICON.JPG"))
        assertTrue(DirectoryIconResolver.isExplicitIconFileName(".icon"))
        assertFalse(DirectoryIconResolver.isExplicitIconFileName("cover.png"))
        assertFalse(DirectoryIconResolver.isExplicitIconFileName("photo.jpg"))
    }

    @Test
    fun resolvesTargetIconFileNameFromDisplayNameAndMime() {
        assertEquals(".icon.webp" to "image/webp", DirectoryIconResolver.targetIconFileName("badge.webp", "image/webp"))
        assertEquals(".icon.jpg" to "image/jpeg", DirectoryIconResolver.targetIconFileName("IMG_001.jpg", null))
        assertEquals(".icon.jpg" to "image/jpeg", DirectoryIconResolver.targetIconFileName("image:1234", "image/jpeg"))
        assertEquals(".icon.svg" to "image/svg+xml", DirectoryIconResolver.targetIconFileName("folder.svg", "image/svg+xml"))
        assertEquals(".icon.png" to "image/png", DirectoryIconResolver.targetIconFileName(null, null))
    }
}
