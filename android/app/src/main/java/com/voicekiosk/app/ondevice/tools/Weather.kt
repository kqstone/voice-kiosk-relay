package com.voicekiosk.app.ondevice.tools

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** One day of forecast. */
data class DayForecast(val date: String, val code: Int, val maxC: Double, val minC: Double, val precipProb: Int?)

data class Forecast(val city: String, val currentC: Double?, val currentCode: Int?, val days: List<DayForecast>)

/** Weather data for the local weather tool; failures make the turn go to the cloud. */
interface WeatherSource {
    fun forecast(city: City): Result<Forecast>
    fun geocode(name: String): Result<City?>
}

/**
 * Open-Meteo (free, no key; the dashboard already uses it). Forecast for today + 2 days in
 * Asia/Shanghai, plus Chinese geocoding for cities outside [CityTable]. Short timeouts:
 * a slow answer falls back to the cloud instead of stalling the turn. Results are cached
 * for [cacheMs] per place.
 */
class OpenMeteoWeatherSource(
    private val connectTimeoutMs: Int = 2500,
    private val readTimeoutMs: Int = 3000,
    private val cacheMs: Long = 20 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val http: (String) -> String = { url -> httpGet(url, connectTimeoutMs, readTimeoutMs) }
) : WeatherSource {
    private val cache = mutableMapOf<String, Pair<Long, Forecast>>()

    override fun forecast(city: City): Result<Forecast> = runCatching {
        val key = String.format(java.util.Locale.US, "%.3f,%.3f", city.latitude, city.longitude)
        synchronized(cache) { cache[key]?.takeIf { clock() - it.first < cacheMs }?.let { return@runCatching it.second.copy(city = city.name) } }
        val url = String.format(java.util.Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", city.latitude, city.longitude) +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FShanghai&forecast_days=3"
        val f = parseForecast(city.name, http(url)) ?: throw IllegalStateException("bad forecast json")
        synchronized(cache) { cache[key] = clock() to f }
        f
    }

    override fun geocode(name: String): Result<City?> = runCatching {
        val q = URLEncoder.encode(name.removeSuffix("市"), "UTF-8")
        parseGeocode(http("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=5&language=zh&format=json"), name)
    }

    companion object {
        fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        fun parseForecast(city: String, json: String): Forecast? = try {
            val o = JSONObject(json)
            val cur = o.optJSONObject("current")
            val d = o.getJSONObject("daily")
            val dates = d.getJSONArray("time")
            val codes = d.getJSONArray("weather_code")
            val maxs = d.getJSONArray("temperature_2m_max")
            val mins = d.getJSONArray("temperature_2m_min")
            val probs = d.optJSONArray("precipitation_probability_max")
            val days = (0 until dates.length()).map { i ->
                DayForecast(
                    dates.getString(i), codes.optInt(i, -1), maxs.getDouble(i), mins.getDouble(i),
                    probs?.let { if (it.isNull(i)) null else it.optInt(i) }
                )
            }
            if (days.isEmpty()) null else Forecast(
                city,
                cur?.takeIf { it.has("temperature_2m") }?.getDouble("temperature_2m"),
                cur?.takeIf { it.has("weather_code") }?.getInt("weather_code"),
                days
            )
        } catch (_: Exception) {
            null
        }

        /** First result, preferring China; the spoken name is the one the user said. */
        fun parseGeocode(json: String, spokenName: String): City? {
            val arr = JSONObject(json).optJSONArray("results") ?: return null
            var pick: JSONObject? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (pick == null) pick = r
                if (r.optString("country_code") == "CN") { pick = r; break }
            }
            val p = pick ?: return null
            return City(spokenName.removeSuffix("市"), p.getDouble("latitude"), p.getDouble("longitude"))
        }
    }
}

/** Spoken Chinese weather replies from a [Forecast] (templates, no model). */
object WeatherSpeech {
    private val DAY_WORD = mapOf("today" to "今天", "tomorrow" to "明天", "after_tomorrow" to "后天")
    val DAY_INDEX = mapOf("today" to 0, "tomorrow" to 1, "after_tomorrow" to 2)

    fun condition(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "有毛毛雨"
        56, 57, 66, 67 -> "有冻雨"
        61, 80 -> "有小雨"
        63, 81 -> "有中雨"
        65, 82 -> "有大雨"
        71, 85 -> "有小雪"
        73 -> "有中雪"
        75, 86 -> "有大雪"
        77 -> "有雪粒"
        95 -> "有雷阵雨"
        96, 99 -> "有雷阵雨伴有冰雹"
        else -> "天气多变"
    }

    fun deg(c: Double): String { val n = c.roundToInt(); return if (n < 0) "零下${-n}度" else "${n}度" }

    private fun range(d: DayForecast): String {
        val lo = d.minC.roundToInt()
        val hi = d.maxC.roundToInt()
        return if (lo < 0 && hi < 0) "零下${-hi}到零下${-lo}度" else if (lo < 0) "零下${-lo}度到${hi}度" else "${lo}到${hi}度"
    }

    private fun rainAdvice(p: Int?): String = when {
        p == null -> ""
        p >= 60 -> "降水概率${p}%，很可能下雨，出门记得带伞。"
        p >= 30 -> "降水概率${p}%，可能有雨，带把伞比较稳妥。"
        else -> "降水概率${p}%，应该不会下雨。"
    }

    /** [kind]: general | rain | temp. Returns null when the forecast lacks [day]. */
    fun reply(f: Forecast, day: String, kind: String): String? {
        val idx = DAY_INDEX[day] ?: return null
        val d = f.days.getOrNull(idx) ?: return null
        val dayWord = DAY_WORD.getValue(day)
        val cond = condition(d.code)
        val now = if (idx == 0 && f.currentC != null) "现在${deg(f.currentC)}，" else ""
        return when (kind) {
            "rain" -> "${f.city}$dayWord$cond，${rainAdvice(d.precipProb).ifEmpty { "暂时没有降水概率数据。" }}"
            "temp" -> "${f.city}$dayWord$now${range(d)}，$cond。"
            else -> buildString {
                append("${f.city}$dayWord$cond，$now${range(d)}。")
                if ((d.precipProb ?: 0) >= 30) append(rainAdvice(d.precipProb))
            }
        }
    }
}
