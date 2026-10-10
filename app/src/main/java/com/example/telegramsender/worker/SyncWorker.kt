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

        val result = com.example.telegramsender.utils.SyncHelper.performSync(context)
        return if (result.success) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
