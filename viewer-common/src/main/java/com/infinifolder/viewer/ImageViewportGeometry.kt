package com.infinifolder.viewer

import kotlin.math.max
import kotlin.math.min

/** Display coordinates only: pixel processing belongs to Canvas. */
internal data class ViewportRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
    val valid get() = listOf(left, top, right, bottom, width, height).all { it.isFinite() } && width > 0 && height > 0
    fun inset(x: Float, y: Float) = ViewportRect(left + x, top + y, right - x, bottom - y)
}

internal data class ImagePlacement(val scale: Float, val translateX: Float, val translateY: Float)

internal object ImageViewportGeometry {
    fun placement(rotated: ViewportRect, viewport: ViewportRect, fit: Boolean): ImagePlacement? {
        if (!rotated.valid || !viewport.valid) return null
        val sx = viewport.width / rotated.width
        val sy = viewport.height / rotated.height
        val scale = if (fit) min(sx, sy) else max(sx, sy)
        val tx = viewport.centerX - rotated.centerX * scale
        val ty = viewport.centerY - rotated.centerY * scale
        return if (scale > 0 && listOf(scale, tx, ty).all { it.isFinite() }) ImagePlacement(scale, tx, ty) else null
    }

    fun panCorrection(image: ViewportRect, viewport: ViewportRect): Pair<Float, Float> {
        if (!image.valid || !viewport.valid) return 0f to 0f
        fun correction(start: Float, end: Float, low: Float, high: Float): Float = when {
            end - start <= high - low -> (low + high - start - end) / 2f
            start > low -> low - start
            end < high -> high - end
            else -> 0f
        }
        return correction(image.left, image.right, viewport.left, viewport.right) to
            correction(image.top, image.bottom, viewport.top, viewport.bottom)
    }

    fun visibleBounds(image: ViewportRect, viewport: ViewportRect): ViewportRect? {
        if (!image.valid || !viewport.valid) return null
        // Leave room for the 15-pixel handle radius, even when the image fills the view.
        val safe = viewport.inset(min(20f, viewport.width * .2f), min(20f, viewport.height * .2f))
        return ViewportRect(max(image.left, safe.left), max(image.top, safe.top),
            min(image.right, safe.right), min(image.bottom, safe.bottom)).takeIf { it.valid }
    }

    fun initialCrop(image: ViewportRect, viewport: ViewportRect): ViewportRect? =
        visibleBounds(image, viewport)?.let { it.inset(min(40f, it.width * .2f), min(40f, it.height * .2f)) }

    fun aspect(rect: ViewportRect, ratio: Float): ViewportRect {
        if (!rect.valid || !ratio.isFinite() || ratio <= 0f) return rect
        val width = min(rect.width, rect.height * ratio)
        val height = min(rect.height, rect.width / ratio)
        return ViewportRect(rect.centerX - width / 2, rect.centerY - height / 2,
            rect.centerX + width / 2, rect.centerY + height / 2).takeIf { it.valid } ?: rect
    }

    fun dragCrop(rect: ViewportRect, handle: Int, x: Float, y: Float, bounds: ViewportRect): ViewportRect {
        if (!rect.valid || !bounds.valid || !x.isFinite() || !y.isFinite()) return rect
        val minW = min(50f, min(rect.width, bounds.width))
        val minH = min(50f, min(rect.height, bounds.height))
        return when (handle) {
            0 -> ViewportRect(x.coerceIn(bounds.left, rect.right - minW), y.coerceIn(bounds.top, rect.bottom - minH), rect.right, rect.bottom)
            1 -> ViewportRect(rect.left, y.coerceIn(bounds.top, rect.bottom - minH), x.coerceIn(rect.left + minW, bounds.right), rect.bottom)
            2 -> ViewportRect(rect.left, rect.top, x.coerceIn(rect.left + minW, bounds.right), y.coerceIn(rect.top + minH, bounds.bottom))
            3 -> ViewportRect(x.coerceIn(bounds.left, rect.right - minW), rect.top, rect.right, y.coerceIn(rect.top + minH, bounds.bottom))
            4 -> {
                val w = min(rect.width, bounds.width)
                val h = min(rect.height, bounds.height)
                val left = (x - w / 2).coerceIn(bounds.left, bounds.right - w)
                val top = (y - h / 2).coerceIn(bounds.top, bounds.bottom - h)
                ViewportRect(left, top, left + w, top + h)
            }
            else -> rect
        }
    }

    fun sourceCrop(rect: ViewportRect, intrinsicWidth: Int, intrinsicHeight: Int, pixelWidth: Int, pixelHeight: Int): ViewportRect? {
        if (!rect.valid || intrinsicWidth <= 0 || intrinsicHeight <= 0 || pixelWidth <= 0 || pixelHeight <= 0) return null
        val sx = pixelWidth.toFloat() / intrinsicWidth
        val sy = pixelHeight.toFloat() / intrinsicHeight
        return ViewportRect(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy).takeIf { it.valid }
    }
}
