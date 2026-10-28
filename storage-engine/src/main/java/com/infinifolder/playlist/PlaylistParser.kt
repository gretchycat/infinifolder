package com.infinifolder.playlist

data class PlaylistEntry(
    val title: String,
    val pathOrUri: String,
    val durationSeconds: Int = -1,
    val startByte: Long = 0L,
    val lengthBytes: Long = -1L,
    val performer: String = "",
    val discId: String = ""
)

object PlaylistParser {

    fun isPlaylist(name: String, content: String = ""): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls") ||
            lower.endsWith(".xspf") || lower.endsWith(".cue")) return true
        if (content.contains("#EXTM3U") || content.contains("[playlist]") ||
            content.contains("<playlist") || content.contains("TRACK 01 AUDIO") ||
            content.contains("TRACK 1 AUDIO")) return true
        return false
    }

    fun parse(name: String, content: String): List<PlaylistEntry> {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pls") || content.contains("[playlist]") -> parsePls(content)
            lower.endsWith(".xspf") || content.contains("<playlist") -> parseXspf(content)
            lower.endsWith(".cue") || content.contains("TRACK 01 AUDIO") || content.contains("TRACK 1 AUDIO") -> parseCue(content)
            else -> parseM3u(content)
        }
    }

    private fun parseM3u(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        var currentTitle = ""
        var currentDur = -1

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#EXTINF:")) {
                val after = trimmed.removePrefix("#EXTINF:")
                val comma = after.indexOf(',')
                if (comma >= 0) {
                    currentDur = after.substring(0, comma).trim().toIntOrNull() ?: -1
                    currentTitle = after.substring(comma + 1).trim()
                } else {
                    currentDur = after.trim().toIntOrNull() ?: -1
                }
            } else if (!trimmed.startsWith("#")) {
                val title = if (currentTitle.isNotEmpty()) currentTitle else trimmed.substringAfterLast('/').substringAfterLast('\\')
                entries.add(PlaylistEntry(title = title, pathOrUri = trimmed, durationSeconds = currentDur))
                currentTitle = ""
                currentDur = -1
            }
        }
        return entries
    }

    private fun parsePls(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        val files = mutableMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        val lengths = mutableMapOf<Int, Int>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("File", ignoreCase = true)) {
                val eq = trimmed.indexOf('=')
                if (eq > 4) {
                    val idx = trimmed.substring(4, eq).toIntOrNull()
                    if (idx != null) files[idx] = trimmed.substring(eq + 1).trim()
                }
            } else if (trimmed.startsWith("Title", ignoreCase = true)) {
                val eq = trimmed.indexOf('=')
                if (eq > 5) {
                    val idx = trimmed.substring(5, eq).toIntOrNull()
                    if (idx != null) titles[idx] = trimmed.substring(eq + 1).trim()
                }
            } else if (trimmed.startsWith("Length", ignoreCase = true)) {
                val eq = trimmed.indexOf('=')
                if (eq > 6) {
                    val idx = trimmed.substring(6, eq).toIntOrNull()
                    if (idx != null) lengths[idx] = trimmed.substring(eq + 1).trim().toIntOrNull() ?: -1
                }
            }
        }

        for (idx in files.keys.sorted()) {
            val file = files[idx] ?: continue
            val title = titles[idx] ?: file.substringAfterLast('/').substringAfterLast('\\')
            val len = lengths[idx] ?: -1
            entries.add(PlaylistEntry(title, file, len))
        }

        return entries
    }

    private fun parseXspf(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        val trackBlocks = content.split(Regex("(?i)<track>"))
        for (i in 1 until trackBlocks.size) {
            val block = trackBlocks[i].substringBefore("</track>")
            val loc = extractTag(block, "location") ?: continue
            val title = extractTag(block, "title") ?: loc.substringAfterLast('/').substringAfterLast('\\')
            entries.add(PlaylistEntry(title = title, pathOrUri = loc))
        }
        return entries
    }

    private fun parseCue(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        var globalPerformer = ""
        var globalTitle = ""
        var globalDiscId = ""
        var currentFile = ""

        data class CueTrackInfo(
            val trackNum: Int,
            var title: String = "",
            var performer: String = "",
            var file: String = "",
            var index01Frames: Int = -1
        )

        val tracks = mutableListOf<CueTrackInfo>()
        var currentTrack: CueTrackInfo? = null

        fun parseFrames(timestamp: String): Int {
            val parts = timestamp.split(':')
            val mm = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val ss = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val ff = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return (mm * 60 + ss) * 75 + ff
        }

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val upper = trimmed.uppercase()
            when {
                upper.startsWith("REM DISCID ") || upper.startsWith("REM CDDB ") -> {
                    globalDiscId = trimmed.substringAfter("DISCID", "").substringAfter("CDDB", "").trim().take(8)
                }
                upper.startsWith("PERFORMER ") -> {
                    val p = extractQuotedOrRest(trimmed.substring(10).trim())
                    if (currentTrack != null) currentTrack.performer = p else globalPerformer = p
                }
                upper.startsWith("TITLE ") -> {
                    val t = extractQuotedOrRest(trimmed.substring(6).trim())
                    if (currentTrack != null) currentTrack.title = t else globalTitle = t
                }
                upper.startsWith("FILE ") -> {
                    val remainder = trimmed.substring(5).trim()
                    currentFile = if (remainder.startsWith('"')) {
                        remainder.substringAfter('"').substringBefore('"')
                    } else {
                        remainder.substringBefore(' ')
                    }
                }
                upper.startsWith("TRACK ") -> {
                    val num = trimmed.substringAfter("TRACK", "").trim().substringBefore(' ').toIntOrNull() ?: (tracks.size + 1)
                    val t = CueTrackInfo(trackNum = num, file = currentFile, performer = globalPerformer)
                    tracks.add(t)
                    currentTrack = t
                }
                upper.startsWith("INDEX 01 ") || upper.startsWith("INDEX 1 ") -> {
                    val ts = trimmed.substringAfter("INDEX 01", "").substringAfter("INDEX 1", "").trim()
                    currentTrack?.index01Frames = parseFrames(ts)
                }
            }
        }

        val discId = if (globalDiscId.isNotEmpty()) globalDiscId else {
            if (tracks.isNotEmpty() && tracks.all { it.index01Frames >= 0 }) {
                calculateCddbDiscId(tracks.map { it.index01Frames })
            } else ""
        }

        for (i in 0 until tracks.size) {
            val t = tracks[i]
            val nextTrackSameFile = tracks.subList(i + 1, tracks.size).firstOrNull { it.file == t.file && it.index01Frames >= 0 }
            val startFrames = if (t.index01Frames >= 0) t.index01Frames else 0
            val startByte = startFrames * 2352L
            val (lengthBytes, durSec) = if (nextTrackSameFile != null && nextTrackSameFile.index01Frames > startFrames) {
                val frameDiff = nextTrackSameFile.index01Frames - startFrames
                (frameDiff * 2352L) to (frameDiff / 75)
            } else {
                -1L to -1
            }

            val title = t.title.ifBlank { "Track ${t.trackNum}" }
            entries.add(
                PlaylistEntry(
                    title = title,
                    pathOrUri = t.file,
                    durationSeconds = durSec,
                    startByte = startByte,
                    lengthBytes = lengthBytes,
                    performer = t.performer.ifBlank { globalPerformer },
                    discId = discId
                )
            )
        }

        return if (entries.isEmpty() && currentFile.isNotEmpty()) {
            listOf(PlaylistEntry(title = globalTitle.ifBlank { currentFile }, pathOrUri = currentFile, discId = discId))
        } else {
            entries
        }
    }

    fun calculateCddbDiscId(trackOffsetsFrames: List<Int>, leadoutFrames: Int? = null): String {
        if (trackOffsetsFrames.isEmpty()) return ""
        val nTracks = trackOffsetsFrames.size
        fun cddbSum(n: Int): Int {
            var sum = 0
            var v = Math.abs(n)
            while (v > 0) {
                sum += v % 10
                v /= 10
            }
            return sum
        }

        var checksum = 0
        for (f in trackOffsetsFrames) {
            checksum += cddbSum((f + 150) / 75)
        }

        val lastOffset = trackOffsetsFrames.last()
        val totalSec = if (leadoutFrames != null && leadoutFrames > 0) {
            (leadoutFrames + 150) / 75 - 2
        } else {
            (lastOffset + 150) / 75 + 120
        }

        val discId = ((checksum % 0xFF) shl 24) or ((totalSec and 0xFFFF) shl 8) or (nTracks and 0xFF)
        return String.format(java.util.Locale.US, "%08x", discId)
    }

    private fun extractQuotedOrRest(s: String): String {
        val q1 = s.indexOf('"')
        val q2 = s.lastIndexOf('"')
        return if (q1 >= 0 && q2 > q1) s.substring(q1 + 1, q2).trim() else s.trim()
    }

    private fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val s = xml.indexOf(open, ignoreCase = true)
        val e = xml.indexOf(close, ignoreCase = true)
        return if (s >= 0 && e > s) xml.substring(s + open.length, e).trim() else null
    }
}
