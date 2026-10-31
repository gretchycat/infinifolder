package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.CancellationToken
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Shared host adapter. Scribe owns tokens/descriptors; Android owns spans, preferences and pickers. */
class SyntaxTextController(private val activity: Activity, private val edit: EditText, private val fileName: String,
    private val mime: String = "", private val sourceOffset: () -> Long = { 0L },
    private val stableLayout: ((() -> Unit) -> Boolean)? = null,
    private val afterApply: () -> Unit = {}) {
    private val preferences = activity.getSharedPreferences("text-appearance", Activity.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    @Volatile private var disposed = false
    private var generation = 0
    private var requested: String? = null
    var theme: TextTheme = readTheme(); private set
    private var languageChoice = preferences.getString("language:$fileName", "auto")?.takeIf { it == "auto" || it in SyntaxHighlighting.languages } ?: "auto"
    private val refresh = Runnable { prepare() }
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { generation++; requested = null; task?.cancel(true) }
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
        if (!edit.getLocalVisibleRect(visible)) return
        val text = edit.text
        val first = edit.layout?.let { it.getLineStart(it.getLineForVertical((visible.top - edit.totalPaddingTop).coerceAtLeast(0))) } ?: 0
        var start = ((first - 8192).coerceAtLeast(0) / 4096) * 4096
        if (start > 0 && Character.isLowSurrogate(text[start])) start--
        var end = (start + 65536).coerceAtMost(text.length)
        if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) end--
        val snapshot = text.subSequence(start, end).toString()
        val language = if (languageChoice == "auto") SyntaxHighlighting.detect(fileName, mime, text.take(256).toString().substringBefore('\n')) else languageChoice
        val version = generation
        val key = "$version:$start:$end:$language:${theme.hashCode()}"
        if (requested == key) return
        requested = key; task?.cancel(true)
        val currentTheme = theme; val from = start
        val contextKnown = from == 0 && sourceOffset() == 0L
        task = worker.submit {
            try {
                val result = SyntaxHighlighting.highlight(snapshot, language, contextComplete = contextKnown,
                    cancellation = CancellationToken { disposed })
                val styles = result.ranges.map { it.scopes }.distinct().associateWith(currentTheme::resolve)
                handler.post {
                    if (disposed || version != generation || requested != key) return@post
                    if (result.ranges.isEmpty() && edit.text.getSpans(0, edit.length(), SyntaxStyleSpan::class.java).isEmpty()) return@post
                    if (!withStableLayout {
                        val target = edit.text
                        target.getSpans(0, target.length, SyntaxStyleSpan::class.java).forEach(target::removeSpan)
                        result.ranges.forEach { range -> target.setSpan(SyntaxStyleSpan(styles.getValue(range.scopes), activity.resources.displayMetrics),
                            from + range.start, from + range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                        afterApply()
                    }) { requested = null; schedule() }
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
        disposed = true; task?.cancel(true); handler.removeCallbacksAndMessages(null)
        edit.removeTextChangedListener(watcher)
        if (edit.viewTreeObserver.isAlive) { edit.viewTreeObserver.removeOnScrollChangedListener(scrollListener); edit.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        worker.shutdownNow()
    }
    companion object { private const val IMPORT = 7311; private const val EXPORT = 7312 }
}
