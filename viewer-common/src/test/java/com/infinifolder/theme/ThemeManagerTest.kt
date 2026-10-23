package com.infinifolder.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeManagerTest {

    @Test
    fun testThemeModeEnum() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.valueOf("SYSTEM"))
        assertEquals(ThemeMode.DARK, ThemeMode.valueOf("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.valueOf("LIGHT"))
    }

    @Test
    fun testPresetThemesIntegrity() {
        val darkModern = ThemeColors.modern(true)
        assertTrue(darkModern.isDark)
        assertEquals(AppThemeId.MODERN, darkModern.id)

        val lightModern = ThemeColors.modern(false)
        assertTrue(!lightModern.isDark)
        assertEquals(AppThemeId.MODERN, lightModern.id)

        val winamp = ThemeColors.WINAMP_CLASSIC
        assertEquals(AppThemeId.WINAMP_CLASSIC, winamp.id)
        assertTrue(winamp.fontMonospace)
        assertEquals(0xFF00FF00.toInt(), winamp.textPrimary) // Neon green
        assertTrue(winamp.spectrumColors.isNotEmpty())

        val wmp = ThemeColors.WMP_GLASS
        assertEquals(AppThemeId.WMP_GLASS, wmp.id)
        assertEquals(14, wmp.cornerRadiusDp)
        assertEquals(0xFFF0F8FF.toInt(), wmp.textPrimary) // Ice white

        val foobar = ThemeColors.FOOBAR2000
        assertEquals(AppThemeId.FOOBAR2000, foobar.id)
        assertTrue(foobar.fontMonospace)
        assertEquals(2, foobar.cornerRadiusDp)
    }

    @Test
    fun testWinampSkinParserWithVisColorAndPledit() {
        val visColorContent = """
            25,25,35
            0,255,0
            255,0,0
            255,50,0
            255,100,0
            255,150,0
            255,200,0
            255,255,0
            200,255,0
            150,255,0
            100,255,0
            50,255,0
            0,255,0
            0,255,100
            0,255,200
            0,200,255
            0,100,255
            0,0,255
            0,255,128
        """.trimIndent()

        val pleditContent = """
            [Options]
            Normal=#00FF66
            Current=#FFCC00
            NormalBG=#1A1A22
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("viscolor.txt"))
            zos.write(visColorContent.toByteArray(Charsets.ISO_8859_1))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("pledit.txt"))
            zos.write(pleditContent.toByteArray(Charsets.ISO_8859_1))
            zos.closeEntry()
        }

        val skinStream = ByteArrayInputStream(baos.toByteArray())
        val parsed = WinampSkinParser.parseSkinFromStream(skinStream, "BentoRetro")

        assertNotNull(parsed)
        assertEquals("BentoRetro", parsed?.name)
        val colors = parsed?.themeColors
        assertNotNull(colors)
        assertEquals(AppThemeId.CUSTOM_WSZ, colors?.id)
        assertEquals(0xFF00FF66.toInt(), colors?.textPrimary)
        assertEquals(0xFFFFCC00.toInt(), colors?.textSecondary)
        assertEquals(0xFF1A1A22.toInt(), colors?.background)
        assertTrue((colors?.spectrumColors?.size ?: 0) >= 16)
    }
}
