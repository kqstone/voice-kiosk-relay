package com.voicekiosk.app.weather

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.DayForecastItem
import com.voicekiosk.app.WeatherInfo
import com.voicekiosk.app.WeatherManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WeatherRepositoryTest {

    private lateinit var dummyContext: Context
    private lateinit var fakePrefs: RepoFakePrefs

    @Before
    fun setUp() {
        fakePrefs = RepoFakePrefs()
        AppConfig.prefsOverride = fakePrefs
        dummyContext = Application()
        WeatherRepository.clearCache()
        WeatherRepository.mainDispatcher = { it() } // Direct synchronous dispatcher for tests
        WeatherRepository.clock = System::currentTimeMillis
        AppConfig.setWeatherLocation(dummyContext, "上海", 31.2304f, 121.4737f)
    }

    @After
    fun tearDown() {
        WeatherRepository.clearCache()
        WeatherRepository.mainDispatcher = null
        WeatherRepository.clock = System::currentTimeMillis
        WeatherRepository.httpFetcher = { _, _ -> null }
        AppConfig.prefsOverride = null
    }

    private fun createSampleWeatherInfo(
        city: String = "上海",
        temp: Int = 22,
        updatedAt: Long = 1000000L,
        aqi: Int? = 42
    ): WeatherInfo {
        return WeatherInfo(
            city = city,
            temperatureC = temp,
            minTempC = 15,
            maxTempC = 25,
            weatherCode = 0,
            conditionZh = "晴",
            iconSymbol = "☀",
            updatedAtMs = updatedAt,
            apparentTempC = 21,
            relativeHumidity = 55,
            windSpeedKmh = 10.5f,
            precipitationProb = 5,
            sunrise = "06:15",
            sunset = "17:05",
            aqi = aqi,
            aqiLevelZh = if (aqi != null) WeatherManager.mapAqiToLevelZh(aqi) else null,
            forecastDays = listOf(
                DayForecastItem("2026-11-15", 0, "晴", "☀", 15, 25, 5, "06:15", "17:05"),
                DayForecastItem("2026-11-16", 1, "多云", "⛅", 16, 26, 15, "06:16", "17:04")
            )
        )
    }

    @Test
    fun testCacheTtlAndExpiration() {
        var simulatedNow = 1000000L
        WeatherRepository.clock = { simulatedNow }

        val info = createSampleWeatherInfo(updatedAt = simulatedNow)
        val entry = WeatherCacheEntry(
            info = info,
            forecast = info.toForecast(),
            timestampMs = simulatedNow,
            locationKey = "31.2304,121.4737"
        )
        WeatherRepository.setCacheEntry(entry, dummyContext)

        // 1. Immediately after caching: fresh
        val fresh0 = WeatherRepository.getFreshCacheEntry(simulatedNow)
        assertNotNull(fresh0)
        assertEquals("上海", fresh0!!.info.city)

        // 2. 29 minutes later: still fresh (< 30 min TTL)
        simulatedNow += 29 * 60 * 1000L
        val fresh29 = WeatherRepository.getFreshCacheEntry(simulatedNow)
        assertNotNull(fresh29)

        // 3. Exactly 30 minutes later: expired
        simulatedNow = 1000000L + 30 * 60 * 1000L
        val fresh30 = WeatherRepository.getFreshCacheEntry(simulatedNow)
        assertNull("Cache should expire after 30 minutes", fresh30)

        // Stale entry can still be read if explicitly requested via getCacheEntry()
        assertNotNull(WeatherRepository.getCacheEntry())
    }

    @Test
    fun testGetFreshForecastIfAvailable_cityMatching() {
        val simulatedNow = 1000000L
        WeatherRepository.clock = { simulatedNow }

        val info = createSampleWeatherInfo(city = "上海", updatedAt = simulatedNow)
        val entry = WeatherCacheEntry(
            info = info,
            forecast = info.toForecast(),
            timestampMs = simulatedNow,
            locationKey = "31.2304,121.4737"
        )
        WeatherRepository.setCacheEntry(entry, dummyContext)

        // Matching without suffix
        val fc1 = WeatherRepository.getFreshForecastIfAvailable("上海", simulatedNow)
        assertNotNull(fc1)
        assertEquals("上海", fc1!!.city)

        // Matching with "市" suffix
        val fc2 = WeatherRepository.getFreshForecastIfAvailable("上海市", simulatedNow)
        assertNotNull(fc2)
        assertEquals("上海", fc2!!.city)

        // Different city: mismatch returns null
        val fc3 = WeatherRepository.getFreshForecastIfAvailable("北京", simulatedNow)
        assertNull("City mismatch should return null", fc3)

        // Null targetCity implies current default city
        val fc4 = WeatherRepository.getFreshForecastIfAvailable(null, simulatedNow)
        assertNotNull(fc4)
    }

    @Test
    fun testFetchWeather_concurrentAqiTimeout_mainSuccess_keepsAqiNull() {
        val sampleForecastJson = """
            {
              "current": {
                "temperature_2m": 18.5,
                "apparent_temperature": 17.0,
                "relative_humidity_2m": 70,
                "weather_code": 3,
                "wind_speed_10m": 8.0
              },
              "daily": {
                "time": ["2026-11-15", "2026-11-16"],
                "weather_code": [3, 61],
                "temperature_2m_max": [20.0, 17.0],
                "temperature_2m_min": [14.0, 12.0],
                "precipitation_probability_max": [30, 80],
                "sunrise": ["2026-11-15T06:20", "2026-11-16T06:21"],
                "sunset": ["2026-11-15T17:10", "2026-11-16T17:09"]
              }
            }
        """.trimIndent()

        // Mock HTTP fetcher: Forecast succeeds, AQI fails / times out (returns null)
        WeatherRepository.httpFetcher = { url, _ ->
            if (url.contains("forecast")) {
                sampleForecastJson
            } else {
                null // Simulated 3s AQI timeout / network error
            }
        }

        var resultInfo: WeatherInfo? = null
        var resultError: Throwable? = null
        val doneLatch = CountDownLatch(1)

        WeatherRepository.fetchWeather(dummyContext, force = true) { res ->
            res.onSuccess { resultInfo = it }
            res.onFailure { resultError = it }
            doneLatch.countDown()
        }

        assertTrue("Fetch must complete within timeout", doneLatch.await(3, TimeUnit.SECONDS))

        assertNull(resultError)
        assertNotNull(resultInfo)
        resultInfo!!

        // Main forecast data is loaded
        assertEquals(19, resultInfo!!.temperatureC) // 18.5 rounds to 19
        assertEquals("阴", resultInfo!!.conditionZh)
        assertEquals("☁", resultInfo!!.iconSymbol)
        assertEquals(17, resultInfo!!.apparentTempC)
        assertEquals(70, resultInfo!!.relativeHumidity)
        assertEquals(2, resultInfo!!.forecastDays.size)

        // AQI is null and aqiLevelZh is null because AQI timed out/failed
        assertNull("AQI must be null on AQI failure/timeout", resultInfo!!.aqi)
        assertNull("aqiLevelZh must be null on AQI failure/timeout", resultInfo!!.aqiLevelZh)
    }

    @Test
    fun testFetchWeather_successWithBothForecastAndAqi() {
        val sampleForecastJson = """
            {
              "current": {
                "temperature_2m": 25.0,
                "apparent_temperature": 26.0,
                "relative_humidity_2m": 50,
                "weather_code": 0,
                "wind_speed_10m": 12.0
              },
              "daily": {
                "time": ["2026-11-15"],
                "weather_code": [0],
                "temperature_2m_max": [28.0],
                "temperature_2m_min": [18.0]
              }
            }
        """.trimIndent()

        val sampleAqiJson = """
            {
              "current": {
                "us_aqi": 35,
                "pm2_5": 8.0
              }
            }
        """.trimIndent()

        WeatherRepository.httpFetcher = { url, _ ->
            if (url.contains("forecast")) sampleForecastJson else sampleAqiJson
        }

        var resultInfo: WeatherInfo? = null
        val doneLatch = CountDownLatch(1)
        WeatherRepository.fetchWeather(dummyContext, force = true) { res ->
            res.onSuccess { resultInfo = it }
            doneLatch.countDown()
        }

        assertTrue("Fetch must complete within timeout", doneLatch.await(3, TimeUnit.SECONDS))

        assertNotNull(resultInfo)
        resultInfo!!
        assertEquals(25, resultInfo!!.temperatureC)
        assertEquals(35, resultInfo!!.aqi)
        assertEquals("优", resultInfo!!.aqiLevelZh)
    }

    @Test
    fun testThreadSafety_concurrentFetchesPreventReentrancy() {
        val sampleForecastJson = """
            {
              "current": {
                "temperature_2m": 20.0,
                "weather_code": 1
              }
            }
        """.trimIndent()

        val networkFetchCount = AtomicInteger(0)
        WeatherRepository.httpFetcher = { url, _ ->
            if (url.contains("forecast")) {
                networkFetchCount.incrementAndGet()
                Thread.sleep(50) // simulate network latency
                sampleForecastJson
            } else {
                null
            }
        }

        val threadCount = 15
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val threadPool = Executors.newFixedThreadPool(threadCount)

        val successCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            threadPool.execute {
                try {
                    startLatch.await()
                    WeatherRepository.fetchWeather(dummyContext, force = true) { res ->
                        if (res.isSuccess) successCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("All threads must finish", doneLatch.await(5, TimeUnit.SECONDS))
        threadPool.shutdown()

        // Give worker thread pool time to finish
        Thread.sleep(150)

        // isFetching CAS flag must ensure only 1 actual network fetch was initiated
        assertEquals("Concurrent fetches should be deduplicated by isFetching flag", 1, networkFetchCount.get())
    }

    @Test
    fun testThreadSafety_concurrentReadWrites() {
        val threadCount = 20
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await()
                    if (i % 2 == 0) {
                        val info = createSampleWeatherInfo(temp = 20 + i, updatedAt = System.currentTimeMillis())
                        val entry = WeatherCacheEntry(info, info.toForecast(), info.updatedAtMs, "31.2304,121.4737")
                        WeatherRepository.setCacheEntry(entry)
                    } else {
                        WeatherRepository.getFreshForecastIfAvailable("上海")
                        val cachedInfo = WeatherRepository.getCachedWeatherInfo()
                        WeatherRepository.getCacheEntry()
                        // Just reading; must not throw ConcurrentModificationException or corrupt state
                        assertNotNull(cachedInfo?.city ?: "ok")
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertNotNull(WeatherRepository.getCacheEntry())
    }

    @Test
    fun testColdStart_persistenceRecovery() {
        val info = createSampleWeatherInfo(city = "广州", temp = 28, updatedAt = 1700000000L, aqi = 60)
        val originalEntry = WeatherCacheEntry(
            info = info,
            forecast = info.toForecast(),
            timestampMs = 1700000000L,
            locationKey = "23.1291,113.2644"
        )

        // Persist to AppConfig
        AppConfig.setCachedWeatherJson(dummyContext, originalEntry.toJson())
        AppConfig.setCachedWeatherSummary(dummyContext, info.formatDisplayLine())
        AppConfig.setLastWeatherFetchMs(dummyContext, 1700000000L)

        // Clear in-memory cache to simulate cold process start
        WeatherRepository.clearCache()
        assertNull(WeatherRepository.getCacheEntry())

        // Load from persistence
        val recovered = WeatherRepository.loadFromPersistence(dummyContext)
        assertNotNull(recovered)
        assertEquals("广州", recovered!!.info.city)
        assertEquals(28, recovered.info.temperatureC)
        assertEquals(60, recovered.info.aqi)
        assertEquals("良", recovered.info.aqiLevelZh)
        assertEquals("23.1291,113.2644", recovered.locationKey)

        // Ensure in-memory cache is populated
        assertNotNull(WeatherRepository.getCacheEntry())
        assertEquals("广州", WeatherRepository.getCachedWeatherInfo(dummyContext)?.city)
    }

    @Test
    fun testWeatherListenerLifecycle() {
        val receivedList = mutableListOf<WeatherInfo>()
        val listener = object : WeatherManager.WeatherListener {
            override fun onWeatherUpdated(info: WeatherInfo) {
                receivedList.add(info)
            }
        }

        WeatherRepository.addListener(listener)

        val info1 = createSampleWeatherInfo(temp = 22)
        WeatherRepository.setCacheEntry(WeatherCacheEntry(info1, info1.toForecast(), info1.updatedAtMs, "key"))
        assertEquals(1, receivedList.size)
        assertEquals(22, receivedList[0].temperatureC)

        // Remove listener
        WeatherRepository.removeListener(listener)

        val info2 = createSampleWeatherInfo(temp = 26)
        WeatherRepository.setCacheEntry(WeatherCacheEntry(info2, info2.toForecast(), info2.updatedAtMs, "key"))
        // receivedList size should still be 1
        assertEquals(1, receivedList.size)
    }

    private class RepoFakePrefs : SharedPreferences {
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

        override fun edit(): SharedPreferences.Editor = RepoFakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class RepoFakeEditor(private val parent: RepoFakePrefs) : SharedPreferences.Editor {
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
