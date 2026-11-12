package com.voicekiosk.app

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android runtime wiring for [ReleaseUpdateChecker]:
 * - Reads installed package version (`versionName`, `longVersionCode`), device `SDK_INT`,
 *   `Build.SUPPORTED_ABIS`, and current APK signing certificate SHA-256 via `PackageManager`.
 * - Persists ETag/metadata cache and rate-limit cooldown in a dedicated SharedPreferences file
 *   (`voice_kiosk_release_update_cache`) completely isolated from NAS Bridge configuration.
 * - Exposes a singleton [ReleaseUpdateChecker.Manager] dispatching callbacks on the Android main Looper.
 */
object AndroidReleaseUpdatePlatform {
    private const val TAG = "ReleaseUpdatePlatform"
    private const val CACHE_PREFS_NAME = "voice_kiosk_release_update_cache"

    private const val KEY_ETAG = "cached_etag"
    private const val KEY_RELEASE_JSON = "cached_release_json"
    private const val KEY_METADATA_JSON = "cached_metadata_json"
    private const val KEY_CACHED_AT_MS = "cached_at_epoch_ms"
    private const val KEY_METADATA_FETCHED_AT_MS = "metadata_fetched_at_epoch_ms"
    private const val KEY_RATE_LIMIT_UNTIL_MS = "rate_limit_until_epoch_ms"

    @Volatile
    private var managerInstance: ReleaseUpdateChecker.Manager? = null

    fun getManager(context: Context): ReleaseUpdateChecker.Manager {
        val existing = managerInstance
        if (existing != null) return existing
        return synchronized(this) {
            val secondCheck = managerInstance
            if (secondCheck != null) {
                secondCheck
            } else {
                val appContext = context.applicationContext
                val mainHandler = Handler(Looper.getMainLooper())
                val created = ReleaseUpdateChecker.Manager(
                    httpClient = ReleaseUpdateChecker.DefaultHttpClient(),
                    clock = ReleaseUpdateChecker.SystemClock,
                    cacheStore = SharedPrefsCacheStore(appContext),
                    installedAppProvider = { readInstalledAppInfo(appContext) },
                    mainThreadPoster = { runnable ->
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            runnable.run()
                        } else {
                            mainHandler.post(runnable)
                        }
                    }
                )
                managerInstance = created
                created
            }
        }
    }

    fun readInstalledAppInfo(context: Context): ReleaseUpdateChecker.InstalledAppInfo {
        val pkgName = context.packageName
        var versionCode: Long? = null
        var versionName: String? = null
        var signingCertSha256: String? = null
        var packageReadError: String? = null

        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            }

            if (pkgInfo == null) {
                packageReadError = "PackageManager.getPackageInfo 返回空对象"
            } else {
                val rawVersionName = pkgInfo.versionName?.trim()?.takeIf { it.isNotEmpty() }
                val rawVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }

                if (rawVersionName == null || rawVersionCode <= 0L) {
                    packageReadError = "本机包信息缺少有效的 versionName 或 versionCode ($rawVersionCode)"
                } else {
                    versionName = rawVersionName
                    versionCode = rawVersionCode
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val signingInfo = pkgInfo.signingInfo
                        if (signingInfo != null) {
                            // Strictly use apkContentsSigners (current active signers on the APK) and NEVER
                            // signingCertificateHistory[0] (which may be a rotated-out historical certificate).
                            // If multiple signers are present, return null (unknown) rather than picking [0].
                            val currentSigners: Array<Signature>? = signingInfo.apkContentsSigners
                            signingCertSha256 = ReleaseUpdateChecker.extractSingleCurrentSignerSha256(
                                apkContentsSignersBytes = currentSigners?.map { it?.toByteArray() },
                                hasMultipleSigners = signingInfo.hasMultipleSigners()
                            )
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val legacySignatures: Array<Signature>? = pkgInfo.signatures
                        signingCertSha256 = ReleaseUpdateChecker.extractSingleCurrentSignerSha256(
                            apkContentsSignersBytes = legacySignatures?.map { it?.toByteArray() },
                            hasMultipleSigners = (legacySignatures?.size ?: 0) > 1
                        )
                    }
                } catch (sigEx: Exception) {
                    Log.w(TAG, "Unable to read APK signing certificate info: ${sigEx.message}")
                    signingCertSha256 = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read local installed package info: ${e.message}")
            versionCode = null
            versionName = null
            signingCertSha256 = null
            packageReadError = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }

        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()

        return ReleaseUpdateChecker.InstalledAppInfo(
            applicationId = pkgName,
            versionCode = versionCode,
            versionName = versionName,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = abis,
            signingCertSha256 = signingCertSha256,
            packageReadError = packageReadError
        )
    }

    private class SharedPrefsCacheStore(private val appContext: Context) : ReleaseUpdateChecker.CacheStore {
        private fun prefs(): SharedPreferences =
            appContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

        override fun read(): ReleaseUpdateChecker.CachedReleaseState? {
            val p = prefs()
            val releaseJson = p.getString(KEY_RELEASE_JSON, null) ?: return null
            val metadataJson = p.getString(KEY_METADATA_JSON, null) ?: return null
            val cachedAt = p.getLong(KEY_CACHED_AT_MS, 0L)
            if (releaseJson.isBlank() || metadataJson.isBlank() || cachedAt <= 0L) {
                return null
            }
            val metadataFetchedAt = p.getLong(KEY_METADATA_FETCHED_AT_MS, cachedAt).let {
                if (it > 0L) it else cachedAt
            }
            return ReleaseUpdateChecker.CachedReleaseState(
                etag = p.getString(KEY_ETAG, null),
                releaseJson = releaseJson,
                metadataJson = metadataJson,
                cachedAtEpochMs = cachedAt,
                rateLimitUntilEpochMs = p.getLong(KEY_RATE_LIMIT_UNTIL_MS, 0L),
                lastStartupCheckEpochMs = AppConfig.getLastStartupUpdateCheckMs(appContext),
                metadataFetchedAtEpochMs = metadataFetchedAt
            )
        }

        override fun write(state: ReleaseUpdateChecker.CachedReleaseState) {
            prefs().edit()
                .putString(KEY_ETAG, state.etag)
                .putString(KEY_RELEASE_JSON, state.releaseJson)
                .putString(KEY_METADATA_JSON, state.metadataJson)
                .putLong(KEY_CACHED_AT_MS, state.cachedAtEpochMs)
                .putLong(KEY_METADATA_FETCHED_AT_MS, state.metadataFetchedAtEpochMs)
                .putLong(KEY_RATE_LIMIT_UNTIL_MS, state.rateLimitUntilEpochMs)
                .apply()
        }

        override fun readRateLimitUntilMs(): Long =
            prefs().getLong(KEY_RATE_LIMIT_UNTIL_MS, 0L)

        override fun writeRateLimitUntilMs(untilEpochMs: Long) {
            prefs().edit().putLong(KEY_RATE_LIMIT_UNTIL_MS, untilEpochMs).apply()
        }

        override fun readLastStartupCheckMs(): Long =
            AppConfig.getLastStartupUpdateCheckMs(appContext)

        override fun writeLastStartupCheckMs(timestampEpochMs: Long) {
            AppConfig.setLastStartupUpdateCheckMs(appContext, timestampEpochMs)
        }
    }
}
