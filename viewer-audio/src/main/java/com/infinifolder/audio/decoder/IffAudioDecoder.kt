package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object IffAudioDecoder {
    private val FIBONACCI_DELTA = intArrayOf(
        -34, -21, -13, -8, -5, -3, -2, -1,
        0, 1, 2, 3, 5, 8, 13, 21
    )

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != "FORM") return false
        val formType = String(bytes, 8, 4, Charsets.US_ASCII)
        return formType == "8SVX" || formType == "16SV" || formType == "MAUD"
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        val formType = String(bytes, 8, 4, Charsets.US_ASCII)

        var pos = 12
        var sampleRate = 22050
        var channels = 1
        var bits = 8
        var isFibonacci = false
        var bodyOffset = -1
        var bodyLength = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val chunkDataPos = pos + 8
            pos += 8 + chunkSize.toInt()
            if ((chunkSize and 1L) == 1L) pos++ // IFF pad byte

            when (chunkId) {
                "VHDR" -> {
                    if (chunkDataPos + 16 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, 16).order(ByteOrder.BIG_ENDIAN)
                        val oneShot = bb.int.toLong() and 0xFFFFFFFFL
                        val repeat = bb.int.toLong() and 0xFFFFFFFFL
                        val spc = bb.int.toLong() and 0xFFFFFFFFL
                        val sr = bb.short.toInt() and 0xFFFF
                        val ctOctave = bb.get().toInt() and 0xFF
                        val compression = bb.get().toInt() and 0xFF

                        sampleRate = if (sr > 0) sr else 22050
                        isFibonacci = compression == 1
                    }
                }
                "CHAN" -> {
                    if (chunkDataPos + 4 <= bytes.size) {
                        val chanMask = ByteBuffer.wrap(bytes, chunkDataPos, 4).order(ByteOrder.BIG_ENDIAN).int
                        channels = if (chanMask == 6) 2 else 1
                    }
                }
                "BODY" -> {
                    bodyOffset = chunkDataPos
                    bodyLength = chunkSize.toInt().coerceAtMost(bytes.size - chunkDataPos)
                }
                "MHDR" -> { // MAUD header
                    if (chunkDataPos + 20 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, 20).order(ByteOrder.BIG_ENDIAN)
                        bb.int // skip total samples (bytes 0..3)
                        val sampleSize = bb.short.toInt() and 0xFFFF // bytes 4..5
                        bits = bb.short.toInt() and 0xFFFF // bytes 6..7
                        val sr = bb.int // bytes 8..11
                        sampleRate = if (sr > 0) sr else 44100
                        bb.short // compression (bytes 12..13)
                        bb.short // format (bytes 14..15)
                        channels = (bb.short.toInt() and 0xFFFF).coerceIn(1, 2) // channels (bytes 16..17)
                    }
                }
                "MDAT" -> { // MAUD data
                    bodyOffset = chunkDataPos
                    bodyLength = chunkSize.toInt().coerceAtMost(bytes.size - chunkDataPos)
                }
            }
        }

        if (bodyOffset < 0 || bodyLength <= 0) return null

        if (formType == "MAUD") {
            return if (bits == 16) {
                AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, bodyOffset, bodyLength)
            } else {
                val sub = bytes.copyOfRange(bodyOffset, bodyOffset + bodyLength)
                AudioPcmData.from8BitSigned(sub, sampleRate, channels)
            }
        }

        if (formType == "16SV") {
            return AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, bodyOffset, bodyLength)
        }

        // Standard 8SVX
        if (!isFibonacci) {
            val sub = bytes.copyOfRange(bodyOffset, bodyOffset + bodyLength)
            return AudioPcmData.from8BitSigned(sub, sampleRate, channels)
        }

        // Fibonacci Delta decompression
        val decompressed = decompressFibonacci(bytes, bodyOffset, bodyLength, channels)
        return AudioPcmData.from8BitSigned(decompressed, sampleRate, channels)
    }

    private fun decompressFibonacci(bytes: ByteArray, offset: Int, length: Int, channels: Int): ByteArray {
        val outSize = (length - 2 * channels).coerceAtLeast(0) * 2
        val out = ByteArray(outSize)
        var outIdx = 0
        var inIdx = offset

        for (ch in 0 until channels) {
            if (inIdx >= offset + length) break
            var currentSample = bytes[inIdx++].toInt()
            val chLen = (length / channels) - 1
            for (i in 0 until chLen) {
                if (inIdx >= offset + length) break
                val b = bytes[inIdx++].toInt() and 0xFF
                val hi = (b shr 4) and 0x0F
                val lo = b and 0x0F

                currentSample = (currentSample + FIBONACCI_DELTA[hi]).coerceIn(-128, 127)
                if (outIdx < out.size) out[outIdx++] = currentSample.toByte()

                currentSample = (currentSample + FIBONACCI_DELTA[lo]).coerceIn(-128, 127)
                if (outIdx < out.size) out[outIdx++] = currentSample.toByte()
            }
        }

        return out
    }
}
