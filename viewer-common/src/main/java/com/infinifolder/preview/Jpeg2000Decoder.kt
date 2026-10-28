package com.infinifolder.preview

import android.graphics.Bitmap
import com.gemalto.jp2.JP2Decoder
import com.infinifolder.preview.ImageDecodeUtils.isValidDimensions
import com.infinifolder.preview.ImageDecodeUtils.u16be
import com.infinifolder.preview.ImageDecodeUtils.u32be
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes JPEG 2000 (.jp2, .j2k, .jpf, .jpx, .j2c) via OpenJPEG (JP2Decoder).
 */
object Jpeg2000Decoder {

    val extensions: Set<String> = setOf("jp2", "j2k", "jpf", "jpx", "j2c")

    fun isJpeg2000(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // Raw J2K codestream SOC+SIZ marker: FF 4F FF 51
        if (u16be(bytes, 0) == 0xFF4F && u16be(bytes, 2) == 0xFF51) return true
        // JP2 box signature: 00 00 00 0C 6A 50 20 20 0D 0A 87 0A
        if (bytes.size >= 12 && u32be(bytes, 0) == 12L &&
            String(bytes, 4, 4, StandardCharsets.US_ASCII) == "jP  "
        ) {
            return true
        }
        return false
    }

    fun decode(input: InputStream, targetPx: Int): Bitmap? = decode(input.readBytes(), targetPx)

    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (!isJpeg2000(bytes)) return null
        return try {
            val decoder = JP2Decoder(bytes)
            val header = decoder.readHeader()
            if (header != null && !isValidDimensions(header.width, header.height)) {
                return null
            }
            if (header != null && targetPx > 0) {
                val maxSide = max(header.width, header.height)
                var skipRes = 0
                while ((maxSide ushr (skipRes + 1)) >= targetPx && skipRes + 1 < header.numResolutions) {
                    skipRes++
                }
                if (skipRes > 0) {
                    decoder.setSkipResolutions(skipRes)
                }
            }
            val bitmap = decoder.decode() ?: return null
            val maxSide = max(bitmap.width, bitmap.height)
            if (targetPx <= 0 || maxSide <= targetPx) return bitmap

            val scale = targetPx.toFloat() / maxSide.toFloat()
            val dstW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val dstH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        } catch (_: Throwable) {
            null
        }
    }
}
