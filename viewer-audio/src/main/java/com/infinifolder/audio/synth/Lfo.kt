package com.infinifolder.audio.synth

import kotlin.math.PI
import kotlin.math.sin

enum class LfoWaveform {
    SINE,
    TRIANGLE,
    SAWTOOTH,
    SQUARE
}

/**
 * Low Frequency Oscillator (LFO) for vibrato, tremolo, and auto-wah modulation.
 */
class Lfo(
    var frequencyHz: Float = 5.0f,
    var depth: Float = 0.5f,
    var waveform: LfoWaveform = LfoWaveform.SINE
) {
    private var phase: Float = 0.0f

    fun reset() {
        phase = 0.0f
    }

    fun step(sampleRate: Int): Float {
        val dt = frequencyHz / sampleRate.coerceAtLeast(1)
        phase = (phase + dt) % 1.0f

        val raw = when (waveform) {
            LfoWaveform.SINE -> sin(phase * 2.0 * PI).toFloat()
            LfoWaveform.TRIANGLE -> {
                if (phase < 0.5f) {
                    4.0f * phase - 1.0f
                } else {
                    3.0f - 4.0f * phase
                }
            }
            LfoWaveform.SAWTOOTH -> 2.0f * phase - 1.0f
            LfoWaveform.SQUARE -> if (phase < 0.5f) 1.0f else -1.0f
        }

        return raw * depth
    }
}
