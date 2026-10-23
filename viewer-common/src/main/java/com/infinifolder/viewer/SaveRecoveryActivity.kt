package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.*
import java.io.File

/** Recovery remains available even when the browser has never been opened in this process. */
class SaveRecoveryActivity : ViewerActivity() {
    private val recoveryRoot get() = File(filesDir, "save_recovery")
    private var selectedSession: String? = null
    private lateinit var list: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedSession = savedInstanceState?.getString("selected_session")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(list) })
        refresh()
    }
    private fun refresh() {
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "Saved recovery copies"; textSize = 24f })
        Thread {
            val records = SaveRecovery.pending(recoveryRoot)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (records.isEmpty()) list.addView(TextView(this).apply { text = "No interrupted saves or recovery copies." })
                records.forEach { record ->
                    list.addView(Button(this).apply {
                        text = "${record.ref.documentId}\n${record.state} · ${record.revision.size} bytes"
                        setOnClickListener { actions(record) }
                    })
                }
            }
        }.start()
    }
    private fun actions(record: SaveRecovery.Record) {
        AlertDialog.Builder(this).setTitle("Recovery copy")
            .setItems(arrayOf("Save backup as a new file", "Restore original location", "Discard recovery copy")) { _, choice ->
                when (choice) {
                    0 -> {
                        selectedSession = record.directory.name
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, "recovered_${record.directory.name}.bin")
                        }, 902)
                    }
                    1 -> prepareRestore(record)
                    2 -> AlertDialog.Builder(this).setMessage("Discard this recovery copy? The source file will be kept.")
                        .setPositiveButton("Discard") { _, _ -> record.directory.deleteRecursively(); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }
    private fun prepareRestore(record: SaveRecovery.Record) {
        Thread {
            val provider = StorageRuntime.get(this).provider
            val current = runCatching {
                val read = provider.openRead(record.ref)
                if (read !is ProviderResult.Success) error("Original location is not readable; export the backup instead")
                read.value.use(ContentRevision::read)
            }
            runOnUiThread {
                current.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                current.onSuccess { expected ->
                    AlertDialog.Builder(this).setTitle("Restore backup?")
                        .setMessage("Replace the current content at ${record.ref.documentId} with the recovery copy?")
                        .setPositiveButton("Restore") { _, _ ->
                            Thread {
                                val result = runCatching { SaveRecovery.restore(provider, record, expected, recoveryRoot) }
                                    .getOrElse { ProviderResult.Failure(it.message ?: "Recovery failed", it) }
                                runOnUiThread {
                                    Toast.makeText(this, if (result is ProviderResult.Success) "Original restored" else
                                        (result as? ProviderResult.Failure)?.message ?: "Recovery is unsupported", Toast.LENGTH_LONG).show()
                                    refresh()
                                }
                            }.start()
                        }.setNegativeButton("Cancel", null).show()
                }
            }
        }.start()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 902 || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        val session = selectedSession ?: return
        Thread {
            val result = runCatching {
                val record = SaveRecovery.pending(recoveryRoot).firstOrNull { it.directory.name == session } ?: error("Recovery copy unavailable")
                record.original.inputStream().use { input ->
                    val output = contentResolver.openOutputStream(destination, "wt") ?: error("Cannot create recovery export")
                    output.use { input.copyTo(it) }
                }
            }
            runOnUiThread { Toast.makeText(this, if (result.isSuccess) "Backup exported; recovery copy retained" else result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show() }
        }.start()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_session", selectedSession)
        super.onSaveInstanceState(outState)
    }
}
