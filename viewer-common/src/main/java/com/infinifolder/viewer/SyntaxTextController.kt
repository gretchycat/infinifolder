package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.storage.DiagnosticLog
import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.CancellationToken
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.ArrayDeque

/** Shared host adapter. Scribe owns tokens/descriptors; Android owns spans, preferences and pickers. */
class SyntaxTextController(private val activity: Activity, private val edit: EditText, private val fileName: String,
    private val mime: String = "", private val sourceOffset: () -> Long = { 0L },
    private val stableLayout: ((() -> Unit) -> Boolean)? = null,
    private val afterApply: () -> Unit = {}) {
    private val preferences = activity.getSharedPreferences("text-appearance", Activity.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private val appliedSpans = ArrayDeque<Any>()
    private var applySerial = 0
    @Volatile private var disposed = false
    @Volatile private var generation = 0
    @Volatile private var requested: String? = null
    @Volatile private var lastWindowSummary = "none"
    @Volatile private var lastHighlightSummary = "none"
    var theme: TextTheme = readTheme(); private set
    private var languageChoice = preferences.getString("language:$fileName", "auto")?.takeIf { it == "auto" || it in SyntaxHighlighting.languages } ?: "auto"
    private val refresh = Runnable { prepare() }
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            generation++; requested = null; applySerial++; task?.cancel(true)
        }
        override fun afterTextChanged(s: Editable?) { schedule() }
    }
    private val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener { schedule() }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { schedule() }
    init {
        edit.addTextChangedListener(watcher)
        edit.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        edit.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        applyDefaults(); schedule()
    }
    private fun readTheme(): TextTheme = runCatching { preferences.getString("theme", null)?.let(TextThemeJson::parse) }.getOrNull() ?: TextThemeJson.DARK
    fun refreshPreferences() {
        val next = readTheme()
        if (next != theme) { theme = next; requested = null; applyDefaults(); schedule() }
    }
    private fun applyDefaults() {
        if (!withStableLayout {
            val style = theme.defaults
            edit.textSize = style.fontSize ?: 13f
            edit.typeface = Typeface.create(style.fontFamily ?: "monospace", (if (style.bold == true) 1 else 0) or (if (style.italic == true) 2 else 0))
            style.foreground?.let(edit::setTextColor); style.background?.let(edit::setBackgroundColor)
            val decorations = android.graphics.Paint.UNDERLINE_TEXT_FLAG or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            edit.paintFlags = (edit.paintFlags and decorations.inv()) or
                (if (style.underline == true) android.graphics.Paint.UNDERLINE_TEXT_FLAG else 0) or
                (if (style.strikethrough == true) android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else 0)
            afterApply()
        }) handler.postDelayed({ if (!disposed) applyDefaults() }, 100)
    }
    private fun withStableLayout(action: () -> Unit): Boolean {
        stableLayout?.let { return it(action) }
        val old = edit.layout
        val visible = Rect()
        if (old == null || !edit.getLocalVisibleRect(visible)) { action(); return true }
        val line = old.getLineForVertical((visible.top - edit.totalPaddingTop).coerceAtLeast(0))
        val character = old.getLineStart(line); val oldTop = old.getLineTop(line)
        var parent = edit.parent
        while (parent != null && parent !is android.widget.ScrollView) parent = parent.parent
        val scroll = parent as? android.widget.ScrollView
        val x = scroll?.scrollX ?: edit.scrollX; val y = scroll?.scrollY ?: edit.scrollY
        val version = generation
        action()
        val observer = edit.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (!disposed && version == generation) edit.layout?.let { layout ->
                    val delta = layout.getLineTop(layout.getLineForOffset(character.coerceAtMost(edit.length()))) - oldTop
                    if (scroll != null) scroll.scrollTo(x, y + delta) else edit.scrollTo(x, y + delta)
                }
                return true
            }
        })
        return true
    }

    private fun schedule() {
        if (disposed) return
        handler.removeCallbacks(refresh); handler.postDelayed(refresh, 160)
    }
    private fun prepare() {
        if (disposed || activity.isDestroyed) return
        val visible = Rect()
        if (!edit.getLocalVisibleRect(visible) || visible.isEmpty || edit.width <= 0) return
        val text = edit.text
        // A new window can temporarily have no layout after setText(). Do not guess from
        // offset zero in that state: wait for the global-layout callback to identify the
        // actual viewport, especially when the user is far into a large document.
        val layout = edit.layout ?: return
        val visibleTop = (visible.top - edit.totalPaddingTop).coerceAtLeast(0)
        val firstLine = layout.getLineForVertical(visibleTop)
        val lastLine = layout.getLineForVertical((visible.bottom - edit.totalPaddingTop).coerceAtLeast(visibleTop))
        var first = layout.getLineStart(firstLine)
        var visibleEnd = layout.getLineEnd(lastLine)
        // A single unwrapped line can be arbitrarily long. Highlight around the horizontal viewport
        // rather than scanning from character zero on every scroll.
        if (layout.lineCount == 1 && text.length > 4096) {
            val left = (maxOf(visible.left, edit.scrollX) + edit.totalPaddingLeft).toFloat()
            val right = (maxOf(visible.left, edit.scrollX) + visible.width() - edit.totalPaddingRight).toFloat()
            first = layout.getOffsetForHorizontal(0, left).coerceIn(0, text.length)
            visibleEnd = layout.getOffsetForHorizontal(0, right).coerceIn(first, text.length)
        }
        var start = ((first - 512).coerceAtLeast(0) / 512) * 512
        if (start > 0 && Character.isLowSurrogate(text[start])) start--
        var end = maxOf(visibleEnd + 1024, start + 4096).coerceAtMost(start + 8192).coerceAtMost(text.length)
        if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) end--
        val snapshot = text.subSequence(start, end).toString()
        val language = if (languageChoice == "auto") SyntaxHighlighting.detect(fileName, mime, text.take(256).toString().substringBefore('\n')) else languageChoice
        val version = generation
        val key = "$version:$start:$end:$language:${theme.hashCode()}"
        if (requested == key) return
        requested = key; task?.cancel(true)
        val currentTheme = theme; val from = start
        val contextKnown = from == 0 && sourceOffset() == 0L
        lastWindowSummary = "generation=$version visible=$first..$visibleEnd range=$start..$end chars=${snapshot.length} language=$language"
        DiagnosticLog.record("text.syntax.window", lastWindowSummary)
        task = worker.submit {
            try {
                val highlightStarted = SystemClock.elapsedRealtime()
                val result = SyntaxHighlighting.highlight(snapshot, language, contextComplete = contextKnown,
                    maxTokens = 512,
                    cancellation = CancellationToken { disposed || version != generation || requested != key })
                val highlightMs = SystemClock.elapsedRealtime() - highlightStarted
                lastHighlightSummary = "generation=$version range=$start..$end chars=${snapshot.length} ranges=${result.ranges.size} elapsedMs=$highlightMs"
                DiagnosticLog.record("text.syntax.highlight", lastHighlightSummary)
                val styles = result.ranges.map { it.scopes }.distinct().associateWith(currentTheme::resolve)
                val resultReadyAt = SystemClock.elapsedRealtime()
                handler.post {
                    if (disposed || version != generation || requested != key) return@post
                    val mainQueueMs = SystemClock.elapsedRealtime() - resultReadyAt
                    val hasOldStyles = appliedSpans.isNotEmpty()
                    if (result.ranges.isEmpty() && !hasOldStyles) return@post
                    val hasMetricChanges = styles.values.any { style ->
                        style.fontFamily != currentTheme.defaults.fontFamily || style.fontSize != currentTheme.defaults.fontSize ||
                            style.bold != currentTheme.defaults.bold || style.italic != currentTheme.defaults.italic
                    }
                    val spansForResult = result.ranges.map { range ->
                        val style = styles.getValue(range.scopes)
                        Triple(style, from + range.start, from + range.end)
                    }
                    val applyAll = {
                        val applyStarted = SystemClock.elapsedRealtime()
                        val target = edit.text
                        while (appliedSpans.isNotEmpty()) target.removeSpan(appliedSpans.removeLast())
                        spansForResult.forEach { (style, start, end) ->
                            val colorSpan = SyntaxStyleSpan(style)
                            target.setSpan(colorSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            appliedSpans.addLast(colorSpan)
                            if (style.fontFamily != currentTheme.defaults.fontFamily || style.fontSize != currentTheme.defaults.fontSize ||
                                style.bold != currentTheme.defaults.bold || style.italic != currentTheme.defaults.italic) {
                                val span = SyntaxMetricSpan(style, activity.resources.displayMetrics)
                                target.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                appliedSpans.addLast(span)
                            }
                        }
                        afterApply()
                        DiagnosticLog.record("text.syntax.apply",
                            "generation=$version mode=stable metricChanges=$hasMetricChanges ranges=${spansForResult.size} " +
                                "spans=${appliedSpans.size} mainQueueMs=$mainQueueMs elapsedMs=${SystemClock.elapsedRealtime() - applyStarted}")
                    }
                    if (hasMetricChanges) {
                        if (!withStableLayout(applyAll)) {
                            DiagnosticLog.record("text.syntax.apply.deferred",
                                "generation=$version mode=stable ranges=${spansForResult.size} mainQueueMs=$mainQueueMs")
                            requested = null; schedule()
                        }
                    } else {
                        val serial = ++applySerial
                        fun current() = !disposed && serial == applySerial && version == generation && requested == key
                        val batch = object : Runnable {
                            var clearing = true
                            var index = 0
                            var batchCount = 0
                            var totalBatchMs = 0L
                            val applyStarted = SystemClock.elapsedRealtime()
                            override fun run() {
                                if (!current()) return
                                val batchStarted = SystemClock.elapsedRealtime()
                                val target = edit.text
                                if (clearing) {
                                    repeat(96) {
                                        if (appliedSpans.isNotEmpty()) target.removeSpan(appliedSpans.removeLast())
                                    }
                                    if (appliedSpans.isEmpty()) clearing = false
                                } else {
                                    val stop = minOf(index + 96, spansForResult.size)
                                    while (index < stop) {
                                        val (style, start, end) = spansForResult[index++]
                                        val span = SyntaxStyleSpan(style)
                                        target.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                        appliedSpans.addLast(span)
                                    }
                                }
                                totalBatchMs += SystemClock.elapsedRealtime() - batchStarted
                                batchCount++
                                if (!clearing && index >= spansForResult.size) {
                                    DiagnosticLog.record("text.syntax.apply",
                                        "generation=$version mode=batched ranges=${spansForResult.size} spans=${appliedSpans.size} " +
                                            "batches=$batchCount batchMs=$totalBatchMs mainQueueMs=$mainQueueMs " +
                                            "elapsedMs=${SystemClock.elapsedRealtime() - applyStarted}")
                                    return
                                }
                                edit.postOnAnimation(this)
                            }
                        }
                        edit.postOnAnimation(batch)
                    }
                }
            } catch (_: java.io.InterruptedIOException) { /* Superseded snapshots do not affect text or spans. */ }
            catch (error: Exception) { handler.post { if (!disposed && version == generation) { requested = null; Toast.makeText(activity, "Highlighting unavailable: ${error.message}", Toast.LENGTH_SHORT).show() } } }
        }
    }
    fun showSettings() = SyntaxAppearanceDialog(activity, theme, languageChoice,
        onTheme = { next -> theme = next; preferences.edit().putString("theme", TextThemeJson.format(next)).apply(); requested = null; applyDefaults(); schedule() },
        onLanguage = { next -> languageChoice = next; preferences.edit().putString("language:$fileName", next).apply(); requested = null; schedule() },
        onImport = { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, IMPORT) },
        onExport = { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; putExtra(Intent.EXTRA_TITLE, "infinistream-text-theme.json"); addCategory(Intent.CATEGORY_OPENABLE) }, EXPORT) }).show()
    fun addToolbarButton(toolbar: LinearLayout) {
        toolbar.addView(TextView(activity).apply {
            text = "Aa"; textSize = 16f; setTextColor(android.graphics.Color.WHITE); contentDescription = "Text appearance and syntax highlighting"
            val pad = (10 * resources.displayMetrics.density).toInt(); setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { showSettings() }
        }, toolbar.childCount.coerceAtLeast(0))
    }
    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != IMPORT && request != EXPORT) return false
        if (result != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        worker.submit {
            try {
                if (request == IMPORT) {
                    val bytes = MediaSourceAdapter.openDocumentSource(activity, uri).use { source ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val n = source.read(buffer, 0, buffer.size)
                            if (n == -1) break
                            require(n > 0 && out.size() + n <= 256 * 1024) { "Theme descriptor exceeds 256 KiB" }
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    val imported = TextThemeJson.parse(String(bytes, Charsets.UTF_8))
                    handler.post { if (!disposed) {
                        theme = imported; preferences.edit().putString("theme", TextThemeJson.format(imported)).apply()
                        requested = null; applyDefaults(); schedule()
                        Toast.makeText(activity, "Theme imported", Toast.LENGTH_SHORT).show()
                    } }
                } else {
                    val bytes = TextThemeJson.format(theme).toByteArray(Charsets.UTF_8)
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot open theme destination")
                    handler.post { if (!disposed) Toast.makeText(activity, "Theme exported", Toast.LENGTH_SHORT).show() }
                }
            } catch (error: Exception) { handler.post { if (!disposed) Toast.makeText(activity, "Theme: ${error.message}", Toast.LENGTH_LONG).show() } }
        }
        return true
    }
    fun close() {
        disposed = true; applySerial++; appliedSpans.clear(); task?.cancel(true); handler.removeCallbacksAndMessages(null)
        edit.removeTextChangedListener(watcher)
        if (edit.viewTreeObserver.isAlive) { edit.viewTreeObserver.removeOnScrollChangedListener(scrollListener); edit.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        worker.shutdownNow()
    }
    fun debugState(): String = "generation=$generation requested=${requested ?: "none"} appliedSpans=${appliedSpans.size} " +
        "lastWindow={$lastWindowSummary} lastHighlight={$lastHighlightSummary}"
    companion object { private const val IMPORT = 7311; private const val EXPORT = 7312 }
}
