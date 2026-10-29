package com.infinifolder.theme

import android.app.AlertDialog
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
    private const val PREF_SKIN_ASSET_PATH = "active_skin_asset_path"
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
    var skinArtwork: Map<String, ByteArray> = emptyMap()
        private set
    var bundledSkinArtwork: Map<String, ByteArray> = emptyMap()
        private set
    var bundledSkinColors: ThemeColors? = null
        private set
    var activeAudioPlayerTheme: ThemeColors? = null
        private set
    private var audioArtwork: Map<String, ByteArray> = emptyMap()
    private val skinBitmaps = mutableMapOf<String, android.graphics.Bitmap>()
    private var cachedPlaylistTitlebar: android.graphics.Bitmap? = null
    private var cachedPlaylistBottomBar: android.graphics.Bitmap? = null
    private var cachedPlaylistLeftTile: android.graphics.Bitmap? = null
    private var cachedPlaylistRightTile: android.graphics.Bitmap? = null

    fun clearSkinBitmaps() {
        skinBitmaps.clear()
        cachedPlaylistTitlebar = null
        cachedPlaylistBottomBar = null
        cachedPlaylistLeftTile = null
        cachedPlaylistRightTile = null
    }

    const val PREF_AUDIO_PLAYER_THEME = "audio_player_theme"

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

    fun getDialogTheme(context: Context): Int {
        val isDark = resolveIsDark(context)
        return if (isDark) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
        }
    }

    fun createDialogBuilder(context: Context): AlertDialog.Builder {
        return AlertDialog.Builder(context, getDialogTheme(context))
    }

    fun init(context: Context, explicitDark: Boolean? = null) {
        if (initialized) return
        initialized = true
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        smoothSkinScaling = shared.getBoolean("skin_smooth_scaling", false)
        ensureBundledSkinLoaded(context)
        val savedThemeId = shared.getString(PREF_ACTIVE_THEME, AppThemeId.MODERN.name)
        val isDark = explicitDark ?: resolveIsDark(context)
        activeTheme = when (savedThemeId) {
            AppThemeId.WINAMP_CLASSIC.name -> {
                skinArtwork = bundledSkinArtwork
                bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
            }
            AppThemeId.WMP_GLASS.name -> ThemeColors.WMP_GLASS
            AppThemeId.FOOBAR2000.name -> ThemeColors.FOOBAR2000
            AppThemeId.CUSTOM_WSZ.name -> {
                val skinPath = shared.getString(PREF_CUSTOM_SKIN_FILE, null)
                val skinAsset = shared.getString(PREF_SKIN_ASSET_PATH, null)
                if (skinPath != null && File(skinPath).exists()) {
                    WinampSkinParser.parseSkin(context, Uri.fromFile(File(skinPath)), shared.getString("custom_skin_name", null))?.let { parsed ->
                        skinArtwork = parsed.artwork
                        parsed.themeColors
                    } ?: run {
                        skinArtwork = bundledSkinArtwork
                        bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                    }
                } else if (!skinAsset.isNullOrEmpty()) {
                    loadBundledSkin(context, skinAsset)?.let { parsed ->
                        skinArtwork = parsed.artwork
                        parsed.themeColors
                    } ?: run {
                        skinArtwork = bundledSkinArtwork
                        bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                    }
                } else {
                    skinArtwork = bundledSkinArtwork
                    bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                }
            }
            else -> ThemeColors.modern(isDark)
        }
    }

    fun ensureBundledSkinLoaded(context: Context) {
        if (bundledSkinArtwork.isEmpty() || bundledSkinColors == null) {
            try {
                loadBundledSkin(context, "skins/base-2.91.wsz")?.let {
                    bundledSkinArtwork = it.artwork
                    bundledSkinColors = it.themeColors.copy(id = AppThemeId.WINAMP_CLASSIC)
                }
            } catch (e: Exception) {
                android.util.Log.w("ThemeManager", "Could not load base-2.91.wsz", e)
            }
        }
    }

    fun loadBundledSkin(context: Context, skinAssetPath: String): WinampSkinParser.ParsedSkin? {
        val path = if (skinAssetPath.startsWith("skins/")) skinAssetPath else "skins/$skinAssetPath"
        return try {
            context.assets.open(path).use { stream ->
                val displayName = skinAssetPath.substringAfterLast('/').substringBeforeLast('.')
                    .replace('_', ' ').replace('-', ' ')
                WinampSkinParser.parseSkinFromStream(stream, displayName)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getAudioPlayerTheme(context: Context): ThemeColors {
        activeAudioPlayerTheme?.let { return it }
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        val savedThemeId = shared.getString(PREF_AUDIO_PLAYER_THEME, AppThemeId.WINAMP_CLASSIC.name)
        ensureBundledSkinLoaded(context)
        val resolved = when (savedThemeId) {
            AppThemeId.WINAMP_CLASSIC.name -> {
                audioArtwork = bundledSkinArtwork
                bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
            }
            AppThemeId.CUSTOM_WSZ.name -> {
                val skinPath = shared.getString("audio_custom_skin_file", null) ?: shared.getString(PREF_CUSTOM_SKIN_FILE, null)
                if (skinPath != null && File(skinPath).exists()) {
                    WinampSkinParser.parseSkin(context, Uri.fromFile(File(skinPath)), shared.getString("audio_custom_skin_name", null) ?: shared.getString("custom_skin_name", null))?.let { parsed ->
                        audioArtwork = parsed.artwork
                        parsed.themeColors
                    } ?: run {
                        audioArtwork = bundledSkinArtwork
                        bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                    }
                } else {
                    audioArtwork = bundledSkinArtwork
                    bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                }
            }
            AppThemeId.WMP_GLASS.name -> ThemeColors.WMP_GLASS
            AppThemeId.FOOBAR2000.name -> ThemeColors.FOOBAR2000
            AppThemeId.MODERN.name -> ThemeColors.modern(resolveIsDark(context))
            else -> {
                val bundled = if (!savedThemeId.isNullOrEmpty()) loadBundledSkin(context, savedThemeId) else null
                if (bundled != null) {
                    audioArtwork = bundled.artwork
                    bundled.themeColors
                } else {
                    audioArtwork = bundledSkinArtwork
                    bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                }
            }
        }
        activeAudioPlayerTheme = resolved
        return resolved
    }

    fun setAudioPlayerSkin(
        context: Context,
        theme: ThemeColors,
        artwork: Map<String, ByteArray>,
        skinFile: File? = null,
        prefKey: String? = null
    ) {
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        val key = prefKey ?: theme.id.name
        shared.edit().putString(PREF_AUDIO_PLAYER_THEME, key).apply()
        if (skinFile != null) shared.edit().putString("audio_custom_skin_file", skinFile.absolutePath)
            .putString("audio_custom_skin_name", theme.name).apply()
        audioArtwork = artwork
        clearSkinBitmaps()
        activeAudioPlayerTheme = theme
        synchronized(listeners) { listeners.toList().forEach { it.onThemeChanged(activeTheme) } }
    }

    fun setAudioPlayerTheme(context: Context, theme: ThemeColors) {
        ensureBundledSkinLoaded(context)
        val (art, resolvedTheme) = when (theme.id) {
            AppThemeId.WINAMP_CLASSIC -> bundledSkinArtwork to (bundledSkinColors ?: theme)
            AppThemeId.CUSTOM_WSZ -> audioArtwork to theme
            else -> emptyMap<String, ByteArray>() to theme
        }
        setAudioPlayerSkin(context, resolvedTheme, art, prefKey = theme.id.name)
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
        if (getThemeMode(context) == ThemeMode.SYSTEM) {
            val isNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (activeTheme.id == AppThemeId.MODERN && activeTheme.isDark != isNight) {
                activeTheme = ThemeColors.modern(isNight)
            }
            synchronized(listeners) {
                for (l in listeners) l.onThemeChanged(activeTheme)
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

    fun setTheme(
        context: Context,
        theme: ThemeColors,
        skinFile: File? = null,
        artwork: Map<String, ByteArray> = emptyMap(),
        assetPath: String? = null
    ) {
        skinArtwork = if (theme.id == AppThemeId.WINAMP_CLASSIC && artwork.isEmpty()) bundledSkinArtwork else artwork
        activeTheme = theme
        val shared = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        shared.edit().apply {
            putString(PREF_ACTIVE_THEME, theme.id.name)
            if (skinFile != null) {
                putString(PREF_CUSTOM_SKIN_FILE, skinFile.absolutePath)
                putString("custom_skin_name", theme.name)
                remove(PREF_SKIN_ASSET_PATH)
            } else if (assetPath != null) {
                putString(PREF_SKIN_ASSET_PATH, assetPath)
                remove(PREF_CUSTOM_SKIN_FILE)
            } else if (theme.id != AppThemeId.CUSTOM_WSZ) {
                remove(PREF_CUSTOM_SKIN_FILE)
                remove(PREF_SKIN_ASSET_PATH)
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
    fun importSkin(context: Context, uri: Uri, audio: Boolean = false): ThemeColors? {
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
                WinampSkinParser.parseSkinFromStream(it, name?.substringBeforeLast('.') ?: "Custom Skin")
            } ?: throw java.io.IOException("No supported skin resources found")
            android.os.Handler(context.mainLooper).post {
                if (audio) setAudioPlayerSkin(context, parsed.themeColors, parsed.artwork, destFile)
                else setTheme(context, parsed.themeColors, destFile, parsed.artwork)
            }
            parsed.themeColors
        } catch (error: Exception) {
            destFile.delete()
            android.util.Log.w("AudioSkin", "Import failed", error)
            null
        }
    }

    fun skinBitmap(name: String): android.graphics.Bitmap? {
        val lower = name.lowercase(Locale.ROOT)
        skinBitmaps[lower]?.let { return it }
        val bytes = audioArtwork[lower] ?: bundledSkinArtwork[lower] ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 2_000_000) return null
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { skinBitmaps[lower] = it }
    }

    fun getSkinSprite(name: String, src: android.graphics.Rect): android.graphics.Bitmap? {
        val full = skinBitmap(name) ?: return null
        val left = src.left.coerceIn(0, full.width)
        val top = src.top.coerceIn(0, full.height)
        val right = src.right.coerceIn(left, full.width)
        val bottom = src.bottom.coerceIn(top, full.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return try {
            android.graphics.Bitmap.createBitmap(full, left, top, w, h)
        } catch (_: Exception) {
            null
        }
    }

    fun createSkinButtonDrawable(
        bitmapName: String,
        normalRect: android.graphics.Rect,
        pressedRect: android.graphics.Rect,
        activeRect: android.graphics.Rect? = null,
        activePressedRect: android.graphics.Rect? = null
    ): android.graphics.drawable.Drawable? {
        val normalBmp = getSkinSprite(bitmapName, normalRect) ?: return null
        val pressedBmp = getSkinSprite(bitmapName, pressedRect) ?: normalBmp

        val sld = android.graphics.drawable.StateListDrawable()
        if (activeRect != null && activePressedRect != null) {
            val activeBmp = getSkinSprite(bitmapName, activeRect) ?: normalBmp
            val activePressedBmp = getSkinSprite(bitmapName, activePressedRect) ?: pressedBmp
            sld.addState(intArrayOf(android.R.attr.state_checked, android.R.attr.state_pressed),
                android.graphics.drawable.BitmapDrawable(null, activePressedBmp).apply { isFilterBitmap = smoothSkinScaling })
            sld.addState(intArrayOf(android.R.attr.state_checked),
                android.graphics.drawable.BitmapDrawable(null, activeBmp).apply { isFilterBitmap = smoothSkinScaling })
        }
        sld.addState(intArrayOf(android.R.attr.state_pressed),
            android.graphics.drawable.BitmapDrawable(null, pressedBmp).apply { isFilterBitmap = smoothSkinScaling })
        sld.addState(intArrayOf(),
            android.graphics.drawable.BitmapDrawable(null, normalBmp).apply { isFilterBitmap = smoothSkinScaling })
        return sld
    }

    fun createCButtonDrawable(index: Int): android.graphics.drawable.Drawable? {
        // Prev: 0..23, Play: 23..46, Pause: 46..69, Stop: 69..92, Next: 92..114, Eject: 114..136
        val (x, w) = when (index) {
            0 -> 0 to 23
            1 -> 23 to 23
            2 -> 46 to 23
            3 -> 69 to 23
            4 -> 92 to 22
            5 -> 114 to 22
            else -> return null
        }
        return createSkinButtonDrawable("cbuttons.bmp",
            android.graphics.Rect(x, 0, x + w, 18),
            android.graphics.Rect(x, 18, x + w, 36)
        )
    }

    fun createShuffleDrawable(): android.graphics.drawable.Drawable? =
        createSkinButtonDrawable("shufrep.bmp",
            android.graphics.Rect(28, 0, 75, 15),
            android.graphics.Rect(28, 15, 75, 30),
            android.graphics.Rect(28, 30, 75, 45),
            android.graphics.Rect(28, 45, 75, 60)
        )

    fun createRepeatDrawable(): android.graphics.drawable.Drawable? =
        createSkinButtonDrawable("shufrep.bmp",
            android.graphics.Rect(0, 0, 28, 15),
            android.graphics.Rect(0, 15, 28, 30),
            android.graphics.Rect(0, 30, 28, 45),
            android.graphics.Rect(0, 45, 28, 60)
        )

    fun createSeekThumbDrawable(): android.graphics.drawable.Drawable? {
        val normal = getSkinSprite("posbar.bmp", android.graphics.Rect(248, 0, 277, 10)) ?: return null
        val pressed = getSkinSprite("posbar.bmp", android.graphics.Rect(278, 0, 307, 10)) ?: normal
        val sld = android.graphics.drawable.StateListDrawable()
        sld.addState(intArrayOf(android.R.attr.state_pressed),
            android.graphics.drawable.BitmapDrawable(null, pressed).apply { isFilterBitmap = smoothSkinScaling })
        sld.addState(intArrayOf(),
            android.graphics.drawable.BitmapDrawable(null, normal).apply { isFilterBitmap = smoothSkinScaling })
        return sld
    }

    fun createFloatingFrameDrawable(theme: ThemeColors, density: Float): android.graphics.drawable.Drawable =
        WinampChromeDrawable(theme.background, theme.cardStroke, density, interfaceBitmap("main.bmp"), null, smoothSkinScaling)

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

    private fun interfaceBitmap(name: String): android.graphics.Bitmap? {
        val bytes = skinArtwork[name] ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 2_000_000) return null
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun createToolbarDrawable(theme: ThemeColors, density: Float, isDark: Boolean = theme.isDark): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.toolbarBackground)
            setStroke((density).toInt().coerceAtLeast(1), theme.toolbarBorder)
        }
    }

    fun getPlaylistTitlebarBitmap(): android.graphics.Bitmap? {
        cachedPlaylistTitlebar?.let { return it }
        val pledit = skinBitmap("pledit.bmp")
        val gen = skinBitmap("gen.bmp")
        val bmp = pledit ?: gen ?: return null

        val isPledit = (pledit != null)
        val yOffset = if (isPledit && bmp.height >= 41) 21 else 0
        val skinName = if (isPledit) "pledit.bmp" else "gen.bmp"

        val assembled = android.graphics.Bitmap.createBitmap(275, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(assembled)
        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

        // 1. Left corner: (0, yOffset, 25, yOffset + 20)
        getSkinSprite(skinName, android.graphics.Rect(0, yOffset, 25, yOffset + 20))?.let {
            c.drawBitmap(it, 0f, 0f, p)
        }

        // 2. Title piece: (26, yOffset, 126, yOffset + 20) width 100
        getSkinSprite(skinName, android.graphics.Rect(26, yOffset, 126, yOffset + 20))?.let {
            c.drawBitmap(it, 25f, 0f, p)
        }

        // 3. Center filler: (127, yOffset, 152, yOffset + 20) width 25 repeated from x=125 to 250
        getSkinSprite(skinName, android.graphics.Rect(127, yOffset, 152, yOffset + 20))?.let { fill ->
            var curX = 125f
            while (curX < 250f) {
                c.drawBitmap(fill, curX, 0f, p)
                curX += 25f
            }
        }

        // 4. Right corner: (153, yOffset, 178, yOffset + 20) width 25
        getSkinSprite(skinName, android.graphics.Rect(153, yOffset, 178, yOffset + 20))?.let {
            c.drawBitmap(it, 250f, 0f, p)
        }

        cachedPlaylistTitlebar = assembled
        return assembled
    }

    fun getPlaylistBottomBarBitmap(): android.graphics.Bitmap? {
        cachedPlaylistBottomBar?.let { return it }
        val pledit = skinBitmap("pledit.bmp") ?: return null
        if (pledit.height < 110 || pledit.width < 276) return null

        val assembled = android.graphics.Bitmap.createBitmap(275, 38, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(assembled)
        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

        // Left piece: (0, 72, 125, 110) at x=0 (w=125, h=38)
        getSkinSprite("pledit.bmp", android.graphics.Rect(0, 72, 125, 110))?.let {
            c.drawBitmap(it, 0f, 0f, p)
        }

        // Right piece: (126, 72, 276, 110) at x=125 (w=150, h=38)
        getSkinSprite("pledit.bmp", android.graphics.Rect(126, 72, 276, 110))?.let {
            c.drawBitmap(it, 125f, 0f, p)
        }

        cachedPlaylistBottomBar = assembled
        return assembled
    }

    fun getPlaylistLeftBorderTile(): android.graphics.Bitmap? {
        cachedPlaylistLeftTile?.let { return it }
        val pledit = skinBitmap("pledit.bmp") ?: return null
        if (pledit.height < 71 || pledit.width < 25) return null
        val tile = getSkinSprite("pledit.bmp", android.graphics.Rect(0, 42, 25, 71))
        cachedPlaylistLeftTile = tile
        return tile
    }

    fun getPlaylistRightBorderTile(): android.graphics.Bitmap? {
        cachedPlaylistRightTile?.let { return it }
        val pledit = skinBitmap("pledit.bmp") ?: return null
        if (pledit.height < 71 || pledit.width < 51) return null
        val tile = getSkinSprite("pledit.bmp", android.graphics.Rect(26, 42, 51, 71))
        cachedPlaylistRightTile = tile
        return tile
    }

    fun createPlaylistTitlebarDrawable(theme: ThemeColors, density: Float): android.graphics.drawable.Drawable? {
        val bmp = getPlaylistTitlebarBitmap()
        if (bmp != null) {
            return android.graphics.drawable.BitmapDrawable(null, bmp).apply {
                isFilterBitmap = smoothSkinScaling
            }
        }
        val dpStroke = (1 * density).toInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.toolbarBackground)
            setStroke(dpStroke, theme.toolbarBorder)
        }
    }
}
