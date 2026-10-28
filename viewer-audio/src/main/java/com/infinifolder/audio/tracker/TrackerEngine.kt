package com.infinifolder.audio.tracker

import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.synth.LoopMode
import com.infinifolder.audio.synth.SampleResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

data class TrackerSample(
    val name: String,
    val length: Int,
    val loopStart: Int,
    val loopEnd: Int,
    val loopMode: LoopMode,
    val volume: Float,
    val baseFreq: Double,
    val pcm: ShortArray,
    val relativeNote: Int = 0,
    val finetune: Int = 0
)

class TrackerChannel {
    var sample: TrackerSample? = null
    var resampler: SampleResampler? = null
    var period: Int = 0
    var targetFreq: Double = 0.0
    var volume: Float = 1.0f
    var panLeft: Float = 0.7f
    var panRight: Float = 0.7f

    fun triggerNote(s: TrackerSample, freq: Double, vol: Float = 1.0f) {
        sample = s
        targetFreq = freq
        volume = vol * s.volume
        resampler = SampleResampler(
            sampleData = s.pcm,
            sampleRate = 44100,
            loopStart = s.loopStart,
            loopEnd = s.loopEnd,
            loopMode = s.loopMode
        )
    }

    fun cut() {
        resampler = null
        targetFreq = 0.0
    }

    fun step(outStereo: FloatArray, offset: Int, masterGain: Float = 0.35f) {
        val r = resampler ?: return
        if (r.isFinished || targetFreq <= 0.0) return

        val step = (targetFreq / 44100.0).coerceIn(0.001, 10.0)
        val sampleVal = r.nextSample(step) * volume * masterGain
        outStereo[offset] += sampleVal * panLeft
        outStereo[offset + 1] += sampleVal * panRight
    }
}

object TrackerEngine {

    fun canDecode(bytes: ByteArray): Boolean {
        if (OpenMpt.canDecode(bytes)) return true
        if (bytes.size < 64) return false
        // MOD check (tag at 1080)
        if (bytes.size >= 1084) {
            val tag = String(bytes, 1080, 4, Charsets.US_ASCII)
            if (tag in setOf("M.K.", "M!K!", "4CHN", "6CHN", "8CHN", "FLT4", "FLT8", "OCTA", "16CN", "32CN")) return true
        }
        // S3M check (offset 44)
        if (bytes.size >= 48 && String(bytes, 44, 4, Charsets.US_ASCII) == "SCRM") return true
        // XM check (offset 0)
        if (bytes.size >= 17 && String(bytes, 0, 16, Charsets.US_ASCII) == "Extended Module:") return true
        // IT check (offset 0)
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "IMPM") return true

        return false
    }

    fun renderToPcm(bytes: ByteArray, maxDurationMs: Long = 180000L): AudioPcmData? {
        if (OpenMpt.isAvailable()) {
            val pcm = OpenMpt.renderToPcm(bytes, maxDurationMs)
            if (pcm != null) return pcm
        }

        if (!canDecode(bytes)) return null

        if (bytes.size >= 17 && String(bytes, 0, 16, Charsets.US_ASCII) == "Extended Module:") {
            return renderXm(bytes, maxDurationMs)
        }
        if (bytes.size >= 48 && String(bytes, 44, 4, Charsets.US_ASCII) == "SCRM") {
            return renderS3m(bytes, maxDurationMs)
        }
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "IMPM") {
            return renderIt(bytes, maxDurationMs)
        }

        if (bytes.size >= 1084) {
            val tag = String(bytes, 1080, 4, Charsets.US_ASCII)
            if (tag in setOf("M.K.", "M!K!", "4CHN", "6CHN", "8CHN", "FLT4", "FLT8", "OCTA", "16CN", "32CN")) {
                return renderMod(bytes, maxDurationMs)
            }
        }

        return null
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0L
        val b0 = bytes[offset].toLong() and 0xFFL
        val b1 = bytes[offset + 1].toLong() and 0xFFL
        val b2 = bytes[offset + 2].toLong() and 0xFFL
        val b3 = bytes[offset + 3].toLong() and 0xFFL
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Short {
        if (offset + 1 >= bytes.size) return 0
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return ((b1 shl 8) or b0).toShort()
    }

    /**
     * Genuine Amiga ProTracker / Soundtracker module playback.
     */
    private fun renderMod(bytes: ByteArray, maxDurationMs: Long): AudioPcmData? {
        if (bytes.size < 1084) return null
        val tag = String(bytes, 1080, 4, Charsets.US_ASCII)
        val numChannels = when (tag) {
            "6CHN" -> 6
            "8CHN", "OCTA", "FLT8" -> 8
            "16CN" -> 16
            "32CN" -> 32
            else -> 4
        }

        val numSamples = 31
        val sampleHeaders = mutableListOf<TrackerSample>()

        // 1. Parse sample headers (offset 20..950)
        for (i in 0 until numSamples) {
            val hPos = 20 + i * 30
            val name = String(bytes, hPos, 22, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
            val lenWords = ByteBuffer.wrap(bytes, hPos + 22, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            val length = lenWords * 2
            val fineTune = bytes[hPos + 24].toInt() and 0x0F
            val vol = (bytes[hPos + 25].toInt() and 0xFF).coerceIn(0, 64) / 64f
            val loopStartWords = ByteBuffer.wrap(bytes, hPos + 26, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            val loopLenWords = ByteBuffer.wrap(bytes, hPos + 28, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            val loopStart = loopStartWords * 2
            val loopLen = loopLenWords * 2
            val loopEnd = (loopStart + loopLen).coerceAtMost(length)
            val loopMode = if (loopLen > 2 && loopEnd > loopStart) LoopMode.FORWARD else LoopMode.NONE

            sampleHeaders.add(
                TrackerSample(
                    name = name,
                    length = length,
                    loopStart = loopStart.coerceIn(0, length),
                    loopEnd = loopEnd,
                    loopMode = loopMode,
                    volume = vol,
                    baseFreq = 8287.0, // Standard C-2 Amiga frequency (period 428)
                    pcm = ShortArray(0),
                    finetune = fineTune
                )
            )
        }

        val songLength = bytes[950].toInt() and 0xFF
        val patternOrders = IntArray(128) { bytes[952 + it].toInt() and 0xFF }
        val maxPatternInOrders = patternOrders.take(songLength.coerceIn(1, 128)).maxOrNull() ?: 0
        val totalSampleSize = sampleHeaders.sumOf { it.length }
        val patternSize = 64 * numChannels * 4
        val patternCountByRemaining = if (bytes.size > 1084 + totalSampleSize) {
            (bytes.size - 1084 - totalSampleSize) / patternSize
        } else {
            maxPatternInOrders + 1
        }
        val patternCount = if (patternCountByRemaining in (maxPatternInOrders + 1)..128) {
            patternCountByRemaining
        } else {
            maxPatternInOrders + 1
        }

        val sampleDataOffset = 1084 + patternCount * patternSize

        // 2. Load sample audio data (signed 8-bit PCM)
        val samples = mutableListOf<TrackerSample>()
        var curSmplPos = sampleDataOffset
        for (sh in sampleHeaders) {
            val len = sh.length.coerceAtMost((bytes.size - curSmplPos).coerceAtLeast(0))
            val pcm = ShortArray(len)
            for (j in 0 until len) {
                if (curSmplPos < bytes.size) {
                    pcm[j] = (bytes[curSmplPos++].toInt() shl 8).toShort()
                }
            }
            samples.add(sh.copy(pcm = pcm))
        }

        // 3. Render patterns
        val sampleRate = 44100
        val channels = Array(numChannels) { i ->
            TrackerChannel().apply {
                if (i % 4 == 0 || i % 4 == 3) {
                    panLeft = 0.85f; panRight = 0.15f
                } else {
                    panLeft = 0.15f; panRight = 0.85f
                }
            }
        }

        val totalFrames = ((maxDurationMs * sampleRate) / 1000L).toInt()
        val outStereo = ShortArray(totalFrames * 2)
        var frameIdx = 0

        var speed = 6
        var tempo = 125

        var orderIdx = 0
        val floatBlock = FloatArray(512 * 2)

        while (orderIdx < songLength && frameIdx < totalFrames) {
            val patIdx = patternOrders[orderIdx]
            val patOffset = 1084 + patIdx * patternSize

            for (row in 0 until 64) {
                if (frameIdx >= totalFrames) break

                for (ch in 0 until numChannels) {
                    val rowPos = patOffset + (row * numChannels + ch) * 4
                    if (rowPos + 4 <= bytes.size) {
                        val b0 = bytes[rowPos].toInt() and 0xFF
                        val b1 = bytes[rowPos + 1].toInt() and 0xFF
                        val b2 = bytes[rowPos + 2].toInt() and 0xFF
                        val b3 = bytes[rowPos + 3].toInt() and 0xFF

                        val smplIdx = ((b0 and 0xF0) or (b2 shr 4)) - 1
                        val period = ((b0 and 0x0F) shl 8) or b1
                        val effect = b2 and 0x0F
                        val effectParam = b3

                        if (smplIdx in samples.indices) {
                            channels[ch].sample = samples[smplIdx]
                        }

                        if (effect == 0x0F && effectParam > 0) {
                            if (effectParam <= 32) speed = effectParam else tempo = effectParam
                        } else if (effect == 0x0C) {
                            channels[ch].volume = effectParam.coerceIn(0, 64) / 64f
                        }

                        if (period > 0) {
                            val smpl = channels[ch].sample
                            if (smpl != null) {
                                val freq = 3546895.0 / period.toDouble()
                                channels[ch].triggerNote(smpl, freq)
                            }
                        }
                    }
                }

                val tickDurationSec = 2.5 / tempo.coerceAtLeast(32)
                val framesPerRow = (tickDurationSec * speed * sampleRate).toInt().coerceAtLeast(1)
                val masterGain = (0.7f / (numChannels / 4.0f)).toFloat().coerceIn(0.2f, 0.7f)
                var remainingFrames = framesPerRow
                while (remainingFrames > 0 && frameIdx < totalFrames) {
                    val count = remainingFrames.coerceAtMost(512)
                    for (i in 0 until count * 2) floatBlock[i] = 0f
                    for (f in 0 until count) {
                        val offset = f * 2
                        for (ch in channels) ch.step(floatBlock, offset, masterGain)
                    }

                    for (i in 0 until count * 2) {
                        if (frameIdx * 2 + i < outStereo.size) {
                            outStereo[frameIdx * 2 + i] = (floatBlock[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    frameIdx += count
                    remainingFrames -= count
                }
            }
            orderIdx++
        }

        return AudioPcmData(sampleRate, 2, outStereo.copyOf(frameIdx * 2), 16)
    }

    /**
     * Genuine Scream Tracker 3 (.s3m) module playback.
     */
    private fun renderS3m(bytes: ByteArray, maxDurationMs: Long): AudioPcmData? {
        if (bytes.size < 96) return null
        val ordNum = readU16LE(bytes, 32)
        val insNum = readU16LE(bytes, 34)
        val patNum = readU16LE(bytes, 36)
        val ffi = readU16LE(bytes, 42) // 1 = signed, 2 = unsigned
        var speed = (bytes[49].toInt() and 0xFF).coerceAtLeast(1)
        var tempo = (bytes[50].toInt() and 0xFF).coerceAtLeast(32)

        // Read channel settings (pan)
        val channelPans = Array(32) { Pair(0.7f, 0.7f) }
        for (i in 0 until 32) {
            val chSetting = bytes[64 + i].toInt() and 0xFF
            if (chSetting < 16) {
                channelPans[i] = Pair(0.85f, 0.15f)
            } else if (chSetting < 32) {
                channelPans[i] = Pair(0.15f, 0.85f)
            } else {
                channelPans[i] = Pair(0.5f, 0.5f)
            }
        }

        // Order table
        var curOffset = 96
        val orders = IntArray(ordNum) { bytes[curOffset + it].toInt() and 0xFF }
        curOffset += ordNum

        // Parapointers
        val insParas = IntArray(insNum) {
            readU16LE(bytes, curOffset + it * 2)
        }
        curOffset += insNum * 2
        val patParas = IntArray(patNum) {
            readU16LE(bytes, curOffset + it * 2)
        }

        // Load Instruments / Samples
        val samples = Array<TrackerSample?>(insNum) { i ->
            val para = insParas[i]
            val insOffset = para * 16
            if (insOffset + 80 <= bytes.size && bytes[insOffset].toInt() == 1) { // 1 = PCM sample
                val smpPara = ((bytes[insOffset + 13].toInt() and 0xFF) shl 16) or readU16LE(bytes, insOffset + 14)
                val smpOffset = smpPara * 16
                val len = readU32LE(bytes, insOffset + 16).toInt().coerceAtLeast(0)
                val loopStart = readU32LE(bytes, insOffset + 20).toInt().coerceAtLeast(0)
                val loopEnd = readU32LE(bytes, insOffset + 24).toInt().coerceAtLeast(0)
                val vol = (bytes[insOffset + 28].toInt() and 0xFF).coerceIn(0, 64) / 64f
                val flags = bytes[insOffset + 31].toInt() and 0xFF
                val isLooped = (flags and 1) != 0 && loopEnd > loopStart
                val is16Bit = (flags and 4) != 0
                val c5speed = readU32LE(bytes, insOffset + 32).toDouble().let { if (it <= 0) 8363.0 else it }
                val smpName = String(bytes, insOffset + 48, 28, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')

                val pcmLen = if (is16Bit) len / 2 else len
                val pcm = ShortArray(pcmLen)
                if (is16Bit) {
                    for (j in 0 until pcmLen) {
                        val pos = smpOffset + j * 2
                        if (pos + 1 < bytes.size) {
                            val uVal = readU16LE(bytes, pos)
                            pcm[j] = if (ffi == 2) (uVal - 32768).toShort() else uVal.toShort()
                        }
                    }
                } else {
                    for (j in 0 until pcmLen) {
                        val pos = smpOffset + j
                        if (pos < bytes.size) {
                            val b = bytes[pos].toInt() and 0xFF
                            pcm[j] = if (ffi == 2) (((b - 128) shl 8).toShort()) else ((bytes[pos].toInt() shl 8).toShort())
                        }
                    }
                }

                TrackerSample(
                    name = smpName,
                    length = pcmLen,
                    loopStart = loopStart.coerceIn(0, pcmLen),
                    loopEnd = loopEnd.coerceIn(0, pcmLen),
                    loopMode = if (isLooped) LoopMode.FORWARD else LoopMode.NONE,
                    volume = vol,
                    baseFreq = c5speed,
                    pcm = pcm
                )
            } else null
        }

        // Playback setup
        val sampleRate = 44100
        val totalFrames = ((maxDurationMs * sampleRate) / 1000L).toInt()
        val outStereo = ShortArray(totalFrames * 2)
        var frameIdx = 0

        val channels = Array(32) { i ->
            TrackerChannel().apply {
                panLeft = channelPans[i].first
                panRight = channelPans[i].second
            }
        }

        val floatBlock = FloatArray(512 * 2)

        for (ord in orders) {
            if (ord == 255 || frameIdx >= totalFrames) break
            if (ord >= patNum) continue
            val patPara = patParas[ord]
            if (patPara == 0) continue
            val patOffset = patPara * 16
            if (patOffset + 2 > bytes.size) continue
            val patLength = readU16LE(bytes, patOffset)
            val maxPatPos = (patOffset + patLength).coerceAtMost(bytes.size)
            var curPos = patOffset + 2

            for (row in 0 until 64) {
                if (frameIdx >= totalFrames) break

                while (curPos < maxPatPos) {
                    val b = bytes[curPos++].toInt() and 0xFF
                    if (b == 0) break // End of row
                    val ch = b and 31
                    var note = 255
                    var inst = 0
                    var vol = -1
                    var cmd = 0
                    var cmdParam = 0

                    if ((b and 32) != 0) {
                        if (curPos < maxPatPos) note = bytes[curPos++].toInt() and 0xFF
                        if (curPos < maxPatPos) inst = bytes[curPos++].toInt() and 0xFF
                    }
                    if ((b and 64) != 0) {
                        if (curPos < maxPatPos) vol = bytes[curPos++].toInt() and 0xFF
                    }
                    if ((b and 128) != 0) {
                        if (curPos < maxPatPos) cmd = bytes[curPos++].toInt() and 0xFF
                        if (curPos < maxPatPos) cmdParam = bytes[curPos++].toInt() and 0xFF
                    }

                    if (ch < 32) {
                        val channel = channels[ch]
                        if (inst in 1..samples.size) {
                            channel.sample = samples[inst - 1]
                        }
                        if (vol in 0..64) {
                            channel.volume = vol / 64f
                        }
                        if (note == 254) { // Key off
                            channel.cut()
                        } else if (note < 254) {
                            val octave = (note shr 4) and 0x0F
                            val noteVal = note and 0x0F
                            val noteIdx = octave * 12 + noteVal
                            val smpl = channel.sample
                            if (smpl != null) {
                                val freq = smpl.baseFreq * 2.0.pow((noteIdx - 48) / 12.0)
                                channel.triggerNote(smpl, freq)
                            }
                        }

                        if (cmd == 1 && cmdParam > 0) { // Set Speed 'A'
                            speed = cmdParam
                        } else if (cmd == 20 && cmdParam >= 32) { // Set Tempo 'T'
                            tempo = cmdParam
                        }
                    }
                }

                val tickDurationSec = 2.5 / tempo.coerceAtLeast(32)
                val framesPerRow = (tickDurationSec * speed * sampleRate).toInt().coerceAtLeast(1)
                val masterGain = 0.35f
                var remainingFrames = framesPerRow

                while (remainingFrames > 0 && frameIdx < totalFrames) {
                    val count = remainingFrames.coerceAtMost(512)
                    for (i in 0 until count * 2) floatBlock[i] = 0f
                    for (f in 0 until count) {
                        val offset = f * 2
                        for (ch in channels) ch.step(floatBlock, offset, masterGain)
                    }

                    for (i in 0 until count * 2) {
                        if (frameIdx * 2 + i < outStereo.size) {
                            outStereo[frameIdx * 2 + i] = (floatBlock[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    frameIdx += count
                    remainingFrames -= count
                }
            }
        }

        return AudioPcmData(sampleRate, 2, outStereo.copyOf(frameIdx * 2), 16)
    }

    /**
     * Genuine FastTracker II (.xm) module playback.
     */
    private fun renderXm(bytes: ByteArray, maxDurationMs: Long): AudioPcmData? {
        if (bytes.size < 80) return null
        val headerSize = readU32LE(bytes, 60).toInt()
        val songLength = readU16LE(bytes, 64)
        val numChannels = readU16LE(bytes, 68).coerceIn(1, 32)
        val numPatterns = readU16LE(bytes, 70)
        val numInstruments = readU16LE(bytes, 72)
        var speed = readU16LE(bytes, 76).coerceAtLeast(1)
        var tempo = readU16LE(bytes, 78).coerceAtLeast(32)

        val orders = IntArray(songLength) { bytes[80 + it].toInt() and 0xFF }

        // Patterns begin at 60 + headerSize
        var pos = 60 + headerSize
        class XmPattern(val numRows: Int, val rows: Array<Array<IntArray>>)
        val patterns = mutableListOf<XmPattern>()

        for (p in 0 until numPatterns) {
            if (pos + 9 > bytes.size) break
            val patHdrLen = readU32LE(bytes, pos).toInt()
            val numRows = readU16LE(bytes, pos + 5).let { if (it <= 0) 64 else it }
            val packedSize = readU16LE(bytes, pos + 7)
            pos += patHdrLen

            val patRows = Array(numRows) { Array(numChannels) { IntArray(5) } }
            val endPat = (pos + packedSize).coerceAtMost(bytes.size)

            if (packedSize > 0) {
                for (r in 0 until numRows) {
                    for (ch in 0 until numChannels) {
                        if (pos >= endPat) break
                        val b = bytes[pos++].toInt() and 0xFF
                        if ((b and 0x80) != 0) {
                            if ((b and 0x01) != 0 && pos < endPat) patRows[r][ch][0] = bytes[pos++].toInt() and 0xFF
                            if ((b and 0x02) != 0 && pos < endPat) patRows[r][ch][1] = bytes[pos++].toInt() and 0xFF
                            if ((b and 0x04) != 0 && pos < endPat) patRows[r][ch][2] = bytes[pos++].toInt() and 0xFF
                            if ((b and 0x08) != 0 && pos < endPat) patRows[r][ch][3] = bytes[pos++].toInt() and 0xFF
                            if ((b and 0x10) != 0 && pos < endPat) patRows[r][ch][4] = bytes[pos++].toInt() and 0xFF
                        } else {
                            patRows[r][ch][0] = b
                            if (pos < endPat) patRows[r][ch][1] = bytes[pos++].toInt() and 0xFF
                            if (pos < endPat) patRows[r][ch][2] = bytes[pos++].toInt() and 0xFF
                            if (pos < endPat) patRows[r][ch][3] = bytes[pos++].toInt() and 0xFF
                            if (pos < endPat) patRows[r][ch][4] = bytes[pos++].toInt() and 0xFF
                        }
                    }
                }
            }
            pos = endPat
            patterns.add(XmPattern(numRows, patRows))
        }

        // Instruments follow patterns
        class XmInstrument(val samples: List<TrackerSample>)
        val instruments = mutableListOf<XmInstrument>()

        for (i in 0 until numInstruments) {
            if (pos + 29 > bytes.size) break
            val insHdrSize = readU32LE(bytes, pos).toInt().coerceAtLeast(29)
            val numSamples = if (insHdrSize > 29 && pos + 28 < bytes.size) readU16LE(bytes, pos + 27) else 0

            if (numSamples <= 0) {
                pos += insHdrSize
                instruments.add(XmInstrument(emptyList()))
            } else {
                val smpHdrSize = readU32LE(bytes, pos + 29).toInt().coerceAtLeast(40)
                pos += insHdrSize

                class RawSmpHdr(
                    val len: Int, val loopStart: Int, val loopLen: Int, val vol: Float,
                    val fineTune: Int, val is16Bit: Boolean, val loopMode: LoopMode,
                    val relNote: Int, val name: String
                )
                val smpHeaders = mutableListOf<RawSmpHdr>()
                for (s in 0 until numSamples) {
                    if (pos + smpHdrSize > bytes.size) break
                    val len = readU32LE(bytes, pos).toInt()
                    val lStart = readU32LE(bytes, pos + 4).toInt()
                    val lLen = readU32LE(bytes, pos + 8).toInt()
                    val vol = (bytes[pos + 12].toInt() and 0xFF).coerceIn(0, 64) / 64f
                    val finetune = bytes[pos + 13].toInt()
                    val type = bytes[pos + 14].toInt() and 0xFF
                    val is16Bit = (type and 16) != 0
                    val loopMode = when (type and 3) {
                        1 -> LoopMode.FORWARD
                        2 -> LoopMode.PING_PONG
                        else -> LoopMode.NONE
                    }
                    val relNote = bytes[pos + 16].toInt()
                    val sName = String(bytes, pos + 18, 22, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
                    smpHeaders.add(RawSmpHdr(len, lStart, lLen, vol, finetune, is16Bit, loopMode, relNote, sName))
                    pos += smpHdrSize
                }

                // Delta-encoded sample data
                val smpList = mutableListOf<TrackerSample>()
                for (sh in smpHeaders) {
                    val pcmLen = if (sh.is16Bit) sh.len / 2 else sh.len
                    val pcm = ShortArray(pcmLen)
                    if (sh.is16Bit) {
                        var old = 0
                        for (j in 0 until pcmLen) {
                            if (pos + 1 < bytes.size) {
                                val delta = readShortLE(bytes, pos).toInt()
                                old = (old + delta).toShort().toInt()
                                pcm[j] = old.toShort()
                                pos += 2
                            }
                        }
                    } else {
                        var old = 0
                        for (j in 0 until pcmLen) {
                            if (pos < bytes.size) {
                                val delta = bytes[pos++].toInt()
                                old = (old + delta).toByte().toInt()
                                pcm[j] = (old shl 8).toShort()
                            }
                        }
                    }

                    smpList.add(
                        TrackerSample(
                            name = sh.name,
                            length = pcmLen,
                            loopStart = sh.loopStart,
                            loopEnd = (sh.loopStart + sh.loopLen).coerceAtMost(pcmLen),
                            loopMode = sh.loopMode,
                            volume = sh.vol,
                            baseFreq = 8363.0,
                            pcm = pcm,
                            relativeNote = sh.relNote,
                            finetune = sh.fineTune
                        )
                    )
                }
                instruments.add(XmInstrument(smpList))
            }
        }

        // Playback setup
        val sampleRate = 44100
        val totalFrames = ((maxDurationMs * sampleRate) / 1000L).toInt()
        val outStereo = ShortArray(totalFrames * 2)
        var frameIdx = 0

        val channels = Array(numChannels) { i ->
            TrackerChannel().apply {
                if (i % 4 == 0 || i % 4 == 3) {
                    panLeft = 0.85f; panRight = 0.15f
                } else {
                    panLeft = 0.15f; panRight = 0.85f
                }
            }
        }

        val floatBlock = FloatArray(512 * 2)

        for (ord in orders) {
            if (ord >= patterns.size || frameIdx >= totalFrames) continue
            val pat = patterns[ord]

            for (row in 0 until pat.numRows) {
                if (frameIdx >= totalFrames) break

                for (ch in 0 until numChannels) {
                    val ev = pat.rows[row][ch]
                    val note = ev[0]
                    val inst = ev[1]
                    val vol = ev[2]
                    val eff = ev[3]
                    val effParam = ev[4]
                    val channel = channels[ch]

                    if (inst in 1..instruments.size) {
                        val ins = instruments[inst - 1]
                        if (ins.samples.isNotEmpty()) {
                            channel.sample = ins.samples[0]
                        }
                    }

                    if (vol in 0x10..0x50) {
                        channel.volume = (vol - 0x10) / 64f
                    }

                    if (note == 97) {
                        channel.cut()
                    } else if (note in 1..96) {
                        val smpl = channel.sample
                        if (smpl != null) {
                            val semitones = (note - 49) + smpl.relativeNote + (smpl.finetune / 128.0)
                            val freq = 8363.0 * 2.0.pow(semitones / 12.0)
                            channel.triggerNote(smpl, freq)
                        }
                    }

                    if (eff == 0x0F && effParam > 0) {
                        if (effParam <= 32) speed = effParam else tempo = effParam
                    } else if (eff == 0x0C) {
                        channel.volume = effParam.coerceIn(0, 64) / 64f
                    }
                }

                val tickDurationSec = 2.5 / tempo.coerceAtLeast(32)
                val framesPerRow = (tickDurationSec * speed * sampleRate).toInt().coerceAtLeast(1)
                val masterGain = (0.7f / (numChannels / 4.0f).coerceAtLeast(1.0f)).coerceIn(0.2f, 0.7f)
                var remainingFrames = framesPerRow

                while (remainingFrames > 0 && frameIdx < totalFrames) {
                    val count = remainingFrames.coerceAtMost(512)
                    for (i in 0 until count * 2) floatBlock[i] = 0f
                    for (f in 0 until count) {
                        val offset = f * 2
                        for (ch in channels) ch.step(floatBlock, offset, masterGain)
                    }

                    for (i in 0 until count * 2) {
                        if (frameIdx * 2 + i < outStereo.size) {
                            outStereo[frameIdx * 2 + i] = (floatBlock[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    frameIdx += count
                    remainingFrames -= count
                }
            }
        }

        return AudioPcmData(sampleRate, 2, outStereo.copyOf(frameIdx * 2), 16)
    }

    /**
     * Genuine Impulse Tracker (.it) module playback.
     */
    private fun renderIt(bytes: ByteArray, maxDurationMs: Long): AudioPcmData? {
        if (bytes.size < 192) return null
        val ordNum = readU16LE(bytes, 32)
        val insNum = readU16LE(bytes, 34)
        val smpNum = readU16LE(bytes, 36)
        val patNum = readU16LE(bytes, 38)
        var speed = (bytes[50].toInt() and 0xFF).coerceAtLeast(1)
        var tempo = (bytes[51].toInt() and 0xFF).coerceAtLeast(32)

        val orders = IntArray(ordNum) { bytes[192 + it].toInt() and 0xFF }
        var ptrPos = 192 + ordNum
        val insParas = IntArray(insNum) {
            val p = readU32LE(bytes, ptrPos).toInt()
            ptrPos += 4
            p
        }
        val smpParas = IntArray(smpNum) {
            val p = readU32LE(bytes, ptrPos).toInt()
            ptrPos += 4
            p
        }
        val patParas = IntArray(patNum) {
            val p = readU32LE(bytes, ptrPos).toInt()
            ptrPos += 4
            p
        }

        // Parse samples
        val samples = Array<TrackerSample?>(smpNum) { i ->
            val smpOffset = smpParas[i]
            if (smpOffset + 80 <= bytes.size && String(bytes, smpOffset, 4, Charsets.US_ASCII) == "IMPS") {
                val flags = bytes[smpOffset + 18].toInt() and 0xFF
                val is16Bit = (flags and 2) != 0
                val isLooped = (flags and 16) != 0
                val loopMode = if (isLooped) LoopMode.FORWARD else LoopMode.NONE
                val vol = (bytes[smpOffset + 19].toInt() and 0xFF).coerceIn(0, 64) / 64f
                val cvt = bytes[smpOffset + 46].toInt() and 0xFF
                val isSigned = (cvt and 1) != 0
                val len = readU32LE(bytes, smpOffset + 48).toInt()
                val loopStart = readU32LE(bytes, smpOffset + 52).toInt()
                val loopEnd = readU32LE(bytes, smpOffset + 56).toInt()
                val c5speed = readU32LE(bytes, smpOffset + 60).toDouble().let { if (it <= 0) 8363.0 else it }
                val datOffset = readU32LE(bytes, smpOffset + 72).toInt()

                val pcmLen = if (is16Bit) len / 2 else len
                val pcm = ShortArray(pcmLen)
                if (is16Bit) {
                    for (j in 0 until pcmLen) {
                        val pos = datOffset + j * 2
                        if (pos + 1 < bytes.size) {
                            val uVal = readU16LE(bytes, pos)
                            pcm[j] = if (!isSigned) (uVal - 32768).toShort() else uVal.toShort()
                        }
                    }
                } else {
                    for (j in 0 until pcmLen) {
                        val pos = datOffset + j
                        if (pos < bytes.size) {
                            val b = bytes[pos].toInt() and 0xFF
                            pcm[j] = if (!isSigned) (((b - 128) shl 8).toShort()) else ((bytes[pos].toInt() shl 8).toShort())
                        }
                    }
                }

                TrackerSample(
                    name = String(bytes, smpOffset + 20, 26, Charsets.ISO_8859_1).trimEnd('\u0000', ' '),
                    length = pcmLen,
                    loopStart = loopStart.coerceIn(0, pcmLen),
                    loopEnd = loopEnd.coerceIn(0, pcmLen),
                    loopMode = loopMode,
                    volume = vol,
                    baseFreq = c5speed,
                    pcm = pcm
                )
            } else null
        }

        // Playback setup
        val sampleRate = 44100
        val totalFrames = ((maxDurationMs * sampleRate) / 1000L).toInt()
        val outStereo = ShortArray(totalFrames * 2)
        var frameIdx = 0

        val channels = Array(64) { i ->
            TrackerChannel().apply {
                if (i % 4 == 0 || i % 4 == 3) {
                    panLeft = 0.85f; panRight = 0.15f
                } else {
                    panLeft = 0.15f; panRight = 0.85f
                }
            }
        }

        val floatBlock = FloatArray(512 * 2)

        for (ord in orders) {
            if (ord == 255 || frameIdx >= totalFrames) break
            if (ord >= patNum) continue
            val patOffset = patParas[ord]
            if (patOffset == 0 || patOffset + 8 > bytes.size) continue

            val patLength = readU16LE(bytes, patOffset)
            val numRows = readU16LE(bytes, patOffset + 2).let { if (it <= 0) 64 else it }
            var curPos = patOffset + 8
            val endPat = (patOffset + 8 + patLength).coerceAtMost(bytes.size)

            val channelMasks = IntArray(64)
            val lastNotes = IntArray(64) { 255 }
            val lastInsts = IntArray(64)
            val lastVols = IntArray(64) { -1 }
            val lastCmds = IntArray(64)
            val lastCmdVals = IntArray(64)

            for (row in 0 until numRows) {
                if (frameIdx >= totalFrames) break

                while (curPos < endPat) {
                    val b = bytes[curPos++].toInt() and 0xFF
                    if (b == 0) break // End of row
                    val ch = (b and 127) - 1
                    var mask = channelMasks.getOrElse(ch) { 0 }
                    if ((b and 128) != 0 && curPos < endPat) {
                        mask = bytes[curPos++].toInt() and 0xFF
                        if (ch in 0 until 64) channelMasks[ch] = mask
                    }

                    var note = 255
                    var inst = 0
                    var vol = -1
                    var cmd = 0
                    var cmdVal = 0

                    if ((mask and 1) != 0 && curPos < endPat) {
                        note = bytes[curPos++].toInt() and 0xFF
                        if (ch in 0 until 64) lastNotes[ch] = note
                    }
                    if ((mask and 2) != 0 && curPos < endPat) {
                        inst = bytes[curPos++].toInt() and 0xFF
                        if (ch in 0 until 64) lastInsts[ch] = inst
                    }
                    if ((mask and 4) != 0 && curPos < endPat) {
                        vol = bytes[curPos++].toInt() and 0xFF
                        if (ch in 0 until 64) lastVols[ch] = vol
                    }
                    if ((mask and 8) != 0 && curPos + 1 < endPat) {
                        cmd = bytes[curPos++].toInt() and 0xFF
                        cmdVal = bytes[curPos++].toInt() and 0xFF
                        if (ch in 0 until 64) {
                            lastCmds[ch] = cmd
                            lastCmdVals[ch] = cmdVal
                        }
                    }
                    if ((mask and 16) != 0 && ch in 0 until 64) note = lastNotes[ch]
                    if ((mask and 32) != 0 && ch in 0 until 64) inst = lastInsts[ch]
                    if ((mask and 64) != 0 && ch in 0 until 64) vol = lastVols[ch]
                    if ((mask and 128) != 0 && ch in 0 until 64) {
                        cmd = lastCmds[ch]
                        cmdVal = lastCmdVals[ch]
                    }

                    if (ch in 0 until 64) {
                        val channel = channels[ch]
                        if (inst in 1..samples.size) {
                            channel.sample = samples[inst - 1]
                        }
                        if (vol in 0..64) {
                            channel.volume = vol / 64f
                        }
                        if (note == 255 || note == 254) {
                            channel.cut()
                        } else if (note < 120) {
                            val smpl = channel.sample
                            if (smpl != null) {
                                val freq = smpl.baseFreq * 2.0.pow((note - 60) / 12.0)
                                channel.triggerNote(smpl, freq)
                            }
                        }
                        if (cmd == 1 && cmdVal > 0) speed = cmdVal
                        if (cmd == 20 && cmdVal >= 32) tempo = cmdVal
                    }
                }

                val tickDurationSec = 2.5 / tempo.coerceAtLeast(32)
                val framesPerRow = (tickDurationSec * speed * sampleRate).toInt().coerceAtLeast(1)
                val masterGain = 0.35f
                var remainingFrames = framesPerRow

                while (remainingFrames > 0 && frameIdx < totalFrames) {
                    val count = remainingFrames.coerceAtMost(512)
                    for (i in 0 until count * 2) floatBlock[i] = 0f
                    for (f in 0 until count) {
                        val offset = f * 2
                        for (ch in channels) ch.step(floatBlock, offset, masterGain)
                    }

                    for (i in 0 until count * 2) {
                        if (frameIdx * 2 + i < outStereo.size) {
                            outStereo[frameIdx * 2 + i] = (floatBlock[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    frameIdx += count
                    remainingFrames -= count
                }
            }
        }

        return AudioPcmData(sampleRate, 2, outStereo.copyOf(frameIdx * 2), 16)
    }
}
