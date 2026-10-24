package com.infinifolder

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import com.infinifolder.theme.ThemeManager

/** Small nested menu with a separate, consistently right-aligned disclosure column. */
object BrowserMenu {
    data class Item(val label: String, val enabled: Boolean = true,
        val children: List<Item>? = null, val action: (() -> Unit)? = null)

    fun show(activity: Activity, context: Context, items: List<Item>) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val theme = ThemeManager.activeTheme
        val stack = mutableListOf("Menu" to items)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = TextView(context).apply {
            textSize = 18f; setTextColor(theme.textPrimary); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(header)
        body.addView(ScrollView(context).apply { addView(rows) })
        val dialog = AlertDialog.Builder(context).setView(body).setNegativeButton("Close", null).create()
        fun render() {
            val (title, entries) = stack.last()
            header.text = if (stack.size > 1) "‹  $title" else title
            rows.removeAllViews()
            for (item in entries) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48); setPadding(dp(20), dp(8), dp(16), dp(8))
                    isEnabled = item.enabled; alpha = if (item.enabled) 1f else .4f
                }
                row.addView(TextView(context).apply { text = item.label; textSize = 16f; setTextColor(theme.textPrimary) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(context).apply {
                    text = if (item.children != null) "▶" else ""
                    gravity = Gravity.END; setTextColor(theme.textSecondary); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(28), -2))
                row.contentDescription = item.label + if (item.children != null) ", submenu" else ""
                row.setOnClickListener {
                    if (item.children != null) { stack.add(item.label to item.children); render() }
                    else { dialog.dismiss(); item.action?.invoke() }
                }
                rows.addView(row)
            }
        }
        header.setOnClickListener { if (stack.size > 1) { stack.removeAt(stack.lastIndex); render() } }
        dialog.setOnKeyListener { _, code, event ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && stack.size > 1) {
                if (event.action == android.view.KeyEvent.ACTION_UP) { stack.removeAt(stack.lastIndex); render() }
                true
            } else false
        }
        render()
        dialog.show()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback {
                if (stack.size > 1) { stack.removeAt(stack.lastIndex); render() } else dialog.dismiss()
            }
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            dialog.setOnDismissListener { dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        dialog.window?.setLayout(minOf(dp(400), (activity.resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1)), -2)
    }
}
