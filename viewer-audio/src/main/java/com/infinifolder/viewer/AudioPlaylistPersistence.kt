package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class SavedAudioPlaylist(
    val tracks: List<AudioTrack>,
    val originalTracks: List<AudioTrack>,
    val currentIndex: Int,
    val positionMs: Int,
    val hasManualOrder: Boolean
)

/** Durable app-private snapshot of InfiniMedia's active queue. Bitmaps are regenerated from sources. */
internal class AudioPlaylistStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "working-audio-playlist.json"))

    @Synchronized
    fun load(): SavedAudioPlaylist? = runCatching {
        val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        if (root.optInt("version") != VERSION) return null
        val tracks = readTracks(root.optJSONArray("tracks"))
        if (tracks.isEmpty()) return null
        val original = readTracks(root.optJSONArray("originalTracks")).ifEmpty { tracks }
        SavedAudioPlaylist(
            tracks = tracks,
            originalTracks = original,
            currentIndex = root.optInt("currentIndex", 0).coerceIn(0, tracks.lastIndex),
            positionMs = root.optInt("positionMs", 0).coerceAtLeast(0),
            hasManualOrder = root.optBoolean("hasManualOrder", false)
        )
    }.getOrNull()

    @Synchronized
    fun save(state: SavedAudioPlaylist) {
        if (state.tracks.isEmpty()) {
            clear()
            return
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("currentIndex", state.currentIndex.coerceIn(0, state.tracks.lastIndex))
            .put("positionMs", state.positionMs.coerceAtLeast(0))
            .put("hasManualOrder", state.hasManualOrder)
            .put("tracks", writeTracks(state.tracks))
            .put("originalTracks", writeTracks(state.originalTracks))
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() = file.delete()

    private fun writeTracks(tracks: List<AudioTrack>): JSONArray = JSONArray().apply {
        tracks.forEach { track -> put(JSONObject()
            .put("uri", track.uri.toString())
            .put("title", track.title)
            .putNullable("artist", track.artist)
            .putNullable("album", track.album)
            .put("durationMs", track.durationMs)
            .put("startByte", track.startByte)
            .put("lengthBytes", track.lengthBytes)
            .putNullable("bitrateKbps", track.bitrateKbps)
            .putNullable("sampleRateHz", track.sampleRateHz)
            .putNullable("channels", track.channels)
            .put("isBitrateRelevant", track.isBitrateRelevant)
            .put("fileName", track.fileName)
            .put("isUnreadable", track.isUnreadable)
            .putNullable("decoderExtension", track.decoderExtension)
            .putNullable("directoryCoverUri", track.directoryCoverUri?.toString())
            .putNullable("directoryUri", track.directoryUri?.toString())
            .putNullable("directoryName", track.directoryName)
            .put("directoryArtworkMetadataResolved", track.directoryArtworkMetadataResolved))
    } }

    private fun readTracks(array: JSONArray?): List<AudioTrack> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val uri = item.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse) ?: continue
            add(AudioTrack(
                uri = uri,
                title = item.optString("title").ifBlank { item.optString("fileName", "Audio Track") },
                artist = item.stringOrNull("artist"),
                album = item.stringOrNull("album"),
                durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                startByte = item.optLong("startByte", 0L).coerceAtLeast(0L),
                lengthBytes = item.optLong("lengthBytes", -1L),
                bitrateKbps = item.intOrNull("bitrateKbps"),
                sampleRateHz = item.intOrNull("sampleRateHz"),
                channels = item.intOrNull("channels"),
                isBitrateRelevant = item.optBoolean("isBitrateRelevant", true),
                fileName = item.optString("fileName").ifBlank { item.optString("title", "Audio Track") },
                isUnreadable = item.optBoolean("isUnreadable", false),
                decoderExtension = item.stringOrNull("decoderExtension"),
                directoryCoverUri = item.stringOrNull("directoryCoverUri")?.let(Uri::parse),
                directoryUri = item.stringOrNull("directoryUri")?.let(Uri::parse),
                directoryName = item.stringOrNull("directoryName"),
                directoryArtworkMetadataResolved = item.optBoolean("directoryArtworkMetadataResolved", false)
            ))
        }
    }

    companion object {
        private const val VERSION = 1
    }
}

/** JSPF export keeps the active queue order, including duplicate and segmented tracks. */
internal object AudioPlaylistJspf {
    const val MIME_TYPE = "application/jspf+json"

    fun encode(tracks: List<AudioTrack>, title: String): ByteArray {
        val items = JSONArray()
        tracks.forEach { track ->
            val item = JSONObject()
                .put("location", track.uri.toString())
                .put("title", track.title)
            track.artist?.takeIf(String::isNotBlank)?.let { item.put("creator", it) }
            track.album?.takeIf(String::isNotBlank)?.let { item.put("album", it) }
            track.durationMs.takeIf { it > 0L }?.let { item.put("duration", it) }
            val extension = JSONObject().put("mimeType", mimeForName(track.fileName))
            track.startByte.takeIf { it > 0L }?.let { extension.put("startByte", it) }
            track.lengthBytes.takeIf { it >= 0L }?.let { extension.put("lengthBytes", it) }
            track.decoderExtension?.takeIf(String::isNotBlank)?.let { extension.put("decoderExtension", it) }
            item.put("extension", JSONObject().put("io.github.gretchycat.infiniselect", extension))
            items.put(item)
        }
        val cleanTitle = title.substringBeforeLast('.', title).ifBlank { "InfiniMedia Playlist" }
        return JSONObject().put("playlist", JSONObject()
            .put("title", cleanTitle)
            .put("track", items)).toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun mimeForName(name: String): String =
        com.infinifolder.mime.MimeRegistry.guess(name).takeIf { it.startsWith("audio/", ignoreCase = true) }
            ?: "application/octet-stream"
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
    put(key, value ?: JSONObject.NULL)
    return this
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
