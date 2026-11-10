package com.infinifolder.preview

import com.infinifolder.mime.MimeRegistry
import java.util.Locale

/** Display names for audio formats sharing a MIME, without changing their associations. */
object AudioIconFormats {
    fun isAudio(mime:String):Boolean = mime.startsWith("audio/") || mime in setOf(
        "application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp")

    private val extensionAssociations by lazy {
        MimeRegistry.associations().entries.associate { (extension, associatedMime) ->
            extension.removePrefix(".").lowercase(Locale.ROOT) to MimeRegistry.normalize(associatedMime)
        }
    }

    private val extensionsByMime by lazy {
        extensionAssociations.entries.groupBy({it.value},{it.key})
            .mapValues {(_,extensions)->extensions.sortedWith(compareBy<String> {it.length}.thenBy {it})}
    }

    /**
     * Resolve the longest configured suffix that agrees with the effective MIME.
     * The provider MIME can be an alias of the registry MIME, and a compound audio
     * suffix must not collapse to its final component (for example .cdda-be).
     */
    fun extension(name:String,mime:String):String? {
        val normalizedMime = MimeRegistry.normalize(mime)
        val lowerName = name.lowercase(Locale.ROOT)
        return extensionAssociations.keys.asSequence()
            .filter { lowerName.endsWith(".$it") }
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .firstOrNull { extensionAssociations[it] == normalizedMime }
    }

    fun label(name:String,mime:String):String {
        val normalizedMime = MimeRegistry.normalize(mime)
        val format=extension(name,mime) ?: when(normalizedMime) {
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/wav" -> "wav"
            else -> extensionsByMime[normalizedMime]?.firstOrNull()
        }
        return (format ?: mime.substringAfter('/').removePrefix("x-").removePrefix("vnd."))
            .uppercase(Locale.ROOT)
    }
}
