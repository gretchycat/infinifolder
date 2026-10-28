package com.infinifolder.audio

import com.infinifolder.audio.chiptune.ChiptunePlayer
import com.infinifolder.audio.decoder.*
import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.playlist.PlaylistParser
import com.infinifolder.audio.synth.*
import com.infinifolder.audio.tracker.MidiSequencer
import com.infinifolder.audio.tracker.TrackerEngine
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.storage.FileMagicDetector
import com.infinifolder.viewer.MediaType
import com.infinifolder.viewer.MediaTypeDetector
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioDecodersFixtureTest {

    private val fixtureDir = File("filesystem-test-images/audio").let {
        if (it.exists()) it else File("../filesystem-test-images/audio")
    }

    private fun readFixture(fileName: String): ByteArray {
        val f = File(fixtureDir, fileName)
        assertTrue("Fixture file must exist: ${f.absolutePath}", f.exists() && f.length() > 0)
        return f.readBytes()
    }

    @Test
    fun testSunAuDecoder() {
        val auBytes = readFixture("c_major_piano.au")
        assertTrue(SunAuDecoder.canDecode(auBytes))
        val pcm = SunAuDecoder.decode(auBytes)
        assertNotNull(pcm)
        assertEquals(44100, pcm!!.sampleRate)
        assertEquals(2, pcm.channels)
        assertTrue(pcm.samples.isNotEmpty())
        assertTrue("Duration should be > 1000ms", pcm.durationMs > 1000)

        val sndBytes = readFixture("c_major_piano.snd")
        assertTrue(SunAuDecoder.canDecode(sndBytes))
        val sndPcm = SunAuDecoder.decode(sndBytes)
        assertNotNull(sndPcm)
        assertTrue(sndPcm!!.samples.isNotEmpty())
    }

    @Test
    fun testAiffDecoder() {
        for (name in listOf("c_major_piano.aiff", "c_major_piano.aif", "c_major_piano.aifc")) {
            val bytes = readFixture(name)
            assertTrue("AiffDecoder should recognize $name", AiffDecoder.canDecode(bytes))
            val pcm = AiffDecoder.decode(bytes)
            assertNotNull("AiffDecoder should decode $name", pcm)
            assertEquals(44100, pcm!!.sampleRate)
            assertEquals(2, pcm.channels)
            assertTrue(pcm.samples.isNotEmpty())
        }
    }

    @Test
    fun testVocDecoder() {
        val bytes = readFixture("c_major_piano.voc")
        assertTrue(VocDecoder.canDecode(bytes))
        val pcm = VocDecoder.decode(bytes)
        assertNotNull(pcm)
        assertTrue(pcm!!.sampleRate > 0)
        assertTrue(pcm.samples.isNotEmpty())
    }

    @Test
    fun testIffAudioDecoder() {
        for (name in listOf("c_major_piano.8svx", "c_major_piano.svx", "c_major_piano.maud")) {
            val bytes = readFixture(name)
            assertTrue("IffAudioDecoder should recognize $name", IffAudioDecoder.canDecode(bytes))
            val pcm = IffAudioDecoder.decode(bytes)
            assertNotNull("IffAudioDecoder should decode $name", pcm)
            assertTrue(pcm!!.sampleRate > 0)
            assertTrue(pcm.samples.isNotEmpty())
        }
    }

    @Test
    fun testWave64AndRf64Decoder() {
        val w64Bytes = readFixture("c_major_piano.w64")
        assertTrue(Wave64AndRf64Decoder.canDecode(w64Bytes))
        val w64Pcm = Wave64AndRf64Decoder.decode(w64Bytes)
        assertNotNull(w64Pcm)
        assertEquals(44100, w64Pcm!!.sampleRate)
        assertEquals(2, w64Pcm.channels)
        assertTrue(w64Pcm.samples.isNotEmpty())

        val rf64Bytes = readFixture("c_major_piano.rf64")
        assertTrue(Wave64AndRf64Decoder.canDecode(rf64Bytes))
        val rf64Pcm = Wave64AndRf64Decoder.decode(rf64Bytes)
        assertNotNull(rf64Pcm)
        assertEquals(44100, rf64Pcm!!.sampleRate)
        assertEquals(2, rf64Pcm.channels)
        assertTrue(rf64Pcm.samples.isNotEmpty())
    }

    @Test
    fun testCafDecoder() {
        val bytes = readFixture("c_major_piano.caf")
        assertTrue(CafDecoder.canDecode(bytes))
        val pcm = CafDecoder.decode(bytes)
        assertNotNull(pcm)
        assertEquals(44100, pcm!!.sampleRate)
        assertEquals(2, pcm.channels)
        assertTrue(pcm.samples.isNotEmpty())
    }

    @Test
    fun testSpecializedSampleDecoder() {
        val avrBytes = readFixture("c_major_piano.avr")
        assertTrue(SpecializedSampleDecoder.canDecode(avrBytes))
        val avrPcm = SpecializedSampleDecoder.decode(avrBytes)
        assertNotNull(avrPcm)
        assertTrue(avrPcm!!.samples.isNotEmpty())

        val ircamBytes = readFixture("c_major_piano.ircam")
        assertTrue(SpecializedSampleDecoder.canDecode(ircamBytes))
        val ircamPcm = SpecializedSampleDecoder.decode(ircamBytes)
        assertNotNull(ircamPcm)
        assertEquals(44100, ircamPcm!!.sampleRate)
        assertEquals(2, ircamPcm.channels)

        val sphBytes = readFixture("c_major_piano.sph")
        assertTrue(SpecializedSampleDecoder.canDecode(sphBytes))
        val sphPcm = SpecializedSampleDecoder.decode(sphBytes)
        assertNotNull(sphPcm)
        assertEquals(44100, sphPcm!!.sampleRate)
        assertEquals(2, sphPcm.channels)

        val pvfBytes = readFixture("c_major_piano.pvf")
        assertTrue(SpecializedSampleDecoder.canDecode(pvfBytes))
        val pvfPcm = SpecializedSampleDecoder.decode(pvfBytes)
        assertNotNull(pvfPcm)
        assertEquals(16000, pvfPcm!!.sampleRate)

        val wveBytes = readFixture("c_major_piano.wve")
        assertTrue(SpecializedSampleDecoder.canDecode(wveBytes))
        val wvePcm = SpecializedSampleDecoder.decode(wveBytes)
        assertNotNull(wvePcm)
        assertEquals(8000, wvePcm!!.sampleRate)

        val htkBytes = readFixture("c_major_piano.htk")
        assertTrue(SpecializedSampleDecoder.canDecode(htkBytes))
        val htkPcm = SpecializedSampleDecoder.decode(htkBytes)
        assertNotNull(htkPcm)
        assertEquals(16000, htkPcm!!.sampleRate)
    }

    @Test
    fun testTelephonyAudioDecoder() {
        val adxBytes = readFixture("c_major_piano.adx")
        assertTrue(TelephonyAudioDecoder.canDecode(adxBytes))
        val adxPcm = TelephonyAudioDecoder.decode(adxBytes)
        assertNotNull(adxPcm)
        assertEquals(44100, adxPcm!!.sampleRate)
        assertEquals(2, adxPcm.channels)
        assertTrue(adxPcm.samples.isNotEmpty())

        val amrBytes = readFixture("c_major_piano.amr")
        assertTrue(TelephonyAudioDecoder.canDecode(amrBytes))
        val amrPcm = TelephonyAudioDecoder.decode(amrBytes)
        assertNotNull(amrPcm)
        assertEquals(8000, amrPcm!!.sampleRate)

        val awbBytes = readFixture("c_major_piano.awb")
        assertTrue(TelephonyAudioDecoder.canDecode(awbBytes))
        val awbPcm = TelephonyAudioDecoder.decode(awbBytes)
        assertNotNull(awbPcm)
        assertEquals(16000, awbPcm!!.sampleRate)
    }

    @Test
    fun testSampleResamplerAndSynthPrerequisites() {
        val originalTone = SoundFontReader.synthesizeHarmonicTone(44100, 261.63, 0.5)
        val resampler = SampleResampler(
            sampleData = originalTone,
            sampleRate = 44100,
            loopStart = 100,
            loopEnd = originalTone.size - 100,
            loopMode = LoopMode.FORWARD
        )

        val outSamples = FloatArray(1000)
        for (i in outSamples.indices) {
            outSamples[i] = resampler.nextSample(1.5, useCubic = true)
        }
        assertTrue("Resampled samples should contain non-zero audio data", outSamples.any { it != 0f })

        // Test ADSR envelope
        val env = EnvelopeGenerator(attackSec = 0.01f, decaySec = 0.02f, sustainLevel = 0.5f, releaseSec = 0.02f)
        env.noteOn()
        assertTrue(env.isActive)
        for (i in 0 until 2000) env.step(44100)
        assertEquals(EnvelopeState.SUSTAIN, env.state)
        assertEquals(0.5f, env.value, 0.01f)
        env.noteOff()
        assertEquals(EnvelopeState.RELEASE, env.state)
        for (i in 0 until 2000) env.step(44100)
        assertEquals(EnvelopeState.IDLE, env.state)

        // Test LFO
        val lfo = Lfo(frequencyHz = 5f, depth = 0.5f)
        val lfoVal = lfo.step(44100)
        assertTrue(lfoVal in -0.5f..0.5f)
    }

    @Test
    fun testSoundFontReader() {
        val sf2Bytes = readFixture("c_major_piano.sf2")
        assertTrue(SoundFontReader.isSoundFont(sf2Bytes))
        val sf2Bank = SoundFontReader.loadSf2(sf2Bytes)
        assertNotNull(sf2Bank)
        assertTrue(sf2Bank!!.instruments.isNotEmpty())

        val sfzText = File(fixtureDir, "c_major_piano.sfz").readText()
        assertTrue(SoundFontReader.isSfz(sfzText))
        val sfzBank = SoundFontReader.parseSfz(sfzText, fixtureDir)
        assertNotNull(sfzBank)
        assertTrue(sfzBank.instruments.isNotEmpty())

        val patBytes = readFixture("c_major_piano.pat")
        assertTrue(SoundFontReader.isGusPatch(patBytes))
        val patBank = SoundFontReader.loadGusPatch(patBytes)
        assertNotNull(patBank)
        assertTrue(patBank!!.instruments.isNotEmpty())
    }

    @Test
    fun testMidiSequencer() {
        for (name in listOf("c_major_piano.mid", "c_major_piano.midi", "c_major_piano.kar", "c_major_piano.rmi")) {
            val bytes = readFixture(name)
            assertTrue("MidiSequencer should identify $name", MidiSequencer.isMidi(bytes))
            val pcm = MidiSequencer.renderToPcm(bytes)
            assertNotNull("MidiSequencer should render $name", pcm)
            assertEquals(44100, pcm!!.sampleRate)
            assertEquals(2, pcm.channels)
            assertTrue(pcm.samples.isNotEmpty())
        }
    }

    @Test
    fun testTrackerEngine() {
        for (name in listOf("c_major_piano.mod", "c_major_piano.s3m", "c_major_piano.xm", "c_major_piano.it")) {
            val bytes = readFixture(name)
            assertTrue("TrackerEngine should recognize $name", TrackerEngine.canDecode(bytes))
            val pcm = TrackerEngine.renderToPcm(bytes)
            assertNotNull("TrackerEngine should render $name", pcm)
            assertEquals(44100, pcm!!.sampleRate)
            assertEquals(2, pcm.channels)
            assertTrue(pcm.samples.isNotEmpty())
        }
    }

    @Test
    fun testChiptunePlayer() {
        for (name in listOf("c_major_piano.dro", "c_major_piano.imf", "c_major_piano.vgm", "c_major_piano.vgz",
            "c_major_piano.ym", "c_major_piano.nsf", "c_major_piano.spc", "c_major_piano.sid")) {
            val bytes = readFixture(name)
            assertTrue("ChiptunePlayer should recognize $name", ChiptunePlayer.canDecode(bytes))
            val pcm = ChiptunePlayer.renderToPcm(bytes)
            assertNotNull("ChiptunePlayer should render $name", pcm)
            assertEquals(44100, pcm!!.sampleRate)
            assertTrue(pcm.samples.isNotEmpty())
        }
    }

    @Test
    fun testAudiophileStreamDecoder() {
        for (name in listOf("c_major_piano.dsf", "c_major_piano.dff", "c_major_piano.tta", "c_major_piano.wv",
            "c_major_piano.ra", "c_major_piano.ac3", "c_major_piano.eac3", "c_major_piano.mlp", "c_major_piano.thd")) {
            val bytes = readFixture(name)
            assertTrue("AudiophileStreamDecoder should recognize $name", AudiophileStreamDecoder.canDecode(bytes))
            val pcm = AudiophileStreamDecoder.decode(bytes)
            assertNotNull("AudiophileStreamDecoder should decode $name", pcm)
            assertEquals(44100, pcm!!.sampleRate)
            assertTrue(pcm.samples.isNotEmpty())
        }
    }

    @Test
    fun testCddaDecoder() {
        val bytes = readFixture("c_major_piano.cdda")
        assertTrue("CddaDecoder should recognize .cdda", com.infinifolder.audio.decoder.CddaDecoder.canDecode(bytes, "cdda"))
        val pcm = com.infinifolder.audio.decoder.CddaDecoder.decode(bytes, "cdda")
        assertNotNull("CddaDecoder should decode .cdda", pcm)
        assertEquals(44100, pcm!!.sampleRate)
        assertEquals(2, pcm.channels)
        assertEquals(16, pcm.bitsPerSample)
        assertTrue(pcm.samples.isNotEmpty())

        val universal = UniversalAudioDecoder.decode(bytes, "cdda")
        assertNotNull("UniversalAudioDecoder should decode .cdda", universal)
        assertEquals(44100, universal!!.sampleRate)
        assertEquals(2, universal.channels)
    }

    @Test
    fun testPlaylistParser() {
        val playlistDir = File(fixtureDir.parentFile, "playlists").let {
            if (it.exists()) it else fixtureDir
        }
        for (name in listOf("c_major_piano.m3u", "c_major_piano.m3u8", "c_major_piano.pls", "c_major_piano.xspf", "c_major_piano.cue")) {
            val content = File(playlistDir, name).readText()
            assertTrue("PlaylistParser should recognize $name", PlaylistParser.isPlaylist(name, content))
            val entries = PlaylistParser.parse(name, content)
            assertTrue("Playlist $name should have parsed tracks", entries.isNotEmpty())
            assertTrue("Parsed track must have non-empty path", entries[0].pathOrUri.isNotBlank())
        }
    }

    @Test
    fun testFileMagicDetectionForAudio() {
        fun magic(name: String) = FileMagicDetector.detect(File(fixtureDir, name))?.mimeType

        assertEquals("audio/basic", magic("c_major_piano.au"))
        assertEquals("audio/x-aiff", magic("c_major_piano.aiff"))
        assertEquals("audio/x-voc", magic("c_major_piano.voc"))
        assertEquals("audio/x-8svx", magic("c_major_piano.8svx"))
        assertEquals("audio/x-wave64", magic("c_major_piano.w64"))
        assertEquals("audio/x-rf64", magic("c_major_piano.rf64"))
        assertEquals("audio/x-caf", magic("c_major_piano.caf"))
        assertEquals("audio/midi", magic("c_major_piano.mid"))
        assertEquals("audio/x-xm", magic("c_major_piano.xm"))
        assertEquals("audio/x-it", magic("c_major_piano.it"))
        assertEquals("audio/x-s3m", magic("c_major_piano.s3m"))
        assertEquals("audio/x-dsf", magic("c_major_piano.dsf"))
    }

    @Test
    fun testMediaTypeDetectionForAudioFormats() {
        for (ext in listOf("au", "snd", "aiff", "aif", "voc", "8svx", "w64", "rf64", "caf",
            "avr", "ircam", "sph", "pvf", "wve", "htk", "adx", "amr", "awb",
            "mid", "midi", "kar", "rmi", "sf2", "sfz", "pat", "mod", "s3m", "xm", "it",
            "dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid", "dsf", "dff", "wv", "tta")) {
            val mediaType = MediaTypeDetector.detect("sample.$ext", null)
            assertEquals("Extension .$ext should be recognized as AUDIO", MediaType.AUDIO, mediaType)
        }
    }
}
