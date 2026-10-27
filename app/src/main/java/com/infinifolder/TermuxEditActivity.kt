package com.infinifolder

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.storage.ExternalEdits
import com.infinifolder.storage.StorageRuntime
import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.model.ProviderResult
import com.infinifolder.viewer.ExternalEditActivity
import com.infinifolder.theme.ThemeManager
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

private object TermuxEditHosts {
    val activities = ConcurrentHashMap<String, WeakReference<Activity>>()
    fun close(id: String) { activities.remove(id)?.get()?.finish() }
}
/** Visible, themed feedback while preparing an edit or saving its results. */
private class TermuxProgress(activity: Activity, message: String) {
    private val theme = ThemeManager.activeTheme
    private val status = TextView(activity).apply {
        text = message; textSize = 16f; gravity = Gravity.CENTER; setTextColor(theme.textPrimary)
    }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        progressTintList = ColorStateList.valueOf(theme.accent)
        indeterminateTintList = ColorStateList.valueOf(theme.accent)
    }
    init {
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        activity.window.setBackgroundDrawable(GradientDrawable().apply {
            setColor(theme.surface); cornerRadius = dp(theme.cornerRadiusDp.toInt()).toFloat()
        })
        activity.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(16) })
        })
        activity.window.setLayout(minOf(dp(420), (activity.resources.displayMetrics.widthPixels * .92f).toInt()),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    fun update(message: String, bytes: Long = 0, total: Long? = null) {
        val hasTotal = total != null && total > 0
        status.text = message
        progress.isIndeterminate = !hasTotal
        if (hasTotal) progress.progress = (bytes.toDouble() * 100 / total!!.toDouble()).toInt().coerceIn(0, 100)
    }
}
private object TermuxEditFiles {
    fun file(activity: Activity, session: ExternalEdits.Session): File {
        val root = activity.externalMediaDirs.firstOrNull() ?: throw java.io.IOException("Shared storage is unavailable")
        val name = session.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        if (name == "." || name == "..") throw java.io.IOException("Invalid file name")
        return File(File(File(root, ".termux-edits"), session.id), name)
    }
    fun prepare(activity: Activity, session: ExternalEdits.Session, progress: (String, Long, Long?) -> Unit): File {
        val target = file(activity, session)
        if (!target.parentFile!!.mkdirs()) throw java.io.IOException("Cannot create a shared Termux working folder")
        if (!target.createNewFile()) throw java.io.IOException("The Termux working file already exists")
        val size = session.baseline.revision.size
        progress("Preparing the Termux working copy…", 0, size)
        session.working.inputStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(128 * 1024)
            var copied = 0L
            var lastUpdate = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count); copied += count
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastUpdate >= 250) { progress("Preparing the Termux working copy…", copied, size); lastUpdate = now }
            }
            output.fd.sync()
        } }
        progress("Checking the Termux working copy…", 0, null)
        if (target.inputStream().use(ContentRevision::read) != session.baseline.revision)
            throw java.io.IOException("The shared working copy failed verification")
        return target
    }
}

/** Receives a file explicitly selected for a configured Termux editor. */
class TermuxEditActivity : Activity() {
    private var sessionId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.init(applicationContext)
        setTheme(if (ThemeManager.activeTheme.isDark) android.R.style.Theme_Material_Dialog_NoActionBar
            else android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val status = TermuxProgress(this, "Preparing file for Termux…")
        sessionId = savedInstanceState?.getString("session")
        sessionId?.let {
            TermuxEditHosts.activities[it] = WeakReference(this)
            status.update("Waiting for the Termux editor to finish…")
            return
        }
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) runCatching {
            contentResolver.takePersistableUriPermission(uri, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
        Thread {
            val store = ExternalEdits(this)
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var prepared: ExternalEdits.Session? = null
            try {
                val provider = StorageRuntime.get(this).provider
                val ref = provider.refForUri(uri)
                val entry = ref?.let { (provider.stat(it) as? ProviderResult.Success)?.value }
                val name = entry?.metadata?.name ?: runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                val rule = TermuxAssociations(this).find(name)
                    ?: throw java.io.IOException("No Termux association matches $name. Add one in Settings → Termux file associations.")
                when (TermuxEditorAvailability.state(this, rule.command)) {
                    TermuxEditorAvailability.State.MISSING -> throw java.io.IOException("The configured Termux editor is not installed. Choose another in Settings → Termux file associations.")
                    TermuxEditorAvailability.State.UNKNOWN -> throw java.io.IOException("The Termux editor could not be verified. Open InfiniFolder and check Settings → Termux file associations.")
                    TermuxEditorAvailability.State.AVAILABLE -> Unit
                }
                val session = store.prepare(uri) { message, bytes, total -> runOnUiThread { if (!isDestroyed) status.update(message, bytes, total) } }
                val stagedAt = android.os.SystemClock.elapsedRealtime()
                prepared = session
                sessionId = session.id
                TermuxEditHosts.activities[session.id] = WeakReference(this)
                val workingFile = TermuxEditFiles.prepare(this, session) { message, bytes, total ->
                    runOnUiThread { if (!isDestroyed) status.update(message, bytes, total) }
                }
                store.reserveExternalTool(session, workingFile.absolutePath)
                val resultIntent = Intent(this, TermuxEditResultActivity::class.java).setAction("com.infinifolder.TERMUX_EDIT_RESULT")
                    .setData(Uri.Builder().scheme("if-termux-result").authority("session").appendPath(session.id).build())
                    .putExtra("editorKey", TermuxEditorAvailability.key(rule.command))
                val flags = PendingIntent.FLAG_ONE_SHOT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getActivity(this, 0, resultIntent, flags)
                val executable = TermuxEditorAvailability.executable(rule.command)
                    ?: throw java.io.IOException("The configured Termux editor cannot be verified")
                val args = arrayOf("-c", SCRIPT, "infinifolder", workingFile.absolutePath, rule.command, session.baseline.revision.sha256, executable)
                DiagnosticLog.record("termux.edit.prepared", "session=${session.id} bytes=${session.baseline.revision.size} " +
                    "sourceStageMs=${stagedAt - startedAt} sharedStageMs=${android.os.SystemClock.elapsedRealtime() - stagedAt}")
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    TermuxCommandLink.start(this, args, null, result) { started ->
                        if (!started) {
                            // No editor has run; the unchanged session can safely be discarded.
                            Thread { runCatching { store.releaseExternalTool(session); store.discard(session); workingFile.delete(); workingFile.parentFile?.delete() } }.start()
                        }
                        if (started) status.update("Opening Termux…") else finish()
                    }
                }
            } catch (error: Exception) {
                DiagnosticLog.record("termux.edit.prepare-failed", error = error)
                prepared?.let { item ->
                    runCatching { store.releaseExternalTool(item); store.discard(item) }
                    runCatching { TermuxEditFiles.file(this, item).let { it.delete(); it.parentFile?.delete() } }
                }
                runOnUiThread { Toast.makeText(this, error.message ?: "Could not open file in Termux", Toast.LENGTH_LONG).show(); finish() }
            }
        }.start()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        sessionId?.let { outState.putString("session", it) }
        super.onSaveInstanceState(outState)
    }
    companion object {
        // Values are passed as separate arguments; the filename is never interpolated into shell code.
        private val SCRIPT = """
            set -e
            file="${'$'}1"; editor="${'$'}2"; expected="${'$'}3"; executable="${'$'}4"
            command -v -- "${'$'}executable" >/dev/null 2>&1 || { echo "The configured Termux editor is not installed."; exit 127; }
            [ -r "${'$'}file" ] && [ -w "${'$'}file" ] || { echo "Termux cannot access the shared working file; check its storage permission."; exit 1; }
            actual="${'$'}(sha256sum -- "${'$'}file")"; actual="${'$'}{actual%% *}"
            [ "${'$'}actual" = "${'$'}expected" ] || { echo "Could not read the complete working copy."; exit 1; }
            set +e
            /data/data/com.termux/files/usr/bin/bash -lc "${'$'}editor \"\${'$'}1\"" infini-editor "${'$'}file"
            rc="${'$'}?"
            [ "${'$'}rc" -ne 127 ] || rc=126
            exit "${'$'}rc"
        """.trimIndent()
    }
}

/** Non-exported callback, invoked only through Termux's one-shot pending intent. */
class TermuxEditResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.init(applicationContext)
        setTheme(if (ThemeManager.activeTheme.isDark) android.R.style.Theme_Material_Dialog_NoActionBar
            else android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        super.onCreate(savedInstanceState)
        val id = intent.data?.lastPathSegment ?: run { finish(); return }
        val result = intent.getBundleExtra("result")
        val store = ExternalEdits(this)
        val item = store.find(id) ?: run { TermuxEditHosts.close(id); finish(); return }
        val workingFile = TermuxEditFiles.file(this, item)
        DiagnosticLog.record("termux.edit.result", "session=$id resultPresent=${result != null} error=${result?.getInt("err", -1)} exit=${result?.getInt("exitCode", 1)}")
        if (result == null || result.getInt("err", -1) != -1 || result.getInt("exitCode", 1) != 0) {
            val missing = result != null && result.getInt("err", -1) == -1 && result.getInt("exitCode", 1) == 127
            if (missing) intent.getStringExtra("editorKey")?.let { TermuxEditorAvailability.markMissing(this, it) }
            android.app.AlertDialog.Builder(this).setTitle("Termux editor did not finish")
                .setMessage((if (missing) "The configured editor is no longer available. " else "Check Termux's storage permission if it could not access the working copy. ") +
                    "The source was not changed. The working copy is kept at ${workingFile.absolutePath}.")
                .setPositiveButton("OK") { _, _ -> TermuxEditHosts.close(id); finish() }
                .setOnCancelListener { TermuxEditHosts.close(id); finish() }.show()
            return
        }
        val status = TermuxProgress(this, "Checking edited file…")
        Thread {
            try {
                store.importExternalToolFile(item, workingFile)
                val changed = store.finish(item, false) { message, bytes, total -> runOnUiThread { if (!isDestroyed) status.update(message, bytes, total) } }
                store.discard(item)
                workingFile.delete(); workingFile.parentFile?.delete()
                runOnUiThread { TermuxEditHosts.close(id); Toast.makeText(this, if (changed) "Changes saved" else "No changes", Toast.LENGTH_SHORT).show(); finish() }
            } catch (error: Exception) {
                DiagnosticLog.record("termux.edit.save-failed", "session=$id", error)
                runOnUiThread {
                    TermuxEditHosts.close(id)
                    Toast.makeText(this, error.message ?: "The edited copy needs review", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, ExternalEditActivity::class.java).putExtra("session", id)); finish()
                }
            }
        }.start()
    }
}
