package com.voicekiosk.app.countdown

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Audio feedback for finished countdowns.
 *
 * Decided behavior:
 * - Plays a gentle chime 3 times.
 * - Does NOT loop the alarm ringtone.
 */
class ChimePlayer(
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    companion object {
        private const val TAG = "ChimePlayer"
        const val DEFAULT_CHIME_COUNT = 3
        const val DEFAULT_CHIME_INTERVAL_MS = 450L
    }

    /**
     * Plays a gentle chime [count] times (default 3) spaced by [intervalMs] (default 450ms).
     * Releases ToneGenerator immediately after the third chime completes.
     */
    fun playChimes(count: Int = DEFAULT_CHIME_COUNT, intervalMs: Long = DEFAULT_CHIME_INTERVAL_MS) {
        var toneGen: ToneGenerator? = null
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
        } catch (e: Throwable) {
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
            } catch (e2: Throwable) {
                Log.w(TAG, "Cannot initialize ToneGenerator for gentle chime: ${e2.message}")
            }
        }
        val tg = toneGen ?: return

        for (i in 0 until count) {
            handler.postDelayed({
                try {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error playing chime tone: ${e.message}")
                }
            }, i * intervalMs)
        }

        handler.postDelayed({
            try {
                tg.release()
            } catch (_: Throwable) {}
        }, count * intervalMs + 600L)
    }
}
