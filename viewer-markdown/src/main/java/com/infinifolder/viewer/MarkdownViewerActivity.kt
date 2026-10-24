package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.*
import com.infinifolder.viewer.common.R
import com.infinifolder.model.ProviderResult

class MarkdownViewerActivity : ViewerActivity() {
    private var loadedRevision: com.infinifolder.storage.ContentRevision? = null

    private var documentFullyLoaded = false


    private lateinit var webView: WebView
    private lateinit var sourceEditText: EditText
    private lateinit var topBar: LinearLayout
    private lateinit var editActionBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var toggleModeBtn: TextView
    private lateinit var editQuillBtn: ImageView

    private var currentUri: Uri? = null
    private var parentFolderUri: Uri? = null
    private var currentName: String = ""
    private var currentMime: String = "text/markdown"

    private var originalMarkdown: String = ""
    private var isRenderedView: Boolean = true
    private var isEditMode: Boolean = false
    private var hasUnsavedEdits: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }

        if (targetUri == null) {
            Toast.makeText(this, "No Markdown URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUri = targetUri
        currentName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra(EXTRA_NAME)
            ?: currentUri?.lastPathSegment?.substringAfterLast('/')
            ?: "Document.md"
        currentMime = intent.getStringExtra("mimeType")
            ?: intent.getStringExtra(EXTRA_MIME)
            ?: "text/markdown"
        val parentStr = intent.getStringExtra(EXTRA_PARENT_URI) ?: intent.getStringExtra("parentUri")
        parentFolderUri = if (parentStr != null) Uri.parse(parentStr) else MediaSourceAdapter.getParentDirectory(this, currentUri!!)

        buildLayout()
        loadMarkdown()
    }

    private fun buildLayout() {
        val basePadH = dp(4)
        val basePadT = dp(4)
        val basePadB = dp(8)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141714.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                } else null
                val left = safe?.left ?: insets.systemWindowInsetLeft
                val top = safe?.top ?: insets.systemWindowInsetTop
                val right = safe?.right ?: insets.systemWindowInsetRight
                val bottom = safe?.bottom ?: insets.systemWindowInsetBottom
                view.setPadding(basePadH + left, basePadT + top, basePadH + right, basePadB + bottom)
                insets
            }
        }

        // Top Toolbar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E231E.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onBackPressed() }
        }
        topBar.addView(backBtn)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(4), 0)
        }
        titleView = TextView(this).apply {
            text = currentName
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = TextView(this).apply {
            text = "Loading…"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
        }
        titleCol.addView(titleView)
        titleCol.addView(subtitleView)
        topBar.addView(titleCol)

        // Toggle Rendered / Source view
        toggleModeBtn = TextView(this).apply {
            text = "Source"
            textSize = 13f
            setTextColor(0xFF81C784.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleRenderedSource() }
        }
        topBar.addView(toggleModeBtn)

        // Edit button: Monochromatic line-art quill icon with transparent background
        editQuillBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_edit_quill)
            setColorFilter(0xFFFFFFFF.toInt())
            contentDescription = "Edit"
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { toggleEditMode() }
        }
        topBar.addView(editQuillBtn)

        // Open with external app
        val openWithBtn = TextView(this).apply {
            text = "↗"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(12), dp(6))
            contentDescription = "Open with external app"
            setOnClickListener { openExternally() }
        }
        topBar.addView(openWithBtn)

        DebugTools.attachToolbarButton(this, topBar)
        root.addView(topBar)

        // Edit Action Bar
        editActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF232B22.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        val previewBtn = TextView(this).apply {
            text = "Preview"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { previewCurrentEdits() }
        }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        val discardBtn = TextView(this).apply {
            text = "Discard"
            textSize = 13f
            setTextColor(0xFFE57373.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { discardEdits() }
        }
        val saveAsBtn = TextView(this).apply {
            text = "Save As…"
            textSize = 13f
            setTextColor(0xFF81C784.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { promptSaveAs() }
        }
        val saveBtn = TextView(this).apply {
            text = "Save"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF4CAF50.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { commitSave(overwrite = true) }
        }
        editActionBar.addView(previewBtn)
        editActionBar.addView(spacer)
        editActionBar.addView(discardBtn)
        editActionBar.addView(saveAsBtn)
        editActionBar.addView(saveBtn)
        root.addView(editActionBar)

        // Main content area
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Rendered WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF141714.toInt())
            // Security sandboxing: no arbitrary file access, no unrestricted bridges
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
        }
        contentFrame.addView(webView)

        // Raw source EditText
        sourceEditText = EditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE0E5DC.toInt())
            setBackgroundColor(0xFF141714.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP or Gravity.START
            visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (isEditMode) hasUnsavedEdits = true
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        contentFrame.addView(sourceEditText)

        root.addView(contentFrame)
        setContentView(root)
    }

    private fun loadMarkdown() {
        documentFullyLoaded = false
        val uri = currentUri ?: return
        subtitleView.text = "Loading…"
        Thread {
            try {
                val document = MediaSourceAdapter.readDocument(this, uri, maxBytes = 4 * 1024 * 1024)
                val text = document.text
                val hasMore = document.hasMore
                runOnUiThread {
                    loadedRevision = document.revision
                    documentFullyLoaded = !hasMore
                    originalMarkdown = text
                    sourceEditText.setText(text)
                    renderHtml(text)
                    val lines = text.count { it == '\n' } + 1
                    val sizeFormatted = android.text.format.Formatter.formatShortFileSize(this, text.length.toLong())
                    subtitleView.text = "$lines lines · $sizeFormatted${if (hasMore) " (preview only: truncated)" else ""}"
                    if (documentFullyLoaded && !isEditMode && ViewerLaunch.requestsEdit(intent)) toggleEditMode()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    subtitleView.text = "Error reading Markdown"
                    webView.loadDataWithBaseURL(null, "<p style='color:red;'>Error: ${t.message}</p>", "text/html", "UTF-8", null)
                }
            }
        }.start()
    }

    private fun renderHtml(markdownText: String) {
        val isDark = com.infinifolder.theme.ThemeManager.isSystemInDarkMode(this)
        val html = MarkdownRenderer.renderToHtml(markdownText, isDark = isDark)
        val baseUrl = parentFolderUri?.toString() ?: currentUri?.toString() ?: "about:blank"
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isRenderedView && originalMarkdown.isNotEmpty()) {
            renderHtml(originalMarkdown)
        }
    }

    private fun toggleRenderedSource() {
        isRenderedView = !isRenderedView
        if (isRenderedView) {
            renderHtml(sourceEditText.text.toString())
            webView.visibility = View.VISIBLE
            sourceEditText.visibility = View.GONE
            toggleModeBtn.text = "Source"
        } else {
            webView.visibility = View.GONE
            sourceEditText.visibility = View.VISIBLE
            toggleModeBtn.text = "Rendered"
        }
    }

    private fun toggleEditMode() {
        if (!isEditMode && !documentFullyLoaded) {
            Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
            return
        }

        isEditMode = !isEditMode
        if (isEditMode) {
            editQuillBtn.setColorFilter(0xFF81C784.toInt())
            editActionBar.visibility = View.VISIBLE
            isRenderedView = false
            webView.visibility = View.GONE
            sourceEditText.visibility = View.VISIBLE
            sourceEditText.isFocusable = true
            sourceEditText.isFocusableInTouchMode = true
            sourceEditText.isCursorVisible = true
            sourceEditText.requestFocus()
            toggleModeBtn.text = "Preview"
            Toast.makeText(this, "Markdown edit mode", Toast.LENGTH_SHORT).show()
        } else {
            if (hasUnsavedEdits) {
                AlertDialog.Builder(this)
                    .setTitle("Discard edits?")
                    .setMessage("You have unsaved changes. Discard them?")
                    .setPositiveButton("Discard") { _, _ -> discardEdits() }
                    .setNegativeButton("Keep editing", null)
                    .show()
                return
            }
            discardEdits()
        }
    }

    private fun previewCurrentEdits() {
        renderHtml(sourceEditText.text.toString())
        webView.visibility = View.VISIBLE
        sourceEditText.visibility = View.GONE
        toggleModeBtn.text = "Edit Source"
    }

    private fun discardEdits() {
        isEditMode = false
        hasUnsavedEdits = false
        editQuillBtn.setColorFilter(0xFFFFFFFF.toInt())
        editActionBar.visibility = View.GONE
        sourceEditText.setText(originalMarkdown)
        sourceEditText.isFocusable = false
        sourceEditText.isFocusableInTouchMode = false
        sourceEditText.isCursorVisible = false
        isRenderedView = true
        renderHtml(originalMarkdown)
        webView.visibility = View.VISIBLE
        sourceEditText.visibility = View.GONE
        toggleModeBtn.text = "Source"
    }

    private fun commitSave(overwrite: Boolean) {
        val uri = currentUri ?: return
        val isWritable = MediaSourceAdapter.isWritable(this, uri)
        if (overwrite && !isWritable) {
            Toast.makeText(this, "This location is read-only. Please use Save As…", Toast.LENGTH_LONG).show()
            promptSaveAs()
            return
        }

        if (overwrite) {
            AlertDialog.Builder(this)
                .setTitle("Overwrite Original?")
                .setMessage("Save changes to $currentName?")
                .setPositiveButton("Save") { _, _ -> performSave(uri) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            promptSaveAs()
        }
    }

    private fun promptSaveAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = currentMime.ifBlank { "text/markdown" }
            putExtra(Intent.EXTRA_TITLE, currentName.substringBeforeLast('.') + "_edited." + currentName.substringAfterLast('.', "txt"))
        }
        startActivityForResult(intent, 901)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 901 && resultCode == RESULT_OK) data?.data?.let(::performSave)
    }

    private fun performSave(targetUri: Uri) {
        if (!documentFullyLoaded) {
            Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
            return
        }

        val text = sourceEditText.text.toString()
        val data = text.toByteArray(Charsets.UTF_8)
        Thread {
            val res = MediaSourceAdapter.saveContent(this, targetUri, data, if (targetUri == currentUri) loadedRevision else null)
            runOnUiThread {
                if (res is ProviderResult.Success) {
                    if (targetUri == currentUri) loadedRevision = com.infinifolder.storage.ContentRevision.of(data)
                    Toast.makeText(this, "Saved successfully (${data.size} bytes)", Toast.LENGTH_SHORT).show()
                    hasUnsavedEdits = false
                    originalMarkdown = text
                    discardEdits()
                } else if (res is ProviderResult.Failure) {
                    Toast.makeText(this, "Failed to save: ${res.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openExternally() {
        val uri = currentUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, currentMime.ifEmpty { "text/markdown" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with…"))
        } catch (_: Exception) {
            Toast.makeText(this, "No external application found for this file.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (isEditMode && hasUnsavedEdits) {
            AlertDialog.Builder(this)
                .setTitle("Discard edits?")
                .setMessage("You have unsaved changes. Discard them?")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep editing", null)
                .show()
            return
        }
        super.onBackPressed()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_MIME = "extra_mime"
        const val EXTRA_PARENT_URI = "extra_parent_uri"
    }
}
