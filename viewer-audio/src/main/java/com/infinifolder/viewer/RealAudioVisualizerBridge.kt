package com.infinifolder.viewer

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hardware audio visualizer bridge capturing real FFT spectrum, waveform,
 * and stereo RMS VU levels directly from the active MediaPlayer audioSessionId.
 * Never generates random or fake animations.
 */
class RealAudioVisualizerBridge(
    private val onUpdate: (() -> Unit)? = null
) {
    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = 0

    val bandCount = 16
    private val rawBands = FloatArray(bandCount)
    private val smoothBands = FloatArray(bandCount)
    private val peakBands = FloatArray(bandCount)
    private val peakFallSpeed = FloatArray(bandCount)

    private val waveformPoints = FloatArray(128)
    private var vuLeft: Float = 0f
    private var vuRight: Float = 0f
    private var peakVuLeft: Float = 0f
    private var peakVuRight: Float = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable {
        onUpdate?.invoke()
    }

    @Synchronized
    fun attach(sessionId: Int) {
        if (sessionId == currentSessionId && visualizer != null) return
        detach()
        if (sessionId <= 0) return

        currentSessionId = sessionId
        try {
            val vis = Visualizer(sessionId)
            val captureRange = Visualizer.getCaptureSizeRange()
            val capSize = captureRange[1].coerceAtMost(1024)
            vis.captureSize = capSize

            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform != null && waveform.isNotEmpty()) {
                        processWaveform(waveform)
                    }
                }

                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null && fft.isNotEmpty()) {
                        processFft(fft)
                    }
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true)

            vis.enabled = true
            visualizer = vis
        } catch (_: Exception) {
            // Permission denied or audio effect not supported on this device/session.
            visualizer = null
        }
    }

    @Synchronized
    fun detach() {
        currentSessionId = 0
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
        reset()
    }

    @Synchronized
    fun reset() {
        for (i in 0 until bandCount) {
            rawBands[i] = 0f
            smoothBands[i] = 0f
            peakBands[i] = 0f
            peakFallSpeed[i] = 0f
        }
        for (i in waveformPoints.indices) {
            waveformPoints[i] = 0f
        }
        vuLeft = 0f
        vuRight = 0f
        peakVuLeft = 0f
        peakVuRight = 0f
    }

    private fun processFft(fft: ByteArray) {
        val n = fft.size
        val halfN = n / 2
        if (halfN <= 1) return

        val magnitudes = FloatArray(halfN)
        // DC
        magnitudes[0] = Math.abs(fft[0].toFloat())
        // Nyquist
        magnitudes[halfN - 1] = Math.abs(fft[1].toFloat())

        for (k in 1 until halfN - 1) {
            val r = fft[2 * k].toFloat()
            val i = fft[2 * k + 1].toFloat()
            magnitudes[k] = sqrt(r * r + i * i)
        }

        // Map halfN bins to 16 logarithmic frequency bands
        synchronized(this) {
            for (b in 0 until bandCount) {
                // Logarithmic frequency bin distribution
                val startRatio = Math.pow(b.toDouble() / bandCount, 2.2)
                val endRatio = Math.pow((b + 1.0) / bandCount, 2.2)
                val startBin = (startRatio * (halfN - 1)).toInt().coerceIn(1, halfN - 1)
                val endBin = (endRatio * (halfN - 1)).toInt().coerceIn(startBin + 1, halfN)

                var sum = 0f
                var count = 0
                for (bin in startBin until endBin) {
                    sum += magnitudes[bin]
                    count++
                }
                val avg = if (count > 0) sum / count else 0f
                // Normalization: audio FFT amplitudes typically range 0..128
                val normalized = (avg / 64f).coerceIn(0f, 1f)
                rawBands[b] = normalized
            }
        }

        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.post(updateRunnable)
    }

    private fun processWaveform(waveform: ByteArray) {
        val len = waveform.size
        var sumSquaresL = 0.0
        var countL = 0
        var sumSquaresR = 0.0
        var countR = 0

        val outLen = waveformPoints.size
        synchronized(this) {
            for (i in 0 until outLen) {
                val sampleIdx = (i.toFloat() / outLen * len).toInt().coerceIn(0, len - 1)
                val raw = (waveform[sampleIdx].toInt() and 0xFF) - 128
                waveformPoints[i] = raw / 128.0f
            }

            // Calculate true RMS for Left and Right channels
            for (i in 0 until len) {
                val raw = (waveform[i].toInt() and 0xFF) - 128
                val norm = raw / 128.0
                if (i % 2 == 0) {
                    sumSquaresL += norm * norm
                    countL++
                } else {
                    sumSquaresR += norm * norm
                    countR++
                }
            }

            val rmsL = if (countL > 0) sqrt(sumSquaresL / countL).toFloat() else 0f
            val rmsR = if (countR > 0) sqrt(sumSquaresR / countR).toFloat() else 0f

            // Slightly amplify RMS for authentic responsive VU meter display (up to 1.0)
            vuLeft = (rmsL * 1.8f).coerceIn(0f, 1f)
            vuRight = (rmsR * 1.8f).coerceIn(0f, 1f)

            if (vuLeft > peakVuLeft) peakVuLeft = vuLeft else peakVuLeft = (peakVuLeft - 0.03f).coerceAtLeast(0f)
            if (vuRight > peakVuRight) peakVuRight = vuRight else peakVuRight = (peakVuRight - 0.03f).coerceAtLeast(0f)
        }

        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.post(updateRunnable)
    }

    /**
     * Updates physics and fills destination spectrum array.
     */
    @Synchronized
    fun getSpectrum(outBands: FloatArray, outPeaks: FloatArray? = null, isPlaying: Boolean = true) {
        val count = min(bandCount, outBands.size)
        for (i in 0 until count) {
            val target = if (isPlaying) rawBands[i] else 0f
            smoothBands[i] += (target - smoothBands[i]) * 0.35f
            outBands[i] = smoothBands[i]

            if (outPeaks != null && i < outPeaks.size) {
                if (smoothBands[i] >= peakBands[i]) {
                    peakBands[i] = smoothBands[i]
                    peakFallSpeed[i] = 0f
                } else {
                    peakFallSpeed[i] += 0.02f // Simulated gravity
                    peakBands[i] = (peakBands[i] - peakFallSpeed[i]).coerceAtLeast(0f)
                }
                outPeaks[i] = peakBands[i]
            }
        }
    }

    @Synchronized
    fun getWaveform(outWaveform: FloatArray, isPlaying: Boolean = true) {
        val count = min(waveformPoints.size, outWaveform.size)
        for (i in 0 until count) {
            outWaveform[i] = if (isPlaying) waveformPoints[i] else 0f
        }
    }

    @Synchronized
    fun getVuLevels(outVu: FloatArray, isPlaying: Boolean = true) {
        if (outVu.isNotEmpty()) outVu[0] = if (isPlaying) vuLeft else 0f
        if (outVu.size > 1) outVu[1] = if (isPlaying) vuRight else 0f
        if (outVu.size > 2) outVu[2] = if (isPlaying) peakVuLeft else 0f
        if (outVu.size > 3) outVu[3] = if (isPlaying) peakVuRight else 0f
    }
}
