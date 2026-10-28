package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wave64AndRf64Decoder {

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 36) return false
        // RF64 check
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'F'.code.toByte() &&
            bytes[2] == '6'.code.toByte() && bytes[3] == '4'.code.toByte()) {
            return true
        }
        // Wave64 check: first 4 bytes of GUID are "riff" in ASCII
        if (bytes[0] == 'r'.code.toByte() && bytes[1] == 'i'.code.toByte() &&
            bytes[2] == 'f'.code.toByte() && bytes[3] == 'f'.code.toByte() &&
            bytes[4] == 0x2E.toByte() && bytes[5] == 0x91.toByte()) {
            return true
        }
        return false
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'F'.code.toByte()) {
            return decodeRf64(bytes)
        }
        return decodeWave64(bytes)
    }

    private fun decodeRf64(bytes: ByteArray): AudioPcmData? {
        var pos = 12
        var channels = 2
        var sampleRate = 44100
        var bitsPerSample = 16
        var formatTag = 1
        var dataOffset = -1
        var dataSize = 0L

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize32 = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val chunkDataPos = pos + 8
            val nextPos = chunkDataPos + (if (chunkSize32 != 0xFFFFFFFFL) chunkSize32 else 0L).toInt()

            when (chunkId) {
                "ds64" -> {
                    if (chunkDataPos + 24 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, 24).order(ByteOrder.LITTLE_ENDIAN)
                        val riffSize64 = bb.long
                        val dataSize64 = bb.long
                        val sampleCount64 = bb.long
                        if (dataSize64 > 0) dataSize = dataSize64
                    }
                    pos = chunkDataPos + chunkSize32.toInt()
                }
                "fmt " -> {
                    if (chunkDataPos + 16 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, 16).order(ByteOrder.LITTLE_ENDIAN)
                        formatTag = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        val byteRate = bb.int
                        val blockAlign = bb.short.toInt() and 0xFFFF
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                    pos = chunkDataPos + chunkSize32.toInt()
                }
                "data" -> {
                    dataOffset = chunkDataPos
                    if (dataSize <= 0) {
                        dataSize = if (chunkSize32 != 0xFFFFFFFFL) chunkSize32 else (bytes.size - chunkDataPos).toLong()
                    }
                    pos = bytes.size // Finished searching
                }
                else -> {
                    pos = if (chunkSize32 != 0xFFFFFFFFL) (chunkDataPos + chunkSize32.toInt()) else (pos + 8)
                }
            }
        }

        if (dataOffset < 0 || dataOffset >= bytes.size) return null
        val len = dataSize.toInt().coerceAtMost(bytes.size - dataOffset)

        return decodePcmSamples(bytes, dataOffset, len, sampleRate, channels, bitsPerSample, formatTag)
    }

    private fun decodeWave64(bytes: ByteArray): AudioPcmData? {
        var pos = 40 // Wave64 header is 40 bytes: 16 (riff GUID) + 8 (size) + 16 (wave GUID)
        var channels = 2
        var sampleRate = 44100
        var bitsPerSample = 16
        var formatTag = 1
        var dataOffset = -1
        var dataSize = 0L

        while (pos + 24 <= bytes.size) {
            val b0 = bytes[pos].toInt().toChar()
            val b1 = bytes[pos + 1].toInt().toChar()
            val b2 = bytes[pos + 2].toInt().toChar()
            val b3 = bytes[pos + 3].toInt().toChar()
            val chunkType = "$b0$b1$b2$b3"

            val chunkSize = ByteBuffer.wrap(bytes, pos + 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val dataPayloadPos = pos + 24
            val dataPayloadLen = (chunkSize - 24).coerceAtLeast(0)

            when (chunkType) {
                "fmt " -> {
                    if (dataPayloadPos + 16 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, dataPayloadPos, 16).order(ByteOrder.LITTLE_ENDIAN)
                        formatTag = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        val byteRate = bb.int
                        val blockAlign = bb.short.toInt() and 0xFFFF
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = dataPayloadPos
                    dataSize = dataPayloadLen
                    break
                }
            }

            // Align to 8-byte boundary
            val paddedLen = ((chunkSize + 7) / 8) * 8
            pos += paddedLen.toInt()
        }

        if (dataOffset < 0 || dataOffset >= bytes.size) return null
        val len = (if (dataSize > 0) dataSize.toInt() else (bytes.size - dataOffset)).coerceAtMost(bytes.size - dataOffset)

        return decodePcmSamples(bytes, dataOffset, len, sampleRate, channels, bitsPerSample, formatTag)
    }

    private fun decodePcmSamples(
        bytes: ByteArray, offset: Int, length: Int,
        sampleRate: Int, channels: Int, bitsPerSample: Int, formatTag: Int
    ): AudioPcmData {
        val sr = if (sampleRate > 0) sampleRate else 44100
        val ch = if (channels > 0) channels else 2

        if (formatTag == 3) { // IEEE Float
            return AudioPcmData.from32BitFloat(bytes, sr, ch, bigEndian = false, offset = offset, length = length)
        }

        return when (bitsPerSample) {
            8 -> AudioPcmData.from8BitUnsigned(bytes.copyOfRange(offset, offset + length), sr, ch)
            16 -> AudioPcmData.from16BitLittleEndian(bytes, sr, ch, offset, length)
            24 -> AudioPcmData.from24Bit(bytes, sr, ch, bigEndian = false, offset = offset, length = length)
            32 -> {
                val sampleCount = length / 4
                val samples = ShortArray(sampleCount)
                val bb = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    samples[i] = (bb.int shr 16).toShort()
                }
                AudioPcmData(sr, ch, samples, 16)
            }
            else -> AudioPcmData.from16BitLittleEndian(bytes, sr, ch, offset, length)
        }
    }
}
