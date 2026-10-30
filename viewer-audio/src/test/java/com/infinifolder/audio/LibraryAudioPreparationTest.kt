package com.infinifolder.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryAudioPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(ext: String): File {
        val root = File("../filesystem-test-images/audio").takeIf { it.isDirectory } ?: File("filesystem-test-images/audio")
        return File(root, "c_major_piano.$ext")
    }
    @Test fun linkedLibraryOwnsPlatformFallbackAndPreparesOnlyPcmForOutput() {
        for (ext in listOf("mp3","aac","m4a","flac")) {
            val attempts = mutableListOf<String>()
            val destination = temporary.newFile("$ext.wav")
            val prepared = AudioProcessing.prepareForOutput(fixture(ext),destination,observer=object : AudioPreparationObserver {
                override fun warning(decoder: String, error: Throwable) { attempts += decoder }
            })
            // Android codec stubs are unavailable on this JVM; the library must own the fallback.
            assertTrue(attempts.contains("Android.MediaCodec"))
            assertTrue(prepared.decoder.startsWith("Portable."))
            assertEquals(44100,prepared.format.sampleRate)
            assertEquals(2,prepared.format.channels)
            assertTrue(prepared.format.durationMs!! in 1400..1650)
            assertEquals("RIFF",destination.inputStream().use { String(it.readNBytes(4),Charsets.US_ASCII) })
            assertTrue(prepared.signalMetrics!!.peakAmplitude>0.05f)
        }
    }
    @Test fun durationDecodingUsesTheSameLibraryBackendsWithoutAPlayer() {
        for (ext in listOf("mp3","m4a","flac")) assertEquals(ext,1500L,AudioProcessing.readDuration(fixture(ext)))
    }
}
