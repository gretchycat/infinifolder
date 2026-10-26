package com.infinifolder

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.*
import com.infinifolder.storage.CommonLocationsPreferences
import com.infinifolder.theme.ThemeManager

/** Settings for durable folder shortcuts; removing one never changes storage contents. */
object CommonLocationsDialog {
    fun show(activity: Activity, context: Context, preferences: CommonLocationsPreferences,
             chooseFolder: (Uri?, (Uri, String) -> Unit) -> Unit) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val theme = ThemeManager.activeTheme
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
        }
        val automatic = CheckBox(context).apply {
            text = "Remember chosen destinations automatically"
            setTextColor(theme.textPrimary)
            isChecked = preferences.rememberAutomatically
            setOnCheckedChangeListener { _, checked -> preferences.rememberAutomatically = checked }
        }
        body.addView(automatic)
        body.addView(TextView(context).apply {
            text = "Frequently chosen folders appear first. Tap a folder to edit it. Removing a shortcut leaves its files untouched."
            setTextColor(theme.textSecondary); setPadding(0, dp(8), 0, dp(8))
        })
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(-1,
            minOf(dp(330), activity.resources.displayMetrics.heightPixels / 2)))
        fun editName(initial: String, onSave: (String) -> Unit) {
            val input = EditText(context).apply { setText(initial); isSingleLine = true; setTextColor(theme.textPrimary) }
            val dialog = AlertDialog.Builder(context).setTitle("Location name").setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isEmpty() || name.length > 256) input.error = "Enter a name of 1–256 characters"
                    else { onSave(name); dialog.dismiss() }
                }
            }
            dialog.show()
        }
        fun render() {
            rows.removeAllViews()
            val entries = preferences.list()
            if (entries.isEmpty()) rows.addView(TextView(context).apply {
                text = "No common locations yet. Add a folder or choose a transfer destination."
                setTextColor(theme.textSecondary); setPadding(0, dp(16), 0, dp(16))
            })
            entries.forEach { entry ->
                rows.addView(TextView(context).apply {
                    text = entry.label + "\n" + (if (entry.manual) "Saved · " else "") +
                        "Uses: ${entry.selections}\n" + entry.uri
                    setTextColor(theme.textPrimary); textSize = 14f
                    setPadding(0, dp(12), 0, dp(12))
                    setOnClickListener {
                        AlertDialog.Builder(context).setTitle(entry.label)
                            .setItems(arrayOf("Rename", "Change folder", "Remove")) { _, which ->
                                when (which) {
                                    0 -> editName(entry.label) { preferences.rename(entry.uri, it); render() }
                                    1 -> chooseFolder(Uri.parse(entry.uri)) { uri, _ ->
                                        preferences.replace(entry.uri, uri, entry.label); render()
                                    }
                                    2 -> { preferences.remove(entry.uri); render() }
                                }
                            }.setNegativeButton("Cancel", null).show()
                    }
                })
            }
        }
        val dialog = AlertDialog.Builder(context).setTitle("Common locations").setView(body)
            .setPositiveButton("Done", null).setNeutralButton("Add folder", null)
            .setNegativeButton("Clear recent", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                chooseFolder(null) { uri, label ->
                    editName(label) { preferences.add(uri, it); render() }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { preferences.clearRecent(); render() }
        }
        render(); dialog.show()
    }
}
