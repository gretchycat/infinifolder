package com.infinifolder

import android.content.Context
import android.view.View.MeasureSpec
import android.widget.TextView
import com.infinifolder.archive.ArchiveFormats
import java.util.Locale

object FileNameEllipsizer {

    const val ELLIPSIS = "…"
    private const val SHORTCUT_BADGE = " ↗"
    private const val MAX_EXTENSION_LENGTH = 12

    /**
     * Intelligently splits [fullName] into stem and extension so that names too long to fit
     * within [maxWidthPx] on a single line are formatted as `<stem-prefix>…<ext>` (preserving
     * compound extensions like `iso.bz2` or `tar.gz` and any trailing shortcut badge ` ↗`).
     */
    fun ellipsize(
        fullName: String,
        isDirectory: Boolean,
        maxWidthPx: Float,
        measureText: (String) -> Float
    ): String {
        if (fullName.isEmpty() || maxWidthPx <= 0f) return fullName
        if (measureText(fullName) <= maxWidthPx) return fullName

        val hasShortcutBadge = fullName.endsWith(SHORTCUT_BADGE)
        val badge = if (hasShortcutBadge) SHORTCUT_BADGE else ""
        val core = if (hasShortcutBadge) fullName.removeSuffix(SHORTCUT_BADGE) else fullName

        val (stem, ext) = splitStemAndExtension(core, isDirectory)
        val tail = "$ELLIPSIS$ext$badge"

        // If even a 1-char stem + tail doesn't fit, fall back to plain prefix + ellipsis
        val minStemEnd = safeCharPrefixIndex(stem, 1)
        if (stem.isEmpty() || minStemEnd == 0 || measureText(stem.substring(0, minStemEnd) + tail) > maxWidthPx) {
            return ellipsizePlain(core, badge, maxWidthPx, measureText)
        }

        var low = 1
        var high = stem.length
        var best = minStemEnd
        while (low <= high) {
            val mid = (low + high) ushr 1
            val safeMid = safeCharPrefixIndex(stem, mid)
            if (safeMid == 0) {
                low = mid + 1
                continue
            }
            val candidate = stem.substring(0, safeMid).trimEnd() + tail
            if (measureText(candidate) <= maxWidthPx) {
                best = safeMid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return stem.substring(0, best).trimEnd() + tail
    }

    internal fun splitStemAndExtension(coreName: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory || coreName.isEmpty()) return coreName to ""

        val archiveExt = ArchiveFormats.archiveExtension(coreName)
        if (archiveExt.contains('.')) {
            val dotArchiveExt = ".$archiveExt"
            if (coreName.length > dotArchiveExt.length &&
                coreName.lowercase(Locale.ROOT).endsWith(dotArchiveExt)
            ) {
                val splitIdx = coreName.length - dotArchiveExt.length
                val stem = coreName.substring(0, splitIdx)
                val extPreservingCase = coreName.substring(splitIdx + 1)
                if (stem.isNotEmpty()) {
                    return stem to extPreservingCase
                }
            }
        }

        val dotIdx = coreName.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < coreName.length - 1) {
            val ext = coreName.substring(dotIdx + 1)
            if (ext.length in 1..MAX_EXTENSION_LENGTH && !ext.contains(' ')) {
                val stem = coreName.substring(0, dotIdx)
                return stem to ext
            }
        }

        return coreName to ""
    }

    private fun ellipsizePlain(
        core: String,
        badge: String,
        maxWidthPx: Float,
        measureText: (String) -> Float
    ): String {
        val tail = "$ELLIPSIS$badge"
        if (measureText(tail) >= maxWidthPx) return ELLIPSIS
        var low = 1
        var high = core.length
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val safeMid = safeCharPrefixIndex(core, mid)
            if (safeMid == 0) {
                low = mid + 1
                continue
            }
            val candidate = core.substring(0, safeMid).trimEnd() + tail
            if (measureText(candidate) <= maxWidthPx) {
                best = safeMid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (best > 0) core.substring(0, best).trimEnd() + tail else tail
    }

    private fun safeCharPrefixIndex(str: String, index: Int): Int {
        val clamped = index.coerceIn(0, str.length)
        if (clamped in 1 until str.length && Character.isHighSurrogate(str[clamped - 1])) {
            return clamped - 1
        }
        return clamped
    }
}

class SmartFileNameTextView(context: Context) : TextView(context) {

    var rawFileName: String = ""
        private set

    var isDirectoryEntry: Boolean = false
    var maxAllowedWidthPx: Int = 0

    init {
        maxLines = 1
        isSingleLine = true
    }

    fun setFileName(name: String, isDirectory: Boolean = isDirectoryEntry) {
        rawFileName = name
        isDirectoryEntry = isDirectory
        super.setText(name, BufferType.NORMAL)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specMode = MeasureSpec.getMode(widthMeasureSpec)
        val specSize = MeasureSpec.getSize(widthMeasureSpec)
        val effectiveMaxViewWidth = when {
            specMode == MeasureSpec.UNSPECIFIED -> maxAllowedWidthPx
            maxAllowedWidthPx > 0 -> minOf(specSize, maxAllowedWidthPx)
            else -> specSize
        }

        if (effectiveMaxViewWidth > 0 && rawFileName.isNotEmpty()) {
            val availTextWidth = (effectiveMaxViewWidth - compoundPaddingLeft - compoundPaddingRight).toFloat()
            if (availTextWidth > 0f) {
                val fitted = FileNameEllipsizer.ellipsize(rawFileName, isDirectoryEntry, availTextWidth) {
                    paint.measureText(it)
                }
                if (text?.toString() != fitted) {
                    super.setText(fitted, BufferType.NORMAL)
                }
            }
        }

        val constrainedWidthSpec = if (specMode == MeasureSpec.UNSPECIFIED && maxAllowedWidthPx > 0) {
            MeasureSpec.makeMeasureSpec(maxAllowedWidthPx, MeasureSpec.AT_MOST)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(constrainedWidthSpec, heightMeasureSpec)
    }
}
