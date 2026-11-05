package com.infinifolder.viewer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Read-only visual viewer for MindNode Touch XML-plist mind maps. */
class MindNodeTouchViewerActivity : ViewerActivity() {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mindnode-touch-reader") }
    private var mapView: MindNodeTouchMapView? = null
    private lateinit var content: FrameLayout
    private var uri: Uri? = null
    private var displayName = "Mind map"
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: intent.getStringExtra("uri")?.let(Uri::parse) ?: intent.getStringExtra("extra_uri")?.let(Uri::parse)
        displayName = intent.getStringExtra("displayName") ?: intent.getStringExtra("extra_name")
            ?: uri?.lastPathSegment ?: displayName
        buildUi()
        loadMap()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7F5.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()) else null
                view.setPadding(safe?.left ?: insets.systemWindowInsetLeft, safe?.top ?: insets.systemWindowInsetTop,
                    safe?.right ?: insets.systemWindowInsetRight, safe?.bottom ?: insets.systemWindowInsetBottom)
                insets
            }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xFFE8E8E5.toInt())
        }
        toolbar.addView(Button(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = displayName
            textSize = 16f
            setTextColor(0xFF222222.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(Button(this).apply {
            text = "Fit"
            setOnClickListener { mapView?.fitMap() }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        content = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        window.statusBarColor = 0xFFE8E8E5.toInt()
        window.navigationBarColor = 0xFFE8E8E5.toInt()
        if (android.os.Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun loadMap() {
        val source = uri
        if (source == null) {
            showError("No MindNode file was provided.")
            return
        }
        content.addView(android.widget.ProgressBar(this), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        worker.execute {
            val result = runCatching { MindNodeTouchParser.parse(MediaSourceAdapter.openStream(this, source)) }
            runOnUiThread {
                if (closed || isFinishing || isDestroyed) return@runOnUiThread
                content.removeAllViews()
                result.onSuccess { map ->
                    com.infinifolder.storage.DiagnosticLog.record(
                        "mindnode.open.ok", "name=$displayName nodes=${map.nodeCount}"
                    )
                    title = map.title
                    mapView = MindNodeTouchMapView(this, map).also { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }
                }.onFailure {
                    com.infinifolder.storage.DiagnosticLog.record("mindnode.open.failed", displayName, it)
                    showError(it.message ?: "Unable to read this MindNode Touch file.")
                }
            }
        }
    }

    private fun showError(message: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "Could not display this MindNode Touch file.\n\n$message"
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closed = true
        worker.shutdownNow()
        super.onDestroy()
    }
}

private class MindNodeTouchMapView(context: android.content.Context, private val map: MindNodeTouchMap) : View(context) {
    private data class Placed(val node: MindNodeTouchNode, val parent: MindNodeTouchNode?, val width: Float, val height: Float)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt(); textSize = 14f }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val placed = mutableListOf<Placed>()
    private val points = mutableMapOf<MindNodeTouchNode, Placed>()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fitted = false
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val density = resources.displayMetrics.density
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * detector.scaleFactor).coerceIn(.15f, 6f)
            val factor = scale / old
            offsetX = detector.focusX - (detector.focusX - offsetX) * factor
            offsetY = detector.focusY - (detector.focusY - offsetY) * factor
            invalidate()
            return true
        }
    })

    init {
        isClickable = true
        fun add(node: MindNodeTouchNode, parent: MindNodeTouchNode?) {
            val lines = node.title.split('\n')
            val measured = lines.maxOfOrNull { textPaint.measureText(it) } ?: 60f
            val width = max(64f, min(node.maxTitleWidth, measured + 28f))
            val height = max(38f, lines.size * 19f + 18f)
            val item = Placed(node, parent, width, height)
            placed += item
            points[node] = item
            node.children.forEach { add(it, node) }
        }
        map.roots.forEach { add(it, null) }
        contentDescription = "Mind map with ${map.nodeCount} topics. Pinch to zoom and drag to pan."
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0 && h > 0) fitMap()
    }

    fun fitMap() {
        if (width <= 0 || height <= 0 || placed.isEmpty()) return
        val left = placed.minOf { it.node.x - it.width / 2 }
        val top = placed.minOf { it.node.y - it.height / 2 }
        val right = placed.maxOf { it.node.x + it.width / 2 }
        val bottom = placed.maxOf { it.node.y + it.height / 2 }
        val mapW = max(1f, right - left)
        val mapH = max(1f, bottom - top)
        scale = min((width - 40f * density) / mapW, (height - 40f * density) / mapH).coerceIn(.15f, 4f)
        offsetX = (width - mapW * scale) / 2f - left * scale
        offsetY = (height - mapH * scale) / 2f - top * scale
        fitted = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFFFFFFF.toInt())
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        placed.forEach { item ->
            val parent = item.parent?.let(points::get) ?: return@forEach
            val fromX = parent.node.x
            val fromY = parent.node.y
            val toX = item.node.x
            val toY = item.node.y
            linePaint.color = item.node.color
            linePaint.strokeWidth = max(2f, item.node.strokeWidth)
            val path = Path().apply {
                moveTo(fromX, fromY)
                val midX = (fromX + toX) / 2f
                cubicTo(midX, fromY, midX, toY, toX, toY)
            }
            canvas.drawPath(path, linePaint)
        }
        placed.forEach { item ->
            val node = item.node
            val rect = RectF(node.x - item.width / 2f, node.y - item.height / 2f, node.x + item.width / 2f, node.y + item.height / 2f)
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = 0xFFFEFEFD.toInt()
            canvas.drawRoundRect(rect, 7f, 7f, boxPaint)
            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = max(2f, node.strokeWidth)
            boxPaint.color = node.color
            canvas.drawRoundRect(rect, 7f, 7f, boxPaint)
            boxPaint.style = Paint.Style.FILL
            textPaint.textSize = 14f
            val lines = node.title.split('\n')
            val lineHeight = 19f
            val startY = node.y - (lines.size - 1) * lineHeight / 2f + 5f
            lines.forEachIndexed { index, line ->
                val trimmed = android.text.TextUtils.ellipsize(line, android.text.TextPaint(textPaint), item.width - 18f, android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(trimmed, node.x - textPaint.measureText(trimmed) / 2f, startY + index * lineHeight, textPaint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                offsetX += event.x - lastX
                offsetY += event.y - lastY
                lastX = event.x; lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
