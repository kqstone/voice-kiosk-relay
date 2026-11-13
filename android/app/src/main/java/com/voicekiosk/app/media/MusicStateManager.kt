package com.voicekiosk.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Manages media session observation and system audio state.
 *
 * Requirements:
 * - Uses MediaSessionManager with NotificationListenerService when notification access is granted.
 * - When notification access is NOT granted, falls back to AudioManager.isMusicActive and routes
 *   the user to the notification-access settings screen.
 * - Inserts the music page when playback starts, and removes it 1 minute after playback stops.
 * - ZERO hardcoded track, artist, album, or app names.
 * - Handles active session disappearing mid-playback gracefully without crashing.
 */
class MusicStateManager(private val context: Context) {

    companion object {
        private const val TAG = "MusicStateManager"
        const val STOP_REMOVAL_DELAY_MS = 60 * 1000L // 1 minute delay before removing stopped music page
        private const val FALLBACK_POLL_INTERVAL_MS = 2000L // 2s poll for audioManager.isMusicActive
    }

    interface Listener {
        fun onMusicVisibilityChanged(visible: Boolean, state: MusicState)
        fun onMusicStateUpdated(state: MusicState)
    }

    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var activeController: MediaController? = null
    private var currentMusicState = MusicState()
    private var isMusicPageVisible = false
    private var isStarted = false
    private var hasAutoRoutedNotificationSettings = false

    // 1-minute stop timer runnable
    private val removeMusicPageRunnable = Runnable {
        Log.i(TAG, "Playback stopped for 1 minute; removing music page from pager")
        if (isMusicPageVisible) {
            isMusicPageVisible = false
            listener?.onMusicVisibilityChanged(false, currentMusicState)
        }
    }

    // Periodic fallback poller
    private val fallbackPollRunnable = object : Runnable {
        override fun run() {
            if (!isStarted) return
            evaluateAudioState()
            mainHandler.postDelayed(this, FALLBACK_POLL_INTERVAL_MS)
        }
    }

    // MediaController callback for active session
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onSessionDestroyed() {
            handleSessionDestroyed()
        }
    }

    // System-wide active sessions changed listener
    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mainHandler.post {
                handleActiveSessionsChanged(controllers)
            }
        }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isMusicPageVisible(): Boolean = isMusicPageVisible

    fun getCurrentState(): MusicState = currentMusicState

    fun start() {
        if (isStarted) return
        isStarted = true

        MediaNotificationListenerService.setConnectionListener { connected ->
            mainHandler.post {
                if (connected) {
                    refreshSessions()
                }
            }
        }

        refreshSessions()
        mainHandler.post(fallbackPollRunnable)
    }

    fun stop() {
        isStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        detachCurrentController()
        unregisterSessionListener()
        MediaNotificationListenerService.setConnectionListener(null)
    }

    fun checkAndUpdateState() {
        if (!isStarted) return
        refreshSessions()
    }

    /**
     * Routes the user directly to the notification-access settings screen.
     */
    fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open notification listener settings: ${e.message}")
        }
    }

    private fun refreshSessions() {
        val hasAccess = MediaNotificationListenerService.isNotificationAccessGranted(context)
        if (!hasAccess || mediaSessionManager == null) {
            unregisterSessionListener()
            detachCurrentController()
            evaluateAudioState()
            return
        }

        try {
            val componentName = ComponentName(context, MediaNotificationListenerService::class.java)
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)

            val controllers = mediaSessionManager.getActiveSessions(componentName)
            handleActiveSessionsChanged(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener access not yet active: ${e.message}")
            unregisterSessionListener()
            detachCurrentController()
            evaluateAudioState()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh media sessions: ${e.message}")
            evaluateAudioState()
        }
    }

    private fun unregisterSessionListener() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
    }

    private fun handleActiveSessionsChanged(controllers: List<MediaController>?) {
        val list = controllers ?: emptyList()
        val targetController = list.firstOrNull { isPlayingState(it.playbackState?.state) }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: list.firstOrNull()

        if (targetController != null) {
            if (activeController?.sessionToken != targetController.sessionToken) {
                detachCurrentController()
                activeController = targetController
                try {
                    targetController.registerCallback(controllerCallback, mainHandler)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register controller callback: ${e.message}")
                }
            }
            extractStateFromController(targetController)
        } else {
            detachCurrentController()
            evaluateAudioState()
        }
    }

    private fun handleSessionDestroyed() {
        Log.i(TAG, "Active media session destroyed mid-playback; handling session disappearance")
        detachCurrentController()
        // If system audio is still active, transition to fallback; otherwise begin stop timer
        if (audioManager.isMusicActive) {
            evaluateAudioState()
        } else {
            val stoppedState = currentMusicState.copy(isPlaying = false, hasActiveSession = false)
            updateMusicState(stoppedState)
        }
    }

    private fun detachCurrentController() {
        activeController?.let {
            try {
                it.unregisterCallback(controllerCallback)
            } catch (_: Exception) {}
        }
        activeController = null
    }

    private fun handlePlaybackStateChanged(playbackState: PlaybackState?) {
        val controller = activeController ?: return
        extractStateFromController(controller, explicitState = playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        val controller = activeController ?: return
        extractStateFromController(controller, metadata = metadata)
    }

    private fun extractStateFromController(
        controller: MediaController,
        explicitState: PlaybackState? = null,
        metadata: MediaMetadata? = null
    ) {
        val pbState = explicitState ?: controller.playbackState
        val meta = metadata ?: controller.metadata
        val isPlaying = isPlayingState(pbState?.state)

        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?: ""

        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?: ""

        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: meta?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString()
            ?: ""

        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val artBitmap: Bitmap? = try {
            meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        } catch (_: Exception) {
            null
        }

        val artUriStr = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val artUri = artUriStr?.let { try { Uri.parse(it) } catch (_: Exception) { null } }

        // Dynamic app name query without hardcoding
        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(controller.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            ""
        }

        val position = pbState?.position ?: 0L
        val lastUpdate = pbState?.lastPositionUpdateTime ?: 0L
        val speed = pbState?.playbackSpeed ?: 1.0f

        val newState = MusicState(
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            positionMs = position,
            lastPositionUpdateTime = lastUpdate,
            playbackSpeed = speed,
            albumArt = artBitmap,
            albumArtUri = artUri,
            appName = appName,
            hasNotificationAccess = true,
            hasActiveSession = true
        )

        updateMusicState(newState)
    }

    private fun evaluateAudioState() {
        if (activeController != null) return

        val hasAccess = MediaNotificationListenerService.isNotificationAccessGranted(context)
        val isMusicActive = audioManager.isMusicActive

        if (isMusicActive) {
            // If notification access is not granted, route user to notification-access settings screen
            if (!hasAccess && !hasAutoRoutedNotificationSettings) {
                hasAutoRoutedNotificationSettings = true
                openNotificationAccessSettings()
            }

            val fallbackState = MusicState(
                isPlaying = true,
                title = "",
                artist = "",
                album = "",
                durationMs = 0L,
                positionMs = 0L,
                lastPositionUpdateTime = SystemClock.elapsedRealtime(),
                playbackSpeed = 1.0f,
                albumArt = null,
                albumArtUri = null,
                appName = "",
                hasNotificationAccess = hasAccess,
                hasActiveSession = false
            )
            updateMusicState(fallbackState)
        } else {
            if (currentMusicState.isPlaying) {
                val stoppedState = currentMusicState.copy(isPlaying = false)
                updateMusicState(stoppedState)
            }
        }
    }

    private fun updateMusicState(newState: MusicState) {
        currentMusicState = newState

        if (newState.isPlaying) {
            // Playback active: cancel any pending 1-minute removal timer
            mainHandler.removeCallbacks(removeMusicPageRunnable)
            if (!isMusicPageVisible) {
                isMusicPageVisible = true
                listener?.onMusicVisibilityChanged(true, currentMusicState)
            } else {
                listener?.onMusicStateUpdated(currentMusicState)
            }
        } else {
            // Playback paused or stopped
            if (isMusicPageVisible) {
                listener?.onMusicStateUpdated(currentMusicState)
                // Schedule removal after 1 minute (60s) per user decision Option A
                mainHandler.removeCallbacks(removeMusicPageRunnable)
                mainHandler.postDelayed(removeMusicPageRunnable, STOP_REMOVAL_DELAY_MS)
            }
        }
    }

    /**
     * Dispatches user media control actions (Previous, Play/Pause, Next).
     */
    fun handleMediaAction(action: MediaControlAction) {
        val controller = activeController
        if (controller != null) {
            try {
                when (action) {
                    MediaControlAction.PLAY_PAUSE -> {
                        if (currentMusicState.isPlaying) {
                            controller.transportControls.pause()
                        } else {
                            controller.transportControls.play()
                        }
                    }
                    MediaControlAction.PREVIOUS -> {
                        controller.transportControls.skipToPrevious()
                    }
                    MediaControlAction.NEXT -> {
                        controller.transportControls.skipToNext()
                    }
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Transport controls failed, falling back to key events: ${e.message}")
            }
        }

        // Fallback media key events
        val keyCode = when (action) {
            MediaControlAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaControlAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaControlAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        }
        dispatchMediaKeyEvent(keyCode)

        // Optimistic toggle for fallback mode
        if (activeController == null && action == MediaControlAction.PLAY_PAUSE) {
            val nextPlaying = !currentMusicState.isPlaying
            updateMusicState(currentMusicState.copy(isPlaying = nextPlaying))
        }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch media key event: ${e.message}")
        }
    }

    private fun isPlayingState(state: Int?): Boolean {
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }
}
