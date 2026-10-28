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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
    val artwork: Bitmap? = null,
    val startByte: Long = 0L,
    val lengthBytes: Long = -1L,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val isBitrateRelevant: Boolean = true
)

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

    @Volatile var cacheFailure: Pair<Throwable, Uri>? = null

    private val playerLock = Any()
    private val progressLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var currentStream: java.io.FileInputStream? = null
    private var currentPfd: android.os.ParcelFileDescriptor? = null
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var focusRequest: AudioFocusRequest? = null
    var mediaSession: MediaSession? = null
        private set

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
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false) }
    val currentPosition: Int
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.currentPosition ?: 0 }.getOrDefault(0) }
    val currentDuration: Int
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0) }
    val audioSessionId: Int
        get() = synchronized(playerLock) { runCatching { mediaPlayer?.audioSessionId ?: 0 }.getOrDefault(0) }
    val currentTrack: AudioTrack?
        get() = synchronized(playlist) { if (currentIndex in playlist.indices) playlist[currentIndex] else null }

    fun applyVolumeAndBalance() {
        val left = volume * (if (balance > 0f) 1f - balance else 1f)
        val right = volume * (if (balance < 0f) 1f + balance else 1f)
        synchronized(playerLock) {
            runCatching { mediaPlayer?.setVolume(left, right) }
        }
    }

    private var progressThread: Thread? = null
    private var isProgressRunning = false
    private var stagedTempFile: File? = null
    private var stagedSource: MediaSourceAdapter.StagedSource? = null
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
        val targetTrack = tracks.getOrNull(startIndex)
        val expanded = mutableListOf<AudioTrack>()
        for (t in tracks) {
            if (PlaylistExpander.isPlaylist(t.uri, t.title)) {
                expanded.addAll(PlaylistExpander.expandPlaylist(this, t.uri))
            } else {
                expanded.add(t)
            }
        }
        val effectiveIndex = if (targetTrack != null) {
            val idx = expanded.indexOfFirst { uriMatches(it.uri, targetTrack.uri) }
            if (idx >= 0) idx else startIndex.coerceIn(0, (expanded.size - 1).coerceAtLeast(0))
        } else 0

        synchronized(playlist) {
            originalPlaylist.clear(); originalPlaylist.addAll(expanded)
            playlist.clear(); playlist.addAll(expanded)
            if (isShuffleEnabled) {
                val initial = expanded.getOrNull(effectiveIndex)
                playlist.shuffle()
                if (initial != null) {
                    playlist.remove(initial)
                    playlist.add(0, initial)
                }
                currentIndex = 0
            } else {
                currentIndex = effectiveIndex.coerceIn(0, (expanded.size - 1).coerceAtLeast(0))
            }
        }
        notifyPlaylistUpdated()
        startBackgroundMetadataScanner(forceRestart = true)
        if (playlist.isNotEmpty()) loadAndPlayCurrent()
    }

    fun updatePlaylistKeepCurrent(tracks: List<AudioTrack>, currentUri: Uri) {
        val expanded = mutableListOf<AudioTrack>()
        for (t in tracks) {
            if (PlaylistExpander.isPlaylist(t.uri, t.title)) {
                expanded.addAll(PlaylistExpander.expandPlaylist(this, t.uri))
            } else {
                expanded.add(t)
            }
        }
        synchronized(playlist) {
            val currentEnriched = currentTrack
            originalPlaylist.clear(); originalPlaylist.addAll(expanded)
            playlist.clear(); playlist.addAll(expanded)
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
        startBackgroundMetadataScanner(forceRestart = true)
    }

    fun enqueue(track: AudioTrack) {
        if (PlaylistExpander.isPlaylist(track.uri, track.title)) {
            val expanded = PlaylistExpander.expandPlaylist(this, track.uri)
            enqueueAll(expanded)
            return
        }
        val wasEmpty = synchronized(playlist) {
            val empty = playlist.isEmpty()
            playlist.add(track)
            originalPlaylist.add(track)
            empty
        }
        notifyPlaylistUpdated()
        startBackgroundMetadataScanner(forceRestart = true)
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
        startBackgroundMetadataScanner(forceRestart = true)
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
            cacheFailure = null
            val source = try { MediaSourceAdapter.stage(this, track.uri) } catch (e: Exception) {
                if (com.infinifolder.storage.DiskCache.capacityError(e) != null) cacheFailure = e to track.uri
                null
            }
            if (loadCounter.get() != thisLoadId) { source?.close(); return@Thread }
            cleanupStagedFile()
            stagedSource = source
            val rawStagedFile = source?.file
            val stagedFile = if (rawStagedFile != null && rawStagedFile.exists() && (track.startByte > 0L || track.lengthBytes > 0L)) {
                sliceTrackFile(rawStagedFile, track.startByte, track.lengthBytes)
            } else {
                rawStagedFile
            }
            stagedTempFile = stagedFile
            if (stagedFile == null || !stagedFile.exists()) {
                isLoading = false
                notifyPlaybackState(isPlaying = false, isLoading = false)
                notifyError("Unable to prepare audio track")
                return@Thread
            }
            val trackMime = try { contentResolver.getType(track.uri) } catch (_: Throwable) { null }
                ?: com.infinifolder.mime.MimeRegistry.guess(stagedFile.name)
            val formatInfo = probeAudioFormat(stagedFile, track)
            com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.startTrack(
                uri = track.uri,
                fileName = stagedFile.name,
                fileSize = stagedFile.length(),
                mimeType = trackMime
            ).apply {
                sampleRate = formatInfo.sampleRate
                channels = formatInfo.channels
                bitrateKbps = formatInfo.bitrateKbps
                isBitrateRelevant = formatInfo.isBitrateRelevant
                isSampleRateRelevant = formatInfo.isSampleRateRelevant
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
                var usedUniversalFallback = false

                val ext = stagedFile.extension.lowercase()
                val isAlacM4a = (ext == "m4a" || ext == "alac") && runCatching {
                    val head = ByteArray(128 * 1024)
                    val read = stagedFile.inputStream().use { it.read(head) }
                    com.infinifolder.audio.decoder.AlacAndMpeg2Decoder.canDecode(head.copyOf(read.coerceAtLeast(0)), ext)
                }.getOrDefault(false)

                val preferDecoder = ext in setOf(
                    "mp2", "alac", "mod", "s3m", "xm", "it", "mid", "midi", "kar", "rmi",
                    "sf2", "sfz", "pat", "dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid",
                    "dsf", "dff", "tta", "wv", "ra", "ac3", "eac3", "mlp", "thd", "wma",
                    "adx", "g722", "gsm", "caf", "voc", "au", "snd", "8svx", "svx", "16sv", "maud",
                    "w64", "rf64", "avr", "ircam", "sph", "pvf", "wve", "htk"
                ) || isAlacM4a

                if (preferDecoder) {
                    try {
                        val bytes = stagedFile.readBytes()
                        val pcm = com.infinifolder.audio.decoder.UniversalAudioDecoder.decode(bytes, ext, stagedFile)
                        if (pcm != null) {
                            val tempWav = java.io.File(cacheDir, "decoded_stream_${System.currentTimeMillis()}.wav")
                            pcm.writeWavFile(tempWav)
                            fis = java.io.FileInputStream(tempWav)
                            p.reset()
                            p.setDataSource(fis.fd, 0, tempWav.length())
                            p.prepare()
                            prepared = true
                            usedUniversalFallback = true
                            tempWav.deleteOnExit()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AudioPlayerService", "PreferDecoder fallback error: ${e.message}")
                    }
                }

                if (!prepared && stagedFile.canRead()) {
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
                if (!prepared) {
                    try {
                        val bytes = stagedFile.readBytes()
                        val pcm = com.infinifolder.audio.decoder.UniversalAudioDecoder.decode(bytes, stagedFile.extension, stagedFile)
                        if (pcm != null) {
                            val tempWav = java.io.File(cacheDir, "decoded_stream_${System.currentTimeMillis()}.wav")
                            pcm.writeWavFile(tempWav)
                            fis = java.io.FileInputStream(tempWav)
                            p.reset()
                            p.setDataSource(fis.fd, 0, tempWav.length())
                            p.prepare()
                            prepared = true
                            usedUniversalFallback = true
                            tempWav.deleteOnExit()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AudioPlayerService", "UniversalAudioDecoder fallback error: ${e.message}")
                    }
                }
                if (!prepared) {
                    com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackError("Unable to init MediaPlayer or any fallback decoder")
                    throw java.io.IOException("Unable to init MediaPlayer")
                }
                com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackStarted(
                    engine = if (usedUniversalFallback) "UniversalAudioDecoder -> MediaPlayer(WavWrap)" else "MediaPlayer(Native)",
                    sampleRate = formatInfo.sampleRate,
                    channels = formatInfo.channels,
                    bitrateKbps = formatInfo.bitrateKbps,
                    isBitrateRelevant = formatInfo.isBitrateRelevant,
                    isSampleRateRelevant = formatInfo.isSampleRateRelevant,
                    sourceFormat = if (!formatInfo.isBitrateRelevant) stagedFile.extension.uppercase() else if (formatInfo.bitrateKbps > 0) "${formatInfo.bitrateKbps} kbps" else null
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                    p.playbackParams = PlaybackParams().apply { speed = playbackSpeed }
                }
                p.setOnCompletionListener { finished ->
                    // Ignore completion events from a player that is no longer current
                    val isCurrent = synchronized(playerLock) { mediaPlayer === finished }
                    if (isCurrent) {
                        com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackCompleted()
                        onTrackCompleted()
                    }
                }
                p.setOnErrorListener { errored, what, extra ->
                    val isCurrent = synchronized(playerLock) { mediaPlayer === errored }
                    if (isCurrent) {
                        com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.recordPlaybackError("MediaPlayer Error: what=$what, extra=$extra")
                        notifyError("Playback error: $what, $extra")
                    }
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
                    startBackgroundMetadataScanner(forceRestart = false)
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

    data class AudioFormatInfo(
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitrateKbps: Int = 0,
        val isBitrateRelevant: Boolean = true,
        val isSampleRateRelevant: Boolean = true
    )

    private fun probeAudioFormat(file: File, track: AudioTrack): AudioFormatInfo {
        val ext = file.extension.lowercase().ifEmpty { track.title.substringAfterLast('.', "").lowercase() }

        val isTracker = ext in setOf(
            "mod", "s3m", "xm", "it", "med", "okt", "mtm", "669", "far", "amf", "dsm", "ult", "psm", "umx"
        )
        val isMidi = ext in setOf("mid", "midi", "kar", "rmi", "smf")
        val isChiptune = ext in setOf(
            "sid", "nsf", "spc", "vgm", "vgz", "gym", "ay", "ym", "gbs", "hes", "kss", "dro", "imf"
        )
        val isSoundfont = ext in setOf("sf2", "sfz", "pat")

        if (isMidi) {
            return AudioFormatInfo(
                sampleRate = 0,
                channels = 0,
                bitrateKbps = 0,
                isBitrateRelevant = false,
                isSampleRateRelevant = false
            )
        }

        if (isTracker || isChiptune || isSoundfont) {
            return AudioFormatInfo(
                sampleRate = 44100,
                channels = 2,
                bitrateKbps = 0,
                isBitrateRelevant = false,
                isSampleRateRelevant = true
            )
        }

        var sampleRate = 0
        var channels = 0
        var bitrateKbps = 0

        // 1. Try MediaMetadataRetriever
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                if (brStr != null) {
                    val b = brStr.toIntOrNull() ?: 0
                    if (b > 0) bitrateKbps = (b / 1000).coerceAtLeast(1)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val srStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    if (srStr != null) {
                        val s = srStr.toIntOrNull() ?: 0
                        if (s > 0) sampleRate = s
                    }
                }
            } finally {
                retriever.release()
            }
        }

        // 2. Try MediaExtractor for accurate sampleRate, channels, and bitrate
        if (sampleRate == 0 || channels == 0 || bitrateKbps == 0) {
            runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            if (sampleRate == 0 && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (channels == 0 && format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (bitrateKbps == 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                val b = format.getInteger(MediaFormat.KEY_BIT_RATE)
                                if (b > 0) bitrateKbps = (b / 1000).coerceAtLeast(1)
                            }
                            break
                        }
                    }
                } finally {
                    extractor.release()
                }
            }
        }

        // 3. Fallback for WAV / PCM RIFF header
        if ((sampleRate == 0 || channels == 0 || bitrateKbps == 0) && (ext == "wav" || ext == "wave" || ext == "riff")) {
            runCatching {
                file.inputStream().use { stream ->
                    val header = ByteArray(44)
                    if (stream.read(header) >= 44 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte()) {
                        val ch = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                        val sr = (header[24].toInt() and 0xFF) or
                                ((header[25].toInt() and 0xFF) shl 8) or
                                ((header[26].toInt() and 0xFF) shl 16) or
                                ((header[27].toInt() and 0xFF) shl 24)
                        val bps = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
                        if (ch in 1..8) channels = ch
                        if (sr in 4000..384000) sampleRate = sr
                        if (sr > 0 && ch > 0 && bps > 0) {
                            bitrateKbps = ((sr.toLong() * ch * bps) / 1000L).toInt().coerceAtLeast(1)
                        }
                    }
                }
            }
        }

        // 4. If bitrate is still not resolved, compute from file size and duration
        if (bitrateKbps == 0 && file.length() > 0) {
            val dur = track.durationMs
            if (dur > 0) {
                bitrateKbps = ((file.length() * 8L) / dur).toInt()
            }
        }

        if (sampleRate == 0) sampleRate = 44100
        if (channels == 0) channels = 2

        return AudioFormatInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitrateKbps = bitrateKbps,
            isBitrateRelevant = true,
            isSampleRateRelevant = true
        )
    }

    private fun enrichTrackMetadata(original: AudioTrack, file: File): AudioTrack {
        val formatInfo = probeAudioFormat(file, original)
        var title = original.title
        var artist = original.artist
        var album = original.album
        var duration = original.durationMs
        var artwork = original.artwork

        if (original.artist == null || original.album == null || original.artwork == null || duration <= 0) {
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

            if (duration <= 0) {
                runCatching {
                    val bytes = file.readBytes()
                    val pcm = com.infinifolder.audio.decoder.UniversalAudioDecoder.decode(bytes, file.extension, file)
                    if (pcm != null && pcm.durationMs > 0) {
                        duration = pcm.durationMs
                    }
                }
            }

            // Fallback: Check folder artwork (.icon.* then cover.*)
            if (artwork == null) {
                artwork = findFolderArtwork(original.uri)
            }
        }

        return original.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            artwork = artwork,
            bitrateKbps = if (formatInfo.isBitrateRelevant && formatInfo.bitrateKbps > 0) formatInfo.bitrateKbps else null,
            sampleRateHz = if (formatInfo.isSampleRateRelevant && formatInfo.sampleRate > 0) formatInfo.sampleRate else null,
            channels = formatInfo.channels.takeIf { it > 0 },
            isBitrateRelevant = formatInfo.isBitrateRelevant
        )
    }

    private fun findFolderArtwork(trackUri: Uri): Bitmap? {
        val parentUri = MediaSourceAdapter.getParentDirectory(this, trackUri) ?: return null
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
        if (stagedTempFile != null && stagedTempFile != stagedSource?.file) {
            runCatching { stagedTempFile?.delete() }
        }
        stagedSource?.close()
        stagedSource = null
        stagedTempFile = null
    }

    private fun sliceTrackFile(file: File, startByte: Long, lengthBytes: Long): File {
        val ext = file.extension.ifEmpty { "bin" }
        val sliceFile = File(cacheDir, "slice_${System.currentTimeMillis()}_${file.nameWithoutExtension}.$ext")
        val totalLen = file.length()
        val validStart = startByte.coerceIn(0L, totalLen)
        val validLen = if (lengthBytes > 0L) minOf(lengthBytes, totalLen - validStart) else (totalLen - validStart)

        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(validStart)
            java.io.FileOutputStream(sliceFile).use { fos ->
                val buf = ByteArray(65536)
                var remaining = validLen
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val r = raf.read(buf, 0, toRead)
                    if (r <= 0) break
                    fos.write(buf, 0, r)
                    remaining -= r
                }
            }
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
        if (com.infinifolder.audio.cast.CastManager.isCasting) {
            com.infinifolder.audio.cast.CastManager.onTrackChanged(track)
        }
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

    private var metadataScannerThread: Thread? = null
    private val metadataScannerGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    private fun startBackgroundMetadataScanner(forceRestart: Boolean = false) {
        if (!forceRestart && metadataScannerThread?.isAlive == true) {
            return
        }
        val gen = metadataScannerGeneration.incrementAndGet()
        try {
            metadataScannerThread?.interrupt()
        } catch (_: Exception) {}

        metadataScannerThread = Thread({
            // Start scanning quickly so the total length is calculated while playback begins
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return@Thread
            }

            while (gen == metadataScannerGeneration.get()) {
                val nextCandidate: Pair<Int, AudioTrack>? = synchronized(playlist) {
                    if (playlist.isEmpty()) null
                    else {
                        val cur = currentIndex
                        var candidateIdx = -1
                        // Prioritize scanning from cur + 1 until playlist.size
                        for (i in (cur + 1) until playlist.size) {
                            val t = playlist[i]
                            if (t.durationMs == 0L || PlaylistExpander.isPlaylist(t.uri, t.title)) {
                                candidateIdx = i
                                break
                            }
                        }
                        // Then check 0..cur
                        if (candidateIdx == -1) {
                            for (i in 0..cur) {
                                if (i in playlist.indices) {
                                    val t = playlist[i]
                                    if (t.durationMs == 0L || PlaylistExpander.isPlaylist(t.uri, t.title)) {
                                        candidateIdx = i
                                        break
                                    }
                                }
                            }
                        }
                        if (candidateIdx >= 0) candidateIdx to playlist[candidateIdx] else null
                    }
                }

                if (nextCandidate == null) break // All parsed!

                val (idx, track) = nextCandidate

                // Check if this candidate is actually a playlist file (e.g. .m3u, .pls, .cue, .xspf)
                if (PlaylistExpander.isPlaylist(track.uri, track.title)) {
                    val expanded = runCatching {
                        PlaylistExpander.expandPlaylist(this@AudioPlayerService, track.uri)
                    }.getOrNull() ?: emptyList()

                    if (gen != metadataScannerGeneration.get()) break

                    if (expanded.isNotEmpty()) {
                        synchronized(playlist) {
                            val pos = playlist.indexOfFirst { uriMatches(it.uri, track.uri) }
                            if (pos >= 0) {
                                playlist.removeAt(pos)
                                playlist.addAll(pos, expanded)
                            }
                            val origPos = originalPlaylist.indexOfFirst { uriMatches(it.uri, track.uri) }
                            if (origPos >= 0) {
                                originalPlaylist.removeAt(origPos)
                                originalPlaylist.addAll(origPos, expanded)
                            }
                        }
                        notifyPlaylistUpdated()
                        continue
                    }
                }

                val enriched = resolveTrackMetadataLight(track)

                if (gen != metadataScannerGeneration.get()) break

                if (enriched != null) {
                    synchronized(playlist) {
                        if (idx < playlist.size && uriMatches(playlist[idx].uri, track.uri)) {
                            playlist[idx] = enriched
                        } else {
                            val pIdx = playlist.indexOfFirst { uriMatches(it.uri, track.uri) }
                            if (pIdx >= 0) {
                                playlist[pIdx] = enriched
                            }
                        }
                        val origIdx = originalPlaylist.indexOfFirst { uriMatches(it.uri, track.uri) }
                        if (origIdx >= 0) {
                            originalPlaylist[origIdx] = enriched
                        }
                    }
                    notifyPlaylistUpdated()
                }

                // Brief pause between songs to ensure 100% smooth audio playback
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "PlaylistMetadataScanner").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun resolveTrackMetadataLight(track: AudioTrack): AudioTrack? {
        if (track.durationMs > 0L) return track
        var title = track.title
        var artist = track.artist
        var album = track.album
        var duration = track.durationMs

        // If this track is the currently playing track in MediaPlayer, query duration directly!
        if (uriMatches(track.uri, currentTrack?.uri)) {
            val liveDur = synchronized(playerLock) {
                runCatching { (mediaPlayer?.duration ?: 0).toLong() }.getOrDefault(0L)
            }
            if (liveDur > 0L) {
                duration = liveDur
            }
        }

        val uri = track.uri
        val file = if (uri.scheme == "file" || uri.scheme == null) {
            uri.path?.let { File(it) }?.takeIf { it.exists() }
        } else {
            MediaSourceAdapter.resolveLocalFile(this, uri)
        }

        if (duration <= 0L) {
            var pfd: android.os.ParcelFileDescriptor? = null
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                if (file != null && file.exists()) {
                    retriever.setDataSource(file.absolutePath)
                } else if (uri.scheme == "content") {
                    pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                    } else {
                        retriever.setDataSource(this, uri)
                    }
                } else {
                    retriever.setDataSource(this, uri)
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (durStr != null) {
                    val d = durStr.toLongOrNull() ?: 0L
                    if (d > 0) duration = d
                }
            } catch (_: Exception) {
            } finally {
                runCatching { retriever?.release() }
                runCatching { pfd?.close() }
            }
        }

        // Fallback for tracker modules or exotic formats if MediaMetadataRetriever didn't resolve duration
        if (duration <= 0L) {
            runCatching {
                val ext = file?.extension?.lowercase()
                    ?: track.title.substringAfterLast('.', "").lowercase()
                val trackerExts = setOf(
                    "mod", "s3m", "xm", "it", "mid", "midi", "kar", "rmi",
                    "dsf", "dff", "cdda", "wav", "flac", "mp2", "alac", "wv"
                )
                if (ext in trackerExts) {
                    val bytes = if (file != null && file.exists()) {
                        file.readBytes()
                    } else {
                        MediaSourceAdapter.readAllBytes(this, uri)
                    }
                    val pcm = com.infinifolder.audio.decoder.UniversalAudioDecoder.decode(bytes, ext, file)
                    if (pcm != null && pcm.durationMs > 0) {
                        duration = pcm.durationMs
                    }
                }
            }
        }

        // Final fallback: MediaPlayer lightweight probe on local file if available
        if (duration <= 0L && file != null && file.exists()) {
            runCatching {
                val mp = MediaPlayer()
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    val d = mp.duration.toLong()
                    if (d > 0) duration = d
                } finally {
                    mp.release()
                }
            }
        }

        val formatInfo = if (file != null && file.exists()) {
            probeAudioFormat(file, track)
        } else null

        val finalDur = if (duration > 0) duration else -1L
        return track.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = finalDur,
            bitrateKbps = if (formatInfo?.isBitrateRelevant == true && formatInfo.bitrateKbps > 0) formatInfo.bitrateKbps else null,
            sampleRateHz = if (formatInfo?.isSampleRateRelevant == true && formatInfo.sampleRate > 0) formatInfo.sampleRate else null,
            channels = formatInfo?.channels?.takeIf { it > 0 },
            isBitrateRelevant = formatInfo?.isBitrateRelevant ?: true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        try {
            metadataScannerGeneration.incrementAndGet()
            metadataScannerThread?.interrupt()
            metadataScannerThread = null
        } catch (_: Exception) {}
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}
        if (currentService == this) currentService = null
    }
}
