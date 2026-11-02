package com.infinifolder.preview

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/** Shared asset lookup for the registry editor and the authoritative icon service. */
object IconOverrideAssets {
    fun decode(base:File,icons:CustomIconStore,decoder:ThumbnailDecoder,reference:String?,mime:String?,size:Int):Bitmap? {
        fun legacy(key:String)=icons.getMimeIconBitmap(key) {decoder.decode(Uri.fromFile(it),size)}
        val selected=when {
            reference==null -> null
            reference.startsWith("mime:") -> legacy(reference.removePrefix("mime:"))
            else -> {
                val file=File(base,reference)
                if(file.canonicalPath.startsWith(base.canonicalPath+File.separator))decoder.decode(Uri.fromFile(file),size) else null
            }
        }
        return selected ?: mime?.let(::legacy)
    }
}
