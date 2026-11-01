package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.max

/** Small, draggable overlay for views whose native scroll indicator is easy to miss. */
class ScrollHandleView(
    context: Context,
    private val scrollRange: () -> Int,
    private val scrollOffset: () -> Int,
    private val viewportSize: () -> Int,
    private val scrollToOffset: (Int) -> Unit,
    private val onDragStateChanged: (Boolean) -> Unit = {},
    private val horizontal: Boolean = false
) : View(context) {
    private val density = resources.displayMetrics.density
    private val lane = 24f * density
    private val thumbWidth = 4f * density
    private val minimumThumb = 36f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false
    private var dragDelta = 0f
    private val trackLength get() = if (horizontal) width else height
    private val trackWidth get() = if (horizontal) height else width
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { invalidate() }

    init {
        setWillNotDraw(false)
        isClickable = true
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    private fun range() = scrollRange().coerceAtLeast(0)

    private fun geometry(): RectF? {
        val maxScroll = range()
        if (trackLength <= 0 || maxScroll <= 0) return null
        val viewport = viewportSize().coerceAtLeast(1)
        val content = maxScroll.toLong() + viewport
        val thumbHeight = max(minimumThumb, trackLength * viewport.toFloat() / content.toFloat()).coerceAtMost(trackLength.toFloat())
        val travel = (trackLength - thumbHeight).coerceAtLeast(0f)
        val top = travel * scrollOffset().coerceIn(0, maxScroll).toFloat() / maxScroll
        return RectF(trackWidth - (lane + thumbWidth) / 2f, top, trackWidth - (lane - thumbWidth) / 2f, top + thumbHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = geometry() ?: return
        val save = canvas.save()
        if (horizontal) {
            canvas.translate(0f, height.toFloat())
            canvas.rotate(-90f)
        }
        paint.color = 0x44666666
        canvas.drawRoundRect(RectF(current.centerX() - thumbWidth / 2f, 0f, current.centerX() + thumbWidth / 2f, trackLength.toFloat()),
            thumbWidth / 2f, thumbWidth / 2f, paint)
        paint.color = 0xCCBDBDBD.toInt()
        canvas.drawRoundRect(current, thumbWidth / 2f, thumbWidth / 2f, paint)
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val maxScroll = range()
        if (maxScroll <= 0 || trackLength <= 0) return false
        val position = if (horizontal) event.x else event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val current = geometry() ?: return false
                dragging = true
                onDragStateChanged(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                if (position in current.top..current.bottom) dragDelta = position - current.top
                else {
                    dragDelta = current.height() / 2f
                    moveThumb(position, current.height())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val current = geometry() ?: return true
                moveThumb(position, current.height())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                onDragStateChanged(false)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return dragging
    }

    private fun moveThumb(y: Float, thumbHeight: Float) {
        val travel = (trackLength - thumbHeight).coerceAtLeast(1f)
        val top = (y - dragDelta).coerceIn(0f, travel)
        scrollToOffset((top / travel * range()).toInt())
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
        super.onDetachedFromWindow()
    }
}

class ScrollableEditText(context: Context) : android.widget.EditText(context) {
    fun verticalScrollRange() = (computeVerticalScrollRange() - height).coerceAtLeast(0)
}

class ScrollableWebView(context: Context) : android.webkit.WebView(context) {
    fun verticalScrollRange() = (computeVerticalScrollRange() - height).coerceAtLeast(0)
}

class ScrollableListView(context: Context) : android.widget.ListView(context) {
    fun verticalScrollRange() = (computeVerticalScrollRange() - height).coerceAtLeast(0)
}
