package com.infinifolder.audio.synth

enum class LoopMode {
    NONE,
    FORWARD,
    PING_PONG
}

/**
 * High-performance audio resampler supporting linear and Hermite cubic interpolation,
 * arbitrary playback pitch ratios, and forward / ping-pong looping.
 */
class SampleResampler(
    private val sampleData: ShortArray,
    private val sampleRate: Int,
    val loopStart: Int = 0,
    val loopEnd: Int = sampleData.size,
    val loopMode: LoopMode = LoopMode.NONE
) {
    var position: Double = 0.0
    var isFinished: Boolean = false
        private set
    private var pingPongForward: Boolean = true

    fun reset() {
        position = 0.0
        isFinished = false
        pingPongForward = true
    }

    /**
     * Reads the next sample interpolated at fractional position and advances by [step].
     * [step] = targetFrequency / sampleRate.
     */
    fun nextSample(step: Double, useCubic: Boolean = false): Float {
        if (isFinished || sampleData.isEmpty()) return 0f

        val len = sampleData.size
        val intPos = position.toInt()
        val frac = (position - intPos).toFloat()

        val sampleVal = if (useCubic && intPos > 0 && intPos + 2 < len) {
            hermiteInterpolate(
                sampleData[intPos - 1].toFloat(),
                sampleData[intPos].toFloat(),
                sampleData[intPos + 1].toFloat(),
                sampleData[intPos + 2].toFloat(),
                frac
            )
        } else {
            val s0 = if (intPos in 0 until len) sampleData[intPos].toFloat() else 0f
            val s1 = if (intPos + 1 in 0 until len) sampleData[intPos + 1].toFloat() else s0
            s0 + frac * (s1 - s0)
        }

        // Advance position based on looping mode
        if (loopMode == LoopMode.NONE) {
            position += step
            if (position >= len) {
                isFinished = true
            }
        } else if (loopMode == LoopMode.FORWARD) {
            position += step
            val lStart = loopStart.coerceIn(0, len - 1)
            val lEnd = loopEnd.coerceIn(lStart + 1, len)
            val lLen = lEnd - lStart
            if (position >= lEnd && lLen > 0) {
                position = lStart + ((position - lStart) % lLen)
            }
        } else if (loopMode == LoopMode.PING_PONG) {
            val lStart = loopStart.coerceIn(0, len - 1)
            val lEnd = loopEnd.coerceIn(lStart + 1, len)
            if (pingPongForward) {
                position += step
                if (position >= lEnd) {
                    position = lEnd - (position - lEnd)
                    pingPongForward = false
                }
            } else {
                position -= step
                if (position <= lStart) {
                    position = lStart + (lStart - position)
                    pingPongForward = true
                }
            }
        }

        return (sampleVal / 32768.0f).coerceIn(-1.0f, 1.0f)
    }

    companion object {
        fun hermiteInterpolate(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
            val m0 = (y2 - y0) * 0.5f
            val m1 = (y3 - y1) * 0.5f
            val mu2 = mu * mu
            val mu3 = mu2 * mu
            val a0 = 2f * mu3 - 3f * mu2 + 1f
            val a1 = mu3 - 2f * mu2 + mu
            val a2 = mu3 - mu2
            val a3 = -2f * mu3 + 3f * mu2
            return a0 * y1 + a1 * m0 + a2 * m1 + a3 * y2
        }
    }
}
