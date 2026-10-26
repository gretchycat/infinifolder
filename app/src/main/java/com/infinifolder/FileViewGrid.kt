package com.infinifolder

import android.content.Context
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import com.infinifolder.model.ResourceEntry

/** The file view's row/column layout. Both full and floating browsers use this grid. */
object FileViewGrid {
    fun sortedEntries(entries: List<ResourceEntry>, showHidden: Boolean, sortKey: String, descending: Boolean): List<ResourceEntry> {
        val byName = compareBy<ResourceEntry> { it.metadata.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.metadata.name }
        val ordering = when (sortKey) {
            "type" -> compareBy<ResourceEntry> { com.infinifolder.mime.MimeRegistry.normalize(it.metadata.mimeType) }.then(byName)
            "size" -> compareBy<ResourceEntry> { it.metadata.sizeBytes ?: -1L }.then(byName)
            "modified" -> compareBy<ResourceEntry> { it.metadata.modifiedAtMillis ?: 0L }.then(byName)
            else -> byName
        }.let { if (descending) it.reversed() else it }
        return entries.filter { showHidden || !it.metadata.isHidden }
            .sortedWith(compareBy<ResourceEntry> { !it.metadata.isDirectory }.then(ordering))
    }

    fun columns(availableWidthDp: Int, iconSizeDp: Int): Int =
        Math.round(availableWidthDp / (iconSizeDp * 1.35f).coerceAtLeast(36f)).coerceIn(1, 14)

    fun build(
        context: Context,
        entries: List<ResourceEntry>,
        columns: Int,
        onEmptyAreaLongPress: (() -> Boolean)? = null,
        card: (ResourceEntry) -> View
    ): TableLayout {
        fun View.emptyAreaAction() {
            isLongClickable = onEmptyAreaLongPress != null
            if (onEmptyAreaLongPress != null) setOnLongClickListener { onEmptyAreaLongPress() }
        }
        return TableLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            isStretchAllColumns = true
            emptyAreaAction()
            entries.chunked(columns).forEach { group ->
                val row = TableRow(context).apply {
                    clipChildren = false
                    clipToPadding = false
                    emptyAreaAction()
                }
                group.forEach { entry ->
                    row.addView(card(entry), TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f))
                }
                repeat(columns - group.size) {
                    row.addView(View(context).apply { emptyAreaAction() },
                        TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f))
                }
                addView(row)
            }
        }
    }
}
