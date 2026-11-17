package com.voicekiosk.app.countdown

import java.util.Locale
import java.util.UUID

/**
 * Representation of an active or completed countdown timer.
 */
data class CountdownItem(
    val id: String = UUID.randomUUID().toString(),
    val label: String? = null,
    val durationSeconds: Int,
    val startTimeMillis: Long = System.currentTimeMillis(),
    val targetTimeMillis: Long = startTimeMillis + durationSeconds * 1000L
) {
    /**
     * Calculates remaining seconds from now, rounding up so that partial seconds show as remaining.
     */
    fun remainingSeconds(nowMillis: Long = System.currentTimeMillis()): Int {
        val remainingMillis = targetTimeMillis - nowMillis
        if (remainingMillis <= 0L) return 0
        return ((remainingMillis + 999L) / 1000L).toInt()
    }

    /**
     * Remaining fraction of initial duration: 1.0f at start down to 0.0f at expiry.
     */
    fun remainingFraction(nowMillis: Long = System.currentTimeMillis()): Float {
        if (durationSeconds <= 0) return 0f
        val remMs = (targetTimeMillis - nowMillis).coerceAtLeast(0L)
        val totalMs = durationSeconds * 1000L
        return (remMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    }

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= targetTimeMillis

    companion object {
        /**
         * Formats seconds into mm:ss (or hh:mm:ss when >= 1 hour).
         */
        fun formatTime(seconds: Int): String {
            val s = seconds.coerceAtLeast(0)
            val hours = s / 3600
            val minutes = (s % 3600) / 60
            val secs = s % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, secs)
            }
        }
    }
}
