package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Choreographer
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.audio.cast.CastManager
import com.infinifolder.audio.cast.CastRouteActivity
import com.infinifolder.theme.AppThemeId
import com.infinifolder.theme.ThemeChangeListener
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager
import io.github.gretchycat.infinistream.ContentType
import io.github.gretchycat.infinistream.Operations
import io.github.gretchycat.infinistream.RuntimeCapabilities
import io.github.gretchycat.infinistream.StreamMetadata
import io.github.gretchycat.infinivideo.VideoCapabilities
import io.github.gretchycat.infinivideo.VideoPlayback
import io.github.gretchycat.infinivideo.VideoSurfacePlayback
import io.github.gretchycat.infiniselect.InfiniSelect
import io.github.gretchycat.infiniselect.MultipleSelectionDelivery
import io.github.gretchycat.infiniselect.OpenRequest
import io.github.gretchycat.infiniselect.SelectionMetadataFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val REQUEST_OPEN_AUDIO = 29163
private const val REQUEST_SAVE_PLAYLIST = 29164

private sealed interface PlaylistDisplayRow {
    data class DirectoryImage(val track: AudioTrack) : PlaylistDisplayRow
    data class Track(val index: Int, val track: AudioTrack) : PlaylistDisplayRow
}

private data class DirectoryImageTag(val track: AudioTrack, val coverKey: String?)
private data class PlaylistPreviewTag(val uri: String)

private class AspectRatio16x9FrameLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> resources.displayMetrics.widthPixels
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }.coerceAtLeast(1)
        val wantedHeight = (width * 9f / 16f).toInt().coerceAtLeast(1)
        val heightLimit = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST && heightLimit > 0)
            minOf(wantedHeight, heightLimit) else wantedHeight
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }
}

class AudioPlayerActivity : ViewerActivity(), AudioPlayerListener, ThemeChangeListener, DebugDumpObserver, CastManager.Listener {

    private val playlistLoadGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val playlistUiLoadGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val playlistUiLoadHandler = Handler(Looper.getMainLooper())
    @Volatile private var activePlaylistUiLoad = 0L
    @Volatile private var playlistUiLoadStartedAt = 0L
    @Volatile private var playlistUiLoadStage = ""
    @Volatile private var playlistUiLastLoggedStage = ""
    private var playlistUiExpectedTracks = -1
    private var playlistUiExpectedRows = -1
    private var playlistUiLayoutWaiter = 0L
    private var playlistUiCompletionToast: String? = null
    private val playlistUiElapsedTicker = object : Runnable {
        override fun run() {
            val loadId = activePlaylistUiLoad
            if (loadId == 0L || !::dockedPlaylistLoadingMessage.isInitialized) return
            val elapsed = ((android.os.SystemClock.elapsedRealtime() - playlistUiLoadStartedAt) / 1000L).coerceAtLeast(0L)
            dockedPlaylistLoadingMessage.text = "${playlistUiLoadStage}\n${elapsed}s"
            playlistUiLoadHandler.postDelayed(this, 1000L)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (CastManager.hasConnectedSession &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                CastManager.adjustVolume(if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            // Keep these keys from changing the handset volume while controlling a receiver.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private lateinit var audioManager: AudioManager

    private lateinit var rootLayout: LinearLayout
    private lateinit var topInsetArea: View
    private lateinit var topBar: LinearLayout
    private lateinit var headerText: TextView
    private lateinit var backBtn: TextView
    private lateinit var themeBtn: TextView
    private lateinit var soundFontBtn: TextView
    private lateinit var playlistBtn: TextView
    private lateinit var addToPlaylistBtn: TextView
    private lateinit var castBtn: TextView
    private lateinit var openExtBtn: TextView
    private lateinit var visualPaneBtn: TextView

    private lateinit var consoleCard: LinearLayout
    private lateinit var timerLedView: TextView
    private lateinit var stereoBadge: TextView
    private lateinit var kbpsBadge: TextView
    private lateinit var khzBadge: TextView

    private lateinit var artCard: FrameLayout
    private lateinit var albumArtView: ImageView
    private lateinit var visualPaneTitleText: TextView
    private lateinit var visualPaneDetailsText: TextView
    private lateinit var visualPaneVisualizerView: VisualizerView
    private lateinit var visualizerView: VisualizerView
    private lateinit var trackTitleText: TextView
    private lateinit var trackArtistText: TextView
    private lateinit var technicalInfoText: TextView

    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var progressBar: SeekBar

    private lateinit var prevBtn: Button
    private lateinit var playPauseBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var shuffleBtn: Button
    private lateinit var repeatBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var volumeBar: SeekBar
    private lateinit var loadingIndicator: ProgressBar

    private lateinit var winampMainWindowView: WinampMainWindowView
    private lateinit var skinContainer: LinearLayout
    private lateinit var skinLeftColumn: LinearLayout
    private lateinit var skinRightColumn: FrameLayout
    private lateinit var skinSpareColumn: FrameLayout
    private lateinit var skinDockedStack: LinearLayout
    private lateinit var dockedVisualWindow: LinearLayout
    private lateinit var dockedVisualTitle: TextView
    private lateinit var dockedVisualBody: FrameLayout
    private lateinit var detailsWindow: LinearLayout
    private lateinit var detailsTitle: WinampPlaylistTitleView
    private lateinit var detailsBody: LinearLayout
    private lateinit var detailsContent: LinearLayout
    private lateinit var detailsHeader: TextView
    private lateinit var detailsBottom: WinampPlaylistBottomView
    private lateinit var dockedCenterCol: LinearLayout
    private lateinit var dockedMetaRow: LinearLayout
    private lateinit var dockedToolScroll: HorizontalScrollView
    private lateinit var nonSkinScroll: ScrollView
    private lateinit var nonSkinColumn: LinearLayout
    private lateinit var dockedSubWindow: LinearLayout
    private lateinit var dockedSubTitleStrip: LinearLayout
    private lateinit var dockedTitleBarView: WinampPlaylistTitleView
    private lateinit var dockedBodyRow: LinearLayout
    private lateinit var dockedLeftBorder: WinampPlaylistBorderView
    private lateinit var dockedRightBorder: WinampPlaylistBorderView
    private lateinit var dockedPlaylistScroll: ScrollView
    private lateinit var dockedPlaylistLoadingOverlay: FrameLayout
    private lateinit var dockedPlaylistLoadingMessage: TextView
    private lateinit var dockedBottomBarView: WinampPlaylistBottomView
    private lateinit var mainColumn: LinearLayout
    private lateinit var dockedAlbumArt: ImageView
    private lateinit var dockedTrackTitle: TextView
    private lateinit var dockedTrackArtist: TextView
    private lateinit var dockedTrackDetails: TextView
    private lateinit var dockedSpeedBtn: Button
    private lateinit var dockedDiagnosticsBtn: Button
    private var dockedCastBtn: Button? = null
    private var dockedAddToPlaylistBtn: Button? = null
    private var dockedRemoveFromPlaylistBtn: Button? = null
    private var dockedSavePlaylistBtn: Button? = null
    private var selectedPlaylistIndex = -1
    private var playlistSelectionPending = false
    private val iconThemeManager by lazy { com.infinifolder.theme.IconThemeManager(applicationContext) }
    private val directoryCoverCache = object : android.util.LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pendingDirectoryCovers = ConcurrentHashMap.newKeySet<String>()
    private val failedDirectoryCovers = ConcurrentHashMap.newKeySet<String>()
    private val directoryCoverExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AudioDirectoryCoverLoader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private lateinit var dockedPlaylistHeader: TextView
    private lateinit var dockedPlaylistFooter: TextView
    private lateinit var dockedPlaylistItems: LinearLayout
    private var lastAutoScrolledDockedTrack: String? = null
    private lateinit var progressContainer: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var secondaryRow: LinearLayout
    private lateinit var volumeRow: LinearLayout
    private lateinit var wmpPlayerLayout: WmpPlayerLayout
    private lateinit var standardSeekThumb: android.graphics.drawable.Drawable
    private var visualizerBridge: RealAudioVisualizerBridge? = null
    private var winampVisualPaneOpen = true
    private var visualVideoPlayback: VideoSurfacePlayback? = null
    private var visualVideoInput: VideoPlaybackInput? = null
    private var visualVideoPlaying = false
    private var visualVideoWasPlayingBeforePause = false
    private var activeVisualTrackKey: String? = null
    private val visualLoadGeneration = java.util.concurrent.atomic.AtomicLong()
    private val visualImageAdvanceHandler = Handler(Looper.getMainLooper())
    private val visualImageAdvanceRunnable = object : Runnable {
        override fun run() {
            val service = AudioPlayerService.currentService
            if (service?.currentTrack?.mediaKind == PlaylistMediaKind.IMAGE) {
                if (service.repeatMode == RepeatMode.ONE) visualImageAdvanceHandler.postDelayed(this, 5_000L)
                else service.onVisualItemCompleted()
            }
        }
    }
    private val visualMediaExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "InfiniMediaVisualLoader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val visualImageCache = object : android.util.LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val visualAnimationCache = ConcurrentHashMap<String, android.graphics.drawable.Drawable>()
    private val visualBitmapAnimationCache = ConcurrentHashMap<String, com.infinifolder.preview.BitmapAnimation>()
    private var visualImagePlayback: PlaylistImageAnimationPlayback? = null
    private var isPlayerResumed = false
    private val visualizerBands = FloatArray(16)
    private var progressFramePosted = false
    private val progressFrameCallback = Choreographer.FrameCallback {
        progressFramePosted = false
        if (!isPlayerResumed) return@FrameCallback
        val service = AudioPlayerService.currentService ?: return@FrameCallback
        updateDisplayedProgress(service)
        if (service.isPlaying || visualVideoPlayback?.isPlaying == true) scheduleProgressFrame()
    }

    private var isUserSeeking = false
    private var showRemainingTime = false

    private var systemInsetLeft = 0
    private var systemInsetTop = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun updateRootPadding() {
        if (!::rootLayout.isInitialized) return
        val theme = ThemeManager.getAudioPlayerTheme(this)
        val isWinamp = (theme.id == AppThemeId.WINAMP_CLASSIC || theme.id == AppThemeId.CUSTOM_WSZ)
        val isWmp = theme.id == AppThemeId.WMP_GLASS
        val sidePadding = if (isWmp) 0 else dp(12)
        val padH = if (isWinamp) 0 else (sidePadding + systemInsetLeft)
        val padRight = if (isWinamp) 0 else (sidePadding + systemInsetRight)
        val effectiveTop = if (systemInsetTop > 0) systemInsetTop else getStatusBarHeight()
        val isSystemDark = ThemeManager.resolveIsDark(this)
        if (::topInsetArea.isInitialized) {
            topInsetArea.layoutParams = LinearLayout.LayoutParams(-1, effectiveTop)
            topInsetArea.setBackgroundColor(if (isSystemDark) Color.BLACK else Color.WHITE)
        }
        val padT = if (isWinamp || isWmp) 0 else dp(6)
        val padB = (if (isWinamp || isWmp) 0 else dp(16)) + systemInsetBottom
        rootLayout.setPadding(padH, padT, padRight, padB)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)

        // Start warming the selected FluidLite bank while the player UI and
        // playlist/source are being prepared. The service repeats this safely
        // when it starts; FluidLite keeps one resident bank per process.
        com.infinifolder.audio.AudioProcessing.initialize(applicationContext)
        MidiSoundFontSettings.apply(applicationContext)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupUI()
        val audioTheme = ThemeManager.getAudioPlayerTheme(this)
        applyTheme(audioTheme)

        AudioPlayerService.addListener(this)
        ThemeManager.addListener(this)
        CastManager.addListener(this)

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isPlayerResumed = true
        applyTheme(ThemeManager.getAudioPlayerTheme(this))
        if (visualVideoWasPlayingBeforePause && AudioPlayerService.currentService?.currentTrack?.mediaKind == PlaylistMediaKind.VIDEO) {
            visualVideoPlayback?.play()
        }
        if (AudioPlayerService.currentService?.currentTrack?.mediaKind == PlaylistMediaKind.IMAGE) {
            (albumArtView.drawable as? android.graphics.drawable.Animatable)?.start()
            visualImagePlayback?.play()
        }
        visualVideoWasPlayingBeforePause = false
        updateCastButtonState()
        AudioPlayerService.currentService?.let {
            updateVisualizerCapture(it.audioSessionId)
            updateUIFromService(it)
            it.cacheFailure?.let { failure ->
                it.cacheFailure = null
                CacheFailureUi.offerCopy(this, failure.first, failure.second)
            }
        }
    }

    override fun onPause() {
        isPlayerResumed = false
        visualVideoWasPlayingBeforePause = visualVideoPlayback?.isPlaying == true
        if (visualVideoWasPlayingBeforePause) visualVideoPlayback?.pause()
        (albumArtView.drawable as? android.graphics.drawable.Animatable)?.stop()
        visualImagePlayback?.pause()
        AudioPlayerService.currentService?.prioritizeMetadataTracks(emptyList())
        stopProgressFrames()
        visualizerBridge?.detach()
        if (::visualizerView.isInitialized) visualizerView.updateData(FloatArray(16))
        if (::visualPaneVisualizerView.isInitialized) visualPaneVisualizerView.updateData(FloatArray(16))
        super.onPause()
    }

    private fun updateVisualizerCapture(sessionId: Int, explicitRequest: Boolean = false) {
        val service = AudioPlayerService.currentService
        val source = service?.analysisSource
        if (!isPlayerResumed || (!CastManager.isCasting && sessionId <= 0) || source == null) {
            visualizerBridge?.detach()
            if (::visualizerView.isInitialized) visualizerView.updateData(FloatArray(16))
            if (::visualPaneVisualizerView.isInitialized) visualPaneVisualizerView.updateData(FloatArray(16))
            return
        }
        visualizerBridge?.attach(source) {
            if (CastManager.isCasting) CastManager.isRemotePlaying to CastManager.analysisPositionMs
            else service.isPlaying to service.currentPosition.toLong()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        configureSkinLayout()
        applyTheme(ThemeManager.getAudioPlayerTheme(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        retainPersistableReadGrants(intent)
        val targetUri = intent?.data ?: intent?.getStringExtra("uri")?.let { Uri.parse(it) }
        if (targetUri == null) {
            val restoreIntent = Intent(this, AudioPlayerService::class.java).apply {
                action = AudioPlayerService.ACTION_RESTORE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restoreIntent)
            else startService(restoreIntent)
            return
        }
        val generation = playlistLoadGeneration.incrementAndGet()
        val displayName = intent?.getStringExtra("displayName") ?: targetUri.lastPathSegment ?: "Audio Track"

        val serviceIntent = Intent(this, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_LOAD
            data = targetUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("uri", targetUri.toString())
            putExtra("name", displayName)
            putExtra("mimeType", intent?.getStringExtra("mimeType") ?: intent?.type)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Thread {
            val tracks = mutableListOf<AudioTrack>()
            val playlistMime = intent?.getStringExtra("mimeType") ?: intent?.type
            val isDirectPlaylist = PlaylistExpander.isPlaylist(this, targetUri, displayName, playlistMime)

            if (isDirectPlaylist) {
                val playlistTracks = PlaylistExpander.expandPlaylist(this, targetUri)
                tracks.addAll(playlistTracks)
            } else {
                val parentUri = intent?.getStringExtra("parentUri")?.let { Uri.parse(it) }
                    ?: intent?.getStringExtra("extra_parent_uri")?.let { Uri.parse(it) }
                    ?: MediaSourceAdapter.getParentDirectory(this, targetUri)
                if (parentUri != null) {
                    try {
                        val siblings = MediaSourceAdapter.listFolder(this, parentUri)
                        val queueMultimedia = intent?.getBooleanExtra("queueMultimedia", false) == true
                        tracks.addAll(PlaylistExpander.expandResourceEntries(this, siblings, queueMultimedia = queueMultimedia))
                    } catch (_: Exception) {}
                }
            }

            if (isDirectPlaylist) {
                com.infinifolder.storage.DiagnosticLog.record("audio.playlist.queued",
                    "format=${displayName.substringAfterLast('.', "").lowercase()} tracks=${tracks.size}")
                if (tracks.isEmpty()) runOnUiThread {
                    Toast.makeText(this, "No tracks found in this playlist", Toast.LENGTH_SHORT).show()
                }
            }

            if (tracks.none { AudioPlayerService.uriMatches(it.uri, targetUri) }) {
                if (!isDirectPlaylist) {
                    tracks.add(0, AudioTrack(targetUri, displayName))
                }
            }

            var startIndex = tracks.indexOfFirst { AudioPlayerService.uriMatches(it.uri, targetUri) }
            if (startIndex < 0) startIndex = 0
            if (generation != playlistLoadGeneration.get()) return@Thread

            runOnUiThread {
                if (generation != playlistLoadGeneration.get()) return@runOnUiThread
                fun applyPlaylistWhenServiceReady(attempt: Int) {
                    if (generation != playlistLoadGeneration.get() || isFinishing || isDestroyed) return
                    val service = AudioPlayerService.currentService
                    if (service == null) {
                        if (attempt < 20) {
                            window.decorView.postDelayed({ applyPlaylistWhenServiceReady(attempt + 1) }, 100)
                        } else {
                            com.infinifolder.storage.DiagnosticLog.record("audio.playlist.service_unavailable",
                                "format=${displayName.substringAfterLast('.', "").lowercase()}")
                        }
                        return
                    }
                    val isSameTrack = AudioPlayerService.uriMatches(service.currentTrack?.uri, targetUri)
                    // The service may still regard the playlist document itself as
                    // current (for example when its URI had no useful extension and
                    // only the Activity's content sniff identified XSPF). Replacing
                    // the queue must discard that document, not preserve it as the
                    // active item at the end of the expanded track list.
                    if (isSameTrack && !isDirectPlaylist) {
                        service.updatePlaylistKeepCurrent(tracks, targetUri)
                        updateUIFromService(service)
                    } else {
                        service.setPlaylist(tracks, startIndex, preservePlaylistOrder = isDirectPlaylist)
                    }
                }
                applyPlaylistWhenServiceReady(0)
            }
        }.start()
    }

    private fun openMediaSelection(includeAudio: Boolean, includeVideo: Boolean, includeImage: Boolean) {
        if (isFinishing || isDestroyed) return
        playlistSelectionPending = true
        val formats = buildList {
            if (includeAudio) addAll(runCatching {
                RuntimeCapabilities.library("sound").supportedFormats(Operations.PLAY).map { it.format }
            }.getOrDefault(emptyList()))
            if (includeImage) addAll(runCatching {
                RuntimeCapabilities.library("canvas").supportedFormats(Operations.THUMBNAIL).map { it.format }
            }.getOrDefault(emptyList()))
            if (includeVideo) addAll(runCatching {
                RuntimeCapabilities.library("video").supportedFormats(Operations.PLAY).map { it.format }
            }.getOrDefault(emptyList()))
        }
        val categoryMimes = buildList {
            if (includeAudio) add("audio/*")
            if (includeVideo) add("video/*")
            if (includeImage) add("image/*")
        }
        val mimes = (categoryMimes + formats.flatMap { it.mimeTypes })
            .filter { it.isNotBlank() && it != "*/*" }.distinct().sorted()
        val extensions = formats.flatMap { it.extensions }
            .map { it.removePrefix(".").lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().sorted()
        InfiniSelect.open(this, OpenRequest(
            clientId = "infinimedia",
            displayName = "InfiniMedia",
            mimeTypes = mimes,
            allowMultiple = true,
            supportedExtensions = extensions,
            hideUnsupported = extensions.isNotEmpty(),
            multipleSelectionDelivery = MultipleSelectionDelivery.JSPF,
            metadataFormat = SelectionMetadataFormat.XML,
            allowRecursiveAudioDirectories = includeAudio,
            progressReceiver = object : android.os.ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (!playlistSelectionPending) return
                    val data = resultData ?: Bundle()
                    val folders = data.getInt(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_PROGRESS_FOLDERS)
                    val tracks = data.getInt(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_PROGRESS_TRACKS)
                    when (resultCode) {
                        io.github.gretchycat.infiniselect.FileSelectionContract.RESULT_LOADING_STARTED ->
                            showPlaylistLoading("Scanning media files…")
                        io.github.gretchycat.infiniselect.FileSelectionContract.RESULT_LOADING_PROGRESS -> {
                            val label = data.getString(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_PROGRESS_LABEL)
                            showPlaylistLoading(label?.takeIf { it.startsWith("Using ") }
                                ?: "Scanning folders…  $folders folders · $tracks items")
                        }
                        io.github.gretchycat.infiniselect.FileSelectionContract.RESULT_LOADING_FAILED -> {
                            com.infinifolder.storage.DiagnosticLog.record(
                                "audio.playlist.selection.failed",
                                "folders=$folders tracks=$tracks label=${data.getString(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_PROGRESS_LABEL).orEmpty()}")
                            hidePlaylistLoading()
                            Toast.makeText(this@AudioPlayerActivity,
                                data.getString(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_PROGRESS_LABEL)
                                    ?: "No supported media files were found", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
        ), REQUEST_OPEN_AUDIO)
    }

    private fun showPlaylistLoading(message: String) {
        if (!::dockedPlaylistLoadingOverlay.isInitialized || isFinishing || isDestroyed) return
        dockedPlaylistLoadingMessage.text = message
        dockedPlaylistLoadingOverlay.visibility = View.VISIBLE
    }

    private fun beginPlaylistUiLoad(message: String): Long {
        val loadId = playlistUiLoadGeneration.incrementAndGet()
        activePlaylistUiLoad = loadId
        playlistUiLoadStartedAt = android.os.SystemClock.elapsedRealtime()
        playlistUiLoadStage = ""
        playlistUiLastLoggedStage = ""
        playlistUiExpectedTracks = -1
        playlistUiExpectedRows = -1
        playlistUiLayoutWaiter = 0L
        playlistUiCompletionToast = null
        playlistUiLoadHandler.removeCallbacks(playlistUiElapsedTicker)
        setPlaylistUiLoadStage(loadId, message, "phase=begin")
        playlistUiLoadHandler.postDelayed(playlistUiElapsedTicker, 1000L)
        return loadId
    }

    private fun setPlaylistUiLoadStage(loadId: Long, message: String, detail: String = "", logStage: Boolean = true) {
        if (loadId == 0L || loadId != activePlaylistUiLoad) return
        val elapsedMs = (android.os.SystemClock.elapsedRealtime() - playlistUiLoadStartedAt).coerceAtLeast(0L)
        playlistUiLoadStage = message
        if (logStage && playlistUiLastLoggedStage != message) {
            playlistUiLastLoggedStage = message
            com.infinifolder.storage.DiagnosticLog.record(
                "audio.playlist.loading.stage",
                "generation=$loadId elapsedMs=$elapsedMs stage=$message $detail")
        }
        runOnUiThread {
            if (loadId != activePlaylistUiLoad || !::dockedPlaylistLoadingOverlay.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            val elapsed = ((android.os.SystemClock.elapsedRealtime() - playlistUiLoadStartedAt) / 1000L).coerceAtLeast(0L)
            dockedPlaylistLoadingMessage.text = "$message\n${elapsed}s"
            dockedPlaylistLoadingOverlay.visibility = View.VISIBLE
        }
    }

    private fun completePlaylistUiLoad(loadId: Long, outcome: String) {
        if (loadId == 0L || loadId != activePlaylistUiLoad) return
        val elapsedMs = (android.os.SystemClock.elapsedRealtime() - playlistUiLoadStartedAt).coerceAtLeast(0L)
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.playlist.loading.$outcome",
            "generation=$loadId elapsedMs=$elapsedMs tracks=$playlistUiExpectedTracks rows=$playlistUiExpectedRows")
        val completionToast = playlistUiCompletionToast
        activePlaylistUiLoad = 0L
        playlistUiLoadStartedAt = 0L
        playlistUiLoadStage = ""
        playlistUiLastLoggedStage = ""
        playlistUiCompletionToast = null
        playlistUiLoadHandler.removeCallbacks(playlistUiElapsedTicker)
        if (::dockedPlaylistLoadingOverlay.isInitialized) dockedPlaylistLoadingOverlay.visibility = View.GONE
        if (!completionToast.isNullOrBlank() && !isFinishing && !isDestroyed) {
            Toast.makeText(this, completionToast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hidePlaylistLoading() {
        activePlaylistUiLoad = 0L
        playlistUiLoadStartedAt = 0L
        playlistUiLoadStage = ""
        playlistUiLastLoggedStage = ""
        playlistUiCompletionToast = null
        playlistUiLoadHandler.removeCallbacks(playlistUiElapsedTicker)
        if (::dockedPlaylistLoadingOverlay.isInitialized) dockedPlaylistLoadingOverlay.visibility = View.GONE
    }

    private fun retainPersistableReadGrants(source: Intent?) {
        source ?: return
        if (source.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        val flags = source.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags == 0) return
        val uris = linkedSetOf<Uri>()
        source.data?.let(uris::add)
        source.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add) }
        source.getStringArrayListExtra(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_RESULT_URIS)
            ?.mapNotNullTo(uris) { runCatching { Uri.parse(it) }.getOrNull() }
        uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, flags) } }
    }

    private fun savePlaylistExternally() {
        val service = AudioPlayerService.currentService ?: return
        val snapshot = synchronized(service.playlist) { service.playlist.toList() }
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "The playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val nameInput = android.widget.EditText(this).apply {
            setSingleLine(true)
            setText("InfiniMedia Playlist.jspf")
            setSelection(text.length)
            hint = "Playlist filename"
        }
        ThemeManager.createDialogBuilder(this)
            .setTitle("Save playlist")
            .setMessage("Choose a filename and destination folder.")
            .setView(nameInput)
            .setPositiveButton("Choose location") { _, _ ->
                val enteredName = nameInput.text.toString().trim().ifBlank { "InfiniMedia Playlist.jspf" }
                val name = if (enteredName.endsWith(".jspf", ignoreCase = true)) enteredName else "$enteredName.jspf"
                val bytes = runCatching { AudioPlaylistJspf.encode(snapshot, "InfiniMedia Playlist") }.getOrElse {
                    Toast.makeText(this, "Could not create playlist: ${it.message ?: "encoding failed"}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val source = runCatching {
                    InfiniSelect.createDocument(this, name, AudioPlaylistJspf.MIME_TYPE, bytes)
                }.getOrElse {
                    Toast.makeText(this, "Could not prepare playlist for saving: ${it.message ?: "failed"}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val request = runCatching { io.github.gretchycat.infiniselect.SaveRequest(
                    clientId = "infinimedia",
                    displayName = "InfiniMedia",
                    fileName = name,
                    mimeType = AudioPlaylistJspf.MIME_TYPE,
                    sourceUri = source,
                    sourceSizeBytes = bytes.size.toLong(),
                ) }.getOrElse {
                    Toast.makeText(this, it.message ?: "Invalid filename", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                runCatching { InfiniSelect.save(this, request, REQUEST_SAVE_PLAYLIST) }
                    .onFailure { Toast.makeText(this, "Could not open a save location: ${it.message ?: "unavailable"}", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMediaCategoryDialog() {
        val categories = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val audio = android.widget.CheckBox(this).apply {
            text = "Audio"
            isChecked = true
        }
        val video = android.widget.CheckBox(this).apply {
            text = "Video"
        }
        val image = android.widget.CheckBox(this).apply {
            text = "Image"
        }
        categories.addView(audio)
        categories.addView(video)
        categories.addView(image)
        ThemeManager.createDialogBuilder(this)
            .setTitle("Add media to playlist")
            .setView(categories)
            .setPositiveButton("Choose files") { _, _ ->
                if (audio.isChecked || video.isChecked || image.isChecked) {
                    openMediaSelection(audio.isChecked, video.isChecked, image.isChecked)
                } else Toast.makeText(this, "Select at least one media type", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Add the chooser result to the existing queue; it never replaces the active playlist. */
    private fun enqueueSelection(data: Intent, loadId: Long) {
        setPlaylistUiLoadStage(loadId, "Reading selected media…", "phase=read-selection")
        val selectionStartedAt = android.os.SystemClock.elapsedRealtime()
        val selectionResult = runCatching { InfiniSelect.readResult(this, data) }
        val selection = selectionResult.getOrNull()
        com.infinifolder.storage.DiagnosticLog.record(
            "audio.playlist.selection.received",
            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - selectionStartedAt} items=${selection?.items?.size ?: 0} hasPlaylist=${selection?.playlistUri != null}",
            selectionResult.exceptionOrNull())
        val items = selection?.items.orEmpty()
        if (items.isEmpty() && selection?.playlistUri == null) {
            completePlaylistUiLoad(loadId, "empty")
            Toast.makeText(this, "No media files were selected", Toast.LENGTH_SHORT).show()
            return
        }
        setPlaylistUiLoadStage(loadId, "Preparing ${items.size} selected item${if (items.size == 1) "" else "s"}…", "phase=prepare")
        Thread {
            try {
                val preparationStartedAt = android.os.SystemClock.elapsedRealtime()
                val tracks = mutableListOf<AudioTrack>()
                val selectedPlaylist = selection?.playlistUri
                val isGeneratedSelectionPlaylist = data.hasExtra(
                    io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_RESULT_PLAYLIST_URI)
                var sortElapsedMs = 0L
                var playlistExpansionElapsedMs = 0L
                var playlistItemsFound = 0
                if (selectedPlaylist != null && !isGeneratedSelectionPlaylist) {
                    // A playlist file's order is authored content. Keep its track order
                    // intact when appending it to the current queue.
                    setPlaylistUiLoadStage(loadId, "Reading playlist document…", "phase=expand-playlist")
                    val expansionStartedAt = android.os.SystemClock.elapsedRealtime()
                    playlistItemsFound = 1
                    tracks += PlaylistExpander.expandPlaylist(this, selectedPlaylist)
                    playlistExpansionElapsedMs = android.os.SystemClock.elapsedRealtime() - expansionStartedAt
                    com.infinifolder.storage.DiagnosticLog.record(
                        "audio.playlist.prepare.playlist-expanded",
                        "source=selected-playlist tracks=${tracks.size} elapsedMs=$playlistExpansionElapsedMs")
                } else {
                    // Recursive selections stay grouped by their source directory, with
                    // each album's files alphabetical inside the group. This keeps one
                    // directory image row ahead of that album instead of interleaving
                    // equally named track numbers from different albums.
                    setPlaylistUiLoadStage(loadId, "Sorting ${items.size} selected items…", "phase=sort")
                    val sortStartedAt = android.os.SystemClock.elapsedRealtime()
                    val recursive = items.any { it.directoryUri != null && it.directoryArtworkMetadataResolved }
                    val orderedItems = if (recursive) {
                        items.sortedWith(compareBy<io.github.gretchycat.infiniselect.SelectionItem>(
                            { it.directoryName.orEmpty().lowercase(Locale.ROOT) },
                            { it.directoryUri?.toString().orEmpty() },
                            { it.name.lowercase(Locale.ROOT) },
                            { it.name },
                        ))
                    } else {
                        items.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.name }))
                    }
                    sortElapsedMs = android.os.SystemClock.elapsedRealtime() - sortStartedAt
                    com.infinifolder.storage.DiagnosticLog.record(
                        "audio.playlist.prepare.sorted",
                        "items=${items.size} recursive=$recursive elapsedMs=$sortElapsedMs")

                    for (item in orderedItems) {
                        // Choose playlist handling from the filename extension only. The
                        // post-enqueue metadata work validates the actual content; probing
                        // each URI here can turn a multi-file selection into hundreds of
                        // slow provider reads before playback starts.
                        val itemIsPlaylist = PlaylistExpander.isPlaylist(item.name)
                        if (itemIsPlaylist) {
                            playlistItemsFound++
                            val before = tracks.size
                            val expansionStartedAt = android.os.SystemClock.elapsedRealtime()
                            tracks += PlaylistExpander.expandPlaylist(this, item.uri)
                            val expansionElapsedMs = android.os.SystemClock.elapsedRealtime() - expansionStartedAt
                            playlistExpansionElapsedMs += expansionElapsedMs
                            com.infinifolder.storage.DiagnosticLog.record(
                                "audio.playlist.prepare.playlist-expanded",
                                "name=${item.name} added=${tracks.size - before} elapsedMs=$expansionElapsedMs")
                        } else {
                            val kind = when (MediaTypeDetector.detect(item.name, item.mimeType)) {
                                MediaType.IMAGE -> PlaylistMediaKind.IMAGE
                                MediaType.VIDEO -> PlaylistMediaKind.VIDEO
                                else -> PlaylistMediaKind.AUDIO
                            }
                            tracks += AudioTrack(uri = item.uri, title = item.name, fileName = item.name,
                                directoryUri = item.directoryUri, directoryName = item.directoryName,
                                directoryCoverUri = item.directoryCoverUri,
                                directoryArtworkMetadataResolved = item.directoryArtworkMetadataResolved,
                                mediaKind = kind, sourceMimeType = item.mimeType)
                        }
                    }
                }
                com.infinifolder.storage.DiagnosticLog.record(
                    "audio.playlist.selection.prepared",
                    "items=${items.size} tracks=${tracks.size} playlistItems=$playlistItemsFound sortMs=$sortElapsedMs playlistExpansionMs=$playlistExpansionElapsedMs elapsedMs=${android.os.SystemClock.elapsedRealtime() - preparationStartedAt}")
                if (loadId != activePlaylistUiLoad) return@Thread
                setPlaylistUiLoadStage(loadId, "Sending ${tracks.size} tracks to the player…", "phase=prepared")
                runOnUiThread {
                    if (isFinishing || isDestroyed || loadId != activePlaylistUiLoad) return@runOnUiThread
                    if (tracks.isEmpty()) {
                        completePlaylistUiLoad(loadId, "empty")
                        Toast.makeText(this, "No supported media was found", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    try {
                        val serviceIntent = Intent(this, AudioPlayerService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                        else startService(serviceIntent)
                        setPlaylistUiLoadStage(loadId, "Waiting for the media service…", "phase=service-start")
                        fun addWhenReady(attempt: Int) {
                            if (isFinishing || isDestroyed || loadId != activePlaylistUiLoad) return
                            val service = AudioPlayerService.currentService
                            if (service == null) {
                                if (attempt < 20) {
                                    if (attempt == 0 || attempt == 10) {
                                        setPlaylistUiLoadStage(loadId, "Waiting for the media service…", "phase=service-wait attempt=$attempt")
                                    }
                                    window.decorView.postDelayed({ addWhenReady(attempt + 1) }, 100)
                                } else {
                                    completePlaylistUiLoad(loadId, "service-unavailable")
                                    Toast.makeText(this, "The media queue could not be started", Toast.LENGTH_SHORT).show()
                                }
                                return
                            }
                            setPlaylistUiLoadStage(loadId, "Adding ${tracks.size} tracks to the playlist…", "phase=enqueue")
                            service.enqueueAll(tracks)
                            if (selectedPlaylistIndex !in service.playlist.indices) selectedPlaylistIndex = service.currentIndex
                            val queued = synchronized(service.playlist) { ArrayList(service.playlist) }
                            playlistUiExpectedTracks = queued.size
                            playlistUiExpectedRows = playlistDisplayRows(queued).size
                            playlistUiCompletionToast = "Added ${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}"
                            setPlaylistUiLoadStage(loadId, "Showing ${queued.size} tracks…", "phase=rows-queued expectedRows=$playlistUiExpectedRows")
                        }
                        addWhenReady(0)
                    } catch (error: Exception) {
                        com.infinifolder.storage.DiagnosticLog.record("audio.playlist.selection.enqueue-failed", error = error)
                        completePlaylistUiLoad(loadId, "failed")
                        Toast.makeText(this, "Could not add the selected media", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                com.infinifolder.storage.DiagnosticLog.record("audio.playlist.selection.prepare-failed", error = error)
                runOnUiThread {
                    if (loadId != activePlaylistUiLoad || isFinishing || isDestroyed) return@runOnUiThread
                    completePlaylistUiLoad(loadId, "failed")
                    Toast.makeText(this, "Could not prepare the selected media", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = kotlin.math.abs(ev.rawX - touchDownX)
                val dy = kotlin.math.abs(ev.rawY - touchDownY)
                val duration = System.currentTimeMillis() - touchDownTime
                val isTap = dx < dp(15) && dy < dp(15) && duration < 500

                if (isTap && ThemeManager.getAudioPlayerTheme(this).id != AppThemeId.WMP_GLASS) {
                    val rawX = ev.rawX.toInt()
                    val rawY = ev.rawY.toInt()

                    if (::topBar.isInitialized && topBar.visibility == View.VISIBLE) {
                        val topBarRect = Rect()
                        topBar.getGlobalVisibleRect(topBarRect)
                        if (!topBarRect.contains(rawX, rawY)) {
                            // Tapped anywhere else -> hide the Now playing bar!
                            topBar.visibility = View.GONE
                        }
                    } else if (::topBar.isInitialized && topBar.visibility != View.VISIBLE) {
                        // Tapped while hidden -> reveal if tapped on the top bar region
                        val topBarThreshold = systemInsetTop + dp(54)
                        if (rawY <= topBarThreshold) {
                            topBar.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setOnApplyWindowInsetsListener { _, insets ->
                val safe = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                } else null
                systemInsetLeft = safe?.left ?: insets.systemWindowInsetLeft
                systemInsetTop = safe?.top ?: insets.systemWindowInsetTop
                systemInsetRight = safe?.right ?: insets.systemWindowInsetRight
                systemInsetBottom = safe?.bottom ?: insets.systemWindowInsetBottom
                updateRootPadding()
                insets
            }
        }

        // Top Bar (Now Playing header bar - hidden by default, tapped to reveal)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        val selectableRes = android.util.TypedValue().let { tv ->
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) tv.resourceId else 0
        }

        backBtn = TextView(this).apply {
            text = "←"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { onBackPressed() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        headerText = TextView(this).apply {
            text = "Now Playing"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener {
                topBar.visibility = View.GONE
            }
        }
        topBar.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))

        // Theme Switcher Button
        themeBtn = TextView(this).apply {
            text = "🎨"
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { showThemeChooser() }
        }
        topBar.addView(themeBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        soundFontBtn = TextView(this).apply {
            text = "🎹"
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "MIDI soundfont"
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { showMidiSoundFontSettings() }
        }
        topBar.addView(soundFontBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        playlistBtn = TextView(this).apply {
            text = "☰"
            textSize = 19f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { showPlaylistDialog() }
        }
        topBar.addView(playlistBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        addToPlaylistBtn = TextView(this).apply {
            text = "+"
            textSize = 25f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Add to playlist"
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { showMediaCategoryDialog() }
        }
        topBar.addView(addToPlaylistBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        castBtn = TextView(this).apply {
            text = "📡"
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { showCastDialog() }
            setOnLongClickListener {
                launchAudioOutput()
                true
            }
        }
        topBar.addView(castBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        openExtBtn = TextView(this).apply {
            text = "↗"
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener { openExternally() }
        }
        topBar.addView(openExtBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        visualPaneBtn = TextView(this).apply {
            text = "VIS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Open visual media pane"
            if (selectableRes != 0) setBackgroundResource(selectableRes)
            setOnClickListener {
                winampVisualPaneOpen = !winampVisualPaneOpen
                applyTheme(ThemeManager.getAudioPlayerTheme(this@AudioPlayerActivity))
            }
        }
        topBar.addView(visualPaneBtn, LinearLayout.LayoutParams(dp(34), dp(38)))

        DebugTools.attachToolbarButton(this, topBar)
        topInsetArea = View(this).apply {
            val isDark = ThemeManager.resolveIsDark(this@AudioPlayerActivity)
            setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)
        }
        rootLayout.addView(topInsetArea, LinearLayout.LayoutParams(-1, 0))
        rootLayout.addView(topBar)

        // 1. Skin Player Container (Main Window docked at top, Playlist Window taking remaining height)
        skinContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Main Window (275x116 scaled to full width, fixed at top, not in scrollview)
        winampMainWindowView = WinampMainWindowView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        setupWinampMainWindow()
        // Docked Playlist Sub-Window (fills remaining vertical screen space)
        dockedSubWindow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        setupDockedSubWindow()
        setupDetailsWindow()
        rootLayout.addView(skinContainer)

        // 2. Non-Skin Fallback Container (Scrollable column for standard Material / AMOLED themes)
        nonSkinScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        nonSkinColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        mainColumn = nonSkinColumn

        // Winamp Digital Display Console / Screen
        consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }

        val ledRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        timerLedView = TextView(this).apply {
            text = "00:00"
            textSize = 26f
            typeface = Typeface.MONOSPACE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRemainingTime = !showRemainingTime
                updateTimeDisplay()
            }
        }
        ledRow.addView(timerLedView)

        val spacer = View(this)
        ledRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val badgesCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        stereoBadge = TextView(this).apply {
            text = "STEREO"
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        badgesCol.addView(stereoBadge)

        val techRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        kbpsBadge = TextView(this).apply {
            text = "320 KBPS"
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        techRow.addView(kbpsBadge)

        khzBadge = TextView(this).apply {
            text = "44 KHZ"
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        techRow.addView(khzBadge, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(6)
        })
        badgesCol.addView(techRow)
        ledRow.addView(badgesCol)

        consoleCard.addView(ledRow)

        // Title, Artist & Marquee
        trackTitleText = TextView(this).apply {
            text = "Audio Title"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            maxLines = 1
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSelected = true
        }
        consoleCard.addView(trackTitleText, LinearLayout.LayoutParams(-1, -2))

        trackArtistText = TextView(this).apply {
            text = "Artist / Album"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setPadding(0, dp(2), 0, dp(2))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        consoleCard.addView(trackArtistText, LinearLayout.LayoutParams(-1, -2))

        // Animated Spectrum Visualizer (inside display console)
        visualizerView = VisualizerView(this).apply {
            val lp = LinearLayout.LayoutParams(-1, dp(38)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            layoutParams = lp
        }
        visualizerView.setOnClickListener {
            updateVisualizerCapture(AudioPlayerService.currentService?.audioSessionId ?: 0, explicitRequest = true)
        }
        consoleCard.addView(visualizerView)

        technicalInfoText = TextView(this).apply {
            text = "Audio information unavailable"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setOnClickListener { showDiagnosticsDialog() }
        }
        consoleCard.addView(technicalInfoText, LinearLayout.LayoutParams(-1, -2))

        mainColumn.addView(consoleCard)

        // Headerless visual media pane. Audio art is the first content type; the
        // surface leaves room for video and other visual media sources to be added.
        artCard = AspectRatio16x9FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
            clipToOutline = true
        }

        albumArtView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            contentDescription = "Visual media"
        }
        artCard.addView(albumArtView)

        // The lower-third background hugs its text; it does not paint a band
        // across the visualization window. The spectrum remains a separate overlay.
        val visualMetadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xC9000000.toInt())
            }
        }
        visualPaneTitleText = TextView(this).apply {
            text = ""
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        visualMetadata.addView(visualPaneTitleText, LinearLayout.LayoutParams(-2, -2))
        visualPaneDetailsText = TextView(this).apply {
            text = ""
            textSize = 10f
            typeface = Typeface.DEFAULT
            setTextColor(0xFFE0E0E0.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        visualMetadata.addView(visualPaneDetailsText, LinearLayout.LayoutParams(-2, -2))
        artCard.addView(visualMetadata, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(8)
            bottomMargin = dp(8)
        })
        visualPaneVisualizerView = VisualizerView(this).apply {
            contentDescription = "Audio visualization"
            isPlaying = false
        }
        artCard.addView(visualPaneVisualizerView, FrameLayout.LayoutParams(dp(92), dp(42), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(8)
            bottomMargin = dp(8)
        })
        artCard.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val textMax = ((right - left) * 0.72f).toInt().coerceAtLeast(dp(80))
            visualPaneTitleText.maxWidth = textMax
            visualPaneDetailsText.maxWidth = textMax
        }

        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            layoutParams = lp
        }
        artCard.addView(loadingIndicator)

        mainColumn.addView(artCard)
        setupDockedVisualWindow()

        // Progress Bar & Timers
        progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        progressBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = AudioPlayerService.currentService?.currentDuration ?: 0
                        val targetMs = (progress / 1000f * dur).toInt()
                        currentTimeText.text = formatTime(targetMs)
                        timerLedView.text = formatTime(targetMs)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    val dur = AudioPlayerService.currentService?.currentDuration ?: 0
                    val targetMs = (progressBar.progress / 1000f * dur).toInt()
                    if (AudioPlayerService.currentService?.currentTrack?.mediaKind == PlaylistMediaKind.VIDEO) {
                        val videoDuration = visualVideoPlayback?.durationMs ?: 0L
                        visualVideoPlayback?.seekTo((progressBar.progress / 1000f * videoDuration).toLong())
                        return
                    }
                    if (CastManager.isCasting) {
                        CastManager.seek(targetMs.toLong())
                    }
                    AudioPlayerService.currentService?.seekTo(targetMs)
                }
            })
        }
        standardSeekThumb = progressBar.thumb
        progressContainer.addView(progressBar, LinearLayout.LayoutParams(-1, -2))

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        currentTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 13f
            isClickable = true
            setOnClickListener {
                showRemainingTime = !showRemainingTime
                updateTimeDisplay()
            }
        }
        timeRow.addView(currentTimeText, LinearLayout.LayoutParams(0, -2, 1f))

        totalTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 13f
            gravity = Gravity.END
        }
        timeRow.addView(totalTimeText, LinearLayout.LayoutParams(0, -2, 1f))
        progressContainer.addView(timeRow)

        mainColumn.addView(progressContainer)

        // Main Controls Row (Shuffle, Prev, Play/Pause, Stop, Next, Repeat)
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
        }

        shuffleBtn = Button(this).apply {
            text = "🔀"
            textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                AudioPlayerService.currentService?.let {
                    it.toggleShuffle()
                    Toast.makeText(this@AudioPlayerActivity, if (it.isShuffleEnabled) "Shuffle: On" else "Shuffle: Off", Toast.LENGTH_SHORT).show()
                }
                updateControlsAppearance()
            }
        }
        controlsRow.addView(shuffleBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(6)
        })

        prevBtn = Button(this).apply {
            text = "⏮"
            textSize = 20f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.playPrevious()
                } else {
                    handleIntent(intent)
                }
            }
        }
        controlsRow.addView(prevBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        playPauseBtn = Button(this).apply {
            text = "▶"
            textSize = 22f
            setOnClickListener { toggleCurrentMediaPlayback() }
        }
        controlsRow.addView(playPauseBtn, LinearLayout.LayoutParams(dp(54), dp(54)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })

        stopBtn = Button(this).apply {
            text = "⏹"
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                stopCurrentMediaPlayback()
            }
        }
        controlsRow.addView(stopBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(6)
        })

        nextBtn = Button(this).apply {
            text = "⏭"
            textSize = 20f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.playNext()
                } else {
                    handleIntent(intent)
                }
            }
        }
        controlsRow.addView(nextBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        repeatBtn = Button(this).apply {
            text = "🔁"
            textSize = 15f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                AudioPlayerService.currentService?.let {
                    it.cycleRepeatMode()
                    val label = when (it.repeatMode) {
                        RepeatMode.NONE -> "Repeat: Off"
                        RepeatMode.ALL -> "Repeat: All"
                        RepeatMode.ONE -> "Repeat: One track"
                    }
                    Toast.makeText(this@AudioPlayerActivity, label, Toast.LENGTH_SHORT).show()
                }
                updateControlsAppearance()
            }
        }
        controlsRow.addView(repeatBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(6)
        })

        mainColumn.addView(controlsRow)

        // Secondary Controls (Speed, Audio Routing)
        secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }

        speedBtn = Button(this).apply {
            formatCompactButton(this, "1.0x", textSizeSp = 12f, padH = 12, padV = 4)
            setOnClickListener { showSpeedPicker() }
        }
        secondaryRow.addView(speedBtn, LinearLayout.LayoutParams(-2, dp(36)))

        mainColumn.addView(secondaryRow)

        // Volume Bar
        volumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        val volIconLow = TextView(this).apply {
            text = "🔈"
            textSize = 13f
        }
        volumeRow.addView(volIconLow)

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeBar = SeekBar(this).apply {
            max = maxVol
            progress = curVol
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        volumeRow.addView(volumeBar, LinearLayout.LayoutParams(0, -2, 1f))

        val volIconHigh = TextView(this).apply {
            text = "🔊"
            textSize = 13f
        }
        volumeRow.addView(volIconHigh)

        nonSkinColumn.addView(volumeRow, LinearLayout.LayoutParams(-1, -2))

        nonSkinScroll.addView(nonSkinColumn)
        rootLayout.addView(nonSkinScroll)
        wmpPlayerLayout = WmpPlayerLayout(this, WmpPlayerLayout.Content(
            consoleCard, artCard, progressContainer, controlsRow, secondaryRow, volumeRow,
            trackTitleText, trackArtistText, albumArtView, visualizerView, progressBar, volumeBar,
            shuffleBtn, prevBtn, playPauseBtn, stopBtn, nextBtn, repeatBtn))
        rootLayout.addView(wmpPlayerLayout)
        setContentView(rootLayout)
        configureSkinLayout()

        // One capture bridge serves the normal visual pane and optional classic skins.
        // It used to be created only by the Winamp setup path, which left the standard
        // player's visible spectrum with no PCM source attached.
        visualizerBridge = RealAudioVisualizerBridge {
            val playing = if (CastManager.isCasting) CastManager.isRemotePlaying
                else AudioPlayerService.currentService?.isPlaying == true
            visualizerBridge?.getSpectrum(visualizerBands, isPlaying = playing)
            if (::visualizerView.isInitialized) visualizerView.updateData(visualizerBands)
            if (::visualPaneVisualizerView.isInitialized) visualPaneVisualizerView.updateData(visualizerBands)
            if (::winampMainWindowView.isInitialized) winampMainWindowView.invalidate()
        }
        if (::winampMainWindowView.isInitialized) winampMainWindowView.visualizerBridge = visualizerBridge
    }

    private fun setupWinampMainWindow() {
        winampMainWindowView.visualizerBridge = visualizerBridge

        winampMainWindowView.callback = object : WinampMainWindowView.Callback {
            override fun onPlay() {
                playCurrentMediaPlayback()
            }
            override fun onPause() {
                toggleCurrentMediaPlayback()
            }
            override fun onStop() {
                stopCurrentMediaPlayback()
            }
            override fun onPrev() {
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.playPrevious()
                } else {
                    handleIntent(intent)
                }
            }
            override fun onNext() {
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.playNext()
                } else {
                    handleIntent(intent)
                }
            }
            override fun onEject() {
                openExternally()
            }
            override fun onSeek(positionMs: Int) {
                if (CastManager.isCasting) {
                    CastManager.seek(positionMs.toLong())
                }
                AudioPlayerService.currentService?.seekTo(positionMs)
            }
            override fun onVolumeChanged(volume: Float) {
                AudioPlayerService.currentService?.volume = volume
            }
            override fun onBalanceChanged(balance: Float) {
                AudioPlayerService.currentService?.balance = balance
            }
            override fun onToggleShuffle() {
                AudioPlayerService.currentService?.let {
                    it.toggleShuffle()
                    winampMainWindowView.isShuffle = it.isShuffleEnabled
                    Toast.makeText(this@AudioPlayerActivity, if (it.isShuffleEnabled) "Shuffle: On" else "Shuffle: Off", Toast.LENGTH_SHORT).show()
                }
                updateControlsAppearance()
            }
            override fun onToggleRepeat() {
                AudioPlayerService.currentService?.let {
                    it.cycleRepeatMode()
                    winampMainWindowView.isRepeat = (it.repeatMode != RepeatMode.NONE)
                    val label = when (it.repeatMode) {
                        RepeatMode.NONE -> "Repeat: Off"
                        RepeatMode.ALL -> "Repeat: All"
                        RepeatMode.ONE -> "Repeat: One track"
                    }
                    Toast.makeText(this@AudioPlayerActivity, label, Toast.LENGTH_SHORT).show()
                }
                updateControlsAppearance()
            }
            override fun onToggleDockedSubwindow() {
                dockedSubWindow.visibility = if (dockedSubWindow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            override fun onClose() {
                finish()
            }
            override fun onVisualizerRequested() {
                updateVisualizerCapture(AudioPlayerService.currentService?.audioSessionId ?: 0, explicitRequest = true)
            }
            override fun onDiagnosticsRequested() {
                showDiagnosticsDialog()
            }
        }
    }

    private fun setupDockedSubWindow() {
        // 1. Authentic Playlist Window Titlebar (275x20 scaled to full width)
        dockedTitleBarView = WinampPlaylistTitleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            onShadeToggle = {
                val isHidden = dockedBodyRow.visibility == View.GONE
                dockedBodyRow.visibility = if (isHidden) View.VISIBLE else View.GONE
                dockedBottomBarView.visibility = if (isHidden) View.VISIBLE else View.GONE
            }
        }
        dockedSubWindow.addView(dockedTitleBarView)

        // 2. Generic Title Strip (for non-skin fallback)
        dockedSubTitleStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        dockedPlaylistHeader = TextView(this).apply {
            text = "PLAYLIST & DETAILS"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.GREEN)
        }
        dockedSubTitleStrip.addView(dockedPlaylistHeader, LinearLayout.LayoutParams(0, -2, 1f))

        val shadeBtn = TextView(this).apply {
            text = "▲"
            textSize = 11f
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val isHidden = dockedBodyRow.visibility == View.GONE
                dockedBodyRow.visibility = if (isHidden) View.VISIBLE else View.GONE
                dockedBottomBarView.visibility = if (isHidden) View.VISIBLE else View.GONE
                text = if (isHidden) "▲" else "▼"
            }
        }
        dockedSubTitleStrip.addView(shadeBtn)
        dockedSubWindow.addView(dockedSubTitleStrip)

        // 3. Middle Body Row (Left Border + Center Column + Right Border)
        dockedBodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        dockedLeftBorder = WinampPlaylistBorderView(this, isLeft = true).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -1)
        }
        dockedBodyRow.addView(dockedLeftBorder)

        dockedCenterCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }

        // Static Current Playing Blurb (Album Art & Metadata)
        dockedMetaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(4))
        }

        dockedAlbumArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                marginEnd = dp(8)
            }
        }
        dockedMetaRow.addView(dockedAlbumArt)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dockedTrackTitle = TextView(this).apply {
            text = "Track Title"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSelected = true
        }
        metaCol.addView(dockedTrackTitle)

        dockedTrackArtist = TextView(this).apply {
            text = "Artist / Album"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        metaCol.addView(dockedTrackArtist)

        dockedTrackDetails = TextView(this).apply {
            text = "44.1 kHz • Stereo • VFS"
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setOnClickListener { showDiagnosticsDialog() }
        }
        metaCol.addView(dockedTrackDetails)
        dockedMetaRow.addView(metaCol, LinearLayout.LayoutParams(0, -2, 1f))
        dockedCenterCol.addView(dockedMetaRow)

        // Static Secondary Controls Bar in a HorizontalScrollView so buttons never squish or clip
        dockedToolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            clipToPadding = false
            setPadding(dp(6), 0, dp(6), dp(4))
        }

        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dockedToolScroll.addView(toolRow, FrameLayout.LayoutParams(-2, -2))

        dockedSpeedBtn = Button(this).apply {
            formatCompactButton(this, "1.0x", textSizeSp = 11f, padH = 8, padV = 3)
            setOnClickListener { showSpeedPicker() }
        }
        toolRow.addView(dockedSpeedBtn, LinearLayout.LayoutParams(-2, dp(32)).apply {
            marginEnd = dp(6)
        })

        dockedDiagnosticsBtn = Button(this).apply {
            formatCompactButton(this, "🔍 Technical", textSizeSp = 11f, padH = 8, padV = 3)
            setOnClickListener { showDiagnosticsDialog() }
        }
        toolRow.addView(dockedDiagnosticsBtn, LinearLayout.LayoutParams(-2, dp(32)).apply {
            marginEnd = dp(6)
        })

        dockedCastBtn = Button(this).apply {
            formatCompactButton(this, "📡 Cast", textSizeSp = 11f, padH = 8, padV = 3)
            setOnClickListener { showCastDialog() }
        }
        toolRow.addView(dockedCastBtn, LinearLayout.LayoutParams(-2, dp(32)))

        dockedAddToPlaylistBtn = Button(this).apply {
            formatCompactButton(this, "+ Add", textSizeSp = 11f, padH = 8, padV = 3)
            contentDescription = "Add to playlist"
            setOnClickListener { showMediaCategoryDialog() }
        }
        toolRow.addView(dockedAddToPlaylistBtn, LinearLayout.LayoutParams(-2, dp(32)).apply {
            marginStart = dp(6)
        })

        dockedRemoveFromPlaylistBtn = Button(this).apply {
            formatCompactButton(this, "− Remove", textSizeSp = 11f, padH = 8, padV = 3)
            contentDescription = "Remove selected track from playlist"
            isEnabled = false
            setOnClickListener {
                val service = AudioPlayerService.currentService ?: return@setOnClickListener
                if (service.removePlaylistItem(selectedPlaylistIndex)) {
                    selectedPlaylistIndex = service.currentIndex
                    updateDockedPlaylist()
                }
            }
        }
        toolRow.addView(dockedRemoveFromPlaylistBtn, LinearLayout.LayoutParams(-2, dp(32)).apply {
            marginStart = dp(6)
        })

        dockedSavePlaylistBtn = Button(this).apply {
            formatCompactButton(this, "💾 Save", textSizeSp = 11f, padH = 8, padV = 3)
            contentDescription = "Save playlist as JSPF"
            setOnClickListener { savePlaylistExternally() }
        }
        toolRow.addView(dockedSavePlaylistBtn, LinearLayout.LayoutParams(-2, dp(32)).apply {
            marginStart = dp(6)
        })
        dockedCenterCol.addView(dockedToolScroll)

        // The ScrollView belongs strictly to JUST the playlist items
        dockedPlaylistScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setOnScrollChangeListener { _, _, _, _, _ -> loadVisibleDirectoryImages() }
        }

        dockedPlaylistItems = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        dockedPlaylistScroll.addView(dockedPlaylistItems)
        val playlistViewport = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        playlistViewport.addView(dockedPlaylistScroll)
        dockedPlaylistLoadingOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0xB0000000.toInt())
            contentDescription = "Loading tracks into playlist"
        }
        val loadingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF252525.toInt())
            }
            addView(ProgressBar(this@AudioPlayerActivity).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER })
            dockedPlaylistLoadingMessage = TextView(this@AudioPlayerActivity).apply {
                text = "Scanning audio files…"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }
            addView(dockedPlaylistLoadingMessage, LinearLayout.LayoutParams(-2, -2))
        }
        dockedPlaylistLoadingOverlay.addView(loadingContent, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playlistViewport.addView(dockedPlaylistLoadingOverlay, FrameLayout.LayoutParams(-1, -1))
        dockedCenterCol.addView(playlistViewport)

        // Static Playlist Total Blurb & Footer
        dockedPlaylistFooter = TextView(this).apply {
            text = "0 tracks • Total: 00:00"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        dockedCenterCol.addView(dockedPlaylistFooter)

        dockedBodyRow.addView(dockedCenterCol)

        dockedRightBorder = WinampPlaylistBorderView(this, isLeft = false).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -1)
        }
        dockedBodyRow.addView(dockedRightBorder)

        dockedSubWindow.addView(dockedBodyRow)

        // 4. Authentic Playlist Window Bottom Bar (275x38 scaled to full width)
        dockedBottomBarView = WinampPlaylistBottomView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            onAddToPlaylist = { showMediaCategoryDialog() }
            onRemoveFromPlaylist = { removeSelectedPlaylistItem() }
            onSelectPlaylistItem = { selectCurrentlyPlayingPlaylistItem() }
            onMorePlaylistActions = { showPlaylistActionsDialog() }
        }
        dockedSubWindow.addView(dockedBottomBarView)
    }

    /** A second attached window, with its own unbranded title strip and 16:9 media stage. */
    private fun setupDockedVisualWindow() {
        dockedVisualWindow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        dockedVisualTitle = TextView(this).apply {
            text = "VISUAL MEDIA"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = "Show or hide visual media"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val show = dockedVisualBody.visibility != View.VISIBLE
                dockedVisualBody.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        dockedVisualWindow.addView(dockedVisualTitle, LinearLayout.LayoutParams(-1, dp(24)))
        dockedVisualBody = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        dockedVisualWindow.addView(dockedVisualBody, LinearLayout.LayoutParams(-1, -2))
    }

    private fun attachArtCardToVisualWindow(attached: Boolean) {
        if (!::artCard.isInitialized || !::dockedVisualBody.isInitialized || !::nonSkinColumn.isInitialized) return
        val wantedParent: android.view.ViewGroup = if (attached) dockedVisualBody else nonSkinColumn
        if (artCard.parent === wantedParent) return
        (artCard.parent as? android.view.ViewGroup)?.removeView(artCard)
        if (attached) {
            dockedVisualBody.addView(artCard, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        } else {
            val beforeControls = nonSkinColumn.indexOfChild(progressContainer).takeIf { it >= 0 }
                ?: nonSkinColumn.childCount
            nonSkinColumn.addView(artCard, beforeControls, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            })
        }
    }

    private fun setupDetailsWindow() {
        skinLeftColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        skinRightColumn = FrameLayout(this)
        skinSpareColumn = FrameLayout(this)
        detailsWindow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailsTitle = WinampPlaylistTitleView(this).apply {
            onShadeToggle = {
                val show = detailsBody.visibility != View.VISIBLE
                detailsBody.visibility = if (show) View.VISIBLE else View.GONE
                detailsBottom.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        detailsWindow.addView(detailsTitle, LinearLayout.LayoutParams(-1, -2))
        detailsBody = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        detailsBody.addView(WinampPlaylistBorderView(this, isLeft = true), LinearLayout.LayoutParams(-2, -1))
        detailsContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailsHeader = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(6), dp(6), 0)
        }
        detailsContent.addView(detailsHeader)
        val detailsScroll = ScrollView(this).apply { isFillViewport = false; addView(detailsContent) }
        detailsBody.addView(detailsScroll, LinearLayout.LayoutParams(0, -1, 1f))
        detailsBody.addView(WinampPlaylistBorderView(this, isLeft = false), LinearLayout.LayoutParams(-2, -1))
        detailsWindow.addView(detailsBody, LinearLayout.LayoutParams(-1, 0, 1f))
        detailsBottom = WinampPlaylistBottomView(this).apply {
            onAddToPlaylist = { showMediaCategoryDialog() }
            onRemoveFromPlaylist = { removeSelectedPlaylistItem() }
            onSelectPlaylistItem = { selectCurrentlyPlayingPlaylistItem() }
            onMorePlaylistActions = { showPlaylistActionsDialog() }
        }
        detailsWindow.addView(detailsBottom, LinearLayout.LayoutParams(-1, -2))
    }

    private fun configureSkinLayout() {
        if (!::detailsWindow.isInitialized) return
        val config = resources.configuration
        val columns = audioPlayerColumnCount(config.smallestScreenWidthDp,
            config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        (dockedMetaRow.parent as? android.view.ViewGroup)?.removeView(dockedMetaRow)
        (dockedToolScroll.parent as? android.view.ViewGroup)?.removeView(dockedToolScroll)
        skinContainer.removeAllViews()
        skinLeftColumn.removeAllViews()
        skinRightColumn.removeAllViews()
        skinSpareColumn.removeAllViews()
        if (!::skinDockedStack.isInitialized) skinDockedStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        skinDockedStack.removeAllViews()
        (dockedSubWindow.parent as? android.view.ViewGroup)?.removeView(dockedSubWindow)
        (dockedVisualWindow.parent as? android.view.ViewGroup)?.removeView(dockedVisualWindow)
        dockedTrackArtist.maxLines = if (columns > 1) 2 else 1
        if (columns > 1) {
            skinContainer.orientation = LinearLayout.HORIZONTAL
            detailsContent.addView(dockedMetaRow)
            detailsContent.addView(dockedToolScroll)
            skinLeftColumn.addView(winampMainWindowView, LinearLayout.LayoutParams(-1, -2))
            skinLeftColumn.addView(detailsWindow, LinearLayout.LayoutParams(-1, 0, 1f))
            skinContainer.addView(skinLeftColumn, LinearLayout.LayoutParams(0, -1, 1f))
            if (columns == 3) {
                skinRightColumn.addView(dockedSubWindow, FrameLayout.LayoutParams(-1, -1))
                skinSpareColumn.addView(dockedVisualWindow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                skinContainer.addView(skinRightColumn, LinearLayout.LayoutParams(0, -1, 1f))
                skinContainer.addView(skinSpareColumn, LinearLayout.LayoutParams(0, -1, 1f))
            } else {
                skinDockedStack.addView(dockedVisualWindow, LinearLayout.LayoutParams(-1, -2))
                skinDockedStack.addView(dockedSubWindow, LinearLayout.LayoutParams(-1, 0, 1f))
                skinRightColumn.addView(skinDockedStack, FrameLayout.LayoutParams(-1, -1))
                skinContainer.addView(skinRightColumn, LinearLayout.LayoutParams(0, -1, 1f))
            }
        } else {
            skinContainer.orientation = LinearLayout.VERTICAL
            dockedCenterCol.addView(dockedToolScroll, 0)
            skinContainer.addView(winampMainWindowView, LinearLayout.LayoutParams(-1, -2))
            skinDockedStack.addView(dockedVisualWindow, LinearLayout.LayoutParams(-1, -2))
            skinDockedStack.addView(dockedSubWindow, LinearLayout.LayoutParams(-1, 0, 1f))
            skinContainer.addView(skinDockedStack, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun updateDockedPlaylist(forceRebuild: Boolean = false) {
        if (!::dockedPlaylistItems.isInitialized) return
        val service = AudioPlayerService.currentService ?: return
        val loadId = activePlaylistUiLoad
        val rowBuildStartedAt = android.os.SystemClock.elapsedRealtime()
        val theme = ThemeManager.getAudioPlayerTheme(this)
        val list = synchronized(service.playlist) { ArrayList(service.playlist) }
        val displayRows = playlistDisplayRows(list)
        if (loadId != 0L) {
            setPlaylistUiLoadStage(loadId, "Building playlist rows (${list.size} tracks)…", "phase=build-rows rows=${displayRows.size}")
        }
        val curIdx = service.currentIndex
        if (selectedPlaylistIndex !in list.indices) selectedPlaylistIndex = curIdx.takeIf { it in list.indices } ?: -1
        dockedRemoveFromPlaylistBtn?.isEnabled = selectedPlaylistIndex in list.indices
        dockedSavePlaylistBtn?.isEnabled = list.isNotEmpty()
        val currentTrack = list.getOrNull(curIdx)
        val currentTrackKey = currentTrack?.let {
            "$curIdx:${it.uri}:${it.startByte}:${it.lengthBytes}"
        }
        if (currentTrackKey == null) lastAutoScrolledDockedTrack = null

        var totalMs = 0L
        for (track in list) {
            if (track.durationMs > 0L) totalMs += track.durationMs
        }

        val sameRowShape = dockedPlaylistItems.childCount == displayRows.size && displayRows.indices.all { rowIndex ->
            val tag = dockedPlaylistItems.getChildAt(rowIndex)?.tag
            when (val displayRow = displayRows[rowIndex]) {
                is PlaylistDisplayRow.DirectoryImage -> tag == "directory-image"
                is PlaylistDisplayRow.Track -> tag == displayRow.index
            }
        }
        if (!forceRebuild && sameRowShape) {
            for ((rowIndex, displayRow) in displayRows.withIndex()) {
                val row = dockedPlaylistItems.getChildAt(rowIndex) as? LinearLayout ?: continue
                when (displayRow) {
                    is PlaylistDisplayRow.DirectoryImage -> {
                        row.setBackgroundColor(theme.alternateBackground)
                        val image = row.getChildAt(0) as? ImageView
                        if (image != null) bindDirectoryImage(image, displayRow.track, dp(40))
                        (row.getChildAt(1) as? TextView)?.text = displayRow.track.directoryName.orEmpty()
                    }
                    is PlaylistDisplayRow.Track -> {
                        val index = displayRow.index
                        val track = displayRow.track
                        val isCurrent = (index == curIdx)
                        row.setBackgroundColor(if (isCurrent) theme.selectionBackground else Color.TRANSPARENT)
                        (row.getChildAt(0) as? TextView)?.setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
                        val displayTitle = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
                            "${track.artist} - ${track.title}"
                        } else track.title
                        val mediaPrefix = when (track.mediaKind) {
                            PlaylistMediaKind.IMAGE -> "[IMAGE] "
                            PlaylistMediaKind.VIDEO -> "[VIDEO] "
                            PlaylistMediaKind.AUDIO -> ""
                        }
                        (row.getChildAt(1) as? TextView)?.apply {
                            text = (if (isCurrent) "▶ " else "") + mediaPrefix + displayTitle
                            setTextColor(if (isCurrent) theme.selectionText else theme.textPrimary)
                        }
                        (row.getChildAt(2) as? TextView)?.apply {
                            text = if (track.durationMs > 0) formatTime(track.durationMs.toInt()) else "--"
                            setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
                        }
                    }
                }
            }
        } else {
            dockedPlaylistItems.removeAllViews()
            for (displayRow in displayRows) {
                when (displayRow) {
                    is PlaylistDisplayRow.DirectoryImage -> {
                        val track = displayRow.track
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                            contentDescription = "Directory image for ${track.directoryName.orEmpty()}. Tap to show it above."
                            tag = "directory-image"
                            isClickable = true
                            isFocusable = true
                            setBackgroundColor(theme.alternateBackground)
                            setOnClickListener { showDirectoryImage(track) }
                        }
                        val image = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                        bindDirectoryImage(image, track, dp(40))
                        row.addView(image, LinearLayout.LayoutParams(dp(40), dp(40)))
                        row.addView(TextView(this).apply {
                            text = track.directoryName.orEmpty()
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(theme.textPrimary)
                            setPadding(dp(10), 0, 0, 0)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        dockedPlaylistItems.addView(row)
                    }
                    is PlaylistDisplayRow.Track -> {
                        val index = displayRow.index
                        val track = displayRow.track
                        val isCurrent = index == curIdx
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                            contentDescription = "${track.mediaKind.name.lowercase()} ${track.title}. Hold and move to reorder; hold and release for track options."
                            tag = index
                            isClickable = true
                            isFocusable = true
                            setBackgroundColor(if (isCurrent) theme.selectionBackground else Color.TRANSPARENT)
                            setOnClickListener {
                                selectedPlaylistIndex = index
                                service.jumpTo(index)
                                updateDockedPlaylist()
                            }
                            setOnDragListener { _, event ->
                                val isCurrentRow = index == (AudioPlayerService.currentService?.currentIndex ?: -1)
                                val restingColor = if (isCurrentRow) theme.selectionBackground else Color.TRANSPARENT
                                when (event.action) {
                                    DragEvent.ACTION_DRAG_STARTED -> event.localState is Int
                                    DragEvent.ACTION_DRAG_ENTERED -> { setBackgroundColor(theme.selectionBackground); true }
                                    DragEvent.ACTION_DRAG_LOCATION -> true
                                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                                        setBackgroundColor(restingColor); true
                                    }
                                    DragEvent.ACTION_DROP -> {
                                        setBackgroundColor(restingColor)
                                        val from = event.localState as? Int
                                        if (from != null) reorderPlaylistItem(from, index)
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                        installPlaylistGesture(row, index)
                        row.addView(TextView(this).apply {
                            text = String.format("%2d. ", index + 1)
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
                        }, LinearLayout.LayoutParams(-2, -2))
                        val displayTitle = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
                            "${track.artist} - ${track.title}"
                        } else track.title
                        val mediaPrefix = when (track.mediaKind) {
                            PlaylistMediaKind.IMAGE -> "[IMAGE] "
                            PlaylistMediaKind.VIDEO -> "[VIDEO] "
                            PlaylistMediaKind.AUDIO -> ""
                        }
                        row.addView(TextView(this).apply {
                            text = (if (isCurrent) "▶ " else "") + mediaPrefix + displayTitle
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(if (isCurrent) theme.selectionText else theme.textPrimary)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(TextView(this).apply {
                            text = if (track.durationMs > 0) formatTime(track.durationMs.toInt()) else "--"
                            textSize = 10f
                            typeface = Typeface.MONOSPACE
                            gravity = Gravity.END
                            setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
                        }, LinearLayout.LayoutParams(-2, -2))
                        dockedPlaylistItems.addView(row)
                    }
                }
            }
        }

        dockedPlaylistHeader.text = "PLAYLIST (${list.size} ITEMS)"
        dockedPlaylistFooter.text = "${list.size} items • Total: ${formatTime(totalMs)}"

        // Add the folder rows immediately; resolve only cover URIs that are on screen.
        // This keeps a large recursive selection from serially opening every album
        // image before the playlist is usable.
        dockedPlaylistScroll.post { loadVisibleDirectoryImages() }

        if (::dockedBottomBarView.isInitialized) {
            dockedBottomBarView.totalDurationMs = totalMs
            val curTrackMs = if (curIdx in list.indices && list[curIdx].durationMs > 0L) list[curIdx].durationMs else 0L
            dockedBottomBarView.currentTrackDurationMs = curTrackMs
            dockedBottomBarView.invalidate()
        }

        if (currentTrackKey != null && currentTrackKey != lastAutoScrolledDockedTrack) {
            lastAutoScrolledDockedTrack = currentTrackKey
            dockedPlaylistScroll.post {
                val currentService = AudioPlayerService.currentService ?: return@post
                if (currentService.currentIndex != curIdx) return@post
                val activeTrack = synchronized(currentService.playlist) {
                    currentService.playlist.getOrNull(curIdx)
                } ?: return@post
                if ("$curIdx:${activeTrack.uri}:${activeTrack.startByte}:${activeTrack.lengthBytes}" != currentTrackKey) return@post
                val rowIndex = displayRows.indexOfFirst { it is PlaylistDisplayRow.Track && it.index == curIdx }
                if (rowIndex < 0) return@post
                val row = dockedPlaylistItems.getChildAt(rowIndex) ?: return@post
                val visibleTop = dockedPlaylistScroll.scrollY + dockedPlaylistScroll.paddingTop
                val visibleBottom = dockedPlaylistScroll.scrollY + dockedPlaylistScroll.height - dockedPlaylistScroll.paddingBottom
                val targetY = when {
                    row.top < visibleTop -> row.top - dockedPlaylistScroll.paddingTop
                    row.bottom > visibleBottom -> row.bottom - dockedPlaylistScroll.height + dockedPlaylistScroll.paddingBottom
                    else -> return@post
                }.coerceAtLeast(0)
                dockedPlaylistScroll.smoothScrollTo(0, targetY)
            }
        }

        if (loadId != 0L && loadId == activePlaylistUiLoad) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - rowBuildStartedAt
            com.infinifolder.storage.DiagnosticLog.record(
                "audio.playlist.rows.built",
                "generation=$loadId tracks=${list.size} rows=${displayRows.size} elapsedMs=$elapsed children=${dockedPlaylistItems.childCount}")
            if (playlistUiExpectedTracks >= 0 && list.size >= playlistUiExpectedTracks &&
                dockedPlaylistItems.childCount >= playlistUiExpectedRows) {
                setPlaylistUiLoadStage(loadId, "Waiting for playlist display…", "phase=layout-wait rows=${dockedPlaylistItems.childCount}")
                waitForPlaylistRowsToRender(loadId)
            } else {
                setPlaylistUiLoadStage(loadId, "Playlist rows ${dockedPlaylistItems.childCount} of $playlistUiExpectedRows…", "phase=rows-incomplete")
            }
        }
    }

    /** Keep the loading veil until the expected rows have passed through Android layout. */
    private fun waitForPlaylistRowsToRender(loadId: Long) {
        if (loadId == 0L || loadId != activePlaylistUiLoad || playlistUiLayoutWaiter == loadId ||
            playlistUiExpectedRows < 0) return
        playlistUiLayoutWaiter = loadId
        val observer = dockedPlaylistItems.viewTreeObserver
        if (!observer.isAlive) {
            playlistUiLayoutWaiter = 0L
            dockedPlaylistItems.postDelayed({ waitForPlaylistRowsToRender(loadId) }, 32L)
            return
        }
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!observer.isAlive) return true
                if (loadId != activePlaylistUiLoad) {
                    observer.removeOnPreDrawListener(this)
                    if (playlistUiLayoutWaiter == loadId) playlistUiLayoutWaiter = 0L
                    return true
                }
                val lastRow = dockedPlaylistItems.getChildAt(dockedPlaylistItems.childCount - 1)
                val rowsReady = dockedPlaylistItems.isLaidOut && dockedPlaylistItems.height > 0 &&
                    dockedPlaylistItems.childCount >= playlistUiExpectedRows && lastRow?.isLaidOut == true
                if (rowsReady) {
                    observer.removeOnPreDrawListener(this)
                    playlistUiLayoutWaiter = 0L
                    completePlaylistUiLoad(loadId, "ready")
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
        dockedPlaylistItems.requestLayout()
        dockedPlaylistItems.invalidate()
    }

    private fun playlistDisplayRows(tracks: List<AudioTrack>): List<PlaylistDisplayRow> {
        val rows = ArrayList<PlaylistDisplayRow>(tracks.size + 8)
        var previousDirectory: String? = null
        tracks.forEachIndexed { index, track ->
            val identity = track.directoryUri?.toString()
                ?: track.directoryName?.takeIf(String::isNotBlank)?.let { "$it:${track.directoryCoverUri}" }
            if (identity != null && identity != previousDirectory) rows += PlaylistDisplayRow.DirectoryImage(track)
            rows += PlaylistDisplayRow.Track(index, track)
            previousDirectory = identity
        }
        return rows
    }

    private fun directoryEntryBitmap(track: AudioTrack, sizePx: Int): Bitmap? =
        track.directoryArtwork ?: iconThemeManager.resolveFolderIcon(track.directoryName.orEmpty(), sizePx)
            ?: iconThemeManager.resolveLogicalIcon("folder", sizePx)
            ?: com.infinifolder.FileTypeIcons.folder(sizePx)

    /** Show a folder icon immediately; image bytes are read only for visible rows. */
    private fun bindDirectoryImage(image: ImageView, track: AudioTrack, sizePx: Int, forceLoad: Boolean = false) {
        val coverUri = track.directoryCoverUri
        val key = coverUri?.toString()
        image.tag = DirectoryImageTag(track, key)
        val cachedCover = key?.let(directoryCoverCache::get)
        image.setImageBitmap(track.directoryArtwork ?: cachedCover ?: directoryEntryBitmap(track, sizePx))
        if (coverUri == null || track.directoryArtwork != null || cachedCover != null) return
        val cacheKey = key ?: return
        if (cacheKey in failedDirectoryCovers) return
        if (!forceLoad) return
        if (!pendingDirectoryCovers.add(cacheKey)) return

        runCatching {
            directoryCoverExecutor.execute {
                val bitmap = decodeDirectoryCover(coverUri)
                pendingDirectoryCovers.remove(cacheKey)
                if (bitmap != null) directoryCoverCache.put(cacheKey, bitmap)
                else failedDirectoryCovers.add(cacheKey)
                runOnUiThread {
                    val tag = image.tag as? DirectoryImageTag
                    if (bitmap != null && tag?.coverKey == cacheKey && !isFinishing && !isDestroyed) {
                        image.setImageBitmap(bitmap)
                    }
                    // A row may have been rebuilt while the one-per-directory decode
                    // was running. Refresh its current ImageView from the new cache.
                    if (!isFinishing && !isDestroyed) updateDockedPlaylist()
                }
            }
        }.onFailure { pendingDirectoryCovers.remove(cacheKey) }
    }

    private fun loadVisibleDirectoryImages() {
        if (!::dockedPlaylistItems.isInitialized || !::dockedPlaylistScroll.isInitialized) return
        val service = AudioPlayerService.currentService
        val tracks = service?.let { player -> synchronized(player.playlist) { player.playlist.toList() } }.orEmpty()
        val visibleTracks = LinkedHashMap<String, AudioTrack>()
        fun remember(track: AudioTrack) {
            visibleTracks["${track.uri}:${track.startByte}:${track.lengthBytes}"] = track
        }
        val visibleRect = Rect()
        val top = dockedPlaylistScroll.scrollY
        val bottom = top + dockedPlaylistScroll.height
        var low = 0
        var high = dockedPlaylistItems.childCount
        while (low < high) {
            val mid = (low + high) ushr 1
            val row = dockedPlaylistItems.getChildAt(mid)
            if (row.bottom <= top) low = mid + 1 else high = mid
        }
        for (index in low until dockedPlaylistItems.childCount) {
            val row = dockedPlaylistItems.getChildAt(index) as? LinearLayout ?: continue
            if (row.top >= bottom) break
            if (!row.isShown || !row.getGlobalVisibleRect(visibleRect)) continue
            when (val rowTag = row.tag) {
                "directory-image" -> {
                    val image = row.getChildAt(0) as? ImageView ?: continue
                    val tag = image.tag as? DirectoryImageTag ?: continue
                    remember(tag.track)
                    bindDirectoryImage(image, tag.track, dp(40), forceLoad = true)
                }
                is Int -> tracks.getOrNull(rowTag)?.let(::remember)
            }
        }
        service?.prioritizeMetadataTracks(visibleTracks.values.toList())
    }

    private fun decodeDirectoryCover(uri: Uri): Bitmap? {
        fun open() = MediaSourceAdapter.openStream(applicationContext, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching { open().use { BitmapFactory.decodeStream(it, null, options) } }.getOrNull()
        return decoded?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun showDirectoryImage(track: AudioTrack) {
        bindDirectoryImage(albumArtView, track, dp(256), forceLoad = true)
        albumArtView.visibility = View.VISIBLE
        albumArtView.alpha = 1f
        albumArtView.setBackgroundColor(Color.TRANSPARENT)
        if (::dockedAlbumArt.isInitialized) bindDirectoryImage(dockedAlbumArt, track, dp(256), forceLoad = true)
    }

    private fun stopVisualVideo() {
        visualVideoPlaying = false
        runCatching { visualVideoPlayback?.stop() }
        visualVideoInput?.close()
        visualVideoInput = null
        visualVideoPlayback?.view?.visibility = View.GONE
    }

    private fun decodePlaylistImage(uri: Uri, targetPixels: Int): Bitmap? {
        visualImageCache.get(uri.toString())?.let { return it }
        return runCatching {
            val resource = MediaSourceAdapter.resource(applicationContext, uri)
            val frame = com.infinifolder.preview.CanvasStreams.decodeViewer(
                contentResolver, resource, uri, targetPixels.coerceIn(64, 1024)) ?: return null
            frame.animationDrawable?.let { visualAnimationCache[uri.toString()] = it }
        (frame.animation ?: frame.colorCycling)?.let { visualBitmapAnimationCache[uri.toString()] = it }
            val bitmap = frame.takeBitmap()
            frame.close()
            visualImageCache.put(uri.toString(), bitmap)
            bitmap
        }.getOrNull()
    }

    private fun loadPlaylistImage(track: AudioTrack, generation: Long) {
        val uri = track.uri
        albumArtView.visibility = View.VISIBLE
        albumArtView.alpha = 0f
        albumArtView.setImageDrawable(null)
        albumArtView.setBackgroundColor(Color.BLACK)
        loadingIndicator.visibility = View.VISIBLE
        val cached = visualImageCache.get(uri.toString())
        if (cached != null) {
            loadingIndicator.visibility = View.GONE
            setPlaylistImageDrawable(uri, cached)
            albumArtView.animate().alpha(1f).setDuration(420L).start()
            startVisualImageAnimation(uri, cached)
            schedulePlaylistImageAdvance(generation)
            return
        }
        visualMediaExecutor.execute {
            val target = maxOf(256, artCard.width, artCard.height)
            val bitmap = decodePlaylistImage(uri, target)
            runOnUiThread {
                if (generation != visualLoadGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                loadingIndicator.visibility = View.GONE
                if (bitmap != null) {
                    setPlaylistImageDrawable(uri, bitmap)
                    albumArtView.alpha = 0f
                    albumArtView.animate().alpha(1f).setDuration(420L).start()
                    startVisualImageAnimation(uri, bitmap)
                } else {
                    albumArtView.alpha = 1f
                    albumArtView.setImageResource(android.R.drawable.ic_menu_report_image)
                    visualPaneDetailsText.text = "Image could not be rendered"
                }
                schedulePlaylistImageAdvance(generation)
            }
        }
    }

    private fun schedulePlaylistImageAdvance(generation: Long) {
        if (generation != visualLoadGeneration.get()) return
        visualImageAdvanceHandler.removeCallbacks(visualImageAdvanceRunnable)
        visualImageAdvanceHandler.postDelayed(visualImageAdvanceRunnable, 5_000L)
    }

    private fun setPlaylistImageDrawable(uri: Uri, bitmap: Bitmap) {
        (albumArtView.drawable as? android.graphics.drawable.Animatable)?.stop()
        val animated = visualAnimationCache[uri.toString()]
        if (animated != null) {
            albumArtView.setImageDrawable(animated)
            (animated as? android.graphics.drawable.Animatable)?.start()
        } else albumArtView.setImageBitmap(bitmap)
    }

    private fun startVisualImageAnimation(uri: Uri, bitmap: Bitmap) {
        stopVisualImageAnimation()
        val animation = visualBitmapAnimationCache[uri.toString()] ?: return
        val playback = PlaylistImageAnimationPlayback(animation, bitmap,
            onFrame = { albumArtView.invalidate() },
            onFailure = { error ->
                if (!isFinishing && !isDestroyed) {
                    visualPaneDetailsText.text = "Image animation stopped"
                    com.infinifolder.storage.DiagnosticLog.record("audio.visual.image-animation.failed", error = error)
                }
            })
        visualImagePlayback = playback
        if (isPlayerResumed) playback.play()
    }

    private fun stopVisualImageAnimation() {
        visualImagePlayback?.close()
        visualImagePlayback = null
    }

    private fun ensureVisualVideoPlayback(track: AudioTrack): VideoSurfacePlayback {
        visualVideoPlayback?.let { return it }
        val mime = track.sourceMimeType ?: com.infinifolder.mime.MimeRegistry.guess(track.fileName)
        val playback = VideoCapabilities.create(this, StreamMetadata(name = track.fileName,
            content = ContentType(mimeType = mime)))
        playback.listener = object : VideoPlayback.Listener {
            override fun onReady(metadata: StreamMetadata) {
                runOnUiThread {
                    loadingIndicator.visibility = View.GONE
                    val size = listOfNotNull(metadata.attributes["width"], metadata.attributes["height"])
                        .takeIf { it.isNotEmpty() }?.joinToString("×")
                    if (size != null) visualPaneDetailsText.text = "VIDEO  $size"
                    totalTimeText.text = formatTime(playback.durationMs ?: 0L)
                    AudioPlayerService.currentService?.let(::updateDisplayedProgress)
                }
            }
            override fun onBuffering(percent: Float) {
                runOnUiThread { if (!isFinishing && !isDestroyed) loadingIndicator.visibility = if (percent < 100f) View.VISIBLE else View.GONE }
            }
            override fun onPlayingChanged(playing: Boolean) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    visualVideoPlaying = playing
                    updateProgressAnimation(playing || (AudioPlayerService.currentService?.isPlaying == true))
                    updatePlayPauseButton(playing || (AudioPlayerService.currentService?.isPlaying == true))
                }
            }
            override fun onEnded() {
                runOnUiThread {
                    val service = AudioPlayerService.currentService ?: return@runOnUiThread
                    if (service.repeatMode == RepeatMode.ONE) {
                        playback.seekTo(0)
                        playback.play()
                    } else service.onVisualItemCompleted()
                }
            }
            override fun onError(message: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingIndicator.visibility = View.GONE
                    visualVideoPlaying = false
                    visualPaneDetailsText.text = "Video unavailable: $message"
                    com.infinifolder.storage.DiagnosticLog.record("audio.visual.video.failed", message)
                    updatePlayPauseButton(AudioPlayerService.currentService?.isPlaying == true)
                }
            }
        }
        playback.view.visibility = View.GONE
        artCard.addView(playback.view, 1, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        visualVideoPlayback = playback
        return playback
    }

    private fun loadPlaylistVideo(track: AudioTrack, generation: Long) {
        albumArtView.visibility = View.GONE
        visualizerPaneVisualizerVisibility(false)
        loadingIndicator.visibility = View.VISIBLE
        val playback = try {
            ensureVisualVideoPlayback(track).also { it.view.visibility = View.VISIBLE }
        } catch (error: Exception) {
            loadingIndicator.visibility = View.GONE
            visualPaneDetailsText.text = "No video renderer: ${error.message ?: "unsupported format"}"
            com.infinifolder.storage.DiagnosticLog.record("audio.visual.video.backend-unavailable", error = error)
            return
        }
        visualMediaExecutor.execute {
            val source = runCatching { VideoPlaybackInput.open(applicationContext, track.uri) }
                .getOrElse { error ->
                    runOnUiThread {
                        if (generation == visualLoadGeneration.get() && !isFinishing && !isDestroyed) {
                            loadingIndicator.visibility = View.GONE
                            visualPaneDetailsText.text = "Video source unavailable: ${error.message ?: "cannot read"}"
                        }
                    }
                    return@execute
                }
            runOnUiThread {
                if (generation != visualLoadGeneration.get() || isFinishing || isDestroyed) {
                    source.close()
                    return@runOnUiThread
                }
                visualVideoInput = source
                playback.view.visibility = View.VISIBLE
                try {
                    val file = source.file
                    if (file != null) playback.openFile(file, source.metadata, autoPlay = true)
                    else playback.openResource(source.resource, autoPlay = true)
                } catch (error: Exception) {
                    stopVisualVideo()
                    loadingIndicator.visibility = View.GONE
                    visualPaneDetailsText.text = "Video could not be opened: ${error.message ?: "unsupported format"}"
                    com.infinifolder.storage.DiagnosticLog.record("audio.visual.video.open-failed", error = error)
                }
            }
        }
    }

    private fun visualizerPaneVisualizerVisibility(visible: Boolean) {
        visualPaneVisualizerView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyTheme(theme: ThemeColors) {
        val isSystemDark = ThemeManager.resolveIsDark(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = if (isSystemDark) Color.BLACK else Color.WHITE
        window.navigationBarColor = if (isSystemDark) Color.BLACK else Color.WHITE
        if (Build.VERSION.SDK_INT >= 30) {
            // Accessing the decor creates it before PhoneWindow queries its controller.
            window.decorView
            val controller = window.insetsController
            if (isSystemDark) {
                controller?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                controller?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            val flags = if (isSystemDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        } else if (Build.VERSION.SDK_INT >= 23) {
            val flags = if (isSystemDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }

        if (::topInsetArea.isInitialized) {
            topInsetArea.setBackgroundColor(if (isSystemDark) Color.BLACK else Color.WHITE)
        }

        val density = resources.displayMetrics.density
        rootLayout.setBackgroundColor(theme.background)

        val barBg = if (isSystemDark) Color.BLACK else Color.WHITE
        val barStroke = theme.toolbarBorder
        val barText = if (isSystemDark) Color.WHITE else Color.BLACK
        val barSubText = theme.textSecondary

        // Clean topBar background - pure black in dark mode, pure white in light mode
        topBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(barBg)
            setStroke((1 * density).toInt().coerceAtLeast(1), barStroke)
        }
        backBtn.setTextColor(barText)
        headerText.setTextColor(barText)
        themeBtn.setTextColor(barText)
        soundFontBtn.setTextColor(barText)
        playlistBtn.setTextColor(barText)
        addToPlaylistBtn.setTextColor(barText)
        castBtn.setTextColor(barText)
        openExtBtn.setTextColor(barSubText)
        visualPaneBtn.setTextColor(if (winampVisualPaneOpen) theme.accent else barText)
        visualPaneBtn.contentDescription = if (winampVisualPaneOpen)
            "Hide visual media window" else "Show visual media window"

        val isWinamp = (theme.id == AppThemeId.WINAMP_CLASSIC || theme.id == AppThemeId.CUSTOM_WSZ)
        val isWmp = theme.id == AppThemeId.WMP_GLASS
        visualPaneBtn.visibility = if (isWinamp) View.VISIBLE else View.GONE
        wmpPlayerLayout.setActive(isWmp)
        if (isWinamp) attachArtCardToVisualWindow(true)
        else if (!isWmp) attachArtCardToVisualWindow(false)

        if (isWinamp) {
            skinContainer.visibility = View.VISIBLE
            nonSkinScroll.visibility = View.GONE
            updateRootPadding()
            dockedVisualWindow.visibility = if (winampVisualPaneOpen) View.VISIBLE else View.GONE
            dockedVisualBody.visibility = View.VISIBLE
            consoleCard.visibility = View.VISIBLE
            artCard.visibility = View.VISIBLE
            winampMainWindowView.visibility = View.VISIBLE
            dockedSubWindow.visibility = View.VISIBLE
            dockedTitleBarView.visibility = View.VISIBLE
            dockedTitleBarView.invalidate()
            dockedLeftBorder.invalidate()
            dockedRightBorder.invalidate()
            dockedBottomBarView.invalidate()
            dockedSubTitleStrip.visibility = View.GONE
            dockedSubWindow.setBackgroundColor(theme.cardBackground)
            dockedVisualWindow.setBackgroundColor(theme.cardBackground)
            dockedVisualTitle.setTextColor(theme.textPrimary)
            dockedVisualTitle.background = GradientDrawable().apply {
                setColor(theme.toolbarBackground)
                setStroke((1 * density).toInt().coerceAtLeast(1), theme.cardStroke)
            }
            dockedVisualBody.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke((1 * density).toInt().coerceAtLeast(1), theme.cardStroke)
            }
            detailsWindow.setBackgroundColor(theme.cardBackground)
            detailsContent.setBackgroundColor(theme.cardBackground)
            skinSpareColumn.setBackgroundColor(theme.background)
            detailsHeader.setTextColor(theme.textSecondary)
            detailsTitle.invalidate()
            detailsBottom.invalidate()
            detailsBody.invalidate()
            dockedPlaylistScroll.setBackgroundColor(theme.cardBackground)
            dockedAlbumArt.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(theme.surface)
                setStroke((1 * density).toInt().coerceAtLeast(1), theme.cardStroke)
            }
            dockedAlbumArt.setPadding(dp(2), dp(2), dp(2), dp(2))
            dockedTrackTitle.setTextColor(theme.textPrimary)
            dockedTrackArtist.setTextColor(theme.textSecondary)
            dockedTrackDetails.setTextColor(theme.textHighlight)
            dockedPlaylistFooter.setBackgroundColor(theme.cardBackground)
            dockedPlaylistFooter.setTextColor(theme.textSecondary)

            dockedSpeedBtn.background = ThemeManager.createButtonDrawable(theme, density)
            dockedSpeedBtn.setTextColor(theme.buttonText)
            dockedDiagnosticsBtn.background = ThemeManager.createButtonDrawable(theme, density)
            dockedDiagnosticsBtn.setTextColor(theme.buttonText)
            dockedCastBtn?.background = ThemeManager.createButtonDrawable(theme, density)
            dockedCastBtn?.setTextColor(theme.buttonText)

            updateDockedPlaylist()
        } else {
            dockedVisualWindow.visibility = View.GONE
            skinContainer.visibility = View.GONE
            nonSkinScroll.visibility = if (isWmp) View.GONE else View.VISIBLE
            updateRootPadding()
            consoleCard.visibility = View.VISIBLE
            artCard.visibility = View.VISIBLE
            progressContainer.visibility = View.VISIBLE
            controlsRow.visibility = View.VISIBLE
            secondaryRow.visibility = View.VISIBLE
            volumeRow.visibility = View.VISIBLE
        }

        headerText.setTextColor(theme.textPrimary)
        headerText.text = "Now Playing"

        val font = if (theme.fontMonospace) Typeface.MONOSPACE else Typeface.DEFAULT
        val fontBold = if (theme.fontMonospace) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD

        headerText.typeface = fontBold
        trackTitleText.typeface = fontBold
        trackTitleText.setTextColor(theme.textPrimary)

        trackArtistText.typeface = font
        trackArtistText.setTextColor(theme.textSecondary)

        technicalInfoText.typeface = font
        technicalInfoText.setTextColor(theme.textHighlight)

        currentTimeText.typeface = fontBold
        currentTimeText.setTextColor(theme.textPrimary)

        timerLedView.typeface = Typeface.MONOSPACE
        timerLedView.setTextColor(theme.textPrimary)
        if (isWinamp) {
            timerLedView.setShadowLayer(8f * density, 0f, 0f, theme.accentGlow)
        } else {
            timerLedView.setShadowLayer(0f, 0f, 0f, 0)
        }

        stereoBadge.typeface = Typeface.MONOSPACE
        stereoBadge.setTextColor(theme.textPrimary)
        kbpsBadge.typeface = Typeface.MONOSPACE
        kbpsBadge.setTextColor(theme.textPrimary)
        khzBadge.typeface = Typeface.MONOSPACE
        khzBadge.setTextColor(theme.textPrimary)

        totalTimeText.typeface = fontBold
        totalTimeText.setTextColor(theme.textSecondary)

        consoleCard.background = if (isWinamp) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor(0xFF000000.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), theme.cardStroke)
            }
        } else {
            ThemeManager.createCardDrawable(theme, density)
        }

        artCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(Color.BLACK)
            setStroke((1 * density).toInt().coerceAtLeast(1), theme.cardStroke)
        }

        // Visualizer styling
        visualizerView.applyTheme(theme)
        visualizerView.visibility = View.VISIBLE
        visualPaneVisualizerView.applyTheme(theme)

        // Seekbar thumb
        val seekThumb = if (isWinamp) ThemeManager.createSeekThumbDrawable() else null
        progressBar.thumb = seekThumb ?: standardSeekThumb
        progressBar.thumbTintList = if (isWinamp) null else android.content.res.ColorStateList.valueOf(theme.accent)
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        progressBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(theme.cardStroke)
        volumeBar.thumbTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        volumeBar.progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        volumeBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(theme.cardStroke)

        // Buttons styling
        prevBtn.setTextColor(theme.textPrimary)
        stopBtn.setTextColor(theme.textPrimary)
        nextBtn.setTextColor(theme.textPrimary)
        themeBtn.setTextColor(theme.textPrimary)
        soundFontBtn.setTextColor(theme.textPrimary)
        playlistBtn.setTextColor(theme.textPrimary)
        addToPlaylistBtn.setTextColor(theme.textPrimary)
        openExtBtn.setTextColor(theme.textSecondary)

        if (isWinamp) {
            val prevBmp = ThemeManager.createCButtonDrawable(0)
            val stopBmp = ThemeManager.createCButtonDrawable(3)
            val nextBmp = ThemeManager.createCButtonDrawable(4)
            val shufBmp = ThemeManager.createShuffleDrawable()
            val repBmp = ThemeManager.createRepeatDrawable()

            if (prevBmp != null) {
                prevBtn.background = prevBmp
                prevBtn.text = ""
                prevBtn.layoutParams = LinearLayout.LayoutParams(dp(46), dp(36))
            } else {
                prevBtn.text = "⏮"
                prevBtn.background = ThemeManager.createButtonDrawable(theme, density)
                prevBtn.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }

            if (stopBmp != null) {
                stopBtn.background = stopBmp
                stopBtn.text = ""
                stopBtn.layoutParams = LinearLayout.LayoutParams(dp(46), dp(36))
            } else {
                stopBtn.text = "⏹"
                stopBtn.background = ThemeManager.createButtonDrawable(theme, density)
                stopBtn.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }

            if (nextBmp != null) {
                nextBtn.background = nextBmp
                nextBtn.text = ""
                nextBtn.layoutParams = LinearLayout.LayoutParams(dp(46), dp(36))
            } else {
                nextBtn.text = "⏭"
                nextBtn.background = ThemeManager.createButtonDrawable(theme, density)
                nextBtn.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }

            if (shufBmp != null) {
                shuffleBtn.background = shufBmp
                shuffleBtn.text = ""
                shuffleBtn.layoutParams = LinearLayout.LayoutParams(dp(50), dp(28)).apply { marginEnd = dp(6) }
            } else {
                shuffleBtn.text = "🔀"
                shuffleBtn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) }
            }

            if (repBmp != null) {
                repeatBtn.background = repBmp
                repeatBtn.text = ""
                repeatBtn.layoutParams = LinearLayout.LayoutParams(dp(36), dp(28)).apply { marginStart = dp(6) }
            } else {
                repeatBtn.text = "🔁"
                repeatBtn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }
            }

            val isPlaying = AudioPlayerService.currentService?.isPlaying == true
            updatePlayPauseButton(isPlaying)
        } else {
            prevBtn.text = "⏮"
            prevBtn.background = ThemeManager.createButtonDrawable(theme, density)
            prevBtn.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))

            stopBtn.text = "⏹"
            stopBtn.background = ThemeManager.createButtonDrawable(theme, density)
            stopBtn.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) }

            nextBtn.text = "⏭"
            nextBtn.background = ThemeManager.createButtonDrawable(theme, density)
            nextBtn.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))

            shuffleBtn.text = "🔀"
            shuffleBtn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) }

            repeatBtn.text = "🔁"
            repeatBtn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }

            playPauseBtn.setTextColor(Color.WHITE)
            playPauseBtn.background = ThemeManager.createButtonDrawable(theme, density, isAccent = true)
            playPauseBtn.text = if (AudioPlayerService.currentService?.isPlaying == true) "⏸" else "▶"
            playPauseBtn.layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }

        speedBtn.setTextColor(theme.buttonText)
        speedBtn.background = ThemeManager.createButtonDrawable(theme, density)

        if (isWmp) {
            topBar.visibility = View.VISIBLE
            topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.toolbarBorder, theme.toolbarBackground)).apply {
                setStroke(dp(1).coerceAtLeast(1), theme.cardStroke)
            }
            listOf(backBtn, headerText, themeBtn, soundFontBtn, playlistBtn, addToPlaylistBtn, castBtn, openExtBtn).forEach {
                it.setTextColor(theme.textPrimary)
            }
            headerText.setOnClickListener { showPlaylistDialog() }
            wmpPlayerLayout.applyTheme(theme)
        } else {
            headerText.setOnClickListener { topBar.visibility = View.GONE }
        }
        updateControlsAppearance()
        if (isWmp) refreshWmpControls()
    }

    private fun toggleCurrentMediaPlayback() {
        val service = AudioPlayerService.currentService
        when (service?.currentTrack?.mediaKind) {
            PlaylistMediaKind.VIDEO -> {
                val playback = visualVideoPlayback ?: return
                if (playback.isPlaying) playback.pause() else playback.play()
                visualVideoPlaying = playback.isPlaying
                updatePlayPauseButton(visualVideoPlaying)
            }
            PlaylistMediaKind.IMAGE -> service?.onVisualItemCompleted()
            else -> if (CastManager.isCasting) CastManager.togglePlayPause()
                else if (service != null && service.playlist.isNotEmpty()) service.togglePlayPause()
                else handleIntent(intent)
        }
    }

    private fun playCurrentMediaPlayback() {
        val service = AudioPlayerService.currentService
        when (service?.currentTrack?.mediaKind) {
            PlaylistMediaKind.VIDEO -> visualVideoPlayback?.play()
            PlaylistMediaKind.IMAGE -> service?.onVisualItemCompleted()
            else -> if (CastManager.isCasting) CastManager.play()
                else if (service != null && service.playlist.isNotEmpty()) service.play()
                else handleIntent(intent)
        }
    }

    private fun stopCurrentMediaPlayback() {
        val service = AudioPlayerService.currentService
        when (service?.currentTrack?.mediaKind) {
            PlaylistMediaKind.VIDEO -> {
                visualVideoPlayback?.pause()
                visualVideoPlayback?.seekTo(0)
                visualVideoPlaying = false
            }
            PlaylistMediaKind.IMAGE -> visualImageAdvanceHandler.removeCallbacks(visualImageAdvanceRunnable)
            else -> {
                if (CastManager.isCasting) {
                    CastManager.seek(0)
                    CastManager.pause()
                }
                service?.let { it.pause(); it.seekTo(0) }
            }
        }
        updatePlayPauseButton(false)
        if (::winampMainWindowView.isInitialized) winampMainWindowView.isPlaying = false
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val displayedPlaying = if (AudioPlayerService.currentService?.currentTrack?.mediaKind == PlaylistMediaKind.VIDEO)
            visualVideoPlaying else isPlaying
        val theme = ThemeManager.getAudioPlayerTheme(this)
        if (theme.id == AppThemeId.WMP_GLASS) {
            refreshWmpControls(displayedPlaying)
            return
        }
        val isWinamp = (theme.id == AppThemeId.WINAMP_CLASSIC || theme.id == AppThemeId.CUSTOM_WSZ)
        if (isWinamp) {
            val sprite = ThemeManager.createCButtonDrawable(if (displayedPlaying) 2 else 1)
            if (sprite != null) {
                playPauseBtn.background = sprite
                playPauseBtn.text = ""
                playPauseBtn.layoutParams = LinearLayout.LayoutParams(dp(46), dp(36)).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
                return
            }
        }
        playPauseBtn.text = if (displayedPlaying) "⏸" else "▶"
    }

    private fun showThemeChooser() {
        val bundledSkins = listOf(
            "Classic 2.91" to "skins/base-2.91.wsz",
            "Bento Retro" to "skins/Bento_Classified.wsz",
            "Classified v3" to "skins/Winamp3_Classified_v5.5.wsz",
            "Classified v5" to "skins/Winamp5_Classified_v5.5.wsz"
        )
        val names = mutableListOf<String>()
        bundledSkins.forEach { (name, _) -> names.add("⚡ $name") }
        names.add("🎨 Windows Media Player (Aero)")
        names.add("🎨 foobar2000 (Minimalist)")
        names.add("🎨 Modern Dark")
        names.add("📂 Import Winamp / XMMS Skin…")

        ThemeManager.createDialogBuilder(this)
            .setTitle("Select Application Theme")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < bundledSkins.size -> {
                        val (_, assetPath) = bundledSkins[which]
                        if (assetPath == "skins/base-2.91.wsz") {
                            ThemeManager.ensureBundledSkinLoaded(this)
                            val baseTheme = ThemeManager.bundledSkinColors ?: ThemeColors.WINAMP_CLASSIC
                            ThemeManager.setAudioPlayerSkin(this, baseTheme, ThemeManager.bundledSkinArtwork, prefKey = AppThemeId.WINAMP_CLASSIC.name)
                        } else {
                            ThemeManager.loadBundledSkin(this, assetPath)?.let { parsed ->
                                ThemeManager.setAudioPlayerSkin(this, parsed.themeColors, parsed.artwork, prefKey = assetPath)
                            }
                        }
                    }
                    which == bundledSkins.size -> {
                        ThemeManager.setAudioPlayerSkin(this, ThemeColors.WMP_GLASS, emptyMap(), prefKey = AppThemeId.WMP_GLASS.name)
                    }
                    which == bundledSkins.size + 1 -> {
                        ThemeManager.setAudioPlayerSkin(this, ThemeColors.FOOBAR2000, emptyMap(), prefKey = AppThemeId.FOOBAR2000.name)
                    }
                    which == bundledSkins.size + 2 -> {
                        ThemeManager.setAudioPlayerSkin(this, ThemeColors.modern(true), emptyMap(), prefKey = AppThemeId.MODERN.name)
                    }
                    else -> {
                        promptImportWszSkin()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMidiSoundFontSettings() {
        val selected = MidiSoundFontSettings.selectedName(this)
        ThemeManager.createDialogBuilder(this)
            .setTitle("MIDI SoundFont")
            .setMessage(if (selected == null) "Using the built-in MIDI synthesizer. Import an SF2 or SF3 soundfont for General MIDI instruments."
                else "Using $selected. The soundfont is stored privately by InfiniFolder.")
            .setPositiveButton("Choose SF2/SF3…") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, 29162)
                } catch (_: Exception) {
                    Toast.makeText(this, "Cannot open file picker", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Use built-in synth") { _, _ ->
                MidiSoundFontSettings.useBuiltInSynth(this)
                Toast.makeText(this, "Built-in MIDI synthesizer selected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptImportWszSkin() {
        val input = android.widget.EditText(this).apply {
            hint = "/sdcard/Download/classic.wsz"
        }
        val padding = dp(20)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

        ThemeManager.createDialogBuilder(this)
            .setTitle("Import Winamp / XMMS Skin")
            .setMessage("Enter the local path or URI of a skin archive (ZIP, WSZ, TAR, TAR.GZ or TAR.BZ2):")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val skinUri = if (path.startsWith("/")) Uri.fromFile(java.io.File(path)) else Uri.parse(path)
                    importAudioSkin(skinUri)
                }
            }
            .setNeutralButton("Browse") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, 29161)
                } catch (error: Exception) {
                    Toast.makeText(this, "Cannot open file picker", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_AUDIO) {
            playlistSelectionPending = false
            if (resultCode == Activity.RESULT_OK && data != null) {
                retainPersistableReadGrants(data)
                val loadId = beginPlaylistUiLoad("Reading selected media…")
                // Give Android a frame to draw the status veil before parsing or
                // expanding a potentially large multi-file selection.
                dockedPlaylistLoadingOverlay.postOnAnimation {
                    if (loadId == activePlaylistUiLoad) enqueueSelection(data, loadId)
                }
            } else hidePlaylistLoading()
            return
        }
        if (requestCode == REQUEST_SAVE_PLAYLIST) {
            if (resultCode == Activity.RESULT_OK && data?.getBooleanExtra(
                    io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_SAVE_SUCCESS, false) == true) {
                val savedName = data.getStringExtra(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_SAVE_RESULT_NAME)
                    ?: "playlist.jspf"
                Toast.makeText(this, "Playlist saved as $savedName", Toast.LENGTH_LONG).show()
            } else if (data?.hasExtra(io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_SAVE_ERROR) == true) {
                Toast.makeText(this, "Could not save playlist: ${data.getStringExtra(
                    io.github.gretchycat.infiniselect.FileSelectionContract.EXTRA_SAVE_ERROR)}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == 29161 && resultCode == Activity.RESULT_OK) data?.data?.let { importAudioSkin(it) }
        if (requestCode == 29162 && resultCode == Activity.RESULT_OK) data?.data?.let { importMidiSoundFont(it) }
    }

    private fun importMidiSoundFont(uri: Uri) {
        Toast.makeText(this, "Importing MIDI soundfont…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { MidiSoundFontSettings.import(applicationContext, uri) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val message = result.fold(
                    { "Using $it for new MIDI playback" },
                    { "Soundfont import failed: ${it.message ?: "invalid file"}" }
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun importAudioSkin(uri: Uri) {
        Toast.makeText(this, "Importing skin…", Toast.LENGTH_SHORT).show()
        Thread {
            val imported = ThemeManager.importSkin(applicationContext, uri, audio = true)
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this,
                    if (imported != null) "Skin '${imported.name}' applied!" else "Failed to parse skin file",
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onThemeChanged(theme: ThemeColors) {
        runOnUiThread {
            val audioTheme = ThemeManager.getAudioPlayerTheme(this)
            applyTheme(audioTheme)
            if (::winampMainWindowView.isInitialized) {
                winampMainWindowView.requestLayout()
                winampMainWindowView.invalidate()
            }
            if (::dockedTitleBarView.isInitialized) {
                dockedTitleBarView.requestLayout()
                dockedTitleBarView.invalidate()
            }
            if (::dockedLeftBorder.isInitialized) {
                dockedLeftBorder.requestLayout()
                dockedLeftBorder.invalidate()
            }
            if (::dockedRightBorder.isInitialized) {
                dockedRightBorder.requestLayout()
                dockedRightBorder.invalidate()
            }
            if (::dockedBottomBarView.isInitialized) {
                dockedBottomBarView.requestLayout()
                dockedBottomBarView.invalidate()
            }
            updateTrackView(AudioPlayerService.currentService?.currentTrack)
            updateControlsAppearance()
            updateDockedPlaylist()
        }
    }

    private fun updateUIFromService(service: AudioPlayerService) {
        val track = service.currentTrack
        updateTrackView(track)
        val isPlaying = if (CastManager.isCasting) CastManager.isRemotePlaying else service.isPlaying
        updateProgressAnimation(isPlaying)
        updatePlayPauseButton(isPlaying)
        loadingIndicator.visibility = if (service.isLoading) View.VISIBLE else View.GONE
        visualizerView.isPlaying = isPlaying

        if (::winampMainWindowView.isInitialized) {
            winampMainWindowView.isPlaying = isPlaying
            winampMainWindowView.isPaused = (!isPlaying && (if (CastManager.isCasting) true else service.currentPosition > 0))
            winampMainWindowView.currentPositionMs = service.currentPosition
            winampMainWindowView.durationMs = service.currentDuration
            val cur = service.currentTrack
            val displayTitle = if (cur != null) {
                if (!cur.artist.isNullOrBlank() && cur.artist != "Unknown Artist") "${cur.artist} - ${cur.title}" else cur.title
            } else "Infinifolder Media"
            winampMainWindowView.trackTitle = displayTitle
            winampMainWindowView.isShuffle = service.isShuffleEnabled
            winampMainWindowView.isRepeat = (service.repeatMode != RepeatMode.NONE)
            winampMainWindowView.volume = service.volume
            winampMainWindowView.balance = service.balance

            val rep = com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.currentReport
            val curTrk = service.currentTrack
            val isBrRel = rep?.isBitrateRelevant ?: (curTrk?.isBitrateRelevant ?: true)
            val kbps = if (isBrRel) (rep?.bitrateKbps?.takeIf { it > 0 } ?: curTrk?.bitrateKbps) else null
            val isSrRel = rep?.isSampleRateRelevant ?: true
            val sampleRate = if (isSrRel) (rep?.sampleRate?.takeIf { it > 0 } ?: curTrk?.sampleRateHz) else null
            val ch = rep?.channels?.takeIf { it > 0 } ?: curTrk?.channels

            winampMainWindowView.channels = ch
            winampMainWindowView.khz = sampleRate?.let { (it / 1000f).toInt() }
            winampMainWindowView.kbps = kbps
        }

        updateControlsAppearance()
        updateTimeDisplay()
        updateDockedPlaylist()
    }

    private fun updateTimeDisplay() {
        val s = AudioPlayerService.currentService
        if (s == null) {
            renderProgress(0, 0)
            return
        }
        updateDisplayedProgress(s)
    }

    private fun updateDisplayedProgress(service: AudioPlayerService) {
        val visualVideo = if (!CastManager.isCasting && service.currentTrack?.mediaKind == PlaylistMediaKind.VIDEO)
            visualVideoPlayback else null
        val position = when {
            visualVideo != null -> visualVideo.positionMs
            CastManager.isCasting -> CastManager.displayPositionMs
            else -> service.currentPosition.toLong()
        }
        val duration = when {
            visualVideo != null -> visualVideo.durationMs ?: 0L
            CastManager.isCasting -> CastManager.displayDurationMs
            else -> service.currentDuration.toLong()
        }
        renderProgress(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun renderProgress(positionMs: Int, durationMs: Int) {
        val pos = positionMs.coerceAtLeast(0)
        val dur = durationMs.coerceAtLeast(0)
        val text = if (showRemainingTime && dur > 0) {
            val rem = (dur - pos).coerceAtLeast(0)
            "-${formatTime(rem)}"
        } else {
            formatTime(pos)
        }
        if (::currentTimeText.isInitialized && currentTimeText.text.toString() != text) currentTimeText.text = text
        if (::timerLedView.isInitialized && timerLedView.text.toString() != text) {
            timerLedView.text = text
        }
        if (::progressBar.isInitialized && !isUserSeeking) {
            val target = if (dur > 0) ((pos.toLong() * progressBar.max) / dur).toInt().coerceIn(0, progressBar.max) else 0
            if (progressBar.progress != target) progressBar.progress = target
        }
        if (::winampMainWindowView.isInitialized) {
            if (winampMainWindowView.currentPositionMs != pos) winampMainWindowView.currentPositionMs = pos
            if (winampMainWindowView.durationMs != dur) winampMainWindowView.durationMs = dur
        }
    }

    private fun updateProgressAnimation(playing: Boolean) {
        if (playing && isPlayerResumed) scheduleProgressFrame() else stopProgressFrames()
    }

    private fun scheduleProgressFrame() {
        if (progressFramePosted || !isPlayerResumed) return
        progressFramePosted = true
        Choreographer.getInstance().postFrameCallback(progressFrameCallback)
    }

    private fun stopProgressFrames() {
        if (progressFramePosted) Choreographer.getInstance().removeFrameCallback(progressFrameCallback)
        progressFramePosted = false
    }

    private fun updateTrackView(track: AudioTrack?) {
        if (track == null) return
        val visualKey = "${track.mediaKind}:${track.uri}"
        val changedVisual = visualKey != activeVisualTrackKey
        if (changedVisual) {
            activeVisualTrackKey = visualKey
            visualLoadGeneration.incrementAndGet()
            visualImageAdvanceHandler.removeCallbacks(visualImageAdvanceRunnable)
            albumArtView.animate().cancel()
            (albumArtView.drawable as? android.graphics.drawable.Animatable)?.stop()
            stopVisualImageAnimation()
            stopVisualVideo()
        }
        val displayTitle = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
            "${track.artist} - ${track.title}"
        } else {
            track.title
        }
        trackTitleText.text = displayTitle
        val sub = listOfNotNull(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • ")
        trackArtistText.text = if (sub.isNotBlank()) sub else "Infinifolder Media"
        visualPaneTitleText.text = track.title.ifBlank { track.fileName }
        val metadataDetails = listOfNotNull(
            track.artist?.takeIf(String::isNotBlank)?.let { "ARTIST  $it" },
            track.album?.takeIf(String::isNotBlank)?.let { "ALBUM  $it" }
        ).joinToString("\n")

        val generation = visualLoadGeneration.get()
        when (track.mediaKind) {
            PlaylistMediaKind.IMAGE -> {
                visualPaneDetailsText.text = metadataDetails.ifBlank {
                    "IMAGE  ${track.sourceMimeType ?: com.infinifolder.mime.MimeRegistry.guess(track.fileName)}"
                }
                visualizerPaneVisualizerVisibility(false)
                if (changedVisual) loadPlaylistImage(track, generation)
            }
            PlaylistMediaKind.VIDEO -> {
                visualPaneDetailsText.text = metadataDetails.ifBlank { "VIDEO" }
                visualizerPaneVisualizerVisibility(false)
                if (changedVisual) loadPlaylistVideo(track, generation)
            }
            PlaylistMediaKind.AUDIO -> {
                visualPaneDetailsText.text = metadataDetails
                visualizerPaneVisualizerVisibility(true)
                if (changedVisual || albumArtView.visibility != View.VISIBLE) {
                    val audioArtwork = track.artwork ?: track.directoryArtwork
                        ?: if (!track.directoryName.isNullOrBlank()) directoryEntryBitmap(track, dp(256)) else null
                    albumArtView.visibility = View.VISIBLE
                    albumArtView.alpha = 1f
                    if (audioArtwork != null) {
                        albumArtView.setImageBitmap(audioArtwork)
                        albumArtView.setBackgroundColor(Color.TRANSPARENT)
                    } else {
                        albumArtView.setImageResource(android.R.drawable.ic_media_play)
                        albumArtView.setBackgroundColor(ThemeManager.getAudioPlayerTheme(this).alternateBackground)
                    }
                } else if (track.artwork != null) {
                    albumArtView.setImageBitmap(track.artwork)
                }
            }
        }

        val activeArtwork = track.artwork ?: track.directoryArtwork
            ?: if (track.mediaKind == PlaylistMediaKind.AUDIO && !track.directoryName.isNullOrBlank())
                directoryEntryBitmap(track, dp(256)) else null

        val rep = com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.currentReport
        val isBrRel = rep?.isBitrateRelevant ?: (track.isBitrateRelevant)
        val kbps = if (isBrRel) (rep?.bitrateKbps?.takeIf { it > 0 } ?: track.bitrateKbps) else null
        val isSrRel = rep?.isSampleRateRelevant ?: true
        val sampleRate = if (isSrRel) (rep?.sampleRate?.takeIf { it > 0 } ?: track.sampleRateHz) else null
        val ch = rep?.channels?.takeIf { it > 0 } ?: track.channels

        stereoBadge.text = when {
            ch == 1 -> "MONO"
            ch != null && ch >= 2 -> "STEREO"
            else -> "--"
        }
        khzBadge.text = if (sampleRate != null && sampleRate > 0) "${(sampleRate / 1000f).toInt()} KHZ" else "--"
        kbpsBadge.text = if (kbps != null && kbps > 0) "$kbps KBPS" else "--"

        val theme = ThemeManager.getAudioPlayerTheme(this)
        stereoBadge.setTextColor(if (ch != null && ch > 0) theme.textPrimary else theme.textSecondary)
        khzBadge.setTextColor(if (sampleRate != null && sampleRate > 0) theme.textPrimary else theme.textSecondary)
        kbpsBadge.setTextColor(if (kbps != null && kbps > 0) theme.textPrimary else theme.textSecondary)

        val durSec = (track.durationMs / 1000).coerceAtLeast(0)
        val ext = track.title.substringAfterLast('.', "").uppercase()
        val engine = rep?.engineUsed ?: ext
        val parts = mutableListOf<String>()
        if (ext.isNotEmpty()) parts.add(ext)
        if (sampleRate != null && sampleRate > 0) parts.add("${sampleRate / 1000f} kHz")
        if (kbps != null && kbps > 0) parts.add("$kbps kbps")
        if (ch != null && ch > 0) parts.add(if (ch == 1) "Mono" else if (ch == 2) "Stereo" else "${ch}ch")
        if (engine.isNotBlank() && engine != ext) parts.add(engine)
        parts.add("${durSec}s")
        val health = if (rep != null && rep.healthStatus != com.infinifolder.audio.diagnostics.AudioHealthStatus.HEALTHY) " • ${rep.healthStatus}" else ""
        technicalInfoText.text = parts.joinToString(" • ") + health

        if (track != null) {
            if (::dockedAlbumArt.isInitialized) {
                if (activeArtwork != null) {
                    dockedAlbumArt.setImageBitmap(activeArtwork)
                } else {
                    dockedAlbumArt.setImageResource(android.R.drawable.ic_media_play)
                }
            }
            if (::dockedTrackTitle.isInitialized) {
                dockedTrackTitle.text = displayTitle
            }
            if (::dockedTrackArtist.isInitialized) {
                dockedTrackArtist.text = listOfNotNull(track.artist, track.album)
                    .filter { it.isNotBlank() }.distinct().joinToString(" • ")
                    .ifBlank { "Infinifolder Media" }
            }
            if (::dockedTrackDetails.isInitialized) {
                val khzStr = if (sampleRate != null && sampleRate > 0) "${(sampleRate / 1000f).toInt()} kHz" else "--"
                val chStr = when {
                    ch == 1 -> "Mono"
                    ch != null && ch >= 2 -> "Stereo"
                    else -> "--"
                }
                val brStr = if (kbps != null && kbps > 0) "$kbps kbps" else "--"
                dockedTrackDetails.text = "$khzStr • $chStr • $brStr • ${rep?.engineUsed ?: "Audio"} • ${durSec}s"
            }
            if (::winampMainWindowView.isInitialized) {
                winampMainWindowView.trackTitle = displayTitle
                winampMainWindowView.durationMs = track.durationMs.toInt()
                winampMainWindowView.channels = ch
                winampMainWindowView.khz = sampleRate?.let { (it / 1000f).toInt() }
                winampMainWindowView.kbps = kbps
            }
        }
        updateDockedPlaylist()
    }

    private fun showDiagnosticsDialog() {
        val rep = com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.currentReport
        val message = rep?.formattedSummary() ?: "No active audio diagnostics recorded yet."
        ThemeManager.createDialogBuilder(this)
            .setTitle("Audio Playback Diagnostics")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Emoji ignore text color, so active state is shown with an accent pill background + full opacity. */
    private fun setToggleState(btn: Button, active: Boolean) {
        val theme = ThemeManager.activeTheme
        btn.alpha = if (active) 1f else 0.4f
        btn.background = if (active) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(70, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)))
                setStroke(dp(1), theme.accent)
            }
        } else {
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun updateControlsAppearance() {
        val s = AudioPlayerService.currentService ?: return
        val speedStr = String.format(Locale.US, "%.2fx", s.playbackSpeed)
        speedBtn.text = speedStr
        if (::dockedSpeedBtn.isInitialized) dockedSpeedBtn.text = speedStr
        if (ThemeManager.getAudioPlayerTheme(this).id == AppThemeId.WMP_GLASS) {
            refreshWmpControls()
            return
        }
        val theme = ThemeManager.activeTheme
        shuffleBtn.setTextColor(if (s.isShuffleEnabled) theme.accent else theme.textSecondary)
        setToggleState(shuffleBtn, s.isShuffleEnabled)
        repeatBtn.apply {
            when (s.repeatMode) {
                RepeatMode.NONE -> {
                    text = "🔁"
                    setTextColor(theme.textSecondary)
                }
                RepeatMode.ALL -> {
                    text = "🔁"
                    setTextColor(theme.accent)
                }
                RepeatMode.ONE -> {
                    text = "🔂"
                    setTextColor(theme.accent)
                }
            }
        }
        setToggleState(repeatBtn, s.repeatMode != RepeatMode.NONE)
    }

    private fun refreshWmpControls(playing: Boolean? = null) {
        if (!::wmpPlayerLayout.isInitialized) return
        val service = AudioPlayerService.currentService
        val effectivePlaying = playing ?: if (CastManager.isCasting) CastManager.isRemotePlaying
            else service?.isPlaying == true
        wmpPlayerLayout.updateControls(effectivePlaying, service?.isShuffleEnabled == true,
            service?.repeatMode ?: RepeatMode.NONE)
    }

    private fun showSpeedPicker() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        ThemeManager.createDialogBuilder(this)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                AudioPlayerService.currentService?.setSpeed(values[which])
                updateControlsAppearance()
            }
            .show()
    }

    private fun showOutputRoutingDialog() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
                else -> false
            }
        }
        val names = listOf("System default") + devices.map { device ->
            val name = device.productName?.toString()?.trim().orEmpty()
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                else -> name.ifEmpty { "Audio device" }
            }
        }
        ThemeManager.createDialogBuilder(this)
            .setTitle("Audio output")
            .setItems(names.toTypedArray()) { _, which ->
                val player = AudioPlayerService.currentService
                if (player == null) {
                    Toast.makeText(this, "Start playback to choose an output", Toast.LENGTH_SHORT).show()
                } else if (player.setOutputDevice(if (which == 0) null else devices[which - 1])) {
                    if (CastManager.isCasting) CastManager.disconnect()
                } else {
                    Toast.makeText(this, "Could not select this audio output", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Bluetooth settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                catch (_: Exception) { Toast.makeText(this, "Bluetooth settings unavailable", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var playlistDialog: AlertDialog? = null
    private var playlistDialogAdapter: android.widget.ArrayAdapter<String>? = null

    private fun showPlaylistDialog() {
        val service = AudioPlayerService.currentService ?: return
        if (selectedPlaylistIndex !in service.playlist.indices) selectedPlaylistIndex = service.currentIndex
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        playlistDialogAdapter = adapter
        val dialog = ThemeManager.createDialogBuilder(this)
            .setTitle("Playlist")
            .setAdapter(adapter) { _, which ->
                selectedPlaylistIndex = which
                service.jumpTo(which)
                updateDockedPlaylist()
            }
            .setPositiveButton("Add to playlist") { _, _ -> showMediaCategoryDialog() }
            .setNeutralButton("Remove selected") { _, _ ->
                if (service.removePlaylistItem(selectedPlaylistIndex)) {
                    selectedPlaylistIndex = service.currentIndex
                    updateDockedPlaylist()
                }
            }
            .setNegativeButton("Close", null)
            .create()
        playlistDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled =
                selectedPlaylistIndex in service.playlist.indices
            val listView = dialog.listView
            listView.setOnItemLongClickListener { _, row, position, _ ->
                showPlaylistItemMenu(row, position)
                true
            }
        }
        dialog.setOnDismissListener {
            if (playlistDialog === dialog) {
                playlistDialog = null
                playlistDialogAdapter = null
            }
        }
        refreshPlaylistDialog()
        dialog.show()
    }

    private fun removeSelectedPlaylistItem() {
        val service = AudioPlayerService.currentService ?: return
        if (selectedPlaylistIndex !in service.playlist.indices) {
            Toast.makeText(this, "Select a playlist track to remove", Toast.LENGTH_SHORT).show()
            return
        }
        if (service.removePlaylistItem(selectedPlaylistIndex)) {
            selectedPlaylistIndex = service.currentIndex
            updateDockedPlaylist()
            refreshPlaylistDialog()
        }
    }

    private fun selectCurrentlyPlayingPlaylistItem() {
        val service = AudioPlayerService.currentService ?: return
        val index = service.currentIndex
        if (index !in service.playlist.indices) {
            Toast.makeText(this, "There is no current playlist item to select", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPlaylistIndex = index
        updateDockedPlaylist()
        refreshPlaylistDialog()
        scrollToSelectedPlaylistItem()
    }

    private fun showPlaylistActionsDialog() {
        val service = AudioPlayerService.currentService ?: return
        val items = arrayOf(
            "Save playlist as JSPF",
            "Shuffle: ${if (service.isShuffleEnabled) "On" else "Off"}",
            "Repeat: ${service.repeatMode.name.lowercase().replaceFirstChar { it.uppercase() }}",
            "Clear playlist",
            "Select currently playing track",
        )
        ThemeManager.createDialogBuilder(this)
            .setTitle("Playlist actions")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> savePlaylistExternally()
                    1 -> {
                        service.toggleShuffle()
                        updateDockedPlaylist(forceRebuild = true)
                        Toast.makeText(this, "Shuffle ${if (service.isShuffleEnabled) "on" else "off"}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        service.cycleRepeatMode()
                        Toast.makeText(this, "Repeat ${service.repeatMode.name.lowercase()}", Toast.LENGTH_SHORT).show()
                    }
                    3 -> confirmClearPlaylist(service)
                    4 -> {
                        selectedPlaylistIndex = service.currentIndex
                        updateDockedPlaylist()
                        scrollToSelectedPlaylistItem()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmClearPlaylist(service: AudioPlayerService) {
        if (synchronized(service.playlist) { service.playlist.isEmpty() }) {
            Toast.makeText(this, "The playlist is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        ThemeManager.createDialogBuilder(this)
            .setTitle("Clear playlist?")
            .setMessage("This stops playback and clears the saved working playlist.")
            .setPositiveButton("Clear") { _, _ ->
                service.clearPlaylist()
                selectedPlaylistIndex = -1
                updateDockedPlaylist(forceRebuild = true)
                refreshPlaylistDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scrollToSelectedPlaylistItem() {
        val selected = selectedPlaylistIndex
        if (selected < 0) return
        val rowIndex = (0 until dockedPlaylistItems.childCount).firstOrNull { index ->
            (dockedPlaylistItems.getChildAt(index)?.tag as? Int) == selected
        } ?: return
        dockedPlaylistScroll.post { dockedPlaylistScroll.smoothScrollTo(0, dockedPlaylistItems.getChildAt(rowIndex).top) }
    }

    private fun reorderPlaylistItem(from: Int, to: Int) {
        val service = AudioPlayerService.currentService ?: return
        if (!service.movePlaylistItem(from, to)) return
        selectedPlaylistIndex = to
        updateDockedPlaylist(forceRebuild = true)
        refreshPlaylistDialog()
    }

    /** Hold and release opens the row menu; hold and move starts an Android drag. */
    private fun installPlaylistGesture(row: View, position: Int) {
        val handler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var tracking = false
        var cancelled = false
        var longPressed = false
        var dragStarted = false
        val longPress = Runnable {
            if (tracking && !cancelled) {
                longPressed = true
                row.parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        row.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    cancelled = false
                    longPressed = false
                    dragStarted = false
                    downX = event.x
                    downY = event.y
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, longPressTimeout)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val moved = dx * dx + dy * dy > touchSlop * touchSlop
                    if (moved && longPressed && !dragStarted) {
                        val dragData = ClipData.newPlainText("playlist-position", position.toString())
                        dragStarted = row.startDragAndDrop(
                            dragData, View.DragShadowBuilder(row), position, 0
                        )
                        cancelled = true
                    } else if (moved && !longPressed) {
                        cancelled = true
                        handler.removeCallbacks(longPress)
                    }
                    dragStarted
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val wasLongGesture = tracking && longPressed
                    val showMenu = wasLongGesture && !cancelled
                    val wasDragging = dragStarted
                    tracking = false
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    if (wasLongGesture && !wasDragging) {
                        if (showMenu) showPlaylistItemMenu(row, position)
                        true
                    } else wasDragging
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    tracking = false
                    cancelled = true
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    dragStarted
                }
                else -> dragStarted
            }
        }
    }

    private fun showPlaylistItemMenu(anchor: View, index: Int) {
        val service = AudioPlayerService.currentService ?: return
        if (!synchronized(service.playlist) { index in service.playlist.indices }) return
        selectedPlaylistIndex = index
        PopupMenu(this, anchor).apply {
            menu.add("Remove from playlist").setOnMenuItemClickListener {
                if (service.removePlaylistItem(index)) {
                    selectedPlaylistIndex = service.currentIndex
                    updateDockedPlaylist(forceRebuild = true)
                    refreshPlaylistDialog()
                }
                true
            }
            show()
        }
    }

    private fun refreshPlaylistDialog() {
        val dialog = playlistDialog ?: return
        val adapter = playlistDialogAdapter ?: return
        val service = AudioPlayerService.currentService ?: return
        val list = synchronized(service.playlist) { ArrayList(service.playlist) }
        val titles = list.mapIndexed { index, track ->
            val mark = if (index == service.currentIndex) "▶ " else "   "
            val title = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") "${track.artist} - ${track.title}" else track.title
            val duration = if (track.durationMs > 0) formatTime(track.durationMs) else "--"
            "$mark${index + 1}. $title  $duration"
        }
        dialog.setTitle("Playlist (${list.size} tracks)")
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = selectedPlaylistIndex in list.indices
        adapter.clear()
        adapter.addAll(titles)
    }

    private fun openExternally() {
        val cur = AudioPlayerService.currentService?.currentTrack ?: return
        val extIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(cur.uri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(extIntent, "Open with…"))
        } catch (_: Exception) {
            Toast.makeText(this, "No external audio player found", Toast.LENGTH_SHORT).show()
        }
    }

    // AudioPlayerListener Callbacks
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        runOnUiThread {
            updateVisualizerCapture(audioSessionId)
        }
    }

    override fun onTrackChanged(track: AudioTrack?, index: Int, total: Int) {
        runOnUiThread {
            selectedPlaylistIndex = index.takeIf { it in 0 until total } ?: -1
            updateTrackView(track)
            updateControlsAppearance()
            updateTimeDisplay()
            totalTimeText.text = formatTime(track?.durationMs?.toInt() ?: 0)
            progressBar.progress = 0
            if (::winampMainWindowView.isInitialized) {
                val cur = track ?: AudioPlayerService.currentService?.currentTrack
                val displayTitle = if (cur != null) {
                    if (!cur.artist.isNullOrBlank() && cur.artist != "Unknown Artist") "${cur.artist} - ${cur.title}" else cur.title
                } else "Infinifolder Media"
                winampMainWindowView.trackTitle = displayTitle
                winampMainWindowView.durationMs = track?.durationMs?.toInt() ?: 0
                winampMainWindowView.currentPositionMs = 0

                val rep = com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.currentReport
                val isBrRel = rep?.isBitrateRelevant ?: (cur?.isBitrateRelevant ?: true)
                val kbps = if (isBrRel) (rep?.bitrateKbps?.takeIf { it > 0 } ?: cur?.bitrateKbps) else null
                val isSrRel = rep?.isSampleRateRelevant ?: true
                val sampleRate = if (isSrRel) (rep?.sampleRate?.takeIf { it > 0 } ?: cur?.sampleRateHz) else null
                val ch = rep?.channels?.takeIf { it > 0 } ?: cur?.channels

                winampMainWindowView.channels = ch
                winampMainWindowView.khz = sampleRate?.let { (it / 1000f).toInt() }
                winampMainWindowView.kbps = kbps
            }
            updateDockedPlaylist()
            refreshPlaylistDialog()
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, isLoading: Boolean) {
        runOnUiThread {
            val effectivePlaying = if (CastManager.isCasting) CastManager.isRemotePlaying else isPlaying || visualVideoPlaying
            updateProgressAnimation(effectivePlaying)
            if (effectivePlaying && !CastManager.isCasting) updateVisualizerCapture(AudioPlayerService.currentService?.audioSessionId ?: 0)
            updatePlayPauseButton(effectivePlaying)
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            visualizerView.isPlaying = effectivePlaying
            visualPaneVisualizerView.isPlaying = effectivePlaying
            if (::winampMainWindowView.isInitialized) {
                winampMainWindowView.isPlaying = effectivePlaying
                winampMainWindowView.isPaused = (!effectivePlaying && ((AudioPlayerService.currentService?.currentPosition ?: 0) > 0 || CastManager.isCasting))
            }
        }
    }

    override fun onProgressUpdated(currentPositionMs: Int, durationMs: Int) {
        if (isUserSeeking) return
        runOnUiThread {
            renderProgress(currentPositionMs, durationMs)
            val durationText = formatTime(durationMs)
            if (totalTimeText.text.toString() != durationText) totalTimeText.text = durationText
        }
    }

    override fun onPlaylistUpdated(playlist: List<AudioTrack>) {
        runOnUiThread {
            updateControlsAppearance()
            val loadId = activePlaylistUiLoad
            if (loadId != 0L) {
                setPlaylistUiLoadStage(loadId, "Updating playlist view (${playlist.size} tracks)…", "phase=playlist-changed")
                // Allow the status overlay to be drawn before creating row views.
                dockedPlaylistItems.postDelayed({
                    if (loadId != activePlaylistUiLoad || isFinishing || isDestroyed) return@postDelayed
                    updateDockedPlaylist()
                    refreshPlaylistDialog()
                    waitForPlaylistRowsToRender(loadId)
                }, 80L)
            } else {
                updateDockedPlaylist()
                refreshPlaylistDialog()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            val service = AudioPlayerService.currentService
            service?.cacheFailure?.let { failure ->
                service.cacheFailure = null
                if (CacheFailureUi.offerCopy(this, failure.first, failure.second)) return@runOnUiThread
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPlaybackComplete() {
        runOnUiThread {
            updatePlayPauseButton(false)
            visualizerView.isPlaying = false
            progressBar.progress = 1000
            currentTimeText.text = totalTimeText.text
            if (::timerLedView.isInitialized) {
                timerLedView.text = totalTimeText.text
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun formatTime(ms: Int): String = formatTime(ms.toLong())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDebugDumpFinished() {
        // Refresh UI state or post status if necessary
    }

    override fun debugDumpState(): String {
        val rep = com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics.currentReport
        return rep?.formattedSummary() ?: "No active audio diagnostics recorded"
    }

    private fun formatCompactButton(
        btn: Button,
        title: String,
        textSizeSp: Float = 11f,
        padH: Int = 8,
        padV: Int = 3
    ) {
        btn.text = title
        btn.textSize = textSizeSp
        btn.minHeight = 0
        btn.minimumHeight = 0
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.includeFontPadding = false
        btn.isAllCaps = false
        btn.gravity = Gravity.CENTER
        btn.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    private fun updateCastButtonState() {
        val casting = CastManager.isCasting
        val connected = CastManager.hasConnectedSession
        if (::castBtn.isInitialized) castBtn.text = when {
            casting -> "📻"
            connected -> "📡✓"
            else -> "📡"
        }
        dockedCastBtn?.let {
            it.text = when {
                casting -> "📻 ${CastManager.deviceName?.take(10) ?: "Casting"}"
                connected -> "📡 ${CastManager.deviceName?.take(10) ?: "Ready"}"
                else -> "📡 Cast"
            }
            it.includeFontPadding = false
            it.isAllCaps = false
        }
    }

    private fun launchAudioOutput() = showOutputRoutingDialog()

    private fun showCastDialog() {
        val items = mutableListOf("Google Cast", "Bluetooth and audio output")
        if (CastManager.isCasting) items.add("Disconnect from ${CastManager.deviceName ?: "Cast"}")
        ThemeManager.createDialogBuilder(this)
            .setTitle("Audio output")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, CastRouteActivity::class.java))
                    1 -> showOutputRoutingDialog()
                    2 -> CastManager.disconnect()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCastStateChanged(isCasting: Boolean, device: String?) {
        runOnUiThread {
            updateCastButtonState()
            updateVisualizerCapture(AudioPlayerService.currentService?.audioSessionId ?: 0)
            val playing = if (isCasting) CastManager.isRemotePlaying else (AudioPlayerService.currentService?.isPlaying == true)
            updateProgressAnimation(playing)
            AudioPlayerService.currentService?.let(::updateDisplayedProgress)
            updatePlayPauseButton(playing)
            if (::winampMainWindowView.isInitialized) winampMainWindowView.isPlaying = playing
        }
    }

    override fun onCastPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            updateProgressAnimation(isPlaying)
            AudioPlayerService.currentService?.let(::updateDisplayedProgress)
            if (CastManager.isCasting) {
                updatePlayPauseButton(isPlaying)
                if (::winampMainWindowView.isInitialized) {
                    winampMainWindowView.isPlaying = isPlaying
                }
                visualizerView.isPlaying = isPlaying
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        returnToFileManager()
    }

    override fun onDestroy() {
        activePlaylistUiLoad = 0L
        playlistUiLoadStartedAt = 0L
        playlistUiLoadHandler.removeCallbacks(playlistUiElapsedTicker)
        visualImageAdvanceHandler.removeCallbacks(visualImageAdvanceRunnable)
        visualLoadGeneration.incrementAndGet()
        visualMediaExecutor.shutdownNow()
        visualAnimationCache.values.forEach { (it as? android.graphics.drawable.Animatable)?.stop() }
        visualAnimationCache.clear()
        stopVisualImageAnimation()
        visualBitmapAnimationCache.clear()
        runCatching { visualVideoPlayback?.stop() }
        visualVideoInput?.close()
        visualVideoPlayback?.close()
        visualVideoInput = null
        visualVideoPlayback = null
        directoryCoverExecutor.shutdownNow()
        pendingDirectoryCovers.clear()
        failedDirectoryCovers.clear()
        super.onDestroy()
        stopProgressFrames()
        visualizerBridge?.detach()
        CastManager.removeListener(this)
        AudioPlayerService.removeListener(this)
        ThemeManager.removeListener(this)
        // Back/close only hides the player: the service keeps the track (playing or paused) available
        // from its notification. Finished playback shuts the service down by itself.
    }
}
