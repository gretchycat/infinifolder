package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.infinifolder.theme.ThemeManager

/**
 * Authentic Playlist Window Vertical Border View (Left or Right).
 * Native dimension is 25 x 29 pixels from PLEDIT.BMP, dynamically scaled
 * to match the 275-width skin grid and tiled vertically along the playlist window.
 */
class WinampPlaylistBorderView @JvmOverloads constructor(
    context: Context,
    val isLeft: Boolean = true,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_WIDTH = 25
        const val NATIVE_HEIGHT = 29
        const val BASE_GRID_WIDTH = 275f
    }

    private val smoothPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }

    private val fallbackBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = false
    }

    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val screenW = resources.displayMetrics.widthPixels
        val parentW = if (widthMode == MeasureSpec.AT_MOST && widthSize > 0) widthSize
            else (parent as? View)?.width.takeIf { it != null && it > 0 } ?: screenW

        val borderW = if (widthMode == MeasureSpec.EXACTLY && widthSize > 0) {
            widthSize
        } else {
            ((NATIVE_WIDTH.toFloat() / BASE_GRID_WIDTH) * parentW).toInt().coerceAtLeast(1)
        }
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(borderW, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        smoothPaint.isFilterBitmap = ThemeManager.smoothSkinScaling
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val tile = if (isLeft) ThemeManager.getPlaylistLeftBorderTile() else ThemeManager.getPlaylistRightBorderTile()
        if (tile != null) {
            val tileW = tile.width
            val tileH = tile.height
            val scaledTileH = ((tileH.toFloat() / tileW.toFloat()) * w).toInt().coerceAtLeast(1)

            var y = 0
            while (y < h) {
                val curH = minOf(scaledTileH, h - y)
                val srcH = ((curH.toFloat() / scaledTileH.toFloat()) * tileH).toInt().coerceAtLeast(1)
                srcRect.set(0, 0, tileW, srcH)
                dstRect.set(0, y, w, y + curH)
                canvas.drawBitmap(tile, srcRect, dstRect, smoothPaint)
                y += curH
            }
        } else {
            val theme = ThemeManager.getAudioPlayerTheme(context)
            canvas.drawColor(theme.cardBackground)
            fallbackBorderPaint.color = theme.cardStroke
            val x = if (isLeft) w.toFloat() - 1f else 1f
            canvas.drawLine(x, 0f, x, h.toFloat(), fallbackBorderPaint)
        }
    }
}
