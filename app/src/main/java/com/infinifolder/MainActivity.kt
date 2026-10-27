package com.infinifolder

import android.app.Activity
import android.app.ProgressDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.infinifolder.archive.MountedPartitionException
import com.infinifolder.archive.readBytes
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import java.util.Locale
import java.util.UUID
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.os.storage.StorageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.ContextThemeWrapper
import kotlin.math.roundToInt
import android.util.TypedValue
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.core.content.FileProvider
import kotlin.math.abs
import com.infinifolder.apps.AppsStorageProvider
import com.infinifolder.apps.AppsUri
import com.infinifolder.apps.InstalledAppItem
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveStorageProvider
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.archive.ArchiveWriter
import com.infinifolder.viewer.*
import com.infinifolder.sftp.SftpAuthType
import com.infinifolder.sftp.SftpConfig
import com.infinifolder.sftp.SftpCredentials
import com.infinifolder.sftp.SftpProviderFactory
import com.infinifolder.sftp.SftpStorageProvider
import com.infinifolder.sftp.SftpUri
import com.infinifolder.ftp.FtpConfig
import com.infinifolder.ftp.FtpCredentials
import com.infinifolder.ftp.FtpProviderFactory
import com.infinifolder.ftp.FtpStorageProvider
import com.infinifolder.ftp.FtpUri
import com.infinifolder.smb.DiscoveredSmbServer
import com.infinifolder.smb.SmbConfig
import com.infinifolder.smb.SmbCredentials
import com.infinifolder.smb.SmbDiscoveryService
import com.infinifolder.smb.SmbProviderFactory
import com.infinifolder.smb.SmbStorageProvider
import com.infinifolder.smb.SmbUri
import com.infinifolder.webdav.WebDavStorageProvider
import com.infinifolder.webdav.WebDavCredentials
import com.infinifolder.webdav.WebDavUri
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.ContainerUris
import com.infinifolder.storage.ContainerLinksProvider
import com.infinifolder.shortcut.WebMetadataResolver
import java.io.File
import com.infinifolder.model.*
import com.infinifolder.preview.CustomIconStore
import com.infinifolder.preview.DirectoryIconResolver
import com.infinifolder.preview.ImageFormats
import com.infinifolder.preview.ThumbnailDecoder
import com.infinifolder.ops.AppOperations
import com.infinifolder.ops.OperationState
import com.infinifolder.ops.OperationSnapshot
import com.infinifolder.ops.FileOperations
import com.infinifolder.ops.OperationService
import com.infinifolder.ops.OperationUpdate
import com.infinifolder.theme.IconThemeManager
import com.infinifolder.theme.IconThemeInfo
import android.os.Build
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageAction
import com.infinifolder.storage.StorageActionPolicy
import com.infinifolder.storage.DestinationAssessment
import com.infinifolder.storage.StorageProviderRegistry
import com.infinifolder.storage.FileMagicDetector
import com.infinifolder.storage.FileMagicResult
import com.infinifolder.storage.SafProviderFactory
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.LocalStorageProvider
import com.infinifolder.storage.RoutingStorageProvider
import com.infinifolder.storage.AppDocumentSources
import com.infinifolder.storage.matchesDocumentGrant
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity(), DebugDumpObserver {
    private data class BrowserDragPayload(
        val entries: List<ResourceEntry>,
        val canMove: Boolean,
        val sourceDirectory: Uri?,
        val sourceScrollY: Int,
        val sourceDisplayName: String?,
        val createLinks: Boolean = false
    )

    private class DragHoldIndicatorView(context: android.content.Context) : View(context) {
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            val themedAccent = resolveAccent(context)
            // 25% transparent: keep the theme accent legible over the icon beneath it.
            color = (themedAccent and 0x00FFFFFF) or 0xBF000000.toInt()
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            val themedAccent = resolveAccent(context)
            color = (themedAccent and 0x00FFFFFF) or 0xBF000000.toInt()
        }
        var progress: Float = -1f
            set(value) {
                field = if (value < 0f) -1f else value.coerceIn(0f, 1f)
                visibility = if (field < 0f) View.GONE else View.VISIBLE
                invalidate()
            }

        init {
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (progress < 0f) return
            val diameter = width.coerceAtMost(height).toFloat()
            borderPaint.strokeWidth = (diameter * 0.035f).coerceAtLeast(2f)
            val inset = borderPaint.strokeWidth / 2f
            val ring = RectF((width - diameter) / 2f + inset, (height - diameter) / 2f + inset,
                (width + diameter) / 2f - inset, (height + diameter) / 2f - inset)
            if (progress > 0f) canvas.drawArc(ring, -90f, 360f * progress, true, progressPaint)
            canvas.drawCircle(ring.centerX(), ring.centerY(), ring.width() / 2f - borderPaint.strokeWidth / 2f, borderPaint)
        }

        companion object {
            private fun resolveAccent(context: android.content.Context): Int {
                val value = TypedValue()
                return if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
                    if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
                } else 0xFF4CAF50.toInt()
            }
        }
    }

    private class SnapshotDragShadowBuilder(private val snapshot: Bitmap) : View.DragShadowBuilder() {
        override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
            size.set(snapshot.width, snapshot.height)
            touch.set(snapshot.width / 2, snapshot.height / 2)
        }
        override fun onDrawShadow(canvas: Canvas) { canvas.drawBitmap(snapshot, 0f, 0f, null) }
    }

    companion object {
        const val SAFETY_NOTIFICATION_CHANNEL_ID = "infinifolder_safety_channel"
        const val NOTIFICATION_ID_MOUNTED_BLOCKED = 3001
        const val ACTION_HOME_ITEM = "com.infinifolder.action.OPEN_HOME_ITEM"
        const val ACTION_HOME_ITEM_IN_APP = "com.infinifolder.action.OPEN_HOME_ITEM_IN_APP"

        private var applicationStorage: StorageProvider? = null
        fun resourceRef(uri: Uri): ResourceRef = applicationStorage?.refForUri(uri)
            ?: throw IllegalArgumentException("No storage provider owns $uri")

    }
    private val io = com.infinifolder.storage.StorageActivity.loadingExecutor(Executors.newFixedThreadPool(4))
    private val accessIo = com.infinifolder.storage.StorageActivity.loadingExecutor(Executors.newFixedThreadPool(2))
    @Volatile private var visibleArtworkUris: Set<Uri> = emptySet()
    private var artworkRenderGeneration = 0
    private val resourceIconResolver by lazy { ResourceIconResolver(applicationContext) }
    private val imageIo = com.infinifolder.storage.StorageActivity.loadingExecutor(Executors.newFixedThreadPool(4))
    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    /** Preview keys the platform decoders could not read; avoids re-reading unsupported files on every render. */
    private val failedThumbnails = LruCache<String, Boolean>(512)
    /** Maps directory keys to their resolved .icon.* or cover.* URI string, or "" if none exists. */
    private val directoryIconCache = LruCache<String, String>(512)
    /** Caches parsed shortcut (.lnk) metadata by entry URI string. */
    private val shortcutCache = LruCache<String, ShortcutInfo>(2048)
    /** Caches detected file magic results for ambiguous or extensionless entries. */
    private val ambiguousMagicCache = LruCache<String, com.infinifolder.storage.FileMagicResult>(1024)
    /** Holds entries copied to the clipboard in this session. */
    private var cutClipboardToken: String? = null
    private var copiedEntries: List<ResourceEntry>? = null

    private data class PendingCardProbe(
        val view: View,
        val generation: Int,
        val startProbe: () -> Unit
    )
    private val pendingCardProbes = mutableListOf<PendingCardProbe>()
    private var visibleProbeScheduled = false
    private val customIconStore by lazy { CustomIconStore(filesDir) }
    private val iconThemeManager by lazy { IconThemeManager(applicationContext) }
    private val thumbnailDecoder by lazy {
        ThumbnailDecoder(contentResolver) { uri ->
            when {
                ArchiveUri.isArchiveUri(uri) -> archiveProvider.openStream(uri)
                SftpUri.isSftpUri(uri) -> (sftpProvider.openRead(ResourceRef("sftp", uri.toString())) as? ProviderResult.Success)?.value
                FtpUri.isFtpUri(uri) -> (ftpProvider.openRead(ResourceRef("ftp", uri.toString())) as? ProviderResult.Success)?.value
                SmbUri.isSmbUri(uri) -> (smbProvider.openRead(ResourceRef("smb", uri.toString())) as? ProviderResult.Success)?.value
                WebDavUri.owns(uri) -> (webDavProvider.openRead(ResourceRef("webdav", uri.toString())) as? ProviderResult.Success)?.value
                uri.scheme == "file" -> uri.path?.let { File(it).inputStream() }
                ::provider.isInitialized -> provider.refForUri(uri)?.let { (provider.openRead(it) as? ProviderResult.Success)?.value }
                else -> null
            }
        }
    }
    private lateinit var provider: StorageProvider
    private var activeDragOverlayHost: ViewGroup? = null
    private var activeDragOverlay: BitmapDrawable? = null
    private val activeDragSourceIconAlphas = mutableMapOf<View, Float>()
    private var activeDragLocationEvents = 0
    private var activeDragDropHandled = false
    private var activeBrowserDragPayload: BrowserDragPayload? = null
    private var pendingDragSelfDropMenuEntries: List<ResourceEntry>? = null
    private var pendingLocationScrollRestore: Pair<Uri, Int>? = null
    private var dragTreeRevealRunnable: Runnable? = null
    private var dragTreeExpandRunnable: Runnable? = null
    private var dragTreeExpandKey: String? = null
    private var dragContainerOpenRunnable: Runnable? = null
    private var dragContainerOpenView: View? = null
    private val dragContainerCards = java.util.WeakHashMap<View, ResourceEntry>()
    private data class DragNavigationTarget(val destination: Uri, val name: String, val sourceUri: Uri,
        val kind: String, val open: () -> Unit)
    private val dragNavigationTargets = java.util.WeakHashMap<View, DragNavigationTarget>()
    private data class PinnedContainer(val entry: ResourceEntry, val destination: Uri)
    private val pinnedContainers = mutableListOf<PinnedContainer>()
    private lateinit var pinnedContainerBar: HorizontalScrollView
    private lateinit var pinnedContainerContent: LinearLayout
    private var renderedPinnedIconSize = 0
    private var dragHoveredTreeRow: View? = null
    private var dragHoveredTreeRowSelected = false
    private var dragHoveredTreeNodeKey: String? = null
    private var lastDragScreenPosition: Pair<Float, Float>? = null
    private var browserEntryHoldArmed = false
    private var dragAutoScrollRunnable: Runnable? = null
    private var dragAutoScrollLastFrameMs = 0L
    private var dragAutoScrollTarget: ScrollView? = null
    private lateinit var localProvider: LocalStorageProvider
    private lateinit var archiveProvider: ArchiveStorageProvider
    private lateinit var appsProvider: AppsStorageProvider
    private lateinit var sftpProvider: SftpStorageProvider
    private lateinit var ftpProvider: FtpStorageProvider
    private lateinit var smbProvider: SmbStorageProvider
    private lateinit var webDavProvider: WebDavStorageProvider
    private val pickSftpFileRequest = 9005
    private var pendingSftpFileCallback: ((String) -> Unit)? = null
    private var internalSharedEntries = emptyList<ResourceEntry>()
    private var internalListGeneration = 0
    private val pendingInternalTreeRefreshCallbacks = mutableListOf<() -> Unit>()
    private var installedAppCategories = emptyList<String>()
    private var appsListGeneration = 0
    private var waitingForStorageAccess = false
    private var hadStorageAccess = false
    private lateinit var providerRegistry: StorageProviderRegistry
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var createButton: TextView
    private lateinit var createLinkButton: TextView
    private lateinit var floatingModeButton: TextView
    private lateinit var content: LinearLayout
    private lateinit var browserArea: LinearLayout
    private lateinit var fileScroll: PullToRefreshScrollView
    private lateinit var fileScrollContainer: FrameLayout
    private var pinchIndicator: TextView? = null
    private lateinit var treePanel: LinearLayout
    private lateinit var treeContent: LinearLayout
    private lateinit var treeScrollView: ScrollView
    private lateinit var breadcrumb: LinearLayout
    private var browserPanePagerGestureInProgress = false
    private var browserPaneTransitionInProgress = false
    private var browserPaneTransitionGeneration = 0
    private lateinit var status: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var shared: android.content.SharedPreferences
    private val operations = AppOperations.coordinator
    private val history = mutableListOf<Uri>()
    private val historyNames = mutableListOf<String>()
    private val knownResourceParents = android.util.LruCache<Uri, Uri>(8192)
    private var backNavigation: java.io.Closeable? = null
    private var navigatingUp = false
    private var browserNavigationGeneration = 0L
    private var current: Uri? = null
    @Volatile private var loadGeneration = 0
    private var entries = emptyList<ResourceEntry>()
    private val containerLinkProvider get() = com.infinifolder.storage.StorageRuntime.get(this).containerLinks
    private val commonLocations get() = com.infinifolder.storage.StorageRuntime.get(this).commonLocations
    @Volatile private var rootContainerLinks = emptyList<Pair<ResourceEntry, ShortcutInfo>>()
    private var homeSourcesGeneration = 0
    private val entryIconViews = mutableMapOf<Uri, View>()
    private var renderedFileLocation: Uri? = null
    private var listingIsLoading = false
    private var openingTorrentDirectory = false
    private var openingVirtualDirectoryLabel: String? = null
    private var entryActionLoading = false
    private var storageOperationLoading = false
    private var entryActionLoadingMessage = ""
    private var entryActionLoadingToken = 0
    private val activeEntryActionMessages = linkedMapOf<Int, String>()
    private var entryActionPreviousStatus = ""
    private var listingNotice: String? = null
    private var listingError: String? = null
    private var grid = true
    private var dark = false
    private var sortByName = true
    private var sortKey = "name"
    private var sortDescending = false
    private var showHidden = false
    private var useBuiltInViewersAndPlayers = true
    private val themeListener = object : com.infinifolder.theme.ThemeChangeListener {
        override fun onThemeChanged(theme: com.infinifolder.theme.ThemeColors) {
            runOnUiThread {
                dark = theme.isDark
                buildUi()
                if (homeMode) showHomeRoot() else current?.let { load(it) }
            }
        }
    }
    private var selectionMode = false
    private val selectedUris = mutableSetOf<Uri>()
    private var diskUsageExplorer: DiskUsageExplorer? = null
    private lateinit var paneSwitchButton: TextView
    private lateinit var operationLight: OperationActivityLight
    private val storageActivityListener: () -> Unit = {
        if (::operationLight.isInitialized) operationLight.postInvalidateOnAnimation()
    }
    private val operationCompletionListener: (OperationSnapshot) -> Unit = { op ->
        if (::operationLight.isInitialized) operationLight.completed(op)
    }
    private lateinit var unhideOperationBar: OperationStatusBar
    private val operationWindowQueue = ArrayDeque<String>()
    private var isOperationWindowHidden = false
    private var browserResumed = false
    private var pendingOperationsIntent = false
    private var currentProgressDialog: android.app.AlertDialog? = null
    private var operationOverview: OperationOverviewDialog? = null
    private var autoHideOperationOverview = false
    private var progressDestinationView: TextView? = null
    private var activeDialogOpId: String? = null
    private var progressTitleView: TextView? = null
    private var progressQueueView: TextView? = null
    private var progressStatusView: TextView? = null
    private var progressItemNameView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressFileBar: ProgressBar? = null
    private var progressFileLabel: TextView? = null
    private var progressBytesView: TextView? = null
    private var progressVerificationCheck: CheckBox? = null
    private var progressRateView: TextView? = null
    private var progressTorrentStatsView: TextView? = null
    private var progressTorrentWaitCheck: CheckBox? = null
    private var progressRetryButton: Button? = null
    private val retryingTorrentDestinations = mutableSetOf<ResourceRef>()
    private val restartingOperations = mutableSetOf<String>()
    private val stoppingOperations = mutableSetOf<String>()
    private val torrentTransferPreferences by lazy {
        getSharedPreferences(com.infinifolder.torrent.TorrentTransferPreferences.NAME, MODE_PRIVATE)
    }
    private var progressCancelButton: Button? = null
    private var progressHideButton: Button? = null
    private val refreshedTerminalOperations = mutableSetOf<String>()
    private var pendingMinimizeAfterNotificationPermission = false
    private var iconSizeDp = 56
    private var treeVisible = false
    private var homeMode = false
    private var homePage = "root"
    private var logicalLocationPrefix: String? = null
    private var currentProgramName: String? = null
    private val treeRoots = mutableListOf<TreeNode>()
    private val homeItems = mutableListOf<HomeItem>()
    private val applicationPublicItems = mutableListOf<HomeItem>()
    private data class ExternalLinkVisit(val uri: String, val title: String, val openedAt: Long)
    private enum class LinkNavigationOrigin { DIRECTORY_LINK, VIRTUAL_ROOT, EXTERNAL_HISTORY }
    private val externalLinkHistoryKey = "external_link_history_v1"
    private val externalLinkSchemes = setOf("ftp", "ftps", "sftp", "ssh", "sshfs", "smb", "webdav", "dav", "davs", "magnet", "torrent")
    private val pickTreeRequest = 4101
    private val pickCustomIconRequest = 4103
    private val pickThemeArchiveRequest = 4104
    private val pickWinampSkinRequest = 4105
    private var pendingTreeSourceKey: String? = null
    private var pendingTreeTarget: Uri? = null
    private var pendingTreeAuthority: String? = null
    private var pendingIconTargetType: String? = null
    private var pendingIconTargetUri: Uri? = null
    private var pendingIconTargetMime: String? = null
    private var pendingCreateLinkIconCallback: ((Bitmap, String) -> Unit)? = null
    private var applicationSourceGeneration = 0
    private var permissionQueryGeneration = 0
    private var accessRequestGeneration = 0
    private var activePickerRerender: (() -> Unit)? = null
    private var activePropertiesIconRefresh: (() -> Unit)? = null
    private val externalStorageDocumentsAuthority = "com.android.externalstorage.documents"

    private data class HomeItem(
        val icon: String,
        val title: String,
        val detail: String,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null,
        val treeKey: String? = null,
        val treeUri: Uri? = null,
        val treeExpandable: Boolean = false,
        val showInHome: Boolean = true,
        val initialUri: Uri? = null,
        val treeParentKey: String? = null,
        val needsAccessBadge: Boolean = false,
        val providerAuthority: String? = null,
        val linkEntry: ResourceEntry? = null
    )
    private inner class TreeBranchView(
        context: android.content.Context,
        private val depth: Int,
        private val ancestorContinues: List<Boolean>,
        private val isLast: Boolean
    ) : View(context) {
        private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF778273.toInt() else 0xFF9A9D94.toInt()
            strokeWidth = dp(1).toFloat()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            // Each nesting step matches the 24 dp distance between disclosure-box centers.
            val cell = dp(24).toFloat()
            ancestorContinues.forEachIndexed { index, continues ->
                if (continues) {
                    val x = cell * (index + 1)
                    canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
                }
            }
            if (depth > 0) {
                // This stem is centered under the direct parent's disclosure box.
                val parentX = cell * depth
                val middle = height / 2f
                canvas.drawLine(parentX, 0f, parentX, middle, linePaint)
                if (!isLast) canvas.drawLine(parentX, middle, parentX, height.toFloat(), linePaint)
            }
        }
    }
    private inner class TreeDisclosureConnector(
        context: android.content.Context,
        private val hasParent: Boolean,
        private val hasVisibleChildren: Boolean,
        private val hasDisclosureBox: Boolean = true
    ) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF778273.toInt() else 0xFF9A9D94.toInt()
            strokeWidth = dp(1).toFloat()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val y = height / 2f
            val boxSize = dp(18).toFloat()
            if (hasParent) {
                // The short leader ends at the box edge when a disclosure box is present,
                // or extends to the box right edge for leaf action/category nodes.
                val endX = if (hasDisclosureBox) {
                    ((width - boxSize) / 2f).coerceAtLeast(0f)
                } else {
                    ((width + boxSize) / 2f).coerceAtMost(width.toFloat())
                }
                canvas.drawLine(0f, y, endX, y, paint)
            }
            if (hasVisibleChildren) {
                canvas.drawLine(width / 2f, y, width / 2f, height.toFloat(), paint)
            }
        }
    }
    private var forwardingHomeShortcut = false
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.action == ACTION_HOME_ITEM) {
            forwardingHomeShortcut = true
            setTheme(R.style.HomeShortcutTheme)
            super.onCreate(savedInstanceState)
            intent.data?.let { startActivity(HomeItemActivity.intent(this, it)) }
            finish()
            overridePendingTransition(0, 0)
            return
        }
        super.onCreate(savedInstanceState)
        backNavigation = ActivityBackNavigation.register(this) { onBackPressed() }
        com.infinifolder.viewer.DebugTools.install(application)
        initSafetyNotificationChannel()
        com.infinifolder.ops.OperationFeedback.install(application)
        com.infinifolder.ops.OperationFeedback.addListener(operationCompletionListener)
        com.infinifolder.storage.StorageActivity.addListener(storageActivityListener)
        operations.addListener(operationsListener)
        shared = getSharedPreferences("browser", MODE_PRIVATE)
        isOperationWindowHidden = savedInstanceState?.getBoolean("operation_window_hidden", false) ?: false
        grid = shared.getBoolean("grid", true); sortByName = shared.getBoolean("sort_name", true)
        sortKey = shared.getString("sort_key", if (sortByName) "name" else "modified") ?: "name"
        sortDescending = shared.getBoolean("sort_descending", !sortByName)
        showHidden = shared.getBoolean("show_hidden", false)
        useBuiltInViewersAndPlayers = shared.getBoolean("use_builtin_viewers_and_players", true)
        com.infinifolder.theme.ThemeManager.init(applicationContext)
        dark = com.infinifolder.theme.ThemeManager.activeTheme.isDark
        com.infinifolder.theme.ThemeManager.addListener(themeListener)
        treeVisible = shared.getBoolean("tree_visible", false)
        iconSizeDp = shared.getInt("icon_size_dp", 56).coerceIn(32, 120)
        val runtime = com.infinifolder.storage.StorageRuntime.get(applicationContext)
        localProvider = runtime.local
        archiveProvider = runtime.archives
        appsProvider = runtime.apps
        sftpProvider = runtime.sftp
        ftpProvider = runtime.ftp
        smbProvider = runtime.smb
        webDavProvider = runtime.webdav
        providerRegistry = runtime.registry
        provider = runtime.provider
        applicationStorage = provider
        hadStorageAccess = localProvider.hasAccess()
        waitingForStorageAccess = savedInstanceState?.getBoolean("storage_access_pending", false) ?: false
        pendingTreeSourceKey = savedInstanceState?.getString("pending_tree_source")
        pendingTreeTarget = savedInstanceState?.getString("pending_tree_target")?.let(Uri::parse)
        pendingTreeAuthority = savedInstanceState?.getString("pending_tree_authority")
        pendingIconTargetType = savedInstanceState?.getString("pending_icon_target_type")
        pendingIconTargetUri = savedInstanceState?.getString("pending_icon_target_uri")?.let(Uri::parse)
        pendingIconTargetMime = savedInstanceState?.getString("pending_icon_target_mime")
        buildUi()
        if (savedInstanceState == null) io.execute {
            val copies = com.infinifolder.storage.SaveRecovery.pending(File(filesDir, "save_recovery"))
            val edits = com.infinifolder.storage.ExternalEdits(this).pending()
            if (copies.isNotEmpty() || edits.isNotEmpty()) runOnUiThread {
                if (!isFinishing) {
                    val choices = mutableListOf<String>()
                    if (edits.isNotEmpty()) choices.add("Unfinished external edits (${edits.size})")
                    if (copies.isNotEmpty()) choices.add("Interrupted-save recovery copies (${copies.size})")
                    dialogBuilder().setTitle("Saved work available")
                        .setItems(choices.toTypedArray()) { _, index ->
                            val target = if (choices[index].startsWith("Unfinished"))
                                com.infinifolder.viewer.ExternalEditActivity::class.java else com.infinifolder.viewer.SaveRecoveryActivity::class.java
                            startActivity(Intent(this, target))
                        }.setNegativeButton("Later", null).show()
                }
            }
        }
        shared.edit().remove("tree_source_installed_apps").apply()
        logicalLocationPrefix = savedInstanceState?.getString("logical_location_prefix")
        currentProgramName = savedInstanceState?.getString("current_program_name")
        showHomeRoot()
        val saved = savedInstanceState?.getStringArrayList("history")?.map(Uri::parse)
        if (!saved.isNullOrEmpty()) {
            history.addAll(saved)
            val names = savedInstanceState.getStringArrayList("history_names")
            if (names != null && names.size == saved.size) historyNames.addAll(names) else historyNames.addAll(saved.mapIndexed { index, _ -> if (index == 0) "Storage" else "Folder" })
            current = history.last(); homeMode = false; updateCreateAction(); renderLocation(); load(current!!)
        } else shared.getString("root", null)?.let {
            val savedRoot = Uri.parse(it)
            if (AppsUri.isAppsUri(savedRoot)) {
                shared.edit().remove("root").apply()
            } else {
                openRoot(savedRoot)
            }
        }
        handleCacheCopyRequest(intent)
        handleExternalLinkIntent(intent)
        handleHomeItemIntent(intent)
        if (intent.getBooleanExtra("show_operations", false)) {
            intent.removeExtra("show_operations")
            pendingOperationsIntent = true
            window.decorView.post {
                if (browserResumed && pendingOperationsIntent && !isFinishing && !isDestroyed) {
                    pendingOperationsIntent = false
                    showOperations()
                }
            }
        }
        io.execute { HomeShortcuts.refreshPinned(applicationContext) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_HOME_ITEM) {
            // Older pins may still target an existing browser task. Hide it before
            // handing off, so it cannot draw under the transparent dispatcher.
            window.decorView.visibility = View.INVISIBLE
            intent.data?.let { startActivity(HomeItemActivity.intent(this, it)) }
            finish()
            overridePendingTransition(0, 0)
            return
        }
        setIntent(intent)
        handleCacheCopyRequest(intent)
        handleExternalLinkIntent(intent)
        handleHomeItemIntent(intent)
        if (intent.getBooleanExtra("show_operations", false)) {
            intent.removeExtra("show_operations")
            pendingOperationsIntent = true
            window.decorView.post {
                if (browserResumed && pendingOperationsIntent && !isFinishing && !isDestroyed) {
                    pendingOperationsIntent = false
                    showOperations()
                }
            }
        }
    }

    private fun handleHomeItemIntent(request: Intent?) {
        if (request?.action == ACTION_HOME_ITEM) {
            val target = request.data
            request.action = null
            target?.let { startActivity(HomeItemActivity.intent(this, it)) }
            return
        }
        if (request?.action != ACTION_HOME_ITEM_IN_APP) return
        val uri = request.data ?: return
        request.action = null
        io.execute {
            try {
                val entry = provider.refForUri(uri)?.let { (provider.stat(it) as? ProviderResult.Success)?.value }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when {
                        entry == null -> openContainerLinkUri(uri, null, requireContainer = false, origin = LinkNavigationOrigin.EXTERNAL_HISTORY)
                        entry.metadata.isDirectory -> navigate(entry.uri, displayName = entry.metadata.name)
                        else -> open(entry)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, error.message ?: "Could not open home-screen item", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun handleExternalLinkIntent(request: Intent?) {
        if (request?.action != Intent.ACTION_VIEW) return
        val uri = request.data ?: return
        if (uri.scheme in setOf("content", "file")) {
            val ref = provider.refForUri(uri)
            if (ref != null) resumeIncompleteDownload(ref)
            return
        }
        if (uri.scheme?.lowercase(Locale.ROOT) !in externalLinkSchemes) return
        val title = uri.host ?: "${uri.scheme?.uppercase(Locale.ROOT) ?: "External"} link"
        openContainerLinkUri(uri, title, requireContainer = true, origin = LinkNavigationOrigin.EXTERNAL_HISTORY)
    }

    private fun externalLinkVisits(): List<ExternalLinkVisit> = runCatching {
        val array = JSONArray(shared.getString(externalLinkHistoryKey, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ExternalLinkVisit(uri, item.optString("title", "External link"), item.optLong("openedAt"))
        }.sortedByDescending { it.openedAt }
    }.getOrDefault(emptyList())

    private fun recordExternalLinkVisit(rawUri: Uri, title: String) {
        val safeUri = if (rawUri.isHierarchical && rawUri.userInfo != null)
            rawUri.buildUpon().encodedAuthority(rawUri.encodedAuthority?.substringAfter('@')).build()
        else rawUri
        val visits = (externalLinkVisits().filterNot { it.uri == safeUri.toString() } +
            ExternalLinkVisit(safeUri.toString(), title.ifBlank { "External link" }, System.currentTimeMillis()))
            .takeLast(100).asReversed()
        val array = JSONArray()
        visits.forEach { visit -> array.put(JSONObject().put("uri", visit.uri).put("title", visit.title).put("openedAt", visit.openedAt)) }
        shared.edit().putString(externalLinkHistoryKey, array.toString()).apply()
        com.infinifolder.storage.DiagnosticLog.record("external-link.open", "scheme=${safeUri.scheme} title=$title uri=$safeUri")
        if (homeMode && homePage == "external_link_history") runOnUiThread { renderHomeItems() }
    }

    private fun removeExternalLinkVisit(uri: String) {
        val array = JSONArray()
        externalLinkVisits().filterNot { it.uri == uri }.forEach { visit ->
            array.put(JSONObject().put("uri", visit.uri).put("title", visit.title).put("openedAt", visit.openedAt))
        }
        shared.edit().putString(externalLinkHistoryKey, array.toString()).apply()
        renderHomeItems()
    }

    private fun showExternalLinkHistory() {
        loadGeneration++
        homeMode = true
        listingIsLoading = false
        cancelEntryActionLoading()
        updateEntryLoadingFeedback()
        homePage = "external_link_history"
        logicalLocationPrefix = null
        currentProgramName = null
        updateCreateAction()
        renderLocation()
        status.text = "Links opened from other apps and recently browsed container links"
        renderHomeItems()
    }

    private fun handleCacheCopyRequest(request: Intent?) {
        val raw = request?.getStringExtra(com.infinifolder.viewer.CacheFailureUi.COPY_SOURCE_EXTRA) ?: return
        request.removeExtra(com.infinifolder.viewer.CacheFailureUi.COPY_SOURCE_EXTRA)
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        io.execute {
            val result = runCatching {
                val ref = provider.refForUri(uri) ?: throw java.io.IOException("No provider owns this source")
                when (val stat = provider.stat(ref)) {
                    is ProviderResult.Success -> stat.value
                    is ProviderResult.Failure -> throw java.io.IOException(stat.message, stat.cause)
                    else -> throw java.io.IOException("Cannot inspect source")
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) result.onSuccess { source ->
                    showBatchDestinationPicker(listOf(source), false)
                }.onFailure { error ->
                    dialogBuilder().setTitle("Cannot copy file").setMessage(error.message).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun buildUi() {
        val theme = com.infinifolder.theme.ThemeManager.activeTheme
        val bg = theme.background
        val fg = theme.textPrimary
        val isThemeDark = theme.isDark
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT >= 26) window.decorView.systemUiVisibility = if (isThemeDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val horizontalInset = dp(16); val topInset = dp(10); val bottomInset = dp(8)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(horizontalInset, topInset, horizontalInset, bottomInset)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                } else null
                val left = safe?.left ?: insets.systemWindowInsetLeft
                val top = safe?.top ?: insets.systemWindowInsetTop
                val right = safe?.right ?: insets.systemWindowInsetRight
                val bottom = safe?.bottom ?: insets.systemWindowInsetBottom
                view.setPadding(horizontalInset + left, topInset + top, horizontalInset + right, bottomInset + bottom)
                insets
            }
        }
        val density = resources.displayMetrics.density
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            background = com.infinifolder.theme.ThemeManager.createToolbarDrawable(theme, density)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        button("☰", fg) { showOptions() }
        paneSwitchButton = button(if (treeVisible) "Files" else "Tree", fg) { showSidebar() }
        createButton = button("＋", fg) { onCreateAction() }
        createLinkButton = button("🔗+", fg) { showCreateUriLinkDialog() }
        button("↻", fg) { refreshCurrentLocation() }
        button(if (grid) "☷" else "▦", fg) { grid = !grid; shared.edit().putBoolean("grid", grid).apply(); renderCurrentContents() }
        floatingModeButton = button("▣", fg) { showCurrentDirectoryFloating() }.apply {
            contentDescription = "Open current directory in floating window"
            tooltipText = contentDescription
        }
        button("⋮", fg) { showOptions() }
        DebugTools.attachToolbarButton(this, toolbar)
        operationLight = OperationActivityLight(this).apply {
            setOnClickListener { showOperations() }
        }
        // Keep the activity light on the right while controls stay reachable on narrow screens.
        val toolbarStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = com.infinifolder.theme.ThemeManager.createToolbarDrawable(theme, density)
        }
        toolbar.background = null
        toolbarStrip.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(toolbar, ViewGroup.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbarStrip.addView(operationLight, LinearLayout.LayoutParams(dp(32), dp(44)).apply {
            marginStart = dp(4); marginEnd = dp(4)
        })
        root.addView(toolbarStrip)
        pinnedContainerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pinnedContainerBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(pinnedContainerContent, ViewGroup.LayoutParams(-2, -2))
            visibility = View.GONE
        }
        root.addView(pinnedContainerBar, LinearLayout.LayoutParams(-1, -2))
        renderPinnedContainers()
        breadcrumb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(breadcrumb, android.view.ViewGroup.LayoutParams(-2, -2))
        })
        unhideOperationBar = OperationStatusBar(this).apply {
            visibility = View.GONE
            setOnClickListener { unhideOperationWindow() }
        }
        updateOperationVisibilityUi()
        status = TextView(this).apply {
            textSize = 13f; setTextColor(theme.textSecondary); setPadding(dp(4), dp(4), dp(4), dp(8))
            setOnClickListener {
                if (operations.activeSnapshots().isNotEmpty()) {
                    unhideOperationWindow()
                }
            }
        }
        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginStart = dp(4)
                marginEnd = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(loadingIndicator)
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        })
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isLongClickable = true
            setOnLongClickListener { onEmptyAreaLongPress() }
        }
        fileScroll = PullToRefreshScrollView(this).apply {
            isFillViewport = true
            isLongClickable = true
            setOnLongClickListener { onEmptyAreaLongPress() }
            updateHeaderTheme(dark)
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            viewTreeObserver.addOnScrollChangedListener { scheduleVisibleProbe() }
            viewTreeObserver.addOnGlobalLayoutListener { scheduleVisibleProbe() }
            onRefreshListener = {
                if (homeMode) showHomeRoot() else current?.let { load(it) }
            }
            setOnScaleGestureListener(object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var startSizeDp = 0f
                private var accumulatedScale = 1f
                private var lastAppliedSize = 0
                private var didSwitch = false

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    startSizeDp = iconSizeDp.toFloat()
                    accumulatedScale = 1f
                    lastAppliedSize = iconSizeDp
                    didSwitch = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedScale *= detector.scaleFactor
                    if (grid) {
                        val targetSize = (startSizeDp * accumulatedScale).roundToInt()
                        if (targetSize in 32..120) {
                            if (targetSize != iconSizeDp && Math.abs(targetSize - lastAppliedSize) >= 3) {
                                val savedScrollY = fileScroll.scrollY
                                iconSizeDp = targetSize
                                lastAppliedSize = targetSize
                                shared.edit().putInt("icon_size_dp", iconSizeDp).apply()
                                renderCurrentContents()
                                fileScroll.post { fileScroll.scrollTo(0, savedScrollY) }
                                showPinchIndicator("Icon size: ${iconSizeDp} dp")
                            }
                        } else if (targetSize < 24 && !didSwitch) {
                            didSwitch = true
                            grid = false
                            shared.edit().putBoolean("grid", false).apply()
                            renderCurrentContents()
                            showPinchIndicator("List view")
                        }
                    } else {
                        if (accumulatedScale > 1.25f && !didSwitch) {
                            didSwitch = true
                            grid = true
                            iconSizeDp = 44
                            startSizeDp = 44f
                            accumulatedScale = 1f
                            shared.edit().putBoolean("grid", true).apply()
                            shared.edit().putInt("icon_size_dp", iconSizeDp).apply()
                            renderCurrentContents()
                            showPinchIndicator("Grid view: ${iconSizeDp} dp")
                        }
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (grid && !didSwitch) {
                        val finalSize = (startSizeDp * accumulatedScale).roundToInt().coerceIn(32, 120)
                        if (finalSize != iconSizeDp) {
                            val savedScrollY = fileScroll.scrollY
                            iconSizeDp = finalSize
                            shared.edit().putInt("icon_size_dp", iconSizeDp).apply()
                            renderCurrentContents()
                            fileScroll.post { fileScroll.scrollTo(0, savedScrollY) }
                        }
                    }
                    hidePinchIndicator()
                }
            })
        }
        fileScroll.setOnDragListener { view, event ->
            val payload = event.localState as? BrowserDragPayload
            moveActiveDragOverlayFromEvent(view, event)
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> payload != null
                android.view.DragEvent.ACTION_DRAG_LOCATION -> true
                android.view.DragEvent.ACTION_DROP -> if (payload?.createLinks == true) dispatchBreadcrumbLinkDrop(view, event)
                    else dispatchNavigationDropAtScreen(view, event) || dispatchEntryDropAtScreen(view, event) || dispatchFilePaneDropAtScreen(view, event)
                else -> true
            }
        }
        fileScrollContainer = SelectionRectangleLayout(this).apply {
            selectionEnabled = { activeBrowserDragPayload == null && (!homeMode || homePage == "root") }
            var baseline: Set<Uri>? = null
            selectRectangle = { rectangle, finished ->
                if (baseline == null) {
                    baseline = selectedUris.toSet()
                    selectionMode = true
                }
                val origin = IntArray(2)
                getLocationOnScreen(origin)
                val covered = mutableListOf<android.graphics.RectF>()
                selectedUris.clear()
                selectedUris.addAll(baseline.orEmpty())
                dragContainerCards.forEach { (card, entry) ->
                    if (card.isShown) {
                        val visible = android.graphics.Rect()
                        if (card.getGlobalVisibleRect(visible)) {
                            val bounds = android.graphics.RectF(visible).apply {
                                offset(-origin[0].toFloat(), -origin[1].toFloat())
                            }
                            if (android.graphics.RectF.intersects(rectangle, bounds)) {
                                selectedUris.add(entry.uri)
                                covered.add(bounds)
                            }
                        }
                    }
                }
                updateListingStatus()
                if (finished) {
                    baseline = null
                    post { renderCurrentContents() }
                }
                covered
            }
            clipChildren = false
            addView(fileScroll, FrameLayout.LayoutParams(-1, -1))
        }
        fileScroll.setupHeaderInParent(fileScrollContainer)
        browserArea = object : LinearLayout(this) {
            private var swipeDownX = 0f
            private var swipeDownY = 0f
            private var pagingSwipeIntercepted = false
            private var pagerStartOffset = 0f
            private var lastTouchTraceAt = 0L
            private var childDisallowedIntercept = false

            private fun tracePaneTouch(phase: String, event: MotionEvent, detail: String = "") {
                val dx = event.x - swipeDownX
                val dy = event.y - swipeDownY
                val reason = when {
                    !isCompactPortrait() -> "not-compact-portrait"
                    activeBrowserDragPayload != null -> "drag-active"
                    browserEntryHoldArmed -> "hold-armed"
                    abs(dx) < dp(48) -> "below-distance"
                    abs(dx) <= abs(dy) -> "vertical-dominant"
                    (treeVisible && dx >= 0f) || (!treeVisible && dx <= 0f) -> "wrong-direction"
                    else -> "eligible"
                }
                com.infinifolder.storage.DiagnosticLog.record(
                    "browser.touch.$phase",
                    "gesture=${event.downTime} action=${MotionEvent.actionToString(event.action)} " +
                        "elapsedMs=${event.eventTime - event.downTime} x=${event.x.toInt()} y=${event.y.toInt()} " +
                        "dx=${dx.toInt()} dy=${dy.toInt()} pointers=${event.pointerCount} " +
                        "tool=${event.getToolType(0)} source=${event.source} " +
                        "pane=${if (treeVisible) "tree" else "files"} reason=$reason " +
                        "intercepted=$pagingSwipeIntercepted childDisallowed=$childDisallowedIntercept " +
                        "treeScroll=${treeScrollView.scrollY} fileScroll=${fileScroll.scrollY} $detail"
                )
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val down = event.actionMasked == MotionEvent.ACTION_DOWN
                if (down) {
                    swipeDownX = event.x
                    swipeDownY = event.y
                    childDisallowedIntercept = false
                    browserEntryHoldArmed = false
                    lastTouchTraceAt = event.eventTime
                    tracePaneTouch("dispatch", event, "stage=before")
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && childDisallowedIntercept &&
                    !pagingSwipeIntercepted && isBrowserPaneSwipe(event.x - swipeDownX, event.y - swipeDownY)
                ) {
                    // ScrollView can claim a slight vertical wobble before the
                    // horizontal swipe threshold is reached. Reopen interception
                    // when horizontal intent is clear; armed holds remain protected.
                    tracePaneTouch("reclaim", event, "reason=horizontal-after-child-scroll")
                    requestDisallowInterceptTouchEvent(false)
                }
                val handled = super.dispatchTouchEvent(event)
                val sampleMove = event.actionMasked == MotionEvent.ACTION_MOVE &&
                    event.eventTime - lastTouchTraceAt >= 150L
                if (down || sampleMove || event.actionMasked != MotionEvent.ACTION_MOVE) {
                    lastTouchTraceAt = event.eventTime
                    tracePaneTouch("dispatch", event, "stage=after handled=$handled")
                }
                return handled
            }

            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                if (childDisallowedIntercept != disallowIntercept) {
                    childDisallowedIntercept = disallowIntercept
                    com.infinifolder.storage.DiagnosticLog.record(
                        "browser.touch.interception-request",
                        "gesture=${downTimeForTrace} disallow=$disallowIntercept intercepted=$pagingSwipeIntercepted"
                    )
                }
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
            }

            private var downTimeForTrace = 0L

            private fun setPagerOffset(offset: Float) {
                treePanel.translationX = offset
                fileScrollContainer.translationX = offset
            }

            private fun animatePagerToCurrentPage(fromOffset: Float) {
                val targetOffset = if (treeVisible) 0f else -width.toFloat()
                browserPanePagerGestureInProgress = false
                val generation = ++browserPaneTransitionGeneration
                browserPaneTransitionInProgress = true
                treePanel.animate().cancel()
                fileScrollContainer.animate().cancel()
                setPagerOffset(fromOffset)
                treePanel.animate().translationX(targetOffset).setDuration(180).withEndAction {
                    if (generation == browserPaneTransitionGeneration) {
                        browserPaneTransitionInProgress = false
                        updateBrowserPanes()
                    }
                }.start()
                fileScrollContainer.animate().translationX(targetOffset).setDuration(180).start()
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downTimeForTrace = event.downTime
                        swipeDownX = event.x
                        swipeDownY = event.y
                        pagingSwipeIntercepted = false
                        pagerStartOffset = if (treeVisible) 0f else -width.toFloat()
                        browserPanePagerGestureInProgress = false
                        browserPaneTransitionInProgress = false
                        browserPaneTransitionGeneration++
                        treePanel.animate().cancel()
                        fileScrollContainer.animate().cancel()
                        if (isCompactPortrait()) setPagerOffset(pagerStartOffset)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - swipeDownX
                        val dy = event.y - swipeDownY
                        if (isBrowserPaneSwipe(dx, dy)) {
                            pagingSwipeIntercepted = true
                            browserPanePagerGestureInProgress = true
                            tracePaneTouch("intercept", event, "decision=take-over")
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - swipeDownX
                        val dy = event.y - swipeDownY
                        if (isBrowserPaneSwipe(dx, dy)) {
                            pagingSwipeIntercepted = true
                            browserPanePagerGestureInProgress = true
                            tracePaneTouch("intercept", event, "decision=take-over-on-release")
                            return true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        tracePaneTouch("intercept", event, "decision=cancel")
                        pagingSwipeIntercepted = false
                        browserPanePagerGestureInProgress = false
                    }
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (pagingSwipeIntercepted) {
                            val offset = (pagerStartOffset + event.x - swipeDownX).coerceIn(-width.toFloat(), 0f)
                            setPagerOffset(offset)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (pagingSwipeIntercepted) {
                            val dx = event.x - swipeDownX
                            val showTree = if (treeVisible) dx > -dp(56) else dx > dp(56)
                            val releaseOffset = (pagerStartOffset + dx).coerceIn(-width.toFloat(), 0f)
                            tracePaneTouch("release", event, "showTree=$showTree releaseOffset=${releaseOffset.toInt()} commitThreshold=${dp(56)}")
                            pagingSwipeIntercepted = false
                            browserPanePagerGestureInProgress = false
                            completeBrowserPaneSwipe(showTree, releaseOffset)
                            com.infinifolder.storage.DiagnosticLog.record(
                                "browser.pane.swipe", "showTree=$showTree"
                            )
                            return true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        tracePaneTouch("cancel", event, "offset=${treePanel.translationX.toInt()}")
                        val currentOffset = treePanel.translationX
                        pagingSwipeIntercepted = false
                        animatePagerToCurrentPage(currentOffset)
                        return true
                    }
                }
                return pagingSwipeIntercepted
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = true
            clipToPadding = true
        }
        treePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(com.infinifolder.theme.ThemeManager.activeTheme.background)
            visibility = if (treeVisible) View.VISIBLE else View.GONE
        }
        treePanel.setOnDragListener { target, event -> handleTreePanelDrag(target, event) }
        treePanel.addView(TextView(this).apply {
            text = "VIRTUAL FILE SYSTEM"; textSize = 12f; setTextColor(com.infinifolder.theme.ThemeManager.activeTheme.textSecondary)
            setPadding(dp(12), dp(12), dp(8), dp(8))
        })
        treeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        treeScrollView = ScrollView(this).apply {
            addView(treeContent)
            // Give the scroll viewport its own stable drop target. Rows are recreated
            // during expansion, so per-row drag listeners can disappear mid-drag.
            setOnDragListener { target, event -> handleTreePanelDrag(target, event) }
        }
        treePanel.addView(treeScrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        browserArea.addView(treePanel, LinearLayout.LayoutParams(dp(180), -1))
        browserArea.addView(fileScrollContainer, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(browserArea, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(unhideOperationBar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.setOnDragListener { host, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is BrowserDragPayload
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    moveActiveDragOverlayFromEvent(host, event)
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    cancelDragContainerHover()
                    if ((event.localState as? BrowserDragPayload)?.createLinks == true) dispatchBreadcrumbLinkDrop(host, event)
                    else dispatchTreeDropAtScreen(host, event) || dispatchNavigationDropAtScreen(host, event) ||
                        dispatchEntryDropAtScreen(host, event) || dispatchFilePaneDropAtScreen(host, event)
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    val finishedPayload = event.localState as? BrowserDragPayload
                    com.infinifolder.storage.DiagnosticLog.record(
                        "browser.drag.ended", "result=${event.result} locations=$activeDragLocationEvents"
                    )
                    if (!event.result && !activeDragDropHandled && finishedPayload != null && !finishedPayload.createLinks && treeVisible) {
                        val lastPosition = lastDragScreenPosition
                        val hadValidTreeHover = dragHoveredTreeNodeKey != null
                        val fallbackHit = lastPosition?.takeIf {
                            hadValidTreeHover && isInsideTreeViewport(it.first, it.second)
                        }
                            ?.let { resolveTreeDropTarget(it.first, it.second, finishedPayload) }
                        if (fallbackHit != null) {
                            com.infinifolder.storage.DiagnosticLog.record(
                                "browser.drag.tree-ended-fallback",
                                "node=${fallbackHit.second.key} name=${fallbackHit.second.name} items=${finishedPayload.entries.size}"
                            )
                            dispatchTreeDrop(fallbackHit.second, finishedPayload)
                        } else if (lastPosition?.let { isInsideTreeViewport(it.first, it.second) } == true) {
                            com.infinifolder.storage.DiagnosticLog.record(
                                "browser.drag.tree-ended-fallback-rejected",
                                "reason=${if (hadValidTreeHover) "no-valid-row" else "no-active-tree-hover"} " +
                                    "x=${lastPosition.first.toInt()} y=${lastPosition.second.toInt()}"
                            )
                        }
                    }
                    val selfDropMenuEntries = pendingDragSelfDropMenuEntries
                    clearActiveDragOverlay()
                    if (finishedPayload != null && selectionMode) exitSelectionMode()
                    if (selfDropMenuEntries != null) root.post {
                        if (selfDropMenuEntries.size == 1) showEntryMenu(selfDropMenuEntries.first())
                        else showBatchActionMenu(selfDropMenuEntries)
                    }
                    true
                }
                else -> false
            }
        }
        root.requestApplyInsets()
        updateCreateAction()
        updateBrowserPanes()
        browserArea.post { updateBrowserPanes() }
        rebuildTreeRoots()
    }

    private fun button(label: String, color: Int, click: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = label
            textSize = when {
                label == "InfiniFolder" || label == "Home" -> 16f
                label == "📁+" || label == "🔗+" -> 17f
                else -> 21f
            }
            setTextColor(com.infinifolder.theme.ThemeManager.activeTheme.toolbarText)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            minWidth = dp(if (label == "InfiniFolder") 112 else 40)
            minimumHeight = dp(48)
            contentDescription = label
            setOnClickListener { click() }
        }
        toolbar.addView(view)
        return view
    }
    private fun updateCreateAction() {
        if (!::createButton.isInitialized) return
        updateFloatingModeAction()
        val inArchive = current?.let { ArchiveUri.isArchiveUri(it) && !archiveProvider.cachedWritable(it) } ?: false
        val inApps = current?.let { AppsUri.isAppsUri(it) } ?: false
        val inPrivateDownloads = current?.scheme == com.infinifolder.storage.PartialDownloadsProvider.SCHEME
        val canWriteDir = !homeMode && current != null && !inArchive && !inApps && !inPrivateDownloads
        createButton.visibility = if ((homeMode && homePage != "root") || inArchive || inApps || inPrivateDownloads) View.GONE else View.VISIBLE
        createButton.text = if (homeMode) "＋" else "📁+"
        createButton.contentDescription = if (homeMode) "Add saved connection" else "Create directory"
        if (::createLinkButton.isInitialized) {
            createLinkButton.visibility = if (canWriteDir) View.VISIBLE else View.GONE
            createLinkButton.text = "🔗+"
            createLinkButton.contentDescription = "Create link"
        }
    }
    private fun updateFloatingModeAction() {
        if (::floatingModeButton.isInitialized) {
            floatingModeButton.isEnabled = !homeMode && current != null
            floatingModeButton.alpha = if (floatingModeButton.isEnabled) 1f else .4f
        }
    }
    private fun showCurrentDirectoryFloating() {
        val location = current?.takeUnless { homeMode } ?: return
        try {
            if (android.provider.Settings.canDrawOverlays(this)) {
                startService(com.infinifolder.overlay.FloatingContainerService.intent(this, location))
            } else {
                startActivity(HomeItemActivity.intent(this, location).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            moveTaskToBack(true)
        } catch (error: Exception) {
            com.infinifolder.storage.DiagnosticLog.record("browser.floating.failed", error = error)
            Toast.makeText(this, error.message ?: "Cannot open floating window", Toast.LENGTH_LONG).show()
        }
    }

    private fun onCreateAction() {
        if (current?.let { AppsUri.isAppsUri(it) } == true) return
        if (homeMode && homePage == "root") showRootContainerLinkDialog() else if (!homeMode) showCreateDirectoryDialog()
    }
    private fun onEmptyAreaLongPress(): Boolean {
        if (homeMode) { if (homePage == "root") { showOptions(); return true }; return false }
        val dirUri = current ?: return false
        showCurrentDirectoryMenu(dirUri)
        return true
    }
    private fun initSafetyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                SAFETY_NOTIFICATION_CHANNEL_ID,
                "Safety Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when dangerous operations like writing to mounted partitions are prevented"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun notifyMountedPartitionBlocked(targetName: String, reason: String) {
        initSafetyNotificationChannel()
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                val notification = NotificationCompat.Builder(this, SAFETY_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Write Blocked: Mounted Partition")
                    .setContentText("Cannot modify '$targetName': $reason")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        "Writing to '$targetName' was blocked because this partition or disk image is currently mounted or in use ($reason).\n\nModifying a mounted partition causes severe filesystem corruption."
                    ))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIFICATION_ID_MOUNTED_BLOCKED, notification)
            }
        } catch (_: Exception) {}

        Toast.makeText(this, "Write blocked: '$targetName' is currently mounted or in use.", Toast.LENGTH_LONG).show()

        dialogBuilder()
            .setTitle("Write Operation Blocked")
            .setMessage("The partition or disk image '$targetName' is currently mounted or in active use ($reason).\n\nWriting directly to a mounted filesystem can cause severe filesystem corruption and data loss.\n\nPlease unmount the partition or stop the virtual machine before making modifications.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun queueAllAudioInFolder(folderUri: Uri, startEntry: ResourceEntry? = null) {
        io.execute {
            val siblings = MediaSourceAdapter.listFolder(this@MainActivity, folderUri)
            val audioTracks = siblings.filter {
                !it.metadata.isDirectory && MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.AUDIO
            }
            if (audioTracks.isEmpty()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "No audio files found in directory", Toast.LENGTH_SHORT).show() }
                return@execute
            }
            val target = startEntry ?: audioTracks.first()
            runOnUiThread {
                val intent = Intent(this@MainActivity, com.infinifolder.viewer.AudioPlayerActivity::class.java).apply {
                    data = target.uri
                    putExtra("uri", target.uri.toString())
                    putExtra("displayName", target.metadata.name)
                    putExtra("parentUri", folderUri.toString())
                }
                startActivity(intent)
            }
        }
    }

    private fun queueAllVideoInFolder(folderUri: Uri, startEntry: ResourceEntry? = null) {
        io.execute {
            val siblings = MediaSourceAdapter.listFolder(this@MainActivity, folderUri)
            val videoTracks = siblings.filter {
                !it.metadata.isDirectory && MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.VIDEO
            }
            if (videoTracks.isEmpty()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "No video files found in directory", Toast.LENGTH_SHORT).show() }
                return@execute
            }
            val target = startEntry ?: videoTracks.first()
            runOnUiThread {
                val intent = Intent(this@MainActivity, com.infinifolder.viewer.VideoPlayerActivity::class.java).apply {
                    data = target.uri
                    putExtra("uri", target.uri.toString())
                    putExtra("displayName", target.metadata.name)
                    putExtra("parentUri", folderUri.toString())
                }
                startActivity(intent)
            }
        }
    }

    private fun queueAllMultimediaInFolder(folderUri: Uri, startEntry: ResourceEntry? = null) {
        io.execute {
            val siblings = MediaSourceAdapter.listFolder(this@MainActivity, folderUri)
            val mediaTracks = siblings.filter {
                if (it.metadata.isDirectory) return@filter false
                val type = MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType)
                type == MediaType.AUDIO || type == MediaType.VIDEO
            }
            if (mediaTracks.isEmpty()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "No multimedia files found in directory", Toast.LENGTH_SHORT).show() }
                return@execute
            }
            val target = startEntry ?: mediaTracks.first()
            val targetType = MediaTypeDetector.detect(target.metadata.name, target.metadata.mimeType)
            runOnUiThread {
                val targetActivity = if (targetType == MediaType.VIDEO) {
                    com.infinifolder.viewer.VideoPlayerActivity::class.java
                } else {
                    com.infinifolder.viewer.AudioPlayerActivity::class.java
                }
                val intent = Intent(this@MainActivity, targetActivity).apply {
                    data = target.uri
                    putExtra("uri", target.uri.toString())
                    putExtra("displayName", target.metadata.name)
                    putExtra("parentUri", folderUri.toString())
                    putExtra("queueMultimedia", true)
                }
                startActivity(intent)
            }
        }
    }

    private fun enqueueAudio(entry: ResourceEntry) {
        val s = com.infinifolder.viewer.AudioPlayerService.currentService
        if (s != null) {
            s.enqueue(com.infinifolder.viewer.AudioTrack(entry.uri, entry.metadata.name))
            Toast.makeText(this, "Queued: ${entry.metadata.name}", Toast.LENGTH_SHORT).show()
        } else {
            launchBuiltInViewer(entry, com.infinifolder.viewer.MediaType.AUDIO)
        }
    }

    private fun showCurrentDirectoryMenu(dirUri: Uri? = current) {
        val target = dirUri ?: return
        val location = loadGeneration
        io.execute {
            // A mounted root represents its backing container file. Subfolders retain their own identity.
            val menuTarget = if (ArchiveUri.isArchiveUri(target) && ArchiveUri.getEntryPath(target).isEmpty())
                ArchiveUri.getSourceUri(target) else target
            val result = provider.stat(resourceRef(menuTarget))
            runOnUiThread {
                if (location != loadGeneration || current != target || isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ProviderResult.Success -> showEntryMenu(result.value)
                    is ProviderResult.Failure -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    is ProviderResult.Unsupported -> Toast.makeText(this, "Folder information is unavailable", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun showCurrentDirectoryProperties(dirUri: Uri, dirName: String) {
        val localDir = if (localProvider.owns(dirUri)) {
            runCatching { localProvider.fileFor(dirUri) }.getOrNull()
        } else null
        val perm = localDir?.let { runCatching { com.infinifolder.storage.PermissionHelper.getFilePermissions(it) }.getOrNull() }
        val modified = localDir?.lastModified()?.takeIf { it > 0 }
        val entry = ResourceEntry(
            ref = resourceRef(dirUri),
            metadata = ResourceMetadata(
                name = dirName,
                mimeType = "vnd.android.document/directory",
                sizeBytes = null,
                modifiedAtMillis = modified,
                isDirectory = true,
                canRead = perm?.canRead ?: true,
                canWrite = perm?.canWrite ?: true,
                canExecute = perm?.canExecute ?: false,
                permissionsMode = perm?.mode,
                permissionsFormatted = perm?.formatted,
                owner = perm?.owner,
                group = perm?.group
            ),
            uri = dirUri
        )
        showPropertiesDialog(entry)
    }
    private fun showCreateUriLinkDialog(initialFolderUri: Uri? = current, initialUri: String? = null) {
        if (homeMode && homePage == "root") { showRootContainerLinkDialog(initialUri); return }
        val defaultTargetFolder = initialFolderUri?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
            ?: current?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
            ?: defaultWritableFolderUri()
        if (defaultTargetFolder == null) {
            Toast.makeText(this, "No writable folder available to create a link.", Toast.LENGTH_SHORT).show()
            return
        }
        var targetFolderUri: Uri = defaultTargetFolder
        val context = dialogContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }

        var isDialogClosed = false
        var detectGeneration = 0
        var detectedTitle: String? = null
        var detectedFaviconBase64: String? = null
        var detectedForUrl: String? = null
        var customSelectedIconBase64: String? = null
        var nameManuallyEdited = false
        var programmaticNameChange = false

        // Header Row: Icon preview (tap to customize) + Status
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val iconBoxPx = dp(56)
        val iconBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconBoxPx, iconBoxPx)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) 0xFF252A24.toInt() else 0xFFEAECE7.toInt())
                setStroke(dp(1), if (dark) 0xFF4E5B49.toInt() else 0xFFB8C2B3.toInt())
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
        }
        val iconPlaceholder = TextView(context).apply {
            text = "🔗"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        val iconImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        iconBox.addView(iconPlaceholder)
        iconBox.addView(iconImage)

        val headerInfoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val headerHintTitle = TextView(context).apply {
            text = "Any URI or Web URL"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(setTextColorForTheme())
        }
        val detectStatusView = TextView(context).apply {
            text = "Web URLs auto-detect title & favicon · Tap icon to customize"
            textSize = 12f
            setTextColor(if (dark) 0xFF9BA994.toInt() else 0xFF687363.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        headerInfoCol.addView(headerHintTitle)
        headerInfoCol.addView(detectStatusView)
        headerRow.addView(iconBox)
        headerRow.addView(headerInfoCol)
        layout.addView(headerRow)
        iconBox.setOnClickListener {
            pendingIconTargetType = "create_link"
            pendingIconTargetUri = null
            pendingIconTargetMime = null
            pendingCreateLinkIconCallback = { bmp, base64 ->
                if (!isDialogClosed) {
                    customSelectedIconBase64 = base64
                    iconImage.setImageBitmap(bmp)
                    iconPlaceholder.visibility = View.GONE
                    detectStatusView.text = "Custom icon selected"
                }
            }
            launchCustomIconPicker()
        }

        val linkProtocolIds=listOf("custom","sftp","smb","webdav","ftp")
        val linkProtocolLabels=listOf("Custom / unknown protocol","SFTP / SSH","SMB / Samba","WebDAV","FTP / FTPS")
        val protocolSelector=Spinner(context).apply {
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,linkProtocolLabels)
            setSelection(0)
        }
        layout.addView(TextView(context).apply { text="Protocol";textSize=12f;setTextColor(setTextColorForTheme()) })
        layout.addView(protocolSelector)
        val uriLabel = TextView(context).apply {
            text = "Target URI:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, dp(4), 0, dp(2))
        }
        val uriInput = EditText(context).apply {
            hint = "https://example.com, content://…, file://…, apps://…"
            setHintTextColor(if (dark) 0xFF888888.toInt() else 0xFFAAAAAA.toInt())
            setTextColor(setTextColorForTheme())
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        var updateProtocolFields: () -> Unit = {}

        protocolSelector.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?)=Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) {
                uriInput.hint=when(linkProtocolIds.getOrElse(position){"custom"}) {
                    "sftp"->"server.example/path or sftp://server/path"
                    "smb"->"server/share/path or smb://server/share/path"
                    "webdav"->"https://server/dav/ or server/dav/"
                    "ftp"->"server/path, ftp://… or ftps://…"
                    else->"https://example.com, content://…, file://…, apps://…"
                }
                updateProtocolFields()
            }
        }
        if(!initialUri.isNullOrBlank()) {
            val scheme=runCatching { Uri.parse(initialUri).scheme?.lowercase() }.getOrNull()
            val index=when(scheme){"sftp","ssh"->1;"smb","cifs"->2;"webdav","dav","davs"->3;"ftp","ftps"->4;else->0}
            protocolSelector.setSelection(index)
        }

        val quickActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        val protocolSetupPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        val protocolSetupHint = TextView(context).apply {
            textSize = 13f
            setTextColor(setTextColorForTheme())
        }
        val configureProtocolButton = Button(context).apply { text = "Set up connection…" }
        val useSavedProtocolButton = Button(context).apply { text = "Use saved connection…" }
        protocolSetupPanel.addView(protocolSetupHint)
        protocolSetupPanel.addView(configureProtocolButton)
        protocolSetupPanel.addView(useSavedProtocolButton)

        fun quickActionBtn(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
            text = label
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
            setPadding(dp(6), dp(4), dp(12), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val nameLabel = TextView(context).apply {
            text = "Link name:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, dp(6), 0, dp(2))
        }
        val nameInput = EditText(context).apply {
            hint = "Leave empty to auto-detect from URI / web title"
            setHintTextColor(if (dark) 0xFF888888.toInt() else 0xFFAAAAAA.toInt())
            setTextColor(setTextColorForTheme())
            textSize = 14f
        }
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!programmaticNameChange) {
                    nameManuallyEdited = !s.isNullOrBlank()
                }
            }
        })

        val isFolderCheck = CheckBox(context).apply {
            text = "Target is a folder"
            setTextColor(setTextColorForTheme())
        }

        fun updatePlaceholderIcon(rawUri: String) {
            if (customSelectedIconBase64 != null) return
            val normalized = WebMetadataResolver.normalizeInputUri(rawUri)
            val parsed = runCatching { Uri.parse(normalized) }.getOrNull()
            if (detectedFaviconBase64 == null) {
                iconImage.setImageDrawable(null)
                iconPlaceholder.visibility = View.VISIBLE
                iconPlaceholder.text = when {
                    linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"}=="sftp" -> "🔐"
                    linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"}=="smb" -> "🖧"
                    linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"} in setOf("webdav","ftp") -> "🌐"
                    WebMetadataResolver.isWebUri(normalized) -> "🌐"
                    isFolderCheck.isChecked -> "📁"
                    parsed != null && AppsUri.isAppUri(parsed) -> "📱"
                    else -> "🔗"
                }
            }
        }

        fun triggerWebDetection(rawUri: String) {
            val normalized = WebMetadataResolver.normalizeInputUri(rawUri)
            if (!WebMetadataResolver.isWebUri(normalized)) {
                detectedTitle = null
                detectedFaviconBase64 = null
                detectedForUrl = null
                updatePlaceholderIcon(rawUri)
                detectStatusView.text = if (normalized.isEmpty()) {
                    "Web URLs auto-detect title & favicon · Tap icon to customize"
                } else {
                    "URI ready (${normalized.substringBefore(':')}://)"
                }
                return
            }
            val gen = ++detectGeneration
            updatePlaceholderIcon(normalized)
            detectStatusView.text = "Detecting web title & favicon…"
            imageIo.execute {
                val meta = WebMetadataResolver.fetchWebMetadata(normalized, dp(128))
                runOnUiThread {
                    if (isDialogClosed || gen != detectGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    detectedForUrl = normalized
                    detectedTitle = meta.title
                    detectedFaviconBase64 = meta.faviconBase64
                    if (!meta.title.isNullOrBlank() && (!nameManuallyEdited || nameInput.text.toString().isBlank())) {
                        programmaticNameChange = true
                        nameInput.setText(meta.title)
                        programmaticNameChange = false
                    }
                    if (customSelectedIconBase64 == null) {
                        if (meta.faviconBitmap != null) {
                            iconImage.setImageBitmap(meta.faviconBitmap)
                            iconPlaceholder.visibility = View.GONE
                        } else {
                            iconImage.setImageDrawable(null)
                            iconPlaceholder.visibility = View.VISIBLE
                            iconPlaceholder.text = "🌐"
                        }
                    }
                    detectStatusView.text = when {
                        !meta.title.isNullOrBlank() && meta.faviconBitmap != null -> "✓ Detected page title & favicon"
                        !meta.title.isNullOrBlank() -> "✓ Detected page title"
                        meta.faviconBitmap != null -> "✓ Detected favicon"
                        else -> "Web URL ready"
                    }
                }
            }
        }

        var debounceRunnable: Runnable? = null
        uriInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val raw = s?.toString()?.trim() ?: ""
                debounceRunnable?.let { root.removeCallbacks(it) }
                val typedScheme=runCatching { Uri.parse(raw).scheme?.lowercase() }.getOrNull()
                val typedProtocol=when(typedScheme){"ssh","sftp"->"sftp";"smb","cifs"->"smb";"dav","davs","webdav"->"webdav";"ftp","ftps"->"ftp";else->null}
                if(typedProtocol!=null && linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"}=="custom") protocolSelector.setSelection(linkProtocolIds.indexOf(typedProtocol))
                val normalized = WebMetadataResolver.normalizeInputUri(raw)
                if (linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"}=="custom" && WebMetadataResolver.isWebUri(normalized)) {
                    isFolderCheck.isChecked = false
                    updatePlaceholderIcon(raw)
                    val task = Runnable {
                        if (!isDialogClosed) triggerWebDetection(raw)
                    }
                    debounceRunnable = task
                    root.postDelayed(task, 500)
                } else {
                    detectGeneration++
                    detectedTitle = null
                    detectedFaviconBase64 = null
                    detectedForUrl = null
                    updatePlaceholderIcon(raw)
                    detectStatusView.text = if (normalized.isEmpty()) {
                        "Web URLs auto-detect title & favicon · Tap icon to customize"
                    } else {
                        "URI ready"
                    }
                }
            }
        })

        isFolderCheck.setOnCheckedChangeListener { _, _ ->
            updatePlaceholderIcon(uriInput.text.toString())
        }

        quickActionsRow.addView(quickActionBtn("Paste URI") {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!clipText.isNullOrEmpty()) {
                uriInput.setText(clipText)
                uriInput.setSelection(clipText.length)
                debounceRunnable?.let { root.removeCallbacks(it) }
                triggerWebDetection(clipText)
            } else {
                Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            }
        })
        quickActionsRow.addView(quickActionBtn("Pick folder…") {
            val currentTyped = runCatching { Uri.parse(uriInput.text.toString().trim()) }.getOrNull()
            showFolderPicker("Select Target Folder", currentTyped ?: targetFolderUri) { pickedUri, displayPath ->
                uriInput.setText(pickedUri.toString())
                isFolderCheck.isChecked = true
                if (!nameManuallyEdited || nameInput.text.toString().isBlank()) {
                    programmaticNameChange = true
                    nameInput.setText(displayPath.substringAfterLast('/').trim())
                    programmaticNameChange = false
                }
                updatePlaceholderIcon(pickedUri.toString())
            }
        })
        quickActionsRow.addView(quickActionBtn("Detect web info") {
            val raw = uriInput.text.toString().trim()
            if (linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"}!="custom") {
                Toast.makeText(this,"Page detection is available for custom HTTP/HTTPS links.",Toast.LENGTH_SHORT).show()
            } else if (raw.isEmpty()) {
                Toast.makeText(this, "Enter or paste a web URL first.", Toast.LENGTH_SHORT).show()
            } else {
                debounceRunnable?.let { root.removeCallbacks(it) }
                triggerWebDetection(raw)
            }
        })

        val destHeader = TextView(context).apply {
            text = "Save link to:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }
        val destRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (dark) 0xFF2A2E28.toInt() else 0xFFEAECE7.toInt())
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        val destIcon = TextView(context).apply {
            text = "📁"
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        }
        val destPathView = TextView(context).apply {
            text = getPathDisplay(targetFolderUri)
            textSize = 13f
            setTextColor(setTextColorForTheme())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val changeBtn = TextView(context).apply {
            text = "Change…"
            textSize = 13f
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            isFocusable = true
        }
        destRow.addView(destIcon)
        destRow.addView(destPathView, LinearLayout.LayoutParams(0, -2, 1f))
        destRow.addView(changeBtn)
        val pickSaveLocation: () -> Unit = {
            showFolderPicker("Select Location for Link", targetFolderUri) { selectedUri, displayPath ->
                targetFolderUri = selectedUri
                destPathView.text = displayPath
            }
        }
        destRow.setOnClickListener { pickSaveLocation() }
        changeBtn.setOnClickListener { pickSaveLocation() }

        layout.addView(uriLabel)
        layout.addView(uriInput)
        layout.addView(quickActionsRow)
        layout.addView(protocolSetupPanel)
        layout.addView(nameLabel)
        layout.addView(nameInput)
        layout.addView(isFolderCheck)
        layout.addView(destHeader)
        layout.addView(destRow)

        if (!initialUri.isNullOrBlank()) {
            uriInput.setText(initialUri)
            uriInput.setSelection(initialUri.length)
            triggerWebDetection(initialUri)
        }

        val scroll = ScrollView(context).apply { addView(layout) }
        val dialog = dialogBuilder()
            .setTitle("Create Link")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()

        updateProtocolFields = {
            val protocol = linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition) { "custom" }
            val custom = protocol == "custom"
            uriLabel.visibility = if (custom) View.VISIBLE else View.GONE
            uriInput.visibility = if (custom) View.VISIBLE else View.GONE
            quickActionsRow.visibility = if (custom) View.VISIBLE else View.GONE
            isFolderCheck.visibility = if (custom) View.VISIBLE else View.GONE
            protocolSetupPanel.visibility = if (custom) View.GONE else View.VISIBLE
            protocolSetupHint.text = "${linkProtocolLabels.getOrElse(protocolSelector.selectedItemPosition) { "Storage" }} needs a saved connection. Configure its server, path, and authentication, or use an existing connection."
        }
        updateProtocolFields()

        fun addConnectionShortcut(connection: ProviderConnection) {
            val targetPath = if (connection.sourceTypeId == "smb" && !connection.options["initialShare"].isNullOrBlank())
                "/${connection.options["initialShare"].orEmpty().trim('/')}" else connection.options["initialPath"] ?: "/"
            val target = Uri.Builder().scheme(connection.sourceTypeId).authority(connection.id).path(targetPath).build()
            val title = nameInput.text.toString().trim().removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION)
                .ifBlank { connection.displayName }
            val info = ShortcutInfo(targetUri = target.toString(), targetPath = targetPath,
                displayNameOverride = title, targetMimeType = "vnd.android.document/directory", targetIsDirectory = true,
                iconBase64 = customSelectedIconBase64)
            val stem = WebMetadataResolver.sanitizeFileName(title, connection.displayName)
            dialog.dismiss()
            createShortcutFile(targetFolderUri, "$stem${ShortcutInfo.SHORTCUT_EXTENSION}", info)
        }
        fun configureConnection(protocol: String, existing: ProviderConnection? = null) {
            val initial = uriInput.text.toString().trim().takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val display = nameInput.text.toString().trim()
            dialog.dismiss()
            val onSaved: (ProviderConnection) -> Unit = ::addConnectionShortcut
            when (protocol) {
                "sftp" -> showSftpConnectionDialog(existing = existing, initialName = display, initialUri = initial, onSaved = onSaved)
                "smb" -> showSmbConnectionDialog(existing = existing, initialName = display, initialUri = initial, onSaved = onSaved)
                "webdav" -> showWebDavConnectionDialog(existing = existing, initialName = display, initialUri = initial, onSaved = onSaved)
                "ftp" -> showFtpConnectionDialog(existing = existing, initialName = display, initialUri = initial, onSaved = onSaved)
            }
        }
        configureProtocolButton.setOnClickListener {
            configureConnection(linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition) { return@setOnClickListener })
        }
        useSavedProtocolButton.setOnClickListener {
            val protocol = linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition) { "custom" }
            val candidates = savedConnections().filter { it.sourceTypeId == protocol }
            if (candidates.isEmpty()) {
                Toast.makeText(this, "No saved ${linkProtocolLabels[protocolSelector.selectedItemPosition]} connection", Toast.LENGTH_SHORT).show()
            } else {
                dialogBuilder().setTitle("Use saved connection").setItems(candidates.map { "${it.displayName} · ${it.endpoint ?: it.options["url"].orEmpty()}" }.toTypedArray()) { _, which ->
                    addConnectionShortcut(candidates[which])
                }.setNegativeButton("Cancel", null).show()
            }
        }

        dialog.setOnDismissListener {
            isDialogClosed = true
            debounceRunnable?.let { root.removeCallbacks(it) }
            if (pendingIconTargetType == "create_link") {
                pendingIconTargetType = null
                pendingCreateLinkIconCallback = null
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawUri = uriInput.text.toString().trim()
                if (linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition) { "custom" } != "custom") {
                    Toast.makeText(this, "Set up the selected protocol connection or choose a saved connection.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (rawUri.isEmpty()) {
                    uriInput.error = "Enter or paste a URI"
                    return@setOnClickListener
                }
                if (ArchiveUri.isArchiveUri(targetFolderUri)) {
                    val mountCheck = archiveProvider.checkMounted(targetFolderUri)
                    if (mountCheck?.isMounted == true) {
                        notifyMountedPartitionBlocked(displayNameFor(targetFolderUri) ?: "Archive", mountCheck.reason)
                        return@setOnClickListener
                    }
                }
                if ((ArchiveUri.isArchiveUri(targetFolderUri) && !archiveProvider.cachedWritable(targetFolderUri)) || AppsUri.isAppsUri(targetFolderUri)) {
                    Toast.makeText(this, "Cannot create link in a read-only location.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedProtocol=linkProtocolIds.getOrElse(protocolSelector.selectedItemPosition){"custom"}
                val protocolUri=when(selectedProtocol) {
                    "sftp" -> if("://" in rawUri) rawUri else "sftp://$rawUri"
                    "smb" -> if("://" in rawUri) rawUri else "smb://$rawUri"
                    "ftp" -> if("://" in rawUri) rawUri else "ftp://$rawUri"
                    "webdav" -> when {
                        rawUri.startsWith("https://",true) -> "davs://${rawUri.substringAfter("//")}"
                        rawUri.startsWith("http://",true) -> "dav://${rawUri.substringAfter("//")}"
                        "://" in rawUri -> rawUri
                        else -> "davs://$rawUri"
                    }
                    else -> rawUri
                }
                val normalizedUri = WebMetadataResolver.normalizeInputUri(protocolUri)
                val userEnteredName = nameInput.text.toString().trim()
                    .removeSuffix(".lnk").removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION)
                    .removeSuffix(".LNK")
                    .trim()
                val isWeb = WebMetadataResolver.isWebUri(normalizedUri)
                val alreadyDetected = detectedForUrl == normalizedUri
                val initialTitle = userEnteredName.ifEmpty {
                    detectedTitle ?: WebMetadataResolver.defaultNameForUri(normalizedUri)
                }
                val initialIconBase64 = customSelectedIconBase64 ?: detectedFaviconBase64
                val folderFlag = isFolderCheck.isChecked

                dialog.dismiss()

                io.execute {
                    var finalUrl = normalizedUri
                    var finalTitle = initialTitle
                    var finalIconBase64 = initialIconBase64
                    if (isWeb && (!alreadyDetected || (userEnteredName.isEmpty() && detectedTitle == null) || finalIconBase64 == null)) {
                        val fetched = WebMetadataResolver.fetchWebMetadata(normalizedUri, dp(128))
                        finalUrl = fetched.normalizedUrl.ifEmpty { normalizedUri }
                        if (userEnteredName.isEmpty() && !fetched.title.isNullOrBlank()) {
                            finalTitle = fetched.title ?: finalTitle
                        }
                        if (finalIconBase64 == null && !fetched.faviconBase64.isNullOrBlank()) {
                            finalIconBase64 = fetched.faviconBase64
                        }
                    }

                    val parsedTarget = runCatching { Uri.parse(finalUrl) }.getOrNull()
                    val isLocalDir = !isWeb && parsedTarget != null && localProvider.owns(parsedTarget) &&
                        runCatching { localProvider.fileFor(parsedTarget).isDirectory }.getOrDefault(false)
                    val targetIsDir = !isWeb && (folderFlag || isLocalDir || (parsedTarget != null && AppsUri.isCategoryUri(parsedTarget)))
                    val targetPathStr = when {
                        isWeb -> finalUrl
                        parsedTarget != null -> getPathDisplay(parsedTarget)
                        else -> finalUrl
                    }
                    val targetMime = when {
                        isWeb -> "text/uri-list"
                        targetIsDir -> "vnd.android.document/directory"
                        else -> null
                    }
                    val cleanStem = WebMetadataResolver.sanitizeFileName(
                        finalTitle,
                        WebMetadataResolver.defaultNameForUri(finalUrl)
                    )
                    val shortcutInfo = ShortcutInfo(
                        targetUri = finalUrl,
                        targetPath = targetPathStr,
                        displayNameOverride = finalTitle.takeIf { it.isNotBlank() },
                        targetMimeType = targetMime,
                        targetIsDirectory = targetIsDir,
                        iconBase64 = finalIconBase64
                    )
                    createShortcutFile(targetFolderUri, "$cleanStem${ShortcutInfo.SHORTCUT_EXTENSION}", shortcutInfo)
                }
            }
        }
        dialog.show()
    }
    private fun showCreateDirectoryDialog() {
        val parent = current ?: return
        if (AppsUri.isAppsUri(parent)) {
            Toast.makeText(this, "Installed applications directory is read-only.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ArchiveUri.isArchiveUri(parent)) {
            val mountCheck = archiveProvider.checkMounted(parent)
            if (mountCheck?.isMounted == true) {
                notifyMountedPartitionBlocked(displayNameFor(parent) ?: "Archive", mountCheck.reason)
                return
            }
        }
        val input = EditText(dialogContext()).apply { hint = "Directory name" }
        dialogBuilder().setTitle("Create directory").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) {
                            input.error = "Enter a valid directory name"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        io.execute {
                            val result = provider.createDirectory(resourceRef(parent), name)
                            runOnUiThread {
                                when (result) {
                                    is ProviderResult.Success -> current?.let(::load)
                                    is ProviderResult.Unsupported -> Toast.makeText(this, "This provider does not support creating directories.", Toast.LENGTH_LONG).show()
                                    is ProviderResult.Failure -> {
                                        if (result.cause is MountedPartitionException || result.message.contains("mounted", ignoreCase = true)) {
                                            notifyMountedPartitionBlocked(name, result.message)
                                        } else {
                                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }
    private fun showAddConnectionDialog() {
        val types = providerRegistry.sourceTypes().filter { it.id != "saf" }
        val context = dialogContext()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val type = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, types.map { "${it.icon}  ${it.label}" })
        }
        val name = EditText(context).apply { hint = "Connection name" }
        val endpoint = EditText(context).apply { hint = "Server or URL (optional)" }
        body.addView(type); body.addView(name); body.addView(endpoint)
        dialogBuilder().setTitle("Add saved connection").setMessage("Configure network or cloud connection.")
            .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Next", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val descriptor = types.getOrNull(type.selectedItemPosition) ?: return@setOnClickListener
                        val displayName = name.text.toString().trim()
                        val endpointText = endpoint.text.toString().trim()
                        if (descriptor.id == "sftp") {
                            dialog.dismiss()
                            showSftpConnectionDialog(initialName = displayName, initialEndpoint = endpointText)
                            return@setOnClickListener
                        }
                        if (descriptor.id == "smb") {
                            dialog.dismiss()
                            showSmbConnectionDialog(initialName = displayName, initialEndpoint = endpointText)
                            return@setOnClickListener
                        }
                        if (descriptor.id == "ftp") {
                            dialog.dismiss()
                            showFtpConnectionDialog(initialName = displayName, initialEndpoint = endpointText)
                            return@setOnClickListener
                        }
                        if (displayName.isEmpty()) { name.error = "Enter a name"; return@setOnClickListener }
                        val connection = ProviderConnection(
                            sourceTypeId = descriptor.id,
                            displayName = displayName,
                            endpoint = endpointText.ifEmpty { null }
                        )
                        saveConnection(connection)
                        dialog.dismiss()
                        showHomeRoot()
                    }
                }
                dialog.show()
            }
    }
    private fun bindContainerUri(uri: Uri, name: String, ready: (Uri) -> Unit) {
        if (ContainerUris.networkType(uri) == null) { ready(uri); return }
        if (ContainerUris.hasPassword(uri)) {
            dialogBuilder().setMessage("Enter the password in connection settings, not in the saved URI.").setPositiveButton("OK", null).show()
            return
        }
        val candidates = ContainerUris.candidates(uri, savedConnections())
        if (candidates.size == 1) { ready(ContainerUris.bind(uri, candidates.single())); return }
        if (candidates.size > 1) {
            dialogBuilder().setTitle("Choose storage account").setItems(candidates.map { it.displayName + " · " + it.options["username"].orEmpty() }.toTypedArray()) { _, index ->
                ready(ContainerUris.bind(uri, candidates[index]))
            }.setNegativeButton("Cancel", null).show()
            return
        }
        if (uri.authority.orEmpty().matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}"))) {
            dialogBuilder().setMessage("The saved connection for this link was removed. Edit the URI to choose another server/account.").setPositiveButton("OK", null).show()
            return
        }
        val saved: (ProviderConnection) -> Unit = { connection ->
            shared.edit().putBoolean("root_link_migrated_${connection.id}", true).apply()
            ready(ContainerUris.bind(uri, connection))
        }
        when (ContainerUris.networkType(uri)) {
            "ftp" -> showFtpConnectionDialog(initialName = name, initialUri = uri, onSaved = saved)
            "sftp" -> showSftpConnectionDialog(initialName = name, initialUri = uri, onSaved = saved)
            "smb" -> showSmbConnectionDialog(initialName = name, initialUri = uri, onSaved = saved)
            "webdav" -> showWebDavConnectionDialog(initialName = name, initialUri = uri, onSaved = saved)
        }
    }

    private fun containerUriHint(uri: Uri): Uri {
        if (!uri.isHierarchical || ArchiveUri.isArchiveUri(uri)) return uri
        return if (!uri.path.orEmpty().endsWith('/') && ArchiveFormats.isArchive(uri.lastPathSegment.orEmpty()))
            ArchiveUri.toArchiveUri(uri) else uri
    }

    private fun showRootContainerLinkDialog(initialUri: String? = null) {
        val context=dialogContext()
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(4)) }
        val protocolLabels=listOf("Custom URI / unknown protocol","SFTP / SSH","SMB / Samba","WebDAV","FTP / FTPS")
        val protocolIds=listOf("custom","sftp","smb","webdav","ftp")
        val selector=Spinner(context).apply { adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,protocolLabels) }
        val name=EditText(context).apply { hint="Link name"; isSingleLine=true }
        val uri=EditText(context).apply { hint="https://…, content://…, or custom URI"; setText(initialUri.orEmpty()); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val uriLabel=TextView(context).apply { text="Target URI"; textSize=12f; setTextColor(setTextColorForTheme()) }
        val savedButton=Button(context).apply { text="Use saved connection…" }
        body.addView(TextView(context).apply { text="Protocol"; textSize=12f; setTextColor(setTextColorForTheme()) })
        body.addView(selector); body.addView(name); body.addView(uriLabel); body.addView(uri); body.addView(savedButton)
        fun selectedProtocol()=protocolIds.getOrElse(selector.selectedItemPosition){"custom"}
        fun updateFields(){
            val custom=selectedProtocol()=="custom"
            uriLabel.visibility=if(custom) View.VISIBLE else View.GONE
            uri.visibility=if(custom) View.VISIBLE else View.GONE
            savedButton.visibility=if(custom) View.GONE else View.VISIBLE
        }
        if(!initialUri.isNullOrBlank()) {
            val scheme=runCatching { Uri.parse(initialUri).scheme?.lowercase() }.getOrNull()
            val selected=when(scheme){"sftp","ssh"->"sftp";"smb","cifs"->"smb";"webdav","dav","davs"->"webdav";"ftp","ftps"->"ftp";else->"custom"}
            selector.setSelection(protocolIds.indexOf(selected).coerceAtLeast(0))
        }
        var pickedConnection:ProviderConnection?=null
        selector.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?)=Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){ pickedConnection=null; updateFields() }
        }
        savedButton.setOnClickListener {
            val type=selectedProtocol(); val candidates=savedConnections().filter { it.sourceTypeId==type }
            if(candidates.isEmpty()) { Toast.makeText(this,"No saved ${protocolLabels.getOrElse(selector.selectedItemPosition){""}} connection",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dialogBuilder().setTitle("Choose saved connection").setItems(candidates.map { "${it.displayName} · ${it.endpoint ?: it.options["url"].orEmpty()}" }.toTypedArray()) { _,which ->
                pickedConnection=candidates[which]; name.setText(pickedConnection?.displayName)
            }.setNegativeButton("Cancel",null).show()
        }
        val dialog=dialogBuilder().setTitle("New link").setMessage("Create a browsable storage connection on Virtual root, or choose a custom URI. Removing a link never deletes its target.")
            .setView(body).setNegativeButton("Cancel",null).setPositiveButton("Create link",null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val protocol=selectedProtocol(); val display=name.text.toString().trim()
                if(protocol!="custom") {
                    val selected=pickedConnection
                    if(selected!=null) {
                        val targetPath=when(selected.sourceTypeId){"smb"->if(!selected.options["initialShare"].isNullOrBlank()) "/${selected.options["initialShare"]!!.trim('/')}" else selected.options["initialPath"] ?: "/";else->selected.options["initialPath"] ?: "/"}
                        val target=Uri.Builder().scheme(selected.sourceTypeId).authority(selected.id).path(targetPath).build()
                        dialog.dismiss(); addContainerRootLink(ShortcutInfo(targetUri=target.toString(),displayNameOverride=display.ifEmpty { selected.displayName },targetIsDirectory=true,targetMimeType="vnd.android.document/directory"));return@setOnClickListener
                    }
                    if(display.isEmpty()){name.error="Enter a link name";return@setOnClickListener}
                    val saved:(ProviderConnection)->Unit={ connection ->
                        val targetPath=when(connection.sourceTypeId){"smb"->if(!connection.options["initialShare"].isNullOrBlank()) "/${connection.options["initialShare"]!!.trim('/')}" else connection.options["initialPath"] ?: "/";else->connection.options["initialPath"] ?: "/"}
                        val target=Uri.Builder().scheme(connection.sourceTypeId).authority(connection.id).path(targetPath).build()
                        addContainerRootLink(ShortcutInfo(targetUri=target.toString(),displayNameOverride=display.ifEmpty { connection.displayName },targetIsDirectory=true,targetMimeType="vnd.android.document/directory"))
                    }
                    val suppliedUri=initialUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    dialog.dismiss()
                    when(protocol){
                        "sftp"->showSftpConnectionDialog(initialName=display,initialUri=suppliedUri,onSaved=saved)
                        "smb"->showSmbConnectionDialog(initialName=display,initialUri=suppliedUri,onSaved=saved)
                        "webdav"->showWebDavConnectionDialog(initialName=display,initialUri=suppliedUri,onSaved=saved)
                        "ftp"->showFtpConnectionDialog(initialName=display,initialUri=suppliedUri,onSaved=saved)
                    }
                    return@setOnClickListener
                }
                val raw=uri.text.toString().trim(); val target=runCatching { Uri.parse(raw) }.getOrNull()
                if(raw.isEmpty() || target?.scheme.isNullOrBlank()){uri.error="Enter a valid URI";return@setOnClickListener}
                if(target==null || target==ContainerLinksProvider.ROOT){uri.error="Enter a container URI";return@setOnClickListener}
                if(ContainerUris.hasPassword(target)){uri.error="Use connection settings for the password";return@setOnClickListener}
                val title=display.ifEmpty { target.host ?: (if (target.isHierarchical) target.lastPathSegment else null) ?: "Container" }
                dialog.dismiss()
                bindContainerUri(target,title){canonical -> addContainerRootLink(ShortcutInfo(targetUri=containerUriHint(canonical).toString(),displayNameOverride=title,targetIsDirectory=true,targetMimeType="vnd.android.document/directory"))}
            }
        }
        dialog.show()
    }

    private fun addContainerRootLink(shortcut: ShortcutInfo) {
        io.execute {
            val name = WebMetadataResolver.sanitizeFileName(shortcut.displayNameOverride ?: "Container", "Container")
            val result = containerLinkProvider.add(shortcut, name)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is ProviderResult.Success -> { showHomeRoot(); Toast.makeText(this, "Container link added", Toast.LENGTH_SHORT).show() }
                    is ProviderResult.Failure -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    else -> Unit
                }
            }
        }
    }

    private fun refreshRootContainerLinks() {
        accessIo.execute {
            migrateContainerConnectionLinks()
            val entries = (containerLinkProvider.list(ResourceRef(containerLinkProvider.id, ContainerLinksProvider.ROOT.toString())) as? ProviderResult.Success)?.value.orEmpty()
            val links = entries.mapNotNull { entry ->
                val info = runCatching { (containerLinkProvider.openRead(entry.ref) as? ProviderResult.Success)?.value?.use { ShortcutInfo.fromJson(it.reader().readText()) } }.getOrNull()
                info?.let { entry to it }
            }
            rootContainerLinks = links
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    homeItems.removeAll { it.linkEntry != null }
                    homeItems.addAll(links.map { rootLinkHomeItem(it.first, it.second) })
                    if (homeMode && homePage == "root") renderHomeItems()
                    rebuildTreeRoots()
                }
            }
        }
    }

    private fun migrateContainerConnectionLinks() {
        savedConnections().filter { it.sourceTypeId in setOf("ftp", "sftp", "smb", "webdav") }.forEach { connection ->
            if (shared.getBoolean("root_link_migrated_${connection.id}", false)) return@forEach
            val path = if (connection.sourceTypeId == "smb" && !connection.options["initialShare"].isNullOrBlank())
                "/${connection.options["initialShare"].orEmpty().trim('/')}" else connection.options["initialPath"] ?: "/"
            val target = Uri.Builder().scheme(connection.sourceTypeId).authority(connection.id).path(path).build()
            val name = WebMetadataResolver.sanitizeFileName(connection.displayName, "Container")
            val result = containerLinkProvider.add(ShortcutInfo(targetUri = target.toString(),
                displayNameOverride = connection.displayName, targetIsDirectory = true,
                targetMimeType = "vnd.android.document/directory"), name)
            if (result is ProviderResult.Success) shared.edit().putBoolean("root_link_migrated_${connection.id}", true).apply()
        }
    }

    private fun rootLinkHomeItem(entry: ResourceEntry, shortcut: ShortcutInfo): HomeItem {
        shortcutCache.put(entry.uri.toString(), shortcut)
        val target = containerUriHint(Uri.parse(shortcut.targetUri))
        val browsable = provider.refForUri(target) != null
        val icon = when {
            ArchiveUri.isArchiveUri(target) -> "📦"
            target.scheme == "magnet" -> "🧲"
            else -> "🔗"
        }
        return HomeItem(icon, shortcut.displayNameOverride ?: entry.metadata.name.removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION),
            shortcut.targetPath ?: shortcut.targetUri, { openContainerLinkUri(target, shortcut.displayNameOverride, true, LinkNavigationOrigin.VIRTUAL_ROOT) },
            { showEntryMenu(entry) }, "root_link_${entry.uri}", target.takeIf { browsable }, browsable,
            linkEntry = entry)
    }

    private fun openContainerLinkUri(
        uri: Uri,
        name: String? = null,
        requireContainer: Boolean = false,
        origin: LinkNavigationOrigin = LinkNavigationOrigin.VIRTUAL_ROOT
    ) {
        val title = name ?: (if (uri.isHierarchical) uri.lastPathSegment else null) ?: "Container"
        if (uri.scheme.equals("magnet", true) || uri.scheme.equals("torrent", true)) {
            // Change the browser location before doing history, tree, metadata, or peer work.
            try {
                // The opaque magnet URI itself is a valid virtual root. Avoid provider
                // initialization here so the browser can navigate before any loading work.
                val target = uri
                logicalLocationPrefix = if (origin == LinkNavigationOrigin.EXTERNAL_HISTORY) "External link history" else
                    logicalLocationPrefix.takeIf { origin == LinkNavigationOrigin.DIRECTORY_LINK }
                currentProgramName = null
                if (origin != LinkNavigationOrigin.DIRECTORY_LINK) {
                    history.clear(); historyNames.clear()
                }
                current = null
                navigate(target, push = origin == LinkNavigationOrigin.DIRECTORY_LINK, displayName = title, deferInitialLoad = true)
                setTreeVisible(false)
                if (uri.scheme?.lowercase(Locale.ROOT) in externalLinkSchemes) recordExternalLinkVisit(uri, title)
            } catch (error: Exception) {
                dialogBuilder().setTitle("Cannot browse $title")
                    .setMessage(error.message ?: "Invalid magnet link")
                    .setPositiveButton("OK", null).show()
            }
            return
        }
        if (uri.scheme?.lowercase(Locale.ROOT) in externalLinkSchemes) recordExternalLinkVisit(uri, title)
        bindContainerUri(uri, title) { canonical ->
            val target = if (requireContainer) containerUriHint(canonical) else canonical
            val ref = provider.refForUri(target)
            if (ref == null) {
                dialogBuilder().setTitle(title).setMessage("No browsable storage provider is installed for ${uri.scheme} URIs yet. The link can remain on Virtual root until that provider is available.")
                    .setPositiveButton("OK", null).show()
                return@bindContainerUri
            }
            val generation = ++browserNavigationGeneration
            io.execute {
                try {
                    val entry = when (val result = provider.stat(ref)) {
                        is ProviderResult.Success -> result.value
                        is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                        else -> throw java.io.IOException("This provider cannot inspect the link target")
                    }
                    var destination: Uri? = if (entry.metadata.isDirectory) entry.uri else entryBrowseDestination(entry)
                    if (destination == null && requireContainer) {
                        val magic = if (localProvider.owns(entry.uri)) FileMagicDetector.detect(localProvider.fileFor(entry.uri), true)
                            else when (val opened = provider.openSeekable(entry.ref)) {
                                is ProviderResult.Success -> opened.value.use { dev -> FileMagicDetector.detectFromBytes(dev.readBytes(0, minOf(dev.size, 69632L).toInt())) }
                                else -> null
                            }
                        if (magic?.isArchiveOrDiscImage == true) destination = ArchiveUri.toArchiveUri(entry.uri)
                        else throw java.io.IOException("This link points to a regular file, not a browsable container")
                    }
                    val resolvedDestination = destination
                    runOnUiThread {
                        if (isFinishing || isDestroyed || generation != browserNavigationGeneration) return@runOnUiThread
                        if (resolvedDestination != null) {
                            // A container link is a navigation root, not a child of its previous location.
                            logicalLocationPrefix = null
                            currentProgramName = null
                            history.clear(); historyNames.clear(); current = null
                            setTreeVisible(false)
                            navigate(resolvedDestination, push = false, displayName = title)
                        }
                        else open(entry)
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing && generation == browserNavigationGeneration)
                            if (!com.infinifolder.viewer.CacheFailureUi.offerCopy(this, error, uri))
                                dialogBuilder().setTitle("Cannot browse $title").setMessage(error.message).setPositiveButton("OK", null).show()
                    }
                }
            }
        }
    }

    private fun virtualRootCrumb(active: Boolean): View = crumbText("Virtual root", setTextColorForTheme(), active) { showHomeRoot() }.apply {
        installBreadcrumbDropTarget(this, ContainerLinksProvider.ROOT, "Virtual root")
    }

    private fun savedConnections(): List<ProviderConnection> = com.infinifolder.storage.StorageRuntime.get(this).connections.list()

    private fun saveConnection(connection: ProviderConnection) {
        com.infinifolder.storage.StorageRuntime.get(this).connections.save(connection)
    }

    private fun removeConnection(connectionId: String) {
        com.infinifolder.storage.StorageRuntime.get(this).connections.remove(connectionId)
        sftpProvider.credentialStore.remove(connectionId)
        sftpProvider.clientPool.disconnect(connectionId)
        ftpProvider.credentialStore.remove(connectionId)
        ftpProvider.clientPool.disconnect(connectionId)
        smbProvider.credentialStore.remove(connectionId)
        smbProvider.clientPool.disconnect(connectionId)
        webDavProvider.credentialStore.remove(connectionId)
        showHomeRoot()
    }

    private fun showWebDavConnectionDialog(existing: ProviderConnection? = null, initialName: String = "", initialUrl: String = "", initialUri: Uri? = null, onSaved: ((ProviderConnection) -> Unit)? = null) {
        val context=dialogContext(); val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(10),dp(20),dp(10)) }; val scroll=ScrollView(context).apply { addView(body) }
        val name=EditText(context).apply { hint="Connection name"; setText(existing?.displayName ?: initialName.ifBlank { initialUri?.host ?: "" }) }
        val url=EditText(context).apply { hint="WebDAV URL (https://server/remote.php/dav/files/user/)"; setText(existing?.options?.get("url") ?: initialUrl.ifBlank { initialUri?.let { val scheme=when(it.scheme?.lowercase()){"davs","webdav"->"https";"dav"->"http";else->it.scheme}; "${scheme}://${it.host}${if(it.port>0) ":${it.port}" else ""}${it.path ?: "/"}" }.orEmpty() }) }
        val user=EditText(context).apply { hint="Username"; setText(existing?.options?.get("username") ?: initialUri?.userInfo.orEmpty()) }
        val password=EditText(context).apply { hint="Password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(existing?.let { webDavProvider.credentialStore.load(it.id)?.password }.orEmpty()) }
        val path=EditText(context).apply { hint="Initial path within collection"; setText(existing?.options?.get("initialPath") ?: "/") }
        body.addView(name);body.addView(url);body.addView(user);body.addView(password);body.addView(path)
        body.addView(TextView(context).apply { text="Use HTTPS when available. Basic authentication over HTTP sends credentials without transport encryption."; textSize=12f; setTextColor(if(dark) 0xFFFFCC80.toInt() else 0xFF8D4B00.toInt()); setPadding(0,dp(6),0,dp(4)) })
        dialogBuilder().setTitle(if(existing==null) "Add WebDAV Connection" else "Edit WebDAV Connection").setView(scroll).setNeutralButton("Test Connection",null).setNegativeButton("Cancel",null).setPositiveButton(if(existing==null) "Save & Connect" else "Save",null).create().also { dialog ->
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val endpoint=url.text.toString().trim(); if(!endpoint.startsWith("https://",true) && !endpoint.startsWith("http://",true)){url.error="Enter an http:// or https:// URL";return@setOnClickListener}
                    val c=ProviderConnection(id="webdav_test",sourceTypeId="webdav",displayName=name.text.toString().ifBlank { Uri.parse(endpoint).host ?: "WebDAV" },endpoint=endpoint,options=mapOf("url" to endpoint,"username" to user.text.toString().trim(),"initialPath" to path.text.toString().ifBlank { "/" }))
                    val testUser=user.text.toString().trim(); val testPassword=password.text.toString()
                    val progress=android.app.ProgressDialog(this).apply { setMessage("Testing WebDAV connection…");setCancelable(false);show() }
                    accessIo.execute { val result=webDavProvider.testConnection(c,testUser,testPassword);runOnUiThread { progress.dismiss();result.onSuccess { msg -> dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK",null).show() }.onFailure { e -> dialogBuilder().setTitle("Connection Failed").setMessage(e.message ?: "Unknown error").setPositiveButton("OK",null).show() } } }
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val endpoint=url.text.toString().trim();val parsed=runCatching { Uri.parse(endpoint) }.getOrNull()
                    if(parsed==null || parsed.scheme !in setOf("https","http") || parsed.host.isNullOrBlank() || !parsed.userInfo.isNullOrEmpty()){url.error="Enter a valid http:// or https:// WebDAV URL without a username or password";return@setOnClickListener}
                    val id=existing?.id ?: UUID.randomUUID().toString();val display=name.text.toString().trim().ifBlank { parsed.host!! };val initial=path.text.toString().trim().ifBlank { "/" }
                    webDavProvider.credentialStore.save(id,WebDavCredentials(password.text.toString().ifEmpty { null }))
                    val options=mapOf("url" to endpoint,"host" to parsed.host.orEmpty(),"port" to (if(parsed.port>0)parsed.port else if(parsed.scheme=="https")443 else 80).toString(),"tls" to (parsed.scheme=="https").toString(),"username" to user.text.toString().trim(),"initialPath" to initial)
                    val connection=ProviderConnection(id=id,sourceTypeId="webdav",displayName=display,endpoint=endpoint,options=options,credentialHandle="webdav_vault")
                    saveConnection(connection);dialog.dismiss()
                    if(onSaved!=null){onSaved(connection);return@setOnClickListener}
                    showHomeRoot();openRoot(WebDavUri.build(id,initial),rememberAuthorizedRoot=false)
                }
            }
        }.show()
    }

    private fun showSavedConnectionActions(connection: ProviderConnection) {
        if (connection.sourceTypeId == "sftp") {
            dialogBuilder().setTitle(connection.displayName).setItems(arrayOf("Open", "Test connection", "Edit connection", "Remove saved connection")) { _, which ->
                when (which) {
                    0 -> {
                        val rootUri = SftpUri.build(connection.id, connection.options["initialPath"] ?: "/")
                        openRoot(rootUri, rememberAuthorizedRoot = false)
                    }
                    1 -> {
                        val creds = sftpProvider.credentialStore.load(connection.id)
                        if (creds == null) {
                            Toast.makeText(this, "No saved credentials found", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val endpoint = connection.endpoint ?: "localhost:22"
                        val host = connection.options["host"]?.ifEmpty { null }
                            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
                        val port = connection.options["port"]?.toIntOrNull()
                            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 22
                        val config = SftpConfig(
                            id = connection.id,
                            displayName = connection.displayName,
                            host = host,
                            port = port,
                            username = connection.options["username"] ?: "root",
                            initialPath = connection.options["initialPath"] ?: "/"
                        )
                        val progress = android.app.ProgressDialog(this).apply {
                            setMessage("Testing connection to ${config.host}:${config.port}…")
                            setCancelable(false)
                            show()
                        }
                        accessIo.execute {
                            val res = sftpProvider.clientPool.testConnection(config, creds)
                            runOnUiThread {
                                progress.dismiss()
                                res.onSuccess { msg ->
                                    dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK", null).show()
                                }.onFailure { err ->
                                    dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }
                    2 -> showSftpConnectionDialog(existing = connection)
                    3 -> dialogBuilder().setMessage("Remove ${connection.displayName} from the Virtual root?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            removeConnection(connection.id)
                        }.show()
                }
            }.show()
            return
        }
        if (connection.sourceTypeId == "ftp") {
            dialogBuilder().setTitle(connection.displayName).setItems(arrayOf("Open", "Test connection", "Edit connection", "Remove saved connection")) { _, which ->
                when (which) {
                    0 -> {
                        val rootUri = FtpUri.build(connection.id, connection.options["initialPath"] ?: "/")
                        openRoot(rootUri, rememberAuthorizedRoot = false)
                    }
                    1 -> {
                        val creds = ftpProvider.credentialStore.load(connection.id) ?: FtpCredentials()
                        val endpoint = connection.endpoint ?: "localhost:21"
                        val host = connection.options["host"]?.ifEmpty { null }
                            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
                        val port = connection.options["port"]?.toIntOrNull()
                            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 21
                        val username = connection.options["username"] ?: "anonymous"
                        val isAnon = connection.options["isAnonymous"]?.toBooleanStrictOrNull() ?: (username == "anonymous")
                        val config = FtpConfig(
                            id = connection.id,
                            displayName = connection.displayName,
                            host = host,
                            port = port,
                            username = username,
                            isAnonymous = isAnon,
                            initialPath = connection.options["initialPath"] ?: "/",
                            useFtps = connection.options["useFtps"]?.toBooleanStrictOrNull() ?: false,
                            passiveMode = connection.options["passiveMode"]?.toBooleanStrictOrNull() ?: true
                        )
                        val progress = android.app.ProgressDialog(this).apply {
                            setMessage("Testing connection to ${config.host}:${config.port}…")
                            setCancelable(false)
                            show()
                        }
                        accessIo.execute {
                            val res = ftpProvider.clientPool.testConnection(config, creds)
                            runOnUiThread {
                                progress.dismiss()
                                res.onSuccess { msg ->
                                    dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK", null).show()
                                }.onFailure { err ->
                                    dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }
                    2 -> showFtpConnectionDialog(existing = connection)
                    3 -> dialogBuilder().setMessage("Remove ${connection.displayName} from the Virtual root?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            removeConnection(connection.id)
                        }.show()
                }
            }.show()
            return
        }
        if (connection.sourceTypeId == "webdav") {
            dialogBuilder().setTitle(connection.displayName).setItems(arrayOf("Open", "Test connection", "Edit connection", "Remove saved connection")) { _, which ->
                when(which) {
                    0 -> openRoot(WebDavUri.build(connection.id, connection.options["initialPath"] ?: "/"), rememberAuthorizedRoot=false)
                    1 -> {
                        val cfg=connection; val user=cfg.options["username"].orEmpty(); val pass=webDavProvider.credentialStore.load(cfg.id)?.password.orEmpty()
                        val progress=android.app.ProgressDialog(this).apply { setMessage("Testing WebDAV connection…"); setCancelable(false); show() }
                        accessIo.execute { val result=webDavProvider.testConnection(cfg,user,pass); runOnUiThread { progress.dismiss(); result.onSuccess { msg -> dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK",null).show() }.onFailure { err -> dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK",null).show() } } }
                    }
                    2 -> showWebDavConnectionDialog(existing=connection)
                    3 -> dialogBuilder().setMessage("Remove ${connection.displayName} from the Virtual root?").setNegativeButton("Cancel",null).setPositiveButton("Remove") { _,_ -> removeConnection(connection.id) }.show()
                }
            }.show()
            return
        }
        if (connection.sourceTypeId == "smb") {
            dialogBuilder().setTitle(connection.displayName).setItems(arrayOf("Open", "Test connection", "Edit connection", "Remove saved connection")) { _, which ->
                when (which) {
                    0 -> {
                        val initialPath = if (!connection.options["initialShare"].isNullOrBlank()) {
                            "/${connection.options["initialShare"]!!.trim('/')}"
                        } else {
                            connection.options["initialPath"] ?: "/"
                        }
                        val rootUri = SmbUri.build(connection.id, initialPath)
                        openRoot(rootUri, rememberAuthorizedRoot = false)
                    }
                    1 -> {
                        val creds = smbProvider.credentialStore.load(connection.id) ?: SmbCredentials()
                        val endpoint = connection.endpoint ?: "localhost:445"
                        val host = connection.options["host"]?.ifEmpty { null }
                            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
                        val port = connection.options["port"]?.toIntOrNull()
                            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 445
                        val domain = connection.options["domain"] ?: "WORKGROUP"
                        val username = connection.options["username"] ?: ""
                        val isGuest = connection.options["isGuest"]?.toBooleanStrictOrNull() ?: false
                        val initialShare = connection.options["initialShare"]?.ifEmpty { null }
                        val initialPath = connection.options["initialPath"] ?: "/"
                        val config = SmbConfig(
                            id = connection.id,
                            displayName = connection.displayName,
                            host = host,
                            port = port,
                            domain = domain,
                            username = username,
                            isGuest = isGuest,
                            initialShare = initialShare,
                            initialPath = initialPath
                        )
                        val progress = android.app.ProgressDialog(this).apply {
                            setMessage("Testing connection to ${config.host}:${config.port}…")
                            setCancelable(false)
                            show()
                        }
                        accessIo.execute {
                            val res = smbProvider.clientPool.testConnection(config, creds)
                            runOnUiThread {
                                progress.dismiss()
                                res.onSuccess { msg ->
                                    dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK", null).show()
                                }.onFailure { err ->
                                    dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }
                    2 -> showSmbConnectionDialog(existing = connection)
                    3 -> dialogBuilder().setMessage("Remove ${connection.displayName} from the Virtual root?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            removeConnection(connection.id)
                        }.show()
                }
            }.show()
            return
        }
        dialogBuilder().setTitle(connection.displayName).setItems(arrayOf("Properties", "Remove saved connection")) { _, which ->
            when (which) {
                0 -> dialogBuilder().setMessage("Type: ${connection.sourceTypeId}\nServer: ${connection.endpoint ?: "Not set"}\nStatus: saved; provider support is not implemented").setPositiveButton("OK", null).show()
                1 -> dialogBuilder().setMessage("Remove ${connection.displayName} from the Virtual root?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        removeConnection(connection.id)
                    }.show()
            }
        }.show()
    }

    private fun showSftpConnectionDialog(
        existing: ProviderConnection? = null,
        initialName: String = "",
        initialEndpoint: String = "",
        initialUri: Uri? = null,
        onSaved: ((ProviderConnection) -> Unit)? = null
    ) {
        val context = dialogContext()
        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(body)

        val nameInput = EditText(context).apply {
            hint = "Display name (e.g. My Server)"
            setText(existing?.displayName ?: initialName)
            setSingleLine(true)
        }

        val existingEndpoint = existing?.endpoint ?: initialEndpoint
        val parsedHost = initialUri?.host?.trim('[', ']') ?: if (existingEndpoint.contains(':')) existingEndpoint.substringBefore(':') else existingEndpoint
        val parsedPort = initialUri?.port?.takeIf { it > 0 } ?: (if (existingEndpoint.contains(':')) existingEndpoint.substringAfter(':').toIntOrNull() else null)
            ?: existing?.options?.get("port")?.toIntOrNull() ?: 22

        val hostInput = EditText(context).apply {
            hint = "Host / IP address (e.g. 192.168.1.100)"
            setText(existing?.options?.get("host") ?: parsedHost)
            setSingleLine(true)
        }

        val portInput = EditText(context).apply {
            hint = "Port (default 22)"
            setText(parsedPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }

        val userInput = EditText(context).apply {
            hint = "Username (e.g. root or user)"
            setText(existing?.options?.get("username") ?: initialUri?.userInfo.orEmpty())
            setSingleLine(true)
        }

        val pathInput = EditText(context).apply {
            hint = "Initial remote path (default /)"
            setText(existing?.options?.get("initialPath") ?: initialUri?.path?.takeIf { it.isNotBlank() } ?: "/")
            setSingleLine(true)
        }

        val authGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val rbPassword = RadioButton(context).apply {
            text = "Password"
            id = View.generateViewId()
        }
        val rbKeyCert = RadioButton(context).apply {
            text = "Key & Certificate"
            id = View.generateViewId()
        }
        authGroup.addView(rbPassword)
        authGroup.addView(rbKeyCert)

        val passwordContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val passwordInput = EditText(context).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        passwordContainer.addView(passwordInput)

        val keyCertContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun createPickerRow(label: String, targetInput: EditText): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(2))
            }
            val title = TextView(context).apply {
                text = label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val btnBrowse = Button(context, null, android.R.attr.buttonStyleSmall).apply {
                text = "Browse…"
                setOnClickListener {
                    pendingSftpFileCallback = { text -> targetInput.setText(text) }
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(intent, pickSftpFileRequest)
                }
            }
            val btnPaste = Button(context, null, android.R.attr.buttonStyleSmall).apply {
                text = "Paste"
                setOnClickListener {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    if (!text.isNullOrEmpty()) {
                        targetInput.setText(text)
                        Toast.makeText(this@MainActivity, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            row.addView(title)
            row.addView(btnBrowse)
            row.addView(btnPaste)
            return row
        }

        val keyInput = EditText(context).apply {
            hint = "Private key (OpenSSH / PEM format)"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            maxLines = 5
        }
        val certInput = EditText(context).apply {
            hint = "Certificate (-cert.pub, optional)"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            maxLines = 4
        }
        val passphraseInput = EditText(context).apply {
            hint = "Key passphrase (optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        keyCertContainer.addView(createPickerRow("Private Key:", keyInput))
        keyCertContainer.addView(keyInput)
        keyCertContainer.addView(createPickerRow("OpenSSH Certificate:", certInput))
        keyCertContainer.addView(certInput)
        keyCertContainer.addView(passphraseInput)

        authGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == rbPassword.id) {
                passwordContainer.visibility = View.VISIBLE
                keyCertContainer.visibility = View.GONE
            } else {
                passwordContainer.visibility = View.GONE
                keyCertContainer.visibility = View.VISIBLE
            }
        }

        if (existing != null) {
            val existingCreds = sftpProvider.credentialStore.load(existing.id)
            if (existingCreds?.authType == SftpAuthType.KEY_CERT) {
                rbKeyCert.isChecked = true
                keyInput.setText(existingCreds.privateKey ?: "")
                certInput.setText(existingCreds.certificate ?: "")
                passphraseInput.setText(existingCreds.passphrase ?: "")
            } else {
                rbPassword.isChecked = true
                passwordInput.setText(existingCreds?.password ?: "")
            }
        } else {
            rbPassword.isChecked = true
        }

        body.addView(nameInput)
        body.addView(hostInput)
        body.addView(portInput)
        body.addView(userInput)
        body.addView(pathInput)
        body.addView(authGroup)
        body.addView(passwordContainer)
        body.addView(keyCertContainer)

        fun extractConfigAndCreds(id: String): Pair<SftpConfig, SftpCredentials>? {
            val host = hostInput.text.toString().trim()
            if (host.isEmpty()) { hostInput.error = "Host is required"; return null }
            val port = portInput.text.toString().trim().toIntOrNull() ?: 22
            val username = userInput.text.toString().trim()
            if (username.isEmpty()) { userInput.error = "Username is required"; return null }
            val displayName = nameInput.text.toString().trim().ifEmpty { host }
            val initialPath = pathInput.text.toString().trim().ifEmpty { "/" }

            val authType = if (rbPassword.isChecked) SftpAuthType.PASSWORD else SftpAuthType.KEY_CERT
            val creds = if (authType == SftpAuthType.PASSWORD) {
                val pwd = passwordInput.text.toString()
                SftpCredentials(authType = SftpAuthType.PASSWORD, password = pwd)
            } else {
                val prvKey = keyInput.text.toString().trim()
                if (prvKey.isEmpty()) { keyInput.error = "Private key is required"; return null }
                val cert = certInput.text.toString().trim().ifEmpty { null }
                val pass = passphraseInput.text.toString().ifEmpty { null }
                SftpCredentials(
                    authType = SftpAuthType.KEY_CERT,
                    privateKey = prvKey,
                    certificate = cert,
                    passphrase = pass
                )
            }
            val config = SftpConfig(
                id = id,
                displayName = displayName,
                host = host,
                port = port,
                username = username,
                initialPath = initialPath
            )
            return config to creds
        }

        dialogBuilder()
            .setTitle(if (existing != null) "Edit SFTP Connection" else "Add SFTP / SSH Connection")
            .setView(scroll)
            .setNeutralButton("Test Connection", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing != null) "Save" else "Save & Connect", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val pair = extractConfigAndCreds("test_temp_id") ?: return@setOnClickListener
                        val (config, creds) = pair
                        val progress = android.app.ProgressDialog(this).apply {
                            setMessage("Testing connection to ${config.host}:${config.port}…")
                            setCancelable(false)
                            show()
                        }
                        accessIo.execute {
                            val res = sftpProvider.clientPool.testConnection(config, creds)
                            runOnUiThread {
                                progress.dismiss()
                                res.onSuccess { msg ->
                                    dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK", null).show()
                                }.onFailure { err ->
                                    dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }

                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val connectionId = existing?.id ?: UUID.randomUUID().toString()
                        val pair = extractConfigAndCreds(connectionId) ?: return@setOnClickListener
                        val (config, creds) = pair

                        sftpProvider.credentialStore.save(connectionId, creds)
                        if (existing != null) {
                            sftpProvider.clientPool.disconnect(connectionId)
                        }

                        val endpoint = if (config.port == 22) config.host else "${config.host}:${config.port}"
                        val connection = ProviderConnection(
                            id = connectionId,
                            sourceTypeId = "sftp",
                            displayName = config.displayName,
                            endpoint = endpoint,
                            options = mapOf(
                                "host" to config.host,
                                "port" to config.port.toString(),
                                "username" to config.username,
                                "initialPath" to config.initialPath
                            ),
                            credentialHandle = "sftp_vault"
                        )
                        saveConnection(connection)
                        dialog.dismiss()
                        if (onSaved != null) { onSaved(connection); return@setOnClickListener }
                        showHomeRoot()

                        val rootUri = SftpUri.build(connectionId, config.initialPath)
                        openRoot(rootUri, rememberAuthorizedRoot = false)
                    }
                }
                dialog.show()
            }
    }

    private fun showFtpConnectionDialog(
        existing: ProviderConnection? = null,
        initialName: String = "",
        initialEndpoint: String = "",
        initialUri: Uri? = null,
        onSaved: ((ProviderConnection) -> Unit)? = null
    ) {
        val context = dialogContext()
        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        scroll.addView(body)

        val nameInput = EditText(context).apply {
            hint = "Connection name"
            setText(existing?.displayName ?: initialName)
        }
        val hostInput = EditText(context).apply {
            hint = "Host / Server IP"
            setText(existing?.options?.get("host") ?: initialUri?.host?.trim('[', ']') ?: initialEndpoint.substringBefore(':'))
        }
        val portInput = EditText(context).apply {
            hint = "Port (default 21)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.options?.get("port") ?: initialUri?.port?.takeIf { it > 0 }?.toString() ?: (if (initialEndpoint.contains(':')) initialEndpoint.substringAfter(':') else "21"))
        }
        val anonCheck = CheckBox(context).apply {
            text = "Anonymous login"
            isChecked = existing?.options?.get("isAnonymous")?.toBooleanStrictOrNull() ?: false
        }
        val userInput = EditText(context).apply {
            hint = "Username"
            setText(existing?.options?.get("username") ?: initialUri?.userInfo ?: "anonymous")
        }
        val passInput = EditText(context).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (existing != null) {
                val creds = ftpProvider.credentialStore.load(existing.id)
                setText(creds?.password ?: "")
            }
        }
        val pathInput = EditText(context).apply {
            hint = "Initial path (default /)"
            setText(existing?.options?.get("initialPath") ?: initialUri?.path?.takeIf { it.isNotBlank() } ?: "/")
        }
        val ftpsCheck = CheckBox(context).apply {
            text = "Use FTPS (SSL/TLS)"
            isChecked = existing?.options?.get("useFtps")?.toBooleanStrictOrNull() ?: initialUri?.scheme.equals("ftps", true)
        }
        val pasvCheck = CheckBox(context).apply {
            text = "Passive mode (PASV)"
            isChecked = existing?.options?.get("passiveMode")?.toBooleanStrictOrNull() ?: true
        }

        fun updateAnonVisibility() {
            if (anonCheck.isChecked) {
                userInput.visibility = View.GONE
                passInput.visibility = View.GONE
            } else {
                userInput.visibility = View.VISIBLE
                passInput.visibility = View.VISIBLE
            }
        }
        anonCheck.setOnCheckedChangeListener { _, _ -> updateAnonVisibility() }
        updateAnonVisibility()

        body.addView(nameInput)
        body.addView(hostInput)
        body.addView(portInput)
        body.addView(anonCheck)
        body.addView(userInput)
        body.addView(passInput)
        body.addView(pathInput)
        body.addView(ftpsCheck)
        body.addView(pasvCheck)

        fun extractConfigAndCreds(id: String): Pair<FtpConfig, FtpCredentials>? {
            val host = hostInput.text.toString().trim()
            if (host.isEmpty()) { hostInput.error = "Host is required"; return null }
            val port = portInput.text.toString().trim().toIntOrNull() ?: 21
            val isAnon = anonCheck.isChecked
            val username = if (isAnon) "anonymous" else userInput.text.toString().trim()
            if (!isAnon && username.isEmpty()) { userInput.error = "Username is required"; return null }
            val displayName = nameInput.text.toString().trim().ifEmpty { host }
            val initialPath = pathInput.text.toString().trim().ifEmpty { "/" }

            val creds = FtpCredentials(password = if (isAnon) null else passInput.text.toString())
            val config = FtpConfig(
                id = id,
                displayName = displayName,
                host = host,
                port = port,
                username = username,
                isAnonymous = isAnon,
                initialPath = initialPath,
                useFtps = ftpsCheck.isChecked,
                passiveMode = pasvCheck.isChecked
            )
            return config to creds
        }

        dialogBuilder()
            .setTitle(if (existing != null) "Edit FTP Connection" else "Add FTP Connection")
            .setView(scroll)
            .setNeutralButton("Test Connection", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing != null) "Save" else "Save & Connect", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val pair = extractConfigAndCreds("test_temp_id") ?: return@setOnClickListener
                        val (config, creds) = pair
                        val progress = android.app.ProgressDialog(this).apply {
                            setMessage("Testing connection to ${config.host}:${config.port}…")
                            setCancelable(false)
                            show()
                        }
                        accessIo.execute {
                            val res = ftpProvider.clientPool.testConnection(config, creds)
                            runOnUiThread {
                                progress.dismiss()
                                res.onSuccess { msg ->
                                    dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK", null).show()
                                }.onFailure { err ->
                                    dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }

                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val connectionId = existing?.id ?: UUID.randomUUID().toString()
                        val pair = extractConfigAndCreds(connectionId) ?: return@setOnClickListener
                        val (config, creds) = pair

                        ftpProvider.credentialStore.save(connectionId, creds)
                        if (existing != null) {
                            ftpProvider.clientPool.disconnect(connectionId)
                        }

                        val endpoint = if (config.port == 21) config.host else "${config.host}:${config.port}"
                        val connection = ProviderConnection(
                            id = connectionId,
                            sourceTypeId = "ftp",
                            displayName = config.displayName,
                            endpoint = endpoint,
                            options = mapOf(
                                "host" to config.host,
                                "port" to config.port.toString(),
                                "username" to config.username,
                                "isAnonymous" to config.isAnonymous.toString(),
                                "initialPath" to config.initialPath,
                                "useFtps" to config.useFtps.toString(),
                                "passiveMode" to config.passiveMode.toString()
                            ),
                            credentialHandle = "ftp_vault"
                        )
                        saveConnection(connection)
                        dialog.dismiss()
                        if (onSaved != null) { onSaved(connection); return@setOnClickListener }
                        showHomeRoot()

                        val rootUri = FtpUri.build(connectionId, config.initialPath)
                        openRoot(rootUri, rememberAuthorizedRoot = false)
                    }
                }
                dialog.show()
            }
    }

    private fun showSmbDiscoveryDialog(onSelected: (DiscoveredSmbServer) -> Unit) {
        val discovery = SmbDiscoveryService(applicationContext)
        val serverList = mutableListOf<DiscoveredSmbServer>()
        val serverAdapter = object : ArrayAdapter<DiscoveredSmbServer>(this, android.R.layout.simple_list_item_2, serverList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val server = getItem(position) ?: return view
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
                text1.text = "🖧  ${server.name}"
                val wg = if (!server.workgroup.isNullOrBlank()) "  ·  [${server.workgroup}]" else ""
                text2.text = "${server.host}:${server.port}$wg  ·  ${server.source}"
                return view
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        val progressLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
        }
        val statusText = TextView(this).apply {
            text = "Scanning local network for Samba servers…"
            textSize = 14f
        }
        progressLayout.addView(progressBar)
        progressLayout.addView(statusText)

        val listView = ListView(this).apply {
            adapter = serverAdapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
        }
        container.addView(progressLayout)
        container.addView(listView)

        val dialog = dialogBuilder()
            .setTitle("Discover Samba Servers")
            .setView(container)
            .setNegativeButton("Close") { _, _ -> discovery.cancel() }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val chosen = serverList.getOrNull(position) ?: return@setOnItemClickListener
            discovery.cancel()
            dialog.dismiss()
            onSelected(chosen)
        }

        dialog.setOnDismissListener {
            discovery.cancel()
        }

        dialog.show()

        accessIo.execute {
            discovery.discoverServers(
                onServerFound = { srv ->
                    runOnUiThread {
                        val idx = serverList.indexOfFirst { it.host == srv.host }
                        if (idx == -1) {
                            serverList.add(srv)
                            serverAdapter.notifyDataSetChanged()
                        } else if (serverList[idx].name == serverList[idx].host && srv.name != srv.host) {
                            serverList[idx] = srv
                            serverAdapter.notifyDataSetChanged()
                        }
                    }
                },
                onProgress = { scanned, total ->
                    runOnUiThread {
                        if (total > 0) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = (scanned * 100) / total
                            statusText.text = "Scanned $scanned/$total hosts… (${serverList.size} found)"
                        }
                    }
                },
                onFinished = {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        statusText.text = if (serverList.isEmpty()) "No Samba servers found on LAN." else "Scan complete. Found ${serverList.size} server(s)."
                    }
                }
            )
        }
    }

    private fun showSmbConnectionDialog(
        existing: ProviderConnection? = null,
        initialName: String = "",
        initialEndpoint: String = "",
        initialUri: Uri? = null,
        onSaved: ((ProviderConnection) -> Unit)? = null
    ) {
        val context = dialogContext()
        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        scroll.addView(body)

        val nameInput = EditText(context).apply {
            hint = "Connection name"
            setText(existing?.displayName ?: initialName)
        }
        val hostInput = EditText(context).apply {
            hint = "Server host or IP address"
            setText(existing?.options?.get("host") ?: initialUri?.host?.trim('[', ']') ?: initialEndpoint.substringBefore(':'))
        }
        val portInput = EditText(context).apply {
            hint = "Port (default 445)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.options?.get("port") ?: initialUri?.port?.takeIf { it > 0 }?.toString() ?: (if (initialEndpoint.contains(':')) initialEndpoint.substringAfter(':') else "445"))
        }
        val domainInput = EditText(context).apply {
            hint = "Workgroup / Domain (default WORKGROUP)"
            setText(existing?.options?.get("domain") ?: "WORKGROUP")
        }
        val guestCheck = CheckBox(context).apply {
            text = "Guest / Anonymous access"
            isChecked = existing?.options?.get("isGuest")?.toBooleanStrictOrNull() ?: false
        }
        val userInput = EditText(context).apply {
            hint = "Username"
            setText(existing?.options?.get("username") ?: initialUri?.userInfo ?: "")
        }
        val passInput = EditText(context).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (existing != null) {
                val creds = smbProvider.credentialStore.load(existing.id)
                setText(creds?.password ?: "")
            }
        }
        val shareInput = EditText(context).apply {
            hint = "Share name (e.g. shared, media, c$)"
            setText(existing?.options?.get("initialShare") ?: initialUri?.pathSegments?.firstOrNull().orEmpty())
        }
        val shareTip = TextView(context).apply {
            text = "💡 Tip: If your server restricts browsing the public share list (common on Windows 10/11), enter your Share name directly."
            textSize = 11f
            setTextColor(if (dark) 0xFF9BA39B.toInt() else 0xFF656D65.toInt())
            setPadding(dp(4), dp(0), dp(4), dp(4))
        }
        val pathInput = EditText(context).apply {
            hint = "Initial path within share (default /)"
            setText(existing?.options?.get("initialPath") ?: initialUri?.pathSegments?.drop(1)?.joinToString("/", prefix = "/") ?: "/")
        }

        val discoverButton = Button(context).apply {
            text = "🔍 Discover Samba Servers on LAN"
            setOnClickListener {
                showSmbDiscoveryDialog { srv ->
                    hostInput.setText(srv.host)
                    if (nameInput.text.isNullOrEmpty() || nameInput.text.toString() == hostInput.text.toString()) {
                        nameInput.setText(srv.name)
                    }
                    portInput.setText(srv.port.toString())
                    if (!srv.workgroup.isNullOrBlank()) {
                        domainInput.setText(srv.workgroup)
                    }
                }
            }
        }

        fun updateGuestVisibility() {
            if (guestCheck.isChecked) {
                userInput.visibility = View.GONE
                passInput.visibility = View.GONE
            } else {
                userInput.visibility = View.VISIBLE
                passInput.visibility = View.VISIBLE
            }
        }
        guestCheck.setOnCheckedChangeListener { _, _ -> updateGuestVisibility() }
        updateGuestVisibility()

        body.addView(discoverButton)
        body.addView(nameInput)
        body.addView(hostInput)
        body.addView(portInput)
        body.addView(domainInput)
        body.addView(guestCheck)
        body.addView(userInput)
        body.addView(passInput)
        body.addView(shareInput)
        body.addView(shareTip)
        body.addView(pathInput)

        fun extractConfigAndCreds(id: String): Pair<SmbConfig, SmbCredentials>? {
            val host = hostInput.text.toString().trim()
            if (host.isEmpty()) { hostInput.error = "Host is required"; return null }
            val port = portInput.text.toString().trim().toIntOrNull() ?: 445
            val isGuest = guestCheck.isChecked
            val username = if (isGuest) "" else userInput.text.toString().trim()
            val domain = domainInput.text.toString().trim().ifEmpty { "WORKGROUP" }
            val displayName = nameInput.text.toString().trim().ifEmpty { host }
            val share = shareInput.text.toString().trim().trim('/').ifEmpty { null }
            val initialPath = pathInput.text.toString().trim().ifEmpty { "/" }

            val creds = SmbCredentials(password = if (isGuest) null else passInput.text.toString())
            val config = SmbConfig(
                id = id,
                displayName = displayName,
                host = host,
                port = port,
                domain = domain,
                username = username,
                isGuest = isGuest,
                initialShare = share,
                initialPath = initialPath
            )
            return config to creds
        }

        dialogBuilder()
            .setTitle(if (existing != null) "Edit SMB / Samba Connection" else "Add SMB / Samba Connection")
            .setView(scroll)
            .setNeutralButton("Test Connection", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing != null) "Save" else "Save & Connect", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val pair = extractConfigAndCreds("test_temp_id") ?: return@setOnClickListener
                        val (config, creds) = pair
                        val progress = android.app.ProgressDialog(this).apply {
                            setMessage("Testing connection to ${config.host}:${config.port}…")
                            setCancelable(false)
                            show()
                        }
                        accessIo.execute {
                            val res = smbProvider.clientPool.testConnection(config, creds)
                            runOnUiThread {
                                progress.dismiss()
                                res.onSuccess { msg ->
                                    dialogBuilder().setTitle("Connection Succeeded").setMessage(msg).setPositiveButton("OK", null).show()
                                }.onFailure { err ->
                                    dialogBuilder().setTitle("Connection Failed").setMessage(err.message ?: "Unknown error").setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }

                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val connectionId = existing?.id ?: UUID.randomUUID().toString()
                        val pair = extractConfigAndCreds(connectionId) ?: return@setOnClickListener
                        val (config, creds) = pair

                        smbProvider.credentialStore.save(connectionId, creds)
                        if (existing != null) {
                            smbProvider.clientPool.disconnect(connectionId)
                        }

                        val endpoint = if (config.port == 445) config.host else "${config.host}:${config.port}"
                        val connection = ProviderConnection(
                            id = connectionId,
                            sourceTypeId = "smb",
                            displayName = config.displayName,
                            endpoint = endpoint,
                            options = mapOf(
                                "host" to config.host,
                                "port" to config.port.toString(),
                                "domain" to config.domain,
                                "username" to config.username,
                                "isGuest" to config.isGuest.toString(),
                                "initialShare" to (config.initialShare ?: ""),
                                "initialPath" to config.initialPath
                            ),
                            credentialHandle = "smb_vault"
                        )
                        saveConnection(connection)
                        dialog.dismiss()
                        if (onSaved != null) { onSaved(connection); return@setOnClickListener }
                        showHomeRoot()

                        val initialPath = if (!config.initialShare.isNullOrBlank()) "/${config.initialShare.orEmpty().trim('/')}" else "/"
                        val rootUri = SmbUri.build(connectionId, initialPath)
                        openRoot(rootUri, rememberAuthorizedRoot = false)
                    }
                }
                dialog.show()
            }
    }
    private fun openOrRequestSource(initialUri: Uri, key: String) {
        val logicalPrefix = if (key.startsWith("public_")) "Internal shared storage" else null
        treeSourceUri(key)?.let { openRoot(it, logicalPrefix); return }
        val requestGeneration = ++accessRequestGeneration
        val locationGeneration = loadGeneration
        status.text = "Checking access to ${initialUri.lastPathSegment ?: "folder"}…"
        accessIo.execute {
            val existing = findAuthorizedDocument(initialUri)
            runOnUiThread {
                if (requestGeneration != accessRequestGeneration || locationGeneration != loadGeneration || isFinishing) return@runOnUiThread
                if (existing != null) {
                    shared.edit().putString("tree_source_$key", existing.toString()).apply()
                    val index = homeItems.indexOfFirst { it.treeKey == key }
                    if (index >= 0) {
                        val item = homeItems[index]
                        homeItems[index] = item.copy(
                            treeUri = existing,
                            detail = "Authorized folder",
                            action = { openRoot(existing) },
                            needsAccessBadge = false
                        )
                    }
                    rebuildTreeRoots()
                    if (homeMode) renderHomeItems()
                    openRoot(existing, logicalPrefix)
                } else {
                    status.text = "Requesting access…"
                    rootPicker(initialUri, key)
                }
            }
        }
    }
    private fun resolveExistingHomeGrants() {
        val generation = ++permissionQueryGeneration
        val candidates = homeItems.mapNotNull { item ->
            val key = item.treeKey ?: return@mapNotNull null
            val target = item.initialUri ?: item.treeUri ?: return@mapNotNull null
            if (localProvider.owns(target) || AppsUri.isAppsUri(target) || SftpUri.isSftpUri(target) ||
                FtpUri.isFtpUri(target) || SmbUri.isSmbUri(target) || WebDavUri.owns(target) ||
                ArchiveUri.isArchiveUri(target) || key.startsWith("sftp_") || key.startsWith("ftp_") || key.startsWith("smb_") || key.startsWith("archive_") ||
                key == "internal_shared" || key == "application_public" || key == "installed_apps") return@mapNotNull null
            key to target
        }
        if (candidates.isEmpty()) return
        accessIo.execute {
            val matches = candidates.map { (key, target) ->
                key to findAuthorizedDocument(target)
            }
            runOnUiThread {
                if (generation != permissionQueryGeneration || isFinishing) return@runOnUiThread
                matches.forEach { (key, uri) ->
                    val index = homeItems.indexOfFirst { it.treeKey == key }
                    if (index >= 0) {
                        val item = homeItems[index]
                        if (uri != null) {
                            shared.edit().putString("tree_source_$key", uri.toString()).apply()
                            val prefix = if (key.startsWith("public_")) "Internal shared storage" else null
                            homeItems[index] = item.copy(treeUri = uri, detail = "Authorized folder",
                                action = { openRoot(uri, prefix) }, needsAccessBadge = false)
                        } else {
                            shared.edit().remove("tree_source_$key").apply()
                            val target = item.initialUri ?: item.treeUri
                            val reauthorize: () -> Unit = if (item.initialUri != null) {
                                { openOrRequestSource(item.initialUri, key) }
                            } else if (target != null) {
                                { rootPicker(target, key) }
                            } else item.action
                            homeItems[index] = item.copy(treeUri = null, action = reauthorize, needsAccessBadge = true)
                        }
                    }
                }
                rebuildTreeRoots()
                if (homeMode && homePage != "application_public") renderHomeItems()
            }
        }
    }
    /** Ask the document provider to resolve the target under each persisted tree grant. Document IDs stay opaque. */
    private fun findAuthorizedDocument(target: Uri): Uri? {
        val authority = target.authority ?: return null
        val targetId = try { uriDocumentId(target) } catch (_: Exception) { return null }
        contentResolver.persistedUriPermissions.filter {
            it.isReadPermission && it.uri.authority == authority && it.uri.pathSegments.contains("tree")
        }.forEach { permission ->
            try {
                val document = DocumentsContract.buildDocumentUriUsingTree(permission.uri, targetId)
                contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) return document
                }
            } catch (_: Exception) { /* This grant does not cover the target, or its provider is unavailable. */ }
        }
        return null
    }
    private fun rootPicker(initialUri: Uri? = null, sourceKey: String? = null, expectedAuthority: String? = null) {
        if (initialUri != null && (localProvider.owns(initialUri) ||
            (android.os.Build.VERSION.SDK_INT >= 30 && initialUri.authority == externalStorageDocumentsAuthority &&
                runCatching { uriDocumentId(initialUri) in setOf("primary:", "primary:Download") }.getOrDefault(false)))) {
            requestSharedStorageAccess()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        initialUri?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        pendingTreeSourceKey = sourceKey
        pendingTreeTarget = initialUri
        pendingTreeAuthority = expectedAuthority
        if (initialUri == null && expectedAuthority != null) {
            val name = applicationPublicItems.firstOrNull { it.treeKey == sourceKey }?.title ?: "the requested app"
            Toast.makeText(this, "Choose $name in Android’s folder picker, then select its folder.", Toast.LENGTH_LONG).show()
        }
        try {
            startActivityForResult(intent, pickTreeRequest)
        } catch (firstFailure: Exception) {
            if (initialUri != null && intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI)) {
                intent.removeExtra(DocumentsContract.EXTRA_INITIAL_URI)
                try { startActivityForResult(intent, pickTreeRequest) }
                catch (secondFailure: Exception) {
                    pendingTreeSourceKey = null
                    Toast.makeText(this, "Could not open Android's folder picker: ${secondFailure.message ?: firstFailure.message ?: "unavailable"}", Toast.LENGTH_LONG).show()
                }
            } else {
                pendingTreeSourceKey = null
                Toast.makeText(this, "Could not open Android's folder picker: ${firstFailure.message ?: "unavailable"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    @Deprecated("Use Activity Result APIs when AndroidX lifecycle is introduced")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickWinampSkinRequest && resultCode == RESULT_OK) {
            data?.data?.let(::importWinampSkin)
            return
        }
        if (requestCode == pickTreeRequest && resultCode == RESULT_OK) data?.data?.let { uri ->
            permissionQueryGeneration++ // Discard checks that started before this new grant.
            accessRequestGeneration++
            applicationSourceGeneration++
            val grants = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val requestedKey = pendingTreeSourceKey
            val candidateIndex = applicationPublicItems.indexOfFirst { it.treeKey == requestedKey }
            val candidate = applicationPublicItems.getOrNull(candidateIndex)
            val expected = pendingTreeTarget ?: candidate?.initialUri
            val expectedAuthority = pendingTreeAuthority ?: candidate?.providerAuthority ?: expected?.authority
            pendingTreeSourceKey = null
            pendingTreeTarget = null
            pendingTreeAuthority = null
            if (requestedKey?.startsWith("app_provider_") == true && !runCatching {
                    matchesDocumentGrant(expectedAuthority, expected?.let(::uriDocumentId), uri.authority, uriDocumentId(uri))
                }.getOrDefault(false)) {
                // Validate first: never release an older valid grant after an incorrect picker selection.
                Toast.makeText(this, "Choose the requested app’s folder in Android’s picker.", Toast.LENGTH_LONG).show()
                return
            }
            val persisted = try {
                contentResolver.takePersistableUriPermission(uri, grants)
                true
            } catch (_: SecurityException) { false }
            if (persisted) refreshTreeGrant(treeRoots, uri)
            if (requestedKey?.startsWith("app_provider_") == true) {
                if (!persisted || (grants and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                    Toast.makeText(this, "Android did not retain access to this app folder.", Toast.LENGTH_LONG).show()
                    return
                }
                shared.edit().putString("tree_source_$requestedKey", uri.toString()).apply()
                val progName = candidate?.detail?.ifBlank { candidate.title }
                currentProgramName = progName
                if (candidate != null) applicationPublicItems[candidateIndex] = candidate.copy(
                    treeUri = uri,
                    detail = "App-provided folder",
                    action = {
                        currentProgramName = progName
                        openRoot(uri, "Application public", false)
                    },
                    needsAccessBadge = false
                )
                rebuildTreeRoots()
                if (treeVisible && isCompactPortrait()) setTreeVisible(false)
                openRoot(uri, "Application public", false)
                discoverDocumentProviderRoots()
                return
            }
            requestedKey?.let { key ->
                val editor = shared.edit()
                if (persisted) editor.putString("tree_source_$key", uri.toString())
                else editor.remove("tree_source_$key")
                editor.apply()
            }
            if (treeVisible && isCompactPortrait()) setTreeVisible(false)
            if (requestedKey != null && requestedKey != "tree_permission_regrant") {
                val index = homeItems.indexOfFirst { it.treeKey == requestedKey }
                if (index >= 0) {
                    val item = homeItems[index]
                    homeItems[index] = item.copy(treeUri = uri.takeIf { persisted }, needsAccessBadge = !persisted,
                        action = { openRoot(uri, if (requestedKey.startsWith("public_")) "Internal shared storage" else null) })
                }
            }
            shared.edit().putString("root", uri.toString()).apply()
            openRoot(uri)
            rebuildTreeRoots()
            resolveExistingHomeGrants()
            discoverDocumentProviderRoots()
        }
        if (requestCode == pickTreeRequest) { pendingTreeSourceKey = null; pendingTreeTarget = null; pendingTreeAuthority = null }
        if (requestCode == pickCustomIconRequest) {
            val targetType = pendingIconTargetType
            val targetUri = pendingIconTargetUri
            val targetMime = pendingIconTargetMime
            val createLinkCb = pendingCreateLinkIconCallback
            pendingIconTargetType = null
            pendingIconTargetUri = null
            pendingIconTargetMime = null
            pendingCreateLinkIconCallback = null
            if (resultCode == RESULT_OK) {
                val pickedImageUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
                if (pickedImageUri != null) {
                    when (targetType) {
                        "folder" -> if (targetUri != null) applyFolderIcon(targetUri, pickedImageUri)
                        "shortcut" -> if (targetUri != null) applyShortcutIcon(targetUri, pickedImageUri)
                        "mime" -> if (targetMime != null) applyMimeTypeIcon(targetMime, pickedImageUri)
                        "create_link" -> if (createLinkCb != null) {
                            imageIo.execute {
                                val bmp = thumbnailDecoder.decode(pickedImageUri, dp(128))
                                val base64 = bmp?.let { CustomIconStore.encodeBitmapToBase64Png(it) }
                                if (bmp != null && !base64.isNullOrBlank()) {
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) {
                                            createLinkCb(bmp, base64)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (requestCode == pickThemeArchiveRequest && resultCode == RESULT_OK) {
            val pickedUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
            if (pickedUri != null) {
                installIconThemeFromUri(pickedUri)
            }
        }
        if (requestCode == pickSftpFileRequest) {
            val receiver = pendingSftpFileCallback
            pendingSftpFileCallback = null
            if (resultCode == RESULT_OK) {
                val pickedUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
                if (pickedUri != null && receiver != null) {
                    io.execute {
                        try {
                            contentResolver.openInputStream(pickedUri)?.use { stream ->
                                val text = stream.bufferedReader(java.nio.charset.StandardCharsets.UTF_8).readText()
                                runOnUiThread { receiver(text) }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
    private fun openInstalledApps(category: String? = null) {
        if (selectionMode) exitSelectionMode()
        if (treeVisible && isCompactPortrait()) setTreeVisible(false)
        homeMode = false
        logicalLocationPrefix = null
        history.clear()
        historyNames.clear()
        history.add(AppsUri.ROOT_URI)
        historyNames.add("Installed Applications")
        val targetUri = if (!category.isNullOrBlank()) {
            val catUri = AppsUri.categoryUri(category)
            history.add(catUri)
            historyNames.add(category)
            catUri
        } else {
            AppsUri.ROOT_URI
        }
        current = targetUri
        updateCreateAction()
        renderLocation()
        load(targetUri)
    }

    private fun openRoot(uri: Uri, logicalPrefix: String? = null, rememberAuthorizedRoot: Boolean = true) {
        if (uri == ContainerLinksProvider.ROOT) { showHomeRoot(); return }
        if (uri == com.infinifolder.storage.PartialDownloadsProvider.ROOT) {
            accessRequestGeneration++
            logicalLocationPrefix = null
            currentProgramName = null
            history.clear(); historyNames.clear()
            shared.edit().putString("root", uri.toString()).apply()
            navigate(uri, push = false, displayName = "Partial downloads")
            rebuildTreeRoots()
            return
        }
        if (AppsUri.isAppsUri(uri)) {
            openInstalledApps(AppsUri.getCategoryName(uri))
            return
        }
        val requestGeneration = ++loadGeneration
        accessRequestGeneration++
        // Commit navigation before asynchronous metadata reads. onResume must not reload the old logical page.
        homeMode = false
        updateCreateAction()
        logicalLocationPrefix = logicalPrefix ?: when {
            AppDocumentSources.isAppAuthority(uri) && !localProvider.owns(uri) -> "Application public"
            uri != localProvider.rootUri && (localProvider.owns(uri) || isPrimaryExternalStorageUri(uri)) -> "Internal shared storage"
            else -> null
        }
        currentProgramName = if (logicalLocationPrefix == "Application public") {
            AppDocumentSources.getProgramName(this, uri) ?: currentProgramName
        } else null
        shared.edit().putString("root", uri.toString()).apply()
        history.clear(); historyNames.clear(); current = null
        status.text = "Loading folder…"
        io.execute {
            val progName = if (logicalLocationPrefix == "Application public") {
                currentProgramName ?: AppDocumentSources.getProgramName(this@MainActivity, uri)
            } else null
            val name = if (logicalLocationPrefix == "Application public" && !progName.isNullOrBlank()) {
                progName
            } else {
                displayNameFor(uri) ?: "Storage"
            }
            val path = displayPathFor(uri) ?: name
            val localRoute: List<Pair<Uri, String>>? = if (localProvider.owns(uri)) runCatching {
                val targetFile = localProvider.fileFor(uri)
                val sharedRoot = localProvider.store.root.canonicalFile
                if (targetFile.canonicalPath.startsWith(sharedRoot.canonicalPath)) {
                    var folder = sharedRoot
                    buildList<Pair<Uri, String>> {
                        add(localProvider.rootUri to "Internal shared storage")
                        sharedRoot.toPath().relativize(targetFile.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
                            .forEach { component ->
                                folder = java.io.File(folder, component)
                                add(localProvider.uriFor(folder) to component)
                            }
                    }
                } else {
                    val matchingVol = getSystemService(StorageManager::class.java)?.storageVolumes?.firstOrNull { vol ->
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            vol.directory?.let { targetFile.canonicalPath.startsWith(it.canonicalPath) } == true
                        } else false
                    }
                    if (matchingVol != null && android.os.Build.VERSION.SDK_INT >= 30) {
                        val volDir = matchingVol.directory!!.canonicalFile
                        var folder = volDir
                        buildList<Pair<Uri, String>> {
                            add(localProvider.uriFor(volDir) to matchingVol.getDescription(this@MainActivity))
                            volDir.toPath().relativize(targetFile.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
                                .forEach { component ->
                                    folder = java.io.File(folder, component)
                                    add(localProvider.uriFor(folder) to component)
                                }
                        }
                    } else null
                }
            }.getOrNull()?.let { it } else null
            shared.edit().putString("source_name_${uri}", name).apply()
            shared.edit().putString("source_path_v2_${uri}", path).apply()
            runOnUiThread {
                if (requestGeneration != loadGeneration || isFinishing) return@runOnUiThread
                if (rememberAuthorizedRoot && !localProvider.owns(uri)) rememberAuthorizedTreeRoot(uri, name, path)
                if (localRoute != null) {
                    logicalLocationPrefix = null
                    history.addAll(localRoute.map { it.first })
                    historyNames.addAll(localRoute.map { it.second })
                }
                rebuildTreeRoots()
                navigate(uri, false, name)
            }
        }
    }
    private fun refreshTreeGrant(nodes: MutableList<TreeNode>, uri: Uri) {
        val grantedIdentity = runCatching { sourceIdentity(uri) }.getOrNull() ?: return
        nodes.indices.forEach { index ->
            val node = nodes[index]
            if (node.uri != null && runCatching { sourceIdentity(node.uri) == grantedIdentity }.getOrDefault(false)) {
                val refreshed = node.copy(uri = uri, needsAccessBadge = false, onRequestAccess = null,
                    children = mutableListOf(), expanded = false, loading = false)
                refreshed.onOpen = { openTreeNode(refreshed) }
                nodes[index] = refreshed
            } else refreshTreeGrant(node.children, uri)
        }
    }
    private fun rememberAuthorizedTreeRoot(uri: Uri, name: String, path: String) {
        if (localProvider.owns(uri) || AppsUri.isAppsUri(uri) || ArchiveUri.isArchiveUri(uri) || SftpUri.isSftpUri(uri) ||
            FtpUri.isFtpUri(uri) || SmbUri.isSmbUri(uri) || WebDavUri.owns(uri) || AppDocumentSources.isAppAuthority(uri) ||
            (localProvider.hasAccess() && isPrimaryExternalStorageUri(uri))) return
        val key = "authorized_${sourceIdentity(uri)}"
        val existing = homeItems.indexOfFirst { it.treeKey == key }
        val item = HomeItem("💾", name, path, { openRoot(uri) }, { showAuthorizedRootActions(uri, name) }, key, uri, true, false,
            initialUri = uri, treeParentKey = if (isPrimaryExternalStorageUri(uri)) "internal_shared" else null)
        if (existing >= 0) homeItems[existing] = item else homeItems.add(0, item)
    }
    private fun emptyState() {
        showHomeRoot()
    }
    private fun navigate(uri: Uri, push: Boolean = true, displayName: String? = null, deferInitialLoad: Boolean = false) {
        if (uri == ContainerLinksProvider.ROOT) { showHomeRoot(); return }
        try {
            browserNavigationGeneration++
            if (selectionMode) exitSelectionMode()
            if (::fileScroll.isInitialized) fileScroll.resetPull(smooth = false)
            homeMode = false
            updateCreateAction()
            if (push && current != uri) {
                history.add(uri)
                historyNames.add(displayName ?: entries.firstOrNull { it.uri == uri }?.metadata?.name ?: "Folder")
            } else if (!push && history.isEmpty()) {
                history.add(uri)
                historyNames.add(displayName ?: "Storage")
            }
            current = uri
            renderLocation()
            if (deferInitialLoad) {
                loadGeneration++ // Invalidate any listing still finishing for the prior location.
                openingTorrentDirectory = true
                openingVirtualDirectoryLabel = displayName ?: "Torrent"
                listingIsLoading = false
                listingNotice = null
                listingError = null
                entries = emptyList()
                status.text = "Opening torrent folder…"
                updateEntryLoadingFeedback()
                content.removeAllViews()
                renderEntries()
                val navigationToken = browserNavigationGeneration
                // Show an unlocked, empty virtual folder first. Start network/metadata work
                // only after that new location has had time to render on screen.
                root.postDelayed({
                    if (!isFinishing && !isDestroyed && !homeMode && current == uri &&
                        browserNavigationGeneration == navigationToken) {
                        openingTorrentDirectory = false
                        openingVirtualDirectoryLabel = null
                        load(uri)
                    }
                }, 120L)
            } else {
                openingTorrentDirectory = false
                openingVirtualDirectoryLabel = null
                load(uri)
            }
        } catch (t: Throwable) {
            android.util.Log.e("InfiniFolder", "Error navigating to $uri", t)
            com.infinifolder.storage.DiagnosticLog.record("browser.failure", error = t)
            status.text = "Error opening folder: ${t.message}"
            Toast.makeText(this, "Error opening folder: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
    private fun renderLocation() {
        updateFloatingModeAction()
        try {
            if (!homeMode && current == ContainerLinksProvider.ROOT) { showHomeRoot(); return }
            // Virtual root is rendered separately, never as a folder-history entry.
            // Also repair history restored from builds that included the tree root.
            for (index in history.indices.reversed()) {
                if (history[index] == ContainerLinksProvider.ROOT) {
                    history.removeAt(index)
                    if (index < historyNames.size) historyNames.removeAt(index)
                }
            }
            if (!homeMode && current?.let { ContainerUris.networkType(it) } != null) {
                val target = current!!
                val type = ContainerUris.networkType(target)
                val lastForeign = history.indexOfLast { ContainerUris.networkType(it) != type || it.authority != target.authority }
                if (lastForeign >= 0) {
                    history.subList(0, lastForeign + 1).clear()
                    historyNames.subList(0, minOf(lastForeign + 1, historyNames.size)).clear()
                }
                logicalLocationPrefix = null
                currentProgramName = null
            }
            breadcrumb.removeAllViews()
            if (homeMode) {
                breadcrumb.addView(virtualRootCrumb(homePage == "root"))
                if (homePage == "internal_shared") {
                    breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                    breadcrumb.addView(crumbText("Internal shared storage", setTextColorForTheme(), true) { showInternalShared() })
                }
                if (homePage == "application_public") {
                    breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                    breadcrumb.addView(crumbText("Application public", setTextColorForTheme(), true) { showApplicationPublic() })
                }
                if (homePage == "external_link_history") {
                    breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                    breadcrumb.addView(crumbText("External link history", setTextColorForTheme(), true) { showExternalLinkHistory() })
                }
                renderTree()
                return
            }
            if (logicalLocationPrefix == "Application public" && historyNames.isNotEmpty()) {
                val prog = currentProgramName ?: AppDocumentSources.getProgramName(this, history.firstOrNull() ?: current)
                if (!prog.isNullOrBlank() && (historyNames[0] == "Storage" || historyNames[0] == "home" || historyNames[0] == "Saved app folder")) {
                    historyNames[0] = prog
                }
            }
            breadcrumb.addView(virtualRootCrumb(false))
            logicalLocationPrefix?.let { prefix ->
                breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                breadcrumb.addView(crumbText(prefix, setTextColorForTheme(), false) {
                    when (prefix) {
                        "Application public" -> showApplicationPublic()
                        "Installed Applications" -> openInstalledApps()
                        "External link history" -> showExternalLinkHistory()
                        else -> showInternalShared()
                    }
                })
            }
            if (history.isNotEmpty()) breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
            if (history.isEmpty()) {
                breadcrumb.addView(crumbText("Choose a folder", setTextColorForTheme(), true) { rootPicker() })
                return
            }
            historyNames.forEachIndexed { index, name ->
                if (index > 0) breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                val crumb = crumbText(name, setTextColorForTheme(), index == historyNames.lastIndex) { selectLocation(index) }
                history.getOrNull(index)?.let { destination ->
                    installBreadcrumbDropTarget(crumb, destination, name, onNavigate = { selectLocation(index) })
                }
                breadcrumb.addView(crumb)
            }
            breadcrumb.post { (breadcrumb.parent as? HorizontalScrollView)?.fullScroll(View.FOCUS_RIGHT) }
            renderTree()
        } catch (t: Throwable) {
            android.util.Log.e("InfiniFolder", "Error rendering location", t)
            com.infinifolder.storage.DiagnosticLog.record("browser.failure", error = t)
        }
    }
    private fun selectLocation(index: Int) {
        if (index !in history.indices) return
        if (history[index] == ContainerLinksProvider.ROOT) { showHomeRoot(); return }
        val targetSize = index + 1
        while (history.size > targetSize) history.removeAt(history.lastIndex)
        while (historyNames.size > targetSize) historyNames.removeAt(historyNames.lastIndex)
        current = history.getOrNull(index) ?: return
        homeMode = false
        updateCreateAction()
        renderLocation()
        load(current!!)
    }
    private fun crumbText(label: String, color: Int, selected: Boolean, click: (() -> Unit)? = null): TextView = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(color); setPadding(dp(6), dp(12), dp(6), dp(12))
        if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
        minimumHeight = dp(48); click?.let { action -> setOnClickListener { action() } }
    }
    private fun setTextColorForTheme() = if (dark) 0xFFF0F0E9.toInt() else 0xFF20231F.toInt()
    private fun load(uri: Uri, loadingAttempt: Int = 0) {
        if (uri == ContainerLinksProvider.ROOT) { showHomeRoot(); return }
        if (loadingAttempt == 0) cancelEntryActionLoading()
        val requestGeneration = ++loadGeneration
        homeMode = false
        listingError = null
        if (loadingAttempt == 0) {
            listingIsLoading = true; listingNotice = null
            status.text = "Loading…"
            updateEntryLoadingFeedback()
            // Keep the current rows and scroll extent while refreshing the same folder.
            if (renderedFileLocation != uri) {
                cancelDragContainerHover()
                dragContainerCards.clear()
                entries = emptyList()
                content.removeAllViews()
            }
        }
        val ref = try {
            resourceRef(uri)
        } catch (t: Throwable) {
            listingIsLoading = false
            updateEntryLoadingFeedback()
            listingError = "Invalid folder reference: ${t.message}"
            status.text = listingError
            return
        }
        io.execute {
            try {
                val result = provider.list(ref)
                runOnUiThread {
                    try {
                        if (requestGeneration != loadGeneration || homeMode || current != uri) return@runOnUiThread
                        when (result) {
                            is ProviderResult.Success -> {
                                entries = result.value
                                if (!result.isLoading || result.value.isNotEmpty()) {
                                    cacheDirectoryIconListing(uri, ref, result.value)
                                }
                                result.value.forEach { child ->
                                    if (child.uri != uri) knownResourceParents.put(child.uri, uri)
                                }
                                listingIsLoading = result.isLoading
                                listingNotice = result.notice
                                updateListingStatus(result.notice, result.isLoading, loadingAttempt)
                                renderEntries()
                                if (result.isLoading && loadingAttempt < 8) root.postDelayed({
                                    if (!isFinishing && !isDestroyed && requestGeneration == loadGeneration && !homeMode && current == uri) {
                                        load(uri, loadingAttempt + 1)
                                    }
                                }, 1000)
                                if (uri == localProvider.rootUri) {
                                    internalSharedEntries = result.value
                                    rebuildTreeRoots()
                                } else if (AppsUri.isRoot(uri)) {
                                    installedAppCategories = result.value.map { it.metadata.name }
                                    rebuildTreeRoots()
                                }
                            }
                            is ProviderResult.Failure -> {
                                entries = emptyList(); listingIsLoading = false; listingError = result.message
                                updateEntryLoadingFeedback()
                                status.text = result.message; renderEntries()
                                val failureCause = result.cause
                                if (failureCause != null && com.infinifolder.viewer.CacheFailureUi.offerCopy(this, failureCause,
                                        if (ArchiveUri.isArchiveUri(uri)) ArchiveUri.getSourceUri(uri) else uri)) return@runOnUiThread
                                if (localProvider.owns(uri)) {
                                    if (!localProvider.hasAccess()) requestSharedStorageAccess()
                                    else status.text = "Android restricts access to this folder: ${result.message}"
                                } else if (uri.scheme == "content" && !ArchiveUri.isArchiveUri(uri) && !AppsUri.isAppsUri(uri) && !SftpUri.isSftpUri(uri) && !FtpUri.isFtpUri(uri) && !SmbUri.isSmbUri(uri) && !WebDavUri.owns(uri) && isAccessFailure(result.message, result.cause)) {
                                    rootPicker(uri, "tree_permission_regrant")
                                }
                            }
                            is ProviderResult.Unsupported -> {
                                entries = emptyList(); listingIsLoading = false
                                updateEntryLoadingFeedback()
                                listingError = "This provider cannot list this folder."
                                status.text = listingError; renderEntries()
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("InfiniFolder", "Error rendering listing for $uri", t)
                        com.infinifolder.storage.DiagnosticLog.record("browser.failure", error = t)
                        listingIsLoading = false
                        updateEntryLoadingFeedback()
                        status.text = "Error rendering folder: ${t.message}"
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("InfiniFolder", "Background list error for $uri", t)
                com.infinifolder.storage.DiagnosticLog.record("browser.failure", error = t)
                runOnUiThread {
                    if (requestGeneration == loadGeneration && current == uri) {
                        listingIsLoading = false
                        updateEntryLoadingFeedback()
                        status.text = "Failed to list folder: ${t.message}"
                    }
                }
            }
        }
    }
    private fun updateListingStatus(notice: String? = listingNotice, isLoading: Boolean = listingIsLoading, loadingAttempt: Int = 0) {
        updateEntryLoadingFeedback()
        if (!isLoading && ::fileScroll.isInitialized) {
            fileScroll.isRefreshing = false
        }
        if (selectionMode) {
            val count = selectedUris.size
            status.text = "$count item${if (count == 1) "" else "s"} selected (long-press to act)"
            return
        }
        val visible = if (showHidden) entries else entries.filterNot { it.metadata.isHidden }
        val hiddenCount = entries.size - visible.size
        val hiddenNotice = if (!showHidden && hiddenCount > 0) " ($hiddenCount hidden)" else ""
        status.text = when {
            isLoading && loadingAttempt >= 8 -> "The storage provider is still loading. Tap Refresh to try again."
            isLoading -> "Loading… ${entries.size} items received"
            notice != null -> "${visible.size} items$hiddenNotice · $notice"
            else -> "${visible.size} items$hiddenNotice"
        }
    }

    /** Keep network and archive work asynchronous and visible without disabling browser icons. */
    private fun updateEntryLoadingFeedback() {
        if (::loadingIndicator.isInitialized) {
            loadingIndicator.visibility = if (listingIsLoading || entryActionLoading || storageOperationLoading) View.VISIBLE else View.GONE
            loadingIndicator.contentDescription = when {
                entryActionLoading -> entryActionLoadingMessage
                listingIsLoading -> "Loading folder"
                storageOperationLoading -> "File operation in progress"
                else -> null
            }
        }
    }

    /** Present a fresh empty directory surface before resolving a shortcut target. */
    private fun showOpeningVirtualDirectory(label: String, preserveBreadcrumbs: Boolean): Long {
        browserNavigationGeneration++
        loadGeneration++
        if (selectionMode) exitSelectionMode()
        if (::fileScroll.isInitialized) fileScroll.resetPull(smooth = false)
        homeMode = false
        current = null
        if (!preserveBreadcrumbs) {
            history.clear(); historyNames.clear()
            logicalLocationPrefix = null
        }
        entries = emptyList()
        listingIsLoading = false
        listingError = null
        listingNotice = null
        openingTorrentDirectory = false
        openingVirtualDirectoryLabel = label.ifBlank { "Container" }
        updateCreateAction()
        renderLocation()
        content.removeAllViews()
        renderEntries()
        status.text = "Opening virtual directory…"
        return browserNavigationGeneration
    }

    private fun beginEntryActionLoading(message: String): Int? {
        if (isFinishing || isDestroyed) return null
        if (activeEntryActionMessages.isEmpty()) entryActionPreviousStatus = status.text?.toString().orEmpty()
        val token = ++entryActionLoadingToken
        activeEntryActionMessages[token] = message
        entryActionLoading = true
        entryActionLoadingMessage = message
        status.text = message
        updateEntryLoadingFeedback()
        return token
    }

    private fun finishEntryActionLoading(token: Int) {
        if (activeEntryActionMessages.remove(token) == null) return
        entryActionLoading = activeEntryActionMessages.isNotEmpty()
        entryActionLoadingMessage = activeEntryActionMessages.values.lastOrNull().orEmpty()
        updateEntryLoadingFeedback()
        if (entryActionLoading) status.text = entryActionLoadingMessage
        else if (listingIsLoading) updateListingStatus()
        else status.text = entryActionPreviousStatus
    }
    private fun cancelEntryActionLoading() {
        if (!entryActionLoading) return
        activeEntryActionMessages.clear()
        entryActionLoading = false
        entryActionLoadingMessage = ""
        entryActionLoadingToken++
        updateEntryLoadingFeedback()
    }
    private fun resourceRef(uri: Uri): ResourceRef = provider.refForUri(uri)
        ?: throw IllegalArgumentException("No configured storage provider owns this URI")
    private fun operationResourceKey(ref: ResourceRef): String = "resource:${ref.providerId}:${ref.documentId}"
    private fun operationTargetKey(parent: ResourceRef, name: String): String =
        "object:${parent.providerId}:${parent.documentId}:${name.lowercase(java.util.Locale.ROOT)}"

    private fun operationEntryKeys(entry: ResourceEntry): Set<String> = buildSet {
        add(operationResourceKey(entry.ref))
        knownResourceParents[entry.uri]?.let { parentUri ->
            runCatching { operationTargetKey(resourceRef(parentUri), entry.metadata.name) }.getOrNull()?.let(::add)
        }
    }

    private fun transferConflictKeys(
        items: List<ResourceEntry>,
        destination: Uri,
        createLinks: Boolean,
        partialRecovery: Boolean = false
    ): Set<String> = buildSet {
        items.forEach { entry ->
            addAll(operationEntryKeys(entry))
            val base = entry.metadata.name
            val targetName = when {
                partialRecovery && entry.metadata.partialRecoveryAvailable -> {
                    val dot = base.lastIndexOf('.').takeIf { it > 0 } ?: base.length
                    base.substring(0, dot) + ".partial" + base.substring(dot)
                }
                createLinks || com.infinifolder.shortcut.ShortcutInfo.isShortcut(base, entry.metadata.mimeType) ->
                    base + com.infinifolder.shortcut.ShortcutInfo.SHORTCUT_EXTENSION
                entry.ref.providerId == "apps" && !base.endsWith(".apk", true) -> "$base.apk"
                else -> base
            }
            runCatching { operationTargetKey(resourceRef(destination), targetName) }.getOrNull()?.let(::add)
            if (entry.ref.providerId == "torrent") {
                runCatching { operationTargetKey(resourceRef(destination), "$targetName.incomplete") }.getOrNull()?.let(::add)
            }
            // Filesystems such as FAT replace invalid characters; include that alias
            // in the lock key so distinct source names cannot race to one output path.
            val compatibleGuess = targetName.replace(Regex("[<>:\"|?*]"), "_")
            runCatching { operationTargetKey(resourceRef(destination), compatibleGuess) }.getOrNull()?.let(::add)
        }
    }
    private fun isAccessFailure(message: String, cause: Throwable?): Boolean =
        cause is SecurityException || listOf("revoked", "permission", "access denied", "permission denied")
            .any { message.contains(it, ignoreCase = true) }
    private fun scheduleVisibleProbe() {
        if (visibleProbeScheduled || !::fileScroll.isInitialized) return
        visibleProbeScheduled = true
        fileScroll.post {
            visibleProbeScheduled = false
            probeVisibleCards()
        }
    }

    private fun isCardVisibleInScroll(cardView: View): Boolean {
        if (!::fileScroll.isInitialized || !cardView.isAttachedToWindow ||
            cardView.width <= 0 || cardView.height <= 0 || fileScroll.width <= 0 || fileScroll.height <= 0) return false
        // Both rectangles use screen coordinates and Android's actual ancestor clipping.
        // Never queue the whole folder while the scroll viewport is unmeasured.
        val viewport = android.graphics.Rect()
        val card = android.graphics.Rect()
        return fileScroll.getGlobalVisibleRect(viewport) && cardView.getGlobalVisibleRect(card) &&
            android.graphics.Rect.intersects(viewport, card)
    }

    private fun probeVisibleCards() {
        visibleArtworkUris = entryIconViews.filterValues { isCardVisibleInScroll(it.parent as? View ?: it) }.keys.toSet()
        if (pendingCardProbes.isEmpty()) return
        val currentGen = loadGeneration
        val snapshot = ArrayList(pendingCardProbes)
        var started = 0
        for (probe in snapshot) {
            if (probe.generation != currentGen) {
                pendingCardProbes.remove(probe)
            } else if (isCardVisibleInScroll(probe.view)) {
                pendingCardProbes.remove(probe)
                started++
                try {
                    probe.startProbe()
                } catch (_: Throwable) {}
            }
        }
        if (started > 0) com.infinifolder.storage.DiagnosticLog.record("artwork.visible-probes",
            "started=$started deferred=${pendingCardProbes.size} scrollY=${fileScroll.scrollY} viewportHeight=${fileScroll.height}")
    }

    private fun registerVisibleCardProbe(cardView: View, generation: Int = loadGeneration, startProbe: () -> Unit) {
        pendingCardProbes += PendingCardProbe(cardView, generation, startProbe)
        scheduleVisibleProbe()
    }

    private fun readShortcutInfo(entry: ResourceEntry): ShortcutInfo? {
        shortcutCache.get(entry.uri.toString())?.let { return it }
        return try {
            val text = if (localProvider.owns(entry.uri)) {
                localProvider.fileFor(entry.uri).readText()
            } else {
                val readRes = provider.openRead(entry.ref)
                if (readRes is ProviderResult.Success) {
                    readRes.value.use { it.reader().readText() }
                } else null
            } ?: return null
            ShortcutInfo.fromJson(text)?.also { info ->
                shortcutCache.put(entry.uri.toString(), info)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderEntries() {
        artworkRenderGeneration++
        if ((!listingIsLoading || entries.isNotEmpty()) && listingError == null) {
            ResourceIconSidecars.observe(entries, current)
        }
        val renderStartedAt = SystemClock.uptimeMillis()
        if (renderedPinnedIconSize != iconSizeDp) renderPinnedContainers()
        val location = current
        val generation = loadGeneration
        val forcedScrollRestore = pendingLocationScrollRestore
            ?.takeIf { it.first == location }
            ?.also { pendingLocationScrollRestore = null }
            ?.second
        val savedScrollY = forcedScrollRestore
            ?: if (renderedFileLocation == location) fileScroll.scrollY else 0
        renderedFileLocation = location
        try {
            cancelDragContainerHover()
            dragContainerCards.clear()
            pendingCardProbes.clear()
            visibleProbeScheduled = false
            entryIconViews.clear()
            content.removeAllViews()
            val rows = FileViewGrid.sortedEntries(entries, showHidden, sortKey, sortDescending)
            if (rows.isEmpty()) {
                content.addView(TextView(this).apply {
                    text = openingVirtualDirectoryLabel?.let { "Opening $it…" }
                        ?: if (openingTorrentDirectory) "Opening a new torrent folder…" else listingError ?: if (listingIsLoading) "Waiting for the storage provider…" else
                        listingNotice ?: "No files or folders were returned for this location."
                    textSize = 16f; setTextColor(setTextColorForTheme()); setPadding(dp(18), dp(28), dp(18), dp(12))
                    setOnLongClickListener { onEmptyAreaLongPress() }
                })
                val path = current?.let { shared.getString("source_path_v2_$it", null) }
                    ?: historyNames.joinToString(" / ")
                content.addView(TextView(this).apply {
                    text = path; textSize = 13f; setTextColor(setTextColorForTheme()); setPadding(dp(18), 0, dp(18), dp(12))
                    setOnLongClickListener { onEmptyAreaLongPress() }
                })
                content.addView(Button(dialogContext()).apply {
                    text = "Refresh"
                    setOnClickListener { current?.let { load(it) } }
                })
                return
            }
            if (grid) {
                val containerPx = if (content.width > 0) content.width else if (fileScroll.width > 0) fileScroll.width else 0
                val availableWidth = if (containerPx > 0) {
                    (containerPx / resources.displayMetrics.density).roundToInt()
                } else {
                    (resources.configuration.screenWidthDp - if (treeVisible) 220 else 32).coerceAtLeast(iconSizeDp)
                }
                val columns = gridColumns(availableWidth)
                val table = FileViewGrid.build(this, rows, columns, ::onEmptyAreaLongPress) { entry ->
                    entryCard(entry, true)
                }
                content.addView(table)
            } else rows.forEach { content.addView(entryCard(it, false)) }
            scheduleVisibleProbe()
        } catch (t: Throwable) {
            android.util.Log.e("InfiniFolder", "Error rendering entries", t)
            com.infinifolder.storage.DiagnosticLog.record("browser.failure", error = t)
            status.text = "Error displaying folder contents: ${t.message}"
        } finally {
            val elapsed = SystemClock.uptimeMillis() - renderStartedAt
            if (elapsed >= 100L) com.infinifolder.storage.DiagnosticLog.record("ui.render-entries.slow", "elapsedMs=$elapsed entries=${entries.size} grid=$grid")
            // Restore after measurement, when ScrollView knows the new content bounds.
            val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (fileScroll.viewTreeObserver.isAlive)
                        fileScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (!isDestroyed && !homeMode && current == location && loadGeneration == generation) {
                        fileScroll.scrollTo(0, savedScrollY)
                        scheduleVisibleProbe()
                    }
                }
            }
            fileScroll.viewTreeObserver.addOnGlobalLayoutListener(listener)
            fileScroll.requestLayout()
        }
    }
    private fun renderCurrentContents() { if (homeMode) renderHomeItems() else renderEntries() }
    private fun iconTextSizeDp(asGrid: Boolean): Float = if (asGrid) (iconSizeDp * 0.72f) else 28f
    private fun gridColumns(availableWidthDp: Int): Int = FileViewGrid.columns(availableWidthDp, iconSizeDp)
    private fun buildFileIconView(
        entry: ResourceEntry,
        meta: ResourceMetadata,
        asGrid: Boolean,
        box: View
    ): View = FileIconView.create(this, entry, meta, asGrid, iconSizeDp,
        customIconStore, iconThemeManager, localProvider, appsProvider,
        mimePreview = { mimeKey, fallback -> mimeIconPreview(mimeKey, fallback, asGrid, box) },
        imagePreview = { imagePreview(entry, asGrid, box) })

    private fun browseNestedArchive(entry: ResourceEntry) {
        val loadingToken = beginEntryActionLoading("Opening ${entry.metadata.name}…") ?: return
        val nestedVirtualPath = if (historyNames.isNotEmpty())
            (historyNames + entry.metadata.name).joinToString("/") else null
        io.execute {
            val result = try {
                val cached = archiveProvider.extractToCache(entry.uri, nestedVirtualPath)
                ArchiveUri.toArchiveUri(Uri.fromFile(cached))
            } catch (error: Exception) {
                error
            }
            runOnUiThread {
                finishEntryActionLoading(loadingToken)
                when (result) {
                    is Uri -> navigate(result, displayName = entry.metadata.name)
                    is Exception -> if (!com.infinifolder.viewer.CacheFailureUi.offerCopy(this, result, entry.uri))
                        Toast.makeText(this, "Could not open archive: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun entryCard(entry: ResourceEntry, asGrid: Boolean): View {
        val isSelected = selectedUris.contains(entry.uri)
        val card = FileEntryCard(this, entry, asGrid, iconSizeDp, dark, isSelected, ::onEmptyAreaLongPress)
        val box = card.container
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val isShortcut = ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)
        val shortcut = if (isShortcut) shortcutCache.get(entry.uri.toString()) else null

        val isPackagedFormat = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isPackagedFormat(entry.metadata.name, entry.metadata.mimeType)
        val detectedMagic = if (!isPackagedFormat && !entry.metadata.isDirectory && FileMagicDetector.isAmbiguousOrUnknown(entry.metadata.name, entry.metadata.mimeType)) {
            if (localProvider.owns(entry.uri)) {
                runCatching { FileMagicDetector.detect(localProvider.fileFor(entry.uri)) }.getOrNull()
            } else {
                ambiguousMagicCache.get(entry.uri.toString())
            }
        } else null
        val effectiveMeta = if (isPackagedFormat) {
            val pkgMime = ArchiveFormats.packageMimeType(entry.metadata.name)
            if (pkgMime != null) entry.metadata.copy(mimeType = pkgMime) else entry.metadata
        } else if (detectedMagic != null && detectedMagic.mimeType != entry.metadata.mimeType) {
            entry.metadata.copy(mimeType = detectedMagic.mimeType)
        } else {
            entry.metadata
        }
        val isArchive = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isArchive(effectiveMeta.name, effectiveMeta.mimeType)
        val isDefaultArchive = isArchive && !isPackagedFormat

        var uncachedShortcutPlaceholder: TextView? = null
        var uncachedShortcutImage: ImageView? = null

        val isApk = !entry.metadata.isDirectory &&
            com.infinifolder.mime.MimeRegistry.effective(effectiveMeta.name, effectiveMeta.mimeType) == "application/vnd.android.package-archive"
        val sharedArtwork = isShortcut || AppsUri.isAppUri(entry.uri) || isApk || ResourceIconSidecars.find(entry) != null ||
            ResourceArtworkCache.peek(entry, sidePx) != null
        val rawIcon = if (sharedArtwork) {
            TextView(this).apply {
                text = if (AppsUri.isAppUri(entry.uri)) "📱" else iconFor(effectiveMeta)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
            }
        } else if (isShortcut && shortcut != null) {
            val targetUri = try { Uri.parse(shortcut.targetUri) } catch (_: Throwable) { null }
            val targetMeta = ResourceMetadata(shortcut.targetPath ?: entry.metadata.name, shortcut.targetMimeType ?: "", null, null, shortcut.targetIsDirectory)
            val targetMimeKey = if (!shortcut.targetIsDirectory && !WebMetadataResolver.isWebUri(shortcut.targetUri)) CustomIconStore.resolveMimeKey(targetMeta) else null
            when {
                !shortcut.iconBase64.isNullOrBlank() -> {
                    shortcutCustomIconPreview(entry.uri, shortcut.iconBase64.orEmpty(), asGrid)
                }
                WebMetadataResolver.isWebUri(shortcut.targetUri) -> TextView(this).apply {
                    text = "🌐"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                }
                targetUri != null && AppsUri.isAppUri(targetUri) -> {
                    val pkg = AppsUri.getPackageName(targetUri)
                    appPreview(pkg, asGrid, box)
                }
                shortcut.targetIsDirectory && targetUri != null -> {
                    val dirEntry = ResourceEntry(resourceRef(targetUri), ResourceMetadata("", "", null, null, true), targetUri)
                    directoryPreview(dirEntry, asGrid, box)
                }
                shortcut.targetIsDirectory -> TextView(this).apply {
                    text = "📁"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                }
                targetMimeKey != null && customIconStore.hasMimeIcon(targetMimeKey) -> {
                    val fallback = if (ArchiveFormats.isDefaultBrowsableArchive(targetMeta.name, targetMeta.mimeType)) "📦" else iconFor(targetMeta)
                    mimeIconPreview(targetMimeKey, fallback, asGrid, box)
                }
                ArchiveFormats.isDefaultBrowsableArchive(shortcut.targetPath ?: "", shortcut.targetMimeType ?: "") -> TextView(this).apply {
                    text = "📦"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                }
                shortcut.targetMimeType?.startsWith("image/") == true -> TextView(this).apply {
                    text = "🖼"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                }
                targetUri?.scheme != null && targetUri.scheme !in setOf("file", "content", "apps", "archive") -> TextView(this).apply {
                    text = "🔗"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                }
                else -> TextView(this).apply {
                    text = iconFor(targetMeta)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                }
            }
        } else if (isShortcut) {
            val frame = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                foregroundGravity = Gravity.CENTER
            }
            val placeholder = TextView(this).apply {
                text = "📄"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = null
                imageTintList = null
                alpha = 1f
                layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            }
            frame.addView(placeholder)
            frame.addView(image)
            uncachedShortcutPlaceholder = placeholder
            uncachedShortcutImage = image
            frame
        } else if (AppsUri.isAppUri(entry.uri)) {
            val pkg = AppsUri.getPackageName(entry.uri)
            appPreview(pkg, asGrid, box)
        } else if (AppsUri.isCategoryUri(entry.uri)) {
            val cat = AppsUri.getCategoryName(entry.uri) ?: ""
            TextView(this).apply {
                text = appsProvider.getCategoryIcon(cat)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                gravity = Gravity.CENTER
            }
        } else if (entry.metadata.isDirectory) {
            directoryPreview(entry, asGrid, box)
        } else if (isDefaultArchive) {
            directoryPreview(entry, asGrid, box, buildFileIconView(entry, effectiveMeta, asGrid, box))
        } else {
            buildFileIconView(entry, effectiveMeta, asGrid, box)
        }
        val icon = card.attachIcon(rawIcon)
        // Shared stale-while-refresh artwork; source access stays in the visible-card worker.
        if (sharedArtwork) {
            val artworkSize = dp(if (asGrid) iconSizeDp else 44)
            val artworkGeneration = loadGeneration
            val cardRender = artworkRenderGeneration
            var authoritativeApplied = false
            fun applyArtwork(bitmap: Bitmap) {
                if (artworkGeneration != loadGeneration || cardRender != artworkRenderGeneration || isDestroyed) return
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                }
                if (icon.childCount > 0) icon.removeViewAt(0)
                icon.addView(image, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            }
            ResourceArtworkCache.peek(entry, artworkSize)?.let(::applyArtwork)
            lateinit var startArtwork: () -> Unit
            fun retryWhenVisible() {
                runOnUiThread {
                    if (artworkGeneration == loadGeneration && cardRender == artworkRenderGeneration && !isDestroyed)
                        registerVisibleCardProbe(box, artworkGeneration, startArtwork)
                }
            }
            startArtwork = {
                val queuedAt = SystemClock.uptimeMillis()
                ResourceArtworkCache.reads.execute cacheRead@ {
                    if (artworkGeneration != loadGeneration || cardRender != artworkRenderGeneration || isDestroyed) return@cacheRead
                    if (entry.uri !in visibleArtworkUris) { retryWhenVisible(); return@cacheRead }
                    val readStartedAt = SystemClock.uptimeMillis()
                    resourceIconResolver.cached(entry, artworkSize)?.bitmap?.let { bitmap ->
                        val readyAt = SystemClock.uptimeMillis()
                        runOnUiThread {
                            if (!authoritativeApplied) {
                                applyArtwork(bitmap)
                                val appliedAt = SystemClock.uptimeMillis()
                                if (appliedAt - queuedAt >= 100) com.infinifolder.storage.DiagnosticLog.record("artwork.cache.display-slow",
                                    "name=${entry.metadata.name} queueMs=${readStartedAt - queuedAt} readMs=${readyAt - readStartedAt} uiMs=${appliedAt - readyAt}")
                            }
                        }
                    }
                    // Cache publication is queued before source work can publish a replacement.
                    val sourceWorker = if (AppsUri.isAppUri(entry.uri)) ResourceArtworkCache.appSources else imageIo
                    sourceWorker.execute sourceRead@ {
                        if (artworkGeneration != loadGeneration || cardRender != artworkRenderGeneration || isDestroyed) return@sourceRead
                        if (entry.uri !in visibleArtworkUris) { retryWhenVisible(); return@sourceRead }
                        val resolved = runCatching {
                            resourceIconResolver.resolve(entry, artworkSize, deferCacheWrite = true)
                        }.getOrNull()
                        if (resolved != null) runOnUiThread {
                            if (artworkGeneration != loadGeneration || cardRender != artworkRenderGeneration || isDestroyed) return@runOnUiThread
                            authoritativeApplied = true
                            if (isShortcut) {
                                resourceIconResolver.peekShortcut(entry)?.let { shortcutCache.put(entry.uri.toString(), it) }
                                card.nameView.setFileName("${resolved.label} ↗", resolved.directory)
                            }
                            if (resolved.bitmap != null) {
                                applyArtwork(resolved.bitmap)
                                resourceIconResolver.cacheDisplayed(entry, artworkSize, resolved)
                            } else {
                                if (icon.childCount > 0) icon.removeViewAt(0)
                                icon.addView(TextView(this).apply {
                                    text = resolved.fallback
                                    gravity = Gravity.CENTER
                                    textSize = (if (asGrid) iconSizeDp else 44) * .72f
                                }, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                            }
                        }
                    }
                }
            }
            registerVisibleCardProbe(box, artworkGeneration, startArtwork)
        }

        if (!isPackagedFormat && detectedMagic == null && FileMagicDetector.isAmbiguousOrUnknown(entry.metadata.name, entry.metadata.mimeType) &&
            !entry.metadata.isDirectory && !isShortcut && !AppsUri.isAppsUri(entry.uri)
        ) {
            val generation = loadGeneration
            registerVisibleCardProbe(box, generation) {
                imageIo.execute {
                    if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                    val magic = try {
                        if (localProvider.owns(entry.uri)) {
                            FileMagicDetector.detect(localProvider.fileFor(entry.uri), peekCompressedPayload = true)
                        } else {
                            FileMagicDetector.detectFromStream {
                                (provider.openRead(entry.ref) as? ProviderResult.Success)?.value
                                    ?: throw java.io.IOException("Cannot open stream")
                            }
                        }
                    } catch (_: Throwable) { null }
                    if (magic != null && magic.mimeType != entry.metadata.mimeType) {
                        ambiguousMagicCache.put(entry.uri.toString(), magic)
                        runOnUiThread {
                            if (generation == loadGeneration && !isFinishing && !isDestroyed) {
                                val updatedMeta = entry.metadata.copy(mimeType = magic.mimeType)
                                val magicIsPkg = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isPackagedFormat(updatedMeta.name, updatedMeta.mimeType)
                                val magicIsArc = !AppsUri.isAppUri(entry.uri) && !magicIsPkg && (
                                    ArchiveFormats.isArchive(updatedMeta.name, updatedMeta.mimeType) ||
                                    magic.isArchiveOrDiscImage ||
                                    ArchiveFormats.isDiscImage(updatedMeta.name, magic.mimeType)
                                )
                                val baseIcon = buildFileIconView(entry, updatedMeta, asGrid, box)
                                val updatedIcon = if (magicIsArc) {
                                    directoryPreview(entry, asGrid, box, baseIcon)
                                } else {
                                    baseIcon
                                }
                                if (icon.childCount > 0) {
                                    icon.removeViewAt(0)
                                    icon.addView(updatedIcon, 0, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                                } else {
                                    icon.addView(updatedIcon, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                                }
                            }
                        }
                    }
                }
            }
        }
        val displayName = if (isShortcut && !shortcut?.displayNameOverride.isNullOrBlank()) {
            "${shortcut.displayNameOverride} ↗"
        } else if (isShortcut) {
            "${entry.metadata.name.removeSuffix(".lnk").removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION)} ↗"
        } else {
            entry.metadata.name
        }
        val gridCellMaxWidthPx = if (asGrid) {
            val availableWidthDp = (resources.configuration.screenWidthDp - if (treeVisible) 220 else 32).coerceAtLeast(iconSizeDp)
            val columns = gridColumns(availableWidthDp)
            val actualContainerWidthPx = content.width.takeIf { it > 0 } ?: dp(availableWidthDp)
            (actualContainerWidthPx / columns - dp(4)).coerceAtLeast(dp(36))
        } else 0
        val isLogicalDir = if (isShortcut && shortcut != null) shortcut.targetIsDirectory else entry.metadata.isDirectory
        card.addLabels(displayName, isLogicalDir, gridCellMaxWidthPx, if (asGrid) "" else metadataLine(entry))
        val nameView = card.nameView
        val metaView = card.metaView
        entryIconViews[entry.uri] = icon

        if (isShortcut && shortcut == null && !sharedArtwork) {
            val generation = loadGeneration
            registerVisibleCardProbe(box, generation) {
                imageIo.execute {
                    if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                    val info = readShortcutInfo(entry) ?: return@execute
                    val targetUri = try { Uri.parse(info.targetUri) } catch (_: Throwable) { null }
                    var resolvedBitmap: Bitmap? = null
                    val fallbackEmoji = when {
                        !info.iconBase64.isNullOrBlank() -> {
                            val cacheKey = "shortcut-icon:${entry.uri}:${info.iconBase64.hashCode()}"
                            resolvedBitmap = thumbnailCache.get(cacheKey)
                                ?: CustomIconStore.decodeBase64Bitmap(info.iconBase64)?.also { thumbnailCache.put(cacheKey, it) }
                            if (WebMetadataResolver.isWebUri(info.targetUri)) "🌐" else "📄"
                        }
                        WebMetadataResolver.isWebUri(info.targetUri) -> "🌐"
                        targetUri != null && AppsUri.isAppUri(targetUri) -> {
                            val pkg = AppsUri.getPackageName(targetUri)
                            if (!pkg.isNullOrEmpty()) {
                                val cacheKey = "app-icon:$pkg:$sidePx"
                                resolvedBitmap = thumbnailCache.get(cacheKey)
                                    ?: appsProvider.getAppIconBitmap(pkg, sidePx)?.also { thumbnailCache.put(cacheKey, it) }
                            }
                            "📱"
                        }
                        info.targetIsDirectory -> {
                            if (targetUri != null && !AppsUri.isAppsUri(targetUri)) {
                                val dirRef = resourceRef(targetUri)
                                val dirKey = "${dirRef.providerId}:${dirRef.documentId}"
                                val iconUri = directoryIconCache.get(dirKey)?.let { if (it.isEmpty()) null else Uri.parse(it) }
                                    ?: findDirectoryIcon(targetUri).also { directoryIconCache.put(dirKey, it?.toString() ?: "") }
                                if (iconUri != null) {
                                    val previewKey = "dir-icon:$dirKey:$iconUri"
                                    resolvedBitmap = thumbnailCache.get(previewKey)
                                        ?: thumbnailDecoder.decode(iconUri, sidePx)?.also { thumbnailCache.put(previewKey, it) }
                                }
                            }
                            "📁"
                        }
                        targetUri?.scheme != null && targetUri.scheme !in setOf("file", "content", "apps", "archive") -> "🔗"
                        else -> {
                            val targetMeta = ResourceMetadata(info.targetPath ?: entry.metadata.name, info.targetMimeType ?: "", null, null, false)
                            val targetMimeKey = CustomIconStore.resolveMimeKey(targetMeta)
                            resolvedBitmap = customIconStore.getMimeIconBitmap(targetMimeKey) { f ->
                                thumbnailDecoder.decode(Uri.fromFile(f), sidePx)
                            }
                            when {
                                ArchiveFormats.isDefaultBrowsableArchive(info.targetPath ?: "", info.targetMimeType ?: "") -> "📦"
                                info.targetMimeType?.startsWith("image/") == true -> "🖼"
                                else -> iconFor(targetMeta)
                            }
                        }
                    }
                    runOnUiThread {
                        if (generation != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                        val updatedDisplayName = if (!info.displayNameOverride.isNullOrBlank()) {
                            "${info.displayNameOverride} ↗"
                        } else {
                            "${entry.metadata.name.removeSuffix(".lnk").removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION)} ↗"
                        }
                        nameView.setFileName(updatedDisplayName, info.targetIsDirectory)
                        metaView?.text = metadataLine(entry)
                        if (resolvedBitmap != null) {
                            uncachedShortcutImage?.setImageBitmap(resolvedBitmap)
                            uncachedShortcutPlaceholder?.visibility = View.GONE
                        } else {
                            uncachedShortcutPlaceholder?.text = fallbackEmoji
                        }
                    }
                }
            }
        }

        val onEntryClick = View.OnClickListener {
            if (selectionMode) {
                if (isSelected) selectedUris.remove(entry.uri) else selectedUris.add(entry.uri)
                renderCurrentContents()
                updateListingStatus()
                return@OnClickListener
            }
            if (current == com.infinifolder.storage.PartialDownloadsProvider.ROOT) {
                openFoundPartialDownload(entry)
                return@OnClickListener
            }
            if (entry.metadata.isBrokenLink) {
                Toast.makeText(this@MainActivity, "Broken link: target does not exist (${entry.metadata.linkTarget ?: ""})", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }
            if (isShortcut) {
                openShortcut(entry)
                return@OnClickListener
            }
            if (entry.metadata.isDirectory) {
                navigate(entry.uri, displayName = entry.metadata.name)
                return@OnClickListener
            }

            if (entry.metadata.name.endsWith(".incomplete", true)) {
                open(entry)
                return@OnClickListener
            }
            val cachedMagic = ambiguousMagicCache.get(entry.uri.toString())
            val effectiveMime = com.infinifolder.mime.MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType, cachedMagic?.mimeType)
            val isPkg = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isPackagedFormat(entry.metadata.name, effectiveMime)
            val isArc = !AppsUri.isAppUri(entry.uri) && (
                ArchiveFormats.isArchive(entry.metadata.name, effectiveMime) ||
                (cachedMagic != null && (cachedMagic.isArchiveOrDiscImage || ArchiveFormats.isDiscImage(entry.metadata.name, cachedMagic.mimeType)))
            )
            if (isArc && !isPkg) {
                if (ArchiveUri.isArchiveUri(entry.uri)) {
                    browseNestedArchive(entry)
                } else {
                    val archiveUri = ArchiveUri.toArchiveUri(entry.uri)
                    navigate(archiveUri, displayName = entry.metadata.name)
                }
                return@OnClickListener
            }

            if (cachedMagic == null && !isPkg && FileMagicDetector.isAmbiguousOrUnknown(entry.metadata.name, entry.metadata.mimeType)) {
                val loadingToken = beginEntryActionLoading("Checking file type…") ?: return@OnClickListener
                io.execute {
                    val magic = try {
                        if (localProvider.owns(entry.uri)) {
                            FileMagicDetector.detect(localProvider.fileFor(entry.uri), peekCompressedPayload = true)
                        } else {
                            FileMagicDetector.detectFromStream {
                                (provider.openRead(entry.ref) as? ProviderResult.Success)?.value
                                    ?: throw java.io.IOException("Cannot open stream")
                            }
                        }
                    } catch (_: Throwable) { null }
                    if (magic != null) {
                        ambiguousMagicCache.put(entry.uri.toString(), magic)
                    }
                    val probeMime = magic?.mimeType ?: entry.metadata.mimeType
                    val probeIsPkg = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isPackagedFormat(entry.metadata.name, probeMime)
                    val probeIsArc = !AppsUri.isAppUri(entry.uri) && (
                        ArchiveFormats.isArchive(entry.metadata.name, probeMime) ||
                        (magic != null && (magic.isArchiveOrDiscImage || ArchiveFormats.isDiscImage(entry.metadata.name, magic.mimeType)))
                    )
                    runOnUiThread {
                        finishEntryActionLoading(loadingToken)
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (probeIsArc && !probeIsPkg) {
                            if (ArchiveUri.isArchiveUri(entry.uri)) {
                                browseNestedArchive(entry)
                            } else {
                                navigate(ArchiveUri.toArchiveUri(entry.uri), displayName = entry.metadata.name)
                            }
                        } else {
                            val effectiveEntry = if (magic != null && magic.mimeType != entry.metadata.mimeType) {
                                entry.copy(metadata = entry.metadata.copy(mimeType = magic.mimeType))
                            } else entry
                            open(effectiveEntry)
                        }
                    }
                }
                return@OnClickListener
            }

            open(entry)
        }
        run {
            val holdTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
            val indicator = DragHoldIndicatorView(this)
            icon.addView(indicator, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            val gesture = object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var downAt = 0L
                private var gestureId = 0L
                private var armed = false
                private var moved = false
                private var dragStarted = false
                private var dragAttempted = false
                private var holding = false
                private var target: View? = null
                private fun traceEntryTouch(phase: String, detail: String = "") {
                    val source = when (target) {
                        icon -> "icon"
                        box -> "card"
                        else -> "label"
                    }
                    com.infinifolder.storage.DiagnosticLog.record(
                        "browser.touch.entry.$phase",
                        "gesture=$gestureId source=$source elapsedMs=${SystemClock.uptimeMillis() - downAt} " +
                            "armed=$armed moved=$moved dragStarted=$dragStarted holdTimeout=$holdTimeout $detail"
                    )
                }
                private fun armIfTimerElapsed() {
                    if (!armed && SystemClock.uptimeMillis() - downAt >= holdTimeout) {
                        armed = true
                        browserEntryHoldArmed = true
                        traceEntryTouch("armed", "trigger=pointer-event")
                        indicator.progress = 1f
                        target?.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                private val updateHold = object : Runnable {
                    override fun run() {
                        val view = target ?: return
                        if (!holding || moved || dragStarted) return
                        val progress = ((SystemClock.uptimeMillis() - downAt).toFloat() / holdTimeout).coerceIn(0f, 1f)
                        indicator.progress = progress
                        if (progress >= 1f) {
                            armed = true
                            browserEntryHoldArmed = true
                            traceEntryTouch("armed", "trigger=timer")
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        } else view.postOnAnimation(this)
                    }
                }

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            target = view
                            downX = event.x
                            downY = event.y
                            downAt = SystemClock.uptimeMillis()
                            gestureId = event.downTime
                            armed = false
                            moved = false
                            dragStarted = false
                            dragAttempted = false
                            holding = true
                            traceEntryTouch("down")
                            indicator.progress = 0f
                            view.postOnAnimation(updateHold)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            armIfTimerElapsed()
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                if (!moved) traceEntryTouch("slop-crossed", "dx=${dx.toInt()} dy=${dy.toInt()} slop=$touchSlop")
                                moved = true
                                holding = false
                                view.removeCallbacks(updateHold)
                                if (!armed) view.parent?.requestDisallowInterceptTouchEvent(false)
                                if (armed && !dragAttempted) {
                                    dragAttempted = true
                                    indicator.progress = -1f
                                    dragStarted = startEntryDrag(view, icon, entry)
                                    traceEntryTouch("drag-attempt", "accepted=$dragStarted")
                                }
                                indicator.progress = -1f
                                if (dragStarted) view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            armIfTimerElapsed()
                            browserEntryHoldArmed = false
                            view.removeCallbacks(updateHold)
                            holding = false
                            indicator.progress = -1f
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            val dx = event.x - downX
                            val dy = event.y - downY
                            val paneSwipe = moved && !armed && !dragStarted && isBrowserPaneSwipe(dx, dy)
                            traceEntryTouch("release", "dx=${dx.toInt()} dy=${dy.toInt()} paneSwipe=$paneSwipe")
                            if (paneSwipe) {
                                val width = browserArea.width.takeIf { it > 0 }
                                    ?: dp(resources.configuration.screenWidthDp.coerceAtLeast(1))
                                val startOffset = if (treeVisible) 0f else -width.toFloat()
                                completeBrowserPaneSwipe(
                                    showTree = !treeVisible,
                                    releaseOffset = (startOffset + dx).coerceIn(-width.toFloat(), 0f)
                                )
                                com.infinifolder.storage.DiagnosticLog.record(
                                    "browser.pane.swipe", "source=entry-icon showTree=$treeVisible"
                                )
                            } else when {
                                armed && !moved && !dragStarted -> {
                                    if (selectionMode) {
                                        if (entry.uri !in selectedUris) selectedUris.add(entry.uri)
                                        renderEntries()
                                        updateListingStatus()
                                        showBatchActionMenu()
                                    } else showEntryMenu(entry)
                                }
                                !moved && !dragStarted -> onEntryClick.onClick(view)
                            }
                            target = null
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            traceEntryTouch("cancel")
                            browserEntryHoldArmed = false
                            view.removeCallbacks(updateHold)
                            holding = false
                            indicator.progress = -1f
                            moved = true
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            target = null
                        }
                    }
                    return true
                }
            }
            icon.isClickable = true
            icon.isLongClickable = false
            icon.setOnTouchListener(gesture)
            if (asGrid) {
                nameView.isClickable = true
                nameView.isLongClickable = false
                nameView.setOnTouchListener(gesture)
            }
            if (!asGrid) {
                box.isClickable = true
                box.isLongClickable = false
                box.setOnTouchListener(gesture)
            }
        }
        installEntryDropTarget(box, entry, asGrid)
        return box
    }
    private fun shortcutCustomIconPreview(entryUri: Uri, base64: String, asGrid: Boolean): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val cacheKey = "shortcut-icon:$entryUri:${base64.hashCode()}"
        val frame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "📄"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            imageTintList = null
            alpha = 1f
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        frame.addView(placeholder)
        frame.addView(image)
        val cached = thumbnailCache.get(cacheKey) ?: CustomIconStore.decodeBase64Bitmap(base64)?.also {
            thumbnailCache.put(cacheKey, it)
        }
        if (cached != null) {
            image.setImageBitmap(cached)
            placeholder.visibility = View.GONE
        }
        return frame
    }

    private fun mimeIconPreview(mimeKey: String, fallbackEmoji: String, asGrid: Boolean, cardView: View? = null): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val frame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = fallbackEmoji
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            imageTintList = null
            alpha = 1f
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        frame.addView(placeholder)
        frame.addView(image)

        val memCached = customIconStore.getCachedMimeBitmap(mimeKey)
        if (memCached != null) {
            image.setImageBitmap(memCached)
            placeholder.visibility = View.GONE
            return frame
        }

        val mimeFile = customIconStore.getMimeIconFile(mimeKey) ?: return frame
        val previewKey = "mime-icon:$mimeKey:${mimeFile.lastModified()}:$sidePx"
        val cached = thumbnailCache.get(previewKey)
        if (cached != null) {
            image.setImageBitmap(cached)
            placeholder.visibility = View.GONE
            return frame
        }

        val generation = loadGeneration
        val hostView = cardView ?: frame
        registerVisibleCardProbe(hostView, generation) {
            imageIo.execute {
                if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                val bitmap = customIconStore.getMimeIconBitmap(mimeKey) { f ->
                    thumbnailDecoder.decode(Uri.fromFile(f), sidePx)
                }
                if (bitmap != null) {
                    thumbnailCache.put(previewKey, bitmap)
                    runOnUiThread {
                        if (generation == loadGeneration) {
                            image.setImageBitmap(bitmap)
                            placeholder.visibility = View.GONE
                        }
                    }
                }
            }
        }
        return frame
    }

    private fun imagePreview(entry: ResourceEntry, asGrid: Boolean, cardView: View? = null): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val previewKey = "${entry.ref.providerId}:${entry.ref.documentId}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}"
        val frame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "🖼"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            imageTintList = null
            alpha = 1f
            tag = previewKey
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        frame.addView(placeholder)
        frame.addView(image)
        val cached = thumbnailCache.get(previewKey)
        if (cached != null) {
            image.setImageBitmap(cached)
            placeholder.visibility = View.GONE
        } else if (failedThumbnails.get(previewKey) == null) {
            val generation = loadGeneration
            val hostView = cardView ?: frame
            registerVisibleCardProbe(hostView, generation) {
                imageIo.execute {
                    // Drop work queued for a folder the user has already left.
                    if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                    val bitmap = thumbnailDecoder.decode(entry.uri, sidePx)
                    if (bitmap != null) thumbnailCache.put(previewKey, bitmap) else failedThumbnails.put(previewKey, true)
                    runOnUiThread {
                        if (image.tag == previewKey && bitmap != null) {
                            image.setImageBitmap(bitmap)
                            placeholder.visibility = View.GONE
                        }
                    }
                }
            }
        }
        return frame
    }
    private fun cacheDirectoryIconListing(dirUri: Uri, dirRef: ResourceRef?, contents: List<ResourceEntry>): Uri? {
        ResourceIconSidecars.observe(contents, dirUri)
        val bestUri = DirectoryIconResolver.findBestIcon(contents)?.uri
        val value = bestUri?.toString() ?: ""
        dirRef?.let { directoryIconCache.put("${it.providerId}:${it.documentId}", value) }
        directoryIconCache.put("tree:$dirUri", value)
        if (ArchiveUri.isArchiveUri(dirUri) && ArchiveUri.getEntryPath(dirUri).isEmpty()) {
            ArchiveUri.getSourceUri(dirUri)?.let { sourceUri ->
                directoryIconCache.put("tree:$sourceUri", value)
                runCatching { resourceRef(sourceUri) }.getOrNull()?.let { sRef ->
                    directoryIconCache.put("${sRef.providerId}:${sRef.documentId}", value)
                }
            }
        }
        return bestUri
    }

    private fun resolveContainerDirectoryUri(entry: ResourceEntry): Pair<Uri, ResourceRef> {
        if (!entry.metadata.isDirectory && !ArchiveUri.isArchiveUri(entry.uri)) {
            val archiveUri = ArchiveUri.toArchiveUri(entry.uri)
            val archiveRef = runCatching { resourceRef(archiveUri) }.getOrElse { ResourceRef("archive", archiveUri.toString()) }
            return archiveUri to archiveRef
        }
        return entry.uri to entry.ref
    }

    private fun findDirectoryIcon(entry: ResourceEntry): Uri? {
        val (targetUri, targetRef) = resolveContainerDirectoryUri(entry)
        (resourceIconResolver.sidecar(entry) ?: resourceIconResolver.sidecar(targetUri, entry.metadata.name))?.uri?.let { return it }
        val known = ResourceIconSidecars.directory(targetUri)
        if (known != null) return known.entry?.uri
        if (localProvider.owns(targetUri)) {
            return try {
                val dir = localProvider.fileFor(targetUri)
                if (!dir.isDirectory) return null
                val files = dir.listFiles() ?: return null
                val candidateFiles = files.filter { file ->
                    !file.isDirectory && (
                        DirectoryIconResolver.isDirectoryIconFileName(file.name) ||
                        ImageFormats.extension(file.name) in ImageFormats.decodableExtensions
                    )
                }
                if (candidateFiles.isEmpty()) return null
                val entries = candidateFiles.map { localProvider.entry(it) }
                DirectoryIconResolver.findBestIcon(entries)?.uri
            } catch (_: Exception) { null }
        }
        return try {
            val result = provider.list(targetRef)
            if (result is ProviderResult.Success) {
                cacheDirectoryIconListing(targetUri, targetRef, result.value)
            } else null
        } catch (_: Exception) { null }
    }
    private fun findDirectoryIcon(uri: Uri): Uri? {
        val boundUri = com.infinifolder.storage.ContainerUris.resolve(
            uri,
            com.infinifolder.storage.StorageRuntime.get(this).connections.list()
        ) ?: uri
        val isLocalDir = localProvider.owns(boundUri) && runCatching { localProvider.fileFor(boundUri).isDirectory }.getOrDefault(false)
        val targetUri = if (!isLocalDir && !ArchiveUri.isArchiveUri(boundUri) && !boundUri.path.orEmpty().endsWith('/') &&
            ArchiveFormats.isDefaultBrowsableArchive(boundUri.lastPathSegment?.substringAfterLast('/').orEmpty(), "")) {
            ArchiveUri.toArchiveUri(boundUri)
        } else {
            boundUri
        }
        val ref = try { resourceRef(targetUri) } catch (_: Exception) { return null }
        val entry = ResourceEntry(ref, ResourceMetadata("", "", null, null, true), targetUri)
        return findDirectoryIcon(entry)
    }
    private fun directoryPreview(
        entry: ResourceEntry,
        asGrid: Boolean,
        cardView: View? = null,
        fallbackView: View? = null,
        probeImmediately: Boolean = false
    ): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val (containerUri, containerRef) = resolveContainerDirectoryUri(entry)
        val dirKey = "${containerRef.providerId}:${containerRef.documentId}"
        val frame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder: View = if (fallbackView != null) {
            fallbackView.apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            }
        } else {
            val isArchiveRoot = ArchiveUri.isArchiveUri(containerUri) && ArchiveUri.getEntryPath(containerUri).isEmpty()
            val themeFolderBmp = if (!isArchiveRoot && iconThemeManager.hasActiveTheme()) {
                iconThemeManager.resolveFolderIcon(entry.metadata.name, sidePx)
            } else null
            if (themeFolderBmp != null) {
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(themeFolderBmp)
                    layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                }
            } else {
                TextView(this).apply {
                    text = if (isArchiveRoot) "📦" else "📁"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                }
            }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            imageTintList = null
            alpha = 1f
            tag = dirKey
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        frame.addView(placeholder)
        frame.addView(image)

        val cachedUriString = directoryIconCache.get(dirKey) ?: directoryIconCache.get("tree:$containerUri")
        if (cachedUriString == "") {
            return frame
        }
        if (cachedUriString != null) {
            val previewKey = "dir-icon:$dirKey:$cachedUriString:$sidePx"
            val cached = thumbnailCache.get(previewKey) ?: thumbnailCache.get("dir-icon:$dirKey:$cachedUriString")
            if (cached != null) {
                image.setImageBitmap(cached)
                placeholder.visibility = View.GONE
                return frame
            }
        }

        val generation = loadGeneration
        val hostView = cardView ?: frame
        val probeAction: () -> Unit = {
            imageIo.execute {
                if ((!probeImmediately && generation != loadGeneration) || isFinishing || isDestroyed) return@execute
                val iconUri = if (cachedUriString != null) {
                    Uri.parse(cachedUriString)
                } else {
                    val resolved = findDirectoryIcon(entry)
                    val resolvedStr = resolved?.toString() ?: ""
                    directoryIconCache.put(dirKey, resolvedStr)
                    directoryIconCache.put("tree:$containerUri", resolvedStr)
                    resolved
                }
                if (iconUri == null) return@execute

                val previewKey = "dir-icon:$dirKey:$iconUri:$sidePx"
                val bitmap = thumbnailCache.get(previewKey) ?: thumbnailDecoder.decode(iconUri, sidePx)
                if (bitmap != null) {
                    thumbnailCache.put(previewKey, bitmap)
                    thumbnailCache.put("dir-icon:$dirKey:$iconUri", bitmap)
                }
                runOnUiThread {
                    if ((probeImmediately || generation == loadGeneration) && image.tag == dirKey && bitmap != null) {
                        image.setImageBitmap(bitmap)
                        placeholder.visibility = View.GONE
                    }
                }
            }
        }
        if (probeImmediately) {
            probeAction()
        } else {
            registerVisibleCardProbe(hostView, generation, probeAction)
        }
        return frame
    }
    private fun appPreview(packageName: String?, asGrid: Boolean, cardView: View? = null): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val frame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "📱"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            imageTintList = null
            alpha = 1f
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        frame.addView(placeholder)
        frame.addView(image)
        if (packageName.isNullOrEmpty()) return frame

        val cacheKey = "app-icon:$packageName:$sidePx"
        val cached = thumbnailCache.get(cacheKey) ?: appsProvider.getCachedAppIconBitmap(packageName, sidePx)?.also {
            thumbnailCache.put(cacheKey, it)
        }
        if (cached != null) {
            image.setImageBitmap(cached)
            placeholder.visibility = View.GONE
        }

        val generation = loadGeneration
        val hostView = cardView ?: frame
        registerVisibleCardProbe(hostView, generation) {
            imageIo.execute {
                if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                val bitmap = appsProvider.getAppIconBitmap(packageName, sidePx)
                if (bitmap != null) {
                    thumbnailCache.put(cacheKey, bitmap)
                    runOnUiThread {
                        if (generation == loadGeneration) {
                            image.setImageBitmap(bitmap)
                            placeholder.visibility = View.GONE
                        }
                    }
                }
            }
        }
        return frame
    }
    private fun resolveMimeType(fileUri: Uri, file: java.io.File, metadataMime: String): String {
        val pkgMime = com.infinifolder.archive.ArchiveFormats.packageMimeType(file.name)
        if (pkgMime != null) return pkgMime
        val fromResolver = try { contentResolver.getType(fileUri) } catch (_: Exception) { null }
        if (!fromResolver.isNullOrEmpty() && fromResolver != "application/octet-stream") {
            return fromResolver
        }
        val ext = file.extension.lowercase()
        val fromMap = android.webkit.MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
        if (!fromMap.isNullOrEmpty() && fromMap != "application/octet-stream") {
            return fromMap
        }
        if (metadataMime.isNotEmpty() && metadataMime != "application/octet-stream") {
            return metadataMime
        }
        val guessed = com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(file.name, false)
        if (guessed.isNotEmpty() && guessed != "application/octet-stream" && !com.infinifolder.storage.FileMagicDetector.isAmbiguousOrUnknown(file.name, guessed)) {
            return guessed
        }
        val magic = com.infinifolder.storage.FileMagicDetector.detect(file, peekCompressedPayload = true)
        if (magic != null) {
            return magic.mimeType
        }
        return guessed.ifEmpty { "application/octet-stream" }
    }

    private fun launchApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot launch app: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val settingsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(settingsIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open settings for $packageName: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = Uri.parse("package:$packageName")
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot uninstall $packageName: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchBuiltInViewer(entry: ResourceEntry, mediaType: com.infinifolder.viewer.MediaType): Boolean {
        val intent = com.infinifolder.viewer.ViewerLaunch.intent(this, entry, mediaType, current) ?: return false
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start built-in viewer: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private val externalCacheLeases = java.util.concurrent.ConcurrentLinkedQueue<java.io.Closeable>()

    private fun openFoundPartialDownload(entry: ResourceEntry) {
        val catalog = com.infinifolder.storage.StorageRuntime.get(this).partialDownloads
        val record = catalog.find(entry.ref) ?: return
        if (record.entry.metadata.name.endsWith(".incomplete", true)) {
            resumeIncompleteDownload(record.entry.ref)
            return
        }
        dialogBuilder().setTitle(record.entry.metadata.name)
            .setMessage("${record.kind}\n\nThis file was discovered while browsing or scanning storage. Continuing it requires the original source and compatible resume metadata. Importing this format is not implemented yet.\n\n${record.entry.uri}")
            .setPositiveButton("Browse location") { _, _ ->
                io.execute {
                    val parent = (provider.stat(record.parent) as? ProviderResult.Success)?.value
                    runOnUiThread {
                        if (parent != null) navigate(parent.uri, displayName = parent.metadata.name)
                        else Toast.makeText(this, "Original location is unavailable", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Forget entry") { _, _ -> catalog.forget(entry.ref); current?.let { load(it) } }
            .setNegativeButton("Close", null).show()
    }

    private fun resumeIncompleteDownload(ref: ResourceRef) {
        io.execute {
            val scheduled = (provider as? RoutingStorageProvider)?.resumePendingTorrentTransfers(ref) ?: 0
            runOnUiThread {
                if (scheduled > 0) {
                    com.infinifolder.ops.OperationService.start(this)
                    showOperations()
                } else android.app.AlertDialog.Builder(this)
                    .setTitle("Incomplete torrent download")
                    .setMessage("No saved torrent session matches this file. Open the original torrent or magnet link and copy to this download's original folder to resume it.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun open(entry: ResourceEntry, externalOnly: Boolean = false) {
        if (!entry.metadata.isDirectory && entry.metadata.name.endsWith(".incomplete", true) && !externalOnly) {
            resumeIncompleteDownload(entry.ref)
            return
        }
        if (com.infinifolder.storage.LinkResolver.kind(entry.metadata) != null) {
            val sourceLocation = current
            val cachedTermuxLink = shortcutCache.get(entry.uri.toString())?.targetUri
                ?.let { runCatching { TermuxCommandLink.owns(Uri.parse(it)) }.getOrDefault(false) } == true
            val linkNavigationToken = if (!externalOnly &&
                !cachedTermuxLink && !entry.metadata.name.endsWith(TermuxCommandLink.SUFFIX, true) &&
                (ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType) || entry.ref.providerId == containerLinkProvider.id))
                showOpeningVirtualDirectory(entry.metadata.name.removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION), preserveBreadcrumbs = true)
            else null
            val resolveLink = Runnable { io.execute {
                val info = if (!entry.metadata.isSymbolicLink) readShortcutInfo(entry) else null
                val linkedUri = info?.targetUri?.let(Uri::parse)
                if (!externalOnly && linkedUri != null &&
                    (ContainerUris.networkType(linkedUri) != null || linkedUri.scheme.equals("magnet", true) ||
                        linkedUri.scheme.equals("torrent", true) || entry.ref.providerId == containerLinkProvider.id)) {
                    runOnUiThread {
                        if (!isDestroyed && (linkNavigationToken == null || browserNavigationGeneration == linkNavigationToken))
                            openContainerLinkUri(linkedUri, info.displayNameOverride,
                                info.targetIsDirectory || entry.ref.providerId == containerLinkProvider.id,
                                LinkNavigationOrigin.DIRECTORY_LINK)
                    }
                    return@execute
                }
                val resolved = try { com.infinifolder.storage.LinkResolver.resolve(provider, entry) }
                    catch (error: Throwable) { ProviderResult.Failure(error.message ?: "Could not resolve link", error) }
                runOnUiThread {
                    if (linkNavigationToken != null && browserNavigationGeneration != linkNavigationToken) return@runOnUiThread
                    when (resolved) {
                        is ProviderResult.Success -> {
                            val target = resolved.value
                            val targetEntry = target.entry
                            if (TermuxCommandLink.owns(target.uri)) {
                                if (linkNavigationToken != null && sourceLocation != null) navigate(sourceLocation, push = false)
                                TermuxCommandLink.launch(this, target.uri)
                                return@runOnUiThread
                            }
                            if (targetEntry == null) launchExternalViewIntent(target.uri, target.mime, target.name)
                            else if (targetEntry.metadata.isDirectory && !externalOnly) navigate(targetEntry.uri, displayName = targetEntry.metadata.name)
                            else open(targetEntry, externalOnly)
                        }
                        is ProviderResult.Failure -> Toast.makeText(this, resolved.message, Toast.LENGTH_LONG).show()
                        else -> Unit
                    }
                }
            } }
            if (linkNavigationToken != null) {
                // Give the blank virtual directory a frame before reading link metadata
                // or initiating any provider/network resolution.
                root.postDelayed({
                    if (!isDestroyed && browserNavigationGeneration == linkNavigationToken) resolveLink.run()
                }, 120L)
            } else resolveLink.run()
            return
        }
        if (!externalOnly && entry.ref.providerId != "torrent" && entry.metadata.name.endsWith(".torrent", ignoreCase = true)) {
            openTorrentFile(entry)
            return
        }
        if (AppsUri.isAppUri(entry.uri)) {
            val pkg = AppsUri.getPackageName(entry.uri)
            if (pkg != null) {
                launchApp(pkg)
            }
            return
        }
        val termuxRule = if (!externalOnly && !entry.metadata.isDirectory) TermuxAssociations(this).find(entry.metadata.name) else null
        if (termuxRule != null && TermuxEditorAvailability.state(this, termuxRule.command) == TermuxEditorAvailability.State.AVAILABLE) {
            startActivity(Intent(this, TermuxEditActivity::class.java).setData(entry.uri))
            return
        }
        val cachedMagic = ambiguousMagicCache.get(entry.uri.toString())
        val effectiveMime = com.infinifolder.mime.MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType, cachedMagic?.mimeType)
        val isPkg = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isPackagedFormat(entry.metadata.name, effectiveMime)
        val isArchiveOrDisc = !AppsUri.isAppUri(entry.uri) && (
            ArchiveFormats.isArchive(entry.metadata.name, effectiveMime) ||
            (cachedMagic != null && (cachedMagic.isArchiveOrDiscImage || ArchiveFormats.isDiscImage(entry.metadata.name, cachedMagic.mimeType)))
        )
        if (isArchiveOrDisc && !isPkg && !externalOnly) {
            navigate(ArchiveUri.toArchiveUri(entry.uri), displayName = entry.metadata.name)
            return
        }
        if (useBuiltInViewersAndPlayers && !externalOnly) {
            val mediaType = com.infinifolder.viewer.MediaTypeDetector.detect(entry.metadata.name, effectiveMime)
            if (mediaType != com.infinifolder.viewer.MediaType.UNSUPPORTED && mediaType != com.infinifolder.viewer.MediaType.ARCHIVE_OR_CONTAINER) {
                if (launchBuiltInViewer(entry.copy(metadata = entry.metadata.copy(mimeType = effectiveMime)), mediaType)) {
                    return
                }
            }
        }
        val pkgMime = ArchiveFormats.packageMimeType(entry.metadata.name)
        val externalMime = pkgMime
            ?: cachedMagic?.mimeType?.takeIf { it.isNotEmpty() }
            ?: entry.metadata.mimeType
        if (provider.refForUri(entry.uri) != null) {
            startActivity(Intent(this, com.infinifolder.viewer.ExternalEditActivity::class.java).setData(entry.uri))
        } else launchExternalViewIntent(entry.uri, externalMime, entry.metadata.name)
    }

    /** Enter a blank torrent directory first, then parse local .torrent metadata off the UI thread. */
    private fun openTorrentFile(entry: ResourceEntry) {
        val title = entry.metadata.name.removeSuffix(".torrent").ifBlank { "Torrent" }
        val navigationToken = showOpeningVirtualDirectory(title, preserveBreadcrumbs = true)
        io.execute {
            try {
                val input = when (val result = provider.openRead(entry.ref)) {
                    is ProviderResult.Success -> result.value
                    is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw java.io.IOException("This storage provider cannot read torrent files")
                }
                val bytes = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 16 * 1024 * 1024) {
                            throw java.io.IOException("Torrent file is larger than the supported 16 MiB limit")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val torrentUri = com.infinifolder.storage.StorageRuntime.get(this).torrents.registerTorrentFile(bytes)
                runOnUiThread {
                    if (!isDestroyed && browserNavigationGeneration == navigationToken) {
                        navigate(torrentUri, push = true, displayName = title, deferInitialLoad = true)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && browserNavigationGeneration == navigationToken) {
                        openingVirtualDirectoryLabel = null
                        openingTorrentDirectory = false
                        listingError = error.message ?: "Could not read torrent file"
                        status.text = listingError
                        renderEntries()
                    }
                }
            }
        }
    }

    private fun launchExternalViewIntent(targetUri: Uri, mimeType: String? = null, displayName: String? = null) {
        if (TermuxCommandLink.owns(targetUri)) {
            TermuxCommandLink.launch(this, targetUri)
            return
        }
        val scheme = targetUri.scheme?.lowercase()
        if (WebMetadataResolver.isWebUri(targetUri.toString()) || (scheme != null && scheme !in setOf("file", "content"))) {
            val browsableIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(browsableIntent)
                return
            } catch (_: Exception) {
                try {
                    val plainIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(plainIntent)
                    return
                } catch (_: Exception) {
                    Toast.makeText(this, "No app can open this URI ($targetUri).", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        val shareableUri = if (scheme == "file" && targetUri.path != null) {
            runCatching {
                FileProvider.getUriForFile(this, "${packageName}.files", File(targetUri.path!!))
            }.getOrDefault(targetUri)
        } else {
            targetUri
        }
        val fileName = displayName ?: targetUri.lastPathSegment ?: ""
        val pkgMime = ArchiveFormats.packageMimeType(fileName)
        val resolvedMime = when {
            pkgMime != null -> pkgMime
            !mimeType.isNullOrBlank() && mimeType != "application/octet-stream" && mimeType != "text/uri-list" -> mimeType
            scheme == "file" && targetUri.path != null -> resolveMimeType(shareableUri, File(targetUri.path!!), mimeType ?: "")
            else -> try { contentResolver.getType(shareableUri) } catch (_: Exception) { null } ?: mimeType ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareableUri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(displayName ?: shareableUri.lastPathSegment ?: "file", shareableUri)
        }
        val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (resolveInfo in resInfoList) {
            grantUriPermission(resolveInfo.activityInfo.packageName, shareableUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, shareableUri).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(this, "No app can open this file or URI.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun showHomeRoot() {
        if (selectionMode) exitSelectionMode()
        ++loadGeneration
        val generation = ++homeSourcesGeneration
        accessRequestGeneration++
        homeMode = true
        listingIsLoading = false
        cancelEntryActionLoading()
        updateEntryLoadingFeedback()
        homePage = "root"
        logicalLocationPrefix = null
        currentProgramName = null
        updateCreateAction()
        renderLocation()
        status.text = "Locations and storage sources"
        content.removeAllViews()
        shared.edit().remove("tree_source_installed_apps").apply()

        fun buildStaticTailItems(): List<HomeItem> {
            val tail = mutableListOf<HomeItem>()
            tail += HomeItem("⬇", "Partial downloads", "Discovered unfinished downloads", {
                openRoot(com.infinifolder.storage.PartialDownloadsProvider.ROOT, rememberAuthorizedRoot = false)
            }, treeKey = "partial_downloads", treeUri = com.infinifolder.storage.PartialDownloadsProvider.ROOT)
            tail += HomeItem("🕘", "External link history", "Recently opened storage links", {
                showExternalLinkHistory()
            }, longAction = {
                dialogBuilder().setTitle("External link history")
                    .setMessage("Clear all remembered external links?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        shared.edit().remove(externalLinkHistoryKey).apply()
                        if (homeMode && homePage == "external_link_history") renderHomeItems()
                    }.show()
            }, treeKey = "external_link_history")
            savedConnections().forEach { connection ->
                if (shared.getBoolean("root_link_migrated_${connection.id}", false)) return@forEach
                val source = providerRegistry.sourceTypes().firstOrNull { it.id == connection.sourceTypeId }
                val icon = source?.icon ?: "🌐"
                val detail = connection.endpoint ?: "Saved connection · provider not implemented"
                if (connection.sourceTypeId == "sftp") {
                    val rootUri = SftpUri.build(connection.id, connection.options["initialPath"] ?: "/")
                    tail += HomeItem(
                        icon = icon,
                        title = connection.displayName,
                        detail = "${connection.endpoint ?: "SFTP"} · ${connection.options["username"] ?: "user"}",
                        action = { openRoot(rootUri, rememberAuthorizedRoot = false) },
                        longAction = { showSavedConnectionActions(connection) },
                        treeKey = "sftp_${connection.id}",
                        treeUri = rootUri,
                        treeExpandable = true
                    )
                } else if (connection.sourceTypeId == "ftp") {
                    val rootUri = FtpUri.build(connection.id, connection.options["initialPath"] ?: "/")
                    val userLabel = if (connection.options["isAnonymous"] == "true") "anonymous" else (connection.options["username"] ?: "user")
                    tail += HomeItem(
                        icon = icon,
                        title = connection.displayName,
                        detail = "${connection.endpoint ?: "FTP"} · $userLabel",
                        action = { openRoot(rootUri, rememberAuthorizedRoot = false) },
                        longAction = { showSavedConnectionActions(connection) },
                        treeKey = "ftp_${connection.id}",
                        treeUri = rootUri,
                        treeExpandable = true
                    )
                } else if (connection.sourceTypeId == "smb") {
                    val initialPath = if (!connection.options["initialShare"].isNullOrBlank()) {
                        "/${connection.options["initialShare"]!!.trim('/')}"
                    } else {
                        connection.options["initialPath"] ?: "/"
                    }
                    val rootUri = SmbUri.build(connection.id, initialPath)
                    val userLabel = if (connection.options["isGuest"] == "true") "guest" else (connection.options["username"]?.ifEmpty { "guest" } ?: "guest")
                    tail += HomeItem(
                        icon = icon,
                        title = connection.displayName,
                        detail = "${connection.endpoint ?: "SMB"} · $userLabel",
                        action = { openRoot(rootUri, rememberAuthorizedRoot = false) },
                        longAction = { showSavedConnectionActions(connection) },
                        treeKey = "smb_${connection.id}",
                        treeUri = rootUri,
                        treeExpandable = true
                    )
                } else if (connection.sourceTypeId == "webdav") {
                    val rootUri=WebDavUri.build(connection.id,connection.options["initialPath"] ?: "/")
                    tail += HomeItem(icon, connection.displayName, "${connection.endpoint ?: connection.options["url"] ?: "WebDAV"} · ${connection.options["username"] ?: "user"}",
                        { openRoot(rootUri,rememberAuthorizedRoot=false) }, { showSavedConnectionActions(connection) },
                        treeKey="webdav_${connection.id}",treeUri=rootUri,treeExpandable=true)
                } else {
                    tail += HomeItem(icon, connection.displayName, detail, {
                        dialogBuilder().setTitle(connection.displayName)
                            .setMessage("This ${source?.label ?: connection.sourceTypeId} connection is saved, but network browsing is not implemented yet.")
                            .setPositiveButton("OK", null).show()
                    }, { showSavedConnectionActions(connection) })
                }
            }
            tail += rootContainerLinks.map { (entry, shortcut) -> rootLinkHomeItem(entry, shortcut) }
            tail += HomeItem("📱", "Application public", "Storage exposed by applications", {
                showApplicationPublic()
            }, treeKey = "application_public", treeExpandable = true)
            tail += HomeItem("📲", "Installed Applications", "Programs installed on the system", {
                openInstalledApps()
            }, treeKey = "installed_apps", treeUri = AppsUri.ROOT_URI, treeExpandable = true)
            return tail
        }

        if (homeItems.isEmpty()) {
            homeItems += HomeItem("📱", "Internal shared storage", "Shared folders", { showInternalShared() },
                treeKey = "internal_shared", treeExpandable = true, needsAccessBadge = !localProvider.hasAccess())
            homeItems += buildStaticTailItems()
        }
        renderHomeItems()
        rebuildTreeRoots()

        accessIo.execute {
            migrateContainerConnectionLinks()
            val storedLinks = (containerLinkProvider.list(ResourceRef(containerLinkProvider.id, ContainerLinksProvider.ROOT.toString())) as? ProviderResult.Success)?.value.orEmpty()
            rootContainerLinks = storedLinks.mapNotNull { entry ->
                val shortcut = runCatching { (containerLinkProvider.openRead(entry.ref) as? ProviderResult.Success)?.value?.use { ShortcutInfo.fromJson(it.reader().readText()) } }.getOrNull()
                shortcut?.let { entry to it }
            }
            val hasLocalAccess = localProvider.hasAccess()
            val grantedRoots = try {
                contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission && it.uri.pathSegments.contains("tree") }
                    .map { it.uri }
                    .filterNot { AppDocumentSources.isAppAuthority(it) || AppsUri.isAppsUri(it) || SftpUri.isSftpUri(it) || FtpUri.isFtpUri(it) || SmbUri.isSmbUri(it) || WebDavUri.owns(it) || ArchiveUri.isArchiveUri(it) }
                    .distinctBy(::sourceIdentity)
                    .toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
            shared.getString("root", null)?.let { rootString ->
                val uri = Uri.parse(rootString)
                if (AppsUri.isAppsUri(uri) || SftpUri.isSftpUri(uri) || FtpUri.isFtpUri(uri) || SmbUri.isSmbUri(uri) || WebDavUri.owns(uri) || ArchiveUri.isArchiveUri(uri)) {
                    // Virtual / remote roots are not SAF document trees
                } else if (!localProvider.owns(uri) && !AppDocumentSources.isAppAuthority(uri) &&
                    grantedRoots.none { sourceIdentity(it) == sourceIdentity(uri) }) {
                    grantedRoots.add(uri)
                }
            }

            val discoveredItems = mutableListOf<HomeItem>()
            val pathsToResolve = mutableListOf<Pair<Uri, String>>()
            grantedRoots.forEach { uri ->
                if (SftpUri.isSftpUri(uri) || FtpUri.isFtpUri(uri) || SmbUri.isSmbUri(uri) || WebDavUri.owns(uri) || ArchiveUri.isArchiveUri(uri)) return@forEach
                if (hasLocalAccess && isPrimaryExternalStorageUri(uri)) return@forEach
                val name = shared.getString("source_name_${uri}", null) ?: "Storage folder"
                val cachedPath = shared.getString("source_path_v2_${uri}", null)
                val path = cachedPath ?: name
                val key = "authorized_${sourceIdentity(uri)}"
                discoveredItems += HomeItem("💾", name, path, { openRoot(uri) }, { showAuthorizedRootActions(uri, name) }, key, uri, true, false,
                    initialUri = uri, treeParentKey = if (isPrimaryExternalStorageUri(uri)) "internal_shared" else null)
                if (cachedPath == null) {
                    pathsToResolve += uri to name
                }
            }

            try {
                getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { volume ->
                    val mounted = volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                    val description = volume.getDescription(this)
                    val key = if (volume.isPrimary) "internal_shared" else "volume_${volume.uuid ?: description.hashCode()}"
                    val grantedUri = treeSourceUri(key)
                    val volDir = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { volume.directory }.getOrNull() else null
                    val hasDirectAccess = hasLocalAccess && volDir != null && volDir.canRead()
                    val detail = when {
                        !mounted -> "Not currently mounted"
                        volume.isRemovable -> if (hasDirectAccess) {
                            if (volume.state == Environment.MEDIA_MOUNTED_READ_ONLY) "Removable storage · Read-only" else "Removable storage · Mounted"
                        } else "Removable storage · choose a folder"
                        volume.isPrimary -> "Device storage · choose a folder"
                        else -> if (hasDirectAccess) "Available volume · Mounted" else "Available volume · choose a folder"
                    }
                    if (volume.isPrimary) {
                        discoveredItems += HomeItem("📱", "Internal shared storage", "Shared folders", {
                            showInternalShared()
                        }, treeKey = key, treeExpandable = true, needsAccessBadge = !hasLocalAccess)
                    } else {
                        discoveredItems += HomeItem(if (volume.isRemovable) "💾" else "📱", description, detail, {
                            if (grantedUri != null) {
                                openRoot(grantedUri, description, false)
                            } else if (hasDirectAccess) {
                                val sdUri = localProvider.uriFor(volDir!!)
                                openRoot(sdUri, description, false)
                            } else if (mounted && android.os.Build.VERSION.SDK_INT >= 29) {
                                pendingTreeSourceKey = key
                                startActivityForResult(volume.createOpenDocumentTreeIntent(), pickTreeRequest)
                            } else if (mounted) {
                                rootPicker(sourceKey = key)
                            } else {
                                Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
                            }
                        }, treeKey = key, treeUri = grantedUri, treeExpandable = mounted, needsAccessBadge = mounted && grantedUri == null && !hasDirectAccess)
                    }
                }
            } catch (_: Exception) {}

            if (discoveredItems.none { it.treeKey == "internal_shared" }) {
                discoveredItems += HomeItem("📱", "Internal shared storage", "Shared folders", { showInternalShared() },
                    treeKey = "internal_shared", treeExpandable = true, needsAccessBadge = !hasLocalAccess)
            }
            discoveredItems += buildStaticTailItems()

            runOnUiThread {
                if (isFinishing || isDestroyed || generation != homeSourcesGeneration) return@runOnUiThread
                homeItems.clear()
                homeItems.addAll(discoveredItems)
                if (homeMode && homePage == "root") {
                    renderHomeItems()
                }
                rebuildTreeRoots()
                resolveExistingHomeGrants()
                discoverDocumentProviderRoots()
                refreshInternalSharedTree()
                refreshInstalledAppsTree()
            }

            pathsToResolve.forEach { (uri, fallbackName) ->
                io.execute {
                    val resolved = displayPathFor(uri) ?: fallbackName
                    shared.edit().putString("source_path_v2_${uri}", resolved).apply()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && homeMode) {
                            val index = homeItems.indexOfFirst { it.treeUri == uri }
                            if (index >= 0) homeItems[index] = homeItems[index].copy(detail = resolved)
                            renderHomeItems()
                        }
                    }
                }
            }
        }
    }
    private fun refreshInstalledAppsTree() {
        val generation = ++appsListGeneration
        io.execute {
            val categories = appsProvider.getCategories()
            runOnUiThread {
                if (generation != appsListGeneration || isFinishing || isDestroyed) return@runOnUiThread
                installedAppCategories = categories
                rebuildTreeRoots()
            }
        }
    }
    private fun showInternalShared(promptForAccess: Boolean = true) {
        if (localProvider.hasAccess()) {
            if (treeVisible && isCompactPortrait()) setTreeVisible(false)
            openRoot(localProvider.rootUri, rememberAuthorizedRoot = false)
            return
        }
        loadGeneration++
        accessRequestGeneration++
        homeMode = true
        listingIsLoading = false
        cancelEntryActionLoading()
        updateEntryLoadingFeedback()
        homePage = "internal_shared"
        logicalLocationPrefix = null
        currentProgramName = null
        updateCreateAction()
        renderLocation()
        status.text = "Previously granted folders only. Allow storage access to see the actual root, including Downloads."
        renderHomeItems()
        if (promptForAccess) requestSharedStorageAccess()
    }

    private fun requestSharedStorageAccess() {
        if (localProvider.hasAccess()) { showInternalShared(false); return }
        val modern = android.os.Build.VERSION.SDK_INT >= 30
        dialogBuilder().setTitle("Access internal shared storage")
            .setMessage(if (modern) "To list the actual files and folders, including Downloads, enable Allow access to manage all files for InfiniFolder in Android settings. Android’s folder picker cannot grant the storage root or Downloads. Other apps’ private data stays restricted."
                else "Allow storage access to browse the files and folders in internal shared storage.")
            .setNegativeButton("Not now", null)
            .setPositiveButton(if (modern) "Open settings" else "Allow access") { _, _ ->
                if (modern) {
                    waitingForStorageAccess = true
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        catch (_: android.content.ActivityNotFoundException) {
                            waitingForStorageAccess = false
                            Toast.makeText(this, "This device does not provide the storage access settings page.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 4102)
            }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4102) {
            hadStorageAccess = localProvider.hasAccess()
            showHomeRoot()
            showInternalShared(false)
        } else if (requestCode == 9002) {
            OperationService.start(this)
            if (pendingMinimizeAfterNotificationPermission) {
                pendingMinimizeAfterNotificationPermission = false
                moveTaskToBack(true)
            }
        }
    }

    private fun refreshInternalSharedTree(onRefreshed: (() -> Unit)? = null) {
        onRefreshed?.let(pendingInternalTreeRefreshCallbacks::add)
        val generation = ++internalListGeneration
        if (!localProvider.hasAccess()) {
            internalSharedEntries = emptyList()
            rebuildTreeRoots()
            runPendingInternalTreeRefreshCallbacks()
            return
        }
        io.execute {
            val result = localProvider.list(resourceRef(localProvider.rootUri))
            runOnUiThread {
                if (generation != internalListGeneration || isFinishing) return@runOnUiThread
                if (result is ProviderResult.Success) internalSharedEntries = result.value
                else internalSharedEntries = emptyList()
                rebuildTreeRoots()
                runPendingInternalTreeRefreshCallbacks()
            }
        }
    }

    private fun runPendingInternalTreeRefreshCallbacks() {
        val callbacks = pendingInternalTreeRefreshCallbacks.toList()
        pendingInternalTreeRefreshCallbacks.clear()
        callbacks.forEach { callback -> runCatching(callback) }
    }
    private fun showApplicationPublic() {
        loadGeneration++
        accessRequestGeneration++
        homeMode = true
        listingIsLoading = false
        cancelEntryActionLoading()
        updateEntryLoadingFeedback()
        homePage = "application_public"
        logicalLocationPrefix = null
        currentProgramName = null
        updateCreateAction()
        renderLocation()
        status.text = "Finding app-provided storage…"
        renderHomeItems()
        rebuildTreeRoots()
        discoverDocumentProviderRoots()
    }
    private fun discoverDocumentProviderRoots() {
        val generation = ++applicationSourceGeneration
        accessIo.execute {
            val discovered = runCatching { AppDocumentSources(applicationContext).discover() }
            runOnUiThread {
                if (generation != applicationSourceGeneration || isFinishing || isDestroyed) return@runOnUiThread
                discovered.onSuccess { sources ->
                    applicationPublicItems.clear()
                    sources.forEach { source ->
                        val progName = source.detail.ifBlank { source.title }
                        applicationPublicItems += HomeItem(
                            "📁", source.title, source.detail,
                            {
                                currentProgramName = progName
                                source.granted?.let { openRoot(it, "Application public", false) }
                                    ?: rootPicker(source.target, source.key, source.authority)
                            },
                            treeKey = source.key, treeUri = source.granted, treeExpandable = true,
                            showInHome = false, initialUri = source.target, treeParentKey = "application_public",
                            needsAccessBadge = source.granted == null, providerAuthority = source.authority
                        )
                    }
                    if (homeMode && homePage == "application_public") {
                        status.text = if (sources.isEmpty()) "No apps exposing public document storage were found."
                            else "${sources.size} app storage locations"
                        renderHomeItems()
                    }
                    rebuildTreeRoots()
                }.onFailure {
                    if (homeMode && homePage == "application_public") status.text = "Could not list app storage: ${it.message}"
                }
            }
        }
    }
    private fun treeSourceUri(key: String): Uri? = shared.getString("tree_source_$key", null)?.let(Uri::parse)
    private fun renderHomeItems() {
        artworkRenderGeneration++
        pendingCardProbes.clear()
        visibleProbeScheduled = false
        entryIconViews.clear()
        renderedFileLocation = null
        val generation = loadGeneration
        val page = homePage
        if (homePage == "root") entries = rootContainerLinks.map { it.first }
        content.removeAllViews()
        val visibleItems = if (homePage == "internal_shared") {
            homeItems.filter { it.treeParentKey == "internal_shared" }
                .sortedBy { if (it.treeKey?.startsWith("public_") == true) 0 else 1 }
        } else if (homePage == "application_public") {
            applicationPublicItems
        } else if (homePage == "external_link_history") {
            externalLinkVisits().map { visit ->
                val target = Uri.parse(visit.uri)
                HomeItem(
                    icon = if (target.scheme.equals("magnet", true)) "🧲" else "🔗",
                    title = visit.title,
                    detail = target.toString().take(180),
                    action = { openContainerLinkUri(target, visit.title, requireContainer = true, origin = LinkNavigationOrigin.EXTERNAL_HISTORY) },
                    longAction = {
                        dialogBuilder().setTitle("Remove link from history?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove") { _, _ -> removeExternalLinkVisit(visit.uri) }.show()
                    }
                )
            }
        } else homeItems.filter { it.showInHome && it.treeParentKey == null }
        if (homePage == "external_link_history" && visibleItems.isEmpty()) {
            content.addView(TextView(dialogContext()).apply {
                text = "Links opened from other apps will appear here."
                setTextColor(setTextColorForTheme())
                setPadding(dp(16), dp(18), dp(16), dp(18))
            })
            return
        }
        if (homePage == "internal_shared" && !localProvider.hasAccess()) {
            content.addView(Button(dialogContext()).apply {
                text = "Allow internal storage access"
                setOnClickListener { requestSharedStorageAccess() }
            })
        }
        if (visibleItems.isEmpty()) return
        if (grid) {
            val containerPx = if (content.width > 0) content.width else if (fileScroll.width > 0) fileScroll.width else 0
            val availableWidth = if (containerPx > 0) {
                (containerPx / resources.displayMetrics.density).roundToInt()
            } else {
                (resources.configuration.screenWidthDp - if (treeVisible) 220 else 32).coerceAtLeast(iconSizeDp)
            }
            val columns = gridColumns(availableWidth)
            val table = TableLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                isStretchAllColumns = true
            }
            visibleItems.chunked(columns).forEach { group ->
                val row = TableRow(this).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                group.forEach { item -> row.addView(homeItemCard(item, true), TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)) }
                repeat(columns - group.size) { row.addView(View(this), TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)) }
                table.addView(row)
            }
            content.addView(table)
        } else visibleItems.forEach { content.addView(homeItemCard(it, false)) }
        scheduleVisibleProbe()
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (fileScroll.viewTreeObserver.isAlive)
                    fileScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!isDestroyed && homeMode && homePage == page && loadGeneration == generation) {
                    scheduleVisibleProbe()
                }
            }
        }
        fileScroll.viewTreeObserver.addOnGlobalLayoutListener(listener)
        fileScroll.requestLayout()
    }
    private fun homeItemCard(item: HomeItem, asGrid: Boolean): View {
        item.linkEntry?.let { entry -> return entryCard(entry, asGrid) }
        val box = LinearLayout(this).apply {
            orientation = if (asGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (asGrid) (Gravity.TOP or Gravity.CENTER_HORIZONTAL) else Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            val hPad = if (asGrid) dp(2) else dp(10)
            val vPad = if (asGrid) dp(4) else dp(8)
            setPadding(hPad, vPad, hPad, vPad); minimumHeight = dp(64)
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val iconSize = if (asGrid) iconSizeDp else 28
        val iconWidth = if (asGrid) dp(iconSize) else dp(52)
        val iconHeight = if (asGrid) dp(iconSize) else dp(48)
        val iconFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            val iconParams = LinearLayout.LayoutParams(iconWidth, iconHeight).apply {
                gravity = if (asGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                if (asGrid) bottomMargin = dp(4)
            }
            layoutParams = iconParams
        }
        val themePlaceBmp = if (iconThemeManager.hasActiveTheme()) {
            val key = item.treeParentKey ?: item.treeKey ?: item.title
            iconThemeManager.resolvePlaceIcon(key, iconWidth)
        } else null

        val placeholderView: View = if (themePlaceBmp != null) {
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(themePlaceBmp)
            }
        } else {
            TextView(this).apply {
                text = item.icon
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, iconTextSizeDp(asGrid))
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
        }
        iconFrame.addView(placeholderView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val targetTreeUri = item.treeUri
        if (targetTreeUri != null && !item.needsAccessBadge && !AppsUri.isAppsUri(targetTreeUri)) {
            val dirKey = "tree:$targetTreeUri"
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                tag = dirKey
            }
            iconFrame.addView(image, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            val cachedUri = directoryIconCache.get(dirKey)
            if (cachedUri != null && cachedUri.isNotEmpty()) {
                val previewKey = "home-dir-icon:$dirKey:$cachedUri:$iconWidth"
                val cachedBitmap = thumbnailCache.get(previewKey) ?: thumbnailCache.get("dir-icon:$dirKey:$cachedUri")
                if (cachedBitmap != null) {
                    image.setImageBitmap(cachedBitmap)
                    placeholderView.visibility = View.GONE
                }
            }
            if (cachedUri != "" && placeholderView.visibility == View.VISIBLE) {
                val generation = loadGeneration
                registerVisibleCardProbe(box, generation) {
                    imageIo.execute {
                        if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                        val iconUri = if (!cachedUri.isNullOrEmpty()) Uri.parse(cachedUri) else {
                            findDirectoryIcon(targetTreeUri).also {
                                directoryIconCache.put(dirKey, it?.toString() ?: "")
                            }
                        }
                        if (iconUri == null) return@execute
                        val previewKey = "home-dir-icon:$dirKey:$iconUri:$iconWidth"
                        val bitmap = thumbnailCache.get(previewKey) ?: thumbnailDecoder.decode(iconUri, iconWidth)
                        if (bitmap != null) {
                            thumbnailCache.put(previewKey, bitmap)
                            runOnUiThread {
                                if (generation == loadGeneration && image.tag == dirKey) {
                                    image.setImageBitmap(bitmap)
                                    placeholderView.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }
        }
        if (item.needsAccessBadge) {
            iconFrame.addView(accessBadge().apply { setOnClickListener { activateHomeItem(item) } }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.TOP))
        }
        box.addView(iconFrame)
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (asGrid) {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                gravity = Gravity.CENTER_HORIZONTAL
            } else {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        labels.addView(TextView(this).apply {
            text = item.title; textSize = 14f; setTextColor(setTextColorForTheme()); maxLines = if (asGrid) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (asGrid) {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            } else {
                gravity = Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
        })
        if (!asGrid) labels.addView(TextView(this).apply {
            text = item.detail; textSize = 12f; setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()); maxLines = 2
        })
        box.addView(labels)
        box.setOnClickListener { activateHomeItem(item) }
        item.longAction?.let { action -> box.setOnLongClickListener { action(); true } }
        return box
    }
    private fun activateHomeItem(item: HomeItem) {
        if (item.needsAccessBadge) {
            when {
                item.treeKey == "application_public" -> item.action()
                item.providerAuthority != null -> item.action()
                item.treeKey?.startsWith("sftp_") == true || item.treeKey?.startsWith("ftp_") == true || item.treeKey?.startsWith("smb_") == true -> item.action()
                item.initialUri != null && !SftpUri.isSftpUri(item.initialUri) && !FtpUri.isFtpUri(item.initialUri) && !SmbUri.isSmbUri(item.initialUri) && !WebDavUri.owns(item.initialUri) -> rootPicker(item.initialUri, item.treeKey)
                else -> item.action()
            }
        } else item.action()
    }
    private fun showSidebar() {
        setTreeVisible(!treeVisible)
    }
    private fun setTreeVisible(visible: Boolean) {
        treeVisible = visible
        shared.edit().putBoolean("tree_visible", treeVisible).apply()
        updateBrowserPanes()
        if (!homeMode) renderEntries()
        if (homeMode && homePage != "application_public") renderHomeItems()
    }
    private fun completeBrowserPaneSwipe(showTree: Boolean, releaseOffset: Float) {
        if (!isCompactPortrait() || !::browserArea.isInitialized) {
            browserPaneTransitionInProgress = false
            setTreeVisible(showTree)
            return
        }
        val generation = ++browserPaneTransitionGeneration
        browserPaneTransitionInProgress = true
        setTreeVisible(showTree)
        val width = browserArea.width.takeIf { it > 0 }
            ?: dp(resources.configuration.screenWidthDp.coerceAtLeast(1))
        val start = releaseOffset.coerceIn(-width.toFloat(), 0f)
        val target = if (showTree) 0f else -width.toFloat()
        treePanel.animate().cancel()
        fileScrollContainer.animate().cancel()
        treePanel.translationX = start
        fileScrollContainer.translationX = start
        treePanel.animate().translationX(target).setDuration(180).withEndAction {
            if (generation == browserPaneTransitionGeneration) {
                browserPaneTransitionInProgress = false
                updateBrowserPanes()
            }
        }.start()
        fileScrollContainer.animate().translationX(target).setDuration(180).start()
    }
    private fun isCompactPortrait(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
            resources.configuration.screenWidthDp < 600

    private fun isBrowserPaneSwipe(dx: Float, dy: Float): Boolean =
        isCompactPortrait() && !selectionMode && activeBrowserDragPayload == null && !browserEntryHoldArmed &&
            abs(dx) >= dp(48) && abs(dx) > abs(dy) &&
            ((treeVisible && dx < 0f) || (!treeVisible && dx > 0f))

    private fun updateBrowserPanes() {
        if (::paneSwitchButton.isInitialized) {
            paneSwitchButton.text = if (treeVisible) "Files" else "Tree"
            paneSwitchButton.contentDescription = if (treeVisible) "Show file view" else "Show tree view"
        }
        if (!::treePanel.isInitialized || !::fileScroll.isInitialized || !::fileScrollContainer.isInitialized) return
        val pagerMode = isCompactPortrait()
        treePanel.visibility = View.VISIBLE
        fileScrollContainer.visibility = View.VISIBLE
        val pageWidth = browserArea.width.takeIf { it > 0 }
            ?: dp(resources.configuration.screenWidthDp.coerceAtLeast(1))
        if (pagerMode) {
            (treePanel.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = pageWidth
                params.weight = 0f
                treePanel.layoutParams = params
            }
            (fileScrollContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = pageWidth
                params.weight = 0f
                fileScrollContainer.layoutParams = params
            }
            if (!browserPanePagerGestureInProgress && !browserPaneTransitionInProgress) {
                val pageOffset = if (treeVisible) 0f else -pageWidth.toFloat()
                treePanel.translationX = pageOffset
                fileScrollContainer.translationX = pageOffset
            }
        } else {
            (treePanel.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = if (treeVisible) requiredTreePanelWidthPx() else 0
                params.weight = 0f
                treePanel.layoutParams = params
            }
            (fileScrollContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = 0
                params.weight = 1f
                fileScrollContainer.layoutParams = params
            }
            treePanel.translationX = 0f
            fileScrollContainer.translationX = 0f
        }
        browserArea.requestLayout()
    }

    private fun requiredTreePanelWidthPx(): Int {
        val availableWidth = browserArea.width.takeIf { it > 0 }
            ?: dp(resources.configuration.screenWidthDp.coerceAtLeast(180))
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        }
        var required = dp(180)
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        }
        required = maxOf(required, headerPaint.measureText("VIRTUAL FILE SYSTEM").toInt() + dp(24))
        if (::treeContent.isInitialized) {
            for (index in 0 until treeContent.childCount) {
                val row = treeContent.getChildAt(index)
                val key = row.tag as? String ?: continue
                val node = findTreeNodeByKey(key) ?: continue
                val textAndIndent = dp(node.depth * 24 + 48 + 32 + 16) + titlePaint.measureText(node.name).toInt()
                required = maxOf(required, textAndIndent)
            }
        }
        return required.coerceAtMost(availableWidth.coerceAtLeast(dp(180)))
    }
    private fun startEntryDrag(handle: View, icon: View, entry: ResourceEntry, createLinks: Boolean = false): Boolean {
        val selectedEntries = if (createLinks) emptyList() else entries.filter { it.uri in selectedUris }
        val draggedEntries = selectedEntries.ifEmpty { listOf(entry) }
        if (draggedEntries.isEmpty()) return false
        val canMove = !createLinks && draggedEntries.all {
            StorageActionPolicy.action(StorageAction.MOVE, it.metadata, null).mayAttempt
        }
        val payload = BrowserDragPayload(
            entries = draggedEntries,
            canMove = canMove,
            sourceDirectory = if (homeMode) ContainerLinksProvider.ROOT else current,
            sourceScrollY = fileScroll.scrollY,
            sourceDisplayName = historyNames.lastOrNull(),
            createLinks = createLinks
        )
        val clip = ClipData.newPlainText("InfiniFolder items", draggedEntries.joinToString(", ") { it.metadata.name })
        val snapshot = captureDragIcon(icon, draggedEntries.size)
        showActiveDragOverlay(icon, snapshot)
        (draggedEntries.mapNotNull { entryIconViews[it.uri] } + icon).distinct().forEach { sourceIcon ->
            activeDragSourceIconAlphas[sourceIcon] = sourceIcon.alpha
            sourceIcon.alpha = 0.35f
        }
        activeDragLocationEvents = 0
        activeDragDropHandled = false
        pendingDragSelfDropMenuEntries = null
        com.infinifolder.storage.DiagnosticLog.record(
            "browser.drag.request", "items=${draggedEntries.size} handle=${handle.javaClass.simpleName} icon=${icon.width}x${icon.height}"
        )
        activeBrowserDragPayload = payload
        val started = handle.startDragAndDrop(clip, SnapshotDragShadowBuilder(snapshot), payload, 0)
        com.infinifolder.storage.DiagnosticLog.record(
            "browser.drag.started", "accepted=$started items=${draggedEntries.size} overlay=${activeDragOverlay != null}"
        )
        if (!started) clearActiveDragOverlay()
        if (started) {
            val action = if (canMove) "Move or copy" else "Copy"
            Toast.makeText(this, if (createLinks) "Drop in the file area to create a link" else "Drag to a folder to $action", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Android could not start dragging this item", Toast.LENGTH_SHORT).show()
        }
        return started
    }

    private fun captureDragIcon(icon: View, itemCount: Int): Bitmap {
        val front = Bitmap.createBitmap(
            icon.width.coerceAtLeast(1), icon.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        ).also { bitmap -> icon.draw(Canvas(bitmap)) }
        if (itemCount <= 1) return front

        val pad = dp(7)
        val layerCount = (itemCount - 1).coerceAtMost(3)
        val layerStep = dp(3)
        val bitmap = Bitmap.createBitmap(
            front.width + pad * 2 + layerCount * layerStep,
            front.height + pad * 2 + layerCount * layerStep,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (dark) 0xFF45534A.toInt() else 0xFFE7EFE4.toInt()
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = if (dark) 0xFFD2E3D3.toInt() else 0xFF637565.toInt()
        }
        val radius = dp(8).toFloat()
        for (layer in layerCount downTo 1) {
            val left = (pad + layer * layerStep).toFloat()
            val top = (pad + layer * layerStep).toFloat()
            val rect = RectF(left, top, left + front.width, top + front.height)
            canvas.drawRoundRect(rect, radius, radius, cardPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
        canvas.drawBitmap(front, pad.toFloat(), pad.toFloat(), null)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (dark) 0xFF2E7D32.toInt() else 0xFF388E3C.toInt()
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = dp(12).toFloat()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val label = itemCount.toString()
        val badgeWidth = maxOf(dp(20), textPaint.measureText(label).toInt() + dp(12))
        val badgeHeight = dp(20)
        val badgeLeft = pad + front.width - badgeWidth + dp(4)
        val badgeTop = pad - dp(4)
        val badgeRect = RectF(
            badgeLeft.toFloat(), badgeTop.toFloat(),
            (badgeLeft + badgeWidth).toFloat(), (badgeTop + badgeHeight).toFloat()
        )
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgePaint)
        val textBaseline = badgeRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, badgeRect.centerX(), textBaseline, textPaint)
        front.recycle()
        return bitmap
    }

    private fun showActiveDragOverlay(icon: View, snapshot: Bitmap) {
        clearActiveDragOverlay()
        val width = snapshot.width
        val height = snapshot.height
        val drawable = BitmapDrawable(resources, snapshot)
        val host = window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ViewGroup
            ?: return
        val iconLocation = IntArray(2)
        val hostLocation = IntArray(2)
        icon.getLocationOnScreen(iconLocation)
        host.getLocationOnScreen(hostLocation)
        val left = iconLocation[0] - hostLocation[0] - (width - icon.width) / 2
        val top = iconLocation[1] - hostLocation[1] - (height - icon.height) / 2
        drawable.setBounds(left, top, left + width, top + height)
        host.overlay.add(drawable)
        host.invalidate()
        activeDragOverlayHost = host
        activeDragOverlay = drawable
    }

    private fun moveActiveDragOverlay(host: View, x: Float, y: Float) {
        val drawable = activeDragOverlay ?: return
        val overlayHost = activeDragOverlayHost ?: return
        val sourceLocation = IntArray(2)
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(sourceLocation)
        overlayHost.getLocationOnScreen(hostLocation)
        val hostX = sourceLocation[0] - hostLocation[0] + x
        val hostY = sourceLocation[1] - hostLocation[1] + y
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(
            (hostX - width / 2f).toInt(), (hostY - height / 2f).toInt(),
            (hostX + width / 2f).toInt(), (hostY + height / 2f).toInt()
        )
        overlayHost.postInvalidateOnAnimation()
    }

    private fun moveActiveDragOverlayFromEvent(view: View, event: android.view.DragEvent) {
        if (event.action == android.view.DragEvent.ACTION_DRAG_LOCATION) {
            activeDragLocationEvents++
            lastDragScreenPosition = dragEventScreenPosition(view, event)
            if (activeDragLocationEvents == 1 || activeDragLocationEvents % 50 == 0) {
                com.infinifolder.storage.DiagnosticLog.record(
                    "browser.drag.location", "count=$activeDragLocationEvents target=${view.javaClass.simpleName} x=${event.x.toInt()} y=${event.y.toInt()} overlay=${activeDragOverlay != null}"
                )
            }
            moveActiveDragOverlay(view, event.x, event.y)
            updateDragEdgeAutoScroll()
            updateDragTreeRevealHover(view, event)
            updateDragTreeNodeHover(view, event)
            updateDragContainerHover()
        }
    }

    private fun updateDragTreeNodeHover(view: View, event: android.view.DragEvent) {
        if (activeBrowserDragPayload == null || !treeVisible) return
        val (screenX, screenY) = dragEventScreenPosition(view, event)
        val hit = treeTargetAtScreen(screenX, screenY)
        setDragHoveredTreeTarget(hit)
        if (hit != null) updateTreeNodeExpandHover(hit.first, hit.second)
        else cancelTreeNodeExpandHover()
    }

    private fun dragEventScreenPosition(view: View, event: android.view.DragEvent): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[0] + event.x to location[1] + event.y
    }

    private fun dragEdgeScrollTarget(): Pair<ScrollView, Float>? {
        if (activeBrowserDragPayload == null || activeDragDropHandled) return null
        val (screenX, screenY) = lastDragScreenPosition ?: return null
        // In portrait the offscreen pane remains VISIBLE, so check the actual
        // viewport and pane selection rather than relying on isShown alone.
        val tree = treeVisible && isInsideTreeViewport(screenX, screenY)
        val scroller: ScrollView = if (tree) treeScrollView else {
            if ((isCompactPortrait() && treeVisible) || !fileScroll.isShown) return null
            fileScroll
        }
        if (scroller.width <= 0 || scroller.height <= 0) return null
        val location = IntArray(2)
        scroller.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + scroller.width
        val bottom = top + scroller.height
        if (screenX < left || screenX >= right) return null

        val edge = if (tree) scroller.height / 6f else dp(64).toFloat()
        val topProgress = ((top + edge - screenY) / edge).coerceIn(0f, 1f)
        val bottomProgress = ((screenY - (bottom - edge)) / edge).coerceIn(0f, 1f)
        val speed = when {
            topProgress > 0f -> -(120f + 840f * topProgress)
            bottomProgress > 0f -> 120f + 840f * bottomProgress
            else -> return null
        }
        return scroller to speed * resources.displayMetrics.density
    }

    private fun updateDragEdgeAutoScroll() {
        if (dragEdgeScrollTarget() == null) {
            cancelDragEdgeAutoScroll()
            return
        }
        if (dragAutoScrollRunnable != null) return
        dragAutoScrollLastFrameMs = android.os.SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val target = dragEdgeScrollTarget()
                if (target == null) {
                    cancelDragEdgeAutoScroll()
                    return
                }
                val (scroller, speed) = target
                if (dragAutoScrollTarget !== scroller) {
                    dragAutoScrollTarget = scroller
                    com.infinifolder.storage.DiagnosticLog.record(
                        "browser.drag.autoscroll", "pane=${if (scroller === treeScrollView) "tree" else "files"} direction=${if (speed < 0f) "up" else "down"}"
                    )
                }
                val now = android.os.SystemClock.uptimeMillis()
                val elapsed = (now - dragAutoScrollLastFrameMs).coerceIn(1L, 32L)
                dragAutoScrollLastFrameMs = now
                val before = scroller.scrollY
                scroller.scrollBy(0, (speed * elapsed / 1000f).roundToInt())
                if (scroller.scrollY != before) {
                    // Rows/cards move under a stationary pointer. Recompute both
                    // the highlighted drop target and linger timers after scrolling.
                    val position = lastDragScreenPosition
                    val hit = position?.takeIf { treeVisible }
                        ?.let { treeTargetAtScreen(it.first, it.second) }
                    setDragHoveredTreeTarget(hit)
                    if (hit != null) updateTreeNodeExpandHover(hit.first, hit.second)
                    else cancelTreeNodeExpandHover()
                    updateDragContainerHover()
                }
                root.postOnAnimation(this)
            }
        }
        dragAutoScrollRunnable = tick
        root.postOnAnimation(tick)
    }

    private fun cancelDragEdgeAutoScroll() {
        dragAutoScrollRunnable?.let(root::removeCallbacks)
        dragAutoScrollRunnable = null
        dragAutoScrollTarget = null
    }

    private fun updateDragTreeRevealHover(view: View, event: android.view.DragEvent) {
        if (activeBrowserDragPayload == null || treeVisible ||
            !fileScrollContainer.isShown || !fileScroll.isShown
        ) {
            cancelDragTreeReveal()
            return
        }
        val (screenX, screenY) = dragEventScreenPosition(view, event)
        val location = IntArray(2)
        fileScroll.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val inLeftEdge = screenX >= left && screenX <= left + dp(40) &&
            screenY >= top && screenY <= top + fileScroll.height
        if (!inLeftEdge) {
            cancelDragTreeReveal()
            return
        }
        if (dragTreeRevealRunnable != null) return
        val reveal = Runnable {
            dragTreeRevealRunnable = null
            if (activeBrowserDragPayload != null && !homeMode && !treeVisible) {
                setTreeVisible(true)
                com.infinifolder.storage.DiagnosticLog.record("browser.drag.tree-revealed", "hovered at left edge")
            }
        }
        dragTreeRevealRunnable = reveal
        root.postDelayed(reveal, 650)
    }

    private fun cancelDragTreeReveal() {
        dragTreeRevealRunnable?.let(root::removeCallbacks)
        dragTreeRevealRunnable = null
    }

    private fun clearActiveDragOverlay() {
        cancelDragEdgeAutoScroll()
        cancelDragContainerHover()
        cancelDragTreeReveal()
        cancelTreeNodeExpandHover()
        setDragHoveredTreeTarget(null)
        activeDragSourceIconAlphas.forEach { (icon, alpha) -> icon.alpha = alpha }
        activeDragSourceIconAlphas.clear()
        activeDragDropHandled = false
        pendingDragSelfDropMenuEntries = null
        dragHoveredTreeNodeKey = null
        lastDragScreenPosition = null
        val host = activeDragOverlayHost
        val drawable = activeDragOverlay
        if (host != null && drawable != null) {
            host.overlay.remove(drawable)
            host.invalidate()
        }
        activeDragOverlayHost = null
        activeDragOverlay = null
        activeBrowserDragPayload = null
    }

    private fun treeContainsUri(root: TreeNode, uri: Uri): Boolean =
        root.uri == uri || root.children.any { treeContainsUri(it, uri) }

    private fun wouldDropIntoDraggedFolder(entries: List<ResourceEntry>, destination: Uri): Boolean {
        fun findNode(nodes: List<TreeNode>, uri: Uri): TreeNode? {
            nodes.forEach { node ->
                if (node.uri == uri) return node
                findNode(node.children, uri)?.let { return it }
            }
            return null
        }
        return entries.any { source ->
            source.uri == destination || (source.metadata.isDirectory &&
                findNode(treeRoots, source.uri)?.let { treeContainsUri(it, destination) } == true)
        }
    }

    private fun setTreeDropTarget(row: View, selected: Boolean, active: Boolean) {
        row.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            val color = when {
                active -> if (dark) 0xFF405B3A.toInt() else 0xFFCBE8C2.toInt()
                selected -> if (dark) 0xFF3A4935.toInt() else 0xFFD9E8D1.toInt()
                else -> if (dark) 0x0020241F else 0x00EAE9E3
            }
            setColor(color)
            if (active) setStroke(dp(2), if (dark) 0xFF9CCC65.toInt() else 0xFF388E3C.toInt())
        }
    }

    private fun handleTreeDrop(node: TreeNode, payload: BrowserDragPayload) {
        val destination = node.uri ?: return
        if (node.needsAccessBadge || isCreateDirectoryNode(node) || AppsUri.isAppsUri(destination) || AppsUri.isCategoryUri(destination)) return
        performBrowserDrop(payload, destination, node.name)
    }

    private fun sameResourceUri(first: Uri, second: Uri): Boolean =
        runCatching { sourceIdentity(first) == sourceIdentity(second) }.getOrDefault(first == second)

    private fun queueDragSelfDropMenu(payload: BrowserDragPayload, selfEntry: ResourceEntry): Boolean {
        if (activeDragDropHandled) return true
        activeDragDropHandled = true
        pendingDragSelfDropMenuEntries = if (payload.entries.size > 1) payload.entries else listOf(selfEntry)
        com.infinifolder.storage.DiagnosticLog.record(
            "browser.drag.self-drop", "item=${selfEntry.metadata.name} items=${payload.entries.size}"
        )
        return true
    }

    private fun dispatchTreeDropAtScreen(view: View, event: android.view.DragEvent): Boolean {
        val payload = event.localState as? BrowserDragPayload ?: return false
        if (activeDragDropHandled || !treeVisible) return activeDragDropHandled
        val (screenX, screenY) = dragEventScreenPosition(view, event)
        val hit = resolveTreeDropTarget(screenX, screenY, payload)
        if (hit == null) {
            com.infinifolder.storage.DiagnosticLog.record(
                "browser.drag.tree-drop-rejected", "reason=no-row x=${screenX.toInt()} y=${screenY.toInt()}"
            )
            return false
        }
        return dispatchTreeDrop(hit.second, payload)
    }

    private fun dispatchTreeDrop(node: TreeNode, payload: BrowserDragPayload): Boolean {
        if (activeDragDropHandled) return true
        if (!canDropOnTreeNode(node, payload)) {
            com.infinifolder.storage.DiagnosticLog.record(
                "browser.drag.tree-drop-rejected", "reason=unsupported-node key=${node.key} name=${node.name}"
            )
            return false
        }
        val destination = node.uri ?: run {
            com.infinifolder.storage.DiagnosticLog.record(
                "browser.drag.tree-drop-rejected", "reason=missing-uri key=${node.key}"
            )
            return false
        }
        payload.entries.firstOrNull { sameResourceUri(it.uri, destination) }?.let {
            return queueDragSelfDropMenu(payload, it)
        }
        activeDragDropHandled = true
        com.infinifolder.storage.DiagnosticLog.record(
            "browser.drag.tree-drop", "node=${node.key} name=${node.name} items=${payload.entries.size}"
        )
        handleTreeDrop(node, payload)
        return true
    }

    private fun canDropOnTreeNode(node: TreeNode, payload: BrowserDragPayload?): Boolean =
        payload != null && !payload.createLinks && node.uri != null && !node.needsAccessBadge &&
            !isCreateDirectoryNode(node) && !AppsUri.isAppsUri(node.uri) && !AppsUri.isCategoryUri(node.uri)

    private fun findTreeNodeByKey(key: String): TreeNode? {
        fun find(nodes: List<TreeNode>): TreeNode? {
            nodes.forEach { node ->
                if (node.key == key) return node
                find(node.children)?.let { return it }
            }
            return null
        }
        return find(treeRoots)
    }

    private fun treeTargetAtScreen(screenX: Float, screenY: Float): Pair<View, TreeNode>? {
        val scroller = treeContent.parent as? View ?: return null
        val scrollLocation = IntArray(2)
        scroller.getLocationOnScreen(scrollLocation)
        if (screenX < scrollLocation[0] || screenX >= scrollLocation[0] + scroller.width ||
            screenY < scrollLocation[1] || screenY >= scrollLocation[1] + scroller.height
        ) return null
        for (index in treeContent.childCount - 1 downTo 0) {
            val row = treeContent.getChildAt(index)
            if (row.visibility != View.VISIBLE) continue
            val location = IntArray(2)
            row.getLocationOnScreen(location)
            if (screenX >= location[0] && screenX < location[0] + row.width &&
                screenY >= location[1] && screenY < location[1] + row.height
            ) {
                val key = row.tag as? String ?: return null
                val node = findTreeNodeByKey(key) ?: return null
                return row to node
            }
        }
        return null
    }

    private fun isInsideTreeViewport(screenX: Float, screenY: Float): Boolean {
        if (!::treeScrollView.isInitialized || !treeScrollView.isShown) return false
        val location = IntArray(2)
        treeScrollView.getLocationOnScreen(location)
        return screenX >= location[0] && screenX < location[0] + treeScrollView.width &&
            screenY >= location[1] && screenY < location[1] + treeScrollView.height
    }

    private fun resolveTreeDropTarget(
        screenX: Float,
        screenY: Float,
        payload: BrowserDragPayload
    ): Pair<View, TreeNode>? {
        val exactHit = treeTargetAtScreen(screenX, screenY)
        if (exactHit != null) return exactHit.takeIf { canDropOnTreeNode(it.second, payload) }

        // Some Android targets report ACTION_DROP in a different view's coordinate
        // space, including a point that still lands inside the viewport but misses the
        // row. Use the last row actually hovered; blank-space movement clears this key.
        return lastHoveredTreeTarget(payload)
    }

    private fun lastHoveredTreeTarget(payload: BrowserDragPayload): Pair<View, TreeNode>? {
        val lastPosition = lastDragScreenPosition ?: return null
        if (!isInsideTreeViewport(lastPosition.first, lastPosition.second)) return null
        treeTargetAtScreen(lastPosition.first, lastPosition.second)?.let { hit ->
            return hit.takeIf { canDropOnTreeNode(it.second, payload) }
        }
        val key = dragHoveredTreeNodeKey ?: return null
        val row = (0 until treeContent.childCount)
            .map { treeContent.getChildAt(it) }
            .firstOrNull { it.isAttachedToWindow && it.tag == key } ?: return null
        val node = findTreeNodeByKey(key) ?: return null
        return (row to node).takeIf { canDropOnTreeNode(node, payload) }
    }

    private fun setDragHoveredTreeTarget(hit: Pair<View, TreeNode>?) {
        val row = hit?.first
        val node = hit?.second
        val payload = activeBrowserDragPayload
        if (dragHoveredTreeRow !== row) {
            dragHoveredTreeRow?.let { setTreeDropTarget(it, dragHoveredTreeRowSelected, false) }
            dragHoveredTreeRow = null
        }
        if (row != null && node != null && canDropOnTreeNode(node, payload)) {
            dragHoveredTreeRow = row
            dragHoveredTreeRowSelected = isTreeNodeSelected(node)
            dragHoveredTreeNodeKey = node.key
            setTreeDropTarget(row, dragHoveredTreeRowSelected, true)
        } else {
            dragHoveredTreeRow = null
            dragHoveredTreeNodeKey = null
        }
    }

    private fun updateTreeNodeExpandHover(row: View, node: TreeNode) {
        if (activeBrowserDragPayload == null || !node.canExpand || node.expanded ||
            node.loading || node.needsAccessBadge
        ) {
            cancelTreeNodeExpandHover()
            return
        }
        if (dragTreeExpandKey == node.key && dragTreeExpandRunnable != null) return
        cancelTreeNodeExpandHover()
        dragTreeExpandKey = node.key
        val expand = Runnable {
            dragTreeExpandRunnable = null
            dragTreeExpandKey = null
            if (activeBrowserDragPayload != null && !node.expanded && row.isAttachedToWindow) {
                com.infinifolder.storage.DiagnosticLog.record("browser.drag.tree-expand", "node=${node.key}")
                toggleTreeNode(node)
            }
        }
        dragTreeExpandRunnable = expand
        root.postDelayed(expand, 650)
    }

    private fun cancelTreeNodeExpandHover() {
        dragTreeExpandRunnable?.let(root::removeCallbacks)
        dragTreeExpandRunnable = null
        dragTreeExpandKey = null
    }

    private fun handleTreePanelDrag(target: View, event: android.view.DragEvent): Boolean {
        val payload = event.localState as? BrowserDragPayload
        if (payload?.createLinks == true) return false
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                val accepted = payload != null
                com.infinifolder.storage.DiagnosticLog.record(
                    "browser.drag.tree-target-started", "target=${target.javaClass.simpleName} accepted=$accepted items=${payload?.entries?.size ?: 0}"
                )
                return accepted
            }
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                moveActiveDragOverlayFromEvent(target, event)
                val (screenX, screenY) = dragEventScreenPosition(target, event)
                val hit = treeTargetAtScreen(screenX, screenY)
                setDragHoveredTreeTarget(hit)
                if (hit != null) updateTreeNodeExpandHover(hit.first, hit.second)
                else cancelTreeNodeExpandHover()
                return true
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                setDragHoveredTreeTarget(null)
                cancelTreeNodeExpandHover()
                return true
            }
            android.view.DragEvent.ACTION_DROP -> {
                val (screenX, screenY) = dragEventScreenPosition(target, event)
                val hit = payload?.let { resolveTreeDropTarget(screenX, screenY, it) }
                if (hit == null) {
                    com.infinifolder.storage.DiagnosticLog.record(
                        "browser.drag.tree-drop-rejected", "reason=no-row x=${screenX.toInt()} y=${screenY.toInt()} target=${target.javaClass.simpleName}"
                    )
                    // Keep the last valid hover alive for the root listener, which may
                    // receive the same drop with coordinates relative to a different view.
                    return false
                }
                setDragHoveredTreeTarget(null)
                cancelTreeNodeExpandHover()
                return dispatchTreeDrop(hit.second, payload ?: return false)
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                setDragHoveredTreeTarget(null)
                cancelTreeNodeExpandHover()
                return true
            }
            else -> return false
        }
    }

    private fun dispatchBreadcrumbLinkDrop(view: View, event: android.view.DragEvent): Boolean {
        val payload = event.localState as? BrowserDragPayload ?: return false
        if (!payload.createLinks) return false
        if (activeDragDropHandled) return true
        val (x, y) = dragEventScreenPosition(view, event)
        val bounds = android.graphics.Rect()
        if (!fileScrollContainer.getGlobalVisibleRect(bounds) || !bounds.contains(x.toInt(), y.toInt())) return false
        val destination = if (homeMode && homePage == "root") ContainerLinksProvider.ROOT
            else current?.takeUnless { homeMode } ?: return false
        activeDragDropHandled = true
        startBatchTransfer(payload.entries, false, destination, createLinks = true,
            refreshLocationAfterTransfer = destination, restoreScrollYAfterTransfer = fileScroll.scrollY,
            refreshLocationDisplayName = historyNames.lastOrNull())
        com.infinifolder.storage.DiagnosticLog.record("browser.drag.breadcrumb-link", "source=${payload.entries.firstOrNull()?.uri} destination=$destination")
        return true
    }

    private fun installBreadcrumbDragSource(view: View, destination: Uri, name: String) {
        val entry = ResourceEntry(resourceRef(destination), ResourceMetadata(name,
            "vnd.android.document/directory", null, null, true), destination)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        view.isLongClickable = false
        view.setOnLongClickListener(null)
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var armed = false
            private var moved = false
            private var dragging = false
            private val arm = Runnable {
                if (!moved && !dragging) {
                    armed = true
                    view.isPressed = true
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            private fun release() {
                view.removeCallbacks(arm)
                view.isPressed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            override fun onTouch(target: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        armed = false; moved = false; dragging = false
                        view.postDelayed(arm, timeout)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (dx * dx + dy * dy > slop * slop) {
                            moved = true
                            view.removeCallbacks(arm)
                            if (armed && !dragging) {
                                dragging = true
                                startEntryDrag(view, view, entry, createLinks = true)
                                release()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        release()
                        if (!moved && !dragging) {
                            if (armed) {
                                if (!AppsUri.isAppsUri(destination)) showContainerPinMenu(entry, destination)
                            } else view.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> { release(); moved = true }
                }
                return true
            }
        })
    }

    private fun performBrowserDrop(payload: BrowserDragPayload, destination: Uri, targetName: String) {
        if (wouldDropIntoDraggedFolder(payload.entries, destination)) {
            Toast.makeText(this, "A folder cannot be dropped into itself or one of its subfolders", Toast.LENGTH_LONG).show()
            return
        }
        io.execute {
            try {
                val destinationRef = resourceRef(destination)
                val alreadyHere = payload.entries.isNotEmpty() && payload.entries.all {
                    (provider.parent(it.ref) as? ProviderResult.Success)?.value == destinationRef
                }
                if (alreadyHere) {
                    com.infinifolder.storage.DiagnosticLog.record("browser.drag.no-op", "reason=already-in-destination items=${payload.entries.size}")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) Toast.makeText(this, "Already in this folder", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val move = payload.canMove && StorageActionPolicy.preferMoveForDrop(provider, payload.entries, destinationRef)
                val assessment = StorageActionPolicy.destination(provider, destinationRef, payload.entries, move)
                when (assessment) {
                    is ProviderResult.Success -> assessment.value.decision.requireAllowed()
                    is ProviderResult.Failure -> throw java.io.IOException(assessment.message, assessment.cause)
                    is ProviderResult.Unsupported -> throw java.io.IOException("Cannot check destination: ${assessment.capability}")
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    startBatchTransfer(payload.entries, move, destination,
                        refreshLocationAfterTransfer = payload.sourceDirectory,
                        restoreScrollYAfterTransfer = payload.sourceScrollY,
                        refreshLocationDisplayName = payload.sourceDisplayName)
                    Toast.makeText(this, if (move) "Moving to $targetName" else "Copying to $targetName", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, error.message ?: "Cannot drop here", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun shortcutContainerDestination(entry: ResourceEntry): Uri? {
        if (!ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) return null
        val shortcut = shortcutCache.get(entry.uri.toString()) ?: return null
        if (!shortcut.targetIsDirectory && !ArchiveFormats.isArchive(Uri.parse(shortcut.targetUri).lastPathSegment.orEmpty())) return null
        val target = containerUriHint(Uri.parse(shortcut.targetUri))
        return provider.refForUri(target)?.let { Uri.parse(it.documentId) }
    }
    private fun entryDropDestination(entry: ResourceEntry): Uri? {
        shortcutContainerDestination(entry)?.let { return it }
        if (AppsUri.isAppsUri(entry.uri) || AppsUri.isCategoryUri(entry.uri) || entry.metadata.isSymbolicLink) return null
        return when {
            entry.metadata.isDirectory -> entry.uri
            !entry.metadata.isDirectory && ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType) ->
                ArchiveUri.toArchiveUri(entry.uri)
            else -> null
        }
    }

    private fun entryBrowseDestination(entry: ResourceEntry): Uri? {
        shortcutContainerDestination(entry)?.let { return it }
        if (entry.metadata.isSymbolicLink || AppsUri.isAppsUri(entry.uri) || AppsUri.isCategoryUri(entry.uri)) return null
        return when {
            entry.metadata.isDirectory -> entry.uri
            ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType) -> ArchiveUri.toArchiveUri(entry.uri)
            else -> null
        }
    }

    private fun isContainerPinned(destination: Uri): Boolean =
        pinnedContainers.any { sameResourceUri(it.destination, destination) }

    private fun pinEntryToHome(entry: ResourceEntry) {
        val manager = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (manager?.isRequestPinShortcutSupported != true) {
            Toast.makeText(this, "This launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Preparing home-screen shortcut…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val shortcut = HomeShortcuts.create(applicationContext, entry)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val accepted = manager.requestPinShortcut(shortcut, null)
                    Toast.makeText(this, if (accepted) "Shortcut request sent to launcher" else "Could not add shortcut", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, error.message ?: "Cannot create shortcut", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun pinContainer(entry: ResourceEntry, destination: Uri) {
        if (!isContainerPinned(destination)) {
            pinnedContainers += PinnedContainer(entry, destination)
            renderPinnedContainers()
        }
    }

    private fun unpinContainer(destination: Uri) {
        pinnedContainers.removeAll { sameResourceUri(it.destination, destination) }
        renderPinnedContainers()
    }

    private fun showContainerPinMenu(entry: ResourceEntry, destination: Uri) {
        val pinned = isContainerPinned(destination)
        showGroupedMenuDialog(entry.metadata.name,
            groups = listOf(listOf("Open container", if (pinned) "Unpin" else "Pin to top"))) { action ->
            when (action) {
                "Open container" -> navigate(destination, displayName = entry.metadata.name)
                "Unpin" -> unpinContainer(destination)
                "Pin to top" -> pinContainer(entry, destination)
            }
        }
    }

    private fun renderPinnedContainers() {
        if (!::pinnedContainerContent.isInitialized) return
        cancelDragContainerHover()
        for (index in 0 until pinnedContainerContent.childCount)
            dragNavigationTargets.remove(pinnedContainerContent.getChildAt(index))
        pinnedContainerContent.removeAllViews()
        renderedPinnedIconSize = iconSizeDp
        pinnedContainerBar.visibility = if (pinnedContainers.isEmpty()) View.GONE else View.VISIBLE
        val side = dp(iconSizeDp / 2)
        val normalSide = dp(44)
        for (pin in pinnedContainers) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                contentDescription = "Pinned container: ${pin.entry.metadata.name}"
                tooltipText = pin.entry.metadata.name
            }
            val iconHolder = FrameLayout(this)
            val isPinnedArchive = !AppsUri.isAppUri(pin.entry.uri) &&
                ArchiveFormats.isDefaultBrowsableArchive(pin.entry.metadata.name, pin.entry.metadata.mimeType)
            val rawPinIcon = when {
                pin.entry.metadata.isDirectory -> directoryPreview(pin.entry, false, card, probeImmediately = true)
                isPinnedArchive -> directoryPreview(
                    pin.entry,
                    false,
                    card,
                    fallbackView = buildFileIconView(pin.entry, pin.entry.metadata, false, card),
                    probeImmediately = true
                )
                else -> buildFileIconView(pin.entry, pin.entry.metadata, false, card)
            }
            val icon = rawPinIcon.apply {
                pivotX = 0f; pivotY = 0f
                scaleX = side.toFloat() / normalSide
                scaleY = side.toFloat() / normalSide
                // The whole pin remains one tap/drop target, including its text.
                isClickable = false; isLongClickable = false
            }
            iconHolder.addView(icon, FrameLayout.LayoutParams(normalSide, normalSide))
            card.addView(iconHolder, LinearLayout.LayoutParams(side, side))
            card.addView(TextView(this).apply {
                text = pin.entry.metadata.name
                textSize = 10f
                setTextColor(setTextColorForTheme())
                maxWidth = dp(96)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2))
            card.setOnClickListener { navigate(pin.destination, displayName = pin.entry.metadata.name) }
            card.setOnLongClickListener {
                showContainerPinMenu(pin.entry, pin.destination)
                true
            }
            installBreadcrumbDropTarget(card, pin.destination, pin.entry.metadata.name,
                sourceUri = pin.entry.uri, kind = "pin")
            pinnedContainerContent.addView(card, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun dragCardAtScreen(x: Float, y: Float): Pair<View, ResourceEntry>? {
        fun contains(view: View): Boolean {
            if (!view.isShown || !view.isAttachedToWindow) return false
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            return x >= position[0] && x < position[0] + view.width && y >= position[1] && y < position[1] + view.height
        }
        if (!contains(fileScroll)) return null
        return dragContainerCards.entries.firstOrNull { contains(it.key) }?.let { it.key to it.value }
    }

    private fun dragNavigationAtScreen(x: Float, y: Float): Pair<View, DragNavigationTarget>? {
        fun contains(view: View): Boolean {
            if (!view.isShown || !view.isAttachedToWindow) return false
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            return x >= position[0] && x < position[0] + view.width && y >= position[1] && y < position[1] + view.height
        }
        if (!contains(root)) return null
        return dragNavigationTargets.entries.firstOrNull { (view, _) ->
            if (!contains(view)) false else {
                // Horizontal strips clip their offscreen children; clipped targets
                // must not activate while the pointer is elsewhere in the window.
                var ancestor = view.parent
                var visible = true
                while (ancestor is View) {
                    if (ancestor is HorizontalScrollView && !contains(ancestor)) visible = false
                    ancestor = ancestor.parent
                }
                visible
            }
        }?.let { it.key to it.value }
    }

    private fun dragHoverTargetAtScreen(x: Float, y: Float): Pair<View, DragNavigationTarget>? {
        dragNavigationAtScreen(x, y)?.let { return it }
        val (view, entry) = dragCardAtScreen(x, y) ?: return null
        val destination = entryBrowseDestination(entry) ?: return null
        return view to DragNavigationTarget(destination, entry.metadata.name, entry.uri, "card") {
            navigate(destination, displayName = entry.metadata.name)
        }
    }

    private fun cancelDragContainerHover() {
        dragContainerOpenRunnable?.let(root::removeCallbacks)
        dragContainerOpenRunnable = null
        dragContainerOpenView = null
    }

    private fun updateDragContainerHover() {
        val payload = activeBrowserDragPayload
        val position = lastDragScreenPosition
        val hit = if (payload != null && !payload.createLinks && !activeDragDropHandled && position != null)
            dragHoverTargetAtScreen(position.first, position.second) else null
        val destination = hit?.second?.destination
        if (payload == null || hit == null || destination == null || current == destination ||
            payload.entries.any { sameResourceUri(it.uri, hit.second.sourceUri) } ||
            wouldDropIntoDraggedFolder(payload.entries, destination)) {
            cancelDragContainerHover()
            return
        }
        if (dragContainerOpenView === hit.first && dragContainerOpenRunnable != null) return
        cancelDragContainerHover()
        val location = current
        dragContainerOpenView = hit.first
        val openContainer = Runnable {
            val last = lastDragScreenPosition
            val stillOver = last?.let { dragHoverTargetAtScreen(it.first, it.second) }
            val valid = activeBrowserDragPayload === payload && !activeDragDropHandled &&
                current == location && stillOver?.first === hit.first
            cancelDragContainerHover()
            if (valid) {
                com.infinifolder.storage.DiagnosticLog.record("browser.drag.container-open", "kind=${hit.second.kind} name=${hit.second.name} items=${payload.entries.size}")
                hit.second.open()
            }
        }
        dragContainerOpenRunnable = openContainer
        root.postDelayed(openContainer, 700L)
    }

    private fun dispatchFilePaneDropAtScreen(view: View, event: android.view.DragEvent): Boolean {
        val payload = event.localState as? BrowserDragPayload ?: return false
        if (payload.createLinks) return dispatchBreadcrumbLinkDrop(view, event)
        if (activeDragDropHandled) return true
        if (isCompactPortrait() && treeVisible) return false
        val (x, y) = dragEventScreenPosition(view, event)
        val bounds = android.graphics.Rect()
        if (!fileScroll.getGlobalVisibleRect(bounds) || !bounds.contains(x.toInt(), y.toInt())) return false
        // Icon/navigation targets retain priority over the background destination.
        if (dragNavigationAtScreen(x, y) != null || dragCardAtScreen(x, y) != null) return false
        val destination = if (homeMode && homePage == "root") ContainerLinksProvider.ROOT
            else current?.takeUnless { homeMode } ?: return false
        cancelDragContainerHover()
        activeDragDropHandled = true
        com.infinifolder.storage.DiagnosticLog.record("browser.drag.background-drop",
            "items=${payload.entries.size} destinationProvider=${resourceRef(destination).providerId}")
        performBrowserDrop(payload, destination, historyNames.lastOrNull() ?: "current folder")
        return true
    }

    private fun dispatchEntryDropAtScreen(view: View, event: android.view.DragEvent): Boolean {
        val payload = event.localState as? BrowserDragPayload ?: return false
        if (activeDragDropHandled) return true
        val position = dragEventScreenPosition(view, event)
        val hit = dragCardAtScreen(position.first, position.second) ?: return false
        return dispatchEntryDrop(hit.second, payload)
    }

    private fun dispatchEntryDrop(entry: ResourceEntry, payload: BrowserDragPayload): Boolean {
        cancelDragContainerHover()
        if (activeDragDropHandled) return true
        payload.entries.firstOrNull { sameResourceUri(it.uri, entry.uri) }?.let { return queueDragSelfDropMenu(payload, it) }
        val destination = entryDropDestination(entry)
        activeDragDropHandled = true
        if (destination != null) performBrowserDrop(payload, destination, entry.metadata.name)
        else io.execute {
            try {
                val parentRef = when (val result = provider.parent(entry.ref)) {
                    is ProviderResult.Success -> result.value ?: throw java.io.IOException("This item has no parent folder to receive a drop")
                    is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw java.io.IOException("This provider cannot identify the item's parent folder")
                }
                val parent = when (val result = provider.stat(parentRef)) {
                    is ProviderResult.Success -> result.value
                    is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw java.io.IOException("This provider cannot open the item's parent folder")
                }
                com.infinifolder.storage.DiagnosticLog.record("browser.drag.file-parent-drop",
                    "items=${payload.entries.size} destinationProvider=${parent.ref.providerId}")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) performBrowserDrop(payload, parent.uri, parent.metadata.name)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) dialogBuilder().setTitle("Cannot drop here")
                        .setMessage(error.message ?: "Cannot determine the destination folder")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
        return true
    }

    private fun installEntryDropTarget(card: View, entry: ResourceEntry, asGrid: Boolean) {
        dragContainerCards[card] = entry
        card.setOnDragListener { view, event ->
            moveActiveDragOverlayFromEvent(view, event)
            val payload = event.localState as? BrowserDragPayload
            val enabled = payload != null && !payload.createLinks
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> enabled
                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                    if (enabled) setEntryDropHighlight(view, true, asGrid)
                    enabled
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    if (dragContainerOpenView === view) cancelDragContainerHover()
                    setEntryDropHighlight(view, false, asGrid)
                    enabled
                }
                android.view.DragEvent.ACTION_DROP -> {
                    cancelDragContainerHover()
                    setEntryDropHighlight(view, false, asGrid)
                    if (enabled && payload != null) {
                        dispatchEntryDrop(entry, payload)
                    }
                    enabled
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    setEntryDropHighlight(view, false, asGrid)
                    true
                }
                else -> enabled
            }
        }
    }

    private fun setEntryDropHighlight(view: View, active: Boolean, asGrid: Boolean) {
        view.background = if (!active && asGrid) null else GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (active) {
                if (dark) 0xFF405B3A.toInt() else 0xFFCBE8C2.toInt()
            } else if (dark) 0x0020241F else 0x00EAE9E3)
            if (active) setStroke(dp(2), if (dark) 0xFF9CCC65.toInt() else 0xFF388E3C.toInt())
        }
    }

    private fun dispatchNavigationDropAtScreen(view: View, event: android.view.DragEvent): Boolean {
        val payload = event.localState as? BrowserDragPayload ?: return false
        if (activeDragDropHandled) return true
        val position = dragEventScreenPosition(view, event)
        val target = dragNavigationAtScreen(position.first, position.second)?.second ?: return false
        return dispatchNavigationDrop(target, payload)
    }

    private fun dispatchNavigationDrop(target: DragNavigationTarget, payload: BrowserDragPayload): Boolean {
        cancelDragContainerHover()
        if (activeDragDropHandled) return true
        payload.entries.firstOrNull { sameResourceUri(it.uri, target.sourceUri) }?.let { return queueDragSelfDropMenu(payload, it) }
        activeDragDropHandled = true
        performBrowserDrop(payload, target.destination, target.name)
        return true
    }

    private fun installBreadcrumbDropTarget(view: View, destination: Uri, name: String,
        sourceUri: Uri = destination, kind: String = "breadcrumb",
        onNavigate: () -> Unit = { navigate(destination, displayName = name) }) {
        val target = DragNavigationTarget(destination, name, sourceUri, kind, onNavigate)
        dragNavigationTargets[view] = target
        if (kind == "breadcrumb") installBreadcrumbDragSource(view, destination, name)
        view.setOnDragListener { crumb, event ->
            moveActiveDragOverlayFromEvent(crumb, event)
            val payload = event.localState as? BrowserDragPayload
            val enabled = payload != null && !payload.createLinks
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> enabled
                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                    if (enabled) crumb.setBackgroundColor(if (dark) 0xFF405B3A.toInt() else 0xFFCBE8C2.toInt())
                    enabled
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    if (dragContainerOpenView === crumb) cancelDragContainerHover()
                    crumb.background = null
                    enabled
                }
                android.view.DragEvent.ACTION_DROP -> {
                    crumb.background = null
                    if (enabled && payload != null) dispatchNavigationDrop(target, payload)
                    enabled
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    crumb.background = null
                    true
                }
                else -> enabled
            }
        }
    }

    private fun installTreeDropTarget(row: View, node: TreeNode, selected: Boolean) {
        row.setOnDragListener { target, event ->
            moveActiveDragOverlayFromEvent(target, event)
            if (event.action == android.view.DragEvent.ACTION_DRAG_LOCATION) {
                updateTreeNodeExpandHover(row, node)
            } else if (event.action == android.view.DragEvent.ACTION_DRAG_EXITED ||
                event.action == android.view.DragEvent.ACTION_DRAG_ENDED
            ) {
                if (dragTreeExpandKey == node.key) cancelTreeNodeExpandHover()
            }
            val payload = event.localState as? BrowserDragPayload
            val enabled = canDropOnTreeNode(node, payload)
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> {
                    if (enabled) com.infinifolder.storage.DiagnosticLog.record(
                        "browser.drag.tree-row-started", "key=${node.key} name=${node.name} items=${payload?.entries?.size ?: 0}"
                    )
                    enabled
                }
                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                    if (enabled) setTreeDropTarget(row, selected, true)
                    enabled
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    setTreeDropTarget(row, selected, false)
                    enabled
                }
                android.view.DragEvent.ACTION_DROP -> {
                    setTreeDropTarget(row, selected, false)
                    if (enabled && payload != null) {
                        com.infinifolder.storage.DiagnosticLog.record(
                            "browser.drag.tree-row-drop", "key=${node.key} name=${node.name} items=${payload.entries.size}"
                        )
                        dispatchTreeDrop(node, payload)
                    } else enabled
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    setTreeDropTarget(row, selected, false)
                    true
                }
                else -> enabled
            }
        }
    }
    private fun rebuildTreeRoots() {
        val priorNodes = mutableMapOf<String, TreeNode>()
        fun collect(nodes: List<TreeNode>) { nodes.forEach { priorNodes[it.key] = it; collect(it.children) } }
        collect(treeRoots)
        val virtualRoot = priorNodes["virtual-root"]?.also { it.onOpen = { showHomeRoot() }; it.canExpand = true }
            ?: TreeNode("virtual-root", ContainerLinksProvider.ROOT, "Virtual root", "🏠", 0, { showHomeRoot() }, true, expanded = true)
        virtualRoot.children.clear()
        val seen = mutableSetOf<String>()
        homeItems.filter { it.treeParentKey == "internal_shared" }
            .mapNotNull { it.treeUri ?: it.treeKey?.let(::treeSourceUri) }
            .forEach { seen += sourceIdentity(it) }
        homeItems.withIndex().filter { it.value.showInHome && it.value.treeParentKey == null }
            .sortedBy { if (it.value.showInHome) 0 else 1 }.forEach { indexed ->
            val index = indexed.index
            val item = indexed.value
            val key = item.treeKey ?: "virtual_${item.title}_$index"
            val uri = when (key) {
                "internal_shared" -> localProvider.rootUri.takeIf { localProvider.hasAccess() }
                "application_public" -> null
                "installed_apps" -> AppsUri.ROOT_URI
                else -> item.treeUri ?: item.treeKey?.let(::treeSourceUri)
            }
            if (uri != null && !seen.add(sourceIdentity(uri)) && !key.startsWith("root_link_")) return@forEach
            val action = when {
                key == "installed_apps" -> ({ openInstalledApps() })
                uri != null && item.treeExpandable -> ({ openRoot(uri) })
                else -> item.action
            }
            val existing = priorNodes[key]?.takeIf { it.uri == uri && it.name == item.title && it.icon == item.icon }
            val node = existing ?: TreeNode(key, uri, item.title, item.icon, 1, action, item.treeExpandable)
            node.onOpen = action
            node.canExpand = item.treeExpandable
            node.onLongPress = item.longAction
            node.needsAccessBadge = item.needsAccessBadge && key != "installed_apps" && !key.startsWith("sftp_") && !key.startsWith("ftp_") && !key.startsWith("smb_")
            node.onRequestAccess = when {
                !node.needsAccessBadge -> null
                key == "internal_shared" -> item.action
                key == "application_public" -> item.action
                key.startsWith("sftp_") || key.startsWith("ftp_") || key.startsWith("smb_") -> item.action
                item.initialUri != null && !SftpUri.isSftpUri(item.initialUri) && !FtpUri.isFtpUri(item.initialUri) && !SmbUri.isSmbUri(item.initialUri) && !WebDavUri.owns(item.initialUri) -> ({ rootPicker(item.initialUri, key) })
                else -> item.action
            }
            if (key == "internal_shared") {
                node.children.clear()
                val childItems = if (localProvider.hasAccess()) internalSharedEntries
                    .filter { (it.metadata.isDirectory || ArchiveFormats.isArchive(it.metadata.name, it.metadata.mimeType)) &&
                        (showHidden || !it.metadata.isHidden) }.sortedBy { it.metadata.name.lowercase() }.map { entry ->
                        val container = !entry.metadata.isDirectory && ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType)
                        val uri = if (container) ArchiveUri.toArchiveUri(entry.uri) else entry.uri
                        HomeItem(if (container) "📦" else "📁", entry.metadata.name, if (container) "Container" else "Folder",
                            { openRoot(uri, rememberAuthorizedRoot = false) }, longAction = { showEntryMenu(entry) },
                            treeKey = "local_$uri", treeUri = uri, treeExpandable = true)
                    }
                    else homeItems.filter { it.treeParentKey == key }.distinctBy { it.treeUri?.let(::sourceIdentity) ?: it.treeKey }
                val childIdentities = mutableSetOf<String>()
                childItems.forEachIndexed { childIndex, childItem ->
                    val childKey = childItem.treeKey ?: "virtual_${childItem.title}_$childIndex"
                    val childUri = childItem.treeUri ?: childItem.treeKey?.let(::treeSourceUri)
                    if (childUri != null && !childIdentities.add(sourceIdentity(childUri))) return@forEachIndexed
                    val childAction = if (childUri != null && childItem.treeExpandable) ({ openRoot(childUri) }) else childItem.action
                    val childNode = priorNodes[childKey]?.takeIf {
                        it.uri == childUri && it.name == childItem.title && it.icon == childItem.icon
                    } ?: TreeNode(childKey, childUri, childItem.title, childItem.icon, 2, childAction, childItem.treeExpandable)
                    childNode.onOpen = childAction
                    childNode.canExpand = childItem.treeExpandable
                    childNode.onLongPress = childItem.longAction
                    childNode.needsAccessBadge = childItem.needsAccessBadge
                    childNode.onRequestAccess = if (!childItem.needsAccessBadge) null else {
                        childItem.initialUri?.let { target -> ({ rootPicker(target, childKey) }) } ?: childItem.action
                    }
                    node.children += childNode
                }
            } else if (key == "application_public") {
                node.children.clear()
                applicationPublicItems.forEachIndexed { childIndex, childItem ->
                    val childKey = childItem.treeKey ?: "application_public_$childIndex"
                    val childUri = childItem.treeUri
                    val progName = childItem.detail?.takeIf { it.isNotBlank() } ?: childItem.title
                    val childNode = priorNodes[childKey]?.takeIf { it.uri == childUri && it.name == childItem.title }
                        ?: TreeNode(childKey, childUri, childItem.title, childItem.icon, 2,
                            {
                                currentProgramName = progName
                                childUri?.let { openRoot(it, "Application public", false) } ?: childItem.action()
                            }, true)
                    childNode.onOpen = {
                        currentProgramName = progName
                        childUri?.let { openRoot(it, "Application public", false) } ?: childItem.action()
                    }
                    childNode.canExpand = childItem.treeExpandable
                    childNode.needsAccessBadge = childItem.needsAccessBadge
                    childNode.onRequestAccess = if (childItem.needsAccessBadge) {
                        childItem.initialUri?.let { target -> ({ rootPicker(target, childKey) }) } ?: childItem.action
                    } else null
                    node.children += childNode
                }
            } else if (key == "installed_apps") {
                node.children.clear()
                installedAppCategories.forEach { category ->
                    val catKey = "apps_category_$category"
                    val catUri = AppsUri.categoryUri(category)
                    val catIcon = appsProvider.getCategoryIcon(category)
                    val openCat = { openInstalledApps(category) }
                    val catEntry = ResourceEntry(
                        ref = ResourceRef("apps", catUri.toString()),
                        metadata = ResourceMetadata(category, "vnd.android.document/directory", null, null, true),
                        uri = catUri
                    )
                    val catNode = priorNodes[catKey]?.takeIf { it.uri == catUri && it.name == category }
                        ?: TreeNode(catKey, catUri, category, catIcon, 2, openCat, false)
                    catNode.onOpen = openCat
                    catNode.canExpand = false
                    catNode.onLongPress = { showEntryMenu(catEntry) }
                    node.children += catNode
                }
            }
            virtualRoot.children += node
        }
        treeRoots.clear()
        treeRoots += virtualRoot
        updateTreeBranches(treeRoots)
        renderTree()
    }
    private fun updateTreeBranches(nodes: List<TreeNode>) {
        fun update(nodesAtDepth: List<TreeNode>, ancestorLastFlags: List<Boolean>) {
            nodesAtDepth.forEachIndexed { index, node ->
                node.isLast = index == nodesAtDepth.lastIndex
                // Depth zero is the single Virtual root. Carry continuation flags from every
                // visible ancestor below it, including the direct parent: its sibling line
                // must remain visible while this row is inside that parent's subtree.
                node.ancestorContinues = ancestorLastFlags.drop(1)
                update(node.children, ancestorLastFlags + !node.isLast)
            }
        }
        update(nodes, emptyList())
    }
    private fun renderTree() {
        if (::treeContent.isInitialized) {
            treeContent.removeAllViews()
            // Existing rows can handle direct drops. The panel and viewport listeners
            // remain as fallbacks for rows created while a drag expands the tree.
            renderTreeNodes(treeContent, treeRoots, ::selectTreeNode, ::renderTree, enableDropTarget = true)
            if (::treePanel.isInitialized) updateBrowserPanes()
            treeContent.post { scrollSelectedTreeRowIntoView() }
        }
        activePickerRerender?.invoke()
    }

    private fun scrollSelectedTreeRowIntoView() {
        if (!::treeContent.isInitialized || !::treeScrollView.isInitialized) return
        val selectedRow = (0 until treeContent.childCount)
            .map { treeContent.getChildAt(it) }
            .firstOrNull { row ->
                val key = row.tag as? String ?: return@firstOrNull false
                findTreeNodeByKey(key)?.let(::isTreeNodeSelected) == true
            } ?: return
        treeScrollView.post {
            if (!selectedRow.isAttachedToWindow || activeBrowserDragPayload != null) return@post
            val bounds = android.graphics.Rect()
            selectedRow.getDrawingRect(bounds)
            treeScrollView.offsetDescendantRectToMyCoords(selectedRow, bounds)
            val scrollTop = treeScrollView.scrollY
            val viewportHeight = treeScrollView.height
            if (viewportHeight <= 0) return@post
            val middleBandTop = scrollTop + viewportHeight / 6
            val middleBandBottom = scrollTop + viewportHeight * 5 / 6
            if (bounds.top < middleBandTop || bounds.bottom > middleBandBottom) {
                val desiredTop = bounds.centerY() - viewportHeight / 2
                val maxScroll = (treeContent.height - viewportHeight).coerceAtLeast(0)
                treeScrollView.smoothScrollTo(0, desiredTop.coerceIn(0, maxScroll))
            }
        }
    }
    private fun selectTreeNode(node: TreeNode) {
        if (node.needsAccessBadge) {
            (node.onRequestAccess ?: node.onOpen).invoke()
            return
        }
        if (treeVisible && isCompactPortrait()) setTreeVisible(false)
        when (node.key) {
            "virtual-root" -> showHomeRoot()
            "internal_shared" -> showInternalShared()
            "installed_apps" -> openInstalledApps()
            else -> if (node.uri != null && AppsUri.isAppsUri(node.uri)) {
                openInstalledApps(AppsUri.getCategoryName(node.uri))
            } else if (node.uri != null) {
                openTreeNode(node)
            } else {
                node.onOpen()
            }
        }
    }
    private fun renderTreeNodes(
        host: LinearLayout,
        nodes: List<TreeNode>,
        onSelect: (TreeNode) -> Unit,
        rerender: () -> Unit,
        excludedUri: Uri? = null,
        blockedSelectionUri: Uri? = null,
        isNodeSelected: (TreeNode) -> Boolean = ::isTreeNodeSelected,
        selectOnExpand: Boolean = false,
        expandOnSelect: Boolean = false,
        includeNode: (TreeNode) -> Boolean = { true },
        onCreateDirectoryInNode: ((TreeNode, Uri) -> Unit)? = null,
        enableDropTarget: Boolean = true
    ) {
        updateTreeBranches(nodes)
        val visibleRoots = nodes.filter { (excludedUri == null || it.uri != excludedUri) && includeNode(it) }
        visibleRoots.forEachIndexed { index, node ->
            node.isLast = index == visibleRoots.lastIndex
            node.ancestorContinues = emptyList()
            addTreeNodeRow(
                node = node,
                host = host,
                onSelect = onSelect,
                rerender = rerender,
                excludedUri = excludedUri,
                blockedSelectionUri = blockedSelectionUri,
                ancestorLastFlags = emptyList(),
                isNodeSelected = isNodeSelected,
                selectOnExpand = selectOnExpand,
                expandOnSelect = expandOnSelect,
                includeNode = includeNode,
                onCreateDirectoryInNode = onCreateDirectoryInNode,
                enableDropTarget = enableDropTarget
            )
        }
    }
    private fun addTreeNodeRow(
        node: TreeNode,
        host: LinearLayout,
        onSelect: (TreeNode) -> Unit,
        rerender: () -> Unit,
        excludedUri: Uri?,
        blockedSelectionUri: Uri?,
        ancestorLastFlags: List<Boolean> = emptyList(),
        isNodeSelected: (TreeNode) -> Boolean = ::isTreeNodeSelected,
        selectOnExpand: Boolean = false,
        expandOnSelect: Boolean = false,
        includeNode: (TreeNode) -> Boolean = { true },
        onCreateDirectoryInNode: ((TreeNode, Uri) -> Unit)? = null,
        enableDropTarget: Boolean = true
    ) {
        val isCreateDirNode = isCreateDirectoryNode(node)
        fun activateNode() {
            if (isCreateDirNode) {
                node.onOpen()
                return
            }
            activateTreeItem(node.uri, blockedSelectionUri, node.needsAccessBadge,
                { (node.onRequestAccess ?: node.onOpen).invoke() }, {
                    onSelect(node)
                    if (expandOnSelect && node.canExpand && !node.expanded) toggleTreeNode(node, rerender)
                })
        }
        val selected = !isCreateDirNode && isNodeSelected(node)
        val showCreateDirChild = onCreateDirectoryInNode != null &&
            selected &&
            node.expanded &&
            !node.needsAccessBadge &&
            node.uri != null &&
            (!ArchiveUri.isArchiveUri(node.uri) || archiveProvider.cachedWritable(node.uri)) &&
            !AppsUri.isAppsUri(node.uri)
        val visibleChildren = prepareVisibleChildren(
            parentNode = node,
            parentAncestorFlags = ancestorLastFlags,
            excludedUri = excludedUri,
            includeCreateDirectory = showCreateDirChild,
            onCreateDirectory = onCreateDirectoryInNode
        ).filter(includeNode)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (selected) {
                    if (dark) 0xFF3A4935.toInt() else 0xFFD9E8D1.toInt()
                } else {
                    if (dark) 0x0020241F else 0x00EAE9E3
                })
            }
            isFocusable = true
            tag = node.key
            contentDescription = if (selected) "${node.name}, current folder" else node.name
            isSelected = selected
            setOnClickListener { activateNode() }
        }
        if (enableDropTarget) installTreeDropTarget(row, node, selected)
        row.addView(TreeBranchView(this, node.depth, node.ancestorContinues, node.isLast).apply {
            setOnClickListener { activateNode() }
        }, LinearLayout.LayoutParams(dp(node.depth * 24), -1))
        val disclosureTarget = FrameLayout(this).apply {
            if (node.needsAccessBadge) {
                setOnClickListener { activateNode() }
            } else if (node.canExpand) {
                setOnClickListener {
                    onDisclosureClicked(
                        node = node,
                        selectOnExpand = selectOnExpand,
                        onSelect = {
                            activateTreeItem(
                                node.uri,
                                blockedSelectionUri,
                                node.needsAccessBadge,
                                { (node.onRequestAccess ?: node.onOpen).invoke() },
                                { onSelect(node) }
                            )
                        },
                        onToggle = { toggleTreeNode(node, rerender) }
                    )
                }
            } else {
                setOnClickListener { activateNode() }
            }
            contentDescription = if (node.expanded) "Collapse ${node.name}" else "Expand ${node.name}"
            isFocusable = node.canExpand || node.needsAccessBadge || isCreateDirNode
        }
        disclosureTarget.addView(TreeDisclosureConnector(
            this,
            hasParent = node.depth > 0,
            hasVisibleChildren = node.expanded && (visibleChildren.isNotEmpty() || node.loading),
            hasDisclosureBox = node.canExpand
        ), FrameLayout.LayoutParams(-1, -1))
        val disclosure = TextView(this).apply {
            text = when { node.loading -> "…"; node.expanded -> "−"; node.canExpand -> "+"; else -> "" }
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(setTextColorForTheme())
            if (node.canExpand) background = GradientDrawable().apply {
                setColor(if (dark) 0xFF252A24.toInt() else 0xFFF5F4EF.toInt())
                setStroke(dp(1), if (dark) 0xFF9BA994.toInt() else 0xFF687363.toInt())
                cornerRadius = dp(2).toFloat(); setSize(dp(18), dp(18))
            }
            isClickable = false
        }
        disclosureTarget.addView(disclosure, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        row.addView(disclosureTarget, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleCell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val iconStack = FrameLayout(this)
        val placeholder = TextView(this).apply {
            text = node.icon; textSize = 17f; gravity = Gravity.CENTER
        }
        iconStack.addView(placeholder, FrameLayout.LayoutParams(dp(30), dp(32)))
        if (node.uri != null && !AppsUri.isAppsUri(node.uri) && (node.icon == "📁" || node.canExpand)) {
            val dirKey = "tree:${node.uri}"
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                tag = dirKey
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            }
            iconStack.addView(image)
            val cachedUri = directoryIconCache.get(dirKey)
            if (cachedUri != null && cachedUri.isNotEmpty()) {
                val previewKey = "tree-dir-icon:$dirKey:$cachedUri"
                val cachedBitmap = thumbnailCache.get(previewKey)
                if (cachedBitmap != null) {
                    image.setImageBitmap(cachedBitmap)
                    placeholder.visibility = View.GONE
                }
            } else if (cachedUri == null) {
                imageIo.execute {
                    if (isFinishing || isDestroyed) return@execute
                    val iconUri = findDirectoryIcon(node.uri)
                    directoryIconCache.put(dirKey, iconUri?.toString() ?: "")
                    if (iconUri != null) {
                        val previewKey = "tree-dir-icon:$dirKey:$iconUri"
                        val bitmap = thumbnailDecoder.decode(iconUri, dp(26))
                        if (bitmap != null) {
                            thumbnailCache.put(previewKey, bitmap)
                            runOnUiThread {
                                if (image.tag == dirKey) {
                                    image.setImageBitmap(bitmap)
                                    placeholder.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isCreateDirNode) {
            iconStack.addView(
                createDirBadge().apply { setOnClickListener { activateNode() } },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.END or Gravity.BOTTOM)
            )
        }
        if (node.needsAccessBadge) {
            iconStack.addView(accessBadge().apply { setOnClickListener { activateNode() } },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.END or Gravity.TOP))
        }
        iconStack.setOnClickListener { activateNode() }
        titleCell.addView(iconStack, LinearLayout.LayoutParams(dp(32), dp(36)))
        titleCell.setOnClickListener { activateNode() }
        val title = TextView(this).apply {
            text = node.name; textSize = 13f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(setTextColorForTheme()); maxLines = 2; setPadding(dp(4), dp(4), dp(4), dp(4))
            alpha = if (blockedSelectionUri != null && node.uri == blockedSelectionUri) 0.55f else 1f
            setOnClickListener { activateNode() }
        }
        titleCell.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(titleCell, LinearLayout.LayoutParams(0, -1, 1f))
        host.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        if (node.expanded) {
            val childAncestorFlags = ancestorLastFlags + !node.isLast
            visibleChildren.forEach { child ->
                addTreeNodeRow(
                    node = child,
                    host = host,
                    onSelect = onSelect,
                    rerender = rerender,
                    excludedUri = excludedUri,
                    blockedSelectionUri = blockedSelectionUri,
                    ancestorLastFlags = childAncestorFlags,
                    isNodeSelected = isNodeSelected,
                    selectOnExpand = selectOnExpand,
                    expandOnSelect = expandOnSelect,
                    includeNode = includeNode,
                    onCreateDirectoryInNode = onCreateDirectoryInNode,
                    enableDropTarget = enableDropTarget
                )
            }
        }
    }
    private fun createDirBadge(): TextView = TextView(this).apply {
        text = "+"; textSize = 11f; gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(if (dark) 0xFFF0F0E9.toInt() else 0xFF20231F.toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (dark) 0xFF3A4935.toInt() else 0xFFD9E8D1.toInt())
            setStroke(dp(1), if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
        }
        includeFontPadding = false
        contentDescription = "Create directory"
    }
    private fun accessBadge(): TextView = TextView(this).apply {
        text = "🔒"; textSize = 10f; gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (dark) 0xFF343A31.toInt() else 0xFFE8E4D8.toInt())
            setStroke(dp(1), if (dark) 0xFF9BA994.toInt() else 0xFF687363.toInt())
        }
        contentDescription = "Tap to grant folder access"
    }
    private fun isTreeNodeSelected(node: TreeNode): Boolean {
        if (homeMode) {
            return when (node.key) {
                "virtual-root" -> homePage == "root"
                "internal_shared" -> homePage == "internal_shared"
                "application_public" -> homePage == "application_public"
                else -> false
            }
        }
        val location = current ?: return false
        if (node.uri == null) return false
        return try { sourceIdentity(location) == sourceIdentity(node.uri) } catch (_: Exception) { location == node.uri }
    }
    private fun toggleTreeNode(node: TreeNode, rerender: () -> Unit = ::renderTree) {
        if (node.needsAccessBadge) {
            (node.onRequestAccess ?: node.onOpen).invoke()
            return
        }
        if (node.expanded) { node.expanded = false; node.loadId++; node.loading = false; rerender(); return }
        if (node.key == "virtual-root" || node.key == "internal_shared" || node.key == "installed_apps") {
            node.expanded = true; rerender()
            if (node.key == "internal_shared") refreshInternalSharedTree()
            if (node.key == "installed_apps" && installedAppCategories.isEmpty()) refreshInstalledAppsTree()
            return
        }
        if (node.key == "application_public") {
            node.expanded = true
            rerender()
            if (node.children.isEmpty()) node.onOpen()
            return
        }
        if (node.uri == null) { node.onOpen(); return }
        node.expanded = true
        loadTreeChildren(node, rerender)
    }
    private fun loadTreeChildren(node: TreeNode, rerender: () -> Unit, attempt: Int = 0) {
        val uri = node.uri ?: return
        val request = ++node.loadId
        node.loading = true; rerender()
        io.execute {
            val result = provider.list(resourceRef(uri))
            runOnUiThread {
                if (isFinishing || isDestroyed || request != node.loadId || !node.expanded || findTreeRoute(treeRoots, node) == null) return@runOnUiThread
                node.loading = false
                var expandedChildrenToRefresh = emptyList<TreeNode>()
                when (result) {
                    is ProviderResult.Success -> {
                        if (!result.isLoading || result.value.isNotEmpty()) {
                            cacheDirectoryIconListing(uri, runCatching { resourceRef(uri) }.getOrNull(), result.value)
                        }
                        val previousChildren = node.children.associateBy { it.key }
                        node.children.clear()
                        val folders = result.value.filter {
                            !AppsUri.isAppUri(it.uri) &&
                            (it.metadata.isDirectory || ArchiveFormats.isArchive(it.metadata.name, it.metadata.mimeType)) &&
                            (showHidden || !it.metadata.isHidden)
                        }
                        folders.forEachIndexed { index, child ->
                            val childIsArchive = !AppsUri.isAppUri(child.uri) && ArchiveFormats.isArchive(child.metadata.name, child.metadata.mimeType)
                            val childUri = if (childIsArchive) ArchiveUri.toArchiveUri(child.uri) else child.uri
                            val icon = if (childIsArchive) "📦" else "📁"
                            val previousChild = previousChildren["${node.key}/${sourceIdentity(childUri)}"]
                            lateinit var childNode: TreeNode
                            childNode = TreeNode(
                                key = "${node.key}/${sourceIdentity(childUri)}",
                                uri = childUri,
                                name = child.metadata.name,
                                icon = icon,
                                depth = node.depth + 1,
                                onOpen = { openTreeNode(childNode) },
                                canExpand = true,
                                ancestorContinues = node.ancestorContinues + !node.isLast,
                                isLast = index == folders.lastIndex,
                                expanded = previousChild?.expanded ?: false,
                                children = previousChild?.children ?: mutableListOf(),
                                onLongPress = { showEntryMenu(child) }
                            )
                            node.children += childNode
                        }
                        expandedChildrenToRefresh = node.children.filter {
                            it.expanded && it.canExpand && !it.needsAccessBadge
                        }
                        if (result.notice != null) status.text = result.notice
                        if (result.isLoading) {
                            if (attempt < 8) {
                                node.loading = true
                                root.postDelayed({
                                    if (!isDestroyed && node.expanded && node.loadId == request && findTreeRoute(treeRoots, node) != null) {
                                        loadTreeChildren(node, rerender, attempt + 1)
                                    }
                                }, 1000)
                            } else status.text = "This storage provider is still loading. Collapse and expand to retry."
                        }
                    }
                    is ProviderResult.Failure -> {
                        status.text = result.message
                        if (localProvider.owns(uri)) {
                            if (!localProvider.hasAccess()) {
                                node.needsAccessBadge = true
                                node.onRequestAccess = { requestSharedStorageAccess() }
                                requestSharedStorageAccess()
                            }
                        } else if (uri.scheme == "content" && !ArchiveUri.isArchiveUri(uri) && !AppsUri.isAppsUri(uri) && !SftpUri.isSftpUri(uri) && !FtpUri.isFtpUri(uri) && !SmbUri.isSmbUri(uri) && !WebDavUri.owns(uri) && isAccessFailure(result.message, result.cause)) {
                            node.needsAccessBadge = true
                            node.onRequestAccess = { rootPicker(node.uri, "tree_permission_regrant") }
                            node.onRequestAccess?.invoke()
                        }
                    }
                    is ProviderResult.Unsupported -> Unit
                }
                rerender()
                expandedChildrenToRefresh.forEach { child -> loadTreeChildren(child, rerender) }
            }
        }
    }

    private fun refreshTreeAfterTransfer(destination: Uri) {
        if (destination == ContainerLinksProvider.ROOT) { refreshRootContainerLinks(); return }
        val destinationIdentity = sourceIdentity(destination)
        fun find(nodes: List<TreeNode>): TreeNode? {
            nodes.forEach { node ->
                if (node.uri?.let { sourceIdentity(it) == destinationIdentity } == true) return node
                find(node.children)?.let { return it }
            }
            return null
        }
        val destinationNode = find(treeRoots)
        if (destinationNode == null || !destinationNode.canExpand || destinationNode.needsAccessBadge) {
            com.infinifolder.storage.DiagnosticLog.record(
                "browser.tree.refresh-after-transfer-skipped",
                "reason=${if (destinationNode == null) "destination-not-in-tree" else "not-expandable-or-needs-access"}"
            )
            return
        }
        destinationNode.expanded = true
        com.infinifolder.storage.DiagnosticLog.record(
            "browser.tree.refresh-after-transfer", "name=${destinationNode.name} uri=$destination"
        )
        loadTreeChildren(destinationNode, ::renderTree)
    }
    private fun openTreeNode(target: TreeNode) {
        if (target.key == "virtual-root" || target.uri == ContainerLinksProvider.ROOT) { showHomeRoot(); return }
        if (target.uri == null) { target.onOpen(); return }
        if (AppsUri.isAppsUri(target.uri)) {
            openInstalledApps(AppsUri.getCategoryName(target.uri))
            return
        }
        val route = findTreeRoute(treeRoots, target) ?: run {
            val prefix = when {
                target.key.startsWith("app_provider_") || target.key.startsWith("application_public") -> "Application public"
                isPrimaryExternalStorageUri(target.uri) -> "Internal shared storage"
                else -> null
            }
            currentProgramName = if (prefix == "Application public") {
                target.uri?.let { AppDocumentSources.getProgramName(this, it) } ?: target.name
            } else null
            openRoot(target.uri, prefix)
            return
        }
        logicalLocationPrefix = when {
            route.any { it.uri == localProvider.rootUri } -> null
            route.any { it.key == "internal_shared" } -> "Internal shared storage"
            route.any { it.key == "application_public" } -> "Application public"
            route.any { it.key == "installed_apps" } -> "Installed Applications"
            else -> null
        }
        currentProgramName = if (logicalLocationPrefix == "Application public") {
            val appNode = route.firstOrNull { it.key.startsWith("app_provider_") || it.key.startsWith("application_public_") }
            appNode?.name ?: target.uri?.let { AppDocumentSources.getProgramName(this, it) } ?: currentProgramName
        } else null
        history.clear(); historyNames.clear()
        route.filter { it.uri != null && it.uri != ContainerLinksProvider.ROOT }.forEach { node ->
            history += requireNotNull(node.uri); historyNames += node.name
        }
        current = target.uri; homeMode = false; updateCreateAction()
        renderLocation(); load(target.uri)
    }
    private fun findTreeRoute(nodes: List<TreeNode>, target: TreeNode, route: MutableList<TreeNode> = mutableListOf()): List<TreeNode>? {
        nodes.forEach { node ->
            route.add(node)
            if (node === target) return route.toList()
            findTreeRoute(node.children, target, route)?.let { return it }
            route.removeAt(route.lastIndex)
        }
        return null
    }
    private fun showSources() {
        val context = dialogContext()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val scroll = ScrollView(context).apply { addView(list) }
        var dialog: android.app.AlertDialog? = null
        fun section(title: String) {
            list.addView(TextView(context).apply { text = title; textSize = 12f; setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt()); setPadding(dp(12), dp(14), dp(12), dp(6)) })
        }
        fun row(icon: String, title: String, detail: String, enabled: Boolean = true, onLongPress: (() -> Unit)? = null, action: () -> Unit): TextView {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(64); alpha = if (enabled) 1f else 0.55f
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            item.addView(TextView(context).apply { text = icon; textSize = 25f; gravity = Gravity.CENTER; minWidth = dp(48); setTextColor(setTextColorForTheme()) })
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(setTextColorForTheme()) })
            val detailView = TextView(context).apply { text = detail; textSize = 12f; setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            labels.addView(detailView)
            item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (enabled) item.setOnClickListener { dialog?.dismiss(); action() }
            if (enabled && onLongPress != null) item.setOnLongClickListener {
                dialog?.dismiss()
                onLongPress()
                true
            }
            list.addView(item)
            return detailView
        }

        if (history.isNotEmpty()) {
            section("CURRENT PATH")
            history.forEachIndexed { index, uri ->
                row(if (index == 0) "🏠" else "📁", historyNames.getOrElse(index) { "Folder" }, if (index == history.lastIndex) "Current folder" else "Open this folder") {
                    selectLocation(index)
                }
            }
        }

        section("ANDROID STORAGE")
        val storageSectionContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.addView(storageSectionContainer)
        fun rowIn(targetHost: LinearLayout, icon: String, title: String, detail: String, enabled: Boolean = true, onLongPress: (() -> Unit)? = null, action: () -> Unit): TextView {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(64); alpha = if (enabled) 1f else 0.55f
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            item.addView(TextView(context).apply { text = icon; textSize = 25f; gravity = Gravity.CENTER; minWidth = dp(48); setTextColor(setTextColorForTheme()) })
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(setTextColorForTheme()) })
            val detailView = TextView(context).apply { text = detail; textSize = 12f; setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            labels.addView(detailView)
            item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (enabled) item.setOnClickListener { dialog?.dismiss(); action() }
            if (enabled && onLongPress != null) item.setOnLongClickListener {
                dialog?.dismiss()
                onLongPress()
                true
            }
            targetHost.addView(item)
            return detailView
        }
        row("➕", "Choose another storage location…", "Opens Android's access grant picker") { rootPicker() }

        accessIo.execute {
            val grantedRoots = try {
                contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission && it.uri.pathSegments.contains("tree") }
                    .map { it.uri }
                    .filterNot { AppsUri.isAppsUri(it) }
                    .distinctBy(::sourceIdentity)
                    .toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
            shared.getString("root", null)?.let { rootUri ->
                val uri = Uri.parse(rootUri)
                if (!AppsUri.isAppsUri(uri) && grantedRoots.none { sourceIdentity(it) == sourceIdentity(uri) }) grantedRoots.add(uri)
            }
            val volumes = try {
                getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || dialog?.isShowing != true) return@runOnUiThread
                storageSectionContainer.removeAllViews()
                if (grantedRoots.isEmpty()) {
                    rowIn(storageSectionContainer, "📂", "Choose a storage folder…", "Grant access with Android's storage picker") { rootPicker() }
                } else {
                    grantedRoots.forEach { uri ->
                        val name = shared.getString("source_name_${uri}", null)
                            ?: (if (uri == current) historyNames.firstOrNull() else null)
                            ?: "Storage folder"
                        val cachedPath = shared.getString("source_path_v2_${uri}", null)
                        val detailView = rowIn(storageSectionContainer, "💾", name, cachedPath ?: "Loading folder path…", onLongPress = { showAuthorizedRootActions(uri, name) }) { openRoot(uri) }
                        if (cachedPath == null) io.execute {
                            val path = displayPathFor(uri) ?: name
                            shared.edit().putString("source_path_v2_${uri}", path).apply()
                            runOnUiThread { detailView.text = path }
                        }
                    }
                }
                volumes.forEach { volume ->
                    val mounted = volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                    val primary = volume.isPrimary
                    val title = volume.getDescription(this)
                    val subtitle = when {
                        !mounted -> "Not currently mounted"
                        volume.isRemovable -> "Removable storage · choose a folder to grant access"
                        primary -> "Device storage · choose a folder to grant access"
                        else -> "Available volume · choose a folder to grant access"
                    }
                    rowIn(storageSectionContainer, if (volume.isRemovable) "💾" else "📱", title, subtitle, mounted) {
                        if (android.os.Build.VERSION.SDK_INT >= 29) startActivityForResult(volume.createOpenDocumentTreeIntent(), pickTreeRequest)
                        else rootPicker()
                    }
                }
            }
        }

        section("NETWORK & WEB SOURCES")
        providerRegistry.sourceTypes().filter { it.id != "saf" }.forEach { source ->
            row(source.icon, source.label, if (source.implemented) "Ready to connect" else "Provider hook added · connection support not implemented") {
                dialogBuilder().setTitle(source.label)
                    .setMessage("${source.description}\n\nThe provider registration hook is in place; connection setup and file access will be added in a later milestone.")
                    .setPositiveButton("OK", null).show()
            }
        }

        dialog = dialogBuilder().setTitle("Locations").setView(scroll).setNegativeButton("Close", null).create()
        dialog?.show()
    }
    private fun showAuthorizedRootActions(uri: Uri, name: String) {
        dialogBuilder().setTitle(name)
            .setItems(arrayOf("Properties", "Remove from list")) { _, index ->
                when (index) {
                    0 -> showAuthorizedRootProperties(uri, name)
                    1 -> dialogBuilder().setTitle("Remove $name?")
                        .setMessage("InfiniFolder will forget this location and release its saved access. Your files will not be changed.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ -> removeAuthorizedRoot(uri) }
                        .show()
                }
            }.show()
    }
    private fun showAuthorizedRootProperties(uri: Uri, name: String) {
        val entry = ResourceEntry(resourceRef(uri), ResourceMetadata(name, "vnd.android.document/directory", null, null, true), uri)
        showPropertiesDialog(entry)
    }
    private fun removeAuthorizedRoot(uri: Uri) {
        contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let { permission ->
            var flags = 0
            if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { contentResolver.releasePersistableUriPermission(uri, flags) } catch (_: SecurityException) { }
        }
        shared.edit().apply {
            if (shared.getString("root", null) == uri.toString()) remove("root")
            remove("source_name_${uri}")
            remove("source_path_${uri}")
            remove("source_path_v2_${uri}")
            shared.all.filter { (key, value) -> key.startsWith("tree_source_") && value == uri.toString() }
                .forEach { (key, _) -> remove(key) }
        }.apply()
        if (current == uri || history.firstOrNull() == uri) {
            history.clear(); historyNames.clear(); current = null
            emptyState()
        }
        showHomeRoot()
    }
    private fun browserMenuItems(capabilities: Map<ResourceRef, ResourceCapabilities> = emptyMap()): List<BrowserMenu.Item> {
        fun action(label: String, enabled: Boolean = true, work: () -> Unit) = BrowserMenu.Item(label, enabled, action = work)
        fun submenu(label: String, items: List<BrowserMenu.Item>) = BrowserMenu.Item(label, children = items)
        val folder = !homeMode && current != null && !AppsUri.isAppsUri(current!!)
        val rootLinks = homeMode && homePage == "root"
        val selectable = !homeMode || rootLinks
        val selected = entries.filter { it.uri in selectedUris }
        val hasSelection = selectable && selected.isNotEmpty()
        val single = selected.singleOrNull()
        fun allowed(action: StorageAction) = hasSelection && StorageActionPolicy.selection(action, selected, capabilities).mayAttempt
        return listOf(
            submenu("File", listOf(
                submenu("New", listOf(
                    action("Folder…", folder) { showCreateDirectoryDialog() },
                    action("Link…", folder || (homeMode && homePage == "root")) { showCreateUriLinkDialog() },
                    action("Termux command…", folder && TermuxAvailability.installed(this)) { showCreateTermuxCommandLinkDialog() },
                    submenu("Document / image", NewFileTemplates.documents.map { template ->
                        action(template.label + "…", folder) { showNewFileDialog(template) }
                    }),
                    submenu("Archive", NewFileTemplates.archives.map { template ->
                        action(template.label + "…", folder) { showNewFileDialog(template) }
                    } + listOf("RAR", "ARJ", "CAB", "TAR + Unix compress").map { BrowserMenu.Item("$it (reading only)", false) })
                )),
                action("Virtual root") { showHomeRoot(); setTreeVisible(false) },
                action("Add container link to Virtual root…") { showRootContainerLinkDialog() },
                action("Add storage connection…") { showAddConnectionDialog() },
                action("Copy to…", allowed(StorageAction.COPY)) { showBatchDestinationPicker(selected, false) },
                action("Move to…", allowed(StorageAction.MOVE)) { showBatchDestinationPicker(selected, true) },
                action("Delete…", allowed(StorageAction.DELETE)) { confirmBatchDelete(selected) },
                action("Properties…", hasSelection || folder) {
                    if (hasSelection) showEntriesPropertiesDialog(selected) else current?.let { location ->
                        io.execute {
                            val entry = (provider.stat(resourceRef(location)) as? ProviderResult.Success)?.value
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed && entry != null) showPropertiesDialog(entry)
                            }
                        }
                    }
                }
            )),
            submenu("Edit", listOf(
                action("Copy file / URI references", allowed(StorageAction.COPY)) { copyEntriesToClipboard(selected) },
                action("Cut file references", allowed(StorageAction.MOVE)) { copyEntriesToClipboard(selected, cut = true) },
                action("Copy contents (text / image)", single != null &&
                    (isTextEntry(single, null) || isImageEntry(single, null))) { single?.let { copyEntryContentsToClipboard(it, ambiguousMagicCache.get(it.uri.toString())) } },
                action("Copy URI text", hasSelection) {
                    copiedEntries = null; cutClipboardToken = null
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("File URIs", selected.joinToString("\n") { it.uri.toString() }))
                },
                action("Paste into current folder", folder || rootLinks) { if (rootLinks) pasteFromClipboard(ContainerLinksProvider.ROOT) else current?.let(::pasteFromClipboard) },
                action("Select destination for clipboard files…") {
                    getSystemService(ClipboardManager::class.java)?.primaryClip?.let { clip ->
                        val copied = resolveClipboardFileEntries(clip)
                        if (copied.isNotEmpty()) showBatchDestinationPicker(copied, false)
                        else Toast.makeText(this, "Clipboard contains no file references", Toast.LENGTH_SHORT).show()
                    }
                }
            )),
            submenu("View", listOf(
                action(if (treeVisible) "Show files" else "Show tree") { showSidebar() },
                action(if (grid) "List / details view" else "Grid view") {
                    grid = !grid; shared.edit().putBoolean("grid", grid).apply(); renderCurrentContents()
                },
                action(if (showHidden) "Hide hidden files" else "Show hidden files") {
                    showHidden = !showHidden; shared.edit().putBoolean("show_hidden", showHidden).apply()
                    if (!homeMode) updateListingStatus()
                    renderCurrentContents(); refreshInternalSharedTree(); rebuildTreeRoots()
                },
                action("Internal viewers: ${if (useBuiltInViewersAndPlayers) "on" else "off"}") {
                    useBuiltInViewersAndPlayers = !useBuiltInViewersAndPlayers
                    shared.edit().putBoolean("use_builtin_viewers_and_players", useBuiltInViewersAndPlayers).apply()
                },
                submenu("Sort order", listOf("name" to "Name", "type" to "File type (MIME)", "size" to "Size", "modified" to "Modified").map { (key, label) ->
                    action((if (sortKey == key) "✓  " else "") + label) { setSortOrder(key, sortDescending) }
                } + listOf(
                    action((if (!sortDescending) "✓  " else "") + "Ascending / A–Z") { setSortOrder(sortKey, false) },
                    action((if (sortDescending) "✓  " else "") + "Descending / Z–A") { setSortOrder(sortKey, true) }
                )),
                submenu("Appearance", listOf(
                    action("Icon size…") { showIconSizeDialog() },
                    action("Icon theme…") { showIconThemeDialog() },
                    action("Interface theme…") { showAppThemeDialog() },
                    action("Light / dark / system…") { showThemeModeDialog() }
                )),
                action("Refresh") { refreshCurrentLocation() },
                action("Activity…") { showOperations() }
            )),
            submenu("Select", listOf(
                action(if (selectionMode) "Leave selection mode" else "Enter selection mode", selectable) {
                    if (selectionMode) exitSelectionMode() else {
                        selectionMode = true; renderCurrentContents(); updateListingStatus()
                    }
                },
                action("Select by wildcard…", selectable) { showPatternSelection(false) },
                action("Select by regular expression…", selectable) { showPatternSelection(true) },
                action("Select all", selectable) {
                    selectionMode = true; selectedUris.addAll(entries.filter { showHidden || !it.metadata.isHidden }.map { it.uri })
                    renderCurrentContents(); updateListingStatus()
                },
                action("Invert selection", selectable) {
                    selectionMode = true
                    entries.filter { showHidden || !it.metadata.isHidden }.forEach { if (!selectedUris.add(it.uri)) selectedUris.remove(it.uri) }
                    renderCurrentContents(); updateListingStatus()
                },
                action("Clear selection", selectable) { selectedUris.clear(); renderCurrentContents(); updateListingStatus() }
            )),
            submenu("Settings", listOf(
                action("Termux file associations…") { TermuxAssociationSettings.show(this) },
                action("Common locations…") { showCommonLocationsSettings() },
                action("Copy verification: ${if (shared.getBoolean("verify_copies", true)) "on" else "off"}…") { showCopyVerificationSettings() },
                action("Disk cache…") { com.infinifolder.viewer.DiskCacheSettings.show(this) },
                action("Unfinished external edits…") { startActivity(Intent(this, com.infinifolder.viewer.ExternalEditActivity::class.java)) },
                action("Recover interrupted saves…") { startActivity(Intent(this, com.infinifolder.viewer.SaveRecoveryActivity::class.java)) },
                action("Debug dump settings…") { startActivity(Intent(this, com.infinifolder.viewer.DiagnosticsActivity::class.java)) }
            ) + if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                listOf(action("Offload debug information") { DebugTools.offload(this) }) else emptyList())
        )
    }

    private fun showCommonLocationsSettings() {
        CommonLocationsDialog.show(this, dialogContext(), commonLocations) { initial, selected ->
            showFolderPicker("Choose common location", initial, requireWritable = false, rememberSelection = false,
                onFolderSelected = selected)
        }
    }

    private fun showCopyVerificationSettings() {
        val checkbox = CheckBox(dialogContext()).apply {
            text = "Read back and verify copies"; isChecked = shared.getBoolean("verify_copies", true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        dialogBuilder().setTitle("Copy verification")
            .setMessage("Verification rereads the entire destination. For a large network file this can take as long as another transfer.\n\nWhen disabled, copies still check reported length and require successful flush/close. Moves and deleted-file recovery remain verified. The transfer dialog also has a remembered checkbox; turning it off can stop an ongoing copy verification.")
            .setView(checkbox).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                shared.edit().putBoolean("verify_copies", checkbox.isChecked).apply()
            }.show()
    }

    private fun setSortOrder(key: String, descending: Boolean) {
        sortKey = key; sortDescending = descending
        shared.edit().putString("sort_key", key).putBoolean("sort_descending", descending).apply()
        renderCurrentContents()
    }

    private fun showPatternSelection(regularExpression: Boolean) {
        val location = current ?: return
        val context = dialogContext()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val input = EditText(context).apply { hint = if (regularExpression) ".*\\.jpg" else "*.jpg"; isSingleLine = true }
        val ignoreCase = CheckBox(context).apply { text = "Ignore case"; isChecked = true }
        val folders = CheckBox(context).apply { text = "Include folders"; isChecked = true }
        val add = CheckBox(context).apply { text = "Add to current selection" }
        body.addView(input); body.addView(ignoreCase); body.addView(folders); body.addView(add)
        val dialog = dialogBuilder().setTitle(if (regularExpression) "Select by regular expression" else "Select by wildcard")
            .setMessage("Match the whole name in the current displayed folder. " + if (regularExpression) "Example: .*\\.(jpg|png)" else "* matches any text; ? matches one character.")
            .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Select", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isEmpty() || value.length > 512) { input.error = "Enter a pattern of 1–512 characters"; return@setOnClickListener }
                val expression = if (regularExpression) value else value.map { character -> when (character) {
                    '*' -> ".*"; '?' -> "."; else -> Regex.escape(character.toString())
                } }.joinToString("")
                val regex = try { Regex(expression, if (ignoreCase.isChecked) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
                    catch (e: Exception) { input.error = e.message ?: "Invalid pattern"; return@setOnClickListener }
                val includeFolders = folders.isChecked
                val addSelection = add.isChecked
                val candidates = entries.filter { (showHidden || !it.metadata.isHidden) && (includeFolders || !it.metadata.isDirectory) }.toList()
                dialog.dismiss()
                io.execute {
                    val matching = try {
                        val matches = FilenamePattern.matching(candidates.map { it.metadata.name }, regex)
                        candidates.filterIndexed { index, _ -> matches[index] }.map { it.uri }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) Toast.makeText(this, error.message ?: "Pattern failed", Toast.LENGTH_LONG).show()
                        }
                        return@execute
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || homeMode || current != location) return@runOnUiThread
                        selectionMode = true
                        if (!addSelection) selectedUris.clear()
                        selectedUris.addAll(matching)
                        renderCurrentContents(); updateListingStatus()
                        Toast.makeText(this, "${matching.size} matching items selected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNewFileDialog(template: NewFileTemplates.Template) {
        val parent = current ?: return
        val input = EditText(dialogContext()).apply { setText("Untitled.${template.extension}"); isSingleLine = true }
        val dialog = dialogBuilder().setTitle("New ${template.label}").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name in setOf(".", "..") || '/' in name || '\u0000' in name) {
                    input.error = "Enter a valid filename"; return@setOnClickListener
                }
                dialog.dismiss()
                val id = operations.submit("Create $name", null, 1,
                    conflictKeys = setOf(operationTargetKey(resourceRef(parent), name)), displayName = name,
                    destination = parent.toString()) { update, cancelled ->
                    val staged = File.createTempFile("new-document-", ".${template.extension}", cacheDir)
                    try {
                        NewFileTemplates.write(template, staged)
                        if (cancelled()) throw java.io.InterruptedIOException("Creation cancelled")
                        val parentRef = resourceRef(parent)
                        val info = (provider.fileSystemInfo(parentRef) as? ProviderResult.Success)?.value
                        info?.requireFileFits(name, staged.length())
                        info?.availableBytes?.let { if (staged.length() > it) throw java.io.IOException("Not enough destination space") }
                        val batchResult = provider.beginWriteBatch(parentRef, cancelled)
                        if (batchResult is ProviderResult.Failure) throw java.io.IOException(batchResult.message, batchResult.cause)
                        val batch = (batchResult as? ProviderResult.Success)?.value
                        val target = batch?.provider ?: provider
                        var created: ResourceEntry? = null
                        try {
                            created = when (val result = target.createFile(parentRef, name, template.mime)) {
                                is ProviderResult.Success -> result.value
                                is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                                else -> throw java.io.IOException("This location cannot create files")
                            }
                            val output = target.openWrite(created.ref)
                            if (output !is ProviderResult.Success) throw java.io.IOException((output as? ProviderResult.Failure)?.message ?: "Cannot write file")
                            output.value.use { out -> staged.inputStream().use { inputStream ->
                                val buffer = ByteArray(65536)
                                var copied = 0L
                                while (true) {
                                    if (cancelled()) throw java.io.InterruptedIOException("Creation cancelled")
                                    val read = inputStream.read(buffer); if (read < 0) break
                                    out.write(buffer, 0, read); copied += read
                                    update(OperationUpdate(bytes = copied, totalBytes = staged.length(), currentItem = name,
                                        currentItemBytes = copied, currentItemTotalBytes = staged.length()))
                                }
                            } }
                            val readBack = target.openRead(created.ref)
                            if (readBack !is ProviderResult.Success) throw java.io.IOException("Cannot verify new file")
                            val expected = staged.inputStream().use { com.infinifolder.storage.ContentRevision.read(it) }
                            val actual = readBack.value.use { com.infinifolder.storage.ContentRevision.read(it) }
                            if (expected != actual) throw java.io.IOException("New file verification failed")
                            if (cancelled()) throw java.io.InterruptedIOException("Creation cancelled")
                            batch?.commit()
                            update(OperationUpdate(bytes = staged.length(), totalBytes = staged.length(), itemsProcessed = 1, totalItems = 1, currentItem = name))
                        } catch (error: Exception) {
                            if (batch == null) created?.let { entry ->
                                val cleanup = target.delete(entry.ref)
                                if (cleanup !is ProviderResult.Success) com.infinifolder.storage.DiagnosticLog.record("create.cleanup.failed", entry.metadata.name)
                            }
                            throw error
                        } finally { batch?.close() }
                    } finally {
                        staged.delete()
                        runOnUiThread { if (!isDestroyed) { current?.let(::load); refreshTreeAfterTransfer(parent) } }
                    }
                }
                enqueueOperationWindow(id)
            }
        }
        dialog.show()
    }

    private fun showOptions() {
        val selected = entries.filter { it.uri in selectedUris }
        withActionCapabilities(selected) { capabilities ->
            val selectionExit = if (selectionMode) listOf(BrowserMenu.Item("Exit selection mode", action = { exitSelectionMode() })) else emptyList()
            BrowserMenu.show(this, dialogContext(), selectionExit + browserMenuItems(capabilities))
        }
    }

    private var actionMenuRequest = 0
    private fun withActionCapabilities(items: List<ResourceEntry>, onReady: (Map<ResourceRef, ResourceCapabilities>) -> Unit) {
        val request = ++actionMenuRequest
        val generation = loadGeneration
        if (items.isEmpty()) { onReady(emptyMap()); return }
        io.execute {
            val capabilities = mutableMapOf<ResourceRef, ResourceCapabilities>()
            var failure: String? = null
            for (entry in items) {
                when (val result = provider.capabilities(entry.ref)) {
                    is ProviderResult.Success -> capabilities[entry.ref] = result.value
                    is ProviderResult.Failure -> { failure = result.message; break }
                    is ProviderResult.Unsupported -> Unit // No report: retain unknown support.
                }
            }
            runOnUiThread {
                if (request != actionMenuRequest || generation != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show() else onReady(capabilities)
            }
        }
    }

    private fun showThemeModeDialog() {
        val currentMode = com.infinifolder.theme.ThemeManager.getThemeMode(this)
        val modes = arrayOf(
            com.infinifolder.theme.ThemeMode.SYSTEM,
            com.infinifolder.theme.ThemeMode.DARK,
            com.infinifolder.theme.ThemeMode.LIGHT
        )
        val labels = arrayOf(
            "⚙️ Follow system (Automatic)",
            "🌙 Dark mode",
            "☀️ Light mode"
        )
        val selectedIdx = modes.indexOf(currentMode).coerceAtLeast(0)

        dialogBuilder()
            .setTitle("Theme mode")
            .setSingleChoiceItems(labels, selectedIdx) { dialog, which ->
                val chosenMode = modes[which]
                com.infinifolder.theme.ThemeManager.setThemeMode(this, chosenMode)
                dark = com.infinifolder.theme.ThemeManager.activeTheme.isDark
                buildUi()
                if (homeMode) showHomeRoot() else current?.let { load(it) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppThemeDialog() {
        val themes = com.infinifolder.theme.ThemeManager.listPresetThemes(dark)
        val activeTheme = com.infinifolder.theme.ThemeManager.activeTheme
        val options = themes.map {
            val mark = if (it.id == activeTheme.id) "● " else "○ "
            "$mark${it.name}"
        }.toMutableList()
        options.add("📂 Import Winamp (.wsz) Skin…")
        options.add("Skin image scaling…")

        dialogBuilder().setTitle("Interface Theme").setItems(options.toTypedArray()) { _, which ->
            if (which < themes.size) {
                com.infinifolder.theme.ThemeManager.setTheme(this, themes[which])
                buildUi()
                if (homeMode) showHomeRoot() else current?.let { load(it) }
            } else if (which == themes.size) {
                promptImportWszSkin()
            } else {
                showSkinScalingDialog()
            }
        }.show()
    }

    private fun showSkinScalingDialog() {
        val manager = com.infinifolder.theme.ThemeManager
        dialogBuilder().setTitle("Skin image scaling")
            .setSingleChoiceItems(arrayOf("Integer scaling (crisp pixels)", "Smooth scaling"),
                if (manager.smoothSkinScaling) 1 else 0) { dialog, which ->
                manager.setSmoothSkinScaling(this, which == 1)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptImportWszSkin() {
        val input = EditText(this).apply {
            hint = "/sdcard/Download/classic.wsz"
        }
        val padding = dp(20)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

        dialogBuilder()
            .setTitle("Import Winamp (.wsz) Skin")
            .setMessage("Enter the local path or URI of a Winamp .wsz / .zip skin file:")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val skinUri = if (path.startsWith("/")) Uri.fromFile(java.io.File(path)) else Uri.parse(path)
                    importWinampSkin(skinUri)
                }
            }
            .setNeutralButton("Browse…") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, pickWinampSkinRequest)
                } catch (error: Exception) {
                    Toast.makeText(this, "Cannot open file picker: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importWinampSkin(uri: Uri) {
        Toast.makeText(this, "Importing Winamp skin…", Toast.LENGTH_SHORT).show()
        Thread {
            val imported = com.infinifolder.theme.ThemeManager.importSkin(applicationContext, uri)
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this,
                    if (imported != null) "Skin '${imported.name}' applied" else "Could not import a classic Winamp skin from this file",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showIconThemeDialog() {
        val availableThemes = iconThemeManager.listAvailableThemes()
        val activeId = iconThemeManager.getActiveThemeId()

        val themeItems = mutableListOf<IconThemeInfo?>()
        themeItems.add(null) // null = default system emojis
        themeItems.addAll(availableThemes)

        var selectedIndex = themeItems.indexOfFirst {
            if (it == null) activeId.isNullOrBlank() else it.id == activeId || it.name.equals(activeId, true)
        }.coerceAtLeast(0)

        val itemLabels = themeItems.map { theme ->
            if (theme == null) {
                "Default (InfiniFolder Emojis)"
            } else {
                val origin = if (theme.isSystem) "System / Termux" else "Imported"
                "${theme.name} ($origin)"
            }
        }.toTypedArray()

        dialogBuilder()
            .setTitle("FreeDesktop Icon Themes")
            .setSingleChoiceItems(itemLabels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Apply") { _, _ ->
                val selected = themeItems.getOrNull(selectedIndex)
                iconThemeManager.setActiveTheme(selected?.id)
                thumbnailCache.evictAll()
                renderCurrentContents()
                rebuildTreeRoots()
                Toast.makeText(this, "Active icon theme: ${selected?.name ?: "Default emojis"}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Import…") { _, _ ->
                showImportThemeOptionsDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportThemeOptionsDialog() {
        val options = arrayOf(
            "Import theme archive (.tar.gz, .tar.xz, .zip)…",
            "Scan Termux & system icon themes"
        )
        dialogBuilder()
            .setTitle("Import Icon Theme")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickThemeArchiveLauncher()
                    1 -> {
                        val count = iconThemeManager.listAvailableThemes().size
                        Toast.makeText(this, "Found $count icon themes", Toast.LENGTH_SHORT).show()
                        showIconThemeDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickThemeArchiveLauncher() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, pickThemeArchiveRequest)
        } catch (_: Exception) {
            val getIn = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try { startActivityForResult(getIn, pickThemeArchiveRequest) }
            catch (e: Exception) {
                Toast.makeText(this, "No file picker available: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installIconThemeFromUri(uri: Uri) {
        val progress = ProgressDialog(this).apply {
            setMessage("Analyzing icon theme...")
            setCancelable(false)
            show()
        }
        val fileName = try {
            val docId = DocumentsContract.getDocumentId(uri)
            docId.substringAfterLast('/')
        } catch (_: Exception) {
            uri.lastPathSegment ?: "theme.tar.gz"
        }

        imageIo.execute {
            try {
                val info = iconThemeManager.importThemeFromArchive(
                    openStream = { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open input stream") },
                    archiveFileName = fileName
                ) { msg ->
                    runOnUiThread { progress.setMessage(msg) }
                }
                runOnUiThread {
                    progress.dismiss()
                    dialogBuilder()
                        .setTitle("Theme Installed")
                        .setMessage("Successfully installed icon theme '${info.name}'.\n\nWould you like to activate it now?")
                        .setPositiveButton("Activate") { _, _ ->
                            iconThemeManager.setActiveTheme(info.id)
                            thumbnailCache.evictAll()
                            renderCurrentContents()
                            rebuildTreeRoots()
                            Toast.makeText(this, "Active icon theme: ${info.name}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Keep current", null)
                        .show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progress.dismiss()
                    dialogBuilder()
                        .setTitle("Theme Import Failed")
                        .setMessage("Failed to import icon theme: ${t.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun installIconThemeFromEntry(entry: ResourceEntry) {
        val progress = ProgressDialog(this).apply {
            setMessage("Installing icon theme...")
            setCancelable(false)
            show()
        }
        imageIo.execute {
            try {
                val info = if (entry.metadata.isDirectory && localProvider.owns(entry.uri)) {
                    val folder = localProvider.fileFor(entry.uri)
                    iconThemeManager.importThemeFromDirectory(folder)
                } else {
                    iconThemeManager.importThemeFromArchive(
                        openStream = {
                            (provider.openRead(entry.ref) as? ProviderResult.Success)?.value
                                ?: throw java.io.IOException("Cannot read archive")
                        },
                        archiveFileName = entry.metadata.name
                    ) { msg ->
                        runOnUiThread { progress.setMessage(msg) }
                    }
                }
                runOnUiThread {
                    progress.dismiss()
                    dialogBuilder()
                        .setTitle("Theme Installed")
                        .setMessage("Successfully installed icon theme '${info.name}'.\n\nWould you like to activate it now?")
                        .setPositiveButton("Activate") { _, _ ->
                            iconThemeManager.setActiveTheme(info.id)
                            thumbnailCache.evictAll()
                            renderCurrentContents()
                            rebuildTreeRoots()
                            Toast.makeText(this, "Active icon theme: ${info.name}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Keep current", null)
                        .show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progress.dismiss()
                    dialogBuilder()
                        .setTitle("Theme Import Failed")
                        .setMessage("Failed to import icon theme: ${t.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
    private fun showIconSizeDialog() {
        val context = dialogContext()
        val preview = TextView(context).apply {
            text = "Icon size: ${iconSizeDp} dp"
            textSize = 16f
            setTextColor(setTextColorForTheme())
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val slider = SeekBar(context).apply {
            max = 88
            progress = iconSizeDp - 32
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                preview.text = "Icon size: ${progress + 32} dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview)
            addView(slider)
        }
        dialogBuilder().setTitle("Grid icon size").setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                iconSizeDp = slider.progress + 32
                shared.edit().putInt("icon_size_dp", iconSizeDp).apply()
                renderCurrentContents()
            }.show()
    }
    private fun showPinchIndicator(text: String) {
        val indicator = pinchIndicator ?: TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xCC1A1E19.toInt())
                setStroke(dp(1), 0x66FFFFFF.toInt())
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            elevation = dp(6).toFloat()
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            fileScrollContainer.addView(this, lp)
            pinchIndicator = this
        }
        indicator.text = text
        indicator.visibility = View.VISIBLE
        indicator.alpha = 1f
        indicator.animate().cancel()
    }

    private fun hidePinchIndicator() {
        pinchIndicator?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            pinchIndicator?.visibility = View.GONE
        }?.start()
    }
    private val operationUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingOperationSnapshots = java.util.concurrent.atomic.AtomicReference<List<OperationSnapshot>?>(null)
    private val operationUiScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var operationStatusTickScheduled = false
    private val operationStatusTick = object : Runnable {
        override fun run() {
            operationStatusTickScheduled = false
            if (isFinishing || isDestroyed) return
            val snapshots = operations.snapshots()
            operationOverview?.update(overviewSnapshots(snapshots))
            updateOperationVisibilityUi()
            snapshots.firstOrNull { it.id == activeDialogOpId }?.let { updateProgressDialogContent(it, snapshots) }
            if (snapshots.any { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }) {
                operationStatusTickScheduled = true
                operationUiHandler.postDelayed(this, 1000)
            }
        }
    }
    private val operationUiUpdate = Runnable {
        operationUiScheduled.set(false)
        if (pendingOperationSnapshots.getAndSet(null) != null) {
            // A worker can deliver a snapshot captured before a history removal.
            // Render current state rather than resurrecting removed rows.
            applyOperationSnapshots(operations.snapshots())
        }
    }
    private val operationsListener: (List<OperationSnapshot>) -> Unit = { snapshots ->
        pendingOperationSnapshots.set(snapshots)
        if (operationUiScheduled.compareAndSet(false, true)) {
            operationUiHandler.postDelayed(operationUiUpdate, 100)
        }
    }
    private fun applyOperationSnapshots(snapshots: List<OperationSnapshot>) {
            if (isFinishing || isDestroyed) return
            operationOverview?.update(overviewSnapshots(snapshots))
            if (autoHideOperationOverview && snapshots.none { !it.id.startsWith("playback_") &&
                    (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) }) {
                operationOverview?.dismiss()
                autoHideOperationOverview = false
            }
            refreshedTerminalOperations.retainAll(snapshots.map { it.id }.toSet())
            val activeList = snapshots.filter {
                (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) &&
                !it.id.startsWith("playback_")
            }
            storageOperationLoading = activeList.isNotEmpty()
            updateEntryLoadingFeedback()
            if (activeList.isNotEmpty() && !operationStatusTickScheduled) {
                operationStatusTickScheduled = true
                operationUiHandler.postDelayed(operationStatusTick, 1000)
            }
            activeList.forEach { snap ->
                if (snap.id !in operationWindowQueue) {
                    operationWindowQueue.addLast(snap.id)
                }
            }
            if (currentProgressDialog?.isShowing == true) {
                val currentId = activeDialogOpId ?: operationWindowQueue.firstOrNull()
                val target = snapshots.firstOrNull { it.id == currentId }
                    ?: operationWindowQueue.firstNotNullOfOrNull { qId -> snapshots.firstOrNull { it.id == qId } }
                    ?: activeList.firstOrNull()
                if (target != null) {
                    activeDialogOpId = target.id
                    updateProgressDialogContent(target, snapshots)
                }
            } else if (activeList.isEmpty()) {
                operationWindowQueue.clear()
                isOperationWindowHidden = false
            }
            updateOperationVisibilityUi()
    }

    private fun updateOperationVisibilityUi() {
        val summary = operations.activitySummary()
        val hasActive = summary.actions > 0
        if (::operationLight.isInitialized) operationLight.setActive(hasActive)
        if (::unhideOperationBar.isInitialized) {
            val dialogShowing = currentProgressDialog?.isShowing == true || operationOverview?.isShowing == true
            if (hasActive && !dialogShowing) {
                unhideOperationBar.update(summary)
                unhideOperationBar.visibility = View.VISIBLE
            } else {
                unhideOperationBar.visibility = View.GONE
            }
        }
    }

    private fun enqueueOperationWindow(opId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !shared.getBoolean("completion_notification_permission_requested", false)) {
            shared.edit().putBoolean("completion_notification_permission_requested", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9003)
        }
        if (opId !in operationWindowQueue) {
            operationWindowQueue.addLast(opId)
        }
        val snapshots = operations.snapshots()
        if (currentProgressDialog?.isShowing == true) {
            val shownId = activeDialogOpId ?: operationWindowQueue.firstOrNull() ?: opId
            val shownSnap = snapshots.firstOrNull { it.id == shownId }
            if (shownSnap != null) {
                updateProgressDialogContent(shownSnap, snapshots)
            }
        } else if (!isOperationWindowHidden) {
            showOperations(autoHideWhenIdle = true)
        }
        updateOperationVisibilityUi()
    }

    private fun unhideOperationWindow() { showOperations() }

    private fun operationDestinationLabel(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value
        return if (SftpUri.isSftpUri(uri) || FtpUri.isFtpUri(uri) || SmbUri.isSmbUri(uri) ||
            WebDavUri.owns(uri) || localProvider.owns(uri) || uri.scheme == "file") {
            displayPathFor(uri) ?: value
        } else value
    }

    private fun overviewSnapshots(snapshots: List<OperationSnapshot>): List<OperationSnapshot> = snapshots.map { op ->
        op.destination?.let { op.copy(destination = operationDestinationLabel(it)) } ?: op
    }

    private fun showOperations(autoHideWhenIdle: Boolean = false) {
        if (!browserResumed || isFinishing || isDestroyed) return
        val snapshots = overviewSnapshots(operations.snapshots())
        operationOverview?.takeIf { it.isShowing }?.let {
            if (!autoHideWhenIdle) autoHideOperationOverview = false
            it.update(snapshots); return
        }
        autoHideOperationOverview = autoHideWhenIdle
        currentProgressDialog?.dismiss()
        isOperationWindowHidden = true
        operationOverview = OperationOverviewDialog(dialogContext(), dialogBuilder(), setTextColorForTheme(),
            onSelect = { op ->
                operationOverview?.dismiss()
                if (op.state == OperationState.RUNNING || op.state == OperationState.QUEUED ||
                    (op.state == OperationState.FAILED && op.canRestart)) {
                    showProgressDialog(op.id)
                } else {
                    dialogBuilder().setTitle(op.displayName ?: op.label)
                        .setMessage(listOfNotNull(op.destination?.let { "To: $it" }, op.message ?: op.displayState,
                            "${op.itemsProcessed} items · ${android.text.format.Formatter.formatShortFileSize(this, op.bytes)}").joinToString("\n\n"))
                        .setPositiveButton("Activity") { _, _ -> showOperations() }
                        .apply {
                            if (op.canRemoveFromHistory) setNegativeButton("Remove from list") { _, _ ->
                                confirmOperationAction(op, right = false)
                            }
                            if (op.canRestart)
                                setNeutralButton(op.restartActionLabel) { _, _ -> confirmOperationAction(op, right = true) }
                        }.show()
                }
            }, onClearFinished = {
                confirmClearFinishedOperations()
            }, onSwipe = { op, right ->
                confirmOperationAction(op, right)
            }, onDismiss = { dismissed ->
                if (operationOverview === dismissed) {
                    operationOverview = null
                    isOperationWindowHidden = true
                    updateOperationVisibilityUi()
                }
            }
        ).also { it.update(snapshots); it.show() }
        updateOperationVisibilityUi()
    }

    private fun refreshOperationOverview() {
        operationOverview?.takeIf { it.isShowing }?.update(overviewSnapshots(operations.snapshots()))
    }

    private fun confirmClearFinishedOperations() {
        val count = operations.snapshots().count { it.canRemoveFromHistory }
        if (count == 0) return
        dialogBuilder().setTitle("Remove $count finished entries?")
            .setMessage("Remove their status records from Activity. Files and saved download progress are kept.")
            .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                operations.clearFinished()
                refreshOperationOverview()
            }.show().setOnDismissListener { refreshOperationOverview() }
    }

    private fun confirmOperationAction(previous: OperationSnapshot, right: Boolean) {
        val op = operations.snapshots().firstOrNull { it.id == previous.id } ?: return
        val active = op.state == OperationState.RUNNING || op.state == OperationState.QUEUED
        val action = when {
            active && !right -> "Cancel"
            active && op.canFinishLater -> "Pause"
            active -> {
                dialogBuilder().setTitle("Pause unavailable")
                    .setMessage("This operation does not support resuming. Swipe left to cancel it instead.")
                    .setPositiveButton("OK", null).show()
                return
            }
            !right && op.canRemoveFromHistory -> "Remove"
            right && op.canRestart -> op.restartActionLabel
            else -> return
        }
        val detail = when (action) {
            "Remove" -> "Remove this status record from Activity? Files and saved download progress are kept."
            "Pause" -> "Keep the downloaded data and saved progress. Choose Resume when you want to continue."
            "Cancel" -> if (op.canFinishLater) "Stop this download. Partial files and saved progress are kept for a manual restart."
                else "Stop this operation. Files already processed are kept; unfinished output follows the operation's normal cleanup."
            else -> if (op.resumeDestination != null) "Continue using saved download progress."
                else "Start this transfer again after checking the sources. Completed copies may be copied again with new names; existing destination files are kept."
        }
        dialogBuilder().setTitle("$action ${op.displayName ?: op.label}?")
            .setMessage(detail).setNegativeButton("Keep", null)
            .setPositiveButton(action) { _, _ ->
                val currentOp = operations.snapshots().firstOrNull { it.id == op.id } ?: return@setPositiveButton
                when (action) {
                    "Remove" -> if (currentOp.canRemoveFromHistory) {
                        operations.clearFinished(currentOp.id)
                        if (activeDialogOpId == currentOp.id) {
                            currentProgressDialog?.dismiss()
                            showOperations()
                        } else refreshOperationOverview()
                    }
                    "Pause", "Cancel" -> if (currentOp.state in setOf(OperationState.RUNNING, OperationState.QUEUED)) {
                        if (!stoppingOperations.add(currentOp.id)) return@setPositiveButton
                        io.execute {
                            val saved = runCatching {
                                currentOp.resumeDestination?.let { destination ->
                                    (provider as? RoutingStorageProvider)?.setTorrentAutoResume(destination, false)
                                }
                            }
                            operations.cancel(currentOp.id, "activity-${action.lowercase()}", pause = action == "Pause")
                            runOnUiThread {
                                stoppingOperations.remove(currentOp.id)
                                if (!isFinishing && !isDestroyed) saved.exceptionOrNull()?.let { error ->
                                    com.infinifolder.storage.DiagnosticLog.record("torrent.stop.preference.failed", error = error)
                                    dialogBuilder().setTitle("Automatic resume setting failed")
                                        .setMessage("The transfer was stopped, but its automatic resume setting could not be saved: ${error.message}")
                                        .setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }
                    else -> if (currentOp.canRestart) {
                        if (currentOp.resumeDestination != null) retryTorrentOperation(currentOp)
                        else restartTransferOperation(currentOp)
                    }
                }
            }.show().setOnDismissListener { refreshOperationOverview() }
    }

    private fun restartTransferOperation(op: OperationSnapshot) {
        val request = op.restartTransfer ?: return
        if (!restartingOperations.add(op.id)) return
        io.execute {
            val missing = mutableListOf<String>()
            val refreshed = runCatching {
                request.entries.mapNotNull { entry ->
                    when (val result = provider.stat(entry.ref)) {
                        is ProviderResult.Success -> result.value
                        is ProviderResult.Unsupported -> entry
                        is ProviderResult.Failure -> {
                            val absent = result.cause?.let { error -> generateSequence(error) { it.cause }.take(8).any {
                                it is java.io.FileNotFoundException || it is java.nio.file.NoSuchFileException
                            } } == true
                            if (absent) { missing += "${entry.metadata.name}: ${result.message}"; null }
                            else throw java.io.IOException("${entry.metadata.name}: ${result.message}", result.cause)
                        }
                    }
                }
            }
            runOnUiThread {
                restartingOperations.remove(op.id)
                if (isFinishing || isDestroyed || operations.snapshots().none { it.id == op.id && it.canRestart }) return@runOnUiThread
                refreshed.fold(onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        dialogBuilder().setTitle("Nothing to restart")
                            .setMessage("The original source entries are no longer available. Already moved files are kept at their destinations.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        val restart = {
                            currentProgressDialog?.dismiss()
                            startBatchTransfer(entries, request.move, Uri.parse(request.destination),
                                createLinks = request.createLinks, partialRecovery = request.partialRecovery)
                        }
                        if (missing.isEmpty()) restart()
                        else dialogBuilder().setTitle("Some sources are no longer available")
                            .setMessage("These sources are unavailable or may already have moved. They will be skipped:\n\n" +
                                missing.take(20).joinToString("\n") + "\n\nRestart with ${entries.size} remaining entries?")
                            .setNegativeButton("Keep", null).setPositiveButton("Restart") { _, _ -> restart() }.show()
                    }
                }, onFailure = { error ->
                    dialogBuilder().setTitle("Cannot restart transfer").setMessage(error.message ?: "Could not check sources")
                        .setPositiveButton("OK", null).show()
                })
            }
        }
    }

    private fun retryTorrentOperation(op: OperationSnapshot) {
        val destination = op.resumeDestination ?: return
        if (!retryingTorrentDestinations.add(destination)) return
        progressRetryButton?.isEnabled = false
        io.execute {
            val result = runCatching { (provider as? RoutingStorageProvider)?.resumePendingTorrentTransfers(destination) ?: 0 }
            runOnUiThread {
                retryingTorrentDestinations.remove(destination)
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressRetryButton?.isEnabled = true
                if (result.getOrDefault(0) > 0) {
                    com.infinifolder.storage.DiagnosticLog.record("torrent.retry.requested", "previousOperation=${op.id}")
                    currentProgressDialog?.dismiss()
                    OperationService.start(this)
                    showOperations()
                } else {
                    Toast.makeText(this, result.exceptionOrNull()?.message ?: "No saved torrent session remains for this file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showProgressDialog(targetOpId: String? = null) {
        if (!browserResumed || isFinishing || isDestroyed) return
        val snapshots = operations.snapshots()
        val activeList = snapshots.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        val active = when {
            targetOpId != null -> snapshots.firstOrNull { it.id == targetOpId }
            currentProgressDialog?.isShowing == true && activeDialogOpId != null ->
                snapshots.firstOrNull { it.id == activeDialogOpId }
            operationWindowQueue.isNotEmpty() ->
                operationWindowQueue.firstNotNullOfOrNull { qId -> snapshots.firstOrNull { it.id == qId } }
            else ->
                activeList.firstOrNull() ?: snapshots.lastOrNull()
        }
        if (active == null) {
            Toast.makeText(this, "No operations running", Toast.LENGTH_SHORT).show()
            return
        }

        isOperationWindowHidden = false
        activeDialogOpId = active.id
        if (active.id !in operationWindowQueue && (active.state == OperationState.RUNNING || active.state == OperationState.QUEUED)) {
            operationWindowQueue.addLast(active.id)
        }

        if (currentProgressDialog?.isShowing == true) {
            updateProgressDialogContent(active, snapshots)
            updateOperationVisibilityUi()
            return
        }

        val context = dialogContext()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }

        val titleView = TextView(context).apply {
            text = active.label
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(setTextColorForTheme())
            setLines(2)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(2))
        }
        progressTitleView = titleView

        val queueView = TextView(context).apply {
            textSize = 12f
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
            setPadding(0, 0, 0, dp(4))
            visibility = View.INVISIBLE
        }
        progressQueueView = queueView

        val statusLabel = TextView(context).apply {
            text = "Status"
            textSize = 12f
            setTextColor(setTextColorForTheme())
            setPadding(0, 0, 0, dp(3))
        }
        val currentItemView = TextView(context).apply {
            text = active.message ?: if (active.state == OperationState.QUEUED) "Waiting in queue…" else "Starting…"
            textSize = 13f
            setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt())
            setLines(3)
            gravity = Gravity.TOP
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(12))
        }
        progressStatusView = currentItemView
        // Keep status and filename in separate, fixed areas so progress updates cannot swap them.
        // Let TextView measure its three reserved lines, including font metrics and
        // padding. A separately calculated ScrollView height can clip the last line.
        currentItemView.layoutParams = LinearLayout.LayoutParams(-1, -2)


        val itemNameView = TextView(context).apply {
            text = "Current item: ${active.currentItem ?: "—"}"
            textSize = 12f
            setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt())
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(12))
        }
        progressItemNameView = itemNameView

        val fileLabel = TextView(context).apply {
            text = "Current file progress"
            textSize = 12f
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(setTextColorForTheme())
            setPadding(0, 0, 0, dp(4))
        }
        progressFileLabel = fileLabel
        val fileBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            contentDescription = "Current file progress"
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { bottomMargin = dp(12) }
        }
        progressFileBar = fileBar
        val overallLabel = TextView(context).apply {
            text = "Overall"
            textSize = 12f
            setLines(1)
            setTextColor(setTextColorForTheme())
            setPadding(0, 0, 0, dp(4))
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            contentDescription = "Overall operation progress"
            val pct = active.overallProgressPercent
            if (pct >= 0) {
                isIndeterminate = false
                progress = pct
            } else {
                isIndeterminate = true
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply {
                bottomMargin = dp(8)
            }
        }
        progressBar = bar

        val statsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(0, 0, 0, dp(16))
        }
        val bytesView = TextView(context).apply {
            text = formatProgressSnapshot(active)
            textSize = 12f
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        progressBytesView = bytesView

        val rateView = TextView(context).apply {
            text = active.formatEta()
            textSize = 12f
            setLines(2)
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(setTextColorForTheme())
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        progressRateView = rateView

        statsLayout.addView(bytesView)
        statsLayout.addView(rateView)

        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.END
        }

        val hideBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
            text = "Activity"
            setTextColor(setTextColorForTheme())
            setOnClickListener { showOperations() }
        }

        progressHideButton = hideBtn

        val cancelBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = if (active.canRemoveFromHistory) "Remove" else active.stopActionLabel
            setTextColor(0xFFD32F2F.toInt())
            visibility = View.VISIBLE
            setOnClickListener {
                operations.snapshots().firstOrNull { it.id == activeDialogOpId }?.let { op ->
                    confirmOperationAction(op, right = !op.canRemoveFromHistory && op.canFinishLater)
                }
            }
        }
        progressCancelButton = cancelBtn

        buttonsLayout.addView(hideBtn)
        progressRetryButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            text = active.restartActionLabel; setTextColor(setTextColorForTheme())
            visibility = if (active.canRestart) View.VISIBLE else View.GONE
            setOnClickListener {
                operations.snapshots().firstOrNull { it.id == activeDialogOpId }?.let { confirmOperationAction(it, right = true) }
            }
            buttonsLayout.addView(this)
        }
        buttonsLayout.addView(cancelBtn)

        body.addView(titleView)
        progressDestinationView = TextView(context).apply {
            textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(setTextColorForTheme())
            body.addView(this)
        }
        body.addView(queueView)
        body.addView(statusLabel)
        body.addView(currentItemView)
        body.addView(itemNameView)
        body.addView(fileLabel)
        body.addView(fileBar)
        body.addView(overallLabel)
        body.addView(bar)
        body.addView(statsLayout)
        progressTorrentStatsView = TextView(context).apply {
            textSize = 12f; setLines(3); setTextColor(setTextColorForTheme())
            body.addView(this)
        }
        progressTorrentWaitCheck = CheckBox(context).apply {
            text = "Wait indefinitely for peers (all torrents)"
            setLines(2); setTextColor(setTextColorForTheme())
            isChecked = torrentTransferPreferences.getBoolean(com.infinifolder.torrent.TorrentTransferPreferences.WAIT_FOREVER, false)
            setOnCheckedChangeListener { _, checked ->
                val key = com.infinifolder.torrent.TorrentTransferPreferences.WAIT_FOREVER
                if (torrentTransferPreferences.getBoolean(key, false) != checked) {
                    torrentTransferPreferences.edit().putBoolean(key, checked).apply()
                    com.infinifolder.storage.DiagnosticLog.record("torrent.wait.preference", "indefinite=$checked")
                }
            }
            body.addView(this)
        }
        progressVerificationCheck = CheckBox(context).apply {
            text = "Verify copied data"; setTextColor(setTextColorForTheme())
            minLines = 2; maxLines = 2
            isChecked = shared.getBoolean("verify_copies", true)
            setOnCheckedChangeListener { button, checked ->
                if (button.isEnabled) {
                    shared.edit().putBoolean("verify_copies", checked).apply()
                    com.infinifolder.storage.DiagnosticLog.record("transfer.verification.preference", "enabled=$checked")
                }
            }
            body.addView(this)
        }
        body.addView(buttonsLayout)

        val dialog = dialogBuilder()
            .setView(body)
            .setCancelable(true)
            .create()

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (currentProgressDialog !== dialog) return@setOnDismissListener
            currentProgressDialog = null
            activeDialogOpId = null
            progressTitleView = null
            progressDestinationView = null
            progressQueueView = null
            progressStatusView = null
            progressItemNameView = null
            progressBar = null
            progressFileBar = null
            progressFileLabel = null
            progressBytesView = null
            progressVerificationCheck = null
            progressRateView = null
            progressTorrentStatsView = null
            progressTorrentWaitCheck = null
            progressRetryButton = null
            progressCancelButton = null
            progressHideButton = null
            val latestSnapshots = operations.snapshots()
            if (latestSnapshots.any { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }) {
                isOperationWindowHidden = true
            }
            updateOperationVisibilityUi()
        }
        currentProgressDialog = dialog
        dialog.show()
        dialog.window?.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            decorView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    isOperationWindowHidden = true
                    com.infinifolder.storage.DiagnosticLog.record("operation.dialog.hidden", "reason=outside-touch")
                    dialog.dismiss()
                    true
                } else false
            }
        }
        updateProgressDialogContent(active, snapshots)
        val availableWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        dialog.window?.setLayout(minOf(dp(480), (availableWidth - dp(32)).coerceAtLeast(1)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        updateOperationVisibilityUi()
    }

    private fun updateProgressDialogContent(op: OperationSnapshot, allSnapshots: List<OperationSnapshot> = operations.snapshots()) {
        currentProgressDialog?.window?.let { window ->
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = 0f }
        }
        val activeList = allSnapshots.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        val waitingAfterCurrent = operationWindowQueue.count { id ->
            id != op.id && allSnapshots.any { it.id == id && (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) }
        }
        if (waitingAfterCurrent > 0) {
            progressQueueView?.text = "${activeList.count { it.state == OperationState.RUNNING }} running · ${activeList.count { it.state == OperationState.QUEUED }} waiting"
            progressQueueView?.setOnClickListener { showOperations() }
            progressQueueView?.visibility = View.VISIBLE
        } else {
            progressQueueView?.visibility = View.INVISIBLE
        }

        if (op.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)) {
            val firstTerminalUpdate = refreshedTerminalOperations.add(op.id)
            when (op.state) {
                OperationState.COMPLETE -> {
                    if (firstTerminalUpdate) {
                    directoryIconCache.evictAll()
                    refreshInternalSharedTree()
                    if (homeMode && homePage == "root") showHomeRoot() else current?.let { load(it) }
                    }
                }
                OperationState.CANCELLED -> {
                    if (firstTerminalUpdate) current?.let { load(it) }
                }
                else -> Unit
            }
            // If this operation completed or was cancelled and there is another operation queued,
            // advance the storage operation window directly to the next queued operation!
            if (op.state != OperationState.FAILED) {
                operationWindowQueue.remove(op.id)
                val nextId = operationWindowQueue.firstOrNull()
                val nextSnap = nextId?.let { id -> allSnapshots.firstOrNull { it.id == id && (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) } }
                    ?: activeList.firstOrNull { it.id != op.id }
                if (nextSnap != null) {
                    currentProgressDialog?.dismiss()
                    showOperations()
                    return
                }
                currentProgressDialog?.dismiss()
                return
            }
        }

        progressRetryButton?.apply {
            visibility = if (op.canRestart) View.VISIBLE else View.GONE
            text = op.restartActionLabel
            isEnabled = op.resumeDestination !in retryingTorrentDestinations && op.id !in restartingOperations
        }
        progressTorrentWaitCheck?.apply {
            visibility = if (op.canFinishLater || op.resumeDestination != null || op.torrentStats != null) View.VISIBLE else View.GONE
            isChecked = torrentTransferPreferences.getBoolean(com.infinifolder.torrent.TorrentTransferPreferences.WAIT_FOREVER, false)
        }
        progressVerificationCheck?.apply {
            val copy = op.label.startsWith("Copy ")
            val required = op.label.startsWith("Move ") || op.label.startsWith("Recover ")
            visibility = if (copy || required) View.VISIBLE else View.INVISIBLE
            isEnabled = copy && (op.state == OperationState.QUEUED || op.state == OperationState.RUNNING)
            isChecked = required || shared.getBoolean("verify_copies", true)
            text = if (required) "Verification required for moves / recovery" else "Verify copied data (remembered)"
        }
        progressTitleView?.text = op.displayName ?: op.label
        progressDestinationView?.text = op.destination?.let { "To: ${operationDestinationLabel(it)}" }.orEmpty()
        progressDestinationView?.visibility = if (op.destination == null) View.GONE else View.VISIBLE
        progressCancelButton?.text = op.stopActionLabel
        progressStatusView?.text = op.message ?: if (op.state == OperationState.QUEUED) "Waiting in queue…" else "Starting…"
        progressItemNameView?.text = "Current item: ${op.currentItem ?: "—"}"
        val checkedPieces = op.torrentPiecesChecked
        val totalPieces = op.torrentPiecesTotal
        val checkingPieces = checkedPieces != null && totalPieces != null
        val filePct = if (checkingPieces && totalPieces!! > 0)
            (checkedPieces!!.toDouble() / totalPieces * 100).toInt().coerceIn(0, 100)
        else if (checkingPieces) -1 else op.currentItemProgressPercent
        progressFileBar?.isIndeterminate = filePct < 0
        if (filePct >= 0) progressFileBar?.progress = filePct
        val fileBytes = op.currentItemBytes
        val restoringCache = checkingPieces && op.message?.startsWith("Restoring saved progress") == true
        progressFileBar?.contentDescription = if (restoringCache) "Restoring torrent progress" else if (checkingPieces) "Torrent verification progress" else "Current file progress"
        progressFileLabel?.text = if (checkingPieces) {
            "${if (restoringCache) "Resume records" else "Verification"}: $checkedPieces / $totalPieces" + if (filePct >= 0) " ($filePct%)" else ""
        } else if (fileBytes != null) {
            val done = android.text.format.Formatter.formatShortFileSize(this, fileBytes)
            val total = op.currentItemTotalBytes?.let { android.text.format.Formatter.formatShortFileSize(this, it) }
            "${if (op.message?.startsWith("Verifying") == true) "Verification" else "Current file"}: $done" + (total?.let { " / $it" } ?: "") + (if (filePct >= 0) " ($filePct%)" else "")
        } else ""
        val pct = op.overallProgressPercent
        val awaitingDownloadTotals = checkingPieces && op.totalBytes == null
        if (pct >= 0 && !awaitingDownloadTotals) {
            progressBar?.isIndeterminate = false
            progressBar?.progress = pct
        } else {
            progressBar?.isIndeterminate = true
        }
        progressBytesView?.text = formatProgressSnapshot(op)
        val idleSeconds = ((System.currentTimeMillis() - op.lastUpdateMillis) / 1000).coerceAtLeast(0)
        val elapsedSeconds = ((System.currentTimeMillis() - op.startTimeMillis) / 1000).coerceAtLeast(0)
        val rateAndEta = listOf(op.formatRate().takeIf { idleSeconds < 3 }.orEmpty(), op.formatEta())
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val elapsedAndWait = listOf("Elapsed ${elapsedSeconds / 60}m ${elapsedSeconds % 60}s",
            if (idleSeconds >= 3 && op.state == OperationState.RUNNING) "Waiting for I/O (${idleSeconds}s)" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        progressRateView?.text = "$rateAndEta\n$elapsedAndWait"
        progressTorrentStatsView?.apply {
            val torrent = op.torrentStats
            visibility = if (torrent != null || op.canFinishLater) View.VISIBLE else View.GONE
            if (torrent == null) text = "Connected peers: —\nServing (last 10s): —\nUpload: 0 B/s"
            else {
                val uploadRate = if (op.state == OperationState.RUNNING && idleSeconds < 5)
                    torrent.uploadBytesPerSecond else 0L
                val rateText = android.text.format.Formatter.formatShortFileSize(this@MainActivity, uploadRate) + "/s"
                val uploaded = android.text.format.Formatter.formatShortFileSize(this@MainActivity, torrent.uploadedBytes)
                val serving = if (op.state == OperationState.RUNNING) torrent.servingPeers else 0
                text = "Connected peers: ${if (op.state == OperationState.RUNNING) torrent.connectedPeers else 0}\n" +
                    "Serving (last 10s): $serving\nUpload: $rateText · Uploaded: $uploaded"
            }
        }

        if (op.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)) {
            progressCancelButton?.apply {
                visibility = View.VISIBLE
                text = "Remove"
                isEnabled = op.canRemoveFromHistory
            }
            progressHideButton?.text = "Activity"
            when (op.state) {
                OperationState.COMPLETE -> {
                    progressTitleView?.text = "${op.label} complete"
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = 100
                    progressFileBar?.isIndeterminate = false
                    progressFileBar?.progress = 100
                }
                OperationState.CANCELLED -> {
                    progressTitleView?.text = "${op.label} ${if (op.pausedByUser) "paused" else "cancelled"}"
                }
                OperationState.FAILED -> {
                    progressTitleView?.text = "${op.label} failed"
                        progressStatusView?.apply {
                        text = op.message ?: "Operation failed"
                        maxLines = Int.MAX_VALUE
                        ellipsize = null
                        setTextIsSelectable(true)
                    }
                }
                else -> Unit
            }
        } else {
            progressStatusView?.apply {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextIsSelectable(false)
            }
            progressCancelButton?.visibility = View.VISIBLE
            progressCancelButton?.isEnabled = op.id !in stoppingOperations
            progressCancelButton?.text = if (op.id in stoppingOperations) "Stopping…" else op.stopActionLabel
            progressHideButton?.text = "Activity"
        }
    }

    private fun formatProgressSnapshot(op: OperationSnapshot): String = when {
        op.torrentPiecesTotal != null && op.totalBytes == null -> "Download progress follows verification"
        (op.totalBytes ?: 0L) > 0 -> {
            val cur = android.text.format.Formatter.formatShortFileSize(this, op.bytes)
            val total = android.text.format.Formatter.formatShortFileSize(this, op.totalBytes ?: 0L)
            val pct = op.overallProgressPercent
            "$cur / $total ($pct%)"
        }
        op.totalItems > 0 -> {
            val pct = op.overallProgressPercent
            "${op.itemsProcessed} / ${op.totalItems} items ($pct%)"
        }
        op.bytes > 0 -> android.text.format.Formatter.formatShortFileSize(this, op.bytes)
        op.indeterminate -> "Working…"
        else -> op.message ?: ""
    }
    private fun openDiskUsageExplorer(entry: ResourceEntry) {
        if (entry.metadata.isSymbolicLink) {
            Toast.makeText(this, "Browse the link target before scanning its disk usage", Toast.LENGTH_LONG).show()
            return
        }
        if (!entry.metadata.isDirectory) {
            Toast.makeText(this, "Disk usage scans folders; archives and disk images are counted as individual files", Toast.LENGTH_LONG).show()
            return
        }
        val folder = entry
        diskUsageExplorer?.close()
        diskUsageExplorer = DiskUsageExplorer(this, dialogContext(), provider, folder,
            browse = { selected ->
                setTreeVisible(false)
                navigate(selected.uri, displayName = selected.metadata.name)
            },
            move = { selected -> showBatchDestinationPicker(listOf(selected), true) },
            delete = { selected, finished -> confirmDelete(selected, finished) })
        diskUsageExplorer?.show()
    }

    private fun showPropertiesDialog(entry: ResourceEntry) {
        showEntriesPropertiesDialog(listOf(entry), entry.metadata.name)
    }

    private fun showEntriesPropertiesDialog(items: List<ResourceEntry>, title: String? = null) {
        val context = dialogContext()
        val isSingle = items.size == 1
        var currentEntry = items.firstOrNull() ?: return
        val isShortcut = isSingle && ShortcutInfo.isShortcut(currentEntry.metadata.name, currentEntry.metadata.mimeType)
        var shortcut = if (isShortcut) shortcutCache.get(currentEntry.uri.toString()) else null
        val isDirectory = isSingle && !isShortcut && currentEntry.metadata.isDirectory
        val cachedMagicForProps = ambiguousMagicCache.get(currentEntry.uri.toString())
        val effectiveMimeForProps = cachedMagicForProps?.mimeType ?: currentEntry.metadata.mimeType
        val isPackagedFormat = isSingle && !AppsUri.isAppUri(currentEntry.uri) && ArchiveFormats.isPackagedFormat(currentEntry.metadata.name, effectiveMimeForProps)
        val isArchive = isSingle && !AppsUri.isAppUri(currentEntry.uri) && (
            ArchiveFormats.isArchive(currentEntry.metadata.name, effectiveMimeForProps) ||
            (cachedMagicForProps != null && (cachedMagicForProps.isArchiveOrDiscImage || ArchiveFormats.isDiscImage(currentEntry.metadata.name, cachedMagicForProps.mimeType)))
        )
        val inArchive = isSingle && ArchiveUri.isArchiveUri(currentEntry.uri)
        val inReadOnlyArchive = inArchive && !archiveProvider.cachedWritable(currentEntry.uri)
        val isFile = isSingle && !isShortcut && !currentEntry.metadata.isDirectory && !AppsUri.isAppsUri(currentEntry.uri)
        val fileMimeKey = if (isFile) CustomIconStore.resolveMimeKey(currentEntry.metadata) else null

        val targetFolderUri: Uri? = if (isDirectory && !inReadOnlyArchive && !AppsUri.isAppsUri(currentEntry.uri)) currentEntry.uri else null
        val canChangeFolderIcon = targetFolderUri != null
        val canChangeShortcutIcon = isShortcut && !inReadOnlyArchive
        val canChangeFileMimeIcon = isFile && fileMimeKey != null
        val canChangeIcon = canChangeFolderIcon || canChangeShortcutIcon || canChangeFileMimeIcon
        val canRename = isSingle &&
            !inArchive &&
            !AppsUri.isAppsUri(currentEntry.uri) &&
            currentEntry.uri != localProvider.rootUri

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        fun propertyRow(label: String, valueView: View) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(6))
            }
            val lbl = TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            }
            row.addView(lbl)
            row.addView(valueView)
            layout.addView(row)
        }

        fun textValue(initialText: String): TextView = TextView(context).apply {
            text = initialText
            textSize = 14f
            setTextColor(setTextColorForTheme())
            setTextIsSelectable(true)
        }

        var isCancelled = false

        // Top Header: Current Icon in Top-Left + Name in Larger Font Next to It
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
        }

        val iconBoxPx = dp(64)
        val iconBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconBoxPx, iconBoxPx)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) 0xFF252A24.toInt() else 0xFFEAECE7.toInt())
                if (canChangeIcon) {
                    setStroke(dp(1), if (dark) 0xFF4E5B49.toInt() else 0xFFB8C2B3.toInt())
                }
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipChildren = false
            clipToPadding = false
            isClickable = isSingle
            isFocusable = isSingle
        }

        val fallbackEmoji = when {
            !isSingle -> "📚"
            AppsUri.isAppUri(currentEntry.uri) -> "📱"
            isShortcut && shortcut != null && WebMetadataResolver.isWebUri(shortcut?.targetUri) -> "🌐"
            isShortcut && shortcut != null && runCatching { AppsUri.isAppUri(Uri.parse(shortcut?.targetUri)) }.getOrDefault(false) -> "📱"
            AppsUri.isCategoryUri(currentEntry.uri) -> appsProvider.getCategoryIcon(AppsUri.getCategoryName(currentEntry.uri) ?: currentEntry.metadata.name)
            isDirectory -> "📁"
            isPackagedFormat -> iconFor(currentEntry.metadata)
            isArchive -> "📦"
            else -> iconFor(currentEntry.metadata)
        }

        val iconPlaceholder = TextView(context).apply {
            text = fallbackEmoji
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }

        val iconImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }

        iconBox.addView(iconPlaceholder)
        iconBox.addView(iconImage)

        fun loadHeaderIcon() {
            if (!isSingle) return
            val decodePx = dp(64)
            ResourceArtworkCache.peek(currentEntry, decodePx)?.let { cachedBmp ->
                iconImage.setImageBitmap(cachedBmp)
                iconPlaceholder.visibility = View.GONE
                return
            }
            if (ResourceIconSidecars.find(currentEntry) != null) {
                imageIo.execute {
                    if (isCancelled || isFinishing || isDestroyed) return@execute
                    val bmp = resourceIconResolver.cached(currentEntry, decodePx)?.bitmap
                        ?: resourceIconResolver.resolve(currentEntry, decodePx).bitmap
                    if (bmp != null) {
                        runOnUiThread {
                            if (!isCancelled) {
                                iconImage.setImageBitmap(bmp)
                                iconPlaceholder.visibility = View.GONE
                            }
                        }
                    }
                }
                return
            }
            if (currentEntry.metadata.name.endsWith(".apk", ignoreCase = true)) {
                if (localProvider.owns(currentEntry.uri)) {
                    val localFile = localProvider.fileFor(currentEntry.uri)
                    val apkIcon = appsProvider.getApkFileIconBitmap(localFile.absolutePath, decodePx)
                    if (apkIcon != null) {
                        iconImage.setImageBitmap(apkIcon)
                        iconPlaceholder.visibility = View.GONE
                        return
                    }
                } else {
                    imageIo.execute {
                        if (isCancelled || isFinishing || isDestroyed) return@execute
                        val resolved = resourceIconResolver.resolve(currentEntry, decodePx)
                        val apkIcon = resolved.bitmap
                        if (apkIcon != null) {
                            resourceIconResolver.cacheDisplayed(currentEntry, decodePx, resolved)
                            runOnUiThread {
                                if (!isCancelled) {
                                    iconImage.setImageBitmap(apkIcon)
                                    iconPlaceholder.visibility = View.GONE
                                }
                            }
                        }
                    }
                    return
                }
            }
            if (isShortcut) {
                val cachedInfo = shortcutCache.get(currentEntry.uri.toString())
                if (cachedInfo != null && !cachedInfo.iconBase64.isNullOrBlank()) {
                    val syncBmp = CustomIconStore.decodeBase64Bitmap(cachedInfo.iconBase64)
                    if (syncBmp != null) {
                        shortcut = cachedInfo
                        iconImage.setImageBitmap(syncBmp)
                        iconPlaceholder.visibility = View.GONE
                        return
                    }
                }
                imageIo.execute {
                    if (isCancelled || isFinishing || isDestroyed) return@execute
                    val info = shortcutCache.get(currentEntry.uri.toString()) ?: readShortcutInfo(currentEntry)
                    shortcut = info
                    var bmp: Bitmap? = null
                    if (info != null) {
                        val tUri = runCatching { Uri.parse(info.targetUri) }.getOrNull()
                        when {
                            !info.iconBase64.isNullOrBlank() -> {
                                bmp = CustomIconStore.decodeBase64Bitmap(info.iconBase64)
                            }
                            tUri != null && AppsUri.isAppUri(tUri) -> {
                                val pkg = AppsUri.getPackageName(tUri)
                                if (!pkg.isNullOrEmpty()) bmp = appsProvider.getAppIconBitmap(pkg, decodePx)
                            }
                            info.targetIsDirectory && tUri != null && !AppsUri.isAppsUri(tUri) -> {
                                val iconUri = findDirectoryIcon(tUri)
                                if (iconUri != null) bmp = thumbnailDecoder.decode(iconUri, decodePx)
                            }
                            !WebMetadataResolver.isWebUri(info.targetUri) -> {
                                val targetName = info.targetPath ?: tUri?.lastPathSegment ?: currentEntry.metadata.name
                                if (tUri != null && !AppsUri.isAppsUri(tUri) &&
                                    (ArchiveUri.isArchiveUri(tUri) || ArchiveFormats.isDefaultBrowsableArchive(targetName, info.targetMimeType ?: ""))
                                ) {
                                    val iconUri = findDirectoryIcon(tUri)
                                    if (iconUri != null) bmp = thumbnailDecoder.decode(iconUri, decodePx)
                                }
                                if (bmp == null) {
                                    val targetMeta = ResourceMetadata(targetName, info.targetMimeType ?: "", null, null, false)
                                    val mKey = CustomIconStore.resolveMimeKey(targetMeta)
                                    bmp = customIconStore.getMimeIconBitmap(mKey) { f ->
                                        thumbnailDecoder.decode(Uri.fromFile(f), decodePx)
                                    }
                                }
                            }
                        }
                    }
                    runOnUiThread {
                        if (!isCancelled) {
                            if (bmp != null) {
                                iconImage.setImageBitmap(bmp)
                                iconPlaceholder.visibility = View.GONE
                            } else {
                                iconImage.setImageDrawable(null)
                                iconPlaceholder.text = when {
                                    info != null && WebMetadataResolver.isWebUri(info.targetUri) -> "🌐"
                                    info != null && info.targetIsDirectory -> "📁"
                                    info != null && runCatching { AppsUri.isAppUri(Uri.parse(info.targetUri)) }.getOrDefault(false) -> "📱"
                                    else -> fallbackEmoji
                                }
                                iconPlaceholder.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                return
            }
            if (AppsUri.isAppUri(currentEntry.uri)) {
                val appPkg = AppsUri.getPackageName(currentEntry.uri)
                if (!appPkg.isNullOrEmpty()) {
                    val cached = appsProvider.getCachedAppIconBitmap(appPkg, decodePx)
                    if (cached != null) {
                        iconImage.setImageBitmap(cached)
                        iconPlaceholder.visibility = View.GONE
                        return
                    }
                    imageIo.execute {
                        if (isCancelled || isFinishing || isDestroyed) return@execute
                        val bmp = appsProvider.getAppIconBitmap(appPkg, decodePx)
                        runOnUiThread {
                            if (!isCancelled && bmp != null) {
                                iconImage.setImageBitmap(bmp)
                                iconPlaceholder.visibility = View.GONE
                            }
                        }
                    }
                }
                return
            }
            if ((isDirectory || (isArchive && !isPackagedFormat)) && !AppsUri.isAppsUri(currentEntry.uri)) {
                val containerUri = if (isDirectory) currentEntry.uri else ArchiveUri.toArchiveUri(currentEntry.uri)
                val dirRef = runCatching { resourceRef(containerUri) }.getOrNull()
                val dirKey = dirRef?.let { "${it.providerId}:${it.documentId}" }
                val cachedIconUri = (dirKey?.let { directoryIconCache.get(it) } ?: directoryIconCache.get("tree:$containerUri"))
                    ?.takeIf { it.isNotEmpty() }
                if (cachedIconUri != null && dirKey != null) {
                    val cachedBmp = thumbnailCache.get("dir-icon:$dirKey:$cachedIconUri:$decodePx")
                        ?: thumbnailCache.get("dir-icon:$dirKey:$cachedIconUri")
                    if (cachedBmp != null) {
                        iconImage.setImageBitmap(cachedBmp)
                        iconPlaceholder.visibility = View.GONE
                        return
                    }
                }
                imageIo.execute {
                    if (isCancelled || isFinishing || isDestroyed) return@execute
                    val iconUri = findDirectoryIcon(containerUri)
                    var bmp = if (iconUri != null) thumbnailDecoder.decode(iconUri, decodePx) else null
                    if (bmp == null && !isDirectory && fileMimeKey != null && customIconStore.hasMimeIcon(fileMimeKey)) {
                        bmp = customIconStore.getMimeIconBitmap(fileMimeKey) { f ->
                            thumbnailDecoder.decode(Uri.fromFile(f), decodePx)
                        }
                    }
                    runOnUiThread {
                        if (!isCancelled) {
                            if (bmp != null) {
                                iconImage.setImageBitmap(bmp)
                                iconPlaceholder.visibility = View.GONE
                            } else {
                                iconImage.setImageDrawable(null)
                                iconPlaceholder.visibility = View.VISIBLE
                            }
                        }
                    }
                }
                return
            }
            if (isFile && fileMimeKey != null) {
                val cachedBmp = customIconStore.getCachedMimeBitmap(fileMimeKey)
                if (cachedBmp != null) {
                    iconImage.setImageBitmap(cachedBmp)
                    iconPlaceholder.visibility = View.GONE
                    return
                }
                if (customIconStore.hasMimeIcon(fileMimeKey)) {
                    imageIo.execute {
                        if (isCancelled || isFinishing || isDestroyed) return@execute
                        val bmp = customIconStore.getMimeIconBitmap(fileMimeKey) { f ->
                            thumbnailDecoder.decode(Uri.fromFile(f), decodePx)
                        }
                        runOnUiThread {
                            if (!isCancelled && bmp != null) {
                                iconImage.setImageBitmap(bmp)
                                iconPlaceholder.visibility = View.GONE
                            }
                        }
                    }
                    return
                }
            }
            if (ImageFormats.isImage(currentEntry.metadata)) {
                imageIo.execute {
                    if (isCancelled || isFinishing || isDestroyed) return@execute
                    val bmp = thumbnailDecoder.decode(currentEntry.uri, decodePx)
                    runOnUiThread {
                        if (!isCancelled && bmp != null) {
                            iconImage.setImageBitmap(bmp)
                            iconPlaceholder.visibility = View.GONE
                        }
                    }
                }
            }
        }
        loadHeaderIcon()

        iconBox.setOnClickListener {
            when {
                canChangeFolderIcon && targetFolderUri != null -> promptSelectFolderIcon(targetFolderUri)
                canChangeShortcutIcon -> promptSelectShortcutIcon(currentEntry.uri)
                canChangeFileMimeIcon && fileMimeKey != null -> promptSelectMimeTypeIcon(fileMimeKey)
                isSingle -> Toast.makeText(this, "Cannot change icon for this item.", Toast.LENGTH_SHORT).show()
            }
        }

        val nameColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (canRename) {
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }
        }

        val headerNameView = TextView(context).apply {
            text = if (isSingle) currentEntry.metadata.name else "${items.size} items selected"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(setTextColorForTheme())
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nameColumn.addView(headerNameView)

        val hintParts = mutableListOf<String>()
        if (canChangeFileMimeIcon && fileMimeKey != null) {
            hintParts += "Tap icon to change for $fileMimeKey"
        } else if (canChangeIcon) {
            hintParts += "Tap icon to change"
        }
        if (canRename) hintParts += "Tap name to rename"
        if (hintParts.isNotEmpty()) {
            nameColumn.addView(TextView(context).apply {
                text = hintParts.joinToString(" · ")
                textSize = 11f
                setTextColor(if (dark) 0xFF9BA994.toInt() else 0xFF687363.toInt())
                setPadding(0, dp(2), 0, 0)
            })
        }

        headerRow.addView(iconBox)
        headerRow.addView(nameColumn)
        layout.addView(headerRow)

        val divider = View(context).apply {
            setBackgroundColor(if (dark) 0xFF2D332B.toInt() else 0xFFDADDD5.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(8)
            }
        }
        layout.addView(divider)

        // 1. Type
        val typeDesc = when {
            !isSingle -> "Multiple items"
            isShortcut -> "Shortcut link"
            inArchive && isDirectory -> "Folder inside archive"
            isSingle && AppsUri.isAppUri(currentEntry.uri) -> "Android Application (APK)"
            isSingle && AppsUri.isCategoryUri(currentEntry.uri) -> "Virtual Application Category"
            isDirectory -> "Folder"
            isPackagedFormat -> {
                val ext = currentEntry.metadata.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
                val mime = currentEntry.metadata.mimeType
                if (ext.isNotEmpty()) " Package ()" else "Package ()"
            }
            isArchive -> "Archive (${currentEntry.metadata.mimeType})"
            else -> currentEntry.metadata.mimeType.ifEmpty { "File" }
        }
        val typeView = textValue(typeDesc)
        propertyRow("Type", typeView)

        var propertiesDialogRef: android.app.AlertDialog? = null

        if (isFile && !isPackagedFormat) {
            io.execute {
                val magic = if (localProvider.owns(currentEntry.uri)) {
                    val file = localProvider.fileFor(currentEntry.uri)
                    com.infinifolder.storage.FileMagicDetector.detect(file, peekCompressedPayload = true)
                } else {
                    try {
                        com.infinifolder.storage.FileMagicDetector.detectFromStream {
                            (provider.openRead(currentEntry.ref) as? ProviderResult.Success)?.value
                                ?: throw java.io.IOException("Cannot open stream")
                        }
                    } catch (_: Throwable) { null }
                }
                if (magic != null) {
                    ambiguousMagicCache.put(currentEntry.uri.toString(), magic)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            typeView.text = "${magic.description} (${magic.mimeType})"
                            val dlg = propertiesDialogRef
                            val isArcOrDisc = magic.isArchiveOrDiscImage ||
                                ArchiveFormats.isArchive(currentEntry.metadata.name, magic.mimeType) ||
                                ArchiveFormats.isDiscImage(currentEntry.metadata.name, magic.mimeType)
                            if (dlg != null && dlg.isShowing && isArcOrDisc && !isPackagedFormat && !inArchive) {
                                dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.apply {
                                    text = "Mount / Browse"
                                    visibility = View.VISIBLE
                                    setOnClickListener {
                                        dlg.dismiss()
                                        navigate(ArchiveUri.toArchiveUri(currentEntry.uri), displayName = currentEntry.metadata.name)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // If app or category, show app info
        if (isSingle && AppsUri.isAppUri(currentEntry.uri)) {
            val pkg = AppsUri.getPackageName(currentEntry.uri)
            val appItem = if (pkg != null) appsProvider.getAppItem(pkg) else null
            if (pkg != null) propertyRow("Package", textValue(pkg))
            if (appItem?.versionName != null) propertyRow("Version", textValue(appItem.versionName.orEmpty()))
            val cat = appItem?.category ?: AppsUri.getCategoryName(currentEntry.uri)
            if (cat != null) propertyRow("Category", textValue(cat))
            val extractable = if (appItem?.canExtract == true) "Yes (can be copied / moved / archived)" else "No (restricted platform access)"
            propertyRow("Extractable", textValue(extractable))
            if (appItem?.sourceDir?.isNotEmpty() == true) propertyRow("APK Path", textValue(appItem.sourceDir))
        } else if (isSingle && AppsUri.isCategoryUri(currentEntry.uri)) {
            val cat = AppsUri.getCategoryName(currentEntry.uri) ?: currentEntry.metadata.name
            propertyRow("Category", textValue(cat))
        }

        // If shortcut, show merged Link Information fields (editable when not inside a read-only archive)
        var shortcutTargetUriInput: EditText? = null
        var shortcutTargetPathInput: EditText? = null
        var shortcutOverrideInput: EditText? = null
        var shortcutIsDirCheckbox: CheckBox? = null
        var shortcutCreatedView: TextView? = null

        if (isShortcut) {
            val initialShortcut = shortcut
            val editableShortcut = !inArchive && !AppsUri.isAppsUri(currentEntry.uri)
            if (editableShortcut) {
                val uriRow = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val tUriEdit = EditText(context).apply {
                    setText(initialShortcut?.targetUri ?: "")
                    textSize = 14f
                    setTextColor(setTextColorForTheme())
                }
                shortcutTargetUriInput = tUriEdit
                val uriActionsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val pickFolderBtn = TextView(context).apply {
                    text = "Pick folder target…"
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
                    setPadding(dp(4), dp(4), dp(12), dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val parsed = runCatching { Uri.parse(tUriEdit.text.toString().trim()) }.getOrNull()
                        showFolderPicker("Select New Target Folder", parsed) { pickedUri, displayPath ->
                            tUriEdit.setText(pickedUri.toString())
                            shortcutTargetPathInput?.setText(displayPath)
                            shortcutIsDirCheckbox?.isChecked = true
                        }
                    }
                }
                val detectWebBtn = TextView(context).apply {
                    text = "Detect web title & icon"
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
                    setPadding(dp(4), dp(4), dp(8), dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val raw = tUriEdit.text.toString().trim()
                        val normalized = WebMetadataResolver.normalizeInputUri(raw)
                        if (!WebMetadataResolver.isWebUri(normalized)) {
                            Toast.makeText(this@MainActivity, "Enter an http:// or https:// web URL to auto-detect.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        tUriEdit.setText(normalized)
                        Toast.makeText(this@MainActivity, "Detecting title and favicon…", Toast.LENGTH_SHORT).show()
                        imageIo.execute {
                            val meta = WebMetadataResolver.fetchWebMetadata(normalized, dp(128))
                            runOnUiThread {
                                if (isCancelled || isFinishing || isDestroyed) return@runOnUiThread
                                if (!meta.title.isNullOrBlank()) {
                                    shortcutOverrideInput?.setText(meta.title)
                                }
                                shortcutTargetPathInput?.setText(meta.normalizedUrl)
                                shortcutIsDirCheckbox?.isChecked = false
                                if (!meta.faviconBase64.isNullOrBlank()) {
                                    val base = shortcut ?: ShortcutInfo(targetUri = meta.normalizedUrl)
                                    val updatedWithIcon = base.copy(
                                        targetUri = meta.normalizedUrl,
                                        iconBase64 = meta.faviconBase64
                                    )
                                    shortcut = updatedWithIcon
                                    shortcutCache.put(currentEntry.uri.toString(), updatedWithIcon)
                                    loadHeaderIcon()
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    if (!meta.title.isNullOrBlank() || !meta.faviconBase64.isNullOrBlank()) "Detected web metadata" else "Could not detect title/favicon for URL",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                uriActionsRow.addView(pickFolderBtn)
                uriActionsRow.addView(detectWebBtn)
                uriRow.addView(tUriEdit)
                uriRow.addView(uriActionsRow)
                propertyRow("Target URI", uriRow)

                val tPathEdit = EditText(context).apply {
                    setText(initialShortcut?.targetPath ?: "")
                    textSize = 14f
                    setTextColor(setTextColorForTheme())
                }
                shortcutTargetPathInput = tPathEdit
                propertyRow("Target Path", tPathEdit)

                val dnoEdit = EditText(context).apply {
                    setText(initialShortcut?.displayNameOverride ?: "")
                    hint = "Leave empty to use target name"
                    setHintTextColor(if (dark) 0xFF888888.toInt() else 0xFFAAAAAA.toInt())
                    textSize = 14f
                    setTextColor(setTextColorForTheme())
                }
                shortcutOverrideInput = dnoEdit
                propertyRow("Display Name Override", dnoEdit)

                val isDirCheck = CheckBox(context).apply {
                    text = "Target is a folder"
                    isChecked = initialShortcut?.targetIsDirectory == true
                    setTextColor(setTextColorForTheme())
                }
                shortcutIsDirCheckbox = isDirCheck
                layout.addView(isDirCheck)
            } else {
                val roTargetUri = textValue(initialShortcut?.targetUri ?: "Loading…")
                propertyRow("Target URI", roTargetUri)
                val roTargetPath = textValue(initialShortcut?.targetPath ?: "—")
                propertyRow("Target Path", roTargetPath)
                val roOverride = textValue(initialShortcut?.displayNameOverride ?: "—")
                propertyRow("Display Name Override", roOverride)
            }

            val createdText = initialShortcut?.let {
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT
                ).format(java.util.Date(it.createdAtMillis))
            } ?: "—"
            val cView = textValue(createdText)
            shortcutCreatedView = cView
            propertyRow("Created", cView)

            if (initialShortcut == null) {
                io.execute {
                    val loaded = readShortcutInfo(currentEntry)
                    if (loaded != null && !isCancelled) {
                        runOnUiThread {
                            if (!isCancelled) {
                                shortcut = loaded
                                shortcutTargetUriInput?.setText(loaded.targetUri)
                                shortcutTargetPathInput?.setText(loaded.targetPath ?: "")
                                shortcutOverrideInput?.setText(loaded.displayNameOverride ?: "")
                                shortcutIsDirCheckbox?.isChecked = loaded.targetIsDirectory
                                shortcutCreatedView?.text = java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.MEDIUM,
                                    java.text.DateFormat.SHORT
                                ).format(java.util.Date(loaded.createdAtMillis))
                                loadHeaderIcon()
                            }
                        }
                    }
                }
            }
        }

        // 2. Location
        val locationStr = if (isSingle) {
            getPathDisplay(currentEntry.uri)
        } else {
            current?.let { getPathDisplay(it) } ?: "Multiple locations"
        }
        val locationView = textValue(locationStr)
        propertyRow("Location", locationView)

        if (isDirectory) {
            val fsTypeView = textValue("Checking…")
            val fsLimitView = textValue("Checking…")
            val fsNameView = textValue("Checking…")
            propertyRow("Filesystem", fsTypeView)
            propertyRow("Maximum file size", fsLimitView)
            propertyRow("Filename rules", fsNameView)
            val folderRef = currentEntry.ref
            io.execute {
                val info = (provider.fileSystemInfo(folderRef) as? ProviderResult.Success)?.value
                runOnUiThread {
                    if (!isCancelled && !isFinishing && !isDestroyed) {
                        fsTypeView.text = info?.displayName ?: "Not reported by this storage provider"
                        fsLimitView.text = info?.limitDescription ?: "Not reported"
                        fsNameView.text = info?.nameDescription ?: "Not reported"
                    }
                }
            }
        }

        // 3. Size & Contains
        val sizeView = textValue(
            if (!isDirectory && isSingle) {
                val b = currentEntry.metadata.sizeBytes ?: 0L
                val formatted = android.text.format.Formatter.formatShortFileSize(this, b)
                val exact = java.text.NumberFormat.getNumberInstance().format(b)
                "$formatted ($exact bytes)"
            } else {
                "Calculating size…"
            }
        )
        if (isDirectory) {
            val usageRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            usageRow.addView(sizeView, LinearLayout.LayoutParams(0, -2, 1f))
            usageRow.addView(Button(context).apply {
                text = "Disk use explorer"
                contentDescription = "Explore file and directory disk usage"
                setOnClickListener {
                    propertiesDialogRef?.dismiss()
                    openDiskUsageExplorer(currentEntry)
                }
            }, LinearLayout.LayoutParams(-2, -2))
            propertyRow(if (isDirectory) "Used disk space (file data)" else "Container size", usageRow)
        } else propertyRow("Size", sizeView)

        var containsView: TextView? = null
        if ((isDirectory || !isSingle) && !isShortcut) {
            containsView = textValue("Calculating items…")
            propertyRow("Contains", containsView)
        }

        // 4. Modified Date
        if (isSingle && currentEntry.metadata.modifiedAtMillis != null) {
            val dateStr = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT
            ).format(java.util.Date(currentEntry.metadata.modifiedAtMillis!!))
            propertyRow("Modified", textValue(dateStr))
        }

        // 5. URI
        var uriView: TextView? = null
        if (isSingle) {
            uriView = textValue(currentEntry.uri.toString())
            propertyRow("URI", uriView)
        }

        // 5b. Symbolic Link Info
        if (isSingle && currentEntry.metadata.isSymbolicLink) {
            val target = currentEntry.metadata.linkTarget ?: "—"
            propertyRow("Link Target", textValue(target))
            val status = if (currentEntry.metadata.isBrokenLink) {
                "Broken symbolic link (target does not exist)"
            } else {
                "Valid link to ${if (currentEntry.metadata.isDirectory) "folder" else "file"}"
            }
            propertyRow("Link Status", textValue(status))
        }

        // 6. Permissions
        if (isSingle) {
            val localFile = runCatching { if (localProvider.owns(currentEntry.uri)) localProvider.fileFor(currentEntry.uri) else null }.getOrNull()
            val meta = if (localFile != null && currentEntry.metadata.permissionsMode == null) {
                val p = com.infinifolder.storage.PermissionHelper.getFilePermissions(localFile)
                currentEntry.metadata.copy(
                    canRead = p.canRead,
                    canWrite = p.canWrite,
                    canExecute = p.canExecute,
                    permissionsMode = p.mode,
                    permissionsFormatted = p.formatted,
                    owner = p.owner,
                    group = p.group
                )
            } else {
                currentEntry.metadata
            }
            val permFormatted = meta.permissionsFormatted
                ?: meta.permissionsMode?.let { com.infinifolder.storage.PermissionHelper.formatPosix(it, meta.isDirectory) }
                ?: (if (meta.isDirectory) {
                    if (meta.canWrite) "drwxr-xr-x (0755)" else "dr-xr-xr-x (0555)"
                } else {
                    if (meta.canWrite) "-rw-r--r-- (0644)" else "-r--r--r-- (0444)"
                })
            val permText = buildString {
                append(permFormatted)
                val statusParts = mutableListOf<String>()
                meta.recoveryNote?.let { statusParts.add(it) }
                if (meta.partialRecoveryAvailable) statusParts.add("Partial recovery with a byte-range report is available")
                statusParts.add(meta.readError?.let { "Read: Unavailable ($it)" } ?: if (meta.canRead) "Read: Allowed" else "Read: Denied")
                statusParts.add(if (meta.canWrite) "Write: Allowed" else "Write: Denied (Read-Only)")
                if (meta.canExecute) statusParts.add("Execute: Allowed")
                append("\n")
                append(statusParts.joinToString(" • "))
                if (!meta.owner.isNullOrEmpty() || !meta.group.isNullOrEmpty()) {
                    append("\nOwner: ${meta.owner ?: "—"}   Group: ${meta.group ?: "—"}")
                }
            }
            propertyRow("Permissions", textValue(permText))
        } else {
            val total = items.size
            val readableCount = items.count { it.metadata.canRead }
            val writableCount = items.count { it.metadata.canWrite }
            val permSummary = buildString {
                append("Read: $readableCount of $total accessible")
                append("\nWrite: $writableCount of $total writable")
            }
            propertyRow("Permissions", textValue(permSummary))
        }

        val onNameTapped: () -> Unit = {
            if (canRename) {
                renameEntry(currentEntry) { renamed ->
                    currentEntry = renamed
                    headerNameView.text = renamed.metadata.name
                    locationView.text = getPathDisplay(renamed.uri)
                    uriView?.text = renamed.uri.toString()
                }
            } else if (isSingle) {
                Toast.makeText(this, "This item cannot be renamed.", Toast.LENGTH_SHORT).show()
            }
        }
        nameColumn.setOnClickListener { onNameTapped() }
        headerNameView.setOnClickListener { onNameTapped() }

        val scroll = ScrollView(context).apply { addView(layout) }
        val builder = dialogBuilder()
            .setTitle(if (isSingle) "Properties" else (title ?: "Properties"))
            .setView(scroll)

        if (isShortcut && shortcutTargetUriInput != null) {
            builder.setNegativeButton("Close", null)
            builder.setPositiveButton("Save") { _, _ ->
                val rawNewUri = shortcutTargetUriInput.text.toString().trim()
                if (rawNewUri.isEmpty()) {
                    Toast.makeText(this, "Target URI cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newUri = WebMetadataResolver.normalizeInputUri(rawNewUri)
                val baseInfo = shortcut ?: ShortcutInfo(targetUri = newUri)
                val updated = baseInfo.copy(
                    targetUri = newUri,
                    targetPath = shortcutTargetPathInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    displayNameOverride = shortcutOverrideInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    targetIsDirectory = shortcutIsDirCheckbox?.isChecked == true
                )
                updateShortcutFile(currentEntry, updated)
            }
        } else {
            builder.setPositiveButton("Close", null)
        }
        val canMountInitially = isArchive && !isPackagedFormat && !inArchive
        if (canMountInitially) {
            builder.setNeutralButton("Mount / Browse") { _, _ ->
                navigate(ArchiveUri.toArchiveUri(currentEntry.uri), displayName = currentEntry.metadata.name)
            }
        } else if (isFile && !isPackagedFormat && !inArchive) {
            builder.setNeutralButton("Mount / Browse", null)
        }
        val dialog = builder.create()
        propertiesDialogRef = dialog
        dialog.setOnShowListener {
            if (!canMountInitially && isFile && !inArchive) {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
            }
        }

        activePropertiesIconRefresh = {
            if (!isCancelled && dialog.isShowing) {
                loadHeaderIcon()
            }
        }

        dialog.setOnDismissListener {
            isCancelled = true
            activePropertiesIconRefresh = null
        }

        if ((isDirectory || !isSingle) && !isShortcut) {
            io.execute {
                val stats = FileOperations.calculateFolderStats(
                    entries = items,
                    provider = provider,
                    isCancelled = { isCancelled || isFinishing || isDestroyed },
                    onProgress = { currentStats ->
                        runOnUiThread {
                            if (!isCancelled && dialog.isShowing) {
                                val curFormatted = android.text.format.Formatter.formatShortFileSize(this, currentStats.totalBytes)
                                sizeView.text = "Calculating… ($curFormatted)"
                                containsView?.text = "${currentStats.fileCount} files, ${currentStats.directoryCount} folders"
                            }
                        }
                    }
                )
                if (!isCancelled) {
                    runOnUiThread {
                        if (dialog.isShowing) {
                            val formatted = android.text.format.Formatter.formatShortFileSize(this, stats.totalBytes)
                            val exact = java.text.NumberFormat.getNumberInstance().format(stats.totalBytes)
                            sizeView.text = "$formatted ($exact bytes)"
                            containsView?.text = "${stats.fileCount} files, ${stats.directoryCount} folders"
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showGroupedMenuDialog(
        title: String,
        subtitle: CharSequence? = null,
        groups: List<List<String>>,
        onAction: (String) -> Unit
    ): android.app.AlertDialog {
        val context = dialogContext()
        val selectionGroup = if (selectionMode) listOf(listOf("Exit selection mode")) else emptyList()
        val nonEmptyGroups = (selectionGroup + groups).map { it.filter(String::isNotBlank) }.filter { it.isNotEmpty() }
        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        var dialog: android.app.AlertDialog? = null
        nonEmptyGroups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                val divider = View(context).apply {
                    setBackgroundColor(if (dark) 0xFF343B32.toInt() else 0xFFD5D8D0.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    }
                }
                listLayout.addView(divider)
            }
            group.forEach { actionLabel ->
                val itemView = TextView(context).apply {
                    text = actionLabel
                    textSize = 16f
                    setTextColor(setTextColorForTheme())
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(22), dp(12), dp(22), dp(12))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog?.dismiss()
                        if (actionLabel == "Exit selection mode") exitSelectionMode()
                        else onAction(actionLabel)
                    }
                }
                listLayout.addView(itemView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        val scroll = ScrollView(context).apply { addView(listLayout) }
        val builder = dialogBuilder()
        if (subtitle != null) {
            val titleContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(18), dp(24), dp(8))
                val titleView = TextView(context).apply {
                    text = title
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(setTextColorForTheme())
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 2
                }
                addView(titleView)
                val subView = TextView(context).apply {
                    id = android.R.id.text2
                    text = subtitle
                    textSize = 13f
                    setTextColor(if (dark) 0xFF9BA994.toInt() else 0xFF687363.toInt())
                    setPadding(0, dp(3), 0, 0)
                    visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 2
                }
                addView(subView)
            }
            builder.setCustomTitle(titleContainer)
        } else {
            builder.setTitle(title)
        }
        dialog = builder
            .setView(scroll)
            .create()
        dialog.show()
        return dialog
    }

    private var entryMenuRequest = 0
    private fun showEntryMenu(entry: ResourceEntry) {
        val request = ++entryMenuRequest
        val location = loadGeneration
        io.execute {
            val capabilities = (provider.capabilities(entry.ref) as? ProviderResult.Success)?.value
            val shortcut = if (com.infinifolder.storage.LinkResolver.kind(entry.metadata) != null && !entry.metadata.isSymbolicLink)
                readShortcutInfo(entry) else null
            val canShowDeleted = entry.metadata.recoveryNote == null && !entry.metadata.isDirectory &&
                ArchiveFormats.isDiscImage(entry.metadata.name, entry.metadata.mimeType) &&
                runCatching { archiveProvider.supportsFatRecovery(entry.uri) }.getOrDefault(false)
            runOnUiThread {
                if (request != entryMenuRequest || location != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                showPreparedEntryMenu(entry, capabilities, shortcut, canShowDeleted)
            }
        }
    }

    private fun showPreparedEntryMenu(entry: ResourceEntry, capabilities: ResourceCapabilities?,
        shortcut: ShortcutInfo?, canShowDeleted: Boolean) {
        val isShortcut = com.infinifolder.storage.LinkResolver.kind(entry.metadata) != null
        val inArchive = ArchiveUri.isArchiveUri(entry.uri)
        val isApp = AppsUri.isAppUri(entry.uri)
        val isCategory = AppsUri.isCategoryUri(entry.uri)
        val isFile = !entry.metadata.isDirectory && !isApp && !isCategory && !isShortcut

        val detectedMagic = if (isFile) {
            ambiguousMagicCache.get(entry.uri.toString()) ?: run {
                if (localProvider.owns(entry.uri)) {
                    val file = localProvider.fileFor(entry.uri)
                    FileMagicDetector.detect(file, peekCompressedPayload = true)?.also {
                        ambiguousMagicCache.put(entry.uri.toString(), it)
                    }
                } else null
            }
        } else null

        val isArchive = !isApp && (
            ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType) ||
            (detectedMagic != null && (detectedMagic.isArchiveOrDiscImage || ArchiveFormats.isArchive(entry.metadata.name, detectedMagic.mimeType) || ArchiveFormats.isDiscImage(entry.metadata.name, detectedMagic.mimeType)))
        )
        val isPackagedFormat = !isApp && (
            ArchiveFormats.isPackagedFormat(entry.metadata.name, entry.metadata.mimeType) ||
            (detectedMagic != null && ArchiveFormats.isPackagedFormat(entry.metadata.name, detectedMagic.mimeType))
        )
        val canExtractApp = if (isApp) {
            val pkg = AppsUri.getPackageName(entry.uri)
            pkg != null && appsProvider.canExtract(pkg)
        } else false

        val detectedMediaType = MediaTypeDetector.detect(entry.metadata.name, (detectedMagic?.mimeType ?: entry.metadata.mimeType))

        val baseOpenGroup: List<String> = when {
            isApp -> listOf("Launch")
            isCategory -> listOf("Browse folder")
            isShortcut -> {
                val parsedTarget = shortcut?.let { runCatching { Uri.parse(it.targetUri) }.getOrNull() }
                val isWebShortcut = shortcut?.let { WebMetadataResolver.isWebUri(it.targetUri) } == true
                val isAppShortcut = parsedTarget?.let { AppsUri.isAppUri(it) } == true
                val isCustomScheme = parsedTarget?.scheme != null &&
                    parsedTarget.scheme !in setOf("file", "content", "apps", "archive")
                when {
                    isWebShortcut -> listOf("Open link")
                    isAppShortcut -> listOf("Launch target app")
                    shortcut?.targetIsDirectory == true -> listOf("Open target folder")
                    isCustomScheme -> listOf("Open target URI")
                    else -> listOf("Open target file", "Open target with external app")
                }
            }
            isPackagedFormat -> {
                if (inArchive) listOf("Open", "Browse archive")
                else listOf("Open", "Browse archive", "Open with external app")
            }
            inArchive && entry.metadata.isDirectory -> listOf("Browse folder")
            inArchive && isArchive -> listOf("Browse archive", "Open", "Open with external app")
            inArchive -> listOf("Open", "Open with external app")
            isArchive -> listOf("Browse archive", "Open with external app")
            entry.metadata.isDirectory -> {
                val list = mutableListOf("Browse folder")
                val dir = MediaSourceAdapter.resolveLocalFile(this, entry.uri)
                if (dir != null && dir.isDirectory) {
                    val files = dir.listFiles() ?: emptyArray()
                    var a = 0
                    var v = 0
                    for (f in files) {
                        if (f.isFile) {
                            val mt = MediaTypeDetector.detect(f.name, null)
                            if (mt == MediaType.AUDIO) a++
                            else if (mt == MediaType.VIDEO) v++
                        }
                    }
                    if (a > 0) list.add("Queue all audio ($a)")
                    if (v > 0) list.add("Play all video ($v)")
                    if (a > 0 && v > 0) list.add("Queue all multimedia (${a + v})")
                }
                list
            }
            detectedMediaType == MediaType.AUDIO -> {
                val audioCount = entries.count { !it.metadata.isDirectory && MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.AUDIO }
                val videoCount = entries.count { !it.metadata.isDirectory && MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.VIDEO }
                val list = mutableListOf("Open", "Queue audio")
                if (audioCount > 1) list.add("Queue all audio ($audioCount)")
                if (videoCount > 0) list.add("Queue all multimedia (${audioCount + videoCount})")
                list.add("Open with external app")
                list
            }
            detectedMediaType == MediaType.VIDEO -> {
                val audioCount = entries.count { !it.metadata.isDirectory && MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.AUDIO }
                val videoCount = entries.count { !it.metadata.isDirectory && MediaTypeDetector.detect(it.metadata.name, it.metadata.mimeType) == MediaType.VIDEO }
                val list = mutableListOf("Open")
                if (videoCount > 1) list.add("Play all video ($videoCount)")
                if (audioCount > 0) list.add("Queue all multimedia (${audioCount + videoCount})")
                list.add("Open with external app")
                list
            }
            else -> listOf("Open", "Open with external app")
        }

        val openGroup = baseOpenGroup + if (canShowDeleted) listOf("Show deleted files") else emptyList()
        val canCopyContents = !entry.metadata.isDirectory && !isApp && !isCategory &&
            (isTextEntry(entry, detectedMagic) || isImageEntry(entry, detectedMagic))
        val canPaste = StorageActionPolicy.action(StorageAction.CREATE, entry.metadata, capabilities).mayAttempt

        val fileOpsGroup: List<String> = when {
            entry.metadata.recoveryNote != null -> listOf("Select", "Undelete…", "Copy to…") +
                if (entry.metadata.partialRecoveryAvailable) listOf("Recover partial copy…") else emptyList()
            isApp -> {
                if (canExtractApp) {
                    listOf("Select", "Copy", "Link to…", "Create archive", "Copy to…", "Move to…", "Uninstall")
                } else {
                    listOf("Select", "Copy", "Link to…", "Uninstall")
                }
            }
            isCategory -> listOf("Select", "Copy", "Copy to…", "Link to…")
            isShortcut -> if (entry.metadata.isSymbolicLink)
                listOf("Select", "Copy", "Copy to…", "Link to…", "Rename", "Delete")
                else listOf("Select", "Copy", "Create archive", "Copy to…", "Move to…", "Link to…", "Rename", "Delete")
            else -> {
                val list = mutableListOf("Select", "Copy")
                if (canCopyContents) list.add("Copy contents")
                if (canPaste) list.add("Paste into folder")
                list.addAll(listOf("Link to…", "Create archive", "Copy to…", "Move to…", "Rename", "Delete"))
                val lower = entry.metadata.name.lowercase(Locale.ROOT)
                val isThemeArchive = isArchive && (lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tgz") || lower.endsWith(".txz") || lower.endsWith(".zip"))
                if (isThemeArchive || (entry.metadata.isDirectory && (lower.contains("icon") || lower.contains("theme")))) {
                    val canInstall = iconThemeManager.isIconTheme(entry) {
                        (provider.openRead(entry.ref) as? ProviderResult.Success)?.value
                            ?: throw java.io.IOException("Cannot read stream")
                    }
                    if (canInstall) {
                        list.add("Install as icon theme")
                    }
                }
                list
            }
        }

        val allowedFileOps = fileOpsGroup.filter { action ->
            val operation = when (action) {
                "Copy", "Copy to…", "Create archive" -> StorageAction.COPY
                "Move to…" -> StorageAction.MOVE
                "Rename" -> StorageAction.RENAME
                "Delete" -> StorageAction.DELETE
                "Paste into folder" -> StorageAction.CREATE
                else -> null
            }
            operation == null || StorageActionPolicy.action(operation, entry.metadata, capabilities).mayAttempt
        }.toMutableList()
        allowedFileOps.add("Add to home screen")
        if (entry.metadata.isDirectory && !isApp && !isCategory && canPaste) allowedFileOps.add("Use as debug dump folder")
        val linkedConnection = shortcut?.let { info ->
            runCatching { Uri.parse(info.targetUri) }.getOrNull()?.let { target ->
                ContainerUris.candidates(target, savedConnections()).singleOrNull()
            }
        }
        if (linkedConnection != null) allowedFileOps.add("Edit connection…")
        val propertiesGroup = listOf("Properties")

        val initialSubtitle = when {
            entry.metadata.recoveryNote != null -> entry.metadata.recoveryNote
            isApp -> {
                val pkg = AppsUri.getPackageName(entry.uri)
                val appItem = if (pkg != null) appsProvider.getAppItem(pkg) else null
                if (appItem?.versionName != null) "Android Application · v${appItem.versionName}" else "Android Application"
            }
            isCategory -> "Virtual Application Category"
            isShortcut -> {
                val target = shortcut?.targetPath ?: shortcut?.targetUri
                if (!target.isNullOrBlank()) "Link → $target" else "Link"
            }
            entry.metadata.isDirectory -> if (inArchive) "Folder in archive" else "Folder"
            isPackagedFormat -> {
                val pkgMime = ArchiveFormats.packageMimeType(entry.metadata.name)
                val ext = entry.metadata.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
                "Packaged file · ${pkgMime ?: entry.metadata.mimeType}"
            }
            detectedMagic != null -> "${detectedMagic.description} · ${detectedMagic.mimeType}"
            else -> {
                val mime = entry.metadata.mimeType.ifEmpty {
                    com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(entry.metadata.name, false)
                }
                when {
                    inArchive -> "File in archive · ${mime.ifEmpty { "unknown type" }}"
                    mime.isNotEmpty() && mime != "application/octet-stream" -> mime
                    FileMagicDetector.isAmbiguousOrUnknown(entry.metadata.name, entry.metadata.mimeType) -> "Detecting file type…"
                    else -> "File"
                }
            }
        }

        val dialog = showGroupedMenuDialog(
            title = entry.metadata.name,
            subtitle = initialSubtitle,
            groups = listOf(openGroup + listOfNotNull(entryBrowseDestination(entry)?.let {
                if (isContainerPinned(it)) "Unpin" else "Pin to top"
            }), allowedFileOps, propertiesGroup)
        ) { action ->
            if (action.startsWith("Queue all audio")) {
                val folder = if (entry.metadata.isDirectory) entry.uri else (current ?: entry.uri)
                val start = if (entry.metadata.isDirectory) null else entry
                queueAllAudioInFolder(folder, start)
                return@showGroupedMenuDialog
            }
            if (action.startsWith("Play all video")) {
                val folder = if (entry.metadata.isDirectory) entry.uri else (current ?: entry.uri)
                val start = if (entry.metadata.isDirectory) null else entry
                queueAllVideoInFolder(folder, start)
                return@showGroupedMenuDialog
            }
            if (action.startsWith("Queue all multimedia")) {
                val folder = if (entry.metadata.isDirectory) entry.uri else (current ?: entry.uri)
                val start = if (entry.metadata.isDirectory) null else entry
                queueAllMultimediaInFolder(folder, start)
                return@showGroupedMenuDialog
            }
            when (action) {
                "Edit connection…" -> linkedConnection?.let { connection ->
                    when (connection.sourceTypeId) {
                        "sftp" -> showSftpConnectionDialog(existing = connection)
                        "ftp" -> showFtpConnectionDialog(existing = connection)
                        "smb" -> showSmbConnectionDialog(existing = connection)
                        "webdav" -> showWebDavConnectionDialog(existing = connection)
                    }
                }
                "Pin to top" -> entryBrowseDestination(entry)?.let { pinContainer(entry, it) }
                "Unpin" -> entryBrowseDestination(entry)?.let(::unpinContainer)
                "Add to home screen" -> pinEntryToHome(entry)
                "Queue audio" -> enqueueAudio(entry)
                "Launch", "Launch target app" -> {
                    if (isShortcut) openShortcut(entry)
                    else {
                        val pkg = AppsUri.getPackageName(entry.uri)
                        if (pkg != null) launchApp(pkg)
                    }
                }
                "Uninstall" -> {
                    val pkg = AppsUri.getPackageName(entry.uri)
                    if (pkg != null) {
                        dialogBuilder()
                            .setTitle("Uninstall ${entry.metadata.name.removeSuffix(".apk")}?")
                            .setMessage("This will request Android to uninstall this application.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Uninstall") { _, _ -> uninstallApp(pkg) }
                            .show()
                    }
                }
                "Open link", "Open target URI", "Open target folder", "Open target file" -> openShortcut(entry)
                "Open target with external app" -> openShortcutWithExternalApp(entry)
                "Show deleted files" -> navigate(ArchiveUri.deletedFiles(entry.uri), displayName = "Deleted files — ${entry.metadata.name}")
                "Recover partial copy…" -> showDestinationPicker(entry, false, partialRecovery = true)
                "Undelete…" -> {
                    if (!entry.metadata.canRead) dialogBuilder().setTitle(if (entry.metadata.partialRecoveryAvailable) "Full recovery unavailable" else "Cannot recover this file")
                        .setMessage((entry.metadata.readError ?: "File data is unavailable") +
                            if (entry.metadata.partialRecoveryAvailable) "\n\nUse Recover partial copy… to keep readable uncertain bytes, zero-fill unavailable ranges and save a byte-range report." else "")
                        .setPositiveButton("OK", null).show()
                    else showDestinationPicker(entry, false)
                }
                "Browse folder" -> navigate(entry.uri, displayName = entry.metadata.name)
                "Browse archive", "Browse" -> navigate(ArchiveUri.toArchiveUri(entry.uri), displayName = entry.metadata.name)
                "Open with external app" -> open(entry, externalOnly = true)
                "Open" -> if (entry.metadata.isDirectory) navigate(entry.uri, displayName = entry.metadata.name) else open(entry)
                "Use as debug dump folder" -> {
                    com.infinifolder.storage.DebugDumpDestination.set(this, entry.ref)
                    com.infinifolder.viewer.DebugTools.onActivityResumed(this)
                    Toast.makeText(this, "Debug dumps will be written to this folder", Toast.LENGTH_LONG).show()
                }
                "Select" -> {
                    selectionMode = true
                    selectedUris.clear()
                    selectedUris.add(entry.uri)
                    renderCurrentContents()
                    updateListingStatus()
                }
                "Copy" -> copyEntriesToClipboard(listOf(entry))
                "Copy contents" -> copyEntryContentsToClipboard(entry, detectedMagic)
                "Paste into folder", "Paste" -> pasteFromClipboard(entry.uri)
                "Link to…" -> showLinkDestinationPicker(listOf(entry))
                "Create archive" -> promptCreateArchive(listOf(entry))
                "Copy to…" -> showDestinationPicker(entry, false)
                "Move to…" -> showDestinationPicker(entry, true)
                "Rename" -> renameEntry(entry)
                "Delete" -> confirmDelete(entry)
                "Install as icon theme" -> installIconThemeFromEntry(entry)
                "Properties" -> showPropertiesDialog(entry)
                else -> showPropertiesDialog(entry)
            }
        }

        if (isFile && !isPackagedFormat && detectedMagic == null) {
            io.execute {
                val magic = try {
                    if (localProvider.owns(entry.uri)) {
                        FileMagicDetector.detect(localProvider.fileFor(entry.uri), peekCompressedPayload = true)
                    } else {
                        FileMagicDetector.detectFromStream {
                            (provider.openRead(entry.ref) as? ProviderResult.Success)?.value
                                ?: throw java.io.IOException("Cannot open stream")
                        }
                    }
                } catch (_: Exception) { null }
                if (magic != null) {
                    ambiguousMagicCache.put(entry.uri.toString(), magic)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            dialog.findViewById<TextView>(android.R.id.text2)?.apply {
                                text = "${magic.description} · ${magic.mimeType}"
                                visibility = View.VISIBLE
                            }
                        }
                    }
                } else if (initialSubtitle == "Detecting file type…") {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val mime = entry.metadata.mimeType.ifEmpty {
                                com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(entry.metadata.name, false)
                            }
                            dialog.findViewById<TextView>(android.R.id.text2)?.apply {
                                text = mime.ifEmpty { "Binary / Data File" }
                                visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    }

    private fun copyEntriesToClipboard(entries: List<ResourceEntry>, cut: Boolean = false) {
        if (entries.isEmpty()) return
        copiedEntries = entries
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val first = entries[0]
        val clip = if (entries.size == 1) {
            val item = ClipData.Item(first.uri.toString(), null, first.uri)
            ClipData(ClipDescription(first.metadata.name, arrayOf("application/x-infinifolder-uri", "text/uri-list", "text/plain")), item)
        } else {
            val firstItem = ClipData.Item(first.uri.toString(), null, first.uri)
            val c = ClipData(ClipDescription("${entries.size} items", arrayOf("application/x-infinifolder-uri", "text/uri-list", "text/plain")), firstItem)
            for (i in 1 until entries.size) {
                val e = entries[i]
                c.addItem(ClipData.Item(e.uri.toString(), null, e.uri))
            }
            c
        }
        cutClipboardToken = if (cut) UUID.randomUUID().toString() else null
        clip.description.extras = PersistableBundle().apply { cutClipboardToken?.let { putString("infinifolder_cut", it) } }
        clipboard.setPrimaryClip(clip)
        val label = if (entries.size == 1) entries[0].metadata.name else "${entries.size} items"
        Toast.makeText(this, if (cut) "Cut $label — paste to move" else "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun isTextEntry(entry: ResourceEntry, detectedMagic: FileMagicResult?): Boolean {
        val mime = (detectedMagic?.mimeType ?: entry.metadata.mimeType).lowercase(Locale.ROOT)
        if (mime.startsWith("text/")) return true
        if (mime in setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-javascript", "application/x-sh", "application/x-shellscript",
            "application/x-yaml", "application/yaml", "application/toml",
            "application/sql", "application/x-sql", "application/x-httpd-php"
        )) return true
        val ext = entry.metadata.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val textExts = setOf(
            "txt", "text", "md", "markdown", "log", "json", "xml", "html", "htm",
            "css", "js", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "java", "kt",
            "kts", "py", "sh", "bash", "zsh", "rc", "conf", "ini", "yaml", "yml",
            "toml", "csv", "tsv", "sql", "properties", "gradle", "bat", "cmd", "ps1"
        )
        return ext in textExts
    }

    private fun isImageEntry(entry: ResourceEntry, detectedMagic: FileMagicResult?): Boolean {
        val mime = (detectedMagic?.mimeType ?: entry.metadata.mimeType).lowercase(Locale.ROOT)
        if (mime.startsWith("image/")) return true
        val ext = entry.metadata.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg", "tif", "tiff", "avif", "heic")
        return ext in imageExts
    }

    private fun copyEntryContentsToClipboard(entry: ResourceEntry, detectedMagic: FileMagicResult?) {
        val isImg = isImageEntry(entry, detectedMagic)
        val isTxt = isTextEntry(entry, detectedMagic)
        if (!isImg && !isTxt) {
            Toast.makeText(this, "Cannot copy contents of this file type.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isTxt) {
            io.execute {
                try {
                    val streamRes = provider.openRead(entry.ref)
                    if (streamRes !is ProviderResult.Success) {
                        throw java.io.IOException((streamRes as? ProviderResult.Failure)?.message ?: "Cannot open file")
                    }
                    val text = streamRes.value.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(1024 * 1024 * 4) // up to 4MB text
                        val read = reader.read(buffer)
                        if (read > 0) String(buffer, 0, read) else ""
                    }
                    runOnUiThread {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText(entry.metadata.name, text)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(this@MainActivity, "Copied text contents to clipboard", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!com.infinifolder.viewer.CacheFailureUi.offerCopy(this@MainActivity, e, entry.uri)) Toast.makeText(this@MainActivity, "Failed to copy contents: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            io.execute {
                try {
                    val shareableUri = if (localProvider.owns(entry.uri)) {
                        val file = localProvider.fileFor(entry.uri)
                        FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", file)
                    } else if (entry.uri.scheme == "file" && entry.uri.path != null) {
                        FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", File(entry.uri.path!!))
                    } else {
                        val staged = MediaSourceAdapter.stage(this, entry.uri, entry.metadata.name)
                        externalCacheLeases.add(staged)
                        val cacheFile = staged.file
                        FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", cacheFile)
                    }
                    val mime = (detectedMagic?.mimeType ?: entry.metadata.mimeType).takeIf { it.startsWith("image/") }
                        ?: com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(entry.metadata.name, false).takeIf { it.startsWith("image/") }
                        ?: "image/png"
                    runOnUiThread {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        val clip = ClipData(
                            ClipDescription(entry.metadata.name, arrayOf(mime)),
                            ClipData.Item(shareableUri)
                        ).apply {
                            description.extras = PersistableBundle().apply {
                                putBoolean("is_image_content", true)
                            }
                        }
                        grantUriPermission(packageName, shareableUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(this@MainActivity, "Copied image to clipboard", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to copy image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun resolveClipboardFileEntries(clip: ClipData): List<ResourceEntry> {
        val clipUris = (0 until clip.itemCount).mapNotNull {
            clip.getItemAt(it).uri ?: runCatching { Uri.parse(clip.getItemAt(it).text?.toString()?.trim() ?: "") }.getOrNull()
        }.filter { it.scheme != null }

        if (clipUris.isEmpty()) return emptyList()

        val cached = copiedEntries
        if (cached != null && cached.isNotEmpty()) {
            val cachedMap = cached.associateBy { it.uri }
            val matched = clipUris.mapNotNull { cachedMap[it] }
            if (matched.size == clipUris.size) {
                return matched
            }
        }

        val resolved = mutableListOf<ResourceEntry>()
        for (uri in clipUris) {
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (localProvider.owns(uri)) {
                val f = localProvider.fileFor(uri)
                if (f.exists()) {
                    val meta = ResourceMetadata(f.name, if (f.isDirectory) "vnd.android.document/directory" else resolveMimeType(uri, f, ""), f.length(), f.lastModified(), f.isDirectory)
                    resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
                }
            } else if (scheme == "file" && uri.path != null) {
                val f = File(uri.path!!)
                if (f.exists()) {
                    val meta = ResourceMetadata(f.name, if (f.isDirectory) "vnd.android.document/directory" else resolveMimeType(uri, f, ""), f.length(), f.lastModified(), f.isDirectory)
                    resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
                }
            } else if (AppsUri.isAppUri(uri)) {
                val pkg = AppsUri.getPackageName(uri)
                if (pkg != null) {
                    val label = appsProvider.getAppLabel(pkg) ?: pkg
                    val meta = ResourceMetadata("$label.apk", "application/vnd.android.package-archive", null, null, false)
                    resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
                }
            } else if (ArchiveUri.isArchiveUri(uri)) {
                val name = uri.lastPathSegment ?: "archive_item"
                val meta = ResourceMetadata(name, "", null, null, false)
                resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
            } else if (SftpUri.isSftpUri(uri)) {
                val name = SftpUri.getRemotePath(uri).substringAfterLast('/').ifEmpty { "sftp_item" }
                val meta = ResourceMetadata(name, "", null, null, false)
                resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
            } else if (FtpUri.isFtpUri(uri)) {
                val name = FtpUri.getRemotePath(uri).substringAfterLast('/').ifEmpty { "ftp_item" }
                val meta = ResourceMetadata(name, "", null, null, false)
                resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
            } else if (SmbUri.isSmbUri(uri)) {
                val name = SmbUri.getRemotePath(uri).substringAfterLast('/').ifEmpty { "smb_item" }
                val meta = ResourceMetadata(name, "", null, null, false)
                resolved.add(ResourceEntry(resourceRef(uri), meta, uri))
            }
        }
        return resolved
    }

    private fun isLikelyUriString(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n') || trimmed.contains('\r') || trimmed.contains(' ')) return false
        if (WebMetadataResolver.isWebUri(trimmed)) return true
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme in setOf("http", "https", "ftp", "ftps", "mailto", "tel", "sms", "market", "intent", "ssh", "git") ||
            (scheme.length in 2..20 && scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' } && uri.schemeSpecificPart.isNotBlank())
    }

    private fun pasteFromClipboard(destDirUri: Uri) {
        val isArchive = ArchiveUri.isArchiveUri(destDirUri)
        val mountCheck = if (isArchive) archiveProvider.checkMounted(destDirUri) else null
        if (mountCheck?.isMounted == true) {
            notifyMountedPartitionBlocked(displayNameFor(destDirUri) ?: "Archive", mountCheck.reason)
            return
        }
        withWritableDestination(destDirUri) { pasteIntoWritableDestination(destDirUri) }
    }

    private fun withWritableDestination(destination: Uri, onReady: () -> Unit) {
        io.execute {
            val result = StorageActionPolicy.destination(provider, resourceRef(destination))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val reason = when (result) {
                    is ProviderResult.Success -> if (result.value.decision.mayAttempt) null else result.value.decision.reason
                    is ProviderResult.Failure -> result.message
                    is ProviderResult.Unsupported -> "Cannot check destination: ${result.capability}"
                }
                if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show() else onReady()
            }
        }
    }

    private fun pasteIntoWritableDestination(destDirUri: Uri) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            return
        }

        val desc = clip.description
        val hasImageMime = desc.hasMimeType("image/*") ||
            desc.extras?.getBoolean("is_image_content") == true

        val firstItem = clip.getItemAt(0)
        val itemUri = firstItem?.uri
        val uriMime = itemUri?.let { runCatching { contentResolver.getType(it) }.getOrNull() }
        val isImage = hasImageMime || (uriMime != null && uriMime.startsWith("image/"))

        // 1. Image in clipboard
        if (isImage && itemUri != null && !desc.hasMimeType("application/x-infinifolder-uri")) {
            pasteImageFromClipboard(destDirUri, itemUri, uriMime ?: "image/png")
            return
        }

        // 2. File(s) in clipboard
        val entriesToCopy = resolveClipboardFileEntries(clip)
        if (entriesToCopy.isNotEmpty()) {
            val move = cutClipboardToken != null && desc.extras?.getString("infinifolder_cut") == cutClipboardToken &&
                copiedEntries?.map { it.ref } == entriesToCopy.map { it.ref }
            cutClipboardToken = null
            startBatchTransfer(entriesToCopy, move = move, destination = destDirUri)
            return
        }

        // 3. URI in clipboard (web URL or custom URI link)
        val clipText = firstItem?.coerceToText(this)?.toString()?.trim() ?: ""
        val parsedUri = itemUri ?: runCatching { Uri.parse(clipText) }.getOrNull()
        val isLikelyUri = (parsedUri != null && parsedUri.scheme != null &&
            (parsedUri.scheme !in setOf("file", "content", "apps", "archive", "sftp", "ftp", "smb") || WebMetadataResolver.isWebUri(clipText))) ||
            isLikelyUriString(clipText)

        if (isLikelyUri) {
            showCreateUriLinkDialog(destDirUri, initialUri = clipText.ifEmpty { parsedUri?.toString() ?: "" })
            return
        }

        // 4. Bit of text in clipboard
        if (clipText.isNotEmpty()) {
            pasteTextFromClipboard(destDirUri, clipText)
            return
        }

        Toast.makeText(this, "Clipboard content cannot be pasted here.", Toast.LENGTH_SHORT).show()
    }

    private fun pasteTextFromClipboard(destDirUri: Uri, text: String) {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val defaultName = "text_$timeStamp.txt"
        val input = EditText(dialogContext()).apply {
            setText(defaultName)
            val dot = defaultName.lastIndexOf('.').takeIf { it > 0 } ?: defaultName.length
            setSelection(0, dot)
            setSingleLine(true)
        }
        val container = LinearLayout(dialogContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), 0)
            addView(input)
        }
        dialogBuilder()
            .setTitle("Paste Text File")
            .setMessage("Enter a name for the new text file:")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val fileName = input.text.toString().trim().ifEmpty { defaultName }
                io.execute {
                    try {
                        val createRes = provider.createFile(resourceRef(destDirUri), fileName, "text/plain")
                        if (createRes !is ProviderResult.Success) {
                            throw java.io.IOException((createRes as? ProviderResult.Failure)?.message ?: "Cannot create file")
                        }
                        val writeRes = provider.openWrite(createRes.value.ref)
                        if (writeRes !is ProviderResult.Success) {
                            throw java.io.IOException((writeRes as? ProviderResult.Failure)?.message ?: "Cannot write to file")
                        }
                        writeRes.value.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        runOnUiThread {
                            directoryIconCache.evictAll()
                            refreshInternalSharedTree()
                            current?.let { load(it) }
                            Toast.makeText(this@MainActivity, "Created $fileName", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Failed to create text file: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun pasteImageFromClipboard(destDirUri: Uri, imageUri: Uri, mimeType: String) {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val ext = when {
            mimeType.contains("png", ignoreCase = true) -> ".png"
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> ".jpg"
            mimeType.contains("webp", ignoreCase = true) -> ".webp"
            mimeType.contains("gif", ignoreCase = true) -> ".gif"
            else -> ".png"
        }
        val defaultName = "image_$timeStamp$ext"
        val input = EditText(dialogContext()).apply {
            setText(defaultName)
            val dot = defaultName.lastIndexOf('.').takeIf { it > 0 } ?: defaultName.length
            setSelection(0, dot)
            setSingleLine(true)
        }
        val container = LinearLayout(dialogContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), 0)
            addView(input)
        }
        dialogBuilder()
            .setTitle("Paste Image File")
            .setMessage("Enter a name for the new image file:")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val fileName = input.text.toString().trim().ifEmpty { defaultName }
                io.execute {
                    try {
                        val inStream = if (imageUri.scheme == "file" && imageUri.path != null) {
                            File(imageUri.path!!).inputStream()
                        } else {
                            contentResolver.openInputStream(imageUri)
                                ?: throw java.io.IOException("Cannot open image from clipboard")
                        }
                        val createRes = provider.createFile(resourceRef(destDirUri), fileName, mimeType)
                        if (createRes !is ProviderResult.Success) {
                            throw java.io.IOException((createRes as? ProviderResult.Failure)?.message ?: "Cannot create file")
                        }
                        val writeRes = provider.openWrite(createRes.value.ref)
                        if (writeRes !is ProviderResult.Success) {
                            throw java.io.IOException((writeRes as? ProviderResult.Failure)?.message ?: "Cannot write to file")
                        }
                        inStream.use { input ->
                            writeRes.value.use { output ->
                                input.copyTo(output, bufferSize = 128 * 1024)
                                output.flush()
                            }
                        }
                        runOnUiThread {
                            directoryIconCache.evictAll()
                            refreshInternalSharedTree()
                            current?.let { load(it) }
                            Toast.makeText(this@MainActivity, "Saved $fileName", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Failed to save image: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }
    private fun launchCustomIconPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, pickCustomIconRequest)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(fallback, pickCustomIconRequest)
            } catch (e: Exception) {
                pendingIconTargetType = null
                pendingIconTargetUri = null
                pendingIconTargetMime = null
                Toast.makeText(this, "Could not open image selector: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptSelectFolderIcon(folderUri: Uri) {
        if ((ArchiveUri.isArchiveUri(folderUri) && !archiveProvider.cachedWritable(folderUri)) || AppsUri.isAppsUri(folderUri)) {
            Toast.makeText(this, "Cannot change icon for a read-only folder.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingIconTargetType = "folder"
        pendingIconTargetUri = folderUri
        pendingIconTargetMime = null
        launchCustomIconPicker()
    }

    private fun promptSelectShortcutIcon(shortcutUri: Uri) {
        if (ArchiveUri.isArchiveUri(shortcutUri) || AppsUri.isAppsUri(shortcutUri)) {
            Toast.makeText(this, "Cannot change icon for a read-only link.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingIconTargetType = "shortcut"
        pendingIconTargetUri = shortcutUri
        pendingIconTargetMime = null
        launchCustomIconPicker()
    }

    private fun promptSelectMimeTypeIcon(mimeKey: String) {
        pendingIconTargetType = "mime"
        pendingIconTargetUri = null
        pendingIconTargetMime = mimeKey
        launchCustomIconPicker()
    }

    private fun readSelectedImageNameAndBytes(sourceImageUri: Uri): Triple<String?, String?, ByteArray?> {
        var displayName: String? = null
        try {
            contentResolver.query(
                sourceImageUri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        displayName = cursor.getString(idx)
                    }
                }
            }
        } catch (_: Exception) {}
        if (displayName.isNullOrBlank()) {
            displayName = sourceImageUri.lastPathSegment
        }
        val mimeType = try { contentResolver.getType(sourceImageUri) } catch (_: Exception) { null }
        val imageBytes = (try {
            contentResolver.openInputStream(sourceImageUri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }) ?: (try {
            val readRes = provider.openRead(resourceRef(sourceImageUri))
            if (readRes is ProviderResult.Success) readRes.value.use { it.readBytes() } else null
        } catch (_: Exception) {
            null
        })
        return Triple(displayName, mimeType, imageBytes)
    }

    private fun applyFolderIcon(folderUri: Uri, sourceImageUri: Uri) {
        io.execute {
            try {
                val (displayName, mimeType, imageBytes) = readSelectedImageNameAndBytes(sourceImageUri)
                val (iconFileName, iconMime) = DirectoryIconResolver.targetIconFileName(displayName, mimeType)

                if (imageBytes == null || imageBytes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not read selected image.", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val folderRef = resourceRef(folderUri)
                var createdEntry: ResourceEntry? = null
                if (ArchiveUri.isArchiveUri(folderUri)) {
                    val batchRes = provider.beginWriteBatch(folderRef) { false }
                    if (batchRes is ProviderResult.Success) {
                        val batch = batchRes.value
                        try {
                            val listRes = batch.provider.list(folderRef)
                            if (listRes is ProviderResult.Success) {
                                listRes.value
                                    .filter { !it.metadata.isDirectory && DirectoryIconResolver.isExplicitIconFileName(it.metadata.name) }
                                    .forEach { existing ->
                                        try { batch.provider.delete(existing.ref) } catch (_: Exception) {}
                                    }
                            }
                            val createRes = batch.provider.createFile(folderRef, iconFileName, iconMime)
                            if (createRes !is ProviderResult.Success) {
                                val msg = (createRes as? ProviderResult.Failure)?.message ?: "Could not create folder icon file"
                                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                                return@execute
                            }
                            val writeRes = batch.provider.openWrite(createRes.value.ref)
                            if (writeRes !is ProviderResult.Success) {
                                val msg = (writeRes as? ProviderResult.Failure)?.message ?: "Could not write folder icon file"
                                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                                return@execute
                            }
                            writeRes.value.use { output ->
                                output.write(imageBytes)
                                output.flush()
                            }
                            batch.commit()
                            createdEntry = createRes.value
                        } catch (e: Throwable) {
                            val msg = e.message ?: "Could not commit folder icon file"
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                            return@execute
                        } finally {
                            try { batch.close() } catch (_: Exception) {}
                        }
                    }
                }
                if (createdEntry == null) {
                    if (localProvider.owns(folderUri)) {
                        try {
                            val dir = localProvider.fileFor(folderUri)
                            dir.listFiles()
                                ?.filter { it.isFile && DirectoryIconResolver.isExplicitIconFileName(it.name) }
                                ?.forEach { it.delete() }
                        } catch (_: Exception) {}
                    } else {
                        val listRes = provider.list(folderRef)
                        if (listRes is ProviderResult.Success) {
                            listRes.value
                                .filter { !it.metadata.isDirectory && DirectoryIconResolver.isExplicitIconFileName(it.metadata.name) }
                                .forEach { existing ->
                                    try { provider.delete(existing.ref) } catch (_: Exception) {}
                                }
                        }
                    }

                    val createRes = provider.createFile(folderRef, iconFileName, iconMime)
                    if (createRes !is ProviderResult.Success) {
                        val msg = (createRes as? ProviderResult.Failure)?.message ?: "Could not create folder icon file"
                        runOnUiThread {
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                        return@execute
                    }

                    val writeRes = provider.openWrite(createRes.value.ref)
                    if (writeRes !is ProviderResult.Success) {
                        try { provider.delete(createRes.value.ref) } catch (_: Exception) {}
                        val msg = (writeRes as? ProviderResult.Failure)?.message ?: "Could not write folder icon file"
                        runOnUiThread {
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                        return@execute
                    }

                    writeRes.value.use { output ->
                        output.write(imageBytes)
                        output.flush()
                    }
                    createdEntry = createRes.value
                }
                val finalCreatedEntry = createdEntry ?: return@execute
                val previewBmp = thumbnailDecoder.decode(sourceImageUri, dp(128))
                    ?: thumbnailDecoder.decode(finalCreatedEntry.uri, dp(128))
                val dirKey = "${folderRef.providerId}:${folderRef.documentId}"
                val createdUriStr = finalCreatedEntry.uri.toString()

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    directoryIconCache.evictAll()
                    ResourceIconSidecars.clearDirectories()
                    ResourceArtworkCache.clearMemory()
                    thumbnailCache.evictAll()
                    failedThumbnails.evictAll()
                    directoryIconCache.put(dirKey, createdUriStr)
                    directoryIconCache.put("tree:$folderUri", createdUriStr)
                    if (previewBmp != null) {
                        thumbnailCache.put("dir-icon:$dirKey:$createdUriStr", previewBmp)
                    }
                    activePropertiesIconRefresh?.invoke()
                    if (current == folderUri) {
                        load(folderUri)
                    } else {
                        renderCurrentContents()
                    }
                    renderTree()
                    Toast.makeText(this, "Folder icon updated", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to set folder icon: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyShortcutIcon(shortcutUri: Uri, sourceImageUri: Uri) {
        io.execute {
            try {
                val bmp = thumbnailDecoder.decode(sourceImageUri, dp(128))
                if (bmp == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not decode selected image.", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val base64 = CustomIconStore.encodeBitmapToBase64Png(bmp)

                val shortcutRef = resourceRef(shortcutUri)
                val existingInfo = shortcutCache.get(shortcutUri.toString()) ?: run {
                    val readRes = provider.openRead(shortcutRef)
                    if (readRes is ProviderResult.Success) {
                        val text = readRes.value.bufferedReader().use { it.readText() }
                        ShortcutInfo.fromJson(text)
                    } else null
                }
                if (existingInfo == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not read link file.", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val updatedInfo = existingInfo.copy(iconBase64 = base64)
                val writeRes = provider.openWrite(shortcutRef)
                if (writeRes !is ProviderResult.Success) {
                    val msg = (writeRes as? ProviderResult.Failure)?.message ?: "Could not write link file"
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }
                writeRes.value.bufferedWriter().use { writer ->
                    writer.write(updatedInfo.toJson())
                    writer.flush()
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    shortcutCache.put(shortcutUri.toString(), updatedInfo)
                    thumbnailCache.evictAll()
                    failedThumbnails.evictAll()
                    activePropertiesIconRefresh?.invoke()
                    renderCurrentContents()
                    Toast.makeText(this, "Link icon updated", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to set link icon: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyMimeTypeIcon(mimeKey: String, sourceImageUri: Uri) {
        io.execute {
            try {
                val (displayName, mimeType, imageBytes) = readSelectedImageNameAndBytes(sourceImageUri)
                val decodedBmp = thumbnailDecoder.decode(sourceImageUri, dp(128))
                    ?: imageBytes?.let { bytes ->
                        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    }
                if (decodedBmp == null && (imageBytes == null || imageBytes.isEmpty())) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not read or decode selected image.", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val pngBytes = decodedBmp?.let { CustomIconStore.encodeBitmapToPngBytes(it) }
                if (pngBytes != null && pngBytes.isNotEmpty() && decodedBmp != null) {
                    customIconStore.setMimeIcon(mimeKey, "png", pngBytes, decodedBmp)
                } else if (imageBytes != null && imageBytes.isNotEmpty()) {
                    val (iconFileName, _) = DirectoryIconResolver.targetIconFileName(displayName, mimeType)
                    val ext = iconFileName.substringAfterLast('.', "png")
                    val file = customIconStore.setMimeIcon(mimeKey, ext, imageBytes, decodedBmp)
                    if (decodedBmp == null) {
                        val fallbackBmp = thumbnailDecoder.decode(Uri.fromFile(file), dp(128))
                        if (fallbackBmp != null) {
                            val fallbackPng = CustomIconStore.encodeBitmapToPngBytes(fallbackBmp)
                            if (fallbackPng != null) {
                                customIconStore.setMimeIcon(mimeKey, "png", fallbackPng, fallbackBmp)
                            }
                        }
                    }
                }
                val finalBmp = customIconStore.getMimeIconBitmap(mimeKey) { file ->
                    thumbnailDecoder.decode(Uri.fromFile(file), dp(128))
                }
                if (finalBmp == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Selected image format could not be decoded.", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    thumbnailCache.evictAll()
                    failedThumbnails.evictAll()
                    activePropertiesIconRefresh?.invoke()
                    renderCurrentContents()
                    Toast.makeText(this, "Icon updated for $mimeKey", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to set file type icon: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedUris.clear()
        renderCurrentContents()
        updateListingStatus()
    }
    private fun showBatchActionMenu(entriesOverride: List<ResourceEntry>? = null) {
        val selectedEntries = entriesOverride ?: entries.filter { it.uri in selectedUris }
        if (selectedEntries.isEmpty()) {
            exitSelectionMode()
            return
        }
        withActionCapabilities(selectedEntries) { capabilities -> showPreparedBatchActionMenu(selectedEntries, capabilities) }
    }

    private fun showPreparedBatchActionMenu(selectedEntries: List<ResourceEntry>, capabilities: Map<ResourceRef, ResourceCapabilities>) {
        val count = selectedEntries.size
        val fileOpsGroup = listOf("Copy", "Copy to…", "Move to…", "Delete", "Create archive", "Link to…", "Deselect all").filter {
            val operation = when (it) {
                "Copy", "Copy to…", "Create archive" -> StorageAction.COPY
                "Move to…" -> StorageAction.MOVE
                "Delete" -> StorageAction.DELETE
                else -> null
            }
            operation == null || StorageActionPolicy.selection(operation, selectedEntries, capabilities).mayAttempt
        }
        val propertiesGroup = listOf("Properties")
        showGroupedMenuDialog(
            title = "$count item${if (count == 1) "" else "s"} selected",
            subtitle = "Batch actions",
            groups = listOf(fileOpsGroup, propertiesGroup)
        ) { action ->
            when (action) {
                "Copy" -> {
                    copyEntriesToClipboard(selectedEntries)
                    exitSelectionMode()
                }
                "Copy to…" -> {
                    val unextractable = selectedEntries.filter { AppsUri.isAppUri(it.uri) && !appsProvider.canExtract(AppsUri.getPackageName(it.uri) ?: "") }
                    if (unextractable.isNotEmpty()) {
                        if (unextractable.size == selectedEntries.size) {
                            Toast.makeText(this@MainActivity, "Selected app APK(s) cannot be extracted.", Toast.LENGTH_SHORT).show()
                            return@showGroupedMenuDialog
                        }
                        Toast.makeText(this@MainActivity, "Skipping ${unextractable.size} unextractable app(s).", Toast.LENGTH_SHORT).show()
                    }
                    showBatchDestinationPicker(selectedEntries.filterNot { it in unextractable }, false)
                }
                "Move to…" -> {
                    val unextractable = selectedEntries.filter { AppsUri.isAppUri(it.uri) && !appsProvider.canExtract(AppsUri.getPackageName(it.uri) ?: "") }
                    if (unextractable.isNotEmpty()) {
                        if (unextractable.size == selectedEntries.size) {
                            Toast.makeText(this@MainActivity, "Selected app APK(s) cannot be extracted.", Toast.LENGTH_SHORT).show()
                            return@showGroupedMenuDialog
                        }
                        Toast.makeText(this@MainActivity, "Skipping ${unextractable.size} unextractable app(s).", Toast.LENGTH_SHORT).show()
                    }
                    showBatchDestinationPicker(selectedEntries.filterNot { it in unextractable }, true)
                }
                "Delete" -> confirmBatchDelete(selectedEntries)
                "Create archive" -> {
                    val unextractable = selectedEntries.filter { AppsUri.isAppUri(it.uri) && !appsProvider.canExtract(AppsUri.getPackageName(it.uri) ?: "") }
                    if (unextractable.isNotEmpty()) {
                        if (unextractable.size == selectedEntries.size) {
                            Toast.makeText(this@MainActivity, "Selected app APK(s) cannot be extracted.", Toast.LENGTH_SHORT).show()
                            return@showGroupedMenuDialog
                        }
                        Toast.makeText(this@MainActivity, "Skipping ${unextractable.size} unextractable app(s).", Toast.LENGTH_SHORT).show()
                    }
                    promptCreateArchive(selectedEntries.filterNot { it in unextractable })
                }
                "Link to…" -> showLinkDestinationPicker(selectedEntries)
                "Properties" -> showEntriesPropertiesDialog(selectedEntries, "$count items")
                "Deselect all" -> exitSelectionMode()
            }
        }
    }

    private fun openShortcut(entry: ResourceEntry) = open(entry)

    private fun showCreateTermuxCommandLinkDialog() {
        val folder = current ?: return
        val context = dialogContext()
        val name = EditText(context).apply { hint = "Name"; setTextColor(setTextColorForTheme()); setSingleLine() }
        val command = EditText(context).apply {
            hint = "Command, e.g. htop"
            setTextColor(setTextColorForTheme())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val cwd = EditText(context).apply {
            hint = "Working directory (optional, absolute path)"
            setTextColor(setTextColorForTheme())
            setSingleLine()
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(name); addView(command); addView(cwd)
        }
        val dialog = dialogBuilder().setTitle("New Termux command")
            .setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                val value = command.text.toString().trim()
                val directory = cwd.text.toString().trim().takeIf { it.isNotEmpty() }
                if (title.isEmpty()) { name.error = "Enter a name"; return@setOnClickListener }
                if (value.isEmpty()) { command.error = "Enter a command"; return@setOnClickListener }
                if (directory != null && !directory.startsWith('/')) {
                    cwd.error = "Use an absolute path"; return@setOnClickListener
                }
                val uri = TermuxCommandLink.uri(value, directory)
                val info = ShortcutInfo(targetUri = uri.toString(), displayNameOverride = title,
                    targetMimeType = "application/vnd.infinifolder.termux-command")
                val safeName = WebMetadataResolver.sanitizeFileName(title, "Termux command")
                dialog.dismiss()
                createShortcutFile(folder, safeName + TermuxCommandLink.SUFFIX, info)
            }
        }
        dialog.show()
    }
    private fun openShortcutWithExternalApp(entry: ResourceEntry) = open(entry, externalOnly = true)

    private fun promptCreateShortcut(entry: ResourceEntry) {
        val defaultTargetFolder = current?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
            ?: defaultWritableFolderUri()
            ?: return

        var targetFolderUri: Uri = defaultTargetFolder
        val isApp = AppsUri.isAppUri(entry.uri)
        val baseName = if (isApp) entry.metadata.name.removeSuffix(".apk") else entry.metadata.name.removeSuffix(".lnk").removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION)
        val defaultShortcutName = "$baseName${ShortcutInfo.SHORTCUT_EXTENSION}"

        val context = dialogContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val nameLabel = TextView(context).apply {
            text = "Shortcut file name:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        val nameInput = EditText(context).apply {
            setText(defaultShortcutName)
            val dot = defaultShortcutName.lastIndexOf('.').takeIf { it > 0 } ?: defaultShortcutName.length
            setSelection(0, dot)
            setTextColor(setTextColorForTheme())
        }

        val overrideLabel = TextView(context).apply {
            text = "File name override (optional):"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }
        val overrideInput = EditText(context).apply {
            hint = "Leave empty to use target name"
            setHintTextColor(if (dark) 0xFF888888.toInt() else 0xFFAAAAAA.toInt())
            setTextColor(setTextColorForTheme())
        }

        val destHeader = TextView(context).apply {
            text = "Save shortcut to:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, dp(14), 0, dp(4))
        }

        val destRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (dark) 0xFF2A2E28.toInt() else 0xFFEAECE7.toInt())
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }

        val destIcon = TextView(context).apply {
            text = "📁"
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        }

        val destPathView = TextView(context).apply {
            text = getPathDisplay(targetFolderUri)
            textSize = 13f
            setTextColor(setTextColorForTheme())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val changeBtn = TextView(context).apply {
            text = "Change…"
            textSize = 13f
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            isFocusable = true
        }

        destRow.addView(destIcon)
        destRow.addView(destPathView, LinearLayout.LayoutParams(0, -2, 1f))
        destRow.addView(changeBtn)

        val pickSaveLocation: () -> Unit = {
            showFolderPicker("Select Location for Shortcut", targetFolderUri) { selectedUri, displayPath ->
                targetFolderUri = selectedUri
                destPathView.text = displayPath
            }
        }
        destRow.setOnClickListener { pickSaveLocation() }
        changeBtn.setOnClickListener { pickSaveLocation() }

        layout.addView(nameLabel)
        layout.addView(nameInput)
        layout.addView(overrideLabel)
        layout.addView(overrideInput)
        layout.addView(destHeader)
        layout.addView(destRow)

        dialogBuilder()
            .setTitle("Create Shortcut to ${entry.metadata.name}")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val enteredName = nameInput.text.toString().trim()
                if (enteredName.isEmpty()) {
                    Toast.makeText(this, "Shortcut name cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (ArchiveUri.isArchiveUri(targetFolderUri) || AppsUri.isAppsUri(targetFolderUri)) {
                    Toast.makeText(this, "Cannot create shortcut file in a read-only location.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val finalFileName = if (enteredName.endsWith(ShortcutInfo.SHORTCUT_EXTENSION, ignoreCase = true)) enteredName else "$enteredName${ShortcutInfo.SHORTCUT_EXTENSION}"
                val override = overrideInput.text.toString().trim().takeIf { it.isNotEmpty() }
                val info = ShortcutInfo(
                    targetUri = entry.uri.toString(),
                    targetPath = getPathDisplay(entry.uri),
                    displayNameOverride = override,
                    targetMimeType = entry.metadata.mimeType,
                    targetIsDirectory = entry.metadata.isDirectory
                )
                createShortcutFile(targetFolderUri, finalFileName, info)
            }
            .show()
    }

    private fun createShortcutFile(
        folderUri: Uri,
        fileName: String,
        shortcut: ShortcutInfo
    ) {
        io.execute {
            try {
                val destRef = resourceRef(folderUri)
                val cleanName = if (fileName.endsWith(ShortcutInfo.SHORTCUT_EXTENSION, ignoreCase = true)) fileName else "$fileName${ShortcutInfo.SHORTCUT_EXTENSION}"
                val createRes = provider.createFile(destRef, cleanName, ShortcutInfo.SHORTCUT_MIME)
                if (createRes !is ProviderResult.Success) {
                    runOnUiThread { Toast.makeText(this, "Failed to create shortcut file: ${(createRes as? ProviderResult.Failure)?.message ?: "Unsupported"}", Toast.LENGTH_LONG).show() }
                    return@execute
                }
                val writeRes = provider.openWrite(createRes.value.ref)
                if (writeRes !is ProviderResult.Success) {
                    runOnUiThread { Toast.makeText(this, "Failed to write shortcut file.", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                writeRes.value.bufferedWriter().use { writer ->
                    writer.write(shortcut.toJson())
                }
                shortcutCache.put(createRes.value.uri.toString(), shortcut)
                runOnUiThread {
                    directoryIconCache.evictAll()
                    refreshInternalSharedTree()
                    current?.let { load(it) }
                    Toast.makeText(this, "Shortcut created: $cleanName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Error creating shortcut: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun updateShortcutFile(
        entry: ResourceEntry,
        shortcut: ShortcutInfo
    ) {
        io.execute {
            try {
                val writeRes = provider.openWrite(entry.ref)
                if (writeRes !is ProviderResult.Success) {
                    runOnUiThread { Toast.makeText(this, "Failed to write shortcut file.", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                writeRes.value.bufferedWriter().use { writer ->
                    writer.write(shortcut.toJson())
                }
                shortcutCache.put(entry.uri.toString(), shortcut)
                runOnUiThread {
                    renderCurrentContents()
                    Toast.makeText(this, "Link properties saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Error updating shortcut: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun getPathDisplay(uri: Uri): String {
        if (uri == ContainerLinksProvider.ROOT) return "Virtual root"
        if (localProvider.owns(uri)) {
            return runCatching {
                val f = localProvider.fileFor(uri)
                if (f == localProvider.store.root) "Internal shared storage" else f.path
            }.getOrDefault("Internal shared storage")
        }
        if (AppsUri.isAppsUri(uri)) {
            return when {
                AppsUri.isAppUri(uri) -> {
                    val cat = AppsUri.getCategoryName(uri) ?: "Apps"
                    val pkg = AppsUri.getPackageName(uri)
                    val label = pkg?.let { appsProvider.getAppLabel(it) } ?: pkg ?: "App"
                    "Installed Applications / $cat / $label"
                }
                AppsUri.isCategoryUri(uri) -> "Installed Applications / ${AppsUri.getCategoryName(uri) ?: "Category"}"
                else -> "Installed Applications"
            }
        }
        fun findNode(nodes: List<TreeNode>): TreeNode? {
            for (node in nodes) {
                if (node.uri == uri) return node
                val found = findNode(node.children)
                if (found != null) return found
            }
            return null
        }
        val node = findNode(treeRoots)
        if (node != null) {
            val route = findTreeRoute(treeRoots, node).orEmpty().filter { it.uri != null }
            if (route.isNotEmpty()) return route.joinToString(" / ") { it.name }
        }
        if (uri == current && historyNames.isNotEmpty()) {
            return historyNames.joinToString(" / ")
        }
        val cached = shared.getString("source_path_v2_$uri", null) ?: shared.getString("source_name_$uri", null)
        if (!cached.isNullOrEmpty()) return cached
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun defaultWritableFolderUri(): Uri? {
        localProvider.rootUri.takeIf { localProvider.hasAccess() }?.let { return it }
        fun findWritable(nodes: List<TreeNode>): Uri? {
            for (n in nodes) {
                val u = n.uri
                if (u != null && !ArchiveUri.isArchiveUri(u) && !AppsUri.isAppsUri(u)) return u
                findWritable(n.children)?.let { return it }
            }
            return null
        }
        return findWritable(treeRoots)
            ?: homeItems.mapNotNull { it.treeUri }.firstOrNull { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
    }

    private fun lastChosenDestination(): Uri? = runCatching {
        shared.getString("last_chosen_destination", null)?.let(Uri::parse)
            ?.takeUnless { AppsUri.isAppsUri(it) }
    }.getOrNull()

    private fun showFolderPicker(
        title: String,
        initialSelection: Uri?,
        blockedUri: Uri? = null,
        forMove: Boolean = false,
        requireWritable: Boolean = true,
        rememberSelection: Boolean = true,
        transferSources: List<ResourceEntry> = emptyList(),
        onFolderSelected: (Uri, String) -> Unit
    ) {
        val context = dialogContext()
        fun isSameUri(a: Uri?, b: Uri?): Boolean {
            if (a == null || b == null) return false
            return try { sourceIdentity(a) == sourceIdentity(b) } catch (_: Exception) { a == b }
        }
        val destinationCapabilities = mutableMapOf<Uri, ProviderResult<DestinationAssessment>>()
        val pendingChecks = mutableSetOf<Uri>()
        var pickerClosed = false
        var capabilitiesChanged: () -> Unit = {}
        fun isWritableDestination(uri: Uri?): Boolean {
            if (uri == null || AppsUri.isAppsUri(uri)) return false
            val result = destinationCapabilities[uri]
            if (result == null && pendingChecks.add(uri)) io.execute {
                val started = android.os.SystemClock.elapsedRealtime()
                val checked = try {
                    StorageActionPolicy.destination(provider, resourceRef(uri), transferSources, forMove)
                } catch (error: Exception) {
                    ProviderResult.Failure(error.message ?: "Destination check failed", error)
                }
                com.infinifolder.storage.DiagnosticLog.record(
                    "picker.destination.check",
                    "uri=$uri elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} result=$checked"
                )
                runOnUiThread {
                    if (!pickerClosed && !isFinishing && !isDestroyed) {
                        pendingChecks.remove(uri)
                        destinationCapabilities[uri] = checked
                        capabilitiesChanged()
                    }
                }
            }
            val assessment = (result as? ProviderResult.Success)?.value ?: return false
            if (!requireWritable) return assessment.capabilities.mayAttempt(ResourceCapability.LIST)
            return assessment.decision.mayAttempt
        }

        val defaultStart = initialSelection?.takeUnless { AppsUri.isAppsUri(it) }
            ?: lastChosenDestination() ?: defaultWritableFolderUri()
        var selected: Uri? = defaultStart?.takeUnless { AppsUri.isAppsUri(it) }
        var selectedPath = selected?.let { getPathDisplay(it) } ?: "Choose a folder"
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val selectedLabel = TextView(context).apply {
            text = selectedPath
            textSize = 14f
            setTextColor(setTextColorForTheme())
            setPadding(dp(4), dp(4), dp(4), dp(8))
            minHeight = dp(40)
        }
        val nodes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val treeScrollView = ScrollView(context).apply { addView(nodes) }
        body.addView(selectedLabel)
        body.addView(treeScrollView, LinearLayout.LayoutParams(-1, dp(360)))

        val dialog = dialogBuilder()
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Common locations", null)
            .setPositiveButton("Select", null)
            .create()

        fun updateSelectButtonState() {
            val dest = selected
            val writable = isWritableDestination(dest)
            val result = dest?.let { destinationCapabilities[it] }
            val detail = when {
                dest == null -> ""
                result == null -> "Checking destination capabilities…"
                result is ProviderResult.Failure -> result.message
                result is ProviderResult.Unsupported -> "Cannot check destination: ${result.capability}"
                !writable -> if (requireWritable) (result as? ProviderResult.Success)?.value?.decision?.reason
                    ?: "This location does not support creating entries" else "This location cannot be listed"
                else -> ""
            }
            selectedLabel.text = selectedPath + if (detail.isNotBlank()) "\n$detail" else ""
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                dest != null && !isSameUri(dest, blockedUri) && writable
        }

        fun selectedTreeNode(list: List<TreeNode>): TreeNode? {
            for (node in list) {
                if (isSameUri(node.uri, selected)) return node
                selectedTreeNode(node.children)?.let { return it }
            }
            return null
        }

        fun scrollSelectedTreeRow() {
            val key = selectedTreeNode(treeRoots)?.key ?: return
            treeScrollView.post {
                if (!dialog.isShowing) return@post
                fun findRow(view: View): View? {
                    if (view.tag == key) return view
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) findRow(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val row = findRow(nodes) ?: return@post
                val bounds = android.graphics.Rect()
                row.getDrawingRect(bounds)
                treeScrollView.offsetDescendantRectToMyCoords(row, bounds)
                val scrollTop = treeScrollView.scrollY
                val visibleBottom = scrollTop + treeScrollView.height
                when {
                    bounds.top < scrollTop -> treeScrollView.scrollTo(0, bounds.top)
                    bounds.bottom > visibleBottom -> treeScrollView.scrollTo(0, bounds.bottom - treeScrollView.height)
                }
            }
        }

        fun renderPickerTree() {
            nodes.removeAllViews()
            fun hasDestinations(list: List<TreeNode>): Boolean = list.any { node ->
                (node.uri != null && node.canExpand && !AppsUri.isAppsUri(node.uri)) || hasDestinations(node.children)
            }
            if (!hasDestinations(treeRoots)) {
                nodes.addView(TextView(context).apply {
                    text = "No folders are available."
                    textSize = 14f
                    setTextColor(setTextColorForTheme())
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                })
                return
            }
            renderTreeNodes(
                host = nodes,
                nodes = treeRoots,
                onSelect = { node ->
                    val destination = node.uri
                    if (destination != null && !AppsUri.isAppsUri(destination)) {
                        com.infinifolder.storage.DiagnosticLog.record(
                            "picker.destination.select", "name=${node.name} uri=$destination expandable=${node.canExpand} expanded=${node.expanded}"
                        )
                        selected = destination
                        val route = findTreeRoute(treeRoots, node).orEmpty().filter { it.uri != null }
                        val display = if (route.isNotEmpty()) route.joinToString(" / ") { it.name } else node.name
                        selectedPath = display
                        updateSelectButtonState()
                        if (dialog.isShowing) renderPickerTree()
                    }
                },
                rerender = { if (dialog.isShowing) renderPickerTree() },
                excludedUri = AppsUri.ROOT_URI,
                blockedSelectionUri = null,
                isNodeSelected = { node -> isSameUri(selected, node.uri) },
                selectOnExpand = true,
                expandOnSelect = true,
                includeNode = { node ->
                    (node.canExpand || isCreateDirectoryNode(node)) &&
                        (node.uri == null || !AppsUri.isAppsUri(node.uri))
                },
                enableDropTarget = false,
                onCreateDirectoryInNode = { parentNode, parentUri ->
                    promptCreateDirectoryInPicker(parentUri) { created ->
                        val childKey = if (parentNode.key == "internal_shared") {
                            "local_${created.uri}"
                        } else {
                            "${parentNode.key}/${sourceIdentity(created.uri)}"
                        }
                        val existingNode = parentNode.children.firstOrNull { isSameUri(it.uri, created.uri) }
                        val createdNode = existingNode ?: run {
                            lateinit var newNode: TreeNode
                            val openAction: () -> Unit = if (parentNode.key == "internal_shared") {
                                { openRoot(created.uri, rememberAuthorizedRoot = false) }
                            } else {
                                { openTreeNode(newNode) }
                            }
                            newNode = TreeNode(
                                key = childKey,
                                uri = created.uri,
                                name = created.metadata.name,
                                icon = "📁",
                                depth = parentNode.depth + 1,
                                onOpen = openAction,
                                canExpand = true,
                                expanded = true
                            )
                            parentNode.children.add(newNode)
                            parentNode.children.sortBy { it.name.lowercase() }
                            newNode
                        }
                        parentNode.expanded = true
                        createdNode.expanded = true
                        selected = created.uri
                        val route = findTreeRoute(treeRoots, createdNode).orEmpty().filter { it.uri != null }
                        val display = if (route.isNotEmpty()) {
                            route.joinToString(" / ") { it.name }
                        } else {
                            "${getPathDisplay(parentUri)} / ${created.metadata.name}"
                        }
                        selectedPath = display
                        updateSelectButtonState()
                        renderPickerTree()
                    }
                }
            )
            scrollSelectedTreeRow()
        }

        fun revealCurrentSelection() {
            selectedTreeNode(treeRoots)?.let { target ->
                findTreeRoute(treeRoots, target)?.dropLast(1)?.forEach { it.expanded = true }
                if (target.canExpand && !target.expanded) toggleTreeNode(target) {
                    if (dialog.isShowing) renderPickerTree()
                }
            }
        }

        capabilitiesChanged = {
            updateSelectButtonState()
            if (dialog.isShowing) renderPickerTree()
        }
        dialog.setOnDismissListener {
            pickerClosed = true
            activePickerRerender = null
        }

        dialog.setOnShowListener {
            activePickerRerender = { if (dialog.isShowing) renderPickerTree() }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val locations = commonLocations.list()
                if (locations.isEmpty()) {
                    dialogBuilder().setTitle("Common locations")
                        .setMessage("No saved folders yet. Choose destinations to remember them, or add folders in Settings → Common locations.")
                        .setPositiveButton("OK", null).show()
                } else dialogBuilder().setTitle("Common locations")
                    .setItems(locations.map { it.label }.toTypedArray()) { _, index ->
                        val location = locations[index]
                        selected = Uri.parse(location.uri)
                        selectedPath = location.label
                        revealCurrentSelection()
                        updateSelectButtonState()
                        renderPickerTree()
                    }.setNegativeButton("Cancel", null).show()
            }
            val selectBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            updateSelectButtonState()
            selectBtn.setOnClickListener {
                val destination = selected
                if (destination != null && !isSameUri(destination, blockedUri) && isWritableDestination(destination)) {
                    com.infinifolder.storage.DiagnosticLog.record("picker.destination.confirm", "uri=$destination path=$selectedPath")
                    if (rememberSelection && requireWritable)
                        shared.edit().putString("last_chosen_destination", destination.toString()).apply()
                    if (rememberSelection) commonLocations.remember(destination, selectedPath)
                    dialog.dismiss()
                    onFolderSelected(destination, selectedPath)
                }
            }
            revealCurrentSelection()
            renderPickerTree()
        }
        dialog.show()
    }

    private fun promptCreateDirectoryInPicker(
        parentUri: Uri,
        onCreated: (ResourceEntry) -> Unit
    ) {
        if (ArchiveUri.isArchiveUri(parentUri)) {
            val mountCheck = archiveProvider.checkMounted(parentUri)
            if (mountCheck?.isMounted == true) {
                notifyMountedPartitionBlocked(displayNameFor(parentUri) ?: "Archive", mountCheck.reason)
                return
            }
        }
        withWritableDestination(parentUri) { showCreateDirectoryInPicker(parentUri, onCreated) }
    }

    private fun showCreateDirectoryInPicker(parentUri: Uri, onCreated: (ResourceEntry) -> Unit) {
        val input = EditText(dialogContext()).apply {
            hint = "Directory name"
            setTextColor(setTextColorForTheme())
            setHintTextColor(if (dark) 0xFF888888.toInt() else 0xFFAAAAAA.toInt())
        }
        dialogBuilder()
            .setTitle("Create directory")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) {
                            input.error = "Enter a valid directory name"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        io.execute {
                            val result = provider.createDirectory(resourceRef(parentUri), name)
                            runOnUiThread {
                                when (result) {
                                    is ProviderResult.Success -> {
                                        val created = result.value
                                        if (current == parentUri) {
                                            load(parentUri)
                                        }
                                        if (parentUri == localProvider.rootUri) {
                                            refreshInternalSharedTree()
                                        }
                                        onCreated(created)
                                    }
                                    is ProviderResult.Unsupported -> Toast.makeText(this, "This provider does not support creating directories.", Toast.LENGTH_LONG).show()
                                    is ProviderResult.Failure -> {
                                        if (result.cause is MountedPartitionException || result.message.contains("mounted", ignoreCase = true)) {
                                            notifyMountedPartitionBlocked(name, result.message)
                                        } else {
                                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showLinkDestinationPicker(items: List<ResourceEntry>) {
        showFolderPicker("Link to…", lastChosenDestination()) { destination, _ ->
            exitSelectionMode()
            startBatchTransfer(items, false, destination, createLinks = !(destination == ContainerLinksProvider.ROOT && items.all { ShortcutInfo.isShortcut(it.metadata.name, it.metadata.mimeType) }))
        }
    }

    private fun showDestinationPicker(entry: ResourceEntry, move: Boolean, partialRecovery: Boolean = false) {
        showBatchDestinationPicker(listOf(entry), move, partialRecovery)
    }
    private fun showBatchDestinationPicker(entries: List<ResourceEntry>, move: Boolean, partialRecovery: Boolean = false,
                                           initialSelection: Uri? = null) {
        val start = if (homeMode && homePage == "root") ContainerLinksProvider.ROOT else current ?: return
        val title = if (entries.size == 1) {
            if (move) "Move ${entries[0].metadata.name} to" else "Copy ${entries[0].metadata.name} to"
        } else {
            if (move) "Move ${entries.size} items to" else "Copy ${entries.size} items to"
        }
        val defaultStart = initialSelection ?: lastChosenDestination() ?: if (AppsUri.isAppsUri(start)) {
            localProvider.rootUri.takeIf { localProvider.hasAccess() }
        } else null
        showFolderPicker(title, defaultStart, blockedUri = start, forMove = move, transferSources = entries) { destination, _ ->
            if (destination != start) {
                if (entries.size == 1) {
                    startTransfer(entries[0], move, destination, partialRecovery)
                } else {
                    exitSelectionMode()
                    startBatchTransfer(entries, move, destination, partialRecovery = partialRecovery)
                }
            }
        }
    }
    private fun startTransfer(entry: ResourceEntry, move: Boolean, destination: Uri, partialRecovery: Boolean = false) {
        startBatchTransfer(listOf(entry), move, destination, partialRecovery = partialRecovery)
    }

    private fun startBatchTransfer(
        entries: List<ResourceEntry>,
        move: Boolean,
        destination: Uri,
        createLinks: Boolean = false,
        refreshLocationAfterTransfer: Uri? = if (homeMode && homePage == "root") ContainerLinksProvider.ROOT else null,
        restoreScrollYAfterTransfer: Int? = null,
        refreshLocationDisplayName: String? = null,
        recoveryConfirmed: Boolean = false,
        partialRecovery: Boolean = false
    ) {
        val recovering = try { entries.any { it.metadata.recoveryNote != null || provider.recoverySource(it.ref) != null } }
            catch (e: Exception) {
                dialogBuilder().setTitle("Cannot prepare recovery").setMessage(e.message ?: "Recovery source is unavailable")
                    .setPositiveButton("OK", null).show()
                return
            }
        if (recovering && move) {
            dialogBuilder().setTitle("Recovery requires copying")
                .setMessage("Deleted-file candidates can only be recovered by copying to another location.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (recovering && !createLinks && !recoveryConfirmed) {
            val details = entries.singleOrNull()?.metadata?.recoveryNote
                ?: "Deleted data may be fragmented, overwritten or incomplete. Multi-cluster recovery assumes contiguous storage."
            val blockedCandidates = entries.count { !it.metadata.canRead && it.metadata.partialRecoveryAvailable }
            if (!partialRecovery && blockedCandidates > 0) {
                dialogBuilder().setTitle("Partial recovery required")
                    .setMessage("$blockedCandidates selected deleted file(s) cannot be copied intact. Recover partial copies to keep readable uncertain bytes and fill unavailable or allocated/reused ranges with NUL bytes. Each partial copy includes a .recovery.json byte-range report.\n\n" +
                        details + "\n\nOriginal contents remain unverified. Recover outside the source image, preferably to a separate drive.")
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Skip unreadable files") { _, _ ->
                        startBatchTransfer(entries, move, destination, createLinks, refreshLocationAfterTransfer,
                            restoreScrollYAfterTransfer, refreshLocationDisplayName, recoveryConfirmed = true)
                    }
                    .setPositiveButton("Recover partial copies") { _, _ ->
                        startBatchTransfer(entries, move, destination, createLinks, refreshLocationAfterTransfer,
                            restoreScrollYAfterTransfer, refreshLocationDisplayName, recoveryConfirmed = true, partialRecovery = true)
                    }.show()
                return
            }
            dialogBuilder().setTitle(if (partialRecovery) "Recover partial file?" else "Recover unverified data?")
                .setMessage((if (partialRecovery) "Unreadable or allocated/reused ranges will be filled with zero bytes. Readable uncertain bytes will be kept. A .recovery.json byte-range report will be saved beside the .partial copy.\n\n" else "") + details + "\n\nCopy verification only checks the saved bytes against the extracted bytes; it cannot verify the original deleted contents. Recover outside the source image, preferably to a separate drive.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Recover copy") { _, _ ->
                    startBatchTransfer(entries, move, destination, createLinks, refreshLocationAfterTransfer,
                        restoreScrollYAfterTransfer, refreshLocationDisplayName, recoveryConfirmed = true, partialRecovery = partialRecovery)
                }.show()
            return
        }
        if (ArchiveUri.isArchiveUri(destination)) {
            val mountCheck = archiveProvider.checkMounted(destination)
            if (mountCheck?.isMounted == true) {
                notifyMountedPartitionBlocked(displayNameFor(destination) ?: "Archive destination", mountCheck.reason)
                return
            }
        }
        if (move) {
            val mountedSource = entries.firstOrNull {
                ArchiveUri.isArchiveUri(it.uri) && archiveProvider.checkMounted(it.uri)?.isMounted == true
            }
            if (mountedSource != null) {
                val mountCheck = archiveProvider.checkMounted(mountedSource.uri)!!
                notifyMountedPartitionBlocked(mountedSource.metadata.name, mountCheck.reason)
                return
            }
        }
        if (destination == ContainerLinksProvider.ROOT && !createLinks) {
            val linkFiles = entries.filter { ShortcutInfo.isShortcut(it.metadata.name, it.metadata.mimeType) && !it.metadata.isDirectory }
            val containers = entries.filterNot { it in linkFiles }
            if (containers.any { entryBrowseDestination(it) == null }) {
                Toast.makeText(this, "Virtual root accepts container links only. Regular files must go inside a storage container.", Toast.LENGTH_LONG).show()
                return
            }
            if (containers.isNotEmpty()) {
                if (linkFiles.isNotEmpty()) startBatchTransfer(linkFiles, move, destination)
                startBatchTransfer(containers, false, destination, createLinks = true)
                Toast.makeText(this, "Adding container links; target contents stay in place", Toast.LENGTH_LONG).show()
                return
            }
        }
        val count = entries.size
        val label = if (recovering && !createLinks) "Recover $count unverified file${if (count == 1) "" else "s"}" else if (createLinks) "Create $count shortcut${if (count == 1) "" else "s"}" else if (count == 1) {
            "${if (move) "Move" else "Copy"} ${entries[0].metadata.name}"
        } else {
            "${if (move) "Move" else "Copy"} $count items"
        }
        val appEntries = entries.filter { AppsUri.isAppUri(it.uri) }
        val hasApps = appEntries.isNotEmpty() && !createLinks
        val isAppMove = move && hasApps

        val verifyCopies = shared.getBoolean("verify_copies", true)
        val id = operations.submit(label, null, count,
            conflictKeys = transferConflictKeys(entries, destination, createLinks, partialRecovery),
            displayName = entries.singleOrNull()?.metadata?.name ?: "${entries.first().metadata.name} (+${count - 1})",
            destination = destination.toString(),
            restartTransfer = com.infinifolder.ops.TransferRestartRequest(entries.toList(), destination.toString(), move, createLinks, partialRecovery)) { update, cancelled ->
            update(OperationUpdate(destination = displayPathFor(destination) ?: destination.toString()))
            var transferSucceeded = false
            val transferWarnings = mutableListOf<String>()
            var latestTransferUpdate = OperationUpdate()
            val reportProgress: (OperationUpdate) -> Unit = { value ->
                latestTransferUpdate = value.copy(warningCount = transferWarnings.size)
                update(latestTransferUpdate)
            }
            try {
                FileOperations.batchTransfer(
                    entries = entries,
                    destinationFolderUri = destination,
                    move = move && !hasApps,
                    provider = provider,
                    resourceRef = { uri -> resourceRef(uri) },
                    update = reportProgress,
                    isCancelled = cancelled,
                    createLinks = createLinks,
                    onWarning = {
                        transferWarnings.add(it)
                        update(latestTransferUpdate.copy(warningCount = transferWarnings.size))
                    },
                    partialRecovery = partialRecovery,
                    verifyCopies = verifyCopies,
                    copyVerificationEnabled = { shared.getBoolean("verify_copies", true) },
                    confirmRandomAccessFallback = { request ->
                        confirmStagedTransfer(request, cancelled) { suggested ->
                            showBatchDestinationPicker(entries, move, partialRecovery, initialSelection = suggested)
                        }
                    }
                )
                transferSucceeded = !cancelled() && transferWarnings.isEmpty()
                if (!cancelled() && !createLinks) runCatching {
                    commonLocations.remember(destination, displayPathFor(destination) ?: getPathDisplay(destination))
                }.onFailure { com.infinifolder.storage.DiagnosticLog.record("common-locations.remember.failed", error = it) }
            } finally {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (transferWarnings.isNotEmpty()) {
                        val shown = transferWarnings.take(20).joinToString("\n\n")
                        val extra = if (transferWarnings.size > 20) "\n\n${transferWarnings.size - 20} more warnings. Full details are in the debug log." else ""
                        dialogBuilder().setTitle("Transfer warnings (${transferWarnings.size})")
                            .setMessage(shown + extra).setPositiveButton("OK", null).show()
                    }
                    directoryIconCache.evictAll()
                    val refreshDestination = { refreshTreeAfterTransfer(destination) }
                    if (destination == localProvider.rootUri) refreshInternalSharedTree(refreshDestination)
                    else refreshDestination()
                    val sourceLocation = refreshLocationAfterTransfer
                    if (sourceLocation == null) {
                        current?.let { load(it) }
                    } else {
                        restoreScrollYAfterTransfer?.let { pendingLocationScrollRestore = sourceLocation to it }
                        val sourceHistoryIndex = history.indexOfLast { it == sourceLocation }
                        when {
                            !homeMode && current == sourceLocation -> load(sourceLocation)
                            sourceHistoryIndex >= 0 -> selectLocation(sourceHistoryIndex)
                            else -> navigate(sourceLocation, push = true, displayName = refreshLocationDisplayName)
                        }
                    }
                }
            }
            if (transferSucceeded && isAppMove) {
                val nonAppEntries = entries.filterNot { AppsUri.isAppUri(it.uri) }
                for (nonApp in nonAppEntries) {
                    try { provider.delete(nonApp.ref) } catch (_: Exception) {}
                }
                runOnUiThread {
                    appEntries.forEach { appEntry ->
                        val pkgName = AppsUri.getPackageName(appEntry.uri)
                        if (pkgName != null) {
                            uninstallApp(pkgName)
                        }
                    }
                }
            }
        }
        enqueueOperationWindow(id)
    }

    private fun suggestedTransferTargets(excluded: ResourceRef): List<RecommendedTargetsView.Candidate> {
        val candidates = commonLocations.list()
            .sortedByDescending { it.randomWriteConfirmedAt > 0 }
            .map { RecommendedTargetsView.Candidate(Uri.parse(it.uri), it.label, it.randomWriteConfirmedAt) }
            .toMutableList()
        if (localProvider.hasAccess()) candidates.add(RecommendedTargetsView.Candidate(localProvider.rootUri, "Internal shared storage"))
        savedConnections().filter { it.sourceTypeId in setOf("sftp", "smb", "ftp", "webdav") }.forEach { connection ->
            val path = if (connection.sourceTypeId == "smb" && !connection.options["initialShare"].isNullOrBlank())
                "/${connection.options["initialShare"].orEmpty().trim('/')}" else connection.options["initialPath"] ?: "/"
            val uri = Uri.Builder().scheme(connection.sourceTypeId).authority(connection.id).path(path).build()
            candidates.add(RecommendedTargetsView.Candidate(uri, connection.displayName))
        }
        return candidates.distinctBy { provider.refForUri(it.uri)?.toString() ?: it.uri.toString() }
            .filterNot { provider.refForUri(it.uri) == excluded }.take(10)
    }

    /** Only the operation worker waits for this choice; browsing and other jobs stay live. */
    private fun confirmStagedTransfer(request: com.infinifolder.storage.RandomAccessFallbackRequest,
                                     cancelled: () -> Boolean, chooseAnotherTarget: (Uri?) -> Unit): Boolean {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val answered = java.util.concurrent.CountDownLatch(1)
        val approved = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = java.util.concurrent.atomic.AtomicReference<android.app.AlertDialog?>()
        runOnUiThread {
            if (isDestroyed || isFinishing || cancelled() || answered.count == 0L) {
                answered.countDown()
                return@runOnUiThread
            }
            val context = dialogContext()
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(TextView(context).apply { text = request.message; setTextColor(setTextColorForTheme()) })
                addView(RecommendedTargetsView(context, provider, suggestedTransferTargets(request.destination)) { uri ->
                    answered.countDown()
                    dialog.get()?.dismiss()
                    chooseAnotherTarget(uri)
                })
            }
            dialog.set(dialogBuilder().setTitle("Choose a different target (recommended)")
                .setView(ScrollView(context).apply { addView(content) })
                .setPositiveButton("Choose another target") { _, _ ->
                    answered.countDown()
                    chooseAnotherTarget(null)
                }
                .setNegativeButton("Continue anyway") { _, _ ->
                    approved.set(true)
                    answered.countDown()
                }
                .setNeutralButton("Cancel") { _, _ -> answered.countDown() }
                .setOnCancelListener { answered.countDown() }
                .setOnDismissListener { answered.countDown() }
                .show())
        }
        try {
            while (!answered.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancelled() || isDestroyed || isFinishing) return false
            }
            return approved.get() && !cancelled()
        } finally {
            answered.countDown()
            runOnUiThread { dialog.getAndSet(null)?.dismiss() }
        }
    }

    private fun confirmBatchDelete(items: List<ResourceEntry>) {
        if (items.size == 1) {
            confirmDelete(items[0])
            return
        }
        val appItems = items.filter { AppsUri.isAppUri(it.uri) }
        val title = if (appItems.size == items.size) "Uninstall ${items.size} applications?" else "Delete ${items.size} items?"
        val hasFolders = items.any { it.metadata.isDirectory && !it.metadata.isSymbolicLink }
        val msg = when {
            appItems.isNotEmpty() && hasFolders -> "This will request Android to uninstall ${appItems.size} application${if (appItems.size == 1) "" else "s"}. Selected folders will be deleted recursively with all their contents. This cannot be undone."
            appItems.isNotEmpty() -> "This will request Android to uninstall ${appItems.size} application${if (appItems.size == 1) "" else "s"} and delete selected files. This cannot be undone."
            hasFolders -> "Selected folders will be deleted recursively, including all files and folders inside them. This cannot be undone."
            else -> "This cannot be undone."
        }
        val deleteLabel = if (hasFolders) "Delete all" else "Delete"
        dialogBuilder().setTitle(title).setMessage(msg)
            .setNegativeButton("Cancel", null).setPositiveButton(if (appItems.size == items.size) "Uninstall" else deleteLabel) { _, _ ->
                exitSelectionMode()
                if (appItems.isNotEmpty()) {
                    val nonApps = items.filterNot { AppsUri.isAppUri(it.uri) }
                    if (nonApps.isNotEmpty()) {
                        startBatchDelete(nonApps)
                    }
                    appItems.forEach { appEntry ->
                        val pkgName = AppsUri.getPackageName(appEntry.uri)
                        if (pkgName != null) uninstallApp(pkgName)
                    }
                } else {
                    startBatchDelete(items)
                }
            }.show()
    }

    private fun startBatchDelete(entries: List<ResourceEntry>, onFinished: (() -> Unit)? = null) {
        val mountedEntry = entries.firstOrNull {
            ArchiveUri.isArchiveUri(it.uri) && archiveProvider.checkMounted(it.uri)?.isMounted == true
        }
        if (mountedEntry != null) {
            val mountCheck = archiveProvider.checkMounted(mountedEntry.uri)!!
            notifyMountedPartitionBlocked(mountedEntry.metadata.name, mountCheck.reason)
            return
        }
        val count = entries.size
        val label = if (count == 1) "Delete ${entries[0].metadata.name}" else "Delete $count items"
        val id = operations.submit(label, null, count,
            conflictKeys = entries.flatMapTo(mutableSetOf(), ::operationEntryKeys),
            displayName = entries.singleOrNull()?.metadata?.name ?: "${entries.first().metadata.name} (+${count - 1})") { update, cancelled ->
            try { FileOperations.batchDelete(
                entries = entries,
                provider = provider,
                update = update,
                isCancelled = cancelled
            ) } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        refreshCurrentLocation()
                        onFinished?.invoke()
                    }
                }
            }
        }
        enqueueOperationWindow(id)
    }

    private fun promptCreateArchive(items: List<ResourceEntry>, initialName: String? = null, initialTargetUri: Uri? = null) {
        val defaultTarget = initialTargetUri?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
            ?: current?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
            ?: defaultWritableFolderUri()
            ?: return

        var targetUri: Uri = defaultTarget
        val defaultName = initialName ?: if (items.size == 1) {
            val stem = items[0].metadata.name.substringBeforeLast('.', items[0].metadata.name)
            "$stem.zip"
        } else {
            "Archive.zip"
        }

        val context = dialogContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val nameLabel = TextView(context).apply {
            text = "Archive file name:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        val input = EditText(context).apply {
            setText(defaultName)
            val dot = defaultName.lastIndexOf('.').takeIf { it > 0 } ?: defaultName.length
            setSelection(0, dot)
            setTextColor(setTextColorForTheme())
        }

        val targetHeader = TextView(context).apply {
            text = "Target location:"
            textSize = 12f
            setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(0, dp(14), 0, dp(4))
        }

        val targetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (dark) 0xFF2A2E28.toInt() else 0xFFEAECE7.toInt())
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }

        val targetIcon = TextView(context).apply {
            text = "📁"
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        }

        val targetPathView = TextView(context).apply {
            text = getPathDisplay(targetUri)
            textSize = 13f
            setTextColor(setTextColorForTheme())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val changeBtn = TextView(context).apply {
            text = "Change…"
            textSize = 13f
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            isFocusable = true
        }

        targetRow.addView(targetIcon)
        targetRow.addView(targetPathView, LinearLayout.LayoutParams(0, -2, 1f))
        targetRow.addView(changeBtn)

        val openFolderPicker: () -> Unit = {
            showFolderPicker("Select Target Location", targetUri) { selectedUri, displayPath ->
                targetUri = selectedUri
                targetPathView.text = displayPath
            }
        }
        targetRow.setOnClickListener { openFolderPicker() }
        changeBtn.setOnClickListener { openFolderPicker() }

        layout.addView(nameLabel)
        layout.addView(input)
        layout.addView(targetHeader)
        layout.addView(targetRow)

        dialogBuilder()
            .setTitle(if (items.size == 1) "Compress ${items[0].metadata.name}" else "Compress ${items.size} items")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isEmpty()) {
                    Toast.makeText(this, "Archive name cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (ArchiveUri.isArchiveUri(targetUri) || AppsUri.isAppsUri(targetUri)) {
                    Toast.makeText(this, "Cannot create archive in a read-only location.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val normalized = ArchiveWriter.normalizeArchiveName(entered)
                if (!ArchiveWriter.isSupportedFormat(normalized)) {
                    val ext = ArchiveFormats.archiveExtension(normalized)
                    val formatsDesc = ArchiveWriter.supportedFormatsDescription()
                    dialogBuilder()
                        .setTitle("Unsupported Format")
                        .setMessage("\"$ext\" is not a supported archive format.\n\nSupported formats: $formatsDesc\n\nPlease retype the target file name.")
                        .setPositiveButton("Retype") { _, _ ->
                            promptCreateArchive(items, normalized, targetUri)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@setPositiveButton
                }
                exitSelectionMode()
                startCreateArchive(items, normalized, targetUri)
            }
            .show()
    }

    private fun startCreateArchive(entries: List<ResourceEntry>, archiveName: String, destinationFolderUri: Uri) {
        val id = operations.submit("Create archive $archiveName", null, entries.size,
            conflictKeys = buildSet {
                entries.forEach { addAll(operationEntryKeys(it)) }
                add(operationTargetKey(resourceRef(destinationFolderUri), archiveName))
            }, displayName = archiveName, destination = destinationFolderUri.toString()) { update, cancelled ->
            val totalInputBytes = FileOperations.calculateTotalBytes(entries, provider, isCancelled = cancelled).takeIf { it > 0 }
            var destCreatedRef: ResourceRef? = null
            try {
                val ext = archiveName.substringAfterLast('.', "").lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val destRef = resourceRef(destinationFolderUri)
                val createResult = provider.createFile(destRef, archiveName, mime)
                if (createResult !is ProviderResult.Success) {
                    throw IllegalStateException((createResult as? ProviderResult.Failure)?.message ?: "Failed to create archive file")
                }
                destCreatedRef = createResult.value.ref
                val writeResult = provider.openWrite(destCreatedRef)
                if (writeResult !is ProviderResult.Success) {
                    throw IllegalStateException((writeResult as? ProviderResult.Failure)?.message ?: "Failed to open archive for writing")
                }
                writeResult.value.buffered().use { targetStream ->
                    ArchiveWriter.createArchive(
                        targetStream = targetStream,
                        archiveName = archiveName,
                        entries = entries,
                        provider = provider,
                        isCancelled = { cancelled() },
                        onProgress = { path ->
                            update(
                                OperationUpdate(
                                    totalBytes = totalInputBytes,
                                    currentItem = path.substringAfterLast('/'),
                                    message = "Archiving ${path.substringAfterLast('/')}"
                                )
                            )
                        },
                        onByteProgress = { bytesWritten, totalBytes, currentItem, speed ->
                            update(
                                OperationUpdate(
                                    bytes = bytesWritten,
                                    totalBytes = totalBytes ?: totalInputBytes,
                                    currentItem = currentItem.substringAfterLast('/'),
                                    bytesPerSecond = speed,
                                    message = "Archiving ${currentItem.substringAfterLast('/')}"
                                )
                            )
                        },
                        tempDir = cacheDir
                    )
                }
                if (cancelled()) throw InterruptedException("Cancelled")
                runOnUiThread {
                    if (current == destinationFolderUri) {
                        current?.let { load(it) }
                    }
                    Toast.makeText(this@MainActivity, "Created $archiveName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (destCreatedRef != null) {
                    try { provider.delete(destCreatedRef) } catch (_: Exception) {}
                }
                throw e
            }
        }
        enqueueOperationWindow(id)
    }
    private fun renameEntry(entry: ResourceEntry, onRenamed: ((ResourceEntry) -> Unit)? = null) {
        val currentName = entry.metadata.name
        val input = EditText(dialogContext()).apply {
            setText(currentName)
            val dot = currentName.lastIndexOf('.').takeIf { it > 0 && !entry.metadata.isDirectory } ?: currentName.length
            setSelection(0, dot)
            setTextColor(setTextColorForTheme())
        }
        dialogBuilder().setTitle("Rename").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == currentName) return@setPositiveButton
                io.execute {
                    val result = provider.rename(entry.ref, newName)
                    runOnUiThread {
                        if (result is ProviderResult.Success) {
                            val renamed = result.value
                            directoryIconCache.evictAll()
                            refreshInternalSharedTree()
                            if (current == entry.uri) {
                                current = renamed.uri
                                if (history.isNotEmpty() && history.last() == entry.uri) {
                                    history[history.lastIndex] = renamed.uri
                                    historyNames[historyNames.lastIndex] = renamed.metadata.name
                                }
                                renderLocation()
                                load(renamed.uri)
                            } else {
                                current?.let { load(it) }
                            }
                            onRenamed?.invoke(renamed)
                        } else {
                            Toast.makeText(this, (result as? ProviderResult.Failure)?.message ?: "Rename unsupported", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show()
    }
    private fun confirmDelete(entry: ResourceEntry, onFinished: (() -> Unit)? = null) {
        if (ArchiveUri.isArchiveUri(entry.uri)) {
            val mountCheck = archiveProvider.checkMounted(entry.uri)
            if (mountCheck?.isMounted == true) {
                notifyMountedPartitionBlocked(entry.metadata.name, mountCheck.reason)
                return
            }
        }
        if (AppsUri.isAppUri(entry.uri)) {
            val pkg = AppsUri.getPackageName(entry.uri)
            if (pkg != null) {
                dialogBuilder().setTitle("Uninstall ${entry.metadata.name.removeSuffix(".apk")}?")
                    .setMessage("This will request Android to uninstall this application.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Uninstall") { _, _ -> uninstallApp(pkg) }
                    .show()
            }
            return
        }
        val isDirectory = entry.metadata.isDirectory && !entry.metadata.isSymbolicLink
        val title = if (isDirectory) "Delete folder and its contents?" else "Delete ${entry.metadata.name}?"
        val message = if (isDirectory) {
            "This will recursively delete '${entry.metadata.name}' and all files and folders inside it. This cannot be undone."
        } else if (entry.ref.providerId == containerLinkProvider.id) "Only this container link will be deleted. Its target and contents stay untouched."
            else "This cannot be undone."
        dialogBuilder().setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null).setPositiveButton(if (isDirectory) "Delete all" else "Delete") { _, _ ->
                startBatchDelete(listOf(entry), onFinished)
            }.show()
    }
    private fun metadataLine(entry: ResourceEntry): String {
        val isShortcut = ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)
        if (isShortcut) {
            val shortcut = shortcutCache.get(entry.uri.toString())
            val target = when {
                shortcut == null -> "Target"
                WebMetadataResolver.isWebUri(shortcut.targetUri) -> shortcut.targetUri
                !shortcut.targetPath.isNullOrBlank() -> shortcut.targetPath
                else -> shortcut.targetUri.trimEnd('/').substringAfterLast('/').ifEmpty { shortcut.targetUri }
            }
            return "Shortcut ↗ · $target"
        }
        if (entry.metadata.isSymbolicLink) {
            val target = entry.metadata.linkTarget ?: "target"
            val prefix = if (entry.metadata.isBrokenLink) "Broken link ↗" else "Link ↗"
            val kind = if (entry.metadata.isDirectory) "Folder" else "File"
            return "$prefix · $kind · $target"
        }
        if (AppsUri.isCategoryUri(entry.uri)) {
            val count = appsProvider.getCategoryAppCount(entry.metadata.name)
            return "Virtual category · $count program${if (count == 1) "" else "s"}"
        }
        if (AppsUri.isAppUri(entry.uri)) {
            val pkg = AppsUri.getPackageName(entry.uri) ?: ""
            val size = entry.metadata.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(this, it) }
            val parts = listOfNotNull(pkg.takeIf { it.isNotEmpty() }, size)
            return parts.joinToString(" · ").ifEmpty { "Installed application" }
        }
        val size = entry.metadata.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(this, it) } ?: "—"
        val date = entry.metadata.modifiedAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: ""
        return if (entry.metadata.isDirectory) "Folder · $date" else "$size · $date"
    }
    private fun iconFor(m: ResourceMetadata): String = FileIconView.fallback(m)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (forwardingHomeShortcut) return
        outState.putBoolean("operation_window_hidden", isOperationWindowHidden)
        outState.putString("logical_location_prefix", logicalLocationPrefix)
        outState.putString("current_program_name", currentProgramName)
        outState.putStringArrayList("history", ArrayList(history.map(Uri::toString)))
        outState.putStringArrayList("history_names", ArrayList(historyNames))
        outState.putBoolean("storage_access_pending", waitingForStorageAccess)
        outState.putString("pending_tree_source", pendingTreeSourceKey)
        outState.putString("pending_tree_target", pendingTreeTarget?.toString())
        outState.putString("pending_tree_authority", pendingTreeAuthority)
        outState.putString("pending_icon_target_type", pendingIconTargetType)
        outState.putString("pending_icon_target_uri", pendingIconTargetUri?.toString())
        outState.putString("pending_icon_target_mime", pendingIconTargetMime)
    }
    private fun navigateUp() {
        val location = current ?: return
        if (navigatingUp) {
            com.infinifolder.storage.DiagnosticLog.record("browser.back.busy", "location=$location")
            return
        }
        if (selectionMode) exitSelectionMode()
        // Mounted paths have an explicit parent. Navigating there must not wait
        // for another remote directory listing or compete with copy IO.
        if (ArchiveUri.isArchiveUri(location) && ArchiveUri.getEntryPath(location).isNotEmpty()) {
            val parentPath = ArchiveUri.getEntryPath(location).substringBeforeLast('/', "")
            val source = ArchiveUri.getSourceUri(location)
            val parentUri = ArchiveUri.build(source, parentPath, deleted = ArchiveUri.isDeletedView(location))
            val index = history.indexOfLast { sameResourceUri(it, parentUri) }
            val parentName = historyNames.getOrNull(index) ?: parentPath.substringAfterLast('/').ifEmpty {
                source.lastPathSegment?.substringAfterLast('/') ?: "Container"
            }
            com.infinifolder.storage.DiagnosticLog.record("browser.back.parent", "source=mounted-path from=$location to=$parentUri")
            openParentLocation(parentUri to parentName)
            return
        }
        // Leaving a mounted container root returns to the directory holding
        // its backing file, including a file in an enclosing archive.
        val resource = if (ArchiveUri.isArchiveUri(location) && ArchiveUri.getEntryPath(location).isEmpty())
            ArchiveUri.getSourceUri(location) else location
        fun treeParent(nodes: List<TreeNode>, parent: Uri? = null): Uri? {
            for (node in nodes) {
                if (node.uri == resource && parent != null) return parent
                treeParent(node.children, node.uri ?: parent)?.let { return it }
            }
            return null
        }
        val knownParent = knownResourceParents[resource] ?: treeParent(treeRoots)
        val generation = browserNavigationGeneration
        val requestedAt = SystemClock.uptimeMillis()
        com.infinifolder.storage.DiagnosticLog.record("browser.back.resolve", "location=$location generation=$generation")
        navigatingUp = true
        io.execute {
            val result: ProviderResult<Pair<Uri, String>?> = try {
                val parent = when (val resolved = provider.parent(resourceRef(resource))) {
                    is ProviderResult.Success -> resolved.value
                    is ProviderResult.Unsupported -> knownParent?.let(::resourceRef)
                        ?: throw java.io.IOException("This storage provider does not report a parent folder")
                    is ProviderResult.Failure -> throw java.io.IOException(resolved.message, resolved.cause)
                }
                if (parent == null) ProviderResult.Success(null) else {
                    when (val info = provider.stat(parent)) {
                        is ProviderResult.Success -> {
                            if (!info.value.metadata.isDirectory) throw java.io.IOException("The parent location is not a directory")
                            ProviderResult.Success(info.value.uri to info.value.metadata.name)
                        }
                        is ProviderResult.Failure -> throw java.io.IOException(info.message, info.cause)
                        is ProviderResult.Unsupported -> throw java.io.IOException("Cannot inspect the parent folder")
                    }
                }
            } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open the parent folder", e) }
            runOnUiThread {
                navigatingUp = false
                if (isFinishing || isDestroyed || homeMode || current != location || browserNavigationGeneration != generation) {
                    com.infinifolder.storage.DiagnosticLog.record("browser.back.discarded", "location=$location elapsedMs=${SystemClock.uptimeMillis() - requestedAt} home=$homeMode locationChanged=${current != location} navigationChanged=${browserNavigationGeneration != generation}")
                    return@runOnUiThread
                }
                when (result) {
                    is ProviderResult.Success -> {
                        com.infinifolder.storage.DiagnosticLog.record("browser.back.parent", "source=provider from=$location to=${result.value?.first} elapsedMs=${SystemClock.uptimeMillis() - requestedAt}")
                        openParentLocation(result.value)
                    }
                    is ProviderResult.Failure -> {
                        com.infinifolder.storage.DiagnosticLog.record("browser.back.failed", "location=$location elapsedMs=${SystemClock.uptimeMillis() - requestedAt}", result.cause)
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                    is ProviderResult.Unsupported -> Unit
                }
            }
        }
    }

    private fun openParentLocation(parent: Pair<Uri, String>?) {
        if (parent == null) {
            when (logicalLocationPrefix) {
                "Application public" -> showApplicationPublic()
                "Installed Applications" -> openInstalledApps()
                "Internal shared storage" -> showInternalShared()
                else -> showHomeRoot()
            }
            return
        }
        val index = history.indexOfLast { sameResourceUri(it, parent.first) }
        if (index >= 0) {
            while (history.size > index + 1) history.removeAt(history.lastIndex)
            while (historyNames.size > index + 1) historyNames.removeAt(historyNames.lastIndex)
        } else {
            history.clear(); historyNames.clear()
            history.add(parent.first); historyNames.add(parent.second)
        }
        navigate(parent.first, push = false, displayName = parent.second)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        com.infinifolder.storage.DiagnosticLog.record("browser.back.request", "home=$homeMode homePage=$homePage location=$current selecting=$selectionMode operationDialog=${currentProgressDialog?.isShowing == true}")
        when {
            !homeMode -> navigateUp()
            selectionMode -> exitSelectionMode()
            homeMode && homePage != "root" -> showHomeRoot()
            else -> {
                val hasRunningOp = operations.snapshots().any {
                    it.state == OperationState.RUNNING || it.state == OperationState.QUEUED
                }
                if (hasRunningOp) {
                    // Auto-hide: keep the operation running in the background without prompting
                    ensureBackgroundPermissionAndMinimize()
                } else {
                    if (isTaskRoot) moveTaskToBack(true) else finish()
                }
            }
        }
    }

    private fun confirmBackgroundPersistenceOnExit() {
        dialogBuilder()
            .setTitle("Operation in progress")
            .setMessage("A file operation is currently running. Do you want it to continue in the background?")
            .setPositiveButton("Keep running") { _, _ ->
                ensureBackgroundPermissionAndMinimize()
            }
            .setNegativeButton("Cancel operation & exit") { _, _ ->
                operations.snapshots().forEach {
                    if (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) {
                        operations.cancel(it.id, "exit-dialog")
                    }
                }
                OperationService.stop(this)
                finish()
            }
            .setNeutralButton("Stay in app", null)
            .show()
    }

    private fun ensureBackgroundPermissionAndMinimize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingMinimizeAfterNotificationPermission = true
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9002)
                return
            }
        }
        OperationService.start(this)
        moveTaskToBack(true)
    }

    private var lastDumpRefresh = 0L

    private fun refreshCurrentLocation() {
        if (homeMode) when (homePage) {
            "internal_shared" -> showInternalShared(false)
            "application_public" -> showApplicationPublic()
            else -> showHomeRoot()
        } else current?.let {
            if (AppsUri.isAppsUri(it)) appsProvider.invalidateCache()
            load(it)
        }
    }

    override fun debugCaptureWindow(): android.view.Window? =
        currentProgressDialog?.takeIf { it.isShowing }?.window

    override fun debugDumpState(): String = buildString {
        append("dialogOperation=$activeDialogOpId hidden=$isOperationWindowHidden showing=${currentProgressDialog?.isShowing == true}")
        append("\nstatus=${progressStatusView?.text}\nfileLabel=${progressFileLabel?.text}\nrateEta=${progressRateView?.text}")
        append("\nfileBar=${progressFileBar?.progress} indeterminate=${progressFileBar?.isIndeterminate}")
        append(" overallBar=${progressBar?.progress} indeterminate=${progressBar?.isIndeterminate}")
        operations.snapshots().takeLast(10).forEach { op ->
            append("\noperation=${op.id} state=${op.state} message=${op.message} bytes=${op.bytes}/${op.totalBytes}")
            append(" fileBytes=${op.currentItemBytes}/${op.currentItemTotalBytes} pieces=${op.torrentPiecesChecked}/${op.torrentPiecesTotal}")
            append(" remaining=${op.remainingBytes} rate=${op.bytesPerSecond} eta=${op.formatEta()}")
        }
    }

    override fun onDebugDumpFinished() {
        lastDumpRefresh = DebugTools.dumpGeneration
        refreshCurrentLocation()
    }

    override fun onResume() {
        super.onResume()
        Thread({ TermuxEditorAvailability.probe(applicationContext) }, "TermuxEditorProbe").start()
        if (forwardingHomeShortcut) return
        browserResumed = true
        OperationService.start(this)
        if (lastDumpRefresh != DebugTools.dumpGeneration) onDebugDumpFinished()
        val snapshots = operations.snapshots()
        updateOperationVisibilityUi()
        if (pendingOperationsIntent) {
            pendingOperationsIntent = false
            showOperations()
        }
        if (::localProvider.isInitialized) {
            val access = localProvider.hasAccess()
            if (waitingForStorageAccess || access != hadStorageAccess) {
                waitingForStorageAccess = false
                hadStorageAccess = access
                showHomeRoot()
                showInternalShared(false)
                return
            }
        }
        if (::shared.isInitialized && homeItems.isNotEmpty()) {
            resolveExistingHomeGrants()
            discoverDocumentProviderRoots()
        }
    }

    override fun onPause() {
        browserResumed = false
        if (!forwardingHomeShortcut) {
            currentProgressDialog?.dismiss()
            operationOverview?.dismiss()
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (forwardingHomeShortcut) return
        val hasActive = operations.snapshots().any {
            (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) &&
            !it.id.startsWith("playback_")
        }
        if (hasActive) {
            OperationService.start(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (forwardingHomeShortcut) return
        com.infinifolder.theme.ThemeManager.handleConfigurationChanged(this, newConfig)
        updateBrowserPanes()
        renderTree()
        if (!homeMode) renderEntries()
    }

    override fun onDestroy() {
        if (forwardingHomeShortcut) { super.onDestroy(); return }
        diskUsageExplorer?.close()
        diskUsageExplorer = null
        operationOverview?.dismiss()
        operationUiHandler.removeCallbacks(operationStatusTick)
        operationUiHandler.removeCallbacks(operationUiUpdate)
        pendingOperationSnapshots.set(null)
        cancelDragEdgeAutoScroll()
        backNavigation?.close()
        backNavigation = null
        cancelDragContainerHover()
        operations.removeListener(operationsListener)
        com.infinifolder.ops.OperationFeedback.removeListener(operationCompletionListener)
        com.infinifolder.storage.StorageActivity.removeListener(storageActivityListener)
        com.infinifolder.theme.ThemeManager.removeListener(themeListener)
        super.onDestroy()
    }

    private fun dialogContext() = ContextThemeWrapper(this, if (dark) R.style.AppTheme_Dark else R.style.AppTheme)
    private fun dialogBuilder() = android.app.AlertDialog.Builder(dialogContext())
    private fun displayNameFor(treeUri: Uri): String? = try {
        if (treeUri == ContainerLinksProvider.ROOT) "Virtual root" else
        if (treeUri == com.infinifolder.storage.PartialDownloadsProvider.ROOT) "Partial downloads" else
        if (AppsUri.isAppsUri(treeUri)) {
            when {
                AppsUri.isAppUri(treeUri) -> {
                    val pkg = AppsUri.getPackageName(treeUri)
                    pkg?.let { appsProvider.getAppLabel(it) } ?: "Application"
                }
                AppsUri.isCategoryUri(treeUri) -> AppsUri.getCategoryName(treeUri) ?: "Category"
                else -> "Installed Applications"
            }
        } else if (ArchiveUri.isArchiveUri(treeUri)) {
            val entryPath = ArchiveUri.getEntryPath(treeUri)
            if (entryPath.isEmpty()) displayNameFor(ArchiveUri.getSourceUri(treeUri)) ?: "Archive"
            else entryPath.substringAfterLast('/')
        } else if (SftpUri.isSftpUri(treeUri)) {
            val remotePath = SftpUri.getRemotePath(treeUri)
            val connId = SftpUri.getConnectionId(treeUri)
            val conn = savedConnections().firstOrNull { it.id == connId }
            if (remotePath == "/" || remotePath.isEmpty()) {
                conn?.displayName ?: "SFTP Server"
            } else {
                remotePath.substringAfterLast('/')
            }
        } else if (FtpUri.isFtpUri(treeUri)) {
            val remotePath = FtpUri.getRemotePath(treeUri)
            val connId = FtpUri.getConnectionId(treeUri)
            val conn = savedConnections().firstOrNull { it.id == connId }
            if (remotePath == "/" || remotePath.isEmpty()) {
                conn?.displayName ?: "FTP Server"
            } else {
                remotePath.substringAfterLast('/')
            }
        } else if (SmbUri.isSmbUri(treeUri)) {
            val remotePath = SmbUri.getRemotePath(treeUri)
            val connId = SmbUri.getConnectionId(treeUri)
            val conn = savedConnections().firstOrNull { it.id == connId }
            if (remotePath == "/" || remotePath.isEmpty()) {
                conn?.displayName ?: "SMB Server"
            } else {
                remotePath.substringAfterLast('/')
            }
        } else if (WebDavUri.owns(treeUri)) {
            val conn=savedConnections().firstOrNull { it.id==WebDavUri.id(treeUri) }; val path=WebDavUri.path(treeUri)
            if(path=="/") conn?.displayName ?: "WebDAV" else path.substringAfterLast('/')
        } else if (localProvider.owns(treeUri)) {
            if (treeUri == localProvider.rootUri) "Internal shared storage" else runCatching { localProvider.fileFor(treeUri).name }.getOrNull()
        } else if (treeUri.scheme == "file") {
            File(treeUri.path ?: "").name.ifEmpty { "Root" }
        } else {
            val id = uriDocumentId(treeUri)
            val document = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, id) }.getOrNull()
            if (document != null) {
                contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } else null
        }
    } catch (_: Exception) { null }

    /** Resolve provider document IDs to user-facing names; never interpret IDs as POSIX paths. */
    private fun displayPathFor(treeUri: Uri): String? = try {
        if (treeUri == com.infinifolder.storage.PartialDownloadsProvider.ROOT) "Virtual root / Partial downloads" else
        if (AppsUri.isAppsUri(treeUri)) {
            when {
                AppsUri.isAppUri(treeUri) -> {
                    val cat = AppsUri.getCategoryName(treeUri) ?: "Apps"
                    val pkg = AppsUri.getPackageName(treeUri)
                    val label = pkg?.let { appsProvider.getAppLabel(it) } ?: pkg ?: "App"
                    "Installed Applications / $cat / $label"
                }
                AppsUri.isCategoryUri(treeUri) -> {
                    val cat = AppsUri.getCategoryName(treeUri) ?: "Apps"
                    "Installed Applications / $cat"
                }
                else -> "Installed Applications"
            }
        } else if (ArchiveUri.isArchiveUri(treeUri)) {
            val source = ArchiveUri.getSourceUri(treeUri)
            val path = ArchiveUri.getEntryPath(treeUri)
            val base = displayPathFor(source) ?: displayNameFor(source) ?: source.lastPathSegment ?: "Archive"
            if (path.isEmpty()) base else "$base / $path"
        } else if (SftpUri.isSftpUri(treeUri)) {
            val connId = SftpUri.getConnectionId(treeUri)
            val conn = savedConnections().firstOrNull { it.id == connId }
            val connName = conn?.displayName ?: "SFTP"
            val remotePath = SftpUri.getRemotePath(treeUri)
            if (remotePath == "/" || remotePath.isEmpty()) connName else "$connName / ${remotePath.trimStart('/')}"
        } else if (FtpUri.isFtpUri(treeUri)) {
            val connId = FtpUri.getConnectionId(treeUri)
            val conn = savedConnections().firstOrNull { it.id == connId }
            val connName = conn?.displayName ?: "FTP"
            val remotePath = FtpUri.getRemotePath(treeUri)
            if (remotePath == "/" || remotePath.isEmpty()) connName else "$connName / ${remotePath.trimStart('/')}"
        } else if (SmbUri.isSmbUri(treeUri)) {
            val connId = SmbUri.getConnectionId(treeUri)
            val conn = savedConnections().firstOrNull { it.id == connId }
            val connName = conn?.displayName ?: "SMB"
            val remotePath = SmbUri.getRemotePath(treeUri)
            if (remotePath == "/" || remotePath.isEmpty()) connName else "$connName / ${remotePath.trimStart('/')}"
        } else if (WebDavUri.owns(treeUri)) {
            val conn=savedConnections().firstOrNull { it.id==WebDavUri.id(treeUri) }; val path=WebDavUri.path(treeUri)
            if(path=="/") conn?.displayName ?: "WebDAV" else "${conn?.displayName ?: "WebDAV"} / ${path.trimStart('/')}"
        } else if (localProvider.owns(treeUri)) {
            runCatching { localProvider.fileFor(treeUri).path }.getOrNull()
        } else if (treeUri.scheme == "file") {
            treeUri.path
        } else {
            val selectedId = uriDocumentId(treeUri)
            val selectedDocument = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, selectedId) }.getOrNull() ?: return null
            val path = runCatching { DocumentsContract.findDocumentPath(contentResolver, selectedDocument)?.path }.getOrNull() ?: return null
            val names = path.mapNotNull { documentId ->
                val uri = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId) }.getOrNull() ?: return@mapNotNull null
                contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
            names.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        }
    } catch (_: Exception) { null }

    private fun uriDocumentId(uri: Uri): String = try {
        when {
            uri.pathSegments.firstOrNull() == "document" || uri.pathSegments.getOrNull(2) == "document" ->
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?: uri.lastPathSegment ?: uri.path?.removePrefix("/") ?: uri.toString()
            uri.pathSegments.firstOrNull() == "tree" ->
                runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                    ?: uri.lastPathSegment ?: uri.path?.removePrefix("/") ?: uri.toString()
            else ->
                uri.lastPathSegment ?: uri.path?.removePrefix("/") ?: uri.toString()
        }
    } catch (_: Throwable) {
        uri.lastPathSegment ?: uri.path?.removePrefix("/") ?: uri.toString()
    }

    /** SAF document IDs are opaque; pair them with their provider authority without parsing them as paths. */
    private fun sourceIdentity(uri: Uri): String = try {
        when {
            AppsUri.isAppsUri(uri) -> uri.toString()
            ArchiveUri.isArchiveUri(uri) -> uri.toString()
            SftpUri.isSftpUri(uri) -> uri.toString()
            FtpUri.isFtpUri(uri) -> uri.toString()
            SmbUri.isSmbUri(uri) -> uri.toString()
            WebDavUri.owns(uri) -> uri.toString()
            localProvider.owns(uri) -> uri.toString()
            uri.scheme != "content" -> uri.toString()
            else -> "${uri.authority.orEmpty()}|${uriDocumentId(uri)}"
        }
    } catch (_: Throwable) {
        uri.toString()
    }
    private fun isPrimaryExternalStorageUri(uri: Uri): Boolean = try {
        uri.scheme == "content" && uri.authority == externalStorageDocumentsAuthority && uriDocumentId(uri).substringBefore(':') == "primary"
    } catch (_: Exception) { false }
}
