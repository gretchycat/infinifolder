package com.infinifolder.theme

import android.graphics.*
import android.graphics.drawable.Drawable

/** Adapts classic bitmap chrome to arbitrary window sizes without stretching player controls. */
internal class WinampChromeDrawable(
    private val fill: Int,
    private val border: Int,
    private val density: Float,
    private val frame: Bitmap?,
    private val title: Bitmap?,
    private val smooth: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = smooth }

    override fun draw(canvas: Canvas) {
        paint.color = fill
        canvas.drawRect(bounds, paint)
        val pixelScale = if (smooth) density.coerceAtLeast(1f) else kotlin.math.round(density).coerceAtLeast(1f)
        val edge = 3 * pixelScale
        val left = bounds.left.toFloat(); val right = bounds.right.toFloat()
        val top = bounds.top.toFloat(); val bottom = bounds.bottom.toFloat()
        fun strip(bitmap: Bitmap, src: Rect, dst: RectF) {
            if (src.right <= bitmap.width && src.bottom <= bitmap.height && dst.width() > 0 && dst.height() > 0)
                canvas.drawBitmap(bitmap, src, dst, paint)
        }
        fun tile(bitmap: Bitmap, src: Rect, dst: RectF, scale: Float) {
            val checkpoint = canvas.save()
            canvas.clipRect(dst)
            var y = dst.top
            while (y < dst.bottom) {
                var x = dst.left
                while (x < dst.right) {
                    strip(bitmap, src, RectF(x, y, x + src.width() * scale, y + src.height() * scale))
                    x += src.width() * scale
                }
                y += src.height() * scale
            }
            canvas.restoreToCount(checkpoint)
        }
        if (title != null && title.width >= 302 && title.height >= 14) {
            val scale = if (smooth) (bounds.height() / 14f).coerceAtLeast(1f)
                else kotlin.math.round(bounds.height() / 14f).coerceAtLeast(1f)
            val cap = minOf(25 * scale, bounds.width() / 3f)
            val y = top + (bounds.height() - 14 * scale) / 2f
            // Filtering and scale policy follow the saved interface-theme preference.
            tile(title, Rect(54, 0, 80, 14), RectF(left + cap, top, right - cap, bottom), scale)
            val checkpoint = canvas.save()
            canvas.clipRect(bounds)
            strip(title, Rect(27, 0, 52, 14), RectF(left, y, left + 25 * scale, y + 14 * scale))
            strip(title, Rect(277, 0, 302, 14), RectF(right - 25 * scale, y, right, y + 14 * scale))
            canvas.restoreToCount(checkpoint)
        } else if (frame != null && frame.width >= 275 && frame.height >= 116) {
            tile(frame, Rect(0, 14, 3, 100), RectF(left, top, left + edge, bottom), pixelScale)
            tile(frame, Rect(272, 14, 275, 100), RectF(right - edge, top, right, bottom), pixelScale)
            tile(frame, Rect(3, 113, 272, 116), RectF(left + edge, bottom - edge, right - edge, bottom), pixelScale)
            tile(frame, Rect(3, 0, 272, 3), RectF(left + edge, top, right - edge, top + edge), pixelScale)
        } else {
            paint.color = border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = edge
            canvas.drawRect(left + edge / 2, top + edge / 2, right - edge / 2, bottom - edge / 2, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
