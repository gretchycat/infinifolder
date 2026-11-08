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
        assertEquals("c_major_piano.wav", tracks[0].fileName)
        assertEquals("c_major_piano.wav", tracks[0].copy(title = "Renamed by metadata").fileName)

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
        assertEquals(listOf("wav", "flac", "mp3", "alac", "mp2", "mod"),
            xspfTracks.map { it.fileName.substringAfterLast('.') })
        assertEquals(listOf(
            "C Major Piano Chord (WAV)", "C Major Piano Chord (FLAC)", "C Major Piano Chord (MP3)",
            "C Major Piano Chord (ALAC)", "C Major Piano Chord (MP2)", "C Major Piano Chord (MOD)"
        ), xspfTracks.map { it.title })
        for (track in xspfTracks) {
            val file = File(track.uri.path ?: "")
            assertTrue("XSPF reference should resolve to an existing audio fixture: ${track.uri}", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun testJspfExpansionAndAudioViewerRouting() {
        val jspfFile = File(playlistsDir, "c_major_piano.jspf")
        assertTrue("c_major_piano.jspf must exist", jspfFile.exists())
        val content = jspfFile.readText()
        val jspfEntries = com.infinifolder.audio.playlist.PlaylistParser.parse(jspfFile.name, content)
        assertEquals(6, jspfEntries.size)
        assertEquals("InfiniFolder", jspfEntries.first().performer)
        assertEquals("Playlist Parser Fixtures", jspfEntries.first().album)
        assertEquals(2000L, jspfEntries.first().durationMillis)
        val tracks = PlaylistExpander.expandPlaylist(dummyContext(), TestUri("file://${jspfFile.absolutePath}"))
        assertEquals(6, tracks.size)
        assertEquals("InfiniFolder", tracks.first().artist)
        assertEquals("C Major Piano Chord Suite", tracks.first().album)
        assertEquals(2000L, tracks.first().durationMs)
        assertTrue("Every JSPF reference should resolve", tracks.all {
            File(it.uri.path ?: "").let { file -> file.isFile && file.length() > 0 }
        })

        val audioViewer = AudioViewerCapabilityProvider().handlers().single() as LibraryViewerComponent
        assertTrue("The audio viewer must inherit playlist formats from Sound",
            audioViewer.describe().formats.any { "xspf" in it.extensions } &&
                audioViewer.describe().formats.any { "jspf" in it.extensions })
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("c_major_piano.xspf", "application/xspf+xml"))
    }

    @Test
    fun testCueAndCdrdaoTocExpandRawCdTracksWithDecoderHint() {
        for (name in listOf("c_major_piano.cue", "c_major_piano.toc")) {
            val playlist = File(playlistsDir, name)
            val tracks = PlaylistExpander.expandPlaylist(dummyContext(), TestUri("file://${playlist.absolutePath}"))
            assertEquals("$name should expand its two audio tracks", 2, tracks.size)
            assertEquals(listOf("cdda", "cdda"), tracks.map { it.decoderExtension })
            assertEquals(listOf(0L, 38L * 2352L), tracks.map { it.startByte })
            assertEquals(38L * 2352L, tracks.first().lengthBytes)
            assertTrue(tracks.all { File(it.uri.path ?: "").isFile })
            val raw = File(tracks.first().uri.path!!).readBytes()
            val pcm = com.infinifolder.audio.decoder.UniversalAudioDecoder.decode(
                raw.copyOfRange(tracks.first().startByte.toInt(),
                    (tracks.first().startByte + tracks.first().lengthBytes).toInt()),
                tracks.first().decoderExtension)
            assertNotNull("Raw CD-DA bytes from $name should reach the CD-DA decoder", pcm)
        }
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("c_major_piano.toc", "application/x-cdrdao-toc"))
    }

    @Test
    fun testXspfAbsoluteAndEncodedFileLocationsResolveAsUris() {
        val folder = tempFolder.newFolder("xspf paths with spaces")
        val playlist = File(folder, "list.xspf").apply { writeText("<playlist/>") }
        val target = File(folder, "track one.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val playlistUri = TestUri("file://${playlist.absolutePath}")
        val absolute = PlaylistExpander.resolveEntryUri(dummyContext(), playlistUri, target.toURI().toString())
        assertEquals(target.toURI().normalize(), java.net.URI(absolute.toString()).normalize())

        val encodedRelative = PlaylistExpander.resolveEntryUri(dummyContext(), playlistUri, "track%20one.wav")
        assertEquals(target.toURI().normalize(), java.net.URI(encodedRelative.toString()).normalize())

        val contentBase = TestUri("content://provider/Music/list.xspf")
        val contentTrack = PlaylistExpander.resolveEntryUri(dummyContext(), contentBase, "track%20one.wav")
        assertEquals("content://provider/Music/track%20one.wav", contentTrack.toString())
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
