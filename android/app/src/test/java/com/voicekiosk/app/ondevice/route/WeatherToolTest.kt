package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.tools.City
import com.voicekiosk.app.ondevice.tools.CityTable
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import com.voicekiosk.app.ondevice.tools.OpenMeteoWeatherSource
import com.voicekiosk.app.ondevice.tools.WeatherSpeech
import com.voicekiosk.app.ondevice.tools.toWeatherInfo
import com.voicekiosk.app.weather.WeatherCacheEntry
import com.voicekiosk.app.weather.WeatherRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeatherToolTest {
    private val forecastJson = """
        {"current":{"time":"2026-11-10T15:00","temperature_2m":-2.4,"weather_code":3},
         "daily":{"time":["2026-11-10","2026-11-11","2026-11-12"],"weather_code":[3,63,71],
          "temperature_2m_max":[1.2,4.0,-0.6],"temperature_2m_min":[-5.5,-1.0,-7.0],
          "precipitation_probability_max":[5,65,40]}}
    """.trimIndent()

    private val forecastJson5Days = """
        {"current":{"time":"2026-11-10T15:00","temperature_2m":-2.4,"weather_code":3},
         "daily":{"time":["2026-11-10","2026-11-11","2026-11-12","2026-11-13","2026-11-14"],
          "weather_code":[3,63,71,61,0],
          "temperature_2m_max":[1.2,4.0,-0.6,5.0,6.0],
          "temperature_2m_min":[-5.5,-1.0,-7.0,-1.0,0.0],
          "precipitation_probability_max":[5,65,40,35,10]}}
    """.trimIndent()

    private fun weather(day: String, kind: String = "general", city: String? = null): RouteDecision {
        val slots = mutableMapOf("day" to day, "kind" to kind)
        if (city != null) slots["city"] = city
        return RouteDecision(Intents.WEATHER, slots, Route.LOCAL, 0.98, RouteSource.RULE)
    }

    @Before
    fun setUp() {
        WeatherRepository.clearCache()
        WeatherRepository.mainDispatcher = { it() }
    }

    @After
    fun tearDown() {
        WeatherRepository.clearCache()
        WeatherRepository.mainDispatcher = null
        WeatherRepository.clock = System::currentTimeMillis
        WeatherRepository.httpFetcher = { _, _ -> null }
    }

    @Test
    fun parsesOpenMeteoAndSpeaksNegativeTemperatures() {
        val f = OpenMeteoWeatherSource.parseForecast("哈尔滨", forecastJson)!!
        assertEquals(3, f.days.size)
        assertEquals("哈尔滨今天阴，现在零下2度，零下5度到1度。", WeatherSpeech.reply(f, "today", "general"))
        assertEquals("哈尔滨明天有中雨，零下1度到4度。降水概率65%，很可能下雨，出门记得带伞。", WeatherSpeech.reply(f, "tomorrow", "general"))
        assertEquals("哈尔滨后天有小雪，降水概率40%，可能有雨，带把伞比较稳妥。", WeatherSpeech.reply(f, "after_tomorrow", "rain"))
        assertEquals("哈尔滨后天零下1到零下7度，有小雪。", WeatherSpeech.reply(f, "after_tomorrow", "temp"))
        assertNull(WeatherSpeech.reply(f, "next_week", "general"))
        assertNull(OpenMeteoWeatherSource.parseForecast("x", "{\"daily\":{}}"))
        assertNull(OpenMeteoWeatherSource.parseForecast("x", "<html>"))
    }

    @Test
    fun fiveDayForecastSpeechUsesWeekdayForDays4And5() {
        val f = OpenMeteoWeatherSource.parseForecast("哈尔滨", forecastJson5Days)!!
        assertEquals(5, f.days.size)
        assertEquals("哈尔滨今天阴，现在零下2度，零下5度到1度。", WeatherSpeech.reply(f, "today", "general"))
        assertEquals("哈尔滨明天有中雨，零下1度到4度。降水概率65%，很可能下雨，出门记得带伞。", WeatherSpeech.reply(f, "tomorrow", "general"))
        assertEquals("哈尔滨后天有小雪，降水概率40%，可能有雨，带把伞比较稳妥。", WeatherSpeech.reply(f, "after_tomorrow", "rain"))
        assertEquals("哈尔滨周五有小雨，零下1度到5度。降水概率35%，可能有雨，带把伞比较稳妥。", WeatherSpeech.reply(f, "day_3", "general"))
        assertEquals("哈尔滨周六0到6度，晴。", WeatherSpeech.reply(f, "day_4", "temp"))
        assertEquals("哈尔滨周五有小雨，零下1度到5度。降水概率35%，可能有雨，带把伞比较稳妥。", WeatherSpeech.reply(f, "大后天", "general"))
    }

    @Test
    fun geocodePrefersChina() {
        val json = """{"results":[{"name":"Suzhou","latitude":1.0,"longitude":2.0,"country_code":"XX"},
            {"name":"苏州","latitude":31.3,"longitude":120.6,"country_code":"CN"}]}"""
        val c = OpenMeteoWeatherSource.parseGeocode(json, "苏州市")!!
        assertEquals("苏州", c.name)
        assertEquals(31.3, c.latitude, 1e-9)
        assertNull(OpenMeteoWeatherSource.parseGeocode("{}", "无"))
    }

    @Test
    fun sourceCachesAndUsesUsLocaleUrls() {
        val urls = mutableListOf<String>()
        var now = 0L
        val src = OpenMeteoWeatherSource(clock = { now }, http = { u -> urls += u; forecastJson })
        val city = City("北京", 39.9042, 116.4074)
        assertTrue(src.forecast(city).isSuccess)
        assertTrue(src.forecast(city).isSuccess)
        assertEquals(1, urls.size)
        assertTrue(urls[0], urls[0].contains("latitude=39.9042&longitude=116.4074") && urls[0].contains("timezone=Asia%2FShanghai"))
        now += 21 * 60_000L
        assertTrue(src.forecast(city).isSuccess)
        assertEquals(2, urls.size)
        val failing = OpenMeteoWeatherSource(http = { throw java.io.IOException("no network") })
        assertTrue(failing.forecast(city).isFailure)
    }

    @Test
    fun cityTable() {
        assertNotNull(CityTable.find("北京"))
        assertNotNull(CityTable.find("北京市"))
        assertNull(CityTable.find("外面"))
    }

    @Test
    fun executorUsesDefaultCityTableAndGeocoding() {
        val w = FakeWeather()
        val ex = LocalToolExecutor(FakePlatform(), w) { City("上海", 31.2, 121.5) }
        assertTrue(ex.execute(weather("today")).handled)
        assertTrue(ex.execute(weather("tomorrow", city = "广州")).handled)
        assertTrue(ex.execute(weather("today", city = "纽约")).handled)
        assertEquals(listOf("上海", "广州", "纽约"), w.asked)
        assertEquals(listOf("纽约"), w.geocoded)
        w.geocodeResult = null
        assertTrue(ex.execute(weather("today", city = "不存在")).fallbackToCloud)
        assertTrue(ex.execute(weather("明天")).handled)
        assertEquals("bad day", ex.validate(weather("next_week")))
        assertEquals("weather unavailable", LocalToolExecutor(FakePlatform()).validate(weather("today")))
        w.fail = true
        assertTrue(ex.execute(weather("today")).fallbackToCloud)
        assertFalse(LocalToolExecutor(FakePlatform(), w) { null }.execute(weather("today")).handled)
    }

    @Test
    fun freshCacheHitZeroNetwork() {
        var httpCalls = 0
        val src = OpenMeteoWeatherSource(
            clock = { 1000L },
            http = { httpCalls++; forecastJson5Days },
            weatherRepo = WeatherRepository
        )
        val shanghai = City("上海", 31.2304, 121.4737)
        val ex = LocalToolExecutor(
            platform = FakePlatform().apply { now = 1000L },
            weather = src,
            weatherRepo = WeatherRepository,
            defaultCity = { shanghai }
        )
        val initialForecast = OpenMeteoWeatherSource.parseForecast("上海", forecastJson5Days)!!
        WeatherRepository.setCacheEntry(
            WeatherCacheEntry(
                info = initialForecast.toWeatherInfo(1000L),
                forecast = initialForecast,
                timestampMs = 1000L,
                locationKey = "31.2304,121.4737"
            )
        )
        val result = ex.execute(weather("today"))
        assertTrue("Execution must be handled", result.handled)
        assertFalse("Execution must not fallback to cloud", result.fallbackToCloud)
        assertEquals("HTTP client call count must be strictly 0 on fresh cache hit", 0, httpCalls)
        assertEquals("上海今天阴，现在零下2度，零下5度到1度。", result.reply)
    }

    @Test
    fun expiredCacheTriggersNetworkFetchAndRefreshesCache() {
        var currentTime = 1000L
        var httpCalls = 0
        val src = OpenMeteoWeatherSource(
            clock = { currentTime },
            http = { httpCalls++; forecastJson5Days },
            weatherRepo = WeatherRepository
        )
        val shanghai = City("上海", 31.2304, 121.4737)
        val platform = FakePlatform().apply { now = currentTime }
        val ex = LocalToolExecutor(
            platform = platform,
            weather = src,
            weatherRepo = WeatherRepository,
            defaultCity = { shanghai }
        )
        val initialForecast = OpenMeteoWeatherSource.parseForecast("上海", forecastJson5Days)!!
        WeatherRepository.setCacheEntry(
            WeatherCacheEntry(
                info = initialForecast.toWeatherInfo(1000L),
                forecast = initialForecast,
                timestampMs = 1000L,
                locationKey = "31.2304,121.4737"
            )
        )
        currentTime += 31 * 60 * 1000L
        platform.now = currentTime
        val result = ex.execute(weather("today"))
        assertTrue("Execution must be handled", result.handled)
        assertFalse("Execution must not fallback to cloud", result.fallbackToCloud)
        assertEquals("Network HTTP call must be triggered when expired", 1, httpCalls)
        val freshEntry = WeatherRepository.getCacheEntry()
        assertNotNull("Cache entry in repository must not be null", freshEntry)
        assertEquals("Cache timestamp must be refreshed to current time", currentTime, freshEntry!!.timestampMs)
        val secondResult = ex.execute(weather("today"))
        assertTrue(secondResult.handled)
        assertEquals("Second call within TTL must hit cache without extra HTTP call", 1, httpCalls)
    }

    @Test
    fun outOfRangeFallsBackToCloud() {
        val src = OpenMeteoWeatherSource(
            clock = { 1000L },
            http = { forecastJson5Days },
            weatherRepo = WeatherRepository
        )
        val shanghai = City("上海", 31.2304, 121.4737)
        val ex = LocalToolExecutor(
            platform = FakePlatform().apply { now = 1000L },
            weather = src,
            weatherRepo = WeatherRepository,
            defaultCity = { shanghai }
        )
        val initialForecast = OpenMeteoWeatherSource.parseForecast("上海", forecastJson5Days)!!
        WeatherRepository.setCacheEntry(
            WeatherCacheEntry(
                info = initialForecast.toWeatherInfo(1000L),
                forecast = initialForecast,
                timestampMs = 1000L,
                locationKey = "31.2304,121.4737"
            )
        )
        val result1 = ex.execute(weather("第6天"))
        assertTrue("Query for day 6 must fallback to cloud", result1.fallbackToCloud)
        assertFalse("Query for day 6 must not be handled locally", result1.handled)
        val result2 = ex.execute(weather("day_5"))
        assertTrue("Query for day_5 (index 5) must fallback to cloud", result2.fallbackToCloud)
        assertFalse("Query for day_5 must not be handled locally", result2.handled)
    }

    @Test
    fun cityMismatchTriggersIndependentNetworkFetch() {
        val requestedUrls = mutableListOf<String>()
        val src = OpenMeteoWeatherSource(
            clock = { 1000L },
            http = { url -> requestedUrls += url; forecastJson5Days },
            weatherRepo = WeatherRepository
        )
        val shanghai = City("上海", 31.2304, 121.4737)
        val ex = LocalToolExecutor(
            platform = FakePlatform().apply { now = 1000L },
            weather = src,
            weatherRepo = WeatherRepository,
            defaultCity = { shanghai }
        )
        val shanghaiForecast = OpenMeteoWeatherSource.parseForecast("上海", forecastJson5Days)!!
        WeatherRepository.setCacheEntry(
            WeatherCacheEntry(
                info = shanghaiForecast.toWeatherInfo(1000L),
                forecast = shanghaiForecast,
                timestampMs = 1000L,
                locationKey = "31.2304,121.4737"
            )
        )
        val result = ex.execute(weather("today", city = "北京"))
        assertTrue("Execution for Beijing must be handled", result.handled)
        assertFalse("Execution must not fallback to cloud", result.fallbackToCloud)
        assertEquals("Independent network fetch must be triggered for Beijing", 1, requestedUrls.size)
        val beijing = CityTable.find("北京")!!
        val expectedLat = String.format(java.util.Locale.US, "%.4f", beijing.latitude)
        val expectedLon = String.format(java.util.Locale.US, "%.4f", beijing.longitude)
        assertTrue(
            "Requested URL must query Beijing coordinates",
            requestedUrls[0].contains("latitude=$expectedLat") && requestedUrls[0].contains("longitude=$expectedLon")
        )
        assertTrue("Speech reply must be for Beijing", result.reply.startsWith("北京"))
    }
}
