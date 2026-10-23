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

    private var initialized = false

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
        val savedThemeId = shared.getString(PREF_ACTIVE_THEME, AppThemeId.MODERN.name)
        val isDark = explicitDark ?: resolveIsDark(context)
        activeTheme = when (savedThemeId) {
            AppThemeId.WINAMP_CLASSIC.name -> ThemeColors.WINAMP_CLASSIC
            AppThemeId.WMP_GLASS.name -> ThemeColors.WMP_GLASS
            AppThemeId.FOOBAR2000.name -> ThemeColors.FOOBAR2000
            AppThemeId.CUSTOM_WSZ.name -> {
                val skinPath = shared.getString(PREF_CUSTOM_SKIN_FILE, null)
                if (skinPath != null && File(skinPath).exists()) {
                    WinampSkinParser.parseSkin(context, Uri.fromFile(File(skinPath)))?.themeColors
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

    fun setTheme(context: Context, theme: ThemeColors, skinFile: File? = null) {
        activeTheme = theme
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        shared.edit().apply {
            putString(PREF_ACTIVE_THEME, theme.id.name)
            if (skinFile != null) {
                putString(PREF_CUSTOM_SKIN_FILE, skinFile.absolutePath)
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

    fun importSkin(context: Context, uri: Uri): ThemeColors? {
        val parsed = WinampSkinParser.parseSkin(context, uri) ?: return null
        val skinsDir = File(context.filesDir, "wsz_skins").apply { mkdirs() }
        val destFile = File(skinsDir, "${System.currentTimeMillis()}_${parsed.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}.wsz")

        try {
            MediaSourceAdapter.openStream(context, uri).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            setTheme(context, parsed.themeColors, destFile)
            return parsed.themeColors
        } catch (_: Exception) {
            setTheme(context, parsed.themeColors)
            return parsed.themeColors
        }
    }

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

    fun createToolbarDrawable(theme: ThemeColors, density: Float): GradientDrawable {
        val dpStroke = (1 * density).toInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.toolbarBackground)
            setStroke(dpStroke, theme.toolbarBorder)
        }
    }
}
