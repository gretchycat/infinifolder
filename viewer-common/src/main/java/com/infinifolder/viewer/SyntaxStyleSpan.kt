package com.infinifolder.viewer

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.UpdateAppearance
import io.github.gretchycat.infiniscribe.TextStyle

/** Color and decoration spans avoid relaying out large documents during syntax coloring. */
class SyntaxStyleSpan(private val style: TextStyle) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) {
        style.foreground?.let { paint.color = it }
        style.background?.let { paint.bgColor = it }
        style.underline?.let { paint.isUnderlineText = it }
        style.strikethrough?.let { paint.isStrikeThruText = it }
    }
}

/** Only themes that change glyph metrics need a layout-affecting span. */
class SyntaxMetricSpan(private val style: TextStyle, private val metrics: android.util.DisplayMetrics) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        val old = paint.typeface ?: Typeface.MONOSPACE
        val flags = (if (style.bold ?: old.isBold) Typeface.BOLD else 0) or (if (style.italic ?: old.isItalic) Typeface.ITALIC else 0)
        paint.typeface = if (style.fontFamily != null) Typeface.create(style.fontFamily, flags) else Typeface.create(old, flags)
        style.fontSize?.let { paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, it, metrics) }
    }
    override fun updateDrawState(paint: TextPaint) = updateMeasureState(paint)
}
