package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class ScrollWindowTest {
    private data class Page(val offset: Long, val count: Int)
    private fun window() = ScrollWindow<Page>(3, { it.offset }, { it.count })
    @Test fun appendEvictsOnlyOffscreenContentAndPreservesTheVisibleAnchor() {
        val window = window(); window.reset(Page(0, 10))
        window.append(Page(10, 20), 0); window.append(Page(30, 30), 0)
        assertNull(window.append(Page(60, 40), 9))
        assertEquals(listOf(0L, 10L, 30L), window.pages.map { it.offset })
        val shift = window.append(Page(60, 40), 15)!!
        assertEquals(5, shift.anchor(15)); assertEquals(10, shift.removedBefore)
        assertEquals(listOf(10L, 30L, 60L), window.pages.map { it.offset })
    }
    @Test fun prependEvictsOnlyTheOffscreenTailAndKeepsPixelRelativeAnchorUnits() {
        val window = window(); window.reset(Page(10, 20))
        window.append(Page(30, 30), 0); window.append(Page(60, 40), 0)
        assertNull(window.prepend(Page(0, 10), 50))
        val shift = window.prepend(Page(0, 10), 49)!!
        assertEquals(25, shift.anchor(15)); assertEquals(10, shift.addedBefore)
        assertEquals(listOf(0L, 10L, 30L), window.pages.map { it.offset })
    }
    @Test fun reversalsAndDuplicateResponsesNeverDiscardVisibleContentOrGrowTheWindow() {
        val window = window(); window.reset(Page(0, 10))
        window.append(Page(10, 10), 0); window.append(Page(20, 10), 0)
        assertNull(window.append(Page(30, 10), 0)) // User reversed while I/O was pending.
        assertNull(window.append(Page(20, 10), 20))
        repeat(1000) { index ->
            val page = Page((index + 3) * 10L, 10)
            assertNotNull(window.append(page, 10)); assertEquals(3, window.size)
        }
        assertEquals(30, window.totalUnits)
    }
    @Test fun emptyWindowsCanAdvanceWithoutAnUnboundedCacheOrAVisibleAnchor() {
        val window = window(); window.reset(Page(0, 0))
        repeat(20) { window.append(Page(it + 1L, 0), 0); assertTrue(window.size <= 3) }
        assertNotNull(window.append(Page(21, 10), 0)); assertEquals(10, window.totalUnits)
        window.clear(); assertEquals(0, window.size)
    }
}
