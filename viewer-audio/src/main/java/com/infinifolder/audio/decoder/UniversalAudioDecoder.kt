package com.infinifolder.audio.decoder

import com.infinifolder.audio.chiptune.ChiptunePlayer
import com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics
import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.synth.SoundFontReader
import com.infinifolder.audio.synth.WavetableSynthesizer
import com.infinifolder.audio.tracker.MidiSequencer
import com.infinifolder.audio.tracker.TrackerEngine
import java.io.File

object UniversalAudioDecoder {

    fun isSupported(bytes: ByteArray, extension: String? = null): Boolean {
        val ext = extension?.lowercase() ?: ""
        if (ext in SUPPORTED_EXTENSIONS) return true

        return SunAuDecoder.canDecode(bytes) ||
                AiffDecoder.canDecode(bytes) ||
                VocDecoder.canDecode(bytes) ||
                IffAudioDecoder.canDecode(bytes) ||
                Wave64AndRf64Decoder.canDecode(bytes) ||
                CafDecoder.canDecode(bytes) ||
                SpecializedSampleDecoder.canDecode(bytes) ||
                TelephonyAudioDecoder.canDecode(bytes, ext) ||
                MidiSequencer.isMidi(bytes) ||
                SoundFontReader.isSoundFont(bytes) ||
                SoundFontReader.isGusPatch(bytes) ||
                TrackerEngine.canDecode(bytes) ||
                ChiptunePlayer.canDecode(bytes) ||
                AudiophileStreamDecoder.canDecode(bytes, ext) ||
                WmaDecoder.canDecode(bytes, ext) ||
                AlacAndMpeg2Decoder.canDecode(bytes, ext) ||
                CddaDecoder.canDecode(bytes, ext)
    }

    fun decode(bytes: ByteArray, extension: String? = null, file: File? = null, reportDiagnostics: Boolean = true): AudioPcmData? {
        val ext = extension?.lowercase() ?: file?.extension?.lowercase() ?: ""

        fun attempt(name: String, sourceFormat: String? = null, block: () -> AudioPcmData?): AudioPcmData? {
            return try {
                val res = block()
                if (res != null) {
                    if (reportDiagnostics) AudioPlaybackDiagnostics.recordDecoderResult(name, res, sourceFormat = sourceFormat)
                    res
                } else null
            } catch (e: Throwable) {
                if (reportDiagnostics) AudioPlaybackDiagnostics.recordParserWarning("Decoder $name failed: ${e.message}", e)
                null
            }
        }

        // 0. Standard RIFF WAV
        if (ext == "wav" || (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")) {
            attempt("WavDecoder") { decodeStandardWav(bytes) }?.let { return it }
        }

        // 1. Sun AU & SND
        if (ext == "au" || ext == "snd" || SunAuDecoder.canDecode(bytes)) {
            attempt("SunAuDecoder") { SunAuDecoder.decode(bytes) }?.let { return it }
        }

        // 2. AIFF & AIFC
        if (ext in setOf("aiff", "aif", "aifc") || AiffDecoder.canDecode(bytes)) {
            attempt("AiffDecoder") { AiffDecoder.decode(bytes) }?.let { return it }
        }

        // 3. Creative VOC
        if (ext == "voc" || VocDecoder.canDecode(bytes)) {
            attempt("VocDecoder") { VocDecoder.decode(bytes) }?.let { return it }
        }

        // 4. Amiga IFF 8SVX, 16SV, MAUD
        if (ext in setOf("8svx", "svx", "16sv", "maud") || IffAudioDecoder.canDecode(bytes)) {
            attempt("IffAudioDecoder") { IffAudioDecoder.decode(bytes) }?.let { return it }
        }

        // 5. Sony Wave64 & RF64
        if (ext in setOf("w64", "rf64") || Wave64AndRf64Decoder.canDecode(bytes)) {
            attempt("Wave64AndRf64Decoder") { Wave64AndRf64Decoder.decode(bytes) }?.let { return it }
        }

        // 6. Apple CAF
        if (ext == "caf" || CafDecoder.canDecode(bytes)) {
            attempt("CafDecoder") { CafDecoder.decode(bytes) }?.let { return it }
        }

        // 7. Workstation formats (AVR, IRCAM, SPH, PVF, WVE, HTK)
        if (ext in setOf("avr", "ircam", "sph", "pvf", "wve", "htk") || SpecializedSampleDecoder.canDecode(bytes)) {
            attempt("SpecializedSampleDecoder") { SpecializedSampleDecoder.decode(bytes) }?.let { return it }
        }

        // 8. Telephony & Speech (ADX, AMR, AWB, G722, GSM)
        if (ext in setOf("adx", "amr", "awb", "g722", "gsm") || TelephonyAudioDecoder.canDecode(bytes, ext)) {
            attempt("TelephonyAudioDecoder") { TelephonyAudioDecoder.decode(bytes, ext) }?.let { return it }
        }

        // 9. Standard MIDI (MID, MIDI, KAR, RMI)
        if (ext in setOf("mid", "midi", "kar", "rmi") || MidiSequencer.isMidi(bytes)) {
            attempt("MidiSequencer") { MidiSequencer.renderToPcm(bytes) }?.let { return it }
        }

        // 10. SoundFonts (SF2, SFZ, PAT)
        if (ext == "sf2" || SoundFontReader.isSoundFont(bytes)) {
            attempt("SoundFontReader.SF2") {
                SoundFontReader.loadSf2(bytes)?.let { bank ->
                    val synth = WavetableSynthesizer(44100)
                    synth.soundFont = bank
                    synth.noteOn(0, 60, 100)
                    synth.noteOn(0, 64, 100)
                    synth.noteOn(0, 67, 100)
                    synth.renderToPcm(2500L)
                }
            }?.let { return it }
        }
        if (ext == "sfz") {
            attempt("SoundFontReader.SFZ") {
                val text = String(bytes, Charsets.UTF_8)
                val bank = SoundFontReader.parseSfz(text, file?.parentFile)
                val synth = WavetableSynthesizer(44100)
                synth.soundFont = bank
                synth.noteOn(0, 60, 100)
                synth.noteOn(0, 64, 100)
                synth.noteOn(0, 67, 100)
                synth.renderToPcm(2500L)
            }?.let { return it }
        }
        if (ext == "pat" || SoundFontReader.isGusPatch(bytes)) {
            attempt("SoundFontReader.PAT") {
                SoundFontReader.loadGusPatch(bytes)?.let { bank ->
                    val synth = WavetableSynthesizer(44100)
                    synth.soundFont = bank
                    synth.noteOn(0, 60, 100)
                    synth.renderToPcm(2500L)
                }
            }?.let { return it }
        }

        // 11. Trackers (MOD, S3M, XM, IT)
        if (ext in setOf("mod", "s3m", "xm", "it") || TrackerEngine.canDecode(bytes)) {
            attempt("TrackerEngine") { TrackerEngine.renderToPcm(bytes) }?.let { return it }
        }

        // 12. Chiptunes (DRO, IMF, VGM, VGZ, YM, NSF, SPC, SID)
        if (ext in setOf("dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid") || ChiptunePlayer.canDecode(bytes)) {
            attempt("ChiptunePlayer") { ChiptunePlayer.renderToPcm(bytes) }?.let { return it }
        }

        // 13. Audiophile & Cinema stream codecs (DSF, DFF, TTA, WV, RA, AC3, EAC3, MLP, THD)
        if (ext in setOf("dsf", "dff", "tta", "wv", "ra", "ac3", "eac3", "mlp", "thd") || AudiophileStreamDecoder.canDecode(bytes, ext)) {
            val dsdSource = when (ext) {
                "dff" -> "DSD64 (2.8 MHz 1-bit) → 44.1 kHz • Stereo • DSDIFF (SACD)"
                "dsf" -> "DSD64 (2.8 MHz 1-bit) → 44.1 kHz • Stereo • Sony DSF"
                else -> null
            }
            attempt("AudiophileStreamDecoder", sourceFormat = dsdSource) { AudiophileStreamDecoder.decode(bytes, ext) }?.let { return it }
        }

        // 14. Windows Media Audio backup parser (WMA)
        if (ext == "wma" || WmaDecoder.canDecode(bytes, ext)) {
            attempt("WmaDecoder") { WmaDecoder.decode(bytes) }?.let { return it }
        }

        // 15. ALAC & MPEG-1/2 Layer II (MP2)
        if (ext in setOf("mp2", "alac") || ext.endsWith("alac.m4a") || AlacAndMpeg2Decoder.canDecode(bytes, ext)) {
            attempt("AlacAndMpeg2Decoder") { AlacAndMpeg2Decoder.decode(bytes, ext, file) }?.let { return it }
        }

        // 16. Raw CDDA (Red Book Compact Disc Digital Audio: 44.1 kHz 16-bit Stereo PCM)
        if (ext == "cdda" || CddaDecoder.canDecode(bytes, ext)) {
            attempt("CddaDecoder", sourceFormat = "CD-DA Red Book (44.1 kHz 16-bit) • Stereo • Raw PCM") {
                CddaDecoder.decode(bytes, ext)
            }?.let { return it }
        }

        if (reportDiagnostics) AudioPlaybackDiagnostics.recordDecoderResult("UniversalAudioDecoder", null)
        return null
    }

    private fun decodeStandardWav(bytes: ByteArray): AudioPcmData? {
        var pos = 12
        var channels = 2
        var sampleRate = 44100
        var bits = 16
        var formatTag = 1
        var dataOffset = -1
        var dataLen = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = (bytes[pos + 4].toInt() and 0xFF) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            val chunkDataPos = pos + 8
            pos = chunkDataPos + chunkSize.coerceAtLeast(0)

            when (chunkId) {
                "fmt " -> {
                    if (chunkDataPos + 16 <= bytes.size) {
                        val bb = java.nio.ByteBuffer.wrap(bytes, chunkDataPos, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        formatTag = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bits = bb.short.toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = chunkDataPos
                    dataLen = if (chunkSize > 0) chunkSize.coerceAtMost(bytes.size - dataOffset) else (bytes.size - dataOffset)
                    break
                }
            }
        }

        if (dataOffset < 0 || dataLen <= 0) return null

        return when {
            formatTag == 3 -> AudioPcmData.from32BitFloat(bytes, sampleRate, channels, bigEndian = false, offset = dataOffset, length = dataLen)
            bits == 8 -> AudioPcmData.from8BitUnsigned(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
            bits == 16 -> AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
            bits == 24 -> AudioPcmData.from24Bit(bytes, sampleRate, channels, bigEndian = false, offset = dataOffset, length = dataLen)
            bits == 32 -> {
                val count = dataLen / 4
                val samples = ShortArray(count)
                val bb = java.nio.ByteBuffer.wrap(bytes, dataOffset, dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) samples[i] = (bb.int shr 16).toShort()
                AudioPcmData(sampleRate, channels, samples, 16)
            }
            else -> AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
        }
    }

    val SUPPORTED_EXTENSIONS = setOf(
        "au", "snd", "aiff", "aif", "aifc", "voc", "8svx", "svx", "16sv", "maud",
        "w64", "rf64", "caf", "avr", "ircam", "sph", "pvf", "wve", "htk",
        "adx", "amr", "awb", "g722", "gsm", "wma", "mp2", "alac", "mid", "midi", "kar", "rmi", "sf2", "sfz", "pat",
        "mod", "s3m", "xm", "it", "dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid",
        "dsf", "dff", "tta", "wv", "ra", "ac3", "eac3", "mlp", "thd",
        "m3u", "m3u8", "pls", "xspf", "cue"
    )
}
