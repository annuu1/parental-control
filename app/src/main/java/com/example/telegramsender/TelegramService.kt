package com.example.telegramsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

class TelegramService : Service() {

    private val client = OkHttpClient()
    private var job: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowWidth = 720
    private var windowHeight = 1280
    private var screenDensity = 320

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TelegramService", "onStartCommand")
        val token = intent?.getStringExtra("token")
        val chatId = intent?.getStringExtra("chatId")
        
        if (token.isNullOrEmpty() || chatId.isNullOrEmpty()) {
             Log.e("TelegramService", "Missing token or chatId")
             return START_NOT_STICKY
        }

        windowWidth = intent.getIntExtra("width", 720)
        windowHeight = intent.getIntExtra("height", 1280)
        screenDensity = intent.getIntExtra("density", 320)
        
        Log.d("TelegramService", "Metrics: ${windowWidth}x${windowHeight} @ $screenDensity dpi")

        val resultCode = intent.getIntExtra("code", 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        startForeground(1, createNotification())

        if (resultCode != 0 && resultData != null) {
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                Log.d("TelegramService", "MediaProjection created")
            } catch (e: Exception) {
                Log.e("TelegramService", "Failed to create MediaProjection", e)
            }
        } else {
            Log.e("TelegramService", "No MediaProjection permission data found")
        }

        job = CoroutineScope(Dispatchers.IO).launch {
            // Send test message
            sendTelegramMessage(token, chatId, "Service Started. Metrics: ${windowWidth}x${windowHeight}")

            while (isActive) {
                if (mediaProjection != null) {
                    captureAndSend(token, chatId)
                } else {
                    Log.w("TelegramService", "MediaProjection is null")
                }
                delay(15_000) // 15 seconds delay
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        mediaProjection?.stop()
        super.onDestroy()
    }
    
    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("TelegramService", "Test msg failed: ${response.body?.string()}")
                } else {
                    Log.d("TelegramService", "Test msg sent")
                }
            }
        } catch (e: Exception) {
            Log.e("TelegramService", "Test msg error", e)
        }
    }

    private suspend fun captureAndSend(token: String, chatId: String) {
        val bitmap = captureScreenshot()
        if (bitmap != null) {
            sendTelegramPhoto(token, chatId, bitmap)
        } else {
            Log.e("TelegramService", "Screenshot returned null")
        }
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            if (mediaProjection == null) {
                 if (cont.isActive) cont.resume(null) {}
                 return@post
            }

            try {
                val reader = ImageReader.newInstance(windowWidth, windowHeight, PixelFormat.RGBA_8888, 2)
                imageReader = reader

                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "screen_cap",
                    windowWidth,
                    windowHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null, 
                    null
                )
                
                Log.d("TelegramService", "VirtualDisplay created")

                reader.setOnImageAvailableListener({ ir ->
                    try {
                        val image = ir.acquireLatestImage()
                        if (image != null) {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            // Calculate proper width from stride
                            val rowPadding = rowStride - pixelStride * windowWidth
        
                            val bitmap = Bitmap.createBitmap(
                                windowWidth + rowPadding / pixelStride,
                                windowHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            
                            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, windowWidth, windowHeight)
                            image.close()
                            
                            Log.d("TelegramService", "Image Captured")
                            
                            virtualDisplay?.release()
                            virtualDisplay = null
                            ir.close()
                            
                            if (cont.isActive) {
                                 cont.resume(finalBitmap) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TelegramService", "Capture error in listener", e)
                        virtualDisplay?.release()
                        try { ir.close() } catch(_:Exception){}
                        if (cont.isActive) cont.resume(null) {}
                    }
                }, handler)
                
                handler.postDelayed({
                    if (cont.isActive) {
                        Log.w("TelegramService", "Capture timeout")
                        virtualDisplay?.release()
                        try { reader.close() } catch(_:Exception){}
                        cont.resume(null) {}
                    }
                }, 8000)

            } catch (e: Exception) {
                Log.e("TelegramService", "Failed to setup VirtualDisplay", e)
                if (cont.isActive) cont.resume(null) {}
            }
        }
    }

    private fun sendTelegramPhoto(token: String, chatId: String, bitmap: Bitmap) {
        val url = "https://api.telegram.org/bot$token/sendPhoto"
        
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val byteArray = stream.toByteArray()
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", "screenshot.jpg",
                byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, byteArray.size))
            .build()
            
        val request = Request.Builder().url(url).post(requestBody).build()
        
        try {
            Log.d("TelegramService", "Sending photo (${byteArray.size} bytes)...")
            client.newCall(request).execute().use { response -> 
                if (!response.isSuccessful) {
                    Log.e("TelegramService", "Failed to send photo: ${response.code} ${response.body?.string()}")
                } else {
                    Log.d("TelegramService", "Photo sent successfully")
                }
            }
        } catch (e: IOException) {
            Log.e("TelegramService", "Network error sending photo", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tg_channel",
                "Telegram Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "tg_channel")
            .setContentTitle("Telegram Service Running")
            .setContentText("Capturing and sending screenshots...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
