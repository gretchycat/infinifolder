package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SpecializedSampleDecoder {

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        // AVR
        if (bytes[0] == '2'.code.toByte() && bytes[1] == 'B'.code.toByte() &&
            bytes[2] == 'I'.code.toByte() && bytes[3] == 'T'.code.toByte()) return true
        // IRCAM (VAX LE 0x0001A364 or Sun BE 0x64A30100)
        val m0 = bytes[0].toInt() and 0xFF
        val m1 = bytes[1].toInt() and 0xFF
        val m2 = bytes[2].toInt() and 0xFF
        val m3 = bytes[3].toInt() and 0xFF
        if ((m0 == 0x64 && m1 == 0xA3 && m2 == 0x01 && m3 == 0x00) ||
            (m0 == 0x00 && m1 == 0x01 && m2 == 0xA3 && m3 == 0x64)) return true
        // SPHERE
        if (bytes.size >= 8 && String(bytes, 0, 7, Charsets.US_ASCII) == "NIST_1A") return true
        // PVF
        if (bytes.size >= 5 && (String(bytes, 0, 5, Charsets.US_ASCII) == "PVF1\n" || String(bytes, 0, 5, Charsets.US_ASCII) == "PVF2\n")) return true
        // WVE (Psion)
        if (bytes.size >= 15 && String(bytes, 0, 15, Charsets.US_ASCII) == "ALawSoundFile**") return true
        // HTK
        if (bytes.size >= 12 && isHtkHeader(bytes)) return true

        return false
    }

    private fun isHtkHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val bb = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.BIG_ENDIAN)
        val nSamples = bb.int
        val sampPeriod = bb.int // in 100ns units
        val sampSize = bb.short.toInt() and 0xFFFF
        val sampKind = bb.short.toInt() and 0xFFFF
        if (nSamples in 100..50000000 && sampPeriod in 50..20000 && sampSize in 1..4) {
            val expectedLen = 12 + nSamples * sampSize
            return expectedLen <= bytes.size + 16
        }
        return false
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null

        // 1. AVR
        if (bytes[0] == '2'.code.toByte() && bytes[1] == 'B'.code.toByte()) {
            return decodeAvr(bytes)
        }

        // 2. IRCAM
        val m0 = bytes[0].toInt() and 0xFF
        val m1 = bytes[1].toInt() and 0xFF
        if ((m0 == 0x64 && m1 == 0xA3) || (m0 == 0x00 && m1 == 0x01)) {
            return decodeIrcam(bytes)
        }

        // 3. NIST SPHERE
        if (String(bytes, 0, 7, Charsets.US_ASCII) == "NIST_1A") {
            return decodeSphere(bytes)
        }

        // 4. PVF
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "PVF1" || String(bytes, 0, 4, Charsets.US_ASCII) == "PVF2") {
            return decodePvf(bytes)
        }

        // 5. Psion WVE
        if (String(bytes, 0, 15, Charsets.US_ASCII) == "ALawSoundFile**") {
            return decodeWve(bytes)
        }

        // 6. HTK
        if (isHtkHeader(bytes)) {
            return decodeHtk(bytes)
        }

        return null
    }

    private fun decodeAvr(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 128) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bb.position(12)
        val isStereo = (bb.short.toInt() and 0xFFFF) != 0
        val resolution = bb.short.toInt() and 0xFFFF
        val isSigned = (bb.short.toInt() and 0xFFFF) != 0
        bb.position(24)
        val sampleRate = bb.short.toInt() and 0xFFFF
        val channels = if (isStereo) 2 else 1
        val sr = if (sampleRate > 0) sampleRate else 22050

        val dataOffset = 128
        val dataLen = bytes.size - dataOffset
        if (dataLen <= 0) return null

        return if (resolution == 16) {
            if (isSigned) {
                AudioPcmData.from16BitBigEndian(bytes, sr, channels, dataOffset, dataLen)
            } else {
                val sampleCount = dataLen / 2
                val samples = ShortArray(sampleCount)
                val sbb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.BIG_ENDIAN)
                for (i in 0 until sampleCount) {
                    val u16 = sbb.short.toInt() and 0xFFFF
                    samples[i] = (u16 - 32768).toShort()
                }
                AudioPcmData(sr, channels, samples, 16)
            }
        } else {
            val sub = bytes.copyOfRange(dataOffset, dataOffset + dataLen)
            if (isSigned) {
                AudioPcmData.from8BitSigned(sub, sr, channels)
            } else {
                AudioPcmData.from8BitUnsigned(sub, sr, channels)
            }
        }
    }

    private fun decodeIrcam(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 1024) return null
        val isLe = (bytes[0].toInt() and 0xFF) == 0x64
        val order = if (isLe) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.wrap(bytes, 0, 32).order(order)
        bb.int // skip magic
        val srFloat = bb.float
        val sampleRate = if (srFloat > 0) srFloat.toInt() else 44100
        val channels = bb.int.coerceIn(1, 8)
        val encoding = bb.int // 1 = 16-bit linear, 2 = 16-bit linear VAX, 4 = float

        val dataOffset = 1024
        val dataLen = bytes.size - dataOffset
        if (dataLen <= 0) return null

        return if (encoding == 4) {
            AudioPcmData.from32BitFloat(bytes, sampleRate, channels, bigEndian = !isLe, offset = dataOffset, length = dataLen)
        } else {
            if (isLe) {
                AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
            } else {
                AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, dataLen)
            }
        }
    }

    private fun decodeSphere(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 1024) return null
        val headerText = String(bytes, 0, 1024, Charsets.US_ASCII)
        var sampleRate = 44100
        var channels = 1
        var sampleNBytes = 2
        var isLittleEndian = true

        val lines = headerText.lines()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                when (parts[0]) {
                    "sample_rate" -> sampleRate = parts[2].toIntOrNull() ?: 44100
                    "channel_count" -> channels = parts[2].toIntOrNull() ?: 1
                    "sample_n_bytes" -> sampleNBytes = parts[2].toIntOrNull() ?: 2
                    "sample_byte_format" -> isLittleEndian = parts[2].startsWith("01")
                }
            }
        }

        val dataOffset = 1024
        val dataLen = bytes.size - dataOffset
        if (dataLen <= 0) return null

        return when (sampleNBytes) {
            1 -> AudioPcmData.from8BitSigned(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
            2 -> {
                if (isLittleEndian) {
                    AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
                } else {
                    AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, dataLen)
                }
            }
            else -> AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
        }
    }

    private fun decodePvf(bytes: ByteArray): AudioPcmData? {
        val firstNl = bytes.indexOf('\n'.code.toByte())
        if (firstNl < 0) return null
        val secondNl = bytes.indexOf('\n'.code.toByte(), firstNl + 1)
        if (secondNl < 0) return null

        val paramStr = String(bytes, firstNl + 1, secondNl - firstNl - 1, Charsets.US_ASCII).trim()
        val tokens = paramStr.split(Regex("\\s+"))
        val channels = tokens.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 8) ?: 1
        val sampleRate = tokens.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(4000) ?: 16000
        val bits = tokens.getOrNull(2)?.toIntOrNull() ?: 16

        val dataOffset = secondNl + 1
        val dataLen = bytes.size - dataOffset
        if (dataLen <= 0) return null

        return when (bits) {
            8 -> AudioPcmData.from8BitSigned(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
            16 -> AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, dataLen)
            32 -> {
                val count = dataLen / 4
                val samples = ShortArray(count)
                val bb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.BIG_ENDIAN)
                for (i in 0 until count) samples[i] = (bb.int shr 16).toShort()
                AudioPcmData(sampleRate, channels, samples, 16)
            }
            else -> AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, dataLen)
        }
    }

    private fun decodeWve(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 32) return null
        val sampleRate = 8000
        val channels = 1
        val dataOffset = 32
        val dataLen = bytes.size - dataOffset
        return AudioPcmData.decodeAlaw(bytes, sampleRate, channels, dataOffset, dataLen)
    }

    private fun decodeHtk(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 12) return null
        val bb = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.BIG_ENDIAN)
        val nSamples = bb.int
        val sampPeriod = bb.int // 100ns units
        val sampSize = bb.short.toInt() and 0xFFFF
        val sampKind = bb.short.toInt() and 0xFFFF

        val sampleRate = if (sampPeriod > 0) (10000000 / sampPeriod).coerceIn(4000, 96000) else 16000
        val channels = 1
        val dataOffset = 12
        val dataLen = (nSamples * sampSize).coerceAtMost(bytes.size - dataOffset)

        return if (sampSize == 2) {
            AudioPcmData.from16BitBigEndian(bytes, sampleRate, channels, dataOffset, dataLen)
        } else {
            AudioPcmData.from8BitSigned(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
        }
    }

    private fun ByteArray.indexOf(target: Byte, start: Int = 0): Int {
        for (i in start until size) {
            if (this[i] == target) return i
        }
        return -1
    }
}
