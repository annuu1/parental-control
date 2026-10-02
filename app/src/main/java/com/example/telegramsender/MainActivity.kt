package com.example.telegramsender

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.ui.*
import com.example.telegramsender.worker.SyncScheduler
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Check if device is paired with the web dashboard
            if (!DevicePreferences.isRegistered(this)) {
                startActivity(Intent(this, SetupWizardActivity::class.java))
                finish()
                return
            }

            // Check if device is locked remotely
            if (DevicePreferences.isLocked(this)) {
                val lockIntent = Intent(this, LockActivity::class.java).apply {
                    putExtra("LOCK_MESSAGE", DevicePreferences.getLockMessage(this@MainActivity))
                }
                startActivity(lockIntent)
                finish()
                return
            }

            // Ensure background sync scheduler is active
            SyncScheduler.schedulePeriodicSync(this, DevicePreferences.getSyncInterval(this))

            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Launch failed", e)
            try {
                startActivity(Intent(this, SetupWizardActivity::class.java))
                finish()
            } catch (e2: Exception) {}
            return
        }

        val navView: BottomNavigationView = findViewById(R.id.bottom_navigation)
        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    loadFragment(DashboardFragment())
                    true
                }
                R.id.nav_monitoring -> {
                    loadFragment(MonitoringFragment())
                    true
                }
                R.id.nav_permissions -> {
                    loadFragment(PermissionsFragment())
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }

        // Default fragment
        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
