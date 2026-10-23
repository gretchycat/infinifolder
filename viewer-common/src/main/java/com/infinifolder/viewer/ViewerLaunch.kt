package com.infinifolder.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.mime.MimeRegistry

/** Common document launch contract; compatibility extras keep existing consumers interoperable. */
object ViewerLaunch {
    fun intent(context: Context, entry: ResourceEntry, type: MediaType, parent: Uri? = null, edit: Boolean = false): Intent? {
        val activity = ViewerCatalog.activity(type) ?: return null
        return Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW).setClassName(context.packageName, activity).apply {
            setDataAndType(entry.uri, MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("uri", entry.uri.toString()); putExtra("extra_uri", entry.uri.toString())
            putExtra("displayName", entry.metadata.name); putExtra("extra_name", entry.metadata.name)
            putExtra("mimeType", this.type); putExtra("extra_mime", this.type)
            putExtra("edit", edit)
            parent?.let { putExtra("parentUri", it.toString()); putExtra("extra_parent_uri", it.toString()) }
            clipData = android.content.ClipData.newRawUri(entry.metadata.name, entry.uri)
        }
    }
    fun requestsEdit(intent: Intent) = intent.action == Intent.ACTION_EDIT || intent.getBooleanExtra("edit", false)
}
