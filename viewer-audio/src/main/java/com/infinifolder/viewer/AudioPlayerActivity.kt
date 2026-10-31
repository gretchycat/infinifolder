package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import java.util.Locale

class AudioPlayerActivity : ViewerActivity(), AudioPlayerListener, ThemeChangeListener, DebugDumpObserver, CastManager.Listener {

    private lateinit var audioManager: AudioManager

    private lateinit var rootLayout: LinearLayout
    private lateinit var topInsetArea: View
    private lateinit var topBar: LinearLayout
    private lateinit var headerText: TextView
    private lateinit var backBtn: TextView
    private lateinit var themeBtn: TextView
    private lateinit var playlistBtn: TextView
    private lateinit var castBtn: TextView
    private lateinit var openExtBtn: TextView

    private lateinit var consoleCard: LinearLayout
    private lateinit var timerLedView: TextView
    private lateinit var stereoBadge: TextView
    private lateinit var kbpsBadge: TextView
    private lateinit var khzBadge: TextView

    private lateinit var artCard: FrameLayout
    private lateinit var albumArtView: ImageView
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
    private lateinit var dockedBottomBarView: WinampPlaylistBottomView
    private lateinit var mainColumn: LinearLayout
    private lateinit var dockedAlbumArt: ImageView
    private lateinit var dockedTrackTitle: TextView
    private lateinit var dockedTrackArtist: TextView
    private lateinit var dockedTrackDetails: TextView
    private lateinit var dockedSpeedBtn: Button
    private lateinit var dockedDiagnosticsBtn: Button
    private var dockedCastBtn: Button? = null
    private lateinit var dockedPlaylistHeader: TextView
    private lateinit var dockedPlaylistFooter: TextView
    private lateinit var dockedPlaylistItems: LinearLayout
    private lateinit var progressContainer: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var secondaryRow: LinearLayout
    private lateinit var volumeRow: LinearLayout
    private var visualizerBridge: RealAudioVisualizerBridge? = null
    private var isPlayerResumed = false
    private val visualizerBands = FloatArray(16)

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
        val padH = if (isWinamp) 0 else (dp(12) + systemInsetLeft)
        val effectiveTop = if (systemInsetTop > 0) systemInsetTop else getStatusBarHeight()
        val isSystemDark = ThemeManager.resolveIsDark(this)
        if (::topInsetArea.isInitialized) {
            topInsetArea.layoutParams = LinearLayout.LayoutParams(-1, effectiveTop)
            topInsetArea.setBackgroundColor(if (isSystemDark) Color.BLACK else Color.WHITE)
        }
        val padT = if (isWinamp) 0 else dp(6)
        val padB = (if (isWinamp) 0 else dp(16)) + systemInsetBottom
        rootLayout.setPadding(padH, padT, padH, padB)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)

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
        visualizerBridge?.detach()
        if (::visualizerView.isInitialized) visualizerView.updateData(FloatArray(16))
        super.onPause()
    }

    private fun updateVisualizerCapture(sessionId: Int, explicitRequest: Boolean = false) {
        val service = AudioPlayerService.currentService
        val source = service?.analysisSource
        if (!isPlayerResumed || CastManager.isCasting || sessionId <= 0 || source == null) {
            visualizerBridge?.detach()
            if (::visualizerView.isInitialized) visualizerView.updateData(FloatArray(16))
            return
        }
        visualizerBridge?.attach(source) {
            val playing = service.isPlaying && !CastManager.isCasting
            playing to service.currentPosition.toLong()
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
        val targetUri = intent?.data ?: intent?.getStringExtra("uri")?.let { Uri.parse(it) } ?: return
        val displayName = intent?.getStringExtra("displayName") ?: targetUri.lastPathSegment ?: "Audio Track"

        val serviceIntent = Intent(this, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_LOAD
            data = targetUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("uri", targetUri.toString())
            putExtra("name", displayName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Thread {
            val tracks = mutableListOf<AudioTrack>()
            val isDirectPlaylist = PlaylistExpander.isPlaylist(targetUri, displayName)

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

            if (tracks.none { AudioPlayerService.uriMatches(it.uri, targetUri) }) {
                if (!isDirectPlaylist) {
                    tracks.add(0, AudioTrack(targetUri, displayName))
                }
            }

            var startIndex = tracks.indexOfFirst { AudioPlayerService.uriMatches(it.uri, targetUri) }
            if (startIndex < 0) startIndex = 0

            runOnUiThread {
                val service = AudioPlayerService.currentService
                val isSameTrack = AudioPlayerService.uriMatches(service?.currentTrack?.uri, targetUri)
                if (service != null && isSameTrack) {
                    service.updatePlaylistKeepCurrent(tracks, targetUri)
                    updateUIFromService(service)
                } else if (service != null) {
                    service.setPlaylist(tracks, startIndex, startAtAlphabeticalBeginning = isDirectPlaylist)
                } else {
                    window.decorView.postDelayed({
                        AudioPlayerService.currentService?.let { s ->
                            val match = AudioPlayerService.uriMatches(s.currentTrack?.uri, targetUri)
                            if (match) {
                                s.updatePlaylistKeepCurrent(tracks, targetUri)
                                updateUIFromService(s)
                            } else {
                                s.setPlaylist(tracks, startIndex, startAtAlphabeticalBeginning = isDirectPlaylist)
                            }
                        }
                    }, 200)
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

                if (isTap) {
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
        configureSkinLayout()
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

        // Album Art Box / Cassette Bezel
        artCard = FrameLayout(this).apply {
            val size = dp(200)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
            clipToOutline = true
        }

        albumArtView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        artCard.addView(albumArtView)

        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            layoutParams = lp
        }
        artCard.addView(loadingIndicator)

        mainColumn.addView(artCard)

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
                    if (CastManager.isCasting) {
                        CastManager.seek(targetMs.toLong())
                    }
                    AudioPlayerService.currentService?.seekTo(targetMs)
                }
            })
        }
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
            setOnClickListener {
                if (CastManager.isCasting) {
                    CastManager.togglePlayPause()
                    return@setOnClickListener
                }
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.togglePlayPause()
                } else {
                    handleIntent(intent)
                }
            }
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
                if (CastManager.isCasting) {
                    CastManager.seek(0)
                    CastManager.pause()
                }
                AudioPlayerService.currentService?.let {
                    it.pause()
                    it.seekTo(0)
                }
                updatePlayPauseButton(false)
                if (::winampMainWindowView.isInitialized) {
                    winampMainWindowView.isPlaying = false
                }
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
        setContentView(rootLayout)
    }

    private fun setupWinampMainWindow() {
        visualizerBridge = RealAudioVisualizerBridge {
            visualizerBridge?.getSpectrum(visualizerBands, isPlaying = AudioPlayerService.currentService?.isPlaying == true && !CastManager.isCasting)
            if (::visualizerView.isInitialized) visualizerView.updateData(visualizerBands)
            winampMainWindowView.invalidate()
        }
        winampMainWindowView.visualizerBridge = visualizerBridge

        winampMainWindowView.callback = object : WinampMainWindowView.Callback {
            override fun onPlay() {
                if (CastManager.isCasting) {
                    CastManager.play()
                    return
                }
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.play()
                } else {
                    handleIntent(intent)
                }
            }
            override fun onPause() {
                if (CastManager.isCasting) {
                    CastManager.togglePlayPause()
                    return
                }
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.togglePlayPause()
                }
            }
            override fun onStop() {
                if (CastManager.isCasting) {
                    CastManager.seek(0)
                    CastManager.pause()
                    return
                }
                AudioPlayerService.currentService?.let {
                    it.pause()
                    it.seekTo(0)
                }
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
        dockedCenterCol.addView(dockedToolScroll)

        // The ScrollView belongs strictly to JUST the playlist items
        dockedPlaylistScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        dockedPlaylistItems = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        dockedPlaylistScroll.addView(dockedPlaylistItems)
        dockedCenterCol.addView(dockedPlaylistScroll)

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
        }
        dockedSubWindow.addView(dockedBottomBarView)
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
        detailsBottom = WinampPlaylistBottomView(this)
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
        dockedTrackArtist.maxLines = if (columns > 1) 2 else 1
        if (columns > 1) {
            skinContainer.orientation = LinearLayout.HORIZONTAL
            detailsContent.addView(dockedMetaRow)
            detailsContent.addView(dockedToolScroll)
            skinLeftColumn.addView(winampMainWindowView, LinearLayout.LayoutParams(-1, -2))
            skinLeftColumn.addView(detailsWindow, LinearLayout.LayoutParams(-1, 0, 1f))
            skinRightColumn.addView(dockedSubWindow, FrameLayout.LayoutParams(-1, -1))
            skinContainer.addView(skinLeftColumn, LinearLayout.LayoutParams(0, -1, 1f))
            skinContainer.addView(skinRightColumn, LinearLayout.LayoutParams(0, -1, 1f))
            if (columns == 3) skinContainer.addView(skinSpareColumn, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            skinContainer.orientation = LinearLayout.VERTICAL
            dockedCenterCol.addView(dockedMetaRow, 0)
            dockedCenterCol.addView(dockedToolScroll, 1)
            skinContainer.addView(winampMainWindowView, LinearLayout.LayoutParams(-1, -2))
            skinContainer.addView(dockedSubWindow, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun updateDockedPlaylist() {
        if (!::dockedPlaylistItems.isInitialized) return
        val service = AudioPlayerService.currentService ?: return
        val theme = ThemeManager.getAudioPlayerTheme(this)
        val list = synchronized(service.playlist) { ArrayList(service.playlist) }
        val curIdx = service.currentIndex

        var totalMs = 0L
        for (track in list) {
            if (track.durationMs > 0L) totalMs += track.durationMs
        }

        if (dockedPlaylistItems.childCount == list.size) {
            for ((index, track) in list.withIndex()) {
                val row = dockedPlaylistItems.getChildAt(index) as? LinearLayout ?: continue
                val isCurrent = (index == curIdx)
                row.setBackgroundColor(if (isCurrent) theme.selectionBackground else Color.TRANSPARENT)

                val numText = row.getChildAt(0) as? TextView
                numText?.setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)

                val displayTitle = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
                    "${track.artist} - ${track.title}"
                } else {
                    track.title
                }
                val titleText = row.getChildAt(1) as? TextView
                titleText?.text = (if (isCurrent) "▶ " else "") + displayTitle
                titleText?.setTextColor(if (isCurrent) theme.selectionText else theme.textPrimary)

                val durText = row.getChildAt(2) as? TextView
                durText?.text = if (track.durationMs > 0) formatTime(track.durationMs.toInt()) else "--"
                durText?.setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
            }
        } else {
            dockedPlaylistItems.removeAllViews()
            for ((index, track) in list.withIndex()) {
                val isCurrent = (index == curIdx)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    isClickable = true
                    isFocusable = true
                    setBackgroundColor(if (isCurrent) theme.selectionBackground else Color.TRANSPARENT)
                    setOnClickListener {
                        service.jumpTo(index)
                    }
                }

                val numText = TextView(this).apply {
                    text = String.format("%2d. ", index + 1)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
                }
                row.addView(numText, LinearLayout.LayoutParams(-2, -2))

                val displayTitle = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
                    "${track.artist} - ${track.title}"
                } else {
                    track.title
                }

                val titleText = TextView(this).apply {
                    text = (if (isCurrent) "▶ " else "") + displayTitle
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (isCurrent) theme.selectionText else theme.textPrimary)
                }
                row.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))

                val durText = TextView(this).apply {
                    text = if (track.durationMs > 0) formatTime(track.durationMs.toInt()) else "--"
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.END
                    setTextColor(if (isCurrent) theme.selectionText else theme.textSecondary)
                }
                row.addView(durText, LinearLayout.LayoutParams(-2, -2))

                dockedPlaylistItems.addView(row)
            }
        }

        dockedPlaylistHeader.text = "PLAYLIST (${list.size} TRACKS)"
        dockedPlaylistFooter.text = "${list.size} tracks • Total: ${formatTime(totalMs)}"

        if (::dockedBottomBarView.isInitialized) {
            dockedBottomBarView.totalDurationMs = totalMs
            val curTrackMs = if (curIdx in list.indices && list[curIdx].durationMs > 0L) list[curIdx].durationMs else 0L
            dockedBottomBarView.currentTrackDurationMs = curTrackMs
            dockedBottomBarView.invalidate()
        }
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
        playlistBtn.setTextColor(barText)
        castBtn.setTextColor(barText)
        openExtBtn.setTextColor(barSubText)

        val isWinamp = (theme.id == AppThemeId.WINAMP_CLASSIC || theme.id == AppThemeId.CUSTOM_WSZ)

        if (isWinamp) {
            skinContainer.visibility = View.VISIBLE
            nonSkinScroll.visibility = View.GONE
            updateRootPadding()
            winampMainWindowView.visibility = View.VISIBLE
            dockedSubWindow.visibility = View.VISIBLE
            dockedTitleBarView.visibility = View.VISIBLE
            dockedTitleBarView.invalidate()
            dockedLeftBorder.invalidate()
            dockedRightBorder.invalidate()
            dockedBottomBarView.invalidate()
            dockedSubTitleStrip.visibility = View.GONE
            dockedSubWindow.setBackgroundColor(theme.cardBackground)
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
            skinContainer.visibility = View.GONE
            nonSkinScroll.visibility = View.VISIBLE
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

        artCard.background = ThemeManager.createCardDrawable(theme, density)

        // Visualizer styling
        visualizerView.applyTheme(theme)
        visualizerView.visibility = View.VISIBLE

        // Seekbar thumb
        val seekThumb = if (isWinamp) ThemeManager.createSeekThumbDrawable() else null
        if (seekThumb != null) {
            progressBar.thumb = seekThumb
        }

        // Buttons styling
        prevBtn.setTextColor(theme.textPrimary)
        stopBtn.setTextColor(theme.textPrimary)
        nextBtn.setTextColor(theme.textPrimary)
        themeBtn.setTextColor(theme.textPrimary)
        playlistBtn.setTextColor(theme.textPrimary)
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

        updateControlsAppearance()
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val theme = ThemeManager.getAudioPlayerTheme(this)
        val isWinamp = (theme.id == AppThemeId.WINAMP_CLASSIC || theme.id == AppThemeId.CUSTOM_WSZ)
        if (isWinamp) {
            val sprite = ThemeManager.createCButtonDrawable(if (isPlaying) 2 else 1)
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
        playPauseBtn.text = if (isPlaying) "⏸" else "▶"
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
            .setTitle("Select Audio Interface Theme")
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
        if (requestCode == 29161 && resultCode == Activity.RESULT_OK) data?.data?.let { importAudioSkin(it) }
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
        val pos = s?.currentPosition ?: 0
        val dur = s?.currentDuration ?: 0
        val text = if (showRemainingTime && dur > 0) {
            val rem = (dur - pos).coerceAtLeast(0)
            "-${formatTime(rem)}"
        } else {
            formatTime(pos)
        }
        currentTimeText.text = text
        if (::timerLedView.isInitialized) {
            timerLedView.text = text
        }
    }

    private fun updateTrackView(track: AudioTrack?) {
        if (track == null) return
        val displayTitle = if (!track.artist.isNullOrBlank() && track.artist != "Unknown Artist") {
            "${track.artist} - ${track.title}"
        } else {
            track.title
        }
        trackTitleText.text = displayTitle
        val sub = listOfNotNull(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • ")
        trackArtistText.text = if (sub.isNotBlank()) sub else "Infinifolder Media"

        if (track.artwork != null) {
            albumArtView.setImageBitmap(track.artwork)
            albumArtView.setBackgroundColor(Color.TRANSPARENT)
        } else {
            albumArtView.setImageResource(android.R.drawable.ic_media_play)
            albumArtView.setBackgroundColor(ThemeManager.getAudioPlayerTheme(this).surface)
        }

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
                if (track.artwork != null) {
                    dockedAlbumArt.setImageBitmap(track.artwork)
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
        val speedStr = String.format(Locale.US, "%.2fx", s.playbackSpeed)
        speedBtn.text = speedStr
        if (::dockedSpeedBtn.isInitialized) {
            dockedSpeedBtn.text = speedStr
        }
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
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        playlistDialogAdapter = adapter
        val dialog = ThemeManager.createDialogBuilder(this)
            .setTitle("Playlist")
            .setAdapter(adapter) { _, which -> service.jumpTo(which) }
            .setPositiveButton("Close", null)
            .create()
        playlistDialog = dialog
        dialog.setOnDismissListener {
            if (playlistDialog === dialog) {
                playlistDialog = null
                playlistDialogAdapter = null
            }
        }
        refreshPlaylistDialog()
        dialog.show()
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
            val effectivePlaying = if (CastManager.isCasting) CastManager.isRemotePlaying else isPlaying
            if (effectivePlaying && !CastManager.isCasting) updateVisualizerCapture(AudioPlayerService.currentService?.audioSessionId ?: 0)
            updatePlayPauseButton(effectivePlaying)
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            visualizerView.isPlaying = effectivePlaying
            if (::winampMainWindowView.isInitialized) {
                winampMainWindowView.isPlaying = effectivePlaying
                winampMainWindowView.isPaused = (!effectivePlaying && ((AudioPlayerService.currentService?.currentPosition ?: 0) > 0 || CastManager.isCasting))
            }
        }
    }

    override fun onProgressUpdated(currentPositionMs: Int, durationMs: Int) {
        if (isUserSeeking) return
        runOnUiThread {
            updateTimeDisplay()
            totalTimeText.text = formatTime(durationMs)
            if (durationMs > 0) {
                progressBar.progress = ((currentPositionMs.toLong() * 1000) / durationMs).toInt()
            } else {
                progressBar.progress = 0
            }
            if (::winampMainWindowView.isInitialized) {
                winampMainWindowView.currentPositionMs = currentPositionMs
                winampMainWindowView.durationMs = durationMs
            }
        }
    }

    override fun onPlaylistUpdated(playlist: List<AudioTrack>) {
        runOnUiThread {
            updateControlsAppearance()
            updateDockedPlaylist()
            refreshPlaylistDialog()
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
        if (::castBtn.isInitialized) castBtn.text = if (casting) "📻" else "📡"
        dockedCastBtn?.let {
            it.text = if (casting) "📻 ${CastManager.deviceName?.take(10) ?: "Casting"}" else "📡 Cast"
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
            updatePlayPauseButton(playing)
            if (::winampMainWindowView.isInitialized) winampMainWindowView.isPlaying = playing
        }
    }

    override fun onCastPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
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
        super.onDestroy()
        visualizerBridge?.detach()
        CastManager.removeListener(this)
        AudioPlayerService.removeListener(this)
        ThemeManager.removeListener(this)
        // Back/close only hides the player: the service keeps the track (playing or paused) available
        // from its notification. Finished playback shuts the service down by itself.
    }
}
