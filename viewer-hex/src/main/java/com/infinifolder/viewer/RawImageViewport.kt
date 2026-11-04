package com.infinifolder.viewer

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** The full preview accepts gestures; the image matrix preserves square source pixels. */
internal class RawImageViewport(context:Context,val image:ZoomImageView):FrameLayout(context){
    private var sourceWidth=128
    private var sourceHeight=128
    init {
        clipChildren=true;clipToPadding=true
        image.preserveZoomOnResize=true
        addView(image,LayoutParams(-1,-1,Gravity.CENTER))
    }
    fun resolution(width:Int,height:Int){
        require(width>0&&height>0)
        if(sourceWidth==width&&sourceHeight==height)return
        sourceWidth=width;sourceHeight=height
        image.contentDescription="Raw image explorer, $width by $height pixels"
        updateZoomLimit()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        updateZoomLimit()
    }
    private fun updateZoomLimit(){
        val size=RawImageViewportSize.fit(sourceWidth,sourceHeight,
            (width-paddingLeft-paddingRight).coerceAtLeast(0),
            (height-paddingTop-paddingBottom).coerceAtLeast(0))
        if(size.first==0||size.second==0)return
        val fittedPixelSize=minOf(size.first.toFloat()/sourceWidth,size.second.toFloat()/sourceHeight)
        val detailZoom=16f*resources.displayMetrics.density/fittedPixelSize
        // Allow at least 16 dp per source pixel, including very tall/narrow raw images.
        // Expanding the limit must not reduce an existing zoom when geometry changes.
        image.maximumZoom=maxOf(image.maximumZoom,detailZoom)
    }
}

internal object RawImageViewportSize {
    fun fit(width:Int,height:Int,availableWidth:Int,availableHeight:Int):Pair<Int,Int>{
        require(width>0&&height>0&&availableWidth>=0&&availableHeight>=0)
        if(availableWidth==0||availableHeight==0)return 0 to 0
        val scale=minOf(availableWidth.toDouble()/width,availableHeight.toDouble()/height)
        return (width*scale).roundToInt().coerceIn(1,availableWidth) to
            (height*scale).roundToInt().coerceIn(1,availableHeight)
    }
}
