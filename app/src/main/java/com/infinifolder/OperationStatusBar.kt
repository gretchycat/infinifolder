package com.infinifolder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.infinifolder.ops.OperationActivitySummary
import com.infinifolder.ops.formatTransferRate
import com.infinifolder.theme.ThemeManager

/** Bottom status strip; the whole row opens Activity. Visibility belongs to the host. */
internal class OperationStatusBar(context: Context) : LinearLayout(context) {
    private val counts: TextView
    private val metrics: TextView
    private val gap = (8 * resources.displayMetrics.density).toInt()
    init {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val theme = ThemeManager.activeTheme
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(36)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        val isDark = ThemeManager.resolveIsDark(context)
        val bgColor = if (isDark) Color.BLACK else Color.WHITE
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val strokeColor = if (isDark) 0xFF2A2A2A.toInt() else 0xFFE0E0E0.toInt()
        background = GradientDrawable().apply { setColor(bgColor); setStroke(dp(1), strokeColor) }
        isClickable = true
        isFocusable = true
        counts = TextView(context).apply {
            textSize = 12f; setTextColor(textColor)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        metrics = TextView(context).apply {
            textSize = 12f; setTextColor(textColor)
            typeface = if (theme.fontMonospace) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
            gravity = Gravity.END; maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }
        addView(counts, LayoutParams(0, -2, 1f))
        addView(metrics, LayoutParams(-2, -2))
    }
    fun update(summary: OperationActivitySummary) {
        val hasMusic = summary.activePlaybackTitle != null
        val fileActions = if (hasMusic) summary.actions - 1 else summary.actions
        val formatTime: (Long) -> String = { ms ->
            if (ms <= 0L) "0:00"
            else {
                val totalSec = ms / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                if (min >= 60) {
                    val hr = min / 60
                    val remMin = min % 60
                    String.format("%d:%02d:%02d", hr, remMin, sec)
                } else {
                    String.format("%d:%02d", min, sec)
                }
            }
        }

        if (hasMusic && fileActions <= 0) {
            val title = summary.activePlaybackTitle ?: ""
            val artist = summary.activePlaybackArtist?.takeIf { it.isNotBlank() }
            counts.text = "♫ $title" + (if (artist != null) " · $artist" else "")
            val pos = formatTime(summary.playbackPositionMs)
            val dur = if (summary.playbackDurationMs > 0) formatTime(summary.playbackDurationMs) else "--:--"
            val pct = if (summary.playbackDurationMs > 0) {
                " · " + ((summary.playbackPositionMs * 100) / summary.playbackDurationMs).toInt().coerceIn(0, 100) + "%"
            } else ""
            metrics.text = "$pos / $dur$pct"
        } else if (hasMusic && fileActions > 0) {
            val title = summary.activePlaybackTitle ?: ""
            val fileCount = when {
                !summary.hasUnknownItems -> "${summary.itemsProcessed}/${summary.knownTotalItems} items"
                summary.knownTotalItems > 0 -> "${summary.itemsProcessed}/${summary.knownTotalItems}+ items"
                else -> "${summary.itemsProcessed}/? items"
            }
            counts.text = "♫ $title | $fileActions ${if (fileActions == 1) "file action" else "file actions"} · $fileCount"
            metrics.text = formatTransferRate(summary.bytesPerSecond).ifEmpty { "0 B/s" } +
                " · " + (summary.percent?.let { "$it%" } ?: "—%")
        } else {
            val itemCount = when {
                !summary.hasUnknownItems -> "${summary.itemsProcessed}/${summary.knownTotalItems} items"
                summary.knownTotalItems > 0 -> "${summary.itemsProcessed}/${summary.knownTotalItems}+ items"
                else -> "${summary.itemsProcessed}/? items"
            }
            counts.text = "${summary.actions} ${if (summary.actions == 1) "action" else "actions"} · $itemCount"
            metrics.text = formatTransferRate(summary.bytesPerSecond).ifEmpty { "0 B/s" } +
                " · " + (summary.percent?.let { "$it%" } ?: "—%") + " · " + summary.formatEta()
        }
        contentDescription = "${counts.text}. ${metrics.text}. Tap to open Activity."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val desired = counts.paint.measureText(counts.text.toString()) + metrics.paint.measureText(metrics.text.toString()) + gap
        val next = if (available > 0 && desired > available) VERTICAL else HORIZONTAL
        if (orientation != next) {
            orientation = next
            val stacked = next == VERTICAL
            counts.layoutParams = if (stacked) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)
            metrics.layoutParams = LayoutParams(if (stacked) -1 else -2, -2)
            metrics.gravity = if (stacked) Gravity.START else Gravity.END
            metrics.maxLines = if (stacked) 2 else 1
            metrics.setPadding(if (stacked) 0 else gap, 0, 0, 0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
