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
        try {
            DebugTools.install(this)
            if (DebugTools.startupError(this) == null) initializeNormal()
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
        TermuxAvailability.watch(this)
        normalInitialized = true
    }
}
