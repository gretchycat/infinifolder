package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import com.infinifolder.storage.DiskCache

object CacheFailureUi {
    const val COPY_SOURCE_EXTRA = "com.infinifolder.viewer.COPY_CACHE_SOURCE"
    /** Call on the UI thread. Returns false for errors unrelated to the cache policy. */
    fun offerCopy(activity: Activity, error: Throwable, source: Uri): Boolean {
        val capacity = DiskCache.capacityError(error) ?: return false
        if (activity.isFinishing || activity.isDestroyed) return true
        val dialog = CacheDialogs.builder(activity).setTitle("Cache limit reached")
            .setMessage(capacity.message + "\n\nUnused cached files are evicted first. Files still in use are protected.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Raise cache limits…") { _, _ -> DiskCacheSettings.show(activity) }
            .setPositiveButton("Copy to folder…") { _, _ ->
                val handler = runCatching {
                    activity.packageManager.getApplicationInfo(activity.packageName, android.content.pm.PackageManager.GET_META_DATA)
                        .metaData?.getString("com.infinifolder.CACHE_COPY_HANDLER")
                }.getOrNull()
                if (handler != null) activity.startActivity(Intent().setClassName(activity.packageName, handler)
                    .putExtra(COPY_SOURCE_EXTRA, source.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                else activity.startActivity(Intent(activity, CacheCopyActivity::class.java).setData(source))
            }.create()
        dialog.show()
        CacheDialogs.style(dialog)
        return true
    }
}
