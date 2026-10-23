package com.infinifolder.viewer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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
}

/** User-triggered app-window captures. No system screen-capture permission or background screenshots. */
object DebugTools : Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val buttons = Collections.synchronizedMap(WeakHashMap<Activity, Button>())
    private val busy = AtomicBoolean(false)
    var dumpGeneration: Long = 0
        private set
    fun install(application: Application) {
        if (installed.compareAndSet(false, true)) {
            DiagnosticLog.init(application)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                DiagnosticLog.record("uncaught", "application failure", error)
                previous?.uncaughtException(thread, error)
            }
            application.registerActivityLifecycleCallbacks(this)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (DebugDumpDestination.get(activity) == null || buttons.containsKey(activity)) return
        val root = activity.window.decorView as? FrameLayout ?: return
        val button = Button(activity).apply {
            text = "Offload debug information"
            textSize = 11f
            alpha = 0.85f
            contentDescription = text
            setOnClickListener { offload(activity) }
        }
        root.addView(button, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (40 * activity.resources.displayMetrics.density).toInt()
        })
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
        val view = activity.window.decorView
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
                PixelCopy.request(activity.window, bitmap, { status ->
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
    override fun onActivityDestroyed(activity: Activity) { buttons.remove(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}

/** Shared viewer lifecycle, independently hosted from the browser application. */
open class ViewerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTools.install(application)
        com.infinifolder.theme.ThemeManager.init(applicationContext)
    }
}
