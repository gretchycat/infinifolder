package com.infinifolder.audio.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.infinifolder.viewer.AudioPlayerService
import com.infinifolder.viewer.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList

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

    fun addListener(listener: Listener) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun updateListeners() {
        listeners.forEach { it.onCastStateChanged(isCasting, deviceName); it.onCastPlaybackStateChanged(isRemotePlaying) }
    }

    private val progress = RemoteMediaClient.ProgressListener { position, duration ->
        positionMs = position.coerceAtLeast(0)
        durationMs = duration.coerceAtLeast(0)
        service?.onCastProgress(positionMs, durationMs)
    }
    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = remote ?: return
            isRemotePlaying = client.isPlaying || client.isBuffering
            positionMs = client.approximateStreamPosition.coerceAtLeast(0)
            durationMs = client.streamDuration.coerceAtLeast(0)
            listeners.forEach { it.onCastPlaybackStateChanged(isRemotePlaying) }
            if (isCasting) service?.onCastStateUpdate(isRemotePlaying)
            val key = lastLoadedKey
            if (!pendingLoad && isCasting && client.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                client.idleReason == MediaStatus.IDLE_REASON_FINISHED && key != null && completedKey != key) {
                completedKey = key
                service?.onCastTrackComplete()
            }
        }
    }
    private fun attach(session: CastSession) {
        remote?.unregisterCallback(remoteCallback)
        remote?.removeProgressListener(progress)
        remote = session.remoteMediaClient
        remote?.registerCallback(remoteCallback)
        remote?.addProgressListener(progress, 1000)
        deviceName = session.castDevice?.friendlyName
    }
    private fun connect(session: CastSession, resumed: Boolean) {
        attach(session)
        val player = service ?: return
        if (resumed && remote?.hasMediaSession() == true) {
            positionMs = remote?.approximateStreamPosition ?: 0L
            isRemotePlaying = remote?.isPlaying == true
            isCasting = true
            player.pauseLocalForCast()
            lastLoadedKey = player.currentTrack?.let(::key)
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
            pendingLoad = false
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
        remote = null; server?.stop(); server = null; service = null; castContext = null
        isCasting = false; isRemotePlaying = false; lastLoadedKey = null; deviceName = null
    }
    private fun key(track: AudioTrack) = "${track.uri}:${track.startByte}:${track.lengthBytes}"
    private fun load(track: AudioTrack, position: Long, autoplay: Boolean, initial: Boolean) {
        val player = service ?: return
        val client = remote ?: return
        val mediaServer = server ?: CastMediaServer(player.applicationContext).also { server = it }
        if (!mediaServer.start()) { player.onCastError("Could not start audio stream"); if (initial) castContext?.sessionManager?.endCurrentSession(true); return }
        val url = mediaServer.getStreamUrl(track) ?: run { player.onCastError("Connect to Wi-Fi to cast audio"); if (initial) castContext?.sessionManager?.endCurrentSession(true); return }
        val name = track.uri.lastPathSegment ?: track.title
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title)
            track.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
            track.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
        }
        val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(AudioTranscoder.getMimeType(track.uri, name)).setMetadata(meta)
            .apply { if (track.durationMs > 0) setStreamDuration(track.durationMs) }.build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(autoplay)
            .setCurrentTime(position.coerceAtLeast(0)).build()
        val requestedKey = key(track)
        pendingLoad = true
        val sequence = ++loadSequence
        client.load(request).setResultCallback { result ->
            if (sequence != loadSequence || remote !== client) return@setResultCallback
            pendingLoad = false
            if (result.status.isSuccess) {
                lastLoadedKey = requestedKey; completedKey = null
                isCasting = true; isRemotePlaying = autoplay; positionMs = position
                if (initial) player.pauseLocalForCast()
                updateListeners(); player.onCastStateUpdate(isRemotePlaying)
            } else {
                player.onCastError("Cast device could not load this track")
                if (initial) {
                    castContext?.sessionManager?.endCurrentSession(true)
                    if (resumeAfterFailure && !player.isPlaying) player.play()
                }
            }
        }
    }
    fun onTrackChanged(track: AudioTrack?) {
        if (track == null) return
        main.post { if (isCasting) load(track, 0, true, initial = false) }
    }
    fun play() { main.post { remote?.play() } }
    fun pause() { main.post { remote?.pause() } }
    fun togglePlayPause() { main.post { remote?.togglePlayback() } }
    fun seek(position: Long) { main.post { remote?.seek(position.coerceAtLeast(0)) } }
    fun disconnect() { main.post { castContext?.sessionManager?.endCurrentSession(true) } }
    fun stopCasting() {
        suppressResume = true
        val stop = { remote?.stop(); castContext?.sessionManager?.endCurrentSession(true); Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else main.post { stop() }
    }
}
