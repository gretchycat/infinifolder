package com.infinifolder.viewer

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.ScrollView

/** Keep an in-flight fling moving when a bounded text window changes its local scroll origin. */
class AnchoredScrollView(context: Context) : ScrollView(context) {
    private var flinging = false
    private var velocity = 0
    private var sampleTime = 0L
    private var restoring = false
    private var savedVelocity: Int? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { flinging = false; velocity = 0; savedVelocity = 0 }
        return super.dispatchTouchEvent(event)
    }
    override fun fling(velocityY: Int) {
        flinging = velocityY != 0; velocity = velocityY; sampleTime = SystemClock.uptimeMillis()
        super.fling(velocityY)
    }
    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        val now = SystemClock.uptimeMillis()
        if (flinging && !restoring && now - sampleTime in 1..100) {
            velocity = ((y - oldY).toLong() * 1000 / (now - sampleTime)).coerceIn(-30000, 30000).toInt()
        }
        if (!restoring) sampleTime = now
        super.onScrollChanged(x, y, oldX, oldY)
    }
    fun cancelAnchorChange() {
        savedVelocity = null; restoring = false; flinging = false; velocity = 0
        super.fling(0)
    }
    fun beginAnchorChange() {
        savedVelocity = if (flinging && SystemClock.uptimeMillis() - sampleTime <= 100) velocity else 0
        restoring = true
        super.fling(0)
    }
    fun restoreAnchor(x: Int, y: Int) {
        val resume = savedVelocity ?: if (flinging && SystemClock.uptimeMillis() - sampleTime <= 100) velocity else 0
        savedVelocity = null
        restoring = true
        super.fling(0)
        scrollTo(x, y)
        restoring = false
        flinging = resume != 0; velocity = resume; sampleTime = SystemClock.uptimeMillis()
        if (resume != 0) super.fling(resume)
    }
}
