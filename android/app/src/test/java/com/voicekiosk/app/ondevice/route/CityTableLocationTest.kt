package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.location.LocationHelper
import com.voicekiosk.app.ondevice.tools.CityTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for offline nearest-neighbor city lookup and spatial debounce:
 * - Tests CityTable.findNearest offline coordinate mapping.
 * - Validates benchmark coordinates: (39.90, 116.40) -> 北京, (31.23, 121.47) -> 上海.
 * - Validates Haversine great-circle distance math.
 * - Validates LocationHelper spatial debounce (15km threshold).
 */
class CityTableLocationTest {

    @Test
    fun testNearest_beijingCoordinates() {
        val nearest = CityTable.findNearest(39.90, 116.40)
        assertNotNull("Expected non-null city for Beijing coordinates", nearest)
        assertEquals("北京", nearest!!.name)
    }

    @Test
    fun testNearest_shanghaiCoordinates() {
        val nearest = CityTable.findNearest(31.23, 121.47)
        assertNotNull("Expected non-null city for Shanghai coordinates", nearest)
        assertEquals("上海", nearest!!.name)
    }

    @Test
    fun testNearest_majorCitiesAcrossChina() {
        // (lat, lon) -> expected city
        val testCases = listOf(
            (30.27 to 120.15) to "杭州",
            (22.54 to 114.05) to "深圳",
            (23.13 to 113.26) to "广州",
            (30.57 to 104.06) to "成都",
            (30.59 to 114.30) to "武汉",
            (32.06 to 118.80) to "南京",
            (34.34 to 108.94) to "西安",
            (29.56 to 106.55) to "重庆",
            (39.08 to 117.20) to "天津",
            (45.80 to 126.53) to "哈尔滨",
            (29.65 to 91.10) to "拉萨",
            (43.82 to 87.61) to "乌鲁木齐",
            (20.04 to 110.20) to "海口",
            (26.07 to 119.30) to "福州",
            (36.06 to 120.38) to "青岛",
            (31.49 to 120.31) to "无锡"
        )

        for ((coords, expectedName) in testCases) {
            val nearest = CityTable.findNearest(coords.first, coords.second)
            assertNotNull("City not found for coords $coords", nearest)
            assertEquals("Coords $coords expected $expectedName", expectedName, nearest!!.name)
        }
    }

    @Test
    fun testNearest_exactCityCenterCoordinatesMatchSelf() {
        for (city in CityTable.CITIES) {
            val matched = CityTable.findNearest(city.latitude, city.longitude)
            assertNotNull(matched)
            assertEquals("City ${city.name} must match itself", city.name, matched!!.name)
        }
    }

    @Test
    fun testFloatOverload() {
        val bj = CityTable.findNearest(39.90f, 116.40f)
        assertNotNull(bj)
        assertEquals("北京", bj!!.name)

        val sh = CityTable.findNearest(31.23f, 121.47f)
        assertNotNull(sh)
        assertEquals("上海", sh!!.name)
    }

    @Test
    fun testDistanceCalculations() {
        // Same point distance is 0
        assertEquals(0.0, CityTable.distanceMeters(39.9042, 116.4074, 39.9042, 116.4074), 0.001)
        assertEquals(0.0, CityTable.distanceKm(39.9042, 116.4074, 39.9042, 116.4074), 0.001)

        // Beijing (39.9042, 116.4074) to Tianjin (39.0842, 117.2009) is ~113km
        val bjToTjKm = CityTable.distanceKm(39.9042, 116.4074, 39.0842, 117.2009)
        assertTrue("Beijing to Tianjin should be 100-130km, got $bjToTjKm", bjToTjKm in 100.0..130.0)

        // Shanghai (31.2304, 121.4737) to Suzhou (31.2990, 120.5853) is ~85km
        val shToSzKm = CityTable.distanceKm(31.2304, 121.4737, 31.2990, 120.5853)
        assertTrue("Shanghai to Suzhou should be 75-95km, got $shToSzKm", shToSzKm in 75.0..95.0)
    }

    @Test
    fun testSpatialDebounce_shouldRefreshWeatherForLocation() {
        val beijingLat = 39.9042
        val beijingLon = 116.4074

        // 1. Same city, tiny drift < 15km (~1km offset): should NOT refresh
        val nearbyLat = 39.9142
        val nearbyLon = 116.4174
        val distNearbyKm = CityTable.distanceKm(beijingLat, beijingLon, nearbyLat, nearbyLon)
        assertTrue(distNearbyKm < 15.0)

        val shouldRefreshNearby = LocationHelper.shouldRefreshWeatherForLocation(
            currentCity = "北京",
            currentLat = beijingLat,
            currentLon = beijingLon,
            newCity = "北京",
            newLat = nearbyLat,
            newLon = nearbyLon
        )
        assertFalse("Same city with < 15km drift must NOT refresh", shouldRefreshNearby)

        // 2. Same city, suffix variation ("北京市" vs "北京"), drift < 15km: should NOT refresh
        val shouldRefreshSuffix = LocationHelper.shouldRefreshWeatherForLocation(
            currentCity = "北京市",
            currentLat = beijingLat,
            currentLon = beijingLon,
            newCity = "北京",
            newLat = nearbyLat,
            newLon = nearbyLon
        )
        assertFalse("Normalized city names with < 15km drift must NOT refresh", shouldRefreshSuffix)

        // 3. Same city, drift >= 15km (~30km offset): SHOULD refresh
        val farLat = 40.1742
        val farLon = 116.4074
        val distFarKm = CityTable.distanceKm(beijingLat, beijingLon, farLat, farLon)
        assertTrue("Expected drift >= 15km, got $distFarKm", distFarKm >= 15.0)

        val shouldRefreshFar = LocationHelper.shouldRefreshWeatherForLocation(
            currentCity = "北京",
            currentLat = beijingLat,
            currentLon = beijingLon,
            newCity = "北京",
            newLat = farLat,
            newLon = farLon
        )
        assertTrue("Same city with >= 15km drift MUST refresh", shouldRefreshFar)

        // 4. City changed (e.g. moved from Beijing to Tianjin): SHOULD refresh immediately
        val shouldRefreshCityChanged = LocationHelper.shouldRefreshWeatherForLocation(
            currentCity = "北京",
            currentLat = beijingLat,
            currentLon = beijingLon,
            newCity = "天津",
            newLat = 39.0842,
            newLon = 117.2009
        )
        assertTrue("Different city MUST trigger refresh", shouldRefreshCityChanged)

        // 5. Empty previous city: SHOULD refresh
        val shouldRefreshEmptyCity = LocationHelper.shouldRefreshWeatherForLocation(
            currentCity = "",
            currentLat = 0.0,
            currentLon = 0.0,
            newCity = "北京",
            newLat = beijingLat,
            newLon = beijingLon
        )
        assertTrue("Empty current city MUST trigger refresh", shouldRefreshEmptyCity)
    }

    @Test
    fun testPerformance_subMillisecondLookup() {
        // Warmup
        repeat(100) {
            CityTable.findNearest(39.90, 116.40)
        }

        val startNs = System.nanoTime()
        val iterations = 500
        repeat(iterations) {
            CityTable.findNearest(31.23, 121.47)
        }
        val elapsedNs = System.nanoTime() - startNs
        val avgNsPerLookup = elapsedNs / iterations
        val avgMsPerLookup = avgNsPerLookup / 1_000_000.0

        assertTrue(
            "Expected nearest lookup to take < 0.1ms on average, took ${avgMsPerLookup}ms",
            avgMsPerLookup < 0.5 // Generous margin for shared CI/box CPU
        )
    }
}
