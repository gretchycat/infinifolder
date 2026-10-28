package com.infinifolder.audio.tracker

import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.synth.SoundFontBank
import com.infinifolder.audio.synth.WavetableSynthesizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MidiEvent(
    val timeTicks: Long,
    val status: Int,
    val data1: Int = 0,
    val data2: Int = 0,
    val metaType: Int = 0,
    val metaData: ByteArray = ByteArray(0)
) : Comparable<MidiEvent> {
    override fun compareTo(other: MidiEvent): Int = timeTicks.compareTo(other.timeTicks)
}

object MidiSequencer {

    fun isMidi(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "MThd") return true
        if (bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "RMID") return true
        return false
    }

    fun unwrapRmid(bytes: ByteArray): ByteArray {
        if (bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "RMID") {
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val chunkDataPos = pos + 8
                if (chunkId == "data" && chunkDataPos < bytes.size) {
                    val len = chunkSize.coerceAtMost(bytes.size - chunkDataPos)
                    return bytes.copyOfRange(chunkDataPos, chunkDataPos + len)
                }
                pos = chunkDataPos + chunkSize
            }
        }
        return bytes
    }

    fun renderToPcm(
        rawBytes: ByteArray,
        soundFont: SoundFontBank? = null,
        sampleRate: Int = 44100,
        maxDurationMs: Long = 120000L
    ): AudioPcmData? {
        val midiBytes = unwrapRmid(rawBytes)
        if (midiBytes.size < 14 || String(midiBytes, 0, 4, Charsets.US_ASCII) != "MThd") return null

        val bb = ByteBuffer.wrap(midiBytes).order(ByteOrder.BIG_ENDIAN)
        bb.int // skip "MThd"
        val headerLen = bb.int
        val format = bb.short.toInt() and 0xFFFF
        val numTracks = bb.short.toInt() and 0xFFFF
        val division = bb.short.toInt() and 0xFFFF // Ticks per quarter note
        val ticksPerQuarter = if (division > 0) division else 480

        var pos = 8 + headerLen
        val allEvents = mutableListOf<MidiEvent>()

        for (t in 0 until numTracks) {
            if (pos + 8 > midiBytes.size) break
            val trackMagic = String(midiBytes, pos, 4, Charsets.US_ASCII)
            val trackLen = ByteBuffer.wrap(midiBytes, pos + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val trackDataPos = pos + 8
            val trackEnd = (trackDataPos + trackLen).toInt().coerceAtMost(midiBytes.size)
            pos = trackEnd

            if (trackMagic != "MTrk") continue

            var curPos = trackDataPos
            var currentTick = 0L
            var runningStatus = 0

            while (curPos < trackEnd) {
                // Read delta time (variable-length quantity)
                var deltaTime = 0L
                while (curPos < trackEnd) {
                    val b = midiBytes[curPos++].toInt() and 0xFF
                    deltaTime = (deltaTime shl 7) or (b and 0x7F).toLong()
                    if ((b and 0x80) == 0) break
                }
                currentTick += deltaTime

                if (curPos >= trackEnd) break
                var status = midiBytes[curPos].toInt() and 0xFF
                if (status >= 0x80) {
                    runningStatus = status
                    curPos++
                } else {
                    status = runningStatus
                }

                val type = status and 0xF0
                when (type) {
                    0x80, 0x90, 0xA0, 0xB0, 0xE0 -> {
                        val d1 = if (curPos < trackEnd) midiBytes[curPos++].toInt() and 0xFF else 0
                        val d2 = if (curPos < trackEnd) midiBytes[curPos++].toInt() and 0xFF else 0
                        allEvents.add(MidiEvent(currentTick, status, d1, d2))
                    }
                    0xC0, 0xD0 -> {
                        val d1 = if (curPos < trackEnd) midiBytes[curPos++].toInt() and 0xFF else 0
                        allEvents.add(MidiEvent(currentTick, status, d1, 0))
                    }
                    0xF0 -> {
                        if (status == 0xFF) { // Meta event
                            val metaType = if (curPos < trackEnd) midiBytes[curPos++].toInt() and 0xFF else 0
                            var metaLen = 0
                            while (curPos < trackEnd) {
                                val b = midiBytes[curPos++].toInt() and 0xFF
                                metaLen = (metaLen shl 7) or (b and 0x7F)
                                if ((b and 0x80) == 0) break
                            }
                            val metaData = if (curPos + metaLen <= trackEnd) {
                                val arr = midiBytes.copyOfRange(curPos, curPos + metaLen)
                                curPos += metaLen
                                arr
                            } else {
                                curPos = trackEnd
                                ByteArray(0)
                            }
                            allEvents.add(MidiEvent(currentTick, status, metaType = metaType, metaData = metaData))
                        } else {
                            // Sysex event
                            var sysexLen = 0
                            while (curPos < trackEnd) {
                                val b = midiBytes[curPos++].toInt() and 0xFF
                                sysexLen = (sysexLen shl 7) or (b and 0x7F)
                                if ((b and 0x80) == 0) break
                            }
                            curPos = (curPos + sysexLen).coerceAtMost(trackEnd)
                        }
                    }
                }
            }
        }

        allEvents.sort()

        val synth = WavetableSynthesizer(sampleRate = sampleRate)
        synth.soundFont = soundFont

        var tempoMicroseconds = 500000.0 // Default 120 BPM = 500,000 µs/beat
        var currentTick = 0L
        var currentSample = 0L
        val maxSamples = (maxDurationMs * sampleRate) / 1000L

        val pcmFrames = mutableListOf<FloatArray>()
        val chunkSize = 256
        val floatChunk = FloatArray(chunkSize * 2)

        var eventIdx = 0
        while (eventIdx < allEvents.size && currentSample < maxSamples) {
            val ev = allEvents[eventIdx]
            val deltaTicks = ev.timeTicks - currentTick
            if (deltaTicks > 0) {
                // Convert delta ticks to frames: seconds = deltaTicks * (tempoMicroseconds / 1000000.0) / ticksPerQuarter
                val deltaSec = (deltaTicks.toDouble() * tempoMicroseconds) / (1000000.0 * ticksPerQuarter)
                var framesToRender = (deltaSec * sampleRate).toInt().coerceAtLeast(0)

                while (framesToRender > 0 && currentSample < maxSamples) {
                    val count = framesToRender.coerceAtMost(chunkSize)
                    synth.renderFrames(floatChunk, 0, count)
                    val frameCopy = FloatArray(count * 2)
                    System.arraycopy(floatChunk, 0, frameCopy, 0, count * 2)
                    pcmFrames.add(frameCopy)
                    framesToRender -= count
                    currentSample += count
                }
                currentTick = ev.timeTicks
            }

            // Dispatch event
            val status = ev.status
            val ch = status and 0x0F
            when (status and 0xF0) {
                0x90 -> { // Note On
                    val vel = ev.data2
                    if (vel > 0) synth.noteOn(ch, ev.data1, vel) else synth.noteOff(ch, ev.data1)
                }
                0x80 -> synth.noteOff(ch, ev.data1) // Note Off
                0xC0 -> synth.programChange(ch, ev.data1) // Program Change
                0xE0 -> { // Pitch Bend
                    val rawVal = ((ev.data2 shl 7) or ev.data1) - 8192
                    val semitones = (rawVal / 8192.0f) * 2.0f // +/- 2 semitones
                    synth.pitchBend(ch, semitones)
                }
                0xF0 -> {
                    if (ev.status == 0xFF && ev.metaType == 0x51 && ev.metaData.size >= 3) {
                        // Set Tempo
                        val b0 = ev.metaData[0].toInt() and 0xFF
                        val b1 = ev.metaData[1].toInt() and 0xFF
                        val b2 = ev.metaData[2].toInt() and 0xFF
                        tempoMicroseconds = ((b0 shl 16) or (b1 shl 8) or b2).toDouble()
                    }
                }
            }
            eventIdx++
        }

        // Render release tail
        var tailFrames = sampleRate / 2
        while (tailFrames > 0 && currentSample < maxSamples) {
            val count = tailFrames.coerceAtMost(chunkSize)
            synth.renderFrames(floatChunk, 0, count)
            val frameCopy = FloatArray(count * 2)
            System.arraycopy(floatChunk, 0, frameCopy, 0, count * 2)
            pcmFrames.add(frameCopy)
            tailFrames -= count
            currentSample += count
        }

        // Flatten into 16-bit PCM ShortArray
        val totalShorts = pcmFrames.sumOf { it.size }
        val outShorts = ShortArray(totalShorts)
        var sIdx = 0
        for (chunk in pcmFrames) {
            for (f in chunk) {
                val clamped = if (f > 1.0f) 1.0f else if (f < -1.0f) -1.0f else f
                outShorts[sIdx++] = (clamped * 32767.0f).toInt().toShort()
            }
        }

        return AudioPcmData(sampleRate, 2, outShorts, 16)
    }
}
