package com.aurum.edge.core

/** Clamp a label into a drawable height, including very small or invalid layouts. */
object ChartViewport {
    fun clampY(value: Float, height: Float, margin: Float): Float {
        if (!height.isFinite() || height <= 0f) return 0f
        val safeMargin = if (margin.isFinite()) margin.coerceIn(0f, height / 2f) else 0f
        val safeValue = if (value.isFinite()) value else height / 2f
        return safeValue.coerceIn(safeMargin, height - safeMargin)
    }
}
