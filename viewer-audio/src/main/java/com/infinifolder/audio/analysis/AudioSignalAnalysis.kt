package com.infinifolder.audio.analysis

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Signal measurements from the playback session's mono waveform and packed real FFT. */
object AudioSignalAnalysis {
    data class Level(val rms: Float, val peak: Float)

    fun waveform(samples: ByteArray, output: FloatArray): Level {
        if (samples.isEmpty()) { output.fill(0f); return Level(0f, 0f) }
        var squares = 0.0
        var peak = 0f
        for (sample in samples) {
            val value = ((sample.toInt() and 0xff) - 128) / 128f
            squares += value * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        for (i in output.indices) {
            val index = (i.toLong() * samples.size / output.size).toInt()
            output[i] = ((samples[index].toInt() and 0xff) - 128) / 128f
        }
        return Level(sqrt(squares / samples.size).toFloat(), peak)
    }

    data class StereoLevel(val left: Level, val right: Level, val stereo: Boolean)

    /** Analyze decoded, interleaved PCM without using an audio capture device. */
    fun pcm(samples: FloatArray, channels: Int, sampleRateHz: Int, waveform: FloatArray, bands: FloatArray): StereoLevel {
        val frames = if (channels > 0) samples.size / channels else 0
        if (frames == 0 || sampleRateHz <= 0) {
            waveform.fill(0f); bands.fill(0f)
            return StereoLevel(Level(0f, 0f), Level(0f, 0f), false)
        }
        var sumLeft = 0.0; var sumRight = 0.0
        var peakLeft = 0f; var peakRight = 0f
        for (frame in 0 until frames) {
            val l = samples[frame * channels].coerceIn(-1f, 1f)
            val r = samples[frame * channels + if (channels > 1) 1 else 0].coerceIn(-1f, 1f)
            sumLeft += l * l; sumRight += r * r
            peakLeft = maxOf(peakLeft, kotlin.math.abs(l)); peakRight = maxOf(peakRight, kotlin.math.abs(r))
        }
        for (i in waveform.indices) {
            val frame = (i.toLong() * frames / waveform.size).toInt()
            val l = samples[frame * channels]
            val r = samples[frame * channels + if (channels > 1) 1 else 0]
            waveform[i] = (l + r) * 0.5f
        }
        val size = 1024
        val re = DoubleArray(size); val im = DoubleArray(size)
        val chosenChannel = if (sumRight > sumLeft && channels > 1) 1 else 0
        val count = minOf(size, frames)
        for (i in 0 until count) {
            val window = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (size - 1))
            re[i] = samples[i * channels + chosenChannel] * window
        }
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= size) {
            val angle = -2 * Math.PI / len
            val stepRe = kotlin.math.cos(angle); val stepIm = kotlin.math.sin(angle)
            for (start in 0 until size step len) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k; val b = a + len / 2
                    val tr = wr * re[b] - wi * im[b]; val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val next = wr * stepRe - wi * stepIm; wi = wr * stepIm + wi * stepRe; wr = next
                }
            }
            len *= 2
        }
        bands.fill(0f)
        val low = maxOf(20.0, sampleRateHz.toDouble() / size)
        val high = minOf(20000.0, sampleRateHz / 2.0)
        if (high > low && bands.isNotEmpty()) {
            for (band in bands.indices) {
                val first = (low * (high / low).pow(band.toDouble() / bands.size) * size / sampleRateHz).toInt().coerceIn(1, size / 2 - 1)
                val last = (low * (high / low).pow((band + 1.0) / bands.size) * size / sampleRateHz).toInt().coerceIn(first, size / 2 - 1)
                var energy = 0.0
                for (bin in first..last) energy += re[bin] * re[bin] + im[bin] * im[bin]
                val magnitude = sqrt(energy / (last - first + 1)) / (size / 4.0)
                bands[band] = (ln(1.0 + magnitude * 100.0) / ln(101.0)).toFloat().coerceIn(0f, 1f)
            }
        }
        return StereoLevel(Level(sqrt(sumLeft / frames).toFloat(), peakLeft), Level(sqrt(sumRight / frames).toFloat(), peakRight), channels > 1)
    }

    /** Android's FFT is packed as DC, Nyquist, then complex bins 1..N/2-1. */
    fun spectrum(fft: ByteArray, sampleRateHz: Float, output: FloatArray) {
        output.fill(0f)
        val n = fft.size
        if (n < 4 || output.isEmpty() || sampleRateHz <= 0f) return
        val maxBin = n / 2 - 1
        val nyquist = sampleRateHz / 2.0
        val lowHz = maxOf(20.0, sampleRateHz.toDouble() / n)
        val highHz = minOf(20000.0, nyquist)
        if (highHz <= lowHz) return
        val ratio = highHz / lowHz
        for (band in output.indices) {
            val firstHz = lowHz * ratio.pow(band.toDouble() / output.size)
            val lastHz = lowHz * ratio.pow((band + 1.0) / output.size)
            val first = (firstHz * n / sampleRateHz).toInt().coerceIn(1, maxBin)
            val last = (lastHz * n / sampleRateHz).toInt().coerceIn(first, maxBin)
            var energy = 0.0
            for (bin in first..last) {
                val magnitude = hypot(fft[bin * 2].toDouble(), fft[bin * 2 + 1].toDouble())
                energy += magnitude * magnitude
            }
            val rms = sqrt(energy / (last - first + 1))
            output[band] = (ln(1.0 + rms) / ln(129.0)).toFloat().coerceIn(0f, 1f)
        }
    }
}
