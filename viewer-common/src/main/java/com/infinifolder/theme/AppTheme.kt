package com.infinifolder.theme

import android.graphics.Color

enum class AppThemeId {
    MODERN,
    WINAMP_CLASSIC,
    WMP_GLASS,
    FOOBAR2000,
    CUSTOM_WSZ
}

data class ThemeColors(
    val id: AppThemeId,
    val name: String,
    val description: String,
    val background: Int,
    val surface: Int,
    val cardBackground: Int,
    val cardStroke: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textHighlight: Int,
    val accent: Int,
    val accentGlow: Int,
    val toolbarBackground: Int,
    val toolbarBorder: Int,
    val statusBackground: Int,
    val buttonBackground: Int,
    val buttonText: Int,
    val toolbarText: Int = textPrimary,
    val selectionText: Int = textPrimary,
    val selectionBackground: Int = (accent and 0x00FFFFFF) or 0x33000000,
    val isDark: Boolean = true,
    val fontMonospace: Boolean = false,
    val activityLight: Int = 0xFFE53935.toInt(),
    val loadingActivityLight: Int = 0xFF4CAF50.toInt(),
    val verificationActivityLight: Int = 0xFFFFB300.toInt(),
    val cornerRadiusDp: Int = 8,
    val spectrumColors: IntArray = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFF7700.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt()
    )
) {
    companion object {
        // 1. Modern Material Theme
        fun modern(isDark: Boolean = true): ThemeColors = if (isDark) {
            ThemeColors(
                id = AppThemeId.MODERN,
                name = "Modern Dark",
                description = "Clean adaptive Material dark theme",
                background = 0xFF171A16.toInt(),
                surface = 0xFF20251F.toInt(),
                cardBackground = 0xFF242A22.toInt(),
                cardStroke = 0xFF353E33.toInt(),
                textPrimary = 0xFFF0F0E9.toInt(),
                textSecondary = 0xFF9BA296.toInt(),
                textHighlight = 0xFF4CAF50.toInt(),
                accent = 0xFF4CAF50.toInt(),
                accentGlow = 0x664CAF50,
                toolbarBackground = 0xFF1C211A.toInt(),
                toolbarBorder = 0xFF2C3529.toInt(),
                statusBackground = 0xFF171A16.toInt(),
                buttonBackground = 0xFF2D362B.toInt(),
                buttonText = 0xFFF0F0E9.toInt(),
                isDark = true,
                cornerRadiusDp = 10
            )
        } else {
            ThemeColors(
                id = AppThemeId.MODERN,
                name = "Modern Light",
                description = "Clean adaptive Material light theme",
                background = 0xFFF5F4EF.toInt(),
                surface = 0xFFEBE9E1.toInt(),
                cardBackground = 0xFFFFFFFF.toInt(),
                cardStroke = 0xFFD8D6CC.toInt(),
                textPrimary = 0xFF20231F.toInt(),
                textSecondary = 0xFF686D65.toInt(),
                textHighlight = 0xFF2E7D32.toInt(),
                accent = 0xFF2E7D32.toInt(),
                accentGlow = 0x442E7D32,
                toolbarBackground = 0xFFECEAE3.toInt(),
                toolbarBorder = 0xFFDBD9D0.toInt(),
                statusBackground = 0xFFF5F4EF.toInt(),
                buttonBackground = 0xFFE0DDD4.toInt(),
                buttonText = 0xFF20231F.toInt(),
                isDark = false,
                cornerRadiusDp = 10
            )
        }

        // 2. Winamp Classic / XMMS Theme
        val WINAMP_CLASSIC = ThemeColors(
            id = AppThemeId.WINAMP_CLASSIC,
            name = "Classic Retro Skin",
            description = "Brushed metal chassis with neon green & amber LED matrix",
            background = 0xFF1B1B22.toInt(),
            surface = 0xFF262630.toInt(),
            cardBackground = 0xFF20202A.toInt(),
            cardStroke = 0xFF424254.toInt(),
            textPrimary = 0xFF00FF00.toInt(), // Fluorescent green
            textSecondary = 0xFFFFB000.toInt(), // Fluorescent amber
            textHighlight = 0xFF00FFFF.toInt(), // Cyan
            accent = 0xFF00FF00.toInt(),
            accentGlow = 0x8800FF00.toInt(),
            toolbarBackground = 0xFF2B2B38.toInt(),
            toolbarBorder = 0xFF505068.toInt(),
            statusBackground = 0xFF14141A.toInt(),
            buttonBackground = 0xFF353545.toInt(),
            buttonText = 0xFF00FF00.toInt(),
            isDark = true,
            fontMonospace = true,
            cornerRadiusDp = 3, // Sharp industrial retro corners
            spectrumColors = intArrayOf(
                0xFFFF0033.toInt(), 0xFFFF2200.toInt(), 0xFFFF6600.toInt(), 0xFFFF9900.toInt(),
                0xFFFFCC00.toInt(), 0xFFFFFF00.toInt(), 0xFFCCFF00.toInt(), 0xFF66FF00.toInt(),
                0xFF00FF00.toInt(), 0xFF00FF66.toInt(), 0xFF00FFCC.toInt(), 0xFF00FFFF.toInt(),
                0xFF00CCFF.toInt(), 0xFF0066FF.toInt(), 0xFF0000FF.toInt(), 0xFF6600FF.toInt()
            )
        )

        // 3. Windows Media Player (Aero Glass / WMP 9 & 11)
        val WMP_GLASS = ThemeColors(
            id = AppThemeId.WMP_GLASS,
            name = "Windows Media Player (Aero)",
            description = "Deep translucent midnight blue with glossy aqua halo glow",
            background = 0xFF0B1626.toInt(),
            surface = 0xFF13223A.toInt(),
            cardBackground = 0xFF162946.toInt(),
            cardStroke = 0xFF254B7E.toInt(),
            textPrimary = 0xFFF0F8FF.toInt(), // Ice white
            textSecondary = 0xFF38BDF8.toInt(), // Electric sky blue
            textHighlight = 0xFF00E5FF.toInt(), // Bright aqua
            accent = 0xFF0284C7.toInt(),
            accentGlow = 0x880284C7.toInt(),
            toolbarBackground = 0xFF102038.toInt(),
            toolbarBorder = 0xFF2A558C.toInt(),
            statusBackground = 0xFF08101C.toInt(),
            buttonBackground = 0xFF1A3358.toInt(),
            buttonText = 0xFFE0F2FE.toInt(),
            isDark = true,
            cornerRadiusDp = 14, // Smooth rounded aero glass
            spectrumColors = intArrayOf(
                0xFF38BDF8.toInt(), 0xFF0284C7.toInt(), 0xFF0369A1.toInt(), 0xFF0284C7.toInt(),
                0xFF06B6D4.toInt(), 0xFF0891B2.toInt(), 0xFF0E7490.toInt(), 0xFF38BDF8.toInt()
            )
        )

        // 4. Foobar2000 (Minimalist Technical Typography)
        val FOOBAR2000 = ThemeColors(
            id = AppThemeId.FOOBAR2000,
            name = "foobar2000",
            description = "High-density utilitarian layout with razor precision and technical typography",
            background = 0xFF141414.toInt(),
            surface = 0xFF1A1A1A.toInt(),
            cardBackground = 0xFF181818.toInt(),
            cardStroke = 0xFF2E2E2E.toInt(),
            textPrimary = 0xFFECECEC.toInt(),
            textSecondary = 0xFF8A8A8A.toInt(),
            textHighlight = 0xFF4A90E2.toInt(), // Classic foobar link blue
            accent = 0xFF3B78C2.toInt(),
            accentGlow = 0x443B78C2,
            toolbarBackground = 0xFF1E1E1E.toInt(),
            toolbarBorder = 0xFF333333.toInt(),
            statusBackground = 0xFF121212.toInt(),
            buttonBackground = 0xFF222222.toInt(),
            buttonText = 0xFFCCCCCC.toInt(),
            isDark = true,
            fontMonospace = true,
            cornerRadiusDp = 2, // Utilitarian boxed borders
            spectrumColors = intArrayOf(
                0xFF4A90E2.toInt(), 0xFF357ABD.toInt(), 0xFF2E6DA4.toInt(), 0xFF23527C.toInt()
            )
        )
    }
}
