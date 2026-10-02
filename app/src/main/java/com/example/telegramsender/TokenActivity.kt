package com.example.telegramsender

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.ui.SetupWizardActivity

class TokenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Directly route to MainActivity if registered, otherwise SetupWizardActivity
        val target = if (DevicePreferences.isRegistered(this)) {
            MainActivity::class.java
        } else {
            SetupWizardActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
