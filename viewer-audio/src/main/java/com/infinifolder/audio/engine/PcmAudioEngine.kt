package com.infinifolder.audio.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics
import com.infinifolder.audio.model.AudioPcmData
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance low-latency PCM audio playback engine using Android AudioTrack.
 */
class PcmAudioEngine(
    private val pcmData: AudioPcmData,
    private val onCompletion: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "PcmAudioEngine"
    }

    private val stereoPcm = pcmData.toStereo()
    val durationMs: Int = stereoPcm.durationMs.toInt()

    private val isPlayingState = AtomicBoolean(false)
    private val isReleasedState = AtomicBoolean(false)

    @Volatile
    private var playbackThread: Thread? = null
    @Volatile
    private var currentFramePosition: Int = 0

    private var audioTrack: AudioTrack? = null

    val isPlaying: Boolean
        get() = isPlayingState.get() && !isReleasedState.get()

    val currentPositionMs: Int
        get() {
            val totalFrames = stereoPcm.samples.size / 2
            if (totalFrames <= 0) return 0
            val fraction = currentFramePosition.toDouble() / totalFrames
            return (fraction * durationMs).toInt().coerceIn(0, durationMs)
        }

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        runCatching {
            val minBufSize = AudioTrack.getMinBufferSize(
                stereoPcm.sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(stereoPcm.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.i(TAG, "AudioTrack initialized: Rate=${stereoPcm.sampleRate}Hz, Stereo, Buffer=${minBufSize * 2} bytes, TotalFrames=${stereoPcm.samples.size / 2}")
        }.onFailure {
            Log.w(TAG, "AudioTrack initialization failed: ${it.message}")
        }
    }

    fun play() {
        if (isReleasedState.get()) return
        if (isPlayingState.compareAndSet(false, true)) {
            Log.i(TAG, "AudioTrack play requested at frame $currentFramePosition / ${stereoPcm.samples.size / 2}")
            val track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                isPlayingState.set(false)
                AudioPlaybackDiagnostics.recordPlaybackError("AudioTrack is unavailable")
                return
            }
            try { track.play() } catch (error: Exception) {
                isPlayingState.set(false)
                AudioPlaybackDiagnostics.recordPlaybackError("AudioTrack play failed: ${error.message}", error)
                return
            }
            AudioPlaybackDiagnostics.recordPlaybackStarted("PcmAudioEngine(AudioTrack)")
            startPlaybackThread()
        }
    }

    fun pause() {
        if (isPlayingState.compareAndSet(true, false)) {
            Log.i(TAG, "AudioTrack pause requested at position ${currentPositionMs}ms")
            runCatching { audioTrack?.pause() }
            playbackThread?.interrupt()
            playbackThread = null
        }
    }

    fun seekTo(positionMs: Int) {
        val totalFrames = stereoPcm.samples.size / 2
        val targetFrame = if (durationMs > 0) {
            ((positionMs.toLong() * totalFrames) / durationMs).toInt().coerceIn(0, totalFrames)
        } else 0
        Log.i(TAG, "AudioTrack seekTo: ${positionMs}ms -> frame $targetFrame / $totalFrames")
        currentFramePosition = targetFrame
        runCatching { audioTrack?.flush() }
    }

    fun setSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val params = audioTrack?.playbackParams ?: android.media.PlaybackParams()
                audioTrack?.playbackParams = params.setSpeed(speed.coerceIn(0.5f, 2.0f))
                Log.i(TAG, "AudioTrack speed set to $speed")
            }
        }
    }

    private fun startPlaybackThread() {
        playbackThread?.interrupt()
        playbackThread = Thread {
            val samples = stereoPcm.samples
            val totalFrames = samples.size / 2
            val chunkSizeFrames = 1024
            val shortBuf = ShortArray(chunkSizeFrames * 2)

            while (isPlayingState.get() && !isReleasedState.get() && currentFramePosition < totalFrames) {
                val framesToWrite = (totalFrames - currentFramePosition).coerceAtMost(chunkSizeFrames)
                val sampleOffset = currentFramePosition * 2
                System.arraycopy(samples, sampleOffset, shortBuf, 0, framesToWrite * 2)

                val written = try {
                    audioTrack?.write(shortBuf, 0, framesToWrite * 2) ?: -1
                } catch (e: Exception) {
                    AudioPlaybackDiagnostics.recordPlaybackError("AudioTrack write failure: ${e.message}", e)
                    -1
                }

                if (written <= 0 || written % 2 != 0) {
                    isPlayingState.set(false)
                    AudioPlaybackDiagnostics.recordPlaybackError("AudioTrack did not accept complete PCM frames: $written")
                    break
                }
                currentFramePosition += written / 2

            }

            if (currentFramePosition >= totalFrames && isPlayingState.compareAndSet(true, false)) {
                Log.i(TAG, "AudioTrack playback reached end of stream (${durationMs}ms)")
                AudioPlaybackDiagnostics.recordPlaybackCompleted()
                onCompletion?.invoke()
            }
        }.apply {
            isDaemon = true
            name = "PcmAudioPlaybackThread"
            start()
        }
    }

    fun release() {
        if (isReleasedState.compareAndSet(false, true)) {
            Log.i(TAG, "AudioTrack releasing engine resources")
            isPlayingState.set(false)
            playbackThread?.interrupt()
            playbackThread = null
            runCatching {
                audioTrack?.stop()
                audioTrack?.release()
            }
            audioTrack = null
        }
    }
}
