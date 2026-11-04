package com.infinifolder.audio.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.infinifolder.viewer.AudioPlayerService
import com.infinifolder.viewer.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

/** Google Cast sender; session and playback callbacks live with the audio service. */
object CastManager {
    interface Listener {
        fun onCastStateChanged(isCasting: Boolean, device: String?)
        fun onCastPlaybackStateChanged(isPlaying: Boolean)
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var castContext: CastContext? = null
    private var service: AudioPlayerService? = null
    private var server: CastMediaServer? = null
    private var remote: RemoteMediaClient? = null
    @Volatile var isCasting = false
        private set
    @Volatile var isRemotePlaying = false
        private set
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile var deviceName: String? = null
        private set
    private var lastLoadedKey: String? = null
    private var completedKey: String? = null
    private var pendingLoad = false
    private var resumeAfterFailure = false
    private var suppressResume = false
    private var loadSequence = 0L
    private var lastRemoteState: Pair<Int, Int>? = null
    private var activeUrl: String? = null
    private var failedUrl: String? = null
    @Volatile private var continuousTimeline: List<AudioTranscoder.TimelineEntry>? = null
    @Volatile private var receiverPositionMs = 0L
    @Volatile private var receiverPositionUpdatedAtMs = 0L
    /** Absolute position in the composite stream, used by the handset PCM analyzer. */
    val analysisPositionMs: Long get() {
        val position = receiverPositionMs
        val updatedAt = receiverPositionUpdatedAtMs
        if (!isRemotePlaying || updatedAt <= 0L) return position
        val elapsed = (SystemClock.elapsedRealtime() - updatedAt).coerceAtLeast(0L)
        val limit = continuousTimeline?.let(::durationMsTotal) ?: Long.MAX_VALUE
        return (position + elapsed).coerceAtMost(limit)
    }
    /** Track-relative interpolated position for smooth UI updates between receiver callbacks. */
    val displayPositionMs: Long get() {
        val timeline = continuousTimeline
        if (timeline.isNullOrEmpty()) return analysisPositionMs
        val absolute = analysisPositionMs
        val entry = timeline.lastOrNull { it.startMs <= absolute } ?: timeline.first()
        return (absolute - entry.startMs).coerceIn(0L, entry.durationMs)
    }
    val displayDurationMs: Long get() {
        val timeline = continuousTimeline
        if (timeline.isNullOrEmpty()) return durationMs
        val absolute = analysisPositionMs
        return (timeline.lastOrNull { it.startMs <= absolute } ?: timeline.first()).durationMs
    }
    private var prepareTask: Future<*>? = null
    private data class QueuedTrack(val track: AudioTrack, val url: String)
    private var queuedNext: QueuedTrack? = null
    private var queuedInsertPending = false
    private var refreshQueueAfterLoad = false

    fun addListener(listener: Listener) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun updateListeners() {
        listeners.forEach { it.onCastStateChanged(isCasting, deviceName); it.onCastPlaybackStateChanged(isRemotePlaying) }
    }

    private val progress = RemoteMediaClient.ProgressListener { position, duration ->
        updateReceiverPosition(position)
        if (activeUrl != null && remote?.mediaInfo?.contentId == activeUrl) {
            if (continuousTimeline != null) updateContinuousPosition(receiverPositionMs)
            else {
                positionMs = receiverPositionMs
                durationMs = duration.coerceAtLeast(0)
                service?.onCastProgress(positionMs, durationMs)
            }
        }
    }
    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = remote ?: return
            isRemotePlaying = client.isPlaying || client.isBuffering
            updateReceiverPosition(client.approximateStreamPosition)
            if (continuousTimeline == null) {
                positionMs = receiverPositionMs
                durationMs = client.streamDuration.coerceAtLeast(0)
            } else updateContinuousPosition(receiverPositionMs)
            val status = client.mediaStatus
            val receiverUrl = client.mediaInfo?.contentId ?: status?.queueItems
                ?.firstOrNull { it.itemId == status.currentItemId }?.media?.contentId
            val queued = queuedNext
            if (!pendingLoad && isCasting && queued != null && receiverUrl == queued.url) {
                queuedNext = null
                queuedInsertPending = false
                if (service?.onCastAdvanced(queued.track) == true) {
                    activeUrl = queued.url
                    failedUrl = null
                    lastLoadedKey = key(queued.track)
                    completedKey = null
                    com.infinifolder.storage.DiagnosticLog.record("cast.queue.advanced", "file=${queued.track.fileName}")
                    removePlayedQueueItems(client)
                    appendNext(client)
                } else {
                    // The playlist changed while the receiver was transitioning.
                    service?.onCastTrackComplete()
                    return
                }
            }
            val state = client.playerState to client.idleReason
            if (state != lastRemoteState) {
                lastRemoteState = state
                com.infinifolder.storage.DiagnosticLog.record(
                    "cast.receiver.status",
                    "state=${client.playerState} idleReason=${client.idleReason} playing=${client.isPlaying} buffering=${client.isBuffering} positionMs=$positionMs durationMs=$durationMs"
                )
            }
            val currentUrl = activeUrl
            if (!pendingLoad && isCasting && currentUrl != null && failedUrl != currentUrl &&
                client.playerState == MediaStatus.PLAYER_STATE_IDLE && client.idleReason == MediaStatus.IDLE_REASON_ERROR &&
                (receiverUrl == null || receiverUrl == currentUrl)) {
                service?.currentTrack?.takeIf { key(it) == lastLoadedKey }?.let { track ->
                    failedUrl = currentUrl
                    com.infinifolder.storage.DiagnosticLog.record("cast.track.skipped", "file=${track.fileName} reason=receiver-error")
                    service?.onCastTrackFailed(track)
                }
                return
            }
            listeners.forEach { it.onCastPlaybackStateChanged(isRemotePlaying) }
            if (isCasting) service?.onCastStateUpdate(isRemotePlaying)
            val key = lastLoadedKey
            if (!pendingLoad && isCasting && client.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                client.idleReason == MediaStatus.IDLE_REASON_FINISHED && key != null && completedKey != key) {
                val hasQueuedItem = queuedNext?.let { next ->
                    client.mediaStatus?.queueItems?.any { it.media?.contentId == next.url } == true
                } == true
                if (hasQueuedItem) return
                completedKey = key
                service?.onCastTrackComplete()
            }
        }
    }
    private fun updateReceiverPosition(position: Long) {
        receiverPositionMs = position.coerceAtLeast(0)
        receiverPositionUpdatedAtMs = SystemClock.elapsedRealtime()
    }
    private fun attach(session: CastSession) {
        remote?.unregisterCallback(remoteCallback)
        remote?.removeProgressListener(progress)
        remote = session.remoteMediaClient
        lastRemoteState = null
        remote?.registerCallback(remoteCallback)
        remote?.addProgressListener(progress, 1000)
        deviceName = session.castDevice?.friendlyName
    }
    private fun connect(session: CastSession, resumed: Boolean) {
        attach(session)
        val player = service ?: return
        player.resetCastFailures()
        if (resumed && remote?.hasMediaSession() == true) {
            positionMs = remote?.approximateStreamPosition ?: 0L
            isRemotePlaying = remote?.isPlaying == true
            isCasting = true
            player.pauseLocalForCast()
            lastLoadedKey = player.currentTrack?.let(::key)
            activeUrl = remote?.mediaInfo?.contentId
            player.startCastMetadataScan()
            updateListeners()
        } else {
            val track = player.currentTrack ?: return
            val wasPlaying = player.isPlaying
            val position = player.currentPosition.toLong()
            resumeAfterFailure = wasPlaying
            load(track, position, wasPlaying, initial = true)
        }
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = connect(session, false)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connect(session, true)
        override fun onSessionEnded(session: CastSession, error: Int) {
            val wasCasting = isCasting
            val position = positionMs
            val playing = isRemotePlaying
            remote?.unregisterCallback(remoteCallback)
            remote?.removeProgressListener(progress)
            remote = null
            loadSequence++
            isCasting = false
            isRemotePlaying = false
            deviceName = null
            lastLoadedKey = null
            activeUrl = null; failedUrl = null
            continuousTimeline = null
            service?.setCastAnalysisSource(null)
            prepareTask?.cancel(true); prepareTask = null
            service?.resetCastFailures()
            pendingLoad = false
            queuedNext = null; queuedInsertPending = false; refreshQueueAfterLoad = false
            server?.stop(); server = null
            updateListeners()
            if (wasCasting && !suppressResume) service?.resumeLocalFromCast(position, playing)
            suppressResume = false
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) { service?.onCastError("Could not connect to Cast device") }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) { service?.onCastError("Could not resume Cast session") }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
    fun initialize(context: Context, player: AudioPlayerService) {
        service = player
        try {
            castContext = CastContext.getSharedInstance(context.applicationContext)
            castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let { connect(it, true) }
        } catch (error: Exception) {
            android.util.Log.w("CastManager", "Google Cast unavailable", error)
        }
    }
    fun release() {
        runCatching { castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java) }
        remote?.unregisterCallback(remoteCallback)
        remote?.removeProgressListener(progress)
        service?.setCastAnalysisSource(null)
        remote = null; server?.stop(); server = null; service = null; castContext = null
        isCasting = false; isRemotePlaying = false; lastLoadedKey = null; deviceName = null
        activeUrl = null; failedUrl = null
        continuousTimeline = null
        prepareTask?.cancel(true); prepareTask = null
        pendingLoad = false; queuedNext = null; queuedInsertPending = false; refreshQueueAfterLoad = false
    }
    private fun key(track: AudioTrack) = "${track.uri}:${track.startByte}:${track.lengthBytes}"
    private fun sameTrack(a: AudioTrack, b: AudioTrack) = key(a) == key(b)
    private fun mediaInfo(track: AudioTrack, url: String): MediaInfo {
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title)
            track.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
            track.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
        }
        return MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(AudioTranscoder.getMimeType(track.uri, track.fileName)).setMetadata(meta)
            .apply { if (track.durationMs > 0) setStreamDuration(track.durationMs) }.build()
    }

    private fun continuousMediaInfo(track: AudioTrack, url: String, duration: Long): MediaInfo {
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, "${track.title} and playlist")
            track.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
            track.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
        }
        return MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/wav").setMetadata(meta).setStreamDuration(duration.coerceAtLeast(1)).build()
    }

    private fun updateContinuousPosition(position: Long) {
        val timeline = continuousTimeline ?: return
        if (timeline.isEmpty()) return
        val entry = timeline.lastOrNull { it.startMs <= position } ?: timeline.first()
        val relative = (position - entry.startMs).coerceIn(0, entry.durationMs)
        positionMs = relative
        durationMs = entry.durationMs
        lastLoadedKey = key(entry.track)
        service?.onCastTimelinePosition(entry.playlistIndex, entry.track, relative, entry.durationMs)
    }

    private fun seekPosition(position: Long): Long {
        val timeline = continuousTimeline ?: return position.coerceAtLeast(0)
        val current = service?.currentTrack
        val entry = timeline.firstOrNull { current != null && sameTrack(it.track, current) }
            ?: timeline.firstOrNull()
            ?: return position.coerceAtLeast(0)
        return (entry.startMs + position.coerceAtLeast(0).coerceAtMost(entry.durationMs)).coerceAtMost(durationMsTotal(timeline))
    }

    private fun durationMsTotal(timeline: List<AudioTranscoder.TimelineEntry>): Long =
        timeline.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L

    private fun queueItem(track: AudioTrack, url: String, autoplay: Boolean): MediaQueueItem =
        MediaQueueItem.Builder(mediaInfo(track, url)).setAutoplay(autoplay).setPreloadTime(30.0).build()

    private fun nextQueueItem(): QueuedTrack? {
        val track = service?.nextCastTrack() ?: return null
        val url = server?.getStreamUrl(track) ?: return null
        server?.prepareNext(url, track)
        return QueuedTrack(track, url)
    }

    /** Retain only the current item and any future items on the receiver. */
    private fun removePlayedQueueItems(client: RemoteMediaClient) {
        val status = client.mediaStatus ?: return
        val current = status.currentItemId
        val items = status.queueItems
        val currentIndex = items.indexOfFirst { it.itemId == current }
        if (currentIndex <= 0) return
        val old = items.take(currentIndex).map { it.itemId }.toIntArray()
        runCatching { client.queueRemoveItems(old, null) }
    }

    private fun appendNext(client: RemoteMediaClient) {
        if (!isCasting || pendingLoad || queuedNext != null || queuedInsertPending) return
        val next = nextQueueItem() ?: return
        queuedNext = next
        queuedInsertPending = true
        val sequence = loadSequence
        try {
            client.queueAppendItem(queueItem(next.track, next.url, true), null).setResultCallback { result ->
                if (sequence != loadSequence || remote !== client || queuedNext !== next) return@setResultCallback
                queuedInsertPending = false
                if (!result.status.isSuccess) {
                    queuedNext = null
                    com.infinifolder.storage.DiagnosticLog.record("cast.queue.append.failed",
                        "file=${next.track.fileName} status=${result.status.statusCode}")
                }
                if (refreshQueueAfterLoad) { refreshQueueAfterLoad = false; refreshQueue() }
            }
        } catch (error: Exception) {
            queuedInsertPending = false
            queuedNext = null
            com.infinifolder.storage.DiagnosticLog.record("cast.queue.append.failed", "file=${next.track.fileName}", error)
        }
    }

    fun onQueueChanged() { main.post { refreshQueue() } }

    private fun refreshQueue() {
        if (!isCasting) return
        if (pendingLoad || queuedInsertPending) { refreshQueueAfterLoad = true; return }
        if (continuousTimeline != null) {
            val track = service?.currentTrack ?: return
            load(track, positionMs, isRemotePlaying, initial = false)
            return
        }
        val desired = service?.nextCastTrack()
        if (desired?.let(::key) == queuedNext?.track?.let(::key)) return
        val track = service?.currentTrack ?: return
        // Rebuild only when the next item changes. This also removes a stale
        // receiver item when shuffle, repeat or the playlist is edited.
        load(track, positionMs, isRemotePlaying, initial = false)
    }

    private fun load(track: AudioTrack, position: Long, autoplay: Boolean, initial: Boolean) {
        val player = service ?: return
        val client = remote ?: return
        val mediaServer = server ?: CastMediaServer(player.applicationContext).also { server = it }
        if (!mediaServer.start()) { player.onCastError("Could not start audio stream"); if (initial) castContext?.sessionManager?.endCurrentSession(true); return }
        prepareTask?.cancel(true)
        val sequence = ++loadSequence
        pendingLoad = true
        queuedInsertPending = false
        queuedNext = null
        val selections = player.continuousCastSelections()
        val candidates = selections.map { it.track }.ifEmpty { listOf(track) }
        val timelineIndexes = selections.map { it.playlistIndex }.ifEmpty { listOf(player.currentIndex) }
        val task = mediaServer.prepareContinuous(candidates, { sequence != loadSequence || remote !== client }) { result ->
            main.post {
                if (sequence != loadSequence || remote !== client) {
                    result.getOrNull()?.close()
                    return@post
                }
                prepareTask = null
                val prepared = result.getOrNull()
                if (prepared == null) {
                    com.infinifolder.storage.DiagnosticLog.record("cast.continuous.prepare.failed", "file=${track.fileName}", result.exceptionOrNull())
                    loadPerTrack(track, position, autoplay, initial, sequence, player, client, mediaServer)
                    return@post
                }
                val timeline = prepared.timeline.map { item ->
                    item.copy(playlistIndex = timelineIndexes.getOrElse(item.sourceOrdinal) { -1 })
                }
                val selectedEntry = timeline.firstOrNull { it.sourceOrdinal == 0 } ?: timeline.first()
                val selected = selectedEntry.track
                val url = mediaServer.registerContinuous(prepared)
                if (url == null) {
                    val fallback = selected
                    loadPerTrack(fallback, if (sameTrack(fallback, track)) position else 0L,
                        autoplay, initial, sequence, player, client, mediaServer)
                    return@post
                }
                queuedNext = null
                val startAt = selectedEntry.startMs +
                    if (selectedEntry.sourceOrdinal == 0 && sameTrack(selectedEntry.track, track))
                        position.coerceAtLeast(0).coerceAtMost(selectedEntry.durationMs) else 0L
                val info = continuousMediaInfo(selected, url, durationMsTotal(timeline))
                val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(autoplay)
                    .setCurrentTime(startAt).build()
                com.infinifolder.storage.DiagnosticLog.record("cast.continuous.load.requested",
                    "tracks=${timeline.size} skipped=${prepared.skipped.size} durationMs=${durationMsTotal(timeline)} positionMs=$startAt")
                client.load(request).setResultCallback { response ->
                    if (sequence != loadSequence || remote !== client) return@setResultCallback
                    if (response.status.isSuccess) {
                        pendingLoad = false
                        if (!player.onCastContinuousPrepared(timeline, prepared.skipped)) {
                            continuousTimeline = null
                            mediaServer.retire(url)
                            refreshQueueAfterLoad = true
                            refreshQueue()
                            return@setResultCallback
                        }
                        val oldUrl = activeUrl
                        continuousTimeline = timeline
                        lastLoadedKey = key(selectedEntry.track); completedKey = null
                        activeUrl = url; failedUrl = null
                        updateReceiverPosition(startAt)
                        player.setCastAnalysisSource(mediaServer.analysisResource(url))
                        isCasting = true; isRemotePlaying = autoplay
                        updateContinuousPosition(startAt)
                        if (initial) player.pauseLocalForCast()
                        player.startCastMetadataScan()
                        com.infinifolder.storage.DiagnosticLog.record("cast.continuous.load.accepted",
                            "tracks=${timeline.size} status=${response.status.statusCode}")
                        mediaServer.retire(oldUrl)
                        updateListeners(); player.onCastStateUpdate(isRemotePlaying)
                        remoteCallback.onStatusUpdated()
                        if (refreshQueueAfterLoad) { refreshQueueAfterLoad = false; refreshQueue() }
                    } else {
                        mediaServer.retire(url)
                        continuousTimeline = null
                        com.infinifolder.storage.DiagnosticLog.record("cast.continuous.load.rejected",
                            "status=${response.status.statusCode} message=${response.status.statusMessage}")
                        val fallback = player.currentTrack ?: track
                        loadPerTrack(fallback, if (sameTrack(fallback, track)) position else 0L,
                            autoplay, initial, sequence, player, client, mediaServer)
                    }
                }
            }
        }
        if (task == null) {
            loadPerTrack(track, position, autoplay, initial, sequence, player, client, mediaServer)
        } else prepareTask = task
    }

    private fun loadPerTrack(track: AudioTrack, position: Long, autoplay: Boolean, initial: Boolean,
        sequence: Long, player: AudioPlayerService, client: RemoteMediaClient, mediaServer: CastMediaServer) {
        if (sequence != loadSequence || remote !== client) return
        continuousTimeline = null
        val url = mediaServer.getStreamUrl(track) ?: run {
            pendingLoad = false
            player.onCastError("Connect to Wi-Fi to cast audio")
            if (initial) castContext?.sessionManager?.endCurrentSession(true)
            return
        }
        val info = mediaInfo(track, url)
        val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(autoplay)
            .setCurrentTime(position.coerceAtLeast(0)).build()
        com.infinifolder.storage.DiagnosticLog.record(
            "cast.load.requested",
            "file=${track.fileName} mime=${AudioTranscoder.getMimeType(track.uri, track.fileName)} autoplay=$autoplay positionMs=${position.coerceAtLeast(0)} durationMs=${track.durationMs}"
        )
        val requestedKey = key(track)
        queuedInsertPending = false
        val next = nextQueueItem()
        queuedNext = next
        val items = mutableListOf(queueItem(track, url, autoplay))
        if (next != null) items.add(queueItem(next.track, next.url, true))
        fun complete(result: RemoteMediaClient.MediaChannelResult) {
            if (sequence != loadSequence || remote !== client) return
            pendingLoad = false
            if (result.status.isSuccess) {
                com.infinifolder.storage.DiagnosticLog.record(
                    "cast.load.accepted",
                    "file=${track.fileName} status=${result.status.statusCode} message=${result.status.statusMessage} autoplay=$autoplay"
                )
                val oldUrl = activeUrl
                player.setCastAnalysisSource(null)
                lastLoadedKey = requestedKey; completedKey = null
                activeUrl = url; failedUrl = null
                mediaServer.retire(oldUrl)
                isCasting = true; isRemotePlaying = autoplay; positionMs = position
                updateReceiverPosition(position)
                if (initial) player.pauseLocalForCast()
                player.startCastMetadataScan()
                updateListeners(); player.onCastStateUpdate(isRemotePlaying)
                remoteCallback.onStatusUpdated()
                if (refreshQueueAfterLoad) { refreshQueueAfterLoad = false; refreshQueue() }
            } else {
                com.infinifolder.storage.DiagnosticLog.record(
                    "cast.load.rejected",
                    "file=${track.fileName} status=${result.status.statusCode} message=${result.status.statusMessage}"
                )
                if (initial) {
                    player.onCastError("Cast device could not load this track")
                    castContext?.sessionManager?.endCurrentSession(true)
                    if (resumeAfterFailure && !player.isPlaying) player.play()
                } else player.onCastTrackFailed(track)
            }
        }
        try {
            client.queueLoad(items.toTypedArray(), 0, MediaStatus.REPEAT_MODE_REPEAT_OFF,
                position.coerceAtLeast(0), null).setResultCallback { result ->
                if (sequence != loadSequence || remote !== client) return@setResultCallback
                if (result.status.isSuccess) complete(result)
                else {
                    queuedNext = null
                    com.infinifolder.storage.DiagnosticLog.record("cast.queue.load.failed",
                        "file=${track.fileName} status=${result.status.statusCode}")
                    client.load(request).setResultCallback { complete(it) }
                }
            }
        } catch (error: Exception) {
            queuedNext = null
            com.infinifolder.storage.DiagnosticLog.record("cast.queue.load.failed", "file=${track.fileName}", error)
            client.load(request).setResultCallback { complete(it) }
        }
    }
    fun onTrackChanged(track: AudioTrack?) {
        if (track == null) return
        main.post { if (isCasting) load(track, 0, true, initial = false) }
    }
    fun play() { main.post { remote?.play() } }
    fun pause() { main.post { remote?.pause() } }
    fun togglePlayPause() { main.post { remote?.togglePlayback() } }
    fun seek(position: Long) { main.post { remote?.seek(seekPosition(position)) } }
    fun disconnect() { main.post { castContext?.sessionManager?.endCurrentSession(true) } }
    fun stopCasting() {
        suppressResume = true
        val stop = { remote?.stop(); castContext?.sessionManager?.endCurrentSession(true); Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else main.post { stop() }
    }
}
