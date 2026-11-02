package com.infinifolder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import java.util.Locale

/** Local protocol artwork: drawing a server icon never contacts the server. */
object LinkIcons {
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun networkProtocol(uri: android.net.Uri, connections: List<com.infinifolder.storage.ProviderConnection>): String {
        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        val connection = connections.firstOrNull { it.id == uri.authority }
        return when {
            scheme == "ftp" && connection?.options?.get("useFtps") == "true" -> "FTPS"
            scheme == "webdav" && connection?.options?.get("tls") == "true" -> "DAVS"
            else -> scheme.ifBlank { "NETWORK" }
        }
    }
    @Synchronized
    fun protocol(rawProtocol: String, requestedSize: Int): Bitmap {
        val label = rawProtocol.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8).ifBlank { "LINK" }
        val size = requestedSize.coerceIn(1, 512)
        val key = "$label:$size"
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 64f, size / 64f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f; color = 0xff2979b8.toInt() }
        if (label in setOf("WEB", "HTTP", "HTTPS")) {
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(32f, 24f, 20f, paint)
            canvas.drawOval(22f, 4f, 42f, 44f, paint)
            canvas.drawLine(12f, 24f, 52f, 24f, paint)
            canvas.drawOval(14f, 14f, 50f, 34f, paint)
        } else if (label in setOf("FTP", "FTPS", "SFTP", "SSH", "SSHFS", "SMB", "CIFS", "WEBDAV", "DAV", "DAVS", "NFS")) {
            for (row in 0..2) {
                val y = 4f + row * 12f
                paint.style = Paint.Style.FILL; paint.color = 0xff244762.toInt()
                canvas.drawRoundRect(10f, y, 54f, y + 10f, 3f, 3f, paint)
                paint.color = 0xff69e1bd.toInt(); canvas.drawCircle(17f, y + 5f, 2f, paint)
                paint.color = 0xffa7c9dc.toInt(); canvas.drawLine(25f, y + 5f, 47f, y + 5f, paint)
            }
        } else {
            paint.style = Paint.Style.STROKE
            canvas.save(); canvas.rotate(-35f, 32f, 24f)
            canvas.drawRoundRect(13f, 15f, 36f, 32f, 8f, 8f, paint)
            canvas.drawRoundRect(28f, 15f, 51f, 32f, 8f, 8f, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.FILL; paint.color = 0xff185a86.toInt()
        canvas.drawRoundRect(3f, 43f, 61f, 61f, 3f, 3f, paint)
        paint.color = Color.WHITE; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 13f
        paint.textSize *= minOf(1f, 52f / paint.measureText(label).coerceAtLeast(1f))
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, 32f, 52f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f, paint)
        cache.put(key, bitmap)
        return bitmap
    }
}
