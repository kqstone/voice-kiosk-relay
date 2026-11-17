package com.voicekiosk.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android coordinator bridging audio focus requests, proactive background music pause/resume,
 * and the pure [ConversationAudioPolicy].
 *
 * Rules:
 * - On wake (before beep): request AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE with USAGE_ASSISTANT + CONTENT_TYPE_SPEECH.
 * - Records whether music was active before wake (MusicStateManager or AudioManager.isMusicActive).
 * - Fallback proactive pause: around 300ms after request, if still playing, pauses via MusicStateManager (transportControls.pause -> KEYCODE_MEDIA_PAUSE).
 * - On session end: abandon exclusive audio focus. Resumes playback only if pausedByUs == true and no user music commands were given.
 * - LocalToolExecutor notifications:
 *   - "pause/stop": clears pausedByUs, will not resume at end.
 *   - "play xxx": abandons focus immediately and clears pausedByUs, session ends after this turn.
 * - External focus loss (call/alarm): logged, conversation not aborted.
 * - Ducking (speakNonBlocking / timer announcements): uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK without pausing.
 */
class AudioFocusCoordinator(
    private val context: Context,
    private val musicStateManager: MusicStateManager = MusicStateManager.getInstance(context),
    val policy: ConversationAudioPolicy = ConversationAudioPolicy()
) {

    companion object {
        private const val TAG = "AudioFocusCoordinator"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var exclusiveFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var duckFocusRequest: AudioFocusRequest? = null

    /**
     * Called on wake initiation (wake word detected, UI click wake, or simulated utterance).
     * Must be called before playing prompt beep.
     */
    @Synchronized
    fun onWakeRequested() {
        val wasActive = musicStateManager.isMusicActive()
        val decision = policy.onWake(wasActive)

        if (decision.requestExclusiveFocus) {
            requestExclusiveFocus()
        }
    }

    private fun requestExclusiveFocus() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.i(TAG, "Audio focus changed: $focusChange (logged, conversation unaffected)")
                    policy.onAudioFocusChange(focusChange)
                }
                .build()

            exclusiveFocusRequest = request
            val res = audioManager.requestAudioFocus(request)
            Log.i(TAG, "Requested AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: result=$res (wasPlaying=${policy.wasMusicActiveBeforeWake})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request exclusive audio focus", e)
        }
    }

    /**
     * Checks if proactive pause is needed (~300ms after wake).
     * If the background player ignored transient focus and is still active, proactively pauses it.
     */
    @Synchronized
    fun checkAndPerformProactivePause(): Boolean {
        val stillPlaying = musicStateManager.isMusicActive()
        if (policy.shouldProactivelyPause(stillPlaying)) {
            Log.i(TAG, "Background music still active 300ms after focus request; performing proactive pause")
            musicStateManager.pause()
            policy.onProactivePauseExecuted()
            return true
        }
        return false
    }

    /**
     * Notification from LocalToolExecutor when user issued a media pause or stop command.
     */
    @Synchronized
    fun onUserPauseCommand() {
        val decision = policy.onUserMediaPause()
        Log.i(TAG, "User media pause command: clearPausedByUs=${decision.clearPausedByUs}")
    }

    /**
     * Notification from LocalToolExecutor when user issued a media play command ("播放 xxx").
     * Abandons focus immediately so new playback can start, and flags session to end after turn.
     */
    @Synchronized
    fun onUserPlayCommand() {
        val decision = policy.onUserMediaPlay()
        Log.i(TAG, "User media play command: abandonFocus=${decision.shouldAbandonFocusImmediately}, endSession=${decision.shouldEndSessionAfterTurn}")
        if (decision.shouldAbandonFocusImmediately) {
            abandonExclusiveFocusInternal()
        }
    }

    /**
     * Whether the multi-turn session should conclude immediately after the current turn.
     */
    fun shouldEndSessionAfterTurn(): Boolean = policy.shouldEndSessionAfterTurn

    /**
     * Called when the conversation session concludes (before re-arming KWS).
     * Abandons exclusive focus and restores playback only if pausedByUs == true and no user music commands.
     */
    @Synchronized
    fun onSessionEnded() {
        val decision = policy.onSessionEnded()
        if (decision.shouldAbandonFocus) {
            abandonExclusiveFocusInternal()
        }
        if (decision.shouldResumePlayback) {
            Log.i(TAG, "Resuming background playback since it was paused by us on wake")
            musicStateManager.play()
        } else {
            Log.i(TAG, "Session ended; not resuming playback (pausedByUs=${policy.pausedByUs}, hasUserMusicCommand=${policy.hasUserMusicCommand})")
        }
    }

    @Synchronized
    private fun abandonExclusiveFocusInternal() {
        val req = exclusiveFocusRequest ?: return
        try {
            val res = audioManager.abandonAudioFocusRequest(req)
            Log.i(TAG, "Abandoned exclusive audio focus: result=$res")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning exclusive audio focus: ${e.message}")
        } finally {
            exclusiveFocusRequest = null
        }
    }

    fun wasPlayingBeforeWake(): Boolean = policy.wasMusicActiveBeforeWake

    fun isMusicActive(): Boolean = musicStateManager.isMusicActive()

    // --- Ducking for non-blocking speech & timer announcements ---

    @Synchronized
    fun requestDuckFocus(): AudioFocusRequest? {
        if (policy.isSessionActive) {
            // Already holding exclusive focus or active in conversation
            return null
        }
        return try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .build()

            val res = audioManager.requestAudioFocus(req)
            Log.i(TAG, "Requested AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: result=$res")
            duckFocusRequest = req
            req
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request duck audio focus: ${e.message}")
            null
        }
    }

    @Synchronized
    fun abandonDuckFocus(request: AudioFocusRequest? = duckFocusRequest) {
        val target = request ?: duckFocusRequest ?: return
        try {
            val res = audioManager.abandonAudioFocusRequest(target)
            Log.i(TAG, "Abandoned duck audio focus: result=$res")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning duck audio focus: ${e.message}")
        } finally {
            if (duckFocusRequest === target) {
                duckFocusRequest = null
            }
        }
    }

    /**
     * Executes non-blocking speech block under AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
     * restoring normal media volume automatically after [durationMs].
     */
    fun duckSpeech(durationMs: Long = 5000L, block: () -> Unit) {
        val req = requestDuckFocus()
        try {
            block()
        } finally {
            if (req != null) {
                mainHandler.postDelayed({
                    abandonDuckFocus(req)
                }, durationMs)
            }
        }
    }
}
