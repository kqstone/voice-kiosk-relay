package com.voicekiosk.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class CityLocation(
    val name: String,
    val latitude: Float,
    val longitude: Float,
    val admin1: String = ""
)

data class WeatherInfo(
    val city: String,
    val temperatureC: Int,
    val minTempC: Int?,
    val maxTempC: Int?,
    val weatherCode: Int,
    val conditionZh: String,
    val iconSymbol: String,
    val updatedAtMs: Long
) {
    fun formatDisplayLine(): String {
        return if (minTempC != null && maxTempC != null) {
            "$city · $iconSymbol $conditionZh ${temperatureC}°C (${minTempC}°~${maxTempC}°C)"
        } else {
            "$city · $iconSymbol $conditionZh ${temperatureC}°C"
        }
    }
}

/**
 * Zero-API-key weather & city location manager using Open-Meteo.
 */
object WeatherManager {
    const val CACHE_COOLDOWN_MS = 30 * 60 * 1000L
    const val HTTP_TIMEOUT_MS = 6000

    val PRESET_CITIES: List<CityLocation> = listOf(
        CityLocation("上海", 31.2304f, 121.4737f, "上海"),
        CityLocation("北京", 39.9042f, 116.4074f, "北京"),
        CityLocation("广州", 23.1291f, 113.2644f, "广东"),
        CityLocation("深圳", 22.5431f, 114.0579f, "广东"),
        CityLocation("杭州", 30.2741f, 120.1551f, "浙江"),
        CityLocation("成都", 30.5728f, 104.0668f, "四川"),
        CityLocation("武汉", 30.5928f, 114.3055f, "湖北"),
        CityLocation("南京", 32.0603f, 118.7969f, "江苏"),
        CityLocation("西安", 34.3416f, 108.9398f, "陕西"),
        CityLocation("重庆", 29.5630f, 106.5516f, "重庆"),
        CityLocation("苏州", 31.2990f, 120.5853f, "江苏"),
        CityLocation("天津", 39.0842f, 117.2009f, "天津")
    )

    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "weather-worker").apply { isDaemon = true }
    }

    internal var mainExecutor: ((() -> Unit) -> Unit)? = null

    private fun dispatchMain(action: () -> Unit) {
        mainExecutor?.invoke(action) ?: run {
            try {
                val looper = Looper.getMainLooper()
                if (looper != null && Looper.myLooper() != looper) {
                    Handler(looper).post(action)
                } else {
                    action()
                }
            } catch (_: Exception) {
                action()
            }
        }
    }

    fun mapWmoWeatherCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> "晴" to "☀"
            1, 2 -> "多云" to "⛅"
            3 -> "阴" to "☁"
            45, 48 -> "雾" to "🌫"
            51, 53, 55, 56, 57 -> "毛毛雨" to "🌦"
            61, 63, 80, 81 -> "小到中雨" to "🌧"
            65, 66, 67, 82 -> "大雨" to "🌧"
            71, 73, 75, 77, 85, 86 -> "雪" to "❄"
            95, 96, 99 -> "雷阵雨" to "⛈"
            else -> "多云" to "⛅"
        }
    }

    fun parseForecastJson(city: String, jsonStr: String, nowMs: Long = System.currentTimeMillis()): WeatherInfo? {
        if (jsonStr.isBlank()) return null
        return try {
            val root = JSONObject(jsonStr)
            val current = root.optJSONObject("current") ?: return null
            if (!current.has("temperature_2m") || !current.has("weather_code")) {
                return null
            }
            val tempDouble = current.getDouble("temperature_2m")
            val weatherCode = current.getInt("weather_code")
            val (conditionZh, iconSymbol) = mapWmoWeatherCode(weatherCode)

            var minTempC: Int? = null
            var maxTempC: Int? = null
            val daily = root.optJSONObject("daily")
            if (daily != null) {
                val minArr = daily.optJSONArray("temperature_2m_min")
                if (minArr != null && minArr.length() > 0 && !minArr.isNull(0)) {
                    minTempC = minArr.getDouble(0).roundToInt()
                }
                val maxArr = daily.optJSONArray("temperature_2m_max")
                if (maxArr != null && maxArr.length() > 0 && !maxArr.isNull(0)) {
                    maxTempC = maxArr.getDouble(0).roundToInt()
                }
            }

            WeatherInfo(
                city = city,
                temperatureC = tempDouble.roundToInt(),
                minTempC = minTempC,
                maxTempC = maxTempC,
                weatherCode = weatherCode,
                conditionZh = conditionZh,
                iconSymbol = iconSymbol,
                updatedAtMs = nowMs
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseGeocodingJson(jsonStr: String): List<CityLocation> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonStr)
            val results = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<CityLocation>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                if (name.isEmpty()) continue
                if (!item.has("latitude") || !item.has("longitude")) continue
                val lat = item.optDouble("latitude", Double.NaN)
                val lon = item.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val admin1 = item.optString("admin1", "").trim()
                list.add(
                    CityLocation(
                        name = name,
                        latitude = lat.toFloat(),
                        longitude = lon.toFloat(),
                        admin1 = admin1
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun findPresetCity(query: String): CityLocation? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val normalizedQ = q.removeSuffix("市").removeSuffix("区").removeSuffix("县")
        return PRESET_CITIES.firstOrNull { preset ->
            val pName = preset.name.removeSuffix("市")
            preset.name.equals(q, ignoreCase = true) ||
                pName.equals(normalizedQ, ignoreCase = true) ||
                preset.name.equals(normalizedQ, ignoreCase = true) ||
                pName.equals(q, ignoreCase = true)
        }
    }

    fun fetchWeatherIfNeeded(context: Context, force: Boolean = false, onResult: (String) -> Unit) {
        val cached = AppConfig.getCachedWeatherSummary(context)
        val lastFetch = AppConfig.getLastWeatherFetchMs(context)
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && (now - lastFetch) < CACHE_COOLDOWN_MS) {
            dispatchMain { onResult(cached) }
            return
        }

        val lat = AppConfig.getWeatherLatitude(context)
        val lon = AppConfig.getWeatherLongitude(context)
        val city = AppConfig.getWeatherCity(context)

        ioExecutor.execute {
            try {
                val urlStr = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto",
                    lat, lon
                )
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "VoiceKiosk/1.0")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val fetchNow = System.currentTimeMillis()
                        val info = parseForecastJson(city, body, fetchNow)
                        if (info != null) {
                            val displayLine = info.formatDisplayLine()
                            AppConfig.setCachedWeatherSummary(context, displayLine)
                            AppConfig.setLastWeatherFetchMs(context, fetchNow)
                            dispatchMain { onResult(displayLine) }
                            return@execute
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                dispatchMain { onResult(cached) }
            } catch (_: Exception) {
                dispatchMain { onResult(cached) }
            }
        }
    }

    fun searchCityLocation(query: String, onResult: (Result<CityLocation>) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            dispatchMain { onResult(Result.failure(IllegalArgumentException("City query cannot be empty"))) }
            return
        }

        val preset = findPresetCity(trimmed)
        if (preset != null) {
            dispatchMain { onResult(Result.success(preset)) }
            return
        }

        ioExecutor.execute {
            try {
                val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
                val urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedQuery&count=5&language=zh&format=json"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "VoiceKiosk/1.0")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val locations = parseGeocodingJson(body)
                        if (locations.isNotEmpty()) {
                            dispatchMain { onResult(Result.success(locations.first())) }
                            return@execute
                        } else {
                            dispatchMain { onResult(Result.failure(NoSuchElementException("未找到城市: $trimmed"))) }
                            return@execute
                        }
                    } else {
                        dispatchMain { onResult(Result.failure(IOException("Geocoding request failed with code: ${conn.responseCode}"))) }
                        return@execute
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                dispatchMain { onResult(Result.failure(e)) }
            }
        }
    }
}
