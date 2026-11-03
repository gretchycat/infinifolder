package com.infinifolder.viewer

import android.content.Context
import android.content.Intent

/** Shared picker contract; local preferences never alter Android's defaults. */
object ExternalAppChooser {
    internal const val TARGET = "external_target"
    internal const val TITLE = "external_title"
    internal const val SOURCE = "external_source_key"
    internal const val NAME = "external_name"
    const val FORCE_CHOOSER = "external_force_chooser"
    internal const val SELECT_EDITOR = "select_default_editor"
    fun create(context: Context, target: Intent, title: CharSequence): Intent =
        create(context, target, title, target.data?.toString()?.let(ExternalAppDefaults::uriKey), title.toString(), false)
    fun create(context: Context, target: Intent, title: CharSequence, sourceKey: String?, name: String,
        forceChooser: Boolean): Intent = Intent(context, ExternalAppPickerActivity::class.java)
        .putExtra(TARGET, Intent(target)).putExtra(TITLE, title).putExtra(SOURCE, sourceKey)
        .putExtra(NAME, name).putExtra(FORCE_CHOOSER, forceChooser)
        .apply {
            clipData = target.clipData
            addFlags(target.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
}
