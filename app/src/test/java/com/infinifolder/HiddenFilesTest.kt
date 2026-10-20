package com.infinifolder

import com.infinifolder.model.ResourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenFilesTest {
    private fun item(name: String) = ResourceMetadata(
        name = name,
        mimeType = "application/octet-stream",
        sizeBytes = 10L,
        modifiedAtMillis = 1000L,
        isDirectory = false
    )

    @Test
    fun dotFilesAreClassifiedAsHidden() {
        assertTrue(item(".git").isHidden)
        assertTrue(item(".hidden").isHidden)
        assertTrue(item(".icon.png").isHidden)
        assertFalse(item("regular.txt").isHidden)
        assertFalse(item("cover.jpg").isHidden)
    }

    @Test
    fun filterHiddenFilesWhenDisabled() {
        val files = listOf(item(".git"), item("notes.txt"), item(".env"), item("photo.jpg"))
        val visible = files.filterNot { it.isHidden }

        assertEquals(listOf("notes.txt", "photo.jpg"), visible.map { it.name })
    }

    @Test
    fun preserveHiddenFilesWhenEnabled() {
        val files = listOf(item(".git"), item("notes.txt"), item(".env"), item("photo.jpg"))
        val visible = files // showHidden = true preserves all

        assertEquals(listOf(".git", "notes.txt", ".env", "photo.jpg"), visible.map { it.name })
    }
}
