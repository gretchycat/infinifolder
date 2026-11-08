package com.infinifolder

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.infinifolder.model.ResourceEntry
import kotlin.math.ceil

/**
 * A fixed-geometry file grid that creates cards only around the scroll viewport.
 * ScrollView still owns scrolling, so existing pull-to-refresh, pinch and position
 * restoration behavior stays intact while large listings avoid retaining every card.
 */
internal class VirtualizedEntrySurface(
    context: Context,
    private val entries: List<ResourceEntry>,
    private val columns: Int,
    private val cellHeightPx: Int,
    private val makeCard: (ResourceEntry) -> View,
    private val onCardRemoved: (ResourceEntry, View) -> Unit
) : ViewGroup(context) {
    private val attachedCards = LinkedHashMap<Int, View>()
    private var requestedScrollY = 0
    private var requestedViewportHeight = 0
    private var updateScheduled = false
    private var lastLoggedRange: Pair<Int, Int>? = null

    private fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return "heapUsedMb=${used / (1024 * 1024)} heapMaxMb=${runtime.maxMemory() / (1024 * 1024)} " +
            "nativeMb=${android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}"
    }

    private val updateRunnable = Runnable {
        updateScheduled = false
        updateAttachedCards()
    }

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    /** Coalesced with drawing so a fling does not repeatedly rebuild the same rows. */
    fun updateViewport(scrollY: Int, viewportHeight: Int) {
        requestedScrollY = scrollY.coerceAtLeast(0)
        requestedViewportHeight = viewportHeight.coerceAtLeast(cellHeightPx)
        if (updateScheduled) return
        updateScheduled = true
        postOnAnimation(updateRunnable)
    }

    private fun updateAttachedCards() {
        if (!isAttachedToWindow || entries.isEmpty()) return
        val rowCount = ceil(entries.size / columns.toDouble()).toInt()
        val viewportHeight = requestedViewportHeight.coerceAtLeast(cellHeightPx)
        val overscanPx = maxOf(cellHeightPx * 2, viewportHeight / 2)
        val firstRow = ((requestedScrollY - overscanPx).coerceAtLeast(0) / cellHeightPx)
            .coerceAtMost((rowCount - 1).coerceAtLeast(0))
        val lastRow = (((requestedScrollY + viewportHeight + overscanPx).coerceAtLeast(0) / cellHeightPx) + 1)
            .coerceAtMost(rowCount)
        val firstIndex = firstRow * columns
        val endIndex = (lastRow * columns).coerceAtMost(entries.size)
        val range = firstIndex to endIndex
        if (range != lastLoggedRange) {
            lastLoggedRange = range
            com.infinifolder.storage.DiagnosticLog.record(
                "ui.render-entries.virtualized-range",
                "first=$firstIndex end=$endIndex attached=${attachedCards.size} total=${entries.size} scrollY=$requestedScrollY viewportHeight=$viewportHeight"
            )
        }
        val wanted = (firstIndex until endIndex).toHashSet()
        var changed = false
        val stale = attachedCards.keys.filterNot(wanted::contains)
        stale.forEach { index ->
            val card = attachedCards.remove(index) ?: return@forEach
            removeView(card)
            onCardRemoved(entries[index], card)
            changed = true
        }
        // Draw the visible rows first. Offscreen overscan must not delay the
        // first icons, and a large viewport must not monopolize one UI frame.
        val visibleFirst = (requestedScrollY / cellHeightPx * columns).coerceIn(firstIndex, endIndex)
        val visibleEnd = (((requestedScrollY + viewportHeight) / cellHeightPx + 1) * columns)
            .coerceIn(visibleFirst, endIndex)
        val prioritized = sequenceOf(visibleFirst until visibleEnd, firstIndex until visibleFirst, visibleEnd until endIndex)
            .flatMap { it.asSequence() }
        val batchStarted = android.os.SystemClock.uptimeMillis()
        var created = 0
        var deferred = false
        for (index in prioritized) {
            if (attachedCards.containsKey(index)) continue
            if (created >= columns && android.os.SystemClock.uptimeMillis() - batchStarted >= 8L) {
                deferred = true
                break
            }
            val entry = entries[index]
            val started = android.os.SystemClock.uptimeMillis()
            com.infinifolder.storage.DiagnosticLog.record(
                "ui.render-entries.card-start",
                "index=$index name=${entry.metadata.name} provider=${entry.ref.providerId} mime=${entry.metadata.mimeType} size=${entry.metadata.sizeBytes} ${memorySummary()}"
            )
            var card: View? = null
            try {
                val createdCard = makeCard(entry)
                card = createdCard
                attachedCards[index] = createdCard
                addView(createdCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                changed = true
                created++
            } catch (error: Throwable) {
                attachedCards.remove(index)
                card?.let { if (it.parent === this) removeView(it) }
                com.infinifolder.storage.DiagnosticLog.record(
                    "ui.render-entries.card-failed",
                    "index=$index name=${entry.metadata.name} provider=${entry.ref.providerId}",
                    error
                )
                throw error
            }
            com.infinifolder.storage.DiagnosticLog.record(
                "ui.render-entries.card-complete",
                "index=$index name=${entry.metadata.name} elapsedMs=${android.os.SystemClock.uptimeMillis() - started} ${memorySummary()}"
            )
        }
        // Requesting layout when nothing changed caused onLayout to schedule
        // another update forever, continuously consuming frames while idle.
        if (changed) requestLayout()
        if (deferred) updateViewport(requestedScrollY, requestedViewportHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = if (entries.isEmpty()) 0 else (entries.size + columns - 1) / columns
        val contentHeight = (rowCount.toLong() * cellHeightPx).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val measuredHeight = resolveSize(contentHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)

        val cellWidth = (measuredWidth / columns).coerceAtLeast(1)
        attachedCards.forEach { (index, card) ->
            val width = if (index % columns == columns - 1) measuredWidth - cellWidth * (columns - 1) else cellWidth
            card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeightPx, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellWidth = (width / columns).coerceAtLeast(1)
        attachedCards.forEach { (index, card) ->
            val column = index % columns
            val row = index / columns
            val x = column * cellWidth
            val itemWidth = if (column == columns - 1) width - x else cellWidth
            val y = row * cellHeightPx
            card.layout(x, y, x + itemWidth, y + cellHeightPx)
        }
        updateViewport(requestedScrollY, requestedViewportHeight)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updateRunnable)
        updateScheduled = false
        attachedCards.toList().forEach { (index, card) ->
            onCardRemoved(entries[index], card)
        }
        attachedCards.clear()
        removeAllViews()
        super.onDetachedFromWindow()
    }
}
