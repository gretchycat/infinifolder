package com.infinifolder.shortcut

import org.junit.Assert.*
import org.junit.Test

class ShortcutFileTest {

    @Test
    fun testIsShortcut() {
        assertTrue(ShortcutInfo.isShortcut("document.lnk"))
        assertTrue(ShortcutInfo.isShortcut("PHOTO.LNK"))
        assertTrue(ShortcutInfo.isShortcut("file", "application/x-ms-shortcut"))
        assertTrue(ShortcutInfo.isShortcut("file", "application/x-shortcut"))

        assertFalse(ShortcutInfo.isShortcut("document.pdf"))
        assertFalse(ShortcutInfo.isShortcut("image.png", "image/png"))
    }

    @Test
    fun testSerializationAndDeserialization() {
        val original = ShortcutInfo(
            targetUri = "content://com.android.externalstorage.documents/document/primary%3ADocuments",
            targetPath = "Internal shared / Documents",
            displayNameOverride = "My Docs",
            targetMimeType = "vnd.android.document/directory",
            targetIsDirectory = true,
            createdAtMillis = 1234567890L
        )

        val json = original.toJson()
        val parsed = ShortcutInfo.fromJson(json)

        assertNotNull(parsed)
        assertEquals(original.targetUri, parsed?.targetUri)
        assertEquals(original.targetPath, parsed?.targetPath)
        assertEquals(original.displayNameOverride, parsed?.displayNameOverride)
        assertEquals(original.targetMimeType, parsed?.targetMimeType)
        assertEquals(original.targetIsDirectory, parsed?.targetIsDirectory)
        assertEquals(original.createdAtMillis, parsed?.createdAtMillis)
    }

    @Test
    fun testMinimalShortcutJson() {
        val json = """
            {
               "targetUri": "file:///sdcard/Download/test.zip",
               "targetIsDirectory": false
            }
        """.trimIndent()

        val parsed = ShortcutInfo.fromJson(json)
        assertNotNull(parsed)
        assertEquals("file:///sdcard/Download/test.zip", parsed?.targetUri)
        assertFalse(parsed!!.targetIsDirectory)
        assertNull(parsed.displayNameOverride)
        assertNull(parsed.targetPath)
    }

    @Test
    fun testInvalidShortcutJson() {
        assertNull(ShortcutInfo.fromJson("not a json"))
        assertNull(ShortcutInfo.fromJson("{}"))
        assertNull(ShortcutInfo.fromJson("""{"otherField": "value"}"""))
    }
}
