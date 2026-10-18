package com.example.telegramsender.utils

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.util.Calendar

data class LocationSchedule(
    val id: String,
    val name: String,
    val startTime: String, // HH:mm e.g. "09:00"
    val endTime: String,   // HH:mm e.g. "19:00"
    val intervalSeconds: Long,
    val enabled: Boolean
)

object LocationScheduleHelper {
    private const val TAG = "LocationScheduleHelper"
    private const val PREF_NAME = "tg_pref"
    private const val KEY_SCHEDULES = "location_schedules_json"
    private const val KEY_DEFAULT_INTERVAL_SEC = "location_default_interval_sec"
    private const val KEY_LAST_LOCATION_SENT = "last_dashboard_location_sent_time"
    private const val KEY_FORCE_NEXT_LOCATION = "force_next_location_record"

    fun saveConfig(context: Context, schedulesJson: String?, defaultIntervalSeconds: Long) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = pref.edit()
        if (!schedulesJson.isNullOrEmpty() && schedulesJson != "[]") {
            editor.putString(KEY_SCHEDULES, schedulesJson)
        }
        if (defaultIntervalSeconds >= 5) {
            editor.putLong(KEY_DEFAULT_INTERVAL_SEC, defaultIntervalSeconds)
        }
        editor.apply()
    }

    fun getSchedules(context: Context): List<LocationSchedule> {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = pref.getString(KEY_SCHEDULES, null) ?: return getDefaultSchedules()
        val list = mutableListOf<LocationSchedule>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LocationSchedule(
                        id = obj.optString("id", "sched_$i"),
                        name = obj.optString("name", "Schedule $i"),
                        startTime = obj.optString("startTime", "09:00"),
                        endTime = obj.optString("endTime", "19:00"),
                        intervalSeconds = Math.max(5L, obj.optLong("intervalSeconds", 60L)),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing schedules json: $jsonStr", e)
            return getDefaultSchedules()
        }
        return if (list.isNotEmpty()) list else getDefaultSchedules()
    }

    fun getDefaultSchedules(): List<LocationSchedule> {
        return listOf(
            LocationSchedule(
                id = "daytime",
                name = "Daytime (9am – 7pm)",
                startTime = "09:00",
                endTime = "19:00",
                intervalSeconds = 60L,
                enabled = true
            ),
            LocationSchedule(
                id = "night",
                name = "Night (7pm – 8am)",
                startTime = "19:00",
                endTime = "08:00",
                intervalSeconds = 300L,
                enabled = true
            )
        )
    }

    fun getDefaultIntervalSeconds(context: Context): Long {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return pref.getLong(KEY_DEFAULT_INTERVAL_SEC, 60L)
    }

    /**
     * Calculates the required recording interval in seconds based on current local device time.
     * Evaluates active schedules including overnight/cross-midnight ranges (e.g. 19:00 to 08:00).
     */
    fun getActiveIntervalSeconds(context: Context): Long {
        val calendar = Calendar.getInstance()
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val schedules = getSchedules(context)
        for (sched in schedules) {
            if (!sched.enabled) continue
            val (sh, sm) = parseTime(sched.startTime)
            val (eh, em) = parseTime(sched.endTime)
            val startMin = sh * 60 + sm
            val endMin = eh * 60 + em

            val isMatch = if (startMin < endMin) {
                currentMinutes in startMin until endMin
            } else {
                // Cross-midnight range e.g. 19:00 (1140m) to 08:00 (480m)
                currentMinutes >= startMin || currentMinutes < endMin
            }

            if (isMatch) {
                return sched.intervalSeconds
            }
        }
        return getDefaultIntervalSeconds(context)
    }

    fun getActiveIntervalMillis(context: Context): Long {
        return getActiveIntervalSeconds(context) * 1000L
    }

    /**
     * Determines whether enough time has passed since the last location fix was sent.
     */
    fun isLocationDue(context: Context): Boolean {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (pref.getBoolean(KEY_FORCE_NEXT_LOCATION, false)) {
            return true
        }

        val lastSent = pref.getLong(KEY_LAST_LOCATION_SENT, 0L)
        if (lastSent == 0L) {
            return true // First run fix
        }

        val intervalMs = getActiveIntervalMillis(context)
        val elapsed = System.currentTimeMillis() - lastSent

        return elapsed >= intervalMs
    }

    fun recordLocationSent(context: Context, timestamp: Long = System.currentTimeMillis()) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        pref.edit()
            .putLong(KEY_LAST_LOCATION_SENT, timestamp)
            .putBoolean(KEY_FORCE_NEXT_LOCATION, false)
            .apply()
    }

    fun forceNextLocation(context: Context) {
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        pref.edit().putBoolean(KEY_FORCE_NEXT_LOCATION, true).apply()
    }

    private fun parseTime(timeStr: String): Pair<Int, Int> {
        val parts = timeStr.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }
}
