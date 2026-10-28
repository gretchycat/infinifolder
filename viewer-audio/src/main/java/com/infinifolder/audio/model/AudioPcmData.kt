package com.infinifolder.audio.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard in-memory decoded PCM audio buffer (16-bit signed, interleaved).
 */
data class AudioPcmData(
    val sampleRate: Int,
    val channels: Int,
    val samples: ShortArray, // interleaved L, R, L, R... or mono
    val bitsPerSample: Int = 16
) {
    val durationMs: Long
        get() {
            if (sampleRate <= 0 || channels <= 0) return 0L
            val totalFrames = samples.size / channels
            return (totalFrames.toLong() * 1000L) / sampleRate
        }

    fun toStereo(): AudioPcmData {
        if (channels == 2) return this
        if (channels == 1) {
            val stereo = ShortArray(samples.size * 2)
            var dst = 0
            for (s in samples) {
                stereo[dst++] = s
                stereo[dst++] = s
            }
            return AudioPcmData(sampleRate, 2, stereo, bitsPerSample)
        }
        return this
    }

    /**
     * Serializes this PCM audio as a standard 16-bit little-endian RIFF WAVE byte array.
     */
    fun toWavByteArray(): ByteArray {
        val numChannels = channels.coerceIn(1, 8)
        val byteRate = sampleRate * numChannels * 2
        val blockAlign = numChannels * 2
        val dataSize = samples.size * 2
        val totalSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt chunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
        buffer.putShort(numChannels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16.toShort()) // BitsPerSample

        // data chunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (sample in samples) {
            buffer.putShort(sample)
        }

        return buffer.array()
    }

    fun writeWavFile(destination: File) {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { out ->
            out.write(toWavByteArray())
            out.flush()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPcmData) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (bitsPerSample != other.bitsPerSample) return false
        return samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + samples.contentHashCode()
        return result
    }

    companion object {
        val EMPTY = AudioPcmData(44100, 2, ShortArray(0), 16)

        /**
         * Converts 8-bit unsigned PCM (standard WAV 8-bit, 0..255 with 128 silence) to 16-bit signed PCM.
         */
        fun from8BitUnsigned(bytes: ByteArray, sampleRate: Int, channels: Int): AudioPcmData {
            val samples = ShortArray(bytes.size)
            for (i in bytes.indices) {
                val unsigned = bytes[i].toInt() and 0xFF
                val sample = ((unsigned - 128) shl 8).coerceIn(-32768, 32767)
                samples[i] = sample.toShort()
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        /**
         * Converts 8-bit signed PCM (used in Amiga 8SVX, VOC, etc.) to 16-bit signed PCM.
         */
        fun from8BitSigned(bytes: ByteArray, sampleRate: Int, channels: Int): AudioPcmData {
            val samples = ShortArray(bytes.size)
            for (i in bytes.indices) {
                val signed = bytes[i].toInt()
                samples[i] = (signed shl 8).toShort()
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        /**
         * Converts 16-bit big-endian PCM (used in AIFF, Sun AU, etc.) to 16-bit signed PCM.
         */
        fun from16BitBigEndian(bytes: ByteArray, sampleRate: Int, channels: Int, offset: Int = 0, length: Int = bytes.size - offset): AudioPcmData {
            val sampleCount = length / 2
            val samples = ShortArray(sampleCount)
            var byteIdx = offset
            for (i in 0 until sampleCount) {
                val b0 = bytes[byteIdx++].toInt() and 0xFF
                val b1 = bytes[byteIdx++].toInt() and 0xFF
                samples[i] = ((b0 shl 8) or b1).toShort()
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        /**
         * Converts 16-bit little-endian PCM to 16-bit signed PCM.
         */
        fun from16BitLittleEndian(bytes: ByteArray, sampleRate: Int, channels: Int, offset: Int = 0, length: Int = bytes.size - offset): AudioPcmData {
            val sampleCount = length / 2
            val samples = ShortArray(sampleCount)
            var byteIdx = offset
            for (i in 0 until sampleCount) {
                val b0 = bytes[byteIdx++].toInt() and 0xFF
                val b1 = bytes[byteIdx++].toInt() and 0xFF
                samples[i] = ((b1 shl 8) or b0).toShort()
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        /**
         * Converts 24-bit PCM (little or big endian) to 16-bit signed PCM.
         */
        fun from24Bit(bytes: ByteArray, sampleRate: Int, channels: Int, bigEndian: Boolean, offset: Int = 0, length: Int = bytes.size - offset): AudioPcmData {
            val sampleCount = length / 3
            val samples = ShortArray(sampleCount)
            var byteIdx = offset
            for (i in 0 until sampleCount) {
                val b0 = bytes[byteIdx++].toInt() and 0xFF
                val b1 = bytes[byteIdx++].toInt() and 0xFF
                val b2 = bytes[byteIdx++].toInt() and 0xFF
                val v24 = if (bigEndian) {
                    ((b0 shl 24) or (b1 shl 16) or (b2 shl 8)) shr 8
                } else {
                    ((b2 shl 24) or (b1 shl 16) or (b0 shl 8)) shr 8
                }
                samples[i] = (v24 shr 8).toShort()
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        /**
         * Converts 32-bit IEEE float PCM (-1.0f .. 1.0f) to 16-bit signed PCM.
         */
        fun from32BitFloat(bytes: ByteArray, sampleRate: Int, channels: Int, bigEndian: Boolean, offset: Int = 0, length: Int = bytes.size - offset): AudioPcmData {
            val sampleCount = length / 4
            val samples = ShortArray(sampleCount)
            val bb = ByteBuffer.wrap(bytes, offset, length).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                val f = bb.float
                val clamped = (f * 32767f).coerceIn(-32768f, 32767f)
                samples[i] = clamped.toInt().toShort()
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        /**
         * Standard G.711 Mu-law decoding table.
         */
        private val ULAW_TABLE = ShortArray(256) { i ->
            var ulaw = (i xor 0xFF)
            var t = ((ulaw and 0x0F) shl 3) + 0x84
            t = t shl ((ulaw and 0x70) shr 4)
            val sample = if ((ulaw and 0x80) != 0) (0x84 - t) else (t - 0x84)
            sample.coerceIn(-32768, 32767).toShort()
        }

        /**
         * Standard G.711 A-law decoding table.
         */
        private val ALAW_TABLE = ShortArray(256) { i ->
            var alaw = i xor 0x55
            var t = (alaw and 0x0F) shl 4
            val seg = (alaw and 0x70) shr 4
            when (seg) {
                0 -> t += 8
                1 -> t += 0x108
                else -> {
                    t += 0x108
                    t = t shl (seg - 1)
                }
            }
            val sample = if ((alaw and 0x80) != 0) t else -t
            sample.coerceIn(-32768, 32767).toShort()
        }

        fun decodeUlaw(bytes: ByteArray, sampleRate: Int, channels: Int, offset: Int = 0, length: Int = bytes.size - offset): AudioPcmData {
            val samples = ShortArray(length)
            for (i in 0 until length) {
                samples[i] = ULAW_TABLE[bytes[offset + i].toInt() and 0xFF]
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }

        fun decodeAlaw(bytes: ByteArray, sampleRate: Int, channels: Int, offset: Int = 0, length: Int = bytes.size - offset): AudioPcmData {
            val samples = ShortArray(length)
            for (i in 0 until length) {
                samples[i] = ALAW_TABLE[bytes[offset + i].toInt() and 0xFF]
            }
            return AudioPcmData(sampleRate, channels, samples, 16)
        }
    }
}
