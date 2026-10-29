package com.infinifolder.audio.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Independently decodes the staged playback source, keeping analysis near the player's position. */
class PlaybackPcmAnalyzer(
    private val path: String,
    private val playback: () -> Pair<Boolean, Long>,
    private val onSamples: (FloatArray, Int, Int) -> Unit
) {
    private val stopped = AtomicBoolean(false)
    private val thread = Thread({ run() }, "PlaybackPcmAnalyzer").apply { isDaemon = true }
    fun start() = thread.start()
    fun stop() { stopped.set(true); thread.interrupt() }

    private fun run() {
        val extractor = try {
            MediaExtractor()
        } catch (error: RuntimeException) {
            Log.w("PlaybackPcmAnalyzer", "Could not create extractor", error)
            com.infinifolder.storage.DiagnosticLog.record("audio.visualizer.failure", "Could not create extractor", error)
            return
        }
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return
            if (mime == "audio/raw") {
                analyzeRaw(extractor, inputFormat)
                return
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var lastOutputUs = -1L
            var lastEmitNs = 0L
            val info = MediaCodec.BufferInfo()
            while (!stopped.get()) {
                val (playing, posMs) = playback()
                if (!playing) { Thread.sleep(60); continue }
                val targetUs = posMs.coerceAtLeast(0) * 1000
                if (lastOutputUs >= 0 && (targetUs < lastOutputUs - 300_000 || targetUs > lastOutputUs + 800_000 || outputEnded)) {
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    decoder.flush()
                    inputEnded = false; outputEnded = false; lastOutputUs = -1L
                }
                if (outputEnded) { Thread.sleep(80); continue }
                if (lastOutputUs > targetUs + 120_000) { Thread.sleep(20); continue }
                if (!inputEnded) {
                    val inputId = decoder.dequeueInputBuffer(5_000)
                    if (inputId >= 0) {
                        val buffer = decoder.getInputBuffer(inputId) ?: throw IllegalStateException("Missing decoder input")
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(inputId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputId, 0, count, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                val outputId = decoder.dequeueOutputBuffer(info, 5_000)
                if (outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (outputId >= 0) {
                    lastOutputUs = info.presentationTimeUs
                    val now = System.nanoTime()
                    if (info.size > 0 && info.presentationTimeUs >= targetUs - 120_000 && now - lastEmitNs >= 65_000_000L) {
                        val output = decoder.getOutputBuffer(outputId)
                        if (output != null && channels in 1..8 && rate > 0) {
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val samples = readPcm(output.slice().order(ByteOrder.nativeOrder()), encoding, channels)
                            if (samples.isNotEmpty()) { onSamples(samples, channels, rate); lastEmitNs = now }
                        }
                    }
                    outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outputId, false)
                }
            }
        } catch (_: InterruptedException) {
            // The activity stopped showing the analyzer.
        } catch (e: Exception) {
            Log.w("PlaybackPcmAnalyzer", "Playback analysis unavailable", e)
            com.infinifolder.storage.DiagnosticLog.record("audio.visualizer.failure", "Playback analysis unavailable", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun analyzeRaw(extractor: MediaExtractor, format: MediaFormat) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (rate <= 0 || channels !in 1..8) return
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        val buffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder())
        var lastPositionUs = -1L
        while (!stopped.get()) {
            val (playing, positionMs) = playback()
            if (!playing) { Thread.sleep(60); continue }
            val targetUs = positionMs.coerceAtLeast(0) * 1000
            if (lastPositionUs < 0 || targetUs < lastPositionUs - 300_000 || targetUs > lastPositionUs + 800_000) {
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val sampleUs = extractor.sampleTime
            if (sampleUs < 0) { Thread.sleep(80); lastPositionUs = -1; continue }
            if (sampleUs > targetUs + 120_000) { Thread.sleep(20); continue }
            buffer.clear()
            val count = extractor.readSampleData(buffer, 0)
            if (count < 0) { Thread.sleep(80); lastPositionUs = -1; continue }
            lastPositionUs = sampleUs
            if (sampleUs >= targetUs - 120_000) {
                buffer.position(0); buffer.limit(count)
                val samples = readPcm(buffer, encoding, channels)
                if (samples.isNotEmpty()) onSamples(samples, channels, rate)
                Thread.sleep(65)
            }
            extractor.advance()
        }
    }

    private fun readPcm(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> return FloatArray(0)
        }
        val count = minOf(buffer.remaining() / bytes, 4096 * channels)
        val out = FloatArray(count - count % channels)
        for (i in out.indices) {
            out[i] = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                    value / 8388608f
                }
                else -> 0f
            }
        }
        return out
    }
}
