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
    private var currentChunk: TableChunk? = null
    private var headerRow: List<String>? = null
    private val chunkOffsets = mutableListOf(0L to 0L)
    private var chunkIndex = 0
    private var renderOffset = 0
    private var columnOffset = 0
    private lateinit var columnsBar: LinearLayout
    private lateinit var previousColumns: Button
    private lateinit var nextColumns: Button
    private lateinit var columnsLabel: TextView
    private lateinit var pageBar: LinearLayout
    private lateinit var previousPage: Button
    private lateinit var nextPage: Button
    private lateinit var pageLabel: TextView


    private var uri: Uri? = null
    private var displayName: String = "spreadsheet.csv"
    private var originalData: MutableList<MutableList<String>> = mutableListOf()
    private var filteredData: List<Pair<Long, List<String>>> = emptyList() // Pair<OriginalRowIndex, RowData>
    private var delimiter: Char = ','
    private var isDirty = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var tableContainer: LinearLayout
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
            text = "0 rows"
            textSize = 12f
            setTextColor(if (isDark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        filterRow.addView(statsTextView)

        mainColumn.addView(filterRow)

        pageBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        previousPage = Button(this).apply { text = "Previous"; setOnClickListener { movePage(-1) } }
        nextPage = Button(this).apply { text = "Next"; setOnClickListener { movePage(1) } }
        pageLabel = TextView(this).apply { textSize = 12f; setTextColor(if (isDark) Color.WHITE else Color.BLACK) }
        pageBar.addView(previousPage)
        pageBar.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        pageBar.addView(nextPage)
        mainColumn.addView(pageBar)
        columnsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        previousColumns = Button(this).apply { text = "◀ Columns"; setOnClickListener { columnOffset = (columnOffset - 16).coerceAtLeast(0); renderTable() } }
        nextColumns = Button(this).apply { text = "Columns ▶"; setOnClickListener { columnOffset += 16; renderTable() } }
        columnsLabel = TextView(this).apply { textSize = 12f; setTextColor(if (isDark) Color.WHITE else Color.BLACK) }
        columnsBar.addView(previousColumns)
        columnsBar.addView(columnsLabel, LinearLayout.LayoutParams(0, -2, 1f))
        columnsBar.addView(nextColumns)
        mainColumn.addView(columnsBar)

        // Content Area: HorizontalScrollView wrapping ScrollView wrapping TableLayout
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(if (isDark) 0xFF181818.toInt() else 0xFFF0F0F0.toInt())
        }

        val horizontalScroll = HorizontalScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val verticalScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -1)
        }

        tableContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-2, -2)
        }
        verticalScroll.addView(tableContainer)
        horizontalScroll.addView(verticalScroll)
        contentFrame.addView(horizontalScroll)

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
        chunkOffsets.clear(); chunkOffsets += (0L to 0L); chunkIndex = 0; renderOffset = 0
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
        progressBar.visibility = View.GONE
        Toast.makeText(this, "Failed to load CSV: ${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun showChunk(chunk: TableChunk) {
        currentChunk = chunk; delimiter = chunk.table.delimiter; tableMetadata = chunk.table.metadata
        documentFullyLoaded = chunk.table.metadata.attributes["complete"] == "true"
        loadedRevision = chunk.originalBytes?.let { com.infinifolder.storage.ContentRevision.of(it) }
        originalData = chunk.table.rows.map { it.toMutableList() }.toMutableList()
        filteredData = chunk.rowIndices.zip(chunk.table.rows)
        if (chunk.rowIndices.firstOrNull() != 0L) headerRow?.let { filteredData = listOf(0L to it) + filteredData }
        statsTextView.text = "${chunk.table.rows.size} rows${if (!documentFullyLoaded) " in chunk" else ""}"
        renderOffset = 0; renderTable(); progressBar.visibility = View.GONE
    }

    private fun movePage(direction: Int) {
        if (documentFullyLoaded) {
            renderOffset = (renderOffset + direction * 50).coerceIn(0, (filteredData.size - 1).coerceAtLeast(0))
            renderTable(); return
        }
        val chunk = currentChunk ?: return
        val bookmark = if (direction < 0) {
            if (chunkIndex == 0) return
            chunkOffsets[--chunkIndex]
        } else {
            if (!chunk.hasNext) return
            while (chunkOffsets.size > chunkIndex + 1) chunkOffsets.removeAt(chunkOffsets.lastIndex)
            (chunk.nextOffset to chunk.nextRowIndex).also { chunkOffsets += it; chunkIndex++ }
        }
        val reader = chunkReader ?: return
        val request = ++documentGeneration
        val query = searchInput.text.toString().trim()
        documentTask?.cancel(true); progressBar.visibility = View.VISIBLE
        previousPage.isEnabled = false; nextPage.isEnabled = false
        documentTask = documentWorker.submit {
            try {
                val next = reader.findRows(bookmark.first, bookmark.second, filter = query)
                runOnUiThread { if (request == documentGeneration && !isDestroyed) showChunk(next) }
            } catch (error: Exception) { showReadError(request, error) }
        }
    }

    private fun applyFilter(query: String) {
        if (!documentFullyLoaded) { loadSpreadsheet(); return }
        val q = query.trim().lowercase()
        filteredData = originalData.mapIndexedNotNull { idx, row ->
            if (idx == 0 || q.isEmpty() || row.any { it.contains(q, ignoreCase = true) }) idx.toLong() to row else null
        }
        statsTextView.text = "${filteredData.size} rows"; renderOffset = 0; renderTable()
    }

    private fun loadForEditing(row: Long, column: Int) {
        if (chunkReader?.sourceMetadata?.length?.let { it > 8L * 1024 * 1024 } == true) {
            Toast.makeText(this, "Large spreadsheets are read-only; use the chunk controls to browse", Toast.LENGTH_LONG).show(); return
        }
        val targetUri = uri ?: return
        val request = ++documentGeneration
        documentTask?.cancel(true); progressBar.visibility = View.VISIBLE
        documentTask = documentWorker.submit {
            try {
                val loaded = MediaSourceAdapter.readTable(this, targetUri, displayName, 8 * 1024 * 1024)
                runOnUiThread {
                    if (request != documentGeneration || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    if (loaded.hasMore) Toast.makeText(this, "Only fully loaded documents can be edited or saved", Toast.LENGTH_LONG).show()
                    else {
                        documentFullyLoaded = true; loadedRevision = loaded.revision; tableMetadata = loaded.table.metadata
                        originalData = loaded.table.rows.map { it.toMutableList() }.toMutableList()
                        applyFilter(searchInput.text.toString())
                        renderOffset = ((filteredData.indexOfFirst { it.first == row } - 1).coerceAtLeast(0) / 50) * 50
                        renderTable()
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
        documentWorker.execute { runCatching { chunkReader?.close() }; chunkReader = null }
        documentWorker.shutdown()
        super.onDestroy()
    }

    private fun renderTable() {
        tableContainer.removeAllViews()
        val columnCount = filteredData.maxOfOrNull { it.second.size } ?: 0
        columnOffset = columnOffset.coerceAtMost(((columnCount - 1).coerceAtLeast(0) / 16) * 16)
        val columnEnd = (columnOffset + 16).coerceAtMost(columnCount)
        columnsBar.visibility = if (columnCount > 16) View.VISIBLE else View.GONE
        previousColumns.isEnabled = columnOffset > 0; nextColumns.isEnabled = columnEnd < columnCount
        columnsLabel.text = "${columnOffset + 1}–$columnEnd / $columnCount"

        // Only one page gets Android views; record bytes are loaded separately by Scribe.
        val hasHeader = filteredData.firstOrNull()?.first == 0L
        val dataStart = if (hasHeader) 1 else 0
        val start = if (documentFullyLoaded) (dataStart + renderOffset).coerceAtMost(filteredData.size) else dataStart
        val end = (start + 50).coerceAtMost(filteredData.size)
        val renderList = (if (hasHeader) listOf(filteredData.first()) else emptyList()) + filteredData.subList(start, end)
        val chunk = currentChunk
        pageBar.visibility = if (!documentFullyLoaded || filteredData.size > 51) View.VISIBLE else View.GONE
        previousPage.isEnabled = if (documentFullyLoaded) renderOffset > 0 else chunkIndex > 0 && chunkReader?.canRead(chunkOffsets[chunkIndex - 1].first) == true
        nextPage.isEnabled = if (documentFullyLoaded) end < filteredData.size else chunk?.hasNext == true
        pageLabel.text = if (documentFullyLoaded) "Rows ${start + 1}–$end / ${filteredData.size}"
            else "Bytes ${chunk?.start ?: 0}–${chunk?.nextOffset ?: 0} / ${chunkReader?.sourceMetadata?.length ?: "?"}"

        if (filteredData.isEmpty()) {
            val emptyNotice = TextView(this).apply {
                text = "No matching records"
                setTextColor(0xFF888888.toInt())
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            tableContainer.addView(emptyNotice)
            return
        }

        tableContainer.post { (tableContainer.parent as? ScrollView)?.scrollTo(0, 0) }
        renderList.forEachIndexed { displayIndex, (originalRowIndex, rowData) ->
            val isHeader = displayIndex == 0 && originalRowIndex == 0L
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(
                    if (isHeader) (if (isDark) 0xFF2D2D30.toInt() else 0xFFE0E0E0.toInt())
                    else if (displayIndex % 2 == 0) (if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt())
                    else (if (isDark) 0xFF242424.toInt() else 0xFFF5F5F5.toInt())
                )
            }

            // Row number / Header indicator
            val rowNumView = TextView(this).apply {
                text = if (isHeader) "#" else (originalRowIndex).toString()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isHeader) (if (isDark) Color.WHITE else Color.BLACK) else (if (isDark) 0xFF888888.toInt() else 0xFF777777.toInt()))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(if (isDark) 0xFF252526.toInt() else 0xFFEAEAEA.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(44), -1)
            }
            rowView.addView(rowNumView)

            // Columns
            for (col in columnOffset until columnEnd) {
                val cellText = rowData.getOrNull(col).orEmpty()
                val cellView = TextView(this).apply {
                    text = cellText.take(512)
                    textSize = if (isHeader) 13f else 12f
                    typeface = if (isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(
                        if (isHeader) (if (isDark) 0xFF9CDCFE.toInt() else 0xFF0D47A1.toInt())
                        else (if (isDark) 0xFFD4D4D4.toInt() else 0xFF212121.toInt())
                    )
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(dp(140), -1)
                    background = GradientDrawable().apply {
                        setStroke(1, if (isDark) 0xFF333333.toInt() else 0xFFD0D0D0.toInt())
                    }
                    if (!isHeader) {
                        setOnClickListener {
                            if (documentFullyLoaded) promptEditCell(originalRowIndex.toInt(), col, cellText)
                            else loadForEditing(originalRowIndex, col)
                        }
                    }
                }
                rowView.addView(cellView)
            }

            tableContainer.addView(rowView)
        }

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
