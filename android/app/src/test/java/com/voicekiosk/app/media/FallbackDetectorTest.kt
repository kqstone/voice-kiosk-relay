package com.voicekiosk.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FallbackDetector].
 *
 * Requirements:
 * - Single positive sample does not trigger playing.
 * - Consecutive 2 positive samples trigger playing.
 * - Suppressed during dialogue or within 3s after TTS ends: positive samples do not trigger.
 * - Negative sample immediately resets playing to false (stop takes effect immediately).
 */
class FallbackDetectorTest {

    private lateinit var detector: FallbackDetector
    private var currentTimeMs = 1000L

    @Before
    fun setUp() {
        currentTimeMs = 1000L
        detector = FallbackDetector(
            requiredConsecutiveSamples = 2,
            cooldownDurationMs = 3000L,
            timeProvider = { currentTimeMs }
        )
    }

    @Test
    fun testSingleSample_doesNotTrigger() {
        // Initially not playing
        assertFalse(detector.isPlaying())
        assertEquals(0, detector.getConsecutiveCount())

        // 1st sample detected: consecutive count reaches 1 (< 2), returns false
        val result = detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        assertFalse("Single positive sample must not trigger playing state", result)
        assertFalse(detector.isPlaying())
        assertEquals(1, detector.getConsecutiveCount())
    }

    @Test
    fun testConsecutiveTwice_triggersPlaying() {
        // 1st sample
        assertFalse(detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs))
        assertEquals(1, detector.getConsecutiveCount())

        // Advance time to next poll interval (e.g. 2s later)
        currentTimeMs += 2000L

        // 2nd sample: consecutive count reaches 2 (>= 2), returns true
        val result = detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        assertTrue("Consecutive two positive samples must trigger playing state", result)
        assertTrue(detector.isPlaying())
        assertEquals(2, detector.getConsecutiveCount())

        // 3rd consecutive sample keeps playing
        currentTimeMs += 2000L
        assertTrue(detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs))
        assertTrue(detector.isPlaying())
    }

    @Test
    fun testNegativeSample_stopsImmediately() {
        // Trigger playing state with 2 consecutive positive samples
        detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        currentTimeMs += 2000L
        assertTrue(detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs))
        assertTrue(detector.isPlaying())

        // Negative sample arrives: stops immediately
        currentTimeMs += 2000L
        val result = detector.evaluateSample(rawDetected = false, nowMs = currentTimeMs)
        assertFalse("Negative sample must immediately reset playing state to false", result)
        assertFalse(detector.isPlaying())
        assertEquals(0, detector.getConsecutiveCount())
    }

    @Test
    fun testInDialogue_doesNotTriggerPlaying() {
        detector.setSuppressed(true, nowMs = currentTimeMs)
        assertTrue(detector.isSuppressionActive(currentTimeMs))

        // First sample in dialogue
        assertFalse(
            "Sample during dialogue must not trigger playing",
            detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        )
        assertEquals(0, detector.getConsecutiveCount())

        // Second sample in dialogue
        currentTimeMs += 2000L
        assertFalse(
            "Repeated sample during dialogue must still not trigger playing",
            detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        )
        assertEquals(0, detector.getConsecutiveCount())
        assertFalse(detector.isPlaying())
    }

    @Test
    fun testWithinThreeSecondsOfTtsFinish_doesNotTriggerPlaying() {
        // Dialogue ends at t = 2000
        currentTimeMs = 2000L
        detector.setSuppressed(true, nowMs = currentTimeMs)
        detector.setSuppressed(false, nowMs = currentTimeMs)

        // At t = 3000 (1000ms after dialogue/TTS end): within 3s cooldown
        currentTimeMs = 3000L
        assertTrue("Suppression must remain active 1s after TTS", detector.isSuppressionActive(currentTimeMs))
        assertFalse(
            "Sample 1s after TTS must not trigger playing",
            detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        )
        assertEquals(0, detector.getConsecutiveCount())

        // At t = 4500 (2500ms after TTS end): still within 3s cooldown
        currentTimeMs = 4500L
        assertTrue("Suppression must remain active 2.5s after TTS", detector.isSuppressionActive(currentTimeMs))
        assertFalse(
            "Sample 2.5s after TTS must not trigger playing",
            detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        )
        assertEquals(0, detector.getConsecutiveCount())

        // At t = 5001 (3001ms after TTS end): cooldown expired!
        currentTimeMs = 5001L
        assertFalse("Suppression must expire after 3s", detector.isSuppressionActive(currentTimeMs))

        // 1st sample after cooldown: count = 1, does not trigger yet
        assertFalse(
            "1st sample after cooldown does not trigger yet",
            detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        )
        assertEquals(1, detector.getConsecutiveCount())

        // 2nd sample after cooldown: count = 2, triggers!
        currentTimeMs += 2000L
        assertTrue(
            "2nd consecutive sample after cooldown triggers playing state",
            detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)
        )
        assertTrue(detector.isPlaying())
        assertEquals(2, detector.getConsecutiveCount())
    }

    @Test
    fun testOnTtsFinished_directCall_appliesCooldown() {
        currentTimeMs = 10000L
        detector.onTtsFinished(finishTimeMs = currentTimeMs)

        // At t = 11000 (< 3s after finish): suppressed
        currentTimeMs = 11000L
        assertTrue(detector.isSuppressionActive(currentTimeMs))
        assertFalse(detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs))

        // At t = 13001 (> 3s after finish): unsuppressed
        currentTimeMs = 13001L
        assertFalse(detector.isSuppressionActive(currentTimeMs))
        assertFalse(detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)) // 1st
        currentTimeMs += 2000L
        assertTrue(detector.evaluateSample(rawDetected = true, nowMs = currentTimeMs)) // 2nd
    }
}
