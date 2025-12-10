package com.example.telegramsender

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private var sendJob: Job? = null

    private lateinit var botTokenEdit: EditText
    private lateinit var chatIdEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botTokenEdit = findViewById(R.id.botToken)
        chatIdEdit = findViewById(R.id.chatId)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)

        startButton.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                statusText.text = "Enter token & chat ID"
                return@setOnClickListener
            }

            startSending(token, chatId)
        }

        stopButton.setOnClickListener { stopSending() }
    }

    private fun startSending(token: String, chatId: String) {
        stopSending()

        sendJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val message = "App is alive: ${System.currentTimeMillis()}"
                val success = sendTelegram(token, chatId, message)

                withContext(Dispatchers.Main) {
                    statusText.text = if (success) "Message Sent" else "Failed"
                }

                delay(10_000)
            }
        }
    }

    private fun stopSending() {
        sendJob?.cancel()
        sendJob = null
        statusText.text = "Stopped"
    }

    private fun sendTelegram(token: String, chatId: String, text: String): Boolean {
        val url = "https://api.telegram.org/bot$token/sendMessage"

        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()

        val request = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            Log.e("TG", "Error", e)
            false
        }
    }

    override fun onDestroy() {
        stopSending()
        super.onDestroy()
    }
}
