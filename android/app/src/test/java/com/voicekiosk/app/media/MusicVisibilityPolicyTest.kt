package com.voicekiosk.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MusicVisibilityPolicy].
 *
 * Requirements:
 * - Active session with title is displayed (playing or paused).
 * - Fallback with empty title is suppressed / not displayed during dialogue.
 * - No notification access with confirmed third-party media playing displays guidance card.
 */
class MusicVisibilityPolicyTest {

    @Test
    fun testActiveSessionWithTitle_showsCard_whenPlaying() {
        val state = MusicState(
            isPlaying = true,
            title = "夜曲",
            artist = "周杰伦",
            hasActiveSession = true
        )
        assertTrue("Active session with title must show card when playing", MusicVisibilityPolicy.shouldShowCard(state))
    }

    @Test
    fun testActiveSessionWithTitle_showsCard_whenPaused_zeroJump() {
        val state = MusicState(
            isPlaying = false,
            title = "夜曲",
            artist = "周杰伦",
            hasActiveSession = true
        )
        assertTrue("Active session with title must remain visible when paused (Zero-Jump invariant)", MusicVisibilityPolicy.shouldShowCard(state))
    }

    @Test
    fun testActiveSessionWithEmptyTitle_doesNotShowCard() {
        val state = MusicState(
            isPlaying = true,
            title = "",
            hasActiveSession = true
        )
        assertFalse("Active session with empty title must not show card", MusicVisibilityPolicy.shouldShowCard(state))
    }

    @Test
    fun testFallbackWithoutTitle_inDialogue_doesNotShowCard() {
        val stateInDialogue = MusicState(
            isPlaying = true,
            title = "",
            hasActiveSession = false,
            isSuppressed = true
        )
        assertFalse("Fallback without title in dialogue must not show card", MusicVisibilityPolicy.shouldShowCard(stateInDialogue))
        assertFalse(
            "Explicit isSuppressed parameter must suppress fallback card",
            MusicVisibilityPolicy.shouldShowCard(stateInDialogue.copy(isSuppressed = false), isSuppressed = true)
        )
    }

    @Test
    fun testNoNotificationAccess_confirmedExternalPlayback_showsGuidanceCard() {
        val guidanceState = MusicState(
            isPlaying = true,
            title = "",
            hasNotificationAccess = false,
            hasActiveSession = false,
            confirmedExternalPlayback = true,
            isSuppressed = false
        )
        assertTrue(
            "Must show guidance card when no notification access, external playback confirmed, and not in dialogue",
            MusicVisibilityPolicy.shouldShowCard(guidanceState)
        )
    }

    @Test
    fun testNoNotificationAccess_confirmedExternalPlayback_inDialogue_doesNotShowCard() {
        val guidanceStateInDialogue = MusicState(
            isPlaying = true,
            title = "",
            hasNotificationAccess = false,
            hasActiveSession = false,
            confirmedExternalPlayback = true,
            isSuppressed = true
        )
        assertFalse(
            "Guidance card must not show during dialogue suppression",
            MusicVisibilityPolicy.shouldShowCard(guidanceStateInDialogue)
        )
        assertFalse(
            "Guidance card must not show when isSuppressed parameter is true",
            MusicVisibilityPolicy.shouldShowCard(guidanceStateInDialogue.copy(isSuppressed = false), isSuppressed = true)
        )
    }

    @Test
    fun testHasNotificationAccess_noSession_doesNotShowCard() {
        // Notification access is granted, but there's no MediaSession (e.g. system sound/game)
        val state = MusicState(
            isPlaying = true,
            title = "",
            hasNotificationAccess = true,
            hasActiveSession = false,
            confirmedExternalPlayback = true,
            isSuppressed = false
        )
        assertFalse(
            "Empty title playback should not show card if notification access is already granted",
            MusicVisibilityPolicy.shouldShowCard(state)
        )
    }

    @Test
    fun testNullState_doesNotShowCard() {
        assertFalse("Null state must not show card", MusicVisibilityPolicy.shouldShowCard(null))
    }

    @Test
    fun testDefaultState_doesNotShowCard() {
        val defaultState = MusicState()
        assertFalse("Default inactive state must not show card", MusicVisibilityPolicy.shouldShowCard(defaultState))
    }
}
