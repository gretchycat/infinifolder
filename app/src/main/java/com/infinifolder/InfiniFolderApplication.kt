package com.infinifolder

import android.app.Application
import com.infinifolder.viewer.DebugTools

/** Install crash capture before Activity constructors and automatic transfer restoration run. */
class InfiniFolderApplication : Application() {
    private var normalInitialized = false
    override fun onCreate() {
        super.onCreate()
        // The debug-only startup reporter must survive failures in the normal
        // application graph. Its process has no content providers or library setup.
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (processName == "$packageName:startup_report") return
        val started = android.os.SystemClock.uptimeMillis()
        try {
            DebugTools.install(this)
            val recovering = DebugTools.startupError(this) != null
            if (!recovering) initializeNormal()
            com.infinifolder.storage.DiagnosticLog.record("startup.application.ready",
                "elapsedMs=${android.os.SystemClock.uptimeMillis() - started} recovery=$recovering")
        } catch (error: Exception) {
            // Application.onCreate runs before the launcher can display recovery.
            // Preserve the failure, then let the existing recovery screen open.
            DebugTools.saveStartupError(this, Thread.currentThread(), error)
        } catch (error: LinkageError) {
            DebugTools.saveStartupError(this, Thread.currentThread(), error)
        }
    }

    fun initializeNormal() {
        if (normalInitialized) return
        com.infinifolder.ops.AppOperations.initialize(this)
        Thread({ runCatching { CompanionViewerDefaults.initialize(applicationContext) } },
            "CompanionViewerDefaults").apply { isDaemon = true; start() }
        TermuxAvailability.watch(this)
        normalInitialized = true
    }
}
