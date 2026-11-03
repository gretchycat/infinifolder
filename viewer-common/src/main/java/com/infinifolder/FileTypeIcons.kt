package com.infinifolder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Typeface
import android.util.LruCache
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.preview.AudioIconFormats
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.viewer.MediaType
import com.infinifolder.viewer.MediaTypeDetector
import java.util.Locale
import com.caverock.androidsvg.SVG
import com.infinifolder.theme.BundledDefaultThemeInfo
import com.infinifolder.theme.BundledIconStyle
import com.infinifolder.storage.DiagnosticLog

/** Type-specific fallback artwork; never reads a document to draw its icon. */
object FileTypeIcons {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private val pictures = mutableMapOf<String, Picture?>()

    /** Records the canonical SVG once per base, then draws from memory at any size. */
    private fun drawBase(canvas: Canvas, base: String) {
        if (!pictures.containsKey(base)) pictures[base] = runCatching {
            val svg = SVG.getFromString(checkNotNull(BundledIconStyle.BASE_SVGS[base]))
            svg.setDocumentWidth(128f); svg.setDocumentHeight(128f)
            val picture = Picture()
            val recording = picture.beginRecording(128, 128)
            try { svg.renderToCanvas(recording) } finally { picture.endRecording() }
            picture
        }.onFailure { DiagnosticLog.record("icon.default.base.failed", "base=$base", it) }.getOrNull()
        pictures[base]?.let(canvas::drawPicture)
    }

    @Synchronized
    fun folder(requestedSize: Int, archive: Boolean = false): Bitmap {
        val size=requestedSize.coerceIn(1,1024)
        val key="folder:$archive:$size"
        cache.get(key)?.let { return it }
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(size/128f,size/128f)
        drawBase(canvas,if(archive)"archive" else "folder")
        cache.put(key,bitmap);return bitmap
    }

    private fun associatedExtension(name: String, mime: String): String? {
        val lower = name.lowercase(Locale.ROOT)
        var dot = lower.indexOf('.')
        while (dot >= 0) {
            val extension = lower.substring(dot + 1)
            if (BundledDefaultThemeInfo.EXTENSION_MIME_TYPES[extension] == mime) return extension
            dot = lower.indexOf('.', dot + 1)
        }
        return null
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
        val audio=AudioIconFormats.isAudio(mime)
        val audioLabel=if(audio)AudioIconFormats.label(meta.name,mime) else null
        val mappedExtension = associatedExtension(meta.name, mime)
        val base = mappedExtension?.let(BundledIconStyle.EXTENSION_BASES::get)
            ?: BundledIconStyle.MIME_BASES[mime] ?: when {
                audio -> "audio"
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                ArchiveFormats.isDefaultBrowsableArchive("", mime) -> "archive"
                text -> "document"
                else -> "binary"
            }
        val mimeLabel=when {
            audioLabel!=null -> audioLabel
            mime=="application/msword" -> "DOC"
            mime.contains("wordprocessingml") -> "DOCX"
            mime.contains("spreadsheetml") -> "XLSX"
            mime.contains("presentationml") -> "PPTX"
            mime=="application/vnd.android.package-archive" -> "APK"
            else -> mime.substringAfter('/').removePrefix("x-").removePrefix("vnd.")
        }
        val label = (audioLabel ?: mappedExtension ?: if(text)"TXT" else if(known)mimeLabel else extension.ifBlank {"FILE"}).uppercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }.take(8).ifBlank { "FILE" }
        val size = requestedSize.coerceIn(1, 1024)
        val key = "$base:$label:$size"
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 128f, size / 128f)
        drawBase(canvas, base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BundledIconStyle.labelColor(label) }
        canvas.drawRoundRect(5f, 96f, 123f, 123f, 8f, 8f, paint)
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        canvas.drawRoundRect(5f, 96f, 123f, 123f, 8f, 8f, paint)
        paint.style = Paint.Style.FILL; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 16f
        paint.textSize *= minOf(1f, 108f / paint.measureText(label).coerceAtLeast(1f))
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, 64f, 115f, paint)
        cache.put(key, bitmap)
        return bitmap
    }
}
