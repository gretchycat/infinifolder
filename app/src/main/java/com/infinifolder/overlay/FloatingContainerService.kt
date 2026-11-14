package com.infinifolder.overlay

import com.infinifolder.SelectionInitialLocationFallback
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.infinifolder.BrowserLocations
import com.infinifolder.MainActivity
import com.infinifolder.FileEntryCard
import com.infinifolder.FileViewGrid
import com.infinifolder.VirtualizedEntrySurface
import com.infinifolder.ResourceIconResolver
import com.infinifolder.HomeItemActivity
import com.infinifolder.HomeShortcuts
import com.infinifolder.HomeShortcutDragActivity
import com.infinifolder.PinBoardDirectory
import com.infinifolder.FileIconView
import com.infinifolder.PullToRefreshScrollView
import com.infinifolder.theme.ThemeManager
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import com.infinifolder.storage.StorageRuntime
import io.github.gretchycat.infiniselect.FileSelectionContract
import java.io.IOException
import kotlin.math.roundToInt

/** Read-only floating browser. It shares the application's storage graph and leaves edits to MainActivity. */
class FloatingContainerService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var runtime: StorageRuntime
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var containerIcon: android.widget.FrameLayout
    private lateinit var breadcrumbs: LinearLayout
    private lateinit var breadcrumbScroll: HorizontalScrollView
    private lateinit var grid: LinearLayout
    private lateinit var scroll: PullToRefreshScrollView
    private var largeEntrySurface: VirtualizedEntrySurface? = null
    private var current: Uri? = null
    private var currentDisplayName: String? = null
    private data class Crumb(val uri: Uri, val name: String)
    private val trail = mutableListOf<Crumb>()
    private var breadcrumbGeneration = 0L
    private var iconDp = 56
    private var currentListingComplete=true
    private val refreshArtwork=Runnable { if(windowView!=null) {iconCache.evictAll();render(currentEntries)} }
    private var registrySubscription:AutoCloseable?=null
    private val iconPreferencesListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key ->
        if(key in setOf("active_icon_theme","icon_theme_revision","raw_media_metadata_revision"))onMain {refreshArtwork.run()}
    }
    private val iconResolver by lazy { ResourceIconResolver(applicationContext).apply {
        onArtworkChanged={onMain {refreshArtwork.run()}}
    } }
    private val iconCache = object : android.util.LruCache<String, ResourceIconResolver.Icon>(8 * 1024) {
        override fun sizeOf(key: String, value: ResourceIconResolver.Icon) = (value.bitmap?.byteCount?.div(1024) ?: 1).coerceAtLeast(1)
    }
    private val pendingIcons = mutableListOf<Pair<View, () -> Unit>>()
    private var visibleIconsScheduled = false
    private var renderGeneration = 0
    private var windowView: View? = null
    private var loadGeneration = 0
    private var currentEntries: List<ResourceEntry> = emptyList()
    private var listingReady = false
    private var resizeReflowScheduled = false
    private data class DragItem(val card: View, val icon: View, val entry: ResourceEntry)
    private data class LauncherDrag(val token: String, var observer: ResultReceiver? = null)
    private val dragItems = mutableListOf<DragItem>()
    private var launcherDrag: LauncherDrag? = null
    private var systemDialogsRegistered = false
    // Keep API 33 types out of the service's field signatures for API 26 devices.
    private var floatingBackDispatcher: Any? = null
    private var floatingBackCallback: Any? = null
    private var selectionMode = false
    private var selectionAllowMultiple = false
    private var selectionSaveMode = false
    private var selectionSaveFileName: String? = null
    private var selectionRecursiveAudioDirectories = false
    private var selectionMimeTypes: List<String> = emptyList()
    private var selectionExtensions: List<String> = emptyList()
    private var hideUnsupportedSelectionFiles = false
    private var selectionRestoreScrollY: Int? = null
    private var selectionReceiver: ResultReceiver? = null
    @Volatile private var selectionScanning = false
    private data class SelectionParent(val uri: Uri, val name: String, val coverUri: Uri? = null)
    private val selectedEntries = linkedMapOf<String, ResourceEntry>()
    private var selectionDoneView: TextView? = null
    private var normalActionView: View? = null
    private val systemDialogs = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Recents/Home can leave an application overlay focused. Receiving
            // this system event does not request that Android close its own UI.
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) dismissWindow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = StorageRuntime.get(applicationContext)
        registrySubscription=com.infinifolder.preview.IconRegistry.listen(filesDir) {onMain {iconResolver.invalidate();refreshArtwork.run()}}
        getSharedPreferences("browser",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(iconPreferencesListener)
        ThemeManager.init(applicationContext)
        val defaultIconDp = getSharedPreferences("browser", MODE_PRIVATE).getInt("icon_size_dp", 56)
        iconDp = getSharedPreferences("floating_window", MODE_PRIVATE)
            .getInt("icon_size_dp", defaultIconDp).coerceIn(32, 120)
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        ContextCompat.registerReceiver(this, systemDialogs,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_EXPORTED)
        systemDialogsRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        selectionScanning = false
        selectionMode = intent?.getBooleanExtra(EXTRA_SELECTION_MODE, false) == true
        selectionAllowMultiple = intent?.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false) == true
        selectionSaveMode = intent?.getBooleanExtra(EXTRA_SELECTION_SAVE_MODE, false) == true
        selectionSaveFileName = intent?.getStringExtra(FileSelectionContract.EXTRA_SAVE_FILE_NAME)
        selectionRecursiveAudioDirectories = intent?.getBooleanExtra(EXTRA_ALLOW_RECURSIVE_AUDIO_DIRECTORIES, false) == true
        selectionMimeTypes = intent?.getStringArrayListExtra(EXTRA_SELECTION_MIME_TYPES).orEmpty()
        selectionExtensions = intent?.getStringArrayListExtra(EXTRA_SELECTION_EXTENSIONS).orEmpty()
        hideUnsupportedSelectionFiles = intent?.getBooleanExtra(EXTRA_HIDE_UNSUPPORTED, false) == true
        selectionRestoreScrollY = intent?.getIntExtra(EXTRA_SELECTION_SCROLL_Y, 0)?.coerceAtLeast(0)
        selectedEntries.clear()
        selectionReceiver = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_SELECTION_RECEIVER, ResultReceiver::class.java)
        } else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_SELECTION_RECEIVER) as? ResultReceiver)
        val uri = intent?.data ?: intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            if (selectionMode) finishSelection(FileSelectionContract.RESULT_FALLBACK, Bundle().apply {
                putBoolean(FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true)
                putString(FileSelectionContract.EXTRA_FAILURE_MESSAGE, "InfiniFolder could not open its file browser")
            })
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            if (selectionMode) finishSelection(FileSelectionContract.RESULT_FALLBACK, Bundle().apply {
                putBoolean(FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true)
                putString(FileSelectionContract.EXTRA_FAILURE_MESSAGE, "Allow display over other apps to use InfiniFolder's floating selector")
            }) else Toast.makeText(this, "Allow display over other apps to open this floating container", Toast.LENGTH_LONG).show()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            if (windowView == null) createWindow()
            updateSelectionControls()
            show(uri, intent?.getStringExtra(EXTRA_DISPLAY_NAME), resetRoot = true)
        } catch (error: Exception) {
            com.infinifolder.storage.DiagnosticLog.record("floating.selector.initialize-failed", error = error)
            if (selectionMode) finishSelection(FileSelectionContract.RESULT_FALLBACK, Bundle().apply {
                putBoolean(FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true)
                putString(FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                    error.message ?: "InfiniFolder could not initialize its floating selector")
            }) else Toast.makeText(this, error.message ?: "Could not open floating window", Toast.LENGTH_LONG).show()
            removeWindow()
            stopSelf(startId)
        }
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
                else if (receivedFocus) dismissWindow()
            }

            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode in setOf(
                        android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE
                    )) {
                    if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) dismissWindow()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismissWindow()
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            this.background = ThemeManager.createFloatingFrameDrawable(theme, density)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(12).toFloat()
            setOnDragListener { _, event ->
                if (launcherDrag == null) false
                else when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType(HomeShortcutDragActivity.MIME_TYPE) == true
                    DragEvent.ACTION_DRAG_ENDED -> {
                        clearLauncherDrag(cancel = false)
                        dismissWindow()
                        true
                    }
                    else -> false // The Home drop activity owns shortcut creation.
                }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            this.background = ThemeManager.createToolbarDrawable(theme, density)
            setPadding(dp(8), dp(4), dp(4), dp(4))
        }
        containerIcon = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(TOOLBAR_HEIGHT_DP - 8), -1).apply { marginEnd = dp(6) }
            contentDescription = "Window location icon"
        }
        breadcrumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        breadcrumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            addView(breadcrumbs, ViewGroup.LayoutParams(-2, -1))
        }
        // Keep taps and horizontal scrolling on breadcrumb buttons intact. Only blank
        // title-bar space is claimed as a move surface.
        val titleDrag = windowDragTouchListener()
        bar.setOnTouchListener(titleDrag)
        containerIcon.setOnTouchListener(windowDragTouchListener())
        breadcrumbScroll.setOnTouchListener(windowDragTouchListener(acceptDown = { _, event ->
            val contentX = (event.x + breadcrumbScroll.scrollX).toInt()
            val contentY = event.y.toInt()
            (0 until breadcrumbs.childCount).none { index ->
                val child = breadcrumbs.getChildAt(index)
                child.isClickable && contentX >= child.left && contentX < child.right &&
                    contentY >= child.top && contentY < child.bottom
            }
        }))
        val openFull = openInAppButton {
            current?.let { launchInMain(it, inApp = true) }
        }
        normalActionView = openFull
        selectionDoneView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(theme.toolbarText)
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = dp(54)
            visibility = View.GONE
            contentDescription = "Finish selecting files"
            tooltipText = "Finish selecting files"
            setOnClickListener {
                if (selectionSaveMode) completeSaveSelection() else completeMultipleSelection()
            }
        }
        bar.addView(containerIcon); bar.addView(breadcrumbScroll); bar.addView(openFull); bar.addView(selectionDoneView)
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll = PullToRefreshScrollView(this).apply {
            isFillViewport = true
            updateHeaderTheme(theme.isDark)
            viewTreeObserver.addOnScrollChangedListener {
                largeEntrySurface?.updateViewport(scrollY, height)
                loadVisibleIcons()
            }
            viewTreeObserver.addOnGlobalLayoutListener { loadVisibleIcons() }
            onGridLongPressAt = { event ->
                val bounds = android.graphics.Rect()
                val origin = IntArray(2)
                dragItems.lastOrNull { item ->
                    item.card.getLocationOnScreen(origin)
                    item.card.getLocalVisibleRect(bounds) &&
                        bounds.contains((event.rawX - origin[0]).toInt(), (event.rawY - origin[1]).toInt())
                }?.let { item ->
                    if (selectionMode) {
                        if (selectionAllowMultiple && item.entry.metadata.isDirectory && selectionRecursiveAudioDirectories) {
                            createRecursiveAudioPlaylist(item.entry)
                        } else if (selectionAllowMultiple && !item.entry.metadata.isDirectory &&
                            com.infinifolder.storage.LinkResolver.kind(item.entry.metadata) == null) {
                            toggleSelection(item.entry, item.card)
                        }
                        true
                    } else startLauncherDrag(item)
                } ?: selectionMode
            }
            // Empty-space holds still open this location in the full browser.
            onGridLongPress = {
                if (!selectionMode) {
                    current?.let {
                        launchInMain(it, inApp = true)
                    }
                }
            }
            onRefreshListener = { current?.let { show(it, currentDisplayName) } }
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
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        windows.addView(root, params)
        root.requestFocus()
        registerFloatingBackCallback(root)
    }

    /** Gesture Back uses the window dispatcher on Android 13+ instead of KEYCODE_BACK. */
    private fun registerFloatingBackCallback(view: View) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        view.post {
            if (windowView !== view) return@post
            val dispatcher = view.findOnBackInvokedDispatcher() ?: return@post
            val callback = android.window.OnBackInvokedCallback { dismissWindow() }
            runCatching {
                dispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback
                )
            }.onSuccess {
                floatingBackDispatcher = dispatcher
                floatingBackCallback = callback
            }
        }
    }

    private fun unregisterFloatingBackCallback() {
        val dispatcher = floatingBackDispatcher as? android.window.OnBackInvokedDispatcher
        val callback = floatingBackCallback as? android.window.OnBackInvokedCallback
        floatingBackDispatcher = null
        floatingBackCallback = null
        if (android.os.Build.VERSION.SDK_INT >= 33 && dispatcher != null && callback != null) {
            runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
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

    private fun show(uri: Uri, displayName: String? = null, resetRoot: Boolean = false) {
        current = uri
        val headerName = displayName?.takeIf { it.isNotBlank() } ?: containerName(uri)
        currentDisplayName = headerName
        val breadcrumbToken = ++breadcrumbGeneration
        if (resetRoot) {
            trail.clear()
            if (selectionMode) {
                trail.add(Crumb(com.infinifolder.storage.ContainerLinksProvider.ROOT, "Virtual root"))
                if (uri != com.infinifolder.storage.ContainerLinksProvider.ROOT) trail.add(Crumb(uri, headerName))
                resolveSelectionBreadcrumbs(uri, headerName, breadcrumbToken)
            } else trail.add(Crumb(uri, headerName))
        } else {
            val previous = trail.indexOfLast { it.uri == uri }
            if (previous >= 0) {
                while (trail.size > previous + 1) trail.removeAt(trail.lastIndex)
            } else trail.add(Crumb(uri, headerName))
        }
        renderBreadcrumbs()
        renderGeneration++
        pendingIcons.clear()
        currentEntries = emptyList()
        listingReady = false
        val generation = ++loadGeneration
        val headerSize = ((TOOLBAR_HEIGHT_DP - 12) * resources.displayMetrics.density).roundToInt()
        val headerEntry = runtime.provider.refForUri(uri)?.let { ref ->
            ResourceEntry(ref, com.infinifolder.model.ResourceMetadata(headerName, "", null, null, true), uri)
        }
        val providerId = headerEntry?.ref?.providerId
        showContainerIcon(null)
        grid.removeAllViews()
        val loading = TextView(this).apply { text = "Loading…"; setTextColor(Color.GRAY); textSize = 14f }
        grid.addView(loading)
        Thread {
            var phase = "resolve provider"
            try {
                val ref = runtime.provider.refForUri(uri)
                phase = "validate provider"
                val syntheticApplicationPublic = selectionMode && uri == BrowserLocations.uri("application_public")
                if (ref == null && !syntheticApplicationPublic) throw IOException("No storage provider owns this location")
                phase = "read cached folder artwork"
                headerEntry?.let { entry ->
                    iconResolver.cached(entry, headerSize)?.let { saved ->
                        onMain { if (generation == loadGeneration) showContainerIcon(saved) }
                    }
                }
                phase = "list location"
                val listing = when {
                    selectionMode && uri == com.infinifolder.storage.ContainerLinksProvider.ROOT ->
                        ProviderResult.Success(selectionRootEntries())
                    selectionMode && uri == BrowserLocations.uri("application_public") ->
                        ProviderResult.Success(selectionApplicationPublicEntries())
                    else -> runtime.provider.list(requireNotNull(ref))
                }
                phase = "handle listing result"
                when (val result = listing) {
                    is ProviderResult.Success -> onMain {
                        if (generation == loadGeneration) {
                            listingReady = true
                            currentListingComplete=!result.isLoading
                            render(result.value,freshListing=true)
                            restoreSelectionScrollPosition()
                        }
                    }.also {
                        com.infinifolder.ResourceIconSidecars.observe(result.value, uri,complete=!result.isLoading)
                        // Resolve artwork off the UI thread; listing feedback remains immediate.
                        val entry = ref?.let { (runtime.provider.stat(it) as? ProviderResult.Success)?.value }
                        val artwork = entry?.let {
                            runCatching { iconResolver.resolve(it, ((TOOLBAR_HEIGHT_DP - 12) * resources.displayMetrics.density).roundToInt()) }.getOrNull()
                        }
                        onMain { if (generation == loadGeneration) showContainerIcon(artwork) }
                    }
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw IOException("This location cannot be browsed")
                }
            } catch (error: Exception) {
                com.infinifolder.storage.DiagnosticLog.record(
                    "floating.browser.location-load-failed",
                    "location=$uri provider=${providerId ?: "unresolved"} phase=$phase",
                    error,
                )
                onMain {
                    if (generation == loadGeneration) {
                        val virtualRoot = com.infinifolder.storage.ContainerLinksProvider.ROOT
                        if (SelectionInitialLocationFallback.shouldReturnToVirtualRoot(
                                selectionMode = selectionMode,
                                resetRoot = resetRoot,
                                location = uri.toString(),
                                virtualRoot = virtualRoot.toString(),
                            )) {
                            com.infinifolder.storage.DiagnosticLog.record(
                                "floating.selector.initial-location.reset-to-virtual-root"
                            )
                            show(virtualRoot, "Virtual root", resetRoot = true)
                            return@onMain
                        }
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

    /** Add the physical/provider ancestors behind a remembered starting folder. */
    private fun resolveSelectionBreadcrumbs(uri: Uri, displayName: String, token: Long) {
        Thread({
            val route = runCatching { buildSelectionBreadcrumbs(uri, displayName) }
                .getOrDefault(listOf(Crumb(com.infinifolder.storage.ContainerLinksProvider.ROOT, "Virtual root"), Crumb(uri, displayName)))
            onMain {
                if (token != breadcrumbGeneration || !selectionMode || current != uri || windowView == null) return@onMain
                trail.clear()
                trail.addAll(route)
                renderBreadcrumbs()
            }
        }, "InfiniSelectBreadcrumbs").start()
    }

    private fun buildSelectionBreadcrumbs(uri: Uri, displayName: String): List<Crumb> {
        val rootUri = com.infinifolder.storage.ContainerLinksProvider.ROOT
        val virtualRoot = Crumb(rootUri, "Virtual root")
        if (uri == rootUri) return listOf(virtualRoot)
        if (BrowserLocations.find(uri) != null) return listOf(virtualRoot, Crumb(uri, displayName))

        val route = mutableListOf<Crumb>() // current folder toward the provider root
        val visited = hashSetOf<String>()
        var cursorUri = uri
        var cursorName = displayName
        var ref = runtime.provider.refForUri(uri)
        var depth = 0
        while (ref != null && depth++ < 128) {
            val key = "${ref.providerId}:${ref.documentId}"
            if (!visited.add(key)) break
            route += Crumb(cursorUri, cursorName)

            val parentRef = (runtime.provider.parent(ref) as? ProviderResult.Success)?.value
            if (parentRef != null) {
                // ResourceRef.documentId is opaque; stat the parent to obtain its
                // navigable URI and user-facing name.
                val parentEntry = (runtime.provider.stat(parentRef) as? ProviderResult.Success)?.value ?: break
                ref = parentRef
                cursorUri = parentEntry.uri
                cursorName = parentEntry.metadata.name.ifBlank { cursorUri.lastPathSegment ?: "Storage" }
                continue
            }

            // An archive's root is a child of the archive file, whose own parent
            // chain leads back to a virtual-root storage location.
            if (ArchiveUri.isArchiveUri(cursorUri) && ArchiveUri.getEntryPath(cursorUri).isEmpty()) {
                val sourceUri = ArchiveUri.getSourceUri(cursorUri)
                val sourceRef = sourceUri?.let(runtime.provider::refForUri)
                if (sourceUri != null && sourceRef != null) {
                    ref = sourceRef
                    cursorUri = sourceUri
                    cursorName = sourceUri.lastPathSegment ?: "Archive"
                    continue
                }
            }
            break
        }
        if (route.isEmpty()) return listOf(virtualRoot, Crumb(uri, displayName))

        val roots = runCatching { selectionRootEntries() }.getOrDefault(emptyList())
        val matchedRoot = roots.firstOrNull { root -> route.any { sameBreadcrumbUri(it.uri, root.uri) } }
        val rootIndex = matchedRoot?.let { root -> route.indexOfLast { sameBreadcrumbUri(it.uri, root.uri) } } ?: -1
        return if (matchedRoot != null && rootIndex >= 0) {
            buildList {
                add(virtualRoot)
                add(Crumb(matchedRoot.uri, matchedRoot.metadata.name))
                route.take(rootIndex).asReversed().forEach(::add)
            }
        } else listOf(virtualRoot) + route.asReversed()
    }

    private fun sameBreadcrumbUri(first: Uri, second: Uri): Boolean =
        first.normalizeScheme().toString() == second.normalizeScheme().toString()

    private fun renderBreadcrumbs() {
        breadcrumbs.removeAllViews()
        val theme = ThemeManager.activeTheme
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        trail.toList().forEachIndexed { index, crumb ->
            if (index > 0) breadcrumbs.addView(TextView(this).apply {
                text = "›"; textSize = 16f; setTextColor(theme.toolbarText)
                gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14), -1))
            val currentCrumb = index == trail.lastIndex
            breadcrumbs.addView(TextView(this).apply {
                text = crumb.name
                textSize = TOOLBAR_HEIGHT_DP * .38f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1; maxEms = 22
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(5), 0, dp(5), 0)
                setTextColor(theme.toolbarText)
                isSelected = currentCrumb
                contentDescription = when {
                    index == 0 -> "Root container: ${crumb.name}"
                    currentCrumb -> "Current folder: ${crumb.name}"
                    else -> "Go to ${crumb.name}"
                }
                tooltipText = crumb.name
                setOnClickListener { show(crumb.uri, crumb.name) }
                if (currentCrumb) setOnTouchListener(windowDragTouchListener(clickOnTap = true))
            }, LinearLayout.LayoutParams(-2, -1))
        }
        breadcrumbScroll.post { breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun windowDragTouchListener(
        acceptDown: (View, MotionEvent) -> Boolean = { _, _ -> true },
        clickOnTap: Boolean = false
    ): View.OnTouchListener {
        var active = false
        var moved = false
        var startRawX = 0f
        var startRawY = 0f
        var startWindowX = 0
        var startWindowY = 0
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        return View.OnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    active = acceptDown(handle, event)
                    moved = false
                    if (active) {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        // Layout params are in the overlay's TOP|LEFT coordinate space.
                        startWindowX = params.x
                        startWindowY = params.y
                        handle.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    active
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active) return@OnTouchListener false
                    val deltaX = (event.rawX - startRawX).roundToInt()
                    val deltaY = (event.rawY - startRawY).roundToInt()
                    if (!moved && maxOf(kotlin.math.abs(deltaX), kotlin.math.abs(deltaY)) >= touchSlop)
                        moved = true
                    if (moved) {
                        val bounds = displayBounds()
                        params.x = (startWindowX + deltaX)
                            .coerceIn(0, (bounds.width() - root.width).coerceAtLeast(0))
                        params.y = (startWindowY + deltaY)
                            .coerceIn(0, (bounds.height() - root.height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(root, params) }.onFailure { error ->
                            com.infinifolder.storage.DiagnosticLog.record("floating.window.move-failed", error = error)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!active) return@OnTouchListener false
                    active = false
                    handle.parent?.requestDisallowInterceptTouchEvent(false)
                    if (moved) saveWindowPlacement()
                    else if (event.actionMasked == MotionEvent.ACTION_UP && clickOnTap) handle.performClick()
                    true
                }
                else -> active
            }
        }
    }

    /** Preserve the full browser's ordering, then merge in the current virtual-root
     * destinations so newly added shortcuts also appear in a filtered selector. */
    private fun selectionRootEntries(): List<ResourceEntry> {
        val prefs = getSharedPreferences("browser", MODE_PRIVATE)
        val savedRoot = prefs.getString("floating_selector_virtual_root_v1", null)
        val savedEntries = if (savedRoot != null) runCatching {
            val rows = org.json.JSONArray(savedRoot)
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val uri = Uri.parse(row.getString("uri"))
                    val name = row.getString("name")
                    val ref = runtime.provider.refForUri(uri)
                    if (ref == null) {
                        if (BrowserLocations.find(uri) != null) add(ResourceEntry(
                            ResourceRef("browser-location", uri.toString()),
                            ResourceMetadata(name, "vnd.android.document/directory", null, null, true), uri))
                        continue
                    }
                    val entry = if (ref.providerId == runtime.containerLinks.id) {
                        (runtime.containerLinks.stat(ref) as? ProviderResult.Success)?.value
                    } else null
                    add(entry?.copy(metadata = entry.metadata.copy(name = name)) ?: ResourceEntry(
                        ref, ResourceMetadata(name, "vnd.android.document/directory", null, null, true), uri))
                }
            }
        }.onFailure { error ->
            com.infinifolder.storage.DiagnosticLog.record("floating.selector.root-snapshot.invalid", error = error)
        }.getOrNull() else null

        // Build a live root list as well as reading the saved presentation. The snapshot
        // preserves the browser's order, while the live list contributes shortcuts added
        // since the last full-browser render (for example, new virtual locations).
        val result = mutableListOf<ResourceEntry>()
        val seen = mutableSetOf<String>()
        val hidden = prefs.getStringSet("hidden_virtual_root_locations_v1", emptySet()).orEmpty()
        val directoryMime = "vnd.android.document/directory"

        fun visible(key: String) = key !in hidden

        fun location(key: String, name: String, detail: String = ""): ResourceEntry {
            val uri = BrowserLocations.uri(key)!!
            return ResourceEntry(ResourceRef("browser-location", uri.toString()),
                ResourceMetadata(name, directoryMime, null, null, true,
                    readError = detail.takeIf(String::isNotBlank)), uri)
        }

        fun addRoot(uri: Uri?, name: String, detail: String = "") {
            uri ?: return
            val key = uri.normalizeScheme().toString()
            if (!seen.add(key)) return
            val ref = runtime.provider.refForUri(uri) ?: return
            result += ResourceEntry(ref, ResourceMetadata(name, directoryMime, null, null, true,
                readError = detail.takeIf(String::isNotBlank)), uri)
        }

        val localAccess = runtime.local.hasAccess()
        if (visible("internal_shared")) {
            if (localAccess) addRoot(runtime.local.rootUri, "Internal shared storage", "Shared folders")
            else result += location("internal_shared", "Internal shared storage", "Allow storage access to browse shared folders")
        }

        // Include mounted secondary volumes when the app has direct access. SAF-granted
        // roots below cover SD cards and providers that need an explicit tree grant.
        if (localAccess) runCatching {
            getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
                .filterNot { it.isPrimary }
                .forEach { volume ->
                    val directory = if (android.os.Build.VERSION.SDK_INT >= 30) volume.directory else null
                    if (volume.state == Environment.MEDIA_MOUNTED && directory?.canRead() == true) {
                        addRoot(runtime.local.uriFor(directory), volume.getDescription(this))
                    }
                }
        }

        runCatching {
            contentResolver.persistedUriPermissions
                .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
                .forEach { permission ->
                    val uri = permission.uri
                    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
                    if (localAccess && documentId == "primary:") return@forEach
                    val savedName = prefs.getString("source_name_$uri", null)
                    val inferredName = documentId.substringAfter(':').substringAfterLast('/').ifBlank {
                        uri.authority?.substringBefore('.')?.replaceFirstChar(Char::uppercase) ?: "Storage folder"
                    }
                    addRoot(uri, savedName?.takeIf(String::isNotBlank) ?: inferredName)
                }
        }

        // Saved remote connections are shown without probing the network. Connection
        // errors appear only after the user opens a connection.
        runtime.connections.list().filterNot { prefs.getBoolean("root_link_migrated_${it.id}", false) }.forEach { connection ->
            val uri = runCatching { runtime.registry.rootUri(connection) }.getOrNull()
            addRoot(uri, connection.displayName, connection.endpoint.orEmpty())
        }

        if (visible("partial_downloads"))
            result += location("partial_downloads", "Unfinished downloads", "Continuable downloads found while browsing")
        if (visible("external_link_history"))
            result += location("external_link_history", "Recent external links", "Files and URIs opened from other apps")

        runCatching {
            val ref = runtime.containerLinks.refForUri(com.infinifolder.storage.ContainerLinksProvider.ROOT)
                ?: return@runCatching
            (runtime.containerLinks.list(ref) as? ProviderResult.Success)?.value?.let(result::addAll)
        }
        if (visible("pin_board") && localAccess) {
            val pinBoardUri = runtime.local.uriFor(java.io.File(runtime.local.store.root, PinBoardDirectory.RELATIVE_PATH))
            val pinBoardRef = runtime.provider.refForUri(pinBoardUri)
            if (pinBoardRef != null && runtime.provider.stat(pinBoardRef) is ProviderResult.Success)
                addRoot(pinBoardUri, "Pin board")
        } else if (visible("pin_board")) result += location("pin_board", "Pin board", "Requires internal storage access")

        if (visible("application_public"))
            result += location("application_public", "Application public", "Storage exposed by installed apps")
        if (visible("installed_apps")) addRoot(com.infinifolder.apps.AppsUri.ROOT_URI, "Installed Applications")

        val snapshot = savedEntries ?: return result
        val liveByUri = result.associateBy { it.uri.normalizeScheme().toString() }
        val merged = ArrayList<ResourceEntry>(snapshot.size + result.size)
        val included = HashSet<String>(snapshot.size + result.size)
        snapshot.forEach { saved ->
            val locationKey = BrowserLocations.find(saved.uri)?.key
            if (locationKey != null && locationKey in hidden) return@forEach
            val key = saved.uri.normalizeScheme().toString()
            if (included.add(key)) {
                // Prefer the freshly resolved provider reference, retaining the name
                // from the browser snapshot when it carries a user-facing label.
                val current = liveByUri[key]
                merged += if (current != null && saved.metadata.name.isNotBlank()) {
                    current.copy(metadata = current.metadata.copy(name = saved.metadata.name))
                } else current ?: saved
            }
        }
        result.forEach { current ->
            if (included.add(current.uri.normalizeScheme().toString())) merged += current
        }
        return merged
    }

    /** This is the same AppDocumentSources discovery used by the full browser's Application public page. */
    private fun selectionApplicationPublicEntries(): List<ResourceEntry> =
        com.infinifolder.storage.AppDocumentSources(this).discover().mapNotNull { source ->
            val uri = source.granted ?: return@mapNotNull null
            val ref = runtime.provider.refForUri(uri) ?: return@mapNotNull null
            val entry = (runtime.provider.stat(ref) as? ProviderResult.Success)?.value ?: return@mapNotNull null
            entry.copy(metadata = entry.metadata.copy(name = source.title, isDirectory = true))
        }

    private fun containerName(uri: Uri): String {
        var location = uri
        repeat(16) {
            if (!ArchiveUri.isArchiveUri(location))
                return location.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Container"
            ArchiveUri.getEntryPath(location).trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }?.let { return it }
            location = runCatching { ArchiveUri.getSourceUri(location) }.getOrNull() ?: return "Container"
        }
        return "Container"
    }

    private fun showContainerIcon(artwork: ResourceIconResolver.Icon?) {
        containerIcon.removeAllViews()
        val view = artwork?.bitmap?.let { bitmap ->
            com.infinifolder.theme.IconImageView(this).apply {
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

    private fun render(entries: List<ResourceEntry>,freshListing:Boolean=false) {
        if (launcherDrag != null) return // Keep the native drag source attached.
        currentEntries = entries
        val iconGeneration = ++renderGeneration
        val iconIndex = java.util.concurrent.CompletableFuture<Unit>()
        val parent = current
        val complete = currentListingComplete
        grid.postOnAnimation {
            grid.post {
                if (iconGeneration != renderGeneration || windowView == null) iconIndex.complete(Unit)
                else com.infinifolder.ResourceArtworkCache.reads.execute {
                    try {
                        com.infinifolder.ResourceIconSidecars.observe(entries, parent, complete=complete, fresh=freshListing)
                    } catch (error: Exception) {
                        com.infinifolder.storage.DiagnosticLog.record("artwork.floating-index.failed", error=error)
                    } finally {
                        iconIndex.complete(Unit)
                    }
                }
            }
        }
        pendingIcons.clear()
        dragItems.clear()
        scroll.isRefreshing = false
        largeEntrySurface = null
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val columns = FileViewGrid.columns((params.width / density).roundToInt(), iconDp)
        val cellWidth = params.width / columns
        val preferences = getSharedPreferences("browser", MODE_PRIVATE)
        val sortByName = preferences.getBoolean("sort_name", true)
        val sortKey = preferences.getString("sort_key", if (sortByName) "name" else "modified") ?: "name"
        val descending = preferences.getBoolean("sort_descending", !sortByName)
        val visibleRows = if (selectionMode && current == com.infinifolder.storage.ContainerLinksProvider.ROOT)
            entries.filterNot { it.metadata.isHidden }
        else FileViewGrid.sortedEntries(entries, showHidden = false, sortKey, descending)
        // Virtual-root entries are navigation shortcuts (storage, network,
        // applications, and other providers), not candidate media files. Keep
        // every shortcut visible so a filtered viewer can still reach its sources.
        val rows = if (selectionMode && hideUnsupportedSelectionFiles && current != com.infinifolder.storage.ContainerLinksProvider.ROOT)
            visibleRows.filter(::isVisibleInFilteredSelection)
            else visibleRows
        val makeCard: (ResourceEntry) -> View = { entry ->
            val card = FileEntryCard(this, entry, true, iconDp, ThemeManager.activeTheme.isDark)
            val icon = card.attachIcon(FileIconView.createDefault(this, entry, entry.metadata, true, iconDp))
            card.addLabels(entry.metadata.name, entry.metadata.isDirectory,
                (cellWidth - 4 * density).roundToInt().coerceAtLeast(36), "")
            val pixelSize = (iconDp * density).roundToInt()
            fun applyArtwork(resolved: ResourceIconResolver.Icon) {
                icon.removeViewAt(0)
                icon.tag=resolved
                val artwork: View = resolved.bitmap?.let { bitmap ->
                    com.infinifolder.theme.IconImageView(this).apply {
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
            var authoritativeApplied = false
            pendingIcons.add(card.container to {
                iconIndex.whenComplete { _, _ -> com.infinifolder.ResourceArtworkCache.reads.execute cacheRead@ {
                    if (iconGeneration != renderGeneration || windowView == null) return@cacheRead
                    val iconRevision=iconResolver.revision()
                    val cacheKey = "${entry.ref}:${entry.metadata.name}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}:${iconRevision}:$pixelSize"
                    val saved=iconResolver.cached(entry, pixelSize)
                    val cached=saved ?: if(entry.metadata.isDirectory)null else iconCache.get(cacheKey)
                    if(cached!=null) {
                        onMain {
                            if (!authoritativeApplied && iconGeneration == renderGeneration && windowView != null) {
                                authoritativeApplied=saved!=null;applyArtwork(cached)
                            }
                        }
                    }
                    if(saved!=null)return@cacheRead
                    if(entry.metadata.isDirectory) {
                        val baseline=iconResolver.directoryTheme(entry,pixelSize).copy(pending=true)
                        onMain {
                            if(!authoritativeApplied && iconGeneration==renderGeneration && windowView!=null)applyArtwork(baseline)
                        }
                    }
                    val sourceWorker=com.infinifolder.ResourceArtworkCache.sourceExecutor(entry)
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
                                }
                            }
                        }
                    }
                } }
            })
            val dragItem = DragItem(card.container, icon, entry)
            dragItems += dragItem
            val entryKey = entry.uri.toString()
            card.container.alpha = if (entryKey in selectedEntries) 0.58f else 1f
            card.container.setOnClickListener {
                if (ArchiveNavigationPolicy.shouldToggleMultipleSelection(
                        selectionMode = selectionMode,
                        allowMultiple = selectionAllowMultiple,
                        isDirectory = entry.metadata.isDirectory,
                        isBrowsableContainer = isBrowsableSelectionContainer(entry),
                        isLink = com.infinifolder.storage.LinkResolver.kind(entry.metadata) != null,
                    )) toggleSelection(entry, card.container)
                else activate(entry)
            }
            card.container.setOnLongClickListener {
                if (selectionMode) {
                    if (selectionAllowMultiple && entry.metadata.isDirectory && selectionRecursiveAudioDirectories) {
                        createRecursiveAudioPlaylist(entry)
                    } else if (selectionAllowMultiple && !entry.metadata.isDirectory &&
                        com.infinifolder.storage.LinkResolver.kind(entry.metadata) == null) toggleSelection(entry, card.container)
                    true
                } else startLauncherDrag(dragItem)
            }
            card.container
        }
        val table = if (rows.size > 48) VirtualizedEntrySurface(
            this, rows, columns, ((iconDp + 48) * density).roundToInt(), makeCard,
            onCardRemoved = { _, card ->
                pendingIcons.removeAll { it.first === card }
                dragItems.removeAll { it.card === card }
            }
        ).also { largeEntrySurface = it }
        else FileViewGrid.build(this, rows, columns, card=makeCard)
        grid.addView(table)
        grid.post {
            largeEntrySurface?.updateViewport(scroll.scrollY, scroll.height)
            loadVisibleIcons()
        }
        if (rows.isEmpty()) grid.addView(TextView(this).apply {
            text = "No files or folders were returned for this location."
            setTextColor(ThemeManager.activeTheme.textPrimary)
            setPadding(18, 28, 18, 12)
        })
    }

    private fun startLauncherDrag(item: DragItem): Boolean {
        if (launcherDrag != null || windowView == null) return true
        var started = false
        try {
            val manager = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (manager?.isRequestPinShortcutSupported != true) {
                Toast.makeText(this, "This launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show()
                return true
            }
            val label = (item.icon.tag as? ResourceIconResolver.Icon)?.label ?: item.entry.metadata.name.let { name ->
                if (com.infinifolder.shortcut.ShortcutInfo.isShortcut(name, item.entry.metadata.mimeType)) name.substringBeforeLast('.') else name
            }
            // The artwork child excludes the card's transient pressed selector
            // and connection badges, which must not be baked into a launcher icon.
            val artwork = (item.icon as? android.view.ViewGroup)?.getChildAt(0) ?: item.icon
            val shortcut = HomeShortcuts.createFromIcon(this, item.entry, artwork, label)
            val drag = LauncherDrag(java.util.UUID.randomUUID().toString())
            launcherDrag = drag
            val cancellation = object : ResultReceiver(Handler(mainLooper)) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == HomeShortcutDragActivity.ATTACH_OBSERVER) {
                        val observer = androidx.core.os.BundleCompat.getParcelable(resultData ?: Bundle.EMPTY,
                            HomeShortcutDragActivity.EXTRA_OBSERVER, ResultReceiver::class.java)
                        if (launcherDrag === drag) {
                            drag.observer = observer
                            observer?.send(HomeShortcutDragActivity.DRAG_ACTIVE, Bundle.EMPTY)
                        } else observer?.send(HomeShortcutDragActivity.DRAG_ENDED, Bundle.EMPTY)
                    } else if (launcherDrag === drag) {
                        clearLauncherDrag(cancel = true)
                        dismissWindow()
                    }
                }
            }
            // Only an opaque, single-use token crosses the native drag surface;
            // the resource URI and artwork travel in our private activity intent.
            val clip = ClipData(ClipDescription(label, arrayOf(HomeShortcutDragActivity.MIME_TYPE)), ClipData.Item(drag.token))
            started = item.icon.startDragAndDrop(clip, View.DragShadowBuilder(item.icon), null, View.DRAG_FLAG_GLOBAL)
            if (!started) {
                clearLauncherDrag(cancel = false)
                Toast.makeText(this, "Could not start shortcut drag", Toast.LENGTH_SHORT).show()
                return true
            }
            loadGeneration++
            renderGeneration++
            pendingIcons.clear()
            saveWindowPlacement()
            startActivities(arrayOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                HomeShortcutDragActivity.intent(this, shortcut, drag.token, cancellation)
            ))
            // Keep the source window attached for ACTION_DRAG_ENDED while Home
            // receives the drag through the temporary transparent activity.
            root.alpha = 0f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windows.updateViewLayout(root, params)
        } catch (error: Exception) {
            com.infinifolder.storage.DiagnosticLog.record("home.shortcut.drag-failed", error = error)
            clearLauncherDrag(cancel = started)
            if (started) dismissWindow()
            Toast.makeText(this, error.message ?: "Cannot drag shortcut", Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun clearLauncherDrag(cancel: Boolean) {
        val drag = launcherDrag ?: return
        launcherDrag = null
        if (cancel) root.cancelDragAndDrop()
        drag.observer?.send(HomeShortcutDragActivity.DRAG_ENDED, Bundle.EMPTY)
    }

    /** Navigate to a synthetic browser location referenced by a shortcut. */
    private fun navigateSelectionLocation(uri: Uri, name: String): Boolean {
        val location = BrowserLocations.find(uri) ?: return false
        when (location.key) {
            "application_public" -> show(uri, name)
            "partial_downloads" -> show(com.infinifolder.storage.PartialDownloadsProvider.ROOT, name)
            "external_link_history" -> show(com.infinifolder.storage.RecentExternalLinksProvider.ROOT, name)
            "installed_apps" -> show(com.infinifolder.apps.AppsUri.ROOT_URI, name)
            "virtual-root" -> show(com.infinifolder.storage.ContainerLinksProvider.ROOT, name)
            "internal_shared" -> if (runtime.local.hasAccess()) show(runtime.local.rootUri, name)
                else fallbackSelection("This location needs access through InfiniFolder's full browser")
            "pin_board" -> if (runtime.local.hasAccess()) {
                val pinBoardUri = runtime.local.uriFor(java.io.File(runtime.local.store.root, PinBoardDirectory.RELATIVE_PATH))
                show(pinBoardUri, name)
            } else fallbackSelection("This location needs access through InfiniFolder's full browser")
            else -> return false
        }
        return true
    }

    /** Resolve links off the UI thread; SFTP and document providers may perform I/O in resolve(). */
    private fun activateSelectionLink(entry: ResourceEntry) {
        val generation = loadGeneration
        Thread {
            val resolved = com.infinifolder.storage.LinkResolver.resolve(runtime.provider, entry)
            onMain {
                if (!selectionMode || windowView == null || generation != loadGeneration) return@onMain
                when (resolved) {
                    is ProviderResult.Success -> {
                        val target = resolved.value
                        val targetName = target.name?.takeIf { it.isNotBlank() } ?: entry.metadata.name
                        if (navigateSelectionLocation(target.uri, targetName)) return@onMain
                        val targetEntry = target.entry
                        val namedTarget = targetEntry?.let { actual ->
                            actual.copy(metadata = actual.metadata.copy(name = targetName))
                        }
                        when {
                            namedTarget == null -> Toast.makeText(this, "This link does not resolve to a readable file", Toast.LENGTH_SHORT).show()
                            namedTarget.metadata.isDirectory -> show(namedTarget.uri, targetName)
                            browseArchive(namedTarget) { selectEntry(namedTarget) } -> Unit
                            else -> selectEntry(namedTarget)
                        }
                    }
                    is ProviderResult.Failure -> Toast.makeText(this, resolved.message, Toast.LENGTH_SHORT).show()
                    is ProviderResult.Unsupported -> Toast.makeText(this, "This link cannot be opened", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "FloatingSelectionLinkResolve"; priority = Thread.NORM_PRIORITY }.start()
    }

    private fun activate(entry: ResourceEntry) {
        if (selectionMode) {
            val location = BrowserLocations.find(entry.uri)
            if (current == com.infinifolder.storage.ContainerLinksProvider.ROOT && location != null) {
                when (location.key) {
                    "application_public" -> show(entry.uri, entry.metadata.name)
                    "partial_downloads" -> show(com.infinifolder.storage.PartialDownloadsProvider.ROOT, entry.metadata.name)
                    "external_link_history" -> show(com.infinifolder.storage.RecentExternalLinksProvider.ROOT, entry.metadata.name)
                    "installed_apps" -> show(com.infinifolder.apps.AppsUri.ROOT_URI, entry.metadata.name)
                    "virtual-root" -> show(com.infinifolder.storage.ContainerLinksProvider.ROOT, entry.metadata.name)
                    "internal_shared", "pin_board" -> {
                        if (entry.ref.providerId == "browser-location") {
                            fallbackSelection("This location needs access through InfiniFolder's full browser")
                        } else show(entry.uri, entry.metadata.name)
                    }
                    else -> if (entry.metadata.isDirectory) show(entry.uri, entry.metadata.name)
                        else selectEntry(entry)
                }
            } else if (entry.metadata.isDirectory) show(entry.uri, entry.metadata.name)
            else if (browseArchive(entry) {
                    Toast.makeText(this, "This archive cannot be browsed here", Toast.LENGTH_SHORT).show()
                }) Unit
            else if (com.infinifolder.storage.LinkResolver.kind(entry.metadata) != null) {
                activateSelectionLink(entry)
            } else selectEntry(entry)
            return
        }
        when {
            entry.metadata.isDirectory -> show(entry.uri, entry.metadata.name)
            browseArchive(entry) { launchInMain(entry.uri) } -> Unit
            else -> launchInMain(entry.uri)
        }
    }

    /** Opens a supported container in this browser without making callers understand archive URIs. */
    private fun browseArchive(entry: ResourceEntry, onUnavailable: () -> Unit): Boolean {
        if (!ArchiveNavigationPolicy.shouldBrowse(
                name = entry.metadata.name,
                mimeType = entry.metadata.mimeType,
                isApplicationUri = com.infinifolder.apps.AppsUri.isAppUri(entry.uri),
            )) return false

        val mounted = ArchiveUri.toArchiveUri(entry.uri)
        if (runtime.provider.refForUri(mounted) != null) show(mounted, entry.metadata.name)
        else onUnavailable()
        return true
    }

    private fun selectEntry(entry: ResourceEntry) {
        val mime = com.infinifolder.mime.MimeRegistry.effective(
            entry.metadata.name, entry.metadata.mimeType.ifBlank { "application/octet-stream" })
        if (!matchesSelectionFormat(entry, mime)) {
            val accepted = (selectionMimeTypes + selectionExtensions.map { ".$it" }).distinct()
            Toast.makeText(this, "Choose a supported file${accepted.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString()})" }.orEmpty()}", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectionAllowMultiple) {
            toggleSelection(entry)
            return
        }
        val result = Bundle().apply {
            putString(FileSelectionContract.EXTRA_RESULT_URI, entry.uri.toString())
            putString(FileSelectionContract.EXTRA_RESULT_NAME, entry.metadata.name)
            putString(FileSelectionContract.EXTRA_RESULT_MIME, mime)
            entry.metadata.sizeBytes?.let { putLong(FileSelectionContract.EXTRA_RESULT_SIZE, it) }
            addSelectionLocation(this)
        }
        finishSelection(FileSelectionContract.RESULT_SELECTED, result)
        removeWindow()
        stopSelf()
    }

    private fun toggleSelection(entry: ResourceEntry, card: View? = null) {
        val key = entry.uri.toString()
        if (selectedEntries.remove(key) == null) selectedEntries[key] = entry
        card?.alpha = if (key in selectedEntries) 0.58f else 1f
        updateSelectionControls()
    }

    private fun updateSelectionControls() {
        selectionDoneView?.visibility = if (selectionMode && (selectionAllowMultiple || selectionSaveMode)) View.VISIBLE else View.GONE
        selectionDoneView?.text = when {
            selectionSaveMode -> "Save here"
            else -> "Open (${selectedEntries.size})"
        }
        selectionDoneView?.contentDescription = if (selectionSaveMode) "Save in this directory" else "Finish selecting files"
        normalActionView?.visibility = if (selectionMode) View.GONE else View.VISIBLE
    }

    private fun completeSaveSelection() {
        val directory = current
        if (directory == null) {
            Toast.makeText(this, "Choose a directory to save into", Toast.LENGTH_SHORT).show()
            return
        }
        val result = Bundle().apply {
            putString(FileSelectionContract.EXTRA_SAVE_DIRECTORY, directory.toString())
            selectionSaveFileName?.let { putString(FileSelectionContract.EXTRA_SAVE_FILE_NAME, it) }
        }
        finishSelection(FileSelectionContract.RESULT_SELECTED, result)
        removeWindow()
        stopSelf()
    }

    private fun completeMultipleSelection(
        entries: List<ResourceEntry> = selectedEntries.values.toList(),
        parents: Map<String, SelectionParent> = emptyMap(),
    ) {
        if (entries.isEmpty()) {
            Toast.makeText(this, "Select at least one file", Toast.LENGTH_SHORT).show()
            return
        }
        val result = Bundle().apply {
            putStringArrayList(FileSelectionContract.EXTRA_RESULT_URIS, ArrayList(entries.map { it.uri.toString() }))
            putStringArrayList(FileSelectionContract.EXTRA_RESULT_NAMES, ArrayList(entries.map { it.metadata.name }))
            putStringArrayList(FileSelectionContract.EXTRA_RESULT_MIMES, ArrayList(entries.map {
                it.metadata.mimeType.ifBlank { "application/octet-stream" }
            }))
            putLongArray(FileSelectionContract.EXTRA_RESULT_SIZES,
                entries.map { it.metadata.sizeBytes ?: -1L }.toLongArray())
            putStringArrayList(FileSelectionContract.EXTRA_ITEM_DIRECTORY_URIS,
                ArrayList(entries.map { parents[it.uri.toString()]?.uri?.toString().orEmpty() }))
            putStringArrayList(FileSelectionContract.EXTRA_ITEM_DIRECTORY_NAMES,
                ArrayList(entries.map { parents[it.uri.toString()]?.name.orEmpty() }))
            putStringArrayList(FileSelectionContract.EXTRA_ITEM_DIRECTORY_COVER_URIS,
                ArrayList(entries.map { parents[it.uri.toString()]?.coverUri?.toString().orEmpty() }))
            addSelectionLocation(this)
        }
        selectionScanning = false
        finishSelection(FileSelectionContract.RESULT_SELECTED, result)
        removeWindow()
        stopSelf()
    }

    /** Long-pressing a folder in an audio multi-select request creates a recursive playlist. */
    private fun createRecursiveAudioPlaylist(root: ResourceEntry) {
        if (!selectionMode || !selectionAllowMultiple || !selectionRecursiveAudioDirectories) return
        selectionScanning = true
        reportSelectionProgress(FileSelectionContract.RESULT_LOADING_STARTED, root.metadata.name, 0, 0)
        removeWindow()
        Thread {
            val scanStartedAt = android.os.SystemClock.elapsedRealtime()
            val found = linkedMapOf<String, ResourceEntry>()
            val parents = linkedMapOf<String, SelectionParent>()
            val visited = hashSetOf<com.infinifolder.model.ResourceRef>()
            val queue = ArrayDeque<Pair<ResourceEntry, Int>>()
            queue.add(root to 0)
            var foldersRead = 0
            var foldersFailed = 0
            var truncated = false
            var lastProgressAt = System.currentTimeMillis()
            while (queue.isNotEmpty() && selectionMode && selectionScanning && !Thread.currentThread().isInterrupted) {
                val (folder, depth) = queue.removeFirst()
                if (depth > 128 || !visited.add(folder.ref)) { truncated = true; continue }
                if (++foldersRead > 50_000) { truncated = true; break }
                val folderEntries = linkedMapOf<String, ResourceEntry>()
                var listAttempt = 0
                var listingComplete = false
                var listingFailed = false
                while (selectionScanning && listAttempt < MAX_SELECTION_LIST_ATTEMPTS &&
                    !Thread.currentThread().isInterrupted) {
                    listAttempt++
                    try {
                        when (val result = runtime.provider.list(folder.ref)) {
                            is ProviderResult.Success -> {
                                if (!result.isLoading) {
                                    // A completed result is authoritative; discard any
                                    // stale rows accumulated from earlier partial polls.
                                    folderEntries.clear()
                                    result.value.forEach { folderEntries[it.uri.toString()] = it }
                                    listingComplete = true
                                    break
                                } else {
                                    result.value.forEach { folderEntries[it.uri.toString()] = it }
                                }
                            }
                            is ProviderResult.Failure, is ProviderResult.Unsupported -> {
                                listingFailed = true
                                break
                            }
                        }
                    } catch (error: Exception) {
                        listingFailed = true
                        com.infinifolder.storage.DiagnosticLog.record(
                            "infiniselect.audio-folder.list-failed", "folder=${folder.metadata.name}", error)
                        break
                    }
                    if (listAttempt < MAX_SELECTION_LIST_ATTEMPTS && selectionScanning) {
                        try {
                            Thread.sleep((100L * listAttempt).coerceAtMost(700L))
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                if (!listingComplete || listingFailed) foldersFailed++
                val entries = folderEntries.values.toList()
                if (entries.isNotEmpty()) {
                    // Keep only a URI to the likely cover image. Do not open or
                    // decode it during the directory scan.
                    val directoryCoverUri = entries.asSequence()
                                .filter { !it.metadata.isDirectory && !it.metadata.isSymbolicLink &&
                                    !it.metadata.isBrokenLink && it.metadata.canRead }
                                .filter { it.metadata.name.startsWith("cover.", ignoreCase = true) }
                                .filter {
                                    it.metadata.mimeType.startsWith("image/", ignoreCase = true) ||
                                        com.infinifolder.mime.MimeRegistry.guess(it.metadata.name)
                                            .startsWith("image/", ignoreCase = true)
                                }
                                .sortedWith(compareBy<ResourceEntry>({ it.metadata.name.lowercase(java.util.Locale.ROOT) },
                                    { it.metadata.name }))
                                .firstOrNull()?.uri
                    for (entry in entries) {
                        if (found.size >= 50_000) { truncated = true; break }
                        if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink && !entry.metadata.isBrokenLink) {
                            queue.add(entry to depth + 1)
                        } else if (!entry.metadata.isDirectory && !entry.metadata.isSymbolicLink &&
                            com.infinifolder.storage.LinkResolver.kind(entry.metadata) == null &&
                            !ArchiveFormats.isPlaylist(entry.metadata.name, entry.metadata.mimeType)) {
                            val mime = com.infinifolder.mime.MimeRegistry.effective(
                                entry.metadata.name, entry.metadata.mimeType.ifBlank { "application/octet-stream" })
                            if (matchesSelectionFormat(entry, mime)) {
                                val key = entry.uri.toString()
                                if (found.putIfAbsent(key, entry) == null) {
                                    parents[key] = SelectionParent(folder.uri, folder.metadata.name, directoryCoverUri)
                                }
                            }
                        }
                    }
                }
                if (listingFailed) {
                    com.infinifolder.storage.DiagnosticLog.record(
                        "infiniselect.audio-folder.list-incomplete", "folder=${folder.metadata.name} attempts=$listAttempt entries=${folderEntries.size}")
                } else if (!listingComplete) {
                    com.infinifolder.storage.DiagnosticLog.record(
                        "infiniselect.audio-folder.list-still-loading", "folder=${folder.metadata.name} attempts=$listAttempt entries=${folderEntries.size}")
                }
                val now = System.currentTimeMillis()
                if (foldersRead == 1 || foldersRead % 16 == 0 || now - lastProgressAt >= 500L) {
                    reportSelectionProgress(FileSelectionContract.RESULT_LOADING_PROGRESS,
                        root.metadata.name, foldersRead, found.size)
                    lastProgressAt = now
                }
                if (truncated) break
            }
            onMain {
                if (!selectionMode || selectionReceiver == null) return@onMain
                if (found.isEmpty()) {
                    reportSelectionProgress(FileSelectionContract.RESULT_LOADING_FAILED,
                        "No supported audio files were found", foldersRead, 0)
                    selectionScanning = false
                    finishSelection(FileSelectionContract.RESULT_CANCELLED, Bundle())
                    removeWindow()
                    stopSelf()
                    return@onMain
                }
                if (foldersFailed > 0 || truncated) {
                    val detail = buildList {
                        if (foldersFailed > 0) add("$foldersFailed folders could not be fully read")
                        if (truncated) add("scan limit reached")
                    }.joinToString("; ")
                    reportSelectionProgress(FileSelectionContract.RESULT_LOADING_PROGRESS,
                        "Using ${found.size} files; $detail", foldersRead, found.size)
                }
                completeMultipleSelection(found.values.toList(), parents)
                val directoryCount = parents.values.asSequence().map { it.uri.toString() }.distinct().count()
                com.infinifolder.storage.DiagnosticLog.record(
                    "infiniselect.audio-folder.complete",
                    "folders=$foldersRead directories=$directoryCount tracks=${found.size} partialFolders=$foldersFailed truncated=$truncated elapsedMs=${android.os.SystemClock.elapsedRealtime() - scanStartedAt}")
            }
        }.apply { name = "InfiniSelectAudioFolderScan"; priority = Thread.NORM_PRIORITY }.start()
    }

    private fun reportSelectionProgress(code: Int, label: String, folders: Int, tracks: Int) {
        selectionReceiver?.send(code, Bundle().apply {
            putString(FileSelectionContract.EXTRA_PROGRESS_LABEL, label)
            putInt(FileSelectionContract.EXTRA_PROGRESS_FOLDERS, folders)
            putInt(FileSelectionContract.EXTRA_PROGRESS_TRACKS, tracks)
        })
    }

    private fun matchesMime(pattern: String, mime: String): Boolean {
        val p = pattern.trim().lowercase()
        val m = mime.trim().lowercase()
        return p == "*/*" || p == m || (p.endsWith("/*") && m.startsWith(p.substringBefore('/') + "/"))
    }

    private fun addSelectionLocation(result: Bundle) {
        current?.let { result.putString(FileSelectionContract.EXTRA_RESULT_DIRECTORY, it.toString()) }
        if (::scroll.isInitialized) result.putInt(FileSelectionContract.EXTRA_RESULT_SCROLL_Y, scroll.scrollY)
    }

    private fun cancelSelection() {
        if (!selectionMode) return
        finishSelection(FileSelectionContract.RESULT_CANCELLED, Bundle().apply { addSelectionLocation(this) })
    }

    private fun restoreSelectionScrollPosition() {
        val target = selectionRestoreScrollY ?: return
        if (!selectionMode) return
        scroll.post {
            scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (!scroll.isAttachedToWindow || scroll.height == 0) return
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    scroll.scrollTo(0, target)
                    selectionRestoreScrollY = null
                }
            })
        }
    }

    /** Directories and real containers remain visible so a filtered picker can browse inside them. */
    private fun isVisibleInFilteredSelection(entry: ResourceEntry): Boolean {
        if (entry.metadata.isDirectory) return true
        val mime = com.infinifolder.mime.MimeRegistry.effective(
            entry.metadata.name, entry.metadata.mimeType.ifBlank { "application/octet-stream" })
        return isBrowsableSelectionContainer(entry) || matchesSelectionFormat(entry, mime)
    }

    private fun isBrowsableSelectionContainer(entry: ResourceEntry): Boolean =
        ArchiveNavigationPolicy.shouldBrowse(
            name = entry.metadata.name,
            mimeType = entry.metadata.mimeType,
            isApplicationUri = com.infinifolder.apps.AppsUri.isAppUri(entry.uri),
        )

    private fun matchesSelectionFormat(entry: ResourceEntry, mime: String): Boolean {
        val hasMimeFilter = selectionMimeTypes.any { it.isNotBlank() && it != "*/*" }
        val hasExtensionFilter = selectionExtensions.isNotEmpty()
        if (!hasMimeFilter && !hasExtensionFilter) return true
        val mimeMatch = selectionMimeTypes.any {
            it.isNotBlank() && (it != "*/*" || !hasExtensionFilter) && matchesMime(it, mime)
        }
        val name = entry.metadata.name.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        val extensionMatch = selectionExtensions.any { extension ->
            val normalized = extension.trim().removePrefix(".").lowercase(java.util.Locale.ROOT)
            normalized.isNotEmpty() && (name == normalized || name.endsWith(".$normalized"))
        }
        return mimeMatch || extensionMatch
    }

    private fun finishSelection(code: Int, data: Bundle) {
        val receiver = selectionReceiver ?: return
        selectionReceiver = null
        selectionScanning = false
        selectionScanning = false
        selectionMode = false
        selectionAllowMultiple = false
        selectionSaveMode = false
        selectionSaveFileName = null
        selectionRecursiveAudioDirectories = false
        selectionMimeTypes = emptyList()
        selectionExtensions = emptyList()
        hideUnsupportedSelectionFiles = false
        selectionRestoreScrollY = null
        selectedEntries.clear()
        receiver.send(code, data)
    }

    private fun fallbackSelection(message: String) {
        finishSelection(FileSelectionContract.RESULT_FALLBACK, Bundle().apply {
            putBoolean(FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true)
            putString(FileSelectionContract.EXTRA_FAILURE_MESSAGE, message)
        })
        removeWindow()
        stopSelf()
    }

    private fun launchInMain(uri: Uri, inApp: Boolean = false) {
        val launch = if (inApp) Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_HOME_ITEM_IN_APP).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        else HomeItemActivity.intent(this, uri)
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        dismissWindow()
    }

    private fun loadVisibleIcons() {
        if (pendingIcons.isEmpty() || visibleIconsScheduled) return
        visibleIconsScheduled = true
        scroll.postOnAnimation {
            scroll.post {
                visibleIconsScheduled = false
                if (windowView != null) {
                    val visible = android.graphics.Rect()
                    val jobs = pendingIcons.filter { it.first.getGlobalVisibleRect(visible) }
                    pendingIcons.removeAll(jobs.toSet())
                    jobs.forEach { it.second() }
                }
            }
        }
    }

    private fun dismissWindow() {
        if (selectionScanning) return
        // Going to Home deliberately changes focus during a shortcut drag. Its
        // activity/native drag callbacks handle cancellation and final dismissal.
        if (windowView == null || launcherDrag != null) return
        cancelSelection()
        removeWindow()
        stopSelf()
    }

    private fun removeWindow() {
        val view = windowView ?: return
        unregisterFloatingBackCallback()
        saveWindowPlacement()
        // Invalidate work before detaching: removal can deliver focus callbacks.
        windowView = null
        loadGeneration++
        renderGeneration++
        pendingIcons.clear()
        dragItems.clear()
        view.visibility = View.GONE
        runCatching { windows.removeView(view) }.onFailure {
            com.infinifolder.storage.DiagnosticLog.record("floating.dismiss.failed", error = it)
        }
    }

    override fun onDestroy() {
        cancelSelection()
        if (systemDialogsRegistered) {
            unregisterReceiver(systemDialogs)
            systemDialogsRegistered = false
        }
        clearLauncherDrag(cancel = true)
        removeWindow()
        registrySubscription?.close()
        getSharedPreferences("browser",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(iconPreferencesListener)
        iconResolver.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onMain(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    companion object {
        private const val TOOLBAR_HEIGHT_DP = 39
        private const val MAX_SELECTION_LIST_ATTEMPTS = 8
        const val EXTRA_URI = "com.infinifolder.overlay.URI"
        const val EXTRA_DISPLAY_NAME = "com.infinifolder.overlay.DISPLAY_NAME"
        const val EXTRA_SELECTION_MODE = "com.infinifolder.overlay.SELECTION_MODE"
        const val EXTRA_SELECTION_SAVE_MODE = "com.infinifolder.overlay.SELECTION_SAVE_MODE"
        const val EXTRA_ALLOW_MULTIPLE = "com.infinifolder.overlay.ALLOW_MULTIPLE"
        const val EXTRA_ALLOW_RECURSIVE_AUDIO_DIRECTORIES = "com.infinifolder.overlay.ALLOW_RECURSIVE_AUDIO_DIRECTORIES"
        const val EXTRA_SELECTION_RECEIVER = "com.infinifolder.overlay.SELECTION_RECEIVER"
        const val EXTRA_SELECTION_MIME_TYPES = "com.infinifolder.overlay.SELECTION_MIME_TYPES"
        const val EXTRA_SELECTION_EXTENSIONS = "com.infinifolder.overlay.SELECTION_EXTENSIONS"
        const val EXTRA_HIDE_UNSUPPORTED = "com.infinifolder.overlay.HIDE_UNSUPPORTED"
        const val EXTRA_SELECTION_SCROLL_Y = "com.infinifolder.overlay.SELECTION_SCROLL_Y"
        fun intent(context: android.content.Context, uri: Uri) = Intent(context, FloatingContainerService::class.java).setData(uri)
        fun intent(context: android.content.Context, uri: Uri, displayName: String?) = intent(context, uri)
            .putExtra(EXTRA_DISPLAY_NAME, displayName)
    }
}
