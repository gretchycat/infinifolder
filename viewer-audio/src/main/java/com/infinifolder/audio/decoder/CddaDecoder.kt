package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CddaDecoder {

    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_FRAME = 4 // 2 channels * 2 bytes
    const val SECTOR_SIZE = 2352 // Standard Red Book CD-DA sector size (588 stereo frames)

    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean {
        val ext = extension?.lowercase() ?: ""
        if (ext == "cdda") return true
        return false
    }

    fun decode(bytes: ByteArray, extension: String? = null): AudioPcmData? {
        if (bytes.size < BYTES_PER_FRAME) return null

        val numFrames = bytes.size / BYTES_PER_FRAME
        val numSamples = numFrames * CHANNELS
        val outStereo = ShortArray(numSamples)

        // Detect endianness by comparing sample-to-sample difference variance
        val probeCount = minOf(numSamples, 256)
        var diffLe = 0L
        var diffBe = 0L

        var prevLe = ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        var prevBe = ((bytes[0].toInt() shl 8) or (bytes[1].toInt() and 0xFF)).toShort()

        for (i in 1 until probeCount) {
            val b0 = bytes[i * 2].toInt() and 0xFF
            val b1 = bytes[i * 2 + 1].toInt() and 0xFF
            val curLe = ((b1 shl 8) or b0).toShort()
            val curBe = ((b0 shl 8) or b1).toShort()
            diffLe += Math.abs(curLe.toInt() - prevLe.toInt())
            diffBe += Math.abs(curBe.toInt() - prevBe.toInt())
            prevLe = curLe
            prevBe = curBe
        }

        val useBigEndian = diffBe * 2 < diffLe

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(if (useBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            outStereo[i] = buffer.short
        }

        return AudioPcmData(
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            samples = outStereo,
            bitsPerSample = BITS_PER_SAMPLE
        )
    }
}
