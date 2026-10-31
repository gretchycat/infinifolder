package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class MonkeyAudioAssociationTest {
    @Test fun monkeyAudioRoutesToTheAudioPlayer() {
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("music.ape", null))
    }
}
