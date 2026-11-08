package com.infinifolder.viewer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import com.infinifolder.theme.ThemeManager

/** One settings surface for the same associations used by the external picker. */
class ExternalAppSettingsActivity : Activity() {
    private lateinit var defaults: ExternalAppDefaults
    override fun onCreate(state: Bundle?) {
        ThemeManager.init(applicationContext)
        setTheme(if (ThemeManager.activeTheme.isDark) com.infinifolder.viewer.common.R.style.AppTheme_Dark
            else com.infinifolder.viewer.common.R.style.AppTheme)
        super.onCreate(state)
        defaults = ExternalAppDefaults(this)
    }
    override fun onResume() { super.onResume(); render() }
    private fun label(component: ComponentName): String = runCatching {
        if (component.packageName == packageName && ViewerComponents.registry.query(io.github.gretchycat.infinistream.Operations.VIEW)
                .any { it.handler.service(ViewerComponent::class.java)?.activityClassName == component.className }) "Built-in viewer"
        else packageManager.getActivityInfo(component, 0).loadLabel(packageManager).toString()
    }.getOrDefault("${component.packageName} (unavailable)")
    private fun render() {
        val theme = ThemeManager.activeTheme
        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(theme.background)
        }
        fun text(value: String, size: Float = 14f) { body.addView(TextView(this).apply {
            text = value; textSize = size; setTextColor(theme.textPrimary); setPadding(0, pad / 2, 0, pad / 2)
        }) }
        fun button(value: String, click: () -> Unit) { body.addView(Button(this).apply {
            text = value; setTextColor(theme.buttonText); backgroundTintList = android.content.res.ColorStateList.valueOf(theme.buttonBackground)
            setOnClickListener { click() }
        }) }
        text("Default viewers and apps", 22f)
        text("These defaults apply only inside InfiniFolder. Supported files use the built-in viewer unless you save another choice. Open with… lists the built-in viewer first and lets you change the default. File-specific choices take priority over extension and file-type choices.")
        text("Default editor: ${defaults.editor?.let(::label) ?: "Ask each time"}", 16f)
        button("Choose default editor…") {
            val target = Intent(Intent.ACTION_EDIT).setDataAndType(Uri.parse("content://$packageName.external-edit/"), "*/*")
            startActivity(ExternalAppChooser.create(this, target, "Default editor", null, "", true)
                .putExtra(ExternalAppChooser.SELECT_EDITOR, true))
        }
        if (defaults.editor != null) button("Clear default editor") { defaults.editor = null; render() }
        text("Saved defaults", 18f)
        val rules = defaults.rules()
        if (rules.isEmpty()) text("No file or file-type defaults. Choose Open with… on a file and enable Remember in InfiniFolder.")
        rules.forEach { rule ->
            val role = if (rule.action == Intent.ACTION_EDIT) "Edit" else "Open"
            val scope = when {
                rule.scope == ExternalAppDefaults.FILE -> "This file: ${rule.name}"
                rule.scope == ExternalAppDefaults.EXTENSION -> "Files ending in .${rule.key}"
                com.infinifolder.mime.MimeRegistry.isUnspecified(rule.mime) -> "Extensionless generic files: ${rule.mime}"
                else -> "All files: ${rule.mime}"
            }
            text("$role · $scope\n${label(rule.component)}", 16f)
            button("Clear this default") { defaults.remove(rule); render() }
        }
        button("Done") { finish() }
        val content = ScrollView(this).apply { isFillViewport = true; addView(body) }
        setContentView(content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content)
    }
}
