package com.infinifolder.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.PlaybackParams
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
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.util.Locale

enum class VideoAspectMode { FIT, FILL, RATIO_16_9, RATIO_4_3 }

class VideoPlayerActivity : ViewerActivity() {

    private var targetUri: Uri? = null
    private var displayName: String = "Video"
    private var stagedSource: MediaSourceAdapter.StagedSource? = null
    private var stagedFile: File? = null

    private lateinit var rootLayout: FrameLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var videoView: VideoView
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

    private var mediaPlayerRef: MediaPlayer? = null
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
            if (videoView.isPlaying && !isUserSeeking) {
                val cur = videoView.currentPosition
                val dur = videoView.duration
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

        videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            setOnPreparedListener { mp ->
                mediaPlayerRef = mp
                loadingIndicator.visibility = View.GONE
                mp.isLooping = false
                applySpeed()
                videoView.start()
                com.infinifolder.ops.AppOperations.coordinator.registerExternal(
                    id = "playback_video",
                    label = "Video: $displayName",
                    totalBytes = videoView.duration.toLong().coerceAtLeast(0L),
                    currentItem = displayName
                )
                playPauseBtn.text = "⏸"
                totalTimeText.text = formatTime(videoView.duration)
                adjustAspectRatio()
                progressHandler.post(progressRunnable)
                scheduleControlsHide()
            }
            setOnCompletionListener {
                playPauseBtn.text = "▶"
                if (currentIndex < playlist.size - 1) {
                    playNext()
                } else {
                    when (repeatMode) {
                        VideoRepeatMode.ALL -> {
                            currentIndex = 0
                            loadVideoAtIndex(currentIndex)
                        }
                        VideoRepeatMode.ONE -> {
                            videoView.seekTo(0)
                            videoView.start()
                            playPauseBtn.text = "⏸"
                        }
                        VideoRepeatMode.NONE -> {
                            // Finished playing through with repeat off: auto close!
                            finish()
                        }
                    }
                }
            }
            setOnErrorListener { _, what, extra ->
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this@VideoPlayerActivity, "Error playing video ($what, $extra)", Toast.LENGTH_SHORT).show()
                true
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
                        val dur = videoView.duration
                        val target = (progress / 1000f * dur).toInt()
                        currentTimeText.text = formatTime(target)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                    hideControlsHandler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    val dur = videoView.duration
                    val target = (progressBar.progress / 1000f * dur).toInt()
                    videoView.seekTo(target)
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
                videoView.seekTo((videoView.currentPosition - 10000).coerceAtLeast(0))
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
                videoView.seekTo((videoView.currentPosition + 10000).coerceAtMost(videoView.duration))
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
        videoView.stopPlayback()

        Thread {
            cleanupStagedFile()
            var loadError: Exception? = null
            val file = try {
                MediaSourceAdapter.stage(this, uri).also { stagedSource = it }.file
            } catch (e: Exception) {
                loadError = e
                null
            }
            stagedFile = file

            runOnUiThread {
                if (file != null && file.exists()) {
                    videoView.setVideoPath(file.absolutePath)
                } else {
                    loadingIndicator.visibility = View.GONE
                    val error = loadError
                    if (error == null || !CacheFailureUi.offerCopy(this, error, uri))
                        Toast.makeText(this, "Failed to load video source", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun togglePlayPause() {
        if (videoView.isPlaying) {
            videoView.pause()
            playPauseBtn.text = "▶"
            com.infinifolder.ops.AppOperations.coordinator.updateExternal(
                id = "playback_video",
                bytes = videoView.currentPosition.toLong(),
                message = "Paused"
            )
        } else {
            videoView.start()
            playPauseBtn.text = "⏸"
            com.infinifolder.ops.AppOperations.coordinator.updateExternal(
                id = "playback_video",
                bytes = videoView.currentPosition.toLong(),
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
                    videoView.seekTo(0)
                    videoView.start()
                    playPauseBtn.text = "⏸"
                }
                VideoRepeatMode.NONE -> {
                    Toast.makeText(this, "End of video list", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun playPrevious() {
        if (videoView.currentPosition > 3000) {
            videoView.seekTo(0)
            return
        }
        if (currentIndex > 0) {
            currentIndex--
            loadVideoAtIndex(currentIndex)
        } else {
            videoView.seekTo(0)
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
        val rootW = rootLayout.width
        val rootH = rootLayout.height
        if (rootW == 0 || rootH == 0) return

        when (aspectMode) {
            VideoAspectMode.FIT -> {
                videoView.layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            }
            VideoAspectMode.FILL -> {
                // Scale so bounds fill screen
                val mp = mediaPlayerRef
                if (mp != null && mp.videoWidth > 0 && mp.videoHeight > 0) {
                    val vw = mp.videoWidth.toFloat()
                    val vh = mp.videoHeight.toFloat()
                    val screenRatio = rootW.toFloat() / rootH
                    val videoRatio = vw / vh
                    if (screenRatio > videoRatio) {
                        val targetH = (rootW / videoRatio).toInt()
                        videoView.layoutParams = FrameLayout.LayoutParams(rootW, targetH, Gravity.CENTER)
                    } else {
                        val targetW = (rootH * videoRatio).toInt()
                        videoView.layoutParams = FrameLayout.LayoutParams(targetW, rootH, Gravity.CENTER)
                    }
                } else {
                    videoView.layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                }
            }
            VideoAspectMode.RATIO_16_9 -> {
                val targetH = (rootW * 9) / 16
                videoView.layoutParams = FrameLayout.LayoutParams(rootW, targetH, Gravity.CENTER)
            }
            VideoAspectMode.RATIO_4_3 -> {
                val targetH = (rootW * 3) / 4
                videoView.layoutParams = FrameLayout.LayoutParams(rootW, targetH, Gravity.CENTER)
            }
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayerRef?.let { mp ->
                try {
                    val wasPlaying = mp.isPlaying
                    mp.playbackParams = PlaybackParams().apply { speed = playbackSpeed }
                    if (!wasPlaying) mp.pause()
                } catch (_: Exception) {}
            }
        }
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
        stagedFile = null
    }

    private fun formatTime(ms: Int): String {
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoView.isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val mp = mediaPlayerRef
                    val ratio = if (mp != null && mp.videoWidth > 0 && mp.videoHeight > 0) {
                        val w = mp.videoWidth.coerceIn(1, 239)
                        val h = mp.videoHeight.coerceIn(1, 239)
                        android.util.Rational(w, h)
                    } else {
                        android.util.Rational(16, 9)
                    }
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

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        videoView.stopPlayback()
        cleanupStagedFile()
        com.infinifolder.ops.AppOperations.coordinator.completeExternal("playback_video")
    }
}
