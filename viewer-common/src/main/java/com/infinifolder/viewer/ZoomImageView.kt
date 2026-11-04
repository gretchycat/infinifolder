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
    /** Opt-in for a geometry explorer whose correctly proportioned drawing surface changes size. */
    var preserveZoomOnResize=false
    private var lastLayoutWidth=0
    private var lastLayoutHeight=0
    private var viewportLeft=0
    private var viewportTop=0
    private var viewportRight=0
    private var viewportBottom=0
    private var cropTopInset = 0
    private var cropBottomInset = 0
    private var scaleFactor = 1f
    private val minScale = 1f
    /** Maximum zoom relative to Fit. Explorers can opt into inspecting individual source pixels. */
    var maximumZoom=5f
        set(value){
            require(value.isFinite()&&value>=minScale){"Maximum zoom must be finite and at least 1"}
            field=value
            if(scaleFactor>value){
                val ratio=value/scaleFactor;scaleFactor=value
                val area=viewport()
                drawMatrix.postScale(ratio,ratio,area.centerX,area.centerY)
                checkBounds();publishMatrix()
            }
        }

    private var rotationAngle = 0f
    private var isFitMode = true
    /** Optional gallery navigation. Positive means next; zoomed gestures continue to pan. */
    var onHorizontalSwipe:((Int)->Unit)?=null
    var onImagePointer:((com.infinifolder.preview.ImagePointerKind,Float,Float)->Unit)?=null
    private var imagePointerActive=false
    var isUserInteracting=false
        private set
    private var swipeEligible=false
    private var swipeHandled=false
    private var touchStartX=0f
    private var touchStartY=0f

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
            scaleFactor = scaleFactor.coerceIn(minScale, maximumZoom)

            drawMatrix.postScale(
                scaleFactor / prevScale,
                scaleFactor / prevScale,
                detector.focusX.coerceIn(viewport().left,viewport().right),
                detector.focusY.coerceIn(viewport().top,viewport().bottom)
            )
            checkBounds()
            publishMatrix()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true

        override fun onSingleTapConfirmed(e:MotionEvent):Boolean=if(onImagePointer!=null)true else performClick()

        override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean =
            dispatchSwipe(e2.x,e2.y)

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isCropMode) return false
            if (scaleFactor > 1.2f) {
                resetZoom()
            } else {
                scaleFactor = minOf(2.5f,maximumZoom)
                drawMatrix.postScale(scaleFactor,scaleFactor,e.x,e.y)
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
            return swipeEligible
        }
    })

    private fun dispatchSwipe(x:Float,y:Float):Boolean {
        if(!swipeEligible || swipeHandled || isCropMode || scaleFactor>1.01f || !isFitMode)return false
        val dx=x-touchStartX;val dy=y-touchStartY
        if(kotlin.math.abs(dx)<48*resources.displayMetrics.density || kotlin.math.abs(dx)<kotlin.math.abs(dy)*1.5f)return false
        swipeHandled=true
        onHorizontalSwipe?.invoke(if(dx<0)1 else -1)
        return true
    }

    private fun dispatchImagePointer(kind:com.infinifolder.preview.ImagePointerKind,x:Float,y:Float){
        val listener=onImagePointer?:return
        val image=drawable?:return
        if(image.intrinsicWidth<=0 || image.intrinsicHeight<=0)return
        val inverse=Matrix();if(!drawMatrix.invert(inverse))return
        val point=floatArrayOf(x,y);inverse.mapPoints(point)
        listener(kind,point[0]/image.intrinsicWidth,point[1]/image.intrinsicHeight)
    }

    fun cancelUserInteraction(){
        if(imagePointerActive)dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.EXIT,-1f,-1f)
        imagePointerActive=false;isUserInteracting=false;swipeEligible=false;activeCropHandle=-1
    }

    override fun onHoverEvent(event:MotionEvent):Boolean {
        if(onImagePointer==null)return super.onHoverEvent(event)
        val kind=when(event.actionMasked){
            MotionEvent.ACTION_HOVER_ENTER->com.infinifolder.preview.ImagePointerKind.ENTER
            MotionEvent.ACTION_HOVER_MOVE->com.infinifolder.preview.ImagePointerKind.MOVE
            MotionEvent.ACTION_HOVER_EXIT->com.infinifolder.preview.ImagePointerKind.EXIT
            else->return super.onHoverEvent(event)
        }
        dispatchImagePointer(kind,event.x,event.y)
        return true
    }

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

    private fun viewport():ViewportRect {
        val left=viewportLeft.coerceAtMost(width).toFloat()
        val top=viewportTop.coerceAtMost(height).toFloat()
        return ViewportRect(left,top,(width-viewportRight).toFloat().coerceAtLeast(left),
            (height-viewportBottom).toFloat().coerceAtLeast(top))
    }

    /** Excludes menus/system bars from fitting, panning, drawing and gesture starts. */
    fun setViewportInsets(left:Int,top:Int,right:Int,bottom:Int) {
        val l=left.coerceAtLeast(0);val t=top.coerceAtLeast(0)
        val r=right.coerceAtLeast(0);val b=bottom.coerceAtLeast(0)
        if(l==viewportLeft && t==viewportTop && r==viewportRight && b==viewportBottom)return
        val before=viewport()
        viewportLeft=l;viewportTop=t;viewportRight=r;viewportBottom=b
        val after=viewport()
        val d=drawable
        if(resetPending || scaleFactor==1f || d==null || !before.valid || !after.valid) {
            resetZoom()
            return
        }
        val rotated=RectF(0f,0f,d.intrinsicWidth.toFloat(),d.intrinsicHeight.toFloat())
        Matrix().apply {postRotate(rotationAngle);mapRect(rotated)}
        val oldPlacement=ImageViewportGeometry.placement(rotated.geometry(),before,isFitMode)
        val newPlacement=ImageViewportGeometry.placement(rotated.geometry(),after,isFitMode)
        if(oldPlacement==null || newPlacement==null) {resetZoom();return}
        // Preserve the central image point and zoom relative to the fitted scene.
        val ratio=newPlacement.scale/oldPlacement.scale
        drawMatrix.postScale(ratio,ratio,before.centerX,before.centerY)
        drawMatrix.postTranslate(after.centerX-before.centerX,after.centerY-before.centerY)
        checkBounds()
        publishMatrix()
        if(isCropMode)initCropRect()
        recordViewport("active-area")
    }

    private fun mappedImageBounds(): RectF? {
        val d = drawable ?: return null
        return RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()).also { drawMatrix.mapRect(it) }
    }

    private fun cropViewport():ViewportRect {
        val area=viewport()
        return ViewportRect(area.left,maxOf(area.top,cropTopInset.toFloat()),area.right,
            minOf(area.bottom,(height-cropBottomInset).toFloat()))
    }

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

    /** Replace a render of the same scene, retaining its normalized location and magnification. */
    fun setImageBitmapPreservingViewport(bitmap: Bitmap) = setImageBitmapPreservingViewport(bitmap,false)

    /** Geometry exploration can change the scene's aspect ratio while retaining zoom and center. */
    fun setImageBitmapPreservingViewport(bitmap: Bitmap, preserveAspectRatio: Boolean) {
        val oldWidth=drawable?.intrinsicWidth ?: 0
        val oldHeight=drawable?.intrinsicHeight ?: 0
        val previous=Matrix(drawMatrix)
        val canRestore=!resetPending && width>0 && height>0 && oldWidth>0 && oldHeight>0
        super.setImageBitmap(bitmap)
        val next=drawable
        if(!canRestore || next==null || next.intrinsicWidth<=0 || next.intrinsicHeight<=0) {
            resetZoom()
            return
        }
        // New render pixels -> previous render pixels -> the unchanged screen viewport.
        // Keeping scaleFactor also keeps pinch/double-tap limits relative to the fitted scene.
        if(preserveAspectRatio){
            val area=viewport()
            val inverse=Matrix()
            if(!area.valid||!previous.invert(inverse)){resetZoom();return}
            val anchor=floatArrayOf(area.centerX,area.centerY)
            inverse.mapPoints(anchor)
            anchor[0]=anchor[0]/oldWidth*next.intrinsicWidth
            anchor[1]=anchor[1]/oldHeight*next.intrinsicHeight
            val bounds=RectF(0f,0f,next.intrinsicWidth.toFloat(),next.intrinsicHeight.toFloat())
            val rotated=Matrix().apply{postRotate(rotationAngle);mapRect(bounds)}
            val placement=ImageViewportGeometry.placement(bounds.geometry(),area,isFitMode)?:run{resetZoom();return}
            rotated.postScale(placement.scale*scaleFactor,placement.scale*scaleFactor)
            rotated.mapPoints(anchor)
            rotated.postTranslate(area.centerX-anchor[0],area.centerY-anchor[1])
            drawMatrix.set(rotated)
        }else {
            drawMatrix.setScale(oldWidth.toFloat()/next.intrinsicWidth,oldHeight.toFloat()/next.intrinsicHeight)
            drawMatrix.postConcat(previous)
        }
        resetPending=false
        checkBounds()
        publishMatrix()
        recordViewport("replace-render")
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
                "mapped=${mappedImageBounds()} activeViewport=${viewport()} crop=$cropRect cropViewport=${cropViewport()}")
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
        val before=ViewportRect(viewportLeft.toFloat(),viewportTop.toFloat(),
            (lastLayoutWidth-viewportRight).toFloat(),(lastLayoutHeight-viewportBottom).toFloat())
        super.onLayout(changed, left, top, right, bottom)
        if (changed || resetPending) {
            if(!preserveZoomOnResize||resetPending||!preserveResizedViewport(before))resetZoom()
        }
        lastLayoutWidth=width;lastLayoutHeight=height
    }

    private fun preserveResizedViewport(before:ViewportRect):Boolean {
        val after=viewport();val d=drawable?:return false
        if(!before.valid||!after.valid)return false
        val bounds=RectF(0f,0f,d.intrinsicWidth.toFloat(),d.intrinsicHeight.toFloat())
        Matrix().apply{postRotate(rotationAngle);mapRect(bounds)}
        val oldPlacement=ImageViewportGeometry.placement(bounds.geometry(),before,isFitMode)?:return false
        val newPlacement=ImageViewportGeometry.placement(bounds.geometry(),after,isFitMode)?:return false
        val ratio=newPlacement.scale/oldPlacement.scale
        drawMatrix.postScale(ratio,ratio,before.centerX,before.centerY)
        drawMatrix.postTranslate(after.centerX-before.centerX,after.centerY-before.centerY)
        checkBounds();publishMatrix();recordViewport("resize")
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            val area=viewport()
            if(!area.valid || event.x<area.left || event.x>=area.right || event.y<area.top || event.y>=area.bottom)return false
            isUserInteracting=true
            touchStartX=event.x;touchStartY=event.y;swipeHandled=false
            swipeEligible=onHorizontalSwipe!=null && onImagePointer==null && !isCropMode && isFitMode && scaleFactor<=1.01f
            imagePointerActive=onImagePointer!=null && !isCropMode
            if(imagePointerActive){
                dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.ENTER,event.x,event.y)
                dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.MOVE,event.x,event.y)
                dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.DOWN,event.x,event.y)
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)isUserInteracting=false
        if(event.pointerCount>1 || event.actionMasked==MotionEvent.ACTION_POINTER_DOWN){
            swipeEligible=false
            if(imagePointerActive)dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.EXIT,event.x,event.y)
            imagePointerActive=false
        }
        if(imagePointerActive){
            when(event.actionMasked){
                MotionEvent.ACTION_MOVE->dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.MOVE,event.x,event.y)
                MotionEvent.ACTION_UP->{
                    dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.UP,event.x,event.y)
                    dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.EXIT,event.x,event.y)
                    imagePointerActive=false
                }
                MotionEvent.ACTION_CANCEL->{dispatchImagePointer(com.infinifolder.preview.ImagePointerKind.EXIT,event.x,event.y);imagePointerActive=false}
            }
        }
        if (isCropMode) {
            handleCropTouch(event)
            return true
        }
        val scaleResult = scaleDetector.onTouchEvent(event)
        val gestureResult = gestureDetector.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP){
            val swiped=dispatchSwipe(event.x,event.y)
            swipeEligible=false
            if(swiped || swipeHandled)return true
        }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL)swipeEligible=false
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
        val area=viewport()
        if(!area.valid)return
        val saved=canvas.save()
        canvas.clipRect(area.left,area.top,area.right,area.bottom)
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
        canvas.restoreToCount(saved)
    }
}
