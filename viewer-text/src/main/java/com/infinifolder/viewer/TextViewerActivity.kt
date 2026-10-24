package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import com.infinifolder.viewer.common.R
import com.infinifolder.model.ProviderResult
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class TextViewerActivity : ViewerActivity() {
    private var loadedRevision: com.infinifolder.storage.ContentRevision? = null

    private var documentFullyLoaded = false


    private lateinit var contentEditText: EditText
    private lateinit var lineNumbersView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var editQuillBtn: ImageView
    private lateinit var editActionBar: LinearLayout

    private var currentUri: Uri? = null
    private var parentFolderUri: Uri? = null
    private var currentName: String = ""
    private var currentMime: String = "text/plain"

    private var currentCharset: Charset = StandardCharsets.UTF_8
    private var originalText: String = ""
    private var isEditMode: Boolean = false
    private var hasUnsavedEdits: Boolean = false
    private var isWordWrap: Boolean = true
    private var showLineNumbers: Boolean = true

    // Undo / Redo history
    private val undoStack = mutableListOf<String>()
    private val redoStack = mutableListOf<String>()
    private var isUndoRedoOperation = false

    // Search state
    private val searchMatches = mutableListOf<Int>()
    private var currentMatchIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }

        if (targetUri == null) {
            Toast.makeText(this, "No text URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUri = targetUri
        currentName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra(EXTRA_NAME)
            ?: currentUri?.lastPathSegment?.substringAfterLast('/')
            ?: "Document.txt"
        currentMime = intent.getStringExtra("mimeType")
            ?: intent.getStringExtra(EXTRA_MIME)
            ?: "text/plain"
        val parentStr = intent.getStringExtra(EXTRA_PARENT_URI) ?: intent.getStringExtra("parentUri")
        parentFolderUri = if (parentStr != null) Uri.parse(parentStr) else MediaSourceAdapter.getParentDirectory(this, currentUri!!)

        buildLayout()
        loadText()
    }

    private fun buildLayout() {
        val basePadH = dp(4)
        val basePadT = dp(4)
        val basePadB = dp(8)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141714.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = if (Build.VERSION.SDK_INT >= 30) {
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

        // Search button
        val searchBtn = TextView(this).apply {
            text = "🔍"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleSearchBar() }
        }
        topBar.addView(searchBtn)

        // Line wrap toggle
        val wrapBtn = TextView(this).apply {
            text = "↩"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Toggle line wrap"
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                isWordWrap = !isWordWrap
                updateWordWrap()
            }
        }
        topBar.addView(wrapBtn)

        // Line numbers toggle
        val lineNumBtn = TextView(this).apply {
            text = "№"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Toggle line numbers"
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                showLineNumbers = !showLineNumbers
                lineNumbersView.visibility = if (showLineNumbers) View.VISIBLE else View.GONE
            }
        }
        topBar.addView(lineNumBtn)

        // Encoding selector
        val encBtn = TextView(this).apply {
            text = "🔤"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Encoding"
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { showEncodingPicker() }
        }
        topBar.addView(encBtn)

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

        // Search Bar (expandable)
        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF262C25.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        searchInput = EditText(this).apply {
            hint = "Find in text…"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { performSearch(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        searchStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(dp(8), 0, dp(8), 0)
        }
        val prevMatchBtn = TextView(this).apply {
            text = "▲"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { navigateSearchMatch(-1) }
        }
        val nextMatchBtn = TextView(this).apply {
            text = "▼"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { navigateSearchMatch(1) }
        }
        val closeSearchBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(4), dp(8), dp(4))
            setOnClickListener { toggleSearchBar() }
        }
        searchBar.addView(searchInput)
        searchBar.addView(searchStatus)
        searchBar.addView(prevMatchBtn)
        searchBar.addView(nextMatchBtn)
        searchBar.addView(closeSearchBtn)
        root.addView(searchBar)

        // Edit Action Bar (Undo, Redo, Save, Save As, Discard)
        editActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF232B22.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        val undoBtn = TextView(this).apply {
            text = "↶ Undo"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { performUndo() }
        }
        val redoBtn = TextView(this).apply {
            text = "↷ Redo"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { performRedo() }
        }
        val replaceBtn = TextView(this).apply {
            text = "Replace…"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { showReplaceDialog() }
        }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
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
        editActionBar.addView(undoBtn)
        editActionBar.addView(redoBtn)
        editActionBar.addView(replaceBtn)
        editActionBar.addView(spacer)
        editActionBar.addView(saveAsBtn)
        editActionBar.addView(saveBtn)
        root.addView(editActionBar)

        // Content Area with Line Numbers
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        lineNumbersView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF5A6658.toInt())
            setBackgroundColor(0xFF101210.toInt())
            setPadding(dp(8), dp(10), dp(8), dp(10))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        contentEditText = EditText(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE0E5DC.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            gravity = Gravity.TOP or Gravity.START
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (isEditMode && !isUndoRedoOperation) {
                        hasUnsavedEdits = true
                        pushUndo(s?.toString().orEmpty())
                        updateLineNumbers()
                    if (documentFullyLoaded && !isEditMode && ViewerLaunch.requestsEdit(intent)) toggleEditMode()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            val horiz = HorizontalScrollView(this@TextViewerActivity).apply {
                isFillViewport = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                val lineAndText = LinearLayout(this@TextViewerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(lineNumbersView)
                    addView(contentEditText, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
                addView(lineAndText)
            }
            addView(horiz)
        }

        textContainer.addView(scrollView)
        root.addView(textContainer)

        setContentView(root)
    }

    private fun loadText() {
        documentFullyLoaded = false
        val uri = currentUri ?: return
        subtitleView.text = "Loading…"
        Thread {
            try {
                val document = MediaSourceAdapter.readDocument(this, uri, maxBytes = 4 * 1024 * 1024, charset = currentCharset)
                val text = document.text
                val hasMore = document.hasMore
                runOnUiThread {
                    loadedRevision = document.revision
                    documentFullyLoaded = !hasMore
                    originalText = text
                    undoStack.clear()
                    redoStack.clear()
                    pushUndo(text)
                    contentEditText.setText(text)
                    val lines = text.count { it == '\n' } + 1
                    val sizeFormatted = android.text.format.Formatter.formatShortFileSize(this, text.length.toLong())
                    subtitleView.text = "${currentCharset.name()} · $lines lines · $sizeFormatted${if (hasMore) " (truncated to 4MB)" else ""}"
                    updateLineNumbers()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    subtitleView.text = "Error reading text"
                    contentEditText.setText("Error loading file: ${t.message}")
                }
            }
        }.start()
    }

    private fun updateLineNumbers() {
        val text = contentEditText.text?.toString() ?: ""
        val count = text.count { it == '\n' } + 1
        val sb = StringBuilder()
        for (i in 1..count) {
            sb.append(i).append('\n')
        }
        lineNumbersView.text = sb.toString()
    }

    private fun updateWordWrap() {
        contentEditText.setHorizontallyScrolling(!isWordWrap)
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
            contentEditText.isFocusable = true
            contentEditText.isFocusableInTouchMode = true
            contentEditText.isCursorVisible = true
            contentEditText.requestFocus()
            Toast.makeText(this, "Editing mode enabled", Toast.LENGTH_SHORT).show()
        } else {
            if (hasUnsavedEdits) {
                AlertDialog.Builder(this)
                    .setTitle("Discard edits?")
                    .setMessage("You have unsaved edits. Discard them?")
                    .setPositiveButton("Discard") { _, _ ->
                        isEditMode = false
                        hasUnsavedEdits = false
                        editQuillBtn.setColorFilter(0xFFFFFFFF.toInt())
                        editActionBar.visibility = View.GONE
                        contentEditText.isFocusable = false
                        contentEditText.isFocusableInTouchMode = false
                        contentEditText.isCursorVisible = false
                        contentEditText.setText(originalText)
                    }
                    .setNegativeButton("Keep editing", null)
                    .show()
                return
            }
            editQuillBtn.setColorFilter(0xFFFFFFFF.toInt())
            editActionBar.visibility = View.GONE
            contentEditText.isFocusable = false
            contentEditText.isFocusableInTouchMode = false
            contentEditText.isCursorVisible = false
        }
    }

    private fun pushUndo(text: String) {
        if (undoStack.isEmpty() || undoStack.last() != text) {
            undoStack.add(text)
            if (undoStack.size > 50) undoStack.removeAt(0)
            redoStack.clear()
        }
    }

    private fun performUndo() {
        if (undoStack.size > 1) {
            isUndoRedoOperation = true
            val current = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(current)
            val prev = undoStack.last()
            contentEditText.setText(prev)
            contentEditText.setSelection(prev.length)
            isUndoRedoOperation = false
            updateLineNumbers()
        }
    }

    private fun performRedo() {
        if (redoStack.isNotEmpty()) {
            isUndoRedoOperation = true
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(next)
            contentEditText.setText(next)
            contentEditText.setSelection(next.length)
            isUndoRedoOperation = false
            updateLineNumbers()
        }
    }

    private fun toggleSearchBar() {
        val vis = searchBar.visibility == View.VISIBLE
        searchBar.visibility = if (vis) View.GONE else View.VISIBLE
        if (!vis) {
            searchInput.requestFocus()
        } else {
            clearSearchHighlight()
        }
    }

    private fun performSearch(query: String) {
        searchMatches.clear()
        currentMatchIndex = -1
        if (query.isEmpty()) {
            searchStatus.text = ""
            clearSearchHighlight()
            return
        }
        val fullText = contentEditText.text.toString()
        var idx = fullText.indexOf(query, 0, ignoreCase = true)
        while (idx >= 0) {
            searchMatches.add(idx)
            idx = fullText.indexOf(query, idx + query.length, ignoreCase = true)
        }

        searchStatus.text = "${searchMatches.size} found"
        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightSearchMatch(query)
        } else {
            clearSearchHighlight()
        }
    }

    private fun navigateSearchMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + delta + searchMatches.size) % searchMatches.size
        searchStatus.text = "${currentMatchIndex + 1}/${searchMatches.size}"
        highlightSearchMatch(searchInput.text.toString())
    }

    private fun highlightSearchMatch(query: String) {
        if (currentMatchIndex !in searchMatches.indices) return
        val pos = searchMatches[currentMatchIndex]
        val spannable = SpannableString(contentEditText.text.toString())
        for (m in searchMatches) {
            val bg = if (m == pos) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt()
            spannable.setSpan(BackgroundColorSpan(bg), m, m + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.BLACK), m, m + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        isUndoRedoOperation = true
        contentEditText.setText(spannable)
        contentEditText.setSelection(pos, pos + query.length)
        isUndoRedoOperation = false
    }

    private fun clearSearchHighlight() {
        val plain = contentEditText.text.toString()
        isUndoRedoOperation = true
        contentEditText.setText(plain)
        isUndoRedoOperation = false
    }

    private fun showReplaceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val findInput = EditText(this).apply { hint = "Find…" }
        val replaceInput = EditText(this).apply { hint = "Replace with…" }
        layout.addView(findInput)
        layout.addView(replaceInput)

        AlertDialog.Builder(this)
            .setTitle("Find and Replace")
            .setView(layout)
            .setPositiveButton("Replace All") { _, _ ->
                val f = findInput.text.toString()
                val r = replaceInput.text.toString()
                if (f.isNotEmpty()) {
                    val updated = contentEditText.text.toString().replace(f, r, ignoreCase = false)
                    contentEditText.setText(updated)
                    hasUnsavedEdits = true
                    Toast.makeText(this, "Replaced occurrences", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEncodingPicker() {
        val encodings = arrayOf("UTF-8", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "US-ASCII")
        AlertDialog.Builder(this)
            .setTitle("Select Encoding")
            .setItems(encodings) { _, which ->
                currentCharset = Charset.forName(encodings[which])
                loadText()
            }
            .show()
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
            type = currentMime.ifBlank { "text/plain" }
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

        val text = contentEditText.text.toString()
        val data = text.toByteArray(currentCharset)
        Thread {
            val res = MediaSourceAdapter.saveContent(this, targetUri, data, if (targetUri == currentUri) loadedRevision else null)
            runOnUiThread {
                if (res is ProviderResult.Success) {
                    if (targetUri == currentUri) loadedRevision = com.infinifolder.storage.ContentRevision.of(data)
                    Toast.makeText(this, "Saved successfully (${data.size} bytes)", Toast.LENGTH_SHORT).show()
                    hasUnsavedEdits = false
                    originalText = text
                    toggleEditMode()
                } else if (res is ProviderResult.Failure) {
                    Toast.makeText(this, "Failed to save: ${res.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openExternally() {
        val uri = currentUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, currentMime.ifEmpty { "text/plain" })
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
