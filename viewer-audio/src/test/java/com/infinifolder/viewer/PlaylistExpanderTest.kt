package com.infinifolder.viewer

import android.net.TestUri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistExpanderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @org.junit.Before
    fun setUp() {
        PlaylistExpander.uriFactory = { str -> TestUri(str) }
    }

    private val playlistsDir = File("filesystem-test-images/playlists").let {
        if (it.exists()) it else File("../filesystem-test-images/playlists")
    }

    private val audioDir = File("filesystem-test-images/audio").let {
        if (it.exists()) it else File("../filesystem-test-images/audio")
    }

    @Test
    fun testDirectM3uPlaylistExpansion() {
        val m3uFile = File(playlistsDir, "c_major_piano.m3u")
        assertTrue("c_major_piano.m3u must exist", m3uFile.exists())

        val uri = TestUri("file://${m3uFile.absolutePath}")
        val tracks = PlaylistExpander.expandPlaylist(null as? android.content.Context ?: dummyContext(), uri)

        assertTrue("Expected multiple tracks from playlist, got ${tracks.size}", tracks.size >= 10)
        assertEquals("InfiniFolder - C Major Piano Chord (WAV)", tracks[0].title)

        // Verify each track resolves to an existing audio file on disk
        for (track in tracks) {
            val f = File(track.uri.path ?: "")
            assertTrue("Referenced track file must exist: ${track.uri} ($f)", f.exists())
            assertTrue("Referenced track file size must be > 0: $f", f.length() > 0)
        }
    }

    @Test
    fun testPlsAndXspfExpansion() {
        val plsFile = File(playlistsDir, "c_major_piano.pls")
        assertTrue("c_major_piano.pls must exist", plsFile.exists())
        val plsTracks = PlaylistExpander.expandPlaylist(dummyContext(), TestUri("file://${plsFile.absolutePath}"))
        assertEquals(6, plsTracks.size)
        assertTrue(File(plsTracks[0].uri.path ?: "").exists())

        val xspfFile = File(playlistsDir, "c_major_piano.xspf")
        assertTrue("c_major_piano.xspf must exist", xspfFile.exists())
        val xspfTracks = PlaylistExpander.expandPlaylist(dummyContext(), TestUri("file://${xspfFile.absolutePath}"))
        assertEquals(6, xspfTracks.size)
        assertTrue(File(xspfTracks[0].uri.path ?: "").exists())
    }

    @Test
    fun testNestedPlaylistRecursiveExpansionInOrder() {
        val nestedFile = File(playlistsDir, "nested_playlist.m3u")
        assertTrue("nested_playlist.m3u must exist", nestedFile.exists())

        val uri = TestUri("file://${nestedFile.absolutePath}")
        val tracks = PlaylistExpander.expandPlaylist(dummyContext(), uri)

        // nested_playlist.m3u has:
        // 1. WAV
        // 2. c_major_piano.m3u (which has 14 tracks)
        // 3. XM
        // Total = 1 + 14 + 1 = 16 tracks
        assertEquals(16, tracks.size)
        assertEquals("InfiniFolder - Overture (WAV)", tracks[0].title)
        assertTrue("Track 1 must be WAV", tracks[0].uri.path?.endsWith(".wav") == true)

        // Next 14 tracks from c_major_piano.m3u
        assertEquals("InfiniFolder - C Major Piano Chord (WAV)", tracks[1].title)
        assertTrue("Track 2 must be WAV", tracks[1].uri.path?.endsWith(".wav") == true)
        assertEquals("InfiniFolder - C Major Piano Chord (WMA)", tracks[14].title)
        assertTrue("Track 15 must be WMA", tracks[14].uri.path?.endsWith(".wma") == true)

        // Final track
        assertEquals("InfiniFolder - Finale Tracker (XM)", tracks[15].title)
        assertTrue("Track 16 must be XM", tracks[15].uri.path?.endsWith(".xm") == true)

        for (track in tracks) {
            assertTrue("Track file must exist: ${track.uri}", File(track.uri.path ?: "").exists())
        }
    }

    @Test
    fun testCircularPlaylistCycleDetection() {
        val folder = tempFolder.newFolder("circular")
        val a = File(folder, "a.m3u")
        val b = File(folder, "b.m3u")
        val song = File(folder, "sample.wav").apply { writeText("RIFF....WAVEfmt ") }

        a.writeText("#EXTM3U\nsample.wav\nb.m3u\n")
        b.writeText("#EXTM3U\na.m3u\nsample.wav\n")

        val tracks = PlaylistExpander.expandPlaylist(dummyContext(), TestUri("file://${a.absolutePath}"))
        // Cycle should be broken gracefully without infinite loop or crash
        assertNotNull(tracks)
        assertTrue(tracks.isNotEmpty())
        assertEquals(2, tracks.size)
    }

    @Test
    fun testExpandResourceEntriesInFolderSplicesPlaylistsInOrder() {
        val folder = tempFolder.newFolder("album")
        val intro = File(folder, "00_intro.mp3").apply { writeText("ID3_INTRO") }
        val outro = File(folder, "99_outro.mp3").apply { writeText("ID3_OUTRO") }
        val nested = File(playlistsDir, "nested_playlist.m3u")

        val entries = listOf(
            ResourceEntry(
                ref = ResourceRef("local", intro.absolutePath),
                uri = TestUri("file://${intro.absolutePath}"),
                metadata = ResourceMetadata(name = intro.name, mimeType = "audio/mpeg", sizeBytes = intro.length(), modifiedAtMillis = intro.lastModified(), isDirectory = false)
            ),
            ResourceEntry(
                ref = ResourceRef("local", nested.absolutePath),
                uri = TestUri("file://${nested.absolutePath}"),
                metadata = ResourceMetadata(name = nested.name, mimeType = "audio/x-mpegurl", sizeBytes = nested.length(), modifiedAtMillis = nested.lastModified(), isDirectory = false)
            ),
            ResourceEntry(
                ref = ResourceRef("local", outro.absolutePath),
                uri = TestUri("file://${outro.absolutePath}"),
                metadata = ResourceMetadata(name = outro.name, mimeType = "audio/mpeg", sizeBytes = outro.length(), modifiedAtMillis = outro.lastModified(), isDirectory = false)
            )
        )

        val expanded = PlaylistExpander.expandResourceEntries(dummyContext(), entries)
        // 1 (intro) + 16 (nested playlist) + 1 (outro) = 18 tracks in total!
        assertEquals(18, expanded.size)
        assertEquals("00_intro.mp3", expanded[0].title)
        assertEquals("InfiniFolder - Overture (WAV)", expanded[1].title)
        assertEquals("InfiniFolder - Finale Tracker (XM)", expanded[16].title)
        assertEquals("99_outro.mp3", expanded[17].title)
    }

    private fun dummyContext(): android.content.Context = object : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
    }
}
