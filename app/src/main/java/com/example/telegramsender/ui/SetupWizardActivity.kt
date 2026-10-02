package com.example.telegramsender.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.telegramsender.MainActivity
import com.example.telegramsender.R
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.network.ApiClient
import com.example.telegramsender.worker.SyncScheduler
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupWizardActivity : AppCompatActivity() {

    private var currentStep = 1

    private lateinit var stepText: TextView
    private lateinit var btnNext: MaterialButton

    private lateinit var stepWelcome: View
    private lateinit var stepLogin: View
    private lateinit var stepPermissions: View
    private lateinit var stepDone: View

    private lateinit var wizardEmail: TextInputEditText
    private lateinit var wizardPassword: TextInputEditText
    private lateinit var wizardErrorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        stepText = findViewById(R.id.stepText)
        btnNext = findViewById(R.id.btnNext)

        stepWelcome = findViewById(R.id.stepWelcome)
        stepLogin = findViewById(R.id.stepLogin)
        stepPermissions = findViewById(R.id.stepPermissions)
        stepDone = findViewById(R.id.stepDone)

        wizardEmail = findViewById(R.id.wizardEmail)
        wizardPassword = findViewById(R.id.wizardPassword)
        wizardErrorText = findViewById(R.id.wizardErrorText)

        findViewById<MaterialButton>(R.id.btnWizardLocation).setOnClickListener {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                101
            )
        }

        findViewById<MaterialButton>(R.id.btnWizardBattery).setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(this, "Find app and select 'Don't Optimize'", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        btnNext.setOnClickListener {
            handleNextAction()
        }

        updateStepVisibility()
    }

    private fun handleNextAction() {
        when (currentStep) {
            1 -> {
                currentStep = 2 // Go to Login / Register
                updateStepVisibility()
            }
            2 -> {
                val email = wizardEmail.text.toString().trim()
                val password = wizardPassword.text.toString().trim()

                if (email.isEmpty() || password.isEmpty()) {
                    wizardErrorText.visibility = View.VISIBLE
                    wizardErrorText.text = "Please enter both email and password"
                    return
                }

                wizardErrorText.visibility = View.GONE
                btnNext.isEnabled = false
                btnNext.text = "CONNECTING..."

                lifecycleScope.launch(Dispatchers.IO) {
                    val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                    val result = ApiClient.registerDevice(
                        email = email,
                        password = password,
                        deviceName = deviceName,
                        deviceModel = Build.MODEL,
                        appVersion = "1.0.0"
                    )

                    withContext(Dispatchers.Main) {
                        btnNext.isEnabled = true
                        if (result.success && result.deviceJwt != null && result.deviceId != null) {
                            // Save pairing info
                            DevicePreferences.saveRegistration(
                                context = this@SetupWizardActivity,
                                jwt = result.deviceJwt,
                                deviceId = result.deviceId,
                                deviceToken = result.deviceToken ?: "",
                                parentEmail = email,
                                deviceName = deviceName,
                                intervalSeconds = result.syncIntervalMinutes * 60L
                            )

                            // Start background sync scheduler
                            SyncScheduler.schedulePeriodicSync(this@SetupWizardActivity, result.syncIntervalMinutes)
                            SyncScheduler.triggerImmediateSync(this@SetupWizardActivity)

                            try {
                                val monitorIntent = Intent(this@SetupWizardActivity, com.example.telegramsender.MonitorService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(monitorIntent)
                                } else {
                                    startService(monitorIntent)
                                }
                            } catch (e: Exception) {}

                            currentStep = 3 // Go to Permissions
                            updateStepVisibility()
                        } else {
                            btnNext.text = "CONNECT ACCOUNT"
                            wizardErrorText.visibility = View.VISIBLE
                            wizardErrorText.text = result.errorMessage ?: "Authentication failed. Check credentials."
                        }
                    }
                }
            }
            3 -> {
                currentStep = 4 // Go to Done
                updateStepVisibility()
            }
            4 -> {
                // Complete setup and launch MainActivity
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun updateStepVisibility() {
        stepWelcome.visibility = View.GONE
        stepLogin.visibility = View.GONE
        stepPermissions.visibility = View.GONE
        stepDone.visibility = View.GONE

        when (currentStep) {
            1 -> {
                stepWelcome.visibility = View.VISIBLE
                stepText.text = "Step 1/4"
                btnNext.text = "GET STARTED"
            }
            2 -> {
                stepLogin.visibility = View.VISIBLE
                stepText.text = "Step 2/4"
                btnNext.text = "CONNECT ACCOUNT"
            }
            3 -> {
                stepPermissions.visibility = View.VISIBLE
                stepText.text = "Step 3/4"
                btnNext.text = "CONTINUE"
            }
            4 -> {
                stepDone.visibility = View.VISIBLE
                stepText.text = "Complete"
                btnNext.text = "GO TO DASHBOARD"
            }
        }
    }
}
