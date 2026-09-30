package com.example.telegramsender.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.fragment.app.Fragment
import com.example.telegramsender.R
import com.google.android.material.switchmaterial.SwitchMaterial

class MonitoringFragment : Fragment() {

    private lateinit var pref: SharedPreferences
    
    private lateinit var screenshotSwitch: SwitchMaterial
    private lateinit var screenshotOptions: LinearLayout
    private lateinit var screenshotSpinner: Spinner

    private lateinit var locationSwitch: SwitchMaterial
    private lateinit var locationOptions: LinearLayout
    private lateinit var locationSpinner: Spinner

    private lateinit var audioSwitch: SwitchMaterial
    private lateinit var audioOptions: LinearLayout
    private lateinit var audioScreenOffSwitch: SwitchMaterial
    private lateinit var audioSpinner: Spinner

    private lateinit var cameraSwitch: SwitchMaterial
    private lateinit var cameraOptions: LinearLayout
    private lateinit var cameraScreenOffSwitch: SwitchMaterial
    private lateinit var cameraSpinner: Spinner

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_monitoring, container, false)
        pref = requireContext().getSharedPreferences("tg_pref", Context.MODE_PRIVATE)

        // Screenshots
        screenshotSwitch = view.findViewById(R.id.screenshotSwitch)
        screenshotOptions = view.findViewById(R.id.screenshotOptions)
        screenshotSpinner = view.findViewById(R.id.screenshotIntervalSpinner)
        setupIntervalSpinner(screenshotSpinner, R.array.screenshot_intervals, "screenshotInterval", 10L)
        
        screenshotSwitch.isChecked = pref.getBoolean("sendScreenshot", true)
        screenshotOptions.visibility = if (screenshotSwitch.isChecked) View.VISIBLE else View.GONE
        screenshotSwitch.setOnCheckedChangeListener { _, isChecked ->
            pref.edit().putBoolean("sendScreenshot", isChecked).apply()
            screenshotOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Location
        locationSwitch = view.findViewById(R.id.locationSwitch)
        locationOptions = view.findViewById(R.id.locationOptions)
        locationSpinner = view.findViewById(R.id.locationIntervalSpinner)
        setupIntervalSpinner(locationSpinner, R.array.location_intervals, "locationInterval", 10L)
        
        locationSwitch.isChecked = pref.getBoolean("sendLocation", false)
        locationOptions.visibility = if (locationSwitch.isChecked) View.VISIBLE else View.GONE
        locationSwitch.setOnCheckedChangeListener { _, isChecked ->
            pref.edit().putBoolean("sendLocation", isChecked).apply()
            locationOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Audio
        audioSwitch = view.findViewById(R.id.audioSwitch)
        audioOptions = view.findViewById(R.id.audioOptions)
        audioScreenOffSwitch = view.findViewById(R.id.audioScreenOffSwitch)
        audioSpinner = view.findViewById(R.id.audioIntervalSpinner)
        setupIntervalSpinner(audioSpinner, R.array.audio_intervals, "audioDuration", 60L)
        
        audioSwitch.isChecked = pref.getBoolean("sendAudio", false)
        audioScreenOffSwitch.isChecked = pref.getBoolean("audioScreenOff", false)
        audioOptions.visibility = if (audioSwitch.isChecked) View.VISIBLE else View.GONE
        
        audioSwitch.setOnCheckedChangeListener { _, isChecked ->
            pref.edit().putBoolean("sendAudio", isChecked).apply()
            audioOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        audioScreenOffSwitch.setOnCheckedChangeListener { _, isChecked ->
            pref.edit().putBoolean("audioScreenOff", isChecked).apply()
        }

        // Camera
        cameraSwitch = view.findViewById(R.id.cameraSwitch)
        cameraOptions = view.findViewById(R.id.cameraOptions)
        cameraScreenOffSwitch = view.findViewById(R.id.cameraScreenOffSwitch)
        cameraSpinner = view.findViewById(R.id.cameraIntervalSpinner)
        setupIntervalSpinner(cameraSpinner, R.array.camera_intervals, "cameraInterval", 10L)
        
        cameraSwitch.isChecked = pref.getBoolean("sendCamera", false)
        cameraScreenOffSwitch.isChecked = pref.getBoolean("cameraScreenOff", false)
        cameraOptions.visibility = if (cameraSwitch.isChecked) View.VISIBLE else View.GONE
        
        cameraSwitch.setOnCheckedChangeListener { _, isChecked ->
            pref.edit().putBoolean("sendCamera", isChecked).apply()
            cameraOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        cameraScreenOffSwitch.setOnCheckedChangeListener { _, isChecked ->
            pref.edit().putBoolean("cameraScreenOff", isChecked).apply()
        }

        return view
    }

    private fun setupIntervalSpinner(spinner: Spinner, arrayRes: Int, prefKey: String, defaultValue: Long) {
        val adapter = ArrayAdapter.createFromResource(requireContext(), arrayRes, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        val currentValue = pref.getLong(prefKey, defaultValue)
        val values = resources.getStringArray(arrayRes)
        
        // Find index (hacky but simple)
        for (i in values.indices) {
            if (values[i].contains(currentValue.toString())) {
                spinner.setSelection(i)
                break
            }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = values[position]
                val num = selected.filter { it.isDigit() }.toLongOrNull() ?: defaultValue
                pref.edit().putLong(prefKey, num).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
