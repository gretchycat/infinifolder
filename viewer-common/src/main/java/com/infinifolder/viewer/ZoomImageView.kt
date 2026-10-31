package com.infinifolder.viewer

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView

/**
 * High-performance zoomable, pannable, and editable ImageView.
 * Supports pinch-to-zoom, double-tap zoom, fit/fill modes, rotation,
 * interactive crop rectangle, and color filter preview.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()

    private var resetPending = false
    private var cropTopInset = 0
    private var cropBottomInset = 0
    private var scaleFactor = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var rotationAngle = 0f
    private var isFitMode = true

    // Editing mode flags
    var isCropMode = false
        set(value) {
            field = value
            if (value) initCropRect()
            invalidate()
        }

    val cropRect = RectF()
    private val cropPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cropShadePaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val cropHandlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var activeCropHandle = -1
    private val HANDLE_RADIUS = 30f

    // Color adjustment parameters
    var brightnessOffset = 0f // -100..+100
        set(value) { field = value; updateColorFilter() }
    var contrastRatio = 1f // 0.2..3.0
        set(value) { field = value; updateColorFilter() }
    var saturationRatio = 1f // 0.0..2.0
        set(value) { field = value; updateColorFilter() }
    var isGrayscale = false
        set(value) { field = value; updateColorFilter() }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isCropMode) return false
            val prevScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(minScale, maxScale)

            drawMatrix.postScale(
                scaleFactor / prevScale,
                scaleFactor / prevScale,
                detector.focusX,
                detector.focusY
            )
            checkBounds()
            publishMatrix()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isCropMode) return false
            if (scaleFactor > 1.2f) {
                resetZoom()
            } else {
                scaleFactor = 2.5f
                drawMatrix.postScale(2.5f, 2.5f, e.x, e.y)
                checkBounds()
                publishMatrix()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isCropMode) return false
            if (scaleFactor > 1f) {
                drawMatrix.postTranslate(-distanceX, -distanceY)
                checkBounds()
                publishMatrix()
                return true
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    private fun updateColorFilter() {
        val cm = ColorMatrix()
        // Saturation
        if (isGrayscale) {
            cm.setSaturation(0f)
        } else {
            cm.setSaturation(saturationRatio)
        }

        // Contrast & Brightness
        // R' = contrast * (R + brightness)
        val scale = contrastRatio
        val translate = brightnessOffset * 1.5f + (1f - scale) * 128f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrastMatrix)
        colorFilter = ColorMatrixColorFilter(cm)
        invalidate()
    }

    fun rotate90(clockwise: Boolean = true) {
        val angle = if (clockwise) 90f else -90f
        rotationAngle = (rotationAngle + angle) % 360f
        resetZoom()
    }

    fun toggleFitFill() {
        isFitMode = !isFitMode
        resetZoom()
    }

    private fun RectF.geometry() = ViewportRect(left, top, right, bottom)

    private fun RectF.setGeometry(rect: ViewportRect?) {
        if (rect == null) setEmpty() else set(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun viewport() = ViewportRect(0f, 0f, width.toFloat(), height.toFloat())

    private fun mappedImageBounds(): RectF? {
        val d = drawable ?: return null
        return RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()).also { drawMatrix.mapRect(it) }
    }

    private fun cropViewport() = ViewportRect(0f, cropTopInset.toFloat(), width.toFloat(), (height - cropBottomInset).toFloat())

    fun setCropViewportInsets(top: Int, bottom: Int) {
        cropTopInset = top.coerceAtLeast(0)
        cropBottomInset = bottom.coerceAtLeast(0)
        if (isCropMode) initCropRect()
    }

    private fun publishMatrix() {
        // Publish a completed matrix, including after a same-size bitmap replacement.
        super.setImageMatrix(Matrix(drawMatrix))
        postInvalidateOnAnimation()
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        super.setImageBitmap(bitmap)
        resetZoom()
    }

    fun resetZoom() {
        resetPending = true
        val d = drawable ?: return
        val bounds = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        // Rotation and its bounds must use the same origin. Mixing center and origin
        // rotation used to translate quarter-turn images completely off screen.
        val next = Matrix().apply { postRotate(rotationAngle) }
        next.mapRect(bounds)
        val placement = ImageViewportGeometry.placement(bounds.geometry(), viewport(), isFitMode) ?: return
        next.postScale(placement.scale, placement.scale)
        next.postTranslate(placement.translateX, placement.translateY)
        drawMatrix.set(next)
        scaleFactor = 1f
        resetPending = false
        publishMatrix()
        if (isCropMode) initCropRect()
        recordViewport("reset")
    }

    private fun checkBounds() {
        val image = mappedImageBounds() ?: return
        val (dx, dy) = ImageViewportGeometry.panCorrection(image.geometry(), viewport())
        drawMatrix.postTranslate(dx, dy)
    }

    private fun initCropRect() {
        cropRect.setGeometry(mappedImageBounds()?.let {
            ImageViewportGeometry.initialCrop(it.geometry(), cropViewport())
        })
        recordViewport("crop")
        invalidate()
    }

    fun setCropAspectRatio(aspectW: Float, aspectH: Float) {
        if (!aspectW.isFinite() || !aspectH.isFinite() || aspectW <= 0f || aspectH <= 0f) return
        cropRect.setGeometry(ImageViewportGeometry.aspect(cropRect.geometry(), aspectW / aspectH))
        invalidate()
    }

    private fun recordViewport(reason: String) {
        val values = FloatArray(9).also { drawMatrix.getValues(it) }
        com.infinifolder.storage.DiagnosticLog.record("ui.image.viewport",
            "reason=$reason view=${width}x$height drawable=${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight} " +
                "rotation=$rotationAngle fit=$isFitMode pending=$resetPending matrix=${values.contentToString()} " +
                "mapped=${mappedImageBounds()} crop=$cropRect cropViewport=${cropViewport()}")
    }

    /** UI maps the display rectangle; Canvas owns source-pixel clamping and cropping. */
    fun getCropBounds(): com.infinifolder.preview.ImageCropBounds? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        return getCropBounds(bitmap.width, bitmap.height)
    }

    private fun getCropBounds(pixelWidth: Int, pixelHeight: Int): com.infinifolder.preview.ImageCropBounds? {
        val d = drawable ?: return null
        if (cropRect.isEmpty) return null
        val inv = Matrix()
        if (!drawMatrix.invert(inv)) return null
        val mappedCrop = RectF()
        inv.mapRect(mappedCrop, cropRect)
        // Drawable coordinates may be density-scaled; Canvas expects source pixels.
        val bounds = ImageViewportGeometry.sourceCrop(mappedCrop.geometry(), d.intrinsicWidth,
            d.intrinsicHeight, pixelWidth, pixelHeight) ?: return null
        return com.infinifolder.preview.ImageCropBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    /** Compatibility for existing callers; new consumers keep the resulting frame metadata. */
    fun getCroppedBitmap(sourceBitmap: Bitmap): Bitmap? {
        val bounds = getCropBounds(sourceBitmap.width, sourceBitmap.height) ?: return null
        return com.infinifolder.preview.ImageTransforms.apply(com.infinifolder.preview.BitmapFrame(sourceBitmap),
            com.infinifolder.preview.ImageTransform.Crop(bounds)).use { it.takeBitmap() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || resetPending) {
            resetZoom()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isCropMode) {
            handleCropTouch(event)
            return true
        }
        val scaleResult = scaleDetector.onTouchEvent(event)
        val gestureResult = gestureDetector.onTouchEvent(event)
        return scaleResult || gestureResult || super.onTouchEvent(event)
    }

    private fun handleCropTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeCropHandle = when {
                    isNear(x, y, cropRect.left, cropRect.top) -> 0 // TL
                    isNear(x, y, cropRect.right, cropRect.top) -> 1 // TR
                    isNear(x, y, cropRect.right, cropRect.bottom) -> 2 // BR
                    isNear(x, y, cropRect.left, cropRect.bottom) -> 3 // BL
                    cropRect.contains(x, y) -> 4 // Drag inside
                    else -> -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val bounds = mappedImageBounds()?.let {
                    ImageViewportGeometry.visibleBounds(it.geometry(), cropViewport())
                }
                if (bounds != null) cropRect.setGeometry(ImageViewportGeometry.dragCrop(
                    cropRect.geometry(), activeCropHandle, x, y, bounds))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCropHandle = -1
            }
        }
    }

    private fun isNear(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy <= HANDLE_RADIUS * HANDLE_RADIUS * 4
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isCropMode && cropRect.width() > 0 && cropRect.height() > 0) {
            // Draw shaded mask outside crop rect
            canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, cropShadePaint)
            canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), cropShadePaint)
            canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, cropShadePaint)
            canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, cropShadePaint)

            // Draw crop border
            canvas.drawRect(cropRect, cropPaint)

            // Draw handles at 4 corners
            canvas.drawCircle(cropRect.left, cropRect.top, HANDLE_RADIUS / 2f, cropHandlePaint)
            canvas.drawCircle(cropRect.right, cropRect.top, HANDLE_RADIUS / 2f, cropHandlePaint)
            canvas.drawCircle(cropRect.right, cropRect.bottom, HANDLE_RADIUS / 2f, cropHandlePaint)
            canvas.drawCircle(cropRect.left, cropRect.bottom, HANDLE_RADIUS / 2f, cropHandlePaint)
        }
    }
}
