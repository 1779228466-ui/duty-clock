package com.dutyclock.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

class AlarmRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("alarms_v2", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class StoredAlarm(
        val id: Long, val hour: Int, val minute: Int, val enabled: Boolean,
        val label: String, val missionType: String, val missionCount: Int,
        val repeatMode: String, val repeatDays: Set<Int> = emptySet(),
        val vibrate: Boolean = true, val snoozeMinutes: Int = 5,
        val ringtoneName: String = "",
        val ringtoneUri: String = ""
    )

    fun getAllAlarms(): List<Alarm> {
        val jsonStr = prefs.getString("alarm_list", "[]") ?: "[]"
        return try { json.decodeFromString<List<StoredAlarm>>(jsonStr).map { it.toAlarm() } } catch (e: Exception) { emptyList() }
    }

    fun saveAlarm(alarm: Alarm) {
        val alarms = getAllAlarms().toMutableList()
        val idx = alarms.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) alarms[idx] = alarm else alarms.add(alarm)
        saveAll(alarms)
    }

    fun deleteAlarm(id: Long) { saveAll(getAllAlarms().filter { it.id != id }) }

    private fun saveAll(alarms: List<Alarm>) {
        prefs.edit().putString("alarm_list", json.encodeToString(alarms.map { it.toStored() })).apply()
    }

    private fun Alarm.toStored() = StoredAlarm(
        id, hour, minute, enabled, label, missionType.name, missionCount,
        repeatMode.name, repeatDays, vibrate, snoozeMinutes, ringtoneName, ringtoneUri
    )

    private fun StoredAlarm.toAlarm() = Alarm(
        id, hour, minute, enabled, label,
        missionType = try { MissionType.valueOf(missionType) } catch (e: Exception) { MissionType.SQUAT },
        missionCount = missionCount,
        repeatMode = try { RepeatMode.valueOf(repeatMode) } catch (e: Exception) { RepeatMode.ONCE },
        repeatDays = repeatDays,
        ringtoneName = ringtoneName,
        ringtoneUri = ringtoneUri
    )
}
