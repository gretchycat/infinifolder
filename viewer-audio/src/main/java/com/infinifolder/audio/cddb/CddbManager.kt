package com.infinifolder.audio.cddb

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class CddbRecord(
    val discId: String,
    val artist: String,
    val album: String,
    val year: String? = null,
    val genre: String? = null,
    val trackTitles: List<String> = emptyList()
)

object CddbManager {

    private val cache = android.util.LruCache<String, CddbRecord>(128)

    fun parseXmcd(text: String): CddbRecord? {
        var discId = ""
        var dtitle = ""
        var year: String? = null
        var genre: String? = null
        val titlesMap = mutableMapOf<Int, StringBuilder>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().uppercase()
            val value = trimmed.substring(eq + 1).trim()

            when {
                key == "DISCID" -> if (discId.isEmpty()) discId = value.split(',').first().trim()
                key == "DTITLE" -> dtitle = if (dtitle.isEmpty()) value else "$dtitle$value"
                key == "DYEAR" -> year = value
                key == "DGENRE" -> genre = value
                key.startsWith("TTITLE") -> {
                    val idx = key.removePrefix("TTITLE").toIntOrNull()
                    if (idx != null) {
                        titlesMap.getOrPut(idx) { StringBuilder() }.append(value)
                    }
                }
            }
        }

        if (dtitle.isEmpty() && titlesMap.isEmpty()) return null

        val artist = if (dtitle.contains(" / ")) dtitle.substringBefore(" / ").trim() else ""
        val album = if (dtitle.contains(" / ")) dtitle.substringAfter(" / ").trim() else dtitle.trim()

        val trackTitles = titlesMap.keys.sorted().map { idx ->
            titlesMap[idx].toString().trim()
        }

        return CddbRecord(
            discId = discId,
            artist = artist,
            album = album,
            year = year,
            genre = genre,
            trackTitles = trackTitles
        )
    }

    fun findLocalCddb(directory: File, discId: String? = null): CddbRecord? {
        if (!directory.exists() || !directory.isDirectory) return null

        if (discId != null) {
            val cached = cache.get(discId)
            if (cached != null) return cached
        }

        val candidates = mutableListOf<File>()
        if (discId != null) {
            candidates.add(File(directory, discId))
            candidates.add(File(directory, "$discId.xmcd"))
            candidates.add(File(directory, "$discId.cddb"))
        }
        candidates.addAll(listOf(
            File(directory, "cddb.toc"),
            File(directory, ".cddb"),
            File(directory, "freedb.txt"),
            File(directory, "cdinfo.txt"),
            File(directory, "album.cddb")
        ))

        for (cand in candidates) {
            if (cand.isFile && cand.canRead()) {
                val record = runCatching { parseXmcd(cand.readText(Charsets.UTF_8)) }.getOrNull()
                if (record != null) {
                    if (record.discId.isNotEmpty()) cache.put(record.discId, record)
                    return record
                }
            }
        }
        return null
    }

    fun queryOnlineGnudb(
        discId: String,
        trackOffsetsFrames: List<Int>,
        totalDurationSeconds: Int
    ): CddbRecord? {
        if (discId.isBlank() || trackOffsetsFrames.isEmpty()) return null
        val cached = cache.get(discId)
        if (cached != null) return cached

        return try {
            val nTracks = trackOffsetsFrames.size
            val offsetsStr = trackOffsetsFrames.joinToString("+")
            val queryCmd = "cddb+query+$discId+$nTracks+$offsetsStr+$totalDurationSeconds"
            val hello = "anonymous+example.com+grip+3.3.1"
            val queryUrl = "http://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=$queryCmd&hello=$hello&proto=6"

            val queryConn = (URL(queryUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }

            val queryResp = queryConn.inputStream.bufferedReader().use { it.readText() }
            val firstLine = queryResp.lineSequence().firstOrNull()?.trim() ?: return null

            val code = firstLine.substringBefore(' ').toIntOrNull() ?: return null
            val matchLine = when (code) {
                200 -> firstLine.removePrefix("200").trim()
                210, 211 -> queryResp.lines().drop(1).firstOrNull { it.isNotBlank() && !it.startsWith(".") }?.trim()
                else -> null
            } ?: return null

            val parts = matchLine.split(' ')
            if (parts.size < 2) return null
            val category = parts[0]
            val matchedDiscId = parts[1]

            val readCmd = "cddb+read+$category+$matchedDiscId"
            val readUrl = "http://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=$readCmd&hello=$hello&proto=6"
            val readConn = (URL(readUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }

            val xmcdText = readConn.inputStream.bufferedReader().use { it.readText() }
            val record = parseXmcd(xmcdText)
            if (record != null) {
                cache.put(discId, record)
                if (matchedDiscId != discId) cache.put(matchedDiscId, record)
            }
            record
        } catch (_: Throwable) {
            null
        }
    }
}
