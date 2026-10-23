package com.infinifolder.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Free, native EPUB document reader.
 * Extracts EPUB packages without third-party dependencies, parses OPF/NCX manifests,
 * and renders chapters with font scaling, dark/sepia/light themes, and chapter navigation.
 */
class EpubViewerActivity : ViewerActivity() {

    enum class ReaderTheme(val nameLabel: String, val bgColor: Int, val textColor: Int, val cssBg: String, val cssText: String, val cssLink: String) {
        DARK("Dark", 0xFF1E1E1E.toInt(), 0xFFD4D4D4.toInt(), "#1E1E1E", "#D4D4D4", "#4EC9B0"),
        SEPIA("Sepia", 0xFFFBF0D9.toInt(), 0xFF4A3B2C.toInt(), "#FBF0D9", "#4A3B2C", "#A25F2B"),
        LIGHT("Light", 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), "#FFFFFF", "#111111", "#1976D2")
    }

    data class ChapterItem(
        val id: String,
        val href: String,
        val title: String,
        val file: File
    )

    private var uri: Uri? = null
    private var displayName: String = "Book.epub"
    private var stagedSource: MediaSourceAdapter.StagedSource? = null
    private var stagedFile: File? = null
    private var extractDir: File? = null

    private var bookTitle: String = "EPUB Reader"
    private var bookAuthor: String = ""
    private val chapters = mutableListOf<ChapterItem>()
    private var currentChapterIndex: Int = 0

    private var currentTheme = ReaderTheme.DARK
    private var currentTextZoom: Int = 110 // percent

    private lateinit var rootLayout: FrameLayout
    private lateinit var titleTextView: TextView
    private lateinit var chapterIndicatorBtn: Button
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isSystemDark = com.infinifolder.theme.ThemeManager.isSystemInDarkMode(this)
        currentTheme = if (isSystemDark) ReaderTheme.DARK else ReaderTheme.LIGHT
        window.statusBarColor = currentTheme.bgColor
        window.navigationBarColor = currentTheme.bgColor

        uri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra("extra_uri")?.let { Uri.parse(it) }
        displayName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra("extra_name")
            ?: uri?.lastPathSegment
            ?: "document.epub"

        setupUI()
        loadEpub()
    }

    private fun setupUI() {
        val basePadH = dp(4)
        val basePadT = dp(4)
        val basePadB = dp(8)
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF1E1E1E.toInt())
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
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF252526.toInt())
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        toolbar.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        titleTextView = TextView(this).apply {
            text = displayName
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleContainer.addView(titleTextView, LinearLayout.LayoutParams(-1, -2))
        toolbar.addView(titleContainer, LinearLayout.LayoutParams(0, -2, 1.0f))

        // Table of contents / chapters button
        val tocBtn = Button(this).apply {
            text = "📑 TOC"
            setTextColor(Color.WHITE)
            textSize = 12f
            isAllCaps = false
            background = makePillDrawable(0xFF333333.toInt(), 0xFF555555.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { showChapterListDialog() }
        }
        toolbar.addView(tocBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })

        // Theme toggle button
        val themeBtn = Button(this).apply {
            text = "🎨"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = makePillDrawable(0xFF333333.toInt(), 0xFF555555.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { cycleTheme(this) }
        }
        toolbar.addView(themeBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })

        // Zoom out (A-)
        val zoomOutBtn = Button(this).apply {
            text = "A-"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = makePillDrawable(0xFF333333.toInt(), 0xFF555555.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { adjustZoom(-15) }
        }
        toolbar.addView(zoomOutBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(4) })

        // Zoom in (A+)
        val zoomInBtn = Button(this).apply {
            text = "A+"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = makePillDrawable(0xFF333333.toInt(), 0xFF555555.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { adjustZoom(15) }
        }
        toolbar.addView(zoomInBtn, LinearLayout.LayoutParams(-2, dp(34)))

        mainColumn.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        mainColumn.addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)))

        // WebView for rendering chapter
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1.0f)
            setBackgroundColor(currentTheme.bgColor)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                builtInZoomControls = true
                displayZoomControls = false
                textZoom = currentTextZoom
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar.visibility = View.GONE
                    applyReaderStyling()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val reqUri = request?.url ?: return false
                    return handleNavigation(reqUri)
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val reqUri = if (url != null) Uri.parse(url) else return false
                    return handleNavigation(reqUri)
                }
            }
        }
        mainColumn.addView(webView)

        // Bottom Navigation Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(0xFF252526.toInt())
        }

        prevBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Previous Chapter"
            setOnClickListener { navigateChapter(-1) }
        }
        bottomBar.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        chapterIndicatorBtn = Button(this).apply {
            text = "Loading..."
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showChapterListDialog() }
        }
        bottomBar.addView(chapterIndicatorBtn, LinearLayout.LayoutParams(0, -2, 1.0f))

        nextBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Next Chapter"
            setOnClickListener { navigateChapter(1) }
        }
        bottomBar.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        mainColumn.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        rootLayout.addView(mainColumn)
        setContentView(rootLayout)
    }

    private fun handleNavigation(navUri: Uri): Boolean {
        val scheme = navUri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "http" || scheme == "https" || scheme == "mailto") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, navUri))
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open external link: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        // Relative internal anchor or chapter file link
        val path = navUri.path ?: ""
        val matchedIndex = chapters.indexOfFirst {
            it.file.absolutePath.endsWith(path) || path.endsWith(it.href.substringBefore('#'))
        }
        if (matchedIndex != -1) {
            loadChapter(matchedIndex)
            return true
        }
        return false
    }

    private fun loadEpub() {
        val targetUri = uri ?: run {
            Toast.makeText(this, "No file provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE
        Thread {
            try {
                val source = MediaSourceAdapter.stage(this, targetUri)
                stagedSource = source
                val file = source.file
                if (file == null || !file.exists() || file.length() == 0L) {
                    throw IllegalStateException("Failed to stage EPUB file")
                }
                stagedFile = file

                val outDir = File(cacheDir, "epub_${System.currentTimeMillis()}")
                outDir.mkdirs()
                extractDir = outDir

                extractZip(file, outDir)
                parseEpubPackage(outDir)

                mainHandler.post {
                    progressBar.visibility = View.GONE
                    if (chapters.isEmpty()) {
                        Toast.makeText(this, "No readable chapters found in EPUB", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    titleTextView.text = if (bookAuthor.isNotEmpty()) "$bookTitle • $bookAuthor" else bookTitle
                    loadChapter(0)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed to load EPUB: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun extractZip(zipFile: File, destDir: File) {
        val zip = ZipFile(zipFile)
        val entries = zip.entries()
        val destCanonicalPath = destDir.canonicalPath
        while (entries.hasMoreElements()) {
            val entry: ZipEntry = entries.nextElement()
            val file = File(destDir, entry.name)
            // Path traversal protection
            if (!file.canonicalPath.startsWith(destCanonicalPath)) {
                continue
            }
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        zip.close()
    }

    private fun parseEpubPackage(extractedDir: File) {
        // 1. Locate container.xml
        val containerFile = File(extractedDir, "META-INF/container.xml")
        if (!containerFile.exists()) {
            throw IllegalStateException("Invalid EPUB: META-INF/container.xml not found")
        }

        var opfRelativePath = ""
        val parser = Xml.newPullParser()
        FileInputStream(containerFile).use { fis ->
            parser.setInput(fis, "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrEmpty()) {
                        opfRelativePath = fullPath
                        break
                    }
                }
                eventType = parser.next()
            }
        }

        if (opfRelativePath.isEmpty()) {
            // Fallback search for any .opf file
            val foundOpf = extractedDir.walkTopDown().firstOrNull { it.extension.equals("opf", ignoreCase = true) }
            if (foundOpf != null) {
                opfRelativePath = foundOpf.relativeTo(extractedDir).path
            } else {
                throw IllegalStateException("Package metadata (OPF) not found")
            }
        }

        val opfFile = File(extractedDir, opfRelativePath)
        val opfDir = opfFile.parentFile ?: extractedDir

        // 2. Parse OPF file
        val manifestItems = mutableMapOf<String, String>() // id -> href
        val spineItems = mutableListOf<String>() // idref list
        val tocMap = mutableMapOf<String, String>() // href -> title
        var parsedTitle: String? = null
        var parsedAuthor: String? = null

        FileInputStream(opfFile).use { fis ->
            parser.setInput(fis, null)
            var eventType = parser.eventType
            var inMetadata = false
            var inManifest = false
            var inSpine = false
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase(Locale.ROOT)
                        when (currentTag) {
                            "metadata" -> inMetadata = true
                            "manifest" -> inManifest = true
                            "spine" -> inSpine = true
                            "item" -> {
                                if (inManifest) {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) {
                                        manifestItems[id] = href
                                    }
                                }
                            }
                            "itemref" -> {
                                if (inSpine) {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    val linear = parser.getAttributeValue(null, "linear")
                                    if (!idref.isNullOrEmpty() && linear != "no") {
                                        spineItems.add(idref)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (inMetadata && text.isNotEmpty()) {
                            if (currentTag.endsWith("title") && parsedTitle == null) {
                                parsedTitle = text
                            } else if (currentTag.endsWith("creator") && parsedAuthor == null) {
                                parsedAuthor = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name.lowercase(Locale.ROOT)
                        when (tagName) {
                            "metadata" -> inMetadata = false
                            "manifest" -> inManifest = false
                            "spine" -> inSpine = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        }

        if (!parsedTitle.isNullOrEmpty()) bookTitle = parsedTitle ?: displayName
        if (!parsedAuthor.isNullOrEmpty()) bookAuthor = parsedAuthor ?: ""

        // 3. Try to parse TOC (NCX or NAV) for chapter titles
        val ncxHref = manifestItems.values.firstOrNull { it.endsWith(".ncx", ignoreCase = true) }
        if (ncxHref != null) {
            val ncxFile = File(opfDir, URLDecoder.decode(ncxHref, "UTF-8"))
            if (ncxFile.exists()) {
                parseNcx(ncxFile, tocMap)
            }
        }

        // 4. Build ordered list of chapters from spine
        chapters.clear()
        for (idref in spineItems) {
            val href = manifestItems[idref] ?: continue
            val decodedHref = try {
                URLDecoder.decode(href, "UTF-8")
            } catch (_: Exception) {
                href
            }
            val chapterFile = File(opfDir, decodedHref)
            if (chapterFile.exists()) {
                val cleanHref = href.substringBefore('#')
                val title = tocMap[cleanHref]
                    ?: tocMap[decodedHref.substringBefore('#')]
                    ?: extractTitleFromHtml(chapterFile)
                    ?: "Chapter ${chapters.size + 1}"
                chapters.add(ChapterItem(idref, href, title, chapterFile))
            }
        }

        // Fallback: if spine is empty, load all HTML/XHTML files in manifest
        if (chapters.isEmpty()) {
            val htmlItems = manifestItems.values.filter {
                it.endsWith(".html", true) || it.endsWith(".xhtml", true) || it.endsWith(".htm", true)
            }
            for ((index, href) in htmlItems.withIndex()) {
                val file = File(opfDir, URLDecoder.decode(href, "UTF-8"))
                if (file.exists()) {
                    chapters.add(ChapterItem("item_$index", href, extractTitleFromHtml(file) ?: "Chapter ${index + 1}", file))
                }
            }
        }
    }

    private fun parseNcx(ncxFile: File, tocMap: MutableMap<String, String>) {
        try {
            val parser = Xml.newPullParser()
            FileInputStream(ncxFile).use { fis ->
                parser.setInput(fis, null)
                var eventType = parser.eventType
                var currentLabel = ""
                var inText = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            val name = parser.name.lowercase(Locale.ROOT)
                            if (name == "text") {
                                inText = true
                            } else if (name == "content") {
                                val src = parser.getAttributeValue(null, "src")
                                if (!src.isNullOrEmpty() && currentLabel.isNotEmpty()) {
                                    val cleanSrc = src.substringBefore('#')
                                    tocMap[cleanSrc] = currentLabel
                                    currentLabel = ""
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inText) {
                                val text = parser.text?.trim()
                                if (!text.isNullOrEmpty()) {
                                    currentLabel = text
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name.equals("text", ignoreCase = true)) {
                                inText = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (_: Exception) {
            // Ignore NCX parsing errors, fallback titles will be used
        }
    }

    private fun extractTitleFromHtml(file: File): String? {
        try {
            val headerText = file.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                val read = reader.read(buffer, 0, buffer.size)
                if (read > 0) String(buffer, 0, read) else ""
            }
            val titleMatch = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(headerText)
            if (titleMatch != null) {
                val title = titleMatch.groupValues[1].trim()
                if (title.isNotEmpty() && !title.equals("untitled", true)) {
                    return title
                }
            }
            val h1Match = Regex("<h[1-2][^>]*>(.*?)</h[1-2]>", RegexOption.IGNORE_CASE).find(headerText)
            if (h1Match != null) {
                val clean = h1Match.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
                if (clean.isNotEmpty()) return clean
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun loadChapter(index: Int) {
        if (index !in chapters.indices) return
        currentChapterIndex = index
        val chapter = chapters[index]

        chapterIndicatorBtn.text = "${index + 1} / ${chapters.size} • ${chapter.title}"
        prevBtn.isEnabled = index > 0
        prevBtn.alpha = if (index > 0) 1.0f else 0.35f
        nextBtn.isEnabled = index < chapters.size - 1
        nextBtn.alpha = if (index < chapters.size - 1) 1.0f else 0.35f

        progressBar.visibility = View.VISIBLE
        val fileUri = Uri.fromFile(chapter.file)
        webView.loadUrl(fileUri.toString())
    }

    private fun navigateChapter(delta: Int) {
        val target = currentChapterIndex + delta
        if (target in chapters.indices) {
            loadChapter(target)
        }
    }

    private fun showChapterListDialog() {
        if (chapters.isEmpty()) return
        val items = chapters.mapIndexed { idx, ch -> "${idx + 1}. ${ch.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Table of Contents (${chapters.size} chapters)")
            .setSingleChoiceItems(items, currentChapterIndex) { dialog, which ->
                loadChapter(which)
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun cycleTheme(btn: Button) {
        currentTheme = when (currentTheme) {
            ReaderTheme.DARK -> ReaderTheme.SEPIA
            ReaderTheme.SEPIA -> ReaderTheme.LIGHT
            ReaderTheme.LIGHT -> ReaderTheme.DARK
        }
        btn.text = "🎨 ${currentTheme.nameLabel}"
        webView.setBackgroundColor(currentTheme.bgColor)
        applyReaderStyling()
    }

    private fun adjustZoom(delta: Int) {
        currentTextZoom = (currentTextZoom + delta).coerceIn(70, 250)
        webView.settings.textZoom = currentTextZoom
        Toast.makeText(this, "Text Zoom: $currentTextZoom%", Toast.LENGTH_SHORT).show()
    }

    private fun applyReaderStyling() {
        val css = """
            body {
                background-color: ${currentTheme.cssBg} !important;
                color: ${currentTheme.cssText} !important;
                line-height: 1.6 !important;
                padding: 12px 8px !important;
            }
            p, div, span, li, blockquote, td, th {
                color: ${currentTheme.cssText} !important;
            }
            a {
                color: ${currentTheme.cssLink} !important;
            }
            img, svg {
                max-width: 100% !important;
                height: auto !important;
            }
        """.trimIndent().replace("\n", " ").replace("'", "\\'")

        val js = """
            (function() {
                var style = document.getElementById('infinifolder-reader-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'infinifolder-reader-style';
                    document.head.appendChild(style);
                }
                style.textContent = '$css';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun makePillDrawable(bgColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(bgColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isNight = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val targetTheme = if (isNight) ReaderTheme.DARK else ReaderTheme.LIGHT
        if (currentTheme != targetTheme) {
            currentTheme = targetTheme
            window.statusBarColor = currentTheme.bgColor
            window.navigationBarColor = currentTheme.bgColor
            webView.setBackgroundColor(currentTheme.bgColor)
            applyReaderStyling()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {}
        try {
            extractDir?.deleteRecursively()
        } catch (_: Exception) {}
        stagedSource?.close()
        stagedSource = null
    }
}
