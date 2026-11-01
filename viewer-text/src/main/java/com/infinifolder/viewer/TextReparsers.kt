package com.infinifolder.viewer

import io.github.gretchycat.infiniscribe.reparse.*

/** The host chooses which optional Scribe backends to ship. */
internal val TextReparsers = TextReparserRegistry(listOf(
    JsonTextReparser, YamlTextReparser, XmlTextReparser, TomlTextReparser))
