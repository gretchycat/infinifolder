package com.infinifolder.viewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.*
import com.infinifolder.storage.DiagnosticLog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The folder picker can grant access to a Termux project/build directory through its document provider. */
class DiagnosticsActivity : ViewerActivity(), DebugDumpObserver {
    override fun onDebugDumpFinished() { refresh() }
    private lateinit var output: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.init(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        layout.addView(TextView(this).apply { text = "Diagnostics log"; textSize = 24f })
        layout.addView(Button(this).apply {
            text = "Choose debug dump folder"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, 903)
            }
        })
        layout.addView(Button(this).apply { text = "Offload debug information"; setOnClickListener { DebugTools.offload(this@DiagnosticsActivity) } })
        layout.addView(Button(this).apply { text = "Refresh log"; setOnClickListener { refresh() } })
        output = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        layout.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(layout)
        refresh()
    }
    private fun refresh() {
        output.text = "Debug dump: ${com.infinifolder.storage.DebugDumpDestination.get(this)?.documentId ?: "Choose a folder (for example your project's build directory)"}\n" +
            (DiagnosticLog.lastExportError?.let { "Last write failed: $it\n" } ?: "") + DiagnosticLog.snapshot(this).takeLast(64 * 1024)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 903 || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Thread {
            val result = runCatching {
                contentResolver.takePersistableUriPermission(tree, flags)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val provider = com.infinifolder.storage.StorageRuntime.get(this).provider
                val directory = provider.refForUri(parent) ?: error("Folder is not supported")
                com.infinifolder.storage.DebugDumpDestination.set(this, directory)
                val name = "infinifolder-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log"
                val file = DocumentsContract.createDocument(contentResolver, parent, "text/plain", name) ?: error("Could not create log file")
                DiagnosticLog.setDestination(this, file)
                DiagnosticLog.writeNow(this).getOrThrow()
            }
            runOnUiThread {
                Toast.makeText(this, if (result.isSuccess) "Log will be updated in the selected folder" else result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                refresh()
                if (result.isSuccess) DebugTools.onActivityResumed(this)
            }
        }.start()
    }
}
