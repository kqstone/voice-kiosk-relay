package com.voicekiosk.app.data

/**
 * Deterministic color palette for board categories/subjects.
 * Assigns vibrant accent colors by label hash code.
 */
object BoardColorPalette {
    val PALETTE = listOf(
        "#06B6D4", // Cyan
        "#8B5CF6", // Purple
        "#10B981", // Emerald
        "#F59E0B", // Amber
        "#EC4899", // Pink
        "#3B82F6"  // Blue
    )

    fun getColorForLabel(label: String, index: Int): String {
        if (label.isNotEmpty()) {
            val h = kotlin.math.abs(label.hashCode())
            return PALETTE[h % PALETTE.size]
        }
        return PALETTE[index % PALETTE.size]
    }
}
