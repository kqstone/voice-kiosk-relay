package com.voicekiosk.app.media

/**
 * Pure Kotlin detector for debouncing fallback music detection.
 *
 * Rules:
 * - Requires at least 2 consecutive positive polling samples (typically ~4 seconds)
 *   to transition into playing state.
 * - Any negative sample immediately resets the state to stopped (playing = false)
 *   and resets the consecutive sample count.
 * - Dialogue suppression: while dialogue is active or within 3 seconds after TTS / dialogue ends,
 *   positive samples are suppressed and CANNOT change the state to playing.
 */
class FallbackDetector(
    private val requiredConsecutiveSamples: Int = 2,
    private val cooldownDurationMs: Long = 3000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private var consecutiveCount: Int = 0
    private var isPlaying: Boolean = false
    private var isSuppressed: Boolean = false
    private var lastTtsEndTimeMs: Long = 0L

    /**
     * Updates dialogue suppression status.
     * When dialogue ends (transitions from true to false), starts the TTS cooldown period.
     */
    fun setSuppressed(suppressed: Boolean, nowMs: Long = timeProvider()) {
        if (isSuppressed && !suppressed) {
            lastTtsEndTimeMs = nowMs
        }
        isSuppressed = suppressed
        if (suppressed) {
            consecutiveCount = 0
            isPlaying = false
        }
    }

    /**
     * Manually triggers TTS cooldown timestamp.
     */
    fun onTtsFinished(finishTimeMs: Long = timeProvider()) {
        lastTtsEndTimeMs = finishTimeMs
        consecutiveCount = 0
        isPlaying = false
    }

    /**
     * Checks whether suppression is currently active (in dialogue or within cooldown window).
     */
    fun isSuppressionActive(nowMs: Long = timeProvider()): Boolean {
        if (isSuppressed) return true
        if (lastTtsEndTimeMs > 0L && (nowMs - lastTtsEndTimeMs) < cooldownDurationMs) {
            return true
        }
        return false
    }

    /**
     * Evaluates a polling sample.
     *
     * @param rawDetected whether third-party media audio was detected in this sample
     * @param nowMs current timestamp for cooldown comparison
     * @return debounced active playback state (true = playing, false = stopped)
     */
    fun evaluateSample(rawDetected: Boolean, nowMs: Long = timeProvider()): Boolean {
        if (!rawDetected) {
            consecutiveCount = 0
            isPlaying = false
            return false
        }

        if (isSuppressionActive(nowMs)) {
            consecutiveCount = 0
            isPlaying = false
            return false
        }

        consecutiveCount++
        if (consecutiveCount >= requiredConsecutiveSamples) {
            isPlaying = true
        }
        return isPlaying
    }

    fun isPlaying(): Boolean = isPlaying

    fun getConsecutiveCount(): Int = consecutiveCount

    fun reset() {
        consecutiveCount = 0
        isPlaying = false
        isSuppressed = false
        lastTtsEndTimeMs = 0L
    }
}
