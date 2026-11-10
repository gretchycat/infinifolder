package com.infinifolder.viewer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.infinifolder.preview.BitmapAnimation
import java.io.Closeable
import java.util.concurrent.Executors

/** Renders Canvas bitmap animations (including IFF palette cycling) off the UI thread. */
internal class PlaylistImageAnimationPlayback(
    private val animation: BitmapAnimation,
    private val bitmap: Bitmap,
    private val onFrame: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "InfiniMediaImageAnimation").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val pixels = IntArray(animation.width * animation.height)
    private var elapsedBeforePlay = 0L
    private var playStartedAt = 0L
    private var generation = 0
    private var playing = false
    private var closed = false
    private var inFlight = false
    private var forceNext = true
    private var repaintPending = false

    private val frameTick = Runnable { render() }

    fun play() {
        if (closed || playing) return
        playing = true
        playStartedAt = SystemClock.elapsedRealtimeNanos()
        generation++
        forceNext = true
        main.removeCallbacks(frameTick)
        render()
    }

    fun pause() {
        if (closed || !playing) return
        elapsedBeforePlay = elapsedNanos()
        playing = false
        generation++
        main.removeCallbacks(frameTick)
    }

    private fun elapsedNanos(): Long = elapsedBeforePlay +
        if (playing) SystemClock.elapsedRealtimeNanos() - playStartedAt else 0L

    private fun render() {
        if (closed || !playing) return
        if (inFlight) {
            repaintPending = true
            return
        }
        inFlight = true
        val requestGeneration = generation
        val duration = animation.durationNanos
        val elapsed = elapsedNanos().let { if (duration > 0L) it % duration else it }
        val force = forceNext
        forceNext = false
        worker.execute {
            var error: Exception? = null
            val updated = try {
                animation.render(elapsed, pixels, force)
            } catch (problem: Exception) {
                error = problem
                false
            }
            main.post {
                inFlight = false
                if (closed) return@post
                if (requestGeneration == generation && playing) {
                    if (error != null) {
                        pause()
                        onFailure(checkNotNull(error))
                        return@post
                    }
                    if (updated && !bitmap.isRecycled) {
                        bitmap.setPixels(pixels, 0, animation.width, 0, 0, animation.width, animation.height)
                        onFrame()
                    }
                }
                if (repaintPending) {
                    repaintPending = false
                    render()
                } else if (requestGeneration == generation && playing &&
                    (animation.needsContinuousFrames || animation.needsMoreRendering)) {
                    main.postDelayed(frameTick, 33L)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        pause()
        closed = true
        repaintPending = false
        main.removeCallbacks(frameTick)
        worker.shutdownNow()
    }
}
