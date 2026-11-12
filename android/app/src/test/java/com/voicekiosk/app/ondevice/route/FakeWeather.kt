package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.tools.City
import com.voicekiosk.app.ondevice.tools.DayForecast
import com.voicekiosk.app.ondevice.tools.Forecast
import com.voicekiosk.app.ondevice.tools.WeatherSource

class FakeWeather : WeatherSource {
    var fail = false
    val asked = mutableListOf<String>()
    val geocoded = mutableListOf<String>()
    var geocodeResult: City? = City("纽约", 40.7, -74.0)

    override fun forecast(city: City): Result<Forecast> {
        asked += city.name
        if (fail) return Result.failure(IllegalStateException("timeout"))
        return Result.success(
            Forecast(
                city.name, 12.4, 2,
                listOf(
                    DayForecast("2026-11-10", 2, 15.2, 8.6, 10),
                    DayForecast("2026-11-11", 61, 11.0, 6.0, 80),
                    DayForecast("2026-11-12", 0, -1.0, -6.4, 0)
                )
            )
        )
    }

    override fun geocode(name: String): Result<City?> { geocoded += name; return Result.success(geocodeResult) }
}
