package com.infinifolder.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.MainActivity
import com.infinifolder.FileEntryCard
import com.infinifolder.FileViewGrid
import com.infinifolder.ResourceIconResolver
import com.infinifolder.HomeItemActivity
import com.infinifolder.FileIconView
import com.infinifolder.PullToRefreshScrollView
import com.infinifolder.preview.CustomIconStore
import com.infinifolder.theme.IconThemeManager
import com.infinifolder.theme.ThemeManager
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.storage.StorageRuntime
import java.io.IOException
import kotlin.math.roundToInt

/** Read-only floating browser. It shares the application's storage graph and leaves edits to MainActivity. */
class FloatingContainerService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var runtime: StorageRuntime
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var containerIcon: android.widget.FrameLayout
    private lateinit var title: TextView
    private lateinit var grid: LinearLayout
    private lateinit var scroll: PullToRefreshScrollView
    private var current: Uri? = null
    private var iconDp = 56
    private val icons by lazy { CustomIconStore(filesDir) }
    private val iconTheme by lazy { IconThemeManager(applicationContext) }
    private val iconWorker = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val iconResolver by lazy { ResourceIconResolver(applicationContext) }
    private val iconCache = object : android.util.LruCache<String, ResourceIconResolver.Icon>(8 * 1024) {
        override fun sizeOf(key: String, value: ResourceIconResolver.Icon) = (value.bitmap?.byteCount?.div(1024) ?: 1).coerceAtLeast(1)
    }
    private val pendingIcons = mutableListOf<Pair<View, () -> Unit>>()
    private var renderGeneration = 0
    private var windowView: View? = null
    private var loadGeneration = 0
    private var currentEntries: List<ResourceEntry> = emptyList()
    private var listingReady = false
    private var startX = 0f
    private var startY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var resizeReflowScheduled = false

    override fun onCreate() {
        super.onCreate()
        runtime = StorageRuntime.get(applicationContext)
        ThemeManager.init(applicationContext)
        val defaultIconDp = getSharedPreferences("browser", MODE_PRIVATE).getInt("icon_size_dp", 56)
        iconDp = getSharedPreferences("floating_window", MODE_PRIVATE)
            .getInt("icon_size_dp", defaultIconDp).coerceIn(32, 120)
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data ?: intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps to open this floating container", Toast.LENGTH_LONG).show()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (windowView == null) createWindow()
        show(uri)
        return START_NOT_STICKY
    }

    private fun createWindow() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val theme = ThemeManager.activeTheme
        val background = theme.background
        val foreground = theme.textPrimary
        root = object : FrameLayout(this) {
            private var receivedFocus = false

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) receivedFocus = true
                else if (receivedFocus) stopSelf()
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    stopSelf()
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            this.background = ThemeManager.createFloatingFrameDrawable(theme, density)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(12).toFloat()

        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            this.background = ThemeManager.createToolbarDrawable(theme, density)
            setPadding(dp(8), dp(4), dp(4), dp(4))
        }
        containerIcon = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(TOOLBAR_HEIGHT_DP - 8), -1).apply { marginEnd = dp(6) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        title = TextView(this).apply {
            textSize = TOOLBAR_HEIGHT_DP * 0.38f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextColor(theme.toolbarText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val dragHandle = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    // Use the rendered position, since WindowManager may have constrained it.
                    val position = IntArray(2)
                    root.getLocationOnScreen(position)
                    startWindowX = position[0]; startWindowY = position[1]
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        windows.currentWindowMetrics.bounds
                    } else {
                        android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    }
                    params.x = (startWindowX + (event.rawX - startX).roundToInt())
                        .coerceIn(0, (bounds.width() - root.width).coerceAtLeast(0))
                    params.y = (startWindowY + (event.rawY - startY).roundToInt())
                        .coerceIn(0, (bounds.height() - root.height).coerceAtLeast(0))
                    // Rebase at every event so dragging against an edge creates no overscroll debt.
                    startWindowX = params.x; startWindowY = params.y
                    startX = event.rawX; startY = event.rawY
                    runCatching { windows.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveWindowPlacement()
                    true
                }
                else -> true
            }
        }
        title.setOnTouchListener(dragHandle)
        containerIcon.setOnTouchListener(dragHandle)
        val openFull = openInAppButton {
            current?.let { launchInMain(it, inApp = true) }
        }
        bar.addView(containerIcon); bar.addView(title); bar.addView(openFull)
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll = PullToRefreshScrollView(this).apply {
            isFillViewport = true
            updateHeaderTheme(theme.isDark)
            viewTreeObserver.addOnScrollChangedListener { loadVisibleIcons() }
            viewTreeObserver.addOnGlobalLayoutListener { loadVisibleIcons() }
            // This action belongs to the whole displayed location, even over an icon.
            onGridLongPress = {
                current?.let {
                    launchInMain(it, inApp = true)
                }
            }
            onRefreshListener = { current?.let(::show) }
            addView(grid, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnScaleGestureListener(object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var initialSize = 56
                private var accumulatedScale = 1f
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    initialSize = iconDp
                    accumulatedScale = 1f
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedScale *= detector.scaleFactor
                    val size = (initialSize * accumulatedScale).roundToInt().coerceIn(32, 120)
                    if (kotlin.math.abs(size - iconDp) >= 3) {
                        val position = scrollY
                        iconDp = size
                        getSharedPreferences("floating_window", MODE_PRIVATE).edit()
                            .putInt("icon_size_dp", iconDp).apply()
                        render(currentEntries)
                        post { scrollTo(0, position) }
                    }
                    return true
                }
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, dp(TOOLBAR_HEIGHT_DP)))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        addResizeHandle(left = true)
        addResizeHandle(left = false)
        params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 2 / 3),
            (resources.displayMetrics.heightPixels / 2),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT
            x = (resources.displayMetrics.widthPixels - width - dp(12)).coerceAtLeast(0)
            y = dp(64) }
        restoreWindowPlacement()
        windowView = root
        windows.addView(root, params)
    }

    private fun addResizeHandle(left: Boolean) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val handle = FloatingResizeHandle(this, left).apply { elevation = dp(2).toFloat() }
        var resizing = false
        var pointerX = 0f
        var pointerY = 0f
        var windowX = 0
        var windowY = 0
        var windowWidth = 0
        var windowHeight = 0
        var minimumWidth = 0
        var minimumHeight = 0
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizing = true
                    val position = IntArray(2)
                    root.getLocationOnScreen(position)
                    windowX = position[0]; windowY = position[1]
                    windowWidth = root.width; windowHeight = root.height
                    pointerX = event.rawX; pointerY = event.rawY
                    // Preserve icon scale and enough room for the toolbar and a row.
                    // An already smaller saved window does not jump to a larger size.
                    minimumWidth = minOf(windowWidth, dp(maxOf(160, iconDp + 40)))
                    minimumHeight = minOf(windowHeight, dp(maxOf(120, TOOLBAR_HEIGHT_DP + iconDp + 48)))
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizing) {
                        val bounds = displayBounds()
                        val dx = (event.rawX - pointerX).roundToInt()
                        val dy = (event.rawY - pointerY).roundToInt()
                        val oldColumns = FileViewGrid.columns((params.width / density).roundToInt(), iconDp)
                        val top = windowY.coerceIn(0, (bounds.height() - 1).coerceAtLeast(0))
                        val right = (windowX + windowWidth).coerceIn(1, bounds.width().coerceAtLeast(1))
                        if (left) {
                            val minWidth = minimumWidth.coerceIn(1, right)
                            params.x = (windowX + dx).coerceIn(0, right - minWidth)
                            params.width = right - params.x
                        } else {
                            params.x = windowX.coerceIn(0, (bounds.width() - 1).coerceAtLeast(0))
                            val available = (bounds.width() - params.x).coerceAtLeast(1)
                            params.width = (windowWidth + dx).coerceIn(minimumWidth.coerceIn(1, available), available)
                        }
                        params.y = top
                        val availableHeight = (bounds.height() - top).coerceAtLeast(1)
                        params.height = (windowHeight + dy).coerceIn(minimumHeight.coerceIn(1, availableHeight), availableHeight)
                        runCatching { windows.updateViewLayout(root, params) }
                        // Rebase at limits so reversing direction responds immediately.
                        windowX = params.x; windowY = params.y
                        windowWidth = params.width; windowHeight = params.height
                        pointerX = event.rawX; pointerY = event.rawY
                        val newColumns = FileViewGrid.columns((params.width / density).roundToInt(), iconDp)
                        if (newColumns != oldColumns) reflowAfterResize()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (resizing) {
                        resizing = false
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        reflowAfterResize()
                        // Persist rendered geometry after WindowManager applies the final frame.
                        root.post { saveWindowPlacement() }
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        // A 48dp grab triangle surrounds the visible 32dp handle.
        root.addView(handle, FrameLayout.LayoutParams(dp(48), dp(48),
            Gravity.BOTTOM or if (left) Gravity.LEFT else Gravity.RIGHT))
    }

    private fun reflowAfterResize() {
        if (resizeReflowScheduled) return
        resizeReflowScheduled = true
        root.postOnAnimation {
            resizeReflowScheduled = false
            if (windowView == null || !::scroll.isInitialized || !listingReady) return@postOnAnimation
            val position = scroll.scrollY
            render(currentEntries)
            scroll.post { scroll.scrollTo(0, position) }
        }
    }

    private fun displayBounds(): android.graphics.Rect =
        if (android.os.Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds
        else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

    private fun restoreWindowPlacement() {
        val saved = getSharedPreferences("floating_window", MODE_PRIVATE)
        val bounds = displayBounds()
        val density = resources.displayMetrics.density
        // Store density-independent coordinates; constrain saved geometry after rotation/display changes.
        params.width = (saved.getFloat("width_dp", params.width / density) * density).roundToInt()
            .coerceIn(1, bounds.width().coerceAtLeast(1))
        params.height = (saved.getFloat("height_dp", params.height / density) * density).roundToInt()
            .coerceIn(1, bounds.height().coerceAtLeast(1))
        params.x = (saved.getFloat("x_dp", params.x / density) * density).roundToInt()
            .coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = (saved.getFloat("y_dp", params.y / density) * density).roundToInt()
            .coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
    }

    private fun saveWindowPlacement() {
        if (!::params.isInitialized || !::root.isInitialized || windowView == null) return
        val density = resources.displayMetrics.density
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        getSharedPreferences("floating_window", MODE_PRIVATE).edit()
            .putInt("icon_size_dp", iconDp)
            .putFloat("x_dp", location[0] / density)
            .putFloat("y_dp", location[1] / density)
            .putFloat("width_dp", root.width.takeIf { it > 0 }?.div(density) ?: params.width / density)
            .putFloat("height_dp", root.height.takeIf { it > 0 }?.div(density) ?: params.height / density)
            .apply()
    }

    private fun openInAppButton(action: () -> Unit) = ImageButton(this).apply {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        setImageResource(com.infinifolder.R.drawable.ic_open_in_app)
        val theme = ThemeManager.activeTheme
        val fill = if (theme.isDark) 0xFFE0E8DD.toInt() else 0xFF243323.toInt()
        imageTintList = android.content.res.ColorStateList.valueOf(
            if (theme.isDark) 0xFF142012.toInt() else android.graphics.Color.WHITE
        )
        backgroundTintList = null
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(7).toFloat()
            setStroke(dp(2), if (theme.isDark) android.graphics.Color.WHITE else 0xFF101C0F.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(TOOLBAR_HEIGHT_DP - 8), dp(TOOLBAR_HEIGHT_DP - 8)).apply { marginStart = dp(6) }
        contentDescription = "Open current directory in file manager"
        tooltipText = "Open in file manager"
        minimumWidth = dp(TOOLBAR_HEIGHT_DP - 8)
        minimumHeight = dp(TOOLBAR_HEIGHT_DP - 8)
        setPadding(dp(5), dp(5), dp(5), dp(5))
        setOnClickListener { action() }
    }

    private fun show(uri: Uri) {
        current = uri
        renderGeneration++
        pendingIcons.clear()
        currentEntries = emptyList()
        listingReady = false
        val generation = ++loadGeneration
        title.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Container"
        val headerSize = ((TOOLBAR_HEIGHT_DP - 12) * resources.displayMetrics.density).roundToInt()
        val headerEntry = runtime.provider.refForUri(uri)?.let { ref ->
            ResourceEntry(ref, com.infinifolder.model.ResourceMetadata(title.text.toString(), "", null, null, true), uri)
        }
        val headerBitmap = headerEntry?.let { com.infinifolder.ResourceArtworkCache.peek(it, headerSize) }
        showContainerIcon(headerBitmap?.let { ResourceIconResolver.Icon(it, "📁", title.text.toString(), true) })
        grid.removeAllViews()
        val loading = TextView(this).apply { text = "Loading…"; setTextColor(Color.GRAY); textSize = 14f }
        grid.addView(loading)
        Thread {
            try {
                val ref = runtime.provider.refForUri(uri) ?: throw IOException("No storage provider owns this location")
                headerEntry?.let { entry ->
                    iconResolver.cached(entry, headerSize)?.let { saved ->
                        onMain { if (generation == loadGeneration) showContainerIcon(saved) }
                    }
                }
                when (val result = runtime.provider.list(ref)) {
                    is ProviderResult.Success -> onMain {
                        if (generation == loadGeneration) {
                            title.text = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Container" } ?: "Container"
                            listingReady = true
                            render(result.value)
                        }
                    }.also {
                        com.infinifolder.ResourceIconSidecars.observe(result.value, uri)
                        // Resolve artwork off the UI thread; listing feedback remains immediate.
                        val entry = (runtime.provider.stat(ref) as? ProviderResult.Success)?.value
                        val artwork = entry?.let {
                            runCatching { iconResolver.resolve(it, ((TOOLBAR_HEIGHT_DP - 12) * resources.displayMetrics.density).roundToInt()) }.getOrNull()
                        }
                        onMain { if (generation == loadGeneration) showContainerIcon(artwork) }
                    }
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw IOException("This location cannot be browsed")
                }
            } catch (error: Exception) {
                onMain {
                    if (generation == loadGeneration) {
                        scroll.isRefreshing = false
                        grid.removeAllViews()
                        grid.addView(TextView(this).apply {
                            text = error.message ?: "Could not open container"
                            setTextColor(Color.RED)
                            setPadding(20, 20, 20, 20)
                        })
                    }
                }
            }
        }.start()
    }

    private fun showContainerIcon(artwork: ResourceIconResolver.Icon?) {
        containerIcon.removeAllViews()
        val view = artwork?.bitmap?.let { bitmap ->
            android.widget.ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val padding = (2 * resources.displayMetrics.density).roundToInt()
                setPadding(padding, padding, padding, padding)
            }
        } ?: TextView(this).apply {
            text = artwork?.fallback ?: "📁"
            textSize = TOOLBAR_HEIGHT_DP * 0.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(ThemeManager.activeTheme.textPrimary)
        }
        containerIcon.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    private fun render(entries: List<ResourceEntry>) {
        currentEntries = entries
        com.infinifolder.ResourceIconSidecars.observe(entries, current)
        val iconGeneration = ++renderGeneration
        pendingIcons.clear()
        scroll.isRefreshing = false
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val columns = FileViewGrid.columns((params.width / density).roundToInt(), iconDp)
        val cellWidth = params.width / columns
        val preferences = getSharedPreferences("browser", MODE_PRIVATE)
        val showHidden = preferences.getBoolean("show_hidden", false)
        val sortByName = preferences.getBoolean("sort_name", true)
        val sortKey = preferences.getString("sort_key", if (sortByName) "name" else "modified") ?: "name"
        val descending = preferences.getBoolean("sort_descending", !sortByName)
        val rows = FileViewGrid.sortedEntries(entries, showHidden, sortKey, descending)
        val table = FileViewGrid.build(this, rows, columns) { entry ->
            val card = FileEntryCard(this, entry, true, iconDp, ThemeManager.activeTheme.isDark)
            val icon = card.attachIcon(FileIconView.create(this, entry, entry.metadata, true, iconDp,
                icons, iconTheme, runtime.local, runtime.apps))
            card.addLabels(entry.metadata.name, entry.metadata.isDirectory,
                (cellWidth - 4 * density).roundToInt().coerceAtLeast(36), "")
            val pixelSize = (iconDp * density).roundToInt()
            val cacheKey = "${entry.ref}:${entry.metadata.modifiedAtMillis}:$pixelSize"
            fun applyArtwork(resolved: ResourceIconResolver.Icon) {
                icon.removeViewAt(0)
                val artwork: View = resolved.bitmap?.let { bitmap ->
                    android.widget.ImageView(this).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(bitmap)
                    }
                } ?: TextView(this).apply {
                    text = resolved.fallback
                    textSize = iconDp * .72f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                icon.addView(artwork, 0, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                card.nameView.setFileName(resolved.label, resolved.directory)
            }
            val cached = iconCache.get(cacheKey)
            if (cached != null) applyArtwork(cached)
            var authoritativeApplied = false
            pendingIcons.add(card.container to {
                com.infinifolder.ResourceArtworkCache.reads.execute cacheRead@ {
                    if (iconGeneration != renderGeneration || windowView == null) return@cacheRead
                    iconResolver.cached(entry, pixelSize)?.let { saved ->
                        onMain {
                            if (!authoritativeApplied && iconGeneration == renderGeneration && windowView != null) applyArtwork(saved)
                        }
                    }
                    val sourceWorker = if (com.infinifolder.apps.AppsUri.isAppUri(entry.uri))
                        com.infinifolder.ResourceArtworkCache.appSources else iconWorker
                    sourceWorker.execute sourceRead@ {
                        if (iconGeneration != renderGeneration || windowView == null) return@sourceRead
                        val resolved = runCatching {
                            iconResolver.resolve(entry, pixelSize, deferCacheWrite = true)
                        }.getOrNull()
                        if (resolved != null) {
                            iconCache.put(cacheKey, resolved)
                            onMain {
                                if (iconGeneration == renderGeneration && windowView != null) {
                                    authoritativeApplied = true
                                    applyArtwork(resolved)
                                    iconResolver.cacheDisplayed(entry, pixelSize, resolved)
                                }
                            }
                        }
                    }
                }
            })
            // Only tap-to-open is installed. Movement and per-item menus belong to the full browser.
            card.container.setOnClickListener { activate(entry) }
            card.container
        }
        grid.addView(table)
        grid.post { loadVisibleIcons() }
        if (rows.isEmpty()) grid.addView(TextView(this).apply {
            text = "No files or folders were returned for this location."
            setTextColor(ThemeManager.activeTheme.textPrimary)
            setPadding(18, 28, 18, 12)
        })
    }

    private fun activate(entry: ResourceEntry) {
        when {
            entry.metadata.isDirectory -> show(entry.uri)
            !com.infinifolder.apps.AppsUri.isAppUri(entry.uri) &&
                !ArchiveFormats.isPackagedFormat(entry.metadata.name, entry.metadata.mimeType) &&
                (ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType) || ArchiveFormats.isDiscImage(entry.metadata.name, entry.metadata.mimeType)) -> {
                val mounted = ArchiveUri.toArchiveUri(entry.uri)
                if (runtime.provider.refForUri(mounted) != null) show(mounted) else launchInMain(entry.uri)
            }
            else -> launchInMain(entry.uri)
        }
    }

    private fun launchInMain(uri: Uri, inApp: Boolean = false) {
        val launch = if (inApp) Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_HOME_ITEM_IN_APP).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        else HomeItemActivity.intent(this, uri)
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stopSelf()
    }

    private fun loadVisibleIcons() {
        if (pendingIcons.isEmpty()) return
        val visible = android.graphics.Rect()
        val jobs = pendingIcons.filter { it.first.getGlobalVisibleRect(visible) }
        pendingIcons.removeAll(jobs.toSet())
        jobs.forEach { it.second() }
    }

    override fun onDestroy() {
        saveWindowPlacement()
        loadGeneration++
        renderGeneration++
        pendingIcons.clear()
        iconWorker.shutdownNow()
        windowView?.let { runCatching { windows.removeView(it) } }
        windowView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onMain(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    companion object {
        private const val TOOLBAR_HEIGHT_DP = 39
        const val EXTRA_URI = "com.infinifolder.overlay.URI"
        fun intent(context: android.content.Context, uri: Uri) = Intent(context, FloatingContainerService::class.java).setData(uri)
    }
}
