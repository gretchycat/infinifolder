package com.infinifolder

import android.app.Application
import com.infinifolder.viewer.DebugTools

/** Install crash capture before Activity constructors and automatic transfer restoration run. */
class InfiniFolderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugTools.install(this)
        com.infinifolder.ops.AppOperations.initialize(this)
    }
}
