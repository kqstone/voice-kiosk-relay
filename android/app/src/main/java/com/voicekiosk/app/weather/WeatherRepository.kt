package com.voicekiosk.app.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.DayForecastItem
import com.voicekiosk.app.WeatherInfo
import com.voicekiosk.app.WeatherManager
import com.voicekiosk.app.ondevice.tools.DayForecast
import com.voicekiosk.app.ondevice.tools.Forecast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class WeatherCacheEntry(
    val info: WeatherInfo,
    val forecast: Forecast,
    val timestampMs: Long,
    val locationKey: String
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestampMs", timestampMs)
        root.put("locationKey", locationKey)

        val infoObj = JSONObject()
        infoObj.put("city", info.city)
        infoObj.put("temperatureC", info.temperatureC)
        if (info.minTempC != null) infoObj.put("minTempC", info.minTempC)
        if (info.maxTempC != null) infoObj.put("maxTempC", info.maxTempC)
        infoObj.put("weatherCode", info.weatherCode)
        infoObj.put("conditionZh", info.conditionZh)
        infoObj.put("iconSymbol", info.iconSymbol)
        infoObj.put("updatedAtMs", info.updatedAtMs)
        if (info.apparentTempC != null) infoObj.put("apparentTempC", info.apparentTempC)
        if (info.relativeHumidity != null) infoObj.put("relativeHumidity", info.relativeHumidity)
        if (info.windSpeedKmh != null) infoObj.put("windSpeedKmh", info.windSpeedKmh.toDouble())
        if (info.precipitationProb != null) infoObj.put("precipitationProb", info.precipitationProb)
        if (info.sunrise != null) infoObj.put("sunrise", info.sunrise)
        if (info.sunset != null) infoObj.put("sunset", info.sunset)
        if (info.aqi != null) infoObj.put("aqi", info.aqi)
        if (info.aqiLevelZh != null) infoObj.put("aqiLevelZh", info.aqiLevelZh)

        val daysArr = JSONArray()
        for (day in info.forecastDays) {
            val dObj = JSONObject()
            dObj.put("date", day.date)
            dObj.put("weatherCode", day.weatherCode)
            dObj.put("conditionZh", day.conditionZh)
            dObj.put("iconSymbol", day.iconSymbol)
            if (day.minTempC != null) dObj.put("minTempC", day.minTempC)
            if (day.maxTempC != null) dObj.put("maxTempC", day.maxTempC)
            if (day.precipitationProb != null) dObj.put("precipitationProb", day.precipitationProb)
            if (day.sunrise != null) dObj.put("sunrise", day.sunrise)
            if (day.sunset != null) dObj.put("sunset", day.sunset)
            daysArr.put(dObj)
        }
        infoObj.put("forecastDays", daysArr)
        root.put("info", infoObj)

        val fcObj = JSONObject()
        fcObj.put("city", forecast.city)
        if (forecast.currentC != null) fcObj.put("currentC", forecast.currentC)
        if (forecast.currentCode != null) fcObj.put("currentCode", forecast.currentCode)
        val fcDaysArr = JSONArray()
        for (d in forecast.days) {
            val fdObj = JSONObject()
            fdObj.put("date", d.date)
            fdObj.put("code", d.code)
            fdObj.put("maxC", d.maxC)
            fdObj.put("minC", d.minC)
            if (d.precipProb != null) fdObj.put("precipProb", d.precipProb)
            fcDaysArr.put(fdObj)
        }
        fcObj.put("days", fcDaysArr)
        root.put("forecast", fcObj)

        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WeatherCacheEntry? {
            if (jsonStr.isBlank()) return null
            return try {
                val root = JSONObject(jsonStr)
                val timestampMs = root.optLong("timestampMs", 0L)
                val locationKey = root.optString("locationKey", "")
                val infoObj = root.optJSONObject("info") ?: return null

                val city = infoObj.optString("city", "")
                val temperatureC = infoObj.optInt("temperatureC", 0)
                val minTempC = if (infoObj.has("minTempC") && !infoObj.isNull("minTempC")) infoObj.getInt("minTempC") else null
                val maxTempC = if (infoObj.has("maxTempC") && !infoObj.isNull("maxTempC")) infoObj.getInt("maxTempC") else null
                val weatherCode = infoObj.optInt("weatherCode", 0)
                val conditionZh = infoObj.optString("conditionZh", "多云")
                val iconSymbol = infoObj.optString("iconSymbol", "⛅")
                val updatedAtMs = infoObj.optLong("updatedAtMs", timestampMs)
                val apparentTempC = if (infoObj.has("apparentTempC") && !infoObj.isNull("apparentTempC")) infoObj.getInt("apparentTempC") else null
                val relativeHumidity = if (infoObj.has("relativeHumidity") && !infoObj.isNull("relativeHumidity")) infoObj.getInt("relativeHumidity") else null
                val windSpeedKmh = if (infoObj.has("windSpeedKmh") && !infoObj.isNull("windSpeedKmh")) infoObj.getDouble("windSpeedKmh").toFloat() else null
                val precipProb = if (infoObj.has("precipitationProb") && !infoObj.isNull("precipitationProb")) infoObj.getInt("precipitationProb") else null
                val sunrise = if (infoObj.has("sunrise") && !infoObj.isNull("sunrise")) infoObj.getString("sunrise") else null
                val sunset = if (infoObj.has("sunset") && !infoObj.isNull("sunset")) infoObj.getString("sunset") else null
                val aqi = if (infoObj.has("aqi") && !infoObj.isNull("aqi")) infoObj.getInt("aqi") else null
                val aqiLevelZh = if (infoObj.has("aqiLevelZh") && !infoObj.isNull("aqiLevelZh")) infoObj.getString("aqiLevelZh") else null

                val daysList = mutableListOf<DayForecastItem>()
                val daysArr = infoObj.optJSONArray("forecastDays")
                if (daysArr != null) {
                    for (i in 0 until daysArr.length()) {
                        val dObj = daysArr.optJSONObject(i) ?: continue
                        val date = dObj.optString("date", "")
                        val code = dObj.optInt("weatherCode", weatherCode)
                        val cond = dObj.optString("conditionZh", conditionZh)
                        val icon = dObj.optString("iconSymbol", iconSymbol)
                        val min = if (dObj.has("minTempC") && !dObj.isNull("minTempC")) dObj.getInt("minTempC") else null
                        val max = if (dObj.has("maxTempC") && !dObj.isNull("maxTempC")) dObj.getInt("maxTempC") else null
                        val precip = if (dObj.has("precipitationProb") && !dObj.isNull("precipitationProb")) dObj.getInt("precipitationProb") else null
                        val sr = if (dObj.has("sunrise") && !dObj.isNull("sunrise")) dObj.getString("sunrise") else null
                        val ss = if (dObj.has("sunset") && !dObj.isNull("sunset")) dObj.getString("sunset") else null
                        daysList.add(DayForecastItem(date, code, cond, icon, min, max, precip, sr, ss))
                    }
                }

                val info = WeatherInfo(
                    city = city,
                    temperatureC = temperatureC,
                    minTempC = minTempC,
                    maxTempC = maxTempC,
                    weatherCode = weatherCode,
                    conditionZh = conditionZh,
                    iconSymbol = iconSymbol,
                    updatedAtMs = updatedAtMs,
                    apparentTempC = apparentTempC,
                    relativeHumidity = relativeHumidity,
                    windSpeedKmh = windSpeedKmh,
                    precipitationProb = precipProb,
                    sunrise = sunrise,
                    sunset = sunset,
                    aqi = aqi,
                    aqiLevelZh = aqiLevelZh,
                    forecastDays = daysList
                )

                val fcObj = root.optJSONObject("forecast")
                val forecast = if (fcObj != null) {
                    val fcCity = fcObj.optString("city", city)
                    val currentC = if (fcObj.has("currentC") && !fcObj.isNull("currentC")) fcObj.getDouble("currentC") else null
                    val currentCode = if (fcObj.has("currentCode") && !fcObj.isNull("currentCode")) fcObj.getInt("currentCode") else null
                    val fcDaysList = mutableListOf<DayForecast>()
                    val fcDaysArr = fcObj.optJSONArray("days")
                    if (fcDaysArr != null) {
                        for (i in 0 until fcDaysArr.length()) {
                            val fdObj = fcDaysArr.optJSONObject(i) ?: continue
                            val dDate = fdObj.optString("date", "")
                            val dCode = fdObj.optInt("code", 0)
                            val dMax = fdObj.optDouble("maxC", 0.0)
                            val dMin = fdObj.optDouble("minC", 0.0)
                            val dPrecip = if (fdObj.has("precipProb") && !fdObj.isNull("precipProb")) fdObj.getInt("precipProb") else null
                            fcDaysList.add(DayForecast(dDate, dCode, dMax, dMin, dPrecip))
                        }
                    }
                    Forecast(fcCity, currentC, currentCode, fcDaysList)
                } else {
                    info.toForecast()
                }

                WeatherCacheEntry(info, forecast, timestampMs, locationKey)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Unified singleton weather repository for Voice Kiosk.
 * Acts as the single source of truth for both the home widgets and voice tools.
 */
object WeatherRepository {
    const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    const val HTTP_MAIN_TIMEOUT_MS = 6000     // 6 seconds
    const val HTTP_AQI_TIMEOUT_MS = 3000      // 3 seconds

    private val cacheRef = AtomicReference<WeatherCacheEntry?>(null)
    private val isFetching = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<WeatherManager.WeatherListener>()

    internal var ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "weather-repo-worker").apply { isDaemon = true }
    }

    internal var clock: () -> Long = System::currentTimeMillis

    internal var httpFetcher: (url: String, timeoutMs: Int) -> String? = ::defaultHttpGet

    internal var mainDispatcher: ((() -> Unit) -> Unit)? = null

    private fun defaultHttpGet(urlStr: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VoiceKiosk/1.0")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun dispatchMain(action: () -> Unit) {
        mainDispatcher?.invoke(action) ?: run {
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

    fun addListener(listener: WeatherManager.WeatherListener) {
        listeners.add(listener)
        cacheRef.get()?.let { entry ->
            dispatchMain { listener.onWeatherUpdated(entry.info) }
        }
    }

    fun removeListener(listener: WeatherManager.WeatherListener) {
        listeners.remove(listener)
    }

    fun getCacheEntry(): WeatherCacheEntry? = cacheRef.get()

    fun getFreshCacheEntry(nowMs: Long = clock()): WeatherCacheEntry? {
        val entry = cacheRef.get() ?: return null
        return if ((nowMs - entry.timestampMs) in 0 until CACHE_TTL_MS) entry else null
    }

    fun getCachedWeatherInfo(context: Context? = null): WeatherInfo? {
        cacheRef.get()?.info?.let { return it }
        if (context != null) {
            loadFromPersistence(context)?.info?.let { return it }
        }
        return null
    }

    fun getFreshWeatherInfo(nowMs: Long = clock()): WeatherInfo? =
        getFreshCacheEntry(nowMs)?.info

    fun getCachedForecast(): Forecast? =
        cacheRef.get()?.forecast

    fun getFreshForecastIfAvailable(targetCity: String? = null, nowMs: Long = clock()): Forecast? {
        val entry = getFreshCacheEntry(nowMs) ?: return null
        if (!targetCity.isNullOrBlank()) {
            val normTarget = targetCity.trim().removeSuffix("市")
            val normCached = entry.info.city.trim().removeSuffix("市")
            if (!normTarget.equals(normCached, ignoreCase = true)) {
                return null
            }
        }
        return entry.forecast
    }

    fun setCacheEntry(entry: WeatherCacheEntry, context: Context? = null) {
        cacheRef.set(entry)
        if (context != null) {
            AppConfig.setCachedWeatherJson(context, entry.toJson())
            AppConfig.setCachedWeatherSummary(context, entry.info.formatDisplayLine())
            AppConfig.setLastWeatherFetchMs(context, entry.timestampMs)
        }
        dispatchMain {
            for (listener in listeners) {
                listener.onWeatherUpdated(entry.info)
            }
        }
    }

    fun loadFromPersistence(context: Context): WeatherCacheEntry? {
        val json = AppConfig.getCachedWeatherJson(context)
        if (json.isNotBlank()) {
            val entry = WeatherCacheEntry.fromJson(json)
            if (entry != null) {
                cacheRef.set(entry)
                return entry
            }
        }
        val summary = AppConfig.getCachedWeatherSummary(context)
        if (summary.isNotBlank()) {
            val parsed = WeatherManager.parseCachedSummary(summary)
            if (parsed != null) {
                val lat = AppConfig.getWeatherLatitude(context)
                val lon = AppConfig.getWeatherLongitude(context)
                val entry = WeatherCacheEntry(
                    info = parsed,
                    forecast = parsed.toForecast(),
                    timestampMs = AppConfig.getLastWeatherFetchMs(context),
                    locationKey = String.format(Locale.US, "%.4f,%.4f", lat, lon)
                )
                cacheRef.set(entry)
                return entry
            }
        }
        return null
    }

    fun clearCache() {
        cacheRef.set(null)
    }

    fun fetchWeather(
        context: Context,
        force: Boolean = false,
        onResult: ((Result<WeatherInfo>) -> Unit)? = null
    ) {
        val lat = AppConfig.getWeatherLatitude(context)
        val lon = AppConfig.getWeatherLongitude(context)
        val city = AppConfig.getWeatherCity(context)
        val locationKey = String.format(Locale.US, "%.4f,%.4f", lat, lon)
        val now = clock()

        if (!force) {
            val current = cacheRef.get() ?: loadFromPersistence(context)
            if (current != null && current.locationKey == locationKey && (now - current.timestampMs) in 0 until CACHE_TTL_MS) {
                dispatchMain {
                    for (listener in listeners) {
                        listener.onWeatherUpdated(current.info)
                    }
                    onResult?.invoke(Result.success(current.info))
                }
                return
            }
        }

        if (!isFetching.compareAndSet(false, true)) {
            val existing = cacheRef.get()?.info
            dispatchMain {
                if (existing != null) {
                    onResult?.invoke(Result.success(existing))
                } else {
                    onResult?.invoke(Result.failure(IllegalStateException("Fetch already in progress")))
                }
            }
            return
        }

        ioExecutor.execute {
            try {
                val forecastUrl = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&forecast_days=5&timezone=auto",
                    lat, lon
                )
                val aqiUrl = String.format(
                    Locale.US,
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=european_aqi,us_aqi,pm2_5,pm10&timezone=auto",
                    lat, lon
                )

                val aqiFuture: Future<String?> = ioExecutor.submit<String?> {
                    httpFetcher(aqiUrl, HTTP_AQI_TIMEOUT_MS)
                }
                val forecastFuture: Future<String?> = ioExecutor.submit<String?> {
                    httpFetcher(forecastUrl, HTTP_MAIN_TIMEOUT_MS)
                }

                val forecastBody = try {
                    forecastFuture.get(HTTP_MAIN_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    null
                }

                if (forecastBody == null) {
                    val fallback = cacheRef.get()?.info
                    dispatchMain {
                        if (fallback != null) {
                            onResult?.invoke(Result.success(fallback))
                        } else {
                            onResult?.invoke(Result.failure(IOException("Failed to fetch forecast from Open-Meteo")))
                        }
                    }
                    return@execute
                }

                val aqiBody = try {
                    aqiFuture.get(HTTP_AQI_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    null
                }

                val fetchNow = clock()
                val info = WeatherManager.parseForecastJson(city, forecastBody, fetchNow, aqiBody)
                if (info != null) {
                    val entry = WeatherCacheEntry(
                        info = info,
                        forecast = info.toForecast(),
                        timestampMs = fetchNow,
                        locationKey = locationKey
                    )
                    cacheRef.set(entry)
                    AppConfig.setCachedWeatherJson(context, entry.toJson())
                    AppConfig.setCachedWeatherSummary(context, info.formatDisplayLine())
                    AppConfig.setLastWeatherFetchMs(context, fetchNow)

                    dispatchMain {
                        for (listener in listeners) {
                            listener.onWeatherUpdated(info)
                        }
                        onResult?.invoke(Result.success(info))
                    }
                } else {
                    dispatchMain {
                        onResult?.invoke(Result.failure(IOException("Failed to parse forecast JSON")))
                    }
                }
            } catch (e: Exception) {
                val fallback = cacheRef.get()?.info
                dispatchMain {
                    if (fallback != null) {
                        onResult?.invoke(Result.success(fallback))
                    } else {
                        onResult?.invoke(Result.failure(e))
                    }
                }
            } finally {
                isFetching.set(false)
            }
        }
    }
}
