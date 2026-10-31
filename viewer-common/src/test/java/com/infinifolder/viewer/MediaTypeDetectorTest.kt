package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypeDetectorTest {

    @Test
    fun testDetectImages() {
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("photo.jpg", "image/jpeg"))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("photo.jpeg", null))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("image.png", null))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("graphic.svg", "image/svg+xml"))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("animated.gif", null))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("modern.webp", null))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("picture.avif", "image/avif"))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("nextgen.jxl", null))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("raw.dng", null))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("apple.heic", null))
    }

    @Test
    fun testDetectMarkdown() {
        assertEquals(MediaType.MARKDOWN, MediaTypeDetector.detect("README.md", "text/markdown"))
        assertEquals(MediaType.MARKDOWN, MediaTypeDetector.detect("DOCUMENTATION.markdown", null))
        assertEquals(MediaType.MARKDOWN, MediaTypeDetector.detect("notes.mdown", null))
    }

    @Test
    fun testDetectHtml() {
        assertEquals(MediaType.HTML, MediaTypeDetector.detect("index.html", "text/html"))
        assertEquals(MediaType.HTML, MediaTypeDetector.detect("page.htm", null))
        assertEquals(MediaType.HTML, MediaTypeDetector.detect("help.xhtml", "application/xhtml+xml"))
    }

    @Test
    fun testDetectText() {
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("notes.txt", "text/plain"))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("server.log", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("data.json", "application/json"))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("layout.xml", "text/xml"))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("config.yaml", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("config.yml", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("script.py", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("Main.kt", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("App.java", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("deploy.sh", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("styles.css", "text/css"))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("app.js", "application/javascript"))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect(".gitignore", null))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect(".bashrc", null))
    }

    @Test
    fun testDetectAudio() {
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("song.mp3", "audio/mpeg"))
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("lossless.flac", "audio/flac"))
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("voice.wav", "audio/wav"))
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("track.ogg", "audio/ogg"))
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("music.m4a", null))
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("voice.opus", null))
        assertEquals(MediaType.AUDIO, MediaTypeDetector.detect("ringtone.aac", null))
    }

    @Test
    fun testDetectVideo() {
        assertEquals(MediaType.VIDEO, MediaTypeDetector.detect("movie.mp4", "video/mp4"))
        assertEquals(MediaType.VIDEO, MediaTypeDetector.detect("clip.mkv", "video/x-matroska"))
        assertEquals(MediaType.VIDEO, MediaTypeDetector.detect("stream.webm", "video/webm"))
        assertEquals(MediaType.VIDEO, MediaTypeDetector.detect("recording.avi", null))
        assertEquals(MediaType.VIDEO, MediaTypeDetector.detect("film.mov", null))
    }

    @Test
    fun testDetectPdf() {
        assertEquals(MediaType.PDF, MediaTypeDetector.detect("manual.pdf", "application/pdf"))
        assertEquals(MediaType.PDF, MediaTypeDetector.detect("book.PDF", null))
        assertTrue(MediaTypeDetector.isViewableInternally("document.pdf"))
    }

    @Test
    fun testDetectEpub() {
        assertEquals(MediaType.EPUB, MediaTypeDetector.detect("novel.epub", "application/epub+zip"))
        assertEquals(MediaType.EPUB, MediaTypeDetector.detect("ebook.EPUB", null))
        assertTrue(MediaTypeDetector.isViewableInternally("novel.epub"))
    }

    @Test
    fun testDetectSpreadsheet() {
        assertEquals(MediaType.SPREADSHEET, MediaTypeDetector.detect("budget.csv", "text/csv"))
        assertEquals(MediaType.SPREADSHEET, MediaTypeDetector.detect("export.tsv", "text/tab-separated-values"))
        assertEquals(MediaType.SPREADSHEET, MediaTypeDetector.detect("data.CSV", null))
        assertTrue(MediaTypeDetector.isViewableInternally("data.csv"))
        assertTrue(MediaTypeDetector.isEditable(MediaType.SPREADSHEET))
    }

    @Test
    fun testDetectArchivesAndContainers() {
        assertEquals(MediaType.ARCHIVE_OR_CONTAINER, MediaTypeDetector.detect("archive.zip", "application/zip"))
        assertEquals(MediaType.ARCHIVE_OR_CONTAINER, MediaTypeDetector.detect("bundle.tar.gz", null))
        assertEquals(MediaType.ARCHIVE_OR_CONTAINER, MediaTypeDetector.detect("disk.iso", null))
        assertEquals(MediaType.ARCHIVE_OR_CONTAINER, MediaTypeDetector.detect("system.img", null))
    }

    @Test
    fun testDetectUnsupportedBinary() {
        assertEquals(MediaType.UNSUPPORTED, MediaTypeDetector.detect("program.exe", "application/octet-stream"))
        assertEquals(MediaType.UNSUPPORTED, MediaTypeDetector.detect("unknown.xyz", null))
        assertEquals(MediaType.UNSUPPORTED, MediaTypeDetector.detect("data.dat", null))
    }

    @Test
    fun testIsEditable() {
        assertTrue(MediaTypeDetector.isEditable(MediaType.TEXT))
        assertTrue(MediaTypeDetector.isEditable(MediaType.MARKDOWN))
        assertTrue(MediaTypeDetector.isEditable(MediaType.HTML))
        assertTrue(MediaTypeDetector.isEditable(MediaType.IMAGE))
        assertFalse(MediaTypeDetector.isEditable(MediaType.AUDIO))
        assertFalse(MediaTypeDetector.isEditable(MediaType.VIDEO))
        assertFalse(MediaTypeDetector.isEditable(MediaType.ARCHIVE_OR_CONTAINER))
    }

    @Test fun looseCsvMimeAliasesReachTheSameGridAsArchiveEntries() {
        for (mime in listOf("text/comma-separated-values", "text/x-comma-separated-values", "text/x-csv", "application/csv", "application/x-csv", "text/plain", "application/vnd.ms-excel")) {
            assertEquals(mime, MediaType.SPREADSHEET, MediaTypeDetector.detect("01-edit-roundtrip.csv", mime))
        }
        assertEquals(MediaType.SPREADSHEET, MediaTypeDetector.detect("02-tabs.tsv", "text/plain"))
        assertEquals(MediaType.IMAGE, MediaTypeDetector.detect("misnamed.csv", "image/png"))
        assertEquals(MediaType.HTML, MediaTypeDetector.detect("misnamed.csv", "text/html"))
        assertEquals(MediaType.TEXT, MediaTypeDetector.detect("notes.txt", "text/plain"))
    }
}
