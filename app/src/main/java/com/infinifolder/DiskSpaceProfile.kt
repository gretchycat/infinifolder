package com.infinifolder

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.text.format.Formatter
import android.widget.TextView
import com.infinifolder.storage.FileSystemInfo
import kotlin.math.roundToInt

/** Compact path-bar summary for providers that report both free and total capacity. */
internal object DiskSpaceProfile {
    data class Badge(val text: String, val color: Int)

    fun create(context: Context, info: FileSystemInfo?): Badge? {
        val capacity = info ?: return null
        val total = capacity.totalBytes?.takeIf { it > 0 } ?: return null
        val available = capacity.availableBytes?.takeIf { it >= 0 } ?: return null
        val used = (total - available).coerceIn(0L, total)
        val exactPercent = used.toDouble() * 100.0 / total.toDouble()
        val percent = exactPercent.roundToInt()
        val usedText = Formatter.formatShortFileSize(context, used)
        val totalText = Formatter.formatShortFileSize(context, total)
        val color = when {
            exactPercent <= 75.0 -> 0xFF4CAF50.toInt()
            exactPercent <= 90.0 -> 0xFFFFEB3B.toInt()
            else -> 0xFFF44336.toInt()
        }
        return Badge("$usedText / $totalText ($percent%)", color)
    }

    fun createView(context: Context, outlineColor: Int): FrameLayout = FrameLayout(context).apply {
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = android.view.View.GONE
        val density = resources.displayMetrics.density
        val backingColor = (outlineColor and 0x00FFFFFF) or 0x80000000.toInt()
        fun label(color: Int, outline: Boolean) = TextView(context).apply {
            textSize = 9f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding((2 * density).roundToInt(), 0, (2 * density).roundToInt(), 0)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (outline) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.75f * density
                paint.strokeJoin = Paint.Join.ROUND
            }
        }
        addView(label(backingColor, outline = true),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(label(Color.WHITE, outline = false),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
    }

    fun apply(view: FrameLayout, badge: Badge) {
        (view.getChildAt(0) as TextView).text = badge.text
        (view.getChildAt(1) as TextView).apply {
            text = badge.text
            setTextColor(badge.color)
        }
    }
}
