package com.voicekiosk.app.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private fun logD(tag: String, msg: String) {
    try { Log.d(tag, msg) } catch (_: Throwable) {}
}

private fun logI(tag: String, msg: String) {
    try { Log.i(tag, msg) } catch (_: Throwable) {}
}

private fun logW(tag: String, msg: String, tr: Throwable? = null) {
    try {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    } catch (_: Throwable) {}
}

/**
 * Outcome of attempting silent background media playback.
 */
enum class BackgroundLaunchResult {
    /** Successfully triggered playback via an active media session or MediaBrowserService. */
    SUCCESS,
    /** Player does not support background playback (no active session and no exported MediaBrowserService, or timeout). */
    NOT_SUPPORTED,
    /** Technical error encountered during launch. */
    FAILED
}

/**
 * Interface for finding and dispatching playback commands to an active MediaSession.
 */
interface ActiveSessionClient {
    /**
     * Looks for an active session belonging to [packageName] and calls playFromSearch.
     * Returns true if successfully dispatched, false otherwise.
     */
    fun playFromActiveSession(
        packageName: String,
        query: String,
        artist: String?,
        song: String?
    ): Boolean
}

/**
 * Interface for connecting to a MediaBrowserService and dispatching playback commands.
 */
interface MediaBrowserClient {
    /**
     * Checks if [packageName] exports a MediaBrowserService, connects via MediaBrowserCompat,
     * and calls playFromSearch upon connection.
     * Enforces [timeoutMs] and releases the connection if connection fails or times out.
     * Returns true if connected within [timeoutMs] and dispatched, false otherwise.
     */
    fun playFromMediaBrowser(
        packageName: String,
        query: String,
        artist: String?,
        song: String?,
        timeoutMs: Long
    ): Boolean
}

/**
 * Standalone background silent playback launcher.
 *
 * Given a target package name and search parameters (query, artist, song), triggers playback
 * in the background WITHOUT starting any Activity or opening any app UI.
 *
 * Employs a strict two-tier fallback:
 * 1. Active session first: checks MediaSessionManager getActiveSessions. If found, calls
 *    transportControls.playFromSearch.
 * 2. Media browser second: binds to the player's MediaBrowserService via MediaBrowserCompat.
 *    If connected within [DEFAULT_TIMEOUT_MS] (3s), calls transportControls.playFromSearch.
 *    Releases the connection on timeout or completion.
 * 3. If neither tier works (e.g. YouTube), returns [BackgroundLaunchResult.NOT_SUPPORTED].
 *    NEVER calls startActivity.
 */
class BackgroundMediaLauncher(
    private val activeSessionClient: ActiveSessionClient,
    private val mediaBrowserClient: MediaBrowserClient,
    private val connectionTimeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    constructor(context: Context) : this(
        activeSessionClient = AndroidActiveSessionClient(context),
        mediaBrowserClient = AndroidMediaBrowserClient(context),
        connectionTimeoutMs = DEFAULT_TIMEOUT_MS
    )

    /**
     * Launches playback in the background for [targetPackage] using search parameters.
     */
    fun launch(
        targetPackage: String,
        query: String,
        artist: String? = null,
        song: String? = null
    ): BackgroundLaunchResult {
        val pkg = targetPackage.trim()
        if (pkg.isEmpty()) {
            logW(TAG, "Empty package name provided for background launch")
            return BackgroundLaunchResult.NOT_SUPPORTED
        }

        return try {
            // Tier 1: Active session first
            if (activeSessionClient.playFromActiveSession(pkg, query, artist, song)) {
                logI(TAG, "Playback successfully triggered via active media session for $pkg")
                return BackgroundLaunchResult.SUCCESS
            }

            // Tier 2: Media browser second
            logD(TAG, "No active session for $pkg; attempting MediaBrowser connection")
            if (mediaBrowserClient.playFromMediaBrowser(pkg, query, artist, song, connectionTimeoutMs)) {
                logI(TAG, "Playback successfully triggered via MediaBrowser connection for $pkg")
                return BackgroundLaunchResult.SUCCESS
            }

            // Fallback: Neither tier works (e.g. YouTube has no active session and no MediaBrowserService)
            logI(TAG, "Neither active session nor MediaBrowser supported for $pkg; returning NOT_SUPPORTED")
            BackgroundLaunchResult.NOT_SUPPORTED
        } catch (e: Exception) {
            logW(TAG, "Failed launching background media playback for $pkg: ${e.message}", e)
            BackgroundLaunchResult.FAILED
        }
    }

    companion object {
        private const val TAG = "BackgroundLauncher"
        const val DEFAULT_TIMEOUT_MS = 3000L

        const val ACTION_MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
        const val ACTION_MEDIA_BROWSER_SERVICE_COMPAT = "androidx.media.MediaBrowserServiceCompat"

        /**
         * Convenience entry point to launch background playback using real Android context services.
         */
        fun launch(
            context: Context,
            targetPackage: String,
            query: String,
            artist: String? = null,
            song: String? = null
        ): BackgroundLaunchResult = BackgroundMediaLauncher(context).launch(targetPackage, query, artist, song)

        /**
         * Builds standard extras bundle for playFromSearch according to MediaStore and SearchManager specifications.
         */
        fun buildSearchExtras(query: String, artist: String?, song: String?): Bundle = Bundle().apply {
            putString(SearchManager.QUERY, query)
            when {
                song != null -> {
                    putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putString(MediaStore.EXTRA_MEDIA_TITLE, song)
                    if (artist != null) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                }
                artist != null -> {
                    putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                }
                else -> putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
        }
    }
}

/**
 * Production implementation of [ActiveSessionClient] using Android [MediaSessionManager].
 */
class AndroidActiveSessionClient(private val context: Context) : ActiveSessionClient {

    companion object {
        private const val TAG = "AndroidActiveSession"
    }

    override fun playFromActiveSession(
        packageName: String,
        query: String,
        artist: String?,
        song: String?
    ): Boolean {
        if (!MediaNotificationListenerService.isNotificationAccessGranted(context)) {
            logD(TAG, "Notification listener access not granted, skipping active session check")
            return false
        }
        val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false

        return try {
            val component = ComponentName(context, MediaNotificationListenerService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(component)
            val matching = controllers.filter { it.packageName == packageName }
            if (matching.isEmpty()) return false

            val targetController = matching.firstOrNull {
                val state = it.playbackState?.state
                state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
            } ?: matching.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
              ?: matching.firstOrNull() ?: return false

            val extras = BackgroundMediaLauncher.buildSearchExtras(query, artist, song)
            targetController.transportControls.playFromSearch(query, extras)
            logI(TAG, "playFromSearch dispatched to active session for $packageName")
            true
        } catch (e: SecurityException) {
            logW(TAG, "SecurityException while accessing active sessions: ${e.message}")
            false
        } catch (e: Exception) {
            logW(TAG, "Error dispatching playFromSearch to active session for $packageName: ${e.message}", e)
            false
        }
    }
}

/**
 * Production implementation of [MediaBrowserClient] using [MediaBrowserCompat].
 */
class AndroidMediaBrowserClient(private val context: Context) : MediaBrowserClient {

    companion object {
        private const val TAG = "AndroidMediaBrowser"

        /**
         * Resolves the target app's exported MediaBrowserService [ComponentName], or null if unsupported.
         */
        fun findMediaBrowserService(context: Context, packageName: String): ComponentName? {
            val pm = context.packageManager

            // 1. AndroidX MediaBrowserServiceCompat first
            try {
                val compatIntent = Intent(BackgroundMediaLauncher.ACTION_MEDIA_BROWSER_SERVICE_COMPAT).setPackage(packageName)
                val services = pm.queryIntentServices(compatIntent, 0)
                for (info in services) {
                    val sInfo = info.serviceInfo ?: continue
                    if (sInfo.packageName == packageName && sInfo.exported) {
                        return ComponentName(sInfo.packageName, sInfo.name)
                    }
                }
            } catch (e: Exception) {
                logW(TAG, "Error querying MediaBrowserServiceCompat for $packageName: ${e.message}")
            }

            // 2. Standard MediaBrowserService
            try {
                val stdIntent = Intent(BackgroundMediaLauncher.ACTION_MEDIA_BROWSER_SERVICE).setPackage(packageName)
                val services = pm.queryIntentServices(stdIntent, 0)
                for (info in services) {
                    val sInfo = info.serviceInfo ?: continue
                    if (sInfo.packageName == packageName && sInfo.exported) {
                        return ComponentName(sInfo.packageName, sInfo.name)
                    }
                }
            } catch (e: Exception) {
                logW(TAG, "Error querying MediaBrowserService for $packageName: ${e.message}")
            }

            return null
        }
    }

    override fun playFromMediaBrowser(
        packageName: String,
        query: String,
        artist: String?,
        song: String?,
        timeoutMs: Long
    ): Boolean {
        val serviceComponent = findMediaBrowserService(context, packageName)
        if (serviceComponent == null) {
            logI(TAG, "No exported MediaBrowserService found for package $packageName")
            return false
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val connectedSuccessfully = AtomicBoolean(false)
        var browser: MediaBrowserCompat? = null

        val callback = object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                try {
                    val b = browser
                    if (b != null && b.isConnected) {
                        val token = b.sessionToken
                        val controller = MediaControllerCompat(context, token)
                        val extras = BackgroundMediaLauncher.buildSearchExtras(query, artist, song)
                        controller.transportControls.playFromSearch(query, extras)
                        connectedSuccessfully.set(true)
                        logI(TAG, "playFromSearch dispatched via MediaBrowserCompat for $packageName")
                    }
                } catch (e: Exception) {
                    logW(TAG, "Error during onConnected for $packageName: ${e.message}", e)
                } finally {
                    try {
                        browser?.disconnect()
                    } catch (_: Exception) {}
                    latch.countDown()
                }
            }

            override fun onConnectionFailed() {
                logW(TAG, "MediaBrowserCompat connection failed for $packageName")
                try {
                    browser?.disconnect()
                } catch (_: Exception) {}
                latch.countDown()
            }

            override fun onConnectionSuspended() {
                logW(TAG, "MediaBrowserCompat connection suspended for $packageName")
                try {
                    browser?.disconnect()
                } catch (_: Exception) {}
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Cannot block MainLooper with latch; connect asynchronously and schedule cleanup.
            // Return false because connection outcome cannot be verified synchronously on main thread;
            // actual playback state changes will be observed asynchronously via MusicStateManager.
            logI(TAG, "Connecting MediaBrowserCompat asynchronously on main thread for $packageName; outcome will be observed via playback state")
            mainHandler.post {
                try {
                    browser = MediaBrowserCompat(context, serviceComponent, callback, null)
                    browser?.connect()
                } catch (e: Exception) {
                    logW(TAG, "Failed connecting MediaBrowserCompat on MainLooper: ${e.message}", e)
                }
            }
            mainHandler.postDelayed({
                try {
                    browser?.disconnect()
                } catch (_: Exception) {}
            }, timeoutMs)
            return false
        }

        // Called on background thread: post connect to MainLooper and await completion or timeout
        mainHandler.post {
            try {
                browser = MediaBrowserCompat(context, serviceComponent, callback, null)
                browser?.connect()
            } catch (e: Exception) {
                logW(TAG, "Failed connecting MediaBrowserCompat: ${e.message}", e)
                latch.countDown()
            }
        }

        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) {
            logW(TAG, "MediaBrowser connection timed out after ${timeoutMs}ms for $packageName; releasing connection")
            mainHandler.post {
                try {
                    browser?.disconnect()
                } catch (_: Exception) {}
            }
        }

        return completed && connectedSuccessfully.get()
    }
}
