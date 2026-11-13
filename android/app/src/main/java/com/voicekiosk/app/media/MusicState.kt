package com.voicekiosk.app.media

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import java.util.Locale

/**
 * Media playback actions for user controls (Previous, Play/Pause, Next).
 */
enum class MediaControlAction {
    PLAY_PAUSE,
    PREVIOUS,
    NEXT
}

/**
 * Immutable state model representing current media playback and metadata.
 *
 * NOTE: Contains ZERO hardcoded track, artist, album, or app names.
 */
data class MusicState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val lastPositionUpdateTime: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val albumArt: Bitmap? = null,
    val albumArtUri: Uri? = null,
    val appName: String = "",
    val hasNotificationAccess: Boolean = true,
    val hasActiveSession: Boolean = false
) {
    /**
     * Estimates the current playback position based on last updated position and elapsed realtime.
     */
    fun getCurrentPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying || lastPositionUpdateTime <= 0L) {
            return positionMs
        }
        val elapsed = (nowElapsedMs - lastPositionUpdateTime).coerceAtLeast(0L)
        val estimated = positionMs + (elapsed * playbackSpeed).toLong()
        return if (durationMs > 0) estimated.coerceIn(0L, durationMs) else estimated.coerceAtLeast(0L)
    }

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
