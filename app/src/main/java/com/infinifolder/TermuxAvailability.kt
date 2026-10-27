package com.infinifolder

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.infinifolder.storage.DiagnosticLog

/** Keep Android's external file handler in sync with the Termux package. */
object TermuxAvailability {
    private const val PACKAGE = "com.termux"
    fun installed(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun refresh(context: Context) {
        val manager = context.packageManager
        val component = ComponentName(context, "${context.packageName}.TermuxFileHandler")
        val available = installed(context) && TermuxAssociations(context).list().any {
            TermuxEditorAvailability.state(context, it.command) == TermuxEditorAvailability.State.AVAILABLE
        }
        val desired = if (available) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (manager.getComponentEnabledSetting(component) != desired) {
            manager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            DiagnosticLog.record("termux.handler.availability", "enabled=$available")
        }
    }
    fun watch(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == PACKAGE) TermuxEditorAvailability.invalidate(context.applicationContext)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Thread({ refresh(context.applicationContext) }, "TermuxAvailability").start()
    }
}

/** Full removals can be delivered even when the browser is not running. */
class TermuxPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.schemeSpecificPart == "com.termux") TermuxEditorAvailability.invalidate(context.applicationContext)
    }
}
