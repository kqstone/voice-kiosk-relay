package com.voicekiosk.app

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
}
