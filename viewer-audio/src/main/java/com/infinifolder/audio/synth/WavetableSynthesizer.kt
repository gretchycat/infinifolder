package com.infinifolder.audio.synth

import com.infinifolder.audio.model.AudioPcmData
import kotlin.math.pow

class Voice {
    var isActive: Boolean = false
    var channel: Int = 0
    var note: Int = 0
    var velocity: Float = 0.8f
    var baseFreq: Double = 261.63 // C4
    var targetFreq: Double = 261.63
    var pitchBendFactor: Double = 1.0
    var panLeft: Float = 0.7f
    var panRight: Float = 0.7f

    var sample: SoundFontSample? = null
    var resampler: SampleResampler? = null
    val envelope = EnvelopeGenerator(attackSec = 0.005f, decaySec = 0.15f, sustainLevel = 0.6f, releaseSec = 0.25f)
    val vibrato = Lfo(frequencyHz = 5.5f, depth = 0.015f, waveform = LfoWaveform.SINE)

    fun start(ch: Int, midiNote: Int, vel: Float, sample: SoundFontSample, pitchBendSemitones: Float = 0f) {
        this.sample = sample
        channel = ch
        note = midiNote
        velocity = (vel / 127f).coerceIn(0.1f, 1.0f)
        targetFreq = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
        baseFreq = 440.0 * 2.0.pow((sample.originalPitch - 69) / 12.0)
        pitchBendFactor = 2.0.pow(pitchBendSemitones / 12.0)

        // Panning (default center with slight stereo spread based on note)
        val panNorm = ((midiNote - 36).toFloat() / 60f).coerceIn(0.2f, 0.8f)
        panLeft = (1.0f - panNorm).coerceIn(0.1f, 1.0f)
        panRight = panNorm.coerceIn(0.1f, 1.0f)

        resampler = SampleResampler(
            sampleData = sample.pcmSamples,
            sampleRate = sample.sampleRate,
            loopStart = sample.loopStart,
            loopEnd = sample.loopEnd,
            loopMode = sample.loopMode
        )
        envelope.noteOn()
        vibrato.reset()
        isActive = true
    }

    fun release() {
        envelope.noteOff()
    }

    fun stop() {
        envelope.stop()
        resampler = null
        isActive = false
    }

    fun step(outStereo: FloatArray, offset: Int, sampleRate: Int) {
        if (!isActive) return
        val r = resampler ?: return

        val vib = vibrato.step(sampleRate)
        val currentPitchRatio = (targetFreq / baseFreq) * pitchBendFactor * (1.0 + vib)
        val s = sample
        val srRatio = if (s != null && s.sampleRate > 0) s.sampleRate.toDouble() / sampleRate.toDouble() else 1.0
        val stepSize = currentPitchRatio * srRatio

        val rawSample = r.nextSample(stepSize)
        val env = envelope.step(sampleRate)

        if (!envelope.isActive || r.isFinished) {
            isActive = false
            return
        }

        val amp = rawSample * env * velocity
        outStereo[offset] += amp * panLeft
        outStereo[offset + 1] += amp * panRight
    }
}

/**
 * Polyphonic wavetable synthesizer engine with SoundFont support and built-in fallback instruments.
 */
class WavetableSynthesizer(
    val sampleRate: Int = 44100,
    val maxPolyphony: Int = 32
) {
    private val voices = Array(maxPolyphony) { Voice() }
    var soundFont: SoundFontBank? = null

    // Channel parameters
    private val channelPrograms = IntArray(16) { 0 }
    private val channelVolumes = FloatArray(16) { 1.0f }
    private val channelPitchBends = FloatArray(16) { 0.0f }

    private val defaultSample: SoundFontSample by lazy {
        val tone = SoundFontReader.synthesizeHarmonicTone(sampleRate, 261.63, 1.5)
        SoundFontSample("DefaultPiano", sampleRate, 60, 0, 0, tone.size, LoopMode.FORWARD, tone)
    }

    fun noteOn(channel: Int, note: Int, velocity: Int) {
        if (velocity <= 0) {
            noteOff(channel, note)
            return
        }

        val ch = channel.coerceIn(0, 15)
        val program = channelPrograms[ch]
        val sample = soundFont?.findSampleForNote(program, note) ?: defaultSample

        // Find available voice or steal oldest
        var targetVoice = voices.firstOrNull { !it.isActive }
        if (targetVoice == null) {
            targetVoice = voices.minByOrNull { it.envelope.value } ?: voices[0]
        }

        targetVoice.start(
            ch = ch,
            midiNote = note,
            vel = velocity.toFloat(),
            sample = sample,
            pitchBendSemitones = channelPitchBends[ch]
        )
    }

    fun noteOff(channel: Int, note: Int) {
        val ch = channel.coerceIn(0, 15)
        for (v in voices) {
            if (v.isActive && v.channel == ch && v.note == note) {
                v.release()
            }
        }
    }

    fun programChange(channel: Int, program: Int) {
        val ch = channel.coerceIn(0, 15)
        channelPrograms[ch] = program.coerceIn(0, 127)
    }

    fun pitchBend(channel: Int, semitones: Float) {
        val ch = channel.coerceIn(0, 15)
        channelPitchBends[ch] = semitones
        for (v in voices) {
            if (v.isActive && v.channel == ch) {
                v.pitchBendFactor = 2.0.pow(semitones / 12.0)
            }
        }
    }

    fun allNotesOff() {
        for (v in voices) v.stop()
    }

    /**
     * Renders [frameCount] interleaved stereo frames into [outBuffer] starting at [offset].
     */
    fun renderFrames(outBuffer: FloatArray, offset: Int, frameCount: Int) {
        val end = offset + frameCount * 2
        for (i in offset until end) {
            outBuffer[i] = 0f
        }

        for (frame in 0 until frameCount) {
            val frameOffset = offset + frame * 2
            for (v in voices) {
                if (v.isActive) {
                    v.step(outBuffer, frameOffset, sampleRate)
                }
            }
        }
    }

    /**
     * Synthesizes audio until all active notes finish playing (up to maxDurationMs).
     */
    fun renderToPcm(maxDurationMs: Long = 5000L): AudioPcmData {
        val totalFrames = ((maxDurationMs * sampleRate) / 1000L).toInt()
        val chunkSize = 512
        val floatBuf = FloatArray(chunkSize * 2)
        val shortList = ShortArray(totalFrames * 2)

        var frame = 0
        var outIdx = 0
        while (frame < totalFrames) {
            val count = (totalFrames - frame).coerceAtMost(chunkSize)
            renderFrames(floatBuf, 0, count)
            for (i in 0 until count * 2) {
                // Soft-knee limiter / saturation
                val f = floatBuf[i]
                val limited = if (f > 1.0f) 1.0f else if (f < -1.0f) -1.0f else f
                shortList[outIdx++] = (limited * 32767.0f).toInt().toShort()
            }
            frame += count
        }

        return AudioPcmData(sampleRate, 2, shortList.copyOf(outIdx), 16)
    }
}
