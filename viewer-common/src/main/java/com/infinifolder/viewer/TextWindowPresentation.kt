package com.infinifolder.viewer

import io.github.gretchycat.infiniscribe.TextChunk
import io.github.gretchycat.infiniscribe.TextDocument
import io.github.gretchycat.infinistream.Verification

/** Compose adjacent decoded windows for display without adding a newline or changing source bytes. */
object TextWindowPresentation {
    fun combine(pages: List<TextChunk>): TextChunk {
        require(pages.isNotEmpty())
        require(pages.zipWithNext().all { (left, right) -> left.nextOffset == right.start })
        val first = pages.first(); val last = pages.last()
        val text = pages.joinToString("") { it.document.text }
        val bytes = ByteArray(pages.sumOf { it.document.bytes.size })
        var position = 0
        pages.forEach { it.document.bytes.copyInto(bytes, position); position += it.document.bytes.size }
        val whole = first.start == 0L && !last.hasNext
        val metadata = first.document.metadata.copy(attributes = first.document.metadata.attributes + mapOf(
            "complete" to whole.toString(), "nextOffset" to last.nextOffset.toString(), "utf16CodeUnits" to text.length.toString()),
            provenance = first.document.metadata.provenance.copy(length = last.nextOffset - first.start),
            verification = if (whole) Verification.UNKNOWN else Verification.PROVISIONAL,
            warnings = pages.flatMap { it.document.metadata.warnings }.distinct().filterNot {
                whole && it == "Document window is not the complete source"
            })
        return TextChunk(TextDocument(text, bytes, !whole, metadata), first.start, last.nextOffset, last.hasNext)
    }
}
