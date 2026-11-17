package com.voicekiosk.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.voicekiosk.app.weather.WeatherCacheEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherAndNightConfigTest {

    // --- AppConfig isNightHour tests ---

    @Test
    fun testIsNightHour_midnightCrossing() {
        val startHour = 23
        val endHour = 7

        // During night hours
        assertTrue(AppConfig.isNightHour(23, startHour, endHour))
        assertTrue(AppConfig.isNightHour(0, startHour, endHour))
        assertTrue(AppConfig.isNightHour(3, startHour, endHour))
        assertTrue(AppConfig.isNightHour(6, startHour, endHour))

        // Outside night hours
        assertFalse(AppConfig.isNightHour(7, startHour, endHour))
        assertFalse(AppConfig.isNightHour(8, startHour, endHour))
        assertFalse(AppConfig.isNightHour(12, startHour, endHour))
        assertFalse(AppConfig.isNightHour(22, startHour, endHour))

        // Clamping edge cases
        assertTrue(AppConfig.isNightHour(-1, startHour, endHour)) // clamped to 0 -> night
        assertTrue(AppConfig.isNightHour(25, startHour, endHour)) // clamped to 23 -> night
    }

    @Test
    fun testIsNightHour_sameDay() {
        val startHour = 1
        val endHour = 6

        assertFalse(AppConfig.isNightHour(0, startHour, endHour))
        assertTrue(AppConfig.isNightHour(1, startHour, endHour))
        assertTrue(AppConfig.isNightHour(3, startHour, endHour))
        assertTrue(AppConfig.isNightHour(5, startHour, endHour))
        assertFalse(AppConfig.isNightHour(6, startHour, endHour))
        assertFalse(AppConfig.isNightHour(12, startHour, endHour))
        assertFalse(AppConfig.isNightHour(23, startHour, endHour))
    }

    @Test
    fun testIsNightHour_equalHours_alwaysNight() {
        // Equal hours represents 24-hour night mode
        assertTrue(AppConfig.isNightHour(0, 0, 0))
        assertTrue(AppConfig.isNightHour(12, 0, 0))
        assertTrue(AppConfig.isNightHour(23, 0, 0))
        assertTrue(AppConfig.isNightHour(15, 15, 15))
    }

    // --- AppConfig clampNightDimAlpha tests ---

    @Test
    fun testClampNightDimAlpha() {
        assertEquals(AppConfig.MIN_NIGHT_DIM_ALPHA, AppConfig.clampNightDimAlpha(0.0f), 0.001f)
        assertEquals(AppConfig.MIN_NIGHT_DIM_ALPHA, AppConfig.clampNightDimAlpha(0.29f), 0.001f)
        assertEquals(0.30f, AppConfig.clampNightDimAlpha(0.30f), 0.001f)
        assertEquals(0.85f, AppConfig.clampNightDimAlpha(0.85f), 0.001f)
        assertEquals(0.95f, AppConfig.clampNightDimAlpha(0.95f), 0.001f)
        assertEquals(AppConfig.MAX_NIGHT_DIM_ALPHA, AppConfig.clampNightDimAlpha(0.99f), 0.001f)
        assertEquals(AppConfig.MAX_NIGHT_DIM_ALPHA, AppConfig.clampNightDimAlpha(2.0f), 0.001f)
    }

    // --- WeatherManager mapWmoWeatherCode tests ---

    @Test
    fun testMapWmoWeatherCode() {
        assertEquals("晴" to "☀", WeatherManager.mapWmoWeatherCode(0))
        assertEquals("多云" to "⛅", WeatherManager.mapWmoWeatherCode(1))
        assertEquals("多云" to "⛅", WeatherManager.mapWmoWeatherCode(2))
        assertEquals("阴" to "☁", WeatherManager.mapWmoWeatherCode(3))
        assertEquals("雾" to "🌫", WeatherManager.mapWmoWeatherCode(45))
        assertEquals("雾" to "🌫", WeatherManager.mapWmoWeatherCode(48))
        assertEquals("毛毛雨" to "🌦", WeatherManager.mapWmoWeatherCode(51))
        assertEquals("毛毛雨" to "🌦", WeatherManager.mapWmoWeatherCode(55))
        assertEquals("小到中雨" to "🌧", WeatherManager.mapWmoWeatherCode(61))
        assertEquals("小到中雨" to "🌧", WeatherManager.mapWmoWeatherCode(80))
        assertEquals("大雨" to "🌧", WeatherManager.mapWmoWeatherCode(65))
        assertEquals("大雨" to "🌧", WeatherManager.mapWmoWeatherCode(82))
        assertEquals("雪" to "❄", WeatherManager.mapWmoWeatherCode(71))
        assertEquals("雪" to "❄", WeatherManager.mapWmoWeatherCode(85))
        assertEquals("雷阵雨" to "⛈", WeatherManager.mapWmoWeatherCode(95))
        assertEquals("雷阵雨" to "⛈", WeatherManager.mapWmoWeatherCode(99))
        // Unknown code fallback to "多云"
        assertEquals("多云" to "⛅", WeatherManager.mapWmoWeatherCode(999))
    }

    // --- WeatherManager parseForecastJson & WeatherInfo.formatDisplayLine tests ---

    @Test
    fun testParseForecastJson_validWithDaily() {
        val jsonStr = """
            {
              "latitude": 31.23,
              "longitude": 121.47,
              "current": {
                "time": "2026-11-08T01:30",
                "temperature_2m": 21.4,
                "weather_code": 0
              },
              "daily": {
                "time": ["2026-11-08"],
                "temperature_2m_max": [24.8],
                "temperature_2m_min": [15.2]
              }
            }
        """.trimIndent()

        val info = WeatherManager.parseForecastJson("上海", jsonStr, nowMs = 1700000000L)
        assertNotNull(info)
        info!!
        assertEquals("上海", info.city)
        assertEquals(21, info.temperatureC)
        assertEquals(15, info.minTempC)
        assertEquals(25, info.maxTempC)
        assertEquals(0, info.weatherCode)
        assertEquals("晴", info.conditionZh)
        assertEquals("☀", info.iconSymbol)
        assertEquals(1700000000L, info.updatedAtMs)

        assertEquals("上海 · ☀ 晴 21°C (15°~25°C)", info.formatDisplayLine())
    }

    @Test
    fun testParseForecastJson_validWithoutDaily() {
        val jsonStr = """
            {
              "latitude": 39.90,
              "longitude": 116.40,
              "current": {
                "time": "2026-11-08T01:30",
                "temperature_2m": 12.0,
                "weather_code": 61
              }
            }
        """.trimIndent()

        val info = WeatherManager.parseForecastJson("北京", jsonStr, nowMs = 1700000000L)
        assertNotNull(info)
        info!!
        assertEquals("北京", info.city)
        assertEquals(12, info.temperatureC)
        assertNull(info.minTempC)
        assertNull(info.maxTempC)
        assertEquals(61, info.weatherCode)
        assertEquals("小到中雨", info.conditionZh)
        assertEquals("🌧", info.iconSymbol)

        assertEquals("北京 · 🌧 小到中雨 12°C", info.formatDisplayLine())
    }

    @Test
    fun testParseForecastJson_5DayAndDetailsWithAqi() {
        val forecastJsonStr = """
            {
              "latitude": 31.23,
              "longitude": 121.47,
              "current": {
                "time": "2026-11-15T08:00",
                "temperature_2m": 22.4,
                "relative_humidity_2m": 65,
                "apparent_temperature": 23.1,
                "weather_code": 1,
                "wind_speed_10m": 14.4
              },
              "daily": {
                "time": [
                  "2026-11-15",
                  "2026-11-16",
                  "2026-11-17",
                  "2026-11-18",
                  "2026-11-19"
                ],
                "weather_code": [1, 0, 3, 61, 2],
                "temperature_2m_max": [25.0, 26.2, 21.0, 18.5, 20.0],
                "temperature_2m_min": [16.0, 15.1, 14.0, 12.0, 13.5],
                "precipitation_probability_max": [10, 0, 45, 90, 20],
                "sunrise": [
                  "2026-11-15T06:15",
                  "2026-11-16T06:16",
                  "2026-11-17T06:17",
                  "2026-11-18T06:18",
                  "2026-11-19T06:19"
                ],
                "sunset": [
                  "2026-11-15T17:05",
                  "2026-11-16T17:04",
                  "2026-11-17T17:03",
                  "2026-11-18T17:02",
                  "2026-11-19T17:01"
                ]
              }
            }
        """.trimIndent()

        val aqiJsonStr = """
            {
              "current": {
                "us_aqi": 42,
                "pm2_5": 10.2,
                "pm10": 25.0
              }
            }
        """.trimIndent()

        val info = WeatherManager.parseForecastJson("上海", forecastJsonStr, nowMs = 1700000000L, aqiJsonStr = aqiJsonStr)
        assertNotNull(info)
        info!!
        assertEquals("上海", info.city)
        assertEquals(22, info.temperatureC)
        assertEquals(16, info.minTempC)
        assertEquals(25, info.maxTempC)
        assertEquals(1, info.weatherCode)
        assertEquals("多云", info.conditionZh)
        assertEquals("⛅", info.iconSymbol)
        assertEquals(1700000000L, info.updatedAtMs)

        // Micro-weather details
        assertEquals(23, info.apparentTempC)
        assertEquals(65, info.relativeHumidity)
        assertEquals(14.4f, info.windSpeedKmh ?: 0f, 0.1f)
        assertEquals(10, info.precipitationProb)
        assertEquals("06:15", info.sunrise)
        assertEquals("17:05", info.sunset)

        // AQI
        assertEquals(42, info.aqi)
        assertEquals("优", info.aqiLevelZh)

        // 5-day forecast list
        assertEquals(5, info.forecastDays.size)

        val day0 = info.forecastDays[0]
        assertEquals("2026-11-15", day0.date)
        assertEquals(1, day0.weatherCode)
        assertEquals("多云", day0.conditionZh)
        assertEquals("⛅", day0.iconSymbol)
        assertEquals(16, day0.minTempC)
        assertEquals(25, day0.maxTempC)
        assertEquals(10, day0.precipitationProb)
        assertEquals("06:15", day0.sunrise)
        assertEquals("17:05", day0.sunset)

        val day3 = info.forecastDays[3]
        assertEquals("2026-11-18", day3.date)
        assertEquals(61, day3.weatherCode)
        assertEquals("小到中雨", day3.conditionZh)
        assertEquals("🌧", day3.iconSymbol)
        assertEquals(12, day3.minTempC)
        assertEquals(19, day3.maxTempC) // 18.5 rounded to 19
        assertEquals(90, day3.precipitationProb)
        assertEquals("06:18", day3.sunrise)
        assertEquals("17:02", day3.sunset)
    }

    @Test
    fun testParseForecastJson_missingAqiOrTimeout_nullSafe() {
        val forecastJsonStr = """
            {
              "current": {
                "temperature_2m": 22.0,
                "weather_code": 0
              }
            }
        """.trimIndent()

        // 1. null aqi
        val infoNull = WeatherManager.parseForecastJson("上海", forecastJsonStr, nowMs = 1700000000L, aqiJsonStr = null)
        assertNotNull(infoNull)
        assertNull(infoNull!!.aqi)
        assertNull(infoNull.aqiLevelZh)

        // 2. empty aqi string
        val infoEmpty = WeatherManager.parseForecastJson("上海", forecastJsonStr, nowMs = 1700000000L, aqiJsonStr = "")
        assertNotNull(infoEmpty)
        assertNull(infoEmpty!!.aqi)
        assertNull(infoEmpty.aqiLevelZh)

        // 3. invalid/malformed aqi json
        val infoBad = WeatherManager.parseForecastJson("上海", forecastJsonStr, nowMs = 1700000000L, aqiJsonStr = "{ invalid json }")
        assertNotNull(infoBad)
        assertNull(infoBad!!.aqi)
        assertNull(infoBad.aqiLevelZh)

        // 4. aqi json without current or missing us_aqi
        val infoNoCurrent = WeatherManager.parseForecastJson("上海", forecastJsonStr, nowMs = 1700000000L, aqiJsonStr = "{}")
        assertNotNull(infoNoCurrent)
        assertNull(infoNoCurrent!!.aqi)
        assertNull(infoNoCurrent.aqiLevelZh)
    }

    @Test
    fun testParseAqiJson_levelsAndFormats() {
        // US AQI levels
        assertEquals(Pair(30, "优"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 30}}"""))
        assertEquals(Pair(50, "优"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 50}}"""))
        assertEquals(Pair(75, "良"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 75}}"""))
        assertEquals(Pair(100, "良"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 100}}"""))
        assertEquals(Pair(120, "轻度"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 120}}"""))
        assertEquals(Pair(180, "中度"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 180}}"""))
        assertEquals(Pair(250, "重度"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 250}}"""))
        assertEquals(Pair(350, "严重"), WeatherManager.parseAqiJson("""{"current": {"us_aqi": 350}}"""))

        // European AQI fallback when us_aqi is absent
        assertEquals(Pair(15, "优"), WeatherManager.parseAqiJson("""{"current": {"european_aqi": 15}}"""))

        // Malformed / Empty
        assertNull(WeatherManager.parseAqiJson(""))
        assertNull(WeatherManager.parseAqiJson("{}"))
        assertNull(WeatherManager.parseAqiJson("""{"current": {}}"""))
        assertNull(WeatherManager.parseAqiJson("invalid"))
    }

    @Test
    fun testAppConfigWeatherCachePersistence_losslessRoundTrip() {
        val prefs = TestFakePrefs()
        AppConfig.prefsOverride = prefs
        try {
            val dummyContext: Context = Application()

            val dayList = listOf(
                DayForecastItem("2026-11-15", 0, "晴", "☀", 15, 25, 10, "06:15", "17:05"),
                DayForecastItem("2026-11-16", 1, "多云", "⛅", 16, 26, 20, "06:16", "17:04")
            )
            val info = WeatherInfo(
                city = "上海",
                temperatureC = 22,
                minTempC = 15,
                maxTempC = 25,
                weatherCode = 0,
                conditionZh = "晴",
                iconSymbol = "☀",
                updatedAtMs = 1700000000L,
                apparentTempC = 23,
                relativeHumidity = 60,
                windSpeedKmh = 12.0f,
                precipitationProb = 10,
                sunrise = "06:15",
                sunset = "17:05",
                aqi = 45,
                aqiLevelZh = "优",
                forecastDays = dayList
            )

            val originalEntry = WeatherCacheEntry(
                info = info,
                forecast = info.toForecast(),
                timestampMs = 1700000000L,
                locationKey = "31.2304,121.4737"
            )

            val json = originalEntry.toJson()
            AppConfig.setCachedWeatherJson(dummyContext, json)

            val retrievedJson = AppConfig.getCachedWeatherJson(dummyContext)
            assertEquals(json, retrievedJson)

            val restoredEntry = WeatherCacheEntry.fromJson(retrievedJson)
            assertNotNull(restoredEntry)
            restoredEntry!!

            assertEquals(originalEntry.timestampMs, restoredEntry.timestampMs)
            assertEquals(originalEntry.locationKey, restoredEntry.locationKey)
            assertEquals(originalEntry.info.city, restoredEntry.info.city)
            assertEquals(originalEntry.info.temperatureC, restoredEntry.info.temperatureC)
            assertEquals(originalEntry.info.minTempC, restoredEntry.info.minTempC)
            assertEquals(originalEntry.info.maxTempC, restoredEntry.info.maxTempC)
            assertEquals(originalEntry.info.weatherCode, restoredEntry.info.weatherCode)
            assertEquals(originalEntry.info.conditionZh, restoredEntry.info.conditionZh)
            assertEquals(originalEntry.info.iconSymbol, restoredEntry.info.iconSymbol)
            assertEquals(originalEntry.info.updatedAtMs, restoredEntry.info.updatedAtMs)
            assertEquals(originalEntry.info.apparentTempC, restoredEntry.info.apparentTempC)
            assertEquals(originalEntry.info.relativeHumidity, restoredEntry.info.relativeHumidity)
            assertEquals(originalEntry.info.windSpeedKmh ?: 0f, restoredEntry.info.windSpeedKmh ?: 0f, 0.01f)
            assertEquals(originalEntry.info.precipitationProb, restoredEntry.info.precipitationProb)
            assertEquals(originalEntry.info.sunrise, restoredEntry.info.sunrise)
            assertEquals(originalEntry.info.sunset, restoredEntry.info.sunset)
            assertEquals(originalEntry.info.aqi, restoredEntry.info.aqi)
            assertEquals(originalEntry.info.aqiLevelZh, restoredEntry.info.aqiLevelZh)

            assertEquals(2, restoredEntry.info.forecastDays.size)
            assertEquals("2026-11-15", restoredEntry.info.forecastDays[0].date)
            assertEquals(0, restoredEntry.info.forecastDays[0].weatherCode)
            assertEquals(15, restoredEntry.info.forecastDays[0].minTempC)
            assertEquals(25, restoredEntry.info.forecastDays[0].maxTempC)

            assertEquals(2, restoredEntry.forecast.days.size)
            assertEquals(22.0, restoredEntry.forecast.currentC ?: 0.0, 0.01)
        } finally {
            AppConfig.prefsOverride = null
        }
    }

    @Test
    fun testAppConfigWeatherCacheTtl() {
        val t0 = 1000000000L
        // 0 or negative is invalid
        assertFalse(AppConfig.isWeatherCacheValid(0L, t0))

        // exactly at t0 -> valid
        assertTrue(AppConfig.isWeatherCacheValid(t0, t0))

        // 29 minutes after -> valid
        val t29Min = t0 + 29 * 60 * 1000L
        assertTrue(AppConfig.isWeatherCacheValid(t0, t29Min))

        // 30 minutes after -> expired
        val t30Min = t0 + 30 * 60 * 1000L
        assertFalse(AppConfig.isWeatherCacheValid(t0, t30Min))

        // 60 minutes after -> expired
        val t60Min = t0 + 60 * 60 * 1000L
        assertFalse(AppConfig.isWeatherCacheValid(t0, t60Min))
    }

    @Test
    fun testParseForecastJson_malformedAndIncomplete() {
        assertNull(WeatherManager.parseForecastJson("上海", ""))
        assertNull(WeatherManager.parseForecastJson("上海", "{ invalid json }"))
        assertNull(WeatherManager.parseForecastJson("上海", "{}"))
        assertNull(WeatherManager.parseForecastJson("上海", """{"current": {}}"""))
        assertNull(WeatherManager.parseForecastJson("上海", """{"current": {"weather_code": 1}}"""))
        assertNull(WeatherManager.parseForecastJson("上海", """{"current": {"temperature_2m": 20.0}}"""))
    }

    // --- WeatherManager parseGeocodingJson tests ---

    @Test
    fun testParseGeocodingJson() {
        val jsonStr = """
            {
              "results": [
                {
                  "id": 1796236,
                  "name": "Shanghai",
                  "latitude": 31.2222,
                  "longitude": 121.4580,
                  "admin1": "Shanghai"
                },
                {
                  "id": 1816670,
                  "name": "Beijing",
                  "latitude": 39.9042,
                  "longitude": 116.4074,
                  "admin1": "Beijing"
                }
              ]
            }
        """.trimIndent()

        val results = WeatherManager.parseGeocodingJson(jsonStr)
        assertEquals(2, results.size)
        assertEquals("Shanghai", results[0].name)
        assertEquals(31.2222f, results[0].latitude, 0.001f)
        assertEquals(121.4580f, results[0].longitude, 0.001f)
        assertEquals("Shanghai", results[0].admin1)

        assertEquals("Beijing", results[1].name)
        assertEquals(39.9042f, results[1].latitude, 0.001f)
        assertEquals(116.4074f, results[1].longitude, 0.001f)

        // Empty results
        assertTrue(WeatherManager.parseGeocodingJson("""{"results": []}""").isEmpty())
        assertTrue(WeatherManager.parseGeocodingJson("{}").isEmpty())
        assertTrue(WeatherManager.parseGeocodingJson("").isEmpty())
        assertTrue(WeatherManager.parseGeocodingJson("bad json").isEmpty())
    }

    // --- WeatherManager findPresetCity tests ---

    @Test
    fun testFindPresetCity() {
        val sh = WeatherManager.findPresetCity("上海")
        assertNotNull(sh)
        assertEquals("上海", sh!!.name)
        assertEquals(31.2304f, sh.latitude, 0.001f)

        val shCity = WeatherManager.findPresetCity("上海市")
        assertNotNull(shCity)
        assertEquals("上海", shCity!!.name)

        val bj = WeatherManager.findPresetCity("北京市")
        assertNotNull(bj)
        assertEquals("北京", bj!!.name)

        val gz = WeatherManager.findPresetCity("  广州  ")
        assertNotNull(gz)
        assertEquals("广州", gz!!.name)

        assertNull(WeatherManager.findPresetCity("不存在的城市xyz"))
        assertNull(WeatherManager.findPresetCity(""))
        assertNull(WeatherManager.findPresetCity("   "))
    }

    @Test
    fun testPresetCitiesCompleteness() {
        val required = listOf("上海", "北京", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆", "苏州", "天津")
        val presetNames = WeatherManager.PRESET_CITIES.map { it.name }
        for (city in required) {
            assertTrue("Expected preset cities to contain $city", presetNames.contains(city))
        }
    }

    private class TestFakePrefs : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = TestFakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class TestFakeEditor(private val parent: TestFakePrefs) : SharedPreferences.Editor {
            private val staging = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) staging[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) staging[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) {
                    removed.add(key)
                    staging.remove(key)
                }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                staging.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) parent.data.clear()
                for (key in removed) parent.data.remove(key)
                for ((key, value) in staging) parent.data[key] = value
            }
        }
    }
}
