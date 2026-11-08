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
    internal const val INTERNAL_TARGET = "internal_viewer_target"
    internal const val STAGE_EXTERNAL = "stage_external_target"
    internal const val EXTERNAL_COMPONENT = "selected_external_component"
    internal const val EXTERNAL_ACTION = "selected_external_action"

    /** Choose before staging: internal viewers open the original resource directly. */
    fun forResource(context: Context, entry: com.infinifolder.model.ResourceEntry, parent: android.net.Uri? = null,
        forceChooser: Boolean = true): Intent {
        val target = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri,
            com.infinifolder.mime.MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType))
        return create(context, target, "Open ${entry.metadata.name}",
            ExternalAppDefaults.sourceKey(entry.ref), entry.metadata.name, forceChooser)
            .putExtra(INTERNAL_TARGET, ViewerLaunch.intentForResource(context, entry, parent))
            .putExtra(STAGE_EXTERNAL, true)
    }
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
