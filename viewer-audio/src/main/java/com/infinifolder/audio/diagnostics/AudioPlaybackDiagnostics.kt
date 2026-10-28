package com.infinifolder.audio.diagnostics

import android.net.Uri
import android.util.Log
import com.infinifolder.audio.model.AudioPcmData
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.log10
import kotlin.math.sqrt

enum class AudioHealthStatus {
    HEALTHY,
    SILENT,
    CLIPPING,
    DC_OFFSET,
    SUSPICIOUS_SHORT,
    DECODE_FAILED,
    PLAYBACK_ERROR
}

data class AudioSignalMetrics(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val totalFrames: Int,
    val minSample: Short,
    val maxSample: Short,
    val peakAmplitude: Float,
    val rmsDb: Float,
    val dcOffset: Float,
    val isSilent: Boolean,
    val isClipping: Boolean,
    val health: AudioHealthStatus
) {
    override fun toString(): String {
        return "AudioSignalMetrics(sr=${sampleRate}Hz, ch=$channels, dur=${durationMs}ms, peak=${"%.2f".format(peakAmplitude)}, rms=${"%.1f".format(rmsDb)}dB, silent=$isSilent, clip=$isClipping, health=$health)"
    }
}

data class AudioPlaybackReport(
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    var engineUsed: String = "Unknown",
    var durationMs: Long = 0L,
    var sampleRate: Int = 0,
    var channels: Int = 0,
    var bitsPerSample: Int = 16,
    var bitrateKbps: Int = 0,
    var isBitrateRelevant: Boolean = true,
    var isSampleRateRelevant: Boolean = true,
    var signalMetrics: AudioSignalMetrics? = null,
    val parsingErrors: MutableList<String> = mutableListOf(),
    val playbackErrors: MutableList<String> = mutableListOf(),
    var healthStatus: AudioHealthStatus = AudioHealthStatus.HEALTHY,
    var isPlaybackStarted: Boolean = false,
    var isPlaybackCompleted: Boolean = false,
    var sourceFormat: String? = null,
    val startTimeMillis: Long = System.currentTimeMillis()
) {
    fun formattedSummary(): String {
        val durSec = durationMs / 1000f
        val errors = (parsingErrors + playbackErrors).joinToString("; ").ifEmpty { "None" }
        val signalStr = signalMetrics?.let {
            "Peak: ${"%.2f".format(it.peakAmplitude)}, RMS: ${"%.1f".format(it.rmsDb)} dB, Silent: ${it.isSilent}, Clip: ${it.isClipping}"
        } ?: "Signal: Not analyzed"
        val brStr = if (isBitrateRelevant && bitrateKbps > 0) "$bitrateKbps kbps" else if (isBitrateRelevant) "-- kbps" else "-- (N/A for format)"
        val srStr = if (isSampleRateRelevant && sampleRate > 0) "$sampleRate Hz" else "--"
        val chStr = if (channels > 0) "$channels ch" else "--"

        return buildString {
            appendLine("=== Audio Playback Diagnostic Report ===")
            appendLine("File: $fileName ($fileSize bytes)")
            appendLine("MIME: $mimeType | Engine: $engineUsed")
            appendLine("Format: $srStr • $chStr • $bitsPerSample-bit • $brStr • ${"%.2f".format(durSec)}s")
            appendLine("Status: $healthStatus | Started: $isPlaybackStarted | Completed: $isPlaybackCompleted")
            appendLine("Audio Quality: $signalStr")
            if (errors != "None") {
                appendLine("Errors/Warnings: $errors")
            }
            appendLine("========================================")
        }
    }
}

object AudioPlaybackDiagnostics {
    private const val TAG = "AudioPlaybackDiagnostics"
    private const val MAX_HISTORY = 100

    private val reportHistory = ConcurrentLinkedDeque<AudioPlaybackReport>()
    @Volatile
    var currentReport: AudioPlaybackReport? = null
        private set

    fun getHistory(): List<AudioPlaybackReport> = reportHistory.toList()

    fun clearHistory() {
        reportHistory.clear()
        currentReport = null
    }

    /**
     * Inspects decoded PCM samples to extract peak amplitude, RMS energy, clipping, and silence metrics.
     */
    fun analyzePcm(pcm: AudioPcmData): AudioSignalMetrics {
        val samples = pcm.samples
        val channels = pcm.channels.coerceAtLeast(1)
        val sampleRate = pcm.sampleRate.coerceAtLeast(1)
        val totalFrames = samples.size / channels
        val durationMs = pcm.durationMs

        if (samples.isEmpty() || totalFrames == 0) {
            return AudioSignalMetrics(
                sampleRate = sampleRate,
                channels = channels,
                durationMs = 0L,
                totalFrames = 0,
                minSample = 0,
                maxSample = 0,
                peakAmplitude = 0f,
                rmsDb = -100f,
                dcOffset = 0f,
                isSilent = true,
                isClipping = false,
                health = AudioHealthStatus.SILENT
            )
        }

        var min = 0
        var max = 0
        var sumSquares = 0.0
        var sumSamples = 0.0
        var clipCount = 0

        for (s in samples) {
            val v = s.toInt()
            if (v < min) min = v
            if (v > max) max = v
            sumSquares += (v * v).toDouble()
            sumSamples += v.toDouble()
            if (v <= -32760 || v >= 32760) {
                clipCount++
            }
        }

        val peakInt = Math.max(Math.abs(min), Math.abs(max))
        val peakAmplitude = (peakInt / 32768.0f).coerceIn(0f, 1f)

        val meanSquare = sumSquares / samples.size
        val rms = sqrt(meanSquare)
        val rmsDb = if (rms > 0.001) {
            (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-100f, 0f)
        } else {
            -100f
        }

        val dcOffset = (sumSamples / (samples.size * 32768.0)).toFloat()
        val isSilent = peakAmplitude < 0.005f || rmsDb < -60f
        val isClipping = (clipCount.toFloat() / samples.size) > 0.01f // more than 1% clipping

        val health = when {
            isSilent -> AudioHealthStatus.SILENT
            isClipping -> AudioHealthStatus.CLIPPING
            Math.abs(dcOffset) > 0.15f -> AudioHealthStatus.DC_OFFSET
            durationMs < 100L -> AudioHealthStatus.SUSPICIOUS_SHORT
            else -> AudioHealthStatus.HEALTHY
        }

        return AudioSignalMetrics(
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            totalFrames = totalFrames,
            minSample = min.toShort(),
            maxSample = max.toShort(),
            peakAmplitude = peakAmplitude,
            rmsDb = rmsDb,
            dcOffset = dcOffset,
            isSilent = isSilent,
            isClipping = isClipping,
            health = health
        )
    }

    fun startTrack(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String
    ): AudioPlaybackReport {
        val report = AudioPlaybackReport(
            uri = uri.toString(),
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType
        )
        currentReport = report
        reportHistory.addFirst(report)
        while (reportHistory.size > MAX_HISTORY) {
            reportHistory.removeLast()
        }

        Log.i(TAG, "START_TRACK: $fileName (Size: $fileSize bytes, MIME: $mimeType, URI: $uri)")
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.start",
            "file=$fileName size=$fileSize mime=$mimeType uri=$uri"
        )
        return report
    }

    fun recordParserWarning(message: String, error: Throwable? = null) {
        val rep = currentReport ?: return
        val entry = if (error != null) "$message (${error.message})" else message
        rep.parsingErrors.add(entry)
        Log.w(TAG, "PARSER_WARNING [${rep.fileName}]: $entry", error)
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.parser.warning",
            "file=${rep.fileName} warning=$entry",
            error
        )
    }

    fun recordDecoderResult(
        engineUsed: String,
        pcm: AudioPcmData?,
        error: Throwable? = null,
        sourceFormat: String? = null
    ) {
        val rep = currentReport ?: return
        rep.engineUsed = engineUsed
        if (sourceFormat != null) {
            rep.sourceFormat = sourceFormat
        }
        if (pcm != null) {
            rep.sampleRate = pcm.sampleRate
            rep.channels = pcm.channels
            rep.bitsPerSample = pcm.bitsPerSample
            rep.durationMs = pcm.durationMs
            val metrics = analyzePcm(pcm)
            rep.signalMetrics = metrics
            rep.healthStatus = metrics.health

            Log.i(TAG, "DECODE_SUCCESS [${rep.fileName}]: Engine=$engineUsed, Rate=${pcm.sampleRate}Hz, Ch=${pcm.channels}, Dur=${pcm.durationMs}ms, Health=${metrics.health}, Peak=${"%.2f".format(metrics.peakAmplitude)}, RMS=${"%.1f".format(metrics.rmsDb)}dB")
            com.infinifolder.storage.DiagnosticLog.record(
                "audio.decode.success",
                "file=${rep.fileName} engine=$engineUsed sr=${pcm.sampleRate} ch=${pcm.channels} dur=${pcm.durationMs}ms health=${metrics.health} peak=${metrics.peakAmplitude} rms=${metrics.rmsDb}dB"
            )
            if (metrics.isSilent) {
                Log.w(TAG, "DECODE_WARNING [${rep.fileName}]: Audio is SILENT (RMS < -60dB or zero waveform)!")
                com.infinifolder.storage.DiagnosticLog.record(
                    "audio.decode.silent",
                    "file=${rep.fileName} rms=${metrics.rmsDb}dB"
                )
            }
            if (metrics.isClipping) {
                Log.w(TAG, "DECODE_WARNING [${rep.fileName}]: Audio waveform is CLIPPING!")
                com.infinifolder.storage.DiagnosticLog.record(
                    "audio.decode.clipping",
                    "file=${rep.fileName} peak=${metrics.peakAmplitude}"
                )
            }
        } else {
            rep.healthStatus = AudioHealthStatus.DECODE_FAILED
            val errStr = error?.message ?: "Decoder returned null PCM data"
            rep.parsingErrors.add(errStr)
            Log.e(TAG, "DECODE_FAILED [${rep.fileName}]: Engine=$engineUsed, Error=$errStr", error)
            com.infinifolder.storage.DiagnosticLog.record(
                "audio.decode.failed",
                "file=${rep.fileName} engine=$engineUsed error=$errStr",
                error
            )
        }
    }

    fun recordPlaybackStarted(
        engine: String,
        sampleRate: Int = 0,
        channels: Int = 0,
        bitrateKbps: Int = 0,
        isBitrateRelevant: Boolean = true,
        isSampleRateRelevant: Boolean = true,
        sourceFormat: String? = null
    ) {
        val rep = currentReport ?: return
        rep.engineUsed = engine
        rep.isPlaybackStarted = true
        if (sampleRate > 0) rep.sampleRate = sampleRate
        if (channels > 0) rep.channels = channels
        rep.bitrateKbps = bitrateKbps
        rep.isBitrateRelevant = isBitrateRelevant
        rep.isSampleRateRelevant = isSampleRateRelevant
        if (sourceFormat != null) rep.sourceFormat = sourceFormat
        Log.i(TAG, "PLAYBACK_STARTED [${rep.fileName}]: Active Engine=$engine, Rate=${rep.sampleRate}Hz, Ch=${rep.channels}, Bitrate=${rep.bitrateKbps}kbps, Duration=${rep.durationMs}ms")
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.playback.started",
            "file=${rep.fileName} engine=$engine sr=${rep.sampleRate} ch=${rep.channels} kbps=${rep.bitrateKbps} duration=${rep.durationMs}ms"
        )
    }

    fun recordPlaybackProgress(positionMs: Int, totalMs: Int) {
        val rep = currentReport ?: return
        if (rep.durationMs <= 0 && totalMs > 0) {
            rep.durationMs = totalMs.toLong()
        }
    }

    fun recordPlaybackError(message: String, error: Throwable? = null) {
        val rep = currentReport ?: return
        rep.healthStatus = AudioHealthStatus.PLAYBACK_ERROR
        val errStr = if (error != null) "$message: ${error.message}" else message
        rep.playbackErrors.add(errStr)
        Log.e(TAG, "PLAYBACK_ERROR [${rep.fileName}]: $errStr", error)
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.playback.error",
            "file=${rep.fileName} error=$errStr",
            error
        )
    }

    fun recordPlaybackCompleted() {
        val rep = currentReport ?: return
        rep.isPlaybackCompleted = true
        Log.i(TAG, "PLAYBACK_COMPLETED [${rep.fileName}]: Duration=${rep.durationMs}ms, Final Status=${rep.healthStatus}")
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.playback.completed",
            "file=${rep.fileName} duration=${rep.durationMs}ms status=${rep.healthStatus}"
        )
    }

    /**
     * Generates a markdown diagnostic summary table across all recent tracks.
     */
    fun generateMarkdownSummary(): String {
        return buildString {
            appendLine("# Audio Playback Diagnostics Report")
            appendLine()
            appendLine("| File | Engine | Status | Duration | Rate/Ch | Peak | RMS | Errors |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
            for (r in reportHistory) {
                val durSec = "%.1fs".format(r.durationMs / 1000f)
                val rateCh = "${r.sampleRate}Hz/${r.channels}ch"
                val peak = r.signalMetrics?.let { "%.2f".format(it.peakAmplitude) } ?: "N/A"
                val rms = r.signalMetrics?.let { "%.1fdB".format(it.rmsDb) } ?: "N/A"
                val errs = (r.parsingErrors + r.playbackErrors).joinToString("; ").ifEmpty { "None" }
                appendLine("| `${r.fileName}` | ${r.engineUsed} | **${r.healthStatus}** | $durSec | $rateCh | $peak | $rms | $errs |")
            }
        }
    }
}
