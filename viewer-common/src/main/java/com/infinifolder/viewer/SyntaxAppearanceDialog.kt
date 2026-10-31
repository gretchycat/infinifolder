package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.*
import io.github.gretchycat.infiniscribe.*

class SyntaxAppearanceDialog(private val activity: Activity, private val theme: TextTheme, private val language: String,
    private val onTheme: (TextTheme) -> Unit, private val onLanguage: (String) -> Unit,
    private val onImport: () -> Unit, private val onExport: () -> Unit) {
    fun show() {
        AlertDialog.Builder(activity).setTitle("Text appearance").setItems(arrayOf("Highlight language ($language)",
            "Theme (${theme.name})", "Default font, size and colors…", "Token styles…", "Import theme…", "Export theme…")) { _, index ->
            when (index) {
                0 -> {
                    val names = listOf("auto") + SyntaxHighlighting.languages
                    AlertDialog.Builder(activity).setTitle("Highlight language").setSingleChoiceItems(names.toTypedArray(), names.indexOf(language)) { dialog, item ->
                        onLanguage(names[item]); dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
                }
                1 -> AlertDialog.Builder(activity).setTitle("Theme").setItems(arrayOf("Forest dark", "Paper light")) { _, item ->
                    onTheme(if (item == 0) TextThemeJson.DARK else TextThemeJson.LIGHT)
                }.show()
                2 -> editStyle(null)
                3 -> {
                    val scopes = (listOf("comment", "keyword", "storage", "string", "constant", "support.type", "entity.name.function",
                        "variable.other.property", "entity.name.tag", "entity.other.attribute-name", "markup.heading", "markup.bold", "markup.italic", "markup.raw") +
                        theme.rules.flatMap { it.scopes }).distinct()
                    AlertDialog.Builder(activity).setTitle("Token style").setItems(scopes.toTypedArray()) { _, index -> editStyle(scopes[index]) }.show()
                }
                4 -> onImport(); 5 -> onExport()
            }
        }.setNegativeButton("Close", null).show()
    }
    private fun editStyle(scope: String?) {
        val original = if (scope == null) theme.defaults else theme.rules.lastOrNull { it.scopes == listOf(scope) }?.style ?: TextStyle()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; val p = dp(16); setPadding(p, p, p, p) }
        fun input(title: String, value: String, numeric: Boolean = false): EditText {
            column.addView(TextView(activity).apply { text = title })
            return EditText(activity).apply {
                setText(value); isSingleLine = true
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                column.addView(this)
            }
        }
        val foreground = input("Text color (blank inherits)", original.foreground?.let(TextThemeJson::colorString).orEmpty())
        val background = input("Background color (blank inherits)", original.background?.let(TextThemeJson::colorString).orEmpty())
        val size = input("Text size, 6–96 (blank inherits)", original.fontSize?.toString().orEmpty(), true)
        column.addView(TextView(activity).apply { text = "Font" })
        val fonts = (listOf("Inherit", "monospace", "sans-serif", "serif", "sans-serif-condensed") + listOfNotNull(original.fontFamily)).distinct()
        val family = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, fonts)
            setSelection(fonts.indexOf(original.fontFamily ?: "Inherit").coerceAtLeast(0)); column.addView(this)
        }
        val override = CheckBox(activity).apply { text = "Override emphasis"; isChecked = listOf(original.bold, original.italic, original.underline, original.strikethrough).any { it != null }; column.addView(this) }
        fun flag(name: String, checked: Boolean?) = CheckBox(activity).apply { text = name; isChecked = checked == true; isEnabled = override.isChecked; column.addView(this) }
        val bold = flag("Bold", original.bold); val italic = flag("Italic", original.italic)
        val underline = flag("Underline", original.underline); val strike = flag("Strikethrough", original.strikethrough)
        override.setOnCheckedChangeListener { _, enabled -> listOf(bold, italic, underline, strike).forEach { it.isEnabled = enabled } }
        column.addView(TextView(activity).apply { text = "Colors use #RGB, #RGBA, #RRGGBB or #RRGGBBAA." })
        val dialog = AlertDialog.Builder(activity).setTitle(scope ?: "Default text")
            .setView(ScrollView(activity).apply { addView(column) }).setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    fun color(text: EditText) = text.text.toString().trim().takeIf { it.isNotEmpty() }?.let(TextThemeJson::parseColor)
                    val fontSize = size.text.toString().trim().takeIf { it.isNotEmpty() }?.let { it.toFloatOrNull() ?: error("Enter a text size from 6 to 96") }
                    val next = TextStyle(color(foreground), color(background), bold.isChecked.takeIf { override.isChecked }, italic.isChecked.takeIf { override.isChecked },
                        underline.isChecked.takeIf { override.isChecked }, strike.isChecked.takeIf { override.isChecked }, fonts[family.selectedItemPosition].takeIf { it != "Inherit" }, fontSize)
                    val updated = if (scope == null) theme.copy(defaults = next.over(TextThemeJson.DARK.defaults)) else {
                        val rules = theme.rules.filterNot { it.scopes == listOf(scope) }
                        require(rules.size < 512) { "Too many token styles" }
                        theme.copy(rules = rules + TextStyleRule(listOf(scope), next))
                    }
                    onTheme(updated); dialog.dismiss()
                } catch (error: Exception) { Toast.makeText(activity, error.message ?: "Invalid style", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
