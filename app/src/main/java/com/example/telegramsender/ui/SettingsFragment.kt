package com.example.telegramsender.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.telegramsender.R
import com.example.telegramsender.data.DevicePreferences
import com.example.telegramsender.utils.SyncHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : Fragment() {

    private lateinit var pref: SharedPreferences
    private lateinit var botTokenEdit: TextInputEditText
    private lateinit var chatIdEdit: TextInputEditText
    private lateinit var btnSave: MaterialButton

    private lateinit var settingsLinkedAccount: TextView
    private lateinit var settingsLastSync: TextView
    private lateinit var btnSettingsSyncNow: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        pref = requireContext().getSharedPreferences("tg_pref", Context.MODE_PRIVATE)

        // Cloud sync views
        settingsLinkedAccount = view.findViewById(R.id.settingsLinkedAccount)
        settingsLastSync = view.findViewById(R.id.settingsLastSync)
        btnSettingsSyncNow = view.findViewById(R.id.btnSettingsSyncNow)

        botTokenEdit = view.findViewById(R.id.settingsBotToken)
        chatIdEdit = view.findViewById(R.id.settingsChatId)
        btnSave = view.findViewById(R.id.btnSaveTelegram)

        botTokenEdit.setText(pref.getString("token", ""))
        chatIdEdit.setText(pref.getString("chatId", ""))

        btnSave.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chat = chatIdEdit.text.toString().trim()
            pref.edit()
                .putString("token", token)
                .putString("chatId", chat)
                .apply()
            Toast.makeText(requireContext(), "Telegram settings saved", Toast.LENGTH_SHORT).show()
        }

        btnSettingsSyncNow.setOnClickListener {
            performManualSync()
        }

        // Diagnostics
        view.findViewById<MaterialButton>(R.id.btnRunSetupWizard).setOnClickListener {
            startActivity(Intent(requireContext(), SetupWizardActivity::class.java))
        }

        view.findViewById<MaterialButton>(R.id.btnDiagScreenshot).setOnClickListener {
            requireContext().sendBroadcast(Intent("com.example.telegramsender.ACTION_TEST_SCREENSHOT"))
            Toast.makeText(requireContext(), "Test screenshot requested", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDiagLocation).setOnClickListener {
            requireContext().sendBroadcast(Intent("com.example.telegramsender.ACTION_FORCE_LOCATION"))
            Toast.makeText(requireContext(), "Location update broadcast sent", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDiagAudio).setOnClickListener {
            requireContext().sendBroadcast(Intent("com.example.telegramsender.ACTION_TEST_AUDIO"))
            Toast.makeText(requireContext(), "Testing microphone recording (10s)...", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDiagForceLogs).setOnClickListener {
            requireContext().sendBroadcast(Intent("com.example.telegramsender.ACTION_FORCE_SEND"))
            Toast.makeText(requireContext(), "Force log upload requested", Toast.LENGTH_SHORT).show()
        }

        updateSyncInfo()
        return view
    }

    override fun onResume() {
        super.onResume()
        updateSyncInfo()
        botTokenEdit.setText(pref.getString("token", ""))
        chatIdEdit.setText(pref.getString("chatId", ""))
    }

    private fun updateSyncInfo() {
        val isRegistered = DevicePreferences.isRegistered(requireContext())
        if (!isRegistered) {
            settingsLinkedAccount.text = "Account: Not paired with dashboard"
            settingsLastSync.text = "Last Synced: Run setup wizard to pair"
            return
        }

        val email = DevicePreferences.getParentEmail(requireContext()) ?: "Parent Account"
        settingsLinkedAccount.text = "Account: $email"

        val lastSync = DevicePreferences.getLastSyncTime(requireContext())
        if (lastSync == 0L) {
            settingsLastSync.text = "Last Synced: Never (Waiting for initial heartbeat)"
        } else {
            val exact = SimpleDateFormat("h:mm:ss a, MMM d", Locale.getDefault()).format(Date(lastSync))
            val diffSec = (System.currentTimeMillis() - lastSync) / 1000
            val relative = when {
                diffSec < 60 -> "${diffSec}s ago"
                diffSec < 3600 -> "${diffSec / 60}m ago"
                else -> "${diffSec / 3600}h ago"
            }
            settingsLastSync.text = "Last Synced: $relative ($exact)"
        }
    }

    private fun performManualSync() {
        if (!DevicePreferences.isRegistered(requireContext())) {
            startActivity(Intent(requireContext(), SetupWizardActivity::class.java))
            return
        }

        btnSettingsSyncNow.isEnabled = false
        btnSettingsSyncNow.text = "SYNCING..."

        lifecycleScope.launch(Dispatchers.IO) {
            val result = SyncHelper.performSync(requireContext())
            withContext(Dispatchers.Main) {
                btnSettingsSyncNow.isEnabled = true
                btnSettingsSyncNow.text = "SYNC WITH DASHBOARD NOW"
                updateSyncInfo()
                botTokenEdit.setText(pref.getString("token", ""))
                chatIdEdit.setText(pref.getString("chatId", ""))

                if (result.success) {
                    Toast.makeText(requireContext(), "✓ Dashboard Sync successful!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Sync error: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
