package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class SyntaxLanguageSelectionTest {
    @Test fun extensionlessJsonKeepsHighlightingAfterReparseAddsNewlines() {
        val name = "save_without_extension"
        assertEquals("json", syntaxLanguage("auto", name, "text/plain", "{\"value\":1}", "json"))
        assertEquals("json", syntaxLanguage("auto", name, "text/plain", "{", "json"))
    }
    @Test fun explicitLanguageAndRecognizedFileTypeRemainAuthoritative() {
        assertEquals("plain", syntaxLanguage("plain", "save", "text/plain", "{", "json"))
        assertEquals("yaml", syntaxLanguage("yaml", "save", "text/plain", "{", "json"))
        assertEquals("kotlin", syntaxLanguage("auto", "source.kt", "text/plain", "", "json"))
    }
    @Test fun missingOrUnsupportedHintsRemainPlain() {
        assertEquals("plain", syntaxLanguage("auto", "notes", "text/plain", "hello", null))
        assertEquals("plain", syntaxLanguage("auto", "notes", "text/plain", "hello", "unsupported"))
    }
}
