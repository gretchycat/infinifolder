package com.infinifolder.audio.cddb

import com.infinifolder.playlist.PlaylistParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CddbManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCddbDiscIdCalculation() {
        // Track 1 at 00:00:00 (0 frames), Track 2 at 00:00:38 (38 frames), leadout at 112 frames
        val discId = PlaylistParser.calculateCddbDiscId(listOf(0, 38), leadoutFrames = 112)
        assertEquals("04000102", discId)
    }

    @Test
    fun testXmcdParsing() {
        val xmcd = """
            # xmcd CD database file
            DISCID=04000102
            DTITLE=InfiniFolder / C Major Piano Chord Suite
            DYEAR=2026
            DGENRE=Classical
            TTITLE0=C Major Attack
            TTITLE1=C Major Sustain & Decay
        """.trimIndent()

        val record = CddbManager.parseXmcd(xmcd)
        assertNotNull(record)
        assertEquals("04000102", record!!.discId)
        assertEquals("InfiniFolder", record.artist)
        assertEquals("C Major Piano Chord Suite", record.album)
        assertEquals("2026", record.year)
        assertEquals("Classical", record.genre)
        assertEquals(2, record.trackTitles.size)
        assertEquals("C Major Attack", record.trackTitles[0])
        assertEquals("C Major Sustain & Decay", record.trackTitles[1])
    }

    @Test
    fun testFindLocalCddbInDirectory() {
        val folder = tempFolder.newFolder("album")
        File(folder, "cddb.toc").writeText(
            """
            # xmcd CD database file
            DISCID=04000102
            DTITLE=InfiniFolder / C Major Piano Chord Suite
            TTITLE0=C Major Attack
            TTITLE1=C Major Sustain & Decay
            """.trimIndent()
        )

        val record = CddbManager.findLocalCddb(folder, "04000102")
        assertNotNull(record)
        assertEquals("InfiniFolder", record!!.artist)
        assertEquals("C Major Piano Chord Suite", record.album)
    }
}
