package com.example.telegramsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.worker.SyncScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed detected. Initializing sync scheduler.")

            if (DevicePreferences.isRegistered(context)) {
                val interval = DevicePreferences.getSyncInterval(context)
                SyncScheduler.schedulePeriodicSync(context, interval)
                SyncScheduler.triggerImmediateSync(context)

                // If device was locked before reboot, re-launch LockActivity
                if (DevicePreferences.isLocked(context)) {
                    val lockIntent = Intent(context, LockActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("LOCK_MESSAGE", DevicePreferences.getLockMessage(context))
                    }
                    context.startActivity(lockIntent)
                }
            }
        }
    }
}
