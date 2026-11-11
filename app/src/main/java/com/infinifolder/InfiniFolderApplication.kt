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
        com.infinifolder.storage.ViewerStorage.factory = { com.infinifolder.storage.StorageRuntime.get(it).provider }
        com.infinifolder.storage.ViewerStorage.readConsistency = object : com.infinifolder.storage.ViewerStorage.ReadConsistency {
            override fun <T> withFreshReads(work: () -> T): T = com.infinifolder.storage.StorageRuntime.get(this@InfiniFolderApplication).archives.withFreshReads(work)
            override fun invalidateReadCaches() { com.infinifolder.storage.StorageRuntime.get(this@InfiniFolderApplication).archives.invalidateReadCaches() }
        }
        com.infinifolder.storage.ViewerStorage.streamingSource = { context, uri ->
            val runtime = com.infinifolder.storage.StorageRuntime.get(context)
            val ref = runtime.archives.refForUri(uri)
            if (ref == null) null else when (val result = runtime.archives.openStreamingSeekable(ref)) {
                is com.infinifolder.model.ProviderResult.Success -> com.infinifolder.storage.BlockDeviceSource(result.value)
                is com.infinifolder.model.ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
                is com.infinifolder.model.ProviderResult.Unsupported -> null
            }
        }
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
