package com.dutyclock.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * 闹钟数据持久化
 * 使用 SharedPreferences + JSON 简单存储
 */
class AlarmRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class StoredAlarm(
        val id: Long,
        val hour: Int,
        val minute: Int,
        val enabled: Boolean,
        val label: String,
        val missionType: String,
        val missionCount: Int,
        val difficulty: String,
        val repeatMode: String,
        val repeatDays: Set<Int> = emptySet(),
        val vibrate: Boolean = true,
        val volume: Int = 80,
        val snoozeMinutes: Int = 5,
        val snoozeEnabled: Boolean = true
    )

    fun getAllAlarms(): List<Alarm> {
        val jsonStr = prefs.getString("alarm_list", "[]") ?: "[]"
        return try {
            json.decodeFromString<List<StoredAlarm>>(jsonStr).map { it.toAlarm() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAlarm(alarm: Alarm) {
        val alarms = getAllAlarms().toMutableList()
        val idx = alarms.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) alarms[idx] = alarm else alarms.add(alarm)
        saveAll(alarms)
    }

    fun deleteAlarm(id: Long) {
        val alarms = getAllAlarms().filter { it.id != id }
        saveAll(alarms)
    }

    fun getAlarm(id: Long): Alarm? {
        return getAllAlarms().find { it.id == id }
    }

    private fun saveAll(alarms: List<Alarm>) {
        val stored = alarms.map { it.toStored() }
        prefs.edit().putString("alarm_list", json.encodeToString(stored)).apply()
    }

    private fun Alarm.toStored() = StoredAlarm(
        id = id, hour = hour, minute = minute, enabled = enabled,
        label = label, missionType = missionType.name,
        missionCount = missionCount, difficulty = difficulty.name,
        repeatMode = repeatMode.name, repeatDays = repeatDays,
        vibrate = vibrate, volume = volume,
        snoozeMinutes = snoozeMinutes, snoozeEnabled = snoozeEnabled
    )

    private fun StoredAlarm.toAlarm() = Alarm(
        id = id, hour = hour, minute = minute, enabled = enabled,
        label = label,
        missionType = try { MissionType.valueOf(missionType) } catch (e: Exception) { MissionType.SQUAT },
        missionCount = missionCount,
        difficulty = try { MissionDifficulty.valueOf(difficulty) } catch (e: Exception) { MissionDifficulty.NORMAL },
        repeatMode = try { RepeatMode.valueOf(repeatMode) } catch (e: Exception) { RepeatMode.ONCE },
        repeatDays = repeatDays,
        vibrate = vibrate, volume = volume,
        snoozeMinutes = snoozeMinutes, snoozeEnabled = snoozeEnabled
    )
}
