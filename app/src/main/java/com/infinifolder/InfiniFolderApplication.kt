package com.infinifolder

import android.app.Application
import com.infinifolder.viewer.DebugTools

/** Install crash capture before Activity constructors and automatic transfer restoration run. */
class InfiniFolderApplication : Application() {
    private var normalInitialized = false
    override fun onCreate() {
        super.onCreate()
        DebugTools.install(this)
        if (DebugTools.startupError(this) == null) initializeNormal()
    }

    fun initializeNormal() {
        if (normalInitialized) return
        com.infinifolder.ops.AppOperations.initialize(this)
        TermuxAvailability.watch(this)
        normalInitialized = true
    }
}
