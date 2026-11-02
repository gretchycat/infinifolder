package com.infinifolder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.LruCache
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.viewer.MediaType
import com.infinifolder.viewer.MediaTypeDetector
import java.util.Locale

/** Type-specific fallback artwork; never reads a document to draw its icon. */
object FileTypeIcons {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun folder(requestedSize: Int, archive: Boolean = false): Bitmap {
        val size=requestedSize.coerceIn(1,1024)
        val key="folder:$archive:$size"
        cache.get(key)?.let { return it }
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(size/64f,size/64f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xffe9b949.toInt() }
        val path=Path().apply { moveTo(3f,15f);lineTo(26f,15f);lineTo(32f,21f);lineTo(61f,21f);lineTo(57f,55f);lineTo(7f,55f);close() }
        canvas.drawPath(path,paint)
        if(archive) {
            paint.color=0xff72592d.toInt();canvas.drawRect(29f,21f,35f,55f,paint)
            paint.color=Color.WHITE
            for(y in 22..50 step 6)canvas.drawRect(30f,y.toFloat(),34f,y+3f,paint)
        }
        cache.put(key,bitmap);return bitmap
    }

    /** App-drawer artwork for Android's virtual installed-applications root. */
    @Synchronized
    fun installedApplications(requestedSize:Int):Bitmap {
        val size=requestedSize.coerceIn(1,1024)
        val key="installed-applications:$size"
        cache.get(key)?.let {return it}
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(size/64f,size/64f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val colours=intArrayOf(0xff56b6f2.toInt(),0xff8acb57.toInt(),0xffffb74d.toInt(),
            0xffb18be8.toInt(),0xff68c9c0.toInt(),0xffee8194.toInt(),
            0xff8acb57.toInt(),0xffffb74d.toInt(),0xff56b6f2.toInt())
        for(row in 0..2)for(col in 0..2) {
            val x=5f+col*19f;val y=5f+row*19f
            paint.color=colours[row*3+col]
            canvas.drawRoundRect(x,y,x+16f,y+16f,4f,4f,paint)
        }
        cache.put(key,bitmap);return bitmap
    }

    @Synchronized
    fun bitmap(meta: ResourceMetadata, requestedSize: Int, resolvedMime: String? = null): Bitmap {
        val mime = resolvedMime?.let(MimeRegistry::normalize) ?: MimeRegistry.effective(meta.name, meta.mimeType)
        val extension = meta.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val text = MediaTypeDetector.detect(if(resolvedMime==null)meta.name else "", mime) in setOf(MediaType.TEXT, MediaType.MARKDOWN, MediaType.HTML)
        val known=mime!="application/octet-stream"
        val type = if (text) "text/plain" else if(known)mime else extension.ifBlank { mime }
        val mimeLabel=when {
            mime=="application/msword" -> "DOC"
            mime.contains("wordprocessingml") -> "DOCX"
            mime.contains("spreadsheetml") -> "XLSX"
            mime.contains("presentationml") -> "PPTX"
            mime=="application/vnd.android.package-archive" -> "APK"
            else -> mime.substringAfter('/').removePrefix("x-").removePrefix("vnd.")
        }
        val label = (if(text)"TXT" else if(known)mimeLabel else extension.ifBlank {"FILE"}).uppercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' || it == '-' }.take(8).ifBlank { "FILE" }
        val size = requestedSize.coerceIn(1, 1024)
        val key = "$type:$label:$size"
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 64f, size / 64f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = Color.HSVToColor(floatArrayOf((type.hashCode().toLong() and 0x7fffffffL).rem(360).toFloat(), .65f, .65f))
        val page = Path().apply {
            moveTo(12f, 3f); lineTo(41f, 3f); lineTo(53f, 15f)
            lineTo(53f, 60f); lineTo(12f, 60f); close()
        }
        paint.color = Color.rgb(245, 247, 250)
        canvas.drawPath(page, paint)
        paint.color = accent
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        canvas.drawPath(page, paint)
        paint.style = Paint.Style.FILL
        canvas.drawPath(Path().apply { moveTo(41f, 3f); lineTo(41f, 15f); lineTo(53f, 15f); close() }, paint)
        canvas.drawRoundRect(4f, 30f, 60f, 53f, 3f, 3f, paint)
        paint.color = Color.WHITE; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 15f
        paint.textSize *= minOf(1f, 50f / paint.measureText(label).coerceAtLeast(1f))
        paint.textAlign = Paint.Align.CENTER
        val metrics = paint.fontMetrics
        canvas.drawText(label, 32f, 41.5f - (metrics.ascent + metrics.descent) / 2f, paint)
        cache.put(key, bitmap)
        return bitmap
    }
}
