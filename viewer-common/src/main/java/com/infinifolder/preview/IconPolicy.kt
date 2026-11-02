package com.infinifolder.preview

/** Representation order only. Detection, rendering and browsing remain separate services. */
object IconPolicy {
    enum class Step { THUMBNAIL, MIME_OVERRIDE, THEME_MIME, ARCHIVE_FOLDER, THEME_CATEGORY, GENERIC }
    fun steps(record: IconRegistry.MimeRecord, genericArchive: Boolean = false): List<Step> = buildList {
        if (record.isImage && !record.overrideImageIcon) add(Step.THUMBNAIL)
        add(Step.MIME_OVERRIDE); add(Step.THEME_MIME)
        if (genericArchive) add(Step.ARCHIVE_FOLDER)
        add(Step.THEME_CATEGORY); add(Step.GENERIC)
    }
}
