package com.infinifolder.audio

import com.infinifolder.audio.engine.PcmAudioEngine
import com.infinifolder.audio.model.AudioPcmData
import org.junit.Assert.*
import org.junit.Test

class PcmAudioEngineTest {
    @Test fun unavailableAudioTrackDoesNotPretendToPlayOrComplete() {
        var completed = false
        val engine = PcmAudioEngine(AudioPcmData(44100, 2, ShortArray(4410), 16)) { completed = true }
        try {
            engine.play()
            assertFalse(engine.isPlaying)
            assertEquals(0, engine.currentPositionMs)
            assertFalse(completed)
        } finally { engine.release() }
    }
}
