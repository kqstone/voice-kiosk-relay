package com.voicekiosk.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import com.voicekiosk.app.ondevice.tools.CityTable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure native coarse location helper for Voice Kiosk.
 *
 * Strict policy:
 * - Only uses ACCESS_COARSE_LOCATION (no ACCESS_FINE_LOCATION).
 * - Only relies on native LocationManager (NETWORK_PROVIDER / PASSIVE_PROVIDER).
 * - Strictly avoids Google Play services, FusedLocationProvider, or GMS dependencies.
 * - Enforces an 8-second fuse timeout with CancellationSignal.
 * - Provides spatial debounce (15km) to avoid redundant network refresh.
 */
object LocationHelper {
    private const val TAG = "LocationHelper"
    const val DEFAULT_TIMEOUT_MS = 8000L
    const val SPATIAL_DEBOUNCE_KM = 15.0

    /**
     * Checks whether ACCESS_COARSE_LOCATION has been granted.
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolves coarse location via native LocationManagerCompat.getCurrentLocation.
     * Strictly restricted to NETWORK_PROVIDER and PASSIVE_PROVIDER.
     * Enforces an 8-second fuse timeout with CancellationSignal.
     */
    @SuppressLint("MissingPermission")
    fun requestCoarseLocation(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        executor: Executor? = null,
        onResult: (Location?) -> Unit
    ) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Cannot request location: ACCESS_COARSE_LOCATION not granted")
            onResult(null)
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.w(TAG, "LocationManager system service not available")
            onResult(null)
            return
        }

        val provider = resolveCoarseProvider(locationManager)
        if (provider == null) {
            Log.w(TAG, "No coarse provider available (NETWORK/PASSIVE disabled)")
            val fallback = getLastKnownCoarseLocation(locationManager)
            onResult(fallback)
            return
        }

        val cancellationSignal = CancellationSignal()
        val callbackInvoked = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        val runExecutor = executor ?: ContextCompat.getMainExecutor(context)

        val timeoutRunnable = Runnable {
            if (callbackInvoked.compareAndSet(false, true)) {
                Log.w(TAG, "Coarse location timed out after ${timeoutMs}ms, cancelling signal")
                try {
                    cancellationSignal.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "Error cancelling location signal", e)
                }
                val fallback = getLastKnownCoarseLocation(locationManager)
                runExecutor.execute { onResult(fallback) }
            }
        }
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        try {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                cancellationSignal,
                runExecutor,
                Consumer<Location?> { location ->
                    if (callbackInvoked.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        val finalLocation = location ?: getLastKnownCoarseLocation(locationManager)
                        onResult(finalLocation)
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting current location", e)
            if (callbackInvoked.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                onResult(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error requesting current location", e)
            if (callbackInvoked.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                val fallback = getLastKnownCoarseLocation(locationManager)
                onResult(fallback)
            }
        }
    }

    /**
     * Resolves native coarse provider: NETWORK_PROVIDER preferred, PASSIVE_PROVIDER fallback.
     * Strictly forbids GPS, Fused, or GMS providers.
     */
    fun resolveCoarseProvider(locationManager: LocationManager): String? {
        return when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            LocationManagerCompat.hasProvider(locationManager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            LocationManagerCompat.hasProvider(locationManager, LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }
    }

    /**
     * Best-effort fallback to last known coarse location when current location times out or fails.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownCoarseLocation(locationManager: LocationManager): Location? {
        return try {
            var loc: Location? = null
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            if (loc == null && locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                loc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            }
            loc
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Spatial debounce check:
     * When new coordinates and current coordinates have a distance < 15km and city name has not changed,
     * do not repeat network refresh.
     *
     * Returns true if a network refresh SHOULD be initiated, false if debounced.
     */
    fun shouldRefreshWeatherForLocation(
        currentCity: String,
        currentLat: Double,
        currentLon: Double,
        newCity: String,
        newLat: Double,
        newLon: Double,
        thresholdKm: Double = SPATIAL_DEBOUNCE_KM
    ): Boolean {
        val normCurrent = currentCity.trim().removeSuffix("市")
        val normNew = newCity.trim().removeSuffix("市")
        if (normCurrent.isEmpty() || normNew.isEmpty()) {
            return true
        }
        if (!normCurrent.equals(normNew, ignoreCase = true)) {
            return true
        }
        val distanceM = CityTable.distanceMeters(currentLat, currentLon, newLat, newLon)
        return distanceM >= (thresholdKm * 1000.0)
    }

    fun shouldRefreshWeatherForLocation(
        currentCity: String,
        currentLat: Float,
        currentLon: Float,
        newCity: String,
        newLat: Float,
        newLon: Float,
        thresholdKm: Double = SPATIAL_DEBOUNCE_KM
    ): Boolean = shouldRefreshWeatherForLocation(
        currentCity = currentCity,
        currentLat = currentLat.toDouble(),
        currentLon = currentLon.toDouble(),
        newCity = newCity,
        newLat = newLat.toDouble(),
        newLon = newLon.toDouble(),
        thresholdKm = thresholdKm
    )
}
