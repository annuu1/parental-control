package com.example.telegramsender

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

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

        requestFgPermission()
        loadSavedValues()

        startButton.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                statusText.text = "Please enter values"
                return@setOnClickListener
            }

            saveValues(token, chatId)

            requestFgPermission()

            val intent = Intent(this, TelegramService::class.java)
            intent.putExtra("token", token)
            intent.putExtra("chatId", chatId)

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            statusText.text = "Service Started"
        }
        startButton.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                statusText.text = "Please enter values"
                return@setOnClickListener
            }

            saveValues(token, chatId)

            requestFgPermission()

            val intent = Intent(this, TelegramService::class.java)
            intent.putExtra("token", token)
            intent.putExtra("chatId", chatId)

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            statusText.text = "Service Started"
        }


        stopButton.setOnClickListener {
            stopService(Intent(this, TelegramService::class.java))
            statusText.text = "Service Stopped"
        }
    }
    private fun requestFgPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val permission = android.Manifest.permission.FOREGROUND_SERVICE
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 123)
            }
        }
    }

    private fun loadSavedValues() {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        botTokenEdit.setText(pref.getString("token", ""))
        chatIdEdit.setText(pref.getString("chatId", ""))
    }

    private fun saveValues(token: String, chatId: String) {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        pref.edit().putString("token", token)
            .putString("chatId", chatId)
            .apply()
    }
}
