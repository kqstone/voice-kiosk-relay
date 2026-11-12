package com.voicekiosk.app

import android.content.Context
import android.content.SharedPreferences
import java.net.URI
import java.util.Locale

/**
 * Global configuration for Voice Kiosk.
 * Defaults align strictly with docs/SPEC-v1.md section 1 and P1 requirements.
 * Nothing should be hardcoded at call sites.
 */
object AppConfig {
    private const val PREFS_NAME = "voice_kiosk_config"

    // Default configuration values from docs/SPEC-v1.md
    const val DEFAULT_WAKE_WORD = "你好小智"
    const val DEFAULT_WAKE_THRESHOLD = 0.25f
    // Empty: when DASHBOARD_URL is not set separately, derive the bridge root (/).
    const val DEFAULT_DASHBOARD_URL = ""
    const val LOCAL_PLACEHOLDER_DASHBOARD_URL = "file:///android_asset/dashboard-placeholder.html"
    const val DEFAULT_BRIDGE_URL = "http://192.168.0.202:8093"

    // KWS Model and Audio constants
    const val AUDIO_SAMPLE_RATE = 16000
    const val MODEL_DIR = "models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
    const val ENCODER_FILE = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER_FILE = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val JOINER_FILE = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val TOKENS_FILE = "$MODEL_DIR/tokens.txt"
    const val KEYWORDS_FILE = "$MODEL_DIR/keywords.txt"

    // ASR Model constants (P2 Streaming Zipformer bilingual zh-en, downloaded on first launch to app private filesDir)
    // Can be swapped later for Paraformer or SenseVoice
    // Note: Prompt specifies sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-01 (upstream release date is 2023-02-20)
    const val ASR_MODEL_NAME = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-01"
    const val ASR_UPSTREAM_MODEL_NAME = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
    const val ASR_MODEL_DIR = "models/$ASR_MODEL_NAME"
    const val ASR_MODEL_TYPE = "zipformer"
    const val ASR_ENCODER_FILENAME = "encoder-epoch-99-avg-1.int8.onnx"
    const val ASR_DECODER_FILENAME = "decoder-epoch-99-avg-1.onnx"
    const val ASR_JOINER_FILENAME = "joiner-epoch-99-avg-1.int8.onnx"
    const val ASR_TOKENS_FILENAME = "tokens.txt"

    // ASR VAD & Endpoint constants
    const val ASR_VAD_SILENCE_MS = 1200L
    const val ASR_MAX_DURATION_MS = 8000L
    const val ASR_VAD_ENERGY_THRESHOLD = 0.012f
    const val ASR_INITIAL_NO_SPEECH_TIMEOUT_MS = 4000L
    const val SESSION_SILENCE_TIMEOUT_MS = 8000L

    // Chat HTTP constants
    const val CHAT_TIMEOUT_MS = 10000
    const val CHAT_DEFAULT_SESSION = "default"

    // Memory threshold for WebView reload (MB)
    const val WEBVIEW_MEMORY_THRESHOLD_MB = 200L

    // Night mode schedule (hours in 24h format)
    const val NIGHT_MODE_START_HOUR = 23
    const val NIGHT_MODE_END_HOUR = 7
    const val DEFAULT_NIGHT_DIM_ALPHA = 0.85f
    const val MIN_NIGHT_DIM_ALPHA = 0.30f
    const val MAX_NIGHT_DIM_ALPHA = 0.95f
    const val AOD_DAY_DIM_ALPHA = 0.30f
    const val AOD_DAY_SCREEN_BRIGHTNESS = 0.12f

    // Weather defaults
    const val DEFAULT_WEATHER_CITY = "上海"
    const val DEFAULT_WEATHER_LATITUDE = 31.2304f
    const val DEFAULT_WEATHER_LONGITUDE = 121.4737f

    private const val KEY_WAKE_WORD = "pref_wake_word"
    private const val KEY_WAKE_THRESHOLD = "pref_wake_threshold"
    private const val KEY_DASHBOARD_URL = "pref_dashboard_url"
    private const val KEY_BRIDGE_URL = "pref_bridge_url"
    private const val KEY_NIGHT_MODE_ENABLED = "pref_night_mode_enabled"
    private const val KEY_NIGHT_MODE_START_HOUR = "pref_night_mode_start_hour"
    private const val KEY_NIGHT_MODE_END_HOUR = "pref_night_mode_end_hour"
    private const val KEY_NIGHT_MODE_DIM_ALPHA = "pref_night_dim_alpha"
    private const val KEY_PET_AVATAR_ENABLED = "pref_pet_avatar_enabled"
    private const val KEY_ONDEVICE_ROUTING_ENABLED = "pref_ondevice_routing_enabled"
    private const val KEY_ONDEVICE_MODEL_ROUTING_ENABLED = "pref_ondevice_model_routing_enabled"
    private const val KEY_ONDEVICE_ROUTE_DEBUG = "pref_ondevice_route_debug"
    private const val KEY_ONDEVICE_CHITCHAT_ENABLED = "pref_ondevice_chitchat_enabled"
    private const val KEY_ONDEVICE_SPECULATIVE_CLOUD = "pref_ondevice_speculative_cloud"
    private const val KEY_ONDEVICE_WEATHER_ENABLED = "pref_ondevice_weather_enabled"
    private const val KEY_ONDEVICE_PLAYER_PACKAGE = "pref_ondevice_player_package"
    private const val KEY_WEATHER_CITY = "pref_weather_city"
    private const val KEY_WEATHER_LATITUDE = "pref_weather_latitude"
    private const val KEY_WEATHER_LONGITUDE = "pref_weather_longitude"
    private const val KEY_CACHED_WEATHER_SUMMARY = "pref_cached_weather_summary"
    private const val KEY_LAST_WEATHER_FETCH_MS = "pref_last_weather_fetch_ms"
    private const val KEY_STARTUP_UPDATE_CHECK_ENABLED = "pref_startup_update_check_enabled"
    private const val KEY_LAST_STARTUP_UPDATE_CHECK_MS = "pref_last_startup_update_check_ms"

    const val DEFAULT_STARTUP_UPDATE_CHECK_ENABLED = false
    const val STARTUP_UPDATE_CHECK_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours

    // Device Token storage (plain text field configured in settings, sent as Bearer token)
    const val KEY_DEVICE_TOKEN = "pref_device_token"

    fun getDeviceToken(context: Context): String =
        prefs(context).getString(KEY_DEVICE_TOKEN, "")?.trim().orEmpty()

    fun setDeviceToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_DEVICE_TOKEN, token.trim()).apply()
    }

    fun hasDeviceToken(context: Context): Boolean =
        getDeviceToken(context).isNotEmpty()

    fun clearDeviceToken(context: Context) {
        prefs(context).edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Normalizes a URL into scheme://host:port origin format for token host-binding checks.
     * Returns empty string if the URL is invalid or lacks scheme/host.
     */
    fun normalizeBridgeOrigin(rawUrl: String): String {
        return try {
            val uri = URI(rawUrl.trim())
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            val host = uri.host?.lowercase(Locale.US) ?: return ""
            val port = if (uri.port != -1) {
                uri.port
            } else if (scheme == "https") {
                443
            } else {
                80
            }
            "$scheme://$host:$port"
        } catch (_: Exception) {
            ""
        }
    }

    fun hasBridgeAuthToken(context: Context): Boolean = hasDeviceToken(context)

    fun clearBridgeAuthToken(context: Context) {
        clearDeviceToken(context)
    }

    fun setBridgeAuthToken(context: Context, rawToken: String, targetBridgeUrl: String = getBridgeUrl(context)): Boolean {
        setDeviceToken(context, rawToken)
        return true
    }

    fun getBridgeAuthTokenForUrl(context: Context, requestBridgeUrl: String): String {
        val savedOrigin = getBoundBridgeOrigin(context)
        val currentOrigin = normalizeBridgeOrigin(requestBridgeUrl)
        if (savedOrigin.isNotEmpty() && currentOrigin.isNotEmpty() && savedOrigin != currentOrigin) {
            return ""
        }
        return getDeviceToken(context)
    }

    /** Origin the device token is bound to (scheme://host:port); empty if unpaired. */
    fun getBoundBridgeOrigin(context: Context): String =
        normalizeBridgeOrigin(getBridgeUrl(context))

    fun getWakeWord(context: Context): String =
        prefs(context).getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD

    fun setWakeWord(context: Context, value: String) {
        prefs(context).edit().putString(KEY_WAKE_WORD, value).apply()
    }

    fun getWakeThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_WAKE_THRESHOLD, DEFAULT_WAKE_THRESHOLD)

    fun setWakeThreshold(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_WAKE_THRESHOLD, value).apply()
    }

    fun deriveDashboardUrl(bridgeUrl: String): String {
        val trimmed = bridgeUrl.trim().trimEnd('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    fun getStoredDashboardUrl(context: Context): String =
        prefs(context).getString(KEY_DASHBOARD_URL, null)?.trim().orEmpty()

    fun getDashboardUrl(context: Context): String {
        val stored = getStoredDashboardUrl(context)
        if (stored.isNotEmpty()) {
            return stored
        }
        return deriveDashboardUrl(getBridgeUrl(context))
    }

    fun setDashboardUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DASHBOARD_URL, value.trim()).apply()
    }

    fun getBridgeUrl(context: Context): String =
        prefs(context).getString(KEY_BRIDGE_URL, DEFAULT_BRIDGE_URL) ?: DEFAULT_BRIDGE_URL

    fun setBridgeUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BRIDGE_URL, value).apply()
    }

    fun isNightModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NIGHT_MODE_ENABLED, true)

    fun setNightModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NIGHT_MODE_ENABLED, enabled).apply()
    }

    fun getNightModeStartHour(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_MODE_START_HOUR, NIGHT_MODE_START_HOUR).coerceIn(0, 23)

    fun setNightModeStartHour(context: Context, hour: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_MODE_START_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun getNightModeEndHour(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_MODE_END_HOUR, NIGHT_MODE_END_HOUR).coerceIn(0, 23)

    fun setNightModeEndHour(context: Context, hour: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_MODE_END_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun getNightModeDimAlpha(context: Context): Float =
        clampNightDimAlpha(prefs(context).getFloat(KEY_NIGHT_MODE_DIM_ALPHA, DEFAULT_NIGHT_DIM_ALPHA))

    fun setNightModeDimAlpha(context: Context, alpha: Float) {
        prefs(context).edit().putFloat(KEY_NIGHT_MODE_DIM_ALPHA, clampNightDimAlpha(alpha)).apply()
    }

    fun isPetAvatarEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PET_AVATAR_ENABLED, true)

    fun setPetAvatarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PET_AVATAR_ENABLED, enabled).apply()
    }

    /**
     * S1 on-device routing (L0 rules + whitelisted local tools, optional L1 model).
     * Default OFF: with it off every turn goes to the bridge exactly as before.
     */
    fun isOnDeviceRoutingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONDEVICE_ROUTING_ENABLED, false)

    fun setOnDeviceRoutingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONDEVICE_ROUTING_ENABLED, enabled).apply()
    }

    /** L1 model intent routing; only takes effect on HIGH tier with the default model downloaded. */
    fun isOnDeviceModelRoutingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONDEVICE_MODEL_ROUTING_ENABLED, true)

    fun setOnDeviceModelRoutingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONDEVICE_MODEL_ROUTING_ENABLED, enabled).apply()
    }

    /** Shows "[本地·规则 3ms]" etc. next to replies on the dashboard (not spoken). */
    fun isOnDeviceRouteDebugEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONDEVICE_ROUTE_DEBUG, false)

    fun setOnDeviceRouteDebugEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONDEVICE_ROUTE_DEBUG, enabled).apply()
    }

    /**
     * S5: after a conversation, extract stable facts the user stated (rules on all tiers,
     * the on-device model on HIGH) into the bridge's shared long-term memory. Off by
     * default; needs on-device routing, the bridge token and MEMORY_ENABLED on the bridge.
     */
    fun isMemoryExtractionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("pref_memory_extraction_enabled", false)

    fun setMemoryExtractionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("pref_memory_extraction_enabled", enabled).apply()
    }

    /**
     * S3.1: start the bridge request while the L1 model runs (saves the model time on cloud
     * turns). Off by default: when the model wins, the bridge has already stored that turn.
     */
    fun isOnDeviceSpeculativeCloudEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONDEVICE_SPECULATIVE_CLOUD, false)

    fun setOnDeviceSpeculativeCloudEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONDEVICE_SPECULATIVE_CLOUD, enabled).apply()
    }

    /** S3.1: local fixed replies to safe small talk (all tiers; S3 used the model). */
    fun isOnDeviceChitchatEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONDEVICE_CHITCHAT_ENABLED, true)

    fun setOnDeviceChitchatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONDEVICE_CHITCHAT_ENABLED, enabled).apply()
    }

    /** S3: answer weather questions locally from Open-Meteo (falls back to the cloud). */
    fun isOnDeviceWeatherEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONDEVICE_WEATHER_ENABLED, true)

    fun setOnDeviceWeatherEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONDEVICE_WEATHER_ENABLED, enabled).apply()
    }

    /** Player package for "播放…" ("" = first player found). */
    fun getOnDevicePlayerPackage(context: Context): String =
        prefs(context).getString(KEY_ONDEVICE_PLAYER_PACKAGE, "") ?: ""

    fun setOnDevicePlayerPackage(context: Context, pkg: String) {
        prefs(context).edit().putString(KEY_ONDEVICE_PLAYER_PACKAGE, pkg).apply()
    }

    fun getWeatherCity(context: Context): String =
        prefs(context).getString(KEY_WEATHER_CITY, DEFAULT_WEATHER_CITY) ?: DEFAULT_WEATHER_CITY

    fun getWeatherLatitude(context: Context): Float =
        prefs(context).getFloat(KEY_WEATHER_LATITUDE, DEFAULT_WEATHER_LATITUDE)

    fun getWeatherLongitude(context: Context): Float =
        prefs(context).getFloat(KEY_WEATHER_LONGITUDE, DEFAULT_WEATHER_LONGITUDE)

    fun setWeatherLocation(context: Context, city: String, latitude: Float, longitude: Float) {
        prefs(context).edit()
            .putString(KEY_WEATHER_CITY, city)
            .putFloat(KEY_WEATHER_LATITUDE, latitude)
            .putFloat(KEY_WEATHER_LONGITUDE, longitude)
            .apply()
    }

    fun getCachedWeatherSummary(context: Context): String =
        prefs(context).getString(KEY_CACHED_WEATHER_SUMMARY, "") ?: ""

    fun setCachedWeatherSummary(context: Context, summary: String) {
        prefs(context).edit().putString(KEY_CACHED_WEATHER_SUMMARY, summary).apply()
    }

    fun getLastWeatherFetchMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_WEATHER_FETCH_MS, 0L)

    fun setLastWeatherFetchMs(context: Context, timestampMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_WEATHER_FETCH_MS, timestampMs).apply()
    }

    fun clampNightDimAlpha(alpha: Float): Float =
        alpha.coerceIn(MIN_NIGHT_DIM_ALPHA, MAX_NIGHT_DIM_ALPHA)

    fun isNightHour(currentHour: Int, startHour: Int, endHour: Int): Boolean {
        val cur = currentHour.coerceIn(0, 23)
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        return when {
            start == end -> true
            start > end -> cur >= start || cur < end
            else -> cur in start until end
        }
    }

    fun isStartupUpdateCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STARTUP_UPDATE_CHECK_ENABLED, DEFAULT_STARTUP_UPDATE_CHECK_ENABLED)

    fun setStartupUpdateCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STARTUP_UPDATE_CHECK_ENABLED, enabled).apply()
    }

    fun getLastStartupUpdateCheckMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_STARTUP_UPDATE_CHECK_MS, 0L)

    fun setLastStartupUpdateCheckMs(context: Context, timestampMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_STARTUP_UPDATE_CHECK_MS, timestampMs).apply()
    }

    /**
     * Map known wake words to pinyin tokens compatible with the WenetSpeech Zipformer model.
     * Uses fast path for known wake words and PinyinConverter for custom phrases.
     */
    fun getKeywordsDefinition(wakeWord: String, threshold: Float): String {
        val trimmed = wakeWord.trim()
        val tokens = when (trimmed) {
            "你好小智" -> "n ǐ h ǎo x iǎo zh ì"
            "嘿缪斯" -> "h ēi m iù s ī"
            "小爱同学" -> "x iǎo ài t óng x ué"
            "小艺小艺" -> "x iǎo y ì x iǎo y ì"
            "小米小米" -> "x iǎo m ǐ x iǎo m ǐ"
            "你好问问" -> "n ǐ h ǎo w èn w èn"
            else -> {
                val converted = PinyinConverter.textToTokens(trimmed)
                if (converted.isNotEmpty()) {
                    converted.joinToString(" ")
                } else {
                    "n ǐ h ǎo x iǎo zh ì"
                }
            }
        }
        val thresholdStr = String.format(java.util.Locale.US, "%.2f", threshold)
        return "$tokens #$thresholdStr @$trimmed"
    }
}
