package com.example.telegramsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed detected.")
            // Accessibility Services are started by the system automatically on boot.
            // We can strictly try to start it as a background service too if needed,
            // but usually this is just a placeholder to ensure the app processes are initialized.
            // Actually, starting MainActivity might be what the user "feels" is a restart,
            // but we want background execution.
            // Since it's an AccessibilityService, we can't manually start it fully.
            // We rely on the system.
        }
    }
}
