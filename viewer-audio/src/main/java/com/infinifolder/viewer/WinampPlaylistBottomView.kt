package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.infinifolder.theme.ThemeManager
import java.util.Locale

/**
 * Authentic Playlist Window Bottom Bar View.
 * Native dimension is 275 x 38 pixels from PLEDIT.BMP, dynamically scaled
 * to match the playlist window width using two-stage scaling.
 */
class WinampPlaylistBottomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_WIDTH = 275
        const val NATIVE_HEIGHT = 38
    }

    var totalDurationMs: Long = 0L
    var currentTrackDurationMs: Long = 0L

    private var pixelScaledBitmap: Bitmap? = null
    private var pixelScaledCanvas: Canvas? = null

    private val nearestPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private val smoothPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }

    private val timeTextPaint = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    private val srcRect = Rect()
    private val dstRect = Rect()

    private fun formatMinSec(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val screenW = resources.displayMetrics.widthPixels
        val finalW = if (widthSize > 0) widthSize else screenW
        val calculatedHeight = ((NATIVE_HEIGHT.toFloat() / NATIVE_WIDTH.toFloat()) * finalW).toInt()
        setMeasuredDimension(finalW, calculatedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smoothPaint.isFilterBitmap = ThemeManager.smoothSkinScaling
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val theme = ThemeManager.getAudioPlayerTheme(context)
        timeTextPaint.color = theme.textPrimary

        val bottomBmp = ThemeManager.getPlaylistBottomBarBitmap()
        if (bottomBmp != null) {
            val k = w / NATIVE_WIDTH
            if (k >= 2) {
                val pixelW = NATIVE_WIDTH * k
                val pixelH = NATIVE_HEIGHT * k

                if (pixelScaledBitmap == null || pixelScaledBitmap?.width != pixelW || pixelScaledBitmap?.height != pixelH) {
                    pixelScaledBitmap?.recycle()
                    val newBmp = Bitmap.createBitmap(pixelW, pixelH, Bitmap.Config.ARGB_8888)
                    pixelScaledBitmap = newBmp
                    pixelScaledCanvas = Canvas(newBmp)
                }

                srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
                dstRect.set(0, 0, pixelW, pixelH)
                pixelScaledCanvas?.drawBitmap(bottomBmp, srcRect, dstRect, nearestPaint)

                if (totalDurationMs > 0) {
                    val totalStr = formatMinSec(totalDurationMs)
                    timeTextPaint.textSize = 7f * k
                    val fontMetrics = timeTextPaint.fontMetrics
                    val baseline = (13.5f * k) - (fontMetrics.ascent + fontMetrics.descent) / 2f
                    pixelScaledCanvas?.let { textCanvas ->
                        val checkpoint = textCanvas.save()
                        textCanvas.clipRect(132f * k, 9f * k, 217f * k, 19f * k)
                        textCanvas.drawText(totalStr, 217f * k, baseline, timeTextPaint)
                        textCanvas.restoreToCount(checkpoint)
                    }
                }

                dstRect.set(0, 0, w, h)
                canvas.drawBitmap(pixelScaledBitmap!!, Rect(0, 0, pixelW, pixelH), dstRect, smoothPaint)
            } else {
                srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
                dstRect.set(0, 0, w, h)
                canvas.drawBitmap(bottomBmp, srcRect, dstRect, smoothPaint)

                if (totalDurationMs > 0) {
                    val totalStr = formatMinSec(totalDurationMs)
                    val scaleX = w.toFloat() / NATIVE_WIDTH.toFloat()
                    val scaleY = h.toFloat() / NATIVE_HEIGHT.toFloat()
                    timeTextPaint.textSize = 7f * scaleY
                    val fontMetrics = timeTextPaint.fontMetrics
                    val baseline = (13.5f * scaleY) - (fontMetrics.ascent + fontMetrics.descent) / 2f
                    val checkpoint = canvas.save()
                    canvas.clipRect(132f * scaleX, 9f * scaleY, 217f * scaleX, 19f * scaleY)
                    canvas.drawText(totalStr, 217f * scaleX, baseline, timeTextPaint)
                    canvas.restoreToCount(checkpoint)
                }
            }
        } else {
            val theme = ThemeManager.getAudioPlayerTheme(context)
            canvas.drawColor(theme.toolbarBackground)
        }
    }
}
