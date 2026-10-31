package com.infinifolder.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.gretchycat.infinivideo.LibVlcVideoBackend
import io.github.gretchycat.infinivideo.VideoPlayback
import io.github.gretchycat.infinivideo.VideoScaleMode
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

enum class VideoAspectMode { FIT, FILL, RATIO_16_9, RATIO_4_3 }

class VideoPlayerActivity : ViewerActivity() {

    private var targetUri: Uri? = null
    private var displayName: String = "Video"
    private var stagedSource: MediaSourceAdapter.StagedSource? = null
    private val loadGeneration = AtomicInteger()
    private val sourceExecutor = Executors.newSingleThreadExecutor()
    private var sourceJob: Future<*>? = null
    private var pausedForBackground = false
    private var activityStopped = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var videoView: View
    private lateinit var videoPlayer: LibVlcVideoBackend
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playPauseBtn: Button
    private lateinit var aspectBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var repeatBtn: Button
    private lateinit var loadingIndicator: ProgressBar

    enum class VideoRepeatMode { NONE, ALL, ONE }
    private var repeatMode = VideoRepeatMode.NONE

    private var videoInputMetadata = io.github.gretchycat.infinistream.StreamMetadata()
    private var videoOutputMetadata = io.github.gretchycat.infinistream.StreamMetadata()
    private var isUserSeeking = false
    private var isControlsVisible = true
    private var aspectMode = VideoAspectMode.FIT
    private var playbackSpeed = 1.0f

    private val playlist = mutableListOf<Uri>()
    private var currentIndex = 0

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (videoPlayer.isPlaying && !isUserSeeking) {
                val cur = videoPlayer.positionMs
                val dur = (videoPlayer.durationMs ?: 0L)
                currentTimeText.text = formatTime(cur)
                totalTimeText.text = formatTime(dur)
                if (dur > 0) {
                    progressBar.progress = ((cur.toLong() * 1000) / dur).toInt()
                }
                com.infinifolder.ops.AppOperations.coordinator.updateExternal(
                    id = "playback_video",
                    bytes = cur.toLong(),
                    totalBytes = if (dur > 0) dur.toLong() else null
                )
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        targetUri = intent.data
            ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: intent.getStringExtra("extra_uri")?.let { Uri.parse(it) }
        displayName = intent.getStringExtra("displayName")
            ?: intent.getStringExtra("extra_name")
            ?: targetUri?.lastPathSegment
            ?: "Video"

        com.infinifolder.ops.AppOperations.coordinator.onCancelExternal = { id ->
            if (id == "playback_video") {
                finish()
            }
        }

        setupUI()
        discoverPlaylistAndStart()
    }

    private fun discoverPlaylistAndStart() {
        val u = targetUri ?: return
        Thread {
            val list = mutableListOf<Uri>()
            val parentUri = intent?.getStringExtra("parentUri")?.let { Uri.parse(it) }
                ?: intent?.getStringExtra("extra_parent_uri")?.let { Uri.parse(it) }
                ?: MediaSourceAdapter.getParentDirectory(this, u)
            if (parentUri != null) {
                try {
                    val siblings = MediaSourceAdapter.listFolder(this, parentUri)
                    for (sibling in siblings) {
                        if (!sibling.metadata.isDirectory && MediaTypeDetector.detect(sibling.metadata.name, sibling.metadata.mimeType) == MediaType.VIDEO) {
                            list.add(sibling.uri)
                        }
                    }
                } catch (_: Exception) {}
            }
            val uStr = u.toString()
            if (list.none { it == u || it.toString() == uStr }) {
                list.add(0, u)
            }
            val idx = list.indexOfFirst { it == u || it.toString() == uStr }.coerceAtLeast(0)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                playlist.clear()
                playlist.addAll(list)
                currentIndex = idx
                loadVideoAtIndex(currentIndex)
            }
        }.start()
    }

    private fun setupUI() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    toggleControls()
                }
                true
            }
        }

        // Video container allowing custom aspect scaling
        videoContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }

        videoPlayer = LibVlcVideoBackend(this)
        videoView = videoPlayer.view.apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        }
        videoPlayer.listener = object : VideoPlayback.Listener {
            override fun onReady(metadata: io.github.gretchycat.infinistream.StreamMetadata) {
                videoOutputMetadata = metadata
                loadingIndicator.visibility = View.GONE
                com.infinifolder.ops.AppOperations.coordinator.registerExternal(
                    id = "playback_video", label = "Video: $displayName",
                    totalBytes = videoPlayer.durationMs, currentItem = displayName)
                totalTimeText.text = formatTime(videoPlayer.durationMs ?: 0L)
                progressHandler.removeCallbacks(progressRunnable)
                progressHandler.post(progressRunnable)
                scheduleControlsHide()
            }
            override fun onMetadataChanged(metadata: io.github.gretchycat.infinistream.StreamMetadata) {
                videoOutputMetadata = metadata
                totalTimeText.text = formatTime(videoPlayer.durationMs ?: 0L)
            }
            override fun onBuffering(percent: Float) {
                loadingIndicator.visibility = if (percent < 100f) View.VISIBLE else View.GONE
            }
            override fun onPlayingChanged(playing: Boolean) {
                playPauseBtn.text = if (playing) "⏸" else "▶"
            }
            override fun onEnded() {
                playPauseBtn.text = "▶"
                if (currentIndex < playlist.size - 1) {
                    playNext()
                } else when (repeatMode) {
                    VideoRepeatMode.ALL -> { currentIndex = 0; loadVideoAtIndex(currentIndex) }
                    VideoRepeatMode.ONE -> { videoPlayer.seekTo(0); videoPlayer.play() }
                    VideoRepeatMode.NONE -> finish()
                }
            }
            override fun onError(message: String) {
                loadingIndicator.visibility = View.GONE
                com.infinifolder.storage.DiagnosticLog.record("viewer.video.error", message)
                Toast.makeText(this@VideoPlayerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
        videoContainer.addView(videoView)
        rootLayout.addView(videoContainer)

        // Loading Indicator
        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            layoutParams = lp
        }
        rootLayout.addView(loadingIndicator)

        // Controls Overlay
        controlsOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Top Bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0x88000000.toInt())
        }

        val backBtn = Button(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onBackPressed() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        titleText = TextView(this).apply {
            text = displayName
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))

        aspectBtn = Button(this).apply {
            text = "Fit"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0x55FFFFFF)
            }
            setOnClickListener { cycleAspectRatio() }
        }
        topBar.addView(aspectBtn, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginEnd = dp(8)
        })

        speedBtn = Button(this).apply {
            text = "1.0x"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0x55FFFFFF)
            }
            setOnClickListener { showSpeedPicker() }
        }
        topBar.addView(speedBtn, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginEnd = dp(8)
        })

        repeatBtn = Button(this).apply {
            text = "🔁"
            textSize = 14f
            setTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0x55FFFFFF)
            }
            setOnClickListener {
                repeatMode = when (repeatMode) {
                    VideoRepeatMode.NONE -> VideoRepeatMode.ALL
                    VideoRepeatMode.ALL -> VideoRepeatMode.ONE
                    VideoRepeatMode.ONE -> VideoRepeatMode.NONE
                }
                updateRepeatButton()
                scheduleControlsHide()
            }
        }
        topBar.addView(repeatBtn, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginEnd = dp(8)
        })

        val openExtBtn = Button(this).apply {
            text = "↗"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openExternally() }
        }
        topBar.addView(openExtBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        DebugTools.attachToolbarButton(this, topBar)
        controlsOverlay.addView(topBar)

        // Middle spacer
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setOnClickListener { toggleControls() }
        }
        controlsOverlay.addView(spacer)

        // Bottom Controls Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // Progress row
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        currentTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        progressRow.addView(currentTimeText)

        progressBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = (videoPlayer.durationMs ?: 0L)
                        val target = (progress / 1000.0 * dur).toLong()
                        currentTimeText.text = formatTime(target)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                    hideControlsHandler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    val dur = (videoPlayer.durationMs ?: 0L)
                    val target = (progressBar.progress / 1000.0 * dur).toLong()
                    videoPlayer.seekTo(target)
                    scheduleControlsHide()
                }
            })
        }
        progressRow.addView(progressBar, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })

        totalTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        progressRow.addView(totalTimeText)
        bottomBar.addView(progressRow)

        // Buttons row (Skip -10s, Prev, Play/Pause, Next, Skip +10s, Fullscreen)
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

        val skipBackBtn = Button(this).apply {
            text = "↺ 10s"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                videoPlayer.seekTo((videoPlayer.positionMs - 10000).coerceAtLeast(0))
                scheduleControlsHide()
            }
        }
        buttonsRow.addView(skipBackBtn)

        val prevBtn = Button(this).apply {
            text = "⏮"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                playPrevious()
                scheduleControlsHide()
            }
        }
        buttonsRow.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        playPauseBtn = Button(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener {
                togglePlayPause()
                scheduleControlsHide()
            }
        }
        buttonsRow.addView(playPauseBtn, LinearLayout.LayoutParams(dp(54), dp(54)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        val nextBtn = Button(this).apply {
            text = "⏭"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                playNext()
                scheduleControlsHide()
            }
        }
        buttonsRow.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        val skipFwdBtn = Button(this).apply {
            text = "10s ↻"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                videoPlayer.seekTo(videoPlayer.positionMs + 10000)
                scheduleControlsHide()
            }
        }
        buttonsRow.addView(skipFwdBtn)

        bottomBar.addView(buttonsRow)
        controlsOverlay.addView(bottomBar)

        val basePad = dp(12)
        controlsOverlay.setOnApplyWindowInsetsListener { _, insets ->
            val safe = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            } else null
            val left = safe?.left ?: insets.systemWindowInsetLeft
            val top = safe?.top ?: insets.systemWindowInsetTop
            val right = safe?.right ?: insets.systemWindowInsetRight
            val bottom = safe?.bottom ?: insets.systemWindowInsetBottom
            topBar.setPadding(basePad + left, basePad + top, basePad + right, basePad)
            bottomBar.setPadding(basePad + left, basePad, basePad + right, basePad + bottom)
            insets
        }

        rootLayout.addView(controlsOverlay)

        setContentView(rootLayout)
    }

    private fun loadVideoAtIndex(index: Int) {
        if (index !in playlist.indices) return
        val uri = playlist[index]
        displayName = uri.lastPathSegment ?: "Video"
        titleText.text = displayName
        loadingIndicator.visibility = View.VISIBLE
        progressBar.progress = 0
        currentTimeText.text = "00:00"
        totalTimeText.text = "00:00"
        val generation = loadGeneration.incrementAndGet()
        sourceJob?.cancel(true)
        progressHandler.removeCallbacks(progressRunnable)
        videoPlayer.stop()
        cleanupStagedFile()
        sourceJob = sourceExecutor.submit {
            var source: MediaSourceAdapter.StagedSource? = null
            try {
                val metadata = MediaSourceAdapter.resource(this, uri).metadata
                source = MediaSourceAdapter.stage(this, uri)
                val loaded = source
                runOnUiThread {
                    if (generation != loadGeneration.get() || isFinishing || isDestroyed) {
                        loaded.close()
                        return@runOnUiThread
                    }
                    stagedSource = loaded
                    try {
                        applySpeed()
                        adjustAspectRatio()
                        videoPlayer.open(loaded.file, metadata, autoPlay = !activityStopped)
                        if (activityStopped) pausedForBackground = true
                        videoInputMetadata = videoPlayer.inputMetadata
                        com.infinifolder.storage.DiagnosticLog.record("viewer.video.open",
                            "backend=libvlc bytes=${videoInputMetadata.length} staged=true")
                    } catch (error: Exception) {
                        videoPlayer.stop()
                        cleanupStagedFile()
                        loadingIndicator.visibility = View.GONE
                        com.infinifolder.storage.DiagnosticLog.record("viewer.video.open.failed", error = error)
                        Toast.makeText(this, "Failed to open video with libVLC", Toast.LENGTH_SHORT).show()
                    }
                }
                source = null // The posted callback now owns the lease, including stale-load cleanup.
            } catch (error: Exception) {
                source?.close()
                runOnUiThread {
                    if (generation == loadGeneration.get() && !isFinishing && !isDestroyed) {
                        loadingIndicator.visibility = View.GONE
                        if (!CacheFailureUi.offerCopy(this, error, uri))
                            Toast.makeText(this, "Failed to load video source", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun togglePlayPause() {
        if (videoPlayer.isPlaying) {
            videoPlayer.pause()
            playPauseBtn.text = "▶"
            com.infinifolder.ops.AppOperations.coordinator.updateExternal(
                id = "playback_video",
                bytes = videoPlayer.positionMs.toLong(),
                message = "Paused"
            )
        } else {
            videoPlayer.play()
            playPauseBtn.text = "⏸"
            com.infinifolder.ops.AppOperations.coordinator.updateExternal(
                id = "playback_video",
                bytes = videoPlayer.positionMs.toLong(),
                message = null
            )
        }
    }

    private fun updateRepeatButton() {
        when (repeatMode) {
            VideoRepeatMode.NONE -> {
                repeatBtn.text = "🔁"
                repeatBtn.setTextColor(0x88FFFFFF.toInt())
            }
            VideoRepeatMode.ALL -> {
                repeatBtn.text = "🔁"
                repeatBtn.setTextColor(0xFF4CAF50.toInt())
            }
            VideoRepeatMode.ONE -> {
                repeatBtn.text = "🔂"
                repeatBtn.setTextColor(0xFF4CAF50.toInt())
            }
        }
    }

    private fun playNext() {
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            loadVideoAtIndex(currentIndex)
        } else {
            when (repeatMode) {
                VideoRepeatMode.ALL -> {
                    currentIndex = 0
                    loadVideoAtIndex(currentIndex)
                }
                VideoRepeatMode.ONE -> {
                    videoPlayer.seekTo(0)
                    videoPlayer.play()
                    playPauseBtn.text = "⏸"
                }
                VideoRepeatMode.NONE -> {
                    Toast.makeText(this, "End of video list", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun playPrevious() {
        if (videoPlayer.positionMs > 3000) {
            videoPlayer.seekTo(0)
            return
        }
        if (currentIndex > 0) {
            currentIndex--
            loadVideoAtIndex(currentIndex)
        } else {
            videoPlayer.seekTo(0)
        }
    }

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        isControlsVisible = true
        controlsOverlay.visibility = View.VISIBLE
        scheduleControlsHide()
    }

    private fun hideControls() {
        isControlsVisible = false
        controlsOverlay.visibility = View.GONE
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleControlsHide() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3500)
    }

    private fun cycleAspectRatio() {
        aspectMode = when (aspectMode) {
            VideoAspectMode.FIT -> VideoAspectMode.FILL
            VideoAspectMode.FILL -> VideoAspectMode.RATIO_16_9
            VideoAspectMode.RATIO_16_9 -> VideoAspectMode.RATIO_4_3
            VideoAspectMode.RATIO_4_3 -> VideoAspectMode.FIT
        }
        aspectBtn.text = when (aspectMode) {
            VideoAspectMode.FIT -> "Fit"
            VideoAspectMode.FILL -> "Fill"
            VideoAspectMode.RATIO_16_9 -> "16:9"
            VideoAspectMode.RATIO_4_3 -> "4:3"
        }
        adjustAspectRatio()
        scheduleControlsHide()
    }

    private fun adjustAspectRatio() {
        videoPlayer.setAspect(VideoScaleMode.valueOf(aspectMode.name))
    }

    private fun showSpeedPicker() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                playbackSpeed = values[which]
                speedBtn.text = speeds[which]
                applySpeed()
                scheduleControlsHide()
            }
            .show()
    }

    private fun applySpeed() {
        videoPlayer.setSpeed(playbackSpeed)
    }

    private fun openExternally() {
        val currentUri = playlist.getOrNull(currentIndex) ?: return
        val extIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(currentUri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(extIntent, "Open with…"))
        } catch (_: Exception) {
            Toast.makeText(this, "No external video player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupStagedFile() {
        stagedSource?.close()
        stagedSource = null
    }

    private fun formatTime(ms: Long): String = io.github.gretchycat.infinivideo.VideoMetadata.formatTime(ms)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlayer.isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val width = videoOutputMetadata.attributes["width"]?.toIntOrNull()
                    val height = videoOutputMetadata.attributes["height"]?.toIntOrNull()
                    val ratio = if (width != null && height != null && width > 0 && height > 0 &&
                        width.toDouble() / height in (1.0 / 2.39)..2.39) {
                        android.util.Rational(width, height)
                    } else android.util.Rational(16, 9)
                    val params = android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(ratio)
                        .build()
                    enterPictureInPictureMode(params)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideControls()
        } else {
            showControls()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStopped = false
        if (pausedForBackground && ::videoPlayer.isInitialized) {
            pausedForBackground = false
            videoPlayer.play()
        }
    }

    override fun onStop() {
        activityStopped = true
        if (::videoPlayer.isInitialized) {
            pausedForBackground = videoPlayer.isPlayRequested
            videoPlayer.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        loadGeneration.incrementAndGet()
        sourceJob?.cancel(true)
        sourceExecutor.shutdownNow()
        if (::videoPlayer.isInitialized) videoPlayer.close()
        cleanupStagedFile()
        com.infinifolder.ops.AppOperations.coordinator.completeExternal("playback_video")
    }
}
