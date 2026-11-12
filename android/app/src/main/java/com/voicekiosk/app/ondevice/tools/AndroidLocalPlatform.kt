package com.voicekiosk.app.ondevice.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import java.time.ZoneId

/**
 * Android side effects for [LocalToolExecutor].
 *
 * - Volume: STREAM_MUSIC (the stream TTS and third-party players use), with FLAG_SHOW_UI
 *   so the system volume bar confirms the change.
 * - Media keys: [AudioManager.dispatchMediaKeyEvent] goes to the active media session
 *   (or the last one that played), so it controls whatever third-party player is in use.
 * - Play-from-search: [MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH] sent explicitly
 *   to the first player that handles it (no chooser on a kiosk). Only third-party
 *   players; whether a search actually plays is up to the player.
 * - Timers: in-app, on the main looper; [onTimerFired] announces them (the voice
 *   service speaks and shows them). They do not survive the service being killed.
 */
class AndroidLocalPlatform(
    private val context: Context,
    /** Package name of the player chosen in settings ("" = first one found). */
    private val preferredPlayer: () -> String = { "" },
    private val onTimerFired: (label: String?, seconds: Int) -> Unit
) : LocalPlatform {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val timers = mutableListOf<Runnable>()

    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun volume(): Pair<Int, Int> =
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun setVolumeIndex(index: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
    }

    override fun setMuted(muted: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    override fun isMuted(): Boolean = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun sendMediaAction(action: MediaAction): Boolean {
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        return try {
            val t = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
            true
        } catch (e: Exception) {
            Log.w(TAG, "media key failed: ${e.message}")
            false
        }
    }

    override fun playFromSearch(query: String, artist: String?, song: String?): Pair<PlaySearchResult, String?> {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            when {
                song != null -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, song)
                    if (artist != null) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                }
                artist != null -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                }
                else -> putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val pm = context.packageManager
            val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName != context.packageName }
            val preferred = preferredPlayer()
            val pick = candidates.firstOrNull { preferred.isNotEmpty() && it.activityInfo.packageName == preferred }
                ?: candidates.firstOrNull() ?: return PlaySearchResult.NO_PLAYER to null
            intent.component = ComponentName(pick.activityInfo.packageName, pick.activityInfo.name)
            context.startActivity(intent)
            PlaySearchResult.STARTED to pick.loadLabel(pm).toString()
        } catch (e: Exception) {
            Log.w(TAG, "play-from-search failed: ${e.message}")
            PlaySearchResult.FAILED to null
        }
    }

    override fun startTimer(seconds: Int, label: String?): Boolean {
        lateinit var r: Runnable
        r = Runnable {
            synchronized(timers) { timers.remove(r) }
            onTimerFired(label, seconds)
        }
        synchronized(timers) {
            if (timers.size >= MAX_TIMERS) return false
            timers += r
        }
        return handler.postDelayed(r, seconds * 1000L)
    }

    override fun cancelTimers(): Int = synchronized(timers) {
        val n = timers.size
        timers.forEach { handler.removeCallbacks(it) }
        timers.clear()
        n
    }

    companion object {
        private const val TAG = "LocalPlatform"
        const val MAX_TIMERS = 5

        /** Third-party players that accept play-from-search: (package, label). */
        fun players(context: Context): List<Pair<String, String>> = try {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
