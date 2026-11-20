package com.infinifolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileViewGridTest {
    @Test
    fun staggeredRowsCoverEveryItemInStableOrderForEmptySmallAndLargeListings() {
        for (columns in 1..8) {
            for (itemCount in listOf(0, 1, 2, columns, columns + 1, 2 * columns + 3, 257)) {
                val rowCount = FileViewGrid.rowCount(itemCount, columns, staggered = true)
                val mapped = (0 until itemCount).map { FileViewGrid.position(it, columns, staggered = true) }
                assertEquals(itemCount, mapped.size)

                var next = 0
                for (row in 0 until rowCount) {
                    val start = FileViewGrid.rowStart(row, itemCount, columns, staggered = true)
                    val end = FileViewGrid.rowEnd(row, itemCount, columns, staggered = true)
                    assertEquals(next, start)
                    assertTrue(end >= start)
                    assertEquals((0 until end - start).toList(), (start until end).map { mapped[it].column })
                    for (index in start until end) {
                        val position = mapped[index]
                        assertEquals(row, position.row)
                        assertEquals(row % 2 == 1 && columns > 1, position.staggered)
                    }
                    next = end
                }
                assertEquals(itemCount, next)
            }
        }
    }

    @Test
    fun staggeredOffsetsNeverPushItemsOutsideTheViewport() {
        val cell = 100
        for (columns in 1..14) {
            val width = columns * cell
            for (index in 0 until 500) {
                val position = FileViewGrid.position(index, columns, staggered = true)
                val x = position.column * cell + if (position.staggered) cell / 2 else 0
                val right = x + cell
                assertTrue("item $index in $columns columns extends to $right/$width", right <= width)
            }
        }
    }

    @Test
    fun legacyIconPreferenceMigratesToGridAndModeCycleIncludesStaggered() {
        assertEquals(BrowserViewMode.GRID, BrowserViewMode.fromPreference(null, legacyIcons = true))
        assertEquals(BrowserViewMode.LIST, BrowserViewMode.fromPreference(null, legacyIcons = false))
        assertEquals(BrowserViewMode.STAGGERED, BrowserViewMode.fromPreference("STAGGERED", false))
        assertEquals(BrowserViewMode.STAGGERED, BrowserViewMode.GRID.next())
        assertEquals(BrowserViewMode.LIST, BrowserViewMode.STAGGERED.next())
    }
}
