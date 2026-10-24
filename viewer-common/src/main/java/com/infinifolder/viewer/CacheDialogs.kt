package com.infinifolder.viewer

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.infinifolder.theme.ThemeChangeListener
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager

/** Shared cache dialogs follow application mode and palette, including custom themes. */
object CacheDialogs {
    fun builder(context: Context): AlertDialog.Builder {
        ThemeManager.init(context)
        return AlertDialog.Builder(ContextThemeWrapper(context, if (ThemeManager.activeTheme.isDark)
            android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert))
    }
    fun style(dialog: AlertDialog) {
        fun apply(theme: ThemeColors) {
            val window = dialog.window ?: return
            window.setBackgroundDrawable(GradientDrawable().apply {
                setColor(theme.surface)
                cornerRadius = dialog.context.resources.displayMetrics.density * theme.cornerRadiusDp
            })
            fun visit(view: View) {
                if (view is TextView) {
                    view.setTextColor(if (view.id == android.R.id.message) theme.textSecondary else theme.textPrimary)
                    view.setHintTextColor(theme.textSecondary)
                }
                if (view is EditText) view.backgroundTintList = ColorStateList.valueOf(theme.accent)
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (name in setOf("parentPanel", "topPanel", "contentPanel", "customPanel", "buttonPanel"))
                    view.setBackgroundColor(theme.surface)
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(window.decorView)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
                dialog.getButton(it)?.setTextColor(theme.accent)
            }
        }
        apply(ThemeManager.activeTheme)
        val listener = object : ThemeChangeListener {
            override fun onThemeChanged(theme: ThemeColors) { dialog.window?.decorView?.post { if (dialog.isShowing) apply(theme) } }
        }
        ThemeManager.addListener(listener)
        dialog.setOnDismissListener { ThemeManager.removeListener(listener) }
    }
}
