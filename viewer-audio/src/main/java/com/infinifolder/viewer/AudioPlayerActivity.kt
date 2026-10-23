package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.infinifolder.theme.AppThemeId
import com.infinifolder.theme.ThemeChangeListener
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager
import java.util.Locale

class AudioPlayerActivity : ViewerActivity(), AudioPlayerListener, ThemeChangeListener {

    private lateinit var audioManager: AudioManager

    private lateinit var rootLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var headerText: TextView
    private lateinit var themeBtn: Button
    private lateinit var playlistBtn: Button
    private lateinit var openExtBtn: Button

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
    private lateinit var nextBtn: Button
    private lateinit var shuffleBtn: Button
    private lateinit var repeatBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var outputDeviceBtn: Button
    private lateinit var volumeBar: SeekBar
    private lateinit var loadingIndicator: ProgressBar

    private var isUserSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupUI()
        applyTheme(ThemeManager.activeTheme)

        AudioPlayerService.addListener(this)
        ThemeManager.addListener(this)

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AudioPlayerService.currentService?.let { updateUIFromService(it) }
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
            val parentUri = intent?.getStringExtra("parentUri")?.let { Uri.parse(it) }
                ?: intent?.getStringExtra("extra_parent_uri")?.let { Uri.parse(it) }
                ?: MediaSourceAdapter.getParentDirectory(this, targetUri)
            if (parentUri != null) {
                try {
                    val siblings = MediaSourceAdapter.listFolder(this, parentUri)
                    val queueMultimedia = intent?.getBooleanExtra("queueMultimedia", false) == true
                    for (sibling in siblings) {
                        if (!sibling.metadata.isDirectory) {
                            val type = MediaTypeDetector.detect(sibling.metadata.name, sibling.metadata.mimeType)
                            if (type == MediaType.AUDIO || (queueMultimedia && type == MediaType.VIDEO)) {
                                tracks.add(AudioTrack(sibling.uri, sibling.metadata.name))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (tracks.none { AudioPlayerService.uriMatches(it.uri, targetUri) }) {
                tracks.add(0, AudioTrack(targetUri, displayName))
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
                    service.setPlaylist(tracks, startIndex)
                } else {
                    window.decorView.postDelayed({
                        AudioPlayerService.currentService?.let { s ->
                            val match = AudioPlayerService.uriMatches(s.currentTrack?.uri, targetUri)
                            if (match) {
                                s.updatePlaylistKeepCurrent(tracks, targetUri)
                                updateUIFromService(s)
                            } else {
                                s.setPlaylist(tracks, startIndex)
                            }
                        }
                    }, 200)
                }
            }
        }.start()
    }

    private fun setupUI() {
        val basePadH = dp(12)
        val basePadT = dp(6)
        val basePadB = dp(16)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                } else null
                val left = safe?.left ?: insets.systemWindowInsetLeft
                val top = safe?.top ?: insets.systemWindowInsetTop
                val right = safe?.right ?: insets.systemWindowInsetRight
                val bottom = safe?.bottom ?: insets.systemWindowInsetBottom
                view.setPadding(basePadH + left, basePadT + top, basePadH + right, basePadB + bottom)
                insets
            }
        }

        // Top Bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val backBtn = Button(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        headerText = TextView(this).apply {
            text = "Now Playing"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        topBar.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))

        // Theme Switcher Button
        themeBtn = Button(this).apply {
            text = "🎨"
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showThemeChooser() }
        }
        topBar.addView(themeBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        playlistBtn = Button(this).apply {
            text = "☰"
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPlaylistDialog() }
        }
        topBar.addView(playlistBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        openExtBtn = Button(this).apply {
            text = "↗"
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openExternally() }
        }
        topBar.addView(openExtBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        rootLayout.addView(topBar)

        val scrollContent = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }

        // Album Art Box / Chassis Screen
        artCard = FrameLayout(this).apply {
            val size = dp(240)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(8)
                bottomMargin = dp(16)
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

        // Animated Spectrum Visualizer (for Winamp & WMP themes)
        visualizerView = VisualizerView(this).apply {
            val lp = LinearLayout.LayoutParams(-1, dp(40)).apply {
                bottomMargin = dp(12)
            }
            layoutParams = lp
        }
        mainColumn.addView(visualizerView)

        // Title, Artist & Technical Inspector
        trackTitleText = TextView(this).apply {
            text = "Audio Title"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mainColumn.addView(trackTitleText, LinearLayout.LayoutParams(-1, -2))

        trackArtistText = TextView(this).apply {
            text = "Artist / Album"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mainColumn.addView(trackArtistText, LinearLayout.LayoutParams(-1, -2))

        technicalInfoText = TextView(this).apply {
            text = "44.1 kHz • Stereo • VFS Playback"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        mainColumn.addView(technicalInfoText, LinearLayout.LayoutParams(-1, -2))

        // Progress Bar & Timers
        val progressContainer = LinearLayout(this).apply {
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
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    val dur = AudioPlayerService.currentService?.currentDuration ?: 0
                    val targetMs = (progressBar.progress / 1000f * dur).toInt()
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

        // Main Controls Row (Shuffle, Prev, Play/Pause, Next, Repeat)
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(14))
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
        controlsRow.addView(shuffleBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

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
        controlsRow.addView(prevBtn, LinearLayout.LayoutParams(dp(50), dp(50)))

        playPauseBtn = Button(this).apply {
            text = "▶"
            textSize = 24f
            setOnClickListener {
                val s = AudioPlayerService.currentService
                if (s != null && s.playlist.isNotEmpty()) {
                    s.togglePlayPause()
                } else {
                    handleIntent(intent)
                }
            }
        }
        controlsRow.addView(playPauseBtn, LinearLayout.LayoutParams(dp(62), dp(62)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
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
        controlsRow.addView(nextBtn, LinearLayout.LayoutParams(dp(50), dp(50)))

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
        controlsRow.addView(repeatBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        mainColumn.addView(controlsRow)

        // Secondary Controls (Speed, Audio Routing)
        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }

        speedBtn = Button(this).apply {
            text = "1.0x"
            textSize = 12f
            setOnClickListener { showSpeedPicker() }
        }
        secondaryRow.addView(speedBtn, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginEnd = dp(12)
        })

        outputDeviceBtn = Button(this).apply {
            text = "🔊 Speaker"
            textSize = 12f
            setOnClickListener { showOutputRoutingDialog() }
        }
        secondaryRow.addView(outputDeviceBtn, LinearLayout.LayoutParams(-2, dp(34)))

        mainColumn.addView(secondaryRow)

        // Volume Bar
        val volumeRow = LinearLayout(this).apply {
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

        mainColumn.addView(volumeRow, LinearLayout.LayoutParams(-1, -2))

        scrollContent.addView(mainColumn)
        rootLayout.addView(scrollContent)
        setContentView(rootLayout)
    }

    private fun applyTheme(theme: ThemeColors) {
        window.statusBarColor = theme.background
        window.navigationBarColor = theme.background

        val density = resources.displayMetrics.density
        rootLayout.setBackgroundColor(theme.background)
        topBar.background = ThemeManager.createToolbarDrawable(theme, density)

        headerText.setTextColor(theme.textPrimary)
        headerText.text = when (theme.id) {
            AppThemeId.WINAMP_CLASSIC, AppThemeId.CUSTOM_WSZ -> "WINAMP"
            AppThemeId.WMP_GLASS -> "Windows Media Player"
            AppThemeId.FOOBAR2000 -> "foobar2000"
            else -> "Now Playing"
        }

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

        totalTimeText.typeface = fontBold
        totalTimeText.setTextColor(theme.textSecondary)

        artCard.background = ThemeManager.createCardDrawable(theme, density)

        // Visualizer styling
        visualizerView.applyTheme(theme)
        visualizerView.visibility = when (theme.id) {
            AppThemeId.FOOBAR2000 -> View.GONE // Minimalist technical disables spectrum
            else -> View.VISIBLE
        }

        // Buttons styling
        prevBtn.setTextColor(theme.textPrimary)
        nextBtn.setTextColor(theme.textPrimary)
        themeBtn.setTextColor(theme.textPrimary)
        playlistBtn.setTextColor(theme.textPrimary)
        openExtBtn.setTextColor(theme.textSecondary)

        playPauseBtn.setTextColor(if (theme.id == AppThemeId.WINAMP_CLASSIC || theme.id == AppThemeId.CUSTOM_WSZ) theme.background else Color.WHITE)
        playPauseBtn.background = ThemeManager.createButtonDrawable(theme, density, isAccent = true)

        speedBtn.setTextColor(theme.buttonText)
        speedBtn.background = ThemeManager.createButtonDrawable(theme, density)

        outputDeviceBtn.setTextColor(theme.buttonText)
        outputDeviceBtn.background = ThemeManager.createButtonDrawable(theme, density)

        updateControlsAppearance()
    }

    private fun showThemeChooser() {
        val themes = ThemeManager.listPresetThemes(isDark = true)
        val names = themes.map { "  ${it.name}" }.toMutableList()
        names.add("📂 Import Winamp (.wsz) Skin…")

        AlertDialog.Builder(this)
            .setTitle("Select Audio Interface Theme")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < themes.size) {
                    ThemeManager.setTheme(this, themes[which])
                } else {
                    promptImportWszSkin()
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

        AlertDialog.Builder(this)
            .setTitle("Import Winamp (.wsz) Skin")
            .setMessage("Enter the local path or URI of a Winamp .wsz / .zip skin file:")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val skinUri = if (path.startsWith("/")) Uri.fromFile(java.io.File(path)) else Uri.parse(path)
                    val imported = ThemeManager.importSkin(this, skinUri)
                    if (imported != null) {
                        Toast.makeText(this, "Skin '${imported.name}' applied!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to parse skin file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onThemeChanged(theme: ThemeColors) {
        runOnUiThread {
            applyTheme(theme)
        }
    }

    private fun updateUIFromService(service: AudioPlayerService) {
        val track = service.currentTrack
        updateTrackView(track)
        playPauseBtn.text = if (service.isPlaying) "⏸" else "▶"
        loadingIndicator.visibility = if (service.isLoading) View.VISIBLE else View.GONE
        visualizerView.isPlaying = service.isPlaying
        updateControlsAppearance()
    }

    private fun updateTrackView(track: AudioTrack?) {
        if (track == null) return
        trackTitleText.text = track.title
        val sub = listOfNotNull(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • ")
        trackArtistText.text = if (sub.isNotBlank()) sub else "Infinifolder Media"

        if (track.artwork != null) {
            albumArtView.setImageBitmap(track.artwork)
            albumArtView.setBackgroundColor(Color.TRANSPARENT)
        } else {
            albumArtView.setImageResource(android.R.drawable.ic_media_play)
            albumArtView.setBackgroundColor(0xFF2A2A2A.toInt())
        }

        val durSec = (track.durationMs / 1000).coerceAtLeast(0)
        technicalInfoText.text = "44.1 kHz • Stereo • ${track.title.substringAfterLast('.', "Audio").uppercase()} • ${durSec}s"
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
        speedBtn.text = String.format(Locale.US, "%.2fx", s.playbackSpeed)
    }

    private fun showSpeedPicker() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                AudioPlayerService.currentService?.setSpeed(values[which])
                updateControlsAppearance()
            }
            .show()
    }

    private fun showOutputRoutingDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val names = devices.map { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Device"
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                    else -> "Audio Output (${device.id})"
                }
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Audio Output Routing")
                .setItems(names) { _, which ->
                    outputDeviceBtn.text = "🔊 ${names[which]}"
                    Toast.makeText(this, "Routing to: ${names[which]}", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("System Sound Settings") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            val isSpeaker = audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = !isSpeaker
            outputDeviceBtn.text = if (!isSpeaker) "🔊 Speaker ON" else "🎧 Headset/Normal"
            Toast.makeText(this, outputDeviceBtn.text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlaylistDialog() {
        val s = AudioPlayerService.currentService ?: return
        val list = synchronized(s.playlist) { ArrayList(s.playlist) }
        val titles = list.mapIndexed { idx, trk ->
            val mark = if (idx == s.currentIndex) "▶ " else "   "
            "$mark${trk.title}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Playlist (${titles.size} tracks)")
            .setItems(titles) { _, which ->
                s.jumpTo(which)
            }
            .setPositiveButton("Close", null)
            .show()
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
    override fun onTrackChanged(track: AudioTrack?, index: Int, total: Int) {
        runOnUiThread {
            updateTrackView(track)
            updateControlsAppearance()
            currentTimeText.text = "00:00"
            totalTimeText.text = formatTime(track?.durationMs?.toInt() ?: 0)
            progressBar.progress = 0
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, isLoading: Boolean) {
        runOnUiThread {
            playPauseBtn.text = if (isPlaying) "⏸" else "▶"
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            visualizerView.isPlaying = isPlaying
        }
    }

    override fun onProgressUpdated(currentPositionMs: Int, durationMs: Int) {
        if (isUserSeeking) return
        runOnUiThread {
            currentTimeText.text = formatTime(currentPositionMs)
            totalTimeText.text = formatTime(durationMs)
            if (durationMs > 0) {
                progressBar.progress = ((currentPositionMs.toLong() * 1000) / durationMs).toInt()
            } else {
                progressBar.progress = 0
            }
        }
    }

    override fun onPlaylistUpdated(playlist: List<AudioTrack>) {
        runOnUiThread {
            updateControlsAppearance()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPlaybackComplete() {
        runOnUiThread {
            finish()
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        AudioPlayerService.removeListener(this)
        ThemeManager.removeListener(this)
        // Back/close only hides the player: the service keeps the track (playing or paused) available
        // from its notification. Finished playback shuts the service down by itself.
    }
}
