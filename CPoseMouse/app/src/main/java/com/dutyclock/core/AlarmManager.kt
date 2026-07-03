package com.dutyclock.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

/**
 * 闹钟管理 - 定时触发 + 铃声播放
 */
class DutyAlarmManager private constructor() {

    data class AlarmConfig(
        val hour: Int = 7,
        val minute: Int = 0,
        val squatCount: Int = 10,
        val enabled: Boolean = true
    )

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmConfig = AlarmConfig()

    companion object {
        private const val TAG = "AlarmManager"
        @Volatile private var instance: DutyAlarmManager? = null

        fun getInstance(): DutyAlarmManager {
            return instance ?: synchronized(this) {
                instance ?: DutyAlarmManager().also { instance = it }
            }
        }

        const val ALARM_ACTION = "com.dutyclock.ALARM_TRIGGER"
        const val ALARM_REQUEST_CODE = 1001
    }

    fun getConfig(): AlarmConfig = alarmConfig

    fun setConfig(config: AlarmConfig) {
        alarmConfig = config
    }

    fun scheduleAlarm(context: Context) {
        if (!alarmConfig.enabled) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarmConfig.hour)
            set(Calendar.MINUTE, alarmConfig.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ALARM_ACTION
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent, flags
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
            AppLog.i(TAG, "Alarm scheduled: ${alarmConfig.hour}:${String.format("%02d", alarmConfig.minute)}")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to schedule alarm", e)
        }
    }

    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ALARM_ACTION
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent, flags
        )
        alarmManager.cancel(pendingIntent)
        AppLog.i(TAG, "Alarm cancelled")
    }

    fun startRinging(context: Context) {
        if (mediaPlayer?.isPlaying == true) return
        acquireWakeLock(context)

        try {
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, ringtoneUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            AppLog.i(TAG, "Alarm ringing started")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to play ringtone, trying fallback", e)
            tryFallbackRing(context)
        }
    }

    private fun tryFallbackRing(context: Context) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setDataSource(context, notificationUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (e2: Exception) {
            AppLog.e(TAG, "Fallback ring also failed", e2)
        }
    }

    fun stopRinging() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
            mediaPlayer = null
        } catch (_: Exception) {}
        releaseWakeLock()
        AppLog.i(TAG, "Alarm ringing stopped")
    }

    fun isRinging(): Boolean = mediaPlayer?.isPlaying == true

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "DutyClock:WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.apply {
                if (isHeld) release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DutyAlarmManager.ALARM_ACTION) {
            val alarmManager = DutyAlarmManager.getInstance()
            alarmManager.startRinging(context)

            val alarmIntent = Intent(context, Class.forName("com.dutyclock.MainActivity")).apply {
                // 使用 Class.forName 避免循环依赖
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("alarm_triggered", true)
            }
            context.startActivity(alarmIntent)
        }
    }
}
