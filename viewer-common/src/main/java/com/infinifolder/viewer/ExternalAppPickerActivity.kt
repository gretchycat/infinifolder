package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.theme.ThemeManager

/** Local file associations require an app-owned picker; Android's chooser has no local-default checkbox. */
class ExternalAppPickerActivity : Activity() {
    private data class Choice(val info: ResolveInfo?, val action: String, val label: String, val internal: Intent? = null) {
        val component get() = internal?.component ?: ComponentName(info!!.activityInfo.packageName, info.activityInfo.name)
    }
    private lateinit var target: Intent
    private lateinit var defaults: ExternalAppDefaults
    private var remember: CheckBox? = null
    private var scope: RadioGroup? = null
    private var dialog: AlertDialog? = null
    private var savedRemember = false
    private var savedFileScope = false
    private var launched = false
    private val sourceKey get() = intent.getStringExtra(ExternalAppChooser.SOURCE)
    private val fileName get() = intent.getStringExtra(ExternalAppChooser.NAME).orEmpty()
    private val selectingEditor get() = intent.getBooleanExtra(ExternalAppChooser.SELECT_EDITOR, false)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        setTheme(com.infinifolder.viewer.common.R.style.ExternalEditHandoffTheme)
        super.onCreate(state)
        if (state?.getBoolean("launched", false) == true ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) { finish(); return }
        ThemeManager.init(applicationContext)
        @Suppress("DEPRECATION")
        val requested = intent.getParcelableExtra<Intent>(ExternalAppChooser.TARGET)
        if (requested == null || requested.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT)) { finish(); return }
        target = requested
        defaults = ExternalAppDefaults(this)
        savedRemember = state?.getBoolean("remember") ?: false
        savedFileScope = state?.getBoolean("file_scope") ?: false
        // A restored picker may show choices, but must not repeat its old target automatically.
        loadChoices(useDefault = state == null &&
            !intent.getBooleanExtra(ExternalAppChooser.FORCE_CHOOSER, false) && !selectingEditor)
    }
    private fun choices(): List<Choice> {
        val actions = if (target.action == Intent.ACTION_EDIT) listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW) else listOf(Intent.ACTION_VIEW)
        val external = actions.flatMap { action ->
            val query = Intent(target).setAction(action).setComponent(null).setPackage(null)
            if (intent.getBooleanExtra(ExternalAppChooser.STAGE_EXTERNAL, false)) {
                // External receivers will get a grantable working-copy content URI,
                // including for archive/network sources. This URI is for matching only.
                query.setDataAndType(android.net.Uri.parse("content://$packageName.external-edit/choice/${android.net.Uri.encode(fileName)}"), target.type)
            }
            if (selectingEditor && action == Intent.ACTION_VIEW) query.setDataAndType(target.data, "text/plain")
            packageManager.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName != packageName && it.activityInfo.exported &&
                    it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
                .map { Choice(it, action, it.loadLabel(packageManager).toString()) }
        }.distinctBy { it.component }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        @Suppress("DEPRECATION")
        val internal = intent.getParcelableExtra<Intent>(ExternalAppChooser.INTERNAL_TARGET)
            ?.takeIf { candidate -> !selectingEditor && target.action == Intent.ACTION_VIEW &&
                candidate.component?.packageName == packageName && candidate.data == target.data &&
                ViewerComponents.registry.query(io.github.gretchycat.infinistream.Operations.VIEW).any {
                    it.handler.service(ViewerComponent::class.java)?.activityClassName == candidate.component?.className
                } }
        return listOfNotNull(internal?.let { Choice(null, Intent.ACTION_VIEW, "Open internally", it) }) + external
    }
    private fun loadChoices(useDefault: Boolean) {
        Thread({
            try {
                val available = choices()
                val preferred = if (useDefault) defaults.candidates(target.action!!, sourceKey, target.type)
                    .firstNotNullOfOrNull { component -> available.firstOrNull { it.component == component } } else null
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (preferred != null) launch(preferred, available, rememberChoice = false)
                    else showChoices(available)
                }
            } catch (error: Exception) {
                DiagnosticLog.record("external-app.query-failed", error = error)
                runOnUiThread { if (!isFinishing && !isDestroyed) showChoices(emptyList(), "Could not list installed apps. Close this picker and try again.") }
            }
        }, "ExternalAppChoices").start()
    }
    private fun showChoices(available: List<Choice>, failure: String? = null) {
        dialog?.dismiss()
        val builder = CacheDialogs.builder(this)
        val colors = ThemeManager.activeTheme
        val body = LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        body.addView(TextView(builder.context).apply {
            text = failure ?: if (available.isEmpty()) "No other installed app can handle this file."
                else if (selectingEditor) "Used by Edit externally. Incompatible file types will still ask."
                else "File type: ${MimeRegistry.normalize(target.type)}"
            setTextColor(colors.textSecondary); setPadding(0, dp(4), 0, dp(8))
        })
        if (available.isNotEmpty()) {
            body.addView(ListView(builder.context).apply {
                adapter = object : BaseAdapter() {
                    override fun getCount() = available.size
                    override fun getItem(position: Int) = available[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                        val choice = available[position]
                        return LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(8), 0, dp(8))
                            addView(ImageView(context).apply {
                                setImageDrawable(choice.info?.loadIcon(packageManager) ?: applicationInfo.loadIcon(packageManager)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, LinearLayout.LayoutParams(dp(40), dp(40)))
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                                addView(TextView(context).apply { text = choice.label; textSize = 16f; setTextColor(colors.textPrimary) })
                                addView(TextView(context).apply { text = if (choice.internal != null) "Built-in viewer" else choice.component.packageName; textSize = 12f; setTextColor(colors.textSecondary) })
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                        }
                    }
                }
                setOnItemClickListener { _, _, position, _ -> launch(available[position], available, remember?.isChecked == true) }
            }, LinearLayout.LayoutParams(-1, minOf(dp(300), resources.displayMetrics.heightPixels / 3)))
        }
        if (!selectingEditor && available.isNotEmpty()) {
            remember = CheckBox(builder.context).apply {
                text = "Remember in InfiniFolder"; isChecked = savedRemember; setTextColor(colors.textPrimary)
            }
            scope = RadioGroup(builder.context).apply {
                orientation = RadioGroup.VERTICAL
                addView(RadioButton(context).apply { id = 1; text = "This file only"; isEnabled = sourceKey != null; setTextColor(colors.textPrimary) })
                addView(RadioButton(context).apply { id = 2; text = "All files of this type"; setTextColor(colors.textPrimary) })
                check(if (savedFileScope && sourceKey != null) 1 else 2)
                visibility = if (savedRemember) View.VISIBLE else View.GONE
            }
            remember!!.setOnCheckedChangeListener { _, checked -> scope?.visibility = if (checked) View.VISIBLE else View.GONE }
            body.addView(remember); body.addView(scope)
        }
        dialog = builder.setTitle(intent.getCharSequenceExtra(ExternalAppChooser.TITLE) ?: "Open with…")
            .setView(ScrollView(builder.context).apply { addView(body) })
            .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }
            .create().also { it.show(); CacheDialogs.style(it) }
    }
    private fun launch(choice: Choice, available: List<Choice>, rememberChoice: Boolean) {
        if (choice.component.packageName == packageName && choice.internal == null) return
        if (selectingEditor) {
            defaults.editor = choice.component
            setResult(RESULT_OK); finish(); return
        }
        try {
            // The original data, MIME, flags and ClipData grants survive explicit selection.
            val launch = choice.internal?.let(::Intent) ?: if (intent.getBooleanExtra(ExternalAppChooser.STAGE_EXTERNAL, false)) {
                Intent(this, ExternalEditActivity::class.java).setData(target.data)
                    .putExtra(ExternalAppChooser.EXTERNAL_COMPONENT, choice.component.flattenToString())
                    .putExtra(ExternalAppChooser.EXTERNAL_ACTION, choice.action)
            } else Intent(target).setAction(choice.action).setComponent(choice.component).setPackage(null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            if (rememberChoice) defaults.remember(target.action!!,
                if (scope?.checkedRadioButtonId == 1) ExternalAppDefaults.FILE else ExternalAppDefaults.TYPE,
                sourceKey, target.type, fileName, choice.component)
            launched = true
            intent.removeExtra(ExternalAppChooser.TARGET)
            DiagnosticLog.record("external-app.launched", "action=${choice.action} remembered=$rememberChoice")
            finish()
        } catch (error: Exception) {
            DiagnosticLog.record("external-app.launch-failed", error = error)
            showChoices(available, "That app could not open this file. Choose another app.")
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("remember", remember?.isChecked ?: savedRemember)
        outState.putBoolean("file_scope", scope?.checkedRadioButtonId == 1)
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { dialog?.dismiss(); super.onDestroy() }
}
