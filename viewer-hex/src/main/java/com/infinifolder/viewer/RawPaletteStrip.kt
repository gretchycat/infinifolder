package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** All palette entries, in index order, fitted into a single row without filtering. */
internal class RawPaletteStrip(context:Context):View(context) {
    private val paint=Paint()
    private var colors=IntArray(0)
    private var outlineColor=0

    fun palette(name:String,entries:IntArray){
        colors=entries.copyOf()
        contentDescription="$name: ${entries.size} colors in palette index order"
        invalidate()
    }

    fun outline(color:Int){
        if(outlineColor==color)return
        outlineColor=color;invalidate()
    }

    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(colors.isEmpty()||width<=0||height<=0)return
        paint.style=Paint.Style.FILL
        colors.forEachIndexed{index,color->
            paint.color=color
            // Shared integer edges avoid seams or blended colors between adjacent swatches.
            val left=(index.toLong()*width/colors.size).toFloat()
            val right=((index+1L)*width/colors.size).toFloat()
            canvas.drawRect(left,0f,right,height.toFloat(),paint)
        }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=outlineColor
        canvas.drawRect(.5f,.5f,width-.5f,height-.5f,paint)
    }
}
