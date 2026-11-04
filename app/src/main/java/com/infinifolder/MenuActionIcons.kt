package com.infinifolder

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.util.Locale

/** Presentation only: callers keep the original label/index as the action identifier. */
internal object MenuActionIcons {
    fun glyph(label: String): String {
        val action = label.trim().lowercase(Locale.ROOT)
        return when {
            action == "file" -> "📄"
            action == "view" -> "👁️"
            action.startsWith("properties") || action.contains("information") -> "ℹ️"
            action.startsWith("rename") || action.startsWith("edit") -> "✏️"
            action.startsWith("unpin") -> "📍"
            action.startsWith("pin") -> "📌"
            action.startsWith("delete") || action.startsWith("remove") || action.startsWith("uninstall") -> "🗑️"
            action.startsWith("undelete") || action.startsWith("recover") || action.startsWith("restore") -> "♻️"
            action.startsWith("copy") -> "📄"
            action.startsWith("cut") -> "✂️"
            action.startsWith("paste") -> "📋"
            action.startsWith("move") -> "🚚"
            action.startsWith("link") || action.contains("link to") -> "🔗"
            action.startsWith("create archive") || action.contains("browse archive") || action.contains("as archive") -> "📦"
            action.contains("hex") -> "🔢"
            action.contains("external") || action.startsWith("open with") -> "↗️"
            action.startsWith("launch") || action.startsWith("play") -> "▶️"
            action.startsWith("queue") -> "🎵"
            action.contains("theme") || action.contains("appearance") -> "🎨"
            action.startsWith("open link") || action.contains("target uri") -> "🔗"
            action.startsWith("open") || action.startsWith("browse") -> "📂"
            action.startsWith("exit selection") -> "↩️"
            action.startsWith("deselect") || action.startsWith("select none") -> "⬜"
            action.contains("select") -> "☑️"
            action.startsWith("add to home") -> "🏠"
            action.contains("debug") -> "🐞"
            action.startsWith("test connection") || action.contains("network") -> "📶"
            action.contains("disconnect") -> "🔌"
            action.contains("connection") || action.contains("storage") -> "🔗"
            action.startsWith("unmount") || action.startsWith("eject") -> "⏏️"
            action.startsWith("mount") -> "💽"
            action.startsWith("extract") || action.startsWith("share") -> "📤"
            action.startsWith("refresh") -> "🔄"
            action.contains("sort") -> "↕️"
            action.contains("folder") || action.contains("directory") -> "📁"
            action.startsWith("new") || action.startsWith("create") -> "➕"
            action.contains("settings") -> "⚙️"
            action.startsWith("help") -> "❓"
            else -> "▸"
        }
    }

    fun decorated(label: String) = "${glyph(label)}  $label"
    fun labels(vararg labels: String) = labels.map(::decorated).toTypedArray()

    fun view(context: Context, label: String) = TextView(context).apply {
        text = glyph(label)
        textSize = 19f
        typeface = android.graphics.Typeface.DEFAULT
        setTextColor(com.infinifolder.theme.ThemeManager.activeTheme.textPrimary)
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
