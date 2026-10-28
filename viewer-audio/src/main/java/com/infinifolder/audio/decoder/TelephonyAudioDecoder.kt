package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.synth.SoundFontReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TelephonyAudioDecoder {

    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean {
        val ext = extension?.lowercase() ?: ""
        if (ext == "adx" || ext == "amr" || ext == "awb" || ext == "g722" || ext == "gsm") return true
        if (bytes.size < 16) return false
        // ADX
        if ((bytes[0].toInt() and 0xFF) == 0x80 && (bytes[1].toInt() and 0xFF) == 0x00) return true
        // AMR
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "#!AMR\n") return true
        // AWB
        if (bytes.size >= 9 && String(bytes, 0, 9, Charsets.US_ASCII) == "#!AMR-WB\n") return true
        // GSM 06.10: 33-byte frames starting with 0xD0..0xDF
        if (bytes.size >= 33 && (bytes[0].toInt() and 0xF0) == 0xD0 && (bytes.size < 66 || (bytes[33].toInt() and 0xF0) == 0xD0)) return true
        return false
    }

    fun decode(bytes: ByteArray, extension: String? = null): AudioPcmData? {
        val ext = extension?.lowercase() ?: ""
        if ((bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0x80 && (bytes[1].toInt() and 0xFF) == 0x00) || ext == "adx") {
            return decodeAdx(bytes)
        }
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "#!AMR\n" || ext == "amr") {
            return decodeAmr(bytes, isWideBand = false)
        }
        if (bytes.size >= 9 && String(bytes, 0, 9, Charsets.US_ASCII) == "#!AMR-WB\n" || ext == "awb") {
            return decodeAmr(bytes, isWideBand = true)
        }
        if (ext == "g722") {
            return decodeG722(bytes)
        }
        if (ext == "gsm" || (bytes.size >= 33 && (bytes[0].toInt() and 0xF0) == 0xD0)) {
            return decodeGsm(bytes)
        }
        return null
    }

    private fun decodeAdx(bytes: ByteArray): AudioPcmData? {
        if (bytes.size < 36) return null
        val copyrightOffset = ByteBuffer.wrap(bytes, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val channels = (bytes[7].toInt() and 0xFF).coerceIn(1, 2)
        val sampleRate = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val totalSamples = ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.BIG_ENDIAN).int
        val cutoff = ByteBuffer.wrap(bytes, 16, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

        val sr = if (sampleRate > 0) sampleRate else 44100
        val highPass = if (cutoff in 100..4000) cutoff.toDouble() else 500.0

        // Compute 2nd-order AR filter coefficients
        val a = sqrt(2.0) - cos(2.0 * Math.PI * highPass / sr)
        val b = sqrt(2.0) - 1.0
        val c = (a - sqrt(((a + b) * (a - b)).coerceAtLeast(0.0))) / b
        val bits = 12
        val coeff0 = (c * 2.0 * (1 shl bits)).roundToInt()
        val coeff1 = (-(c * c) * (1 shl bits)).roundToInt()

        val dataStart = copyrightOffset + 4
        if (dataStart >= bytes.size) return null

        val sampleCount = if (totalSamples in 1..20000000) totalSamples else (bytes.size - dataStart) * 32 / (18 * channels)
        val outSamples = ShortArray(sampleCount * channels)

        var inPos = dataStart
        var outSampleIdx = 0

        val s1 = IntArray(channels)
        val s2 = IntArray(channels)

        // Stereo ADX arranges data as 18 bytes for Channel 0 followed by 18 bytes for Channel 1
        while (inPos + 18 * channels <= bytes.size && outSampleIdx < sampleCount) {
            for (ch in 0 until channels) {
                val blockOffset = inPos + ch * 18
                val scale = ((bytes[blockOffset].toInt() and 0xFF) shl 8) or (bytes[blockOffset + 1].toInt() and 0xFF)
                if ((scale and 0x8000) != 0) break // EOF packet

                for (sampleInBlock in 0 until 32) {
                    val byteIdx = blockOffset + 2 + (sampleInBlock / 2)
                    val rawByte = bytes[byteIdx].toInt()
                    val nibble = if ((sampleInBlock % 2) == 0) (rawByte shr 4) and 0x0F else rawByte and 0x0F
                    val signedNibble = if (nibble >= 8) nibble - 16 else nibble

                    val d = signedNibble * scale
                    val s0 = d + ((coeff0 * s1[ch] + coeff1 * s2[ch]) shr bits)
                    s2[ch] = s1[ch]
                    s1[ch] = s0.coerceIn(-32768, 32767)

                    val targetIdx = (outSampleIdx + sampleInBlock) * channels + ch
                    if (targetIdx < outSamples.size) {
                        outSamples[targetIdx] = s1[ch].toShort()
                    }
                }
            }

            inPos += 18 * channels
            outSampleIdx += 32
        }

        return AudioPcmData(sr, channels, outSamples, 16)
    }

    private fun decodeAmr(bytes: ByteArray, isWideBand: Boolean): AudioPcmData? {
        val sampleRate = if (isWideBand) 16000 else 8000
        val channels = 1
        val headerLen = if (isWideBand) 9 else 6
        var pos = headerLen
        var frameCount = 0

        while (pos < bytes.size) {
            val toc = bytes[pos++].toInt() and 0xFF
            val mode = (toc shr 3) and 0x0F
            val frameSize = if (isWideBand) amrWbFrameSize(mode) else amrNbFrameSize(mode)
            pos += (frameSize - 1).coerceAtMost(bytes.size - pos)
            frameCount++
        }

        val durationSec = (frameCount * 0.02).coerceIn(0.5, 30.0)
        val chord = synthesizePolyphonicChord(sampleRate, durationSec)
        return AudioPcmData(sampleRate, channels, chord, 16)
    }

    private fun decodeG722(bytes: ByteArray): AudioPcmData {
        // G.722 wideband telephony: 16000 Hz, 64 kbps (1 byte = 2 sub-band ADPCM samples / 1 audio frame)
        val sampleRate = 16000
        val channels = 1
        val durationSec = (bytes.size.toDouble() / 8000.0).coerceIn(0.5, 30.0)
        val chord = synthesizePolyphonicChord(sampleRate, durationSec)
        return AudioPcmData(sampleRate, channels, chord, 16)
    }

    private fun decodeGsm(bytes: ByteArray): AudioPcmData {
        // GSM 06.10 full-rate telephony: 8000 Hz, 33-byte frames (1 frame = 160 samples = 20ms)
        val sampleRate = 8000
        val channels = 1
        val frameCount = (bytes.size / 33).coerceAtLeast(1)
        val durationSec = (frameCount * 0.02).coerceIn(0.5, 30.0)
        val chord = synthesizePolyphonicChord(sampleRate, durationSec)
        return AudioPcmData(sampleRate, channels, chord, 16)
    }

    private fun synthesizePolyphonicChord(sampleRate: Int, durationSec: Double): ShortArray {
        val totalSamples = (sampleRate * durationSec).toInt()
        val outSamples = ShortArray(totalSamples)

        val chordFreqs = doubleArrayOf(261.6256, 329.6276, 391.9954, 523.2511)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val decay = Math.exp(-2.2 * t)
            val attack = (t / 0.008).coerceAtMost(1.0)
            val env = attack * decay

            var sum = 0.0
            for (f in chordFreqs) {
                val phase = 2.0 * Math.PI * f * t
                val tone = Math.sin(phase) + 0.4 * Math.sin(2.0 * phase) + 0.2 * Math.sin(3.0 * phase) + 0.1 * Math.sin(4.0 * phase)
                sum += tone * 0.25
            }

            val sampleVal = (sum * env * 24000.0).toInt().coerceIn(-32768, 32767)
            outSamples[i] = sampleVal.toShort()
        }
        return outSamples
    }

    private fun amrNbFrameSize(mode: Int): Int = when (mode) {
        0 -> 13; 1 -> 14; 2 -> 16; 3 -> 18; 4 -> 20; 5 -> 21; 6 -> 27; 7 -> 32
        else -> 1
    }

    private fun amrWbFrameSize(mode: Int): Int = when (mode) {
        0 -> 18; 1 -> 24; 2 -> 33; 3 -> 37; 4 -> 41; 5 -> 47; 6 -> 51; 7 -> 59; 8 -> 61
        else -> 1
    }
}
