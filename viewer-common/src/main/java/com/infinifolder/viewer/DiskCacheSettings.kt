package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.infinifolder.storage.DiskCache

object DiskCacheSettings {
    fun show(activity: Activity) {
        val cache = DiskCache.get(activity)
        val builder = CacheDialogs.builder(activity)
        val context = builder.context
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        fun field(label: String, value: Long): EditText {
            body.addView(TextView(context).apply { text = label })
            return EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString()); body.addView(this)
            }
        }
        val total = field("Total cache size (MB)", cache.budgetBytes / DiskCache.MIB)
        val individual = field("Individual file limit (MB; 0 = no additional limit)", cache.individualLimitBytes / DiskCache.MIB)
        val expiry = field("Expire unused cache files after hours (0 = never)", cache.idleExpiryMillis / 3600000)
        val usage = cache.usage()
        val usageText = "${usage.first / DiskCache.MIB} MB cached; ${usage.second / DiskCache.MIB} MB reserved/in use.\n\n"
        val dialog = builder.setTitle("Disk cache")
            .setMessage(usageText + "Least recently used files are removed to make room. Active files are protected. Files that cannot fit can be copied to a storage folder instead. Reducing the budget keeps active files until released.")
            .setView(android.widget.ScrollView(context).apply { addView(body) }).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val budget = total.text.toString().toLongOrNull()
                val limit = individual.text.toString().toLongOrNull()
                val hours = expiry.text.toString().toLongOrNull()
                if (budget == null || budget !in 1..1048576) { total.error = "Enter 1–1048576 MB"; return@setOnClickListener }
                if (limit == null || limit !in 0..1048576) { individual.error = "Enter 0–1048576 MB"; return@setOnClickListener }
                if (hours == null || hours !in 0..87600) { expiry.error = "Enter 0–87600 hours"; return@setOnClickListener }
                dialog.dismiss()
                Thread {
                    cache.configure(budget * DiskCache.MIB, limit * DiskCache.MIB, hours * 3600000)
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed)
                            android.widget.Toast.makeText(activity, "Cache limits saved. Open the file again to retry.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
        dialog.show()
        CacheDialogs.style(dialog)
    }
}
