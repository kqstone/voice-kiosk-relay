package com.voicekiosk.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.voicekiosk.app.R
import com.voicekiosk.app.media.MediaControlAction
import com.voicekiosk.app.media.MusicState
import java.util.Locale

/**
 * Interface abstracting the widget view operations for clean testing and decoupling.
 */
interface HomeMusicWidgetView {
    fun setVisible(visible: Boolean, animate: Boolean)
    fun isVisible(): Boolean
    fun setTitle(title: String)
    fun setArtistAndSource(text: String)
    fun setProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean)
    fun setPlayPauseIcon(isPlaying: Boolean)
    fun setOnMediaControl(action: MediaControlAction, listener: () -> Unit)
    fun setOnOpenNotificationSettings(listener: () -> Unit)
}

/**
 * Concrete implementation binding to real Android View hierarchy.
 */
class AndroidHomeMusicWidgetView(rootView: View) : HomeMusicWidgetView {
    val widgetView: View = rootView.findViewById(R.id.layoutNowPlaying) ?: rootView
    val tvTrackTitle: TextView? = widgetView.findViewById(R.id.tvTrackTitle)
    val tvTrackArtist: TextView? = widgetView.findViewById(R.id.tvTrackArtist)
    val progressBar: ProgressBar? = widgetView.findViewById(R.id.progressBar)
    val tvTimeProgress: TextView? = widgetView.findViewById(R.id.tvTimeProgress)
    val btnPrev: View? = widgetView.findViewById(R.id.btnPrev)
    val btnPlayPause: ImageView? = widgetView.findViewById(R.id.btnPlayPause)
    val btnNext: View? = widgetView.findViewById(R.id.btnNext)

    override fun setVisible(visible: Boolean, animate: Boolean) {
        if (visible) {
            if (widgetView.visibility == View.VISIBLE && widgetView.alpha == 1f) return
            widgetView.visibility = View.VISIBLE
            if (animate && widgetView.isAttachedToWindow) {
                widgetView.alpha = 0f
                widgetView.animate().alpha(1f).setDuration(300L).start()
            } else {
                widgetView.alpha = 1f
            }
        } else {
            if (widgetView.visibility == View.GONE) return
            if (animate && widgetView.isAttachedToWindow) {
                widgetView.animate().alpha(0f).setDuration(250L).withEndAction {
                    widgetView.visibility = View.GONE
                }.start()
            } else {
                widgetView.visibility = View.GONE
                widgetView.alpha = 0f
            }
        }
    }

    override fun isVisible(): Boolean = widgetView.visibility == View.VISIBLE

    override fun setTitle(title: String) {
        tvTrackTitle?.text = title
    }

    override fun setArtistAndSource(text: String) {
        tvTrackArtist?.text = text
    }

    override fun setProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
        val elapsedStr = HomeMusicWidgetController.formatTime(currentMs)
        val durationStr = if (durationMs > 0) HomeMusicWidgetController.formatTime(durationMs) else "--:--"

        tvTimeProgress?.text = "$elapsedStr / $durationStr"

        progressBar?.let { pb ->
            if (durationMs > 0) {
                pb.max = 1000
                val ratio = (currentMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
                pb.progress = (ratio * 1000).toInt()
            } else {
                pb.max = 100
                pb.progress = if (isPlaying) 50 else 0
            }
        }
    }

    override fun setPlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
        )
    }

    override fun setOnMediaControl(action: MediaControlAction, listener: () -> Unit) {
        when (action) {
            MediaControlAction.PREVIOUS -> btnPrev?.setOnClickListener { listener() }
            MediaControlAction.PLAY_PAUSE -> btnPlayPause?.setOnClickListener { listener() }
            MediaControlAction.NEXT -> btnNext?.setOnClickListener { listener() }
        }
    }

    override fun setOnOpenNotificationSettings(listener: () -> Unit) {
        tvTrackArtist?.setOnClickListener { listener() }
        widgetView.setOnClickListener { listener() }
    }
}

/**
 * Interface abstracting runnable scheduling for tick updates.
 */
interface TickerScheduler {
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
    fun post(runnable: Runnable)
}

class DefaultTickerScheduler(
    private val handler: Handler? = try { Handler(Looper.getMainLooper()) } catch (_: Throwable) { null }
) : TickerScheduler {
    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler?.postDelayed(runnable, delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler?.removeCallbacks(runnable)
    }

    override fun post(runnable: Runnable) {
        handler?.post(runnable)
    }
}

/**
 * Controller for the compact Now-Playing widget on the Home page (Page 0).
 *
 * Requirements:
 * - Binds widget to existing MusicState data model without duplicating state observation.
 * - Displays track title, artist and source app label.
 * - Compact horizontal progress bar in cyan with current position and total duration (mm:ss / mm:ss).
 * - Debounces user media control buttons (Previous, Play/Pause, Next).
 * - Advances progress bar once per second while playing.
 * - Smooth fade in / out and folding when playback stops.
 */
class HomeMusicWidgetController(
    private val widgetView: HomeMusicWidgetView,
    private val onMediaControl: ((MediaControlAction) -> Unit)? = null,
    private val onOpenNotificationSettings: (() -> Unit)? = null,
    private val timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    },
    private val tickerScheduler: TickerScheduler = DefaultTickerScheduler()
) {
    /**
     * Primary Android constructor wrapping a View hierarchy.
     */
    constructor(
        rootView: View,
        onMediaControl: ((MediaControlAction) -> Unit)? = null,
        onOpenNotificationSettings: (() -> Unit)? = null
    ) : this(
        AndroidHomeMusicWidgetView(rootView),
        onMediaControl,
        onOpenNotificationSettings
    )

    private var currentState: MusicState? = null
    private var lastControlTimeMs: Long = 0L

    companion object {
        const val DEBOUNCE_INTERVAL_MS = 300L

        fun formatTrackTitle(state: MusicState): String {
            return if (state.title.isNotEmpty()) {
                state.title
            } else {
                if (state.isPlaying) "正在播放音频" else "音频已暂停"
            }
        }

        fun formatArtistAndSource(state: MusicState): String {
            return when {
                state.artist.isNotEmpty() && state.appName.isNotEmpty() -> "${state.artist} · ${state.appName}"
                state.artist.isNotEmpty() -> state.artist
                state.appName.isNotEmpty() -> state.appName
                !state.hasNotificationAccess -> "未授予通知使用权 · 点击开启"
                else -> "未知艺术家"
            }
        }

        fun formatTime(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

        fun formatProgressText(currentMs: Long, durationMs: Long): String {
            val elapsedStr = formatTime(currentMs)
            val durationStr = if (durationMs > 0) formatTime(durationMs) else "--:--"
            return "$elapsedStr / $durationStr"
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (currentState?.isPlaying == true) {
                tickerScheduler.postDelayed(this, 1000L)
            }
        }
    }

    init {
        widgetView.setOnMediaControl(MediaControlAction.PREVIOUS) {
            handleAction(MediaControlAction.PREVIOUS)
        }
        widgetView.setOnMediaControl(MediaControlAction.PLAY_PAUSE) {
            handleAction(MediaControlAction.PLAY_PAUSE)
        }
        widgetView.setOnMediaControl(MediaControlAction.NEXT) {
            handleAction(MediaControlAction.NEXT)
        }
    }

    fun handleAction(action: MediaControlAction) {
        val now = timeProvider()
        if (now - lastControlTimeMs < DEBOUNCE_INTERVAL_MS) {
            return
        }
        lastControlTimeMs = now
        onMediaControl?.invoke(action)
    }

    fun bind(state: MusicState) {
        currentState = state

        // Format Title & Artist
        widgetView.setTitle(formatTrackTitle(state))
        widgetView.setArtistAndSource(formatArtistAndSource(state))

        // Play/Pause icon
        widgetView.setPlayPauseIcon(state.isPlaying)

        // Progress
        updateProgress()

        // Notification permission click routing
        if (!state.hasNotificationAccess) {
            widgetView.setOnOpenNotificationSettings {
                onOpenNotificationSettings?.invoke()
            }
        }

        // Timer advancement: advance progress bar once per second while playing
        if (state.isPlaying) {
            tickerScheduler.removeCallbacks(progressRunnable)
            tickerScheduler.postDelayed(progressRunnable, 1000L)
        } else {
            tickerScheduler.removeCallbacks(progressRunnable)
        }
    }

    fun updateProgress() {
        val state = currentState ?: return
        val currentMs = state.getCurrentPositionMs(timeProvider())
        val durationMs = state.durationMs
        widgetView.setProgress(currentMs, durationMs, state.isPlaying)
    }

    fun setVisible(visible: Boolean, animate: Boolean = true) {
        widgetView.setVisible(visible, animate)
    }

    fun isVisible(): Boolean = widgetView.isVisible()

    fun show(animate: Boolean = true) = setVisible(true, animate)

    fun hide(animate: Boolean = true) = setVisible(false, animate)

    fun getCurrentState(): MusicState? = currentState

    fun getView(): HomeMusicWidgetView = widgetView

    val androidView: View? get() = (widgetView as? AndroidHomeMusicWidgetView)?.widgetView

    fun onAttached() {
        if (currentState?.isPlaying == true) {
            tickerScheduler.removeCallbacks(progressRunnable)
            tickerScheduler.post(progressRunnable)
        }
    }

    fun onDetached() {
        tickerScheduler.removeCallbacks(progressRunnable)
    }
}
