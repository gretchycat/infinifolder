// AudioPlayerService.kt - Fixed version
package com.infinifolder.viewer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import com.infinifolder.audio.AudioMetadataSource
import com.infinifolder.audio.AudioProcessing
import com.infinifolder.audio.AudioTags
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.LruCache
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class RepeatMode { NONE, ALL, ONE }

/** Media kind is persisted with the URI so a mixed playlist survives process death. */
enum class PlaylistMediaKind { AUDIO, IMAGE, VIDEO }

data class AudioTrack(
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artwork: Bitmap? = null,
    val startByte: Long = 0L,
    val lengthBytes: Long = -1L,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val isBitrateRelevant: Boolean = true,
    val fileName: String = title,
    val isUnreadable: Boolean = false,
    /** Explicit decoder hint for raw audio carried in CUE/cdrdao images. */
    val decoderExtension: String? = null,
    /** URI of a sibling cover image, resolved through InfiniFolder's storage provider. */
    val directoryCoverUri: Uri? = null,
    /** Identity and display name of the containing directory, used for playlist image entries. */
    val directoryUri: Uri? = null,
    val directoryName: String? = null,
    /** Directory cover independent of embedded per-track artwork. */
    val directoryArtwork: Bitmap? = null,
    /** True when recursive selection already checked the directory for a cover URI. */
    val directoryArtworkMetadataResolved: Boolean = false,
    val mediaKind: PlaylistMediaKind = PlaylistMediaKind.AUDIO,
    val sourceMimeType: String? = null
)

internal data class CastPlaylistSelection(val playlistIndex: Int, val track: AudioTrack)

interface AudioPlayerListener {
    fun onTrackChanged(track: AudioTrack?, index: Int, total: Int)
    fun onPlaybackStateChanged(isPlaying: Boolean, isLoading: Boolean)
    fun onProgressUpdated(currentPositionMs: Int, durationMs: Int)
    fun onPlaylistUpdated(playlist: List<AudioTrack>)
    fun onError(message: String)
    fun onPlaybackComplete()
    fun onAudioSessionIdChanged(audioSessionId: Int) {}
}

class AudioPlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "infinifolder_audio_playback"
        const val NOTIFICATION_ID = 3001
        const val ACTION_PLAY = "com.infinifolder.viewer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.infinifolder.viewer.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.infinifolder.viewer.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.infinifolder.viewer.ACTION_NEXT"
        const val ACTION_PREV = "com.infinifolder.viewer.ACTION_PREV"
        const val ACTION_STOP = "com.infinifolder.viewer.ACTION_STOP"
        const val ACTION_CLOSE = "com.infinifolder.viewer.ACTION_CLOSE"
        const val ACTION_LOAD = "com.infinifolder.viewer.ACTION_LOAD"
        const val ACTION_RESTORE = "com.infinifolder.viewer.ACTION_RESTORE"
        private const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        private val listeners = mutableListOf<AudioPlayerListener>()
        @Volatile var currentService: AudioPlayerService? = null
            private set
        fun addListener(l: AudioPlayerListener) = synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }
        fun removeListener(l: AudioPlayerListener) = synchronized(listeners) { listeners.remove(l) }
        fun uriMatches(u1: Uri?, u2: Uri?): Boolean {
            if (u1 == null || u2 == null) return false
            if (u1 == u2 || u1.toString() == u2.toString()) return true
            val a1 = com.infinifolder.archive.ArchiveUri.isArchiveUri(u1)
            val a2 = com.infinifolder.archive.ArchiveUri.isArchiveUri(u2)
            if (a1 || a2) {
                if (!a1 || !a2) return false
                // Archive identity lives in source/path query parameters. Its
                // empty URI path must never alias every other archive entry.
                return runCatching {
                    com.infinifolder.archive.ArchiveUri.getEntryPath(u1) == com.infinifolder.archive.ArchiveUri.getEntryPath(u2) &&
                        com.infinifolder.archive.ArchiveUri.isDeletedView(u1) == com.infinifolder.archive.ArchiveUri.isDeletedView(u2) &&
                        uriMatches(com.infinifolder.archive.ArchiveUri.getSourceUri(u1), com.infinifolder.archive.ArchiveUri.getSourceUri(u2))
                }.getOrDefault(false)
            }
            val p1 = u1.path?.let { Uri.decode(it) }?.trimEnd('/')
            val p2 = u2.path?.let { Uri.decode(it) }?.trimEnd('/')
            if (p1 != null && p2 != null) {
                val sub1 = p1.removePrefix("/shared").removePrefix("/root")
                val sub2 = p2.removePrefix("/shared").removePrefix("/root")
                if (sub1.isEmpty() || sub2.isEmpty()) return false
                if (sub1.isNotEmpty() && sub1 == sub2) return true
                if (p1.endsWith(sub2) || p2.endsWith(sub1)) return true
            }
            return false
        }
    }

    @Volatile var cacheFailure: Pair<Throwable, Uri>? = null

    private val playerLock = Any()
    private val progressLock = Any()
    private val directoryCoverCache = Collections.synchronizedMap(object : LinkedHashMap<String, List<Uri>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Uri>>?): Boolean = size > 128
    })
    private val directoryArtworkInfoCache = Collections.synchronizedMap(object : LinkedHashMap<String, MediaSourceAdapter.AudioDirectoryArtworkInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaSourceAdapter.AudioDirectoryArtworkInfo>?): Boolean = size > 256
    })
    private val directoryArtworkBitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private var mediaPlayer: MediaPlayer? = null
    private var currentStream: java.io.FileInputStream? = null
    private var currentPcmSource: com.infinifolder.audio.AudioWaveSource? = null
    private data class PendingSeek(val playerId: Int, val targetMs: Int, val startedMs: Long)
    private var pendingSeek: PendingSeek? = null // Guarded by playerLock; Android may coalesce seeks.
    private var currentPfd: android.os.ParcelFileDescriptor? = null
    private var currentDecodedFile: File? = null
    private val gaplessExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "AudioGaplessPreload").apply { isDaemon = true }
    }
    private var gaplessWork: java.util.concurrent.Future<*>? = null
    private var gaplessRequestedTrack: AudioTrack? = null
    private var gaplessGeneration = 0L
    private var preparedNext: GaplessPrepared? = null

    private class GaplessPrepared(
        val track: AudioTrack,
        val player: MediaPlayer,
        val source: AudioPlaybackInput,
        val stagedFile: File?,
        val playbackFile: File,
        val decodedFile: File?,
        val decodeEngine: String,
        val pcmSource: com.infinifolder.audio.AudioWaveSource
    ) {
        fun release() {
            runCatching { pcmSource.close() }
            runCatching { player.release() }
            runCatching { decodedFile?.delete() }
            if (stagedFile != null && stagedFile != source.file) runCatching { stagedFile?.delete() }
            runCatching { source.close() }
        }
    }
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var focusRequest: AudioFocusRequest? = null
    var mediaSession: MediaSession? = null
        private set

    val playlist = mutableListOf<AudioTrack>()
    private val originalPlaylist = mutableListOf<AudioTrack>()
    private var hasManualPlaylistOrder = false
    var currentIndex: Int = -1
        private set
    var isShuffleEnabled: Boolean = false
        private set
    var repeatMode: RepeatMode = RepeatMode.NONE
        private set
    var playbackSpeed: Float = 1.0f
        private set
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolumeAndBalance()
        }
    var balance: Float = 0.0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            applyVolumeAndBalance()
        }
    @Volatile var isLoading: Boolean = false
        private set

    val isPlaying: Boolean
        get() = if (com.infinifolder.audio.cast.CastManager.isCasting) com.infinifolder.audio.cast.CastManager.isRemotePlaying
            else synchronized(playerLock) { runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false) }
    val currentPosition: Int
        get() = if (com.infinifolder.audio.cast.CastManager.isCasting) com.infinifolder.audio.cast.CastManager.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else synchronized(playerLock) { runCatching { mediaPlayer?.currentPosition ?: 0 }.getOrDefault(0) }
    val currentDuration: Int
        get() = if (com.infinifolder.audio.cast.CastManager.isCasting) com.infinifolder.audio.cast.CastManager.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else synchronized(playerLock) { runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0) }
    data class AnalysisSource(val id: Long, val path: String,
        val resource: io.github.gretchycat.infinistream.SourceResource? = null)
    @Volatile var analysisSource: AnalysisSource? = null
        private set

    /** Use the active composite PCM Cast source for the same analysis pipeline as local playback. */
    internal fun setCastAnalysisSource(resource: io.github.gretchycat.infinistream.SourceResource?) {
        analysisSource = resource?.let {
            AnalysisSource(analysisSourceCounter.incrementAndGet(), "", it)
        }
    }

    val audioSessionId: Int
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.audioSessionId ?: 0 }.getOrDefault(0) }
    val currentTrack: AudioTrack?
        get() = synchronized(playlist) { if (currentIndex in playlist.indices) playlist[currentIndex] else null }

    fun applyVolumeAndBalance() {
        val left = volume * (if (balance > 0f) 1f - balance else 1f)
        val right = volume * (if (balance < 0f) 1f + balance else 1f)
        synchronized(playerLock) {
            runCatching { mediaPlayer?.setVolume(left, right) }
            runCatching { preparedNext?.player?.setVolume(left, right) }
        }
    }

    private var progressThread: Thread? = null
    private var isProgressRunning = false
    private var stagedTempFile: File? = null
    private var stagedSource: AudioPlaybackInput? = null
    private val loadCounter = AtomicLong(0L)
    private val analysisSourceCounter = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playlistStore by lazy { AudioPlaylistStore(applicationContext) }
    private val playlistPersistenceLock = Any()
    private val playlistPersistenceExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "AudioPlaylistPersistence").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var pendingPlaylistPersistence: ScheduledFuture<*>? = null
    private var lastPositionPersistElapsed = 0L
    @Volatile private var restoredPlaylist = false
    @Volatile private var restorePositionTrack: AudioTrack? = null
    @Volatile private var restorePositionMs: Int? = null
    @Volatile private var lastKnownPositionMs = 0
    private var isForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        currentService = this
        AudioProcessing.initialize(applicationContext)
        MidiSoundFontSettings.apply(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        loadPlaybackPrefs()
        restorePlaylistState()
        ensureForegroundService()
        try {
            mediaSession = MediaSession(this, "InfiniFolderAudio").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { play() }
                    override fun onPause() { pause() }
                    override fun onSkipToNext() { playNext() }
                    override fun onSkipToPrevious() { playPrevious() }
                    override fun onStop() { stopPlayback() }
                    override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                })
                isActive = true
            }
        } catch (_: Exception) {}
        com.infinifolder.audio.cast.CastManager.initialize(applicationContext, this)
        if (restoredPlaylist) {
            notifyPlaylistUpdated()
            notifyTrackChanged(currentTrack)
            updateNotification()
        }
        com.infinifolder.ops.AppOperations.coordinator.onCancelExternal = { id ->
            if (id == "playback_audio") stopPlayback()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundService()
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_STOP -> stopPlayback()
            ACTION_CLOSE -> {
                stopPlayback()
                stopSelf()
            }
            ACTION_LOAD -> {
                val uriStr = intent.getStringExtra("uri")
                val name = intent.getStringExtra("name") ?: "Audio Track"
                val mimeType = intent.getStringExtra("mimeType") ?: intent.type
                uriStr?.let {
                    val uri = Uri.parse(it)
                    // Activity expands playlist references into the queue. Do not first
                    // treat the playlist document itself as an audio track; that races
                    // queue initialization and sends XML/JSON bytes to the audio decoder.
                    if (!PlaylistExpander.isPlaylist(uri, name, mimeType)) {
                        val cur = currentTrack
                        if (cur == null || !uriMatches(cur.uri, uri)) {
                            val kind = when (MediaTypeDetector.detect(name, mimeType)) {
                                MediaType.IMAGE -> PlaylistMediaKind.IMAGE
                                MediaType.VIDEO -> PlaylistMediaKind.VIDEO
                                else -> PlaylistMediaKind.AUDIO
                            }
                            setPlaylist(listOf(AudioTrack(uri, name, fileName = name,
                                mediaKind = kind, sourceMimeType = mimeType)), 0)
                        }
                    }
                }
            }
            ACTION_RESTORE -> if (!restoredPlaylist && synchronized(playlist) { playlist.isEmpty() }) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and status for audio playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sortPlaylistLocked() {
        currentIndex = sortPlaylistByFileName(playlist, currentIndex, { it.fileName }, { it.startByte })
    }

    private fun restorePlaylistState() {
        val saved = playlistStore.load() ?: return
        synchronized(playlist) {
            playlist.clear()
            playlist.addAll(saved.tracks)
            originalPlaylist.clear()
            originalPlaylist.addAll(saved.originalTracks)
            currentIndex = saved.currentIndex
            hasManualPlaylistOrder = saved.hasManualOrder
            restorePositionTrack = playlist.getOrNull(currentIndex)
            restorePositionMs = saved.positionMs
            lastKnownPositionMs = saved.positionMs
            restoredPlaylist = playlist.isNotEmpty()
        }
    }

    /** Debounced durable snapshot of queue order, selection, and play position. */
    private fun persistPlaylistState(positionMs: Int? = null, immediate: Boolean = false) {
        val active = currentTrack
        val restoredPosition = restorePositionTrack?.takeIf { saved -> active?.let { sameTrack(it, saved) } == true }
            ?.let { restorePositionMs }
        val hasPlayer = synchronized(playerLock) { mediaPlayer != null }
        val observedPosition = if (hasPlayer || com.infinifolder.audio.cast.CastManager.isCasting)
            currentPosition else lastKnownPositionMs
        val position = (positionMs ?: restoredPosition ?: observedPosition).coerceAtLeast(0)
        lastKnownPositionMs = if (playlist.isEmpty()) 0 else position
        val snapshot = synchronized(playlist) {
            SavedAudioPlaylist(
                tracks = playlist.map { it.copy(artwork = null, directoryArtwork = null) },
                originalTracks = originalPlaylist.map { it.copy(artwork = null, directoryArtwork = null) },
                currentIndex = currentIndex,
                positionMs = position,
                hasManualOrder = hasManualPlaylistOrder
            )
        }
        synchronized(playlistPersistenceLock) {
            if (playlistPersistenceExecutor.isShutdown) return
            pendingPlaylistPersistence?.cancel(false)
            val task = try {
                playlistPersistenceExecutor.schedule({
                    try {
                        playlistStore.save(snapshot)
                    } catch (error: Exception) {
                        com.infinifolder.storage.DiagnosticLog.record("audio.playlist.persist.failed", error = error)
                    }
                }, if (immediate) 0L else 300L, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) { return }
            pendingPlaylistPersistence = task
            if (immediate) runCatching { task.get(3, TimeUnit.SECONDS) }
        }
    }

    private fun restoredPositionFor(track: AudioTrack): Int? {
        val restoredTrack = restorePositionTrack ?: return null
        if (!sameTrack(track, restoredTrack)) return null
        return restorePositionMs
    }

    private fun sameTrack(a: AudioTrack, b: AudioTrack): Boolean =
        uriMatches(a.uri, b.uri) && a.startByte == b.startByte && a.lengthBytes == b.lengthBytes

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0, preservePlaylistOrder: Boolean = false) {
        restorePositionTrack = null
        restorePositionMs = null
        restoredPlaylist = false
        invalidateGapless()
        resetCastFailures()
        val targetTrack = tracks.getOrNull(startIndex)
        val selectedPlaylistFile = targetTrack?.let { PlaylistExpander.isPlaylist(it.uri, it.title) } == true
        val expanded = mutableListOf<AudioTrack>()
        for (t in tracks) {
            if (PlaylistExpander.isPlaylist(t.uri, t.title)) expanded.addAll(PlaylistExpander.expandPlaylist(this, t.uri))
            else expanded.add(t)
        }
        val effectiveIndex = targetTrack?.let { target -> expanded.indexOfFirst { sameTrack(it, target) } }
            ?.takeIf { it >= 0 } ?: startIndex.coerceIn(0, (expanded.size - 1).coerceAtLeast(0))
        synchronized(playlist) {
            originalPlaylist.clear(); originalPlaylist.addAll(expanded)
            playlist.clear(); playlist.addAll(expanded)
            hasManualPlaylistOrder = false
            currentIndex = if (playlist.isEmpty()) -1 else effectiveIndex
            if (isShuffleEnabled && currentIndex >= 0) {
                val initial = playlist.removeAt(currentIndex)
                playlist.shuffle()
                playlist.add(0, initial)
                currentIndex = 0
            } else if (!isShuffleEnabled) {
                // Explicit playlists carry their own ordering. Keep XSPF/JSPF/M3U
                // sequence intact; ordinary directory queues retain filename sort.
                if (preservePlaylistOrder || selectedPlaylistFile) {
                    if (playlist.isNotEmpty()) currentIndex = effectiveIndex.coerceIn(0, playlist.lastIndex)
                } else {
                    sortPlaylistLocked()
                }
            }
        }
        directoryCoverCache.clear()
        directoryArtworkInfoCache.clear()
        directoryArtworkBitmapCache.evictAll()
        notifyPlaylistUpdated()
        stopBackgroundMetadataScanner()
        if (playlist.isNotEmpty()) loadAndPlayCurrent()
    }

    fun updatePlaylistKeepCurrent(tracks: List<AudioTrack>, currentUri: Uri) {
        invalidateGapless()
        val expanded = mutableListOf<AudioTrack>()
        for (t in tracks) {
            if (PlaylistExpander.isPlaylist(t.uri, t.title)) expanded.addAll(PlaylistExpander.expandPlaylist(this, t.uri))
            else expanded.add(t)
        }
        synchronized(playlist) {
            val active = playlist.getOrNull(currentIndex)
            val refreshed = retainUnreadableState(playlist, expanded)
            originalPlaylist.clear(); originalPlaylist.addAll(refreshed)
            playlist.clear(); playlist.addAll(refreshed)
            val idx = if (active != null && uriMatches(active.uri, currentUri)) {
                playlist.indexOfFirst { sameTrack(it, active) }
            } else playlist.indexOfFirst { uriMatches(it.uri, currentUri) }
            if (idx >= 0) {
                currentIndex = idx
                if (active != null && sameTrack(playlist[idx], active)) {
                    playlist[idx] = active
                    val originalIndex = originalPlaylist.indexOfFirst { sameTrack(it, active) }
                    if (originalIndex >= 0) originalPlaylist[originalIndex] = active
                }
            } else if (active != null) {
                playlist.add(active); originalPlaylist.add(active); currentIndex = playlist.lastIndex
            } else {
                currentIndex = if (playlist.isEmpty()) -1 else 0
            }
            if (!isShuffleEnabled) sortPlaylistLocked()
            else if (currentIndex >= 0) {
                val selected = playlist.removeAt(currentIndex)
                playlist.shuffle(); playlist.add(0, selected); currentIndex = 0
            }
        }
        directoryCoverCache.clear()
        directoryArtworkInfoCache.clear()
        directoryArtworkBitmapCache.evictAll()
        notifyPlaylistUpdated()
        stopBackgroundMetadataScanner()
        startBackgroundMetadataScanner()
        scheduleGapless()
    }

    fun enqueue(track: AudioTrack) {
        if (PlaylistExpander.isPlaylist(track.uri, track.title)) {
            enqueueAll(PlaylistExpander.expandPlaylist(this, track.uri))
        } else enqueueAll(listOf(track))
    }

    fun enqueueAll(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        invalidateGapless()
        val wasEmpty = synchronized(playlist) {
            val empty = playlist.isEmpty()
            playlist.addAll(tracks)
            originalPlaylist.addAll(tracks)
            // Enqueue is an append operation: preserve the active queue's order and
            // place the submitted batch after its current end. Initial playlist loads
            // still apply sorting/shuffle policy in setPlaylist().
            if (empty) currentIndex = 0
            empty
        }
        notifyPlaylistUpdated()
        if (mediaPlayer == null && wasEmpty) {
            stopBackgroundMetadataScanner()
            loadAndPlayCurrent()
        } else startBackgroundMetadataScanner(forceRestart = false)
        if (!wasEmpty) scheduleGapless()
    }

    /** Remove one visible queue entry without disturbing the active track unless it was selected. */
    fun removePlaylistItem(index: Int): Boolean {
        invalidateGapless()
        val removedCurrent: Boolean
        val isEmpty: Boolean
        synchronized(playlist) {
            if (index !in playlist.indices) return false
            val removed = playlist.removeAt(index)
            val originalIndex = originalPlaylist.indexOfFirst { sameTrack(it, removed) }
            if (originalIndex >= 0) originalPlaylist.removeAt(originalIndex)
            removedCurrent = index == currentIndex
            if (playlist.isEmpty()) {
                currentIndex = -1
            } else if (index < currentIndex) {
                currentIndex--
            } else if (removedCurrent) {
                currentIndex = index.coerceAtMost(playlist.lastIndex)
            }
            isEmpty = playlist.isEmpty()
        }
        if (isEmpty) {
            notifyPlaylistUpdated()
            stopPlayback()
        } else if (removedCurrent) {
            loadAndPlayCurrent()
        } else {
            notifyPlaylistUpdated()
            startBackgroundMetadataScanner(forceRestart = false)
            scheduleGapless()
        }
        return true
    }

    /** Clear the persisted working queue and stop local or remote playback. */
    fun clearPlaylist(): Boolean {
        invalidateGapless()
        val hadTracks = synchronized(playlist) {
            val nonEmpty = playlist.isNotEmpty()
            playlist.clear()
            originalPlaylist.clear()
            currentIndex = -1
            hasManualPlaylistOrder = false
            nonEmpty
        }
        if (!hadTracks) return false
        stopBackgroundMetadataScanner()
        notifyPlaylistUpdated()
        notifyTrackChanged(null)
        stopPlayback()
        return true
    }

    /** Move a queue entry to its dropped position without restarting the active track. */
    fun movePlaylistItem(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex == toIndex) return false
        if (!synchronized(playlist) { fromIndex in playlist.indices && toIndex in playlist.indices }) return false
        invalidateGapless()
        synchronized(playlist) {
            if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return false
            val oldCurrent = currentIndex
            val moved = playlist.removeAt(fromIndex)
            playlist.add(toIndex, moved)
            currentIndex = when {
                oldCurrent == fromIndex -> toIndex
                fromIndex < oldCurrent && toIndex >= oldCurrent -> oldCurrent - 1
                fromIndex > oldCurrent && toIndex <= oldCurrent -> oldCurrent + 1
                else -> oldCurrent
            }
            originalPlaylist.clear()
            originalPlaylist.addAll(playlist)
            hasManualPlaylistOrder = true
        }
        notifyPlaylistUpdated()
        scheduleGapless()
        return true
    }

    private fun loadPlaybackPrefs() {
        val prefs = getSharedPreferences("audio_player", Context.MODE_PRIVATE)
        isShuffleEnabled = prefs.getBoolean("shuffle", false)
        repeatMode = runCatching { RepeatMode.valueOf(prefs.getString("repeat", null) ?: "NONE") }
            .getOrDefault(RepeatMode.NONE)
    }

    private fun savePlaybackPrefs() {
        getSharedPreferences("audio_player", Context.MODE_PRIVATE).edit()
            .putBoolean("shuffle", isShuffleEnabled)
            .putString("repeat", repeatMode.name)
            .apply()
    }

    fun toggleShuffle() {
        invalidateGapless()
        isShuffleEnabled = !isShuffleEnabled
        savePlaybackPrefs()
        synchronized(playlist) {
            if (isShuffleEnabled) {
                val selected = if (currentIndex in playlist.indices) playlist.removeAt(currentIndex) else null
                playlist.shuffle()
                if (selected != null) { playlist.add(0, selected); currentIndex = 0 }
                else currentIndex = if (playlist.isEmpty()) -1 else 0
            } else if (hasManualPlaylistOrder) {
                val active = playlist.getOrNull(currentIndex)
                playlist.clear()
                playlist.addAll(originalPlaylist)
                currentIndex = active?.let { selected -> playlist.indexOfFirst { sameTrack(it, selected) } }
                    ?.takeIf { it >= 0 } ?: if (playlist.isEmpty()) -1 else 0
            } else sortPlaylistLocked()
        }
        notifyPlaylistUpdated()
        scheduleGapless()
    }

    fun cycleRepeatMode() {
        invalidateGapless()
        repeatMode = when (repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        savePlaybackPrefs()
        scheduleGapless()
    }

    fun setSpeed(speed: Float) {
        invalidateGapless()
        playbackSpeed = speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            synchronized(playerLock) {
                mediaPlayer?.let { p ->
                    runCatching {
                        val wasPlaying = p.isPlaying
                        p.playbackParams = PlaybackParams().apply { this.speed = speed }
                        if (!wasPlaying) p.pause()
                    }
                }
            }
        }
        scheduleGapless()
    }

    fun jumpTo(index: Int) {
        synchronized(playlist) {
            if (index in playlist.indices) {
                castFailures.remove(castTrackKey(playlist[index]))
                currentIndex = index
                loadAndPlayCurrent()
            }
        }
    }

    fun seekTo(positionMs: Int) {
        if (com.infinifolder.audio.cast.CastManager.isCasting) {
            com.infinifolder.audio.cast.CastManager.seek(positionMs.toLong()); return
        }
        synchronized(playerLock) {
            mediaPlayer?.let { player ->
                pendingSeek = PendingSeek(System.identityHashCode(player), positionMs, android.os.SystemClock.elapsedRealtime())
                com.infinifolder.storage.DiagnosticLog.record("audio.seek.requested",
                    "targetMs=$positionMs durationMs=$currentDuration ${pcmAccessDescription()}")
                runCatching { player.seekTo(positionMs) }.onFailure { error ->
                    pendingSeek = null
                    com.infinifolder.storage.DiagnosticLog.record("audio.seek.failed", "targetMs=$positionMs", error)
                }
            }
        }
        notifyProgress(positionMs, currentDuration)
    }

    /** Snapshot only: reading diagnostics must not wait for a decoder or request PCM. */
    private fun pcmAccessDescription(): String {
        val source = currentPcmSource ?: return "pcmSource=none"
        val attributes = source.metadata.attributes
        return "decoder=${source.preparation.decoder} progressive=${source.progressive} " +
            "access=${attributes["access"]} cachedPcmBytes=${attributes["cachedPcmBytes"]} " +
            "decoderFrameSeeks=${attributes["decoderFrameSeeks"]}"
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        if (currentTrack?.mediaKind != PlaylistMediaKind.AUDIO) return
        if (com.infinifolder.audio.cast.CastManager.isCasting) { com.infinifolder.audio.cast.CastManager.play(); return }
        if (mediaPlayer == null) {
            synchronized(playlist) { if (currentIndex in playlist.indices) loadAndPlayCurrent() }
            return
        }
        requestAudioFocus()
        var started = false
        synchronized(playerLock) {
            started = runCatching { mediaPlayer?.start(); mediaPlayer?.isPlaying == true }.getOrDefault(false)
        }
        if (!started) {
            loadAndPlayCurrent()
            return
        }
        startProgressReporting()
        com.infinifolder.ops.AppOperations.coordinator.updateExternal(
            id = "playback_audio",
            bytes = currentPosition.toLong(),
            message = null
        )
        notifyPlaybackState(isPlaying = true, isLoading = false)
        updateNotification()
        scheduleGapless()
    }

    fun pause() {
        if (com.infinifolder.audio.cast.CastManager.isCasting) { com.infinifolder.audio.cast.CastManager.pause(); return }
        synchronized(playerLock) { runCatching { mediaPlayer?.pause() } }
        persistPlaylistState(immediate = true)
        stopProgressReporting()
        com.infinifolder.ops.AppOperations.coordinator.updateExternal(
            id = "playback_audio",
            bytes = currentPosition.toLong(),
            message = "Paused"
        )
        notifyPlaybackState(isPlaying = false, isLoading = false)
        updateNotification()
    }

    fun playNext() {
        android.util.Log.d("AudioPlayerService", "playNext called, index=$currentIndex size=${playlist.size} repeat=$repeatMode")
        runCatching {
            synchronized(playlist) {
                if (playlist.isEmpty()) return@runCatching
                val wrap = repeatMode == RepeatMode.ALL || playlist.size > 1
                val next = if (com.infinifolder.audio.cast.CastManager.isCasting)
                    nextCastTrackIndex(playlist, currentIndex, if (wrap) RepeatMode.ALL else RepeatMode.NONE, castFailures)
                else nextReadableTrack(playlist, currentIndex, wrap)
                if (next == null) { stopPlayback(); return@runCatching }
                currentIndex = next
                loadAndPlayCurrent()
            }
        }
    }

    fun playPrevious() {
        runCatching {
            synchronized(playlist) {
                if (playlist.isEmpty()) return@runCatching
                if (currentPosition > 3000) {
                    seekTo(0)
                    return@runCatching
                }
                val previous = nextReadableTrack(playlist, currentIndex, repeatMode == RepeatMode.ALL, -1)
                if (previous != null) { currentIndex = previous; loadAndPlayCurrent() }
                else if (currentTrack?.isUnreadable != true) seekTo(0)
            }
        }
    }

    fun stopPlayback() {
        persistPlaylistState(immediate = true)
        loadingThread?.interrupt()
        loadingThread = null
        currentTrackMetadataThread?.interrupt()
        currentTrackMetadataThread = null
        invalidateGapless()
        loadCounter.incrementAndGet()
        analysisSource = null
        notifyAudioSession(0)
        if (com.infinifolder.audio.cast.CastManager.isCasting) com.infinifolder.audio.cast.CastManager.stopCasting()
        stopProgressReporting()
        synchronized(playerLock) {
            runCatching { currentPcmSource?.close() }
            currentPcmSource = null
            runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { currentStream?.close() }
            currentStream = null
            runCatching { currentPfd?.close() }
            currentPfd = null
        }
        abandonAudioFocus()
        cleanupStagedFile()
        com.infinifolder.ops.AppOperations.coordinator.completeExternal("playback_audio")
        notifyPlaybackState(isPlaying = false, isLoading = false)
        if (isForegroundActive) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {}
            isForegroundActive = false
        }
        // Playback is finished/stopped: shut the service down even if a UI is still bound as a listener
        stopSelf()
    }

    private var pendingCastReturn: Pair<Long, Boolean>? = null
    private var preferredOutputId: Int? = null

    fun setOutputDevice(device: android.media.AudioDeviceInfo?): Boolean {
        val accepted = synchronized(playerLock) {
            val ok = mediaPlayer?.setPreferredDevice(device) ?: true
            if (ok) runCatching { preparedNext?.player?.setPreferredDevice(device) }
            ok
        }
        if (accepted) preferredOutputId = device?.id
        return accepted
    }

    fun pauseLocalForCast() {
        invalidateGapless()
        synchronized(playerLock) { runCatching { mediaPlayer?.pause() } }
        stopProgressReporting()
        abandonAudioFocus()
    }
    fun resumeLocalFromCast(position: Long, playing: Boolean) {
        pendingCastReturn = position to playing
        loadAndPlayCurrent()
    }
    fun onCastProgress(position: Long, duration: Long) {
        if (duration > 0) synchronized(playlist) {
            val track = playlist.getOrNull(currentIndex)
            if (track != null && track.durationMs <= 0 && !track.isUnreadable) {
                restoreReadableState(playlist, track, duration)
                restoreReadableState(originalPlaylist, track, duration)
                notifyPlaylistUpdated()
            }
        }
        notifyProgress(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Snapshot playlist positions and playback order for the continuous Cast PCM timeline. */
    internal fun continuousCastSelections(): List<CastPlaylistSelection> = synchronized(playlist) {
        if (currentIndex !in playlist.indices) return@synchronized emptyList()
        val indices = when (repeatMode) {
            RepeatMode.ONE -> listOf(currentIndex)
            RepeatMode.ALL -> (currentIndex until playlist.size).toList() + (0 until currentIndex).toList()
            RepeatMode.NONE -> (currentIndex until playlist.size).toList()
        }
        indices.map { CastPlaylistSelection(it, playlist[it]) }
            .filter { !it.track.isUnreadable && castTrackKey(it.track) !in castFailures }
    }

    /** Keep Cast-only decode failures in this session and align the UI to the first decodable item. */
    internal fun onCastContinuousPrepared(timeline: List<com.infinifolder.audio.cast.AudioTranscoder.TimelineEntry>,
        skipped: List<AudioTrack>): Boolean {
        val selected = synchronized(playlist) {
            skipped.forEach { castFailures.add(castTrackKey(it)) }
            val first = timeline.firstOrNull() ?: return false
            val index = first.playlistIndex.takeIf { it in playlist.indices && sameTrack(playlist[it], first.track) }
                ?: playlist.indexOfFirst { sameTrack(it, first.track) }
            if (index < 0) return false
            val changed = currentIndex != index
            currentIndex = index
            val current = playlist[index]
            val exactDuration = timeline.first().durationMs
            if (current.durationMs <= 0 && exactDuration > 0) {
                val updated = current.copy(durationMs = exactDuration)
                playlist[index] = updated
                val original = originalPlaylist.indexOfFirst { sameTrack(it, current) }
                if (original >= 0) originalPlaylist[original] = updated
            }
            currentIndex to changed
        }
        val current = currentTrack ?: return false
        if (selected.second) notifyTrackChanged(current)
        notifyPlaylistUpdated()
        updateNotification()
        return true
    }

    /** Translate the single receiver stream position into the selected source track's timeline. */
    fun onCastTimelinePosition(playlistIndex: Int, track: AudioTrack, positionMs: Long, durationMs: Long) {
        var changed = false
        synchronized(playlist) {
            val index = playlistIndex.takeIf { it in playlist.indices && sameTrack(playlist[it], track) }
                ?: playlist.indexOfFirst { sameTrack(it, track) }
            if (index >= 0) {
                changed = currentIndex != index
                currentIndex = index
                val current = playlist[index]
                if (current.durationMs <= 0 && durationMs > 0 && !current.isUnreadable) {
                    val updated = current.copy(durationMs = durationMs)
                    playlist[index] = updated
                    val original = originalPlaylist.indexOfFirst { sameTrack(it, current) }
                    if (original >= 0) originalPlaylist[original] = updated
                }
            }
        }
        val current = currentTrack ?: return
        if (changed) {
            notifyTrackChanged(current)
            notifyPlaylistUpdated()
            updateNotification()
        }
        notifyProgress(positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    fun onCastStateUpdate(playing: Boolean) {
        notifyPlaybackState(playing, false)
        updateNotification()
    }
    fun onCastTrackComplete() = onTrackCompleted()
    fun onCastError(message: String) = notifyError(message)

    private val castFailures = mutableSetOf<CastTrackKey>()
    fun resetCastFailures() = synchronized(playlist) { castFailures.clear() }
    fun startCastMetadataScan() { startBackgroundMetadataScanner() }

    private fun automaticCastNextIndex(): Int? = nextCastTrackIndex(playlist, currentIndex, repeatMode, castFailures)

    /** Receiver failures are session-specific; keep the source available for local playback. */
    fun onCastTrackFailed(track: AudioTrack) {
        synchronized(playlist) {
            if (currentTrack?.let { sameTrack(it, track) } != true) return
            castFailures.add(castTrackKey(track))
            val next = nextCastTrackIndex(playlist, currentIndex,
                if (repeatMode == RepeatMode.ALL) RepeatMode.ALL else RepeatMode.NONE, castFailures)
            if (next == null) {
                stopPlayback()
                notifyPlaybackComplete()
            } else {
                currentIndex = next
                loadAndPlayCurrent()
            }
        }
    }

    fun nextCastTrack(): AudioTrack? = synchronized(playlist) {
        automaticCastNextIndex()?.let { playlist[it] }
    }

    /** Prioritize tracks currently visible in the playlist; the scan still covers all others. */
    fun prioritizeMetadataTracks(tracks: List<AudioTrack>) = synchronized(metadataScannerLock) {
        metadataPriorityTracks = tracks.toList()
        metadataWork.prioritize(metadataPriorityTracks)
    }

    /** The receiver has advanced to its queued item; don't send another load. */
    fun onCastAdvanced(track: AudioTrack): Boolean {
        val next = synchronized(playlist) {
            val index = automaticCastNextIndex() ?: return false
            if (!sameTrack(playlist[index], track)) return false
            currentIndex = index
            playlist[index]
        }
        notifyTrackChanged(next)
        notifyPlaylistUpdated()
        updateNotification()
        return true
    }

    @Volatile private var loadingThread: Thread? = null
    @Volatile private var currentTrackMetadataThread: Thread? = null

    private fun loadAndPlayCurrent() {
        loadingThread?.interrupt()
        currentTrackMetadataThread?.interrupt()
        currentTrackMetadataThread = null
        invalidateGapless()
        val track = currentTrack ?: return
        val thisLoadId = loadCounter.incrementAndGet()
        val resumePosition = restoredPositionFor(track)
        restorePositionTrack = null
        restorePositionMs = null
        persistPlaylistState(positionMs = resumePosition ?: 0)
        analysisSource = null
        notifyAudioSession(0)
        if (track.mediaKind != PlaylistMediaKind.AUDIO) {
            stopProgressReporting()
            synchronized(playerLock) {
                runCatching { currentPcmSource?.close() }
                currentPcmSource = null
                runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
                mediaPlayer = null
                runCatching { currentStream?.close() }
                currentStream = null
                runCatching { currentPfd?.close() }
                currentPfd = null
            }
            cleanupStagedFile()
            abandonAudioFocus()
            isLoading = false
            notifyPlaybackState(isPlaying = false, isLoading = false)
            notifyTrackChanged(track)
            notifyProgress(0, track.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            updateNotification()
            startBackgroundMetadataScanner(forceRestart = false)
            return
        }
        if (com.infinifolder.audio.cast.CastManager.isCasting || com.infinifolder.audio.cast.CastManager.hasConnectedSession) {
            isLoading = false
            notifyTrackChanged(track)
            com.infinifolder.audio.cast.CastManager.onTrackChanged(track)
            startCurrentTrackMetadataEnrichment(track, thisLoadId)
            notifyProgress(0, track.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return
        }
        val castReturn = pendingCastReturn.also { pendingCastReturn = null }
        isLoading = true
        notifyPlaybackState(isPlaying = false, isLoading = true)
        notifyTrackChanged(track)
        notifyProgress(0, track.durationMs.toInt())
        stopProgressReporting()
        synchronized(playerLock) {
            runCatching { currentPcmSource?.close() }
            currentPcmSource = null
            runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { currentStream?.close() }
            currentStream = null
            runCatching { currentPfd?.close() }
            currentPfd = null
        }
        loadingThread = Thread {
            if (loadCounter.get() != thisLoadId) return@Thread
            val loadStarted = android.os.SystemClock.elapsedRealtime()
            cacheFailure = null
            val needsLocalRange = track.startByte > 0 || track.lengthBytes > 0 || track.decoderExtension != null
            val source = try { AudioPlaybackInput.open(this, track.uri, track.fileName, needsLocalRange) } catch (e: Exception) {
                if (com.infinifolder.storage.DiskCache.capacityError(e) != null) cacheFailure = e to track.uri
                null
            }
            if (loadCounter.get() != thisLoadId) { runCatching { source?.close() }; return@Thread }
            cleanupStagedFile()
            stagedSource = source
            val rawStagedFile = source?.file
            val stagedFile = if (rawStagedFile != null && rawStagedFile.exists() &&
                (track.startByte > 0L || track.lengthBytes > 0L || track.decoderExtension != null)) {
                sliceTrackFile(rawStagedFile, track.startByte, track.lengthBytes, track.decoderExtension)
            } else {
                rawStagedFile
            }
            stagedTempFile = stagedFile
            if (source == null || (stagedFile != null && !stagedFile.exists())) {
                isLoading = false
                notifyPlaybackState(isPlaying = false, isLoading = false)
                skipUnreadable(track, thisLoadId, java.io.IOException("Unable to read audio source"))
                return@Thread
            }
            val trackMime = try { contentResolver.getType(track.uri) } catch (_: Throwable) { null }
                ?: com.infinifolder.mime.MimeRegistry.guess(stagedFile?.name ?: track.fileName)
            val stagedAt = android.os.SystemClock.elapsedRealtime()
            // Content and tag probing is deferred until the track has started. Decoder
            // selection below validates what it needs to play, while playlist metadata
            // is enriched on a background thread after queue submission.
            var formatInfo = com.infinifolder.audio.AudioFormatInfo()
            com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.startTrack(
                uri = track.uri,
                fileName = stagedFile?.name ?: source.metadata.name ?: track.fileName,
                fileSize = stagedFile?.length() ?: source.metadata.length ?: 0L,
                mimeType = trackMime
            ).apply {
                sampleRate = formatInfo.sampleRate
                channels = formatInfo.channels
                bitrateKbps = formatInfo.bitrateKbps
                isBitrateRelevant = formatInfo.isBitrateRelevant
                isSampleRateRelevant = formatInfo.isSampleRateRelevant
            }
            var enriched = if (track.isUnreadable) unreadableTrack(track) else track
            val metadataAt = android.os.SystemClock.elapsedRealtime()
            if (loadCounter.get() != thisLoadId) return@Thread
            synchronized(playlist) {
                if (currentIndex in playlist.indices && sameTrack(playlist[currentIndex], track)) {
                    playlist[currentIndex] = enriched
                    val oi = originalPlaylist.indexOfFirst { sameTrack(it, track) }
                    if (oi >= 0) originalPlaylist[oi] = enriched
                    notifyTrackChanged(enriched)
                    notifyPlaylistUpdated()
                }
            }
            var fis: java.io.FileInputStream? = null
            var pfd: android.os.ParcelFileDescriptor? = null
            var player: MediaPlayer? = null
            var decodedOutput: File? = null
            var pcmSource: com.infinifolder.audio.AudioWaveSource? = null
            try {
                val p = MediaPlayer()
                player = p
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                val output = File.createTempFile("decoded_stream_", ".wav", cacheDir)
                decodedOutput = output
                val waveSource = source.openWaveSource(output, stagedFile, cancelled = { loadCounter.get() != thisLoadId })
                if (stagedTempFile == null) stagedTempFile = source.file
                val decodedAt = android.os.SystemClock.elapsedRealtime()
                pcmSource = waveSource
                val preparation = waveSource.preparation
                val decodedInfo = Triple(preparation.format.sampleRate, preparation.format.channels, preparation.format.durationMs ?: 0L)
                val playbackFile = output
                resetPlayerForAudio(p)
                // Only library-decoded PCM crosses this output boundary, pulled as needed.
                p.setDataSource(com.infinifolder.audio.engine.PcmMediaDataSource(waveSource.borrowedResource().open()))
                p.prepare()
                com.infinifolder.storage.DiagnosticLog.record("audio.load.prepared",
                    "load=$thisLoadId sourceStageMs=${stagedAt - loadStarted} metadataMs=deferred " +
                        "pcmOpenMs=${decodedAt - metadataAt} playerPrepareMs=${android.os.SystemClock.elapsedRealtime() - decodedAt} " +
                        "encodedAccess=${source.access} decoder=${preparation.decoder} progressive=${waveSource.progressive} cachedPcmBytes=${waveSource.metadata.attributes["cachedPcmBytes"] ?: output.length()} " +
                        "totalPcmBytes=${waveSource.metadata.length} access=${waveSource.metadata.attributes["access"]}")
                decodedInfo.let { (rate, channels, duration) ->
                    formatInfo = formatInfo.copy(sampleRate = rate, channels = channels, isSampleRateRelevant = true)
                    enriched = enriched.copy(isUnreadable = false, sampleRateHz = rate.takeIf { it > 0 }, channels = channels.takeIf { it > 0 },
                        durationMs = if (duration > 0) duration else enriched.durationMs)
                    com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.currentReport?.apply {
                        sampleRate = rate; this.channels = channels; bitsPerSample = preparation.format.bitsPerSample; isSampleRateRelevant = true
                    }
                    synchronized(playlist) {
                        if (currentIndex in playlist.indices && playlist[currentIndex].uri == track.uri && loadCounter.get() == thisLoadId) {
                            restoreReadableState(playlist, track, enriched.durationMs)
                            restoreReadableState(originalPlaylist, track, enriched.durationMs)
                            playlist[currentIndex] = enriched
                            val oi = originalPlaylist.indexOfFirst { it.uri == track.uri && it.startByte == track.startByte && it.lengthBytes == track.lengthBytes }
                            if (oi >= 0) originalPlaylist[oi] = enriched
                            notifyTrackChanged(enriched)
                        }
                    }
                }
                com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackStarted(
                    engine = "${preparation.decoder} -> MediaPlayer(PCM)",
                    sampleRate = formatInfo.sampleRate,
                    channels = formatInfo.channels,
                    bitrateKbps = formatInfo.bitrateKbps,
                    isBitrateRelevant = formatInfo.isBitrateRelevant,
                    isSampleRateRelevant = formatInfo.isSampleRateRelevant,
                    sourceFormat = if (!formatInfo.isBitrateRelevant) (stagedFile?.extension ?: track.fileName.substringAfterLast('.', "")).uppercase() else if (formatInfo.bitrateKbps > 0) "${formatInfo.bitrateKbps} kbps" else null
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                    p.playbackParams = PlaybackParams().apply { speed = playbackSpeed }
                }
                installPlayerListeners(p)

                // Hand the player and its streams to the service only if this load is still the
                // latest one; otherwise release everything so nothing is leaked or left holding locks.
                var attached = false
                synchronized(playerLock) {
                    if (loadCounter.get() == thisLoadId) {
                        mediaPlayer = p
                        analysisSource = AnalysisSource(analysisSourceCounter.incrementAndGet(), playbackFile.absolutePath, waveSource.borrowedResource())
                        currentPcmSource = waveSource
                        currentDecodedFile = playbackFile.takeIf { it != stagedFile }
                        currentStream = fis
                        currentPfd = pfd
                        attached = true
                    }
                }
                if (!attached) {
                    runCatching { pcmSource?.close() }
                    runCatching { p.release() }
                    runCatching { fis?.close() }
                    runCatching { pfd?.close() }
                    runCatching { decodedOutput?.delete() }
                    return@Thread
                }
                decodedOutput = null // Ownership transferred to currentDecodedFile.

                isLoading = false
                if (castReturn?.second != false) requestAudioFocus()
                var started = false
                synchronized(playerLock) {
                    preferredOutputId?.let { id ->
                        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                        if (device != null) mediaPlayer?.setPreferredDevice(device)
                    }
                    val seekPosition = castReturn?.first?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                        ?: resumePosition?.takeIf { it > 0 }
                    seekPosition?.let { mediaPlayer?.seekTo(it) }
                    if (castReturn?.second != false) {
                        started = runCatching { mediaPlayer?.start(); mediaPlayer?.isPlaying == true }.getOrDefault(false)
                    }
                }
                if (castReturn?.second == false) {
                    notifyPlaybackState(isPlaying = false, isLoading = false)
                    ensureForegroundService()
                } else if (started) {
                    val actualDur = (mediaPlayer?.duration ?: 0).coerceAtLeast(0).toLong()
                    if (actualDur > 0L) {
                        synchronized(playlist) {
                            if (currentIndex in playlist.indices && playlist[currentIndex].uri == track.uri) {
                                val cur = playlist[currentIndex]
                                if (cur.durationMs != actualDur) {
                                    val updated = cur.copy(durationMs = actualDur)
                                    playlist[currentIndex] = updated
                                    val oi = originalPlaylist.indexOfFirst { it.uri == track.uri }
                                    if (oi >= 0) originalPlaylist[oi] = updated
                                    notifyPlaylistUpdated()
                                }
                            }
                        }
                    }
                    startProgressReporting()
                    com.infinifolder.ops.AppOperations.coordinator.registerExternal(
                        id = "playback_audio",
                        label = "Audio: ${enriched.title}",
                        totalBytes = if (enriched.durationMs > 0) enriched.durationMs else null,
                        currentItem = enriched.artist ?: enriched.album ?: "Infinifolder Media"
                    )
                    applyVolumeAndBalance()
                    val sid = runCatching { mediaPlayer?.audioSessionId ?: 0 }.getOrDefault(0)
                    notifyAudioSession(sid)
                    notifyPlaybackState(isPlaying = true, isLoading = false)
                    ensureForegroundService()
                    scheduleGapless()
                } else {
                    notifyPlaybackState(isPlaying = false, isLoading = false)
                }
                if (loadCounter.get() == thisLoadId) {
                    startCurrentTrackMetadataEnrichment(track, thisLoadId)
                }
            } catch (e: Exception) {
                runCatching { pcmSource?.close() }
                runCatching { player?.release() }
                runCatching { fis?.close() }
                runCatching { pfd?.close() }
                runCatching { decodedOutput?.delete() }
                if (loadCounter.get() == thisLoadId) {
                    isLoading = false
                    notifyPlaybackState(isPlaying = false, isLoading = false)
                    skipUnreadable(track, thisLoadId, e)
                }
            }
        }.apply { name = "AudioTrackLoader-$thisLoadId"; start() }
    }

    /** Keep failed entries visible, but never retry an unreadable queue in a loop. */
    private fun skipUnreadable(track: AudioTrack, loadId: Long, error: Exception) {
        com.infinifolder.storage.DiagnosticLog.record("audio.track.skipped", "file=${track.fileName} load=$loadId", error)
        mainHandler.post {
            if (loadCounter.get() != loadId) return@post
            synchronized(playlist) {
                if (currentTrack?.let { sameTrack(it, track) } != true) return@synchronized
                for (index in playlist.indices) if (sameTrack(playlist[index], track)) playlist[index] = unreadableTrack(playlist[index])
                for (index in originalPlaylist.indices) if (sameTrack(originalPlaylist[index], track)) originalPlaylist[index] = unreadableTrack(originalPlaylist[index])
                notifyPlaylistUpdated()
                val next = nextReadableTrack(playlist, currentIndex, repeatMode == RepeatMode.ALL)
                if (next == null) {
                    isLoading = false
                    stopPlayback()
                    notifyTrackChanged(currentTrack)
                    notifyProgress(0, 0)
                } else {
                    currentIndex = next
                    loadAndPlayCurrent()
                }
            }
        }
    }

    private fun resetPlayerForAudio(player: MediaPlayer, sessionId: Int = 0) {
        player.reset()
        if (sessionId > 0) runCatching { player.setAudioSessionId(sessionId) }
        player.setAudioAttributes(AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA).build())
    }

    private fun installPlayerListeners(player: MediaPlayer) {
        player.setOnSeekCompleteListener { completed ->
            synchronized(playerLock) {
                val request = pendingSeek
                if (mediaPlayer === completed && request?.playerId == System.identityHashCode(completed)) {
                    pendingSeek = null
                    com.infinifolder.storage.DiagnosticLog.record("audio.seek.completed",
                        "latestTargetMs=${request.targetMs} positionMs=${runCatching { completed.currentPosition }.getOrNull()} " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - request.startedMs} ${pcmAccessDescription()}")
                }
            }
        }
        player.setOnCompletionListener { finished ->
            val isCurrent = synchronized(playerLock) { mediaPlayer === finished }
            if (isCurrent) {
                com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackCompleted()
                if (!advanceGapless(finished)) onTrackCompleted()
            }
        }
        player.setOnErrorListener { errored, what, extra ->
            val isCurrent = synchronized(playerLock) { mediaPlayer === errored }
            if (isCurrent) {
                com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackError("MediaPlayer Error: what=$what, extra=$extra")
                currentTrack?.let { skipUnreadable(it, loadCounter.get(), java.io.IOException("PCM playback error: $what, $extra")) }
            } else {
                val isPrepared = synchronized(playerLock) { preparedNext?.player === errored }
                if (isPrepared) invalidateGapless()
            }
            true
        }
    }

    /** Unlink stale preloads before changing the queue or stopping local playback. */
    private fun invalidateGapless() {
        synchronized(playerLock) {
            gaplessGeneration++
            gaplessWork?.cancel(true)
            gaplessWork = null
            gaplessRequestedTrack = null
            runCatching { mediaPlayer?.setNextMediaPlayer(null) }
            preparedNext?.release()
            preparedNext = null
        }
    }

    private fun readableGaplessNextIndex(): Int? {
        if (repeatMode == RepeatMode.ONE) return null
        val next = nextReadableTrack(playlist, currentIndex, repeatMode == RepeatMode.ALL) ?: return null
        // A visual item is a real queue stop. Do not let the linked MediaPlayer
        // jump across it before the playlist listener can display it.
        return next.takeIf { playlist.getOrNull(it)?.mediaKind == PlaylistMediaKind.AUDIO }
    }

    /** Called by the visual pane when its image dwell or video playback ends. */
    fun onVisualItemCompleted() = onTrackCompleted()

    private fun scheduleGapless() {
        if (com.infinifolder.audio.cast.CastManager.isCasting) {
            com.infinifolder.audio.cast.CastManager.onQueueChanged()
            return
        }
        synchronized(playlist) {
            if (playbackSpeed != 1f) return
            val nextIndex = readableGaplessNextIndex() ?: return
            val track = playlist[nextIndex]
            synchronized(playerLock) {
                val owner = mediaPlayer ?: return
                if (preparedNext?.let { sameTrack(it.track, track) } == true) return
                if (gaplessWork?.isDone == false && gaplessRequestedTrack?.let { sameTrack(it, track) } == true) return
                val generation = gaplessGeneration
                val sessionId = runCatching { owner.audioSessionId }.getOrDefault(0)
                gaplessRequestedTrack = track
                gaplessWork = gaplessExecutor.submit {
                    val next = prepareGaplessTrack(track, sessionId) ?: return@submit
                    var installed = false
                    synchronized(playlist) {
                        val index = readableGaplessNextIndex()
                        val stillNext = index != null && sameTrack(playlist[index], track)
                        synchronized(playerLock) {
                            if (generation == gaplessGeneration && mediaPlayer === owner && stillNext &&
                                !com.infinifolder.audio.cast.CastManager.isCasting && playbackSpeed == 1f) {
                                try {
                                    val left = volume * (if (balance > 0f) 1f - balance else 1f)
                                    val right = volume * (if (balance < 0f) 1f + balance else 1f)
                                    next.player.setVolume(left, right)
                                    preferredOutputId?.let { id ->
                                        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                            .firstOrNull { it.id == id }?.let { next.player.setPreferredDevice(it) }
                                    }
                                    owner.setNextMediaPlayer(next.player)
                                    preparedNext = next
                                    installed = true
                                } catch (error: Exception) {
                                    android.util.Log.w("AudioPlayerService", "Could not link gapless player", error)
                                }
                            }
                        }
                    }
                    if (!installed) next.release()
                }
            }
        }
    }

    /** Prepare the next source without changing the current player or cache lease. */
    private fun prepareGaplessTrack(track: AudioTrack, sessionId: Int): GaplessPrepared? {
        var source: AudioPlaybackInput? = null
        var stagedFile: File? = null
        var decodedFile: File? = null
        var pcmSource: com.infinifolder.audio.AudioWaveSource? = null
        var player: MediaPlayer? = null
        var complete = false
        try {
            val needsLocalRange = track.startByte > 0 || track.lengthBytes > 0 || track.decoderExtension != null
            source = AudioPlaybackInput.open(this, track.uri, track.fileName, needsLocalRange)
            if (Thread.currentThread().isInterrupted) return null
            stagedFile = if (track.startByte > 0 || track.lengthBytes > 0 || track.decoderExtension != null)
                sliceTrackFile(source.file ?: throw java.io.IOException("CUE source needs a file"), track.startByte, track.lengthBytes, track.decoderExtension) else source.file
            val file = stagedFile
            val p = MediaPlayer()
            player = p
            decodedFile = File.createTempFile("gapless_decoded_", ".wav", cacheDir)
            val waveSource = source.openWaveSource(decodedFile!!, file, reportDiagnostics = false)
            pcmSource = waveSource
            val preparation = waveSource.preparation
            if (Thread.currentThread().isInterrupted) return null
            val decodedMetadata = Triple(preparation.format.sampleRate, preparation.format.channels, preparation.format.durationMs ?: 0L)
            resetPlayerForAudio(p, sessionId)
            p.setDataSource(com.infinifolder.audio.engine.PcmMediaDataSource(waveSource.borrowedResource().open()))
            p.prepare()
            installPlayerListeners(p)
            val preparedTrack = decodedMetadata.let { (rate, channels, duration) ->
                track.copy(isUnreadable = false, sampleRateHz = rate.takeIf { it > 0 } ?: track.sampleRateHz,
                    channels = channels.takeIf { it > 0 } ?: track.channels,
                    durationMs = duration.takeIf { it > 0 } ?: track.durationMs)
            }
            val result = GaplessPrepared(preparedTrack, p, source, file, decodedFile!!, decodedFile, preparation.decoder, waveSource)
            complete = true
            return result
        } catch (error: Exception) {
            android.util.Log.w("AudioPlayerService", "Next track could not be preloaded", error)
            return null
        } finally {
            if (!complete) {
                runCatching { pcmSource?.close() }
                runCatching { player?.release() }
                runCatching { decodedFile?.delete() }
                if (stagedFile != null && stagedFile != source?.file) runCatching { stagedFile?.delete() }
                runCatching { source?.close() }
            }
        }
    }

    /** The framework has already started the linked player; promote its resources and queue entry. */
    private fun advanceGapless(finished: MediaPlayer): Boolean {
        val track: AudioTrack
        val next: GaplessPrepared
        synchronized(playlist) {
            val nextIndex = readableGaplessNextIndex() ?: return false
            synchronized(playerLock) {
                next = preparedNext ?: return false
                if (mediaPlayer !== finished || !sameTrack(playlist[nextIndex], next.track) ||
                    com.infinifolder.audio.cast.CastManager.isCasting) return false
                preparedNext = null
                gaplessWork = null
                gaplessRequestedTrack = null
                gaplessGeneration++
                runCatching { currentPcmSource?.close() }
                runCatching { finished.release() }
                runCatching { currentStream?.close() }
                runCatching { currentPfd?.close() }
                currentPfd = null
                cleanupStagedFile()
                stagedSource = next.source
                stagedTempFile = next.stagedFile
                currentDecodedFile = next.decodedFile
                currentStream = null
                currentPcmSource = next.pcmSource
                mediaPlayer = next.player
                currentIndex = nextIndex
                val duration = runCatching { next.player.duration.toLong() }.getOrDefault(0L)
                val current = playlist[currentIndex]
                playlist[currentIndex] = current.copy(
                    durationMs = duration.takeIf { it > 0 } ?: next.track.durationMs.takeIf { it > 0 } ?: current.durationMs,
                    sampleRateHz = current.sampleRateHz ?: next.track.sampleRateHz,
                    channels = current.channels ?: next.track.channels
                )
                track = playlist[currentIndex]
                restoreReadableState(playlist, track, track.durationMs)
                restoreReadableState(originalPlaylist, track, track.durationMs)
                analysisSource = AnalysisSource(analysisSourceCounter.incrementAndGet(), next.playbackFile.absolutePath, next.pcmSource.borrowedResource())
            }
        }
        com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.startTrack(
            uri = track.uri, fileName = track.fileName, fileSize = next.stagedFile?.length() ?: next.source.metadata.length ?: 0L,
            mimeType = com.infinifolder.mime.MimeRegistry.guess(track.fileName) ?: "audio/*"
        ).apply {
            sampleRate = track.sampleRateHz ?: 0; channels = track.channels ?: 0
            bitsPerSample = next.pcmSource.preparation.format.bitsPerSample
            bitrateKbps = track.bitrateKbps ?: 0; isBitrateRelevant = track.isBitrateRelevant
        }
        com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackStarted(
            engine = "${next.decodeEngine} -> MediaPlayer(PCM)",
            sampleRate = track.sampleRateHz ?: 0, channels = track.channels ?: 0,
            bitrateKbps = track.bitrateKbps ?: 0, isBitrateRelevant = track.isBitrateRelevant
        )
        com.infinifolder.ops.AppOperations.coordinator.registerExternal(
            id = "playback_audio", label = "Audio: ${track.title}",
            totalBytes = track.durationMs.takeIf { it > 0 }, currentItem = track.artist ?: track.album ?: "Infinifolder Media"
        )
        notifyTrackChanged(track)
        notifyPlaylistUpdated()
        notifyAudioSession(audioSessionId)
        notifyProgress(0, currentDuration)
        notifyPlaybackState(isPlaying = true, isLoading = false)
        updateNotification()
        startBackgroundMetadataScanner(forceRestart = false)
        scheduleGapless()
        return true
    }

    /** Called when the current track finishes naturally. */
    private fun onTrackCompleted() {
        when (repeatMode) {
            RepeatMode.ONE -> { seekTo(0); play() }
            RepeatMode.ALL -> playNext()
            RepeatMode.NONE -> {
                val next = synchronized(playlist) { nextReadableTrack(playlist, currentIndex, false) }
                if (next != null) {
                    synchronized(playlist) { currentIndex = next; loadAndPlayCurrent() }
                } else {
                    // Finished the whole queue with no repeat: release everything and let the UI close
                    stopPlayback()
                    notifyPlaybackComplete()
                }
            }
        }
    }

    private fun decodeSampledBitmap(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            var inSampleSize = 1
            while (options.outHeight / inSampleSize > reqHeight || options.outWidth / inSampleSize > reqWidth) {
                inSampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }

    private fun readTrackTags(input: AudioPlaybackInput, includeArtwork: Boolean): AudioTags {
        val streamTags = AudioProcessing.readTags(input.resource, includeArtwork)
        if (!includeArtwork) return streamTags
        // Stream readers sometimes return the embedded cover but omit textual tags,
        // while Android's file-backed retriever can still supply title/artist/album.
        // Merge field by field so one partially successful reader doesn't suppress
        // useful metadata from the other access path.
        val file = input.file?.takeIf { it.isFile && it.canRead() } ?: return streamTags
        val fileTags = runCatching {
            AudioProcessing.metadata.readTags(AudioMetadataSource.LocalFile(file), includeArtwork = true)
        }.getOrNull() ?: return streamTags
        return streamTags.copy(
            title = streamTags.title?.takeIf(String::isNotBlank) ?: fileTags.title?.takeIf(String::isNotBlank),
            artist = streamTags.artist?.takeIf(String::isNotBlank) ?: fileTags.artist?.takeIf(String::isNotBlank),
            album = streamTags.album?.takeIf(String::isNotBlank) ?: fileTags.album?.takeIf(String::isNotBlank),
            duration = streamTags.duration?.takeIf(String::isNotBlank) ?: fileTags.duration?.takeIf(String::isNotBlank),
            artwork = streamTags.artwork ?: fileTags.artwork
        )
    }

    private fun probeAudioFormat(file: File, track: AudioTrack): com.infinifolder.audio.AudioFormatInfo =
        com.infinifolder.audio.AudioProcessing.metadata.probeFormat(file, track.title, track.durationMs)

    private fun enrichTrackMetadata(original: AudioTrack, file: File?, formatInfo: com.infinifolder.audio.AudioFormatInfo, input: AudioPlaybackInput): AudioTrack {
        var title = original.title
        var artist = original.artist
        var album = original.album
        var duration = original.durationMs
        var artwork = original.artwork

        if (original.artist == null || original.album == null || original.artwork == null || duration <= 0) {
            try {
                val tags = readTrackTags(input, includeArtwork = true)
                title = tags.title ?: title
                artist = tags.artist ?: artist
                album = tags.album ?: album
                val durStr = tags.duration
                if (durStr != null) duration = durStr.toLongOrNull() ?: duration

                val pic = tags.artwork
                if (pic != null) {
                    artwork = decodeSampledBitmap(pic, 512, 512)
                }
            } catch (_: Exception) {}

            // Unknown duration is filled by the opened PCM source/player. Never decode
            // the complete source just to enrich metadata before playback can begin.

        }

        // Embedded artwork remains the track art. Resolve the directory art separately
        // so the playlist can show a named image entry for each directory even when a
        // track has its own embedded cover.
        // Recursive selections already carry the directory and its cover URI.
        // Trust that lightweight metadata instead of probing the parent once per
        // track while the queue is being built.
        val directoryInfo = if (original.directoryArtworkMetadataResolved || original.directoryCoverUri != null) null
            else findDirectoryArtworkInfo(original.uri)
        val candidateCoverUris = when {
            original.directoryCoverUri != null -> listOf(original.directoryCoverUri)
            original.directoryArtworkMetadataResolved -> emptyList()
            else -> directoryInfo?.coverUris ?: findDirectoryCoverUris(original.uri)
        }
        var directoryCoverUri = original.directoryCoverUri ?: candidateCoverUris.firstOrNull()
        var directoryArtwork = original.directoryArtwork
        if (directoryArtwork == null) {
            for (coverUri in candidateCoverUris) {
                directoryArtwork = loadCoverArtwork(coverUri)
                if (directoryArtwork != null) {
                    directoryCoverUri = coverUri
                    break
                }
            }
        }
        if (artwork == null) artwork = directoryArtwork

        return original.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            artwork = artwork,
            directoryCoverUri = directoryCoverUri,
            directoryUri = original.directoryUri ?: directoryInfo?.directoryUri,
            directoryName = original.directoryName ?: directoryInfo?.directoryName,
            directoryArtwork = directoryArtwork,
            bitrateKbps = if (formatInfo.isBitrateRelevant && formatInfo.bitrateKbps > 0) formatInfo.bitrateKbps else null,
            sampleRateHz = if (formatInfo.isSampleRateRelevant && formatInfo.sampleRate > 0) formatInfo.sampleRate else null,
            channels = formatInfo.channels.takeIf { it > 0 },
            isBitrateRelevant = formatInfo.isBitrateRelevant
        )
    }

    private fun findDirectoryCoverUris(trackUri: Uri): List<Uri> {
        val info = findDirectoryArtworkInfo(trackUri)
        val cacheKey = info?.directoryUri?.toString()
            ?: "${trackUri.authority}:${trackUri.path?.substringBeforeLast('/', "") ?: trackUri}"
        directoryCoverCache[cacheKey]?.let { return it }
        return info?.coverUris.orEmpty().distinct()
            .also { directoryCoverCache[cacheKey] = it }
    }

    private fun findDirectoryArtworkInfo(trackUri: Uri, directoryUri: Uri? = null): MediaSourceAdapter.AudioDirectoryArtworkInfo? {
        directoryUri?.let { directoryArtworkInfoCache[it.toString()]?.let { cached -> return cached } }
        directoryArtworkInfoCache[trackUri.toString()]?.let { return it }
        val parentUri = directoryUri ?: runCatching { MediaSourceAdapter.getParentDirectory(this, trackUri) }.getOrNull()
        parentUri?.let { parent ->
            directoryArtworkInfoCache[parent.toString()]?.let { cached ->
                directoryArtworkInfoCache[trackUri.toString()] = cached
                return cached
            }
        }
        return runCatching { MediaSourceAdapter.getAudioDirectoryArtwork(this, trackUri) }.getOrNull()?.also { info ->
            directoryArtworkInfoCache[trackUri.toString()] = info
            info.directoryUri?.let { directoryArtworkInfoCache[it.toString()] = info }
        }
    }

    private fun loadCoverArtwork(coverUri: Uri): Bitmap? {
        directoryArtworkBitmapCache.get(coverUri.toString())?.let { return it }
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            MediaSourceAdapter.openStream(this, coverUri).use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var sample = 1
            // Playlist rows and the player surface do not need full resolution. Keep
            // one shared, sampled bitmap per directory instead of retaining originals.
            while (options.outWidth / sample > 256 || options.outHeight / sample > 256) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            MediaSourceAdapter.openStream(this, coverUri).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?.also { directoryArtworkBitmapCache.put(coverUri.toString(), it) }
        } catch (error: Exception) {
            android.util.Log.w("AudioPlayerService", "Could not decode directory cover $coverUri", error)
            null
        }
    }

    private fun ensureForegroundService() {
        val notification = buildNotification()
        if (!isForegroundActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundActive = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        mainHandler.post { runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) } }
    }

    private fun buildNotification(): android.app.Notification {
        val track = currentTrack
        val pOpen = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioPlayerActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun actionIntent(action: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, AudioPlayerService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPause = if (isPlaying) {
            NotificationCompat.Action.Builder(android.R.drawable.ic_media_pause, "Pause", actionIntent(ACTION_TOGGLE, 1)).build()
        } else {
            NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, "Play", actionIntent(ACTION_TOGGLE, 2)).build()
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "Infinifolder Audio")
            .setContentText(track?.artist ?: track?.album ?: "Audio Playback")
            .setContentIntent(pOpen)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                mediaSession?.sessionToken?.let { token ->
                    setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(token))
                        .setShowActionsInCompactView(0, 1, 2))
                }
            }
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV, 3))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT, 4))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_STOP, 5))
            .apply {
                track?.artwork?.let { art ->
                    scaleBitmapForNotification(art, 256, 256)?.let { setLargeIcon(it) }
                }
            }
            .build()
    }

    // Single persistent listener/request: creating a new request on every play() left the old
    // ones registered, and their late AUDIOFOCUS_LOSS callbacks paused the newly started track.
    private var resumeOnFocusGain = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlaying
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                synchronized(playerLock) { runCatching { mediaPlayer?.setVolume(0.2f, 0.2f) } }
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(playerLock) { runCatching { mediaPlayer?.setVolume(1.0f, 1.0f) } }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        resumeOnFocusGain = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun startProgressReporting() {
        synchronized(progressLock) {
            if (isProgressRunning) return
            isProgressRunning = true
            progressThread = Thread {
                while (isProgressRunning) {
                    try {
                        if (isPlaying) {
                            val pos = currentPosition
                            val dur = currentDuration
                            notifyProgress(pos, dur)
                        }
                    } catch (_: Exception) {}
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
            }.apply { start() }
        }
    }

    private fun stopProgressReporting() {
        synchronized(progressLock) {
            isProgressRunning = false
            progressThread?.interrupt()
            progressThread = null
        }
    }

    private fun cleanupStagedFile() {
        runCatching { currentDecodedFile?.delete() }
        currentDecodedFile = null
        if (stagedTempFile != null && stagedTempFile != stagedSource?.file) {
            runCatching { stagedTempFile?.delete() }
        }
        val closing = stagedSource
        stagedSource = null
        stagedTempFile = null
        runCatching { closing?.close() }.onFailure { error ->
            com.infinifolder.storage.DiagnosticLog.record("audio.source.close.warning", error = error)
        }
    }

    private fun sliceTrackFile(file: File, startByte: Long, lengthBytes: Long, extensionOverride: String? = null): File {
        val ext = extensionOverride?.trimStart('.')?.takeIf { it.isNotBlank() } ?: file.extension.ifEmpty { "bin" }
        val sliceFile = File.createTempFile("audio_slice_", ".$ext", cacheDir)
        val totalLen = file.length()
        val validStart = startByte.coerceIn(0L, totalLen)
        val validLen = if (lengthBytes > 0L) minOf(lengthBytes, totalLen - validStart) else (totalLen - validStart)

        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(validStart)
                java.io.FileOutputStream(sliceFile).use { fos ->
                    val buf = ByteArray(65536)
                    var remaining = validLen
                    while (remaining > 0) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Audio slice cancelled")
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val r = raf.read(buf, 0, toRead)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        remaining -= r
                    }
                }
            }
        } catch (error: Exception) {
            sliceFile.delete()
            throw error
        }
        return sliceFile
    }

    private fun scaleBitmapForNotification(src: Bitmap, maxW: Int, maxH: Int): Bitmap? {
        return try {
            if (src.width <= maxW && src.height <= maxH) return src
            val ratio = kotlin.math.min(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
            val w = (src.width * ratio).toInt().coerceAtLeast(1)
            val h = (src.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (e: Exception) { null }
    }

    private fun notifyTrackChanged(track: AudioTrack?) {
        val idx = currentIndex
        val total = playlist.size
        updateMediaSession(track, isPlaying)
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onTrackChanged(track, idx, total) } }
    }

    private fun notifyAudioSession(sessionId: Int) {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onAudioSessionIdChanged(sessionId) } }
    }

    private fun notifyPlaybackState(isPlaying: Boolean, isLoading: Boolean) {
        updateMediaSession(currentTrack, isPlaying)
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onPlaybackStateChanged(isPlaying, isLoading) } }
    }

    private fun updateMediaSession(track: AudioTrack?, isPlaying: Boolean) {
        val session = mediaSession ?: return
        try {
            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val actions = PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO

            val stateBuilder = PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPosition.toLong(), playbackSpeed)
            session.setPlaybackState(stateBuilder.build())

            if (track != null) {
                val metaBuilder = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: "Unknown Artist")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDuration.toLong())
                track.artwork?.let { art ->
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                }
                session.setMetadata(metaBuilder.build())
            }
        } catch (_: Exception) {}
    }

    private fun notifyProgress(pos: Int, dur: Int) {
        com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackProgress(pos, dur)
        com.infinifolder.ops.AppOperations.coordinator.updateExternal(
            id = "playback_audio",
            bytes = pos.toLong(),
            totalBytes = if (dur > 0) dur.toLong() else null
        )
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onProgressUpdated(pos, dur) } }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPositionPersistElapsed >= POSITION_PERSIST_INTERVAL_MS) {
            lastPositionPersistElapsed = now
            persistPlaylistState(positionMs = pos)
        }
    }

    private fun notifyPlaylistUpdated() {
        val copy = synchronized(playlist) { ArrayList(playlist) }
        persistPlaylistState()
        val copyListeners = synchronized(listeners) { ArrayList(listeners) }
        copyListeners.forEach { runCatching { it.onPlaylistUpdated(copy) } }
    }

    private fun notifyError(message: String) {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onError(message) } }
    }

    private fun notifyPlaybackComplete() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onPlaybackComplete() } }
    }

    private var metadataScannerThread: Thread? = null
    private val metadataScannerGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val metadataScannerLock = Any()
    private var metadataWork = PlaylistMetadataWork()
    private var metadataPriorityTracks: List<AudioTrack> = emptyList()

    private fun stopBackgroundMetadataScanner() = synchronized(metadataScannerLock) {
        metadataScannerGeneration.incrementAndGet()
        metadataWork = PlaylistMetadataWork()
        metadataWork.prioritize(metadataPriorityTracks)
        metadataScannerThread?.interrupt()
        metadataScannerThread = null
    }

    private fun startBackgroundMetadataScanner(forceRestart: Boolean = false) = synchronized(metadataScannerLock) {
        if (!forceRestart && metadataScannerThread?.isAlive == true) return@synchronized
        val gen = metadataScannerGeneration.incrementAndGet()
        if (forceRestart) {
            metadataWork = PlaylistMetadataWork()
            metadataWork.prioritize(metadataPriorityTracks)
        }
        val work = metadataWork
        metadataScannerThread?.interrupt()
        val worker = Thread({
            while (gen == metadataScannerGeneration.get() && !Thread.currentThread().isInterrupted) {
                // Let foreground preparation have the CPU/provider while opening a track.
                if (isLoading) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    continue
                }
                // Read the work list while holding the scanner lock too. This closes
                // the idle/enqueue race: an append either becomes visible here or
                // starts a fresh worker after this one marks itself idle.
                val track = synchronized(metadataScannerLock) {
                    if (gen != metadataScannerGeneration.get()) return@synchronized null
                    val next = synchronized(playlist) { work.next(playlist, currentIndex) }
                    if (next == null && metadataScannerThread === Thread.currentThread()) {
                        metadataScannerThread = null
                    }
                    next
                } ?: break
                val enriched = try {
                    resolveTrackMetadataLight(track)
                } catch (error: Exception) {
                    android.util.Log.w("AudioPlayerService", "Playlist metadata probe failed", error)
                    null
                }
                if (gen != metadataScannerGeneration.get() || Thread.currentThread().isInterrupted) break
                if (enriched != null) {
                    synchronized(playlist) {
                        if (gen != metadataScannerGeneration.get()) return@synchronized
                        for (i in playlist.indices) if (work.matches(playlist[i], track)) {
                            playlist[i] = work.merge(playlist[i], track, enriched)
                        }
                        for (i in originalPlaylist.indices) if (work.matches(originalPlaylist[i], track)) {
                            originalPlaylist[i] = work.merge(originalPlaylist[i], track, enriched)
                        }
                    }
                    notifyPlaylistUpdated()
                }
                try { Thread.sleep(25) } catch (_: InterruptedException) { break }
            }
            synchronized(metadataScannerLock) {
                if (metadataScannerThread === Thread.currentThread()) metadataScannerThread = null
            }
        }, "PlaylistMetadataScanner").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
        metadataScannerThread = worker
        worker.start()
    }

    /** Enrich the active track after playback starts so tags and artwork never delay the queue. */
    private fun startCurrentTrackMetadataEnrichment(track: AudioTrack, loadId: Long) {
        if (track.mediaKind != PlaylistMediaKind.AUDIO) return
        currentTrackMetadataThread?.interrupt()
        val worker = Thread({
            var metadataReadCompleted = false
            try {
                val enriched = try {
                    AudioPlaybackInput.open(this, track.uri, track.fileName, metadataOnly = true).use { input ->
                        val formatInfo = try {
                            input.file?.let { probeAudioFormat(it, track) }
                                ?: AudioProcessing.probeFormat(input.resource, track.fileName, track.durationMs)
                        } catch (error: Exception) {
                            if (Thread.currentThread().isInterrupted) return@Thread
                            com.infinifolder.storage.DiagnosticLog.record(
                                "audio.metadata.background-format-warning", "load=$loadId", error)
                            com.infinifolder.audio.AudioFormatInfo()
                        }
                        enrichTrackMetadata(track, input.file, formatInfo, input)
                    }
                } catch (error: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        com.infinifolder.storage.DiagnosticLog.record(
                            "audio.metadata.background-enrichment-failed", "load=$loadId", error)
                    }
                    null
                }
                if (enriched == null || Thread.currentThread().isInterrupted || loadCounter.get() != loadId) return@Thread
                metadataReadCompleted = true

                val update = synchronized(playlist) {
                    var currentUpdate: AudioTrack? = null
                    var found = false
                    val mergeWork = PlaylistMetadataWork()
                    for (index in playlist.indices) {
                        if (!sameTrack(playlist[index], track)) continue
                        val mergedBase = mergeWork.merge(playlist[index], track, enriched)
                        playlist[index] = mergedBase.copy(
                            artwork = enriched.artwork ?: mergedBase.artwork,
                            directoryArtwork = enriched.directoryArtwork ?: mergedBase.directoryArtwork
                        )
                        found = true
                        if (index == currentIndex) currentUpdate = playlist[index]
                    }
                    if (!found) return@synchronized null
                    for (index in originalPlaylist.indices) {
                        if (sameTrack(originalPlaylist[index], track)) {
                            val mergedBase = mergeWork.merge(originalPlaylist[index], track, enriched)
                            originalPlaylist[index] = mergedBase.copy(
                                artwork = enriched.artwork ?: mergedBase.artwork,
                                directoryArtwork = enriched.directoryArtwork ?: mergedBase.directoryArtwork
                            )
                        }
                    }
                    currentUpdate
                }
                if (update != null && loadCounter.get() == loadId) {
                    if (synchronized(playlist) { currentTrack?.let { sameTrack(it, track) } == true }) {
                        notifyTrackChanged(update)
                        updateNotification()
                    }
                    notifyPlaylistUpdated()
                }
            } finally {
                if (currentTrackMetadataThread === Thread.currentThread()) currentTrackMetadataThread = null
                if (loadCounter.get() == loadId && !Thread.currentThread().isInterrupted) {
                    if (metadataReadCompleted) {
                        synchronized(metadataScannerLock) { metadataWork.markAttempted(track) }
                    }
                    startBackgroundMetadataScanner(forceRestart = false)
                }
            }
        }, "CurrentTrackMetadata-$loadId").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
        currentTrackMetadataThread = worker
        worker.start()
        // Scan the remainder of the queue alongside the active track's richer pass.
        // Both stay off the playback and UI threads; the queue pass starts after the
        // current index and updates each row as its metadata becomes available.
        startBackgroundMetadataScanner(forceRestart = false)
    }

    private fun resolveTrackMetadataLight(track: AudioTrack): AudioTrack? {
        if (Thread.currentThread().isInterrupted) return null
        var result = track
        try {
            AudioPlaybackInput.open(this, track.uri, track.fileName, metadataOnly = true).use { input ->
                val tags = readTrackTags(input, includeArtwork = true)
                val segmented = track.startByte > 0 || track.lengthBytes > 0 || track.decoderExtension != null
                val duration = if (track.durationMs > 0 || segmented) track.durationMs
                    else tags.duration?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                val format = try {
                    input.file?.let { probeAudioFormat(it, track) }
                        ?: AudioProcessing.probeFormat(input.resource, track.fileName, duration)
                } catch (cancel: java.io.InterruptedIOException) {
                    throw cancel
                } catch (error: Exception) {
                    android.util.Log.w("AudioPlayerService", "Playlist format probe failed", error)
                    null
                }
                val embeddedArtwork = tags.artwork?.let { decodeSampledBitmap(it, 160, 160) }
                result = track.copy(
                    title = if (segmented) track.title else tags.title?.takeIf { it.isNotBlank() } ?: track.title,
                    artist = tags.artist?.takeIf { it.isNotBlank() } ?: track.artist,
                    album = tags.album?.takeIf { it.isNotBlank() } ?: track.album,
                    durationMs = if (segmented && duration <= 0 &&
                        (track.decoderExtension?.startsWith("cdda") == true || track.fileName.substringAfterLast('.', "").lowercase() in setOf("bin", "cdda"))) {
                        val bytes = track.lengthBytes.takeIf { it > 0 }
                            ?: input.metadata.length?.minus(track.startByte)?.coerceAtLeast(0) ?: 0L
                        bytes / 2352L * 1000L / 75L
                    } else duration,
                    artwork = track.artwork ?: embeddedArtwork,
                    bitrateKbps = format?.takeIf { it.isBitrateRelevant && it.bitrateKbps > 0 }?.bitrateKbps ?: track.bitrateKbps,
                    sampleRateHz = format?.takeIf { it.isSampleRateRelevant && it.sampleRate > 0 }?.sampleRate ?: track.sampleRateHz,
                    channels = format?.channels?.takeIf { it > 0 } ?: track.channels,
                    isBitrateRelevant = format?.isBitrateRelevant ?: track.isBitrateRelevant
                )
            }
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw error
            android.util.Log.w("AudioPlayerService", "Playlist tags probe failed", error)
        }
        return resolveDirectoryArtworkLight(result)
    }

    /** Resolve sibling cover metadata and a small reusable image for every queued track. */
    private fun resolveDirectoryArtworkLight(track: AudioTrack): AudioTrack {
        if (Thread.currentThread().isInterrupted) return track
        val shouldProbeDirectory = !track.directoryArtworkMetadataResolved &&
            (track.directoryCoverUri == null || track.directoryUri == null || track.directoryName.isNullOrBlank())
        val directoryInfo = if (shouldProbeDirectory) findDirectoryArtworkInfo(track.uri, track.directoryUri) else null
        val candidates = when {
            track.directoryCoverUri != null -> listOf(track.directoryCoverUri)
            track.directoryArtworkMetadataResolved -> emptyList()
            else -> directoryInfo?.coverUris.orEmpty()
        }
        var selectedCover = track.directoryCoverUri
        var directoryArtwork = track.directoryArtwork
        if (directoryArtwork == null) {
            for (coverUri in candidates) {
                if (Thread.currentThread().isInterrupted) break
                val decoded = loadCoverArtwork(coverUri)
                if (decoded != null) {
                    selectedCover = coverUri
                    directoryArtwork = decoded
                    break
                }
                if (selectedCover == null) selectedCover = coverUri
            }
        }
        return track.copy(
            directoryCoverUri = selectedCover,
            directoryUri = track.directoryUri ?: directoryInfo?.directoryUri,
            directoryName = track.directoryName ?: directoryInfo?.directoryName,
            directoryArtwork = directoryArtwork,
            directoryArtworkMetadataResolved = track.directoryArtworkMetadataResolved ||
                track.directoryCoverUri != null || shouldProbeDirectory
        )
    }

    override fun onDestroy() {
        stopPlayback()
        playlistPersistenceExecutor.shutdown()
        gaplessExecutor.shutdownNow()
        com.infinifolder.audio.cast.CastManager.release()
        super.onDestroy()
        try {
            metadataScannerGeneration.incrementAndGet()
            metadataScannerThread?.interrupt()
            metadataScannerThread = null
            currentTrackMetadataThread?.interrupt()
            currentTrackMetadataThread = null
        } catch (_: Exception) {}
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}
        if (currentService == this) currentService = null
    }
}
