package com.infinifolder.viewer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Implemented by screens whose listings should update after a dump, including partial exports. */
interface DebugDumpObserver {
    fun onDebugDumpFinished()
    fun debugDumpState(): String? = null
    fun debugCaptureWindow(): android.view.Window? = null
}

/** A routing activity is not evidence that the destination screen started successfully. */
interface StartupDispatcher

/** User-triggered app-window captures. No system screen-capture permission or background screenshots. */
object DebugTools : Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val buttons = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val busy = AtomicBoolean(false)
    private val startupReady = AtomicBoolean(false)
    private const val STARTUP_RECOVERY_PREFS = "startup_recovery"
    private const val STARTUP_ERROR = "pending_error"

    fun startupError(context: android.content.Context): String? =
        context.getSharedPreferences(STARTUP_RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(STARTUP_ERROR, null)

    fun saveStartupError(context: android.content.Context, thread: Thread, error: Throwable): String {
        val report = DiagnosticLog.sanitize("Thread: ${thread.name}\n" + android.util.Log.getStackTraceString(error)).take(64 * 1024)
        context.getSharedPreferences(STARTUP_RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(STARTUP_ERROR, report).commit()
        return report
    }

    fun prepareStartupRetry() { startupReady.set(false) }

    fun clearStartupError(context: android.content.Context) {
        context.getSharedPreferences(STARTUP_RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().remove(STARTUP_ERROR).apply()
    }
    private val crashExporter = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "InfiniFolderCrashExport").apply { isDaemon = true }
    }
    private val stallExporter = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "InfiniFolderStallExport").apply { isDaemon = true }
    }
    private val stallExportPending = AtomicBoolean(false)
    var dumpGeneration: Long = 0
        private set
    fun install(application: Application) {
        if (installed.compareAndSet(false, true)) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Save recovery evidence before touching optional logging/runtime services.
                if (!startupReady.get()) runCatching { saveStartupError(application, thread, error) }
                runCatching { DiagnosticLog.record("uncaught", "application failure", error) }
                if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    runCatching { crashExporter.submit { exportPendingCrash(application) }.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                }
                previous?.uncaughtException(thread, error)
            }
            DiagnosticLog.providerResolver = { com.infinifolder.storage.StorageRuntime.get(it).provider }
            DiagnosticLog.init(application)
            UiResponsivenessMonitor.install()
            if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                UiResponsivenessMonitor.onStall = { activity, evidence ->
                    if (stallExportPending.compareAndSet(false, true)) {
                        try {
                            // Capture thread state before queuing storage work: the UI may
                            // have recovered or Android may terminate the process later.
                            val trace = buildString {
                                append("Activity: $activity\n"); append(android.util.Log.getStackTraceString(evidence))
                                Thread.getAllStackTraces().entries.sortedBy { it.key.name }.take(64).forEach { (thread, stack) ->
                                    append("\nThread: ${thread.name} state=${thread.state}\n")
                                    stack.take(32).forEach { append("    at $it\n") }
                                }
                            }
                            val report = DiagnosticLog.sanitize(trace).take(128 * 1024)
                            stallExporter.execute {
                                try {
                                    val pending = java.io.File(application.filesDir, "diagnostics/ui-stall-pending.log")
                                    pending.parentFile?.mkdirs()
                                    pending.writeText(report)
                                    exportPendingStall(application)
                                } catch (error: Exception) {
                                    DiagnosticLog.record("ui.stall.capture-failed", error=error)
                                } finally { stallExportPending.set(false) }
                            }
                        } catch (error: Exception) {
                            stallExportPending.set(false)
                            DiagnosticLog.record("ui.stall.capture-failed", error=error)
                        }
                    }
                }
            }
            application.registerActivityLifecycleCallbacks(this)
            if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                crashExporter.execute { exportPendingCrash(application) }
                stallExporter.execute { exportPendingStall(application) }
            }
        }
    }

    /** Log-only automatic export to the already configured folder; no background screenshots. */
    private fun exportPendingStall(application: Application) {
        runCatching {
            val pending = java.io.File(application.filesDir, "diagnostics/ui-stall-pending.log")
            if (!pending.isFile) return
            val destination = DebugDumpDestination.get(application) ?: return
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now())
            val report = pending.readText().take(128 * 1024) + "\n--- Session ---\n" + DiagnosticLog.snapshot(application)
            when (val result = DebugDumpWriter.write(StorageRuntime.get(application).provider, destination,
                listOf(DebugArtifact("infinifolder-stall-$stamp.log", "text/plain", report.toByteArray())))) {
                is ProviderResult.Success -> pending.delete()
                is ProviderResult.Failure -> DiagnosticLog.record("ui.stall.export-failed", result.message, result.cause)
                else -> DiagnosticLog.record("ui.stall.export-failed", "Debug destination does not support log export")
            }
        }.onFailure { DiagnosticLog.record("ui.stall.export-failed", error=it) }
    }

    fun activityStarting(activity: Activity) { UiResponsivenessMonitor.starting(activity) }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) { activityStarting(activity) }

    /** Export fatal traces without requiring an Activity to stay open. Retry on the next launch. */
    private fun exportPendingCrash(application: Application) {
        runCatching {
            val destination = DebugDumpDestination.get(application) ?: return
            val log = DiagnosticLog.snapshot(application)
            val marker = log.lineSequence().lastOrNull { it.contains(" uncaught ") } ?: return
            val preferences = application.getSharedPreferences("diagnostics", Application.MODE_PRIVATE)
            if (preferences.getString("exported_crash_marker", null) == marker) return
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now())
            val artifacts = listOf(DebugArtifact("infinifolder-crash-$stamp.log", "text/plain", log.toByteArray()))
            when (DebugDumpWriter.write(StorageRuntime.get(application).provider, destination, artifacts)) {
                is ProviderResult.Success -> preferences.edit().putString("exported_crash_marker", marker).commit()
                else -> Unit // Preserve the private trace for another export attempt.
            }
        }
    }
    override fun onActivityResumed(activity: Activity) {
        UiResponsivenessMonitor.resumed(activity)
        if (activity !is StartupDispatcher) {
            // Run after the complete onResume chain, including subclass work.
            Handler(Looper.getMainLooper()).post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    startupReady.set(true)
                    UiResponsivenessMonitor.contentReady()
                }
            }
        }
    }

    /** Explicit toolbar attachment keeps the control out of document and file content. */
    fun attachToolbarButton(activity: Activity, toolbar: LinearLayout) {
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val density = activity.resources.displayMetrics.density
        val button = ImageButton(activity).apply {
            setImageResource(com.infinifolder.viewer.common.R.drawable.ic_debug_bug)
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.infinifolder.theme.ThemeManager.activeTheme.textPrimary)
            contentDescription = "Offload debug information"
            tooltipText = contentDescription
            val backgroundAttr = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)
            setBackgroundResource(backgroundAttr.resourceId)
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnClickListener { offload(activity) }
        }
        toolbar.addView(button, LinearLayout.LayoutParams((36 * density).toInt(), (44 * density).toInt()))
        buttons[activity] = button
    }
    fun offload(activity: Activity) {
        val parent = DebugDumpDestination.get(activity)
        if (parent == null) {
            Toast.makeText(activity, "Choose the debug dump folder first", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(activity, DiagnosticsActivity::class.java))
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val button = buttons[activity]
        button?.visibility = View.INVISIBLE
        val observer = activity as? DebugDumpObserver
        observer?.debugDumpState()?.let { DiagnosticLog.record("debug.ui.state", it) }
        val captureWindow = observer?.debugCaptureWindow() ?: activity.window
        val view = captureWindow.decorView
        view.post {
            val bitmap = runCatching { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }.getOrNull()
            fun write(capture: Bitmap?, method: String) {
                button?.visibility = View.VISIBLE
                Thread {
                    val result = runCatching {
                        val provider = StorageRuntime.get(activity).provider
                        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now())
                        val prefix = "infinifolder-debug-$stamp"
                        DiagnosticLog.record("debug.dump", "activity=${activity.javaClass.simpleName} capture=$method")
                        val artifacts = mutableListOf<DebugArtifact>()
                        if (capture != null) {
                            val bytes = ByteArrayOutputStream()
                            check(capture.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                            artifacts += DebugArtifact("$prefix.png", "image/png", bytes.toByteArray())
                        }
                        val info = JSONObject().put("time", Instant.now().toString()).put("package", activity.packageName)
                            .put("activity", activity.javaClass.name).put("sdk", android.os.Build.VERSION.SDK_INT)
                            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            .put("version", activity.packageManager.getPackageInfo(activity.packageName, 0).versionName)
                            .put("appUpdatedAt", Instant.ofEpochMilli(activity.packageManager.getPackageInfo(activity.packageName, 0).lastUpdateTime).toString())
                            .put("capture", method).put("width", capture?.width ?: 0).put("height", capture?.height ?: 0)
                        val destinationFs = (provider.fileSystemInfo(parent) as? ProviderResult.Success)?.value
                        info.put("dumpDestinationFilesystem", JSONObject()
                            .put("type", destinationFs?.displayName ?: "Unknown")
                            .put("maxFileSizeBytes", destinationFs?.maxFileSizeBytes ?: JSONObject.NULL)
                            .put("availableBytes", destinationFs?.availableBytes ?: JSONObject.NULL)
                            .put("readOnly", destinationFs?.readOnly ?: JSONObject.NULL)
                            .put("filenameRules", destinationFs?.nameDescription ?: "Not reported")
                            .put("evidence", destinationFs?.evidence ?: "Not reported by provider"))
                        artifacts += DebugArtifact("$prefix.json", "application/json", info.toString(2).toByteArray())
                        artifacts += DebugArtifact("$prefix.log", "text/plain", DiagnosticLog.snapshot(activity).toByteArray())
                        when (val exported = DebugDumpWriter.write(provider, parent, artifacts)) {
                            is ProviderResult.Success -> Unit
                            is ProviderResult.Failure -> error(exported.message)
                            is ProviderResult.Unsupported -> error("Debug dump destination is unsupported")
                        }
                    }
                    capture?.recycle()
                    busy.set(false)
                    result.onFailure { DiagnosticLog.record("debug.dump.failed", error = it) }
                    activity.runOnUiThread {
                        dumpGeneration++
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            (activity as? DebugDumpObserver)?.onDebugDumpFinished()
                        }
                        Toast.makeText(activity, if (result.isSuccess) "Debug information saved to the debug dump folder" else
                            "Debug dump failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            if (bitmap == null) { write(null, "unavailable"); return@post }
            runCatching {
                PixelCopy.request(captureWindow, bitmap, { status ->
                    if (status == PixelCopy.SUCCESS) write(bitmap, "PixelCopy") else {
                        val drawn = runCatching { view.draw(Canvas(bitmap)); bitmap }.getOrNull()
                        if (drawn == null) bitmap.recycle()
                        write(drawn, "View.draw (PixelCopy result $status)")
                    }
                }, Handler(Looper.getMainLooper()))
            }.onFailure {
                val drawn = runCatching { view.draw(Canvas(bitmap)); bitmap }.getOrNull()
                if (drawn == null) bitmap.recycle()
                write(drawn, "View.draw fallback")
            }
        }
    }
    override fun onActivityDestroyed(activity: Activity) { buttons.remove(activity); UiResponsivenessMonitor.stopped(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) { UiResponsivenessMonitor.paused(activity) }
    override fun onActivityStopped(activity: Activity) { UiResponsivenessMonitor.stopped(activity) }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}

/** Shared viewer lifecycle, independently hosted from the browser application. */
open class ViewerActivity : Activity() {
    private var backNavigation: java.io.Closeable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTools.install(application)
        DebugTools.activityStarting(this)
        com.infinifolder.theme.ThemeManager.init(applicationContext)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        // Some hosts replace their entire view tree when applying a theme. Rebind
        // after that replacement so this activity remains the active Back target.
        if (backNavigation != null) {
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.back.rebind", "activity=${javaClass.simpleName} reason=content-changed"
            )
            backNavigation?.close()
            backNavigation = ActivityBackNavigation.register(this) { onBackPressed() }
        }
    }

    override fun onResume() {
        super.onResume()
        backNavigation?.close()
        backNavigation = ActivityBackNavigation.register(this) { onBackPressed() }
    }

    override fun onPause() {
        backNavigation?.close()
        backNavigation = null
        super.onPause()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (event.isCanceled) {
                    com.infinifolder.storage.DiagnosticLog.record(
                        "navigation.back.dispatch", "activity=${javaClass.simpleName} source=key state=canceled"
                    )
                } else {
                    com.infinifolder.storage.DiagnosticLog.record(
                        "navigation.back.dispatch", "activity=${javaClass.simpleName} source=key state=begin"
                    )
                    val startedAt = android.os.SystemClock.uptimeMillis()
                    try {
                        onBackPressed()
                    } finally {
                        com.infinifolder.storage.DiagnosticLog.record(
                            "navigation.back.dispatch",
                            "activity=${javaClass.simpleName} source=key state=returned elapsedMs=${android.os.SystemClock.uptimeMillis() - startedAt} finishing=$isFinishing"
                        )
                    }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    open fun returnToFileManager() {
        com.infinifolder.storage.DiagnosticLog.record(
            "navigation.viewer.return", "activity=${javaClass.simpleName} state=begin finishing=$isFinishing"
        )
        // Built-in viewers are launched by MainActivity in the same task. Finishing
        // the viewer returns to that existing browser instance and preserves its
        // back stack. Starting MainActivity again with CLEAR_TOP/SINGLE_TOP can
        // deliver a synthetic ACTION_MAIN intent to the existing activity and
        // leaves Android's task/back dispatcher to reconcile two competing routes.
        if (!isTaskRoot) {
            finish()
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.viewer.return", "activity=${javaClass.simpleName} state=finished-to-caller finishing=$isFinishing"
            )
            return
        }

        // Keep a recovery route for viewers launched as a task root (for example,
        // from a standalone host that did not provide the file manager activity).
        val intent = android.content.Intent().apply {
            setClassName(this@ViewerActivity, "com.infinifolder.MainActivity")
            action = android.content.Intent.ACTION_MAIN
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launched = runCatching {
            startActivity(intent)
            // Remove this viewer from the task. If the browser already existed,
            // CLEAR_TOP has brought it forward; otherwise it is now the task root.
            finish()
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.viewer.return", "activity=${javaClass.simpleName} state=started finishing=$isFinishing"
            )
            true
        }.onFailure { error ->
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.viewer.return", "activity=${javaClass.simpleName} state=failed", error
            )
        }.getOrDefault(false)

        if (!launched) {
            finish()
            com.infinifolder.storage.DiagnosticLog.record(
                "navigation.viewer.return", "activity=${javaClass.simpleName} state=finished-fallback finishing=$isFinishing"
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Every built-in viewer returns to the file manager through the same task route.
        com.infinifolder.storage.DiagnosticLog.record(
            "navigation.viewer.back", "activity=${javaClass.simpleName} dirtyHandler=base"
        )
        returnToFileManager()
    }

    override fun onDestroy() {
        backNavigation?.close()
        backNavigation = null
        super.onDestroy()
    }
}
