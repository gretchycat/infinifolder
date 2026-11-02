package com.infinifolder.preview

import com.infinifolder.mime.MimeRegistry
import java.util.Locale

/** Display names for audio formats sharing a MIME, without changing their associations. */
object AudioIconFormats {
    fun isAudio(mime:String):Boolean = mime.startsWith("audio/") || mime in setOf(
        "application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp")

    private val extensionsByMime by lazy {
        MimeRegistry.associations().entries.groupBy({it.value},{it.key})
            .mapValues {(_,extensions)->extensions.sortedWith(compareBy<String> {it.length}.thenBy {it})}
    }

    fun extension(name:String,mime:String):String? = name.substringAfterLast('.',"")
        .lowercase(Locale.ROOT).takeIf {it.isNotBlank() && MimeRegistry.guess(name)==mime}

    fun label(name:String,mime:String):String {
        val format=extension(name,mime) ?: when(mime) {
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/wav" -> "wav"
            else -> extensionsByMime[mime]?.firstOrNull()
        }
        return (format ?: mime.substringAfter('/').removePrefix("x-").removePrefix("vnd."))
            .uppercase(Locale.ROOT)
    }
}
