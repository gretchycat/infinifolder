package com.infinifolder.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

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

    private val matrixValues = FloatArray(9)
    private val drawMatrix = Matrix()

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
            imageMatrix = drawMatrix
            invalidate()
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
                imageMatrix = drawMatrix
                invalidate()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isCropMode) return false
            if (scaleFactor > 1f) {
                drawMatrix.postTranslate(-distanceX, -distanceY)
                checkBounds()
                imageMatrix = drawMatrix
                invalidate()
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

    fun resetZoom() {
        scaleFactor = 1f
        drawMatrix.reset()
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val dW = d.intrinsicWidth.toFloat()
        val dH = d.intrinsicHeight.toFloat()
        if (dW <= 0f || dH <= 0f) return

        // Handle rotation
        if (rotationAngle != 0f) {
            drawMatrix.postRotate(rotationAngle, dW / 2f, dH / 2f)
        }

        val rotatedRect = RectF(0f, 0f, dW, dH)
        val rotM = Matrix().apply { postRotate(rotationAngle) }
        rotM.mapRect(rotatedRect)
        val rotW = rotatedRect.width()
        val rotH = rotatedRect.height()

        val scale = if (isFitMode) {
            min(viewW / rotW, viewH / rotH)
        } else {
            max(viewW / rotW, viewH / rotH)
        }
        drawMatrix.postScale(scale, scale)

        // Center on screen
        val dx = (viewW - rotW * scale) / 2f - rotatedRect.left * scale
        val dy = (viewH - rotH * scale) / 2f - rotatedRect.top * scale
        drawMatrix.postTranslate(dx, dy)

        imageMatrix = drawMatrix
        if (isCropMode) initCropRect()
        invalidate()
    }

    private fun checkBounds() {
        val d = drawable ?: return
        drawMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val currentScale = matrixValues[Matrix.MSCALE_X]

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = d.intrinsicWidth * currentScale
        val imgH = d.intrinsicHeight * currentScale

        var deltaX = 0f
        var deltaY = 0f

        if (imgW < viewW) {
            deltaX = (viewW - imgW) / 2f - transX
        } else if (transX > 0) {
            deltaX = -transX
        } else if (transX + imgW < viewW) {
            deltaX = viewW - (transX + imgW)
        }

        if (imgH < viewH) {
            deltaY = (viewH - imgH) / 2f - transY
        } else if (transY > 0) {
            deltaY = -transY
        } else if (transY + imgH < viewH) {
            deltaY = viewH - (transY + imgH)
        }

        drawMatrix.postTranslate(deltaX, deltaY)
    }

    private fun initCropRect() {
        val d = drawable ?: return
        val mappedRect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(mappedRect)

        val margin = 40f
        cropRect.set(
            max(mappedRect.left + margin, 20f),
            max(mappedRect.top + margin, 20f),
            min(mappedRect.right - margin, width - 20f),
            min(mappedRect.bottom - margin, height - 20f)
        )
        if (cropRect.right <= cropRect.left) cropRect.right = cropRect.left + 100f
        if (cropRect.bottom <= cropRect.top) cropRect.bottom = cropRect.top + 100f
    }

    fun setCropAspectRatio(aspectW: Float, aspectH: Float) {
        if (aspectW <= 0f || aspectH <= 0f) return
        val targetRatio = aspectW / aspectH
        val curW = cropRect.width()
        val curH = cropRect.height()
        val curRatio = curW / curH

        if (curRatio > targetRatio) {
            val newW = curH * targetRatio
            val cx = cropRect.centerX()
            cropRect.left = cx - newW / 2f
            cropRect.right = cx + newW / 2f
        } else {
            val newH = curW / targetRatio
            val cy = cropRect.centerY()
            cropRect.top = cy - newH / 2f
            cropRect.bottom = cy + newH / 2f
        }
        invalidate()
    }

    fun getCroppedBitmap(sourceBitmap: Bitmap): Bitmap? {
        val d = drawable ?: return null
        val inv = Matrix()
        if (!drawMatrix.invert(inv)) return null

        val mappedCrop = RectF()
        inv.mapRect(mappedCrop, cropRect)

        val left = mappedCrop.left.toInt().coerceIn(0, sourceBitmap.width - 1)
        val top = mappedCrop.top.toInt().coerceIn(0, sourceBitmap.height - 1)
        val right = mappedCrop.right.toInt().coerceIn(left + 1, sourceBitmap.width)
        val bottom = mappedCrop.bottom.toInt().coerceIn(top + 1, sourceBitmap.height)

        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return null

        return Bitmap.createBitmap(sourceBitmap, left, top, cropW, cropH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
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
                when (activeCropHandle) {
                    0 -> { cropRect.left = min(x, cropRect.right - 50f); cropRect.top = min(y, cropRect.bottom - 50f) }
                    1 -> { cropRect.right = max(x, cropRect.left + 50f); cropRect.top = min(y, cropRect.bottom - 50f) }
                    2 -> { cropRect.right = max(x, cropRect.left + 50f); cropRect.bottom = max(y, cropRect.top + 50f) }
                    3 -> { cropRect.left = min(x, cropRect.right - 50f); cropRect.bottom = max(y, cropRect.top + 50f) }
                    4 -> {
                        // Drag whole crop rect
                        val dx = x - cropRect.centerX()
                        val dy = y - cropRect.centerY()
                        cropRect.offset(dx, dy)
                    }
                }
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
