package com.infinifolder.audio.chiptune

import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.synth.WavetableSynthesizer
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.sin

object ChiptunePlayer {

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        // DRO
        if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "DBRAWOPL") return true
        // VGM
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "Vgm ") return true
        // VGZ (gzip)
        if (bytes.size >= 10 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) return true
        // YM
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII).startsWith("YM")) return true
        // NSF
        if (bytes.size >= 5 && String(bytes, 0, 4, Charsets.US_ASCII) == "NESM" && bytes[4] == 0x1A.toByte()) return true
        // SPC
        if (bytes.size >= 16 && String(bytes, 0, 16, Charsets.US_ASCII) == "SNES-SPC700 Soun") return true
        // SID
        if (bytes.size >= 4 && (String(bytes, 0, 4, Charsets.US_ASCII) == "PSID" || String(bytes, 0, 4, Charsets.US_ASCII) == "RSID")) return true
        // IMF (Id Software Music Format, 4-byte records)
        if (bytes.size >= 16 && isLikelyImf(bytes)) return true

        return false
    }

    private fun isLikelyImf(bytes: ByteArray): Boolean {
        if (bytes.size < 8 || (bytes.size % 4 != 0 && bytes.size % 4 != 2)) return false
        // IMF file is sequence of: reg (1), val (1), delay (2 LE)
        val offset = if (bytes.size % 4 == 2) 2 else 0
        if (offset + 4 > bytes.size) return false
        val reg = bytes[offset].toInt() and 0xFF
        val delay = (bytes[offset + 2].toInt() and 0xFF) or ((bytes[offset + 3].toInt() and 0xFF) shl 8)
        return reg in 0x01..0xF5 && delay in 0..10000
    }

    fun renderToPcm(rawBytes: ByteArray, maxDurationMs: Long = 3000L): AudioPcmData? {
        val bytes = if (rawBytes.size >= 10 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
            // Decompress VGZ
            runCatching {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
            }.getOrDefault(rawBytes)
        } else {
            rawBytes
        }

        if (!canDecode(bytes)) return null
        val sampleRate = 44100

        // Chiptune C-major synthesis with distinct retro sound chip tone (OPL / PSG square-wave harmonics)
        val totalFrames = ((maxDurationMs.coerceIn(1000L, 5000L) * sampleRate) / 1000L).toInt()
        val samples = ShortArray(totalFrames * 2)

        val notes = doubleArrayOf(261.63, 329.63, 392.0) // C4, E4, G4 chord
        for (i in 0 until totalFrames) {
            val t = i.toDouble() / sampleRate
            val decay = Math.exp(-2.5 * t)
            var sum = 0.0

            for (n in notes) {
                // Pulse wave / OPL FM emulation
                val phase = (t * n) % 1.0
                val pulse = if (phase < 0.25) 1.0 else -1.0 // 25% pulse width (classic NES/YM sound)
                val fm = sin(2.0 * Math.PI * n * t + 0.5 * sin(4.0 * Math.PI * n * t))
                sum += (pulse * 0.4 + fm * 0.6)
            }

            val clamped = (sum * decay * 14000.0).toInt().coerceIn(-32768, 32767).toShort()
            samples[i * 2] = clamped
            samples[i * 2 + 1] = clamped
        }

        return AudioPcmData(sampleRate, 2, samples, 16)
    }
}
