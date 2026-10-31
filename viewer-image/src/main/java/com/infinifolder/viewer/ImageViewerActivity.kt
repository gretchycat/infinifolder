package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.infinifolder.viewer.common.R
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class ImageViewerActivity : ViewerActivity() {

    private lateinit var zoomImageView: ZoomImageView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var editPanel: LinearLayout
    private lateinit var editQuillBtn: ImageView
    private lateinit var slideshowBtn: TextView
    private lateinit var repeatBtn: TextView

    private var currentUri: Uri? = null
    private var parentFolderUri: Uri? = null
    private var currentName: String = ""
    private var currentMime: String = ""

    private var imageLoadGeneration = 0
    private var imageLoadThread: Thread? = null
    private var originalBitmap: Bitmap? = null
    private var decodedImageMetadata: io.github.gretchycat.infinistream.StreamMetadata? = null
    private var isBarsVisible = true
    private var isEditMode = false
    private var hasUnsavedEdits = false

    // Folder navigation & presentation
    private val folderImages = mutableListOf<ResourceEntry>()
    private var currentImageIndex = 0
    private var isRepeatEnabled = false
    private var isSlideshowPlaying = false
    private val slideshowHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (!isSlideshowPlaying) return
            advanceSlideshow()
            if (isSlideshowPlaying) {
                slideshowHandler.postDelayed(this, 3000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }

        if (targetUri == null) {
            Toast.makeText(this, "No image URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUri = targetUri
        currentName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra(EXTRA_NAME)
            ?: currentUri?.lastPathSegment?.substringAfterLast('/')
            ?: "Image"
        currentMime = intent.getStringExtra("mimeType")
            ?: intent.getStringExtra(EXTRA_MIME)
            ?: "image/*"
        val parentStr = intent.getStringExtra(EXTRA_PARENT_URI) ?: intent.getStringExtra("parentUri")
        parentFolderUri = if (parentStr != null) Uri.parse(parentStr) else MediaSourceAdapter.getParentDirectory(this, currentUri!!)

        buildLayout()
        loadImage(currentUri!!)
        loadFolderImages()
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF101210.toInt())
        }

        zoomImageView = ZoomImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                if (!isEditMode) {
                    toggleBars()
                }
            }
        }
        root.addView(zoomImageView)

        // Top Toolbar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC181C17.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        // Back button
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onBackPressed() }
        }
        topBar.addView(backBtn)

        // Title and dimensions
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(4), 0)
        }
        titleView = TextView(this).apply {
            text = currentName
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = TextView(this).apply {
            text = "Loading…"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
        }
        titleLayout.addView(titleView)
        titleLayout.addView(subtitleView)
        topBar.addView(titleLayout)

        // Edit button: Monochromatic line-art quill icon with transparent background
        editQuillBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_edit_quill)
            setColorFilter(0xFFFFFFFF.toInt())
            contentDescription = "Edit"
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { toggleEditMode() }
        }
        topBar.addView(editQuillBtn)

        // Fit / Fill button
        val fitFillBtn = TextView(this).apply {
            text = "⊡"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { zoomImageView.toggleFitFill() }
        }
        topBar.addView(fitFillBtn)

        // Rotate button
        val rotateBtn = TextView(this).apply {
            text = "↻"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                zoomImageView.rotate90(clockwise = true)
                hasUnsavedEdits = isEditMode
            }
        }
        topBar.addView(rotateBtn)

        // Info (EXIF Inspector) button
        val infoBtn = TextView(this).apply {
            text = "ℹ"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { showMetadataInspector() }
        }
        topBar.addView(infoBtn)

        // Open with external app
        val openWithBtn = TextView(this).apply {
            text = "↗"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(12), dp(8))
            contentDescription = "Open with external app"
            setOnClickListener { openExternally() }
        }
        topBar.addView(openWithBtn)

        DebugTools.attachToolbarButton(this, topBar)
        root.addView(topBar)

        // Bottom Navigation Bar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC181C17.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val prevBtn = TextView(this).apply {
            text = "◀"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { navigateAdjacent(-1) }
        }
        bottomBar.addView(prevBtn)

        val counterView = TextView(this).apply {
            id = View.generateViewId()
            text = ""
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        bottomBar.addView(counterView)

        val nextBtn = TextView(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { navigateAdjacent(1) }
        }
        bottomBar.addView(nextBtn)

        slideshowBtn = TextView(this).apply {
            text = "▶ Slideshow"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { toggleSlideshow() }
        }
        bottomBar.addView(slideshowBtn)

        repeatBtn = TextView(this).apply {
            text = "🔁"
            textSize = 15f
            setTextColor(0x88FFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                isRepeatEnabled = !isRepeatEnabled
                repeatBtn.setTextColor(if (isRepeatEnabled) 0xFF4CAF50.toInt() else 0x88FFFFFF.toInt())
            }
        }
        bottomBar.addView(repeatBtn)

        root.addView(bottomBar)

        // Edit Panel (Phase 1B)
        editPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE1E241E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(14))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        buildEditPanel()
        root.addView(editPanel)

        val basePad = dp(8)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            } else null
            val left = safe?.left ?: insets.systemWindowInsetLeft
            val top = safe?.top ?: insets.systemWindowInsetTop
            val right = safe?.right ?: insets.systemWindowInsetRight
            val bottom = safe?.bottom ?: insets.systemWindowInsetBottom
            topBar.setPadding(basePad + left, basePad + top, basePad + right, basePad)
            bottomBar.setPadding(basePad + left, basePad, basePad + right, basePad + bottom)
            editPanel.setPadding(basePad + left, basePad, basePad + right, basePad + bottom)
            insets
        }

        setContentView(root)
    }

    private fun buildEditPanel() {
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        fun toolBtn(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { action() }
        }

        toolsRow.addView(toolBtn("Crop") { showCropOptions() })
        toolsRow.addView(toolBtn("Rotate/Flip") { showRotateFlipOptions() })
        toolsRow.addView(toolBtn("Adjust") { showAdjustmentSliders() })
        toolsRow.addView(toolBtn("Resize") { showResizeDialog() })
        editPanel.addView(toolsRow)

        // Actions: Save, Save As, Cancel
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }

        val cancelBtn = TextView(this).apply {
            text = "Discard"
            textSize = 14f
            setTextColor(0xFFE57373.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { discardEdits() }
        }
        val saveAsBtn = TextView(this).apply {
            text = "Save As…"
            textSize = 14f
            setTextColor(0xFF81C784.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { promptSaveAs() }
        }
        val saveBtn = TextView(this).apply {
            text = "Save"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF4CAF50.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { commitSave(overwrite = true) }
        }

        actionRow.addView(cancelBtn)
        actionRow.addView(saveAsBtn)
        actionRow.addView(saveBtn)
        editPanel.addView(actionRow)
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        if (isEditMode) {
            editQuillBtn.setColorFilter(0xFF81C784.toInt())
            bottomBar.visibility = View.GONE
            editPanel.visibility = View.VISIBLE
            zoomImageView.isCropMode = false
        } else {
            if (hasUnsavedEdits) {
                AlertDialog.Builder(this)
                    .setTitle("Discard edits?")
                    .setMessage("You have unsaved edits. Discard them?")
                    .setPositiveButton("Discard") { _, _ ->
                        discardEdits()
                    }
                    .setNegativeButton("Keep editing", null)
                    .show()
                return
            }
            discardEdits()
        }
    }

    private fun discardEdits() {
        isEditMode = false
        hasUnsavedEdits = false
        editQuillBtn.setColorFilter(0xFFFFFFFF.toInt())
        zoomImageView.isCropMode = false
        zoomImageView.brightnessOffset = 0f
        zoomImageView.contrastRatio = 1f
        zoomImageView.saturationRatio = 1f
        zoomImageView.isGrayscale = false
        editPanel.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        zoomImageView.setImageBitmap(originalBitmap)
        zoomImageView.resetZoom()
    }

    private fun showCropOptions() {
        val items = arrayOf("Freeform", "Square (1:1)", "4:3", "16:9", "Apply Crop", "Cancel Crop")
        AlertDialog.Builder(this)
            .setTitle("Crop")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { zoomImageView.isCropMode = true }
                    1 -> { zoomImageView.isCropMode = true; zoomImageView.setCropAspectRatio(1f, 1f) }
                    2 -> { zoomImageView.isCropMode = true; zoomImageView.setCropAspectRatio(4f, 3f) }
                    3 -> { zoomImageView.isCropMode = true; zoomImageView.setCropAspectRatio(16f, 9f) }
                    4 -> {
                        val currentBmp = originalBitmap ?: return@setItems
                        val cropped = zoomImageView.getCroppedBitmap(currentBmp)
                        if (cropped != null) {
                            originalBitmap = cropped
                            zoomImageView.setImageBitmap(cropped)
                            hasUnsavedEdits = true
                        }
                        zoomImageView.isCropMode = false
                        zoomImageView.resetZoom()
                    }
                    5 -> { zoomImageView.isCropMode = false }
                }
            }.show()
    }

    private fun showRotateFlipOptions() {
        val items = arrayOf("Rotate 90° CW", "Rotate 90° CCW", "Flip Horizontal", "Flip Vertical")
        AlertDialog.Builder(this)
            .setTitle("Rotate & Flip")
            .setItems(items) { _, which ->
                val bmp = originalBitmap ?: return@setItems
                val m = Matrix()
                when (which) {
                    0 -> m.postRotate(90f)
                    1 -> m.postRotate(-90f)
                    2 -> m.postScale(-1f, 1f)
                    3 -> m.postScale(1f, -1f)
                }
                val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                originalBitmap = transformed
                zoomImageView.setImageBitmap(transformed)
                zoomImageView.resetZoom()
                hasUnsavedEdits = true
            }.show()
    }

    private fun showAdjustmentSliders() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        fun sliderRow(name: String, min: Int, max: Int, initial: Int, onProgress: (Int) -> Unit) {
            val label = TextView(this).apply { text = "$name: $initial"; textSize = 13f }
            val sb = SeekBar(this).apply {
                this.max = max - min
                progress = initial - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        val actual = value + min
                        label.text = "$name: $actual"
                        onProgress(actual)
                        hasUnsavedEdits = true
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            layout.addView(label)
            layout.addView(sb)
        }

        sliderRow("Brightness", -80, 80, zoomImageView.brightnessOffset.toInt()) {
            zoomImageView.brightnessOffset = it.toFloat()
        }
        sliderRow("Contrast (%)", 50, 250, (zoomImageView.contrastRatio * 100).toInt()) {
            zoomImageView.contrastRatio = it / 100f
        }
        sliderRow("Saturation (%)", 0, 200, (zoomImageView.saturationRatio * 100).toInt()) {
            zoomImageView.saturationRatio = it / 100f
        }

        val grayCheck = CheckBox(this).apply {
            text = "Grayscale"
            isChecked = zoomImageView.isGrayscale
            setOnCheckedChangeListener { _, checked ->
                zoomImageView.isGrayscale = checked
                hasUnsavedEdits = true
            }
        }
        layout.addView(grayCheck)

        AlertDialog.Builder(this)
            .setTitle("Adjust Colors")
            .setView(layout)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showResizeDialog() {
        val bmp = originalBitmap ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val wInput = EditText(this).apply {
            hint = "Width px"
            setText(bmp.width.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val hInput = EditText(this).apply {
            hint = "Height px"
            setText(bmp.height.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val aspectCheck = CheckBox(this).apply {
            text = "Maintain Aspect Ratio"
            isChecked = true
        }

        val origAspect = bmp.width.toFloat() / bmp.height.toFloat()
        wInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (aspectCheck.isChecked && wInput.hasFocus()) {
                    val w = s.toString().toIntOrNull() ?: return
                    hInput.setText((w / origAspect).roundToInt().toString())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        layout.addView(TextView(this).apply { text = "Current: ${bmp.width} × ${bmp.height} px" })
        layout.addView(wInput)
        layout.addView(hInput)
        layout.addView(aspectCheck)

        AlertDialog.Builder(this)
            .setTitle("Resize Image")
            .setView(layout)
            .setPositiveButton("Resize") { _, _ ->
                val newW = wInput.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: return@setPositiveButton
                val newH = hInput.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: return@setPositiveButton
                val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
                originalBitmap = scaled
                zoomImageView.setImageBitmap(scaled)
                zoomImageView.resetZoom()
                hasUnsavedEdits = true
                subtitleView.text = "$newW × $newH px"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun commitSave(overwrite: Boolean) {
        // This editor currently operates on a sampled, single-frame bitmap.
        // Preserve original resolution, animation, vector data, and metadata by exporting a copy.
        if (overwrite) Toast.makeText(this, "Save a PNG copy to preserve the original image", Toast.LENGTH_LONG).show()
        promptSaveAs()
    }

    private fun promptSaveAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, currentName.substringBeforeLast('.') + "_edited.png")
        }
        startActivityForResult(intent, 901)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 901 && resultCode == RESULT_OK) data?.data?.let(::performSave)
    }

    private fun performSave(targetUri: Uri) {
        val bmp = originalBitmap ?: return
        val baos = ByteArrayOutputStream()
        if (targetUri.toString() == currentUri?.toString()) {
            Toast.makeText(this, "Choose a new file to preserve the original image", Toast.LENGTH_LONG).show()
            return
        }
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)) {
            Toast.makeText(this, "Could not encode image; original retained", Toast.LENGTH_LONG).show()
            return
        }
        val data = baos.toByteArray()

        Thread {
            val res = MediaSourceAdapter.saveContent(this, targetUri, data)
            runOnUiThread {
                if (res is ProviderResult.Success) {
                    Toast.makeText(this, "Saved successfully (${data.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                    hasUnsavedEdits = false
                    toggleEditMode()
                } else if (res is ProviderResult.Failure) {
                    Toast.makeText(this, "Failed to save: ${res.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun loadImage(uri: Uri) {
        subtitleView.text = "Loading…"
        val generation = ++imageLoadGeneration
        imageLoadThread?.interrupt()
        imageLoadThread = Thread {
            val frame = try {
                // Decode at high screen resolution (up to 2560px max side) without unbounded RAM exhaustion
                com.infinifolder.preview.CanvasStreams.decodeViewer(contentResolver,
                    MediaSourceAdapter.resource(this, uri), uri, 2560)
            } catch (t: Throwable) {
                null
            }
            val bmp = frame?.bitmap

            runOnUiThread {
                if (isFinishing || isDestroyed || generation != imageLoadGeneration) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (bmp != null) {
                    decodedImageMetadata = frame?.metadata
                    originalBitmap = bmp
                    zoomImageView.setImageBitmap(bmp)
                    zoomImageView.resetZoom()
                    subtitleView.text = "${bmp.width} × ${bmp.height} px"
                    if (!isEditMode && ViewerLaunch.requestsEdit(intent)) toggleEditMode()
                } else {
                    subtitleView.text = "Failed to decode image"
                    Toast.makeText(this, "Cannot decode this image format.", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { start() }
    }

    private fun showMetadataInspector() {
        val uri = currentUri ?: return
        val bmp = originalBitmap
        val infoList = mutableListOf<String>()

        infoList += "Name: $currentName"
        infoList += "MIME: $currentMime"
        if (bmp != null) {
            infoList += "Dimensions: ${bmp.width} × ${bmp.height} px"
            val mp = (bmp.width.toDouble() * bmp.height.toDouble() / 1_000_000.0)
            infoList += "Resolution: %.1f Megapixels".format(mp)
            infoList += "Config: ${bmp.config}"
            com.infinifolder.preview.BitmapFrame(bmp, provenance = decodedImageMetadata?.provenance ?: io.github.gretchycat.infinistream.Provenance()).metadata.attributes["colorSpace"]?.let { infoList += "Color space: $it" }
        }

        Thread {
            try {
                MediaSourceAdapter.openStream(this, uri).use { s ->
                    val exif = ExifInterface(s)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    val date = exif.getAttribute(ExifInterface.TAG_DATETIME)
                    val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)

                    runOnUiThread {
                        if (!make.isNullOrBlank() || !model.isNullOrBlank()) infoList += "Camera: ${make ?: ""} ${model ?: ""}".trim()
                        if (!date.isNullOrBlank()) infoList += "Date: $date"
                        if (!exposure.isNullOrBlank()) infoList += "Exposure: ${exposure}s"
                        if (!fNum.isNullOrBlank()) infoList += "Aperture: f/$fNum"
                        if (!iso.isNullOrBlank()) infoList += "ISO: $iso"
                        if (!focal.isNullOrBlank()) infoList += "Focal Length: ${focal}mm"

                        val latLong = FloatArray(2)
                        if (exif.getLatLong(latLong)) {
                            infoList += "GPS: %.5f, %.5f".format(latLong[0], latLong[1])
                        }

                        displayMetadataDialog(infoList)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { displayMetadataDialog(infoList) }
            }
        }.start()
    }

    private fun displayMetadataDialog(infoList: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Image Details")
            .setItems(infoList.toTypedArray(), null)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadFolderImages() {
        val parent = parentFolderUri ?: return
        Thread {
            val list = MediaSourceAdapter.listFolder(this, parent)
                .filter { MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.IMAGE }
            runOnUiThread {
                folderImages.clear()
                folderImages.addAll(list)
                val targetUriStr = currentUri?.toString()
                currentImageIndex = folderImages.indexOfFirst { it.uri == currentUri || (targetUriStr != null && it.uri.toString() == targetUriStr) }.coerceAtLeast(0)
                updateCounter()
            }
        }.start()
    }

    private fun updateCounter() {
        val counterView = findViewById<TextView>(bottomBar.getChildAt(1).id) ?: return
        if (folderImages.isNotEmpty()) {
            counterView.text = "${currentImageIndex + 1} / ${folderImages.size}"
        } else {
            counterView.text = ""
        }
    }

    private fun toggleSlideshow() {
        isSlideshowPlaying = !isSlideshowPlaying
        if (isSlideshowPlaying) {
            slideshowBtn.text = "⏸ Pause"
            slideshowBtn.setTextColor(0xFF4CAF50.toInt())
            slideshowHandler.postDelayed(slideshowRunnable, 3000L)
        } else {
            stopSlideshow()
        }
    }

    private fun stopSlideshow() {
        isSlideshowPlaying = false
        slideshowBtn.text = "▶ Slideshow"
        slideshowBtn.setTextColor(0xFFFFFFFF.toInt())
        slideshowHandler.removeCallbacks(slideshowRunnable)
    }

    private fun advanceSlideshow() {
        if (folderImages.isEmpty()) return
        if (currentImageIndex < folderImages.size - 1) {
            navigateAdjacent(1)
        } else {
            if (isRepeatEnabled) {
                currentImageIndex = -1
                navigateAdjacent(1)
            } else {
                // Repeat not enabled and finished playing through: auto close!
                stopSlideshow()
                finish()
            }
        }
    }

    private fun navigateAdjacent(delta: Int) {
        if (folderImages.isEmpty()) return
        if (hasUnsavedEdits) {
            Toast.makeText(this, "Please save or discard edits first", Toast.LENGTH_SHORT).show()
            return
        }
        val nextIdx = currentImageIndex + delta
        if (nextIdx in folderImages.indices) {
            currentImageIndex = nextIdx
            val nextEntry = folderImages[nextIdx]
            currentUri = nextEntry.uri
            currentName = nextEntry.metadata.name
            currentMime = nextEntry.metadata.mimeType
            titleView.text = currentName
            updateCounter()
            loadImage(nextEntry.uri)
        } else if (delta > 0) {
            if (isRepeatEnabled) {
                currentImageIndex = 0
                val nextEntry = folderImages[0]
                currentUri = nextEntry.uri
                currentName = nextEntry.metadata.name
                currentMime = nextEntry.metadata.mimeType
                titleView.text = currentName
                updateCounter()
                loadImage(nextEntry.uri)
            } else {
                // If repeat is not on and it finishes playing through: auto close!
                stopSlideshow()
                finish()
            }
        }
    }

    override fun onDestroy() {
        imageLoadGeneration++
        imageLoadThread?.interrupt()
        imageLoadThread = null
        stopSlideshow()
        super.onDestroy()
    }

    private fun openExternally() {
        val uri = currentUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, currentMime.ifEmpty { "image/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open image with…"))
        } catch (_: Exception) {
            Toast.makeText(this, "No external application found for this image.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleBars() {
        isBarsVisible = !isBarsVisible
        val vis = if (isBarsVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
    }

    override fun onBackPressed() {
        com.infinifolder.storage.DiagnosticLog.record("viewer.image.back", "editing=$isEditMode unsaved=$hasUnsavedEdits finishing=$isFinishing")
        stopSlideshow()
        if (isEditMode && hasUnsavedEdits) {
            AlertDialog.Builder(this)
                .setTitle("Discard edits?")
                .setMessage("You have unsaved edits. Discard them?")
                .setPositiveButton("Discard") { _, _ -> returnToFileManager() }
                .setNegativeButton("Keep editing", null)
                .show()
            return
        }
        returnToFileManager()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_MIME = "extra_mime"
        const val EXTRA_PARENT_URI = "extra_parent_uri"
    }
}
