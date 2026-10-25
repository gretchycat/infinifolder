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
import java.nio.charset.StandardCharsets

/**
 * Interactive CSV / TSV spreadsheet viewer and editor.
 * Features auto-delimiter detection, row numbering, cell inspector/editor,
 * search/filter across columns, and in-place saving back to storage.
 */
class CsvViewerActivity : ViewerActivity() {
    private var loadedRevision: com.infinifolder.storage.ContentRevision? = null

    private var documentFullyLoaded = false


    private var uri: Uri? = null
    private var displayName: String = "spreadsheet.csv"
    private var originalData: MutableList<MutableList<String>> = mutableListOf()
    private var filteredData: List<Pair<Int, List<String>>> = emptyList() // Pair<OriginalRowIndex, RowData>
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
        val targetUri = uri ?: run {
            Toast.makeText(this, "No spreadsheet URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE

        Thread {
            try {
                val document = MediaSourceAdapter.readDocument(this, targetUri, maxBytes = 8 * 1024 * 1024)
                val rawText = document.text
                delimiter = detectDelimiter(rawText, displayName)
                val rows = parseCsv(rawText, delimiter)

                runOnUiThread {
                    documentFullyLoaded = !document.hasMore
                    loadedRevision = document.revision
                    if (!documentFullyLoaded) Toast.makeText(this, "Large spreadsheet: partial preview is read-only", Toast.LENGTH_LONG).show()
                    originalData = rows.map { it.toMutableList() }.toMutableList()
                    applyFilter(searchInput.text.toString())
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed to load CSV: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        filteredData = if (q.isEmpty()) {
            originalData.mapIndexed { idx, row -> idx to row }
        } else {
            originalData.mapIndexedNotNull { idx, row ->
                if (idx == 0) idx to row // Keep header
                else if (row.any { it.lowercase().contains(q) }) idx to row else null
            }
        }
        statsTextView.text = "${filteredData.size} rows"
        renderTable()
    }

    private fun renderTable() {
        tableContainer.removeAllViews()
        if (filteredData.isEmpty()) {
            val emptyNotice = TextView(this).apply {
                text = "No matching records"
                setTextColor(0xFF888888.toInt())
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            tableContainer.addView(emptyNotice)
            return
        }

        val columnCount = filteredData.maxOfOrNull { it.second.size } ?: 0

        // Render each row (capped at 500 rows for smooth UI performance)
        val maxRenderRows = 500
        val renderList = filteredData.take(maxRenderRows)

        renderList.forEachIndexed { displayIndex, (originalRowIndex, rowData) ->
            val isHeader = displayIndex == 0 && originalRowIndex == 0
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
            for (col in 0 until columnCount) {
                val cellText = rowData.getOrNull(col).orEmpty()
                val cellView = TextView(this).apply {
                    text = cellText
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
                        setOnClickListener { promptEditCell(originalRowIndex, col, cellText) }
                    }
                }
                rowView.addView(cellView)
            }

            tableContainer.addView(rowView)
        }

        if (filteredData.size > maxRenderRows) {
            val truncatedNotice = TextView(this).apply {
                text = "… Showing first $maxRenderRows of ${filteredData.size} rows"
                setTextColor(0xFF888888.toInt())
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            tableContainer.addView(truncatedNotice)
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
        val csvText = formatCsv(originalData, delimiter)

        progressBar.visibility = View.VISIBLE
        Thread {
            val res = MediaSourceAdapter.saveContent(this, u, csvText.toByteArray(StandardCharsets.UTF_8), loadedRevision)
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (res is com.infinifolder.model.ProviderResult.Success) {
                    loadedRevision = com.infinifolder.storage.ContentRevision.of(csvText.toByteArray(StandardCharsets.UTF_8))
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
        fun detectDelimiter(sample: String, fileName: String = ""): Char {
            if (fileName.endsWith(".tsv", ignoreCase = true)) return '\t'
            val firstLine = sample.lineSequence().firstOrNull() ?: return ','
            val commaCount = firstLine.count { it == ',' }
            val tabCount = firstLine.count { it == '\t' }
            val semiCount = firstLine.count { it == ';' }
            val pipeCount = firstLine.count { it == '|' }

            return when {
                tabCount > commaCount && tabCount > semiCount -> '\t'
                semiCount > commaCount && semiCount > tabCount -> ';'
                pipeCount > commaCount && pipeCount > semiCount -> '|'
                else -> ','
            }
        }

        fun parseCsv(text: String, delim: Char = ','): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            val currentRow = mutableListOf<String>()
            val currentField = StringBuilder()
            var insideQuotes = false
            var i = 0
            val len = text.length

            while (i < len) {
                val c = text[i]
                when {
                    c == '"' -> {
                        if (insideQuotes && i + 1 < len && text[i + 1] == '"') {
                            currentField.append('"')
                            i++ // Skip escaped quote
                        } else {
                            insideQuotes = !insideQuotes
                        }
                    }
                    c == delim && !insideQuotes -> {
                        currentRow.add(currentField.toString().trim())
                        currentField.clear()
                    }
                    (c == '\r' || c == '\n') && !insideQuotes -> {
                        if (c == '\r' && i + 1 < len && text[i + 1] == '\n') i++ // Skip CRLF
                        currentRow.add(currentField.toString().trim())
                        currentField.clear()
                        if (currentRow.isNotEmpty() && currentRow.any { it.isNotEmpty() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                    }
                    else -> {
                        currentField.append(c)
                    }
                }
                i++
            }
            if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
                currentRow.add(currentField.toString().trim())
                rows.add(currentRow.toList())
            }
            return rows
        }

        fun formatCsv(rows: List<List<String>>, delimiter: Char = ','): String {
            return buildString {
                rows.forEach { row ->
                    val line = row.joinToString(delimiter.toString()) { field ->
                        if (field.contains(delimiter) || field.contains('"') || field.contains('\n') || field.contains('\r')) {
                            "\"" + field.replace("\"", "\"\"") + "\""
                        } else {
                            field
                        }
                    }
                    append(line).append("\n")
                }
            }
        }
    }
}
