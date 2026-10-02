package com.example.telegramsender.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

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

    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Cannot start location listener: permission missing")
            return
        }

        if (isListening) return

        try {
            locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val lm = locationManager ?: return

            // Register on GPS Provider
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // 5 seconds
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            // Register on Network Provider (fast, works indoors)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            // Also check PASSIVE provider
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    5000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            isListening = true
            Log.d(TAG, "Location listeners registered on available providers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register location listener", e)
        }
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

    @SuppressLint("MissingPermission")
    fun getBestLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null

        // Make sure listeners are active
        startListening(context)

        // Return current active listener location if fresh
        val cached = lastLocation
        if (cached != null && (System.currentTimeMillis() - cached.time) < 120000) {
            return cached
        }

        // Fallback: Check lastKnownLocation from all providers
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return cached
        try {
            var best: Location? = cached
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time || (l.accuracy < best.accuracy && (System.currentTimeMillis() - l.time) < 300000)) {
                    best = l
                }
            }
            if (best != null) {
                lastLocation = best
            }
            return best
        } catch (e: Exception) {
            Log.e(TAG, "Error getting best location", e)
            return cached
        }
    }
}
