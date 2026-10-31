package com.infinifolder.viewer

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import io.github.gretchycat.infiniscribe.TextStyle

/** One owned span keeps syntax separate from selection/search spans and source characters. */
class SyntaxStyleSpan(private val style: TextStyle, private val metrics: android.util.DisplayMetrics) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        val old = paint.typeface ?: Typeface.MONOSPACE
        val flags = (if (style.bold ?: old.isBold) Typeface.BOLD else 0) or (if (style.italic ?: old.isItalic) Typeface.ITALIC else 0)
        paint.typeface = if (style.fontFamily != null) Typeface.create(style.fontFamily, flags) else Typeface.create(old, flags)
        style.fontSize?.let { paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, it, metrics) }
    }
    override fun updateDrawState(paint: TextPaint) {
        updateMeasureState(paint)
        style.foreground?.let { paint.color = it }; style.background?.let { paint.bgColor = it }
        style.underline?.let { paint.isUnderlineText = it }; style.strikethrough?.let { paint.isStrikeThruText = it }
    }
}
