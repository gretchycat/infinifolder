package com.infinifolder.theme

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import java.util.Locale
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
        val file: File? = null,
        val artwork: Map<String, ByteArray> = emptyMap()
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
        val artwork = mutableMapOf<String, ByteArray>()
        var entryCount = 0
        var expandedBytes = 0

        try {
            ZipArchiveInputStream(inputStream).use { zis ->
                var entry: ZipArchiveEntry? = zis.nextZipEntry
                while (entry != null) {
                    if (++entryCount > 512) return null
                    val entryName = entry.name.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
                    if (!entry.isDirectory && entryName in setOf("viscolor.txt", "pledit.txt", "main.bmp", "gen.bmp", "cbuttons.bmp", "titlebar.bmp")) {
                        val bytes = readEntry(zis)
                        expandedBytes += bytes.size
                        if (expandedBytes > 24 * 1024 * 1024) return null
                        when (entryName) {
                            "viscolor.txt" -> visColorText = String(bytes, StandardCharsets.ISO_8859_1)
                            "pledit.txt" -> pleditText = String(bytes, StandardCharsets.ISO_8859_1)
                            else -> artwork[entryName] = bytes
                        }
                    }
                    entry = zis.nextZipEntry
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (visColorText == null && pleditText == null && artwork.isEmpty()) return null
        val colors = parseColors(skinName, visColorText, pleditText, artwork)
        return ParsedSkin(skinName, colors, artwork = artwork)
    }

    private fun readEntry(zis: ZipArchiveInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var r: Int
        while (zis.read(buf).also { r = it } > 0) {
            if (baos.size() + r > 8 * 1024 * 1024) throw java.io.IOException("Skin entry is too large")
            baos.write(buf, 0, r)
        }
        return baos.toByteArray()
    }

    private fun parseColors(skinName: String, visText: String?, pleditText: String?, artwork: Map<String, ByteArray>): ThemeColors {
        var bg = 0xFF1B1B22.toInt()
        var textPrimary = 0xFF00FF00.toInt()
        var textSecondary = 0xFFFFB000.toInt()
        var selectedBackground: Int? = null
        var monospace = false
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
                val clean = line.substringBefore("//").substringBefore(';').trim()
                val key = clean.substringBefore('=').trim().lowercase(Locale.ROOT)
                val value = clean.substringAfter('=', "").trim()
                when (key) {
                    "normal" -> parseHexOrRgb(value)?.let { textPrimary = it }
                    "current" -> parseHexOrRgb(value)?.let { textSecondary = it }
                    "normalbg" -> parseHexOrRgb(value)?.let { bg = it }
                    "selectedbg" -> selectedBackground = parseHexOrRgb(value)
                    "font" -> monospace = value.contains("mono", true) || value.contains("courier", true) || value.contains("console", true)
                }
            }
        }

        val spectrumArray = if (spectrum.size >= 8) spectrum.toIntArray() else ThemeColors.WINAMP_CLASSIC.spectrumColors

        val toolbar = artwork["main.bmp"]?.let { bitmapColor(it, true) } ?: lightenOrDarken(bg, 1.25f)
        val surface = artwork["gen.bmp"]?.let { bitmapColor(it) } ?: lightenOrDarken(bg, 1.2f)
        val button = artwork["cbuttons.bmp"]?.let { bitmapColor(it) } ?: surface
        val accent = selectedBackground ?: textSecondary
        return ThemeColors(
            id = AppThemeId.CUSTOM_WSZ,
            name = skinName,
            description = "Imported Winamp palette",
            background = bg,
            surface = surface,
            cardBackground = bg,
            cardStroke = lightenOrDarken(surface, 1.4f),
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textHighlight = textPrimary,
            accent = accent,
            accentGlow = (0x66000000 or (accent and 0x00FFFFFF)),
            toolbarBackground = toolbar,
            toolbarBorder = lightenOrDarken(toolbar, 1.5f),
            statusBackground = bg,
            buttonBackground = button,
            buttonText = readable(textPrimary, button),
            isDark = luminance(bg) < 0.5,
            fontMonospace = monospace,
            cornerRadiusDp = 3,
            spectrumColors = spectrumArray,
            selectionBackground = selectedBackground ?: accent,
            selectionText = readable(textPrimary, selectedBackground ?: accent),
            toolbarText = readable(textPrimary, toolbar)
        )
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((color ushr shift) and 255) / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    private fun readable(preferred: Int, background: Int): Int {
        val a = luminance(preferred); val b = luminance(background)
        if ((maxOf(a, b) + .05) / (minOf(a, b) + .05) >= 4.5) return preferred
        return if (b > .179) Color.BLACK else Color.WHITE
    }

    private fun bitmapColor(bytes: ByteArray, titleStrip: Boolean = false): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 2_000_000) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val colors = mutableMapOf<Int, Int>()
            val height = if (titleStrip) minOf(bitmap.height, 14) else bitmap.height
            for (y in 0 until height) for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                if (Color.alpha(c) < 128 || (Color.red(c) > 240 && Color.blue(c) > 240 && Color.green(c) < 16)) continue
                val bucket = Color.rgb(Color.red(c) and 0xF8, Color.green(c) and 0xF8, Color.blue(c) and 0xF8)
                colors[bucket] = (colors[bucket] ?: 0) + 1
            }
            colors.maxByOrNull { it.value }?.key
        } finally { bitmap.recycle() }
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
        val v = value.substringBefore("//").substringBefore(';').trim().removePrefix("#").removePrefix("0x")
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
        val r = (((color ushr 16) and 0xFF) * factor + (factor - 1f) * 40).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * factor + (factor - 1f) * 40).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor + (factor - 1f) * 40).toInt().coerceIn(0, 255)
        return argb(a, r, g, b)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

}
