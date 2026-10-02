package com.example.telegramsender.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.telegramsender.TelegramService
import org.json.JSONObject

object DeviceHealthHelper {

    data class DeviceHealth(
        val locationPermission: Boolean,
        val backgroundLocationPermission: Boolean,
        val gpsHardwareEnabled: Boolean,
        val batteryOptimizationIgnored: Boolean,
        val accessibilityEnabled: Boolean,
        val cameraPermission: Boolean,
        val audioPermission: Boolean,
        val notificationPermission: Boolean
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("locationPermission", locationPermission)
                put("backgroundLocationPermission", backgroundLocationPermission)
                put("gpsHardwareEnabled", gpsHardwareEnabled)
                put("batteryOptimizationIgnored", batteryOptimizationIgnored)
                put("accessibilityEnabled", accessibilityEnabled)
                put("cameraPermission", cameraPermission)
                put("audioPermission", audioPermission)
                put("notificationPermission", notificationPermission)
            }
        }
    }

    fun getDeviceHealth(context: Context): DeviceHealth {
        val fineLocation = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationPerm = fineLocation || coarseLocation

        val bgLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            locationPerm
        }

        val gpsEnabled = LocationHelper.isGpsEnabled(context)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOptimizationIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        val cameraPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val notificationPerm = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val accessibilityActive = isAccessibilityServiceEnabled(context)

        return DeviceHealth(
            locationPermission = locationPerm,
            backgroundLocationPermission = bgLocation,
            gpsHardwareEnabled = gpsEnabled,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            accessibilityEnabled = accessibilityActive,
            cameraPermission = cameraPerm,
            audioPermission = audioPerm,
            notificationPermission = notificationPerm
        )
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = "${context.packageName}/${TelegramService::class.java.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) return true
        }
        return false
    }
}
