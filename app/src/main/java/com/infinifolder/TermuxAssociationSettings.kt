package com.infinifolder

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.infinifolder.theme.ThemeManager
import java.util.Locale

object TermuxAssociationSettings {
    fun show(activity: Activity) {
        val store = TermuxAssociations(activity)
        val theme = if (ThemeManager.activeTheme.isDark) R.style.AppTheme_Dark else R.style.AppTheme
        val context = android.view.ContextThemeWrapper(activity, theme)
        fun showEditor(existing: TermuxAssociations.Rule?) {
            val suffix = EditText(context).apply { hint = "Extension, e.g. .py"; setSingleLine(); setText(existing?.suffix.orEmpty()) }
            val command = EditText(context).apply {
                hint = "Termux editor command, e.g. nvim"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(); setText(existing?.command.orEmpty())
            }
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0); addView(suffix); addView(command)
            }
            val dialog = AlertDialog.Builder(context).setTitle(if (existing == null) "Add association" else "Edit association")
                .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val ext = suffix.text.toString().trim().lowercase(Locale.ROOT).let { if (it.startsWith('.')) it else ".$it" }
                    val cmd = command.text.toString().trim()
                    if (ext.length < 2 || ext.any { it == '/' || it == '\\' || it.isWhitespace() }) { suffix.error = "Enter a file extension"; return@setOnClickListener }
                    if (cmd.isEmpty()) { command.error = "Enter a command"; return@setOnClickListener }
                    val rules = store.list().filterNot { it == existing || it.suffix == ext } + TermuxAssociations.Rule(ext, cmd)
                    store.save(rules)
                    TermuxAvailability.refresh(activity)
                    Thread({ TermuxEditorAvailability.probe(activity.applicationContext, force = true) }, "TermuxEditorProbe").start()
                    dialog.dismiss(); show(activity)
                }
            }
            dialog.show()
        }
        val rules = store.list()
        val labels = rules.map {
            val state = when (TermuxEditorAvailability.state(activity, it.command)) {
                TermuxEditorAvailability.State.AVAILABLE -> "installed"
                TermuxEditorAvailability.State.MISSING -> "not installed"
                TermuxEditorAvailability.State.UNKNOWN -> "not verified"
            }
            "${it.suffix}  →  ${it.command}  ·  $state"
        } + "Add association…"
        AlertDialog.Builder(context).setTitle("Termux file associations").setItems(labels.toTypedArray()) { _, index ->
            if (index == rules.size) showEditor(null) else {
                val selected = rules[index]
                AlertDialog.Builder(context).setTitle("${selected.suffix} → ${selected.command}")
                    .setItems(arrayOf("Edit", "Remove")) { _, choice ->
                        if (choice == 0) showEditor(selected) else {
                            store.save(store.list().filterNot { it == selected })
                            TermuxAvailability.refresh(activity)
                            show(activity)
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }.setNeutralButton("Check editors") { _, _ ->
            if (!TermuxAvailability.installed(activity)) {
                AlertDialog.Builder(context).setMessage("Install Termux to check its editors.")
                    .setPositiveButton("OK") { _, _ -> show(activity) }.show()
            } else if (!TermuxEditorAvailability.canCheck(activity)) {
                AlertDialog.Builder(context).setTitle("Allow Termux commands")
                    .setMessage("Allow ‘Run commands in Termux environment’ in InfiniFolder's permissions, and enable allow-external-apps in Termux. Then check the editors again.")
                    .setPositiveButton("App settings") { _, _ ->
                        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                    }.setNegativeButton("Close", null).show()
            } else {
                Thread({ TermuxEditorAvailability.probe(activity.applicationContext, force = true) }, "TermuxEditorProbe").start()
                android.widget.Toast.makeText(activity, "Checking Termux editors… Reopen this list for results.", android.widget.Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("Close", null).show()
    }
}
