package com.infinifolder.audio.cast

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.decoder.UniversalAudioDecoder
import com.infinifolder.viewer.MediaSourceAdapter
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Inspects audio streams and transcodes non-standard formats (trackers, DSD/DFF, CDDA,
 * WavPack, Monkey's Audio, Chiptunes, etc.) into standard 16-bit stereo PCM RIFF WAVE
 * on the fly for universal compatibility with Google Cast, DLNA, and smart TV renderers.
 */
object AudioTranscoder {

    private val DIRECT_CAST_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "wav"
    )

    private val DIRECT_CAST_MIMES = setOf(
        "audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac",
        "audio/flac", "audio/ogg", "audio/wav", "audio/x-wav"
    )

    // Cache recently transcoded tracks in memory (keyed by URI string) to avoid re-decoding across HTTP Range requests
    private val transcodeCache = ConcurrentHashMap<String, ByteArray>()
    private var lastCachedUri: String? = null

    fun isDirectlyPlayableByCast(uri: Uri, name: String?): Boolean {
        val ext = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in DIRECT_CAST_EXTENSIONS
    }

    fun getMimeType(uri: Uri, name: String?): String {
        val ext = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/wav" // All incompatible/transcoded formats stream as standard PCM WAV
        }
    }

    /**
     * Transcodes any incompatible audio format to a 16-bit 44.1kHz / 48kHz stereo WAV byte array.
     * Caches the result in memory for efficient HTTP streaming.
     */
    fun getOrTranscodeToWav(context: Context, uri: Uri, name: String?): ByteArray? {
        val uriKey = uri.toString()
        transcodeCache[uriKey]?.let { return it }

        val ext = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        return try {
            val bytes = MediaSourceAdapter.openStream(context, uri).use { it.readBytes() }
            if (bytes.isEmpty()) return null

            val pcm = UniversalAudioDecoder.decode(bytes, ext) ?: return null
            val stereoPcm = pcm.toStereo()
            val wavBytes = stereoPcm.toWavByteArray()

            // Keep cache bounded
            if (transcodeCache.size > 3) {
                transcodeCache.clear()
            }
            transcodeCache[uriKey] = wavBytes
            lastCachedUri = uriKey
            wavBytes
        } catch (e: Exception) {
            android.util.Log.w("AudioTranscoder", "Transcode failed for $name: ${e.message}", e)
            null
        }
    }

    fun clearCache() {
        transcodeCache.clear()
        lastCachedUri = null
    }
}
