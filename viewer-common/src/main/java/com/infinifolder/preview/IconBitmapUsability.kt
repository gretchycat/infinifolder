package com.infinifolder.preview

import android.graphics.Bitmap

/** An entirely transparent render is not useful as an item icon; keep the fallback visible. */
object IconBitmapUsability {
    fun visible(bitmap:Bitmap):Boolean {
        val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height) {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            if(row.any {it ushr 24 != 0})return true
        }
        return false
    }
}
