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
import io.github.gretchycat.infiniscribe.TextChunk
import io.github.gretchycat.infiniscribe.TextChunkReader
import io.github.gretchycat.infinistream.CancellationToken
import java.util.concurrent.Executors
import java.util.concurrent.Future

class TextViewerActivity : ViewerActivity() {
    private var loadedRevision: com.infinifolder.storage.ContentRevision? = null

    private var documentFullyLoaded = false
    private val documentWorker = Executors.newSingleThreadExecutor()
    private var documentTask: Future<*>? = null
    @Volatile private var documentGeneration = 0
    @Volatile private var chunkReader: TextChunkReader? = null
    private var currentChunk: TextChunk? = null
    private val textWindow = ScrollWindow<TextChunk>(3, { it.start }, { it.document.text.length })
    private var firstWindowLine: Long? = 1L
    private var edgeLoading = false
    private var adjustingWindow = false
    private var blockedEdge: Pair<Int, Long>? = null
    private data class TextAnchor(val character: Int, val withinLine: Int, val horizontal: Int)
    private lateinit var chunkBar: LinearLayout
    private lateinit var jumpChunk: Button


    private lateinit var contentEditText: EditText
    private lateinit var lineNumbersView: SyntaxLineNumbersView
    private lateinit var syntax: SyntaxTextController
    private lateinit var scrollView: AnchoredScrollView
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
    private var pendingSearchPosition: Int? = null

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

        // Text appearance and encoding menu
        val encBtn = TextView(this).apply {
            text = "Aa"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Text appearance and encoding"
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { anchor ->
                PopupMenu(this@TextViewerActivity, anchor).apply {
                    menu.add("Text appearance…").setOnMenuItemClickListener { if (::syntax.isInitialized) syntax.showSettings(); true }
                    menu.add("Encoding…").setOnMenuItemClickListener { showEncodingPicker(); true }
                    show()
                }
            }
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

        chunkBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        jumpChunk = Button(this).apply { text = "Go to…"; setOnClickListener { promptChunkOffset() } }
        chunkBar.addView(jumpChunk, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(chunkBar)

        // Content Area with Line Numbers
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        lineNumbersView = SyntaxLineNumbersView(this) { if (::contentEditText.isInitialized) contentEditText else null }.apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            minWidth = dp(80)
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

        scrollView = AnchoredScrollView(this).apply {
            setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadTextEdge() }
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
        contentEditText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> lineNumbersView.requestLayout(); lineNumbersView.invalidate() }
        syntax = SyntaxTextController(this, contentEditText, currentName, currentMime, { currentChunk?.start ?: 0L },
            stableLayout = ::applySyntaxWithAnchor, afterApply = {
                lineNumbersView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, contentEditText.textSize)
                lineNumbersView.typeface = contentEditText.typeface
                (contentEditText.background as? android.graphics.drawable.ColorDrawable)?.color?.let(lineNumbersView::setBackgroundColor)
                lineNumbersView.requestLayout(); lineNumbersView.invalidate()
                if (searchInput.text.isNotEmpty()) performSearch(searchInput.text.toString(), followMatch = false)
            })
    }

    private fun applySyntaxWithAnchor(action: () -> Unit): Boolean {
        if (adjustingWindow) return false
        val anchor = textAnchor(); val request = documentGeneration
        adjustingWindow = true; scrollView.beginAnchorChange()
        action()
        val observer = contentEditText.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (request != documentGeneration || isDestroyed) { scrollView.cancelAnchorChange(); return true }
                contentEditText.layout?.let { layout ->
                    val line = layout.getLineForOffset(anchor.character.coerceAtMost(contentEditText.length()))
                    scrollView.restoreAnchor(anchor.horizontal, contentEditText.top + contentEditText.totalPaddingTop + layout.getLineTop(line) + anchor.withinLine)
                }
                adjustingWindow = false; scrollView.post { maybeLoadTextEdge() }
                return true
            }
        })
        return true
    }

    override fun onResume() { super.onResume(); if (::syntax.isInitialized) syntax.refreshPreferences() }

    private fun loadText() {
        documentFullyLoaded = false
        val uri = currentUri ?: return
        val request = ++documentGeneration
        documentTask?.cancel(true)
        subtitleView.text = "Loading…"
        textWindow.clear(); edgeLoading = false; blockedEdge = null
        documentTask = documentWorker.submit {
            try {
                chunkReader?.close()
                val reader = TextChunkReader(MediaSourceAdapter.openDocumentSource(this, uri), currentCharset,
                    CancellationToken { isDestroyed || isFinishing })
                chunkReader = reader
                val chunk = reader.read()
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    showChunk(chunk)
                    if (ViewerLaunch.requestsEdit(intent)) toggleEditMode()
                }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    private fun showChunk(chunk: TextChunk) {
        textWindow.reset(chunk); firstWindowLine = if (chunk.start == 0L) 1L else null
        edgeLoading = false; blockedEdge = null
        renderTextWindow()
    }

    private fun textAnchor(): TextAnchor {
        val layout = contentEditText.layout ?: return TextAnchor(0, 0, scrollView.scrollX)
        val localY = scrollView.scrollY - contentEditText.top - contentEditText.totalPaddingTop
        val line = layout.getLineForVertical(localY.coerceAtLeast(0))
        return TextAnchor(layout.getLineStart(line), localY - layout.getLineTop(line), scrollView.scrollX)
    }

    private fun lastVisibleCharacter(): Int {
        val layout = contentEditText.layout ?: return 0
        val localY = scrollView.scrollY + scrollView.height - contentEditText.top - contentEditText.totalPaddingTop
        return (layout.getLineEnd(layout.getLineForVertical(localY.coerceAtLeast(0))) - 1).coerceAtLeast(0)
    }

    private fun renderTextWindow(anchor: TextAnchor? = null, shift: ScrollWindow.Shift = ScrollWindow.Shift()) {
        val pages = textWindow.pages
        if (pages.isEmpty()) return
        adjustingWindow = true; pendingSearchPosition = null
        val chunk = TextWindowPresentation.combine(pages)
        val text = chunk.document.text; val bytes = chunk.document.bytes; val whole = !chunk.document.hasMore
        currentChunk = chunk; documentFullyLoaded = whole
        loadedRevision = if (whole) com.infinifolder.storage.ContentRevision.of(bytes) else null
        if (!whole) {
            isEditMode = false; hasUnsavedEdits = false; editActionBar.visibility = View.GONE
            contentEditText.isFocusable = false; contentEditText.isFocusableInTouchMode = false; contentEditText.isCursorVisible = false
        }
        originalText = text; undoStack.clear(); redoStack.clear()
        if (whole) pushUndo(text)
        if (anchor != null) scrollView.beginAnchorChange() else scrollView.cancelAnchorChange()
        contentEditText.setText(text)
        subtitleView.text = if (whole) "${currentCharset.name()} · ${text.count { it == '\n' } + 1} lines"
            else "${currentCharset.name()} · Read-only · ${chunkReader?.sourceMetadata?.length?.let { android.text.format.Formatter.formatShortFileSize(this, it) } ?: "stream"}"
        chunkBar.visibility = if (whole) View.GONE else View.VISIBLE
        jumpChunk.isEnabled = chunkReader?.supportsRandomAccess == true
        updateLineNumbers()
        val request = documentGeneration
        val observer = contentEditText.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (request != documentGeneration || isDestroyed) { scrollView.cancelAnchorChange(); return true }
                val layout = contentEditText.layout
                val searchPosition = pendingSearchPosition
                pendingSearchPosition = null
                if (searchPosition != null && layout != null) {
                    scrollView.cancelAnchorChange()
                    scrollView.scrollTo(0, contentEditText.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(searchPosition.coerceAtMost(text.length))))
                } else if (anchor != null && layout != null) {
                    val position = shift.anchor(anchor.character).coerceIn(0, text.length)
                    val line = layout.getLineForOffset(position)
                    scrollView.restoreAnchor(anchor.horizontal, contentEditText.top + contentEditText.totalPaddingTop + layout.getLineTop(line) + anchor.withinLine)
                } else scrollView.scrollTo(0, 0)
                adjustingWindow = false
                scrollView.post { maybeLoadTextEdge() }
                return true
            }
        })
    }

    private fun maybeLoadTextEdge() {
        if (isDestroyed || isFinishing || isEditMode || documentFullyLoaded || edgeLoading || adjustingWindow) return
        val reader = chunkReader ?: return
        val pages = textWindow.pages; if (pages.isEmpty() || scrollView.height == 0) return
        val first = pages.first(); val last = pages.last()
        val anchor = textAnchor()
        val remaining = (scrollView.getChildAt(0)?.height ?: 0) - scrollView.scrollY - scrollView.height
        val direction = when {
            remaining < scrollView.height * 2 && last.hasNext && textWindow.canAppend(anchor.character) && blockedEdge != (1 to last.nextOffset) -> 1
            scrollView.scrollY < scrollView.height * 2 && first.start > 0 && reader.canRead((first.start - 16 * 1024).coerceAtLeast(0)) &&
                textWindow.canPrepend(lastVisibleCharacter()) && blockedEdge != (-1 to first.start) -> -1
            else -> return
        }
        val request = documentGeneration
        val edge = if (direction > 0) last.nextOffset else first.start
        edgeLoading = true
        documentTask = documentWorker.submit {
            try {
                val next = if (direction > 0) reader.read(edge) else reader.readPrevious(edge)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    edgeLoading = false
                    if ((direction > 0 && next.start != edge) || (direction < 0 && next.nextOffset != edge)) {
                        blockedEdge = direction to edge
                        Toast.makeText(this, "The source changed; reopen the document", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val latest = textAnchor()
                    val shift = if (direction > 0) textWindow.append(next, latest.character) else textWindow.prepend(next, lastVisibleCharacter())
                    if (shift != null) {
                        if (direction < 0) firstWindowLine = firstWindowLine?.minus(next.document.text.count { it == '\n' })
                        else if (shift.removedBefore > 0) firstWindowLine = firstWindowLine?.plus(first.document.text.count { it == '\n' })
                        if (textWindow.pages.first().start == 0L) firstWindowLine = 1L
                        renderTextWindow(latest, shift)
                        if (searchInput.text.isNotEmpty()) performSearch(searchInput.text.toString(), followMatch = false)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    edgeLoading = false; blockedEdge = direction to edge
                    Toast.makeText(this, error.message ?: "Cannot load more text", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showReadError(request: Int, error: Exception) = runOnUiThread {
        if (request != documentGeneration || isDestroyed) return@runOnUiThread
        edgeLoading = false; adjustingWindow = false
        scrollView.cancelAnchorChange()
        subtitleView.text = "Error reading text"
        Toast.makeText(this, error.message ?: "Cannot read document", Toast.LENGTH_LONG).show()
    }

    private fun readChunk(offset: Long) {
        val reader = chunkReader ?: return
        val request = ++documentGeneration
        documentTask?.cancel(true)
        subtitleView.text = "Loading…"
        edgeLoading = true; blockedEdge = null
        documentTask = documentWorker.submit {
            try {
                val chunk = reader.read(offset)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    searchMatches.clear(); currentMatchIndex = -1; searchStatus.text = ""
                    showChunk(chunk)
                    if (searchInput.text.isNotEmpty()) performSearch(searchInput.text.toString())
                }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    private fun promptChunkOffset() {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText((currentChunk?.start ?: 0).toString()) }
        AlertDialog.Builder(this).setTitle("Go to byte offset").setView(input)
            .setPositiveButton("Go") { _, _ ->
                val offset = input.text.toString().toLongOrNull()
                if (offset != null && offset >= 0 && (chunkReader?.sourceMetadata?.length?.let { offset < it } != false)) {
                    readChunk(offset)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Existing complete-document editor is materialized only on explicit edit, within its original 4 MiB guard. */
    private fun loadForEditing() {
        if (chunkReader?.sourceMetadata?.length?.let { it > 4L * 1024 * 1024 } == true) {
            Toast.makeText(this, "Large documents are read-only; scroll to browse", Toast.LENGTH_LONG).show(); return
        }
        val uri = currentUri ?: return
        val request = ++documentGeneration
        documentTask?.cancel(true); edgeLoading = true; subtitleView.text = "Loading document for editing…"
        documentTask = documentWorker.submit {
            try {
                val loaded = MediaSourceAdapter.readDocument(this, uri, 4 * 1024 * 1024, currentCharset)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    if (loaded.hasMore) {
                        edgeLoading = false
                        renderTextWindow(textAnchor())
                        Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
                    } else {
                        documentFullyLoaded = true; loadedRevision = loaded.revision; currentChunk = null
                        textWindow.clear(); edgeLoading = false
                        originalText = loaded.text; undoStack.clear(); redoStack.clear(); pushUndo(originalText)
                        contentEditText.setText(originalText); chunkBar.visibility = View.GONE
                        subtitleView.text = "${currentCharset.name()} · ${originalText.count { it == '\n' } + 1} lines"
                        updateLineNumbers(); toggleEditMode()
                    }
                }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    override fun onDestroy() {
        if (::syntax.isInitialized) syntax.close()
        documentGeneration++; documentTask?.cancel(true)
        documentWorker.execute { runCatching { chunkReader?.close() }; chunkReader = null }
        documentWorker.shutdown()
        super.onDestroy()
    }

    private fun updateLineNumbers() {
        val text = contentEditText.text?.toString() ?: ""
        val count = text.count { it == '\n' } + 1
        val sb = StringBuilder()
        val base = if (currentChunk == null) 1L else firstWindowLine
        for (i in 0 until count) {
            sb.append(base?.plus(i) ?: "·").append('\n')
        }
        lineNumbersView.text = sb.toString()
        val starts = IntArray(count); var next = 1
        text.forEachIndexed { index, char -> if (char == '\n') starts[next++] = index + 1 }
        lineNumbersView.starts = starts; lineNumbersView.firstLine = base
        lineNumbersView.requestLayout(); lineNumbersView.invalidate()
    }

    private fun updateWordWrap() {
        contentEditText.setHorizontallyScrolling(!isWordWrap)
    }

    private fun toggleEditMode() {
        if (!isEditMode && !documentFullyLoaded) { loadForEditing(); return }

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

    private fun performSearch(query: String, followMatch: Boolean = true) {
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

        searchStatus.text = "${searchMatches.size} found${if (currentChunk?.document?.hasMore == true) " in chunk" else ""}"
        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightSearchMatch(query, followMatch)
        } else {
            clearSearchHighlight()
        }
    }

    private fun searchFollowingChunks(query: String) {
        if (query.isEmpty()) return
        val reader = chunkReader ?: return
        val current = currentChunk ?: return
        if (!current.hasNext) { searchStatus.text = "End of file"; return }
        val selected = searchMatches.getOrNull(currentMatchIndex)
        val afterByte = selected?.let { current.start + current.document.text.substring(0, it + 1).toByteArray(currentCharset).size }
        val request = ++documentGeneration
        documentTask?.cancel(true); edgeLoading = true; searchStatus.text = "Searching…"
        documentTask = documentWorker.submit {
            try {
                val found = reader.findNext(query, current.nextOffset, current)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    edgeLoading = false
                    if (found == null) { searchStatus.text = "No further match"; return@runOnUiThread }
                    showChunk(found); performSearch(query)
                    if (afterByte != null) {
                        val match = searchMatches.indexOfFirst { found.start + found.document.text.substring(0, it).toByteArray(currentCharset).size >= afterByte }
                        if (match >= 0) { currentMatchIndex = match; highlightSearchMatch(query) }
                    }
                }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    private fun navigateSearchMatch(delta: Int) {
        if (currentChunk?.document?.hasMore == true && delta > 0 &&
            (searchMatches.isEmpty() || currentMatchIndex == searchMatches.lastIndex)) {
            searchFollowingChunks(searchInput.text.toString()); return
        }
        if (searchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + delta + searchMatches.size) % searchMatches.size
        searchStatus.text = "${currentMatchIndex + 1}/${searchMatches.size}"
        highlightSearchMatch(searchInput.text.toString())
    }

    private fun highlightSearchMatch(query: String, followMatch: Boolean = true) {
        if (currentMatchIndex !in searchMatches.indices) return
        val pos = searchMatches[currentMatchIndex]
        val spannable = contentEditText.text
        spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java).forEach { spannable.removeSpan(it) }
        spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java).forEach { spannable.removeSpan(it) }
        for (m in searchMatches) {
            val bg = if (m == pos) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt()
            spannable.setSpan(BackgroundColorSpan(bg), m, m + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.BLACK), m, m + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (followMatch) {
            contentEditText.setSelection(pos, pos + query.length)
            if (adjustingWindow) pendingSearchPosition = pos
            else contentEditText.post {
                contentEditText.layout?.let { scrollView.scrollTo(0, contentEditText.totalPaddingTop + it.getLineTop(it.getLineForOffset(pos.coerceAtMost(contentEditText.length())))) }
            }
        }
    }

    private fun clearSearchHighlight() {
        pendingSearchPosition = null
        val text = contentEditText.text
        text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
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
        if (::syntax.isInitialized && syntax.onActivityResult(requestCode, resultCode, data)) return
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
                .setPositiveButton("Discard") { _, _ -> returnToFileManager() }
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
