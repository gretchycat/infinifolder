package com.infinifolder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.ContainerUris
import com.infinifolder.storage.NetworkConnectionStatus
import com.infinifolder.storage.StorageRuntime
import java.util.concurrent.CopyOnWriteArraySet

/** Parsed link destinations shared with icon resolution; never reads or follows a link itself. */
internal object NetworkIconTargets {
    private val targets = android.util.LruCache<String, Uri>(256)
    private val listeners = CopyOnWriteArraySet<(Uri?) -> Unit>()
    private fun key(entry: ResourceEntry) = "${entry.uri}:${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}"
    fun remember(entry: ResourceEntry, target: String) {
        val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return
        val key = key(entry)
        if (targets.get(key) == uri) return
        targets.put(key, uri)
        listeners.forEach { it(entry.uri) }
    }
    fun target(entry: ResourceEntry): Uri? = if (!entry.metadata.isDirectory &&
        ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) targets.get(key(entry)) else entry.uri
    fun clear() { targets.evictAll(); listeners.forEach { it(null) } }
    fun forget(uris: Set<Uri>) {
        val prefixes = uris.map { "$it:" }
        targets.snapshot().keys.filter { key -> prefixes.any(key::startsWith) }.forEach { targets.remove(it) }
        uris.forEach { uri -> listeners.forEach { it(uri) } }
    }
    fun addListener(listener: (Uri?) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Uri?) -> Unit) { listeners.remove(listener) }

    fun connection(context: Context, uri: Uri?): NetworkConnectionStatus.Key? = resource(context, uri)?.key
    fun resource(context: Context, uri: Uri?): NetworkConnectionStatus.Target? {
        var target = uri ?: return null
        repeat(16) {
            if (!ArchiveUri.isArchiveUri(target)) return@repeat
            target = runCatching { ArchiveUri.getSourceUri(target) }.getOrNull() ?: return null
        }
        if (ContainerUris.networkType(target) == null) return null
        // Bind host aliases through the same saved-connection resolver as actual opening.
        val ref = StorageRuntime.get(context).provider.refForUri(target) ?: return null
        return NetworkConnectionStatus.target(ref)
    }
}

/** Non-interactive overlay: status follows the target connection without replacing its artwork. */
internal class NetworkConnectionBadge private constructor(
    context: Context, private val entry: ResourceEntry?, private val target: Uri?
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var connection: NetworkConnectionStatus.Key? = null
    private var state = NetworkConnectionStatus.State.UNKNOWN
    private var blinkStarted = 0L
    private val frame = Runnable { invalidate() }
    private val refresh = Runnable { refreshState() }
    private val statusListener: (NetworkConnectionStatus.Key) -> Unit = { key ->
        if (key == connection) post(refresh)
    }
    private val targetListener: (Uri?) -> Unit = { source ->
        if (source == null || source == entry?.uri) post(refresh)
    }

    init {
        isClickable = false
        isFocusable = false
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun refreshState() {
        if (!isAttachedToWindow) return
        val resource = NetworkIconTargets.resource(context, target ?: entry?.let(NetworkIconTargets::target))
        connection = resource?.key
        val next = resource?.let(NetworkConnectionStatus::state) ?: NetworkConnectionStatus.State.UNKNOWN
        if (next != state) blinkStarted = SystemClock.uptimeMillis()
        state = next
        visibility = if (next == NetworkConnectionStatus.State.CONNECTING ||
            next == NetworkConnectionStatus.State.DISCONNECTED ||
            next == NetworkConnectionStatus.State.RECONNECTING) VISIBLE else INVISIBLE
        contentDescription = when (next) {
            NetworkConnectionStatus.State.CONNECTING -> "Connecting to remote storage"
            NetworkConnectionStatus.State.DISCONNECTED -> if (connection?.let(NetworkConnectionStatus::state) == NetworkConnectionStatus.State.DISCONNECTED)
                "Remote connection disconnected" else "Remote folder or link target unavailable"
            NetworkConnectionStatus.State.RECONNECTING -> "Remote connection reconnecting"
            else -> null
        }
        importantForAccessibility = if (visibility == VISIBLE) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        removeCallbacks(frame)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NetworkConnectionStatus.addListener(statusListener)
        NetworkIconTargets.addListener(targetListener)
        refreshState()
    }
    override fun onDetachedFromWindow() {
        NetworkConnectionStatus.removeListener(statusListener)
        NetworkIconTargets.removeListener(targetListener)
        removeCallbacks(frame); removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(frame)
        if (visibility == VISIBLE) post(refresh)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        removeCallbacks(frame)
        val connecting = state == NetworkConnectionStatus.State.CONNECTING
        val reconnecting = state == NetworkConnectionStatus.State.RECONNECTING
        if (!connecting && !reconnecting && state != NetworkConnectionStatus.State.DISCONNECTED) return
        val save = canvas.save()
        val side = minOf(width, height).toFloat()
        canvas.translate((width - side) / 2, (height - side) / 2)
        canvas.scale(side / 20f, side / 20f)
        paint.style = Paint.Style.FILL
        paint.color = when {
            connecting -> 0xFF1976D2.toInt()
            reconnecting -> 0xFFF57C00.toInt()
            else -> 0xFFD32F2F.toInt()
        }
        paint.alpha = if ((connecting || reconnecting) &&
            (SystemClock.uptimeMillis() - blinkStarted) % 900 >= 450) 210 else 255
        canvas.drawCircle(10f, 10f, 9f, paint)
        if (connecting || reconnecting) {
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = 255
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f
            paint.strokeCap = Paint.Cap.ROUND
            val rotation = (SystemClock.uptimeMillis() % 900).toFloat() * 0.4f
            canvas.drawArc(RectF(5f, 5f, 15f, 15f), rotation, 245f, false, paint)
        } else {
            // A disconnected plug, drawn locally at badge size (no font/emoji dependency).
            paint.color = 0xFFFFFFFF.toInt(); paint.alpha = 255
            canvas.rotate(-35f, 10f, 10f)
            canvas.drawRoundRect(RectF(6.5f, 7.5f, 13.5f, 12.5f), 1f, 1f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(8f, 4.5f, 8f, 7.5f, paint)
            canvas.drawLine(12f, 4.5f, 12f, 7.5f, paint)
            canvas.drawLine(10f, 12.5f, 10f, 14f, paint)
            canvas.drawLine(10f, 16f, 10f, 17f, paint)
        }
        canvas.restoreToCount(save)
        if ((connecting || reconnecting) && isShown && windowVisibility == VISIBLE) postDelayed(frame, 80)
    }
    companion object {
        private fun needsConnectionBadge(entry: ResourceEntry?, target: Uri?): Boolean {
            // Local files cannot have a transport state. Avoid creating one
            // listener View for every item in large local ROM/media folders.
            if (entry != null && ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) return true
            var uri = target ?: entry?.uri ?: return false
            repeat(16) {
                if (!ArchiveUri.isArchiveUri(uri)) return ContainerUris.networkType(uri) != null
                uri = runCatching { ArchiveUri.getSourceUri(uri) }.getOrNull() ?: return false
            }
            return false
        }

        fun attach(frame: FrameLayout, entry: ResourceEntry? = null, target: Uri? = null) {
            // Artwork replacement rebinds pins without accumulating identical overlays/listeners.
            for (index in frame.childCount - 1 downTo 0)
                if (frame.getChildAt(index) is NetworkConnectionBadge) frame.removeViewAt(index)
            if (!needsConnectionBadge(entry, target)) return
            val badge = NetworkConnectionBadge(frame.context, entry, target)
            val side = (16 * frame.resources.displayMetrics.density).toInt()
            frame.addView(badge, FrameLayout.LayoutParams(side, side, Gravity.TOP or Gravity.END))
        }
    }
}
