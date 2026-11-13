package com.voicekiosk.app.ondevice.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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

        const val ACTION_MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
        const val ACTION_MEDIA_BROWSER_SERVICE_COMPAT = "androidx.media.MediaBrowserServiceCompat"

        /**
         * Discovered candidate player item before deduplication.
         */
        data class DiscoveredCandidate(
            val packageName: String,
            val label: String
        )

        /**
         * Scanner interface for discovering music players and checking package state.
         * Allows comprehensive unit testing with simulated package managers without Android runtime stubs.
         */
        interface MusicPlayerScanner {
            fun queryActivityPlayers(): List<DiscoveredCandidate>
            fun queryServicePlayers(): List<DiscoveredCandidate>
            fun isPackageInstalled(packageName: String): Boolean
            fun getApplicationLabel(packageName: String): String?
        }

        /**
         * Android runtime implementation of [MusicPlayerScanner] using [PackageManager].
         */
        class AndroidMusicPlayerScanner(private val context: Context) : MusicPlayerScanner {
            private val pm: PackageManager get() = context.packageManager

            override fun queryActivityPlayers(): List<DiscoveredCandidate> {
                val results = mutableListOf<DiscoveredCandidate>()

                // 1. MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH (Flag 0 so non-DEFAULT categories are included)
                try {
                    val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    val activities = pm.queryIntentActivities(searchIntent, 0)
                    for (info in activities) {
                        val pkg = info.activityInfo?.packageName ?: continue
                        val label = resolveLabel(pkg, info)
                        results.add(DiscoveredCandidate(pkg, label))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying MEDIA_PLAY_FROM_SEARCH activities: ${e.message}")
                }

                // 2. Intent.ACTION_MAIN with Intent.CATEGORY_APP_MUSIC
                try {
                    val musicAppIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
                    val activities = pm.queryIntentActivities(musicAppIntent, 0)
                    for (info in activities) {
                        val pkg = info.activityInfo?.packageName ?: continue
                        val label = resolveLabel(pkg, info)
                        results.add(DiscoveredCandidate(pkg, label))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying CATEGORY_APP_MUSIC activities: ${e.message}")
                }

                return results
            }

            override fun queryServicePlayers(): List<DiscoveredCandidate> {
                val results = mutableListOf<DiscoveredCandidate>()

                // 1. Standard MediaBrowserService
                try {
                    val mbsIntent = Intent(ACTION_MEDIA_BROWSER_SERVICE)
                    val services = pm.queryIntentServices(mbsIntent, 0)
                    for (info in services) {
                        val pkg = info.serviceInfo?.packageName ?: continue
                        val label = resolveLabel(pkg, info)
                        results.add(DiscoveredCandidate(pkg, label))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying MediaBrowserService services: ${e.message}")
                }

                // 2. AndroidX MediaBrowserServiceCompat
                try {
                    val compatIntent = Intent(ACTION_MEDIA_BROWSER_SERVICE_COMPAT)
                    val services = pm.queryIntentServices(compatIntent, 0)
                    for (info in services) {
                        val pkg = info.serviceInfo?.packageName ?: continue
                        val label = resolveLabel(pkg, info)
                        results.add(DiscoveredCandidate(pkg, label))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying MediaBrowserServiceCompat services: ${e.message}")
                }

                return results
            }

            override fun isPackageInstalled(packageName: String): Boolean {
                if (packageName.isBlank()) return false
                return try {
                    pm.getPackageInfo(packageName, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun getApplicationLabel(packageName: String): String? {
                if (packageName.isBlank()) return null
                return try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    null
                }
            }

            private fun resolveLabel(pkg: String, resolveInfo: ResolveInfo): String {
                return try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    resolveInfo.loadLabel(pm).toString()
                }.ifBlank { pkg }
            }
        }

        /**
         * Scans and aggregates player candidates from both activity and service dimensions,
         * filtering out [ownPackageName] and deduplicating by package name.
         */
        fun scanPlayers(
            ownPackageName: String,
            scanner: MusicPlayerScanner
        ): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()

            val allCandidates = scanner.queryActivityPlayers() + scanner.queryServicePlayers()
            for (candidate in allCandidates) {
                val pkg = candidate.packageName.trim()
                if (pkg.isEmpty() || pkg == ownPackageName || !seen.add(pkg)) {
                    continue
                }
                val label = candidate.label.ifBlank {
                    scanner.getApplicationLabel(pkg) ?: pkg
                }
                result.add(pkg to label)
            }
            return result
        }

        /**
         * Third-party players that accept music playback: (package, label).
         * Scans both activity and service dimensions per Phase 1 specification.
         */
        fun players(context: Context): List<Pair<String, String>> = try {
            scanPlayers(context.packageName, AndroidMusicPlayerScanner(context))
        } catch (e: Exception) {
            Log.w(TAG, "players scan failed: ${e.message}")
            emptyList()
        }

        /**
         * Formats a user-facing label for the selected player package, properly handling:
         * - Empty string -> "自动选择（推荐）"
         * - Available scanned player -> player's display label
         * - Installed package not in scan list -> application label or package name
         * - Uninstalled package -> "$storedPackage（已卸载）"
         */
        fun formatPlayerLabel(
            storedPackage: String,
            availablePlayers: List<Pair<String, String>>,
            isInstalled: (packageName: String) -> Boolean,
            installedLabel: (packageName: String) -> String? = { null }
        ): String {
            val trimmed = storedPackage.trim()
            if (trimmed.isEmpty()) {
                return "自动选择（推荐）"
            }
            val matched = availablePlayers.firstOrNull { it.first == trimmed }
            if (matched != null) {
                return matched.second
            }
            if (isInstalled(trimmed)) {
                return installedLabel(trimmed) ?: trimmed
            }
            return "$trimmed（已卸载）"
        }

        /** Checks if a package is currently installed. */
        fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
            AndroidMusicPlayerScanner(context).isPackageInstalled(packageName)
        } catch (_: Exception) {
            false
        }

        /** Resolves the display label for an installed package, or null if missing. */
        fun resolveInstalledAppLabel(context: Context, packageName: String): String? = try {
            AndroidMusicPlayerScanner(context).getApplicationLabel(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
