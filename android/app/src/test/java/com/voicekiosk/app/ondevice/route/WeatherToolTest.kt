package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.tools.City
import com.voicekiosk.app.ondevice.tools.CityTable
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import com.voicekiosk.app.ondevice.tools.OpenMeteoWeatherSource
import com.voicekiosk.app.ondevice.tools.WeatherSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherToolTest {
    private val forecastJson = """
        {"current":{"time":"2026-11-10T15:00","temperature_2m":-2.4,"weather_code":3},
         "daily":{"time":["2026-11-10","2026-11-11","2026-11-12"],"weather_code":[3,63,71],
          "temperature_2m_max":[1.2,4.0,-0.6],"temperature_2m_min":[-5.5,-1.0,-7.0],
          "precipitation_probability_max":[5,65,40]}}
    """.trimIndent()

    private fun weather(day: String, kind: String = "general", city: String? = null): RouteDecision {
        val slots = mutableMapOf("day" to day, "kind" to kind)
        if (city != null) slots["city"] = city
        return RouteDecision(Intents.WEATHER, slots, Route.LOCAL, 0.98, RouteSource.RULE)
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
}
