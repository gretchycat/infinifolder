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
import io.github.gretchycat.infinistream.Operations
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.viewer.MediaSourceAdapter
import com.infinifolder.viewer.ViewerComponents
import io.github.gretchycat.infiniselect.InfiniSelect
import io.github.gretchycat.infiniselect.OpenRequest
import io.github.gretchycat.infiniselect.FileSelectionContract

/** Standalone single-document host for the Scribe-backed viewer components. */
class InfiniScribeActivity : Activity() {
    private var selectorSession = false
    private var selectorDirectory: Uri? = null
    private var selectorScrollY = 0
    private var openGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            selectorSession = savedInstanceState.getBoolean(STATE_SELECTOR_SESSION, false)
            selectorDirectory = savedInstanceState.getString(STATE_SELECTOR_DIRECTORY)?.let(Uri::parse)
            selectorScrollY = savedInstanceState.getInt(STATE_SELECTOR_SCROLL_Y, 0).coerceAtLeast(0)
            if (selectorSession) return
        }
        handleDocumentIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SELECTOR_SESSION, selectorSession)
        selectorDirectory?.let { outState.putString(STATE_SELECTOR_DIRECTORY, it.toString()) }
        outState.putInt(STATE_SELECTOR_SCROLL_Y, selectorScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDocumentIntent(intent)
    }

    private fun chooseDocument(directory: Uri? = selectorDirectory, scrollY: Int = selectorScrollY) {
        selectorSession = true
        // Use the installed Scribe handlers as the source of truth. Adding a
        // reader to libinfiniscribe automatically adds its MIME types and
        // extensions to this picker without a second format list here.
        val readableFormats = ViewerComponents.registry.supportedFormats(Operations.VIEW)
            .map { it.format }
        val mimeTypes = readableFormats.flatMap { it.mimeTypes }
            .filterNot { it == "*/*" }
            .distinct().sorted()
        val extensions = readableFormats.flatMap { it.extensions }
            .distinct().sorted()
        val canFilter = mimeTypes.isNotEmpty() || extensions.isNotEmpty()
        InfiniSelect.open(this, OpenRequest(
            clientId = "infiniscribe",
            displayName = "InfiniScribe",
            mimeTypes = mimeTypes.ifEmpty { listOf("*/*") },
            allowMultiple = false,
            initialDirectory = directory,
            supportedExtensions = extensions,
            hideUnsupported = canFilter,
            initialScrollY = scrollY,
        ), REQUEST_OPEN_DOCUMENT)
    }

    @Deprecated("Activity result contract kept compatible with the platform-minimum runtime")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_VIEWER) {
            if (selectorSession) chooseDocument(selectorDirectory, selectorScrollY)
            else finish()
            return
        }
        if (requestCode != REQUEST_OPEN_DOCUMENT) return
        if (resultCode != RESULT_OK || data?.data == null) {
            finish()
            return
        }
        val uri = data.data ?: return
        if (selectorSession) {
            selectorDirectory = data.getStringExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY)?.let(Uri::parse)
                ?: selectorDirectory
            selectorScrollY = data.getIntExtra(FileSelectionContract.EXTRA_RESULT_SCROLL_Y, selectorScrollY).coerceAtLeast(0)
        }
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
        val source = object : io.github.gretchycat.infinistream.SourceResource {
            override val metadata = StreamMetadata(name = name, content = ContentType(mimeType = mime),
                capabilities = setOf(io.github.gretchycat.infinistream.Capability.READ,
                    io.github.gretchycat.infinistream.Capability.REOPEN),
                provenance = io.github.gretchycat.infinistream.Provenance(source = uri.toString()))
            override fun open() = MediaSourceAdapter.openSource(this@InfiniScribeActivity, uri)
        }
        // Always probe the source bytes in a worker. Extension/MIME associations are
        // candidates only, so an unfamiliar suffix can still fall through to plain text.
        val resource = CapabilityResource(
            metadata = StreamMetadata(name = name, content = ContentType(mimeType = mime)),
            uri = uri.toString(), source = source
        )
        val request = ++openGeneration
        Thread({
            val result = runCatching { ViewerComponents.resolve(resource, edit = edit) }
            runOnUiThread {
                if (request != openGeneration || isFinishing || isDestroyed) return@runOnUiThread
                val component = result.getOrNull()
                if (component == null) {
                    val failure = result.exceptionOrNull()
                    val detail = failure?.message?.takeIf(String::isNotBlank)
                    Toast.makeText(this, detail?.let { "Could not inspect $name: $it" }
                        ?: "No InfiniScribe viewer supports $name ($mime).", Toast.LENGTH_LONG).show()
                    if (selectorSession) chooseDocument(selectorDirectory, selectorScrollY) else finish()
                    return@runOnUiThread
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
                    if (selectorSession) {
                        // Retain this host beneath the document viewer. Its result callback
                        // restores the selector's last location after Back/Escape.
                        startActivityForResult(launch, REQUEST_OPEN_VIEWER)
                    } else {
                        startActivity(launch)
                        finish()
                    }
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not open $name: ${error.message ?: "viewer launch failed"}", Toast.LENGTH_LONG).show()
                    if (selectorSession) chooseDocument(selectorDirectory, selectorScrollY) else finish()
                }
            }
        }, "InfiniScribe-format-probe").start()
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
        private const val REQUEST_OPEN_VIEWER = 1002
        private const val STATE_SELECTOR_SESSION = "selector_session"
        private const val STATE_SELECTOR_DIRECTORY = "selector_directory"
        private const val STATE_SELECTOR_SCROLL_Y = "selector_scroll_y"
    }
}
