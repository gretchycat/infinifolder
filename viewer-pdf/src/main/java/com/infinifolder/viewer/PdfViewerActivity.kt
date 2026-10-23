package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

/**
 * Native, zero-dependency PDF document viewer using Android's built-in PdfRenderer.
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
    private var stagedFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null

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
            setOnClickListener { finish() }
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

        Thread {
            try {
                val source = MediaSourceAdapter.stage(this, targetUri, "doc.pdf")
                stagedSource = source
                val file = source.file
                if (file == null || !file.exists()) {
                    throw java.io.IOException("Unable to stage PDF file for reading")
                }
                stagedFile = file
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fileDescriptor
                val renderer = PdfRenderer(fileDescriptor)
                pdfRenderer = renderer
                totalPages = renderer.pageCount

                mainHandler.post {
                    progressBar.visibility = View.GONE
                    if (totalPages > 0) {
                        renderPage(0)
                    } else {
                        Toast.makeText(this, "PDF contains no pages", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Failed to open PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun renderPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex !in 0 until totalPages) return

        progressBar.visibility = View.VISIBLE

        Thread {
            try {
                currentPage?.close()
                val page = renderer.openPage(pageIndex)
                currentPage = page

                // Calculate high-DPI rendering dimensions based on screen display metrics
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels.coerceAtLeast(1080)
                val screenH = dm.heightPixels.coerceAtLeast(1920)

                // Scale factor for crisp text (at least 2x or screen width fit)
                val scale = kotlin.math.max(2.0f, screenW.toFloat() / page.width.toFloat())
                val bitmapWidth = (page.width * scale).toInt().coerceAtMost(4096)
                val bitmapHeight = (page.height * scale).toInt().coerceAtMost(4096)

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                mainHandler.post {
                    currentPageIndex = pageIndex
                    pageImageView.setImageBitmap(bitmap)
                    pageImageView.resetZoom()
                    updateNavigationUI()
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Error rendering page: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateNavigationUI() {
        pageIndicatorBtn.text = "Page ${currentPageIndex + 1} / $totalPages"
        prevPageBtn.isEnabled = currentPageIndex > 0
        nextPageBtn.isEnabled = currentPageIndex < totalPages - 1
        prevPageBtn.alpha = if (prevPageBtn.isEnabled) 1.0f else 0.3f
        nextPageBtn.alpha = if (nextPageBtn.isEnabled) 1.0f else 0.3f
    }

    private fun showPreviousPage() {
        if (currentPageIndex > 0) {
            renderPage(currentPageIndex - 1)
        }
    }

    private fun showNextPage() {
        if (currentPageIndex < totalPages - 1) {
            renderPage(currentPageIndex + 1)
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
        super.onDestroy()
        runCatching { currentPage?.close() }
        currentPage = null
        runCatching { pdfRenderer?.close() }
        pdfRenderer = null
        runCatching { pfd?.close() }
        pfd = null
        stagedSource?.close()
        stagedSource = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
