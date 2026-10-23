package com.infinifolder.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Sandboxed HTML document viewer with Rendered / Source toggle, safe web settings,
 * in-viewer quill editing, and VFS save/Save As.
 */
class HtmlViewerActivity : ViewerActivity() {
    private var loadedRevision: com.infinifolder.storage.ContentRevision? = null


    private var uri: Uri? = null
    private var displayName: String = "HTML Viewer"
    private var htmlContent: String = ""
    private var isSourceMode = false
    private var isEditMode = false
    private var isDirty = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var sourceContainer: LinearLayout
    private lateinit var sourceEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var titleTextView: TextView
    private lateinit var modeToggleBtn: Button
    private lateinit var editQuillBtn: ImageButton
    private lateinit var saveBtn: Button

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
            ?: "document.html"

        setupUI()
        loadDocument()
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

        // Mode toggle (Rendered / Source)
        modeToggleBtn = Button(this).apply {
            text = "Source"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF333333.toInt())
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { toggleSourceMode() }
        }
        toolbar.addView(modeToggleBtn, LinearLayout.LayoutParams(-2, dp(36)).apply {
            marginEnd = dp(8)
        })

        // Quill Edit Button
        editQuillBtn = ImageButton(this).apply {
            setImageResource(com.infinifolder.viewer.common.R.drawable.ic_edit_quill)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "Edit HTML"
            setOnClickListener { toggleEditMode() }
        }
        toolbar.addView(editQuillBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginEnd = dp(8)
        })

        // Save Button (visible when dirty/editing)
        saveBtn = Button(this).apply {
            text = "Save"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF1976D2.toInt())
            }
            visibility = View.GONE
            setOnClickListener { saveChanges() }
        }
        toolbar.addView(saveBtn, LinearLayout.LayoutParams(-2, dp(36)).apply {
            marginEnd = dp(8)
        })

        // External Open
        val openExternalBtn = Button(this).apply {
            text = "↗"
            textSize = 16f
            setTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openExternally() }
        }
        toolbar.addView(openExternalBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        mainColumn.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        // Content Area Frame
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // 1. Sandboxed WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(0xFFFFFFFF.toInt())
            settings.apply {
                // Strict sandbox settings to prevent local file leaks or remote escalation
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                javaScriptEnabled = false
                domStorageEnabled = false
                loadsImagesAutomatically = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val targetUri = request?.url ?: return false
                    // External links open in system browser
                    val extIntent = Intent(Intent.ACTION_VIEW, targetUri)
                    try {
                        startActivity(extIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this@HtmlViewerActivity, "Cannot open: $targetUri", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // Block unauthorized external requests if necessary, or let safe images load
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        contentFrame.addView(webView)

        // 2. Source / Edit View
        sourceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xFF1E1E1E.toInt())
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            isFillViewport = true
        }

        val hScroll = HorizontalScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isFillViewport = true
        }

        sourceEditText = EditText(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFD4D4D4.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.TOP or Gravity.START
            isFocusableInTouchMode = false
            isCursorVisible = false
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (isEditMode) {
                        this@HtmlViewerActivity.isDirty = true
                        saveBtn.visibility = View.VISIBLE
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        hScroll.addView(sourceEditText, FrameLayout.LayoutParams(-2, -1))
        scroll.addView(hScroll, FrameLayout.LayoutParams(-1, -1))
        sourceContainer.addView(scroll)
        contentFrame.addView(sourceContainer)

        // Progress bar
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            layoutParams = lp
        }
        contentFrame.addView(progressBar)

        mainColumn.addView(contentFrame)
        rootLayout.addView(mainColumn)
        setContentView(rootLayout)
    }

    private fun loadDocument() {
        loadedRevision = null
        val u = uri ?: run {
            progressBar.visibility = View.GONE
            Toast.makeText(this, "No file URI provided", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        Thread {
            val content = try {
                val document = MediaSourceAdapter.readDocument(this, u, 8 * 1024 * 1024)
                if (document.hasMore) throw java.io.IOException("HTML document exceeds the editor limit")
                loadedRevision = document.revision
                document.text
            } catch (e: Exception) {
                null
            }

            runOnUiThread {
                progressBar.visibility = View.GONE
                if (content != null) {
                    htmlContent = content
                    sourceEditText.setText(content)
                    renderHtml()
                    if (!isEditMode && ViewerLaunch.requestsEdit(intent)) toggleEditMode()
                } else {
                    Toast.makeText(this, "Failed to load HTML file", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun renderHtml() {
        webView.loadDataWithBaseURL("about:blank", htmlContent, "text/html", "UTF-8", null)
    }

    private fun toggleSourceMode() {
        isSourceMode = !isSourceMode
        if (isSourceMode) {
            modeToggleBtn.text = "Rendered"
            webView.visibility = View.GONE
            sourceContainer.visibility = View.VISIBLE
        } else {
            modeToggleBtn.text = "Source"
            if (isEditMode) {
                htmlContent = sourceEditText.text.toString()
                renderHtml()
            }
            webView.visibility = View.VISIBLE
            sourceContainer.visibility = View.GONE
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (isEditMode) {
            // Enter source mode if not already there
            if (!isSourceMode) {
                toggleSourceMode()
            }
            sourceEditText.isFocusableInTouchMode = true
            sourceEditText.isCursorVisible = true
            sourceEditText.requestFocus()
            editQuillBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            Toast.makeText(this, "Edit mode enabled", Toast.LENGTH_SHORT).show()
        } else {
            sourceEditText.isFocusableInTouchMode = false
            sourceEditText.isCursorVisible = false
            editQuillBtn.background = null
            if (isDirty) {
                htmlContent = sourceEditText.text.toString()
                renderHtml()
            }
        }
    }

    private fun saveChanges() {
        val u = uri ?: return
        val expected = loadedRevision ?: run {
            Toast.makeText(this, "Reload the original before saving changes", Toast.LENGTH_LONG).show()
            return
        }
        val newContent = sourceEditText.text.toString()
        progressBar.visibility = View.VISIBLE

        Thread {
            val res = MediaSourceAdapter.saveContent(this, u, newContent.toByteArray(StandardCharsets.UTF_8), expected)
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (res is com.infinifolder.model.ProviderResult.Success) {
                    loadedRevision = com.infinifolder.storage.ContentRevision.of(newContent.toByteArray(StandardCharsets.UTF_8))
                    htmlContent = newContent
                    isDirty = false
                    saveBtn.visibility = View.GONE
                    Toast.makeText(this, "Saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    promptSaveAs(newContent)
                }
            }
        }.start()
    }

    private fun promptSaveAs(content: String) {
        val input = EditText(this).apply {
            setText(displayName)
            setSelectAllOnFocus(true)
        }
        val padding = dp(20)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
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
            setDataAndType(u, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(extIntent, "Open with…"))
        } catch (e: Exception) {
            Toast.makeText(this, "No external handler found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
