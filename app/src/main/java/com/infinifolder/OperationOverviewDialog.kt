package com.infinifolder

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.infinifolder.ops.OperationSnapshot
import com.infinifolder.ops.OperationState
import kotlin.math.abs

/** Compact live activity list. The existing transfer dialog supplies per-operation detail. */
internal class OperationOverviewDialog(
    private val context: Context,
    builder: AlertDialog.Builder,
    private val textColor: Int,
    private val onSelect: (OperationSnapshot) -> Unit,
    private val onClearFinished: () -> Unit,
    private val onSwipe: (OperationSnapshot, Boolean) -> Unit,
    onDismiss: (OperationOverviewDialog) -> Unit
) {
    private data class Row(val root: SwipeOperationRow, val name: TextView, val destination: TextView,
        val progress: ProgressBar, val status: TextView, val rate: TextView, val hint: TextView)
    private val rows = LinkedHashMap<String, Row>()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = TextView(context).apply {
        text = "No recent operations"; setTextColor(textColor); setPadding(dp(20), dp(24), dp(20), dp(24))
    }
    private var canClearFinished = false
    private var recentLimit = 15
    private var latestSnapshots: List<OperationSnapshot> = emptyList()
    private val older = Button(context).apply {
        text = "Show older results"
        setTextColor(textColor)
        setOnClickListener {
            recentLimit += 15
            update(latestSnapshots)
        }
    }
    private val dialog = builder.setTitle("Activity")
        .setView(ScrollView(context).apply { addView(content); minimumHeight = dp(140) })
        .setNegativeButton("Hide", null).setNeutralButton("Clear finished", null).create().apply {
            setOnDismissListener { onDismiss(this@OperationOverviewDialog) }
        }
    val isShowing: Boolean get() = dialog.isShowing
    fun show() {
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            isEnabled = canClearFinished
            setOnClickListener { onClearFinished() }
        }
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * .92).toInt().coerceAtMost(dp(520)), -2)
    }
    fun dismiss() { dialog.dismiss() }

    fun update(snapshots: List<OperationSnapshot>) {
        latestSnapshots = snapshots
        val relevant = snapshots.filterNot { it.id.startsWith("playback_") }
        canClearFinished = relevant.any { it.canRemoveFromHistory }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = canClearFinished
        val active = relevant.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        val finished = relevant.filter { it.canRemoveFromHistory }
        val recent = finished.takeLast(recentLimit).reversed()
        val visible = active + recent
        val ids = visible.map { it.id }.toSet()
        rows.keys.filter { it !in ids }.toList().forEach { id -> content.removeView(rows.remove(id)?.root) }
        content.removeView(empty)
        content.removeView(older)
        if (visible.isEmpty()) content.addView(empty)
        visible.forEachIndexed { index, operation ->
            val row = rows.getOrPut(operation.id) { makeRow() }
            if (row.root.parent == null) content.addView(row.root, index)
            else if (content.indexOfChild(row.root) != index) {
                content.removeView(row.root); content.addView(row.root, index)
            }
            row.name.text = operation.displayName ?: operation.currentItem ?: operation.label
            row.destination.text = operation.destination?.let { "To: $it" } ?: operation.label
            val percent = operation.progressPercent
            row.progress.isIndeterminate = percent < 0 && operation.state == OperationState.RUNNING
            row.progress.progress = if (operation.state == OperationState.COMPLETE) 100 else percent.coerceAtLeast(0)
            val liveRate = if (System.currentTimeMillis() - operation.lastUpdateMillis < 5000)
                operation.formatRate().ifEmpty { "0 B/s" } else "0 B/s"
            row.status.text = when (operation.state) {
                OperationState.QUEUED -> operation.message ?: "Waiting"
                OperationState.RUNNING -> listOfNotNull(operation.message ?: "Running", percent.takeIf { it >= 0 }?.let { "$it%" }).joinToString(" · ")
                OperationState.FAILED -> "Failed: ${operation.message?.takeIf { it.isNotBlank() } ?: "No failure reason was reported."}"
                else -> operation.displayState.replaceFirstChar { it.uppercase() }
            }
            row.rate.text = "↓ $liveRate"
            row.rate.visibility = if (operation.state == OperationState.RUNNING) View.VISIBLE else View.GONE
            row.root.setOnClickListener { onSelect(operation) }
            row.root.swipeAction = { right -> onSwipe(operation, right) }
            row.root.canDismiss = operation.canRemoveFromHistory
            row.hint.text = if (operation.canRemoveFromHistory) {
                "← Remove" + if (operation.canRestart) " · ${operation.restartActionLabel} →" else ""
            } else "← Cancel" + if (operation.canFinishLater) " · Pause →" else ""
        }
        if (finished.size > recentLimit) content.addView(older)
        val running = active.count { it.state == OperationState.RUNNING }
        val waiting = active.size - running
        dialog.setTitle(if (active.isEmpty()) "Activity" else "Activity · $running running" + if (waiting > 0) " · $waiting waiting" else "")
    }

    private fun makeRow(): Row {
        fun label(size: Float, lines: Int) = TextView(context).apply {
            textSize = size; setTextColor(textColor); maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        }
        val root = SwipeOperationRow(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true; isFocusable = true
            val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = attributes.getDrawable(0); attributes.recycle()
        }
        val name = label(16f, 2).apply { typeface = Typeface.DEFAULT_BOLD }
        val destination = label(12f, 2)
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val status = label(12f, 2)
        val rate = label(12f, 1).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(rate, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(name); root.addView(destination)
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(6); bottomMargin = dp(4) })
        root.addView(statusRow)
        val hint = label(11f, 1).apply { alpha = .7f; setPadding(0, dp(4), 0, 0) }
        root.addView(hint)
        return Row(root, name, destination, progress, status, rate, hint)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

/** Directional swipes request confirmed actions; vertical drags keep scrolling. */
private class SwipeOperationRow(context: Context) : LinearLayout(context) {
    var swipeAction: ((Boolean) -> Unit)? = null
    var canDismiss: Boolean = false
    private var gestureAction: ((Boolean) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var ignored = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (swipeAction == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                translationX = 0f; alpha = 1f
                downX = event.rawX; downY = event.rawY
                swiping = false; ignored = false
                gestureAction = swipeAction
                parent?.requestDisallowInterceptTouchEvent(true)
                super.onTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                ignored = true
                cancelPressed(event)
                restore()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignored) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!swiping && abs(dy) > slop && abs(dx) <= abs(dy) * 1.3f) {
                    ignored = true
                    cancelPressed(event)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (!swiping && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                    swiping = true
                    cancelPressed(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (swiping) {
                    translationX = dx
                    alpha = 1f - (abs(dx) / width.coerceAtLeast(1) * .5f).coerceAtMost(.5f)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (swiping) {
                    val threshold = maxOf(64 * resources.displayMetrics.density, width * .28f)
                    val right = translationX > 0
                    val triggered = abs(translationX) >= threshold
                    restore()
                    if (triggered) gestureAction?.invoke(right)
                    swiping = false
                    return true
                }
                if (ignored) { cancelPressed(event); return true }
            }
            MotionEvent.ACTION_CANCEL -> {
                restore()
                swiping = false; ignored = true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelPressed(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun restore() {
        parent?.requestDisallowInterceptTouchEvent(false)
        animate().translationX(0f).alpha(1f).setDuration(120).start()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (canDismiss) {
            info.isDismissable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id) {
            if (canDismiss) swipeAction?.let { it(false); return true }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
