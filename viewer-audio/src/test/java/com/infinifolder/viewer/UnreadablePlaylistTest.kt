package com.infinifolder.viewer

import android.net.TestUri
import org.junit.Assert.*
import org.junit.Test

class UnreadablePlaylistTest {
    private fun track(name: String, bad: Boolean = false) = AudioTrack(TestUri("file:///$name"), name,
        durationMs = if (bad) 0 else 1000, isUnreadable = bad)
    @Test fun consecutiveFailuresKeepTheirEntriesAndAdvanceToReadable() {
        val tracks = listOf(track("first", true), track("second", true), track("third"))
        assertEquals(2, nextReadableTrack(tracks, 0, false))
        assertEquals(3, tracks.size)
        assertEquals("second", tracks[1].title)
    }
    @Test fun allUnreadableStopsEvenWithRepeatAll() {
        assertNull(nextReadableTrack(List(20) { track("bad$it", true) }, 4, true))
    }
    @Test fun nonRepeatingQueueDoesNotWrapPastTrailingFailures() {
        assertNull(nextReadableTrack(listOf(track("good"), track("bad", true)), 0, false))
    }
    @Test fun repeatAllWrapsOnlyToReadable() {
        val tracks = listOf(track("bad",true), track("good"), track("last",true))
        assertEquals(1, nextReadableTrack(tracks,2,true))
    }
    @Test fun previousSkipsUnreadableAndRespectsBeginning() {
        val tracks = listOf(track("good"), track("bad",true), track("last"))
        assertEquals(0,nextReadableTrack(tracks,2,false,-1))
        assertNull(nextReadableTrack(tracks,0,false,-1))
    }
    @Test fun failedTrackRetainsIdentityAndClearsEvenKnownDuration() {
        val original = track("song").copy(startByte=2352,lengthBytes=4704,artist="Artist")
        val failed = unreadableTrack(original)
        assertEquals(original.uri,failed.uri)
        assertEquals(original.title,failed.title)
        assertEquals(original.artist,failed.artist)
        assertEquals(original.startByte,failed.startByte)
        assertEquals(original.lengthBytes,failed.lengthBytes)
        assertEquals(0,failed.durationMs)
        assertTrue(failed.isUnreadable)
    }
    @Test fun metadataCannotReinstateFailedDurationAfterConcurrentProbe() {
        val original=track("song")
        val failed=unreadableTrack(original)
        val result=PlaylistMetadataWork().merge(failed,original,original.copy(durationMs=9999))
        assertEquals(0,result.durationMs)
        assertTrue(result.isUnreadable)
    }
    @Test fun scannerDoesNotReprobeUnreadableEntries() {
        val tracks=listOf(track("bad",true),track("good"))
        val work=PlaylistMetadataWork()
        assertEquals(tracks[1],work.next(tracks,-1))
        assertNull(work.next(tracks,-1))
    }
    @Test fun lateFolderRefreshRetainsFailureAndUnknownDuration() {
        val bad=unreadableTrack(track("bad"))
        val refreshed=retainUnreadableState(listOf(bad),listOf(track("bad").copy(title="New metadata",durationMs=9000)))
        assertTrue(refreshed[0].isUnreadable)
        assertEquals(0,refreshed[0].durationMs)
        assertEquals("New metadata",refreshed[0].title)
    }
    @Test fun refreshDoesNotPoisonAnotherSourceSlice() {
        val bad=unreadableTrack(track("same").copy(startByte=2352,lengthBytes=2352))
        val other=track("same").copy(startByte=4704,lengthBytes=2352)
        assertEquals(other,retainUnreadableState(listOf(bad),listOf(other))[0])
    }
    @Test fun successfulRetryRestoresDuplicateReferencesAndKeepsTheirTitles() {
        val first=unreadableTrack(track("same"))
        val tracks=mutableListOf(first,first.copy(title="Alias"),unreadableTrack(track("other")))
        restoreReadableState(tracks,first,2345)
        assertFalse(tracks[0].isUnreadable)
        assertFalse(tracks[1].isUnreadable)
        assertEquals("Alias",tracks[1].title)
        assertEquals(2345,tracks[1].durationMs)
        assertTrue(tracks[2].isUnreadable)
        assertFalse(retainUnreadableState(tracks,listOf(track("same")))[0].isUnreadable)
    }

}
