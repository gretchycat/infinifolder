package com.infinifolder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.infinifolder.ops.OperationSnapshot
import com.infinifolder.ops.OperationState
import com.infinifolder.theme.ThemeManager

/** Small disk-style light. Animation stops completely when hidden or idle. */
class OperationActivityLight(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false
    private var completionUntil = 0L
    private var resultColor: Int? = null
    private val frame = Runnable { invalidate() }

    init {
        contentDescription = "File activity: idle"
        isClickable = true
        isFocusable = true
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) contentDescription = "File activity: working. Tap for progress."
        invalidate()
    }

    fun completed(op: OperationSnapshot) {
        resultColor = when {
            op.state == OperationState.FAILED -> 0xFFE53935.toInt()
            op.warningCount > 0 -> 0xFFFFB300.toInt()
            else -> null // Successful operations use the current theme accent.
        }
        completionUntil = SystemClock.uptimeMillis() + 2400L
        contentDescription = "${op.label}: " + when {
            op.state == OperationState.FAILED -> "failed"
            op.state == OperationState.CANCELLED -> "cancelled"
            op.warningCount > 0 -> "finished with warnings"
            else -> "completed"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        removeCallbacks(frame)
        val now = SystemClock.uptimeMillis()
        val completing = now < completionUntil
        val theme = ThemeManager.activeTheme
        paint.color = if (completing) resultColor ?: theme.accent else theme.accent
        paint.alpha = when {
            completing -> (255 * ((completionUntil - now) / 2400f)).toInt().coerceIn(35, 255)
            active -> if (now % 900L < 450L) 230 else 65
            else -> 30
        }
        val radius = 4f * resources.displayMetrics.density
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        if ((active || completing) && isShown && windowVisibility == VISIBLE) postDelayed(frame, 80L)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(frame)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }
}
