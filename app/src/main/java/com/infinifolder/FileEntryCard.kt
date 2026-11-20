package com.infinifolder

import android.content.Context
import android.view.View
import com.infinifolder.model.ResourceEntry
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.theme.ThemeManager
import io.github.gretchycat.infiniselect.browser.FileCardEntry
import io.github.gretchycat.infiniselect.browser.FileCardStyle

/** Adapts Folder metadata/theme to the shared InfiniSelect file card. */
class FileEntryCard(
    context: Context,
    entry: ResourceEntry,
    asGrid: Boolean,
    iconSizeDp: Int,
    dark: Boolean,
    selectedItem: Boolean = false,
    onEmptyAreaLongPress: (() -> Boolean)? = null,
) {
    private val card =
        io.github.gretchycat.infiniselect.browser.FileEntryCard(
            context,
            FileCardEntry(
                entry.metadata.isDirectory,
                ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType),
                entry.metadata.isSymbolicLink,
                entry.metadata.isBrokenLink,
                entry.metadata.recoveryNote != null,
                com.infinifolder.apps.AppsUri.isAppsUri(entry.uri),
                entry.metadata.canRead,
                entry.metadata.canWrite,
                entry.metadata.isHidden,
            ),
            asGrid,
            iconSizeDp,
            dark,
            selectedItem,
            onEmptyAreaLongPress,
            browserCardStyle(),
            { NetworkConnectionBadge.attach(it, entry = entry) },
            FileNameEllipsizer::ellipsize,
        )
    val container
        get() = card.container

    val nameView
        get() = card.nameView

    val metaView
        get() = card.metaView

    fun attachIcon(icon: View) = card.attachIcon(icon)

    fun addLabels(
        displayName: String,
        logicalDirectory: Boolean,
        maxWidthPx: Int,
        metadata: String,
    ) = card.addLabels(displayName, logicalDirectory, maxWidthPx, metadata)
}

internal fun browserCardStyle(): FileCardStyle =
    ThemeManager.activeTheme.let {
        FileCardStyle(
            it.textPrimary,
            it.textSecondary,
            it.selectionText,
            it.selectionBackground,
            it.accent,
            it.fontMonospace,
            it.isDark,
            it.background,
        )
    }
