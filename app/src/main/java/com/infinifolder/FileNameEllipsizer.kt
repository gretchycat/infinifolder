package com.infinifolder

/** Source-compatible adapter; filename fitting/rendering lives in InfiniSelect. */
object FileNameEllipsizer {
    const val ELLIPSIS = io.github.gretchycat.infiniselect.browser.FileNameEllipsizer.ELLIPSIS

    fun ellipsize(
        fullName: String,
        isDirectory: Boolean,
        maxWidthPx: Float,
        measureText: (String) -> Float,
    ): String =
        io.github.gretchycat.infiniselect.browser.FileNameEllipsizer.ellipsizeWithExtension(
            fullName,
            isDirectory,
            maxWidthPx,
            measureText,
            com.infinifolder.archive.ArchiveFormats::archiveExtension,
        )

    internal fun splitStemAndExtension(
        coreName: String,
        isDirectory: Boolean,
    ): Pair<String, String> =
        io.github.gretchycat.infiniselect.browser.FileNameEllipsizer.splitStemAndExtension(
            coreName,
            isDirectory,
            com.infinifolder.archive.ArchiveFormats::archiveExtension,
        )
}

typealias SmartFileNameTextView = io.github.gretchycat.infiniselect.browser.SmartFileNameTextView
