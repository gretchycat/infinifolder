package com.infinifolder.viewer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.infinifolder.theme.ThemeColors

/** Aero presentation of the player's existing widgets, listeners and playback state. */
internal class WmpPlayerLayout(context: Context, private val content: Content) : LinearLayout(context) {
    data class Content(
        val metadata: LinearLayout, val art: FrameLayout, val progress: LinearLayout,
        val controls: LinearLayout, val secondary: LinearLayout, val volume: LinearLayout,
        val title: TextView, val artist: TextView, val artwork: ImageView,
        val visualizer: View, val seek: SeekBar, val volumeSeek: SeekBar,
        val shuffle: Button, val previous: Button, val play: Button,
        val stop: Button, val next: Button, val repeat: Button
    )
    private data class Home(val view: View, val parent: ViewGroup, val index: Int,
        val params: ViewGroup.LayoutParams, val padding: IntArray)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val sections = listOf(content.metadata, content.art, content.progress,
        content.controls, content.secondary, content.volume)
    private val homes = sections.map { Home(it, it.parent as ViewGroup,
        (it.parent as ViewGroup).indexOfChild(it), it.layoutParams,
        intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)) }
    private val buttons = listOf(content.shuffle, content.previous, content.play,
        content.stop, content.next, content.repeat)
    private val buttonParams = buttons.map { it.layoutParams }
    private val buttonMinimums = buttons.map { intArrayOf(it.minimumWidth, it.minimumHeight, it.minWidth, it.minHeight) }
    private val buttonTints = buttons.map { it.backgroundTintList }
    private val buttonDescriptions = buttons.map { it.contentDescription }
    private val textSizes = listOf(content.title, content.artist).map { it.textSize }
    private val originalScale = content.artwork.scaleType
    private val stage = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val artStage = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val side = (minOf(w, h) - dp(20)).coerceAtLeast(0)
            for (i in 0 until childCount) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val x = (width - child.measuredWidth) / 2
                val y = (height - child.measuredHeight) / 2
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            }
        }
    }
    private val dock = LinearLayout(context).apply { orientation = VERTICAL }
    private val tools = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private var active = false
    private var palette: ThemeColors? = null
    private var lastControls: Triple<Boolean, Boolean, RepeatMode>? = null

    init {
        orientation = VERTICAL
        visibility = GONE
        layoutParams = LayoutParams(-1, 0, 1f)
        addView(stage, LayoutParams(-1, 0, 1f))
        addView(dock, LayoutParams(-1, -2))
        stage.addView(artStage)
        dock.addView(tools)
    }

    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        visibility = if (enabled) VISIBLE else GONE
        sections.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        if (enabled) {
            stage.addView(content.metadata, 0)
            artStage.addView(content.art)
            dock.addView(content.progress, 0, LayoutParams(-1, -2))
            dock.addView(content.controls, 1, LayoutParams(-1, -2))
            tools.addView(content.secondary, LayoutParams(-2, -2))
            tools.addView(content.volume, LayoutParams(0, -2, 1f))
        } else {
            homes.forEach { home ->
                home.parent.addView(home.view, home.index.coerceAtMost(home.parent.childCount), home.params)
                home.view.setPadding(home.padding[0], home.padding[1], home.padding[2], home.padding[3])
            }
            buttons.forEachIndexed { index, button ->
                button.layoutParams = buttonParams[index]
                val sizes = buttonMinimums[index]
                button.minimumWidth = sizes[0]; button.minimumHeight = sizes[1]
                button.minWidth = sizes[2]; button.minHeight = sizes[3]
                button.backgroundTintList = buttonTints[index]
                button.contentDescription = buttonDescriptions[index]
            }
            listOf(content.title, content.artist).forEachIndexed { i, text ->
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizes[i])
            }
            content.metadata.getChildAt(0).visibility = VISIBLE
            content.artwork.scaleType = originalScale
            lastControls = null
        }
    }

    fun applyTheme(theme: ThemeColors) {
        if (!active) return
        palette = theme
        background = glass(theme.surface, theme.background, 0)
        dock.background = glass(theme.toolbarBorder, theme.statusBackground, 0)
        dock.setPadding(dp(8), dp(4), dp(8), dp(6))
        content.metadata.background = null
        content.metadata.setPadding(dp(20), dp(14), dp(20), dp(6))
        content.metadata.getChildAt(0).visibility = GONE // WMP uses the seek-row clock.
        content.visualizer.visibility = GONE
        content.title.textSize = 20f
        content.artist.textSize = 14f
        content.art.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.BLACK)
            setStroke(dp(1).coerceAtLeast(1), theme.cardStroke)
        }
        content.art.setPadding(dp(3), dp(3), dp(3), dp(3))
        content.artwork.scaleType = ImageView.ScaleType.FIT_CENTER
        content.controls.setPadding(0, dp(4), 0, dp(4))
        content.secondary.setPadding(dp(4), 0, 0, 0)
        content.volume.setPadding(dp(4), 0, dp(4), 0)
        buttons.forEach { button ->
            button.layoutParams = if (button === content.play) LayoutParams(dp(68), dp(68))
                else LayoutParams(0, dp(48), 1f)
            button.minWidth = 0; button.minHeight = 0
            button.minimumWidth = 0; button.minimumHeight = 0
            button.backgroundTintList = null
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        stage.orientation = if (landscape) HORIZONTAL else VERTICAL
        content.metadata.layoutParams = if (landscape) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2)
        artStage.layoutParams = if (landscape) LayoutParams(0, -1, 1f) else LayoutParams(-1, 0, 1f)
        listOf(content.seek, content.volumeSeek).forEach {
            it.progressTintList = android.content.res.ColorStateList.valueOf(theme.textHighlight)
            it.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(theme.cardStroke)
            it.thumbTintList = android.content.res.ColorStateList.valueOf(theme.textPrimary)
        }
        lastControls = null
    }

    fun updateControls(playing: Boolean, shuffle: Boolean, repeat: RepeatMode) {
        if (!active) return
        val theme = palette ?: return
        val state = Triple(playing, shuffle, repeat)
        if (lastControls == state) return
        lastControls = state
        val symbols = listOf("shuffle", "previous", if (playing) "pause" else "play", "stop", "next", "repeat")
        val labels = listOf("Shuffle: ${if (shuffle) "on" else "off"}", "Previous track",
            if (playing) "Pause" else "Play", "Stop", "Next track", "Repeat: ${repeat.name.lowercase()}")
        buttons.forEachIndexed { i, button ->
            val selected = (i == 0 && shuffle) || (i == 5 && repeat != RepeatMode.NONE)
            button.text = ""
            button.alpha = 1f
            button.contentDescription = labels[i]
            button.background = AeroTransportDrawable(symbols[i], theme, selected, i == 5 && repeat == RepeatMode.ONE)
        }
    }

    private fun glass(top: Int, bottom: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1).coerceAtLeast(1), 0x4438BDF8)
        }
}

/** Scalable glass chrome and transport glyphs; native Buttons supply touch/accessibility. */
private class AeroTransportDrawable(
    private val symbol: String, private val theme: ThemeColors,
    private val selected: Boolean, private val repeatOne: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private var filter: ColorFilter? = null
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        val checkpoint = if (opacity < 255) canvas.saveLayerAlpha(RectF(bounds), opacity) else canvas.save()
        val orb = symbol == "play" || symbol == "pause"
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
        canvas.scale(size / 64f, size / 64f)
        val pressed = state.contains(android.R.attr.state_pressed)
        paint.alpha = 255; paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(32f, 34f, 32f,
            intArrayOf(if (orb || selected) theme.accentGlow else Color.TRANSPARENT, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(32f, 32f, 32f, paint)
        val rect = if (orb) RectF(6f, 6f, 58f, 58f) else RectF(3f, 12f, 61f, 52f)
        val top = if (orb || selected) theme.textSecondary else theme.toolbarBorder
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(if (pressed) theme.accent else top,
                if (orb || selected || pressed) theme.accent else theme.surface, theme.background),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, if (orb) 26f else 9f, if (orb) 26f else 9f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
        paint.color = ColorUtils.setAlphaComponent(theme.textPrimary, if (pressed) 255 else 135)
        canvas.drawRoundRect(rect, if (orb) 26f else 9f, if (orb) 26f else 9f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, rect.top, 0f, 33f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(rect.left + 2, rect.top + 2, rect.right - 2, 32f), 16f, 16f, paint)
        paint.shader = null; paint.color = theme.textPrimary
        fun polygon(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                close()
            }
            canvas.drawPath(path, paint)
        }
        when (symbol) {
            "play" -> polygon(26f, 21f, 26f, 44f, 44f, 32.5f)
            "pause" -> { canvas.drawRect(24f, 22f, 29f, 43f, paint); canvas.drawRect(35f, 22f, 40f, 43f, paint) }
            "stop" -> canvas.drawRect(24f, 24f, 40f, 40f, paint)
            "previous" -> { canvas.drawRect(21f, 23f, 24f, 42f, paint); polygon(41f, 23f, 25f, 32.5f, 41f, 42f) }
            "next" -> { polygon(23f, 23f, 39f, 32.5f, 23f, 42f); canvas.drawRect(40f, 23f, 43f, 42f, paint) }
            "shuffle" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
                val path = Path().apply {
                    moveTo(20f, 24f); lineTo(26f, 24f); lineTo(38f, 40f); lineTo(44f, 40f)
                    moveTo(20f, 40f); lineTo(26f, 40f); lineTo(38f, 24f); lineTo(44f, 24f)
                }
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
                polygon(41f, 19f, 47f, 24f, 41f, 29f); polygon(41f, 35f, 47f, 40f, 41f, 45f)
            }
            "repeat" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
                canvas.drawArc(RectF(20f, 23f, 44f, 42f), 170f, 155f, false, paint)
                canvas.drawArc(RectF(20f, 23f, 44f, 42f), -10f, 155f, false, paint)
                paint.style = Paint.Style.FILL
                polygon(38f, 20f, 47f, 25f, 39f, 29f); polygon(26f, 45f, 17f, 40f, 25f, 36f)
                if (repeatOne) { paint.textSize = 13f; paint.textAlign = Paint.Align.CENTER; canvas.drawText("1", 32f, 37f, paint) }
            }
        }
        canvas.restoreToCount(checkpoint)
    }
}
