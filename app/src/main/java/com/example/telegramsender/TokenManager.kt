package com.example.telegramsender

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

object TokenManager {
    private const val SECRET_KEY = "K9#pZ!4x"
    private const val PREF_NAME = "tg_pref"
    private const val KEY_ACCESS_TOKEN = "access_token"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .apply()
    }

    fun getToken(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_TOKEN, null)
    }

    fun isTokenValid(context: Context): Boolean {
        val token = getToken(context) ?: return false
        return try {
            val keyBytes = SECRET_KEY.toByteArray(StandardCharsets.UTF_8)
            // Python urlsafe_b64decode handles standard Base64 chars too usually, 
            // but for Android we strictly use URL_SAFE. NO_PADDING might be needed if python stripped it, 
            // but standard python base64 output includes padding usually unless stripped manually.
            val decodedBytes = Base64.decode(token, Base64.URL_SAFE)
            
            val decryptedBytes = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                decryptedBytes[i] = (decodedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            
            val decodedStr = String(decryptedBytes, StandardCharsets.UTF_8)
            val parts = decodedStr.split("|")
            
            // Format: app_id|issued|expires|user|flags
            if (parts.size < 3) return false
            
            val expires = parts[2].toLong()
            val currentTime = System.currentTimeMillis() / 1000
            
            currentTime < expires
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getTokenDetails(token: String): String {
        return try {
             val keyBytes = SECRET_KEY.toByteArray(StandardCharsets.UTF_8)
            val decodedBytes = Base64.decode(token, Base64.URL_SAFE)
            
            val decryptedBytes = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                decryptedBytes[i] = (decodedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
             String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            "Invalid Token"
        }
    }
}
