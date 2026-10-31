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
}
