package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AiffDecoder {

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != "FORM") return false
        val formType = String(bytes, 8, 4, Charsets.US_ASCII)
        return formType == "AIFF" || formType == "AIFC"
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        val formType = String(bytes, 8, 4, Charsets.US_ASCII)
        val isAifc = formType == "AIFC"

        var pos = 12
        var channels = 2
        var sampleSize = 16
        var sampleRate = 44100
        var compressionType = if (isAifc) "NONE" else "twos"

        var soundDataOffset = -1
        var soundDataLength = -1

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val chunkDataPos = pos + 8
            pos += 8 + chunkSize.toInt()
            if ((chunkSize and 1L) == 1L) pos++ // Pad byte

            when (chunkId) {
                "COMM" -> {
                    if (chunkDataPos + 18 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, chunkSize.toInt().coerceAtMost(bytes.size - chunkDataPos))
                            .order(ByteOrder.BIG_ENDIAN)
                        channels = bb.short.toInt() and 0xFFFF
                        val numSampleFrames = bb.int.toLong() and 0xFFFFFFFFL
                        sampleSize = bb.short.toInt() and 0xFFFF
                        val exp = bb.short.toInt() and 0xFFFF
                        val mantissaHi = bb.int.toLong() and 0xFFFFFFFFL
                        val mantissaLo = bb.int.toLong() and 0xFFFFFFFFL
                        sampleRate = decodeIeeeExtended(exp, mantissaHi, mantissaLo)
                        if (sampleRate <= 0) sampleRate = 44100

                        if (isAifc && bb.remaining() >= 4) {
                            val compBytes = ByteArray(4)
                            bb.get(compBytes)
                            compressionType = String(compBytes, Charsets.US_ASCII)
                        }
                    }
                }
                "SSND" -> {
                    if (chunkDataPos + 8 <= bytes.size) {
                        val bb = ByteBuffer.wrap(bytes, chunkDataPos, 8).order(ByteOrder.BIG_ENDIAN)
                        val offset = bb.int.toLong() and 0xFFFFFFFFL
                        val blockSize = bb.int.toLong() and 0xFFFFFFFFL
                        soundDataOffset = (chunkDataPos + 8 + offset).toInt()
                        soundDataLength = (chunkSize - 8 - offset).toInt()
                    }
                }
            }
        }

        if (soundDataOffset < 0 || soundDataOffset >= bytes.size) return null
        val dataLen = if (soundDataLength > 0 && soundDataOffset + soundDataLength <= bytes.size) {
            soundDataLength
        } else {
            bytes.size - soundDataOffset
        }

        return when (compressionType.lowercase()) {
            "sowt" -> { // Little-endian 16-bit PCM
                AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, soundDataOffset, dataLen)
            }
            "ulaw" -> {
                AudioPcmData.decodeUlaw(bytes, sampleRate, channels, soundDataOffset, dataLen)
            }
            "alaw" -> {
                AudioPcmData.decodeAlaw(bytes, sampleRate, channels, soundDataOffset, dataLen)
            }
            "fl32" -> {
                AudioPcmData.from32BitFloat(bytes, sampleRate, channels, bigEndian = true, offset = soundDataOffset, length = dataLen)
            }
            else -> {
                // Standard AIFF (big-endian linear PCM)
                when (sampleSize) {
                    8 -> {
                        val sub = bytes.copyOfRange(soundDataOffset, soundDataOffset + dataLen)
                        AudioPcmData.from8BitSigned(sub, sampleRate, channels)
                    }
                    16 -> {
                        AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, soundDataOffset, dataLen)
                    }
                    24 -> {
                        AudioPcmData.from24Bit(bytes, sampleRate, channels, bigEndian = true, offset = soundDataOffset, length = dataLen)
                    }
                    32 -> {
                        val sampleCount = dataLen / 4
                        val samples = ShortArray(sampleCount)
                        val bb = ByteBuffer.wrap(bytes, soundDataOffset, dataLen).order(ByteOrder.BIG_ENDIAN)
                        for (i in 0 until sampleCount) {
                            samples[i] = (bb.int shr 16).toShort()
                        }
                        AudioPcmData(sampleRate, channels, samples, 16)
                    }
                    else -> AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, soundDataOffset, dataLen)
                }
            }
        }
    }

    private fun decodeIeeeExtended(exp: Int, mantissaHi: Long, mantissaLo: Long): Int {
        if (exp == 0 && mantissaHi == 0L && mantissaLo == 0L) return 0
        if (exp == 0x7FFF) return 0
        val actualExp = exp - 16383
        val mantissaDbl = (mantissaHi.toDouble() * 4294967296.0 + mantissaLo.toDouble()) / 9223372036854775808.0
        val rate = Math.scalb(mantissaDbl, actualExp)
        return rate.toInt()
    }
}
