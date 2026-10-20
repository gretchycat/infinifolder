package com.infinifolder

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.os.storage.StorageManager
import android.graphics.Bitmap
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ContextThemeWrapper
import android.util.TypedValue
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.core.content.FileProvider
import com.infinifolder.apps.AppsStorageProvider
import com.infinifolder.apps.AppsUri
import com.infinifolder.apps.InstalledAppItem
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveStorageProvider
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.archive.ArchiveWriter
import com.infinifolder.shortcut.ShortcutInfo
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
import android.os.Build
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageProviderRegistry
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

class MainActivity : Activity() {
    private val io = Executors.newFixedThreadPool(4)
    private val accessIo = Executors.newFixedThreadPool(2)
    private val imageIo = Executors.newFixedThreadPool(4)
    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    /** Preview keys the platform decoders could not read; avoids re-reading unsupported files on every render. */
    private val failedThumbnails = LruCache<String, Boolean>(512)
    /** Maps directory keys to their resolved .icon.* or cover.* URI string, or "" if none exists. */
    private val directoryIconCache = LruCache<String, String>(512)
    /** Caches parsed shortcut (.lnk) metadata by entry URI string. */
    private val shortcutCache = LruCache<String, ShortcutInfo>(2048)

    private data class PendingCardProbe(
        val view: View,
        val generation: Int,
        val startProbe: () -> Unit
    )
    private val pendingCardProbes = mutableListOf<PendingCardProbe>()
    private var visibleProbeScheduled = false
    private val customIconStore by lazy { CustomIconStore(filesDir) }
    private val thumbnailDecoder by lazy {
        ThumbnailDecoder(contentResolver) { uri ->
            when {
                ArchiveUri.isArchiveUri(uri) -> archiveProvider.openStream(uri)
                uri.scheme == "file" -> uri.path?.let { File(it).inputStream() }
                else -> null
            }
        }
    }
    private lateinit var provider: StorageProvider
    private lateinit var localProvider: LocalStorageProvider
    private lateinit var archiveProvider: ArchiveStorageProvider
    private lateinit var appsProvider: AppsStorageProvider
    private var internalSharedEntries = emptyList<ResourceEntry>()
    private var internalListGeneration = 0
    private var installedAppCategories = emptyList<String>()
    private var appsListGeneration = 0
    private var waitingForStorageAccess = false
    private var hadStorageAccess = false
    private val providerRegistry = StorageProviderRegistry().apply { register(SafProviderFactory()) }
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var createButton: TextView
    private lateinit var content: LinearLayout
    private lateinit var browserArea: LinearLayout
    private lateinit var fileScroll: ScrollView
    private lateinit var treePanel: LinearLayout
    private lateinit var treeContent: LinearLayout
    private lateinit var breadcrumb: LinearLayout
    private lateinit var status: TextView
    private lateinit var shared: android.content.SharedPreferences
    private val operations = AppOperations.coordinator
    private val history = mutableListOf<Uri>()
    private val historyNames = mutableListOf<String>()
    private var current: Uri? = null
    @Volatile private var loadGeneration = 0
    private var entries = emptyList<ResourceEntry>()
    private var listingIsLoading = false
    private var listingNotice: String? = null
    private var listingError: String? = null
    private var grid = true
    private var dark = false
    private var sortByName = true
    private var showHidden = false
    private var selectionMode = false
    private val selectedUris = mutableSetOf<Uri>()
    private lateinit var operationsButton: TextView
    private lateinit var unhideOperationBar: LinearLayout
    private lateinit var unhideOperationLabel: TextView
    private lateinit var unhideOperationButton: TextView
    private val operationWindowQueue = ArrayDeque<String>()
    private var isOperationWindowHidden = false
    private var currentProgressDialog: android.app.AlertDialog? = null
    private var activeDialogOpId: String? = null
    private var progressTitleView: TextView? = null
    private var progressQueueView: TextView? = null
    private var progressCurrentItemView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressBytesView: TextView? = null
    private var progressRateView: TextView? = null
    private var progressCancelButton: Button? = null
    private var progressHideButton: Button? = null
    private var pendingMinimizeAfterNotificationPermission = false
    private var iconSizeDp = 56
    private var treeVisible = false
    private var homeMode = false
    private var homePage = "root"
    private var logicalLocationPrefix: String? = null
    private val treeRoots = mutableListOf<TreeNode>()
    private val homeItems = mutableListOf<HomeItem>()
    private val applicationPublicItems = mutableListOf<HomeItem>()
    private val pickTreeRequest = 4101
    private val pickCustomIconRequest = 4103
    private var pendingTreeSourceKey: String? = null
    private var pendingTreeTarget: Uri? = null
    private var pendingTreeAuthority: String? = null
    private var pendingIconTargetType: String? = null
    private var pendingIconTargetUri: Uri? = null
    private var pendingIconTargetMime: String? = null
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
        val providerAuthority: String? = null
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operations.addListener(operationsListener)
        shared = getSharedPreferences("browser", MODE_PRIVATE)
        grid = shared.getBoolean("grid", true); dark = shared.getBoolean("dark", false); sortByName = shared.getBoolean("sort_name", true)
        showHidden = shared.getBoolean("show_hidden", false)
        treeVisible = shared.getBoolean("tree_visible", false)
        iconSizeDp = shared.getInt("icon_size_dp", 56).coerceIn(32, 96)
        localProvider = LocalStorageProvider(applicationContext)
        archiveProvider = ArchiveStorageProvider(applicationContext, localProvider)
        appsProvider = AppsStorageProvider(applicationContext)
        provider = RoutingStorageProvider(requireNotNull(providerRegistry.create("saf", contentResolver)), localProvider, archiveProvider, appsProvider)
        hadStorageAccess = localProvider.hasAccess()
        waitingForStorageAccess = savedInstanceState?.getBoolean("storage_access_pending", false) ?: false
        pendingTreeSourceKey = savedInstanceState?.getString("pending_tree_source")
        pendingTreeTarget = savedInstanceState?.getString("pending_tree_target")?.let(Uri::parse)
        pendingTreeAuthority = savedInstanceState?.getString("pending_tree_authority")
        pendingIconTargetType = savedInstanceState?.getString("pending_icon_target_type")
        pendingIconTargetUri = savedInstanceState?.getString("pending_icon_target_uri")?.let(Uri::parse)
        pendingIconTargetMime = savedInstanceState?.getString("pending_icon_target_mime")
        buildUi()
        shared.edit().remove("tree_source_installed_apps").apply()
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
    }

    private fun buildUi() {
        val bg = if (dark) 0xFF171A16.toInt() else 0xFFF5F4EF.toInt()
        val fg = if (dark) 0xFFF0F0E9.toInt() else 0xFF20231F.toInt()
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT >= 26) window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
        toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        button("☰", fg) { showSidebar() }; button("Home", fg) { showHomeRoot() }
        createButton = button("＋", fg) { onCreateAction() }
        button("↻", fg) {
            if (homeMode) when (homePage) {
                "internal_shared" -> showInternalShared(false)
                "application_public" -> showApplicationPublic()
                else -> showHomeRoot()
            } else current?.let {
                if (AppsUri.isAppsUri(it)) appsProvider.invalidateCache()
                load(it)
            }
        }
        button(if (grid) "☷" else "▦", fg) { grid = !grid; shared.edit().putBoolean("grid", grid).apply(); renderCurrentContents() }
        operationsButton = button("⚡", 0xFFFFB300.toInt()) { unhideOperationWindow() }
        operationsButton.visibility = if (operations.activeSnapshots().isNotEmpty()) View.VISIBLE else View.GONE
        button("⋮", fg) { showOptions() }
        root.addView(toolbar)
        breadcrumb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(breadcrumb, android.view.ViewGroup.LayoutParams(-2, -2))
        })
        unhideOperationBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (dark) 0xFF283424.toInt() else 0xFFDCEAD5.toInt())
                setStroke(dp(1), if (dark) 0xFF4CAF50.toInt() else 0xFF2E7D32.toInt())
            }
            setPadding(dp(12), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            visibility = View.GONE
            setOnClickListener { unhideOperationWindow() }
        }
        unhideOperationLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(setTextColorForTheme())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        unhideOperationButton = TextView(this).apply {
            text = "Unhide"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF1B5E20.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (dark) 0xFF3A4935.toInt() else 0xFFC5DEC0.toInt())
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { unhideOperationWindow() }
        }
        unhideOperationBar.addView(unhideOperationLabel)
        unhideOperationBar.addView(unhideOperationButton)
        root.addView(unhideOperationBar)
        updateOperationVisibilityUi()
        status = TextView(this).apply {
            textSize = 13f; setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()); setPadding(dp(4), dp(4), dp(4), dp(8))
            setOnClickListener {
                if (operations.activeSnapshots().isNotEmpty()) {
                    unhideOperationWindow()
                }
            }
        }
        root.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fileScroll = ScrollView(this).apply {
            addView(content)
            viewTreeObserver.addOnScrollChangedListener { scheduleVisibleProbe() }
        }
        browserArea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        treePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) 0xFF20241F.toInt() else 0xFFEAE9E3.toInt())
            visibility = if (treeVisible) View.VISIBLE else View.GONE
        }
        treePanel.addView(TextView(this).apply {
            text = "VIRTUAL FILE SYSTEM"; textSize = 12f; setTextColor(if (dark) 0xFFB8CCAF.toInt() else 0xFF52644F.toInt())
            setPadding(dp(12), dp(12), dp(8), dp(8))
        })
        treeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        treePanel.addView(ScrollView(this).apply { addView(treeContent) }, LinearLayout.LayoutParams(-1, 0, 1f))
        browserArea.addView(treePanel, LinearLayout.LayoutParams(dp(180), -1))
        browserArea.addView(fileScroll, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(browserArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        updateBrowserPanes()
        rebuildTreeRoots()
    }

    private fun button(label: String, color: Int, click: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = label; textSize = if (label == "InfiniFolder") 16f else 21f; setTextColor(color); gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10)); minWidth = dp(if (label == "InfiniFolder") 112 else 44); minimumHeight = dp(48); contentDescription = label
            setOnClickListener { click() }
        }
        toolbar.addView(view)
        return view
    }
    private fun updateCreateAction() {
        if (!::createButton.isInitialized) return
        val inArchive = current?.let { ArchiveUri.isArchiveUri(it) } ?: false
        val inApps = current?.let { AppsUri.isAppsUri(it) } ?: false
        createButton.visibility = if ((homeMode && homePage != "root") || inArchive || inApps) View.GONE else View.VISIBLE
        createButton.text = if (homeMode) "＋" else "📁+"
        createButton.contentDescription = if (homeMode) "Add saved connection" else "Create directory"
    }
    private fun onCreateAction() {
        if (current?.let { AppsUri.isAppsUri(it) } == true) return
        if (homeMode && homePage == "root") showAddConnectionDialog() else if (!homeMode) showCreateDirectoryDialog()
    }
    private fun showCreateDirectoryDialog() {
        val parent = current ?: return
        if (AppsUri.isAppsUri(parent)) {
            Toast.makeText(this, "Installed applications directory is read-only.", Toast.LENGTH_SHORT).show()
            return
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
                                    is ProviderResult.Failure -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
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
        dialogBuilder().setTitle("Add saved connection").setMessage("Connection details are saved locally. Network access is not implemented yet, and credentials are not stored.")
            .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val displayName = name.text.toString().trim()
                        if (displayName.isEmpty()) { name.error = "Enter a name"; return@setOnClickListener }
                        val descriptor = types.getOrNull(type.selectedItemPosition) ?: return@setOnClickListener
                        val connection = ProviderConnection(
                            sourceTypeId = descriptor.id,
                            displayName = displayName,
                            endpoint = endpoint.text.toString().trim().ifEmpty { null }
                        )
                        saveConnection(connection)
                        dialog.dismiss()
                        showHomeRoot()
                    }
                }
                dialog.show()
            }
    }
    private fun savedConnections(): List<ProviderConnection> = try {
        val json = JSONArray(shared.getString("saved_connections", "[]"))
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val type = item.optString("type").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            ProviderConnection(item.optString("id"), type, name, item.optString("endpoint").takeIf(String::isNotBlank))
        }
    } catch (_: Exception) { emptyList() }
    private fun saveConnection(connection: ProviderConnection) {
        val all = (savedConnections().filterNot { it.id == connection.id } + connection)
        val json = JSONArray()
        all.forEach { item -> json.put(JSONObject().put("id", item.id).put("type", item.sourceTypeId).put("name", item.displayName).put("endpoint", item.endpoint ?: "")) }
        shared.edit().putString("saved_connections", json.toString()).apply()
    }
    private fun showSavedConnectionActions(connection: ProviderConnection) {
        dialogBuilder().setTitle(connection.displayName).setItems(arrayOf("Properties", "Remove saved connection")) { _, which ->
            when (which) {
                0 -> dialogBuilder().setMessage("Type: ${connection.sourceTypeId}\nServer: ${connection.endpoint ?: "Not set"}\nStatus: saved; provider support is not implemented").setPositiveButton("OK", null).show()
                1 -> dialogBuilder().setMessage("Remove ${connection.displayName} from the Virtual root?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        val json = JSONArray()
                        savedConnections().filterNot { it.id == connection.id }.forEach { item ->
                            json.put(JSONObject().put("id", item.id).put("type", item.sourceTypeId).put("name", item.displayName).put("endpoint", item.endpoint ?: ""))
                        }
                        shared.edit().putString("saved_connections", json.toString()).apply()
                        showHomeRoot()
                    }.show()
            }
        }.show()
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
            if (localProvider.owns(target) || AppsUri.isAppsUri(target) || key == "internal_shared" || key == "application_public" || key == "installed_apps") return@mapNotNull null
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
                if (candidate != null) applicationPublicItems[candidateIndex] = candidate.copy(
                    treeUri = uri,
                    detail = "App-provided folder",
                    action = { openRoot(uri, "Application public", false) },
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
            pendingIconTargetType = null
            pendingIconTargetUri = null
            pendingIconTargetMime = null
            if (resultCode == RESULT_OK) {
                val pickedImageUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
                if (pickedImageUri != null) {
                    when (targetType) {
                        "folder" -> if (targetUri != null) applyFolderIcon(targetUri, pickedImageUri)
                        "shortcut" -> if (targetUri != null) applyShortcutIcon(targetUri, pickedImageUri)
                        "mime" -> if (targetMime != null) applyMimeTypeIcon(targetMime, pickedImageUri)
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
            !localProvider.owns(uri) && AppDocumentSources.isAppAuthority(uri.authority) -> "Application public"
            uri != localProvider.rootUri && (localProvider.owns(uri) || isPrimaryExternalStorageUri(uri)) -> "Internal shared storage"
            else -> null
        }
        shared.edit().putString("root", uri.toString()).apply()
        history.clear(); historyNames.clear(); current = null
        status.text = "Loading folder…"
        io.execute {
            val name = displayNameFor(uri) ?: "Storage"
            val path = displayPathFor(uri) ?: name
            val localRoute = if (localProvider.owns(uri)) runCatching {
                var folder = localProvider.store.root
                buildList {
                    add(localProvider.rootUri to "Internal shared storage")
                    localProvider.store.components(localProvider.fileFor(uri)).forEach { component ->
                        folder = java.io.File(folder, component)
                        add(localProvider.uriFor(folder) to component)
                    }
                }
            }.getOrNull() else null
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
        if (localProvider.owns(uri) || AppsUri.isAppsUri(uri) || ArchiveUri.isArchiveUri(uri) || AppDocumentSources.isAppAuthority(uri.authority) ||
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
    private fun navigate(uri: Uri, push: Boolean = true, displayName: String? = null) {
        if (selectionMode) exitSelectionMode()
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
        load(uri)
    }
    private fun renderLocation() {
        breadcrumb.removeAllViews()
        if (homeMode) {
            breadcrumb.addView(crumbText("Virtual root", setTextColorForTheme(), homePage == "root") { showHomeRoot() })
            if (homePage == "internal_shared") {
                breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                breadcrumb.addView(crumbText("Internal shared storage", setTextColorForTheme(), true) { showInternalShared() })
            }
            if (homePage == "application_public") {
                breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
                breadcrumb.addView(crumbText("Application public", setTextColorForTheme(), true) { showApplicationPublic() })
            }
            renderTree()
            return
        }
        breadcrumb.addView(crumbText("Virtual root", setTextColorForTheme(), false) { showHomeRoot() })
        logicalLocationPrefix?.let { prefix ->
            breadcrumb.addView(crumbText("›", setTextColorForTheme(), false))
            breadcrumb.addView(crumbText(prefix, setTextColorForTheme(), false) {
                when (prefix) {
                    "Application public" -> showApplicationPublic()
                    "Installed Applications" -> openInstalledApps()
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
            breadcrumb.addView(crumbText(name, setTextColorForTheme(), index == history.lastIndex) { selectLocation(index) })
        }
        breadcrumb.post { (breadcrumb.parent as? HorizontalScrollView)?.fullScroll(View.FOCUS_RIGHT) }
        renderTree()
    }
    private fun selectLocation(index: Int) {
        if (index !in history.indices) return
        if (index + 1 < history.size) {
            history.subList(index + 1, history.size).clear()
            historyNames.subList(index + 1, historyNames.size).clear()
        }
        current = history[index]
        homeMode = false
        updateCreateAction()
        renderLocation(); load(current!!)
    }
    private fun crumbText(label: String, color: Int, selected: Boolean, click: (() -> Unit)? = null): TextView = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(color); setPadding(dp(6), dp(12), dp(6), dp(12))
        if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
        minimumHeight = dp(48); click?.let { action -> setOnClickListener { action() } }
    }
    private fun setTextColorForTheme() = if (dark) 0xFFF0F0E9.toInt() else 0xFF20231F.toInt()
    private fun load(uri: Uri, loadingAttempt: Int = 0) {
        val requestGeneration = ++loadGeneration
        homeMode = false
        listingError = null
        if (loadingAttempt == 0) {
            entries = emptyList(); listingIsLoading = true; listingNotice = null
            status.text = "Loading…"; content.removeAllViews()
        }
        val ref = resourceRef(uri)
        io.execute {
            val result = provider.list(ref)
            runOnUiThread {
                if (requestGeneration != loadGeneration || homeMode || current != uri) return@runOnUiThread
                when (result) {
                    is ProviderResult.Success -> {
                        entries = result.value
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
                        status.text = result.message; renderEntries()
                        if (localProvider.owns(uri)) {
                            if (!localProvider.hasAccess()) requestSharedStorageAccess()
                            else status.text = "Android restricts access to this folder: ${result.message}"
                        } else if (isAccessFailure(result.message, result.cause)) {
                            rootPicker(uri, "tree_permission_regrant")
                        }
                    }
                    is ProviderResult.Unsupported -> {
                        entries = emptyList(); listingIsLoading = false
                        listingError = "This provider cannot list this folder."
                        status.text = listingError; renderEntries()
                    }
                }
            }
        }
    }
    private fun updateListingStatus(notice: String? = listingNotice, isLoading: Boolean = listingIsLoading, loadingAttempt: Int = 0) {
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
    private fun resourceRef(uri: Uri) = ResourceRef(
        when {
            AppsUri.isAppsUri(uri) -> "apps"
            ArchiveUri.isArchiveUri(uri) -> "archive"
            localProvider.owns(uri) -> "local"
            else -> "saf"
        },
        uri.toString()
    )
    private fun isAccessFailure(message: String, cause: Throwable?): Boolean =
        cause is SecurityException || listOf("revoked", "permission", "access denied", "permission denied")
            .any { message.contains(it, ignoreCase = true) }
    private fun scheduleVisibleProbe() {
        if (visibleProbeScheduled || pendingCardProbes.isEmpty() || !::fileScroll.isInitialized) return
        visibleProbeScheduled = true
        fileScroll.post {
            visibleProbeScheduled = false
            probeVisibleCards()
        }
    }

    private fun isCardVisibleInScroll(cardView: View): Boolean {
        if (!::fileScroll.isInitialized || !cardView.isAttachedToWindow) return false
        val scrollHeight = fileScroll.height
        if (scrollHeight <= 0) return true
        val rect = android.graphics.Rect()
        cardView.getDrawingRect(rect)
        return try {
            fileScroll.offsetDescendantRectToMyCoords(cardView, rect)
            val buffer = dp(160)
            val visibleTop = fileScroll.scrollY - buffer
            val visibleBottom = fileScroll.scrollY + scrollHeight + buffer
            rect.bottom >= visibleTop && rect.top <= visibleBottom
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun probeVisibleCards() {
        if (pendingCardProbes.isEmpty()) return
        val currentGen = loadGeneration
        val iterator = pendingCardProbes.iterator()
        while (iterator.hasNext()) {
            val probe = iterator.next()
            if (probe.generation != currentGen) {
                iterator.remove()
            } else if (isCardVisibleInScroll(probe.view)) {
                iterator.remove()
                probe.startProbe()
            }
        }
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
        pendingCardProbes.clear()
        visibleProbeScheduled = false
        content.removeAllViews()
        val visible = if (showHidden) entries else entries.filterNot { it.metadata.isHidden }
        val rows = if (sortByName) visible.sortedWith(compareBy<ResourceEntry> { !it.metadata.isDirectory }.thenBy { it.metadata.name.lowercase() }) else visible.sortedByDescending { it.metadata.modifiedAtMillis ?: 0L }
        if (rows.isEmpty()) {
            content.addView(TextView(this).apply {
                text = listingError ?: if (listingIsLoading) "Waiting for the storage provider…" else
                    listingNotice ?: "No files or folders were returned for this location."
                textSize = 16f; setTextColor(setTextColorForTheme()); setPadding(dp(18), dp(28), dp(18), dp(12))
            })
            val path = current?.let { shared.getString("source_path_v2_$it", null) }
                ?: historyNames.joinToString(" / ")
            content.addView(TextView(this).apply {
                text = path; textSize = 13f; setTextColor(setTextColorForTheme()); setPadding(dp(18), 0, dp(18), dp(12))
            })
            content.addView(Button(dialogContext()).apply {
                text = "Refresh"
                setOnClickListener { current?.let { load(it) } }
            })
            return
        }
        if (grid) {
            val availableWidth = (resources.configuration.screenWidthDp - if (treeVisible) 220 else 32).coerceAtLeast(iconSizeDp)
            val minimumCellWidth = iconSizeDp + iconSizeDp / 2
            val columns = (availableWidth / minimumCellWidth).coerceIn(1, 8)
            val table = TableLayout(this).apply { isStretchAllColumns = true }
            rows.chunked(columns).forEach { group ->
                val tr = TableRow(this)
                val cellHeight = dp(iconSizeDp + iconSizeDp / 2 + 52)
                group.forEach { entry -> tr.addView(entryCard(entry, true), TableRow.LayoutParams(0, cellHeight, 1f)) }
                repeat(columns - group.size) { tr.addView(View(this), TableRow.LayoutParams(0, cellHeight, 1f)) }
                table.addView(tr)
            }
            content.addView(table)
        } else rows.forEach { content.addView(entryCard(it, false)) }
        scheduleVisibleProbe()
    }
    private fun renderCurrentContents() { if (homeMode) renderHomeItems() else renderEntries() }
    private fun entryCard(entry: ResourceEntry, asGrid: Boolean): View {
        val fg = if (dark) 0xFFF0F0E9.toInt() else 0xFF20231F.toInt()
        val isSelected = selectedUris.contains(entry.uri)
        val box = LinearLayout(this).apply {
            orientation = if (asGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (asGrid) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minimumHeight = dp(64)
            if (isSelected) {
                background = GradientDrawable().apply {
                    setColor(if (dark) 0x334CAF50.toInt() else 0x3381C784.toInt())
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), if (dark) 0xFF4CAF50.toInt() else 0xFF2E7D32.toInt())
                }
            } else {
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
        }
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val isShortcut = ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)
        val shortcut = if (isShortcut) shortcutCache.get(entry.uri.toString()) else null
        val isArchive = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType)

        var uncachedShortcutPlaceholder: TextView? = null
        var uncachedShortcutImage: ImageView? = null

        val rawIcon = if (isShortcut && shortcut != null) {
            val targetUri = try { Uri.parse(shortcut.targetUri) } catch (_: Throwable) { null }
            val targetMeta = ResourceMetadata(shortcut.targetPath ?: entry.metadata.name, shortcut.targetMimeType ?: "", null, null, shortcut.targetIsDirectory)
            val targetMimeKey = if (!shortcut.targetIsDirectory) CustomIconStore.resolveMimeKey(targetMeta) else null
            when {
                !shortcut.iconBase64.isNullOrBlank() -> {
                    shortcutCustomIconPreview(entry.uri, shortcut.iconBase64, asGrid)
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
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                targetMimeKey != null && customIconStore.hasMimeIcon(targetMimeKey) -> {
                    val fallback = if (ArchiveFormats.isArchive(targetMeta.name, targetMeta.mimeType)) "📦" else iconFor(targetMeta)
                    mimeIconPreview(targetMimeKey, fallback, asGrid, box)
                }
                ArchiveFormats.isArchive(shortcut.targetPath ?: "", shortcut.targetMimeType ?: "") -> TextView(this).apply {
                    text = "📦"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                shortcut.targetMimeType?.startsWith("image/") == true -> TextView(this).apply {
                    text = "🖼"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                else -> TextView(this).apply {
                    text = iconFor(targetMeta)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
            }
        } else if (isShortcut) {
            val frame = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                foregroundGravity = Gravity.CENTER
            }
            val placeholder = TextView(this).apply {
                text = "📄"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                gravity = Gravity.CENTER
                includeFontPadding = false
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
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
        } else if (entry.metadata.isDirectory) {
            directoryPreview(entry, asGrid, box)
        } else {
            val mimeKey = CustomIconStore.resolveMimeKey(entry.metadata)
            if (customIconStore.hasMimeIcon(mimeKey)) {
                mimeIconPreview(mimeKey, if (isArchive) "📦" else iconFor(entry.metadata), asGrid, box)
            } else if (isArchive) {
                TextView(this).apply {
                    text = "📦"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
            } else if (ImageFormats.isImage(entry.metadata)) {
                imagePreview(entry, asGrid, box)
            } else TextView(this).apply {
                text = iconFor(entry.metadata)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
        }
        val iconWidth = if (asGrid) sidePx else dp(52)
        val iconHeight = sidePx
        val icon = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconWidth, iconHeight).apply {
                gravity = if (asGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                if (asGrid) bottomMargin = dp(6)
            }
            addView(rawIcon, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            if (isShortcut) {
                val linkBadge = TextView(context).apply {
                    text = "↗"
                    textSize = 10f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (dark) 0xFF1976D2.toInt() else 0xFF1565C0.toInt())
                    }
                    val badgeSize = dp(16)
                    layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                }
                addView(linkBadge)
            }
            if (isSelected) {
                val badge = TextView(context).apply {
                    text = "✓"
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFF2E7D32.toInt())
                    }
                    val badgeSize = dp(20)
                    layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                }
                addView(badge)
            }
        }
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
        val displayName = if (isShortcut && !shortcut?.displayNameOverride.isNullOrBlank()) {
            "${shortcut.displayNameOverride} ↗"
        } else if (isShortcut) {
            "${entry.metadata.name.removeSuffix(".lnk")} ↗"
        } else {
            entry.metadata.name
        }
        val nameView = TextView(this).apply {
            text = displayName
            textSize = 14f
            setTextColor(fg)
            maxLines = if (asGrid) 2 else 1
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
        }
        labels.addView(nameView)
        val metaView = if (!asGrid) {
            TextView(this).apply {
                text = metadataLine(entry)
                textSize = 12f
                setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt())
            }.also { labels.addView(it) }
        } else null
        box.addView(icon); box.addView(labels)

        if (isShortcut && shortcut == null) {
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
                            "📄"
                        }
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
                        else -> {
                            val targetMeta = ResourceMetadata(info.targetPath ?: entry.metadata.name, info.targetMimeType ?: "", null, null, false)
                            val targetMimeKey = CustomIconStore.resolveMimeKey(targetMeta)
                            val mimeFile = customIconStore.getMimeIconFile(targetMimeKey)
                            if (mimeFile != null) {
                                val previewKey = "mime-icon:$targetMimeKey:${mimeFile.lastModified()}:$sidePx"
                                resolvedBitmap = thumbnailCache.get(previewKey)
                                    ?: thumbnailDecoder.decode(Uri.fromFile(mimeFile), sidePx)?.also { thumbnailCache.put(previewKey, it) }
                            }
                            when {
                                ArchiveFormats.isArchive(info.targetPath ?: "", info.targetMimeType ?: "") -> "📦"
                                info.targetMimeType?.startsWith("image/") == true -> "🖼"
                                else -> iconFor(targetMeta)
                            }
                        }
                    }
                    runOnUiThread {
                        if (generation != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                        nameView.text = if (!info.displayNameOverride.isNullOrBlank()) {
                            "${info.displayNameOverride} ↗"
                        } else {
                            "${entry.metadata.name.removeSuffix(".lnk")} ↗"
                        }
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

        box.setOnClickListener {
            if (selectionMode) {
                if (isSelected) selectedUris.remove(entry.uri) else selectedUris.add(entry.uri)
                renderEntries()
                updateListingStatus()
                return@setOnClickListener
            }
            if (isShortcut) {
                openShortcut(entry)
                return@setOnClickListener
            }
            if (entry.metadata.isDirectory) {
                navigate(entry.uri, displayName = entry.metadata.name)
            } else if (isArchive) {
                if (ArchiveUri.isArchiveUri(entry.uri)) {
                    val nestedVirtualPath = if (historyNames.isNotEmpty()) (historyNames + entry.metadata.name).joinToString("/") else null
                    io.execute {
                        try {
                            val cached = archiveProvider.extractToCache(entry.uri, nestedVirtualPath)
                            val fileUri = Uri.fromFile(cached)
                            val nestedUri = ArchiveUri.toArchiveUri(fileUri)
                            runOnUiThread { navigate(nestedUri, displayName = entry.metadata.name) }
                        } catch (e: Exception) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "Could not open archive: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                } else {
                    val archiveUri = ArchiveUri.toArchiveUri(entry.uri)
                    navigate(archiveUri, displayName = entry.metadata.name)
                }
            } else {
                open(entry)
            }
        }
        box.setOnLongClickListener {
            if (selectionMode) {
                if (!isSelected) {
                    selectedUris.add(entry.uri)
                    renderEntries()
                    updateListingStatus()
                }
                showBatchActionMenu()
                true
            } else {
                showEntryMenu(entry)
                true
            }
        }
        return box
    }
    private fun shortcutCustomIconPreview(entryUri: Uri, base64: String, asGrid: Boolean): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val cacheKey = "shortcut-icon:$entryUri:${base64.hashCode()}"
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "📄"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
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
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = fallbackEmoji
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
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
                val bitmap = thumbnailDecoder.decode(Uri.fromFile(mimeFile), sidePx)
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
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "🖼"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
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
    private fun findDirectoryIcon(entry: ResourceEntry): Uri? {
        if (localProvider.owns(entry.uri)) {
            return try {
                val dir = localProvider.fileFor(entry.uri)
                if (!dir.isDirectory) return null
                val files = dir.listFiles() ?: return null
                val candidateFiles = files.filter { file ->
                    !file.isDirectory && (
                        DirectoryIconResolver.isExplicitIconFileName(file.name) ||
                        ImageFormats.extension(file.name) in ImageFormats.decodableExtensions
                    )
                }
                if (candidateFiles.isEmpty()) return null
                val entries = candidateFiles.map { localProvider.entry(it) }
                DirectoryIconResolver.findBestIcon(entries)?.uri
            } catch (_: Exception) { null }
        }
        return try {
            val result = provider.list(entry.ref)
            if (result is ProviderResult.Success) {
                DirectoryIconResolver.findBestIcon(result.value)?.uri
            } else null
        } catch (_: Exception) { null }
    }
    private fun findDirectoryIcon(uri: Uri): Uri? {
        val ref = resourceRef(uri)
        val entry = ResourceEntry(ref, ResourceMetadata("", "", null, null, true), uri)
        return findDirectoryIcon(entry)
    }
    private fun directoryPreview(entry: ResourceEntry, asGrid: Boolean, cardView: View? = null): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val dirKey = "${entry.ref.providerId}:${entry.ref.documentId}"
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "📁"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
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

        val cachedUriString = directoryIconCache.get(dirKey)
        if (cachedUriString == "") {
            return frame
        }
        if (cachedUriString != null) {
            val previewKey = "dir-icon:$dirKey:$cachedUriString"
            val cached = thumbnailCache.get(previewKey)
            if (cached != null) {
                image.setImageBitmap(cached)
                placeholder.visibility = View.GONE
                return frame
            }
        }

        val generation = loadGeneration
        val hostView = cardView ?: frame
        registerVisibleCardProbe(hostView, generation) {
            imageIo.execute {
                if (generation != loadGeneration || isFinishing || isDestroyed) return@execute
                val iconUri = if (cachedUriString != null) {
                    Uri.parse(cachedUriString)
                } else {
                    val resolved = findDirectoryIcon(entry)
                    directoryIconCache.put(dirKey, resolved?.toString() ?: "")
                    resolved
                }
                if (iconUri == null) return@execute

                val previewKey = "dir-icon:$dirKey:$iconUri"
                val bitmap = thumbnailDecoder.decode(iconUri, sidePx)
                if (bitmap != null) thumbnailCache.put(previewKey, bitmap)
                runOnUiThread {
                    if (generation == loadGeneration && image.tag == dirKey && bitmap != null) {
                        image.setImageBitmap(bitmap)
                        placeholder.visibility = View.GONE
                    }
                }
            }
        }
        return frame
    }
    private fun appPreview(packageName: String?, asGrid: Boolean, cardView: View? = null): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "📱"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
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
            return frame
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
        return com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(file.name, false)
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

    private fun open(entry: ResourceEntry) {
        if (AppsUri.isAppUri(entry.uri)) {
            val pkg = AppsUri.getPackageName(entry.uri)
            if (pkg != null) {
                launchApp(pkg)
            }
            return
        }
        if (ArchiveUri.isArchiveUri(entry.uri)) {
            val virtualPath = archiveProvider.resolveVirtualPath(entry.uri)
            io.execute {
                try {
                    val cachedFile = archiveProvider.extractToCache(entry.uri, virtualPath)
                    val fileUri = FileProvider.getUriForFile(this, "${packageName}.files", cachedFile)
                    val mimeType = resolveMimeType(fileUri, cachedFile, entry.metadata.mimeType)
                    runOnUiThread {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            clipData = ClipData.newRawUri(cachedFile.name, fileUri)
                        }
                        val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        for (resolveInfo in resInfoList) {
                            grantUriPermission(resolveInfo.activityInfo.packageName, fileUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not extract file from archive: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, entry.metadata.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_SHORT).show() }
    }
    private fun showHomeRoot() {
        if (selectionMode) exitSelectionMode()
        val generation = ++loadGeneration
        accessRequestGeneration++
        homeMode = true
        homePage = "root"
        logicalLocationPrefix = null
        updateCreateAction()
        renderLocation()
        status.text = "Locations and storage sources"
        content.removeAllViews()
        shared.edit().remove("tree_source_installed_apps").apply()

        fun buildStaticTailItems(): List<HomeItem> {
            val tail = mutableListOf<HomeItem>()
            savedConnections().forEach { connection ->
                val source = providerRegistry.sourceTypes().firstOrNull { it.id == connection.sourceTypeId }
                val icon = source?.icon ?: "🌐"
                val detail = connection.endpoint ?: "Saved connection · provider not implemented"
                tail += HomeItem(icon, connection.displayName, detail, {
                    dialogBuilder().setTitle(connection.displayName)
                        .setMessage("This ${source?.label ?: connection.sourceTypeId} connection is saved, but network browsing is not implemented yet.")
                        .setPositiveButton("OK", null).show()
                }, { showSavedConnectionActions(connection) })
            }
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
            val hasLocalAccess = localProvider.hasAccess()
            val grantedRoots = try {
                contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission && it.uri.pathSegments.contains("tree") }
                    .map { it.uri }
                    .filterNot { AppDocumentSources.isAppAuthority(it.authority) || AppsUri.isAppsUri(it) }
                    .distinctBy(::sourceIdentity)
                    .toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
            shared.getString("root", null)?.let { rootString ->
                val uri = Uri.parse(rootString)
                if (AppsUri.isAppsUri(uri)) {
                    shared.edit().remove("root").apply()
                } else if (!localProvider.owns(uri) && !AppDocumentSources.isAppAuthority(uri.authority) &&
                    grantedRoots.none { sourceIdentity(it) == sourceIdentity(uri) }) {
                    grantedRoots.add(uri)
                }
            }

            val discoveredItems = mutableListOf<HomeItem>()
            val pathsToResolve = mutableListOf<Pair<Uri, String>>()
            grantedRoots.forEach { uri ->
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
                    val detail = when {
                        !mounted -> "Not currently mounted"
                        volume.isRemovable -> "Removable storage · choose a folder"
                        volume.isPrimary -> "Device storage · choose a folder"
                        else -> "Available volume · choose a folder"
                    }
                    if (volume.isPrimary) {
                        discoveredItems += HomeItem("📱", "Internal shared storage", "Shared folders", {
                            showInternalShared()
                        }, treeKey = key, treeExpandable = true, needsAccessBadge = !hasLocalAccess)
                    } else {
                        discoveredItems += HomeItem(if (volume.isRemovable) "💾" else "📱", description, detail, {
                            if (grantedUri != null) openRoot(grantedUri)
                            else if (mounted && android.os.Build.VERSION.SDK_INT >= 29) {
                                pendingTreeSourceKey = key
                                startActivityForResult(volume.createOpenDocumentTreeIntent(), pickTreeRequest)
                            }
                            else if (mounted) rootPicker(sourceKey = key) else Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
                        }, treeKey = key, treeUri = grantedUri, treeExpandable = mounted, needsAccessBadge = mounted && grantedUri == null)
                    }
                }
            } catch (_: Exception) {}

            if (discoveredItems.none { it.treeKey == "internal_shared" }) {
                discoveredItems += HomeItem("📱", "Internal shared storage", "Shared folders", { showInternalShared() },
                    treeKey = "internal_shared", treeExpandable = true, needsAccessBadge = !hasLocalAccess)
            }
            discoveredItems += buildStaticTailItems()

            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
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
        homePage = "internal_shared"
        logicalLocationPrefix = null
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

    private fun refreshInternalSharedTree() {
        val generation = ++internalListGeneration
        if (!localProvider.hasAccess()) { internalSharedEntries = emptyList(); rebuildTreeRoots(); return }
        io.execute {
            val result = localProvider.list(resourceRef(localProvider.rootUri))
            runOnUiThread {
                if (generation != internalListGeneration || isFinishing) return@runOnUiThread
                if (result is ProviderResult.Success) internalSharedEntries = result.value
                else internalSharedEntries = emptyList()
                rebuildTreeRoots()
            }
        }
    }
    private fun showApplicationPublic() {
        loadGeneration++
        accessRequestGeneration++
        homeMode = true
        homePage = "application_public"
        logicalLocationPrefix = null
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
                        applicationPublicItems += HomeItem(
                            "📁", source.title, source.detail,
                            { source.granted?.let { openRoot(it, "Application public", false) }
                                ?: rootPicker(source.target, source.key, source.authority) },
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
        content.removeAllViews()
        val visibleItems = if (homePage == "internal_shared") {
            homeItems.filter { it.treeParentKey == "internal_shared" }
                .sortedBy { if (it.treeKey?.startsWith("public_") == true) 0 else 1 }
        } else if (homePage == "application_public") {
            applicationPublicItems
        } else homeItems.filter { it.showInHome && it.treeParentKey == null }
        if (homePage == "internal_shared" && !localProvider.hasAccess()) {
            content.addView(Button(dialogContext()).apply {
                text = "Allow internal storage access"
                setOnClickListener { requestSharedStorageAccess() }
            })
        }
        if (visibleItems.isEmpty()) return
        if (grid) {
            val availableWidth = (resources.configuration.screenWidthDp - if (treeVisible) 220 else 32).coerceAtLeast(iconSizeDp)
            val columns = (availableWidth / (iconSizeDp + iconSizeDp / 2)).coerceIn(1, 8)
            val table = TableLayout(this).apply { isStretchAllColumns = true }
            visibleItems.chunked(columns).forEach { group ->
                val row = TableRow(this)
                val height = dp(iconSizeDp + iconSizeDp / 2 + 52)
                group.forEach { item -> row.addView(homeItemCard(item, true), TableRow.LayoutParams(0, height, 1f)) }
                repeat(columns - group.size) { row.addView(View(this), TableRow.LayoutParams(0, height, 1f)) }
                table.addView(row)
            }
            content.addView(table)
        } else visibleItems.forEach { content.addView(homeItemCard(it, false)) }
    }
    private fun homeItemCard(item: HomeItem, asGrid: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = if (asGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (asGrid) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8)); minimumHeight = dp(64)
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val iconSize = if (asGrid) iconSizeDp else 28
        val iconWidth = if (asGrid) dp(iconSize) else dp(52)
        val iconHeight = if (asGrid) dp(iconSize) else dp(48)
        val iconFrame = FrameLayout(this).apply {
            val iconParams = LinearLayout.LayoutParams(iconWidth, iconHeight).apply {
                gravity = if (asGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                if (asGrid) bottomMargin = dp(6)
            }
            layoutParams = iconParams
        }
        iconFrame.addView(TextView(this).apply {
            text = item.icon
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
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
                item.initialUri != null -> rootPicker(item.initialUri, item.treeKey)
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
        if (homeMode && homePage != "application_public") renderHomeItems()
    }
    private fun isCompactPortrait(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
            resources.configuration.screenWidthDp < 600
    private fun updateBrowserPanes() {
        if (!::treePanel.isInitialized || !::fileScroll.isInitialized) return
        val treeOnly = treeVisible && isCompactPortrait()
        treePanel.visibility = if (treeVisible) View.VISIBLE else View.GONE
        fileScroll.visibility = if (treeOnly) View.GONE else View.VISIBLE
        (treePanel.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = if (treeOnly) LinearLayout.LayoutParams.MATCH_PARENT else dp(180)
            params.weight = 0f
            treePanel.layoutParams = params
        }
        (fileScroll.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.weight = if (treeOnly) 0f else 1f
            fileScroll.layoutParams = params
        }
        browserArea.requestLayout()
    }
    private fun rebuildTreeRoots() {
        val priorNodes = mutableMapOf<String, TreeNode>()
        fun collect(nodes: List<TreeNode>) { nodes.forEach { priorNodes[it.key] = it; collect(it.children) } }
        collect(treeRoots)
        val virtualRoot = priorNodes["virtual-root"]?.also { it.onOpen = { showHomeRoot() }; it.canExpand = true }
            ?: TreeNode("virtual-root", null, "Virtual root", "🏠", 0, { showHomeRoot() }, true, expanded = true)
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
            if (uri != null && !seen.add(sourceIdentity(uri))) return@forEach
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
            node.needsAccessBadge = item.needsAccessBadge && key != "installed_apps"
            node.onRequestAccess = when {
                !node.needsAccessBadge -> null
                key == "internal_shared" -> item.action
                key == "application_public" -> item.action
                item.initialUri != null -> ({ rootPicker(item.initialUri, key) })
                else -> item.action
            }
            if (key == "internal_shared") {
                node.children.clear()
                val childItems = if (localProvider.hasAccess()) internalSharedEntries
                    .filter { it.metadata.isDirectory && (showHidden || !it.metadata.isHidden) }.sortedBy { it.metadata.name.lowercase() }.map { entry ->
                        HomeItem("📁", entry.metadata.name, "Folder", { openRoot(entry.uri, rememberAuthorizedRoot = false) },
                            longAction = { showEntryMenu(entry) },
                            treeKey = "local_${entry.uri}", treeUri = entry.uri, treeExpandable = true)
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
                    val childNode = priorNodes[childKey]?.takeIf { it.uri == childUri && it.name == childItem.title }
                        ?: TreeNode(childKey, childUri, childItem.title, childItem.icon, 2,
                            { childUri?.let { openRoot(it, "Application public", false) } ?: childItem.action() }, true)
                    childNode.onOpen = { childUri?.let { openRoot(it, "Application public", false) } ?: childItem.action() }
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
            renderTreeNodes(treeContent, treeRoots, ::selectTreeNode, ::renderTree)
        }
        activePickerRerender?.invoke()
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
        onCreateDirectoryInNode: ((TreeNode, Uri) -> Unit)? = null
    ) {
        updateTreeBranches(nodes)
        val visibleRoots = nodes.filter { excludedUri == null || it.uri != excludedUri }
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
                onCreateDirectoryInNode = onCreateDirectoryInNode
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
        onCreateDirectoryInNode: ((TreeNode, Uri) -> Unit)? = null
    ) {
        val isCreateDirNode = isCreateDirectoryNode(node)
        fun activateNode() {
            if (isCreateDirNode) {
                node.onOpen()
                return
            }
            activateTreeItem(node.uri, blockedSelectionUri, node.needsAccessBadge,
                { (node.onRequestAccess ?: node.onOpen).invoke() }, { onSelect(node) })
        }
        val selected = !isCreateDirNode && isNodeSelected(node)
        val showCreateDirChild = onCreateDirectoryInNode != null &&
            selected &&
            node.expanded &&
            !node.needsAccessBadge &&
            node.uri != null &&
            !ArchiveUri.isArchiveUri(node.uri) &&
            !AppsUri.isAppsUri(node.uri)
        val visibleChildren = prepareVisibleChildren(
            parentNode = node,
            parentAncestorFlags = ancestorLastFlags,
            excludedUri = excludedUri,
            includeCreateDirectory = showCreateDirChild,
            onCreateDirectory = onCreateDirectoryInNode
        )
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
            contentDescription = if (selected) "${node.name}, current folder" else node.name
            isSelected = selected
            setOnClickListener { activateNode() }
            setOnLongClickListener { node.onLongPress?.invoke(); node.onLongPress != null }
        }
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
        iconStack.setOnLongClickListener { node.onLongPress?.invoke(); node.onLongPress != null }
        titleCell.addView(iconStack, LinearLayout.LayoutParams(dp(32), dp(36)))
        titleCell.setOnClickListener { activateNode() }
        titleCell.setOnLongClickListener { node.onLongPress?.invoke(); node.onLongPress != null }
        val title = TextView(this).apply {
            text = node.name; textSize = 13f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(setTextColorForTheme()); maxLines = 2; setPadding(dp(4), dp(4), dp(4), dp(4))
            alpha = if (blockedSelectionUri != null && node.uri == blockedSelectionUri) 0.55f else 1f
            setOnClickListener { activateNode() }
            setOnLongClickListener { node.onLongPress?.invoke(); node.onLongPress != null }
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
                    onCreateDirectoryInNode = onCreateDirectoryInNode
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
                when (result) {
                    is ProviderResult.Success -> {
                        node.children.clear()
                        val folders = result.value.filter {
                            !AppsUri.isAppUri(it.uri) &&
                            (it.metadata.isDirectory || ArchiveFormats.isArchive(it.metadata.name, it.metadata.mimeType)) &&
                            (showHidden || !it.metadata.isHidden)
                        }
                        folders.forEachIndexed { index, child ->
                            val childIsArchive = !AppsUri.isAppUri(child.uri) && ArchiveFormats.isArchive(child.metadata.name, child.metadata.mimeType)
                            val childUri = if (childIsArchive && !ArchiveUri.isArchiveUri(child.uri)) ArchiveUri.toArchiveUri(child.uri) else child.uri
                            val icon = if (childIsArchive) "📦" else "📁"
                            lateinit var childNode: TreeNode
                            childNode = TreeNode(
                                "${node.key}/${sourceIdentity(childUri)}", childUri, child.metadata.name, icon, node.depth + 1,
                                { openTreeNode(childNode) }, true, node.ancestorContinues + !node.isLast, index == folders.lastIndex,
                                onLongPress = { showEntryMenu(child) }
                            )
                            node.children += childNode
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
                        } else if (isAccessFailure(result.message, result.cause)) {
                            node.needsAccessBadge = true
                            node.onRequestAccess = { rootPicker(node.uri, "tree_permission_regrant") }
                            node.onRequestAccess?.invoke()
                        }
                    }
                    is ProviderResult.Unsupported -> Unit
                }
                rerender()
            }
        }
    }
    private fun openTreeNode(target: TreeNode) {
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
        history.clear(); historyNames.clear()
        route.filter { it.uri != null }.forEach { node -> history += requireNotNull(node.uri); historyNames += node.name }
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
    private fun showOptions() {
        val choices = arrayOf(
            if (grid) "List / details view" else "Grid view",
            if (sortByName) "Sort by modified" else "Sort by name",
            if (showHidden) "Hide hidden files" else "Show hidden files",
            if (dark) "Light theme" else "Dark theme",
            "Operations",
            "Icon size (${iconSizeDp} dp)"
        )
        dialogBuilder().setTitle("View options").setItems(choices) { _, i -> when (i) {
            0 -> { grid = !grid; shared.edit().putBoolean("grid", grid).apply(); renderCurrentContents() }
            1 -> { sortByName = !sortByName; shared.edit().putBoolean("sort_name", sortByName).apply(); if (!homeMode) renderEntries() }
            2 -> {
                showHidden = !showHidden
                shared.edit().putBoolean("show_hidden", showHidden).apply()
                if (!homeMode) {
                    updateListingStatus()
                    renderEntries()
                }
                refreshInternalSharedTree()
                rebuildTreeRoots()
            }
            3 -> { dark = !dark; shared.edit().putBoolean("dark", dark).apply(); buildUi(); if (homeMode) showHomeRoot() else current?.let { load(it) } }
            4 -> showOperations()
            5 -> showIconSizeDialog()
        } }.show()
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
            max = 64
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
    private val operationsListener: (List<OperationSnapshot>) -> Unit = { snapshots ->
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val activeList = snapshots.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
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
            } else if (!isOperationWindowHidden && activeList.isNotEmpty()) {
                showProgressDialog(operationWindowQueue.firstOrNull() ?: activeList.first().id)
            } else if (activeList.isEmpty()) {
                operationWindowQueue.clear()
                isOperationWindowHidden = false
            }
            updateOperationVisibilityUi(snapshots)
        }
    }

    private fun updateOperationVisibilityUi(snapshots: List<OperationSnapshot> = operations.snapshots()) {
        val activeList = snapshots.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        val hasActive = activeList.isNotEmpty()
        if (::operationsButton.isInitialized) {
            operationsButton.visibility = if (hasActive) View.VISIBLE else View.GONE
        }
        if (::unhideOperationBar.isInitialized) {
            val dialogShowing = currentProgressDialog?.isShowing == true
            if (hasActive && (isOperationWindowHidden || !dialogShowing)) {
                val currentOp = activeList.firstOrNull { it.state == OperationState.RUNNING } ?: activeList.first()
                val pct = currentOp.progressPercent
                val pctText = if (pct >= 0 && currentOp.state == OperationState.RUNNING) " · $pct%" else ""
                val queuedExtra = (activeList.size - 1).coerceAtLeast(0)
                val queueSuffix = if (queuedExtra > 0) " (+$queuedExtra queued)" else ""
                unhideOperationLabel.text = "⚡ ${currentOp.label}$pctText$queueSuffix"
                unhideOperationButton.text = if (activeList.size > 1) "Unhide (${activeList.size})" else "Unhide"
                unhideOperationBar.visibility = View.VISIBLE
            } else {
                unhideOperationBar.visibility = View.GONE
            }
        }
    }

    private fun enqueueOperationWindow(opId: String) {
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
            showProgressDialog(operationWindowQueue.firstOrNull() ?: opId)
        }
        updateOperationVisibilityUi(snapshots)
    }

    private fun unhideOperationWindow() {
        isOperationWindowHidden = false
        val targetId = operationWindowQueue.firstOrNull()
        showProgressDialog(targetId)
        updateOperationVisibilityUi()
    }

    private fun showOperations() {
        unhideOperationWindow()
    }

    private fun showProgressDialog(targetOpId: String? = null) {
        val snapshots = operations.snapshots()
        val activeList = snapshots.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        val active = when {
            currentProgressDialog?.isShowing == true && activeDialogOpId != null ->
                snapshots.firstOrNull { it.id == activeDialogOpId }
            operationWindowQueue.isNotEmpty() ->
                operationWindowQueue.firstNotNullOfOrNull { qId -> snapshots.firstOrNull { it.id == qId } }
            targetOpId != null ->
                snapshots.firstOrNull { it.id == targetOpId }
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
            updateOperationVisibilityUi(snapshots)
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
            setPadding(0, 0, 0, dp(2))
        }
        progressTitleView = titleView

        val queueView = TextView(context).apply {
            textSize = 12f
            setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
            setPadding(0, 0, 0, dp(4))
            visibility = View.GONE
        }
        progressQueueView = queueView

        val currentItemView = TextView(context).apply {
            text = active.currentItem ?: active.message ?: "Starting…"
            textSize = 13f
            setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, 0, dp(12))
        }
        progressCurrentItemView = currentItemView

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val pct = active.progressPercent
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
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        val bytesView = TextView(context).apply {
            text = formatProgressSnapshot(active)
            textSize = 12f
            setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        progressBytesView = bytesView

        val rateView = TextView(context).apply {
            text = active.formatRate()
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(setTextColorForTheme())
            gravity = Gravity.END
        }
        progressRateView = rateView

        statsLayout.addView(bytesView)
        statsLayout.addView(rateView)

        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val hideBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            val isFinished = active.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)
            text = if (isFinished) "Close" else "Hide"
            setTextColor(setTextColorForTheme())
            setOnClickListener {
                val curId = activeDialogOpId
                val latestSnapshots = operations.snapshots()
                val curSnap = latestSnapshots.firstOrNull { it.id == curId }
                val finishedNow = curSnap == null || curSnap.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)
                if (finishedNow) {
                    if (curId != null) operationWindowQueue.remove(curId)
                    val nextId = operationWindowQueue.firstOrNull()
                    val nextSnap = nextId?.let { id -> latestSnapshots.firstOrNull { it.id == id } }
                    if (nextSnap != null) {
                        activeDialogOpId = nextSnap.id
                        updateProgressDialogContent(nextSnap, latestSnapshots)
                        updateOperationVisibilityUi(latestSnapshots)
                    } else {
                        isOperationWindowHidden = false
                        currentProgressDialog?.dismiss()
                        updateOperationVisibilityUi(latestSnapshots)
                    }
                } else {
                    isOperationWindowHidden = true
                    currentProgressDialog?.dismiss()
                    updateOperationVisibilityUi(latestSnapshots)
                }
            }
        }
        progressHideButton = hideBtn

        val cancelBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setTextColor(0xFFD32F2F.toInt())
            visibility = if (active.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)) View.GONE else View.VISIBLE
            setOnClickListener {
                activeDialogOpId?.let { opId ->
                    operations.cancel(opId)
                    text = "Cancelling…"
                    isEnabled = false
                }
            }
        }
        progressCancelButton = cancelBtn

        buttonsLayout.addView(hideBtn)
        buttonsLayout.addView(cancelBtn)

        body.addView(titleView)
        body.addView(queueView)
        body.addView(currentItemView)
        body.addView(bar)
        body.addView(statsLayout)
        body.addView(buttonsLayout)

        val dialog = dialogBuilder()
            .setView(body)
            .setCancelable(true)
            .create()

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            currentProgressDialog = null
            activeDialogOpId = null
            progressTitleView = null
            progressQueueView = null
            progressCurrentItemView = null
            progressBar = null
            progressBytesView = null
            progressRateView = null
            progressCancelButton = null
            progressHideButton = null
            val latestSnapshots = operations.snapshots()
            if (latestSnapshots.any { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }) {
                isOperationWindowHidden = true
            }
            updateOperationVisibilityUi(latestSnapshots)
        }
        currentProgressDialog = dialog
        updateProgressDialogContent(active, snapshots)
        dialog.show()
        updateOperationVisibilityUi(snapshots)
    }

    private fun updateProgressDialogContent(op: OperationSnapshot, allSnapshots: List<OperationSnapshot> = operations.snapshots()) {
        val activeList = allSnapshots.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        val waitingAfterCurrent = operationWindowQueue.count { id ->
            id != op.id && allSnapshots.any { it.id == id && (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) }
        }
        if (waitingAfterCurrent > 0) {
            progressQueueView?.text = "Queued: $waitingAfterCurrent more operation${if (waitingAfterCurrent == 1) "" else "s"} waiting"
            progressQueueView?.visibility = View.VISIBLE
        } else {
            progressQueueView?.visibility = View.GONE
        }

        if (op.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)) {
            when (op.state) {
                OperationState.COMPLETE -> {
                    directoryIconCache.evictAll()
                    refreshInternalSharedTree()
                    current?.let { load(it) }
                }
                OperationState.CANCELLED -> {
                    current?.let { load(it) }
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
                    activeDialogOpId = nextSnap.id
                    updateProgressDialogContent(nextSnap, allSnapshots)
                    return
                }
            }
        }

        progressTitleView?.text = op.label
        progressCurrentItemView?.text = op.currentItem ?: op.message ?: if (op.state == OperationState.QUEUED) "Waiting in queue…" else "Starting…"
        val pct = op.progressPercent
        if (pct >= 0) {
            progressBar?.isIndeterminate = false
            progressBar?.progress = pct
        } else {
            progressBar?.isIndeterminate = true
        }
        progressBytesView?.text = formatProgressSnapshot(op)
        progressRateView?.text = op.formatRate()

        if (op.state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)) {
            progressCancelButton?.visibility = View.GONE
            progressHideButton?.text = if (waitingAfterCurrent > 0) "Next" else "Close"
            when (op.state) {
                OperationState.COMPLETE -> {
                    progressTitleView?.text = "${op.label} complete"
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = 100
                }
                OperationState.CANCELLED -> {
                    progressTitleView?.text = "${op.label} cancelled"
                }
                OperationState.FAILED -> {
                    progressTitleView?.text = "${op.label} failed"
                    progressCurrentItemView?.text = op.message ?: "Operation failed"
                }
                else -> Unit
            }
        } else {
            progressCancelButton?.visibility = View.VISIBLE
            progressCancelButton?.isEnabled = true
            progressCancelButton?.text = "Cancel"
            progressHideButton?.text = "Hide"
        }
    }

    private fun formatProgressSnapshot(op: OperationSnapshot): String = when {
        op.totalBytes != null && op.totalBytes > 0 -> {
            val cur = android.text.format.Formatter.formatShortFileSize(this, op.bytes)
            val total = android.text.format.Formatter.formatShortFileSize(this, op.totalBytes)
            val pct = op.progressPercent
            "$cur / $total ($pct%)"
        }
        op.totalItems > 0 -> {
            val pct = op.progressPercent
            "${op.itemsProcessed} / ${op.totalItems} items ($pct%)"
        }
        op.bytes > 0 -> android.text.format.Formatter.formatShortFileSize(this, op.bytes)
        else -> op.message ?: ""
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
        val isArchive = isSingle && !AppsUri.isAppUri(currentEntry.uri) && ArchiveFormats.isArchive(currentEntry.metadata.name, currentEntry.metadata.mimeType)
        val inArchive = isSingle && ArchiveUri.isArchiveUri(currentEntry.uri)
        val isFile = isSingle && !isShortcut && !currentEntry.metadata.isDirectory && !AppsUri.isAppsUri(currentEntry.uri)
        val fileMimeKey = if (isFile) CustomIconStore.resolveMimeKey(currentEntry.metadata) else null

        val targetFolderUri: Uri? = if (isDirectory && !inArchive && !AppsUri.isAppsUri(currentEntry.uri)) currentEntry.uri else null
        val canChangeFolderIcon = targetFolderUri != null
        val canChangeShortcutIcon = isShortcut && !inArchive
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
            isClickable = isSingle
            isFocusable = isSingle
        }

        val fallbackEmoji = when {
            !isSingle -> "📚"
            AppsUri.isAppUri(currentEntry.uri) -> "📱"
            isShortcut && shortcut != null && runCatching { AppsUri.isAppUri(Uri.parse(shortcut.targetUri)) }.getOrDefault(false) -> "📱"
            AppsUri.isCategoryUri(currentEntry.uri) -> appsProvider.getCategoryIcon(AppsUri.getCategoryName(currentEntry.uri) ?: currentEntry.metadata.name)
            isDirectory -> "📁"
            isArchive -> "📦"
            else -> iconFor(currentEntry.metadata)
        }

        val iconPlaceholder = TextView(context).apply {
            text = fallbackEmoji
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34f)
            gravity = Gravity.CENTER
            includeFontPadding = false
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
            if (isShortcut) {
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
                            else -> {
                                val targetMeta = ResourceMetadata(info.targetPath ?: currentEntry.metadata.name, info.targetMimeType ?: "", null, null, false)
                                val mKey = CustomIconStore.resolveMimeKey(targetMeta)
                                val mFile = customIconStore.getMimeIconFile(mKey)
                                if (mFile != null) bmp = thumbnailDecoder.decode(Uri.fromFile(mFile), decodePx)
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
            if (isDirectory && !AppsUri.isAppsUri(currentEntry.uri)) {
                imageIo.execute {
                    if (isCancelled || isFinishing || isDestroyed) return@execute
                    val iconUri = findDirectoryIcon(currentEntry.uri)
                    val bmp = if (iconUri != null) thumbnailDecoder.decode(iconUri, decodePx) else null
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
                val mimeFile = customIconStore.getMimeIconFile(fileMimeKey)
                if (mimeFile != null) {
                    imageIo.execute {
                        if (isCancelled || isFinishing || isDestroyed) return@execute
                        val bmp = thumbnailDecoder.decode(Uri.fromFile(mimeFile), decodePx)
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
            isShortcut -> "Shortcut (.lnk)"
            inArchive && isDirectory -> "Folder inside archive"
            isSingle && AppsUri.isAppUri(currentEntry.uri) -> "Android Application (APK)"
            isSingle && AppsUri.isCategoryUri(currentEntry.uri) -> "Virtual Application Category"
            isDirectory -> "Folder"
            isArchive -> "Archive (${currentEntry.metadata.mimeType})"
            else -> currentEntry.metadata.mimeType.ifEmpty { "File" }
        }
        val typeView = textValue(typeDesc)
        propertyRow("Type", typeView)

        // If app or category, show app info
        if (isSingle && AppsUri.isAppUri(currentEntry.uri)) {
            val pkg = AppsUri.getPackageName(currentEntry.uri)
            val appItem = if (pkg != null) appsProvider.getAppItem(pkg) else null
            if (pkg != null) propertyRow("Package", textValue(pkg))
            if (appItem?.versionName != null) propertyRow("Version", textValue(appItem.versionName))
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
                val pickFolderBtn = TextView(context).apply {
                    text = "Pick folder target…"
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt())
                    setPadding(dp(4), dp(4), dp(8), dp(4))
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
                uriRow.addView(tUriEdit)
                uriRow.addView(pickFolderBtn)
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
        propertyRow("Size", sizeView)

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
                val newUri = shortcutTargetUriInput.text.toString().trim()
                if (newUri.isEmpty()) {
                    Toast.makeText(this, "Target URI cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
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
        val dialog = builder.create()

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
        groups: List<List<String>>,
        onAction: (String) -> Unit
    ) {
        val context = dialogContext()
        val nonEmptyGroups = groups.map { it.filter(String::isNotBlank) }.filter { it.isNotEmpty() }
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
                        onAction(actionLabel)
                    }
                }
                listLayout.addView(itemView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        val scroll = ScrollView(context).apply { addView(listLayout) }
        dialog = dialogBuilder()
            .setTitle(title)
            .setView(scroll)
            .create()
        dialog?.show()
    }

    private fun showEntryMenu(entry: ResourceEntry) {
        val isShortcut = ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)
        val shortcut = if (isShortcut) shortcutCache.get(entry.uri.toString()) else null
        val isArchive = !AppsUri.isAppUri(entry.uri) && ArchiveFormats.isArchive(entry.metadata.name, entry.metadata.mimeType)
        val inArchive = ArchiveUri.isArchiveUri(entry.uri)
        val isApp = AppsUri.isAppUri(entry.uri)
        val isCategory = AppsUri.isCategoryUri(entry.uri)
        val canExtractApp = if (isApp) {
            val pkg = AppsUri.getPackageName(entry.uri)
            pkg != null && appsProvider.canExtract(pkg)
        } else false

        val openGroup: List<String> = when {
            isApp -> listOf("Launch")
            isCategory -> listOf("Browse folder")
            isShortcut -> {
                val isAppShortcut = shortcut?.let { AppsUri.isAppUri(Uri.parse(it.targetUri)) } == true
                val openLabel = if (isAppShortcut) "Launch target app" else if (shortcut?.targetIsDirectory == true) "Open target folder" else "Open target file"
                if (shortcut?.targetIsDirectory == true || isAppShortcut) {
                    listOf(openLabel)
                } else {
                    listOf(openLabel, "Open target with external app")
                }
            }
            inArchive && entry.metadata.isDirectory -> listOf("Browse folder")
            inArchive -> listOf("Open")
            isArchive -> listOf("Browse archive", "Open with external app")
            entry.metadata.isDirectory -> listOf("Browse folder")
            else -> listOf("Open", "Open with external app")
        }

        val fileOpsGroup: List<String> = when {
            isApp -> {
                if (canExtractApp) {
                    listOf("Select", "Create shortcut", "Create archive", "Copy to…", "Move to…", "Uninstall")
                } else {
                    listOf("Select", "Create shortcut", "Uninstall")
                }
            }
            isCategory -> listOf("Select", "Copy to…", "Create shortcut")
            isShortcut -> listOf("Select", "Create archive", "Copy to…", "Move to…", "Delete")
            inArchive -> listOf("Create shortcut")
            else -> listOf("Select", "Create shortcut", "Create archive", "Copy to…", "Move to…", "Delete")
        }

        val propertiesGroup = listOf("Properties")

        showGroupedMenuDialog(
            title = entry.metadata.name,
            groups = listOf(openGroup, fileOpsGroup, propertiesGroup)
        ) { action ->
            when (action) {
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
                "Open target folder", "Open target file" -> openShortcut(entry)
                "Open target with external app" -> openShortcutWithExternalApp(entry)
                "Browse folder" -> navigate(entry.uri, displayName = entry.metadata.name)
                "Browse archive" -> {
                    val archiveUri = ArchiveUri.toArchiveUri(entry.uri)
                    navigate(archiveUri, displayName = entry.metadata.name)
                }
                "Open with external app" -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(entry.uri, entry.metadata.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        clipData = ClipData.newRawUri(entry.metadata.name, entry.uri)
                    }
                    try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_SHORT).show() }
                }
                "Open" -> {
                    if (entry.metadata.isDirectory) navigate(entry.uri, displayName = entry.metadata.name)
                    else if (isArchive) navigate(ArchiveUri.toArchiveUri(entry.uri), displayName = entry.metadata.name)
                    else open(entry)
                }
                "Select" -> {
                    selectionMode = true
                    selectedUris.clear()
                    selectedUris.add(entry.uri)
                    renderCurrentContents()
                    updateListingStatus()
                }
                "Create shortcut" -> promptCreateShortcut(entry)
                "Create archive" -> promptCreateArchive(listOf(entry))
                "Copy to…" -> showDestinationPicker(entry, false)
                "Move to…" -> showDestinationPicker(entry, true)
                "Rename" -> renameEntry(entry)
                "Delete" -> confirmDelete(entry)
                "Properties" -> showPropertiesDialog(entry)
                else -> showPropertiesDialog(entry)
            }
        }
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
        if (ArchiveUri.isArchiveUri(folderUri) || AppsUri.isAppsUri(folderUri)) {
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

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    directoryIconCache.evictAll()
                    thumbnailCache.evictAll()
                    failedThumbnails.evictAll()
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
                if (imageBytes == null || imageBytes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "Could not read selected image.", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val (iconFileName, _) = DirectoryIconResolver.targetIconFileName(displayName, mimeType)
                val ext = iconFileName.substringAfterLast('.', "png")
                customIconStore.setMimeIcon(mimeKey, ext, imageBytes)

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
    private fun showBatchActionMenu() {
        val selectedEntries = entries.filter { it.uri in selectedUris }
        if (selectedEntries.isEmpty()) {
            exitSelectionMode()
            return
        }
        val count = selectedEntries.size
        val fileOpsGroup = listOf("Copy to…", "Move to…", "Delete", "Create archive", "Create shortcuts", "Deselect all")
        val propertiesGroup = listOf("Properties")
        showGroupedMenuDialog(
            title = "$count item${if (count == 1) "" else "s"} selected",
            groups = listOf(fileOpsGroup, propertiesGroup)
        ) { action ->
            when (action) {
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
                "Create shortcuts" -> {
                    val defaultFolder = current?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
                        ?: defaultWritableFolderUri()
                        ?: return@showGroupedMenuDialog
                    showFolderPicker("Select Location for Shortcuts", defaultFolder) { destFolder, _ ->
                        exitSelectionMode()
                        io.execute {
                            selectedEntries.forEach { e ->
                                val base = e.metadata.name.removeSuffix(".apk").removeSuffix(".lnk")
                                val info = ShortcutInfo(
                                    targetUri = e.uri.toString(),
                                    targetPath = getPathDisplay(e.uri),
                                    displayNameOverride = base,
                                    targetMimeType = e.metadata.mimeType,
                                    targetIsDirectory = e.metadata.isDirectory
                                )
                                createShortcutFile(destFolder, "$base.lnk", info)
                            }
                        }
                    }
                }
                "Properties" -> showEntriesPropertiesDialog(selectedEntries, "$count items")
                "Deselect all" -> exitSelectionMode()
            }
        }
    }

    private fun openShortcut(entry: ResourceEntry) {
        fun handle(info: ShortcutInfo) {
            val targetUri = Uri.parse(info.targetUri)
            val targetName = info.displayNameOverride ?: info.targetPath?.substringAfterLast('/') ?: info.targetUri.substringAfterLast('/')
            if (AppsUri.isAppUri(targetUri)) {
                val pkg = AppsUri.getPackageName(targetUri)
                if (pkg != null) {
                    launchApp(pkg)
                    return
                }
            } else if (info.targetIsDirectory) {
                navigate(targetUri, displayName = targetName)
            } else if (ArchiveFormats.isArchive(info.targetPath ?: targetName, info.targetMimeType ?: "")) {
                if (ArchiveUri.isArchiveUri(targetUri)) {
                    val nestedVirtualPath = info.targetPath ?: targetName
                    io.execute {
                        try {
                            val cached = archiveProvider.extractToCache(targetUri, nestedVirtualPath)
                            val fileUri = Uri.fromFile(cached)
                            val nestedUri = ArchiveUri.toArchiveUri(fileUri)
                            runOnUiThread { navigate(nestedUri, displayName = targetName) }
                        } catch (e: Exception) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "Could not open archive shortcut: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                } else {
                    val archiveUri = ArchiveUri.toArchiveUri(targetUri)
                    navigate(archiveUri, displayName = targetName)
                }
            } else {
                val targetRef = resourceRef(targetUri)
                val targetEntry = ResourceEntry(
                    targetRef,
                    ResourceMetadata(
                        targetName,
                        info.targetMimeType ?: "application/octet-stream",
                        null, null, false
                    ),
                    targetUri
                )
                open(targetEntry)
            }
        }

        val cached = shortcutCache.get(entry.uri.toString())
        if (cached != null) {
            handle(cached)
        } else {
            io.execute {
                try {
                    val readRes = provider.openRead(entry.ref)
                    if (readRes is ProviderResult.Success) {
                        val text = readRes.value.use { it.reader().readText() }
                        val info = ShortcutInfo.fromJson(text)
                        if (info != null) {
                            shortcutCache.put(entry.uri.toString(), info)
                            runOnUiThread { handle(info) }
                            return@execute
                        }
                    }
                    runOnUiThread { Toast.makeText(this@MainActivity, "Invalid or broken shortcut file.", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Error opening shortcut: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun openShortcutWithExternalApp(entry: ResourceEntry) {
        val shortcut = shortcutCache.get(entry.uri.toString())
        fun launchExternal(info: ShortcutInfo) {
            val targetUri = Uri.parse(info.targetUri)
            if (AppsUri.isAppUri(targetUri)) {
                val pkg = AppsUri.getPackageName(targetUri)
                if (pkg != null) {
                    launchApp(pkg)
                    return
                }
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, info.targetMimeType ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri(info.displayNameOverride ?: targetUri.lastPathSegment ?: "file", targetUri)
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_SHORT).show()
            }
        }
        if (shortcut != null) {
            launchExternal(shortcut)
        } else {
            io.execute {
                val readRes = provider.openRead(entry.ref)
                if (readRes is ProviderResult.Success) {
                    val text = readRes.value.use { it.reader().readText() }
                    val info = ShortcutInfo.fromJson(text)
                    if (info != null) {
                        shortcutCache.put(entry.uri.toString(), info)
                        runOnUiThread { launchExternal(info) }
                        return@execute
                    }
                }
                runOnUiThread { Toast.makeText(this, "Invalid shortcut file.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun promptCreateShortcut(entry: ResourceEntry) {
        val defaultTargetFolder = current?.takeIf { !ArchiveUri.isArchiveUri(it) && !AppsUri.isAppsUri(it) }
            ?: defaultWritableFolderUri()
            ?: return

        var targetFolderUri: Uri = defaultTargetFolder
        val isApp = AppsUri.isAppUri(entry.uri)
        val baseName = if (isApp) entry.metadata.name.removeSuffix(".apk") else entry.metadata.name.removeSuffix(".lnk")
        val defaultShortcutName = "$baseName.lnk"

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
                    if (current == folderUri) {
                        load(folderUri)
                    }
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

    private fun showFolderPicker(
        title: String,
        initialSelection: Uri?,
        blockedUri: Uri? = null,
        onFolderSelected: (Uri, String) -> Unit
    ) {
        val context = dialogContext()
        fun isSameUri(a: Uri?, b: Uri?): Boolean {
            if (a == null || b == null) return false
            return try { sourceIdentity(a) == sourceIdentity(b) } catch (_: Exception) { a == b }
        }
        fun isWritableDestination(uri: Uri?): Boolean =
            uri != null && !ArchiveUri.isArchiveUri(uri) && !AppsUri.isAppsUri(uri)

        val defaultStart = if (AppsUri.isAppsUri(current) || initialSelection == null || AppsUri.isAppsUri(initialSelection)) {
            defaultWritableFolderUri()
        } else initialSelection
        var selected: Uri? = defaultStart?.takeIf { isWritableDestination(it) }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val selectedLabel = TextView(context).apply {
            text = selected?.let { getPathDisplay(it) } ?: "Choose a folder"
            textSize = 14f
            setTextColor(setTextColorForTheme())
            setPadding(dp(4), dp(4), dp(4), dp(8))
            minHeight = dp(40)
        }
        val nodes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(selectedLabel)
        body.addView(ScrollView(context).apply { addView(nodes) }, LinearLayout.LayoutParams(-1, dp(360)))

        val dialog = dialogBuilder()
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Select", null)
            .create()

        fun updateSelectButtonState() {
            val dest = selected
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                dest != null && !isSameUri(dest, blockedUri)
        }

        fun renderPickerTree() {
            nodes.removeAllViews()
            fun hasDestinations(list: List<TreeNode>): Boolean = list.any { node ->
                (node.uri != null && node.canExpand && !ArchiveUri.isArchiveUri(node.uri) && !AppsUri.isAppsUri(node.uri)) || hasDestinations(node.children)
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
                    if (isWritableDestination(destination)) {
                        selected = destination
                        val route = findTreeRoute(treeRoots, node).orEmpty().filter { it.uri != null }
                        val display = if (route.isNotEmpty()) route.joinToString(" / ") { it.name } else node.name
                        selectedLabel.text = display
                        updateSelectButtonState()
                        if (dialog.isShowing) renderPickerTree()
                    }
                },
                rerender = { if (dialog.isShowing) renderPickerTree() },
                excludedUri = AppsUri.ROOT_URI,
                blockedSelectionUri = null,
                isNodeSelected = { node -> isSameUri(selected, node.uri) },
                selectOnExpand = true,
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
                        selectedLabel.text = display
                        updateSelectButtonState()
                        renderTree()
                    }
                }
            )
        }

        dialog.setOnDismissListener {
            activePickerRerender = null
        }

        dialog.setOnShowListener {
            activePickerRerender = { if (dialog.isShowing) renderPickerTree() }
            val selectBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            updateSelectButtonState()
            selectBtn.setOnClickListener {
                val destination = selected
                if (destination != null && !isSameUri(destination, blockedUri)) {
                    dialog.dismiss()
                    onFolderSelected(destination, selectedLabel.text.toString())
                }
            }
            renderPickerTree()
        }
        dialog.show()
    }

    private fun promptCreateDirectoryInPicker(
        parentUri: Uri,
        onCreated: (ResourceEntry) -> Unit
    ) {
        if (ArchiveUri.isArchiveUri(parentUri) || AppsUri.isAppsUri(parentUri)) {
            Toast.makeText(this, "Cannot create directory in a read-only location.", Toast.LENGTH_SHORT).show()
            return
        }
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
                                    is ProviderResult.Failure -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showDestinationPicker(entry: ResourceEntry, move: Boolean) {
        showBatchDestinationPicker(listOf(entry), move)
    }
    private fun showBatchDestinationPicker(entries: List<ResourceEntry>, move: Boolean) {
        val start = current ?: return
        val title = if (entries.size == 1) {
            if (move) "Move ${entries[0].metadata.name} to" else "Copy ${entries[0].metadata.name} to"
        } else {
            if (move) "Move ${entries.size} items to" else "Copy ${entries.size} items to"
        }
        val defaultStart = if (AppsUri.isAppsUri(start)) {
            localProvider.rootUri.takeIf { localProvider.hasAccess() }
        } else null
        showFolderPicker(title, defaultStart, blockedUri = start) { destination, _ ->
            if (destination != start) {
                if (entries.size == 1) {
                    startTransfer(entries[0], move, destination)
                } else {
                    exitSelectionMode()
                    startBatchTransfer(entries, move, destination)
                }
            }
        }
    }
    private fun startTransfer(entry: ResourceEntry, move: Boolean, destination: Uri) {
        startBatchTransfer(listOf(entry), move, destination)
    }

    private fun startBatchTransfer(entries: List<ResourceEntry>, move: Boolean, destination: Uri) {
        val count = entries.size
        val label = if (count == 1) {
            "${if (move) "Move" else "Copy"} ${entries[0].metadata.name}"
        } else {
            "${if (move) "Move" else "Copy"} $count items"
        }
        val appEntries = entries.filter { AppsUri.isAppUri(it.uri) }
        val hasApps = appEntries.isNotEmpty()
        val isAppMove = move && hasApps

        val id = operations.submit(label, null, count) { update, cancelled ->
            FileOperations.batchTransfer(
                entries = entries,
                destinationFolderUri = destination,
                move = move && !hasApps,
                provider = provider,
                resourceRef = { uri -> resourceRef(uri) },
                update = update,
                isCancelled = cancelled
            )
            if (!cancelled()) {
                runOnUiThread {
                    directoryIconCache.evictAll()
                    refreshInternalSharedTree()
                }
            }
            if (!cancelled() && isAppMove) {
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

    private fun confirmBatchDelete(items: List<ResourceEntry>) {
        if (items.size == 1) {
            confirmDelete(items[0])
            return
        }
        val appItems = items.filter { AppsUri.isAppUri(it.uri) }
        val title = if (appItems.size == items.size) "Uninstall ${items.size} applications?" else "Delete ${items.size} items?"
        val msg = if (appItems.isNotEmpty()) "This will request Android to uninstall ${appItems.size} application${if (appItems.size == 1) "" else "s"} and delete selected files. This cannot be undone." else "This cannot be undone."
        dialogBuilder().setTitle(title).setMessage(msg)
            .setNegativeButton("Cancel", null).setPositiveButton(if (appItems.size == items.size) "Uninstall" else "Delete") { _, _ ->
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

    private fun startBatchDelete(entries: List<ResourceEntry>) {
        val count = entries.size
        val label = if (count == 1) "Delete ${entries[0].metadata.name}" else "Delete $count items"
        val id = operations.submit(label, null, count) { update, cancelled ->
            FileOperations.batchDelete(
                entries = entries,
                provider = provider,
                update = update,
                isCancelled = cancelled
            )
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
        val id = operations.submit("Create archive $archiveName", null, entries.size) { update, cancelled ->
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
                            current?.let { load(it) }
                            onRenamed?.invoke(renamed)
                        } else {
                            Toast.makeText(this, (result as? ProviderResult.Failure)?.message ?: "Rename unsupported", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show()
    }
    private fun confirmDelete(entry: ResourceEntry) {
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
        dialogBuilder().setTitle("Delete ${entry.metadata.name}?").setMessage("This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                startBatchDelete(listOf(entry))
            }.show()
    }
    private fun metadataLine(entry: ResourceEntry): String {
        val isShortcut = ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)
        if (isShortcut) {
            val shortcut = shortcutCache.get(entry.uri.toString())
            val target = shortcut?.targetPath ?: shortcut?.targetUri?.substringAfterLast('/') ?: "Target"
            return "Shortcut ↗ · $target"
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
    private fun iconFor(m: ResourceMetadata) = when {
        ArchiveFormats.isArchive(m.name, m.mimeType) -> "📦"
        m.mimeType.substringBefore('/') == "image" -> "🖼"
        m.mimeType.substringBefore('/') == "audio" -> "♫"
        m.mimeType.substringBefore('/') == "video" -> "▶"
        m.mimeType.substringBefore('/') == "text" -> "▤"
        else -> "📄"
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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
    override fun onBackPressed() {
        when {
            selectionMode -> exitSelectionMode()
            treeVisible -> showSidebar()
            homeMode && homePage != "root" -> showHomeRoot()
            history.size > 1 && !homeMode -> { history.removeAt(history.lastIndex); historyNames.removeAt(historyNames.lastIndex); current = history.last(); renderLocation(); load(current!!) }
            !homeMode && logicalLocationPrefix != null -> {
                when (logicalLocationPrefix) {
                    "Application public" -> showApplicationPublic()
                    "Installed Applications" -> openInstalledApps()
                    else -> showInternalShared()
                }
            }
            !homeMode -> showHomeRoot()
            else -> {
                val hasRunningOp = operations.snapshots().any {
                    it.state == OperationState.RUNNING || it.state == OperationState.QUEUED
                }
                if (hasRunningOp) {
                    confirmBackgroundPersistenceOnExit()
                } else {
                    super.onBackPressed()
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
                        operations.cancel(it.id)
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

    override fun onResume() {
        super.onResume()
        val snapshots = operations.snapshots()
        val hasActive = snapshots.any { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        if (hasActive && !isOperationWindowHidden && currentProgressDialog?.isShowing != true) {
            showProgressDialog()
        }
        updateOperationVisibilityUi(snapshots)
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

    override fun onStop() {
        super.onStop()
        val hasActive = operations.snapshots().any { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }
        if (hasActive) {
            OperationService.start(this)
        }
    }

    override fun onDestroy() {
        operations.removeListener(operationsListener)
        super.onDestroy()
    }

    private fun dialogContext() = ContextThemeWrapper(this, if (dark) R.style.AppTheme_Dark else R.style.AppTheme)
    private fun dialogBuilder() = android.app.AlertDialog.Builder(dialogContext())
    private fun displayNameFor(treeUri: Uri): String? = try {
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
        } else if (localProvider.owns(treeUri)) {
            if (treeUri == localProvider.rootUri) "Internal shared storage" else localProvider.fileFor(treeUri).name
        } else {
        val id = uriDocumentId(treeUri)
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        }
    } catch (_: Exception) { null }

    /** Resolve provider document IDs to user-facing names; never interpret IDs as POSIX paths. */
    private fun displayPathFor(treeUri: Uri): String? = try {
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
        } else if (localProvider.owns(treeUri)) localProvider.fileFor(treeUri).path else {
        val selectedId = uriDocumentId(treeUri)
        val selectedDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, selectedId)
        val path = DocumentsContract.findDocumentPath(contentResolver, selectedDocument)?.path ?: return null
        val names = path.mapNotNull { documentId ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
        names.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        }
    } catch (_: Exception) { null }

    private fun uriDocumentId(uri: Uri): String = if (uri.pathSegments.firstOrNull() == "document" || uri.pathSegments.getOrNull(2) == "document") {
        DocumentsContract.getDocumentId(uri)
    } else {
        DocumentsContract.getTreeDocumentId(uri)
    }

    /** SAF document IDs are opaque; pair them with their provider authority without parsing them as paths. */
    private fun sourceIdentity(uri: Uri): String = when {
        AppsUri.isAppsUri(uri) -> uri.toString()
        ArchiveUri.isArchiveUri(uri) -> uri.toString()
        localProvider.owns(uri) -> uri.toString()
        else -> "${uri.authority.orEmpty()}|${uriDocumentId(uri)}"
    }
    private fun isPrimaryExternalStorageUri(uri: Uri): Boolean = try {
        uri.scheme == "content" && uri.authority == externalStorageDocumentsAuthority && uriDocumentId(uri).substringBefore(':') == "primary"
    } catch (_: Exception) { false }
}
