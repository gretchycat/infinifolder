package com.infinifolder.standalone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.*
import io.github.gretchycat.infiniselect.InfiniSelect
import io.github.gretchycat.infiniselect.SelectionItem
import com.infinifolder.viewer.*

/** A standalone application host with no dependency on InfiniFolder's browser application. */
class ViewerHostActivity : ViewerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.VIEWER_KIND == "audio") {
            openMediaPlayer(intent)
            finish()
            return
        }
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
        val mime = source.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        // A selected standalone viewer owns the representation it advertises:
        // text shows source, and audio can play the audio track of a 3GP/MP4.
        // The combined host keeps the regular MIME-aware dispatch.
        val type = when (BuildConfig.VIEWER_KIND) {
            "text" -> MediaType.TEXT
            "image" -> if (MediaTypeDetector.detect(name, mime) == MediaType.TEXT) MediaType.TEXT else MediaType.IMAGE
            "markdown" -> MediaType.MARKDOWN
            "html" -> MediaType.HTML
            "pdf" -> MediaType.PDF
            "font" -> MediaType.FONT
            "csv" -> MediaType.SPREADSHEET
            "epub" -> MediaType.EPUB // The reader selects its Scribe document backend.
            "audio" -> MediaType.AUDIO
            "video" -> MediaType.VIDEO
            else -> MediaTypeDetector.detect(name, mime)
        }
        val edit = source.action == Intent.ACTION_EDIT
        if (edit && BuildConfig.VIEWER_KIND!="hex" && !MediaTypeDetector.isEditable(name,mime)) {
            Toast.makeText(this, "Editing is not supported for this format", Toast.LENGTH_LONG).show(); return
        }
        val ref = com.infinifolder.storage.StorageRuntime.get(this).provider.refForUri(uri)
            ?: run { Toast.makeText(this, "No storage provider for this document", Toast.LENGTH_LONG).show(); return }
        val entry = com.infinifolder.model.ResourceEntry(ref, com.infinifolder.model.ResourceMetadata(name, mime.orEmpty(), null, null, false), uri)
        val launch = (if(BuildConfig.VIEWER_KIND=="image" && type==MediaType.TEXT)ViewerLaunch.intent(this,entry,type,edit=edit)else if(BuildConfig.VIEWER_KIND!="all")ViewerLaunch.intentForKind(this,entry,BuildConfig.VIEWER_KIND,edit)else ViewerLaunch.intentForResource(this,entry,edit=edit))
            ?: ViewerLaunch.intent(this, entry, type, edit = edit)
            ?: run { Toast.makeText(this, "No viewer for this document", Toast.LENGTH_LONG).show(); return }
        launch.addFlags(source.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { startActivity(launch) } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "This viewer is not included in this application", Toast.LENGTH_LONG).show()
        }
    }

    /** InfiniMedia's application entry point forwards Android shares straight to its player. */
    private fun openMediaPlayer(source: Intent?) {
        val action = source?.action
        val uris = linkedSetOf<Uri>()
        source?.data?.let(uris::add)
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            source?.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM)?.forEach(uris::add)
            source?.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add) }
            source?.getParcelableExtraCompat(Intent.EXTRA_STREAM)?.let(uris::add)
        }
        val playerIntent = if (uris.size > 1) {
            val items = uris.map { uri ->
                val queried = runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) null to null else {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                (nameIndex.takeIf { it >= 0 }?.let(cursor::getString)) to
                                    (sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong)?.takeIf { it >= 0L })
                            }
                        } ?: (null to null)
                }.getOrDefault(null to null)
                SelectionItem(uri, queried.first ?: uri.lastPathSegment.orEmpty(),
                    contentResolver.getType(uri) ?: "application/octet-stream", queried.second)
            }
            val playlist = InfiniSelect.createJspfPlaylist(this, items, "Shared audio.jspf")
            Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                setDataAndType(playlist, "application/jspf+json")
                putExtra("displayName", "Shared audio.jspf")
                putExtra("mimeType", "application/jspf+json")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val uri = uris.firstOrNull()
            if (uri == null) Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
            else Intent(this, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                setDataAndType(uri, source?.type ?: contentResolver.getType(uri))
                putExtra("displayName", source?.getStringExtra("displayName")
                    ?: source?.getStringExtra("extra_name") ?: displayName(uri))
                putExtra("mimeType", source?.type ?: contentResolver.getType(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        source?.clipData?.let { playerIntent.clipData = it }
        playerIntent.addFlags(source?.flags?.and(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ) ?: 0)
        startActivity(playerIntent)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio track"

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<Uri>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java)
        else getParcelableArrayListExtra(key)
}
