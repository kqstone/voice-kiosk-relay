package com.voicekiosk.app.media

/**
 * Pure Kotlin decision policy governing the visibility of the Home music card.
 *
 * Rules:
 * - `hasActiveSession && title 不为空`: Shows music card (and remains visible during pause).
 * - `!hasNotificationAccess && 已确认的第三方媒体播放 && !isSuppressed`:
 *   Shows concise notification guidance card ("有音频在播放 · 点击开启通知使用权").
 * - During dialogue or TTS cooldown (`isSuppressed`):
 *   Fallback/guidance card MUST NOT be displayed.
 * - Null or inactive without active session: Card is hidden.
 */
object MusicVisibilityPolicy {

    /**
     * Determines whether the music card should be visible on the home screen.
     *
     * @param state the current [MusicState]
     * @param isSuppressed whether fallback detection/display is currently suppressed by dialogue or TTS cooldown
     */
    fun shouldShowCard(state: MusicState?, isSuppressed: Boolean = state?.isSuppressed ?: false): Boolean {
        if (state == null) return false

        // 1. MediaSession is active with a non-blank track title:
        // Always visible (remains visible even when paused to prevent UI jumping).
        if (state.hasActiveSession && state.title.isNotBlank()) {
            return true
        }

        // 2. In dialogue suppression period:
        // Fallback / guidance card must never be shown.
        if (isSuppressed || state.isSuppressed) {
            return false
        }

        // 3. No notification access, but confirmed third-party media is playing:
        // Show simplified guidance card ("有音频在播放 · 点击开启通知使用权").
        val hasConfirmedExternalPlayback = state.confirmedExternalPlayback ||
            (!state.hasActiveSession && state.isPlaying)

        if (!state.hasNotificationAccess && hasConfirmedExternalPlayback) {
            return true
        }

        return false
    }
}
