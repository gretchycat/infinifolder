package com.infinifolder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.infinifolder.theme.ThemeManager

/** View decoration stays separate from cached artwork and never intercepts item gestures. */
internal class HiddenFileBadge private constructor(context: Context, directory: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Path().apply {
        moveTo(4f, 10f)
        quadTo(10f, 3f, 16f, 10f)
        quadTo(10f, 17f, 4f, 10f)
        close()
    }
    private val backgroundColor = if (ThemeManager.activeTheme.isDark) 0xFF546E7A.toInt() else 0xFF455A64.toInt()

    init {
        isClickable = false
        isFocusable = false
        contentDescription = if (directory) "Hidden folder" else "Hidden file"
        tooltipText = contentDescription
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        val side = minOf(width, height).toFloat()
        canvas.translate((width - side) / 2, (height - side) / 2)
        canvas.scale(side / 20f, side / 20f)
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawCircle(10f, 10f, 9f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(eye, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(10f, 10f, 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = backgroundColor
        paint.strokeWidth = 3.5f
        canvas.drawLine(5f, 4.5f, 15f, 15.5f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.strokeWidth = 1.5f
        canvas.drawLine(5f, 4.5f, 15f, 15.5f, paint)
        canvas.restoreToCount(saved)
    }

    companion object {
        fun attach(frame: FrameLayout, hidden: Boolean, directory: Boolean, selectionBadge: Boolean = false) {
            if (!hidden) return
            val density = frame.resources.displayMetrics.density
            val side = (16 * density).toInt()
            frame.addView(HiddenFileBadge(frame.context, directory),
                FrameLayout.LayoutParams(side, side, Gravity.BOTTOM or Gravity.END).apply {
                    // The selection check occupies the lower end corner on selected file cards.
                    if (selectionBadge) marginEnd = (20 * density).toInt()
                })
        }
    }
}
