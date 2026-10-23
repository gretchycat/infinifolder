package com.infinifolder.theme

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.infinifolder.viewer.MediaSourceAdapter
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

object WinampSkinParser {

    data class ParsedSkin(
        val name: String,
        val themeColors: ThemeColors,
        val file: File? = null
    )

    fun parseSkin(context: Context, uri: Uri, skinName: String? = null): ParsedSkin? {
        val stream = try {
            MediaSourceAdapter.openStream(context, uri)
        } catch (_: Exception) {
            return null
        }
        val name = skinName ?: uri.lastPathSegment?.substringBeforeLast('.') ?: "Winamp Skin"
        return parseSkinFromStream(stream, name)
    }

    fun parseSkinFromStream(inputStream: InputStream, skinName: String): ParsedSkin? {
        var visColorText: String? = null
        var pleditText: String? = null

        try {
            ZipArchiveInputStream(inputStream).use { zis ->
                var entry: ZipArchiveEntry? = zis.nextZipEntry
                while (entry != null) {
                    val entryName = entry.name.lowercase()
                    if (entryName.endsWith("viscolor.txt")) {
                        visColorText = readEntryText(zis)
                    } else if (entryName.endsWith("pledit.txt")) {
                        pleditText = readEntryText(zis)
                    }
                    entry = zis.nextZipEntry
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (visColorText == null && pleditText == null) {
            // If neither text file is present, generate a classic retro fallback
            return ParsedSkin(skinName, AppThemeColorsFallback(skinName))
        }

        val colors = parseColors(skinName, visColorText, pleditText)
        return ParsedSkin(skinName, colors)
    }

    private fun readEntryText(zis: ZipArchiveInputStream): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var r: Int
        while (zis.read(buf).also { r = it } > 0) {
            baos.write(buf, 0, r)
        }
        return String(baos.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun parseColors(skinName: String, visText: String?, pleditText: String?): ThemeColors {
        var bg = 0xFF1B1B22.toInt()
        var textPrimary = 0xFF00FF00.toInt()
        var textSecondary = 0xFFFFB000.toInt()
        val spectrum = mutableListOf<Int>()

        if (!visText.isNullOrBlank()) {
            val lines = visText.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
            val parsedRgb = lines.mapNotNull { parseRgbLine(it) }

            if (parsedRgb.isNotEmpty()) {
                bg = parsedRgb[0]
            }
            if (parsedRgb.size > 2) {
                // Lines 2 to 17 are 16 spectrum analyzer colors
                val end = minOf(parsedRgb.size, 18)
                for (i in 2 until end) {
                    spectrum.add(parsedRgb[i])
                }
            }
            if (parsedRgb.size > 18) {
                // Oscilloscope color
                textPrimary = parsedRgb[18]
            }
        }

        if (!pleditText.isNullOrBlank()) {
            for (line in pleditText.lines()) {
                val clean = line.trim()
                if (clean.startsWith("Normal=", ignoreCase = true)) {
                    parseHexOrRgb(clean.substringAfter('='))?.let { textPrimary = it }
                } else if (clean.startsWith("Current=", ignoreCase = true)) {
                    parseHexOrRgb(clean.substringAfter('='))?.let { textSecondary = it }
                } else if (clean.startsWith("NormalBG=", ignoreCase = true)) {
                    parseHexOrRgb(clean.substringAfter('='))?.let { bg = it }
                }
            }
        }

        val spectrumArray = if (spectrum.size >= 8) spectrum.toIntArray() else ThemeColors.WINAMP_CLASSIC.spectrumColors

        return ThemeColors(
            id = AppThemeId.CUSTOM_WSZ,
            name = skinName,
            description = "Custom Winamp (.wsz) Skin",
            background = bg,
            surface = lightenOrDarken(bg, 1.2f),
            cardBackground = lightenOrDarken(bg, 1.15f),
            cardStroke = lightenOrDarken(bg, 1.4f),
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textHighlight = textPrimary,
            accent = textPrimary,
            accentGlow = (0x66000000 or (textPrimary and 0x00FFFFFF)),
            toolbarBackground = lightenOrDarken(bg, 1.25f),
            toolbarBorder = lightenOrDarken(bg, 1.5f),
            statusBackground = bg,
            buttonBackground = lightenOrDarken(bg, 1.3f),
            buttonText = textPrimary,
            isDark = true,
            fontMonospace = true,
            cornerRadiusDp = 3,
            spectrumColors = spectrumArray
        )
    }

    private fun parseRgbLine(line: String): Int? {
        val parts = line.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (parts.size >= 3) {
            val r = parts[0].toIntOrNull() ?: return null
            val g = parts[1].toIntOrNull() ?: return null
            val b = parts[2].toIntOrNull() ?: return null
            return argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
        return null
    }

    private fun parseHexOrRgb(value: String): Int? {
        val v = value.trim().removePrefix("#")
        return try {
            if (v.length == 6) {
                val r = v.substring(0, 2).toInt(16)
                val g = v.substring(2, 4).toInt(16)
                val b = v.substring(4, 6).toInt(16)
                argb(255, r, g, b)
            } else {
                parseRgbLine(v)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun lightenOrDarken(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = (((color ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return argb(a, r, g, b)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun AppThemeColorsFallback(skinName: String): ThemeColors = ThemeColors(
        id = AppThemeId.CUSTOM_WSZ,
        name = skinName,
        description = "Winamp Imported Skin",
        background = 0xFF222228.toInt(),
        surface = 0xFF2C2C36.toInt(),
        cardBackground = 0xFF282832.toInt(),
        cardStroke = 0xFF4A4A5A.toInt(),
        textPrimary = 0xFF00FF66.toInt(),
        textSecondary = 0xFFFFCC00.toInt(),
        textHighlight = 0xFF00EEFF.toInt(),
        accent = 0xFF00FF66.toInt(),
        accentGlow = 0x6600FF66,
        toolbarBackground = 0xFF30303C.toInt(),
        toolbarBorder = 0xFF58586E.toInt(),
        statusBackground = 0xFF1C1C22.toInt(),
        buttonBackground = 0xFF383848.toInt(),
        buttonText = 0xFF00FF66.toInt(),
        isDark = true,
        fontMonospace = true,
        cornerRadiusDp = 3
    )
}
