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

    @Test
    fun testWebMetadataNormalizeAndDetectWebUri() {
        assertEquals("https://example.com", WebMetadataResolver.normalizeInputUri("example.com"))
        assertEquals("https://www.github.com/torvalds", WebMetadataResolver.normalizeInputUri("www.github.com/torvalds"))
        assertEquals("http://example.org/test", WebMetadataResolver.normalizeInputUri("HTTP://example.org/test"))
        assertEquals("file:///storage/emulated/0/Download", WebMetadataResolver.normalizeInputUri("/storage/emulated/0/Download"))
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ADocs",
            WebMetadataResolver.normalizeInputUri("content://com.android.externalstorage.documents/tree/primary%3ADocs"))
        assertEquals("mailto:user@example.com", WebMetadataResolver.normalizeInputUri("mailto:user@example.com"))

        assertTrue(WebMetadataResolver.isWebUri("https://news.ycombinator.com"))
        assertTrue(WebMetadataResolver.isWebUri("example.com"))
        assertFalse(WebMetadataResolver.isWebUri("content://authority/doc"))
        assertFalse(WebMetadataResolver.isWebUri("apps://category/Games"))
    }

    @Test
    fun testExtractHtmlTitleAndEntities() {
        val html1 = """
            <html>
              <head>
                <title>
                  Hacker News &amp; Tech &#8212; Home&#39;s Page
                </title>
              </head>
              <body></body>
            </html>
        """.trimIndent()
        assertEquals("Hacker News & Tech — Home's Page", WebMetadataResolver.extractHtmlTitle(html1))

        val htmlOgFallback = """
            <html>
              <head>
                <meta property="og:title" content="OpenGraph &quot;Title&quot;" />
              </head>
            </html>
        """.trimIndent()
        assertEquals("OpenGraph \"Title\"", WebMetadataResolver.extractHtmlTitle(htmlOgFallback))
    }

    @Test
    fun testExtractFaviconUrlsOrderingAndResolution() {
        val html = """
            <html>
              <head>
                <link rel="shortcut icon" href="/favicon.ico">
                <link rel="icon" type="image/png" sizes="32x32" href="assets/icon-32.png">
                <link rel="apple-touch-icon" sizes="180x180" href="//cdn.example.com/apple-touch-icon.png">
                <link rel="icon" type="image/svg+xml" href="/vector.svg">
              </head>
            </html>
        """.trimIndent()

        val urls = WebMetadataResolver.extractFaviconUrls(html, "https://example.com/docs/index.html")
        assertTrue(urls.isNotEmpty())
        // High-res apple-touch-icon should be prioritized first
        assertEquals("https://cdn.example.com/apple-touch-icon.png", urls[0])
        assertTrue(urls.contains("https://example.com/docs/assets/icon-32.png"))
        assertTrue(urls.contains("https://example.com/favicon.ico"))
        // SVG is skipped in favor of raster icons
        assertFalse(urls.any { it.endsWith(".svg") })
    }

    @Test
    fun testSanitizeFileNameAndDefaultName() {
        assertEquals("Hacker News Y Combinator", WebMetadataResolver.sanitizeFileName("Hacker News : Y/Combinator?*", "fallback"))
        assertEquals("example.com - guide", WebMetadataResolver.defaultNameForUri("https://www.example.com/docs/guide"))
        assertEquals("Documents", WebMetadataResolver.defaultNameForUri("file:///storage/emulated/0/Documents"))
    }
}
