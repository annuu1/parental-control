package com.example.telegramsender.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.telegramsender.R
import com.example.telegramsender.TelegramService
import com.google.android.material.button.MaterialButton

class PermissionsFragment : Fragment() {

    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnLocation: MaterialButton
    private lateinit var btnMic: MaterialButton
    private lateinit var btnCamera: MaterialButton
    private lateinit var btnBattery: MaterialButton

    private lateinit var iconAccessibility: ImageView
    private lateinit var iconLocation: ImageView
    private lateinit var iconMic: ImageView
    private lateinit var iconCamera: ImageView
    private lateinit var iconBattery: ImageView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_permissions, container, false)

        btnAccessibility = view.findViewById(R.id.btnFixAccessibility)
        btnLocation = view.findViewById(R.id.btnFixLocation)
        btnMic = view.findViewById(R.id.btnFixMic)
        btnCamera = view.findViewById(R.id.btnFixCamera)
        btnBattery = view.findViewById(R.id.btnFixBattery)

        iconAccessibility = view.findViewById(R.id.iconAccessibility)
        iconLocation = view.findViewById(R.id.iconLocation)
        iconMic = view.findViewById(R.id.iconMic)
        iconCamera = view.findViewById(R.id.iconCamera)
        iconBattery = view.findViewById(R.id.iconBattery)

        btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btnLocation.setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 101) }
        btnMic.setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102) }
        btnCamera.setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 103) }
        
        btnBattery.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(requireContext(), "Find 'Notes' and set to 'Don't Optimize' or 'No Restrictions'", Toast.LENGTH_LONG).show()
                } else {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        updateStates()
    }

    private fun updateStates() {
        updateItem(isAccessibilityServiceEnabled(), btnAccessibility, iconAccessibility)
        updateItem(hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION), btnLocation, iconLocation)
        updateItem(hasPermission(android.Manifest.permission.RECORD_AUDIO), btnMic, iconMic)
        updateItem(hasPermission(android.Manifest.permission.CAMERA), btnCamera, iconCamera)
        updateItem(isBatteryOptimized(), btnBattery, iconBattery)
    }

    private fun updateItem(enabled: Boolean, button: MaterialButton, icon: ImageView) {
        if (enabled) {
            button.text = "OK"
            button.isEnabled = false
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green_active))
        } else {
            button.text = "FIX"
            button.isEnabled = true
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_pause))
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        }
        return true
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
}
