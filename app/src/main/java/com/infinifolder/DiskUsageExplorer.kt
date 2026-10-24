package com.infinifolder

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.theme.ThemeManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Provider-neutral, read-only metadata scan. Actions go back through the browser's normal workflows. */
class DiskUsageExplorer(
    private val activity: Activity,
    private val context: Context,
    private val provider: StorageProvider,
    private var rootEntry: ResourceEntry,
    private val browse: (ResourceEntry) -> Unit,
    private val move: (ResourceEntry) -> Unit,
    private val delete: (ResourceEntry, () -> Unit) -> Unit
) {
    private class Node(val entry: ResourceEntry, val parent: Node? = null) {
        val children = mutableListOf<Node>()
        var aggregate = false
        var label = entry.metadata.name
        var bytes = if (entry.metadata.isDirectory || entry.metadata.isSymbolicLink) 0L else entry.metadata.sizeBytes?.coerceAtLeast(0) ?: 0L
        var incomplete = !entry.metadata.isDirectory && !entry.metadata.isSymbolicLink && entry.metadata.sizeBytes == null
    }
    private val cancelled = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var scanGeneration = 0
    private var backRegistration: java.io.Closeable? = null
    private var root: Node? = null
    private var focus: Node? = null
    private var returning = false
    private var scanning = false
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val theme get() = ThemeManager.activeTheme
    private lateinit var dialog: Dialog
    private lateinit var graph: UsageMap
    private lateinit var status: TextView
    private lateinit var path: TextView
    private lateinit var up: Button

    fun show() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.background)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Button(context).apply {
            text = "← Back"
            contentDescription = "Back to files in the displayed folder"
            setOnClickListener { returnToFolder() }
        }, LinearLayout.LayoutParams(-2, -2))
        heading.addView(TextView(context).apply {
            text = "Disk usage explorer"; textSize = 20f; setTextColor(theme.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(heading)
        path = TextView(context).apply { text = rootEntry.metadata.name; textSize = 15f; setTextColor(theme.textPrimary); setPadding(0, dp(8), 0, dp(4)) }
        column.addView(path)
        status = TextView(context).apply { text = "Scanning folders…"; textSize = 13f; setTextColor(theme.textSecondary); minLines = 2 }
        column.addView(status)
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        up = Button(context).apply {
            text = "↑ Parent folder"; isEnabled = true
            setOnClickListener { goToParent() }
        }
        controls.addView(up, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(context).apply { text = "Browse folder"; setOnClickListener { returnToFolder() } }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(controls)
        graph = UsageMap(context)
        column.addView(graph, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(TextView(context).apply {
            text = "Current folder only. Folder areas include their contents. Links are not followed. Tap to enter the next folder level; hold for actions. Back returns to files in this folder; Parent folder zooms out."
            textSize = 12f; setTextColor(theme.textSecondary); setPadding(0, dp(8), 0, 0)
        })
        dialog = object : Dialog(context) {
            @Deprecated("Handled for legacy Back")
            override fun onBackPressed() { returnToFolder() }
        }
        dialog.setContentView(column)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) returnToFolder()
                true
            } else false
        }
        dialog.setOnDismissListener {
            cancelled.set(true); worker?.interrupt(); handler.removeCallbacksAndMessages(null)
            graph.stopAnimation(); backRegistration?.close(); backRegistration = null
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { returnToFolder() }
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backRegistration = java.io.Closeable { dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        startScan()
    }

    fun close() {
        returning = true
        if (::dialog.isInitialized) dialog.dismiss()
    }

    private fun returnToFolder() {
        if (returning) return
        returning = true
        val entry = focus?.entry ?: rootEntry
        DiagnosticLog.record("usage.back", "folder=${entry.uri} scanning=$scanning")
        dialog.dismiss()
        browse(entry)
    }

    private fun goToParent() {
        focus?.parent?.let { showNode(it); return }
        val current = focus?.entry ?: rootEntry
        val generation = scanGeneration
        up.isEnabled = false
        Thread({
            val result = runCatching {
                val ref = when (val parent = provider.parent(current.ref)) {
                    is ProviderResult.Success -> parent.value
                    is ProviderResult.Failure -> throw java.io.IOException(parent.message, parent.cause)
                    else -> throw java.io.IOException("Parent navigation is unavailable")
                }
                ref?.let {
                    when (val entry = provider.stat(it)) {
                        is ProviderResult.Success -> entry.value
                        is ProviderResult.Failure -> throw java.io.IOException(entry.message, entry.cause)
                        else -> throw java.io.IOException("Cannot inspect parent folder")
                    }
                }
            }
            handler.post {
                if (!cancelled.get() && !activity.isDestroyed && generation == scanGeneration) {
                    up.isEnabled = true
                    result.onSuccess { parent ->
                        if (parent == null) {
                            up.isEnabled = false
                            Toast.makeText(context, "Already at the storage root", Toast.LENGTH_SHORT).show()
                        } else {
                            rootEntry = parent
                            focus = null
                            root = null
                            graph.reset()
                            path.text = parent.metadata.name
                            startScan()
                        }
                    }.onFailure { error ->
                        Toast.makeText(context, error.message ?: "Cannot open parent folder", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "DiskUsageParent").start()
    }

    private fun startScan() {
        if (cancelled.get()) return
        val generation = ++scanGeneration
        val preferred = focus?.entry?.ref
        scanning = true
        graph.isEnabled = false
        status.text = if (root == null) "Scanning folders…" else "Refreshing disk usage…"
        worker?.interrupt()
        worker = Thread({ scan(generation, preferred) }, "DiskUsageScan").apply { start() }
    }

    private fun scan(generation: Int, preferred: ResourceRef?) {
        val started = SystemClock.uptimeMillis()
        val surviving = (provider.stat(rootEntry.ref) as? ProviderResult.Success)?.value
        if (surviving == null) {
            val parent = (provider.parent(rootEntry.ref) as? ProviderResult.Success)?.value
            val parentEntry = parent?.let { (provider.stat(it) as? ProviderResult.Success)?.value }
            if (parentEntry != null) rootEntry = parentEntry
        }
        val top = Node(rootEntry)
        val visited = mutableSetOf<ResourceRef>()
        val queue = java.util.ArrayDeque<Pair<Node, Int>>()
        val nodes = mutableListOf(top)
        queue.add(top to 0)
        var folders = 0
        var failures = 0
        var lastUpdate = 0L
        val previewPending = AtomicBoolean(false)
        fun publishPreview(message: String) {
            if (!previewPending.compareAndSet(false, true)) return
            // Copy on the scanner thread: the UI must never traverse a tree being mutated.
            val copies = java.util.IdentityHashMap<Node, Node>()
            val snapshot = nodes.map { source ->
                Node(source.entry, source.parent?.let { copies[it] }).also { copy ->
                    copy.bytes = source.bytes
                    copy.incomplete = source.incomplete
                    copies[source] = copy
                    copy.parent?.children?.add(copy)
                }
            }
            for (copy in snapshot.asReversed()) {
                copy.children.sortByDescending { it.bytes }
                copy.parent?.let { parent ->
                    parent.bytes = if (Long.MAX_VALUE - parent.bytes < copy.bytes) Long.MAX_VALUE else parent.bytes + copy.bytes
                    parent.incomplete = parent.incomplete || copy.incomplete
                }
            }
            handler.post {
                try {
                    if (!cancelled.get() && !activity.isDestroyed && generation == scanGeneration) {
                        val wanted = focus?.entry?.ref ?: preferred
                        root = snapshot.first()
                        graph.isEnabled = true
                        graph.reset()
                        showNode(snapshot.firstOrNull { it.entry.ref == wanted } ?: snapshot.first())
                        status.text = "$message · " + android.text.format.Formatter.formatShortFileSize(activity, snapshot.first().bytes) +
                            " found so far (partial)"
                    }
                } finally { previewPending.set(false) }
            }
        }
        try {
            while (queue.isNotEmpty() && !cancelled.get() && generation == scanGeneration) {
                if (Thread.currentThread().isInterrupted) return
                if (nodes.size >= 200_000) {
                    queue.forEach { it.first.incomplete = true }
                    failures += queue.size
                    queue.clear()
                    break
                }
                val (node, depth) = queue.removeFirst()
                if (depth >= 128 || !visited.add(node.entry.ref)) {
                    node.incomplete = true; failures++; continue
                }
                folders++
                when (val listed = provider.list(node.entry.ref)) {
                    is ProviderResult.Success -> {
                        if (listed.isLoading || listed.notice != null) { node.incomplete = true; failures++ }
                        for (entry in listed.value) {
                            if (cancelled.get()) return
                            if (nodes.size >= 200_000) { node.incomplete = true; failures++; break }
                            val child = Node(entry, node)
                            nodes.add(child); node.children.add(child)
                            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) queue.add(child to depth + 1)
                        }
                    }
                    is ProviderResult.Failure -> {
                        node.incomplete = true; failures++
                        DiagnosticLog.record("usage.scan.unreadable", node.entry.metadata.name, listed.cause)
                    }
                    else -> { node.incomplete = true; failures++ }
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastUpdate > 750L) {
                    lastUpdate = now
                    val message = "Scanning… $folders folders · ${nodes.size - 1} items" + if (failures > 0) " · $failures incomplete folders" else ""
                    publishPreview(message)
                }
            }
            if (cancelled.get() || generation != scanGeneration) return
            // Children are always created after parents; reverse accumulation avoids recursive stack growth.
            for (node in nodes.asReversed()) {
                node.children.sortByDescending { it.bytes }
                node.parent?.let { parent ->
                    parent.bytes = if (Long.MAX_VALUE - parent.bytes < node.bytes) Long.MAX_VALUE else parent.bytes + node.bytes
                    parent.incomplete = parent.incomplete || node.incomplete
                }
            }
            DiagnosticLog.record("usage.scan.complete", "folders=$folders items=${nodes.size - 1} bytes=${top.bytes} incomplete=${top.incomplete} elapsedMs=${SystemClock.uptimeMillis() - started}")
            handler.post {
                if (!cancelled.get() && !activity.isDestroyed && generation == scanGeneration) {
                    val wanted = focus?.entry?.ref ?: preferred
                    root = top
                    scanning = false
                    graph.reset()
                    graph.isEnabled = true
                    showNode(nodes.firstOrNull { it.entry.ref == wanted } ?: top)
                }
            }
        } catch (error: Exception) {
            if (!cancelled.get()) {
                DiagnosticLog.record("usage.scan.failed", rootEntry.metadata.name, error)
                handler.post {
                    if (!cancelled.get() && generation == scanGeneration) {
                        scanning = false
                        status.text = "Scan failed: ${error.message} · displayed sizes are partial"
                    }
                }
            }
        }
    }

    private fun showNode(node: Node) {
        focus = node
        path.text = generateSequence(node) { it.parent }.toList().asReversed().joinToString(" / ") { it.label }
        status.text = (if (scanning) "Scanning… partial · " else "") +
            "${android.text.format.Formatter.formatShortFileSize(activity, node.bytes)} · ${node.children.size} direct items" +
            if (node.incomplete) " · INCOMPLETE: unreadable folders, missing sizes, or scan limits; totals are a lower bound" else ""
        up.isEnabled = true
        graph.zoomTo(node)
    }

    private fun actions(node: Node) {
        val entry = node.entry
        val choices = mutableListOf<String>()
        if (entry.metadata.isDirectory) choices.add("Browse")
        if (!node.aggregate && !scanning) {
            if (!entry.metadata.isSymbolicLink) choices.add("Move…")
            choices.add("Delete…")
        }
        AlertDialog.Builder(context).setTitle(node.label).setItems(choices.toTypedArray()) { _, index ->
            when (choices[index]) {
                "Browse" -> { dialog.dismiss(); browse(entry) }
                "Move…" -> { dialog.dismiss(); move(entry) }
                "Delete…" -> { delete(entry) { if (!cancelled.get()) startScan() } }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private data class Tile(val node: Node, val rect: RectF, val level: Int)
    private data class Geometry(val tiles: List<Tile>, val bounds: java.util.IdentityHashMap<Node, RectF>)

    private inner class UsageMap(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var geometry = Geometry(emptyList(), java.util.IdentityHashMap())
        private var transitionTiles: List<Tile>? = null
        private val viewport = RectF(0f, 0f, 1f, 1f)
        private var animator: ValueAnimator? = null
        private var shown: Node? = null
        private var selected: Node? = null
        private val overflowGroups = java.util.IdentityHashMap<Node, Node>()
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (animator?.isRunning == true) return true
                // Each region represents an immediate child of the displayed folder.
                val node = geometry.tiles.firstOrNull {
                    it.level == 1 && project(it.rect).contains(event.x, event.y)
                }?.node ?: return true
                if (node.entry.metadata.isDirectory && !node.entry.metadata.isSymbolicLink) showNode(node)
                else {
                    selected = node
                    status.text = "${node.label} · ${android.text.format.Formatter.formatShortFileSize(activity, node.bytes)}"
                    invalidate()
                }
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                if (animator?.isRunning != true) (hit(event.x, event.y) ?: focus)?.let(::actions)
            }
        })
        init { isClickable = true; contentDescription = "Disk usage treemap. Tap a region to enter one folder level. Hold for browse, move or delete." }

        // Show only immediate children; descendants contribute size but no nested rectangles.
        // Large child sets get an explicit, zoomable
        // 'Other items' group, preserving their combined area without drawing 200k cells.
        private fun layoutNodes(top: Node): Geometry {
            val tiles = mutableListOf<Tile>()
            val bounds = java.util.IdentityHashMap<Node, RectF>()
            fun layout(node: Node, rect: RectF, level: Int) {
                bounds[node] = RectF(rect)
                tiles.add(Tile(node, RectF(rect), level))
                if (level >= 1) return
                val measurable = node.children.filter { it.bytes > 0 }
                val children = if (measurable.size > 128) {
                    val overflow = overflowGroups[node] ?: Node(node.entry, node).apply {
                        aggregate = true
                        label = "Other ${measurable.size - 127} items"
                        children.addAll(measurable.drop(127))
                        bytes = children.sumOf { it.bytes.toDouble() }.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
                        incomplete = node.incomplete
                        overflowGroups[node] = this
                    }
                    (measurable.take(127) + overflow).sortedByDescending { it.bytes }
                } else measurable
                if (children.isEmpty()) return
                val inner = RectF(rect)
                if (level > 0) { inner.inset(rect.width() * .005f, rect.height() * .005f); inner.top += rect.height() * .08f }
                if (inner.width() <= 0f || inner.height() <= 0f) return
                val sums = DoubleArray(children.size + 1)
                for (index in children.indices) sums[index + 1] = sums[index] + children[index].bytes.toDouble()
                fun partition(start: Int, end: Int, area: RectF) {
                    if (start >= end) return
                    if (end - start == 1) { layout(children[start], area, level + 1); return }
                    val total = sums[end] - sums[start]
                    var split = start + 1
                    while (split < end - 1 && sums[split] - sums[start] < total / 2) split++
                    val fraction = ((sums[split] - sums[start]) / total).toFloat().coerceIn(.000001f, .999999f)
                    if (area.width() >= area.height()) {
                        val edge = area.left + area.width() * fraction
                        partition(start, split, RectF(area.left, area.top, edge, area.bottom))
                        partition(split, end, RectF(edge, area.top, area.right, area.bottom))
                    } else {
                        val edge = area.top + area.height() * fraction
                        partition(start, split, RectF(area.left, area.top, area.right, edge))
                        partition(split, end, RectF(area.left, edge, area.right, area.bottom))
                    }
                }
                partition(0, children.size, inner)
            }
            layout(top, RectF(0f, 0f, 1f, 1f), 0)
            return Geometry(tiles, bounds)
        }

        private fun mapped(tiles: List<Tile>, area: RectF): List<Tile> = tiles.map { tile ->
            val rect = tile.rect
            tile.copy(rect = RectF(area.left + rect.left * area.width(), area.top + rect.top * area.height(),
                area.left + rect.right * area.width(), area.top + rect.bottom * area.height()))
        }

        fun zoomTo(node: Node) {
            animator?.end()
            val next = layoutNodes(node)
            val previous = shown
            val into = geometry.bounds[node]?.takeIf { node !== previous && it.width() > 0 && it.height() > 0 }
            val outOf = previous?.let { next.bounds[it] }?.takeIf { it.width() > 0 && it.height() > 0 }
            val from: RectF
            val to: RectF
            when {
                into != null -> {
                    from = RectF(0f, 0f, 1f, 1f); to = RectF(into)
                    transitionTiles = geometry.tiles + mapped(next.tiles, into)
                }
                outOf != null -> {
                    from = RectF(outOf); to = RectF(0f, 0f, 1f, 1f)
                    transitionTiles = next.tiles + mapped(geometry.tiles, outOf)
                }
                else -> {
                    geometry = next; shown = node; viewport.set(0f, 0f, 1f, 1f); invalidate(); return
                }
            }
            up.isEnabled = false
            viewport.set(from)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450L; interpolator = DecelerateInterpolator()
                addUpdateListener { value ->
                    val t = value.animatedValue as Float
                    viewport.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                        from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        geometry = next; shown = node; transitionTiles = null
                        viewport.set(0f, 0f, 1f, 1f); up.isEnabled = true; invalidate()
                    }
                })
                start()
            }
        }
        fun reset() {
            animator?.end(); transitionTiles = null; shown = null; selected = null
            geometry = Geometry(emptyList(), java.util.IdentityHashMap()); overflowGroups.clear()
            viewport.set(0f, 0f, 1f, 1f)
        }
        fun stopAnimation() { animator?.cancel() }
        private fun project(rect: RectF) = RectF(
            (rect.left - viewport.left) / viewport.width() * width,
            (rect.top - viewport.top) / viewport.height() * height,
            (rect.right - viewport.left) / viewport.width() * width,
            (rect.bottom - viewport.top) / viewport.height() * height)
        private fun hit(x: Float, y: Float): Node? = geometry.tiles.asReversed().firstOrNull {
            it.node !== shown && project(it.rect).contains(x, y)
        }?.node
        override fun onTouchEvent(event: MotionEvent): Boolean = isEnabled && (gestures.onTouchEvent(event) || super.onTouchEvent(event))
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(theme.surface)
            if (focus?.bytes == 0L) {
                paint.color = theme.textPrimary; paint.textSize = 15f * density
                canvas.drawText("No measurable file data", dp(12).toFloat(), dp(30).toFloat(), paint)
                return
            }
            for (tile in transitionTiles ?: geometry.tiles) {
                val area = project(tile.rect)
                if (area.right < 0 || area.bottom < 0 || area.left > width || area.top > height || area.width() < 1 || area.height() < 1) continue
                val hue = (tile.node.label.hashCode().toLong().and(0x7fffffff) % 360).toFloat()
                paint.style = Paint.Style.FILL
                paint.color = Color.HSVToColor(floatArrayOf(hue, .48f, if (theme.isDark) .55f else .85f))
                canvas.drawRect(area, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = if (selected === tile.node) 3f * density else density
                paint.color = if (selected === tile.node) theme.textPrimary else theme.background
                canvas.drawRect(area, paint); paint.style = Paint.Style.FILL
                if (area.width() > dp(46) && area.height() > dp(18)) {
                    canvas.save(); canvas.clipRect(area)
                    paint.color = Color.WHITE; paint.textSize = 12f * density
                    val text = tile.node.label + if (tile.node.entry.metadata.isDirectory) "/" else ""
                    val count = paint.breakText(text, true, (area.width() - dp(8)).coerceAtLeast(0f), null)
                    canvas.drawText(text.take(count), area.left + dp(4), area.top + dp(14), paint)
                    canvas.restore()
                }
            }
            // Draw directory frames last so descendants cannot paint over them.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = theme.textHighlight
            for (tile in transitionTiles ?: geometry.tiles) {
                if (!tile.node.entry.metadata.isDirectory) continue
                val area = project(tile.rect)
                if (area.width() < dp(8) || area.height() < dp(8)) continue
                area.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
                canvas.drawRect(area, paint)
            }
            paint.style = Paint.Style.FILL
        }
    }
}
