package com.infinifolder.audio.synth

enum class EnvelopeState {
    IDLE,
    ATTACK,
    DECAY,
    SUSTAIN,
    RELEASE
}

/**
 * Standard 4-stage ADSR volume and modulation envelope generator.
 */
class EnvelopeGenerator(
    var attackSec: Float = 0.01f,
    var decaySec: Float = 0.1f,
    var sustainLevel: Float = 0.7f,
    var releaseSec: Float = 0.2f
) {
    var state: EnvelopeState = EnvelopeState.IDLE
        private set
    var value: Float = 0.0f
        private set

    fun noteOn() {
        state = EnvelopeState.ATTACK
    }

    fun noteOff() {
        if (state != EnvelopeState.IDLE) {
            state = EnvelopeState.RELEASE
        }
    }

    fun stop() {
        state = EnvelopeState.IDLE
        value = 0.0f
    }

    fun step(sampleRate: Int): Float {
        val dt = 1.0f / sampleRate.coerceAtLeast(1)
        when (state) {
            EnvelopeState.IDLE -> {
                value = 0.0f
            }
            EnvelopeState.ATTACK -> {
                val rate = if (attackSec > 0.0001f) dt / attackSec else 1.0f
                value += rate
                if (value >= 1.0f) {
                    value = 1.0f
                    state = EnvelopeState.DECAY
                }
            }
            EnvelopeState.DECAY -> {
                val rate = if (decaySec > 0.0001f) dt / decaySec else 1.0f
                value -= rate * (1.0f - sustainLevel)
                if (value <= sustainLevel) {
                    value = sustainLevel
                    state = EnvelopeState.SUSTAIN
                }
            }
            EnvelopeState.SUSTAIN -> {
                value = sustainLevel
            }
            EnvelopeState.RELEASE -> {
                val rate = if (releaseSec > 0.0001f) dt / releaseSec else 1.0f
                value -= rate * sustainLevel.coerceAtLeast(0.1f)
                if (value <= 0.0001f) {
                    value = 0.0f
                    state = EnvelopeState.IDLE
                }
            }
        }
        return value.coerceIn(0.0f, 1.0f)
    }

    val isActive: Boolean
        get() = state != EnvelopeState.IDLE
}
