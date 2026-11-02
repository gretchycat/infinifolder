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

    @Synchronized
    fun bitmap(meta: ResourceMetadata, requestedSize: Int): Bitmap {
        val mime = MimeRegistry.effective(meta.name, meta.mimeType)
        val extension = meta.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val text = MediaTypeDetector.detect(meta.name, mime) in setOf(MediaType.TEXT, MediaType.MARKDOWN, MediaType.HTML)
        val type = if (text) "text/plain" else extension.ifBlank { mime }
        val label = if (text) "TXT" else extension.ifBlank {
            if (mime == "application/octet-stream") "FILE"
            else mime.substringAfter('/').removePrefix("x-").removePrefix("vnd.")
        }.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' || it == '-' }.take(8).ifBlank { "FILE" }
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
