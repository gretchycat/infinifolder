package com.infinifolder.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.infinifolder.theme.ThemeColors
import com.infinifolder.theme.ThemeManager
import kotlin.math.max
import kotlin.math.min

/**
 * Authentic pixel-perfect Winamp 2.x Main Window view.
 * Native dimension is 275 x 116 pixels, dynamically scaled to the screen width.
 * Places all original Winamp faceplate elements, sprites, and controls at their exact coordinates.
 * Connected to live audio visualizer data with zero fake random animations.
 */
class WinampMainWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Callback {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onPrev()
        fun onNext()
        fun onEject()
        fun onSeek(positionMs: Int)
        fun onVolumeChanged(volume: Float)
        fun onBalanceChanged(balance: Float)
        fun onToggleShuffle()
        fun onToggleRepeat()
        fun onToggleDockedSubwindow()
        fun onClose()
        fun onDiagnosticsRequested()
    }

    var callback: Callback? = null
    var visualizerBridge: RealAudioVisualizerBridge? = null

    // Playback state
    var isPlaying: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var isPaused: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var currentPositionMs: Int = 0
        set(value) {
            field = value
            if (!isSeeking) invalidate()
        }
    var durationMs: Int = 0
        set(value) {
            field = value
            if (!isSeeking) invalidate()
        }
    var trackTitle: String = "Infinifolder Media Player"
        set(value) {
            field = value
            marqueeOffset = 0f
            invalidate()
        }
    var kbps: Int? = null
        set(value) {
            field = value
            invalidate()
        }
    var khz: Int? = null
        set(value) {
            field = value
            invalidate()
        }
    var channels: Int? = 2
        set(value) {
            field = value
            invalidate()
        }
    var isStereo: Boolean
        get() = (channels ?: 2) >= 2
        set(value) {
            channels = if (value) 2 else 1
            invalidate()
        }
    var isShuffle: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var isRepeat: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var balance: Float = 0.0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            invalidate()
        }

    private var showRemainingTime: Boolean = false
    private var visualizerMode: Int = 0 // 0: Spectrum, 1: Oscilloscope, 2: VU Meter

    // Touch interaction
    private var pressedControl: Control? = null
    private var isSeeking = false
    private var isDraggingVolume = false
    private var isDraggingBalance = false

    // Real audio buffers
    private val fftBands = FloatArray(16)
    private val peakBands = FloatArray(16)
    private val waveform = FloatArray(76)
    private val vuLevels = FloatArray(4) // L, R, peakL, peakR

    // Animation & marquee
    private var lastFrameTime = SystemClock.uptimeMillis()
    private var marqueeOffset = 0f

    companion object {
        const val NATIVE_WIDTH = 275
        const val NATIVE_HEIGHT = 116
    }

    // 1:1 Offscreen Canvas and Intermediate Pixel-Scaling Buffers
    private val baseBitmap = Bitmap.createBitmap(NATIVE_WIDTH, NATIVE_HEIGHT, Bitmap.Config.ARGB_8888)
    private val baseCanvas = Canvas(baseBitmap)
    private var pixelScaledBitmap: Bitmap? = null
    private var pixelScaledCanvas: Canvas? = null

    // Rendering paints
    private val nearestPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    private val smoothPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val textPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = 8.5f
        isAntiAlias = false
        color = 0xFF00FF00.toInt()
    }
    private val visBarPaint = Paint().apply {
        isAntiAlias = false
    }
    private val visPeakPaint = Paint().apply {
        isAntiAlias = false
    }
    private val visBgPaint = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }
    private val vuPaint = Paint().apply {
        isAntiAlias = false
    }

    private val srcRect = Rect()
    private val dstRect = Rect()

    private enum class Control {
        PREV, PLAY, PAUSE, STOP, NEXT, EJECT,
        SHUFFLE, REPEAT,
        TIMER, VISUALIZER,
        SEEK, VOLUME, BALANCE,
        EQ_BTN, PL_BTN,
        TITLE_CLOSE, TITLE_SHADE
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val screenW = resources.displayMetrics.widthPixels
        val finalW = if (widthSize > 0) widthSize else screenW
        val calculatedHeight = ((NATIVE_HEIGHT.toFloat() / NATIVE_WIDTH.toFloat()) * finalW).toInt()
        setMeasuredDimension(finalW, calculatedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrameTime).coerceIn(0, 100)
        lastFrameTime = now

        // 1. Generate the entire interface at true 1:1 pixel resolution
        baseBitmap.eraseColor(Color.TRANSPARENT)

        drawFaceplate(baseCanvas)
        drawTitleBar(baseCanvas)
        drawPlayStatus(baseCanvas)
        drawTimer(baseCanvas)
        drawMarquee(baseCanvas, dt)
        drawBadges(baseCanvas)
        drawVisualizer(baseCanvas)
        drawVolume(baseCanvas)
        drawBalance(baseCanvas)
        drawEqPlButtons(baseCanvas)
        drawPositionBar(baseCanvas)
        drawTransportButtons(baseCanvas)
        drawShuffleRepeat(baseCanvas)

        // 2. Scale to maximum allowable size with pixel scaling, then scale all the way with smooth scaling
        val k = w / NATIVE_WIDTH
        if (k >= 2) {
            val pixelW = NATIVE_WIDTH * k
            val pixelH = NATIVE_HEIGHT * k

            if (pixelScaledBitmap == null || pixelScaledBitmap?.width != pixelW || pixelScaledBitmap?.height != pixelH) {
                pixelScaledBitmap?.recycle()
                val newBmp = Bitmap.createBitmap(pixelW, pixelH, Bitmap.Config.ARGB_8888)
                pixelScaledBitmap = newBmp
                pixelScaledCanvas = Canvas(newBmp)
            }

            // Step A: Pixel scaling (integer nearest-neighbor) to maximum allowable integer multiple
            srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
            dstRect.set(0, 0, pixelW, pixelH)
            pixelScaledCanvas?.drawBitmap(baseBitmap, srcRect, dstRect, nearestPaint)

            // Step B: Smooth scaling from the pixel-scaled buffer all the way to target view bounds
            dstRect.set(0, 0, w, h)
            canvas.drawBitmap(pixelScaledBitmap!!, Rect(0, 0, pixelW, pixelH), dstRect, smoothPaint)
        } else {
            // k <= 1: Smooth scale directly from 1:1 to target bounds
            srcRect.set(0, 0, NATIVE_WIDTH, NATIVE_HEIGHT)
            dstRect.set(0, 0, w, h)
            canvas.drawBitmap(baseBitmap, srcRect, dstRect, smoothPaint)
        }

        if (isPlaying || fftBands.any { it > 0.01f } || peakBands.any { it > 0.01f }) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawFaceplate(canvas: Canvas) {
        val mainBmp = ThemeManager.skinBitmap("main.bmp")
        if (mainBmp != null) {
            srcRect.set(0, 0, min(275, mainBmp.width), min(116, mainBmp.height))
            dstRect.set(0, 0, 275, 116)
            canvas.drawBitmap(mainBmp, srcRect, dstRect, bitmapPaint)
        } else {
            // High fidelity fallback faceplate
            canvas.drawColor(0xFF262738.toInt())
        }
    }

    private fun drawTitleBar(canvas: Canvas) {
        val titleBmp = ThemeManager.skinBitmap("titlebar.bmp")
        if (titleBmp != null && titleBmp.width >= 302 && titleBmp.height >= 14) {
            // Row 0: Active title strip (27, 0, 302, 14)
            srcRect.set(27, 0, 302, 14)
            dstRect.set(0, 0, 275, 14)
            canvas.drawBitmap(titleBmp, srcRect, dstRect, bitmapPaint)

            // Window shade button at x=254, y=3 (9x9)
            val shadePressed = (pressedControl == Control.TITLE_SHADE)
            val shadeY = if (shadePressed) 9 else 0
            srcRect.set(9, shadeY, 18, shadeY + 9)
            dstRect.set(254, 3, 263, 12)
            canvas.drawBitmap(titleBmp, srcRect, dstRect, bitmapPaint)

            // Close button at x=264, y=3 (9x9)
            val closePressed = (pressedControl == Control.TITLE_CLOSE)
            val closeY = if (closePressed) 9 else 0
            srcRect.set(18, closeY, 27, closeY + 9)
            dstRect.set(264, 3, 273, 12)
            canvas.drawBitmap(titleBmp, srcRect, dstRect, bitmapPaint)
        }
    }

    private fun drawPlayStatus(canvas: Canvas) {
        val ppBmp = ThemeManager.skinBitmap("playpaus.bmp")
        if (ppBmp != null && ppBmp.width >= 27) {
            // At x=24, y=28 (9x9) inside the time display window
            val srcX = when {
                isPlaying -> 0
                isPaused -> 9
                else -> 18
            }
            srcRect.set(srcX, 0, srcX + 9, 9)
            dstRect.set(24, 28, 33, 37)
            canvas.drawBitmap(ppBmp, srcRect, dstRect, bitmapPaint)
        }
    }

    private fun drawTimer(canvas: Canvas) {
        val numBmp = ThemeManager.skinBitmap("numbers.bmp")
        val effectiveMs = if (showRemainingTime) {
            max(0, durationMs - currentPositionMs)
        } else {
            currentPositionMs
        }
        val totalSec = effectiveMs / 1000
        val min = (totalSec / 60) % 100
        val sec = totalSec % 60

        val min1 = min / 10
        val min2 = min % 10
        val sec1 = sec / 10
        val sec2 = sec % 10

        if (numBmp != null && numBmp.width >= 99 && numBmp.height >= 13) {
            // Draw minus sign if remaining time
            if (showRemainingTime && numBmp.width >= 108) {
                // Minus sign character (index 10 or 11)
                srcRect.set(90, 0, 99, 13)
                dstRect.set(36, 26, 45, 39)
                canvas.drawBitmap(numBmp, srcRect, dstRect, bitmapPaint)
            }

            fun drawDigit(digit: Int, targetX: Int) {
                val clamped = digit.coerceIn(0, 9)
                srcRect.set(clamped * 9, 0, (clamped + 1) * 9, 13)
                dstRect.set(targetX, 26, targetX + 9, 39)
                canvas.drawBitmap(numBmp, srcRect, dstRect, bitmapPaint)
            }

            drawDigit(min1, 48)
            drawDigit(min2, 60)
            drawDigit(sec1, 78)
            drawDigit(sec2, 90)
        } else {
            // Monospace fallback
            val theme = ThemeManager.getAudioPlayerTheme(context)
            textPaint.color = theme.textPrimary
            val prefix = if (showRemainingTime) "-" else " "
            val formatted = String.format("%s%02d:%02d", prefix, min, sec)
            canvas.drawText(formatted, 38f, 36f, textPaint)
        }
    }

    private fun drawMarquee(canvas: Canvas, dtMs: Long) {
        val theme = ThemeManager.getAudioPlayerTheme(context)
        textPaint.color = theme.textPrimary
        textPaint.textSize = 7.5f

        val clipLeft = 111
        val clipTop = 24
        val clipRight = 265
        val clipBottom = 35
        val boxWidth = clipRight - clipLeft

        canvas.save()
        canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)

        val fullText = "$trackTitle *** "
        val measured = textPaint.measureText(fullText)

        if (isPlaying) {
            marqueeOffset += (dtMs * 0.035f)
            if (marqueeOffset > measured) marqueeOffset = 0f
        }

        var startX = clipLeft.toFloat() - marqueeOffset
        while (startX < clipRight) {
            canvas.drawText(fullText, startX, 32.0f, textPaint)
            startX += measured
            if (measured <= 0f) break
        }

        canvas.restore()
    }

    private fun drawBadges(canvas: Canvas) {
        // Mono and Stereo indicators on Main Window:
        // MONO is on the LEFT at (212, 41..53)
        // STEREO is on the RIGHT at (239, 41..53)
        // In monoster.bmp:
        // Left column (0..29): Stereo (y=0..12 lit, y=12..24 unlit)
        // Right column (29..58): Mono (y=0..12 lit, y=12..24 unlit)
        val msBmp = ThemeManager.skinBitmap("monoster.bmp")
        val theme = ThemeManager.getAudioPlayerTheme(context)
        val ch = channels
        if (msBmp != null && msBmp.width >= 56 && msBmp.height >= 24) {
            val monoW = (msBmp.width - 29).coerceIn(26, 28)
            val stereoW = 29.coerceAtMost(msBmp.width)

            val isMono = (ch == 1)
            val isStereo = (ch != null && ch >= 2)

            if (isStereo) {
                // Mono unlit at x=212
                srcRect.set(29, 12, 29 + monoW, 24)
                dstRect.set(212, 41, 212 + monoW, 53)
                canvas.drawBitmap(msBmp, srcRect, dstRect, bitmapPaint)

                // Stereo lit at x=239
                srcRect.set(0, 0, stereoW, 12)
                dstRect.set(239, 41, 239 + stereoW, 53)
                canvas.drawBitmap(msBmp, srcRect, dstRect, bitmapPaint)
            } else if (isMono) {
                // Mono lit at x=212
                srcRect.set(29, 0, 29 + monoW, 12)
                dstRect.set(212, 41, 212 + monoW, 53)
                canvas.drawBitmap(msBmp, srcRect, dstRect, bitmapPaint)

                // Stereo unlit at x=239
                srcRect.set(0, 12, stereoW, 24)
                dstRect.set(239, 41, 239 + stereoW, 53)
                canvas.drawBitmap(msBmp, srcRect, dstRect, bitmapPaint)
            } else {
                // Both unlit (when channel info is irrelevant or unavailable)
                srcRect.set(29, 12, 29 + monoW, 24)
                dstRect.set(212, 41, 212 + monoW, 53)
                canvas.drawBitmap(msBmp, srcRect, dstRect, bitmapPaint)

                srcRect.set(0, 12, stereoW, 24)
                dstRect.set(239, 41, 239 + stereoW, 53)
                canvas.drawBitmap(msBmp, srcRect, dstRect, bitmapPaint)
            }
        } else {
            textPaint.textSize = 6.0f
            textPaint.color = if (ch == 1) theme.textPrimary else theme.textSecondary
            canvas.drawText("MONO", 212f, 48.0f, textPaint)
            textPaint.color = if (ch != null && ch >= 2) theme.textPrimary else theme.textSecondary
            canvas.drawText("STEREO", 239f, 48.0f, textPaint)
        }

        // KBPS & KHZ readouts at (111, 41..50) and (156, 41..50)
        val kbpsVal = kbps
        val khzVal = khz
        val kbpsStr = if (kbpsVal != null && kbpsVal > 0) String.format(java.util.Locale.US, "%3d", kbpsVal) else " --"
        val khzStr = if (khzVal != null && khzVal > 0) String.format(java.util.Locale.US, "%2d", khzVal) else "--"

        val textBmp = ThemeManager.skinBitmap("text.bmp")
        if (textBmp != null && textBmp.width >= 155 && textBmp.height >= 12) {
            fun drawSkinText(str: String, startX: Int, startY: Int) {
                var curX = startX
                for (c in str) {
                    val col = when (c) {
                        in '0'..'9' -> c - '0'
                        '-' -> 15
                        in 'A'..'Z' -> c - 'A'
                        in 'a'..'z' -> c.uppercaseChar() - 'A'
                        else -> -1
                    }
                    if (col >= 0) {
                        val rowY = if (c in '0'..'9' || c == '-') 6 else 0
                        srcRect.set(col * 5, rowY, (col + 1) * 5, rowY + 6)
                        dstRect.set(curX, startY, curX + 5, startY + 6)
                        canvas.drawBitmap(textBmp, srcRect, dstRect, bitmapPaint)
                    }
                    curX += 5
                }
            }

            drawSkinText(kbpsStr, 111, 43)
            drawSkinText(khzStr, 156, 43)
        } else {
            textPaint.textSize = 7.0f
            textPaint.color = if (kbpsVal != null && kbpsVal > 0) theme.textPrimary else theme.textSecondary
            canvas.drawText(kbpsStr, 111f, 48.0f, textPaint)
            textPaint.color = if (khzVal != null && khzVal > 0) theme.textPrimary else theme.textSecondary
            canvas.drawText(khzStr, 156f, 48.0f, textPaint)
        }
    }

    private fun drawVisualizer(canvas: Canvas) {
        val visLeft = 24
        val visTop = 43
        val visRight = 100
        val visBottom = 59
        val visW = visRight - visLeft
        val visH = visBottom - visTop

        canvas.save()
        canvas.clipRect(visLeft, visTop, visRight, visBottom)
        // Background left transparent to allow underlying theme/main.bmp artwork to show through

        val bridge = visualizerBridge
        if (bridge != null) {
            bridge.getSpectrum(fftBands, peakBands, isPlaying)
            bridge.getWaveform(waveform, isPlaying)
            bridge.getVuLevels(vuLevels, isPlaying)
        } else {
            fftBands.fill(0f)
            peakBands.fill(0f)
            waveform.fill(0f)
            vuLevels.fill(0f)
        }

        val theme = ThemeManager.getAudioPlayerTheme(context)
        val spectrumPalette = theme.spectrumColors

        when (visualizerMode) {
            0 -> {
                // Spectrum Analyzer (16 classic Winamp vertical bars with peak falloff)
                val barW = 4f
                val spacing = 0.75f
                for (i in 0 until 16) {
                    val barX = visLeft + 1f + i * (barW + spacing)
                    val mag = fftBands[i].coerceIn(0f, 1f)
                    val barHeight = mag * (visH - 1f)

                    // Draw segmented blocks
                    var y = visBottom - 1f
                    while (y > visBottom - barHeight && y > visTop) {
                        val normY = (visBottom - y) / visH
                        val colorIdx = ((1f - normY) * (spectrumPalette.size - 1)).toInt().coerceIn(0, spectrumPalette.size - 1)
                        visBarPaint.color = spectrumPalette[colorIdx]
                        canvas.drawRect(barX, y - 1f, barX + barW - 1f, y, visBarPaint)
                        y -= 2f // 1px bar, 1px black gap
                    }

                    // Peak line
                    val peak = peakBands[i].coerceIn(0f, 1f)
                    val peakY = visBottom - peak * (visH - 1f)
                    if (peakY < visBottom - 2f) {
                        visPeakPaint.color = 0xFFFFFFFF.toInt()
                        canvas.drawRect(barX, peakY - 1f, barX + barW - 1f, peakY, visPeakPaint)
                    }
                }
            }
            1 -> {
                // Oscilloscope (continuous real waveform)
                visPeakPaint.color = theme.textPrimary
                visPeakPaint.strokeWidth = 1f
                val midY = visTop + visH / 2f
                val pts = min(waveform.size, visW)
                for (i in 0 until pts - 1) {
                    val x1 = visLeft + i.toFloat()
                    val y1 = midY + waveform[i] * (visH / 2f - 1f)
                    val x2 = visLeft + (i + 1).toFloat()
                    val y2 = midY + waveform[i + 1] * (visH / 2f - 1f)
                    canvas.drawLine(x1, y1, x2, y2, visPeakPaint)
                }
            }
            2 -> {
                // Real Stereo VU Meter
                val vuL = vuLevels[0].coerceIn(0f, 1f)
                val vuR = vuLevels[1].coerceIn(0f, 1f)
                val pL = vuLevels[2].coerceIn(0f, 1f)
                val pR = vuLevels[3].coerceIn(0f, 1f)

                fun drawVuChannel(topY: Float, level: Float, peak: Float) {
                    val w = level * (visW - 4f)
                    var curX = visLeft + 2f
                    while (curX < visLeft + 2f + w) {
                        val ratio = (curX - (visLeft + 2f)) / (visW - 4f)
                        vuPaint.color = when {
                            ratio > 0.85f -> 0xFFFF2222.toInt()
                            ratio > 0.65f -> 0xFFFFCC00.toInt()
                            else -> 0xFF00FF22.toInt()
                        }
                        canvas.drawRect(curX, topY, curX + 2f, topY + 5f, vuPaint)
                        curX += 3f
                    }
                    // Peak indicator
                    val peakX = visLeft + 2f + peak * (visW - 4f)
                    if (peakX > visLeft + 3f) {
                        vuPaint.color = 0xFFFFFFFF.toInt()
                        canvas.drawRect(peakX - 1f, topY, peakX + 1f, topY + 5f, vuPaint)
                    }
                }

                drawVuChannel(visTop + 2f, vuL, pL)
                drawVuChannel(visTop + 9f, vuR, pR)
            }
        }

        canvas.restore()
    }

    private fun drawVolume(canvas: Canvas) {
        val volBmp = ThemeManager.skinBitmap("volume.bmp")
        if (volBmp != null && volBmp.height >= 433) {
            val frame = (volume * 27).toInt().coerceIn(0, 27)
            val srcY = frame * 15
            srcRect.set(0, srcY, 68, srcY + 13)
            dstRect.set(107, 57, 175, 70)
            canvas.drawBitmap(volBmp, srcRect, dstRect, bitmapPaint)

            // Thumb at bottom of VOLUME.BMP (0, 422, 14, 433)
            val thumbPressed = (pressedControl == Control.VOLUME)
            val thumbSrcX = if (thumbPressed) 15 else 0
            val thumbX = 107 + (volume * 54).toInt()
            srcRect.set(thumbSrcX, 422, thumbSrcX + 14, 433)
            dstRect.set(thumbX, 57, thumbX + 14, 68)
            canvas.drawBitmap(volBmp, srcRect, dstRect, bitmapPaint)
        }
    }

    private fun drawBalance(canvas: Canvas) {
        val balBmp = ThemeManager.skinBitmap("balance.bmp")
        if (balBmp != null && balBmp.height >= 433) {
            val norm = (balance + 1f) / 2f
            val frame = (norm * 27).toInt().coerceIn(0, 27)
            val srcY = frame * 15
            srcRect.set(0, srcY, 38, srcY + 13)
            dstRect.set(177, 57, 215, 70)
            canvas.drawBitmap(balBmp, srcRect, dstRect, bitmapPaint)

            // Thumb
            val thumbPressed = (pressedControl == Control.BALANCE)
            val thumbSrcX = if (thumbPressed) 15 else 0
            val thumbX = 177 + (norm * 24).toInt()
            srcRect.set(thumbSrcX, 422, thumbSrcX + 14, 433)
            dstRect.set(thumbX, 57, thumbX + 14, 68)
            canvas.drawBitmap(balBmp, srcRect, dstRect, bitmapPaint)
        }
    }

    private fun drawEqPlButtons(canvas: Canvas) {
        val shufBmp = ThemeManager.skinBitmap("shufrep.bmp")
        if (shufBmp != null && shufBmp.width >= 92) {
            // EQ at (219, 58, 242, 70) -> size 23x12
            val eqPressed = (pressedControl == Control.EQ_BTN)
            val eqY = if (eqPressed) 73 else 61
            if (shufBmp.height >= eqY + 12) {
                srcRect.set(0, eqY, 23, eqY + 12)
                dstRect.set(219, 58, 242, 70)
                canvas.drawBitmap(shufBmp, srcRect, dstRect, bitmapPaint)
            }

            // PL at (242, 58, 265, 70) -> size 23x12
            val plPressed = (pressedControl == Control.PL_BTN)
            val plY = if (plPressed) 73 else 61
            if (shufBmp.height >= plY + 12) {
                srcRect.set(23, plY, 46, plY + 12)
                dstRect.set(242, 58, 265, 70)
                canvas.drawBitmap(shufBmp, srcRect, dstRect, bitmapPaint)
            }
        }
    }

    private fun drawPositionBar(canvas: Canvas) {
        val posBmp = ThemeManager.skinBitmap("posbar.bmp")
        val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        if (posBmp != null && posBmp.width >= 307) {
            // Track (0, 0, 248, 10) at (16, 72, 264, 82)
            srcRect.set(0, 0, 248, 10)
            dstRect.set(16, 72, 264, 82)
            canvas.drawBitmap(posBmp, srcRect, dstRect, bitmapPaint)

            // Thumb (normal 248..277, pressed 278..307) width 29
            val thumbPressed = (pressedControl == Control.SEEK || isSeeking)
            val thumbSrcX = if (thumbPressed) 278 else 248
            val thumbX = 16 + (progress * (248 - 29)).toInt()
            srcRect.set(thumbSrcX, 0, thumbSrcX + 29, 10)
            dstRect.set(thumbX, 72, thumbX + 29, 82)
            canvas.drawBitmap(posBmp, srcRect, dstRect, bitmapPaint)
        }
    }

    private fun drawTransportButtons(canvas: Canvas) {
        val cbBmp = ThemeManager.skinBitmap("cbuttons.bmp") ?: return

        fun drawCButton(index: Int, x: Int, y: Int, w: Int, h: Int, ctrl: Control) {
            val pressed = (pressedControl == ctrl)
            val srcY = if (pressed) 18 else 0
            val srcX = when (index) {
                0 -> 0   // Prev
                1 -> 23  // Play
                2 -> 46  // Pause
                3 -> 69  // Stop
                4 -> 92  // Next
                5 -> 114 // Eject
                else -> 0
            }
            srcRect.set(srcX, srcY, srcX + w, srcY + h)
            dstRect.set(x, y, x + w, y + h)
            canvas.drawBitmap(cbBmp, srcRect, dstRect, bitmapPaint)
        }

        drawCButton(0, 16, 88, 23, 18, Control.PREV)
        drawCButton(1, 39, 88, 23, 18, Control.PLAY)
        drawCButton(2, 62, 88, 23, 18, Control.PAUSE)
        drawCButton(3, 85, 88, 23, 18, Control.STOP)
        drawCButton(4, 108, 88, 22, 18, Control.NEXT)
        drawCButton(5, 136, 89, 22, 16, Control.EJECT)
    }

    private fun drawShuffleRepeat(canvas: Canvas) {
        val srBmp = ThemeManager.skinBitmap("shufrep.bmp") ?: return

        // Shuffle at (164, 89, 211, 104) w=47, h=15
        val shufPressed = (pressedControl == Control.SHUFFLE)
        val shufY = when {
            isShuffle && shufPressed -> 45
            isShuffle -> 30
            shufPressed -> 15
            else -> 0
        }
        srcRect.set(28, shufY, 75, shufY + 15)
        dstRect.set(164, 89, 211, 104)
        canvas.drawBitmap(srBmp, srcRect, dstRect, bitmapPaint)

        // Repeat at (210, 89, 238, 104) w=28, h=15
        val repPressed = (pressedControl == Control.REPEAT)
        val repY = when {
            isRepeat && repPressed -> 45
            isRepeat -> 30
            repPressed -> 15
            else -> 0
        }
        srcRect.set(0, repY, 28, repY + 15)
        dstRect.set(210, 89, 238, 104)
        canvas.drawBitmap(srBmp, srcRect, dstRect, bitmapPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleX = width.toFloat() / NATIVE_WIDTH
        val scaleY = height.toFloat() / NATIVE_HEIGHT
        if (scaleX <= 0f || scaleY <= 0f) return super.onTouchEvent(event)

        val nx = event.x / scaleX
        val ny = event.y / scaleY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(nx, ny)
                pressedControl = hit
                when (hit) {
                    Control.SEEK -> {
                        isSeeking = true
                        updateSeekPosition(nx)
                    }
                    Control.VOLUME -> {
                        isDraggingVolume = true
                        updateVolume(nx)
                    }
                    Control.BALANCE -> {
                        isDraggingBalance = true
                        updateBalance(nx)
                    }
                    else -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    updateSeekPosition(nx)
                } else if (isDraggingVolume) {
                    updateVolume(nx)
                } else if (isDraggingBalance) {
                    updateBalance(nx)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = hitTest(nx, ny)
                val wasControl = pressedControl
                pressedControl = null

                if (isSeeking) {
                    isSeeking = false
                    val pos = updateSeekPosition(nx)
                    callback?.onSeek(pos)
                } else if (isDraggingVolume) {
                    isDraggingVolume = false
                    updateVolume(nx)
                } else if (isDraggingBalance) {
                    isDraggingBalance = false
                    updateBalance(nx)
                } else if (hit != null && hit == wasControl) {
                    performControlAction(hit)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedControl = null
                isSeeking = false
                isDraggingVolume = false
                isDraggingBalance = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Control? {
        return when {
            // Titlebar buttons
            x in 264f..274f && y in 2f..13f -> Control.TITLE_CLOSE
            x in 254f..264f && y in 2f..13f -> Control.TITLE_SHADE
            // Timer
            x in 24f..102f && y in 25f..40f -> Control.TIMER
            // Visualizer
            x in 24f..100f && y in 43f..59f -> Control.VISUALIZER
            // Sliders
            x in 16f..264f && y in 71f..83f -> Control.SEEK
            x in 107f..175f && y in 56f..71f -> Control.VOLUME
            x in 177f..215f && y in 56f..71f -> Control.BALANCE
            // Buttons
            x in 219f..242f && y in 57f..71f -> Control.EQ_BTN
            x in 242f..265f && y in 57f..71f -> Control.PL_BTN
            x in 16f..39f && y in 87f..107f -> Control.PREV
            x in 39f..62f && y in 87f..107f -> Control.PLAY
            x in 62f..85f && y in 87f..107f -> Control.PAUSE
            x in 85f..108f && y in 87f..107f -> Control.STOP
            x in 108f..131f && y in 87f..107f -> Control.NEXT
            x in 136f..158f && y in 88f..106f -> Control.EJECT
            x in 164f..211f && y in 88f..105f -> Control.SHUFFLE
            x in 210f..238f && y in 88f..105f -> Control.REPEAT
            else -> null
        }
    }

    private fun performControlAction(c: Control) {
        when (c) {
            Control.PREV -> callback?.onPrev()
            Control.PLAY -> callback?.onPlay()
            Control.PAUSE -> callback?.onPause()
            Control.STOP -> callback?.onStop()
            Control.NEXT -> callback?.onNext()
            Control.EJECT -> callback?.onEject()
            Control.SHUFFLE -> callback?.onToggleShuffle()
            Control.REPEAT -> callback?.onToggleRepeat()
            Control.TIMER -> {
                showRemainingTime = !showRemainingTime
                invalidate()
            }
            Control.VISUALIZER -> {
                visualizerMode = (visualizerMode + 1) % 3
                invalidate()
            }
            Control.EQ_BTN -> callback?.onDiagnosticsRequested()
            Control.PL_BTN, Control.TITLE_SHADE -> callback?.onToggleDockedSubwindow()
            Control.TITLE_CLOSE -> callback?.onClose()
            else -> {}
        }
    }

    private fun updateSeekPosition(nx: Float): Int {
        val rel = ((nx - 16f) / (248f - 29f)).coerceIn(0f, 1f)
        val targetMs = (rel * durationMs).toInt()
        currentPositionMs = targetMs
        invalidate()
        return targetMs
    }

    private fun updateVolume(nx: Float) {
        val vol = ((nx - 107f) / 54f).coerceIn(0f, 1f)
        volume = vol
        callback?.onVolumeChanged(vol)
        invalidate()
    }

    private fun updateBalance(nx: Float) {
        val balNorm = ((nx - 177f) / 24f).coerceIn(0f, 1f)
        val bal = (balNorm - 0.5f) * 2f
        balance = bal
        callback?.onBalanceChanged(bal)
        invalidate()
    }
}
