package com.infinifolder.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import com.infinifolder.viewer.MediaSourceAdapter
import java.io.File
import java.util.Locale

interface ThemeChangeListener {
    fun onThemeChanged(theme: ThemeColors)
}

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

object ThemeManager {

    private const val PREF_ACTIVE_THEME = "active_system_theme"
    private const val PREF_CUSTOM_SKIN_FILE = "active_custom_skin_file"
    const val PREF_THEME_MODE = "theme_mode"

    private val listeners = mutableListOf<ThemeChangeListener>()

    @Volatile
    var activeTheme: ThemeColors = ThemeColors.modern(true)
        private set

    var smoothSkinScaling: Boolean = false
        private set

    fun setSmoothSkinScaling(context: Context, smooth: Boolean) {
        smoothSkinScaling = smooth
        context.getSharedPreferences("browser", Context.MODE_PRIVATE).edit()
            .putBoolean("skin_smooth_scaling", smooth).apply()
        synchronized(listeners) { listeners.toList().forEach { it.onThemeChanged(activeTheme) } }
    }

    private var initialized = false
    private var skinArtwork: Map<String, ByteArray> = emptyMap()
    private val skinBitmaps = mutableMapOf<String, android.graphics.Bitmap>()

    fun isSystemInDarkMode(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun getThemeMode(context: Context): ThemeMode {
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        val modeStr = shared.getString(PREF_THEME_MODE, null)
        if (modeStr != null) {
            return try {
                ThemeMode.valueOf(modeStr.uppercase(Locale.ROOT))
            } catch (_: Exception) {
                ThemeMode.SYSTEM
            }
        }
        return ThemeMode.SYSTEM
    }

    fun resolveIsDark(context: Context): Boolean {
        return when (getThemeMode(context)) {
            ThemeMode.SYSTEM -> isSystemInDarkMode(context)
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    }

    fun init(context: Context, explicitDark: Boolean? = null) {
        if (initialized) return
        initialized = true
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        smoothSkinScaling = shared.getBoolean("skin_smooth_scaling", false)
        val savedThemeId = shared.getString(PREF_ACTIVE_THEME, AppThemeId.MODERN.name)
        val isDark = explicitDark ?: resolveIsDark(context)
        activeTheme = when (savedThemeId) {
            AppThemeId.WINAMP_CLASSIC.name -> ThemeColors.WINAMP_CLASSIC
            AppThemeId.WMP_GLASS.name -> ThemeColors.WMP_GLASS
            AppThemeId.FOOBAR2000.name -> ThemeColors.FOOBAR2000
            AppThemeId.CUSTOM_WSZ.name -> {
                val skinPath = shared.getString(PREF_CUSTOM_SKIN_FILE, null)
                if (skinPath != null && File(skinPath).exists()) {
                    WinampSkinParser.parseSkin(context, Uri.fromFile(File(skinPath)), shared.getString("custom_skin_name", null))?.let { parsed ->
                        skinArtwork = parsed.artwork
                        parsed.themeColors
                    }
                        ?: ThemeColors.WINAMP_CLASSIC
                } else {
                    ThemeColors.WINAMP_CLASSIC
                }
            }
            else -> ThemeColors.modern(isDark)
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        shared.edit().putString(PREF_THEME_MODE, mode.name.lowercase(Locale.ROOT)).apply()
        val isDark = when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkMode(context)
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        updateDarkMode(context, isDark)
    }

    fun handleConfigurationChanged(context: Context, newConfig: Configuration) {
        if (getThemeMode(context) == ThemeMode.SYSTEM && activeTheme.id == AppThemeId.MODERN) {
            val isNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (activeTheme.isDark != isNight) {
                activeTheme = ThemeColors.modern(isNight)
                synchronized(listeners) {
                    for (l in listeners) l.onThemeChanged(activeTheme)
                }
            }
        }
    }

    fun addListener(listener: ThemeChangeListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: ThemeChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun setTheme(context: Context, theme: ThemeColors, skinFile: File? = null, artwork: Map<String, ByteArray> = emptyMap()) {
        skinArtwork = artwork
        skinBitmaps.clear()
        activeTheme = theme
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        shared.edit().apply {
            putString(PREF_ACTIVE_THEME, theme.id.name)
            if (skinFile != null) {
                putString(PREF_CUSTOM_SKIN_FILE, skinFile.absolutePath)
                putString("custom_skin_name", theme.name)
            } else if (theme.id != AppThemeId.CUSTOM_WSZ) {
                remove(PREF_CUSTOM_SKIN_FILE)
            }
        }.apply()

        synchronized(listeners) {
            for (l in listeners) l.onThemeChanged(theme)
        }
    }

    fun updateDarkMode(context: Context, isDark: Boolean) {
        if (activeTheme.id == AppThemeId.MODERN) {
            setTheme(context, ThemeColors.modern(isDark))
        }
    }

    fun listPresetThemes(isDark: Boolean = true): List<ThemeColors> {
        return listOf(
            ThemeColors.modern(isDark),
            ThemeColors.WINAMP_CLASSIC,
            ThemeColors.WMP_GLASS,
            ThemeColors.FOOBAR2000
        )
    }

    /** Stages and validates one private copy before publishing the new theme. Call from a worker. */
    fun importSkin(context: Context, uri: Uri): ThemeColors? {
        val skinsDir = File(context.filesDir, "wsz_skins").apply { mkdirs() }
        val destFile = File.createTempFile("skin-", ".wsz", skinsDir)
        return try {
            MediaSourceAdapter.openStream(context, uri).use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 32 * 1024 * 1024) throw java.io.IOException("Skin archive exceeds 32 MiB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val name = if (uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } else uri.lastPathSegment
            val parsed = destFile.inputStream().use {
                WinampSkinParser.parseSkinFromStream(it, name?.substringBeforeLast('.') ?: "Winamp Skin")
            } ?: throw java.io.IOException("No supported classic Winamp skin resources found")
            android.os.Handler(context.mainLooper).post {
                setTheme(context, parsed.themeColors, destFile, parsed.artwork)
            }
            parsed.themeColors
        } catch (error: Exception) {
            destFile.delete()
            android.util.Log.w("WinampSkin", "Import failed", error)
            null
        }
    }

    private fun skinBitmap(name: String): android.graphics.Bitmap? {
        if (activeTheme.id != AppThemeId.CUSTOM_WSZ) return null
        skinBitmaps[name]?.let { return it }
        val bytes = skinArtwork[name] ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 2_000_000) return null
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { skinBitmaps[name] = it }
    }

    fun createFloatingFrameDrawable(theme: ThemeColors, density: Float): android.graphics.drawable.Drawable =
        WinampChromeDrawable(theme.background, theme.cardStroke, density, skinBitmap("main.bmp"), null, smoothSkinScaling)

    fun createCardDrawable(theme: ThemeColors, density: Float): GradientDrawable {
        val dpCorner = (theme.cornerRadiusDp * density)
        val dpStroke = (1 * density).toInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpCorner
            setColor(theme.cardBackground)
            setStroke(dpStroke, theme.cardStroke)
        }
    }

    fun createButtonDrawable(theme: ThemeColors, density: Float, isAccent: Boolean = false): GradientDrawable {
        val dpCorner = (theme.cornerRadiusDp * density)
        val dpStroke = (1 * density).toInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpCorner
            if (isAccent) {
                setColor(theme.accent)
                setStroke(dpStroke, theme.accentGlow)
            } else {
                setColor(theme.buttonBackground)
                setStroke(dpStroke, theme.cardStroke)
            }
        }
    }

    fun createToolbarDrawable(theme: ThemeColors, density: Float): android.graphics.drawable.Drawable {
        val title = skinBitmap("titlebar.bmp")
        if (title != null && theme.id == AppThemeId.CUSTOM_WSZ) {
            return WinampChromeDrawable(theme.toolbarBackground, theme.toolbarBorder, density, null, title, smoothSkinScaling)
        }
        val dpStroke = (1 * density).toInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.toolbarBackground)
            setStroke(dpStroke, theme.toolbarBorder)
        }
    }
}
