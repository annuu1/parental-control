package com.example.telegramsender.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val TAG = "SyncScheduler"
    private const val PERIODIC_WORK_NAME = "parental_device_sync_periodic"

    /**
     * Enqueue or update periodic heartbeat sync
     */
    fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15) {
        val safeInterval = Math.max(15L, intervalMinutes) // WorkManager minimum periodic interval is 15 mins

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<SyncWorker>(safeInterval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )

        Log.d(TAG, "Periodic sync scheduled every $safeInterval minutes")
    }

    /**
     * Trigger immediate one-time sync (e.g. after registration or manual refresh)
     */
    fun triggerImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeWork)
        Log.d(TAG, "Immediate one-time sync enqueued")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
