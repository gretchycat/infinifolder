package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class ViewerCatalogTest {
    @Test fun videoActivityShortcutReturnsVideoPlayer() {
        assertEquals("com.infinifolder.viewer.VideoPlayerActivity", ViewerCatalog.playbackActivity("playback_video"))
    }
    @Test fun audioActivityShortcutReturnsAudioPlayer() {
        assertEquals("com.infinifolder.viewer.AudioPlayerActivity", ViewerCatalog.playbackActivity("playback_audio"))
    }
    @Test fun unrelatedActivityDoesNotOpenAPlayer() {
        assertNull(ViewerCatalog.playbackActivity("copy"))
        assertNull(ViewerCatalog.playbackActivity("playback_unknown"))
    }
}
