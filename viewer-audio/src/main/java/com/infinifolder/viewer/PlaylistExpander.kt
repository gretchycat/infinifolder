package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.model.ResourceEntry
import com.infinifolder.playlist.PlaylistParser
import java.io.File

object PlaylistExpander {

    var uriFactory: (String) -> Uri = { str -> Uri.parse(str) }

    fun isPlaylist(name: String, mimeType: String = ""): Boolean {
        return ArchiveFormats.isPlaylist(name, mimeType) || PlaylistParser.isPlaylist(name)
    }

    fun isPlaylist(uri: Uri, name: String? = null, mimeType: String? = null): Boolean {
        val checkName = name ?: uri.lastPathSegment ?: ""
        return isPlaylist(checkName, mimeType ?: "")
    }

    fun resolveRelativePath(baseDir: String, relative: String): String {
        val isAbsolute = baseDir.startsWith('/')
        val segments = baseDir.replace('\\', '/').split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in relative.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        return (if (isAbsolute) "/" else "") + segments.joinToString("/")
    }

    fun resolveEntryUri(context: Context?, playlistUri: Uri, pathOrUri: String): Uri {
        if (pathOrUri.contains("://")) {
            return uriFactory(pathOrUri)
        }

        if (pathOrUri.startsWith('/') && File(pathOrUri).exists()) {
            return uriFactory("file://$pathOrUri")
        }

        if (playlistUri.scheme == "file") {
            val localFile = playlistUri.path?.let { File(it) }
            if (localFile != null) {
                val parent = localFile.parentFile ?: File(".")
                val target = File(parent, pathOrUri).canonicalFile
                if (target.exists()) return uriFactory("file://${target.absolutePath}")

                val sibling = File(parent, File(pathOrUri).name).canonicalFile
                if (sibling.exists()) return uriFactory("file://${sibling.absolutePath}")

                val parentParent = parent.parentFile
                if (parentParent != null) {
                    val audioSibling = File(parentParent, "audio/${File(pathOrUri).name}").canonicalFile
                    if (audioSibling.exists()) return uriFactory("file://${audioSibling.absolutePath}")
                }

                val audioSub = File(parent, "audio/${File(pathOrUri).name}").canonicalFile
                if (audioSub.exists()) return uriFactory("file://${audioSub.absolutePath}")

                return uriFactory("file://${target.absolutePath}")
            }
        }

        if (playlistUri.scheme == "content") {
            try {
                val authority = playlistUri.authority
                val isTree = (try { android.provider.DocumentsContract.isTreeUri(playlistUri) } catch (_: Throwable) { false }) ||
                    playlistUri.pathSegments.contains("tree")
                val docId = try {
                    if (playlistUri.pathSegments.firstOrNull() == "document" || playlistUri.pathSegments.getOrNull(2) == "document") {
                        android.provider.DocumentsContract.getDocumentId(playlistUri)
                    } else if (isTree) {
                        android.provider.DocumentsContract.getTreeDocumentId(playlistUri)
                    } else null
                } catch (_: Throwable) { null } ?: playlistUri.lastPathSegment

                if (docId != null) {
                    val colonIdx = docId.indexOf(':')
                    val prefix = if (colonIdx >= 0) docId.substring(0, colonIdx + 1) else ""
                    val docPath = if (colonIdx >= 0) docId.substring(colonIdx + 1) else docId
                    val parentDocPath = docPath.substringBeforeLast('/', "")
                    val targetName = pathOrUri.substringAfterLast('/').substringAfterLast('\\')

                    val candidatePaths = listOfNotNull(
                        resolveRelativePath(parentDocPath, pathOrUri),
                        resolveRelativePath(parentDocPath, targetName),
                        if (parentDocPath.contains('/')) resolveRelativePath(parentDocPath.substringBeforeLast('/', ""), "audio/$targetName") else null,
                        resolveRelativePath(parentDocPath, "audio/$targetName")
                    ).distinct()

                    for (candidate in candidatePaths) {
                        val candidateDocId = prefix + candidate
                        val targetSafUri = try {
                            if (isTree) {
                                android.provider.DocumentsContract.buildDocumentUriUsingTree(playlistUri, candidateDocId)
                            } else if (authority != null) {
                                android.provider.DocumentsContract.buildDocumentUri(authority, candidateDocId)
                            } else null
                        } catch (_: Throwable) { null }

                        if (targetSafUri != null) {
                            if (context != null) {
                                val canOpen = try {
                                    context.contentResolver.openInputStream(targetSafUri)?.use { true } ?: false
                                } catch (_: Throwable) { false }
                                if (canOpen) return targetSafUri
                            } else {
                                if (candidate == candidatePaths.first()) return targetSafUri
                            }
                        }
                    }

                    val primaryDocId = prefix + candidatePaths.first()
                    val primarySafUri = try {
                        if (isTree) {
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(playlistUri, primaryDocId)
                        } else if (authority != null) {
                            android.provider.DocumentsContract.buildDocumentUri(authority, primaryDocId)
                        } else null
                    } catch (_: Throwable) { null }
                    if (primarySafUri != null) return primarySafUri
                }
            } catch (_: Throwable) {}

            val localFile = context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri) }.getOrNull() }
            if (localFile != null) {
                val parent = localFile.parentFile ?: File(".")
                val target = File(parent, pathOrUri).canonicalFile
                if (target.exists()) return uriFactory("file://${target.absolutePath}")

                val sibling = File(parent, File(pathOrUri).name).canonicalFile
                if (sibling.exists()) return uriFactory("file://${sibling.absolutePath}")

                val parentParent = parent.parentFile
                if (parentParent != null) {
                    val audioSibling = File(parentParent, "audio/${File(pathOrUri).name}").canonicalFile
                    if (audioSibling.exists()) return uriFactory("file://${audioSibling.absolutePath}")
                }

                val audioSub = File(parent, "audio/${File(pathOrUri).name}").canonicalFile
                if (audioSub.exists()) return uriFactory("file://${audioSub.absolutePath}")

                return uriFactory("file://${target.absolutePath}")
            }
        }

        // For non-local URIs, normalize path relative to parent
        val uriPath = playlistUri.path ?: ""
        val parentPath = uriPath.substringBeforeLast('/', "")
        val resolved = resolveRelativePath(parentPath, pathOrUri)
        return try {
            playlistUri.buildUpon().path(resolved).build()
        } catch (_: Throwable) {
            val sourceStr = playlistUri.toString()
            val baseStr = sourceStr.substringBeforeLast('/')
            uriFactory("$baseStr/$pathOrUri")
        }
    }

    fun expandPlaylist(
        context: Context?,
        playlistUri: Uri,
        visited: MutableSet<String> = mutableSetOf()
    ): List<AudioTrack> {
        val uriKey = playlistUri.toString()
        if (visited.contains(uriKey)) return emptyList()
        visited.add(uriKey)

        val tracks = mutableListOf<AudioTrack>()
        try {
            val name = playlistUri.lastPathSegment ?: "playlist.m3u"
            val localFile = if (playlistUri.scheme == "file") {
                playlistUri.path?.let { File(it) }
            } else {
                context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri) }.getOrNull() }
            }

            val text = if (localFile != null && localFile.canRead()) {
                localFile.readText(Charsets.UTF_8)
            } else {
                if (context != null) MediaSourceAdapter.readText(context, playlistUri).first else ""
            }
            val entries = PlaylistParser.parse(name, text)

            for (entry in entries) {
                val itemUri = resolveEntryUri(context, playlistUri, entry.pathOrUri)
                val itemName = entry.pathOrUri.substringAfterLast('/').substringAfterLast('\\')
                if (isPlaylist(itemUri, itemName)) {
                    // Recursive expansion for nested playlists
                    val nestedTracks = expandPlaylist(context, itemUri, visited)
                    tracks.addAll(nestedTracks)
                } else {
                    val title = entry.title.ifBlank { itemName }
                    val durationMs = if (entry.durationSeconds > 0) entry.durationSeconds * 1000L else 0L
                    tracks.add(
                        AudioTrack(
                            uri = itemUri,
                            title = title,
                            artist = entry.performer.ifBlank { null },
                            durationMs = durationMs,
                            startByte = entry.startByte,
                            lengthBytes = entry.lengthBytes
                        )
                    )
                }
            }

            // CDDB enrichment for CUE sheets / CD albums
            val discId = entries.firstOrNull { it.discId.isNotEmpty() }?.discId
            val parentDir = if (localFile != null) localFile.parentFile
                else context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri)?.parentFile }.getOrNull() }

            val cddbRecord = if (parentDir != null) {
                com.infinifolder.audio.cddb.CddbManager.findLocalCddb(parentDir, discId)
            } else null

            if (cddbRecord != null) {
                for (i in 0 until tracks.size) {
                    val t = tracks[i]
                    val cTitle = cddbRecord.trackTitles.getOrNull(i)
                    val newTitle = if (!cTitle.isNullOrBlank() && (t.title.startsWith("Track ") || t.title.isBlank())) cTitle else t.title
                    val newArtist = t.artist ?: cddbRecord.artist.ifBlank { null }
                    tracks[i] = t.copy(title = newTitle, artist = newArtist, album = cddbRecord.album.ifBlank { null })
                }
            }
        } catch (e: Exception) {
            println("PlaylistExpander exception on $playlistUri: $e")
        }

        return tracks
    }

    fun expandResourceEntries(
        context: Context?,
        entries: List<ResourceEntry>,
        queueMultimedia: Boolean = false,
        visited: MutableSet<String> = mutableSetOf()
    ): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        for (entry in entries) {
            if (entry.metadata.isDirectory) continue
            if (isPlaylist(entry.uri, entry.metadata.name, entry.metadata.mimeType)) {
                val playlistTracks = expandPlaylist(context, entry.uri, visited)
                tracks.addAll(playlistTracks)
            } else {
                val type = MediaTypeDetector.detect(entry.metadata.name, entry.metadata.mimeType)
                if (type == MediaType.AUDIO || (queueMultimedia && type == MediaType.VIDEO)) {
                    tracks.add(AudioTrack(entry.uri, entry.metadata.name))
                }
            }
        }
        return tracks
    }
}
