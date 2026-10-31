package com.infinifolder.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.gretchycat.infiniscribe.android.PdfDocument
import io.github.gretchycat.infiniscribe.android.PdfDocuments
import io.github.gretchycat.infinistream.ContentType
import io.github.gretchycat.infinistream.Provenance
import io.github.gretchycat.infinistream.StreamMetadata
import com.infinifolder.storage.DiagnosticLog
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * PDF presentation; Scribe owns the Android PDF parsing and rasterization backend.
 * Supports pinch-to-zoom, page navigation, direct jump-to-page, and fit width/page modes.
 */
class PdfViewerActivity : ViewerActivity() {

    private var uri: Uri? = null
    private var displayName: String = "Document.pdf"

    private lateinit var rootLayout: FrameLayout
    private lateinit var pageImageView: ZoomImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleTextView: TextView
    private lateinit var pageIndicatorBtn: Button
    private lateinit var prevPageBtn: ImageButton
    private lateinit var nextPageBtn: ImageButton
    private lateinit var fitToggleBtn: Button

    private var stagedSource: MediaSourceAdapter.StagedSource? = null
    // These handles are only accessed by the single document worker.
    private var pdfDocument: PdfDocument? = null
    private val pdfWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "pdf-viewer") }
    private var work: Future<*>? = null
    private val generation = AtomicLong()
    @Volatile private var destroyed = false
    private var requestedPageIndex = 0

    private var currentPageIndex: Int = 0
    private var totalPages: Int = 0
    private var isFitWidth: Boolean = true
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF1E1E1E.toInt()
        window.navigationBarColor = 0xFF1E1E1E.toInt()

        uri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra("extra_uri")?.let { Uri.parse(it) }
        displayName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra("extra_name")
            ?: uri?.lastPathSegment
            ?: "document.pdf"

        setupUI()
        loadPdfDocument()
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
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF252526.toInt())
        }

        val backBtn = Button(this).apply {
            text = "←"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackPressed() }
        }
        toolbar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        titleTextView = TextView(this).apply {
            text = displayName
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(titleTextView, LinearLayout.LayoutParams(0, -2, 1f))

        fitToggleBtn = Button(this).apply {
            text = "Fit"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(0x22FFFFFF)
            }
            setOnClickListener {
                isFitWidth = !isFitWidth
                pageImageView.toggleFitFill()
            }
        }
        toolbar.addView(fitToggleBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) })

        val openExtBtn = Button(this).apply {
            text = "↗"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openExternally() }
        }
        toolbar.addView(openExtBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        DebugTools.attachToolbarButton(this, toolbar)
        mainColumn.addView(toolbar)

        // Content Area (ZoomImageView for pinch-to-zoom page rendering)
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(0xFF181818.toInt())
        }

        pageImageView = ZoomImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(0xFF181818.toInt())
        }
        contentFrame.addView(pageImageView)

        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            visibility = View.VISIBLE
        }
        contentFrame.addView(progressBar)

        mainColumn.addView(contentFrame)

        // Bottom Navigation Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF252526.toInt())
        }

        prevPageBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPreviousPage() }
        }
        bottomBar.addView(prevPageBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        pageIndicatorBtn = Button(this).apply {
            text = "Page 0 / 0"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(0x22FFFFFF)
            }
            setOnClickListener { promptJumpToPage() }
        }
        bottomBar.addView(pageIndicatorBtn, LinearLayout.LayoutParams(-2, dp(36)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        nextPageBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showNextPage() }
        }
        bottomBar.addView(nextPageBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        mainColumn.addView(bottomBar)

        rootLayout.addView(mainColumn)
        setContentView(rootLayout)
    }

    private fun loadPdfDocument() {
        val targetUri = uri ?: run {
            Toast.makeText(this, "No PDF URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE

        work = pdfWorker.submit {
            var source: MediaSourceAdapter.StagedSource? = null
            var document: PdfDocument? = null
            try {
                source = MediaSourceAdapter.stage(applicationContext, targetUri, "doc.pdf")
                document = PdfDocuments.open(source.file, StreamMetadata(
                    content = ContentType(mimeType = "application/pdf"), name = displayName,
                    provenance = Provenance(source = targetUri.toString())))
                val pages = document.pageCount
                stagedSource = source
                pdfDocument = document
                source = null
                document = null

                mainHandler.post {
                    if (destroyed) return@post
                    totalPages = pages
                    progressBar.visibility = View.GONE
                    if (totalPages > 0) {
                        renderPage(0)
                    } else {
                        Toast.makeText(this, "PDF contains no pages", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                DiagnosticLog.record("pdf.open.failed", "Unable to open PDF $targetUri", e)
                mainHandler.post {
                    if (destroyed) return@post
                    progressBar.visibility = View.GONE
                    if (!CacheFailureUi.offerCopy(this, e, targetUri)) Toast.makeText(this, "Failed to open PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runCatching { document?.close() }
                runCatching { source?.close() }
            }
        }
    }

    private fun renderPage(pageIndex: Int) {
        if (destroyed || pageIndex !in 0 until totalPages) return

        val request = generation.incrementAndGet()
        requestedPageIndex = pageIndex
        work?.cancel(true)
        progressBar.visibility = View.VISIBLE
        val viewportWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1080)

        work = pdfWorker.submit {
            try {
                val document = pdfDocument ?: return@submit
                val frame = document.renderPage(pageIndex, viewportWidth)

                mainHandler.post {
                    frame.use {
                        if (destroyed || request != generation.get()) return@post
                        currentPageIndex = pageIndex
                        // Transfer the bitmap to ImageView/GC; recycled stale frames are never displayed.
                        pageImageView.setImageBitmap(frame.takeBitmap())
                        pageImageView.resetZoom()
                        updateNavigationUI()
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                if (e is java.io.InterruptedIOException) return@submit
                DiagnosticLog.record("pdf.render.failed", "Unable to render page ${pageIndex + 1}", e)
                mainHandler.post {
                    if (destroyed || request != generation.get()) return@post
                    requestedPageIndex = currentPageIndex
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Error rendering page: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateNavigationUI() {
        pageIndicatorBtn.text = "Page ${currentPageIndex + 1} / $totalPages"
        prevPageBtn.isEnabled = currentPageIndex > 0
        nextPageBtn.isEnabled = currentPageIndex < totalPages - 1
        prevPageBtn.alpha = if (prevPageBtn.isEnabled) 1.0f else 0.3f
        nextPageBtn.alpha = if (nextPageBtn.isEnabled) 1.0f else 0.3f
    }

    private fun showPreviousPage() {
        if (requestedPageIndex > 0) {
            renderPage(requestedPageIndex - 1)
        }
    }

    private fun showNextPage() {
        if (requestedPageIndex < totalPages - 1) {
            renderPage(requestedPageIndex + 1)
        }
    }

    private fun promptJumpToPage() {
        if (totalPages <= 1) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val seekLabel = TextView(this).apply {
            text = "Page ${currentPageIndex + 1} of $totalPages"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        container.addView(seekLabel)

        val seekBar = SeekBar(this).apply {
            max = totalPages - 1
            progress = currentPageIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    seekLabel.text = "Page ${progress + 1} of $totalPages"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(seekBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        AlertDialog.Builder(this)
            .setTitle("Jump to Page")
            .setView(container)
            .setPositiveButton("Go") { _, _ ->
                renderPage(seekBar.progress)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openExternally() {
        val u = uri ?: return
        val extIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(u, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(extIntent, "Open with…"))
        } catch (e: Exception) {
            Toast.makeText(this, "No external PDF handler found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        work?.cancel(true)
        pdfWorker.execute {
            runCatching { pdfDocument?.close() }
            pdfDocument = null
            runCatching { stagedSource?.close() }
            stagedSource = null
        }
        pdfWorker.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
