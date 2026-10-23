package com.infinifolder.standalone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import com.infinifolder.viewer.*

/** A standalone application host with no dependency on InfiniFolder's browser application. */
class ViewerHostActivity : ViewerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(Button(this).apply { text = "Open document"; setOnClickListener { pick() } })
        layout.addView(Button(this).apply { text = "Recovery copies"; setOnClickListener { startActivity(Intent(this@ViewerHostActivity, SaveRecoveryActivity::class.java)) } })
        layout.addView(Button(this).apply { text = "Diagnostics log"; setOnClickListener { startActivity(Intent(this@ViewerHostActivity, DiagnosticsActivity::class.java)) } })
        setContentView(layout)
        if (intent.data != null) open(intent)
    }
    private fun pick() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 901) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 901 && resultCode == RESULT_OK && data != null) open(data)
    }
    private fun open(source: Intent) {
        val uri = source.data ?: return
        val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
        val mime = source.type ?: contentResolver.getType(uri)
        val type = MediaTypeDetector.detect(name, mime)
        val edit = source.action == Intent.ACTION_EDIT
        if (edit && !MediaTypeDetector.isEditable(type)) {
            Toast.makeText(this, "Editing is not supported for this format", Toast.LENGTH_LONG).show(); return
        }
        val ref = com.infinifolder.storage.StorageRuntime.get(this).provider.refForUri(uri)
            ?: run { Toast.makeText(this, "No storage provider for this document", Toast.LENGTH_LONG).show(); return }
        val entry = com.infinifolder.model.ResourceEntry(ref, com.infinifolder.model.ResourceMetadata(name, mime.orEmpty(), null, null, false), uri)
        val launch = ViewerLaunch.intent(this, entry, type, edit = edit)
            ?: run { Toast.makeText(this, "No viewer for this document", Toast.LENGTH_LONG).show(); return }
        launch.addFlags(source.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { startActivity(launch) } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "This viewer is not included in this application", Toast.LENGTH_LONG).show()
        }
    }
}
