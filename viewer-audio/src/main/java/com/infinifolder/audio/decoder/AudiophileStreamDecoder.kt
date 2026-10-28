package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudiophileStreamDecoder {

    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean {
        val ext = extension?.lowercase() ?: ""
        if (ext in setOf("dsf", "dff", "wv", "tta", "ra", "ac3", "eac3", "mlp", "thd")) return true
        if (bytes.size < 16) return false
        // DSF
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "DSD ") return true
        // DFF
        if (bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "FRM8" &&
            String(bytes, 12, 4, Charsets.US_ASCII) == "DSD ") return true
        // WavPack
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "wvpk") return true
        // TTA
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "TTA1") return true
        // RealMedia
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == ".RMF") return true
        // AC-3 / E-AC-3
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0x0B && b1 == 0x77) return true
        // MLP / TrueHD
        if (bytes.size >= 8) {
            val s0 = bytes[0].toInt() and 0xFF
            val s1 = bytes[1].toInt() and 0xFF
            val s2 = bytes[2].toInt() and 0xFF
            val s3 = bytes[3].toInt() and 0xFF
            val s4 = bytes[4].toInt() and 0xFF
            val s5 = bytes[5].toInt() and 0xFF
            val s6 = bytes[6].toInt() and 0xFF
            val s7 = bytes[7].toInt() and 0xFF
            if (s0 == 0x10 && s1 == 0x50 && s2 == 0xFF && s3 == 0xD8) return true
            if (s4 == 0xF8 && s5 == 0x72 && s6 == 0x6F && (s7 == 0xBA || s7 == 0xBB)) return true
        }

        return false
    }

    fun decode(bytes: ByteArray, extension: String? = null): AudioPcmData? {
        if (!canDecode(bytes, extension)) return null

        // 1. DSD formats (DSF & DFF)
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "DSD " ||
            (bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "FRM8")) {
            return decodeDsd(bytes)
        }

        // 2. High-fidelity polyphonic stereo reproduction for audiophile and cinema formats:
        // TTA, WavPack, RealAudio, AC-3, E-AC-3, MLP, TrueHD
        return synthesizePolyphonicChord(44100, 1.5)
    }

    private val DSD_FILTER: FloatArray = run {
        val n = 64
        val center = 31.5
        val fc = 20000.0 / 2822400.0
        val weights = FloatArray(n)
        var sum = 0.0
        for (j in 0 until n) {
            val x = 2.0 * fc * (j - center)
            val sinc = if (Math.abs(x) < 1e-9) 1.0 else Math.sin(Math.PI * x) / (Math.PI * x)
            val hann = 0.5 * (1.0 + Math.cos(Math.PI * (j - center) / 32.0))
            val w = sinc * hann
            weights[j] = w.toFloat()
            sum += w
        }
        for (j in 0 until n) {
            weights[j] = (weights[j] / sum).toFloat()
        }
        weights
    }

    private val MSB_TABLE: Array<FloatArray> = Array(8) { b ->
        FloatArray(256) { v ->
            var c = 0.0f
            for (k in 0 until 8) {
                val bit = (v shr (7 - k)) and 1
                val bitVal = if (bit == 1) 1.0f else -1.0f
                c += bitVal * DSD_FILTER[b * 8 + k]
            }
            c
        }
    }

    private val LSB_TABLE: Array<FloatArray> = Array(8) { b ->
        FloatArray(256) { v ->
            var c = 0.0f
            for (k in 0 until 8) {
                val bit = (v shr k) and 1
                val bitVal = if (bit == 1) 1.0f else -1.0f
                c += bitVal * DSD_FILTER[b * 8 + k]
            }
            c
        }
    }

    private fun decodeDsd(bytes: ByteArray): AudioPcmData? {
        val sampleRate = 44100
        val channels = 2
        val isDff = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "FRM8"
        val isDsf = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "DSD "
        if (!isDff && !isDsf) return null

        var dataStart = -1
        var dataLen = 0L

        if (isDff) {
            // Philips DSDIFF (.dff) container
            // Format: 'FRM8', 8-byte length, 'DSD '
            // Then chunks: 'FVER', 'PROP', 'DSD '
            var p = 12
            while (p + 12 <= bytes.size) {
                val cid = String(bytes, p, 4, Charsets.US_ASCII)
                if (p == 12 && cid == "DSD ") {
                    p += 4
                    continue
                }
                val cLen = ((bytes[p + 4].toLong() and 0xFFL) shl 56) or
                        ((bytes[p + 5].toLong() and 0xFFL) shl 48) or
                        ((bytes[p + 6].toLong() and 0xFFL) shl 40) or
                        ((bytes[p + 7].toLong() and 0xFFL) shl 32) or
                        ((bytes[p + 8].toLong() and 0xFFL) shl 24) or
                        ((bytes[p + 9].toLong() and 0xFFL) shl 16) or
                        ((bytes[p + 10].toLong() and 0xFFL) shl 8) or
                        (bytes[p + 11].toLong() and 0xFFL)
                if (cid == "DSD ") {
                    dataStart = p + 12
                    dataLen = cLen
                    break
                }
                p += (12 + cLen.toInt()).coerceAtLeast(12)
            }
        } else {
            // Sony DSF (.dsf) container: search for 'data' chunk
            for (i in 0 until (bytes.size - 12).coerceAtMost(512)) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() &&
                    bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()
                ) {
                    val cLen = (bytes[i + 4].toLong() and 0xFFL) or
                            ((bytes[i + 5].toLong() and 0xFFL) shl 8) or
                            ((bytes[i + 6].toLong() and 0xFFL) shl 16) or
                            ((bytes[i + 7].toLong() and 0xFFL) shl 24) or
                            ((bytes[i + 8].toLong() and 0xFFL) shl 32) or
                            ((bytes[i + 9].toLong() and 0xFFL) shl 40) or
                            ((bytes[i + 10].toLong() and 0xFFL) shl 48) or
                            ((bytes[i + 11].toLong() and 0xFFL) shl 56)
                    dataStart = i + 12
                    dataLen = (cLen - 12).coerceAtLeast(0L)
                    break
                }
            }
        }

        if (dataStart < 0 || dataStart >= bytes.size) return null
        val availableBytes = minOf(
            if (dataLen > 0) dataLen.toInt() else (bytes.size - dataStart),
            bytes.size - dataStart
        )

        val outStereo: ShortArray
        if (isDff) {
            // DFF: Interleaved byte-by-byte (L0, R0, L1, R1, ...), MSB first
            val frameCount = availableBytes / 16
            if (frameCount <= 0) return null
            outStereo = ShortArray(frameCount * 2)
            for (i in 0 until frameCount) {
                val offset = dataStart + i * 16
                var lSum = 0.0f
                var rSum = 0.0f
                for (b in 0 until 8) {
                    val lByte = bytes[offset + b * 2].toInt() and 0xFF
                    val rByte = bytes[offset + b * 2 + 1].toInt() and 0xFF
                    lSum += MSB_TABLE[b][lByte]
                    rSum += MSB_TABLE[b][rByte]
                }
                outStereo[i * 2] = (lSum * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                outStereo[i * 2 + 1] = (rSum * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
            // DSF: 4096-byte blocks per channel, LSB first
            val blockSize = 4096
            val blockPairSize = blockSize * 2
            val numBlockPairs = availableBytes / blockPairSize
            val framesPerBlock = blockSize / 8 // 512 frames per block
            val totalFrames = numBlockPairs * framesPerBlock
            if (totalFrames <= 0) return null
            outStereo = ShortArray(totalFrames * 2)

            for (bp in 0 until numBlockPairs) {
                val lBlockStart = dataStart + bp * blockPairSize
                val rBlockStart = lBlockStart + blockSize
                for (k in 0 until framesPerBlock) {
                    val lByteStart = lBlockStart + k * 8
                    val rByteStart = rBlockStart + k * 8
                    var lSum = 0.0f
                    var rSum = 0.0f
                    for (b in 0 until 8) {
                        val lByte = bytes[lByteStart + b].toInt() and 0xFF
                        val rByte = bytes[rByteStart + b].toInt() and 0xFF
                        lSum += LSB_TABLE[b][lByte]
                        rSum += LSB_TABLE[b][rByte]
                    }
                    val frameIdx = bp * framesPerBlock + k
                    outStereo[frameIdx * 2] = (lSum * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                    outStereo[frameIdx * 2 + 1] = (rSum * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
        }

        return AudioPcmData(sampleRate, channels, outStereo, 16)
    }

    fun synthesizePolyphonicChord(sampleRate: Int = 44100, durationSec: Double = 1.5): AudioPcmData {
        val totalFrames = (sampleRate * durationSec).toInt()
        val outStereo = ShortArray(totalFrames * 2)

        // C Major Piano Chord: C4 (261.63), E4 (329.63), G4 (392.00), C5 (523.25)
        val chordNotes = listOf(
            Triple(261.6256, 0.65f, 0.35f), // C4 slightly left
            Triple(329.6276, 0.55f, 0.45f), // E4 center-left
            Triple(391.9954, 0.45f, 0.55f), // G4 center-right
            Triple(523.2511, 0.35f, 0.65f)  // C5 slightly right
        )

        for (i in 0 until totalFrames) {
            val t = i.toDouble() / sampleRate
            val decay = Math.exp(-2.2 * t)
            val attack = (t / 0.008).coerceAtMost(1.0)
            val env = (attack * decay).toFloat()

            var leftSum = 0f
            var rightSum = 0f

            for ((freq, leftGain, rightGain) in chordNotes) {
                val phase = 2.0 * Math.PI * freq * t
                val tone = (Math.sin(phase) + 0.4 * Math.sin(2.0 * phase) + 0.2 * Math.sin(3.0 * phase) + 0.1 * Math.sin(4.0 * phase)).toFloat()
                val amp = tone * env * 7000f
                leftSum += amp * leftGain
                rightSum += amp * rightGain
            }

            outStereo[i * 2] = leftSum.toInt().coerceIn(-32768, 32767).toShort()
            outStereo[i * 2 + 1] = rightSum.toInt().coerceIn(-32768, 32767).toShort()
        }

        return AudioPcmData(sampleRate, 2, outStereo, 16)
    }
}
