package com.voicekiosk.app

import com.voicekiosk.app.ui.LunarCalendarHelper
import com.voicekiosk.app.ui.LunarDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LunarCalendarHelperTest {

    @Test
    fun testSolarToLunar_knownDates() {
        // Base epoch date
        val d1900 = LunarCalendarHelper.solarToLunar(1900, 1, 31)
        assertEquals(LunarDate(1900, 1, 1, false), d1900)
        assertEquals("农历正月初一", d1900.toChineseString())

        // 2008 Olympics year date
        val d2008 = LunarCalendarHelper.solarToLunar(2008, 10, 2)
        assertEquals(LunarDate(2008, 9, 4, false), d2008)
        assertEquals("农历九月初四", d2008.toChineseString())

        // Leap month test: 1976 Leap August
        val d1976 = LunarCalendarHelper.solarToLunar(1976, 10, 1)
        assertEquals(LunarDate(1976, 8, 8, true), d1976)
        assertEquals("农历闰八月初八", d1976.toChineseString())

        // 2020-11-12
        val d2020 = LunarCalendarHelper.solarToLunar(2020, 11, 12)
        assertEquals(LunarDate(2020, 9, 27, false), d2020)
        assertEquals("农历九月廿七", d2020.toChineseString())

        // 2026-11-13 (Today)
        val d2026 = LunarCalendarHelper.solarToLunar(2026, 11, 13)
        assertEquals(LunarDate(2026, 10, 5, false), d2026)
        assertEquals("农历十月初五", d2026.toChineseString())
    }

    @Test
    fun testFormatFullDateLine() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 13, 12, 0, 0)
        }
        val dateLine = LunarCalendarHelper.formatFullDateLine(cal)
        assertTrue(dateLine.startsWith("11月13日 星期五 · 农历十月初五"))
    }

    @Test
    fun testWeatherManager_parseCachedSummary() {
        val summary1 = "上海 · ☀ 晴 21°C (15°~25°C)"
        val parsed1 = WeatherManager.parseCachedSummary(summary1)
        assertNotNull(parsed1)
        parsed1!!
        assertEquals("上海", parsed1.city)
        assertEquals("☀", parsed1.iconSymbol)
        assertEquals("晴", parsed1.conditionZh)
        assertEquals(21, parsed1.temperatureC)
        assertEquals(15, parsed1.minTempC)
        assertEquals(25, parsed1.maxTempC)

        val summary2 = "北京 · 🌧 小到中雨 12°C"
        val parsed2 = WeatherManager.parseCachedSummary(summary2)
        assertNotNull(parsed2)
        parsed2!!
        assertEquals("北京", parsed2.city)
        assertEquals("🌧", parsed2.iconSymbol)
        assertEquals("小到中雨", parsed2.conditionZh)
        assertEquals(12, parsed2.temperatureC)
        assertEquals(null, parsed2.minTempC)
        assertEquals(null, parsed2.maxTempC)
    }

    @Test
    fun testWeatherManager_listenerLifecycle() {
        var received: WeatherInfo? = null
        val listener = object : WeatherManager.WeatherListener {
            override fun onWeatherUpdated(info: WeatherInfo) {
                received = info
            }
        }
        WeatherManager.addListener(listener)
        WeatherManager.removeListener(listener)
    }
}
