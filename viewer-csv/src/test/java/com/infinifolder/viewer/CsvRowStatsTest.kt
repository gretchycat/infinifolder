package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CsvRowStatsTest {
    private fun label(first: Long?, last: Long?, total: Long?, counting: Boolean = false, filtered: Boolean = false) =
        CsvRowStats.label(first, last, total, counting, filtered, Locale.US)

    @Test fun visibleSourceRowsReplaceTheCacheSizeAndMoveWithScrolling() {
        assertEquals("Rows 501–515\nof 31,669", label(501, 515, 31669))
        assertEquals("Rows 900–914\nof 31,669", label(900, 914, 31669))
        assertEquals("Rows 31,660–31,669\nof 31,669", label(31660, 31669, 31669))
    }
    @Test fun unknownTotalsAreExplicitUntilTheCountCompletes() {
        assertEquals("Rows 1–15\nCounting total…", label(1, 15, null, counting = true))
        assertEquals("Rows 1–15\nTotal unknown", label(1, 15, null))
        assertEquals("Counting rows…", label(null, null, null, counting = true))
    }
    @Test fun emptyAndHeaderOnlyTablesContainZeroDataRows() {
        assertEquals("0 rows", label(null, null, 0))
        assertEquals("7 rows", label(null, null, 7))
    }
    @Test fun sparseFiltersUseSourceRowNumbersAndNameTheirScope() {
        assertEquals("Filtered rows 50–10,001\nof 31,669", label(50, 10001, 31669, filtered = true))
        assertEquals("No matching rows\n31,669 total rows", label(null, null, 31669, filtered = true))
        assertEquals("No matching rows\nCounting total…", label(null, null, null, counting = true, filtered = true))
    }
    @Test fun sourceRowNumbersRemainAccurateBeyondIntegerLimits() {
        assertEquals("Rows 4,294,967,296–4,294,967,310\nof 8,589,934,592", label(4294967296, 4294967310, 8589934592))
    }
}
