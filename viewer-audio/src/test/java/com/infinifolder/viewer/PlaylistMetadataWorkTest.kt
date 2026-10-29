package com.infinifolder.viewer

import android.net.TestUri
import org.junit.Assert.*
import org.junit.Test

class PlaylistMetadataWorkTest {
    private fun track(path: String, duration: Long = 0, start: Long = 0, length: Long = -1) =
        AudioTrack(TestUri(path), path.substringAfterLast('/'), durationMs = duration, startByte = start, lengthBytes = length)

    @Test fun scansKnownDurationsAndMovesPastFailedProbes() {
        val work = PlaylistMetadataWork()
        val tracks = listOf(track("file:///first.mp3"), track("file:///second.mp3", 5000), track("file:///bad.mp3"))
        assertEquals(tracks[1], work.next(tracks, 0))
        assertEquals(tracks[2], work.next(tracks, 0))
        assertEquals(tracks[0], work.next(tracks, 0))
        assertNull(work.next(tracks, 0))
    }

    @Test fun distinguishesServersAndCueSlicesAndHandlesDuplicates() {
        val work = PlaylistMetadataWork()
        val a = track("sftp://one/music/song.mp3")
        val b = track("sftp://two/music/song.mp3")
        val slice = a.copy(startByte = 2352, lengthBytes = 2352)
        val list = listOf(a, a.copy(), b, slice)
        assertEquals(a, work.next(list, -1))
        assertEquals(b, work.next(list, -1))
        assertEquals(slice, work.next(list, -1))
        assertNull(work.next(list, -1))
        assertFalse(work.matches(a, b))
        assertFalse(work.matches(a, slice))
    }

    @Test fun keepsPlaybackUpdatesAndCueTitlesDuringConcurrentProbe() {
        val work = PlaylistMetadataWork()
        val before = track("file:///album.bin", duration = 2000, length = 20000).copy(title = "Track One")
        val current = before.copy(durationMs = 2500, artist = "Live artist")
        val probe = before.copy(title = "Album title", artist = "Probe artist", album = "Album", durationMs = 9000)
        val result = work.merge(current, before, probe)
        assertEquals("Track One", result.title)
        assertEquals("Live artist", result.artist)
        assertEquals("Album", result.album)
        assertEquals(2500, result.durationMs)
    }
}
