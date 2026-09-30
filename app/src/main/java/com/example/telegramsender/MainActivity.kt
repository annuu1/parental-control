package com.example.telegramsender

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.telegramsender.ui.*
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            val pref = getSharedPreferences("tg_pref", Context.MODE_PRIVATE)
            if (!pref.getBoolean("setup_complete", false)) {
                startActivity(Intent(this, SetupWizardActivity::class.java))
                finish()
                return
            }

            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Launch failed", e)
            android.widget.Toast.makeText(this, "Launch Fail: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            // If main failed, maybe try setup wizard directly?
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
