package com.voicekiosk.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Kotlin unit tests for [ConversationAudioPolicy].
 * Covers:
 * 1. Originally playing -> paused -> resumes after session ends.
 * 2. Originally not playing -> does not resume after session ends.
 * 3. User says "pause/stop" in session -> cleared pausedByUs -> does not resume.
 * 4. User says "play xxx" in session -> abandons focus immediately, clears pausedByUs, ends session after turn, does not resume.
 * 5. External focus loss (e.g. phone call) -> records state/log, session continues unimpacted.
 * 6. Player ignores focus loss -> proactively pauses at ~300ms.
 * Pure decision functions verified without any Android dependencies.
 */
class ConversationAudioPolicyTest {

    /**
     * Case 1: 本来在放 → 暂停 → 结束后恢复
     */
    @Test
    fun testCase1_initiallyPlaying_paused_resumesOnSessionEnd() {
        val policy = ConversationAudioPolicy()

        // 1. Wake event occurs when music is active
        val wake = policy.onWake(isMusicActive = true)
        assertTrue("Must request exclusive focus on wake", wake.requestExclusiveFocus)
        assertTrue("Must track music was active before wake", wake.wasMusicActive)
        assertTrue("Policy tracks music was active", policy.wasMusicActiveBeforeWake)
        assertTrue("pausedByUs initialized to true", policy.pausedByUs)
        assertTrue("Session active", policy.isSessionActive)
        assertTrue("Holding exclusive focus", policy.hasExclusiveFocus)
        assertFalse("User has not issued music command yet", policy.hasUserMusicCommand)

        // 2. Music player paused (e.g. on focus change or proactive pause)
        assertFalse("No need to proactively pause if music is no longer active", policy.shouldProactivelyPause(isMusicStillActive = false))

        // 3. Normal turns completed with no music commands from user
        val end = policy.onSessionEnded()
        assertTrue("Must abandon audio focus on session end", end.shouldAbandonFocus)
        assertTrue("Must resume playback since it was paused by us", end.shouldResumePlayback)
        assertFalse("Session is now inactive", policy.isSessionActive)
        assertFalse("Focus is no longer held", policy.hasExclusiveFocus)
    }

    /**
     * Case 2: 本来没放 → 结束后不恢复
     */
    @Test
    fun testCase2_initiallyNotPlaying_doesNotResumeOnSessionEnd() {
        val policy = ConversationAudioPolicy()

        // 1. Wake event occurs when music was NOT active
        val wake = policy.onWake(isMusicActive = false)
        assertTrue("Must request exclusive focus on wake", wake.requestExclusiveFocus)
        assertFalse("Music was not active before wake", wake.wasMusicActive)
        assertFalse("Policy tracks music was not active", policy.wasMusicActiveBeforeWake)
        assertFalse("pausedByUs must be false", policy.pausedByUs)

        // Proactive pause check
        assertFalse("Should not proactively pause if music wasn't active", policy.shouldProactivelyPause(isMusicStillActive = false))
        assertFalse("Should not proactively pause even if isMusicStillActive reported true", policy.shouldProactivelyPause(isMusicStillActive = true))

        // 2. Normal conversation finishes
        val end = policy.onSessionEnded()
        assertTrue("Must abandon audio focus on session end", end.shouldAbandonFocus)
        assertFalse("Must NOT resume playback since music was not playing originally", end.shouldResumePlayback)
    }

    /**
     * Case 3: 会话中说「暂停」→ 不恢复
     */
    @Test
    fun testCase3_userSaysPause_clearsPausedByUs_doesNotResume() {
        val policy = ConversationAudioPolicy()

        // 1. Initially playing
        policy.onWake(isMusicActive = true)
        assertTrue("Initially pausedByUs is true", policy.pausedByUs)

        // 2. User says "暂停" / "停止" during conversation
        val userPause = policy.onUserMediaPause()
        assertTrue("User pause clears pausedByUs", userPause.clearPausedByUs)
        assertFalse("Must not resume at end after user pause", userPause.shouldResumeAtEnd)
        assertFalse("Policy pausedByUs is cleared", policy.pausedByUs)
        assertTrue("Policy tracks user music command", policy.hasUserMusicCommand)

        // 3. Conversation finishes
        val end = policy.onSessionEnded()
        assertTrue("Abandons focus on session end", end.shouldAbandonFocus)
        assertFalse("Must NOT resume playback after user explicitly paused", end.shouldResumePlayback)
    }

    /**
     * Case 4: 会话中说「播放 xxx」→ 放弃焦点、不恢复，但新播放保持
     */
    @Test
    fun testCase4_userSaysPlay_abandonsFocusImmediately_endsSessionAfterTurn_doesNotResume() {
        val policy = ConversationAudioPolicy()

        // 1. Initially playing
        policy.onWake(isMusicActive = true)
        assertTrue("Initially pausedByUs is true", policy.pausedByUs)
        assertTrue("Exclusive focus held", policy.hasExclusiveFocus)

        // 2. User says "播放周杰伦的青花瓷"
        val userPlay = policy.onUserMediaPlay()
        assertTrue("Must abandon focus immediately so new player can play", userPlay.shouldAbandonFocusImmediately)
        assertTrue("Must end session after this turn without waiting for follow-up", userPlay.shouldEndSessionAfterTurn)
        assertFalse("Must not resume old music at end", userPlay.shouldResumeAtEnd)

        assertFalse("Policy clears pausedByUs", policy.pausedByUs)
        assertTrue("Policy records user music command", policy.hasUserMusicCommand)
        assertFalse("Exclusive focus immediately marked abandoned", policy.hasExclusiveFocus)
        assertTrue("shouldEndSessionAfterTurn flag is set", policy.shouldEndSessionAfterTurn)

        // 3. Session ends after spoken reply
        val end = policy.onSessionEnded()
        assertFalse("Focus was already abandoned immediately", end.shouldAbandonFocus)
        assertFalse("Must NOT resume old music (new music continues playing)", end.shouldResumePlayback)
    }

    /**
     * Case 5: 焦点被来电抢走（只记状态，会话不受影响，按你设计的纯函数结果断言）
     */
    @Test
    fun testCase5_externalFocusLoss_recordsState_doesNotAbortSession() {
        val policy = ConversationAudioPolicy()
        policy.onWake(isMusicActive = true)
        assertNull("No external focus change initially", policy.lastExternalFocusChange)

        // Incoming call causes permanent or transient loss (-1 / -2)
        val focusLossDecision = policy.onAudioFocusChange(-1)
        assertEquals(-1, focusLossDecision.recordedFocusChange)
        assertFalse("Conversation session must NOT be aborted on external focus loss", focusLossDecision.shouldAbortSession)
        assertEquals(-1, policy.lastExternalFocusChange)
        assertTrue("Session remains active", policy.isSessionActive)

        // Transient loss with duck (-3)
        val duckDecision = policy.onAudioFocusChange(-3)
        assertEquals(-3, duckDecision.recordedFocusChange)
        assertFalse("Session remains active on duck", duckDecision.shouldAbortSession)
        assertEquals(-3, policy.lastExternalFocusChange)
        assertTrue("Session remains active", policy.isSessionActive)
    }

    /**
     * Case 6: 播放器不理会焦点 → 主动 pause
     */
    @Test
    fun testCase6_playerIgnoresFocus_proactivelyPauses() {
        val policy = ConversationAudioPolicy()
        policy.onWake(isMusicActive = true)

        // Around 300ms, player is still playing (ignored transient focus)
        assertTrue("Must proactively pause if music still active", policy.shouldProactivelyPause(isMusicStillActive = true))

        // Proactive pause action executed
        policy.onProactivePauseExecuted()
        assertTrue("pausedByUs remains true after proactive pause", policy.pausedByUs)

        // If player already stopped / paused on transient focus
        assertFalse("No proactive pause if music already stopped", policy.shouldProactivelyPause(isMusicStillActive = false))
    }

    /**
     * Pure static decision helper functions test.
     */
    @Test
    fun testPureStaticDecisionFunctions() {
        // evaluateProactivePause: only true when music was active before wake AND is still active
        assertTrue(ConversationAudioPolicy.evaluateProactivePause(wasMusicActiveBeforeWake = true, isMusicStillActive = true))
        assertFalse(ConversationAudioPolicy.evaluateProactivePause(wasMusicActiveBeforeWake = true, isMusicStillActive = false))
        assertFalse(ConversationAudioPolicy.evaluateProactivePause(wasMusicActiveBeforeWake = false, isMusicStillActive = true))
        assertFalse(ConversationAudioPolicy.evaluateProactivePause(wasMusicActiveBeforeWake = false, isMusicStillActive = false))

        // evaluateResume: only true when pausedByUs == true AND hasUserMusicCommand == false
        assertTrue(ConversationAudioPolicy.evaluateResume(pausedByUs = true, hasUserMusicCommand = false))
        assertFalse(ConversationAudioPolicy.evaluateResume(pausedByUs = true, hasUserMusicCommand = true))
        assertFalse(ConversationAudioPolicy.evaluateResume(pausedByUs = false, hasUserMusicCommand = false))
        assertFalse(ConversationAudioPolicy.evaluateResume(pausedByUs = false, hasUserMusicCommand = true))
    }
}
