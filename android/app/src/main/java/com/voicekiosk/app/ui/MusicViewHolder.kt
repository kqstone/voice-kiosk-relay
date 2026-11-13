package com.voicekiosk.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.R
import com.voicekiosk.app.media.MediaControlAction
import com.voicekiosk.app.media.MusicState
import java.util.Locale

/**
 * ViewHolder for the dynamically inserted Music Page (Page 1).
 *
 * Visual spec aligns with Screenshot 1 (Portrait) and Screenshot 4/5 OLED styling:
 * - Pure black background, #141416 card, #27272A border.
 * - Circular album art or vinyl disc rotation animation when playing.
 * - Soundwave icon + "正在播放" / "已暂停" + dynamic player app badge.
 * - Track title and artist / album subtitle (ZERO hardcoding).
 * - Cyan progress bar + elapsed / duration times.
 * - Media controls: Previous, Play/Pause, Next.
 * - One-touch routing to notification-access settings when permissions are missing.
 */
class MusicViewHolder(
    itemView: View,
    private val onMediaControl: ((MediaControlAction) -> Unit)?,
    private val onOpenNotificationSettings: (() -> Unit)?
) : RecyclerView.ViewHolder(itemView) {

    private val flDiscContainer: View = itemView.findViewById(R.id.flDiscContainer)
    private val ivVinylDisc: ImageView = itemView.findViewById(R.id.ivVinylDisc)
    private val ivAlbumArt: ImageView = itemView.findViewById(R.id.ivAlbumArt)
    private val tvMusicPlayingStatus: TextView = itemView.findViewById(R.id.tvMusicPlayingStatus)
    private val tvMusicSourceBadge: TextView = itemView.findViewById(R.id.tvMusicSourceBadge)
    private val tvTrackTitle: TextView = itemView.findViewById(R.id.tvTrackTitle)
    private val tvTrackArtist: TextView = itemView.findViewById(R.id.tvTrackArtist)
    private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
    private val tvTimeElapsed: TextView = itemView.findViewById(R.id.tvTimeElapsed)
    private val tvTimeDuration: TextView = itemView.findViewById(R.id.tvTimeDuration)
    private val btnPrev: View = itemView.findViewById(R.id.btnPrev)
    private val btnPlayPause: ImageView = itemView.findViewById(R.id.btnPlayPause)
    private val btnNext: View = itemView.findViewById(R.id.btnNext)
    private val layoutPermissionHint: View? = itemView.findViewById(R.id.layoutPermissionHint)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var discAnimator: ObjectAnimator? = null
    private var currentState: MusicState? = null

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (currentState?.isPlaying == true) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    init {
        btnPrev.setOnClickListener {
            onMediaControl?.invoke(MediaControlAction.PREVIOUS)
        }
        btnPlayPause.setOnClickListener {
            onMediaControl?.invoke(MediaControlAction.PLAY_PAUSE)
        }
        btnNext.setOnClickListener {
            onMediaControl?.invoke(MediaControlAction.NEXT)
        }
        layoutPermissionHint?.setOnClickListener {
            onOpenNotificationSettings?.invoke()
        }
    }

    fun bind(state: MusicState) {
        currentState = state

        // Dynamic Title & Artist (ZERO hardcoding)
        val titleText = if (state.title.isNotEmpty()) {
            state.title
        } else {
            if (state.isPlaying) "正在播放音频" else "音频已暂停"
        }
        tvTrackTitle.text = titleText

        val artistText = when {
            state.artist.isNotEmpty() && state.album.isNotEmpty() -> "${state.artist} · ${state.album}"
            state.artist.isNotEmpty() -> state.artist
            state.album.isNotEmpty() -> state.album
            else -> if (state.hasNotificationAccess) "未知艺术家" else "点击开启通知使用权以显示歌曲信息"
        }
        tvTrackArtist.text = artistText

        // Playback status label
        tvMusicPlayingStatus.text = if (state.isPlaying) "正在播放" else "已暂停"
        tvMusicPlayingStatus.setTextColor(
            if (state.isPlaying) Color.parseColor("#22D3EE") else Color.parseColor("#94A3B8")
        )

        // Dynamic source app badge
        if (state.appName.isNotEmpty()) {
            tvMusicSourceBadge.text = state.appName
            tvMusicSourceBadge.visibility = View.VISIBLE
        } else {
            tvMusicSourceBadge.visibility = View.GONE
        }

        // Play/Pause button icon
        if (state.isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_music_pause)
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_music_play)
        }

        // Album art vs. Vinyl disc
        if (state.albumArt != null && !state.albumArt.isRecycled) {
            val roundedArt = RoundedBitmapDrawableFactory.create(
                itemView.resources,
                state.albumArt
            ).apply {
                isCircular = true
            }
            ivAlbumArt.setImageDrawable(roundedArt)
            ivAlbumArt.visibility = View.VISIBLE
            ivVinylDisc.visibility = View.GONE
        } else {
            ivAlbumArt.setImageDrawable(null)
            ivAlbumArt.visibility = View.GONE
            ivVinylDisc.visibility = View.VISIBLE
        }

        // Notification permission fallback hint banner
        if (!state.hasNotificationAccess) {
            layoutPermissionHint?.visibility = View.VISIBLE
        } else {
            layoutPermissionHint?.visibility = View.GONE
        }

        // Real-time progress and duration
        updateProgress()

        // Disc rotation animation
        if (state.isPlaying) {
            startDiscAnimation()
            mainHandler.removeCallbacks(progressUpdateRunnable)
            mainHandler.postDelayed(progressUpdateRunnable, 1000L)
        } else {
            pauseDiscAnimation()
            mainHandler.removeCallbacks(progressUpdateRunnable)
        }
    }

    private fun updateProgress() {
        val state = currentState ?: return
        val currentMs = state.getCurrentPositionMs()
        val durationMs = state.durationMs

        tvTimeElapsed.text = formatTime(currentMs)
        tvTimeDuration.text = if (durationMs > 0) formatTime(durationMs) else "--:--"

        if (durationMs > 0) {
            progressBar.max = 1000
            val ratio = (currentMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
            progressBar.progress = (ratio * 1000).toInt()
        } else {
            progressBar.max = 100
            progressBar.progress = if (state.isPlaying) 50 else 0
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun startDiscAnimation() {
        if (discAnimator == null) {
            discAnimator = ObjectAnimator.ofFloat(flDiscContainer, View.ROTATION, 0f, 360f).apply {
                duration = 16000L // 16s per revolution
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        if (discAnimator?.isStarted != true) {
            discAnimator?.start()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && discAnimator?.isPaused == true) {
            discAnimator?.resume()
        }
    }

    private fun pauseDiscAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && discAnimator?.isStarted == true) {
            discAnimator?.pause()
        } else {
            discAnimator?.cancel()
        }
    }

    fun onAttached() {
        if (currentState?.isPlaying == true) {
            startDiscAnimation()
            mainHandler.removeCallbacks(progressUpdateRunnable)
            mainHandler.post(progressUpdateRunnable)
        }
    }

    fun onDetached() {
        pauseDiscAnimation()
        discAnimator?.cancel()
        discAnimator = null
        mainHandler.removeCallbacks(progressUpdateRunnable)
    }
}
