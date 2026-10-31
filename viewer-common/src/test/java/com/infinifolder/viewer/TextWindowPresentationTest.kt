package com.infinifolder.viewer

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class TextWindowPresentationTest {
    @Test fun adjacentWindowsComposeWithoutArtificialLineBreaksOrFalsePartialSaveRevisions() {
        val text = "plain long line 🎵 é " .repeat(100)
        TextChunkReader(InputStreamSource(text.byteInputStream())).use { reader ->
            val pages = mutableListOf<TextChunk>(); var position = 0L
            do { val page = reader.read(position, 113); pages += page; position = page.nextOffset } while (page.hasNext)
            val whole = TextWindowPresentation.combine(pages)
            assertEquals(text, whole.document.text); assertArrayEquals(text.toByteArray(), whole.document.bytes)
            assertFalse(whole.document.hasMore)
            assertEquals(Verification.UNKNOWN, whole.document.metadata.verification)
            val tail = TextWindowPresentation.combine(pages.drop(1))
            assertTrue(tail.document.hasMore); assertEquals("false", tail.document.metadata.attributes["complete"])
        }
    }
    @Test fun aGapOrOverlapIsRejectedRatherThanDisplayedAsAContinuousSource() {
        TextChunkReader(InputStreamSource("x".repeat(100).byteInputStream())).use { reader ->
            val a = reader.read(0, 16); val b = reader.read(32, 16)
            assertThrows(IllegalArgumentException::class.java) { TextWindowPresentation.combine(listOf(a, b)) }
        }
    }
}
