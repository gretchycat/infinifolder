package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.playlist.PlaylistParser
import java.io.File
import java.net.URI
import java.net.URLDecoder

object PlaylistExpander {

    var uriFactory: (String) -> Uri = { str -> Uri.parse(str) }

    fun isPlaylist(name: String, mimeType: String = ""): Boolean {
        return ArchiveFormats.isPlaylist(name, mimeType) || PlaylistParser.isPlaylist(name)
    }

    fun isPlaylist(uri: Uri, name: String? = null, mimeType: String? = null): Boolean {
        // A display name may be a user-defined shortcut title without an extension.
        // Keep it as a useful clue, but never let it hide the URI's actual file name.
        val uriName = if (ArchiveUri.isArchiveUri(uri)) {
            ArchiveUri.getEntryPath(uri).substringAfterLast('/')
        } else {
            uri.lastPathSegment.orEmpty()
        }
        val uriPathName = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return sequenceOf(name, uriName, uriPathName)
            .filterNotNull()
            .distinct()
            .any { isPlaylist(it, mimeType.orEmpty()) }
    }

    /** Content sniffing is a bounded fallback for providers that omit a filename or MIME. */
    fun isPlaylist(context: Context, uri: Uri, name: String? = null, mimeType: String? = null): Boolean {
        if (isPlaylist(uri, name, mimeType)) return true
        val candidateName = name ?: uri.lastPathSegment.orEmpty()
        return runCatching {
            val prefix = MediaSourceAdapter.readText(context, uri, maxBytes = 64 * 1024).first
            PlaylistParser.isPlaylist(candidateName, prefix)
        }.getOrDefault(false)
    }

    /** Resolve a CDDB metadata record to its matching playable sibling playlist, when unique. */
    fun matchingCddbPlaylist(context: Context, uri: Uri): ResourceEntry? = runCatching {
        val record = com.infinifolder.audio.cddb.CddbManager.parseXmcd(
            MediaSourceAdapter.readText(context, uri, maxBytes = 64 * 1024).first
        ) ?: return null
        if (record.discId.isBlank()) return null
        val parent = MediaSourceAdapter.getParentDirectory(context, uri) ?: return null
        val siblings = MediaSourceAdapter.listFolder(context, parent)
        val matches = siblings.asSequence()
            .filter { !it.metadata.isDirectory && it.uri != uri &&
                it.metadata.name.substringAfterLast('.', "").equals("cue", ignoreCase = true) }
            .mapNotNull { candidate ->
                val text = runCatching {
                    MediaSourceAdapter.readText(context, candidate.uri, maxBytes = 256 * 1024).first
                }.getOrNull() ?: return@mapNotNull null
                val tracks = PlaylistParser.parse(candidate.metadata.name, text)
                val matchingTrack = tracks.firstOrNull {
                    it.discId.equals(record.discId, ignoreCase = true) && it.pathOrUri.isNotBlank()
                } ?: return@mapNotNull null
                val source = resolveEntryUri(context, candidate.uri, matchingTrack.pathOrUri)
                if (!isSourceAvailable(context, source)) return@mapNotNull null
                candidate
            }
            .take(2)
            .toList()
        matches.singleOrNull()
    }.onFailure {
        com.infinifolder.storage.DiagnosticLog.record("audio.cddb.companion.resolve.failed", error = it)
    }.getOrNull()

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

    private fun decodeUriPath(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(value)

    private fun entryFileName(reference: String): String {
        val path = runCatching { URI(reference).path }.getOrNull()
            ?: reference.substringBefore('?').substringBefore('#')
        return decodeUriPath(path.substringAfterLast('/').substringAfterLast('\\'))
    }

    private fun fileUri(file: File): String = file.toURI().toString().replaceFirst("file:/", "file:///")

    fun resolveEntryUri(context: Context?, playlistUri: Uri, pathOrUri: String): Uri {
        val reference = pathOrUri.trim()
        if (reference.isEmpty()) return uriFactory(playlistUri.toString())
        val parsedReference = runCatching { URI(reference.replace(" ", "%20")) }.getOrNull()
        if (parsedReference?.isAbsolute == true) return uriFactory(parsedReference.toASCIIString())
        if (reference.startsWith("//")) {
            val resolved = runCatching { URI(playlistUri.toString().replace(" ", "%20")).resolve(reference.replace(" ", "%20")).toString() }.getOrNull()
            if (resolved != null) return uriFactory(resolved)
        }
        val pathOrUri = reference

        if (ArchiveUri.isArchiveUri(playlistUri)) {
            val entry = ArchiveUri.getEntryPath(playlistUri)
            val relative = pathOrUri.replace('\\', '/')
            val resolved = resolveRelativePath(if (relative.startsWith('/')) "" else entry.substringBeforeLast('/', ""), relative)
            return ArchiveUri.build(ArchiveUri.getSourceUri(playlistUri), resolved,
                deleted = ArchiveUri.isDeletedView(playlistUri))
        }

        if (pathOrUri.startsWith('/') && File(pathOrUri).exists()) {
            return uriFactory(fileUri(File(pathOrUri)))
        }

        if (playlistUri.scheme == "file") {
            val localFile = playlistUri.path?.let { File(it) }
            if (localFile != null) {
                val parent = localFile.parentFile ?: File(".")
                val localReference = decodeUriPath(pathOrUri)
                val target = File(parent, localReference).canonicalFile
                if (target.exists()) return uriFactory(fileUri(target))

                val sibling = File(parent, File(localReference).name).canonicalFile
                if (sibling.exists()) return uriFactory(fileUri(sibling))

                val parentParent = parent.parentFile
                if (parentParent != null) {
                    val audioSibling = File(parentParent, "audio/${File(localReference).name}").canonicalFile
                    if (audioSibling.exists()) return uriFactory(fileUri(audioSibling))
                }

                val audioSub = File(parent, "audio/${File(localReference).name}").canonicalFile
                if (audioSub.exists()) return uriFactory(fileUri(audioSub))

                return uriFactory(fileUri(target))
            }
        }

        if (playlistUri.scheme == "content") {
            try {
                val authority = playlistUri.authority
                val isTree = (try { android.provider.DocumentsContract.isTreeUri(playlistUri) } catch (_: Throwable) { false }) ||
                    playlistUri.pathSegments.firstOrNull() == "tree"
                val isDocument = playlistUri.pathSegments.firstOrNull() == "document" ||
                    playlistUri.pathSegments.getOrNull(2) == "document" ||
                    (context != null && runCatching {
                        android.provider.DocumentsContract.isDocumentUri(context, playlistUri)
                    }.getOrDefault(false))
                val docId = try {
                    when {
                        isDocument -> android.provider.DocumentsContract.getDocumentId(playlistUri)
                        isTree -> android.provider.DocumentsContract.getTreeDocumentId(playlistUri)
                        else -> null
                    }
                } catch (_: Throwable) { null }

                // A content:// URI is not necessarily a SAF document. Do not invent
                // DocumentsContract URIs for ordinary providers (FileProvider, app
                // providers, etc.); those must resolve relative to their own URI below.
                if (docId != null && (isTree || isDocument)) {
                    val colonIdx = docId.indexOf(':')
                    val prefix = if (colonIdx >= 0) docId.substring(0, colonIdx + 1) else ""
                    val docPath = if (colonIdx >= 0) docId.substring(colonIdx + 1) else docId
                    val parentDocPath = docPath.substringBeforeLast('/', "")
                    val decodedReference = decodeUriPath(pathOrUri)
                    val targetName = decodedReference.substringAfterLast('/').substringAfterLast('\\')

                    val candidatePaths = listOfNotNull(
                        resolveRelativePath(parentDocPath, decodedReference),
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
                    // Preserve a best-effort URI only for callers without a Context.
                    // With a Context, a failed open means the provider rejected this
                    // sibling; do not fabricate a queue item that points nowhere.
                    if (primarySafUri != null && context == null) return primarySafUri
                }
                if (isTree || isDocument) return Uri.EMPTY
            } catch (_: Throwable) {}

            val localFile = context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri) }.getOrNull() }
            if (localFile != null) {
                val parent = localFile.parentFile ?: File(".")
                val localReference = decodeUriPath(pathOrUri)
                val target = File(parent, localReference).canonicalFile
                if (target.exists()) return uriFactory(fileUri(target))

                val sibling = File(parent, File(localReference).name).canonicalFile
                if (sibling.exists()) return uriFactory(fileUri(sibling))

                val parentParent = parent.parentFile
                if (parentParent != null) {
                    val audioSibling = File(parentParent, "audio/${File(localReference).name}").canonicalFile
                    if (audioSibling.exists()) return uriFactory(fileUri(audioSibling))
                }

                val audioSub = File(parent, "audio/${File(localReference).name}").canonicalFile
                if (audioSub.exists()) return uriFactory(fileUri(audioSub))

                return uriFactory(fileUri(target))
            }
        }

        // Resolve against the playlist URI itself so ordinary content:// providers,
        // HTTP locations, and non-DocumentsProvider references keep their authority.
        val resolved = runCatching {
            URI(playlistUri.toString().replace(" ", "%20")).resolve(reference.replace(" ", "%20")).toString()
        }.getOrNull()
        if (resolved != null) return uriFactory(resolved)
        val uriPath = playlistUri.path ?: ""
        val parentPath = uriPath.substringBeforeLast('/', "")
        return runCatching { playlistUri.buildUpon().encodedPath(resolveRelativePath(parentPath, reference)).build() }
            .getOrElse { uriFactory("${playlistUri.toString().substringBeforeLast('/')}/$reference") }
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
        var hasMoreText = false
        var playlistName = "playlist.m3u"
        try {
            val name = if (ArchiveUri.isArchiveUri(playlistUri)) ArchiveUri.getEntryPath(playlistUri).substringAfterLast('/')
                else playlistUri.lastPathSegment ?: "playlist.m3u"
            playlistName = name
            val localFile = if (playlistUri.scheme == "file") {
                playlistUri.path?.let { File(it) }
            } else {
                context?.let { runCatching { MediaSourceAdapter.resolveLocalFile(it, playlistUri) }.getOrNull() }
            }

            val text = if (localFile != null && localFile.canRead()) {
                localFile.readText(Charsets.UTF_8)
            } else {
                if (context != null) MediaSourceAdapter.readText(context, playlistUri).let { (content, truncated) ->
                    hasMoreText = truncated
                    content
                } else ""
            }
            val entries = PlaylistParser.parse(name, text)
            var missingSources = 0
            if (entries.isEmpty()) {
                com.infinifolder.storage.DiagnosticLog.record("audio.playlist.empty",
                    "format=${name.substringAfterLast('.', "").lowercase()} chars=${text.length} truncated=$hasMoreText")
            }

            for (entry in entries) {
                val itemUri = resolveEntryUri(context, playlistUri, entry.pathOrUri)
                if (itemUri == Uri.EMPTY || !isSourceAvailable(context, itemUri)) {
                    missingSources++
                    continue
                }
                val itemName = entryFileName(entry.pathOrUri)
                if (isPlaylist(itemUri, itemName)) {
                    // Recursive expansion for nested playlists
                    val nestedTracks = expandPlaylist(context, itemUri, visited)
                    tracks.addAll(nestedTracks)
                } else {
                    val title = entry.title.ifBlank { itemName }
                    val durationMs = entry.durationMillis ?: if (entry.durationSeconds > 0) entry.durationSeconds * 1000L else 0L
                    tracks.add(
                        AudioTrack(
                            uri = itemUri,
                            title = title,
                            fileName = itemName,
                            artist = entry.performer.ifBlank { null },
                            album = entry.album.ifBlank { null },
                            durationMs = durationMs,
                            startByte = entry.startByte,
                            lengthBytes = entry.lengthBytes,
                            decoderExtension = entry.decoderExtension
                        )
                    )
                }
            }
            if (missingSources > 0) {
                com.infinifolder.storage.DiagnosticLog.record("audio.playlist.sources_missing",
                    "format=${name.substringAfterLast('.', "").lowercase()} missing=$missingSources parsed=${entries.size}")
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
            com.infinifolder.storage.DiagnosticLog.record("audio.playlist.expand.failed",
                "format=${playlistName.substringAfterLast('.', "").lowercase()} truncated=$hasMoreText", e)
        }

        return tracks
    }

    private fun isSourceAvailable(context: Context?, uri: Uri): Boolean {
        if (context == null) return true
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { File(it).isFile && File(it).canRead() } == true
            "content" -> runCatching {
                MediaSourceAdapter.openStream(context, uri).use { }
                true
            }.getOrDefault(false)
            else -> true
        }
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
