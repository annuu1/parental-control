package com.example.telegramsender.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.telegramsender.MonitorService
import com.example.telegramsender.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    private lateinit var pref: SharedPreferences
    private lateinit var botTokenEdit: TextInputEditText
    private lateinit var chatIdEdit: TextInputEditText
    private lateinit var btnSave: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        pref = requireContext().getSharedPreferences("tg_pref", Context.MODE_PRIVATE)

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

        // Diagnostics
        view.findViewById<MaterialButton>(R.id.btnRunSetupWizard).setOnClickListener {
            startActivity(Intent(requireContext(), SetupWizardActivity::class.java))
        }

        view.findViewById<MaterialButton>(R.id.btnDiagScreenshot).setOnClickListener {
            requireContext().sendBroadcast(Intent("com.example.telegramsender.ACTION_TEST_SCREENSHOT"))
            Toast.makeText(requireContext(), "Test screenshot requested", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDiagLocation).setOnClickListener {
            // Need a way to trigger this from fragment... 
            // For simplicity, just show a message or use a direct method if possible.
            // Actually, we can just trigger a one-time work or use a broadcast if we add it.
            Toast.makeText(requireContext(), "Test location feature not implemented in this view yet", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDiagAudio).setOnClickListener {
             Toast.makeText(requireContext(), "Test audio feature not implemented in this view yet", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDiagForceLogs).setOnClickListener {
            requireContext().sendBroadcast(Intent("com.example.telegramsender.ACTION_FORCE_SEND"))
            Toast.makeText(requireContext(), "Force log upload requested", Toast.LENGTH_SHORT).show()
        }

        return view
    }
}
