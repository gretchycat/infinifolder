package com.infinifolder.viewer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.infinifolder.theme.ThemeChangeListener
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager
import android.widget.*
import com.infinifolder.storage.*
import java.io.IOException

/** External program handoff, explicit completion and conflict decisions; independent of the browser. */
class ExternalEditActivity : ViewerActivity(), ThemeChangeListener {
    private lateinit var store: ExternalEdits
    private var session: ExternalEdits.Session? = null
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private var busy = false
    private var pendingCopyName: String? = null
    private var pendingReview = false
    private var reviewingWindow = false
    private var reviewWindowAlpha = 1f
    private var reviewWindowFlags = 0
    private val reviewFlagMask = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.init(applicationContext)
        setTheme(if (ThemeManager.activeTheme.isDark) android.R.style.Theme_Material_Dialog_NoActionBar
            else android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        super.onCreate(savedInstanceState)
        store = ExternalEdits(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(12)) }
        title = TextView(this).apply { textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); text = "External file" }
        status = TextView(this).apply { textSize = 14f; text = "Preparing file…"; setPadding(0, dp(8), 0, dp(8)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        column.addView(title); column.addView(status); column.addView(progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        setContentView(object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                    (resources.displayMetrics.heightPixels * .8f).toInt(), View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; addView(column) })
        setFinishOnTouchOutside(false)
        window.setLayout(minOf(dp(480), (resources.displayMetrics.widthPixels * .92f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        ThemeManager.addListener(this)
        applyTheme(ThemeManager.activeTheme)
        val id = savedInstanceState?.getString("session") ?: intent.getStringExtra("session")
        pendingCopyName = savedInstanceState?.getString("copy_name")
        if (id != null) {
            session = store.find(id)
            if (session == null) finish() else reviewChanges()
        } else if (intent.data != null) {
            work("Preparing file…") {
                val prepared = store.prepare(intent.data!!, ::report)
                session = prepared
                runOnUiThread {
                    intent.putExtra("session", prepared.id)
                    launchEditor()
                }
            }
        } else showPending()
    }
    override fun onThemeChanged(theme: ThemeColors) { runOnUiThread { if (!isDestroyed) applyTheme(theme) } }
    private fun applyTheme(theme: ThemeColors) {
        window.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(theme.surface); cornerRadius = dp(theme.cornerRadiusDp.toInt()).toFloat()
        })
        column.setBackgroundColor(theme.surface)
        fun style(view: View) {
            if (view is TextView) view.setTextColor(if (view is Button) theme.buttonText else if (view === status) theme.textSecondary else theme.textPrimary)
            if (view is Button) view.backgroundTintList = android.content.res.ColorStateList.valueOf(theme.buttonBackground)
            if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
        }
        style(column)
        val tint = android.content.res.ColorStateList.valueOf(theme.accent)
        progress.progressTintList = tint; progress.indeterminateTintList = tint
    }
    private fun clearActions() { while (column.childCount > 3) column.removeViewAt(3) }
    private fun hideForReview() {
        if (reviewingWindow) return
        val attributes = window.attributes
        reviewWindowAlpha = attributes.alpha
        reviewWindowFlags = attributes.flags and reviewFlagMask
        attributes.alpha = 0f
        attributes.flags = (attributes.flags and reviewFlagMask.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        window.attributes = attributes
        reviewingWindow = true
    }
    private fun showAfterReview() {
        if (!reviewingWindow) return
        val attributes = window.attributes
        attributes.alpha = reviewWindowAlpha
        attributes.flags = (attributes.flags and reviewFlagMask.inv()) or reviewWindowFlags
        window.attributes = attributes
        reviewingWindow = false
    }
    private fun report(message: String, bytes: Long, total: Long?) {
        runOnUiThread {
            if (!isDestroyed) {
                status.text = message + if (total != null) "\n" + android.text.format.Formatter.formatShortFileSize(this, bytes) + " / " + android.text.format.Formatter.formatShortFileSize(this, total) else ""
                progress.isIndeterminate = total == null || total <= 0
                if (total != null && total > 0) progress.progress = (bytes.toDouble() / total * 100).toInt().coerceIn(0, 100)
            }
        }
    }
    private fun work(message: String, action: () -> Unit) {
        if (busy) return
        busy = true
        status.text = message
        progress.visibility = android.view.View.VISIBLE
        progress.isIndeterminate = true
        clearActions()
        Thread({
            try { action() }
            catch (error: Exception) { runOnUiThread { if (!isFinishing && !isDestroyed) showFailure(error) } }
            finally { runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    progress.visibility = View.GONE
                    if (pendingReview && !isFinishing) reviewChanges()
                }
            } }
        }, "ExternalEditSession").start()
    }
    private fun button(label: String, action: () -> Unit) {
        column.addView(Button(this).apply {
            text = label
            setTextColor(com.infinifolder.theme.ThemeManager.activeTheme.buttonText)
            backgroundTintList = android.content.res.ColorStateList.valueOf(com.infinifolder.theme.ThemeManager.activeTheme.buttonBackground)
            setOnClickListener { if (!busy) action() }
        })
    }
    private fun showActions(message: String) {
        if (isDestroyed || isFinishing) return
        showAfterReview()
        title.text = session?.name ?: "External file"
        status.text = message
        progress.visibility = android.view.View.GONE
        clearActions()
        if (session == null) {
            if (intent.data != null) button("Retry opening file") {
                work("Preparing file…") {
                    val prepared = store.prepare(intent.data!!, ::report)
                    session = prepared
                    runOnUiThread { intent.putExtra("session", prepared.id); launchEditor() }
                }
            }
            button("Unfinished external edits") { showPending() }
            return
        }
        button("Save changes") { complete(false) }
        button("Save a copy…") {
            val dialog = CacheDialogs.builder(this).setTitle("Save edited copy")
                .setItems(arrayOf("New name in original folder…", "Another folder…")) { _, which ->
                    if (which == 0) newName() else copyToFolder()
                }.create()
            dialog.show(); CacheDialogs.style(dialog)
        }
        button("More…") {
            val dialog = CacheDialogs.builder(this).setTitle("Edited file")
                .setItems(arrayOf("Open external program again", "Keep edits for later", "Discard edits…")) { _, which ->
                    when (which) { 0 -> launchEditor(); 1 -> finish(); else -> confirmDiscard() }
                }.create()
            dialog.show(); CacheDialogs.style(dialog)
        }
    }
    private fun reviewChanges() {
        pendingReview = true
        if (busy) return
        val item = session ?: return
        pendingReview = false
        // Keep the browser visible while checking. Read-only opens must not
        // flash a save dialog before we know that any local bytes changed.
        hideForReview()
        title.text = item.name
        work("Checking for local edits…") {
            val changed = store.reviewChanges(item, ::report)
            runOnUiThread {
                if (changed) showActions("This working copy has changed. Save it to the original location or keep a separate copy.")
                else finish()
            }
        }
    }
    private fun showPending() {
        status.text = "Unfinished external edits"
        progress.visibility = android.view.View.GONE
        title.text = "Unfinished external edits"
        clearActions()
        work("Loading unfinished edits…") {
            val pending = store.pending()
            runOnUiThread {
                status.text = if (pending.isEmpty()) "No unfinished external edits." else "Unfinished external edits"
                pending.forEach { item -> button(item.name + "\n" + item.ref.documentId) {
                    session = item
                    reviewChanges()
                } }
            }
        }
    }
    private fun launchEditor() {
        val item = session ?: return
        try {
            store.reopen(item)
            val uri = store.uri(item)
            val handoff = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifBlank { "application/octet-stream" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            handoff.clipData = android.content.ClipData.newRawUri(item.name, uri)
            startActivityForResult(Intent.createChooser(handoff, "Open ${item.name}"), 701)
            title.text = item.name
            status.text = "Open in external program"
            progress.visibility = View.GONE
            clearActions()
        } catch (error: Exception) { showFailure(error) }
    }
    @Deprecated("Legacy result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) {
            reviewChanges()
        } else if (requestCode == 702 && resultCode == RESULT_OK && data?.data != null) {
            val target = data.data!!
            val name = pendingCopyName ?: session?.name ?: "edited-file"
            pendingCopyName = null
            copy(target, name)
        }
    }
    private fun complete(overwrite: Boolean) {
        val item = session ?: return
        work("Checking edited file…") {
            val changed = store.finish(item, overwrite, ::report)
            store.discard(item)
            runOnUiThread {
                Toast.makeText(this, if (changed) "Edited file saved to source" else "No local changes; source kept", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    private fun showFailure(error: Exception) {
        showAfterReview()
        if (session == null && CacheFailureUi.offerCopy(this, error, intent.data ?: Uri.EMPTY)) {
            showActions("The file could not fit in the cache. Change the limits and reopen it, or save a copy.")
            return
        }
        if (session == null) { showActions(error.message ?: "Could not open this file."); return }
        title.text = session!!.name
        status.text = error.message ?: "Operation failed. Your working copy is kept."
        progress.visibility = View.GONE
        clearActions()
        if (session != null && (error is ExternalSourceConflict || generateSequence(error.cause) { it.cause }.any { it is ExternalSourceConflict })) {
            status.text = "The original file's contents changed at its storage location while you were editing. Your edited copy is kept."
            button("Save with a new name…") { newName() }
            button("Copy elsewhere…") { copyToFolder() }
            button("Overwrite original…") { confirmOverwrite() }
        } else button("Check again") { reviewChanges() }
        button("Keep for later") { finish() }
    }
    private fun confirmOverwrite() {
        val dialog = CacheDialogs.builder(this).setTitle("Overwrite source?")
            .setMessage("Replace the current source file with your edited copy?")
            .setPositiveButton("Overwrite") { _, _ -> complete(true) }.setNegativeButton("Cancel", null).create()
        dialog.show(); CacheDialogs.style(dialog)
    }
    private fun newName() {
        val item = session ?: return
        val builder = CacheDialogs.builder(this)
        val input = EditText(builder.context).apply { inputType = InputType.TYPE_CLASS_TEXT; setText(item.name) }
        val dialog = builder.setTitle("Save with a new name").setView(input)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                try { StorageSafety.validateName(name) } catch (error: Exception) { input.error = error.message; return@setOnClickListener }
                if (name == item.name) { input.error = "Choose a different filename"; return@setOnClickListener }
                dialog.dismiss()
                work("Finding source folder…") {
                    val parent = store.parent(item) ?: throw IOException("The source folder is unavailable; copy to another folder instead")
                    store.copy(item, parent, name, ::report)
                    store.discard(item)
                    runOnUiThread { Toast.makeText(this, "Edited copy saved", Toast.LENGTH_LONG).show(); finish() }
                }
            }
        }
        dialog.show(); CacheDialogs.style(dialog)
    }
    private fun copyToFolder() {
        pendingCopyName = session?.name
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION), 702)
    }
    private fun copy(target: Uri, name: String) {
        val item = session ?: return
        work("Saving edited copy…") {
            store.copy(item, target, name, ::report)
            store.discard(item)
            runOnUiThread { Toast.makeText(this, "Edited copy saved", Toast.LENGTH_LONG).show(); finish() }
        }
    }
    private fun confirmDiscard() {
        val dialog = CacheDialogs.builder(this).setTitle("Discard working copy?")
            .setMessage("Discard these local edits? The source will not be changed.")
            .setPositiveButton("Discard") { _, _ -> work("Discarding working copy…") { session?.let(store::discard); runOnUiThread { finish() } } }
            .setNegativeButton("Keep", null).create()
        dialog.show(); CacheDialogs.style(dialog)
    }
    @Deprecated("Legacy Back")
    override fun onBackPressed() {
        if (busy) { Toast.makeText(this, "Please wait; the working copy is being checked or saved", Toast.LENGTH_SHORT).show(); return }
        // Returning to the browser never silently discards unsaved edits.
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        session?.let { outState.putString("session", it.id) }
        outState.putString("copy_name", pendingCopyName)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { ThemeManager.removeListener(this); super.onDestroy() }
}
