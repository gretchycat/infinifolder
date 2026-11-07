package com.infiniscribe

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import io.github.gretchycat.infinistream.CapabilityResource
import io.github.gretchycat.infinistream.ContentType
import io.github.gretchycat.infinistream.StreamMetadata
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.viewer.ViewerComponents
import io.github.gretchycat.infiniselect.InfiniSelect
import io.github.gretchycat.infiniselect.OpenRequest
import io.github.gretchycat.infiniselect.FileSelectionContract

/** Standalone single-document host for the Scribe-backed viewer components. */
class InfiniScribeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDocumentIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDocumentIntent(intent)
    }

    private fun chooseDocument() {
        InfiniSelect.open(this, OpenRequest(
            clientId = "infiniscribe",
            displayName = "InfiniScribe",
            mimeTypes = listOf("*/*"),
            allowMultiple = false,
        ), REQUEST_OPEN_DOCUMENT)
    }

    @Deprecated("Activity result contract kept compatible with the platform-minimum runtime")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_DOCUMENT) return
        if (resultCode != RESULT_OK || data?.data == null) {
            finish()
            return
        }
        val uri = data.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
        openDocument(uri, Intent.ACTION_VIEW, data.flags,
            data.getStringExtra(FileSelectionContract.EXTRA_RESULT_MIME) ?: data.type,
            data.getStringExtra(FileSelectionContract.EXTRA_RESULT_NAME) ?: data.getStringExtra("displayName"))
    }

    private fun handleDocumentIntent(incoming: Intent?) {
        if (incoming == null || incoming.action == Intent.ACTION_MAIN || incoming.action == null) {
            chooseDocument()
            return
        }
        if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
            Toast.makeText(this, "InfiniScribe opens one document at a time.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (incoming.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND)) {
            chooseDocument()
            return
        }
        val uri = incoming.data ?: incoming.getParcelableExtraCompat(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "The sending app did not provide a document reference.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        openDocument(uri, if (incoming.action == Intent.ACTION_EDIT) Intent.ACTION_EDIT else Intent.ACTION_VIEW,
            incoming.flags, incoming.type, incoming.getStringExtra("displayName") ?: incoming.getStringExtra("extra_name"))
    }

    private fun openDocument(uri: Uri, action: String, incomingFlags: Int,
        suppliedMime: String? = null, suppliedName: String? = null) {
        if (uri.scheme !in setOf("content", "file")) {
            Toast.makeText(this, "InfiniScribe accepts file and content document references.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val name = suppliedName?.takeIf(String::isNotBlank) ?: displayName(uri)
        val rawMime = suppliedMime?.takeIf(String::isNotBlank)
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val mime = MimeRegistry.effective(name, rawMime)
        val edit = action == Intent.ACTION_EDIT
        val resource = CapabilityResource(
            metadata = StreamMetadata(name = name, content = ContentType(mimeType = mime)),
            uri = uri.toString()
        )
        val component = ViewerComponents.resolve(resource, edit = edit)
        if (component == null) {
            Toast.makeText(this, "No InfiniScribe viewer supports $name ($mime).", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val permissionFlags = incomingFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val launch = Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW).apply {
            setClassName(this@InfiniScribeActivity, component.activityClassName)
            setDataAndType(uri, mime)
            addFlags(permissionFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("uri", uri.toString())
            putExtra("extra_uri", uri.toString())
            putExtra("displayName", name)
            putExtra("extra_name", name)
            putExtra("mimeType", mime)
            putExtra("extra_mime", mime)
            putExtra("edit", edit)
            clipData = android.content.ClipData.newRawUri(name, uri)
        }
        try {
            startActivity(launch)
            // This host only routes a document to its viewer. Leave the caller
            // beneath the viewer so closing the document returns there instead
            // of exposing InfiniScribe's document picker screen.
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not open $name: ${error.message ?: "viewer launch failed"}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "document"
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column)?.takeIf(String::isNotBlank)?.let { return it }
            }
        } catch (_: Exception) {
            // Some content providers omit query support; the URI name remains a useful hint.
        } finally {
            cursor?.close()
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "document"
    }

    private fun Intent.getParcelableExtraCompat(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java)
        else @Suppress("DEPRECATION") (getParcelableExtra(key) as? Uri)

    companion object {
        private const val REQUEST_OPEN_DOCUMENT = 1001
    }
}
