package com.infinifolder.viewer

import java.text.NumberFormat
import java.util.Locale

/** Source row numbers exclude the fixed header; cached row count is never a document total. */
object CsvRowStats {
    fun label(firstRow: Long?, lastRow: Long?, totalRows: Long?, counting: Boolean,
        filtered: Boolean, locale: Locale = Locale.getDefault()): String {
        val numbers = NumberFormat.getIntegerInstance(locale)
        if (firstRow == null || lastRow == null) {
            if (filtered) return "No matching rows\n" + when {
                totalRows != null -> "${numbers.format(totalRows)} total rows"
                counting -> "Counting total…"
                else -> "Total unknown"
            }
            return when {
                totalRows != null -> "${numbers.format(totalRows)} rows"
                counting -> "Counting rows…"
                else -> "Total unknown"
            }
        }
        val range = "${if (filtered) "Filtered rows" else "Rows"} ${numbers.format(firstRow)}–${numbers.format(lastRow)}"
        return range + "\n" + when {
            totalRows != null -> "of ${numbers.format(totalRows)}"
            counting -> "Counting total…"
            else -> "Total unknown"
        }
    }
}
