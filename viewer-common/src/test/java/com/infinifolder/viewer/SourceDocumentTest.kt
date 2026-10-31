package com.infinifolder.viewer

import io.github.gretchycat.infinistream.*
import com.infinifolder.storage.ContentRevision
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SourceDocumentTest {
    @Test fun actualDocumentPathPreservesMetadataRevisionAndBorrowedInput() {
        var closes = 0
        val bytes = "original document".toByteArray()
        val stream = object : ByteArrayInputStream(bytes) { override fun close() { closes++; super.close() } }
        val input = InputStreamSource(stream, StreamMetadata(provenance = Provenance(source = "fixture://original"), warnings = listOf("source warning")))
        val doc = MediaSourceAdapter.readDocument(input)
        assertEquals("original document", doc.text); assertEquals(ContentRevision.of(bytes), doc.revision)
        assertEquals("fixture://original", doc.metadata.provenance.source)
        assertTrue("source warning" in doc.metadata.warnings); assertEquals("unicode", doc.metadata.content.encoding)
        assertEquals(Ownership.OWNED, doc.metadata.ownership); assertEquals(0, closes)
        input.close(); assertEquals(1, closes)
        InputStreamSource(bytes.inputStream()).use { assertNull(MediaSourceAdapter.readDocument(it, 3).revision) }
    }

    @Test fun actualTablePathUsesLibraryCellsAndOriginalByteRevision() {
        var closes = 0
        val bytes = "name;city\r\n Alice ;Paris\r\nBob;Rome\r\n".toByteArray()
        val stream = object : ByteArrayInputStream(bytes) { override fun close() { closes++; super.close() } }
        val source = InputStreamSource(stream, StreamMetadata(provenance = Provenance(source = "fixture://table"),
            warnings = listOf("source warning")))
        val loaded = MediaSourceAdapter.readTable(source, "table.csv")
        assertFalse(loaded.hasMore); assertEquals(ContentRevision.of(bytes), loaded.revision)
        assertEquals(';', loaded.table.delimiter)
        assertEquals(listOf(listOf("name", "city"), listOf("Alice", "Paris"), listOf("Bob", "Rome")), loaded.table.rows)
        assertEquals("fixture://table", loaded.table.metadata.provenance.source)
        assertEquals("3", loaded.table.metadata.attributes["rowCount"])
        assertEquals("true", loaded.table.metadata.attributes["complete"])
        assertTrue("source warning" in loaded.table.metadata.warnings)
        val formatted = io.github.gretchycat.infiniscribe.TableDocuments.format(loaded.table)
        assertEquals("name;city\nAlice;Paris\nBob;Rome\n", formatted.text)
        assertNotEquals(loaded.revision, ContentRevision.of(formatted.bytes))
        assertEquals(0, closes); source.close(); assertEquals(1, closes)
    }

    @Test fun partialTableHasNoSaveRevisionAndErrorsAreNotEmptyTables() {
        InputStreamSource("a,b\nc,d\ne,f".byteInputStream()).use { source ->
            val loaded = MediaSourceAdapter.readTable(source, maxBytes = 7)
            assertTrue(loaded.hasMore); assertNull(loaded.revision)
            assertEquals("false", loaded.table.metadata.attributes["complete"])
            assertTrue(io.github.gretchycat.infiniscribe.TableDocuments.format(loaded.table).hasMore)
        }
        val source = InputStreamSource(object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("broken table")
        })
        assertThrows(java.io.IOException::class.java) { MediaSourceAdapter.readTable(source) }
    }
}
