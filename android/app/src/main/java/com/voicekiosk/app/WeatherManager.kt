package com.voicekiosk.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.voicekiosk.app.ondevice.tools.DayForecast
import com.voicekiosk.app.ondevice.tools.Forecast
import com.voicekiosk.app.weather.WeatherRepository
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

data class DayForecastItem(
    val date: String,
    val weatherCode: Int,
    val conditionZh: String,
    val iconSymbol: String,
    val minTempC: Int?,
    val maxTempC: Int?,
    val precipitationProb: Int? = null,
    val sunrise: String? = null,
    val sunset: String? = null
)

data class WeatherInfo(
    val city: String,
    val temperatureC: Int,
    val minTempC: Int?,
    val maxTempC: Int?,
    val weatherCode: Int,
    val conditionZh: String,
    val iconSymbol: String,
    val updatedAtMs: Long,
    val apparentTempC: Int? = null,
    val relativeHumidity: Int? = null,
    val windSpeedKmh: Float? = null,
    val precipitationProb: Int? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
    val aqi: Int? = null,
    val aqiLevelZh: String? = null,
    val forecastDays: List<DayForecastItem> = emptyList()
) {
    val iconRes: Int get() = WeatherManager.weatherCodeToDrawableRes(weatherCode)

    fun formatDisplayLine(): String {
        return if (minTempC != null && maxTempC != null) {
            "$city · $iconSymbol $conditionZh ${temperatureC}°C (${minTempC}°~${maxTempC}°C)"
        } else {
            "$city · $iconSymbol $conditionZh ${temperatureC}°C"
        }
    }

    fun toForecast(): Forecast {
        val dayForecastList = if (forecastDays.isNotEmpty()) {
            forecastDays.map {
                DayForecast(
                    date = it.date,
                    code = it.weatherCode,
                    maxC = (it.maxTempC ?: temperatureC).toDouble(),
                    minC = (it.minTempC ?: temperatureC).toDouble(),
                    precipProb = it.precipitationProb
                )
            }
        } else if (minTempC != null && maxTempC != null) {
            listOf(
                DayForecast(
                    date = "",
                    code = weatherCode,
                    maxC = maxTempC.toDouble(),
                    minC = minTempC.toDouble(),
                    precipProb = precipitationProb
                )
            )
        } else {
            emptyList()
        }

        return Forecast(
            city = city,
            currentC = temperatureC.toDouble(),
            currentCode = weatherCode,
            days = dayForecastList
        )
    }
}

/**
 * Zero-API-key weather & city location manager using Open-Meteo.
 * Delegated to [WeatherRepository] for unified client-side weather caching.
 */
object WeatherManager {
    const val CACHE_COOLDOWN_MS = WeatherRepository.CACHE_TTL_MS
    const val HTTP_TIMEOUT_MS = WeatherRepository.HTTP_MAIN_TIMEOUT_MS
    const val HTTP_AQI_TIMEOUT_MS = WeatherRepository.HTTP_AQI_TIMEOUT_MS

    interface WeatherListener {
        fun onWeatherUpdated(info: WeatherInfo)
    }

    fun getCachedWeatherInfo(context: Context? = null): WeatherInfo? {
        return WeatherRepository.getCachedWeatherInfo(context)
    }

    fun addListener(listener: WeatherListener) {
        WeatherRepository.addListener(listener)
    }

    fun removeListener(listener: WeatherListener) {
        WeatherRepository.removeListener(listener)
    }

    fun parseCachedSummary(summary: String): WeatherInfo? {
        if (summary.isBlank()) return null
        return try {
            val parts = summary.split("·")
            if (parts.size < 2) return null
            val city = parts[0].trim()
            val rest = parts[1].trim()
            val tokens = rest.split(" ").filter { it.isNotBlank() }
            val icon = if (tokens.isNotEmpty()) tokens[0].trim() else "⛅"
            val condition = if (tokens.size > 1) tokens[1].trim() else "多云"
            val tempToken = tokens.find { it.contains("°") }
            val temp = tempToken?.replace("°C", "")?.replace("°", "")?.trim()?.toIntOrNull() ?: 20

            var minTemp: Int? = null
            var maxTemp: Int? = null
            if (summary.contains("(") && summary.contains(")")) {
                val rangeStr = summary.substringAfter("(").substringBefore(")")
                val rangeTokens = rangeStr.split("~")
                if (rangeTokens.size == 2) {
                    minTemp = rangeTokens[0].replace("°C", "").replace("°", "").trim().toIntOrNull()
                    maxTemp = rangeTokens[1].replace("°C", "").replace("°", "").trim().toIntOrNull()
                }
            }

            WeatherInfo(
                city = city,
                temperatureC = temp,
                minTempC = minTemp,
                maxTempC = maxTemp,
                weatherCode = 1,
                conditionZh = condition,
                iconSymbol = icon,
                updatedAtMs = 0L
            )
        } catch (_: Exception) {
            null
        }
    }

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

    internal var mainExecutor: ((() -> Unit) -> Unit)?
        get() = WeatherRepository.mainDispatcher
        set(value) {
            WeatherRepository.mainDispatcher = value
        }

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

    fun weatherCodeToDrawableRes(code: Int): Int {
        return when (code) {
            0 -> R.drawable.ic_weather_sunny
            1, 2 -> R.drawable.ic_weather_cloudy
            3 -> R.drawable.ic_weather_overcast
            45, 48 -> R.drawable.ic_weather_fog
            51, 53, 55, 56, 57 -> R.drawable.ic_weather_drizzle
            61, 63, 80, 81, 65, 66, 67, 82 -> R.drawable.ic_weather_rain
            71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
            95, 96, 99 -> R.drawable.ic_weather_thunder
            else -> R.drawable.ic_weather_cloudy
        }
    }

    fun formatSunTime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.contains("T")) raw.substringAfter("T") else raw
    }

    fun mapAqiToLevelZh(aqi: Int): String {
        return when {
            aqi <= 50 -> "优"
            aqi <= 100 -> "良"
            aqi <= 150 -> "轻度"
            aqi <= 200 -> "中度"
            aqi <= 300 -> "重度"
            else -> "严重"
        }
    }

    fun parseAqiJson(jsonStr: String): Pair<Int, String>? {
        if (jsonStr.isBlank()) return null
        return try {
            val root = JSONObject(jsonStr)
            val current = root.optJSONObject("current") ?: return null
            val aqiValue = when {
                current.has("us_aqi") && !current.isNull("us_aqi") -> current.optDouble("us_aqi").roundToInt()
                current.has("european_aqi") && !current.isNull("european_aqi") -> current.optDouble("european_aqi").roundToInt()
                else -> null
            } ?: return null
            val level = mapAqiToLevelZh(aqiValue)
            Pair(aqiValue, level)
        } catch (_: Exception) {
            null
        }
    }

    fun parseForecastJson(
        city: String,
        jsonStr: String,
        nowMs: Long = System.currentTimeMillis(),
        aqiJsonStr: String? = null
    ): WeatherInfo? {
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

            val apparentTempC = if (current.has("apparent_temperature") && !current.isNull("apparent_temperature")) {
                current.getDouble("apparent_temperature").roundToInt()
            } else null

            val relativeHumidity = if (current.has("relative_humidity_2m") && !current.isNull("relative_humidity_2m")) {
                current.getDouble("relative_humidity_2m").roundToInt()
            } else null

            val windSpeedKmh = if (current.has("wind_speed_10m") && !current.isNull("wind_speed_10m")) {
                current.getDouble("wind_speed_10m").toFloat()
            } else null

            var minTempC: Int? = null
            var maxTempC: Int? = null
            var precipProb: Int? = null
            var sunrise: String? = null
            var sunset: String? = null
            val forecastDays = mutableListOf<DayForecastItem>()

            val daily = root.optJSONObject("daily")
            if (daily != null) {
                val minArr = daily.optJSONArray("temperature_2m_min")
                val maxArr = daily.optJSONArray("temperature_2m_max")
                val precipArr = daily.optJSONArray("precipitation_probability_max")
                val sunriseArr = daily.optJSONArray("sunrise")
                val sunsetArr = daily.optJSONArray("sunset")
                val timeArr = daily.optJSONArray("time")
                val codeArr = daily.optJSONArray("weather_code")

                if (minArr != null && minArr.length() > 0 && !minArr.isNull(0)) {
                    minTempC = minArr.getDouble(0).roundToInt()
                }
                if (maxArr != null && maxArr.length() > 0 && !maxArr.isNull(0)) {
                    maxTempC = maxArr.getDouble(0).roundToInt()
                }
                if (precipArr != null && precipArr.length() > 0 && !precipArr.isNull(0)) {
                    precipProb = precipArr.optInt(0)
                }
                if (sunriseArr != null && sunriseArr.length() > 0 && !sunriseArr.isNull(0)) {
                    sunrise = formatSunTime(sunriseArr.optString(0))
                }
                if (sunsetArr != null && sunsetArr.length() > 0 && !sunsetArr.isNull(0)) {
                    sunset = formatSunTime(sunsetArr.optString(0))
                }

                if (timeArr != null) {
                    for (i in 0 until timeArr.length()) {
                        val date = timeArr.optString(i, "")
                        val dayCode = if (codeArr != null && codeArr.length() > i && !codeArr.isNull(i)) {
                            codeArr.optInt(i, weatherCode)
                        } else weatherCode
                        val (dayCond, dayIcon) = mapWmoWeatherCode(dayCode)
                        val dayMin = if (minArr != null && minArr.length() > i && !minArr.isNull(i)) {
                            minArr.optDouble(i).roundToInt()
                        } else null
                        val dayMax = if (maxArr != null && maxArr.length() > i && !maxArr.isNull(i)) {
                            maxArr.optDouble(i).roundToInt()
                        } else null
                        val dayPrecip = if (precipArr != null && precipArr.length() > i && !precipArr.isNull(i)) {
                            precipArr.optInt(i)
                        } else null
                        val daySunrise = if (sunriseArr != null && sunriseArr.length() > i && !sunriseArr.isNull(i)) {
                            formatSunTime(sunriseArr.optString(i))
                        } else null
                        val daySunset = if (sunsetArr != null && sunsetArr.length() > i && !sunsetArr.isNull(i)) {
                            formatSunTime(sunsetArr.optString(i))
                        } else null

                        forecastDays.add(
                            DayForecastItem(
                                date = date,
                                weatherCode = dayCode,
                                conditionZh = dayCond,
                                iconSymbol = dayIcon,
                                minTempC = dayMin,
                                maxTempC = dayMax,
                                precipitationProb = dayPrecip,
                                sunrise = daySunrise,
                                sunset = daySunset
                            )
                        )
                    }
                }
            }

            val (aqi, aqiLevelZh) = if (!aqiJsonStr.isNullOrBlank()) {
                parseAqiJson(aqiJsonStr) ?: (null to null)
            } else {
                null to null
            }

            WeatherInfo(
                city = city,
                temperatureC = tempDouble.roundToInt(),
                minTempC = minTempC,
                maxTempC = maxTempC,
                weatherCode = weatherCode,
                conditionZh = conditionZh,
                iconSymbol = iconSymbol,
                updatedAtMs = nowMs,
                apparentTempC = apparentTempC,
                relativeHumidity = relativeHumidity,
                windSpeedKmh = windSpeedKmh,
                precipitationProb = precipProb,
                sunrise = sunrise,
                sunset = sunset,
                aqi = aqi,
                aqiLevelZh = aqiLevelZh,
                forecastDays = forecastDays
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
        WeatherRepository.fetchWeather(context, force) { result ->
            result.onSuccess { info ->
                onResult(info.formatDisplayLine())
            }.onFailure {
                onResult(AppConfig.getCachedWeatherSummary(context))
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
