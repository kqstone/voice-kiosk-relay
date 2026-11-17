package com.voicekiosk.app.media

/**
 * Pure Kotlin enum representing media actions executed by local tools or voice commands.
 */
enum class MediaExecutedAction {
    PAUSE,
    PLAY
}

/**
 * Pure Kotlin policy class governing conversation audio decisions:
 * - Decides whether to request transient exclusive audio focus.
 * - Decides whether to proactively pause background music (~300ms fallback).
 * - Decides whether to abandon focus immediately when user commands "播放 xxx".
 * - Decides whether to end multi-turn session after a play command without waiting for follow-up.
 * - Decides whether to resume music at the end of the session (only if paused by us and no user music commands).
 * - Handles external focus loss (logs and retains session without aborting).
 *
 * NOTE: Contains ZERO Android framework imports. Fully testable in plain JVM JUnit 4.
 */
class ConversationAudioPolicy {

    /** Whether music was active/playing when the wake event occurred. */
    var wasMusicActiveBeforeWake: Boolean = false
        private set

    /** Whether we paused playback (either via transient focus request or proactive pause). */
    var pausedByUs: Boolean = false
        private set

    /** Whether the user issued a music command (play / pause / stop) during the session. */
    var hasUserMusicCommand: Boolean = false
        private set

    /** Whether the session should end immediately after the current turn (e.g. after "播放 xxx"). */
    var shouldEndSessionAfterTurn: Boolean = false
        private set

    /** Whether audio focus is currently held by the conversation session. */
    var hasExclusiveFocus: Boolean = false
        private set

    /** Last external focus change received (e.g. incoming call / alarm). */
    var lastExternalFocusChange: Int? = null
        private set

    /** Whether the conversation session is currently active. */
    var isSessionActive: Boolean = false
        private set

    /**
     * Called when wake is initiated (voice wake word, UI click, or simulated utterance).
     * Decides whether exclusive focus should be requested.
     */
    fun onWake(isMusicActive: Boolean): WakeDecision {
        isSessionActive = true
        wasMusicActiveBeforeWake = isMusicActive
        pausedByUs = isMusicActive
        hasUserMusicCommand = false
        shouldEndSessionAfterTurn = false
        lastExternalFocusChange = null
        hasExclusiveFocus = true

        return WakeDecision(
            requestExclusiveFocus = true,
            wasMusicActive = isMusicActive
        )
    }

    /**
     * Pure function / decision: around 300ms after focus request, check if proactive pause is needed.
     * True if music was active before wake and is still active (player ignored transient focus).
     */
    fun shouldProactivelyPause(isMusicStillActive: Boolean): Boolean {
        return evaluateProactivePause(wasMusicActiveBeforeWake, isMusicStillActive)
    }

    /**
     * Called when proactive pause is executed (transportControls.pause() / KEYCODE_MEDIA_PAUSE).
     */
    fun onProactivePauseExecuted() {
        if (wasMusicActiveBeforeWake) {
            pausedByUs = true
        }
    }

    /**
     * Called when user issues a media pause / stop command during the session.
     * Clears pausedByUs so playback will NOT be resumed when the session ends.
     */
    fun onUserMediaPause(): UserPauseDecision {
        hasUserMusicCommand = true
        pausedByUs = false
        return UserPauseDecision(
            clearPausedByUs = true,
            shouldResumeAtEnd = false
        )
    }

    /**
     * Called when user issues a media play command ("播放 xxx") during the session.
     * Decisions:
     * - Abandon audio focus immediately so the new player can acquire focus and play.
     * - Clear pausedByUs so we do not resume old music.
     * - End the session after this turn without waiting for follow-up questions.
     */
    fun onUserMediaPlay(): UserPlayDecision {
        hasUserMusicCommand = true
        pausedByUs = false
        hasExclusiveFocus = false
        shouldEndSessionAfterTurn = true
        return UserPlayDecision(
            shouldAbandonFocusImmediately = true,
            shouldEndSessionAfterTurn = true,
            shouldResumeAtEnd = false
        )
    }

    /**
     * Called when external audio focus change occurs (e.g., incoming phone call, alarm).
     * Decision: record state/log, but do NOT abort or impact the conversation session.
     */
    fun onAudioFocusChange(focusChange: Int): FocusChangeDecision {
        lastExternalFocusChange = focusChange
        return FocusChangeDecision(
            recordedFocusChange = focusChange,
            shouldAbortSession = false
        )
    }

    /**
     * Called when the conversation session ends.
     * Decisions:
     * - Abandon audio focus (if still held).
     * - Resume music playback ONLY if pausedByUs is true and no user music command was given.
     */
    fun onSessionEnded(): SessionEndDecision {
        val shouldResume = evaluateResume(pausedByUs, hasUserMusicCommand)
        val shouldAbandon = hasExclusiveFocus
        hasExclusiveFocus = false
        isSessionActive = false

        return SessionEndDecision(
            shouldAbandonFocus = shouldAbandon,
            shouldResumePlayback = shouldResume
        )
    }

    companion object {
        /**
         * Pure function: whether proactive pause is required.
         */
        fun evaluateProactivePause(wasMusicActiveBeforeWake: Boolean, isMusicStillActive: Boolean): Boolean {
            return wasMusicActiveBeforeWake && isMusicStillActive
        }

        /**
         * Pure function: whether playback should resume on session end.
         */
        fun evaluateResume(pausedByUs: Boolean, hasUserMusicCommand: Boolean): Boolean {
            return pausedByUs && !hasUserMusicCommand
        }
    }
}

data class WakeDecision(
    val requestExclusiveFocus: Boolean,
    val wasMusicActive: Boolean
)

data class UserPauseDecision(
    val clearPausedByUs: Boolean,
    val shouldResumeAtEnd: Boolean
)

data class UserPlayDecision(
    val shouldAbandonFocusImmediately: Boolean,
    val shouldEndSessionAfterTurn: Boolean,
    val shouldResumeAtEnd: Boolean
)

data class FocusChangeDecision(
    val recordedFocusChange: Int,
    val shouldAbortSession: Boolean
)

data class SessionEndDecision(
    val shouldAbandonFocus: Boolean,
    val shouldResumePlayback: Boolean
)
