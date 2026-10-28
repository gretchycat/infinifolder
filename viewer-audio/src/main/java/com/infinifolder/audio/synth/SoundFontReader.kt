package com.infinifolder.audio.synth

import com.infinifolder.audio.model.AudioPcmData
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SoundFontSample(
    val name: String,
    val sampleRate: Int,
    val originalPitch: Int = 60, // Middle C (MIDI 60)
    val pitchCorrection: Int = 0, // In cents (-50..50)
    val loopStart: Int = 0,
    val loopEnd: Int = 0,
    val loopMode: LoopMode = LoopMode.NONE,
    val pcmSamples: ShortArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoundFontSample) return false
        return name == other.name && sampleRate == other.sampleRate && originalPitch == other.originalPitch
    }

    override fun hashCode(): Int = name.hashCode() * 31 + originalPitch
}

data class SoundFontInstrument(
    val name: String,
    val program: Int = 0,
    val bank: Int = 0,
    val samples: List<SoundFontSample>
)

data class SoundFontBank(
    val name: String,
    val instruments: List<SoundFontInstrument>
) {
    fun findSampleForNote(program: Int, midiNote: Int): SoundFontSample? {
        val inst = instruments.firstOrNull { it.program == program } ?: instruments.firstOrNull() ?: return null
        if (inst.samples.isEmpty()) return null
        // Find closest sample by pitch
        return inst.samples.minByOrNull { Math.abs(it.originalPitch - midiNote) }
    }
}

object SoundFontReader {

    fun isSoundFont(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val form = String(bytes, 8, 4, Charsets.US_ASCII)
        return riff == "RIFF" && form == "sfbk"
    }

    fun isSfz(text: String): Boolean {
        return text.contains("<region>") || text.contains("<group>") || text.contains("<control>")
    }

    fun isGusPatch(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val sig = String(bytes, 0, 10, Charsets.US_ASCII)
        return sig.startsWith("GF1PATCH1")
    }

    fun loadSf2(bytes: ByteArray): SoundFontBank? {
        if (!isSoundFont(bytes)) return null

        var bankName = "SoundFont"
        var smplOffset = -1
        var smplLength = 0
        var shdrOffset = -1
        var shdrLength = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val listDataPos = pos + 8
            val listEnd = (pos + 8 + chunkSize).toInt().coerceAtMost(bytes.size)
            pos = listEnd

            if (chunkId == "LIST" && listDataPos + 4 <= bytes.size) {
                val listType = String(bytes, listDataPos, 4, Charsets.US_ASCII)
                var subPos = listDataPos + 4
                while (subPos + 8 <= listEnd) {
                    val subId = String(bytes, subPos, 4, Charsets.US_ASCII)
                    val subSize = ByteBuffer.wrap(bytes, subPos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val subDataPos = subPos + 8
                    val subDataEnd = (subDataPos + subSize).toInt().coerceAtMost(listEnd)

                    when (subId) {
                        "INAM" -> {
                            val len = subSize.toInt().coerceAtMost(subDataEnd - subDataPos)
                            if (len > 0) {
                                bankName = String(bytes, subDataPos, len, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
                            }
                        }
                        "smpl" -> {
                            smplOffset = subDataPos
                            smplLength = (subDataEnd - subDataPos).coerceAtLeast(0)
                        }
                        "shdr" -> {
                            shdrOffset = subDataPos
                            shdrLength = (subDataEnd - subDataPos).coerceAtLeast(0)
                        }
                    }
                    subPos = subDataEnd
                }
            }
        }

        if (smplOffset < 0 || smplLength <= 0) return null

        // Decode 16-bit linear PCM sample pool
        val totalSmplCount = smplLength / 2
        val smplPool = ShortArray(totalSmplCount)
        val sbb = ByteBuffer.wrap(bytes, smplOffset, smplLength).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSmplCount) {
            smplPool[i] = sbb.short
        }

        val parsedSamples = mutableListOf<SoundFontSample>()
        if (shdrOffset > 0 && shdrLength >= 46) {
            val recordCount = shdrLength / 46
            val hbb = ByteBuffer.wrap(bytes, shdrOffset, shdrLength).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until recordCount) {
                val nameBytes = ByteArray(20)
                hbb.get(nameBytes)
                val sampleName = String(nameBytes, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
                val start = hbb.int.coerceIn(0, totalSmplCount)
                val end = hbb.int.coerceIn(start, totalSmplCount)
                val startLoop = hbb.int.coerceIn(start, end)
                val endLoop = hbb.int.coerceIn(startLoop, end)
                val sr = hbb.int
                val origPitch = hbb.get().toInt() and 0xFF
                val pitchCorr = hbb.get().toInt()
                val sampleLink = hbb.short.toInt() and 0xFFFF
                val sampleType = hbb.short.toInt() and 0xFFFF

                val sampleLen = end - start
                if (sampleLen > 0 && sampleName.isNotBlank() && !sampleName.startsWith("EOS")) {
                    val subSamples = smplPool.copyOfRange(start, end)
                    val loopMode = if (endLoop > startLoop) LoopMode.FORWARD else LoopMode.NONE
                    parsedSamples.add(
                        SoundFontSample(
                            name = sampleName,
                            sampleRate = if (sr > 0) sr else 44100,
                            originalPitch = if (origPitch in 0..127) origPitch else 60,
                            pitchCorrection = pitchCorr,
                            loopStart = (startLoop - start).coerceAtLeast(0),
                            loopEnd = (endLoop - start).coerceAtMost(subSamples.size),
                            loopMode = loopMode,
                            pcmSamples = subSamples
                        )
                    )
                }
            }
        }

        // If no sample headers parsed or single sample file, treat entire sample pool as sample
        if (parsedSamples.isEmpty() && smplPool.isNotEmpty()) {
            parsedSamples.add(
                SoundFontSample(
                    name = bankName,
                    sampleRate = 44100,
                    originalPitch = 60,
                    loopStart = 0,
                    loopEnd = smplPool.size,
                    loopMode = LoopMode.FORWARD,
                    pcmSamples = smplPool
                )
            )
        }

        val defaultInst = SoundFontInstrument(name = bankName, program = 0, bank = 0, samples = parsedSamples)
        return SoundFontBank(bankName, listOf(defaultInst))
    }

    fun parseSfz(text: String, directory: File? = null): SoundFontBank {
        val samples = mutableListOf<SoundFontSample>()
        val regions = text.split(Regex("<region>"))
        for (reg in regions) {
            val lines = reg.lines()
            var sampleFile = ""
            var keyCenter = 60
            var loopMode = LoopMode.NONE

            for (line in lines) {
                val tokens = line.trim().split(Regex("\\s+"))
                for (token in tokens) {
                    val kv = token.split("=")
                    if (kv.size == 2) {
                        when (kv[0].lowercase()) {
                            "sample" -> sampleFile = kv[1]
                            "pitch_keycenter" -> keyCenter = kv[1].toIntOrNull() ?: 60
                            "loop_mode" -> if (kv[1].contains("loop")) loopMode = LoopMode.FORWARD
                        }
                    }
                }
            }

            // Load referenced WAV or synthetic waveform
            var pcm: ShortArray? = null
            var rate = 44100
            if (sampleFile.isNotBlank() && directory != null) {
                val f = File(directory, sampleFile)
                if (f.exists()) {
                    runCatching {
                        val bytes = f.readBytes()
                        if (bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
                            val decoded = AudioPcmData.from16BitLittleEndian(bytes, 44100, 1, 44)
                            pcm = decoded.samples
                            rate = decoded.sampleRate
                        }
                    }
                }
            }

            if (pcm == null) {
                // Synthesize basic piano-harmonic tone
                pcm = synthesizeHarmonicTone(44100, 261.63, 1.0)
            }

            samples.add(
                SoundFontSample(
                    name = sampleFile.ifEmpty { "sfz_region" },
                    sampleRate = rate,
                    originalPitch = keyCenter,
                    loopStart = 0,
                    loopEnd = pcm.size,
                    loopMode = loopMode,
                    pcmSamples = pcm
                )
            )
        }

        val inst = SoundFontInstrument("SFZ Instrument", program = 0, bank = 0, samples = samples)
        return SoundFontBank("SFZ SoundBank", listOf(inst))
    }

    fun loadGusPatch(bytes: ByteArray): SoundFontBank? {
        if (!isGusPatch(bytes)) return null
        val desc = if (bytes.size >= 72) String(bytes, 12, 60, Charsets.ISO_8859_1).trimEnd('\u0000', ' ') else "GUS Patch"
        val pcmDataOffset = if (bytes.size > 239) 239 else bytes.size
        val pcmLen = bytes.size - pcmDataOffset
        val sampleCount = pcmLen / 2
        val samples = ShortArray(sampleCount)
        if (sampleCount > 0) {
            val bb = ByteBuffer.wrap(bytes, pcmDataOffset, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) samples[i] = bb.short
        } else {
            val tone = synthesizeHarmonicTone(44100, 261.63, 1.0)
            return SoundFontBank(desc, listOf(SoundFontInstrument(desc, 0, 0, listOf(
                SoundFontSample(desc, 44100, 60, 0, 0, tone.size, LoopMode.FORWARD, tone)
            ))))
        }

        val sample = SoundFontSample(desc, 44100, 60, 0, 0, samples.size, LoopMode.FORWARD, samples)
        return SoundFontBank(desc, listOf(SoundFontInstrument(desc, 0, 0, listOf(sample))))
    }

    fun synthesizeHarmonicTone(sampleRate: Int, baseFreq: Double, durationSec: Double): ShortArray {
        val totalSamples = (sampleRate * durationSec).toInt()
        val out = ShortArray(totalSamples)
        val harmonics = doubleArrayOf(1.0, 0.5, 0.25, 0.125, 0.0625)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val decay = Math.exp(-3.0 * t) // Natural exponential piano decay
            var sum = 0.0
            for (h in harmonics.indices) {
                val f = baseFreq * (h + 1)
                sum += harmonics[h] * Math.sin(2.0 * Math.PI * f * t)
            }
            val sample = (sum * decay * 20000.0).toInt().coerceIn(-32768, 32767)
            out[i] = sample.toShort()
        }
        return out
    }
}
