package com.infinifolder.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** A native app chooser that cannot route an external handoff back to this host. */
object ExternalAppChooser {
    fun create(context: Context, target: Intent, title: CharSequence): Intent {
        // Enumerate our own package, including aliases. This does not depend on
        // Android package visibility exposing every other installed handler.
        val ownActivities = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).activities.orEmpty()
        val excluded = ownActivities.map { ComponentName(it.packageName, it.name) }.distinct().toTypedArray()
        return Intent.createChooser(target, title)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded)
    }
}
