package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import io.github.gretchycat.infiniscribe.DelimitedText
import io.github.gretchycat.infiniscribe.TableDocument
import io.github.gretchycat.infiniscribe.TableDocuments
import io.github.gretchycat.infiniscribe.TableChunk
import io.github.gretchycat.infiniscribe.TableChunkReader
import io.github.gretchycat.infinistream.CancellationToken
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Interactive CSV / TSV spreadsheet viewer and editor.
 * Features auto-delimiter detection, row numbering, cell inspector/editor,
 * search/filter across columns, and in-place saving back to storage.
 */
class CsvViewerActivity : ViewerActivity() {
    private var loadedRevision: com.infinifolder.storage.ContentRevision? = null

    private var tableMetadata: io.github.gretchycat.infinistream.StreamMetadata? = null
    private var documentFullyLoaded = false
    private val documentWorker = Executors.newSingleThreadExecutor()
    private var documentTask: Future<*>? = null
    @Volatile private var documentGeneration = 0
    @Volatile private var chunkReader: TableChunkReader? = null
    private val countWorker = Executors.newSingleThreadExecutor()
    private var countTask: Future<*>? = null
    private var totalDataRows: Long? = null
    private var rowCountStarted = false
    private var rowCountRunning = false
    private var currentChunk: TableChunk? = null
    private var headerRow: List<String>? = null
    private val rowWindow = ScrollWindow<TableChunk>(5, { it.start }, { chunk -> chunk.rowIndices.count { it != 0L } })
    private var edgeLoading = false
    private var adjustingWindow = false
    private var blockedEdge: Pair<Int, Long>? = null
    private data class RowAnchor(val id: Long, val top: Int)
    private lateinit var rowList: ListView
    private lateinit var headerContainer: LinearLayout
    private val rowAdapter = RowAdapter()
    private var columnEnd = 0
    private var columnOffset = 0
    private lateinit var columnsBar: LinearLayout
    private lateinit var previousColumns: Button
    private lateinit var nextColumns: Button
    private lateinit var columnsLabel: TextView
    private var uri: Uri? = null
    private var displayName: String = "spreadsheet.csv"
    private var originalData: MutableList<MutableList<String>> = mutableListOf()
    private var filteredData: List<Pair<Long, List<String>>> = emptyList() // Pair<OriginalRowIndex, RowData>
    private var delimiter: Char = ','
    private var isDirty = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var searchInput: EditText
    private var isDark: Boolean = true
    private lateinit var statsTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var saveBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = com.infinifolder.theme.ThemeManager.isSystemInDarkMode(this)
        val barColor = if (isDark) 0xFF1E1E1E.toInt() else 0xFFE0E0E0.toInt()
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        uri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra("extra_uri")?.let { Uri.parse(it) }
        displayName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra("extra_name")
            ?: uri?.lastPathSegment
            ?: "spreadsheet.csv"

        setupUI()
        loadSpreadsheet()
    }

    private fun setupUI() {
        val basePadH = dp(4)
        val basePadT = dp(4)
        val basePadB = dp(8)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(if (isDark) 0xFF1E1E1E.toInt() else 0xFFF5F5F5.toInt())
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

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Top Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(if (isDark) 0xFF252526.toInt() else 0xFFE0E0E0.toInt())
        }

        val backBtn = Button(this).apply {
            text = "←"
            textSize = 18f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackPressed() }
        }
        toolbar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        val titleView = TextView(this).apply {
            text = displayName
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))

        saveBtn = Button(this).apply {
            text = "Save"
            textSize = 12f
            setTextColor(0xFF4CAF50.toInt())
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(0x224CAF50)
            }
            setOnClickListener { saveSpreadsheet() }
        }
        toolbar.addView(saveBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) })

        val openExtBtn = Button(this).apply {
            text = "↗"
            textSize = 16f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openExternally() }
        }
        toolbar.addView(openExtBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        DebugTools.attachToolbarButton(this, toolbar)
        mainColumn.addView(toolbar)

        // Filter / Search bar + Stats
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFECECEC.toInt())
        }

        searchInput = EditText(this).apply {
            hint = "Filter rows…"
            setHintTextColor(if (isDark) 0xFF888888.toInt() else 0xFF999999.toInt())
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            textSize = 13f
            setBackgroundColor(if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        filterRow.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))

        statsTextView = TextView(this).apply {
            text = "Loading…"
            maxLines = 2
            maxWidth = dp(190)
            textSize = 12f
            setTextColor(if (isDark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        filterRow.addView(statsTextView)

        mainColumn.addView(filterRow)

        columnsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        previousColumns = Button(this).apply { text = "◀ Columns"; setOnClickListener { columnOffset = (columnOffset - 16).coerceAtLeast(0); renderTable() } }
        nextColumns = Button(this).apply { text = "Columns ▶"; setOnClickListener { columnOffset += 16; renderTable() } }
        columnsLabel = TextView(this).apply { textSize = 12f; setTextColor(if (isDark) Color.WHITE else Color.BLACK) }
        columnsBar.addView(previousColumns)
        columnsBar.addView(columnsLabel, LinearLayout.LayoutParams(0, -2, 1f))
        columnsBar.addView(nextColumns)
        mainColumn.addView(columnsBar)

        // Native rows recycle vertically beneath a fixed header; columns scroll together horizontally.
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(if (isDark) 0xFF181818.toInt() else 0xFFF0F0F0.toInt())
        }

        val horizontalScroll = HorizontalScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val tableColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowList = ListView(this).apply {
            divider = null
            adapter = rowAdapter
            isFastScrollEnabled = false
        }
        // Registration invokes onScroll synchronously: publish the ListView field first.
        rowList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) { maybeLoadRows() }
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (!adjustingWindow) updateRowStats()
                maybeLoadRows()
            }
        })
        tableColumn.addView(headerContainer, LinearLayout.LayoutParams(-1, -2))
        tableColumn.addView(rowList, LinearLayout.LayoutParams(-1, 0, 1f))
        horizontalScroll.addView(tableColumn, FrameLayout.LayoutParams(dp(44), -1))
        contentFrame.addView(horizontalScroll)

        val emptyNotice = TextView(this).apply {
            text = "No matching records"
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            visibility = View.GONE
        }
        contentFrame.addView(emptyNotice, FrameLayout.LayoutParams(-1, -1))
        rowList.emptyView = emptyNotice
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            visibility = View.VISIBLE
        }
        contentFrame.addView(progressBar)

        mainColumn.addView(contentFrame)
        rootLayout.addView(mainColumn)
        setContentView(rootLayout)
    }

    private fun loadSpreadsheet() {
        documentFullyLoaded = false
        val targetUri = uri ?: run { Toast.makeText(this, "No spreadsheet URI provided", Toast.LENGTH_SHORT).show(); finish(); return }
        val request = ++documentGeneration
        documentTask?.cancel(true); progressBar.visibility = View.VISIBLE
        rowWindow.clear(); edgeLoading = false; blockedEdge = null
        val query = searchInput.text.toString().trim()
        documentTask = documentWorker.submit {
            try {
                chunkReader?.close()
                val reader = TableChunkReader(MediaSourceAdapter.openDocumentSource(this, targetUri), displayName,
                    CancellationToken { isDestroyed || isFinishing })
                chunkReader = reader
                val chunk = reader.findRows(filter = query)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    headerRow = reader.firstRow
                    showChunk(chunk)
                }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    private fun showReadError(request: Int, error: Exception) = runOnUiThread {
        if (request != documentGeneration || isDestroyed) return@runOnUiThread
        progressBar.visibility = View.GONE; edgeLoading = false
        Toast.makeText(this, "Failed to load CSV: ${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun showChunk(chunk: TableChunk) {
        rowWindow.reset(chunk); edgeLoading = false; blockedEdge = null
        currentChunk = chunk; delimiter = chunk.table.delimiter; tableMetadata = chunk.table.metadata
        documentFullyLoaded = chunk.table.metadata.attributes["complete"] == "true"
        loadedRevision = chunk.originalBytes?.let { com.infinifolder.storage.ContentRevision.of(it) }
        originalData = if (documentFullyLoaded) chunk.table.rows.map { it.toMutableList() }.toMutableList() else mutableListOf()
        rememberRowTotal(chunk)
        rebuildWindowRows()
        renderTable(preservePosition = false); progressBar.visibility = View.GONE
        rowList.post { ensureRowCount() }
    }

    private fun rebuildWindowRows() {
        filteredData = rowWindow.pages.flatMap { it.rowIndices.zip(it.table.rows) }.filter { it.first != 0L }
    }

    private fun rememberRowTotal(chunk: TableChunk) {
        if (!chunk.hasNext) {
            totalDataRows = (chunk.nextRowIndex - 1).coerceAtLeast(0)
            rowCountRunning = false
            countTask?.cancel(true)
        }
    }

    private fun ensureRowCount() {
        if (isDestroyed || isFinishing || totalDataRows != null || rowCountStarted) return
        val targetUri = uri ?: return
        rowCountStarted = true; rowCountRunning = true
        updateRowStats()
        countTask = countWorker.submit {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val total = TableChunkReader(MediaSourceAdapter.openDocumentSource(this, targetUri), displayName,
                    CancellationToken { isDestroyed || isFinishing }).use { it.countDataRows() }
                runOnUiThread {
                    if (isDestroyed || isFinishing || totalDataRows != null) return@runOnUiThread
                    totalDataRows = total; rowCountRunning = false
                    updateRowStats()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isDestroyed || isFinishing || totalDataRows != null) return@runOnUiThread
                    rowCountRunning = false
                    updateRowStats()
                }
            }
        }
    }

    private fun updateRowStats() {
        if (adjustingWindow || !::rowList.isInitialized || !::statsTextView.isInitialized || !::searchInput.isInitialized) return
        val first = filteredData.getOrNull(rowList.firstVisiblePosition)?.first
        val last = filteredData.getOrNull(rowList.lastVisiblePosition)?.first
        statsTextView.text = CsvRowStats.label(first, last, totalDataRows, rowCountRunning,
            searchInput.text.toString().isNotBlank())
    }

    private fun rowAnchor(): RowAnchor? = filteredData.getOrNull(rowList.firstVisiblePosition)?.let {
        RowAnchor(it.first, rowList.getChildAt(0)?.top ?: 0)
    }

    private fun maybeLoadRows() {
        if (isDestroyed || isFinishing || documentFullyLoaded || edgeLoading || adjustingWindow || !::rowList.isInitialized) return
        val reader = chunkReader ?: return
        val pages = rowWindow.pages; if (pages.isEmpty() || rowList.height == 0) return
        val first = pages.first(); val last = pages.last()
        val firstVisible = rowList.firstVisiblePosition.coerceAtLeast(0)
        val lastVisible = rowList.lastVisiblePosition.coerceAtLeast(0)
        val direction = when {
            lastVisible >= filteredData.size - 9 && last.hasNext && rowWindow.canAppend(firstVisible) && blockedEdge != (1 to last.nextOffset) -> 1
            firstVisible < 8 && first.start > 0 && reader.canRead((first.start - 64 * 1024).coerceAtLeast(0)) &&
                rowWindow.canPrepend(lastVisible) && blockedEdge != (-1 to first.start) -> -1
            else -> return
        }
        val request = documentGeneration
        val edge = if (direction > 0) last.nextOffset else first.start
        val query = searchInput.text.toString().trim()
        edgeLoading = true
        documentTask = documentWorker.submit {
            try {
                val next = if (direction > 0) reader.findRows(edge, last.nextRowIndex, filter = query)
                    else reader.findPreviousRows(edge, first.firstRowIndex, filter = query)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    edgeLoading = false
                    rememberRowTotal(next)
                    val anchor = rowAnchor()
                    val shift = if (direction > 0) rowWindow.append(next, rowList.firstVisiblePosition.coerceAtLeast(0))
                        else rowWindow.prepend(next, rowList.lastVisiblePosition.coerceAtLeast(0))
                    if (shift != null) { rebuildWindowRows(); renderTable(anchor = anchor) }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    edgeLoading = false; blockedEdge = direction to edge
                    Toast.makeText(this, error.message ?: "Cannot load more rows", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyFilter(query: String) {
        if (!documentFullyLoaded) { loadSpreadsheet(); return }
        val q = query.trim().lowercase()
        filteredData = originalData.mapIndexedNotNull { idx, row ->
            if (idx != 0 && (q.isEmpty() || row.any { it.contains(q, ignoreCase = true) })) idx.toLong() to row else null
        }
        headerRow = originalData.firstOrNull()
        totalDataRows = (originalData.size - 1).coerceAtLeast(0).toLong()
        rowCountRunning = false; countTask?.cancel(true)
        renderTable(preservePosition = false)
    }

    private fun loadForEditing(row: Long, column: Int) {
        if (chunkReader?.sourceMetadata?.length?.let { it > 8L * 1024 * 1024 } == true) {
            Toast.makeText(this, "Large spreadsheets are read-only; scroll to browse", Toast.LENGTH_LONG).show(); return
        }
        val targetUri = uri ?: return
        val request = ++documentGeneration
        documentTask?.cancel(true); edgeLoading = true; progressBar.visibility = View.VISIBLE
        documentTask = documentWorker.submit {
            try {
                val loaded = MediaSourceAdapter.readTable(this, targetUri, displayName, 8 * 1024 * 1024)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE; edgeLoading = false
                    if (loaded.hasMore) Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
                    else {
                        documentFullyLoaded = true; loadedRevision = loaded.revision; tableMetadata = loaded.table.metadata
                        originalData = loaded.table.rows.map { it.toMutableList() }.toMutableList()
                        applyFilter(searchInput.text.toString())
                        filteredData.indexOfFirst { it.first == row }.takeIf { it >= 0 }?.let { rowList.setSelection(it) }
                        if (row <= Int.MAX_VALUE && row.toInt() in originalData.indices) {
                            val value = originalData[row.toInt()].getOrNull(column).orEmpty()
                            promptEditCell(row.toInt(), column, value)
                        }
                    }
                }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    override fun onDestroy() {
        documentGeneration++; documentTask?.cancel(true)
        countTask?.cancel(true); countWorker.shutdownNow()
        documentWorker.execute { runCatching { chunkReader?.close() }; chunkReader = null }
        documentWorker.shutdown()
        super.onDestroy()
    }

    private fun renderTable(preservePosition: Boolean = true, anchor: RowAnchor? = if (preservePosition) rowAnchor() else null) {
        adjustingWindow = true
        val columnCount = maxOf(headerRow?.size ?: 0, filteredData.maxOfOrNull { it.second.size } ?: 0)
        columnOffset = columnOffset.coerceAtMost(((columnCount - 1).coerceAtLeast(0) / 16) * 16)
        columnEnd = (columnOffset + 16).coerceAtMost(columnCount)
        columnsBar.visibility = if (columnCount > 16) View.VISIBLE else View.GONE
        previousColumns.isEnabled = columnOffset > 0; nextColumns.isEnabled = columnEnd < columnCount
        columnsLabel.text = "${columnOffset + 1}–$columnEnd / $columnCount"
        (headerContainer.parent as LinearLayout).layoutParams = FrameLayout.LayoutParams(dp(44 + (columnEnd - columnOffset) * 140), -1)
        headerContainer.removeAllViews()
        headerRow?.let { headerContainer.addView(bindRow(null, 0L, it, true)) }
        rowAdapter.notifyDataSetChanged()
        val position = anchor?.let { saved -> filteredData.indexOfFirst { it.first == saved.id } } ?: 0
        rowList.setSelectionFromTop(position.coerceAtLeast(0), anchor?.top ?: 0)
        val request = documentGeneration
        val observer = rowList.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (request == documentGeneration && !isDestroyed) {
                    adjustingWindow = false
                    updateRowStats()
                    rowList.post { maybeLoadRows() }
                }
                return true
            }
        })
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = filteredData.size
        override fun getItem(position: Int) = filteredData[position]
        override fun getItemId(position: Int) = filteredData[position].first
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val (id, cells) = filteredData[position]
            return bindRow(convertView as? LinearLayout, id, cells, false)
        }
    }

    private fun bindRow(recycled: LinearLayout?, id: Long, cells: List<String>, header: Boolean): LinearLayout {
        val row = recycled ?: LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cellCount = 1 + columnEnd - columnOffset
        if (row.childCount != cellCount) {
            row.removeAllViews()
            repeat(cellCount) { index ->
                row.addView(TextView(this).apply {
                    textSize = if (header) 13f else 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    gravity = if (index == 0) Gravity.CENTER else Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setStroke(1, if (isDark) 0xFF333333.toInt() else 0xFFD0D0D0.toInt()) }
                }, LinearLayout.LayoutParams(dp(if (index == 0) 44 else 140), dp(52)))
            }
        }
        row.setBackgroundColor(if (header) (if (isDark) 0xFF2D2D30.toInt() else 0xFFE0E0E0.toInt())
            else if (id % 2 == 0L) (if (isDark) 0xFF1E1E1E.toInt() else Color.WHITE)
            else (if (isDark) 0xFF242424.toInt() else 0xFFF5F5F5.toInt()))
        (row.getChildAt(0) as TextView).apply {
            text = if (header) "#" else id.toString()
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDark) Color.LTGRAY else Color.DKGRAY)
        }
        for (column in columnOffset until columnEnd) {
            val value = cells.getOrNull(column).orEmpty()
            (row.getChildAt(1 + column - columnOffset) as TextView).apply {
                text = value.take(512)
                typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (header) (if (isDark) 0xFF9CDCFE.toInt() else 0xFF0D47A1.toInt())
                    else (if (isDark) 0xFFD4D4D4.toInt() else 0xFF212121.toInt()))
                setOnClickListener(if (header) null else View.OnClickListener {
                    if (documentFullyLoaded) promptEditCell(id.toInt(), column, value) else loadForEditing(id, column)
                })
            }
        }
        return row
    }

    private fun promptEditCell(rowIndex: Int, colIndex: Int, currentValue: String) {
        if (!documentFullyLoaded) {
            Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
            return
        }

        val input = EditText(this).apply {
            setText(currentValue)
            setSelectAllOnFocus(true)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2A2A2A.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val colName = originalData.firstOrNull()?.getOrNull(colIndex) ?: "Column ${colIndex + 1}"

        AlertDialog.Builder(this)
            .setTitle("Edit Cell [Row $rowIndex, $colName]")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val newValue = input.text.toString()
                if (rowIndex in originalData.indices) {
                    val row = originalData[rowIndex]
                    while (row.size <= colIndex) row.add("")
                    row[colIndex] = newValue
                    isDirty = true
                    saveBtn.visibility = View.VISIBLE
                    applyFilter(searchInput.text.toString())
                }
            }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cell", currentValue))
                Toast.makeText(this, "Cell content copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSpreadsheet() {
        if (!documentFullyLoaded) {
            Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
            return
        }

        val u = uri ?: return
        val metadata = tableMetadata ?: return
        val formatted = TableDocuments.format(TableDocument(originalData, delimiter, metadata))
        val csvText = formatted.text

        progressBar.visibility = View.VISIBLE
        Thread {
            val res = MediaSourceAdapter.saveContent(this, u, formatted.bytes, loadedRevision)
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (res is com.infinifolder.model.ProviderResult.Success) {
                    loadedRevision = com.infinifolder.storage.ContentRevision.of(formatted.bytes)
                    isDirty = false
                    saveBtn.visibility = View.GONE
                    Toast.makeText(this, "Spreadsheet saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    promptSaveAs(csvText)
                }
            }
        }.start()
    }

    private fun promptSaveAs(content: String) {
        val input = EditText(this).apply {
            setText(displayName)
            setSelectAllOnFocus(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Save As…")
            .setMessage("Source is read-only. Save a copy to local storage:")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val destDir = getExternalFilesDir(null) ?: filesDir
                    val destFile = java.io.File(destDir, newName)
                    try {
                        destFile.writeText(content)
                        Toast.makeText(this, "Saved to ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
                        isDirty = false
                        saveBtn.visibility = View.GONE
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openExternally() {
        val u = uri ?: return
        val extIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(u, if (delimiter == '\t') "text/tab-separated-values" else "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(extIntent, "Open with…"))
        } catch (e: Exception) {
            Toast.makeText(this, "No external handler found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (isDirty) {
            AlertDialog.Builder(this)
                .setTitle("Discard changes?")
                .setMessage("You have unsaved spreadsheet changes. Discard them?")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep editing", null)
                .show()
            return
        }
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val newIsDark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isDark != newIsDark) {
            isDark = newIsDark
            val barColor = if (isDark) 0xFF1E1E1E.toInt() else 0xFFE0E0E0.toInt()
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                window.decorView.systemUiVisibility = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            setupUI()
            renderTable()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        // Compatibility entry points; parsing and formatting are owned by the JVM Scribe library.
        fun detectDelimiter(sample: String, fileName: String = ""): Char = DelimitedText.detectDelimiter(sample, fileName)
        fun parseCsv(text: String, delim: Char = ','): List<List<String>> = DelimitedText.parseCsv(text, delim)
        fun formatCsv(rows: List<List<String>>, delimiter: Char = ','): String = DelimitedText.formatCsv(rows, delimiter)
    }
}
