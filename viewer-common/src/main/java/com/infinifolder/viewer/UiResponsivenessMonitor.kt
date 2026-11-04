package com.infinifolder.viewer

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import com.infinifolder.storage.DiagnosticLog
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Startup/foreground evidence for UI stalls; no disk/network IO on the UI thread. */
internal object UiResponsivenessMonitor {
    private val installed = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "InfiniFolderUiMonitor").apply { isDaemon = true }
    }
    private val resumed = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val starting = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    @Volatile private var activityName: String? = null
    @Volatile private var startupPending = true
    @Volatile private var postedAt = 0L
    private var lastStallReport = 0L
    @Volatile private var stallReported = false
    @Volatile var onStall: ((String, Throwable) -> Unit)? = null
    private val violationReports = LinkedHashMap<String, Long>()

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        activityName = "Application startup"
        val policy = Runnable {
            if (Build.VERSION.SDK_INT >= 28) {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyListener(worker) { violation ->
                        val key = violation.javaClass.simpleName + ":" + violation.stackTrace.firstOrNull {
                            it.className.startsWith("com.infinifolder")
                        }
                        val now = SystemClock.uptimeMillis()
                        if (now - (violationReports[key] ?: 0L) >= 5000L) {
                            violationReports[key] = now
                            if (violationReports.size > 64) violationReports.remove(violationReports.keys.first())
                            DiagnosticLog.record("ui.main-thread.io", "activity=$activityName kind=${violation.javaClass.simpleName}", violation)
                        }
                    }.build())
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) policy.run() else main.post(policy)
        worker.scheduleWithFixedDelay({
            if (activityName != null) {
                val now = SystemClock.uptimeMillis()
                if (pending.compareAndSet(false, true)) {
                    postedAt = now
                    main.post {
                        val delay = SystemClock.uptimeMillis() - postedAt
                        pending.set(false)
                        stallReported = false
                        if (delay >= 500L && activityName != null)
                            DiagnosticLog.record("ui.main-thread.recovered", "activity=$activityName queueDelayMs=$delay")
                    }
                } else if (now - postedAt >= 500L &&
                    (now - lastStallReport >= 2000L || (!stallReported && now - postedAt >= 2000L))) {
                    val evidence = Throwable("Main thread delayed at least ${now - postedAt} ms").apply {
                        stackTrace = Looper.getMainLooper().thread.stackTrace
                    }
                    if (now - lastStallReport >= 2000L) {
                        lastStallReport = now
                        DiagnosticLog.record("ui.main-thread.stall", "activity=$activityName queueDelayMs=${now - postedAt}", evidence)
                    }
                    if (!stallReported && now - postedAt >= 2000L) {
                        stallReported = true
                        runCatching { onStall?.invoke(activityName.orEmpty(), evidence) }
                            .onFailure { DiagnosticLog.record("ui.stall.capture-failed", error=it) }
                    }
                }
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
        // The queue can drain between Application.onCreate and Activity construction.
        // Keep watching that gap, including a transparent dispatcher's handoff.
    }

    fun contentReady() { startupPending = false }

    fun resumed(activity: Activity) {
        starting.remove(activity)
        resumed.add(activity)
        activityName = activity.javaClass.simpleName
    }

    fun starting(activity: Activity) { starting.add(activity); activityName = activity.javaClass.simpleName }

    fun paused(activity: Activity) {
        resumed.remove(activity)
        activityName = resumed.lastOrNull()?.javaClass?.simpleName ?: starting.lastOrNull()?.javaClass?.simpleName
            ?: if (startupPending) "Application startup" else null
    }
    fun stopped(activity: Activity) { starting.remove(activity); paused(activity) }
}
