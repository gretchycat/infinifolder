package com.infinifolder.viewer

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch

/** Native switches in two columns when their full labels fit, otherwise one column. */
internal class RawToggleGrid(context:Context,private val switches:List<Switch>):LinearLayout(context) {
    private var columns=0
    private var rowHeight=0
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()

    init {
        orientation=VERTICAL
        switches.forEach {
            it.textSize=14f;it.maxLines=2
            it.switchPadding=dp(8);it.setPadding(dp(4),0,dp(4),0)
            it.gravity=Gravity.CENTER_VERTICAL
        }
    }

    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val unspecified=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        var desiredCellWidth=dp(48)
        switches.forEach {
            it.measure(unspecified,unspecified)
            desiredCellWidth=maxOf(desiredCellWidth,it.measuredWidth)
        }
        val available=if(View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.UNSPECIFIED)
            desiredCellWidth*2 else (View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(0)
        val count=if(switches.size>1&&available>=desiredCellWidth*2)2 else 1
        val cellWidth=View.MeasureSpec.makeMeasureSpec(available/count,View.MeasureSpec.EXACTLY)
        var height=dp(48)
        switches.forEach {
            it.measure(cellWidth,unspecified)
            height=maxOf(height,it.measuredHeight)
        }
        if(columns!=count||rowHeight!=height){
            switches.forEach{(it.parent as? ViewGroup)?.removeView(it)}
            removeAllViews();columns=count;rowHeight=height
            switches.chunked(count).forEach {cells->
                addView(LinearLayout(context).apply {
                    cells.forEach{addView(it,LayoutParams(0,height,1f))}
                    repeat(count-cells.size){addView(View(context),LayoutParams(0,height,1f))}
                },LayoutParams(-1,height))
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
