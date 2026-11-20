package com.infinifolder.overlay

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.*
import android.view.SurfaceControlViewHost
import com.infinifolder.FileSelectionRelayProvider
import com.infinifolder.StorageDocumentsProvider
import com.infinifolder.storage.ContainerLinksProvider
import com.infinifolder.storage.DiagnosticLog
import io.github.gretchycat.infiniselect.EmbeddedBrowserProtocol as P
import io.github.gretchycat.infiniselect.FileSelectionContract as F
import io.github.gretchycat.infiniselect.SelectionAction
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Folder-owned sessions. The caller receives this browser's actual interactive view surface. */
@TargetApi(30)
internal class EmbeddedBrowserSessions(private val app: Context) {
    private data class Session(
        val uid: Int,
        val lifetime: IBinder,
        val death: IBinder.DeathRecipient,
        val host: SurfaceControlViewHost,
        val browser: FolderBrowserController,
        var surface: SurfaceControlViewHost.SurfacePackage? = null,
    )

    private val server = Binder()
    private val main = Handler(Looper.getMainLooper())
    private val sessions = linkedMapOf<String, Session>()
    private val selectionWorker = Executors.newSingleThreadExecutor()

    fun call(uid: Int, caller: String, args: Bundle): Bundle {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return execute(uid, caller, args)
            } catch (error: Exception) {
                throw reportFailure(args, error)
            }
        }
        val created = java.util.concurrent.atomic.AtomicReference<String?>()
        val task = FutureTask {
            execute(uid, caller, args).also {
                if (args.getString(P.ACTION) == P.CREATE) created.set(it.getString(P.SESSION))
            }
        }
        main.post(task)
        try {
            return task.get(10, TimeUnit.SECONDS)
        } catch (error: Exception) {
            task.cancel(false)
            main.post { created.get()?.let(::close) }
            throw reportFailure(args, error)
        }
    }

    private fun reportFailure(args: Bundle, error: Exception): IllegalStateException {
        val failure = browserRequestFailure(error)
        DiagnosticLog.record(
            "embedded.browser.request-failed",
            args.getString(P.ACTION).orEmpty(),
            failure,
        )
        // Binder transports this message; its cause chain remains in Folder's diagnostic log.
        return IllegalStateException(
            DiagnosticLog.sanitize(failure.message.orEmpty()),
            failure.cause,
        )
    }

    private inline fun <T> setupStep(name: String, block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            throw IllegalStateException(
                "Folder browser $name failed (${error.javaClass.simpleName}): ${error.message.orEmpty()}",
                error,
            )
        }

    private fun execute(uid: Int, caller: String, args: Bundle): Bundle {
        if (args.getString(P.ACTION) == P.CREATE) return create(uid, caller, args)
        val id = requireNotNull(args.getString(P.SESSION))
        val session = sessions[id] ?: return Bundle()
        require(session.uid == uid) { "This browser belongs to another application" }
        when (args.getString(P.ACTION)) {
            P.CLOSE -> {
                val state = session.browser.saveEmbeddedState()
                close(id)
                return Bundle().apply {
                    putInt("version", P.VERSION)
                    putBundle(P.STATE, state)
                }
            }
            P.RESIZE -> {
                val (width, height) = dimensions(args)
                session.browser.resize(width, height)
                session.host.relayout(width, height)
            }
            P.OPEN -> session.browser.open(openIntent(args))
            P.BACK -> session.browser.goBack()
            P.REFRESH -> session.browser.refresh()
            P.CONFIGURE ->
                session.browser.configure(
                    args.getBoolean(P.BREADCRUMBS, true),
                    args.getBoolean(P.SORT_MODIFIED),
                    args.getBoolean(P.SELECT_LINK_DOCUMENTS),
                    actions(args),
                )
            else -> error("Unknown embedded browser command")
        }
        return Bundle().apply { putInt("version", P.VERSION) }
    }

    private fun dimensions(args: Bundle): Pair<Int, Int> {
        val width = args.getInt(P.WIDTH)
        val height = args.getInt(P.HEIGHT)
        require(width in 1..8192 && height in 1..8192) { "Invalid browser dimensions" }
        return width to height
    }

    private fun actions(args: Bundle) =
        SelectionAction.fromWire(
            args.getStringArrayList(F.EXTRA_FILE_ACTION_IDS).orEmpty(),
            args.getStringArrayList(F.EXTRA_FILE_ACTION_LABELS).orEmpty(),
        )

    private fun create(uid: Int, caller: String, args: Bundle): Bundle {
        require(args.getInt("version") == P.VERSION) { "Unsupported embedded browser protocol" }
        check(sessions.size < 16 && sessions.values.count { it.uid == uid } < 4) {
            "Too many embedded browsers"
        }
        val (width, height) = dimensions(args)
        val lifetime =
            requireNotNull(args.getBinder(P.LIFETIME)) {
                "Folder browser requires a client lifetime binder"
            }
        val token =
            requireNotNull(args.getBinder(P.HOST_TOKEN)) {
                "Folder browser requires an attached host surface"
            }
        @Suppress("DEPRECATION")
        val receiver =
            requireNotNull(args.getParcelable<ResultReceiver>(P.RECEIVER)) {
                "Folder browser requires a result receiver"
            }
        val display =
            requireNotNull(
                app.getSystemService(DisplayManager::class.java).getDisplay(args.getInt(P.DISPLAY))
            ) {
                "Folder browser display is unavailable"
            }
        val context = app.createDisplayContext(display)
        @Suppress("DEPRECATION")
        val host =
            setupStep("surface host creation") { SurfaceControlViewHost(context, display, token) }
        val id = UUID.randomUUID().toString()
        val death = IBinder.DeathRecipient { main.post { close(id) } }
        val browser =
            FolderBrowserController(
                context,
                embedded = true,
                attachView = { view, params ->
                    setupStep("view attachment") { host.setView(view, params.width, params.height) }
                },
                detachView = {},
                closeRequested = { main.post { close(id) } },
                locationChanged = { directory, back ->
                    receiver.send(
                        P.NAVIGATION,
                        Bundle().apply {
                            putString(P.DIRECTORY, directory?.toString())
                            putBoolean(P.CAN_GO_BACK, back)
                        },
                    )
                },
            )
        sessions[id] = Session(uid, lifetime, death, host, browser)
        try {
            lifetime.linkToDeath(death, 0)
            setupStep("initialization") { browser.initialize() }
            browser.resize(width, height)
            val request = setupStep("directory resolution") { openIntent(args) }
            request.putExtra(
                FloatingContainerService.EXTRA_SELECTION_RECEIVER,
                object : ResultReceiver(main) {
                    override fun onReceiveResult(code: Int, data: Bundle?) {
                        if (sessions[id] == null) return
                        if (code != F.RESULT_SELECTED) {
                            receiver.send(
                                P.ERROR,
                                Bundle().apply {
                                    putString(
                                        "message",
                                        data?.getString(F.EXTRA_FAILURE_MESSAGE)
                                            ?: "Folder browser closed",
                                    )
                                },
                            )
                            return
                        }
                        val selection = data ?: return
                        selectionWorker.execute {
                            try {
                                val source =
                                    Uri.parse(
                                        requireNotNull(selection.getString(F.EXTRA_RESULT_URI))
                                    )
                                val name = selection.getString(F.EXTRA_RESULT_NAME).orEmpty()
                                val mime =
                                    selection.getString(F.EXTRA_RESULT_MIME)
                                        ?: "application/octet-stream"
                                val size =
                                    selection.getLong(F.EXTRA_RESULT_SIZE, -1).takeIf { it >= 0 }
                                val uri =
                                    FileSelectionRelayProvider.register(
                                        app,
                                        source,
                                        name,
                                        mime,
                                        size,
                                    )
                                app.grantUriPermission(
                                    caller,
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                                val reply =
                                    Bundle(selection).apply {
                                        putString(F.EXTRA_RESULT_URI, uri.toString())
                                        putString(P.SOURCE, source.toString())
                                    }
                                main.post {
                                    if (sessions[id] != null)
                                        receiver.send(F.RESULT_SELECTED, reply)
                                }
                            } catch (error: Exception) {
                                main.post {
                                    if (sessions[id] != null)
                                        receiver.send(
                                            P.ERROR,
                                            Bundle().apply {
                                                putString(
                                                    "message",
                                                    error.message ?: "Cannot open selected file",
                                                )
                                            },
                                        )
                                }
                            }
                        }
                    }
                },
            )
            setupStep("opening") { browser.open(request) }
            val pkg =
                requireNotNull(host.surfacePackage) {
                    "Folder could not create its browser surface"
                }
            sessions.getValue(id).surface = pkg
            return Bundle().apply {
                putBinder(P.SERVER, server)
                putInt("version", P.VERSION)
                putString(P.SESSION, id)
                putParcelable(P.SURFACE, pkg)
            }
        } catch (error: Exception) {
            try {
                close(id)
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun openIntent(args: Bundle): Intent {
        actions(args) // validate before installing caller-owned menu items
        val uri =
            args.getString(P.DIRECTORY)?.let(Uri::parse)?.let {
                StorageDocumentsProvider.resolveReference(app, it)
                    ?: error("Storage directory unavailable")
            } ?: ContainerLinksProvider.ROOT
        return FloatingContainerService.intent(app, uri, args.getString(P.LABEL)).apply {
            putExtra(FloatingContainerService.EXTRA_SELECTION_MODE, true)
            putExtra(P.STATE, args.getBundle(P.STATE))
            putExtra("breadcrumbs", args.getBoolean(P.BREADCRUMBS, true))
            putExtra(P.LOCK_ROOT, args.getBoolean(P.LOCK_ROOT))
            putExtra("sortModified", args.getBoolean(P.SORT_MODIFIED))
            putExtra("selectLinkDocuments", args.getBoolean(P.SELECT_LINK_DOCUMENTS))
            putExtra(F.EXTRA_PREFERRED_VIEW_MODE, args.getString(F.EXTRA_PREFERRED_VIEW_MODE))
            putStringArrayListExtra(
                F.EXTRA_FILE_ACTION_IDS,
                args.getStringArrayList(F.EXTRA_FILE_ACTION_IDS),
            )
            putStringArrayListExtra(
                F.EXTRA_FILE_ACTION_LABELS,
                args.getStringArrayList(F.EXTRA_FILE_ACTION_LABELS),
            )
            // The persistent result receiver is preserved when navigating through open().
        }
    }

    private fun close(id: String) {
        val session = sessions.remove(id) ?: return
        runCatching { session.lifetime.unlinkToDeath(session.death, 0) }
        try {
            session.browser.destroy()
        } finally {
            try {
                session.surface?.release()
            } finally {
                session.host.release()
            }
        }
    }
}
