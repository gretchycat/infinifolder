package com.infinifolder

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.storage.ExternalEdits
import com.infinifolder.storage.StorageRuntime
import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.model.ProviderResult
import com.infinifolder.viewer.ExternalEditActivity
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

private object TermuxEditHosts {
    val activities = ConcurrentHashMap<String, WeakReference<Activity>>()
    fun close(id: String) { activities.remove(id)?.get()?.finish() }
}
private object TermuxEditFiles {
    fun file(activity: Activity, session: ExternalEdits.Session): File {
        val root = activity.externalMediaDirs.firstOrNull() ?: throw java.io.IOException("Shared storage is unavailable")
        val name = session.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        if (name == "." || name == "..") throw java.io.IOException("Invalid file name")
        return File(File(File(root, ".termux-edits"), session.id), name)
    }
    fun prepare(activity: Activity, session: ExternalEdits.Session): File {
        val target = file(activity, session)
        if (!target.parentFile!!.mkdirs()) throw java.io.IOException("Cannot create a shared Termux working folder")
        if (!target.createNewFile()) throw java.io.IOException("The Termux working file already exists")
        session.working.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
        if (target.inputStream().use(ContentRevision::read) != session.baseline.revision)
            throw java.io.IOException("The shared working copy failed verification")
        return target
    }
}

/** Receives a file explicitly selected for a configured Termux editor. */
class TermuxEditActivity : Activity() {
    private var sessionId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val status = TextView(this).apply { text = "Preparing file for Termux…"; setPadding(32, 32, 32, 32) }
        setContentView(status)
        sessionId = savedInstanceState?.getString("session")
        sessionId?.let {
            TermuxEditHosts.activities[it] = WeakReference(this)
            status.text = "Waiting for the Termux editor to finish…"
            return
        }
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) runCatching {
            contentResolver.takePersistableUriPermission(uri, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
        Thread {
            val store = ExternalEdits(this)
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
                val session = store.prepare(uri) { message, _, _ -> runOnUiThread { if (!isDestroyed) status.text = message } }
                prepared = session
                sessionId = session.id
                TermuxEditHosts.activities[session.id] = WeakReference(this)
                val workingFile = TermuxEditFiles.prepare(this, session)
                store.reserveExternalTool(session, workingFile.absolutePath)
                val resultIntent = Intent(this, TermuxEditResultActivity::class.java).setAction("com.infinifolder.TERMUX_EDIT_RESULT")
                    .setData(Uri.Builder().scheme("if-termux-result").authority("session").appendPath(session.id).build())
                val flags = PendingIntent.FLAG_ONE_SHOT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getActivity(this, 0, resultIntent, flags)
                val args = arrayOf("-c", SCRIPT, "infinifolder", workingFile.absolutePath, rule.command, session.baseline.revision.sha256)
                DiagnosticLog.record("termux.edit.prepared", "session=${session.id} bytes=${session.baseline.revision.size}")
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    TermuxCommandLink.start(this, args, null, result) { started ->
                        if (!started) {
                            // No editor has run; the unchanged session can safely be discarded.
                            Thread { runCatching { store.releaseExternalTool(session); store.discard(session); workingFile.delete(); workingFile.parentFile?.delete() } }.start()
                        }
                        if (started) status.text = "Waiting for the Termux editor to finish…" else finish()
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
            file="${'$'}1"; editor="${'$'}2"; expected="${'$'}3"
            [ -r "${'$'}file" ] && [ -w "${'$'}file" ] || { echo "Termux cannot access the shared working file; check its storage permission."; exit 1; }
            actual="${'$'}(sha256sum -- "${'$'}file")"; actual="${'$'}{actual%% *}"
            [ "${'$'}actual" = "${'$'}expected" ] || { echo "Could not read the complete working copy."; exit 1; }
            /data/data/com.termux/files/usr/bin/bash -lc "${'$'}editor \"\${'$'}1\"" infini-editor "${'$'}file"
        """.trimIndent()
    }
}

/** Non-exported callback, invoked only through Termux's one-shot pending intent. */
class TermuxEditResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.data?.lastPathSegment ?: run { finish(); return }
        val result = intent.getBundleExtra("result")
        val store = ExternalEdits(this)
        val item = store.find(id) ?: run { finish(); return }
        val workingFile = TermuxEditFiles.file(this, item)
        DiagnosticLog.record("termux.edit.result", "session=$id resultPresent=${result != null} error=${result?.getInt("err", -1)} exit=${result?.getInt("exitCode", 1)}")
        if (result == null || result.getInt("err", -1) != -1 || result.getInt("exitCode", 1) != 0) {
            android.app.AlertDialog.Builder(this).setTitle("Termux editor did not finish")
                .setMessage("The source was not changed. The working copy is kept at ${workingFile.absolutePath}. Check Termux's storage permission if the editor could not access it.")
                .setPositiveButton("OK") { _, _ -> TermuxEditHosts.close(id); finish() }
                .setOnCancelListener { TermuxEditHosts.close(id); finish() }.show()
            return
        }
        val status = TextView(this).apply { text = "Checking edited file…"; setPadding(32, 32, 32, 32) }
        setContentView(status)
        Thread {
            try {
                store.importExternalToolFile(item, workingFile)
                val changed = store.finish(item, false) { message, _, _ -> runOnUiThread { if (!isDestroyed) status.text = message } }
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
