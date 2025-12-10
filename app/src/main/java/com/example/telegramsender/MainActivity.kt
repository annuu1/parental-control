package com.example.telegramsender

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var botTokenEdit: EditText
    private lateinit var chatIdEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private val SCREENSHOT_REQUEST = 2001
    private var screenshotResultCode: Int = 0
    private var screenshotResultData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botTokenEdit = findViewById(R.id.botToken)
        chatIdEdit = findViewById(R.id.chatId)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val screenshotButton = findViewById<Button>(R.id.screenshotButton)
        screenshotButton.setOnClickListener {
            val intent = projectionManager.createScreenCaptureIntent()
            startActivityForResult(intent, SCREENSHOT_REQUEST)
        }

        requestFgPermission()
        loadSavedValues()

        startButton.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()

            if (token.isEmpty() || chatId.isEmpty()) {
                statusText.text = "Please enter values"
                return@setOnClickListener
            }

            if (screenshotResultData == null) {
                Toast.makeText(this, "Please enable screenshot capture first", Toast.LENGTH_SHORT).show()
                statusText.text = "Permission Missing"
                return@setOnClickListener
            }

            saveValues(token, chatId)

            requestFgPermission()

            val metrics = resources.displayMetrics
            val intent = Intent(this, TelegramService::class.java)
            intent.putExtra("token", token)
            intent.putExtra("chatId", chatId)
            intent.putExtra("code", screenshotResultCode)
            intent.putExtra("data", screenshotResultData)
            intent.putExtra("width", metrics.widthPixels)
            intent.putExtra("height", metrics.heightPixels)
            intent.putExtra("density", metrics.densityDpi)

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREENSHOT_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                screenshotResultCode = resultCode
                screenshotResultData = data
                statusText.text = "Screenshot Permission Granted"
            } else {
                statusText.text = "Screenshot Permission Denied"
            }
        }
    }
}
