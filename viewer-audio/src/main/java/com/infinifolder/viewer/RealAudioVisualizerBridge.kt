package com.infinifolder.viewer

import android.os.Handler
import android.os.Looper
import com.infinifolder.audio.analysis.AudioSignalAnalysis
import com.infinifolder.audio.analysis.PlaybackPcmAnalyzer
import kotlin.math.max
import kotlin.math.min

/** Receives decoded playback PCM and exposes measurements to the visualizer views. */
class RealAudioVisualizerBridge(
    private val onUpdate: (() -> Unit)? = null
) {
    private var analyzer: PlaybackPcmAnalyzer? = null
    private var sourceId: Long = -1
    private var generation = 0L
    @Volatile var hasStereoVu: Boolean = false
        private set

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
    fun attach(source: AudioPlayerService.AnalysisSource, playback: () -> Pair<Boolean, Long>) {
        if (source.id == sourceId && analyzer != null) return
        detach()
        sourceId = source.id
        val thisGeneration = generation
        val next = PlaybackPcmAnalyzer(source.path, playback) { samples, channels, rate ->
            synchronized(this) {
                if (generation != thisGeneration) return@PlaybackPcmAnalyzer
                val levels = AudioSignalAnalysis.pcm(samples, channels, rate, waveformPoints, rawBands)
                hasStereoVu = levels.stereo
                vuLeft = (levels.left.rms * 1.8f).coerceIn(0f, 1f)
                vuRight = (levels.right.rms * 1.8f).coerceIn(0f, 1f)
                peakVuLeft = max(levels.left.peak, peakVuLeft - 0.03f).coerceIn(0f, 1f)
                peakVuRight = max(levels.right.peak, peakVuRight - 0.03f).coerceIn(0f, 1f)
                mainHandler.removeCallbacks(updateRunnable)
                mainHandler.post(updateRunnable)
            }
        }
        analyzer = next
        next.start()
    }

    @Synchronized
    fun detach() {
        generation++
        analyzer?.stop()
        analyzer = null
        sourceId = -1
        mainHandler.removeCallbacks(updateRunnable)
        reset()
    }

    @Synchronized
    fun reset() {
        rawBands.fill(0f); smoothBands.fill(0f); peakBands.fill(0f); peakFallSpeed.fill(0f)
        waveformPoints.fill(0f)
        vuLeft = 0f; vuRight = 0f; peakVuLeft = 0f; peakVuRight = 0f
        hasStereoVu = false
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
