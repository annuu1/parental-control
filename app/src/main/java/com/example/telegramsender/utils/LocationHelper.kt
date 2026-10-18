package com.example.telegramsender.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocationHelper {
    private const val TAG = "LocationHelper"
    
    @Volatile
    private var lastLocation: Location? = null
    private var isListening = false
    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")
            synchronized(this) {
                if (lastLocation == null || location.accuracy <= (lastLocation?.accuracy ?: Float.MAX_VALUE) ||
                    (location.time - (lastLocation?.time ?: 0)) > 60000) {
                    lastLocation = location
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Provider disabled: $provider")
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun startListening(context: Context) {
        // Location is acquired on-demand in getBestLocation() to conserve battery
    }

    /**
     * Power-efficient location acquisition:
     * 1. Check recent cached fix (< 60s)
     * 2. Check lastKnownLocation across GPS & Network
     * 3. If stale, request fresh on-demand fix with timeout and release listeners immediately
     */
    @SuppressLint("MissingPermission")
    fun getBestLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null

        val now = System.currentTimeMillis()

        // 1. Check in-memory cached location
        val cached = lastLocation
        if (cached != null && (now - cached.time) < 60000) {
            return cached
        }

        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return cached

        // 2. Check lastKnownLocation from system providers
        try {
            var best: Location? = cached
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || (now - l.time) < 60000 || (l.accuracy < best.accuracy && (now - l.time) < 180000)) {
                    best = l
                }
            }
            if (best != null && (now - best.time) < 60000) {
                lastLocation = best
                return best
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking last known location", e)
        }

        // 3. Request fresh on-demand fix with short timeout
        try {
            val latch = CountDownLatch(1)
            var freshLoc: Location? = null

            val singleListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    freshLoc = location
                    lastLocation = location
                    latch.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.PASSIVE_PROVIDER
            }

            lm.requestLocationUpdates(provider, 1000L, 0f, singleListener, Looper.getMainLooper())

            // Wait up to 5 seconds for a fresh fix, then stop listener so GPS hardware sleeps
            latch.await(5, TimeUnit.SECONDS)
            lm.removeUpdates(singleListener)

            if (freshLoc != null) {
                return freshLoc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining on-demand location fix", e)
        }

        return lastLocation
    }

    fun stopListening() {
        if (!isListening) return
        try {
            locationManager?.removeUpdates(locationListener)
            isListening = false
            Log.d(TAG, "Location listener stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location listener", e)
        }
    }
}
