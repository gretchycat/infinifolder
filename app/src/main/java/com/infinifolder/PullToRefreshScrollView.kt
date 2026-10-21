package com.infinifolder

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class PullToRefreshScrollView(context: Context) : ScrollView(context) {

    var onRefreshListener: (() -> Unit)? = null
    var isRefreshing: Boolean = false
        set(value) {
            field = value
            if (!value) {
                resetPull(smooth = true)
            }
        }

    private var scaleDetector: ScaleGestureDetector? = null

    fun setOnScaleGestureListener(listener: ScaleGestureDetector.OnScaleGestureListener) {
        scaleDetector = ScaleGestureDetector(context, listener).apply {
            isQuickScaleEnabled = false
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialY = 0f
    private var isPulling = false
    private var currentPullDistance = 0f
    private val refreshThreshold = (64 * resources.displayMetrics.density)

    private val refreshHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val pad = (10 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
        alpha = 0f
        visibility = View.GONE
    }

    private val refreshIcon = TextView(context).apply {
        text = "↓"
        textSize = 16f
        setPadding(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
    }

    private val refreshText = TextView(context).apply {
        text = "Pull to refresh"
        textSize = 13f
    }

    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        visibility = View.GONE
        val size = (18 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            rightMargin = (8 * resources.displayMetrics.density).toInt()
        }
    }

    init {
        refreshHeader.addView(refreshIcon)
        refreshHeader.addView(progressBar)
        refreshHeader.addView(refreshText)
    }

    fun setupHeaderInParent(parentContainer: FrameLayout) {
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        parentContainer.addView(refreshHeader, lp)
    }

    fun updateHeaderTheme(dark: Boolean) {
        val fg = if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()
        refreshIcon.setTextColor(fg)
        refreshText.setTextColor(fg)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(ev)
        if (scaleDetector?.isInProgress == true) {
            if (isPulling) {
                isPulling = false
                resetPull(smooth = false)
            }
        }
        return try {
            super.dispatchTouchEvent(ev)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (scaleDetector?.isInProgress == true || ev.pointerCount >= 2) {
            if (isPulling) {
                isPulling = false
                resetPull(smooth = false)
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialY = ev.y
                isPulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - initialY
                if (scrollY <= 0 && dy > touchSlop && !isRefreshing) {
                    isPulling = true
                    initialY = ev.y
                    return true
                }
            }
        }
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (scaleDetector?.isInProgress == true || ev.pointerCount >= 2) {
            if (isPulling) {
                isPulling = false
                resetPull(smooth = false)
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isPulling) {
                    val dy = ev.y - initialY
                    if (dy > 0f) {
                        currentPullDistance = dy
                        applyPull(dy)
                        return true
                    } else {
                        currentPullDistance = 0f
                        applyPull(0f)
                        isPulling = false
                    }
                } else if (scrollY <= 0 && (ev.y - initialY) > touchSlop && !isRefreshing) {
                    isPulling = true
                    initialY = ev.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPulling) {
                    isPulling = false
                    if (currentPullDistance * 0.45f >= refreshThreshold && !isRefreshing) {
                        triggerRefresh()
                    } else {
                        resetPull(smooth = true)
                    }
                    return true
                }
            }
        }
        return try {
            super.onTouchEvent(ev)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun applyPull(rawDistance: Float) {
        val child = getChildAt(0) ?: return
        val clampedPull = rawDistance.coerceAtLeast(0f)
        val translation = (clampedPull * 0.45f).coerceAtMost(refreshThreshold * 1.6f)
        child.translationY = translation

        if (translation > 4f) {
            refreshHeader.visibility = View.VISIBLE
            refreshHeader.translationY = (translation - (40 * resources.displayMetrics.density)).coerceAtLeast(0f)
            refreshHeader.alpha = (translation / refreshThreshold).coerceIn(0f, 1f)
            if (translation >= refreshThreshold) {
                refreshIcon.text = "↑"
                refreshText.text = "Release to refresh"
            } else {
                refreshIcon.text = "↓"
                refreshText.text = "Pull to refresh"
            }
        } else {
            refreshHeader.visibility = View.GONE
        }
    }

    private fun triggerRefresh() {
        isRefreshing = true
        refreshIcon.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        refreshText.text = "Refreshing…"
        val child = getChildAt(0)
        child?.animate()?.translationY(refreshThreshold * 0.6f)?.setDuration(150)?.start()
        refreshHeader.animate()?.translationY((8 * resources.displayMetrics.density))?.alpha(1f)?.setDuration(150)?.start()
        onRefreshListener?.invoke()
    }

    fun resetPull(smooth: Boolean) {
        val child = getChildAt(0)
        refreshIcon.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        refreshIcon.text = "↓"
        refreshText.text = "Pull to refresh"
        if (smooth) {
            child?.animate()?.translationY(0f)?.setDuration(200)?.start()
            refreshHeader.animate()?.alpha(0f)?.translationY(0f)?.setDuration(200)?.withEndAction {
                refreshHeader.visibility = View.GONE
            }?.start()
        } else {
            child?.translationY = 0f
            refreshHeader.alpha = 0f
            refreshHeader.visibility = View.GONE
        }
    }
}
