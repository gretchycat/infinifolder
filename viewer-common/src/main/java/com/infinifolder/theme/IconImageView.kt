package com.infinifolder.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.util.LruCache
import android.widget.ImageView
import java.util.WeakHashMap

/** File artwork with an optional alpha-shaped shadow, independent of icon resolution. */
class IconImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs), ThemeChangeListener {
    private val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrixValues = FloatArray(9)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ThemeManager.PREF_ICON_SHADOWS) postInvalidateOnAnimation()
    }

    init { ThemeManager.init(context) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        ThemeManager.addListener(this)
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        ThemeManager.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onThemeChanged(theme: ThemeColors) { postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        val source = drawable as? BitmapDrawable
        if (!ThemeManager.iconShadowsEnabled || source == null || source.bitmap.isRecycled ||
            width <= 0 || height <= 0 || width > 1024 || height > 1024) {
            super.onDraw(canvas)
            return
        }
        imageMatrix.getValues(matrixValues)
        val radius = 2f * resources.displayMetrics.density
        val bounds = source.bounds
        val key = MaskKey(sourceId(source.bitmap), source.bitmap.generationId,
            width, height, paddingLeft, paddingTop, bounds.left, bounds.top, bounds.right, bounds.bottom,
            source.alpha, source.colorFilter?.hashCode() ?: 0, radius, matrixValues.toList())
        val mask = masks.get(key) ?: run {
            // Rasterize only the displayed size, never a full-resolution thumbnail.
            val raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val target = Canvas(raster)
            target.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            target.concat(imageMatrix)
            if (source.bitmap.config == Bitmap.Config.HARDWARE) {
                val software = source.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                try { target.drawBitmap(software, null, bounds, source.paint) }
                finally { software.recycle() }
            } else source.draw(target)
            val offsets = IntArray(2)
            val alpha = try {
                raster.extractAlpha(Paint().apply {
                    maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                }, offsets)
            } finally { raster.recycle() }
            ShadowMask(alpha, offsets[0], offsets[1]).also { masks.put(key, it) }
        }
        // Reserve a small gutter so the fading edge is not clipped at the view boundary.
        // A uniform scale preserves the icon's aspect ratio in rectangular list/tree slots.
        val gutter = radius * 1.5f
        val scale = (1f - 2f * gutter / minOf(width, height)).coerceIn(0.5f, 1f)
        val checkpoint = canvas.save()
        canvas.scale(scale, scale, width / 2f, height / 2f)
        paint.color = ThemeManager.activeTheme.alternateBackground
        val offset = resources.displayMetrics.density
        canvas.drawBitmap(mask.bitmap, mask.left + offset, mask.top + offset, paint)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private data class MaskKey(
        val source: Long, val generation: Int, val width: Int, val height: Int,
        val paddingLeft: Int, val paddingTop: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val alpha: Int, val filter: Int, val radius: Float, val matrix: List<Float>
    )
    private data class ShadowMask(val bitmap: Bitmap, val left: Int, val top: Int)

    companion object {
        // Alpha masks can be reused across themes: tint is applied at draw time.
        // Weak source IDs avoid retaining original artwork through cache keys.
        private val sourceIds = WeakHashMap<Bitmap, Long>()
        private var nextSourceId = 0L
        @Synchronized private fun sourceId(bitmap: Bitmap): Long =
            sourceIds.getOrPut(bitmap) { ++nextSourceId }
        private val masks = object : LruCache<MaskKey, ShadowMask>(4 * 1024 * 1024) {
            override fun sizeOf(key: MaskKey, value: ShadowMask) = value.bitmap.allocationByteCount
        }
    }
}
