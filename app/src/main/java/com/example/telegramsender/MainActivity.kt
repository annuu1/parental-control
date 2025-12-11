package com.example.telegramsender

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var botTokenEdit: EditText
    private lateinit var chatIdEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var cameraSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botTokenEdit = findViewById(R.id.botToken)
        chatIdEdit = findViewById(R.id.chatId)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        cameraSwitch = findViewById(R.id.cameraSwitch)

        // Reuse screenshotButton to open Accessibility Settings
        val screenshotButton = findViewById<Button>(R.id.screenshotButton)
        screenshotButton.text = "Enable Accessibility Service"
        screenshotButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        loadSavedValues()
        updateStatus()

        startButton.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()
            val sendCamera = cameraSwitch.isChecked

            if (token.isEmpty() || chatId.isEmpty()) {
                statusText.text = "Please enter values"
                return@setOnClickListener
            }

            if (sendCamera && android.os.Build.VERSION.SDK_INT >= 23) {
                 if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
                     return@setOnClickListener
                 }
            }

            saveValues(token, chatId, sendCamera)
            
            if (isAccessibilityServiceEnabled()) {
                statusText.text = "Service is Active (managed by System)"
                Toast.makeText(this, "Service is already running in background", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "Please Enable Accessibility First"
                Toast.makeText(this, "Enable 'Telegram Sender' in Accessibility Settings", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        stopButton.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Disable service in Accessibility Settings to stop", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } else {
                statusText.text = "Service Stopped"
            }
        }
        
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            Toast.makeText(this, "MIUI Detected: Please enable 'Autostart' and Lock the app in Recents to prevent stopping.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.downloadLogsButton).setOnClickListener {
            // Send broadcast to service to upload logs
            val intent = Intent("com.example.telegramsender.ACTION_FORCE_SEND")
            intent.setPackage(packageName) // Restrict to own app
            sendBroadcast(intent)
            Toast.makeText(this, "Requesting Log Upload to Telegram...", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = packageName + "/" + TelegramService::class.java.canonicalName
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    private fun updateStatus() {
        if (isAccessibilityServiceEnabled()) {
            statusText.text = "Status: Service Active"
            val screenshotButton = findViewById<Button>(R.id.screenshotButton)
            screenshotButton.isEnabled = false
            screenshotButton.text = "Accessibility Enabled"
        } else {
            statusText.text = "Status: Service Inactive"
            val screenshotButton = findViewById<Button>(R.id.screenshotButton)
            screenshotButton.isEnabled = true
            screenshotButton.text = "Enable Accessibility Service"
        }
    }

    private fun loadSavedValues() {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        botTokenEdit.setText(pref.getString("token", ""))
        chatIdEdit.setText(pref.getString("chatId", ""))
        cameraSwitch.isChecked = pref.getBoolean("sendCamera", false)
    }

    private fun saveValues(token: String, chatId: String, sendCamera: Boolean) {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        pref.edit().putString("token", token)
            .putString("chatId", chatId)
            .putBoolean("sendCamera", sendCamera)
            .apply()
    }
}


