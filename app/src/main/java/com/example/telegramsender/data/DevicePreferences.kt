package com.example.telegramsender.data

import android.content.Context
import android.content.SharedPreferences

object DevicePreferences {
    private const val PREF_NAME = "parental_pref"
    private const val KEY_DEVICE_JWT = "device_jwt"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_PARENT_EMAIL = "parent_email"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_SYNC_INTERVAL_SECONDS = "sync_interval_seconds"
    private const val KEY_SYNC_INTERVAL = "sync_interval_mins"
    private const val KEY_IS_LOCKED = "is_locked"
    private const val KEY_LOCK_MESSAGE = "lock_message"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_EXECUTED_COMMANDS = "executed_commands_set"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isRegistered(context: Context): Boolean {
        val jwt = getDeviceJwt(context)
        return !jwt.isNullOrEmpty()
    }

    fun saveRegistration(
        context: Context,
        jwt: String,
        deviceId: String,
        deviceToken: String,
        parentEmail: String,
        deviceName: String,
        intervalSeconds: Long
    ) {
        val safeSeconds = Math.max(5L, intervalSeconds)
        getPrefs(context).edit()
            .putString(KEY_DEVICE_JWT, jwt)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_PARENT_EMAIL, parentEmail)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putLong(KEY_SYNC_INTERVAL_SECONDS, safeSeconds)
            .putLong(KEY_SYNC_INTERVAL, Math.max(1L, safeSeconds / 60))
            .putBoolean("setup_complete", true)
            .apply()
    }

    fun getDeviceJwt(context: Context): String? {
        return getPrefs(context).getString(KEY_DEVICE_JWT, null)
    }

    fun getDeviceId(context: Context): String? {
        return getPrefs(context).getString(KEY_DEVICE_ID, null)
    }

    fun getParentEmail(context: Context): String? {
        return getPrefs(context).getString(KEY_PARENT_EMAIL, null)
    }

    fun getSyncIntervalSeconds(context: Context): Long {
        val sec = getPrefs(context).getLong(KEY_SYNC_INTERVAL_SECONDS, 0L)
        if (sec >= 5L) return sec
        val mins = getPrefs(context).getLong(KEY_SYNC_INTERVAL, 1L)
        return Math.max(5L, mins * 60)
    }

    fun setSyncIntervalSeconds(context: Context, intervalSeconds: Long) {
        val safeSeconds = Math.max(5L, intervalSeconds)
        getPrefs(context).edit()
            .putLong(KEY_SYNC_INTERVAL_SECONDS, safeSeconds)
            .putLong(KEY_SYNC_INTERVAL, Math.max(1L, safeSeconds / 60))
            .apply()
    }

    fun getSyncInterval(context: Context): Long {
        return Math.max(1L, getSyncIntervalSeconds(context) / 60)
    }

    fun setSyncInterval(context: Context, intervalMinutes: Long) {
        setSyncIntervalSeconds(context, intervalMinutes * 60)
    }

    fun isLocked(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_LOCKED, false)
    }

    fun setLocked(context: Context, locked: Boolean, message: String? = null) {
        val editor = getPrefs(context).edit().putBoolean(KEY_IS_LOCKED, locked)
        if (!message.isNullOrEmpty()) {
            editor.putString(KEY_LOCK_MESSAGE, message)
        }
        editor.apply()
    }

    fun getLockMessage(context: Context): String {
        return getPrefs(context).getString(KEY_LOCK_MESSAGE, "This device is locked by parental control.")
            ?: "This device is locked by parental control."
    }

    fun setLastSyncTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun addExecutedCommand(context: Context, commandId: String) {
        val currentSet = getPrefs(context).getStringSet(KEY_EXECUTED_COMMANDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(commandId)
        getPrefs(context).edit().putStringSet(KEY_EXECUTED_COMMANDS, currentSet).apply()
    }

    fun getAndClearExecutedCommands(context: Context): List<String> {
        val currentSet = getPrefs(context).getStringSet(KEY_EXECUTED_COMMANDS, emptySet())?.toList() ?: emptyList()
        getPrefs(context).edit().remove(KEY_EXECUTED_COMMANDS).apply()
        return currentSet
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
