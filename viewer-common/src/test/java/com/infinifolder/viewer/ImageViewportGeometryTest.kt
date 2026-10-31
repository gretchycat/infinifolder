package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class ImageViewportGeometryTest {
    private val view = ViewportRect(0f, 0f, 1344f, 2774f)
    private val image = ViewportRect(0f, 0f, 320f, 200f)
    private fun near(a: Float, b: Float) = assertEquals(a, b, .01f)
    private fun contained(inner: ViewportRect, outer: ViewportRect) {
        assertTrue("$inner within $outer", inner.valid && inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom)
    }

    @Test fun unrotatedFitCentersImage() {
        val p = ImageViewportGeometry.placement(image, view, true)!!
        near(4.2f, p.scale); near(view.centerX, image.centerX * p.scale + p.translateX)
        near(view.centerY, image.centerY * p.scale + p.translateY)
    }
    @Test fun quarterTurnNegativeOriginCentersImage() {
        val rotated = ViewportRect(-200f, 0f, 0f, 320f)
        val p = ImageViewportGeometry.placement(rotated, view, true)!!
        near(6.72f, p.scale); near(1344f, p.translateX)
        near(view.centerY, rotated.centerY * p.scale + p.translateY)
    }
    @Test fun halfTurnNegativeAxesCenterImage() {
        val rotated = ViewportRect(-320f, -200f, 0f, 0f)
        val p = ImageViewportGeometry.placement(rotated, view, true)!!
        near(view.centerX, rotated.centerX * p.scale + p.translateX)
        near(view.centerY, rotated.centerY * p.scale + p.translateY)
    }
    @Test fun fillCoversViewport() {
        val p = ImageViewportGeometry.placement(image, view, false)!!
        assertTrue(image.width * p.scale >= view.width && image.height * p.scale >= view.height)
    }
    @Test fun invalidDimensionsDeferPlacement() {
        assertNull(ImageViewportGeometry.placement(image, ViewportRect(0f, 0f, 0f, 0f), true))
        assertNull(ImageViewportGeometry.placement(ViewportRect(0f, 0f, Float.NaN, 3f), view, true))
        assertNull(ImageViewportGeometry.placement(ViewportRect(0f, 0f, 3f, -1f), view, true))
    }
    @Test fun panCentersSmallRotatedBounds() {
        val (x, y) = ImageViewportGeometry.panCorrection(ViewportRect(-200f, -320f, 0f, 0f), view)
        near(772f, x); near(1547f, y)
    }
    @Test fun panClampsLargeBoundsWithoutUsingMatrixScaleSign() {
        val (x, y) = ImageViewportGeometry.panCorrection(ViewportRect(50f, -300f, 2050f, 3000f), view)
        near(-50f, x); near(0f, y)
        val (right, bottom) = ImageViewportGeometry.panCorrection(ViewportRect(-1000f, -1000f, 1000f, 2300f), view)
        near(344f, right); near(474f, bottom)
    }
    @Test fun cropIntersectsImageAndViewportBeforeInsetting() {
        val cropped = ImageViewportGeometry.initialCrop(ViewportRect(-1000f, -1000f, 5000f, 5000f), view)!!
        contained(cropped, view); near(60f, cropped.left); near(60f, cropped.top)
    }
    @Test fun offscreenImageProducesNoCropInsteadOfOffscreenFallback() {
        assertNull(ImageViewportGeometry.initialCrop(ViewportRect(1747f, -91f, 3091f, 2059f), view))
    }
    @Test fun cropRespectsToolbarInsets() {
        val safe = ViewportRect(0f, 180f, 1344f, 2300f)
        val cropped = ImageViewportGeometry.initialCrop(view, safe)!!
        contained(cropped, safe); near(240f, cropped.top); near(2240f, cropped.bottom)
    }
    @Test fun tinyViewsAndThinImagesKeepPositiveCrop() {
        val tiny = ViewportRect(0f, 0f, 10f, 8f)
        contained(ImageViewportGeometry.initialCrop(tiny, tiny)!!, tiny)
        val thin = ViewportRect(600f, 100f, 602f, 2000f)
        contained(ImageViewportGeometry.initialCrop(thin, view)!!, thin)
    }
    @Test fun aspectPreservesCenterAndStaysWithinCrop() {
        val crop = ViewportRect(40f, 80f, 300f, 200f)
        for (ratio in listOf(1f, 4f / 3f, 16f / 9f)) {
            val result = ImageViewportGeometry.aspect(crop, ratio)
            contained(result, crop); near(ratio, result.width / result.height)
            near(crop.centerX, result.centerX); near(crop.centerY, result.centerY)
        }
        assertEquals(crop, ImageViewportGeometry.aspect(crop, Float.NaN))
    }
    @Test fun allCornersStayInsideVisibleBounds() {
        val bounds = ViewportRect(20f, 180f, 500f, 900f)
        val crop = ViewportRect(60f, 220f, 460f, 860f)
        for (handle in 0..3) for (xy in listOf(-1000f to -1000f, 2000f to 2000f)) {
            val result = ImageViewportGeometry.dragCrop(crop, handle, xy.first, xy.second, bounds)
            contained(result, bounds); assertTrue(result.width >= 50 && result.height >= 50)
        }
    }
    @Test fun wholeBoxDragClampsWithoutChangingSize() {
        val crop = ViewportRect(40f, 80f, 300f, 200f)
        for (xy in listOf(-1000f to -1000f, 5000f to 5000f)) {
            val result = ImageViewportGeometry.dragCrop(crop, 4, xy.first, xy.second, view)
            contained(result, view); near(crop.width, result.width); near(crop.height, result.height)
        }
    }
    @Test fun smallCropHandlesDoNotExpandPastImage() {
        val bounds = ViewportRect(1f, 2f, 4f, 5f)
        val crop = ImageViewportGeometry.initialCrop(bounds, ViewportRect(0f, 0f, 6f, 7f))!!
        for (handle in 0..4) contained(ImageViewportGeometry.dragCrop(crop, handle, -100f, 100f, bounds), bounds)
        assertEquals(crop, ImageViewportGeometry.dragCrop(crop, 4, Float.NaN, 0f, bounds))
    }
    @Test fun densityScaledDrawableMapsBackToSourcePixels() {
        assertEquals(ViewportRect(80f, 50f, 240f, 150f), ImageViewportGeometry.sourceCrop(
            ViewportRect(160f, 100f, 480f, 300f), 640, 400, 320, 200))
        assertNull(ImageViewportGeometry.sourceCrop(image, 0, 200, 320, 200))
    }
}
