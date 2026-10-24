package com.infinifolder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** Two-finger drag selects; one-finger gestures pass through for scrolling and taps. */
class SelectionRectangleLayout(context: Context) : FrameLayout(context) {
    var selectionEnabled: () -> Boolean = { false }
    var selectRectangle: (RectF, Boolean) -> List<RectF> = { _, _ -> emptyList() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var selecting = false
    private var rectangle: RectF? = null
    private var hits = emptyList<RectF>()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tracking = false
            selecting = false
        }
        if (!tracking && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            event.pointerCount == 2 && selectionEnabled()) {
            tracking = true
            selecting = true
            startX = (event.getX(0) + event.getX(1)) / 2f
            startY = (event.getY(0) + event.getY(1)) / 2f
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (!tracking) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (selecting && event.pointerCount == 2) {
                val x = (event.getX(0) + event.getX(1)) / 2f
                val y = (event.getY(0) + event.getY(1)) / 2f
                if (rectangle != null || (x - startX) * (x - startX) + (y - startY) * (y - startY) > slop * slop) {
                    val rect = RectF(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                    rectangle = rect
                    hits = selectRectangle(rect, false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rectangle?.let { selectRectangle(it, true) }
                rectangle = null
                hits = emptyList()
                selecting = false
                // Consume the remaining finger until release; never synthesize a tap or scroll.
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    tracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                invalidate()
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        rectangle?.let { rect ->
            paint.style = Paint.Style.FILL
            paint.color = 0x334CAF50
            hits.forEach { canvas.drawRect(it, paint) }
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2
            paint.color = 0xFF4CAF50.toInt()
            canvas.drawRect(rect, paint)
        }
    }
}
