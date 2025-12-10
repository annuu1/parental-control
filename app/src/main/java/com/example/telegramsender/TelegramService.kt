package com.example.telegramsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class TelegramService : android.app.Service() {

    private val client = OkHttpClient()
    private var job: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val token = intent?.getStringExtra("token") ?: return START_NOT_STICKY
        val chatId = intent.getStringExtra("chatId") ?: return START_NOT_STICKY

        startForeground(1, createNotification())

        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                sendTelegramMessage(token, chatId, "Service alive: ${System.currentTimeMillis()}")
                delay(10_000)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {

        val url = "https://api.telegram.org/bot$token/sendMessage"

        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()

        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use {  }
        } catch (_: IOException) {}
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tg_channel",
                "Telegram Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "tg_channel")
            .setContentTitle("Telegram Service Running")
            .setContentText("Sending messages every 10 seconds")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
