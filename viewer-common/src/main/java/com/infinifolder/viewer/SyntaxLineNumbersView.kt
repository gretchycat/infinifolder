package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.widget.EditText
import android.widget.TextView

/** Draw logical line labels against the actual styled/wrapped text baselines. */
class SyntaxLineNumbersView(context: Context, private val editor: () -> EditText?) : TextView(context) {
    var starts = intArrayOf(0)
    var firstLine: Long? = 1L
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val edit = editor() ?: return
        val height = edit.layout?.height?.plus(edit.compoundPaddingTop + edit.compoundPaddingBottom) ?: measuredHeight
        setMeasuredDimension(measuredWidth, height)
    }
    override fun onDraw(canvas: Canvas) {
        val edit = editor() ?: return
        val layout = edit.layout ?: return
        val clip = canvas.clipBounds
        val from = layout.getLineForVertical((clip.top - edit.totalPaddingTop).coerceAtLeast(0))
        val to = layout.getLineForVertical((clip.bottom - edit.totalPaddingTop).coerceAtLeast(0))
        paint.color = currentTextColor
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        for (line in from..to) {
            val index = starts.binarySearch(layout.getLineStart(line))
            if (index < 0) continue // A wrapped continuation has no new logical line number.
            canvas.drawText(firstLine?.plus(index)?.toString() ?: "·", (width - paddingRight).toFloat(),
                (edit.totalPaddingTop + layout.getLineBaseline(line)).toFloat(), paint)
        }
    }
}
