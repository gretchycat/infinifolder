package com.infinifolder

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import com.infinifolder.model.ResourceEntry
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The file view's row/column layout. Both full and floating browsers use this grid. */
object FileViewGrid {
    private const val ICON_ROW_HEIGHT_EXTRA_DP = 48
    private const val STAGGERED_ROW_TIGHTENING_DP = 16

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

    internal fun staggeredRowHeightPx(iconSizeDp: Int, density: Float): Int =
        ((iconSizeDp + ICON_ROW_HEIGHT_EXTRA_DP - STAGGERED_ROW_TIGHTENING_DP) * density).roundToInt()

    /** Alternating staggered rows hold one fewer item after their half-cell offset. */
    internal fun rowCount(itemCount: Int, columns: Int, staggered: Boolean): Int {
        if (itemCount <= 0) return 0
        val count = columns.coerceAtLeast(1)
        if (!staggered || count == 1) return ceil(itemCount / count.toDouble()).toInt()
        val pairCapacity = count * 2 - 1
        val fullPairs = itemCount / pairCapacity
        val remainder = itemCount % pairCapacity
        return fullPairs * 2 + when {
            remainder == 0 -> 0
            remainder <= count -> 1
            else -> 2
        }
    }

    internal fun rowStart(row: Int, itemCount: Int, columns: Int, staggered: Boolean): Int {
        val count = columns.coerceAtLeast(1)
        if (!staggered || count == 1) return (row * count).coerceAtMost(itemCount)
        val pairCapacity = count * 2 - 1
        val pairStart = (row / 2) * pairCapacity
        return (pairStart + if (row % 2 == 0) 0 else count).coerceAtMost(itemCount)
    }

    internal fun rowEnd(row: Int, itemCount: Int, columns: Int, staggered: Boolean): Int {
        val count = columns.coerceAtLeast(1)
        val start = rowStart(row, itemCount, count, staggered)
        val capacity = if (staggered && count > 1 && row % 2 == 1) count - 1 else count
        return (start + capacity).coerceAtMost(itemCount)
    }

    internal data class CellPosition(val row: Int, val column: Int, val staggered: Boolean)

    internal fun position(index: Int, columns: Int, staggered: Boolean): CellPosition {
        val count = columns.coerceAtLeast(1)
        if (!staggered || count == 1) return CellPosition(index / count, index % count, false)
        val pairCapacity = count * 2 - 1
        val pair = index / pairCapacity
        val inPair = index % pairCapacity
        return if (inPair < count) {
            CellPosition(pair * 2, inPair, false)
        } else {
            CellPosition(pair * 2 + 1, inPair - count, true)
        }
    }

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

    internal fun buildStaggered(
        context: Context,
        cards: List<View>,
        iconSizeDp: Int,
        cellHeightPx: Int,
        onEmptyAreaLongPress: (() -> Boolean)? = null,
    ): StaggeredIconLayout = StaggeredIconLayout(
        context,
        cards,
        iconSizeDp,
        cellHeightPx,
        onEmptyAreaLongPress,
    )
}

/** A compact, reflowing staggered surface for ordinary-sized directories. */
internal class StaggeredIconLayout(
    context: Context,
    cards: List<View>,
    private val iconSizeDp: Int,
    private val cellHeightPx: Int,
    onEmptyAreaLongPress: (() -> Boolean)?,
) : ViewGroup(context) {
    private var columns = 1

    init {
        clipChildren = false
        clipToPadding = false
        cards.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        isLongClickable = onEmptyAreaLongPress != null
        if (onEmptyAreaLongPress != null) setOnLongClickListener { onEmptyAreaLongPress() }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        columns = FileViewGrid.columns((measuredWidth / density).roundToInt(), iconSizeDp)
        val rows = FileViewGrid.rowCount(childCount, columns, staggered = true)
        val contentHeight = (rows.toLong() * cellHeightPx).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val measuredHeight = resolveSize(contentHeight, heightMeasureSpec)
        val cellWidth = (measuredWidth / columns).coerceAtLeast(1)
        for (index in 0 until childCount) {
            val position = FileViewGrid.position(index, columns, staggered = true)
            val width = if (position.column == columns - 1 && !position.staggered)
                measuredWidth - cellWidth * (columns - 1) else cellWidth
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeightPx, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellWidth = (width / columns).coerceAtLeast(1)
        for (index in 0 until childCount) {
            val position = FileViewGrid.position(index, columns, staggered = true)
            val x = position.column * cellWidth + if (position.staggered) cellWidth / 2 else 0
            val itemWidth = if (position.column == columns - 1 && !position.staggered) width - x else cellWidth
            val y = position.row * cellHeightPx
            val child = getChildAt(index)
            child.layout(x, y, x + itemWidth, y + cellHeightPx)
        }
    }
}
