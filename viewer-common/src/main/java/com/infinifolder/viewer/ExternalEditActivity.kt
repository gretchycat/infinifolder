package com.infinifolder.viewer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.*
import com.infinifolder.storage.*
import java.io.IOException

/** External program handoff, explicit completion and conflict decisions; independent of the browser. */
class ExternalEditActivity : ViewerActivity() {
    private lateinit var store: ExternalEdits
    private var session: ExternalEdits.Session? = null
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var busy = false
    private var pendingCopyName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExternalEdits(this)
        val theme = com.infinifolder.theme.ThemeManager.activeTheme
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(theme.background) }
        status = TextView(this).apply { textSize = 17f; setTextColor(theme.textPrimary); text = "External file editing" }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        column.addView(status); column.addView(progress)
        setContentView(ScrollView(this).apply { addView(column) })
        val id = savedInstanceState?.getString("session") ?: intent.getStringExtra("session")
        pendingCopyName = savedInstanceState?.getString("copy_name")
        if (id != null) {
            session = store.find(id)
            showActions("Working copy retained. Close the external program, then finish editing to check and save it.")
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
        while (column.childCount > 2) column.removeViewAt(2)
        Thread({
            try { action() }
            catch (error: Exception) { runOnUiThread { if (!isFinishing && !isDestroyed) showFailure(error) } }
            finally { runOnUiThread { busy = false; if (!isDestroyed) progress.visibility = android.view.View.GONE } }
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
        status.text = (session?.name?.let { "$it\n\n" } ?: "") + message
        progress.visibility = android.view.View.GONE
        while (column.childCount > 2) column.removeViewAt(2)
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
        button("Finish editing and save") { complete(false) }
        button("Open external program again") { launchEditor() }
        button("Save with a new name…") { newName() }
        button("Copy to another folder…") { copyToFolder() }
        button("Keep edits for later") { finish() }
        button("Discard working copy…") { confirmDiscard() }
    }
    private fun showPending() {
        status.text = "Unfinished external edits"
        progress.visibility = android.view.View.GONE
        while (column.childCount > 2) column.removeViewAt(2)
        work("Loading unfinished edits…") {
            val pending = store.pending()
            runOnUiThread {
                status.text = if (pending.isEmpty()) "No unfinished external edits." else "Unfinished external edits"
                pending.forEach { item -> button(item.name + "\n" + item.ref.documentId) {
                    session = item
                    showActions("Working copy retained. Close the external program before saving.")
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
            showActions("When the external program is finished, return here and choose Finish editing and save.")
        } catch (error: Exception) { showFailure(error) }
    }
    @Deprecated("Legacy result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) {
            showActions("Close the external program, then finish editing to check the source and save your changes.")
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
        if (CacheFailureUi.offerCopy(this, error, intent.data ?: session?.ref?.documentId?.let(Uri::parse) ?: Uri.EMPTY)) {
            showActions("The file could not fit in the cache. Change the limits and reopen it, or save a copy.")
            return
        }
        showActions(error.message ?: "Operation failed. Your working copy is kept.")
        if (session != null && (error is ExternalSourceConflict || generateSequence(error.cause) { it.cause }.any { it is ExternalSourceConflict })) {
            val dialog = CacheDialogs.builder(this).setTitle("File changed at source")
                .setMessage("The file changed at the source while we were working on it. Your edited copy has been kept. Save with a new name, copy it to another location, or explicitly overwrite the source.")
                .setPositiveButton("Save as…") { _, _ -> newName() }
                .setNeutralButton("Copy elsewhere…") { _, _ -> copyToFolder() }
                .setNegativeButton("Overwrite…") { _, _ -> confirmOverwrite() }.create()
            dialog.show(); CacheDialogs.style(dialog)
        }
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
}
