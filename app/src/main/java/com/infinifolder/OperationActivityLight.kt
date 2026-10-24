package com.infinifolder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.infinifolder.ops.OperationSnapshot
import com.infinifolder.ops.OperationState
import com.infinifolder.theme.ThemeManager
import com.infinifolder.storage.StorageActivity

/** Small disk-style light. Animation stops completely when hidden or idle. */
class OperationActivityLight(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false
    private var completionUntil = 0L
    private var resultColor: Int? = null
    private var completionDescription: CharSequence? = null
    private var blinkStartedAt = 0L
    private var wasWorking = false
    private var previousColor = 0
    private val frame = Runnable { invalidate() }

    init {
        contentDescription = "File activity: idle"
        isClickable = true
        isFocusable = true
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        contentDescription = if (value) "File activity: working. Tap for progress." else "File activity: idle. Tap for recent operations."
        invalidate()
    }

    fun completed(op: OperationSnapshot) {
        resultColor = when {
            op.state == OperationState.FAILED -> 0xFFE53935.toInt()
            op.warningCount > 0 -> 0xFFFFB300.toInt()
            else -> null // Successful operations use the current theme activity color.
        }
        completionUntil = SystemClock.uptimeMillis() + 2400L
        contentDescription = "${op.label}: " + when {
            op.state == OperationState.FAILED -> "failed"
            op.state == OperationState.CANCELLED -> "cancelled"
            op.warningCount > 0 -> "finished with warnings"
            else -> "completed"
        }
        completionDescription = contentDescription
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        removeCallbacks(frame)
        val now = SystemClock.uptimeMillis()
        val completing = now < completionUntil
        val theme = ThemeManager.activeTheme
        val verifying = StorageActivity.active(StorageActivity.Kind.VERIFICATION)
        val writing = active || StorageActivity.active(StorageActivity.Kind.FILE_OPERATION)
        val loading = StorageActivity.active(StorageActivity.Kind.LOADING)
        val waiting = !loading && StorageActivity.pending(StorageActivity.Kind.LOADING)
        val working = verifying || writing || loading
        paint.color = when {
            verifying -> theme.verificationActivityLight
            writing -> theme.activityLight
            loading || waiting -> theme.loadingActivityLight
            completing -> resultColor ?: theme.activityLight
            else -> theme.activityLight
        }
        contentDescription = when {
            verifying -> "Verifying data. Tap for file operations."
            writing -> "File operation in progress. Tap for progress."
            loading -> "Loading files. Tap for file operations."
            waiting -> "Waiting for loading to finish. Tap for file operations."
            completing -> completionDescription ?: contentDescription
            else -> "File activity: idle. Tap for recent operations."
        }
        // Begin every newly active phase brightly, including a very short directory read.
        if (working && (!wasWorking || previousColor != paint.color)) blinkStartedAt = now
        wasWorking = working
        previousColor = paint.color
        paint.alpha = when {
            working -> if ((now - blinkStartedAt) % 700L < 350L) 245 else 45
            waiting -> 70 // Same theme loading hue, steady and dim while waiting for progress.
            completing -> (255 * ((completionUntil - now) / 2400f)).toInt().coerceIn(35, 255)
            else -> 30
        }
        val radius = 4f * resources.displayMetrics.density
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        if ((working || (completing && !waiting)) && isShown && windowVisibility == VISIBLE) postDelayed(frame, 80L)
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
