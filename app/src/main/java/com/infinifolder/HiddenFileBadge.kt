package com.infinifolder

import android.widget.FrameLayout

internal object HiddenFileBadge {
    fun attach(
        frame: FrameLayout,
        hidden: Boolean,
        directory: Boolean,
        selectionBadge: Boolean = false,
    ) =
        io.github.gretchycat.infiniselect.browser.HiddenFileBadge.attach(
            frame,
            hidden,
            directory,
            selectionBadge,
            com.infinifolder.theme.ThemeManager.activeTheme.isDark,
        )
}
