package com.infinifolder.viewer

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.infinifolder.storage.DiagnosticLog
import io.github.gretchycat.infiniscribe.FontDocument
import io.github.gretchycat.infiniscribe.FontDocuments
import io.github.gretchycat.infiniscribe.android.FontTypefaces
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Presentation only: Scribe inspects the file and Android shapes/renders its real typeface. */
class FontViewerActivity : ViewerActivity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "font-viewer") }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private var work: Future<*>? = null
    @Volatile private var destroyed = false
    // Owned and released on the document worker, including after a cancelled load.
    private var staged: MediaSourceAdapter.StagedSource? = null
    private var document: FontDocument? = null
    private val typefaces = object : LinkedHashMap<Int, Typeface>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Typeface>?) = size > 8
    }
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var facePicker: Spinner
    private lateinit var sampleInput: EditText
    private lateinit var preview: LinearLayout
    private lateinit var sample: TextView
    private lateinit var alphabet: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private var selectedFace = 0
    private var sizeSp = 32
    private var darkPreview = false
    private var restoredScroll = 0
    private var currentTypeface: Typeface? = null
    private var fontName = "Font"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: (intent.getStringExtra("uri") ?: intent.getStringExtra("extra_uri"))?.let(Uri::parse)
        fontName = intent.getStringExtra("displayName") ?: intent.getStringExtra("extra_name") ?: uri?.lastPathSegment ?: "Font"
        selectedFace = savedInstanceState?.getInt("face", 0) ?: 0
        sizeSp = (savedInstanceState?.getInt("size", 32) ?: 32).coerceIn(8, 144)
        darkPreview = savedInstanceState?.getBoolean("dark", false) ?: false
        restoredScroll = savedInstanceState?.getInt("scroll", 0) ?: 0
        buildUi(savedInstanceState?.getString("sample") ?: "The quick brown fox jumps over the lazy dog.\nPack my box with five dozen liquor jugs.")
        if (uri == null) showError("No font was supplied") else load(uri)
    }

    private fun buildUi(initialSample: String) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(Button(this).apply { text = "←"; contentDescription = "Back"; setOnClickListener { onBackPressed() } }, LinearLayout.LayoutParams(dp(52), dp(48)))
        toolbar.addView(TextView(this).apply { text = fontName; textSize = 18f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(Switch(this).apply {
            text = "Dark preview"; isChecked = darkPreview
            setOnCheckedChangeListener { _, checked -> darkPreview = checked; applyPreviewColors() }
        })
        root.addView(toolbar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        status = TextView(this).apply { text = "Loading font…"; setPadding(dp(12), dp(4), dp(12), dp(4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        facePicker = Spinner(this).apply { isEnabled = false; contentDescription = "Font face" }
        root.addView(facePicker)
        val sizeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        sizeLabel = TextView(this).apply { text = "$sizeSp sp"; minWidth = dp(60) }
        sizeRow.addView(sizeLabel)
        sizeRow.addView(SeekBar(this).apply {
            contentDescription = "Preview text size"; max = 136; progress = sizeSp - 8
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    sizeSp = value + 8; sizeLabel.text = "$sizeSp sp"
                    if (::sample.isInitialized) updateSample()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(sizeRow)
        sampleInput = EditText(this).apply {
            hint = "Sample text"; contentDescription = "Custom sample text"; minLines = 1; maxLines = 3
            filters = arrayOf(InputFilter.LengthFilter(2000)); setText(initialSample)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSample() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(sampleInput, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(this).apply { isFillViewport = true }
        preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)); visibility = View.GONE
        }
        details = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        preview.addView(details)
        sample = TextView(this).apply { setPadding(0, dp(20), 0, dp(20)); setTextIsSelectable(true) }
        preview.addView(sample)
        alphabet = TextView(this).apply {
            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789\n! @ # $ % ^ & * ( ) _ + − = [ ] { } ; : ' \" , . / ?\n\nÀÁÂÃÄÅ Æ Ç ÈÉÊË ÌÍÎÏ Ñ ÒÓÔÕÖ Ø ÙÚÛÜ Ý ß\n\nΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ\nАБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩ\nאבגדהוזחטיכלמנסעפצקרשת\nالعربية  नमस्ते  日本語  中文  한글"
            setTextIsSelectable(true)
        }
        preview.addView(alphabet)
        preview.addView(TextView(this).apply {
            text = "Android may substitute another font for characters missing from this face. Previewing does not install or change your system font."
            textSize = 13f; setPadding(0, dp(24), 0, 0)
        })
        scroll.addView(preview)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        applyPreviewColors(); updateSample(); ViewCompat.requestApplyInsets(root)
    }

    private fun applyPreviewColors() {
        if (!::preview.isInitialized) return
        preview.setBackgroundColor(if (darkPreview) 0xff202124.toInt() else Color.WHITE)
        val foreground = if (darkPreview) Color.WHITE else 0xff202124.toInt()
        for (i in 0 until preview.childCount) (preview.getChildAt(i) as? TextView)?.setTextColor(foreground)
    }

    private fun updateSample() {
        if (!::sample.isInitialized) return
        sample.text = sampleInput.text.toString()
        sample.textSize = sizeSp.toFloat(); alphabet.textSize = sizeSp.toFloat()
    }

    private fun load(uri: Uri) {
        val request = generation.incrementAndGet()
        work = worker.submit {
            var source: MediaSourceAdapter.StagedSource? = null
            try {
                source = MediaSourceAdapter.stageBounded(applicationContext, uri, FontDocuments.MAX_BYTES, fontName)
                val info = FontDocuments.inspect(source.file)
                val index = selectedFace.coerceIn(info.faces.indices)
                staged = source; source = null; document = info
                main.post {
                    if (destroyed || request != generation.get()) return@post
                    selectedFace = index
                    facePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        info.faces.map { "${it.index + 1}. ${it.fullName ?: listOfNotNull(it.family, it.style).joinToString(" ").ifBlank { "Face ${it.index + 1}" }}" })
                    facePicker.setSelection(index); facePicker.isEnabled = info.faces.size > 1
                    facePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            if (position != selectedFace) selectFace(position)
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                    work = null
                    selectFace(index)
                }
            } catch (error: Exception) {
                DiagnosticLog.record("font.open.failed", "Font could not be opened", error)
                main.post {
                    if (destroyed || request != generation.get()) return@post
                    if (!CacheFailureUi.offerCopy(this, error, uri)) showError(error.message ?: "Unable to load font")
                    else showError("Unable to cache this font")
                }
            } finally { source?.close() }
        }
    }

    private fun selectFace(index: Int) {
        if (destroyed) return
        selectedFace = index
        val request = generation.incrementAndGet()
        work?.cancel(true)
        preview.visibility = View.GONE; currentTypeface = null
        progress.visibility = View.VISIBLE; status.text = "Loading face ${index + 1}…"
        work = worker.submit {
            try {
                val info = checkNotNull(document); val source = checkNotNull(staged)
                val face = typefaces[index] ?: FontTypefaces.open(source.file, info, index).also { typefaces[index] = it }
                main.post { if (!destroyed && request == generation.get()) showFace(info, index, face) }
            } catch (error: Exception) {
                DiagnosticLog.record("font.face.failed", "Unable to render face $index", error)
                main.post { if (!destroyed && request == generation.get()) showError(error.message ?: "Unable to render font face") }
            }
        }
    }

    private fun showFace(info: FontDocument, index: Int, typeface: Typeface) {
        currentTypeface = typeface
        sample.typeface = typeface; alphabet.typeface = typeface
        val face = info.faces[index]
        details.text = buildString {
            appendLine(face.fullName ?: face.family ?: fontName)
            face.style?.let { appendLine("Style: $it") }
            appendLine("${info.format} · ${info.length / 1024} KiB · Face ${index + 1} of ${info.faces.size}")
            face.postScriptName?.let { appendLine("PostScript: $it") }
            face.glyphCount?.let { appendLine("Glyphs: $it") }
            face.unitsPerEm?.let { appendLine("Units per em: $it") }
            face.weight?.let { appendLine("Weight: $it") }
            if (face.axes.isNotEmpty()) appendLine("Variable axes (default instance): " + face.axes.joinToString { "${it.tag} ${it.default}" })
        }.trimEnd()
        updateSample(); preview.visibility = View.VISIBLE; progress.visibility = View.GONE
        status.text = "Font preview"
        if (restoredScroll > 0) scroll.post {
            if (!destroyed) { scroll.scrollTo(0, restoredScroll); restoredScroll = 0 }
        }
    }

    private fun showError(message: String) {
        preview.visibility = View.GONE; progress.visibility = View.GONE
        status.text = "Cannot preview font: $message"
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("face", selectedFace); out.putInt("size", sizeSp); out.putBoolean("dark", darkPreview)
        out.putString("sample", sampleInput.text.toString()); out.putInt("scroll", scroll.scrollY)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        destroyed = true; generation.incrementAndGet(); work?.cancel(true)
        worker.execute { typefaces.clear(); staged?.close(); staged = null; document = null }
        worker.shutdown()
        currentTypeface = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
