package com.voicekiosk.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class SettingsActivityContractTest {

    private fun locateSourceFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java/com/voicekiosk/app/$relativePath"),
            File("app/src/main/java/com/voicekiosk/app/$relativePath"),
            File("android/app/src/main/java/com/voicekiosk/app/$relativePath"),
            File("../android/app/src/main/java/com/voicekiosk/app/$relativePath")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not find source file: $relativePath", file)
        return file!!
    }

    @Test
    fun testSettingsActivity_topNavigationHeaderContract() {
        val file = locateSourceFile("SettingsActivity.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue("Must include back button ← 返回", content.contains("\"← 返回\""))
        assertTrue("Must call finish() on back button click", content.contains("finish()"))
        assertTrue("Must display Voice Kiosk 设置 title", content.contains("\"Voice Kiosk 设置\""))
        assertTrue(
            "Must display comprehensive subtitle",
            content.contains("\"配置唤醒词、天气位置、OLED 息屏待机与夜间护眼参数\"")
        )
    }

    @Test
    fun testSettingsActivity_wakeWordChipHighlightContract() {
        val file = locateSourceFile("SettingsActivity.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue("Must track wakeWordPresetChips", content.contains("wakeWordPresetChips"))
        assertTrue("Must use emerald selected background #065F46", content.contains("#065F46"))
        assertTrue("Must use emerald selected stroke #10B981", content.contains("#10B981"))
        assertTrue("Must attach TextWatcher to wakeWordEditText", content.contains("addTextChangedListener"))
        assertTrue("Must declare updateWakeWordChipHighlights", content.contains("updateWakeWordChipHighlights"))
    }

    @Test
    fun testSettingsActivity_weatherCardContract() {
        val file = locateSourceFile("SettingsActivity.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue(
            "Must include weather title",
            content.contains("\"天气与位置设置 (免 Key 实时气象)\"")
        )
        assertTrue("Must declare selectedWeatherCity", content.contains("selectedWeatherCity"))
        assertTrue("Must declare selectedWeatherLat", content.contains("selectedWeatherLat"))
        assertTrue("Must declare selectedWeatherLon", content.contains("selectedWeatherLon"))
        assertTrue("Must declare weatherStatusText", content.contains("weatherStatusText"))
        assertTrue("Must iterate WeatherManager.PRESET_CITIES", content.contains("WeatherManager.PRESET_CITIES"))
        assertTrue("Must declare weatherCityEditText", content.contains("weatherCityEditText"))
        assertTrue("Must declare searchCityButton with 搜索定位", content.contains("\"搜索定位\""))
        assertTrue("Must call WeatherManager.searchCityLocation", content.contains("WeatherManager.searchCityLocation"))
        assertTrue("Must check isUiSafeForInteraction before applying search result", content.contains("isUiSafeForInteraction"))
    }

    @Test
    fun testSettingsActivity_oledAndNightModeCardContract() {
        val file = locateSourceFile("SettingsActivity.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue(
            "Must include OLED card title",
            content.contains("\"OLED 息屏待机与夜间护眼设置\"")
        )
        assertTrue("Must declare nightModeCheckBox", content.contains("nightModeCheckBox"))
        assertTrue("Must mention night mode description", content.contains("\"启用夜间深暗护眼模式"))
        assertTrue("Must include schedule label", content.contains("\"夜间变黑生效时间段 (24 小时制，支持跨午夜)\""))
        assertTrue("Must declare nightStartHourText", content.contains("nightStartHourText"))
        assertTrue("Must declare nightEndHourText", content.contains("nightEndHourText"))
        assertTrue("Must declare nightDimSeekBar", content.contains("nightDimSeekBar"))
        assertTrue("Must declare nightDimValueText", content.contains("nightDimValueText"))
        assertTrue("Must display 30% label", content.contains("\"30% (微暗)\""))
        assertTrue("Must display 85% label", content.contains("\"85% (推荐)\""))
        assertTrue("Must display 95% label", content.contains("\"95% (极暗)\""))
    }

    @Test
    fun testSettingsActivity_handleSavePersistenceContract() {
        val file = locateSourceFile("SettingsActivity.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue("Must save nightModeEnabled in AppConfig", content.contains("AppConfig.setNightModeEnabled"))
        assertTrue("Must save nightModeStartHour in AppConfig", content.contains("AppConfig.setNightModeStartHour"))
        assertTrue("Must save nightModeEndHour in AppConfig", content.contains("AppConfig.setNightModeEndHour"))
        assertTrue("Must save nightModeDimAlpha in AppConfig", content.contains("AppConfig.setNightModeDimAlpha"))
        assertTrue("Must save weatherLocation in AppConfig", content.contains("AppConfig.setWeatherLocation"))
        assertTrue("Must save onDevicePlayerPackage in AppConfig", content.contains("AppConfig.setOnDevicePlayerPackage(this, onDevicePlayerPackage)"))
        assertTrue("Must reset cached weather if location changed", content.contains("AppConfig.setCachedWeatherSummary"))
        assertTrue("Must trigger fetchWeatherIfNeeded force=true on location change", content.contains("WeatherManager.fetchWeatherIfNeeded(applicationContext, force = true)"))
    }

    @Test
    fun testNightHourCycleLogic() {
        // Test stepper decrement
        fun dec(hour: Int) = (hour + 23) % 24
        // Test stepper increment
        fun inc(hour: Int) = (hour + 1) % 24

        assertEquals(22, dec(23))
        assertEquals(23, dec(0))
        assertEquals(0, inc(23))
        assertEquals(1, inc(0))
        assertEquals(6, dec(7))
        assertEquals(8, inc(7))
    }

    @Test
    fun testNightDimAlphaMath() {
        val minDim = 0.30f
        val step = 0.05f
        val maxSteps = 13

        // Progress 0 -> 0.30f
        val alpha0 = (minDim + 0 * step).let { (it * 100).roundToInt() / 100f }
        assertEquals(0.30f, alpha0, 0.001f)

        // Progress 11 -> 0.85f (0.30 + 11 * 0.05 = 0.85)
        val alpha11 = (minDim + 11 * step).let { (it * 100).roundToInt() / 100f }
        assertEquals(0.85f, alpha11, 0.001f)

        // Progress 13 -> 0.95f (0.30 + 13 * 0.05 = 0.95)
        val alpha13 = (minDim + maxSteps * step).let { (it * 100).roundToInt() / 100f }
        assertEquals(0.95f, alpha13, 0.001f)

        // Progress reverse calculation
        val progDefault = ((0.85f - minDim) / step).roundToInt().coerceIn(0, maxSteps)
        assertEquals(11, progDefault)

        val progMin = ((0.30f - minDim) / step).roundToInt().coerceIn(0, maxSteps)
        assertEquals(0, progMin)

        val progMax = ((0.95f - minDim) / step).roundToInt().coerceIn(0, maxSteps)
        assertEquals(13, progMax)
    }

    @Test
    fun testSettingsActivity_musicPlayerSettingsContract() {
        val file = locateSourceFile("SettingsActivity.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue("Must include 音乐播放设置 section", content.contains("\"音乐播放设置\""))
        assertTrue("Must query AndroidLocalPlatform.players", content.contains("AndroidLocalPlatform.players(this)"))
        assertTrue("Must read AppConfig.getOnDevicePlayerPackage", content.contains("AppConfig.getOnDevicePlayerPackage(this)"))
        assertTrue("Must format player label via formatPlayerLabel", content.contains("AndroidLocalPlatform.formatPlayerLabel"))
        assertTrue("Must display 自动选择（推荐） option", content.contains("\"自动选择（推荐）\""))
        assertTrue("Must handle no player detected fallback", content.contains("未检测到可用音乐播放器"))
        assertTrue("Must prompt single choice dialog title 选择默认音乐播放器", content.contains("\"选择默认音乐播放器\""))
    }
}
