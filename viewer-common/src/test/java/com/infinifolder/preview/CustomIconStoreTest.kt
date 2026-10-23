package com.infinifolder.preview

import com.infinifolder.shortcut.ShortcutInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomIconStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolveMimeKey normalizes mime types and falls back to extension`() {
        assertEquals("text/plain", CustomIconStore.resolveMimeKey("readme.txt", "text/plain"))
        assertEquals("text/plain", CustomIconStore.resolveMimeKey("readme.txt", "TEXT/PLAIN; charset=utf-8"))
        assertEquals("application/pdf", CustomIconStore.resolveMimeKey("manual.PDF", "application/octet-stream"))
        assertEquals("application/octet-stream", CustomIconStore.resolveMimeKey("blob", ""))
    }

    @Test
    fun `setMimeIcon persists and replaces custom icon for mime key`() {
        val store = CustomIconStore(tempFolder.newFolder("mime_icons"))
        val mimeKey = "application/pdf"
        assertFalse(store.hasMimeIcon(mimeKey))
        assertNull(store.getMimeIconFile(mimeKey))

        val firstBytes = byteArrayOf(1, 2, 3, 4)
        val saved1 = store.setMimeIcon(mimeKey, "png", firstBytes)
        assertTrue(saved1.exists())
        assertTrue(store.hasMimeIcon(mimeKey))
        assertArrayEquals(firstBytes, store.getMimeIconFile(mimeKey)?.readBytes())

        val secondBytes = byteArrayOf(9, 8, 7)
        val saved2 = store.setMimeIcon(mimeKey, "webp", secondBytes)
        assertFalse(saved1.exists())
        assertTrue(saved2.exists())
        assertEquals(saved2.absolutePath, store.getMimeIconFile(mimeKey)?.absolutePath)
        assertArrayEquals(secondBytes, store.getMimeIconFile(mimeKey)?.readBytes())
    }

    @Test
    fun `ShortcutInfo serializes and deserializes iconBase64`() {
        val rawBytes = "custom-link-icon-bytes".toByteArray()
        val encoded = CustomIconStore.encodeBytesToBase64(rawBytes)
        val info = ShortcutInfo(
            targetUri = "file:///storage/emulated/0/Documents/report.pdf",
            targetPath = "Documents / report.pdf",
            displayNameOverride = "report",
            targetIsDirectory = false,
            iconBase64 = encoded
        )

        val json = info.toJson()
        val parsed = ShortcutInfo.fromJson(json)
        assertNotNull(parsed)
        assertEquals(info.targetUri, parsed?.targetUri)
        assertEquals(info.targetPath, parsed?.targetPath)
        assertEquals(info.displayNameOverride, parsed?.displayNameOverride)
        assertEquals(info.targetIsDirectory, parsed?.targetIsDirectory)
        assertEquals(encoded, parsed?.iconBase64)
        assertArrayEquals(rawBytes, CustomIconStore.decodeBase64ToBytes(parsed?.iconBase64))
    }
}
