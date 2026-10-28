package com.infinifolder.archive

import android.content.Context
import android.net.TestUri
import android.net.Uri
import com.infinifolder.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistVirtualDirectoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        ArchiveUri.uriFactory = { str -> TestUri(str) }
    }

    private class TestContext(private val cache: File) : android.content.ContextWrapper(null) {
        override fun getCacheDir(): File = cache
    }

    @Test
    fun testPlaylistFormatsRecognizedAsBrowsableArchives() {
        val formats = listOf("playlist.m3u", "playlist.m3u8", "playlist.pls", "playlist.xspf", "playlist.cue")
        for (f in formats) {
            assertTrue("Expected $f to be recognized as playlist", ArchiveFormats.isPlaylist(f))
            assertTrue("Expected $f to be recognized as archive", ArchiveFormats.isArchive(f))
            assertFalse("Playlists default to playback rather than browse", ArchiveFormats.isDefaultBrowsableArchive(f))
        }
    }

    @Test
    fun testM3uVirtualDirectoryListingAndLinkResolution() {
        val rootDir = tempFolder.newFolder("music")
        val audio1 = File(rootDir, "song1.mp3").apply { writeText("PCM_SONG_1_DATA") }
        val audio2 = File(rootDir, "song2.flac").apply { writeText("PCM_SONG_2_DATA") }

        val m3uFile = File(rootDir, "favorites.m3u").apply {
            writeText(
                """
                #EXTM3U
                #EXTINF:180,Artist A - Song One
                song1.mp3
                #EXTINF:210,Artist B - Song Two
                song2.flac
                """.trimIndent()
            )
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp"))
        val tree = reader.buildTree(null, m3uFile.name, { m3uFile.inputStream() }, m3uFile)

        val entries = tree.list("")
        assertEquals(2, entries.size)

        val e1 = entries[0]
        assertEquals("01. Artist A - Song One.mp3", e1.name)
        assertTrue(e1.isSymbolicLink)
        assertEquals("song1.mp3", e1.linkTarget)

        val e2 = entries[1]
        assertEquals("02. Artist B - Song Two.flac", e2.name)
        assertTrue(e2.isSymbolicLink)
        assertEquals("song2.flac", e2.linkTarget)

        // Test stream opening via ArchiveReader
        val stream1 = reader.openEntryStream(m3uFile.name, e1.path, { m3uFile.inputStream() }, m3uFile)
        val read1 = stream1.use { it.bufferedReader().readText() }
        assertEquals("PCM_SONG_1_DATA", read1)

        val stream2 = reader.openEntryStream(m3uFile.name, e2.path, { m3uFile.inputStream() }, m3uFile)
        val read2 = stream2.use { it.bufferedReader().readText() }
        assertEquals("PCM_SONG_2_DATA", read2)
    }

    @Test
    fun testArchiveStorageProviderPlaylistResolveLink() {
        val rootDir = tempFolder.newFolder("music")
        val audio = File(rootDir, "sample.wav").apply { writeText("WAV_DATA") }
        val plsFile = File(rootDir, "playlist.pls").apply {
            writeText(
                """
                [playlist]
                File1=sample.wav
                Title1=Sample Wave
                Length1=10
                NumberOfEntries=1
                Version=2
                """.trimIndent()
            )
        }

        val provider = ArchiveStorageProvider(
            context = TestContext(tempFolder.newFolder("context_cache")),
            customCacheDir = tempFolder.newFolder("archive_cache")
        )

        val playlistSourceUri = TestUri("file://${plsFile.absolutePath}")
        val playlistRootRef = ResourceRef(provider.id, ArchiveUri.build(playlistSourceUri, "").toString())

        val listResult = provider.list(playlistRootRef)
        assertTrue("Expected list success: $listResult", listResult is ProviderResult.Success)
        val items = (listResult as ProviderResult.Success).value
        assertEquals(1, items.size)

        val trackItem = items[0]
        assertEquals("01. Sample Wave.wav", trackItem.metadata.name)
        assertTrue(trackItem.metadata.isSymbolicLink)
        assertEquals("sample.wav", trackItem.metadata.linkTarget)

        // Resolve link
        val linkResult = provider.resolveLink(trackItem.ref)
        assertTrue("Resolve link should succeed: $linkResult", linkResult is ProviderResult.Success)
        val resolvedRef = (linkResult as ProviderResult.Success).value
        assertEquals("file://${audio.canonicalPath}", resolvedRef.documentId)
    }

    @Test
    fun testArchiveStorageProviderAudioSiblingResolutionFallback() {
        val root = tempFolder.newFolder("fixtures")
        val audioDir = File(root, "audio").apply { mkdirs() }
        val playlistsDir = File(root, "playlists").apply { mkdirs() }

        val audio = File(audioDir, "c_major_piano.wav").apply { writeText("RIFF_WAV_BYTES") }
        val m3uFile = File(playlistsDir, "c_major_piano.m3u").apply {
            writeText("#EXTM3U\n#EXTINF:2,Piano\n../audio/c_major_piano.wav\n")
        }

        val provider = ArchiveStorageProvider(
            context = TestContext(tempFolder.newFolder("context_cache")),
            customCacheDir = tempFolder.newFolder("archive_cache")
        )

        val playlistSourceUri = TestUri("file://${m3uFile.absolutePath}")
        val playlistRootRef = ResourceRef(provider.id, ArchiveUri.build(playlistSourceUri, "").toString())

        val listResult = provider.list(playlistRootRef)
        assertTrue(listResult is ProviderResult.Success)
        val items = (listResult as ProviderResult.Success).value
        assertEquals(1, items.size)

        val linkResult = provider.resolveLink(items[0].ref)
        assertTrue(linkResult is ProviderResult.Success)
        val resolvedRef = (linkResult as ProviderResult.Success).value
        assertEquals("file://${audio.canonicalPath}", resolvedRef.documentId)
    }
}
