package com.infinifolder.viewer

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.infinifolder.preview.CanvasFonts
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.theme.ThemeChangeListener
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager
import io.github.gretchycat.infiniscribe.HexDocument
import io.github.gretchycat.infiniscribe.HexEncoding
import java.io.InterruptedIOException
import java.util.Locale

/** Android widgets handle scrolling, selection, dialogs and the keyboard; Scribe handles bytes. */
class HexViewerActivity : ViewerActivity(), ThemeChangeListener {
    private enum class Mode { HEX, PIC, WAVE }
    private var mode=Mode.HEX
    private lateinit var toolsHost:LinearLayout
    private lateinit var explorerActions:LinearLayout
    private lateinit var toolsScroll:ScrollView
    private lateinit var controlsHint:TextView
    private lateinit var hexTools:LinearLayout
    private lateinit var pic:RawPicPane
    private lateinit var wave:RawWavePane
    private lateinit var modes:RadioGroup
    private val modeButtons=mutableMapOf<Mode,RadioButton>()
    private var playing=false
    private lateinit var session: HexSession
    private val main = Handler(Looper.getMainLooper())
    private var dead = false
    private var discardDraft = false
    private var encoding = HexEncoding.Text.CP437
    private var stringsMode = false
    private var columns = 8
    private var page = 0L
    private var selected = 0L
    private var editMode = false
    private var pageToken = 0L
    private var pattern: ByteArray? = null
    private var match: Long? = null
    private var length = 0L
    private var changed = 0
    private var restoreRow = 0
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var legend: TextView
    private lateinit var list: ListView
    private lateinit var table: HorizontalScrollView
    private lateinit var stringsScroll: ScrollView
    private lateinit var stringsText: TextView
    private lateinit var editToggle: Switch
    private lateinit var encodingSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var cancel: Button
    private lateinit var save: Button
    private lateinit var undo: Button
    private lateinit var redo: Button
    private lateinit var nextPage: Button
    private lateinit var previousPage: Button
    private val controls = mutableListOf<View>()
    private var rowTextSizePx = 0f
    private var currentRows: List<HexRow> = emptyList()
    private data class HexRow(val offset: Long, val bytes: ByteArray, val text: List<HexEncoding.Glyph>, val changes: Set<Long>)
    private val theme get() = ThemeManager.activeTheme
    private val adapter: BaseAdapter = object : BaseAdapter() {
        override fun getCount() = currentRows.size
        override fun getItem(position: Int) = currentRows[position]
        override fun getItemId(position: Int) = currentRows[position].offset
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = currentRows[position]
            val view = (convertView as? TextView) ?: TextView(this@HexViewerActivity).apply {
                textSize = 13f; includeFontPadding = false
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(1), dp(8), dp(1))
                minHeight = dp(20)
            }
            if (rowTextSizePx > 0f) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rowTextSizePx)
            view.typeface = if (encoding == HexEncoding.Text.CP437) CanvasFonts.cp437(this@HexViewerActivity) else Typeface.MONOSPACE
            view.setTextColor(theme.textPrimary)
            view.setBackgroundColor(if (position % 2 == 0) theme.background else
                theme.alternateBackground)
            val content = SpannableStringBuilder(String.format(Locale.ROOT, if (length > 0xffffffffL) "%016X  " else "%08X  ", row.offset))
            val hits = ArrayList<Pair<IntRange, Long>>()
            row.bytes.forEachIndexed { i, byte ->
                val begin = content.length
                content.append(String.format(Locale.ROOT, "%02X", byte.toInt() and 255))
                decorate(content, begin, content.length, row.offset + i, 1, row.changes)
                hits.add((begin until content.length) to row.offset + i)
                content.append(if (i == 7 && columns == 16) "  " else " ")
            }
            repeat(columns - row.bytes.size) { content.append("   ") }
            content.append(" │ ")
            // Pad continuation bytes rather than reinterpreting them as single-byte characters.
            val glyphs = row.text.associateBy { it.offset }
            for (i in row.bytes.indices) {
                val glyph = glyphs[row.offset + i]
                val begin = content.length
                content.append(glyph?.text?.ifEmpty { "·" } ?: " ")
                decorate(content, begin, content.length, row.offset + i, glyph?.byteCount ?: 1, row.changes)
                hits.add((begin until content.length) to row.offset + i)
            }
            view.text = content
            view.contentDescription = "Offset ${row.offset}, ${row.bytes.size} bytes"
            var touchX = 0f
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) touchX = event.x
                false
            }
            fun tappedOffset(): Long {
                val index = view.layout?.getOffsetForHorizontal(0, touchX - view.totalPaddingLeft) ?: 0
                return hits.firstOrNull { index in it.first }?.second ?: row.offset
            }
            view.setOnClickListener { selected = tappedOffset(); adapter.notifyDataSetChanged(); showPosition() }
            view.setOnLongClickListener {
                selected = tappedOffset(); adapter.notifyDataSetChanged(); showPosition(); editBytes(); true
            }
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val name = intent.getStringExtra("displayName") ?: uri.lastPathSegment ?: "Bytes"
        mode=runCatching {Mode.valueOf(savedInstanceState?.getString("mode")?:intent.getStringExtra("mode")?:"HEX")}.getOrDefault(Mode.HEX)
        encoding = runCatching { HexEncoding.Text.valueOf(savedInstanceState?.getString("encoding") ?: "CP437") }.getOrDefault(HexEncoding.Text.CP437)
        stringsMode = savedInstanceState?.getBoolean("strings") ?: false
        columns = savedInstanceState?.getInt("columns")?.takeIf { it == 8 || it == 16 } ?: 8
        selected = savedInstanceState?.getLong("selected") ?: 0
        page = savedInstanceState?.getLong("page") ?: 0
        restoreRow = savedInstanceState?.getInt("row") ?: 0
        editMode = savedInstanceState?.getBoolean("editing") ?: false
        pattern = savedInstanceState?.getByteArray("pattern")
        match = savedInstanceState?.getLong("match", -1)?.takeIf { it >= 0 }
        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? HexSession
        session = retained ?: HexSession(uri, name)
        buildUi(name)
        savedInstanceState?.getString("pic")?.let {runCatching {pic.install(RawMediaConfigStore.image(org.json.JSONObject(it)))}}
        pic.smoothScaling=savedInstanceState?.getBoolean("picSmooth")?:false
        savedInstanceState?.getString("wave")?.let {runCatching {wave.install(RawMediaConfigStore.audio(org.json.JSONObject(it)))}}
        ThemeManager.addListener(this)
        if (retained != null) refreshPage()
        else runWork("Opening bytes…", cancellable = true, done={
            val record=loadedMetadata
            if(savedInstanceState==null&&record!=null){
                record.image?.let(pic::install);record.audio?.let(wave::install)
                if(record.image!=null)pic.smoothScaling=record.smoothScaling
                if(intent.getBooleanExtra("raw_metadata",false))setMode(if(record.kind=="image")Mode.PIC else Mode.WAVE)
            }
        }) {
            session.open(applicationContext, savedInstanceState?.getString("draft"))
            loadedMetadata=RawMediaConfigStore.load(applicationContext,uri,name)
        }
    }
    private var loadedMetadata:RawMediaConfigStore.Record?=null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setOnClickListener { action() }; controls.add(this)
    }
    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(-2, dp(48))) }
    }
    private fun addToolbar(vararg views: View) {
        hexTools.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=true;isScrollbarFadingEnabled=false;scrollBarSize=dp(4)
            isFillViewport = true; addView(row(*views))
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }
    private fun buildUi(name: String) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        val title = TextView(this).apply { text = name; textSize = 18f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("←") { onBackPressed() }.also { controls.remove(it) }, LinearLayout.LayoutParams(dp(52), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        })
        explorerActions=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        root.addView(explorerActions,LinearLayout.LayoutParams(-1,-2))
        toolsHost=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        hexTools=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        toolsHost.addView(hexTools)
        controlsHint=TextView(this).apply {
            text="Controls";textSize=11f;setLines(1)
            ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(dp(8),dp(2),dp(8),dp(2))
        }
        root.addView(controlsHint,LinearLayout.LayoutParams(-1,-2))
        toolsScroll=object:ScrollView(this){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val available=View.MeasureSpec.getSize(heightMeasureSpec)
                val limit=minOf(dp(208),(available/3).coerceAtLeast(dp(48)))
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled=true;isScrollbarFadingEnabled=false;scrollBarSize=dp(5)
            scrollBarStyle=View.SCROLLBARS_INSIDE_INSET
            setPadding(dp(4),dp(2),dp(4),dp(2));addView(toolsHost)
            setOnScrollChangeListener{_,_,_,_,_->updateControlsHint()}
            addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateControlsHint()}
        }
        toolsHost.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateControlsHint()}
        root.addView(toolsScroll,LinearLayout.LayoutParams(-1,-2))
        encodingSpinner = spinner(listOf("UTF-8", "CP437"), encoding.ordinal) { position ->
            encoding = HexEncoding.Text.entries[position]; refreshPage(keepScroll = true)
        }
        controls.add(encodingSpinner)
        editToggle = Switch(this).apply {
            text = "Edit"; isChecked = editMode; controls.add(this)
            setOnCheckedChangeListener { _, checked ->
                editMode = checked
                if (checked && session.document != null && session.directory == null)
                    runWork("Preserving original bytes…", cancellable = true) { session.snapshot(applicationContext, ::reportProgress) }
            }
        }
        val strings = Switch(this).apply {
            text = "Strings"; isChecked = stringsMode
            setOnCheckedChangeListener { _, checked -> stringsMode = checked; refreshPage(keepScroll = true) }
        }
        addToolbar(encodingSpinner, strings, editToggle, button("⋮") { moreMenu() })
        undo = button("Undo") { mutate { it.undo() } }
        redo = button("Redo") { mutate { it.redo() } }
        save = button("Save") { confirmSave() }
        save.setPadding(dp(4),0,dp(4),0);save.textSize=12f;save.isSingleLine=true
        addToolbar(button("Go to…") { goTo() }, button("Search…") { searchDialog() }, button("Next") { findNext() },
            button("Edit byte…") { editBytes() },undo,redo)
        pic=RawPicPane(this,session,{status.text=it},::saveRawMetadata)
        wave=RawWavePane(this,session,{status.text=it},::saveRawMetadata,::playRaw){offset->selected=offset;showPosition()}
        explorerActions.addView(pic.actions,LinearLayout.LayoutParams(-1,dp(48)))
        explorerActions.addView(wave.actions,LinearLayout.LayoutParams(-1,dp(48)))
        toolsHost.addView(pic.tools);toolsHost.addView(wave.tools)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        status = TextView(this).apply {
            text = "Opening bytes…"; textSize = 12f; setPadding(dp(8), dp(3), dp(8), dp(3))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnLongClickListener {
                ThemeManager.createDialogBuilder(this@HexViewerActivity).setTitle("Status")
                    .setMessage(text).setPositiveButton("OK",null).show()
                true
            }
        }
        root.addView(status)
        legend = TextView(this).apply { textSize = 11f; setPadding(dp(8), 0, dp(8), dp(3)) }
        root.addView(legend)
        list = ListView(this).apply { adapter = this@HexViewerActivity.adapter; dividerHeight = 0; isVerticalScrollBarEnabled = true }
        table = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(list, ViewGroup.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) fitTableToWidth()
            }
        }
        root.addView(table, LinearLayout.LayoutParams(-1, 0, 1f))
        stringsText = TextView(this).apply {
            textSize = 16f; setTextIsSelectable(true); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        stringsScroll = ScrollView(this).apply { addView(stringsText) }
        root.addView(stringsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(pic.view,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(wave.view,LinearLayout.LayoutParams(-1,0,1f))
        previousPage = button("‹") {stepMode(-1)}.apply {contentDescription="Previous page/window";setPadding(0,0,0,0)}
        nextPage = button("›") {stepMode(1)}.apply {contentDescription="Next page/window";setPadding(0,0,0,0)}
        cancel = button("×") { session.cancelled.set(true) }.apply { visibility = View.GONE;contentDescription="Cancel or stop";setPadding(0,0,0,0) }
        modes=RadioGroup(this).apply {orientation=RadioGroup.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        Mode.entries.forEach {value->
            val choice=RadioButton(this).apply {
                id=View.generateViewId();text=value.name;buttonDrawable=null;gravity=Gravity.CENTER
                minWidth=0;minimumWidth=0;setPadding(0,0,0,0);textSize=12f
                contentDescription="${value.name} mode";controls.add(this)
            }
            modeButtons[value]=choice;modes.addView(choice,RadioGroup.LayoutParams(dp(47),dp(42)))
        }
        modes.check(modeButtons.getValue(mode).id)
        modes.setOnCheckedChangeListener{_,id->modeButtons.entries.firstOrNull{it.value.id==id}?.key?.let(::setMode)}
        listOf(pic.saveButton to "Save image metadata",wave.saveButton to "Save audio metadata").forEach{(button,label)->
            button.text="Save\nmetadata";button.contentDescription=label
            button.textSize=12f;button.maxLines=2;button.setPadding(dp(4),0,dp(4),0)
        }
        root.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(save,LinearLayout.LayoutParams(dp(55),dp(48)))
            addView(pic.saveButton,LinearLayout.LayoutParams(dp(80),dp(48)))
            addView(wave.saveButton,LinearLayout.LayoutParams(dp(80),dp(48)))
            addView(previousPage,LinearLayout.LayoutParams(dp(32),dp(48)))
            addView(nextPage,LinearLayout.LayoutParams(dp(32),dp(48)))
            addView(cancel,LinearLayout.LayoutParams(dp(32),dp(48)))
            addView(View(this@HexViewerActivity),LinearLayout.LayoutParams(0,1,1f))
            addView(modes,LinearLayout.LayoutParams(-2,dp(48)))
        },LinearLayout.LayoutParams(-1,dp(48)))
        setContentView(root)
        applyTheme(); updateMode(); ViewCompat.requestApplyInsets(root)
    }
    private fun spinner(labels: List<String>, selection: Int, selected: (Int) -> Unit) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@HexViewerActivity, android.R.layout.simple_spinner_dropdown_item, labels) {
            private fun themed(view: View) = view.apply {
                (this as? TextView)?.setTextColor(theme.textPrimary)
                setBackgroundColor(theme.alternateBackground)
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = themed(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = themed(super.getDropDownView(position, convertView, parent))
        }
        setSelection(selection)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
        }
    }
    private fun fitTableToWidth() {
        val available = table.width - table.paddingLeft - table.paddingRight
        val padding = dp(24)
        if (available <= padding) return
        val paint = android.graphics.Paint().apply {
            textSize = 13f * resources.displayMetrics.scaledDensity
            typeface = if (encoding == HexEncoding.Text.CP437) CanvasFonts.cp437(this@HexViewerActivity) else Typeface.MONOSPACE
        }
        val cell = paint.measureText("0")
        var textWidth = columns * cell
        currentRows.forEach { row ->
            var width = (row.bytes.size - row.text.size) * cell
            row.text.forEach { width += paint.measureText(it.text.ifEmpty { "·" }) }
            textWidth = maxOf(textWidth, width)
        }
        val prefix = (if (length > 0xffffffffL) 16 else 8) + 2 + columns * 3 + (if (columns == 16) 1 else 0) + 3
        val naturalWidth = prefix * cell + textWidth
        if (naturalWidth <= 0f) return
        rowTextSizePx = paint.textSize * (available - padding) / naturalWidth
        if (list.layoutParams.width != available) {
            list.layoutParams.width = available
            list.requestLayout()
        }
        table.scrollTo(0, 0)
        adapter.notifyDataSetChanged()
    }
    private fun updateMode() {
        table.visibility = if (mode==Mode.HEX&&!stringsMode) View.VISIBLE else View.GONE
        stringsScroll.visibility = if (mode==Mode.HEX&&stringsMode) View.VISIBLE else View.GONE
        hexTools.visibility=if(mode==Mode.HEX)View.VISIBLE else View.GONE
        pic.tools.visibility=if(mode==Mode.PIC)View.VISIBLE else View.GONE
        wave.tools.visibility=if(mode==Mode.WAVE)View.VISIBLE else View.GONE
        explorerActions.visibility=if(mode==Mode.HEX)View.GONE else View.VISIBLE
        pic.actions.visibility=pic.tools.visibility;wave.actions.visibility=wave.tools.visibility
        save.visibility=if(mode==Mode.HEX)View.VISIBLE else View.GONE
        pic.saveButton.visibility=pic.tools.visibility;wave.saveButton.visibility=wave.tools.visibility
        pic.view.visibility=pic.tools.visibility;wave.view.visibility=wave.tools.visibility
        pic.active=mode==Mode.PIC;wave.active=mode==Mode.WAVE
        // Preview messages must not grow/shrink the drawing surface as numbers change.
        status.minLines=if(mode==Mode.HEX)1 else 2
        status.maxLines=if(mode==Mode.HEX)Int.MAX_VALUE else 2
        status.ellipsize=if(mode==Mode.HEX)null else android.text.TextUtils.TruncateAt.END
        modeButtons.forEach{(value,button)->
            button.backgroundTintList=null
            button.background=android.graphics.drawable.GradientDrawable().apply {
                cornerRadius=dp(4).toFloat();setColor(if(value==mode)theme.selectionBackground else theme.buttonBackground)
                setStroke(dp(1),if(value==mode)theme.accent else theme.cardStroke)
            }
            button.elevation=if(value==mode)0f else dp(2).toFloat()
        }
        stringsText.typeface = if (encoding == HexEncoding.Text.CP437) CanvasFonts.cp437(this) else Typeface.MONOSPACE
        legend.text = when(mode){
            Mode.PIC->"Raw raster · palette applies through 8 bpp · artifact presets are approximations"
            Mode.WAVE->"Raw PCM · tap waveform to select a byte offset · playback converts to 16-bit PCM"
            Mode.HEX->if (stringsMode) "Strings: printable ASCII · controls and bytes 0x7F–0xFF shown as ·"
                else "Offset (hex)   |   bytes (hex)   |   ${encoding.title} · tap a byte; hold to edit"
        }
    }
    private fun setMode(value:Mode){
        if(mode==value&&modes.checkedRadioButtonId==modeButtons.getValue(value).id){updateMode();return}
        mode=value
        if(modes.checkedRadioButtonId!=modeButtons.getValue(value).id)modes.check(modeButtons.getValue(value).id)
        updateMode();refreshPage(keepScroll=true)
    }
    private fun updateControlsHint(){
        val up=toolsScroll.canScrollVertically(-1)
        val down=toolsScroll.canScrollVertically(1)
        val hint=when {
            up&&down -> "Controls · scroll ↑↓ for more"
            down -> "Controls · scroll ↓ for more"
            up -> "Controls · scroll ↑ for more"
            else -> "Controls"
        }
        if(controlsHint.text.toString()!=hint)controlsHint.text=hint
    }
    private fun stepMode(direction:Int){when(mode){
        Mode.PIC->pic.step(direction)
        Mode.WAVE->wave.step(direction)
        Mode.HEX->{val next=page+direction.toLong()*PAGE_BYTES;if(next>=0&&next<length){page=next;selected=page;refreshPage()}}
    }}
    private fun applyTheme() {
        root.setBackgroundColor(theme.background)
        fun color(view: View) {
            if (view is TextView) view.setTextColor(if (view is Button) theme.buttonText else theme.textPrimary)
            if (view is Button) view.backgroundTintList = ColorStateList.valueOf(theme.buttonBackground)
            if (view is Switch) view.thumbTintList = ColorStateList.valueOf(theme.accent)
            if (view is ViewGroup) for (i in 0 until view.childCount) color(view.getChildAt(i))
        }
        color(root); status.setTextColor(theme.textSecondary); legend.setTextColor(theme.textSecondary)
        controlsHint.setTextColor(theme.textSecondary)
        toolsScroll.background=android.graphics.drawable.GradientDrawable().apply {
            cornerRadius=dp(4).toFloat();setColor(theme.alternateBackground);setStroke(dp(1),theme.cardStroke)
        }
        progress.progressTintList = ColorStateList.valueOf(theme.accent)
        adapter.notifyDataSetChanged()
        if(::pic.isInitialized){updateMode();wave.view.invalidate()}
    }
    override fun onThemeChanged(theme: ThemeColors) { if (!dead) main.post { if (!dead) { applyTheme(); refreshPage(keepScroll = true) } } }

    private fun decorate(text: SpannableStringBuilder, start: Int, end: Int, offset: Long, bytes: Int, changes: Set<Long>) {
        if (start == end) return
        val matched = match?.let { at -> offset < at + (pattern?.size ?: 0) && offset + bytes > at } == true
        val edited = (0 until bytes).any { offset + it in changes }
        val active = selected >= offset && selected < offset + bytes
        val color = when {
            active -> theme.accent
            matched -> if (theme.isDark) 0xFF315A97.toInt() else 0xFFB8D8FF.toInt()
            edited -> if (theme.isDark) 0xFF795A20.toInt() else 0xFFFFD88A.toInt()
            else -> return
        }
        text.setSpan(BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(if (active) theme.selectionText else theme.textPrimary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private fun refreshPage(keepScroll: Boolean = false) {
        if (dead || !::session.isInitialized) return
        val request = ++pageToken
        val row = if (keepScroll && ::list.isInitialized) list.firstVisiblePosition else restoreRow.also { restoreRow = 0 }
        val rowTop = if (keepScroll) list.getChildAt(0)?.top ?: 0 else 0
        val requestedPage = page
        val requestedColumns = columns
        val requestedEncoding = encoding
        session.worker.execute {
            try {
                val doc = session.document ?: return@execute
                val start = requestedPage.coerceIn(0, if (doc.length == 0L) 0 else (doc.length - 1) / PAGE_BYTES * PAGE_BYTES)
                // Three bytes of context cover a UTF-8 code point crossing the page boundary.
                val contextStart = (start - 3).coerceAtLeast(0)
                val data = doc.read(contextStart, (start - contextStart).toInt() + PAGE_BYTES + 3)
                val glyphs = HexEncoding.glyphs(data, contextStart, requestedEncoding)
                val changes = doc.changedOffsets(contextStart, minOf(doc.length, start + PAGE_BYTES + 3))
                val rows = ArrayList<HexRow>()
                var at = start
                val end = minOf(doc.length, start + PAGE_BYTES)
                while (at < end) {
                    val size = minOf(requestedColumns.toLong(), end - at).toInt()
                    val index = (at - contextStart).toInt()
                    rows.add(HexRow(at, data.copyOfRange(index, index + size), glyphs.filter { it.offset >= at && it.offset < at + size }, changes))
                    at += size
                }
                val canUndo = doc.canUndo; val canRedo = doc.canRedo; val changedCount = doc.changedCount; val total = doc.length
                main.post {
                    if (dead || request != pageToken) return@post
                    length = total; changed = changedCount; page = start
                    selected = selected.coerceIn(0, (length - 1).coerceAtLeast(0))
                    currentRows = rows; fitTableToWidth(); adapter.notifyDataSetChanged()
                    list.setSelectionFromTop(row, rowTop)
                    val strings = SpannableStringBuilder()
                    // Strings is a byte-for-character ASCII view in either encoding mode.
                    // Keep one marker per control/high byte, including UTF-8 continuation bytes.
                    for (offset in start until end) {
                        val byte = data[(offset - contextStart).toInt()].toInt() and 255
                        val begin = strings.length
                        strings.append(if (byte in 0x20..0x7E) byte.toChar() else '·')
                        decorate(strings, begin, strings.length, offset, 1, changes)
                    }
                    stringsText.text = strings
                    if (!keepScroll) stringsScroll.scrollTo(0, 0)
                    updateMode(); updateControls(canUndo, canRedo)
                    if (!session.busy) showPosition()
                    if(mode==Mode.PIC){pic.invalidateSizing();pic.render()}else if(mode==Mode.WAVE)wave.render()
                }
            } catch (error: Exception) { main.post { if (!dead && request == pageToken) showError(error) } }
        }
    }
    private fun updateControls(canUndo: Boolean, canRedo: Boolean) {
        controls.forEach { it.isEnabled = !session.busy && session.document != null }
        cancel.isEnabled = true
        pic.enabled(!session.busy&&session.document!=null);wave.enabled(!session.busy&&session.document!=null)
        save.isEnabled = !session.busy && session.writable && changed > 0
        editToggle.isEnabled = !session.busy && session.document != null
        previousPage.isEnabled = !session.busy && when(mode){Mode.HEX->page>0;Mode.PIC->pic.config.offset>0;Mode.WAVE->wave.config.offset>0}
        nextPage.isEnabled = !session.busy && when(mode){Mode.HEX->length-page>PAGE_BYTES;Mode.PIC->pic.config.offset<length;Mode.WAVE->wave.config.offset<length}
        undo.isEnabled = !session.busy && canUndo
        redo.isEnabled = !session.busy && canRedo
    }
    private fun showPosition(message: String? = null) {
        val address = "0x${selected.toString(16).uppercase(Locale.ROOT)} ($selected)"
        status.text = listOfNotNull(message, "$length bytes · offset $address · $changed changed",
            if (!session.writable) "Original read-only; Save copy available" else null).joinToString("\n")
    }
    private fun runWork(label: String, cancellable: Boolean = false, done: (() -> Unit)? = null, task: () -> Unit) {
        if (session.busy || dead) return
        session.busy = true; session.cancelled.set(false)
        status.text = label; progress.visibility = View.VISIBLE
        cancel.visibility = if (cancellable) View.VISIBLE else View.GONE
        controls.forEach { it.isEnabled = false }; cancel.isEnabled = true
        pic.enabled(false);wave.enabled(false)
        session.worker.execute {
            var failure: Exception? = null
            try { task() } catch (error: Exception) { failure = error }
            session.busy = false
            main.post {
                if (dead) return@post
                progress.visibility = View.GONE; cancel.visibility = View.GONE
                if(playing){playing=false;wave.playButton.text="Play"}
                val error = failure
                if (error != null) {
                    if (session.directory == null && editMode) { editMode = false; editToggle.isChecked = false }
                    showError(error)
                }
                refreshPage(keepScroll = true)
                if (error == null) done?.invoke()
            }
        }
    }
    private fun playRaw(config:com.infinifolder.audio.model.RawAudioExplorer.Config){
        playing=true;wave.playButton.text="Stop"
        runWork("Playing raw PCM…",cancellable=true){RawPcmPlayback.play(applicationContext,checkNotNull(session.document),config,session.cancelled,::reportProgress)}
        wave.playButton.isEnabled=true
    }
    private fun saveRawMetadata(record:RawMediaConfigStore.Record){
        if(changed>0){Toast.makeText(this,"Save byte changes in HEX mode before saving media metadata",Toast.LENGTH_LONG).show();return}
        runWork("Saving raw media metadata…",done={Toast.makeText(this,"Media metadata saved beside the file",Toast.LENGTH_LONG).show()}){
            val provider=com.infinifolder.storage.StorageRuntime.get(this).provider
            val ref=provider.refForUri(session.uri)?:error("This URI has no storage provider")
            val entry=(provider.stat(ref) as? com.infinifolder.model.ProviderResult.Success)?.value?:error("Cannot identify source file")
            RawMediaConfigStore.save(applicationContext,entry,record)
        }
    }
    private var lastProgress = 0L
    private fun reportProgress(at: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastProgress < 200) return
        lastProgress = now
        main.post { if (!dead && session.busy) status.text = "Processing byte $at…" }
    }
    private fun showError(error: Exception) {
        val message = if (error is InterruptedIOException) "Cancelled; buffered edits retained" else error.message ?: error.javaClass.simpleName
        DiagnosticLog.record("hex.operation.failed", error = error)
        status.text = message
        if (error !is InterruptedIOException) ThemeManager.createDialogBuilder(this).setTitle("Hex editor").setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun input(hintText: String, value: String = "") = EditText(this).apply {
        hint = hintText; setText(value); isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(32768))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(theme.textPrimary); setHintTextColor(theme.textSecondary)
        typeface = Typeface.MONOSPACE
    }
    /** Validation errors keep the dialog and its entered text open. */
    private fun dialog(title: String, content: View, positive: String, accept: () -> Unit) {
        val dialog = ThemeManager.createDialogBuilder(this).setTitle(title).setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(positive, null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { accept(); dialog.dismiss() } catch (error: Exception) { Toast.makeText(this, error.message ?: "Invalid input", Toast.LENGTH_LONG).show() }
        } }
        dialog.show()
    }
    private fun form(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
        setBackgroundColor(theme.alternateBackground)
        views.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }
    private fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(theme.textPrimary); setPadding(0, dp(6), 0, dp(6)) }
    private fun goTo() {
        val value = input("Decimal or 0x hexadecimal offset", "0x${selected.toString(16)}")
        dialog("Go to byte offset", form(value), "Go") {
            val offset = HexEncoding.offset(value.text.toString())
            require(offset < length || offset == 0L && length == 0L) { "Offset is outside this file" }
            jump(offset)
        }
    }
    private fun jump(offset: Long) {
        selected = offset; page = offset / PAGE_BYTES * PAGE_BYTES
        restoreRow = ((offset - page) / columns).toInt(); refreshPage()
    }
    private fun searchDialog() {
        val value = input("Search value")
        val kind = spinner(listOf("Text (${encoding.title})", "Hexadecimal bytes", "Binary bits", "Integer"), 0) {}
        val width = spinner(listOf("8 bit", "16 bit", "24 bit", "32 bit", "64 bit"), 3) {}
        val order = spinner(listOf("Little endian", "Big endian"), 0) {}
        val signed = CheckBox(this).apply { text = "Signed integer"; setTextColor(theme.textPrimary) }
        val beginning = CheckBox(this).apply { text = "Search from beginning"; isChecked = true; setTextColor(theme.textPrimary) }
        val integerOptions = form(width, order, signed).apply { visibility = View.GONE }
        kind.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                integerOptions.visibility = if (position == 3) View.VISIBLE else View.GONE
            }
        }
        dialog("Search bytes", form(kind, value, integerOptions, beginning,
            label("Hex: DE AD BE EF · bits: 01001000 · integers: decimal or 0x hex. Matches may be unaligned.")), "Find") {
            pattern = when (kind.selectedItemPosition) {
                0 -> HexEncoding.text(value.text.toString(), encoding)
                1 -> HexEncoding.hex(value.text.toString())
                2 -> HexEncoding.binary(value.text.toString())
                else -> HexEncoding.integer(value.text.toString(), listOf(8, 16, 24, 32, 64)[width.selectedItemPosition], signed.isChecked, order.selectedItemPosition == 0)
            }
            match = null; findNext(if (beginning.isChecked) 0 else selected)
        }
    }
    private fun findNext(start: Long? = null) {
        val needle = pattern ?: run { searchDialog(); return }
        var found: Long? = null
        val from = (start ?: ((match ?: selected) + 1).coerceAtMost(length)).coerceIn(0, length)
        runWork("Searching…", cancellable = true, done = {
            match = found
            if (found != null) jump(found!!) else { refreshPage(keepScroll = true); Toast.makeText(this, "No match", Toast.LENGTH_SHORT).show() }
        }) {
            val doc = checkNotNull(session.document)
            found = doc.find(needle, from, cancelled = session.cancelled::get, progress = ::reportProgress)
            if (found == null && from > 0) found = doc.find(needle, 0,
                minOf(doc.length, from + needle.size - 1), session.cancelled::get, ::reportProgress)
        }
    }
    private fun editBytes() {
        if (length == 0L) { Toast.makeText(this, "This file is empty", Toast.LENGTH_SHORT).show(); return }
        if (!editMode || session.directory == null) {
            ThemeManager.createDialogBuilder(this).setTitle("Enable byte editing?")
                .setMessage("Create a local snapshot, then overwrite bytes without changing the file length. The source changes only after Save.")
                .setPositiveButton("Enable") { _, _ ->
                    editMode = true
                    runWork("Preserving original bytes…", cancellable = true, done = { editToggle.isChecked = true; editBytes() }) {
                        session.snapshot(applicationContext, ::reportProgress)
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        val offset = input("Byte offset", "0x${selected.toString(16)}")
        val kind = spinner(listOf("Hexadecimal bytes", "Text (${encoding.title})", "Binary bits"), 0) {}
        val data = input("Replacement bytes")
        dialog("Overwrite bytes", form(offset, kind, data, label("Replaces existing bytes, up to 4096 per edit. Amber = changed, blue = search, accent = selected.")), "Apply") {
            val at = HexEncoding.offset(offset.text.toString())
            val bytes = when (kind.selectedItemPosition) { 0 -> HexEncoding.hex(data.text.toString()); 1 -> HexEncoding.text(data.text.toString(), encoding); else -> HexEncoding.binary(data.text.toString()) }
            require(at <= length && bytes.size <= length - at) { "Replacement extends beyond the file" }
            selected = at
            mutate { it.overwrite(at, bytes) }
        }
    }
    private fun mutate(change: (HexDocument) -> Unit) {
        runWork("Updating buffered bytes…") { change(checkNotNull(session.document)); session.persist() }
    }
    private fun confirmSave() {
        if (changed == 0 || !session.writable) return
        ThemeManager.createDialogBuilder(this).setTitle("Save changed bytes?")
            .setMessage("Replace ${session.name} with the buffered bytes? The original will be checked for changes first.")
            .setPositiveButton("Save") { _, _ -> runWork("Saving and verifying…", done = {
                editMode = false; editToggle.isChecked = false
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }) { session.save(applicationContext) } }.setNegativeButton("Cancel", null).show()
    }
    private fun moreMenu() {
        val items = arrayOf("Fill byte range…", "Checksums…", "Save copy…", "${if (columns == 8) 16 else 8} bytes per row")
        ThemeManager.createDialogBuilder(this).setTitle("Hex editor").setItems(items) { _, index ->
            when (index) {
                0 -> fillRange()
                1 -> checksums()
                2 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, session.name), SAVE_COPY)
                3 -> { columns = if (columns == 8) 16 else 8; refreshPage() }
            }
        }.show()
    }
    private fun fillRange() {
        val start = input("Start offset (inclusive)", "0x${selected.toString(16)}")
        val end = input("End offset (exclusive)", "0x${length.toString(16)}")
        val value = input("Value (decimal or 0x hex)", "0")
        val width = spinner(listOf("8-bit chunks", "16-bit chunks", "24-bit chunks", "32-bit chunks"), 0) {}
        val endian = spinner(listOf("Little endian", "Big endian"), 0) {}
        val signed = CheckBox(this).apply { text = "Signed value"; setTextColor(theme.textPrimary) }
        dialog("Fill byte range", form(start, end, width, endian, value, signed,
            label("Repeats the value over this byte range as one undoable edit. Whole chunks only; up to 1 MiB per fill and 1 MiB of changed bytes between saves.")), "Fill") {
            val first = HexEncoding.offset(start.text.toString()); val last = HexEncoding.offset(end.text.toString())
            val bits = listOf(8, 16, 24, 32)[width.selectedItemPosition]
            val unit = HexEncoding.integer(value.text.toString(), bits, signed.isChecked, endian.selectedItemPosition == 0)
            require(first < last && last <= length) { "Choose a nonempty range within this file" }
            require((last - first) % unit.size == 0L) { "The range must contain a whole number of chunks" }
            require(last - first <= 1024 * 1024) { "Fill up to 1 MiB per operation" }
            runWork("Filling buffered bytes…", cancellable = true, done = {
                editMode = true; editToggle.isChecked = true; jump(first)
            }) {
                session.snapshot(applicationContext, ::reportProgress)
                if (session.cancelled.get()) throw InterruptedIOException("Fill cancelled")
                checkNotNull(session.document).fill(first, last, unit); session.persist()
            }
        }
    }
    private fun checksums() {
        val start = input("Start offset (inclusive)", "0")
        val end = input("End offset (exclusive)", "0x${length.toString(16)}")
        val target = spinner(listOf("8-bit sum should equal 00", "8-bit sum should equal FF"), 0) {}
        val fix = CheckBox(this).apply { text = "Adjust final byte of range"; setTextColor(theme.textPrimary) }
        dialog("Checksums", form(start, end, target, fix,
            label("Calculates sum8, CRC32 and SHA-256 over buffered bytes. Optional correction changes only the final byte of this range; it does not infer a BIOS/vendor checksum format.")), "Calculate") {
            val first = HexEncoding.offset(start.text.toString()); val last = HexEncoding.offset(end.text.toString())
            require(first < last && last <= length) { "Choose a nonempty range within this file" }
            val adjusting = fix.isChecked
            val desired = if (target.selectedItemPosition == 0) 0 else 255
            var info: HexDocument.Checksums? = null
            var old = 0
            runWork("Calculating checksums…", cancellable = true, done = {
                val result = checkNotNull(info)
                val next = (desired - result.sum8 + old) and 255
                val details = "Range: $first to $last (end exclusive)\nSum8: %02X\nCRC32: %08X\nSHA-256: %s".format(result.sum8, result.crc32, result.sha256)
                val builder = ThemeManager.createDialogBuilder(this).setTitle("Checksums").setMessage(details +
                    if (adjusting) "\n\nByte at 0x${(last - 1).toString(16)}: %02X → %02X\nDesired sum8: %02X\nApply as an unsaved edit?".format(old, next, desired) else "")
                if (adjusting) builder.setPositiveButton("Apply byte") { _, _ ->
                    selected = last - 1; editMode = true; editToggle.isChecked = true
                    mutate { it.overwrite(last - 1, byteArrayOf(next.toByte())) }
                }.setNegativeButton("Cancel", null) else builder.setPositiveButton("OK", null)
                builder.show()
            }) {
                if (adjusting) session.snapshot(applicationContext, ::reportProgress)
                val doc = checkNotNull(session.document)
                info = doc.checksums(first, last, session.cancelled::get, ::reportProgress)
                old = doc.read(last - 1, 1)[0].toInt() and 255
            }
        }
    }
    @Deprecated("Android result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == SAVE_COPY && resultCode == Activity.RESULT_OK) {
            runWork("Saving copy…", done = { Toast.makeText(this, "Copy saved", Toast.LENGTH_SHORT).show() }) {
                session.snapshot(applicationContext, ::reportProgress); session.save(applicationContext, uri)
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode",mode.name);outState.putString("pic",RawMediaConfigStore.imageJson(pic.config).toString())
        outState.putBoolean("picSmooth",pic.smoothScaling)
        outState.putString("wave",RawMediaConfigStore.audioJson(wave.config).toString())
        outState.putString("encoding", encoding.name); outState.putBoolean("strings", stringsMode)
        outState.putInt("columns", columns); outState.putLong("page", page); outState.putLong("selected", selected)
        outState.putBoolean("editing", editMode); outState.putInt("row", list.firstVisiblePosition)
        outState.putString("draft", session.directory?.name); outState.putByteArray("pattern", pattern)
        outState.putLong("match", match ?: -1)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity retention")
    override fun onRetainNonConfigurationInstance(): Any = session
    @Deprecated("Activity back callback")
    override fun onBackPressed() {
        if (session.busy) {
            Toast.makeText(this, "Wait for the operation or use Cancel", Toast.LENGTH_SHORT).show(); return
        }
        if (changed > 0) {
            ThemeManager.createDialogBuilder(this).setTitle("Discard changed bytes?")
                .setMessage("There are $changed unsaved bytes.")
                .setPositiveButton("Discard") { _, _ -> discardDraft = true; finish() }
                .setNegativeButton("Keep editing", null).show()
        } else finish()
    }
    override fun onDestroy() {
        pic.dispose();wave.dispose()
        dead = true; pageToken++; main.removeCallbacksAndMessages(null)
        ThemeManager.removeListener(this)
        if (!isChangingConfigurations) {
            session.cancelled.set(true)
            session.worker.execute { session.close(discardDraft) }
            session.worker.shutdown()
        }
        super.onDestroy()
    }
    override fun onPause(){if(playing)session.cancelled.set(true);super.onPause()}
    companion object { private const val PAGE_BYTES = 4096; private const val SAVE_COPY = 801 }
}
