package com.infinifolder

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.ResourceEntry
import com.infinifolder.shortcut.ShortcutInfo

/** The browser's file card, shared by the full browser and read-only floating grid.
 * Hosts supply icons and interactions; layout, badges and filename rendering stay identical. */
class FileEntryCard(
    private val context: Context,
    private val entry: ResourceEntry,
    private val asGrid: Boolean,
    private val iconSizeDp: Int,
    private val dark: Boolean,
    private val selectedItem: Boolean = false,
    private val onEmptyAreaLongPress: (() -> Boolean)? = null
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val cardTheme = com.infinifolder.theme.ThemeManager.activeTheme
    private val fg = if (selectedItem) cardTheme.selectionText else cardTheme.textPrimary
    val container: LinearLayout = with(context) {
        val box = LinearLayout(this).apply {
            orientation = if (asGrid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (asGrid) (Gravity.TOP or Gravity.CENTER_HORIZONTAL) else Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            val hPad = if (asGrid) dp(2) else dp(10)
            val vPad = if (asGrid) dp(4) else dp(8)
            setPadding(hPad, vPad, hPad, vPad)
            minimumHeight = dp(64)
            if (!asGrid) {
                if (selectedItem) {
                    background = GradientDrawable().apply {
                        setColor(cardTheme.selectionBackground)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), cardTheme.accent)
                    }
                } else {
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
            } else {
                isLongClickable = onEmptyAreaLongPress != null
                if (onEmptyAreaLongPress != null) setOnLongClickListener { onEmptyAreaLongPress.invoke() }
            }
        }
        box
    }

    fun attachIcon(rawIcon: View): FrameLayout = with(context) {
        val sidePx = dp(if (asGrid) iconSizeDp else 44)
        val isShortcut = ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)
        val iconWidth = if (asGrid) sidePx else dp(52)
        val iconHeight = sidePx
        val icon = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(iconWidth, iconHeight).apply {
                gravity = if (asGrid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                if (asGrid) bottomMargin = dp(4)
            }
            if (asGrid) {
                if (!selectedItem) {
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
            }
            addView(rawIcon, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            if (entry.metadata.recoveryNote != null) {
                addView(ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    contentDescription = "Deleted file"
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (dark) 0xFF795548.toInt() else 0xFF5D4037.toInt())
                    }
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START))
            }
            if (isShortcut || entry.metadata.isSymbolicLink) {
                val isBroken = entry.metadata.isBrokenLink
                val linkBadge = TextView(context).apply {
                    text = if (isBroken) "⊘" else "↗"
                    textSize = 10f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(
                            if (isBroken) 0xFFD32F2F.toInt()
                            else if (dark) 0xFF1976D2.toInt()
                            else 0xFF1565C0.toInt()
                        )
                    }
                    val badgeSize = dp(16)
                    layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                }
                addView(linkBadge)
            }
            if (!AppsUri.isAppsUri(entry.uri) && (!entry.metadata.canRead || !entry.metadata.canWrite)) {
                val isNoRead = !entry.metadata.canRead
                val permBadge = TextView(context).apply {
                    text = if (isNoRead) "×" else "🔒"
                    textSize = 9f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (isNoRead) 0xFFD32F2F.toInt() else (if (dark) 0xFF546E7A.toInt() else 0xFF455A64.toInt()))
                    }
                    val badgeSize = dp(16)
                    layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                        gravity = Gravity.BOTTOM or Gravity.START
                    }
                }
                addView(permBadge)
            }
            if (selectedItem) {
                // A foreground is drawn after every child, including asynchronously replaced artwork.
                foreground = FileSelectionDrawable(context.resources.displayMetrics.density,
                    cardTheme.selectionBackground, cardTheme.accent)
            }
        }

        container.addView(icon)
        icon
    }

    lateinit var nameView: SmartFileNameTextView
        private set
    var metaView: TextView? = null
        private set

    fun addLabels(displayName: String, logicalDirectory: Boolean, maxWidthPx: Int, metadata: String) = with(context) {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (asGrid) {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                gravity = Gravity.CENTER_HORIZONTAL
            } else {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        nameView = SmartFileNameTextView(this).apply {
            textSize = 14f
            setTextColor(fg)
            typeface = if (cardTheme.fontMonospace) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT
            maxAllowedWidthPx = maxWidthPx
            setFileName(displayName, logicalDirectory)
            if (asGrid) {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setPadding(dp(4), dp(2), dp(4), dp(2))
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                if (selectedItem) {
                    background = GradientDrawable().apply {
                        setColor(cardTheme.selectionBackground)
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1), cardTheme.accent)
                    }
                } else {
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
            } else {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
        }
        labels.addView(nameView)
        metaView = if (!asGrid) {
            TextView(this).apply {
                text = metadata
                textSize = 12f
                setTextColor(cardTheme.textSecondary)
            }.also { labels.addView(it) }
        } else null
        container.addView(labels)
    }
}

/** Selection must remain above artwork, badges and asynchronous preview replacements. */
private class FileSelectionDrawable(
    private val density: Float,
    private val tint: Int,
    private val accent: Int
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255

    override fun draw(canvas: android.graphics.Canvas) {
        val stroke = 2 * density
        val box = android.graphics.RectF(bounds).apply { inset(stroke / 2, stroke / 2) }
        val radius = 8 * density
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = (tint and 0x00FFFFFF) or
            ((android.graphics.Color.alpha(tint).coerceIn(51, 85) * opacity / 255) shl 24)
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = accent
        paint.alpha = opacity
        canvas.drawRoundRect(box, radius, radius, paint)
        val badgeRadius = minOf(10 * density, bounds.width() / 3f, bounds.height() / 3f)
        val cx = bounds.right - badgeRadius
        val cy = bounds.bottom - badgeRadius
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = accent
        paint.alpha = opacity
        canvas.drawCircle(cx, cy, badgeRadius, paint)
        paint.color = if (android.graphics.Color.luminance(accent) > .5f)
            android.graphics.Color.BLACK else android.graphics.Color.WHITE
        paint.alpha = opacity
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        val check = android.graphics.Path().apply {
            moveTo(cx - badgeRadius * .45f, cy)
            lineTo(cx - badgeRadius * .1f, cy + badgeRadius * .35f)
            lineTo(cx + badgeRadius * .5f, cy - badgeRadius * .35f)
        }
        canvas.drawPath(check, paint)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
