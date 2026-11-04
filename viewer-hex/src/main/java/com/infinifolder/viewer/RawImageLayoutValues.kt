package com.infinifolder.viewer

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Fixed positions and text heights keep changing byte counts from resizing the preview. */
internal class RawImageLayoutValues(context:Context):LinearLayout(context) {
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private inner class Value(label:String) {
        val caption=TextView(context).apply {
            text=label;textSize=10f;setLines(1);ellipsize=TextUtils.TruncateAt.END
        }
        val number=TextView(context).apply {
            text="—";textSize=12f;setLines(1);ellipsize=TextUtils.TruncateAt.END
            val metrics=paint.fontMetrics
            // Reserve the maximum font height before autosizing long numbers down to fit.
            height=maxOf(dp(22),ceil(metrics.bottom-metrics.top).toInt()+dp(2))
            setAutoSizeTextTypeUniformWithConfiguration(8,12,1,TypedValue.COMPLEX_UNIT_SP)
        }
        val view=LinearLayout(context).apply {
            orientation=VERTICAL;setPadding(dp(4),0,dp(4),0)
            addView(caption,LayoutParams(-1,-2));addView(number,LayoutParams(-1,-2))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            caption.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            number.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {display("—")}
        fun display(value:String){number.text=value;view.contentDescription="${caption.text}: $value"}
    }
    private val rows=Value("Rows")
    private val rowBytes=Value("Bytes / row")
    private val usedBytes=Value("Image bytes")
    private val availableBytes=Value("Available bytes")
    private val trailingBytes=Value("Trailing bytes")
    private val paddingBits=Value("Padding bits / row")
    private val values=listOf(rows,rowBytes,usedBytes,availableBytes,trailingBytes,paddingBits)

    init {
        orientation=VERTICAL
        values.chunked(3).forEach {cells->
            addView(LinearLayout(context).apply {
                cells.forEach {addView(it.view,LayoutParams(0,-2,1f))}
            },LayoutParams(-1,-2))
        }
    }
    fun clear(){values.forEach{it.display("—")}}
    fun show(rows:Int,rowBytes:Int,used:Long,available:Long,padding:Long,decoded:Boolean){
        availableBytes.caption.text=if(decoded)"Decoded bytes"else "Available bytes"
        this.rows.display(rows.toString());this.rowBytes.display(rowBytes.toString())
        usedBytes.display(used.toString());availableBytes.display(available.toString())
        trailingBytes.display((available-used).toString());paddingBits.display(padding.toString())
    }
    fun colors(primary:Int,secondary:Int){
        values.forEach{it.caption.setTextColor(secondary);it.number.setTextColor(primary)}
    }
}
