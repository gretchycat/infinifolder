package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.infinifolder.theme.ThemeColors

/**
 * Lightweight, high-performance spectrum analyzer & oscilloscope visualizer.
 * Supports classic 16-band Winamp segmented bars with peak falloff, WMP glow bars,
 * and customizable color maps from active skins.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 16
    private val barHeights = FloatArray(barCount)
    private val barTargets = FloatArray(barCount)
    private val peakHeights = FloatArray(barCount)
    private val peakSpeed = FloatArray(barCount)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var colors: IntArray = intArrayOf(
        0xFFFF0033.toInt(), 0xFFFF6600.toInt(), 0xFFFFCC00.toInt(),
        0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0066FF.toInt()
    )

    var isPlaying: Boolean = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation()
        }

    fun updateData(newBands: FloatArray, newPeaks: FloatArray? = null) {
        val count = minOf(barCount, newBands.size)
        for (i in 0 until count) {
            barTargets[i] = newBands[i]
            if (newPeaks != null && i < newPeaks.size) {
                peakHeights[i] = newPeaks[i]
            }
        }
        invalidate()
    }

    fun setSpectrumColors(spectrumColors: IntArray) {
        if (spectrumColors.isNotEmpty()) {
            colors = spectrumColors
            invalidate()
        }
    }

    fun applyTheme(theme: ThemeColors) {
        setSpectrumColors(theme.spectrumColors)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val spacing = (w * 0.02f).coerceAtLeast(2f)
        val barWidth = (w - spacing * (barCount + 1)) / barCount

        // Update animation values
        for (i in 0 until barCount) {
            val targetH = if (isPlaying) barTargets[i] * h else 0f
            // Smooth interpolation
            barHeights[i] += (targetH - barHeights[i]) * 0.35f

            // Peak calculation with simulated gravity
            if (barHeights[i] >= peakHeights[i]) {
                peakHeights[i] = barHeights[i]
                peakSpeed[i] = 0f
            } else {
                peakSpeed[i] += 0.8f // Gravity
                peakHeights[i] = (peakHeights[i] - peakSpeed[i]).coerceAtLeast(0f)
            }
        }

        // Draw segmented bars
        for (i in 0 until barCount) {
            val left = spacing + i * (barWidth + spacing)
            val right = left + barWidth
            val barH = barHeights[i].coerceIn(0f, h)

            // Segment color interpolation
            val colorIndex = (i * colors.size) / barCount
            val barColor = colors[colorIndex.coerceIn(0, colors.size - 1)]
            barPaint.color = barColor

            // Draw segmented blocks (Winamp classic style)
            val segmentH = 4f
            val segmentGap = 2f
            var curY = h - 2f
            while (curY > h - barH && curY > 0) {
                val blockTop = (curY - segmentH).coerceAtLeast(0f)
                canvas.drawRect(left, blockTop, right, curY, barPaint)
                curY -= (segmentH + segmentGap)
            }

            // Draw floating peak dot
            val peakY = h - peakHeights[i]
            if (peakY < h - 4f) {
                peakPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(left, peakY - 3f, right, peakY, peakPaint)
            }
        }

        if (isPlaying || barHeights.any { it > 1f }) {
            postInvalidateOnAnimation()
        }
    }
}
