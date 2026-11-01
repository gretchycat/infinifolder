package com.infinifolder.viewer

import io.github.gretchycat.infiniscribe.SyntaxHighlighting

/** A document processor supplies a fallback for sources without a useful filename or MIME type. */
internal fun syntaxLanguage(choice: String, fileName: String, mime: String, firstLine: String,
    documentLanguage: String?): String {
    if (choice != "auto") return choice
    val detected = SyntaxHighlighting.detect(fileName, mime, firstLine)
    return if (detected != "plain") detected else
        documentLanguage?.takeIf { it in SyntaxHighlighting.languages } ?: "plain"
}
