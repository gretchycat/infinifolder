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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
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

    private fun openStream(uri: Uri, cachedBytes: ByteArray? = null): InputStream? =
        if (cachedBytes != null) ByteArrayInputStream(cachedBytes)
        else streamOpener?.invoke(uri) ?: try { resolver.openInputStream(uri) } catch (_: Exception) { null }

    private fun isDirectUri(uri: Uri): Boolean =
        uri.scheme == "content" ||
            uri.scheme == "android.resource" ||
            (uri.scheme == "file" && !uri.path.isNullOrEmpty())

    private fun extensionOf(uri: Uri): String {
        val rawPath = if (uri.scheme == "archive") {
            uri.getQueryParameter("path")?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment
        } else {
            uri.lastPathSegment ?: uri.path
        }
        val fileName = rawPath?.substringAfterLast('/') ?: return ""
        return fileName.substringAfterLast('.', "").lowercase()
    }

    private fun readStreamBytes(uri: Uri): ByteArray? = try {
        openStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_STREAM_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    private fun readBytesForCustomDecoder(uri: Uri, cachedBytes: ByteArray?): ByteArray? =
        cachedBytes ?: readStreamBytes(uri)

    private fun decodeCustomFormat(ext: String, uri: Uri, targetPx: Int, cachedBytes: ByteArray?): Bitmap? {
        return when (ext) {
            "svg", "svgz" -> decodeSvg(uri, targetPx, cachedBytes)
            "jxl" -> decodeJxl(uri, targetPx, cachedBytes)
            "jp2", "j2k", "jpf", "jpx", "j2c" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { Jpeg2000Decoder.decode(it, targetPx) }
            "tga", "icb", "vda", "vst" -> decodeTga(uri, targetPx, cachedBytes)
            "qoi", "ff" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { SimpleLosslessImageDecoder.decode(it, targetPx) }
            "pbm", "pgm", "ppm", "pnm", "pam", "pfm" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { NetpbmDecoder.decode(it, targetPx) }
            "xbm", "xpm", "xwd" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { X11ImageDecoder.decode(it, targetPx) }
            "pcx", "dcx" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { PcxDecoder.decode(it, targetPx) }
            "ora", "kra", "psd", "psb", "xcf", "ase", "aseprite" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { LayeredImageDecoder.decode(it, targetPx, ext) }
            "tif", "tiff" -> readBytesForCustomDecoder(uri, cachedBytes)?.let { TiffAndRawImageDecoder.decode(it, targetPx) }
                ?: decodePlatform(uri, targetPx, cachedBytes)
            "dng" -> decodePlatform(uri, targetPx, cachedBytes)
                ?: readBytesForCustomDecoder(uri, cachedBytes)?.let { TiffAndRawImageDecoder.decode(it, targetPx) }
            "iff", "ilbm", "lbm", "neo", "pi1", "pi2", "pi3", "pc1", "pc2", "pc3",
            "scr", "kla", "koa", "icns", "pntg", "mac", "pct", "pict",
            "ras", "sun", "sgi", "rgb", "bw", "rgba", "pdb", "otb", "sixel", "six" ->
                readBytesForCustomDecoder(uri, cachedBytes)?.let { RetroImageDecoder.decode(it, targetPx, ext) }
            "hdr", "exr", "dds", "ktx", "fits", "fit", "fts", "dcm", "dicom" ->
                readBytesForCustomDecoder(uri, cachedBytes)?.let { HdrAndScientificImageDecoder.decode(it, targetPx, ext) }
            "wmf", "wmz", "ps", "eps", "ept", "ans" ->
                readBytesForCustomDecoder(uri, cachedBytes)?.let { MetafileAndVectorImageDecoder.decode(it, targetPx, ext) }
            else -> null
        }
    }

    /** Returns a bitmap whose longer side is about [targetPx], or null if the image cannot be previewed. */
    fun decode(uri: Uri, targetPx: Int): Bitmap? = try {
        val ext = extensionOf(uri)
        val cachedBytes = if (!isDirectUri(uri)) readStreamBytes(uri) ?: return null else null
        decodeCustomFormat(ext, uri, targetPx, cachedBytes) ?: run {
            decodePlatform(uri, targetPx, cachedBytes) ?: run {
                val bytes = readBytesForCustomDecoder(uri, cachedBytes) ?: return@run null
                decodeSvg(uri, targetPx, bytes)
                    ?: decodeJxl(uri, targetPx, bytes)
                    ?: Jpeg2000Decoder.decode(bytes, targetPx)
                    ?: SimpleLosslessImageDecoder.decode(bytes, targetPx)
                    ?: NetpbmDecoder.decode(bytes, targetPx)
                    ?: TiffAndRawImageDecoder.decode(bytes, targetPx)
                    ?: LayeredImageDecoder.decode(bytes, targetPx, ext)
                    ?: PcxDecoder.decode(bytes, targetPx)
                    ?: HdrAndScientificImageDecoder.decode(bytes, targetPx, ext)
                    ?: X11ImageDecoder.decode(bytes, targetPx)
                    ?: RetroImageDecoder.decode(bytes, targetPx, ext)
                    ?: MetafileAndVectorImageDecoder.decode(bytes, targetPx, ext)
                    ?: decodeTga(uri, targetPx, bytes)
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decodePlatform(uri: Uri, targetPx: Int, cachedBytes: ByteArray? = null): Bitmap? {
        if (cachedBytes != null) {
            return if (Build.VERSION.SDK_INT >= 28) {
                try { decodeBytesWithImageDecoder(cachedBytes, targetPx) }
                catch (oversized: OversizedImage) { throw oversized }
                catch (_: Exception) { decodeBytesWithBitmapFactory(cachedBytes, targetPx) }
            } else {
                decodeBytesWithBitmapFactory(cachedBytes, targetPx)
            }
        }
        val canUseImageDecoder = Build.VERSION.SDK_INT >= 28 && isDirectUri(uri)
        return if (canUseImageDecoder) {
            try { decodeWithImageDecoder(uri, targetPx) }
            catch (oversized: OversizedImage) { throw oversized }
            catch (_: Exception) { decodeWithBitmapFactory(uri, targetPx) }
        } else {
            decodeWithBitmapFactory(uri, targetPx)
        }
    }

    private fun decodeSvg(uri: Uri, targetPx: Int, cachedBytes: ByteArray? = null): Bitmap? {
        return try {
            openStream(uri, cachedBytes)?.use { rawStream ->
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

    private fun decodeJxl(uri: Uri, targetPx: Int, cachedBytes: ByteArray? = null): Bitmap? {
        return try {
            val bytes = cachedBytes ?: openStream(uri)?.use { it.readBytes() } ?: return null
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
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeTga(uri: Uri, targetPx: Int, cachedBytes: ByteArray? = null): Bitmap? {
        return try {
            openStream(uri, cachedBytes)?.use { stream ->
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
        val source = if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
            ImageDecoder.createSource(java.io.File(uri.path!!))
        } else {
            ImageDecoder.createSource(resolver, uri)
        }
        return decodeSourceWithImageDecoder(source, targetPx)
    }

    @RequiresApi(28)
    private fun decodeBytesWithImageDecoder(bytes: ByteArray, targetPx: Int): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return decodeSourceWithImageDecoder(source, targetPx)
    }

    @RequiresApi(28)
    private fun decodeSourceWithImageDecoder(source: ImageDecoder.Source, targetPx: Int): Bitmap {
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

    private fun decodeBytesWithBitmapFactory(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!withinLimits(bounds.outWidth, bounds.outHeight)) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return applyExifOrientationFromStream(ByteArrayInputStream(bytes), bitmap)
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
        val stream = openStream(uri) ?: return bitmap
        return stream.use { applyExifOrientationFromStream(it, bitmap) }
    }

    private fun applyExifOrientationFromStream(stream: InputStream, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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
        const val MAX_STREAM_BYTES = 32 * 1024 * 1024
    }
}
