package com.dutyclock.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器 — 参考 yuriykulikov/AlarmClock 的 AlarmsReceiver 模式
 * 系统重启后自动恢复所有已启用的闹钟调度
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val alarmRepo = AlarmRepository(context)
            val alarms = alarmRepo.getAllAlarms().filter { it.enabled }
            AppLog.i("BootReceiver", "Rescheduling ${alarms.size} alarms after boot")
            for (alarm in alarms) {
                val mgr = DutyAlarmManager.getInstance()
                mgr.setConfig(DutyAlarmManager.AlarmConfig(alarm.hour, alarm.minute, alarm.missionCount, alarm.enabled))
                mgr.scheduleAlarm(context)
            }
        }
    }
}
