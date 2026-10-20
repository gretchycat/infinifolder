package com.infinifolder.preview

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes display-only thumbnails for all supported image formats.
 *
 * Supported formats:
 * - Platform formats (JPEG/Ultra HDR, PNG/APNG, GIF, WebP, BMP, WBMP, ICO/CUR, HEIF/HEIC, AVIF,
 *   DNG, and camera RAW): decoded via [ImageDecoder] on API 28+ (with EXIF orientation and animated
 *   first-frame support), falling back to [BitmapFactory].
 * - Vector images (.svg, .svgz) via AndroidSVG.
 * - JPEG XL (.jxl) via JxlCoder.
 * - Truevision TGA (.tga, .icb, .vda, .vst) via [TgaDecoder].
 */
class ThumbnailDecoder(
    private val resolver: ContentResolver,
    private val streamOpener: ((Uri) -> InputStream?)? = null
) {
    private class OversizedImage : Exception()

    private fun openStream(uri: Uri): InputStream? =
        streamOpener?.invoke(uri) ?: try { resolver.openInputStream(uri) } catch (_: Exception) { null }

    /** Returns a bitmap whose longer side is about [targetPx], or null if the image cannot be previewed. */
    fun decode(uri: Uri, targetPx: Int): Bitmap? = try {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        when (ext) {
            "svg", "svgz" -> decodeSvg(uri, targetPx)
            "jxl" -> decodeJxl(uri, targetPx)
            "tga", "icb", "vda", "vst" -> decodeTga(uri, targetPx)
            else -> decodePlatform(uri, targetPx)
                ?: decodeSvg(uri, targetPx)
                ?: decodeJxl(uri, targetPx)
                ?: decodeTga(uri, targetPx)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decodePlatform(uri: Uri, targetPx: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 28 && (uri.scheme == "content" || uri.scheme == "android.resource")) {
            try { decodeWithImageDecoder(uri, targetPx) }
            catch (oversized: OversizedImage) { throw oversized }
            catch (_: Exception) { decodeWithBitmapFactory(uri, targetPx) }
        } else {
            decodeWithBitmapFactory(uri, targetPx)
        }
    }

    private fun decodeSvg(uri: Uri, targetPx: Int): Bitmap? {
        return try {
            openStream(uri)?.use { rawStream ->
                val bis = BufferedInputStream(rawStream)
                bis.mark(2)
                val b1 = bis.read()
                val b2 = bis.read()
                bis.reset()
                val stream = if (b1 == 0x1F && b2 == 0x8B) GZIPInputStream(bis) else bis

                val svg = SVG.getFromInputStream(stream)
                val rawViewBox = svg.documentViewBox
                val rawDocWidth = svg.documentWidth
                val rawDocHeight = svg.documentHeight

                val naturalWidth = when {
                    rawViewBox != null && rawViewBox.width() > 0f -> rawViewBox.width()
                    rawDocWidth > 0f -> rawDocWidth
                    else -> targetPx.toFloat()
                }
                val naturalHeight = when {
                    rawViewBox != null && rawViewBox.height() > 0f -> rawViewBox.height()
                    rawDocHeight > 0f -> rawDocHeight
                    else -> targetPx.toFloat()
                }

                if (rawViewBox == null) {
                    svg.setDocumentViewBox(0f, 0f, naturalWidth, naturalHeight)
                }
                // Ensure document attributes do not clip or override canvas dimensions
                svg.setDocumentWidth("100%")
                svg.setDocumentHeight("100%")

                val maxDim = max(naturalWidth, naturalHeight)
                val scale = if (maxDim > 0f) targetPx.toFloat() / maxDim else 1f
                val outW = (naturalWidth * scale).roundToInt().coerceAtLeast(1)
                val outH = (naturalHeight * scale).roundToInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                // Use LETTERBOX to guarantee the entire SVG graphic is fitted without cropping/slicing
                val renderOptions = RenderOptions.create()
                    .preserveAspectRatio(PreserveAspectRatio.LETTERBOX)
                svg.renderToCanvas(canvas, renderOptions)
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJxl(uri: Uri, targetPx: Int): Bitmap? {
        return try {
            openStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (!JxlCoder.isJXL(bytes)) return null
                val size = JxlCoder.getSize(bytes)
                if (size != null && (size.width <= 0 || size.height <= 0 || !withinLimits(size.width, size.height))) {
                    return null
                }
                JxlCoder.decodeSampled(
                    byteArray = bytes,
                    width = targetPx,
                    height = targetPx,
                    preferredColorConfig = PreferredColorConfig.RGBA_8888,
                    scaleMode = ScaleMode.FIT,
                    jxlResizeFilter = JxlResizeFilter.BILINEAR
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeTga(uri: Uri, targetPx: Int): Bitmap? {
        return try {
            openStream(uri)?.use { stream ->
                TgaDecoder.decode(stream, targetPx)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun withinLimits(width: Int, height: Int) = width in 1..MAX_SIDE && height in 1..MAX_SIDE &&
        width.toLong() * height.toLong() <= MAX_PIXELS

    @RequiresApi(28)
    private fun decodeWithImageDecoder(uri: Uri, targetPx: Int): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (!withinLimits(width, height)) throw OversizedImage()
            val scale = targetPx.toDouble() / max(width, height)
            if (scale < 1.0) {
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            }
            // Software bitmaps keep LRU accounting accurate and avoid exhausting graphics buffers in large folders.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Show whatever decoded from truncated or partially corrupt files instead of failing.
            decoder.setOnPartialImageListener { true }
        }
    }

    private fun decodeWithBitmapFactory(uri: Uri, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (!withinLimits(bounds.outWidth, bounds.outHeight)) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return applyExifOrientation(uri, bitmap)
    }

    /** BitmapFactory ignores EXIF orientation; rotate/flip so camera photos are upright. */
    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            openStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: return bitmap
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        const val MAX_SIDE = 40_000
        const val MAX_PIXELS = 160_000_000L
    }
}
