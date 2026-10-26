package com.infinifolder.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.infinifolder.theme.ThemeManager

/** Oversized corner hit target; the transparent half-square passes touches to the grid. */
internal class FloatingResizeHandle(context: Context, private val left: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triangle = Path()
    init {
        contentDescription = if (left) "Resize floating window from lower left" else "Resize floating window from lower right"
        tooltipText = contentDescription
        isClickable = true
        isFocusable = true
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (width <= 0 || height <= 0) return false
            val x = event.x / width
            val y = event.y / height
            if (if (left) y < x else x + y < 1f) return false
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val theme = ThemeManager.activeTheme
        val w = minOf(width.toFloat(), 32f * density)
        val h = minOf(height.toFloat(), 32f * density)
        val saved = canvas.save()
        canvas.translate(if (left) 0f else width - w, height - h)
        triangle.reset()
        if (left) {
            triangle.moveTo(0f, 0f); triangle.lineTo(w, h); triangle.lineTo(0f, h)
        } else {
            triangle.moveTo(w, 0f); triangle.lineTo(w, h); triangle.lineTo(0f, h)
        }
        triangle.close()
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(theme.accent, 220)
        canvas.drawPath(triangle, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = ColorUtils.setAlphaComponent(theme.textPrimary, 220)
        canvas.drawPath(triangle, paint)
        paint.color = if (ColorUtils.calculateLuminance(theme.accent) > .45) Color.BLACK else Color.WHITE
        paint.alpha = 220
        paint.strokeWidth = 1.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        for (index in 1..3) {
            val inset = 5f * density
            val reach = (index * 6 + 3) * density
            if (left) canvas.drawLine(inset, h - reach, reach, h - inset, paint)
            else canvas.drawLine(w - inset, h - reach, w - reach, h - inset, paint)
        }
        canvas.restoreToCount(saved)
    }
}
