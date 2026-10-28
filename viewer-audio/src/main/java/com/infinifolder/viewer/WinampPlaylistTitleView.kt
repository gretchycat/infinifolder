package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.infinifolder.theme.ThemeManager

/**
 * Authentic Winamp Playlist Window Titlebar View.
 * Native dimension is 275 x 20 pixels, dynamically scaled to match the main player's width.
 * Employs two-stage scaling (pixel-scaling up to maximum integer multiple, then smooth-scaling to target width).
 */
class WinampPlaylistTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_WIDTH = 275
        const val NATIVE_HEIGHT = 20
    }

    var onShadeToggle: (() -> Unit)? = null

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

    private val srcRect = Rect()
    private val dstRect = Rect()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            onShadeToggle?.invoke()
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
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val titleBmp = ThemeManager.getPlaylistTitlebarBitmap()
        if (titleBmp != null) {
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
                pixelScaledCanvas?.drawBitmap(titleBmp, srcRect, dstRect, nearestPaint)

                dstRect.set(0, 0, w, h)
                canvas.drawBitmap(pixelScaledBitmap!!, Rect(0, 0, pixelW, pixelH), dstRect, smoothPaint)
            } else {
                srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
                dstRect.set(0, 0, w, h)
                canvas.drawBitmap(titleBmp, srcRect, dstRect, smoothPaint)
            }
        } else {
            // Clean fallback titlebar
            val theme = ThemeManager.getAudioPlayerTheme(context)
            canvas.drawColor(theme.toolbarBackground)
        }
    }
}
