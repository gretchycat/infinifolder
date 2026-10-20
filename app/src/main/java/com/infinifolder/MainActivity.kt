package com.infinifolder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.os.storage.StorageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ContextThemeWrapper
import android.util.TypedValue
import android.widget.*
import android.graphics.drawable.GradientDrawable
import com.infinifolder.model.*
import com.infinifolder.ops.AppOperations
import com.infinifolder.ops.OperationState
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
    private val io = Executors.newSingleThreadExecutor()
    private val accessIo = Executors.newFixedThreadPool(2)
    private val imageIo = Executors.newFixedThreadPool(2)
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private lateinit var provider: StorageProvider
    private lateinit var localProvider: LocalStorageProvider
    private var internalSharedEntries = emptyList<ResourceEntry>()
    private var internalListGeneration = 0
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
    private var loadGeneration = 0
    private var entries = emptyList<ResourceEntry>()
    private var listingIsLoading = false
    private var listingNotice: String? = null
    private var listingError: String? = null
    private var grid = true
    private var dark = false
    private var sortByName = true
    private var iconSizeDp = 56
    private var treeVisible = false
    private var homeMode = false
    private var homePage = "root"
    private var logicalLocationPrefix: String? = null
    private val treeRoots = mutableListOf<TreeNode>()
    private val homeItems = mutableListOf<HomeItem>()
    private val applicationPublicItems = mutableListOf<HomeItem>()
    private val pickTreeRequest = 4101
    private var pendingTreeSourceKey: String? = null
    private var pendingTreeTarget: Uri? = null
    private var pendingTreeAuthority: String? = null
    private var applicationSourceGeneration = 0
    private var permissionQueryGeneration = 0
    private var accessRequestGeneration = 0
    private val externalStorageDocumentsAuthority = "com.android.externalstorage.documents"

    private data class TreeNode(
        val key: String,
        val uri: Uri?,
        val name: String,
        val icon: String,
        val depth: Int,
        var onOpen: () -> Unit,
        var canExpand: Boolean,
        var ancestorContinues: List<Boolean> = emptyList(),
        var isLast: Boolean = true,
        var expanded: Boolean = false,
        var loading: Boolean = false,
        var loadId: Int = 0,
        val children: MutableList<TreeNode> = mutableListOf(),
        var onLongPress: (() -> Unit)? = null,
        var needsAccessBadge: Boolean = false,
        var onRequestAccess: (() -> Unit)? = null
    )
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
        private val hasVisibleChildren: Boolean
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
                // The short leader ends at the box edge; each level advances only 24 dp.
                canvas.drawLine(0f, y, ((width - boxSize) / 2f).coerceAtLeast(0f), y, paint)
            }
            if (hasVisibleChildren) {
                canvas.drawLine(width / 2f, y, width / 2f, height.toFloat(), paint)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shared = getSharedPreferences("browser", MODE_PRIVATE)
        grid = shared.getBoolean("grid", true); dark = shared.getBoolean("dark", false); sortByName = shared.getBoolean("sort_name", true)
        treeVisible = shared.getBoolean("tree_visible", false)
        iconSizeDp = shared.getInt("icon_size_dp", 56).coerceIn(32, 96)
        localProvider = LocalStorageProvider(applicationContext)
        provider = RoutingStorageProvider(requireNotNull(providerRegistry.create("saf", contentResolver)), localProvider)
        hadStorageAccess = localProvider.hasAccess()
        waitingForStorageAccess = savedInstanceState?.getBoolean("storage_access_pending", false) ?: false
        pendingTreeSourceKey = savedInstanceState?.getString("pending_tree_source")
        pendingTreeTarget = savedInstanceState?.getString("pending_tree_target")?.let(Uri::parse)
        pendingTreeAuthority = savedInstanceState?.getString("pending_tree_authority")
        buildUi()
        showHomeRoot()
        val saved = savedInstanceState?.getStringArrayList("history")?.map(Uri::parse)
        if (!saved.isNullOrEmpty()) {
            history.addAll(saved)
            val names = savedInstanceState.getStringArrayList("history_names")
            if (names != null && names.size == saved.size) historyNames.addAll(names) else historyNames.addAll(saved.mapIndexed { index, _ -> if (index == 0) "Storage" else "Folder" })
            current = history.last(); homeMode = false; updateCreateAction(); renderLocation(); load(current!!)
        } else shared.getString("root", null)?.let { openRoot(Uri.parse(it)) }
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
            } else current?.let { load(it) }
        }
        button(if (grid) "☷" else "▦", fg) { grid = !grid; shared.edit().putBoolean("grid", grid).apply(); renderCurrentContents() }
        button("⋮", fg) { showOptions() }
        root.addView(toolbar)
        breadcrumb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(breadcrumb, android.view.ViewGroup.LayoutParams(-2, -2))
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()); setPadding(dp(4), dp(4), dp(4), dp(8)) }
        root.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fileScroll = ScrollView(this).apply { addView(content) }
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
        createButton.visibility = if (homeMode && homePage != "root") View.GONE else View.VISIBLE
        createButton.text = if (homeMode) "＋" else "📁+"
        createButton.contentDescription = if (homeMode) "Add saved connection" else "Create directory"
    }
    private fun onCreateAction() {
        if (homeMode && homePage == "root") showAddConnectionDialog() else if (!homeMode) showCreateDirectoryDialog()
    }
    private fun showCreateDirectoryDialog() {
        val parent = current ?: return
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
            if (localProvider.owns(target) || key == "internal_shared" || key == "application_public") return@mapNotNull null
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
    }
    private fun openRoot(uri: Uri, logicalPrefix: String? = null, rememberAuthorizedRoot: Boolean = true) {
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
        if (localProvider.owns(uri) || AppDocumentSources.isAppAuthority(uri.authority) ||
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
                if (prefix == "Application public") showApplicationPublic() else showInternalShared()
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
                        status.text = when {
                            result.isLoading && loadingAttempt >= 8 -> "The storage provider is still loading. Tap Refresh to try again."
                            result.isLoading -> "Loading… ${entries.size} items received"
                            result.notice != null -> "${entries.size} items · ${result.notice}"
                            else -> "${entries.size} items"
                        }
                        renderEntries()
                        if (result.isLoading && loadingAttempt < 8) root.postDelayed({
                            if (!isFinishing && !isDestroyed && requestGeneration == loadGeneration && !homeMode && current == uri) {
                                load(uri, loadingAttempt + 1)
                            }
                        }, 1000)
                        if (uri == localProvider.rootUri) {
                            internalSharedEntries = result.value
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
    private fun resourceRef(uri: Uri) = ResourceRef(if (localProvider.owns(uri)) "local" else "saf", uri.toString())
    private fun isAccessFailure(message: String, cause: Throwable?): Boolean =
        cause is SecurityException || listOf("revoked", "permission", "access denied", "permission denied")
            .any { message.contains(it, ignoreCase = true) }
    private fun renderEntries() {
        content.removeAllViews()
        val rows = if (sortByName) entries.sortedWith(compareBy<ResourceEntry> { !it.metadata.isDirectory }.thenBy { it.metadata.name.lowercase() }) else entries.sortedByDescending { it.metadata.modifiedAtMillis ?: 0L }
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
    }
    private fun renderCurrentContents() { if (homeMode) renderHomeItems() else renderEntries() }
    private fun entryCard(entry: ResourceEntry, asGrid: Boolean): View {
        val fg = if (dark) 0xFFF0F0E9.toInt() else 0xFF20231F.toInt()
        val box = LinearLayout(this).apply { orientation = if (asGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = if (asGrid) Gravity.CENTER else Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); minimumHeight = dp(64) }
        val icon = if (!entry.metadata.isDirectory && entry.metadata.mimeType.startsWith("image/")) {
            imagePreview(entry, asGrid)
        } else TextView(this).apply {
            text = if (entry.metadata.isDirectory) "📁" else iconFor(entry.metadata)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            minWidth = dp(if (asGrid) iconSizeDp else 52)
            if (asGrid) minimumHeight = dp(iconSizeDp)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; if (!asGrid) layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        labels.addView(TextView(this).apply { text = entry.metadata.name; textSize = 14f; setTextColor(fg); maxLines = if (asGrid) 2 else 1; gravity = if (asGrid) Gravity.CENTER else Gravity.START })
        if (!asGrid) labels.addView(TextView(this).apply { text = metadataLine(entry); textSize = 12f; setTextColor(if (dark) 0xFFBFC5BA.toInt() else 0xFF686D65.toInt()) })
        box.addView(icon); box.addView(labels)
        box.setOnClickListener { if (entry.metadata.isDirectory) navigate(entry.uri, displayName = entry.metadata.name) else open(entry) }
        box.setOnLongClickListener { showEntryMenu(entry); true }
        box.setBackgroundResource(android.R.drawable.list_selector_background)
        return box
    }
    private fun imagePreview(entry: ResourceEntry, asGrid: Boolean): View {
        val sideDp = if (asGrid) iconSizeDp else 44
        val sidePx = dp(sideDp)
        val previewKey = "${entry.ref.providerId}:${entry.ref.documentId}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}"
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(if (asGrid) iconSizeDp else 52), sidePx)
            foregroundGravity = Gravity.CENTER
        }
        val placeholder = TextView(this).apply {
            text = "🖼"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(if (dark) 0xFF252922.toInt() else 0xFFE8EBE3.toInt())
                cornerRadius = dp(8).toFloat()
            }
            clipToOutline = true
            tag = previewKey
            layoutParams = FrameLayout.LayoutParams(sidePx, sidePx, Gravity.CENTER)
        }
        frame.addView(placeholder)
        frame.addView(image)
        val cached = thumbnailCache.get(previewKey)
        if (cached != null) {
            image.setImageBitmap(cached)
            placeholder.visibility = View.GONE
        } else {
            imageIo.execute {
                val bitmap = decodeThumbnail(entry.uri, sidePx)
                if (bitmap != null) thumbnailCache.put(previewKey, bitmap)
                runOnUiThread {
                    if (image.tag == previewKey && bitmap != null) {
                        image.setImageBitmap(bitmap)
                        placeholder.visibility = View.GONE
                    }
                }
            }
        }
        return frame
    }
    private fun decodeThumbnail(uri: Uri, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 40_000 || bounds.outHeight > 40_000 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 160_000_000L) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
    private fun open(entry: ResourceEntry) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, entry.metadata.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_SHORT).show() }
    }
    private fun showHomeRoot() {
        loadGeneration++
        accessRequestGeneration++
        homeMode = true
        homePage = "root"
        logicalLocationPrefix = null
        updateCreateAction()
        renderLocation()
        status.text = "Locations and storage sources"
        content.removeAllViews()
        homeItems.clear()
        val grantedRoots = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.pathSegments.contains("tree") }
            .map { it.uri }
            .filterNot { AppDocumentSources.isAppAuthority(it.authority) }
            .distinctBy(::sourceIdentity)
            .toMutableList()
        shared.getString("root", null)?.let { rootString ->
            val uri = Uri.parse(rootString)
            if (!localProvider.owns(uri) && !AppDocumentSources.isAppAuthority(uri.authority) &&
                grantedRoots.none { sourceIdentity(it) == sourceIdentity(uri) }) grantedRoots.add(uri)
        }
        grantedRoots.forEach { uri ->
            if (localProvider.hasAccess() && isPrimaryExternalStorageUri(uri)) return@forEach
            val name = shared.getString("source_name_${uri}", null) ?: "Storage folder"
            val path = shared.getString("source_path_v2_${uri}", null) ?: name
            val key = "authorized_${sourceIdentity(uri)}"
            homeItems += HomeItem("💾", name, path, { openRoot(uri) }, { showAuthorizedRootActions(uri, name) }, key, uri, true, false,
                initialUri = uri, treeParentKey = if (isPrimaryExternalStorageUri(uri)) "internal_shared" else null)
            if (shared.getString("source_path_v2_${uri}", null) == null) io.execute {
                val resolved = displayPathFor(uri) ?: name
                shared.edit().putString("source_path_v2_${uri}", resolved).apply()
                runOnUiThread {
                    if (homeMode) {
                        val index = homeItems.indexOfFirst { it.treeUri == uri }
                        if (index >= 0) homeItems[index] = homeItems[index].copy(detail = resolved)
                        renderHomeItems()
                    }
                }
            }
        }
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
                homeItems += HomeItem("📱", "Internal shared storage", "Shared folders", {
                    showInternalShared()
                }, treeKey = key, treeExpandable = true, needsAccessBadge = !localProvider.hasAccess())
            } else {
                homeItems += HomeItem(if (volume.isRemovable) "💾" else "📱", description, detail, {
                    if (grantedUri != null) openRoot(grantedUri)
                    else if (mounted && android.os.Build.VERSION.SDK_INT >= 29) {
                        pendingTreeSourceKey = key
                        startActivityForResult(volume.createOpenDocumentTreeIntent(), pickTreeRequest)
                    }
                    else if (mounted) rootPicker(sourceKey = key) else Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
                }, treeKey = key, treeUri = grantedUri, treeExpandable = mounted, needsAccessBadge = mounted && grantedUri == null)
            }
        }
        if (homeItems.none { it.treeKey == "internal_shared" }) {
            homeItems += HomeItem("📱", "Internal shared storage", "Shared folders", { showInternalShared() },
                treeKey = "internal_shared", treeExpandable = true, needsAccessBadge = !localProvider.hasAccess())
        }
        savedConnections().forEach { connection ->
            val source = providerRegistry.sourceTypes().firstOrNull { it.id == connection.sourceTypeId }
            val icon = source?.icon ?: "🌐"
            val detail = connection.endpoint ?: "Saved connection · provider not implemented"
            homeItems += HomeItem(icon, connection.displayName, detail, {
                dialogBuilder().setTitle(connection.displayName)
                    .setMessage("This ${source?.label ?: connection.sourceTypeId} connection is saved, but network browsing is not implemented yet.")
                    .setPositiveButton("OK", null).show()
            }, { showSavedConnectionActions(connection) })
        }
        homeItems += HomeItem("📱", "Application public", "Storage exposed by applications", {
            showApplicationPublic()
        }, treeKey = "application_public", treeExpandable = true)
        renderHomeItems()
        rebuildTreeRoots()
        resolveExistingHomeGrants()
        discoverDocumentProviderRoots()
        refreshInternalSharedTree()
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
        val iconFrame = FrameLayout(this).apply {
            val iconSize = if (asGrid) iconSizeDp else 28
            val iconParams = if (asGrid) LinearLayout.LayoutParams(dp(iconSize), dp(iconSize))
                else LinearLayout.LayoutParams(dp(52), dp(48))
            layoutParams = iconParams
        }
        iconFrame.addView(TextView(this).apply {
            text = item.icon; setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (asGrid) iconSizeDp.toFloat() else 28f)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        if (item.needsAccessBadge) {
            iconFrame.addView(accessBadge().apply { setOnClickListener { activateHomeItem(item) } }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.TOP))
        }
        box.addView(iconFrame)
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; if (!asGrid) layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        labels.addView(TextView(this).apply {
            text = item.title; textSize = 14f; setTextColor(setTextColorForTheme()); maxLines = if (asGrid) 2 else 1
            gravity = if (asGrid) Gravity.CENTER else Gravity.START
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
                else -> item.treeUri ?: item.treeKey?.let(::treeSourceUri)
            }
            if (uri != null && !seen.add(sourceIdentity(uri))) return@forEach
            val action = if (uri != null && item.treeExpandable) ({ openRoot(uri) }) else item.action
            val existing = priorNodes[key]?.takeIf { it.uri == uri && it.name == item.title && it.icon == item.icon }
            val node = existing ?: TreeNode(key, uri, item.title, item.icon, 1, action, item.treeExpandable)
            node.onOpen = action
            node.canExpand = item.treeExpandable
            node.onLongPress = item.longAction
            node.needsAccessBadge = item.needsAccessBadge
            node.onRequestAccess = when {
                !item.needsAccessBadge -> null
                key == "internal_shared" -> item.action
                key == "application_public" -> item.action
                item.initialUri != null -> ({ rootPicker(item.initialUri, key) })
                else -> item.action
            }
            if (key == "internal_shared") {
                node.children.clear()
                val childItems = if (localProvider.hasAccess()) internalSharedEntries
                    .filter { it.metadata.isDirectory }.sortedBy { it.metadata.name.lowercase() }.map { entry ->
                        HomeItem("📁", entry.metadata.name, "Folder", { openRoot(entry.uri, rememberAuthorizedRoot = false) },
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
        if (!::treeContent.isInitialized) return
        treeContent.removeAllViews()
        renderTreeNodes(treeContent, treeRoots, ::selectTreeNode, ::renderTree)
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
            else -> if (node.uri != null) openTreeNode(node) else node.onOpen()
        }
    }
    private fun renderTreeNodes(
        host: LinearLayout,
        nodes: List<TreeNode>,
        onSelect: (TreeNode) -> Unit,
        rerender: () -> Unit,
        excludedUri: Uri? = null,
        blockedSelectionUri: Uri? = null
    ) {
        updateTreeBranches(nodes)
        nodes.forEach { node ->
            if (excludedUri != null && node.uri == excludedUri) return@forEach
            addTreeNodeRow(node, host, onSelect, rerender, excludedUri, blockedSelectionUri)
        }
    }
    private fun addTreeNodeRow(
        node: TreeNode,
        host: LinearLayout,
        onSelect: (TreeNode) -> Unit,
        rerender: () -> Unit,
        excludedUri: Uri?,
        blockedSelectionUri: Uri?
    ) {
        fun activateNode() {
            activateTreeItem(node.uri, blockedSelectionUri, node.needsAccessBadge,
                { (node.onRequestAccess ?: node.onOpen).invoke() }, { onSelect(node) })
        }
        val selected = isTreeNodeSelected(node)
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
            if (node.needsAccessBadge) setOnClickListener { activateNode() }
            else if (node.canExpand) setOnClickListener { toggleTreeNode(node, rerender) }
            contentDescription = if (node.expanded) "Collapse ${node.name}" else "Expand ${node.name}"
            isFocusable = node.canExpand || node.needsAccessBadge
        }
        disclosureTarget.addView(TreeDisclosureConnector(
            this,
            hasParent = node.depth > 0,
            hasVisibleChildren = node.expanded && (node.children.isNotEmpty() || node.loading)
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
        iconStack.addView(TextView(this).apply {
            text = node.icon; textSize = 17f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(dp(30), dp(32)))
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
        if (node.expanded) node.children.forEach { child ->
            if (excludedUri == null || child.uri != excludedUri) addTreeNodeRow(child, host, onSelect, rerender, excludedUri, blockedSelectionUri)
        }
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
        if (node.key == "virtual-root" || node.key == "internal_shared") {
            node.expanded = true; rerender()
            if (node.key == "internal_shared") refreshInternalSharedTree()
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
                        val folders = result.value.filter { it.metadata.isDirectory }
                        folders.forEachIndexed { index, child ->
                            lateinit var childNode: TreeNode
                            childNode = TreeNode(
                                "${node.key}/${sourceIdentity(child.uri)}", child.uri, child.metadata.name, "📁", node.depth + 1,
                                { openTreeNode(childNode) }, true, node.ancestorContinues + !node.isLast, index == folders.lastIndex
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
        val grantedRoots = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.pathSegments.contains("tree") }
            .map { it.uri }
            .distinctBy(::sourceIdentity)
            .toMutableList()
        shared.getString("root", null)?.let { rootUri ->
            val uri = Uri.parse(rootUri)
            if (grantedRoots.none { sourceIdentity(it) == sourceIdentity(uri) }) grantedRoots.add(uri)
        }
        if (grantedRoots.isEmpty()) {
            row("📂", "Choose a storage folder…", "Grant access with Android's storage picker") { rootPicker() }
        } else {
            grantedRoots.forEach { uri ->
                val name = shared.getString("source_name_${uri}", null)
                    ?: (if (uri == current) historyNames.firstOrNull() else null)
                    ?: "Storage folder"
                val cachedPath = shared.getString("source_path_v2_${uri}", null)
                val detailView = row("💾", name, cachedPath ?: "Loading folder path…", onLongPress = { showAuthorizedRootActions(uri, name) }) { openRoot(uri) }
                if (cachedPath == null) io.execute {
                    val path = displayPathFor(uri) ?: name
                    shared.edit().putString("source_path_v2_${uri}", path).apply()
                    runOnUiThread { detailView.text = path }
                }
            }
        }

        val storageManager = getSystemService(StorageManager::class.java)
        storageManager?.storageVolumes?.forEach { volume ->
            val mounted = volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
            val primary = volume.isPrimary
            val title = volume.getDescription(this)
            val subtitle = when {
                !mounted -> "Not currently mounted"
                volume.isRemovable -> "Removable storage · choose a folder to grant access"
                primary -> "Device storage · choose a folder to grant access"
                else -> "Available volume · choose a folder to grant access"
            }
            row(if (volume.isRemovable) "💾" else "📱", title, subtitle, mounted) {
                if (android.os.Build.VERSION.SDK_INT >= 29) startActivityForResult(volume.createOpenDocumentTreeIntent(), pickTreeRequest)
                else rootPicker()
            }
        }
        row("➕", "Choose another storage location…", "Opens Android's access grant picker") { rootPicker() }

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
        val cachedPath = shared.getString("source_path_v2_${uri}", null)
        val grants = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val permissions = buildList {
            if (grants?.isReadPermission == true) add("Read")
            if (grants?.isWritePermission == true) add("Write")
        }.joinToString(", ").ifEmpty { "Available to InfiniFolder" }
        val message = "Path: ${cachedPath ?: "Resolving…"}\nProvider: Android Storage Access Framework\nAccess: $permissions"
        val dialog = dialogBuilder().setTitle("Properties").setMessage(message).setPositiveButton("OK", null).create()
        dialog.show()
        if (cachedPath == null) io.execute {
            val path = displayPathFor(uri) ?: name
            shared.edit().putString("source_path_v2_${uri}", path).apply()
            runOnUiThread {
                if (!isFinishing) {
                    dialog.setMessage("Path: $path\nProvider: Android Storage Access Framework\nAccess: $permissions")
                }
            }
        }
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
        val choices = arrayOf(if (grid) "List / details view" else "Grid view", if (sortByName) "Sort by modified" else "Sort by name", if (dark) "Light theme" else "Dark theme", "Operations", "Icon size (${iconSizeDp} dp)")
        dialogBuilder().setTitle("View options").setItems(choices) { _, i -> when (i) {
            0 -> { grid = !grid; shared.edit().putBoolean("grid", grid).apply(); renderCurrentContents() }
            1 -> { sortByName = !sortByName; shared.edit().putBoolean("sort_name", sortByName).apply(); if (!homeMode) renderEntries() }
            2 -> { dark = !dark; shared.edit().putBoolean("dark", dark).apply(); buildUi(); if (homeMode) showHomeRoot() else current?.let { load(it) } }
            3 -> showOperations()
            4 -> showIconSizeDialog()
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
    private fun showOperations() {
        val active = operations.snapshots()
        val labels = active.map { "${it.state}: ${it.label}${it.message?.let { detail -> " — $detail" } ?: ""}" }
        if (labels.isEmpty()) Toast.makeText(this, "No transfers yet", Toast.LENGTH_SHORT).show()
        else dialogBuilder().setTitle("Operations").setItems(labels.toTypedArray()) { _, index ->
            val operation = active[index]
            if (operation.state == OperationState.RUNNING || operation.state == OperationState.QUEUED) {
                dialogBuilder().setMessage("Cancel ${operation.label}?").setNegativeButton("Keep running", null)
                    .setPositiveButton("Cancel") { _, _ -> operations.cancel(operation.id) }.show()
            }
        }.setPositiveButton("Close", null).show()
    }
    private fun showEntryMenu(entry: ResourceEntry) {
        val actions = arrayOf("Open", "Copy to…", "Move to…", "Rename", "Delete", "Properties")
        dialogBuilder().setTitle(entry.metadata.name).setItems(actions) { _, i ->
            when (i) {
                0 -> { if (entry.metadata.isDirectory) navigate(entry.uri, displayName = entry.metadata.name) else open(entry) }
                1 -> showDestinationPicker(entry, false)
                2 -> showDestinationPicker(entry, true)
                3 -> renameEntry(entry)
                4 -> confirmDelete(entry)
                else -> dialogBuilder().setMessage("${entry.metadata.mimeType}\n${metadataLine(entry)}\n${entry.uri}").setPositiveButton("OK", null).show()
            }
        }.show()
    }
    private fun showDestinationPicker(entry: ResourceEntry, move: Boolean) {
        val start = current ?: return
        val context = dialogContext()
        var selected: Uri? = null
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val selectedLabel = TextView(context).apply {
            text = "Choose a destination folder"; textSize = 14f; setTextColor(setTextColorForTheme())
            setPadding(dp(4), dp(4), dp(4), dp(8)); minHeight = dp(40)
        }
        val nodes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(selectedLabel)
        body.addView(ScrollView(context).apply { addView(nodes) }, LinearLayout.LayoutParams(-1, dp(360)))
        val dialog = dialogBuilder().setTitle(if (move) "Move ${entry.metadata.name} to" else "Copy ${entry.metadata.name} to")
            .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Choose destination", null).create()

        fun renderDestinationTree() {
            nodes.removeAllViews()
            fun hasDestinations(nodes: List<TreeNode>): Boolean = nodes.any { node ->
                (node.uri != null && node.canExpand) || hasDestinations(node.children)
            }
            val hasDestinations = hasDestinations(treeRoots)
            if (!hasDestinations) {
                nodes.addView(TextView(context).apply {
                    text = "No authorized folders are available as destinations."; textSize = 14f
                    setTextColor(setTextColorForTheme()); setPadding(dp(8), dp(12), dp(8), dp(12))
                })
                return
            }
            renderTreeNodes(
                nodes, treeRoots,
                onSelect = { node ->
                    val destination = node.uri
                    if (destination != null && destination != start) {
                        selected = destination
                        val route = findTreeRoute(treeRoots, node).orEmpty().filter { it.uri != null }
                        selectedLabel.text = route.joinToString(" / ") { it.name }
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                    }
                },
                rerender = { if (dialog.isShowing) renderDestinationTree() },
                excludedUri = if (entry.metadata.isDirectory) entry.uri else null,
                blockedSelectionUri = start
            )
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val destination = selected
                if (destination != null && destination != start) {
                    dialog.dismiss()
                    startTransfer(entry, move, destination)
                }
            }
            renderDestinationTree()
        }
        dialog.show()
    }
    private fun startTransfer(entry: ResourceEntry, move: Boolean, destination: Uri) {
        status.text = if (move) "Moving ${entry.metadata.name}…" else "Copying ${entry.metadata.name}…"
        val id = operations.submit(if (move) "Move ${entry.metadata.name}" else "Copy ${entry.metadata.name}") { _, cancelled ->
            if (cancelled()) throw InterruptedException("Cancelled")
            val result = if (move) provider.move(entry.ref, resourceRef(destination))
                else provider.copy(entry.ref, resourceRef(destination))
            if (result !is ProviderResult.Success) throw IllegalStateException(when (result) {
                is ProviderResult.Failure -> result.message
                is ProviderResult.Unsupported -> "This storage provider does not support this operation"
                else -> "Operation failed"
            })
        }
        Thread {
            while (operations.snapshots().firstOrNull { it.id == id }?.state in setOf(OperationState.QUEUED, OperationState.RUNNING)) Thread.sleep(250)
            val snapshot = operations.snapshots().firstOrNull { it.id == id }
            runOnUiThread {
                status.text = when (snapshot?.state) {
                    OperationState.COMPLETE -> if (move) "Move complete" else "Copy complete"
                    OperationState.CANCELLED -> "Operation cancelled"
                    else -> snapshot?.message ?: "Operation failed"
                }
                current?.let { load(it) }
            }
        }.start()
    }
    private fun renameEntry(entry: ResourceEntry) {
        val input = EditText(dialogContext()).apply { setText(entry.metadata.name); setSelection(text.length) }
        dialogBuilder().setTitle("Rename").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ -> io.execute {
                val result = provider.rename(entry.ref, input.text.toString())
                runOnUiThread { if (result is ProviderResult.Success) current?.let { load(it) } else Toast.makeText(this, (result as? ProviderResult.Failure)?.message ?: "Rename unsupported", Toast.LENGTH_LONG).show() }
            } }.show()
    }
    private fun confirmDelete(entry: ResourceEntry) {
        dialogBuilder().setTitle("Delete ${entry.metadata.name}?").setMessage("This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> io.execute {
                val result = provider.delete(entry.ref)
                runOnUiThread { if (result is ProviderResult.Success) current?.let { load(it) } else Toast.makeText(this, (result as? ProviderResult.Failure)?.message ?: "Delete unsupported", Toast.LENGTH_LONG).show() }
            } }.show()
    }
    private fun metadataLine(entry: ResourceEntry): String {
        val size = entry.metadata.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(this, it) } ?: "—"
        val date = entry.metadata.modifiedAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: ""
        return if (entry.metadata.isDirectory) "Folder · $date" else "$size · $date"
    }
    private fun iconFor(m: ResourceMetadata) = when (m.mimeType.substringBefore('/')) { "image" -> "🖼"; "audio" -> "♫"; "video" -> "▶"; "text" -> "▤"; else -> "📄" }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("history", ArrayList(history.map(Uri::toString)))
        outState.putStringArrayList("history_names", ArrayList(historyNames))
        outState.putBoolean("storage_access_pending", waitingForStorageAccess)
        outState.putString("pending_tree_source", pendingTreeSourceKey)
        outState.putString("pending_tree_target", pendingTreeTarget?.toString())
        outState.putString("pending_tree_authority", pendingTreeAuthority)
    }
    override fun onBackPressed() {
        when {
            treeVisible -> showSidebar()
            homeMode && homePage != "root" -> showHomeRoot()
            history.size > 1 && !homeMode -> { history.removeAt(history.lastIndex); historyNames.removeAt(historyNames.lastIndex); current = history.last(); renderLocation(); load(current!!) }
            !homeMode && logicalLocationPrefix != null -> {
                if (logicalLocationPrefix == "Application public") showApplicationPublic() else showInternalShared()
            }
            !homeMode -> showHomeRoot()
            else -> super.onBackPressed()
        }
    }
    override fun onResume() {
        super.onResume()
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

    private fun dialogContext() = ContextThemeWrapper(this, if (dark) R.style.AppTheme_Dark else R.style.AppTheme)
    private fun dialogBuilder() = android.app.AlertDialog.Builder(dialogContext())
    private fun displayNameFor(treeUri: Uri): String? = try {
        if (localProvider.owns(treeUri)) {
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
        if (localProvider.owns(treeUri)) localProvider.fileFor(treeUri).path else {
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
    private fun sourceIdentity(uri: Uri): String = if (localProvider.owns(uri)) uri.toString()
        else "${uri.authority.orEmpty()}|${uriDocumentId(uri)}"
    private fun isPrimaryExternalStorageUri(uri: Uri): Boolean = try {
        uri.authority == externalStorageDocumentsAuthority && uriDocumentId(uri).substringBefore(':') == "primary"
    } catch (_: Exception) { false }
}
