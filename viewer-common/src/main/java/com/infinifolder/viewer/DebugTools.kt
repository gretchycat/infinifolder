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

/** User-triggered app-window captures. No system screen-capture permission or background screenshots. */
object DebugTools : Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val buttons = Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val busy = AtomicBoolean(false)
    private val crashExporter = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "InfiniFolderCrashExport").apply { isDaemon = true }
    }
    var dumpGeneration: Long = 0
        private set
    fun install(application: Application) {
        if (installed.compareAndSet(false, true)) {
            DiagnosticLog.init(application)
            UiResponsivenessMonitor.install()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                DiagnosticLog.record("uncaught", "application failure", error)
                if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    runCatching { crashExporter.submit { exportPendingCrash(application) }.get(2500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                }
                previous?.uncaughtException(thread, error)
            }
            application.registerActivityLifecycleCallbacks(this)
            if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                crashExporter.execute { exportPendingCrash(application) }
            }
        }
    }

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
    override fun onActivityDestroyed(activity: Activity) { buttons.remove(activity); UiResponsivenessMonitor.paused(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) { UiResponsivenessMonitor.paused(activity) }
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}

/** Shared viewer lifecycle, independently hosted from the browser application. */
open class ViewerActivity : Activity() {
    private var backNavigation: java.io.Closeable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTools.install(application)
        com.infinifolder.theme.ThemeManager.init(applicationContext)
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
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Pop this viewer; the browser (or independent host) remains underneath.
        // Editor overrides still handle unsaved changes before reaching here.
        finish()
    }

    override fun onDestroy() {
        backNavigation?.close()
        backNavigation = null
        super.onDestroy()
    }
}
