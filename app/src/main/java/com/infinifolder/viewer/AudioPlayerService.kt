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
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

enum class RepeatMode { NONE, ALL, ONE }

data class AudioTrack(
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artwork: Bitmap? = null
)

interface AudioPlayerListener {
    fun onTrackChanged(track: AudioTrack?, index: Int, total: Int)
    fun onPlaybackStateChanged(isPlaying: Boolean, isLoading: Boolean)
    fun onProgressUpdated(currentPositionMs: Int, durationMs: Int)
    fun onPlaylistUpdated(playlist: List<AudioTrack>)
    fun onError(message: String)
    fun onPlaybackComplete()
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
        private val listeners = mutableListOf<AudioPlayerListener>()
        @Volatile var currentService: AudioPlayerService? = null
            private set
        fun addListener(l: AudioPlayerListener) = synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }
        fun removeListener(l: AudioPlayerListener) = synchronized(listeners) { listeners.remove(l) }
        fun uriMatches(u1: Uri?, u2: Uri?): Boolean {
            if (u1 == null || u2 == null) return false
            if (u1 == u2 || u1.toString() == u2.toString()) return true
            val p1 = u1.path?.let { Uri.decode(it) }?.trimEnd('/')
            val p2 = u2.path?.let { Uri.decode(it) }?.trimEnd('/')
            if (p1 != null && p2 != null) {
                val sub1 = p1.removePrefix("/shared").removePrefix("/root")
                val sub2 = p2.removePrefix("/shared").removePrefix("/root")
                if (sub1.isNotEmpty() && sub1 == sub2) return true
                if (p1.endsWith(sub2) || p2.endsWith(sub1)) return true
            }
            return false
        }
    }

    private val playerLock = Any()
    private val progressLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var currentStream: java.io.FileInputStream? = null
    private var currentPfd: android.os.ParcelFileDescriptor? = null
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var focusRequest: AudioFocusRequest? = null

    val playlist = mutableListOf<AudioTrack>()
    private val originalPlaylist = mutableListOf<AudioTrack>()
    var currentIndex: Int = -1
        private set
    var isShuffleEnabled: Boolean = false
        private set
    var repeatMode: RepeatMode = RepeatMode.NONE
        private set
    var playbackSpeed: Float = 1.0f
        private set
    @Volatile var isLoading: Boolean = false
        private set

    val isPlaying: Boolean
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false) }
    val currentPosition: Int
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.currentPosition ?: 0 }.getOrDefault(0) }
    val currentDuration: Int
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0) }
    val currentTrack: AudioTrack?
        get() = synchronized(playlist) { if (currentIndex in playlist.indices) playlist[currentIndex] else null }

    private var progressThread: Thread? = null
    private var isProgressRunning = false
    private var stagedTempFile: File? = null
    private val loadCounter = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        currentService = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        loadPlaybackPrefs()
        ensureForegroundService()
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
                uriStr?.let {
                    val uri = Uri.parse(it)
                    val cur = currentTrack
                    if (cur == null || !uriMatches(cur.uri, uri)) {
                        setPlaylist(listOf(AudioTrack(uri, name)), 0)
                    }
                }
            }
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

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        synchronized(playlist) {
            originalPlaylist.clear(); originalPlaylist.addAll(tracks)
            playlist.clear(); playlist.addAll(tracks)
            if (isShuffleEnabled) {
                val initial = tracks.getOrNull(startIndex)
                playlist.shuffle()
                if (initial != null) {
                    playlist.remove(initial)
                    playlist.add(0, initial)
                }
                currentIndex = 0
            } else {
                currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            }
        }
        notifyPlaylistUpdated()
        if (playlist.isNotEmpty()) loadAndPlayCurrent()
    }

    fun updatePlaylistKeepCurrent(tracks: List<AudioTrack>, currentUri: Uri) {
        synchronized(playlist) {
            val currentEnriched = currentTrack
            originalPlaylist.clear(); originalPlaylist.addAll(tracks)
            playlist.clear(); playlist.addAll(tracks)
            val idx = playlist.indexOfFirst { uriMatches(it.uri, currentUri) }
            if (idx >= 0) {
                currentIndex = idx
                if (currentEnriched != null && uriMatches(currentEnriched.uri, currentUri)) {
                    playlist[idx] = currentEnriched
                }
            } else {
                if (currentEnriched != null) {
                    playlist.add(0, currentEnriched)
                    originalPlaylist.add(0, currentEnriched)
                    currentIndex = 0
                }
            }
        }
        notifyPlaylistUpdated()
    }

    fun enqueue(track: AudioTrack) {
        val wasEmpty = synchronized(playlist) {
            val empty = playlist.isEmpty()
            playlist.add(track)
            originalPlaylist.add(track)
            empty
        }
        notifyPlaylistUpdated()
        if (mediaPlayer == null && wasEmpty) loadAndPlayCurrent()
    }

    fun enqueueAll(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        val wasEmpty = synchronized(playlist) {
            val empty = playlist.isEmpty()
            playlist.addAll(tracks)
            originalPlaylist.addAll(tracks)
            empty
        }
        notifyPlaylistUpdated()
        if (mediaPlayer == null && wasEmpty) loadAndPlayCurrent()
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
        isShuffleEnabled = !isShuffleEnabled
        savePlaybackPrefs()
        synchronized(playlist) {
            // Match by URI: the active entry may have been replaced by an enriched copy,
            // so object equality against originalPlaylist would fail.
            val active = if (currentIndex in playlist.indices) playlist[currentIndex] else null
            playlist.clear(); playlist.addAll(originalPlaylist)
            if (active != null) {
                val idx = playlist.indexOfFirst { it.uri == active.uri }
                if (idx >= 0) playlist[idx] = active else playlist.add(0, active)
            }
            val activeIdx = if (active != null) playlist.indexOfFirst { it.uri == active.uri } else -1
            if (isShuffleEnabled) {
                playlist.shuffle()
                if (activeIdx >= 0 && active != null) {
                    val i = playlist.indexOfFirst { it.uri == active.uri }
                    if (i > 0) { val t = playlist.removeAt(i); playlist.add(0, t) }
                }
                currentIndex = if (playlist.isEmpty()) -1 else 0
            } else {
                currentIndex = activeIdx.coerceAtLeast(0)
            }
        }
        notifyPlaylistUpdated()
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        savePlaybackPrefs()
    }

    fun setSpeed(speed: Float) {
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
    }

    fun jumpTo(index: Int) {
        synchronized(playlist) {
            if (index in playlist.indices) {
                currentIndex = index
                loadAndPlayCurrent()
            }
        }
    }

    fun seekTo(positionMs: Int) {
        synchronized(playerLock) { runCatching { mediaPlayer?.seekTo(positionMs) } }
        notifyProgress(positionMs, currentDuration)
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun play() {
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
    }

    fun pause() {
        synchronized(playerLock) { runCatching { mediaPlayer?.pause() } }
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
                if (currentIndex < playlist.size - 1) {
                    currentIndex++
                } else if (repeatMode == RepeatMode.ALL || playlist.size > 1) {
                    currentIndex = 0
                } else {
                    stopPlayback()
                    return@runCatching
                }
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
                if (currentIndex > 0) {
                    currentIndex--
                    loadAndPlayCurrent()
                } else if (repeatMode == RepeatMode.ALL) {
                    currentIndex = playlist.size - 1
                    loadAndPlayCurrent()
                } else {
                    seekTo(0)
                }
            }
        }
    }

    fun stopPlayback() {
        stopProgressReporting()
        synchronized(playerLock) {
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

    private fun loadAndPlayCurrent() {
        val track = currentTrack ?: return
        val thisLoadId = loadCounter.incrementAndGet()
        isLoading = true
        notifyPlaybackState(isPlaying = false, isLoading = true)
        notifyTrackChanged(track)
        notifyProgress(0, track.durationMs.toInt())
        stopProgressReporting()
        synchronized(playerLock) {
            runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { currentStream?.close() }
            currentStream = null
            runCatching { currentPfd?.close() }
            currentPfd = null
        }
        Thread {
            if (loadCounter.get() != thisLoadId) return@Thread
            val stagedFile = try { MediaSourceAdapter.stageToTempFile(this, track.uri) } catch (e: Exception) { null }
            if (loadCounter.get() != thisLoadId) return@Thread
            cleanupStagedFile()
            stagedTempFile = stagedFile
            if (stagedFile == null || !stagedFile.exists()) {
                isLoading = false
                notifyPlaybackState(isPlaying = false, isLoading = false)
                notifyError("Unable to prepare audio track")
                return@Thread
            }
            val enriched = enrichTrackMetadata(track, stagedFile)
            if (loadCounter.get() != thisLoadId) return@Thread
            synchronized(playlist) {
                if (currentIndex in playlist.indices && playlist[currentIndex].uri == track.uri) {
                    playlist[currentIndex] = enriched
                    val oi = originalPlaylist.indexOfFirst { it.uri == track.uri }
                    if (oi >= 0) originalPlaylist[oi] = enriched
                    notifyTrackChanged(enriched)
                }
            }
            var fis: java.io.FileInputStream? = null
            var pfd: android.os.ParcelFileDescriptor? = null
            var player: MediaPlayer? = null
            try {
                val p = MediaPlayer()
                player = p
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                var prepared = false
                if (stagedFile.canRead()) {
                    try {
                        fis = java.io.FileInputStream(stagedFile)
                        p.reset()
                        p.setDataSource(fis.fd, 0, stagedFile.length())
                        p.prepare()
                        prepared = true
                    } catch (e: Exception) {
                        android.util.Log.w("AudioPlayerService", "FIS fail: ${e.message}")
                        runCatching { fis?.close() }
                        fis = null
                    }
                }
                if (!prepared) {
                    try {
                        p.reset()
                        p.setDataSource(stagedFile.absolutePath)
                        p.prepare()
                        prepared = true
                    } catch (e: Exception) {
                        android.util.Log.w("AudioPlayerService", "Path fail: ${e.message}")
                    }
                }
                if (!prepared && track.uri.scheme == "content") {
                    try {
                        pfd = contentResolver.openFileDescriptor(track.uri, "r")
                        if (pfd != null) {
                            p.reset()
                            p.setDataSource(pfd.fileDescriptor)
                            p.prepare()
                            prepared = true
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AudioPlayerService", "PFD fail: ${e.message}")
                        runCatching { pfd?.close() }
                        pfd = null
                    }
                }
                if (!prepared) throw java.io.IOException("Unable to init MediaPlayer")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                    p.playbackParams = PlaybackParams().apply { speed = playbackSpeed }
                }
                p.setOnCompletionListener { finished ->
                    // Ignore completion events from a player that is no longer current
                    val isCurrent = synchronized(playerLock) { mediaPlayer === finished }
                    if (isCurrent) onTrackCompleted()
                }
                p.setOnErrorListener { errored, what, extra ->
                    val isCurrent = synchronized(playerLock) { mediaPlayer === errored }
                    if (isCurrent) notifyError("Playback error: $what, $extra")
                    true
                }

                // Hand the player and its streams to the service only if this load is still the
                // latest one; otherwise release everything so nothing is leaked or left holding locks.
                var attached = false
                synchronized(playerLock) {
                    if (loadCounter.get() == thisLoadId) {
                        mediaPlayer = p
                        currentStream = fis
                        currentPfd = pfd
                        attached = true
                    }
                }
                if (!attached) {
                    runCatching { p.release() }
                    runCatching { fis?.close() }
                    runCatching { pfd?.close() }
                    return@Thread
                }

                isLoading = false
                requestAudioFocus()
                var started = false
                synchronized(playerLock) {
                    started = runCatching { mediaPlayer?.start(); mediaPlayer?.isPlaying == true }.getOrDefault(false)
                }
                if (started) {
                    startProgressReporting()
                    com.infinifolder.ops.AppOperations.coordinator.registerExternal(
                        id = "playback_audio",
                        label = "Audio: ${enriched.title}",
                        totalBytes = if (enriched.durationMs > 0) enriched.durationMs else null,
                        currentItem = enriched.artist ?: enriched.album ?: "Infinifolder Media"
                    )
                    notifyPlaybackState(isPlaying = true, isLoading = false)
                    ensureForegroundService()
                } else {
                    notifyPlaybackState(isPlaying = false, isLoading = false)
                }
            } catch (e: Exception) {
                runCatching { player?.release() }
                runCatching { fis?.close() }
                runCatching { pfd?.close() }
                if (loadCounter.get() == thisLoadId) {
                    isLoading = false
                    notifyPlaybackState(isPlaying = false, isLoading = false)
                    notifyError("Error preparing playback: ${e.message}")
                }
            }
        }.start()
    }

    /** Called when the current track finishes naturally. */
    private fun onTrackCompleted() {
        when (repeatMode) {
            RepeatMode.ONE -> { seekTo(0); play() }
            RepeatMode.ALL -> playNext()
            RepeatMode.NONE -> {
                val isLast = synchronized(playlist) { currentIndex >= playlist.size - 1 }
                if (!isLast) {
                    playNext()
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
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }

    private fun enrichTrackMetadata(original: AudioTrack, file: File): AudioTrack {
        if (original.artist != null && original.album != null && original.artwork != null) {
            return original
        }

        var title = original.title
        var artist = original.artist
        var album = original.album
        var duration = original.durationMs
        var artwork = original.artwork

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (durStr != null) duration = durStr.toLongOrNull() ?: duration

            val pic = retriever.embeddedPicture
            if (pic != null) {
                artwork = decodeSampledBitmap(pic, 512, 512)
            }
            runCatching { retriever.release() }
        } catch (_: Exception) {}

        // Fallback: Check folder artwork (.icon.* then cover.*)
        if (artwork == null) {
            artwork = findFolderArtwork(original.uri)
        }

        return AudioTrack(original.uri, title, artist, album, duration, artwork)
    }

    private fun findFolderArtwork(trackUri: Uri): Bitmap? {
        val parentUri = MediaSourceAdapter.getParentDirectory(trackUri) ?: return null
        return try {
            val files = MediaSourceAdapter.listFolder(this, parentUri)
            // Precedence: .icon.* first, then cover.*
            val iconCandidate = files.firstOrNull { it.metadata.name.startsWith(".icon.", ignoreCase = true) }
            val coverCandidate = files.firstOrNull { it.metadata.name.startsWith("cover.", ignoreCase = true) }
            val chosen = iconCandidate ?: coverCandidate ?: return null
            val bytes = MediaSourceAdapter.readAllBytes(this, chosen.uri)
            decodeSampledBitmap(bytes, 512, 512)
        } catch (_: Exception) {
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
        stagedTempFile?.let { if (it.exists() && it.parent?.contains("media_staging") == true) runCatching { it.delete() } }
        stagedTempFile = null
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
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onTrackChanged(track, idx, total) } }
    }

    private fun notifyPlaybackState(isPlaying: Boolean, isLoading: Boolean) {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onPlaybackStateChanged(isPlaying, isLoading) } }
    }

    private fun notifyProgress(pos: Int, dur: Int) {
        com.infinifolder.ops.AppOperations.coordinator.updateExternal(
            id = "playback_audio",
            bytes = pos.toLong(),
            totalBytes = if (dur > 0) dur.toLong() else null
        )
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.onProgressUpdated(pos, dur) } }
    }

    private fun notifyPlaylistUpdated() {
        val copy = synchronized(playlist) { ArrayList(playlist) }
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

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        if (currentService == this) currentService = null
    }
}
