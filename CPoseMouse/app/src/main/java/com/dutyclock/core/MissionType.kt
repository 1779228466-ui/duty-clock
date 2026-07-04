package com.dutyclock.core

/**
 * 闹钟任务类型
 */
enum class MissionType(val displayName: String, val description: String, val emoji: String, val isPoseType: Boolean = false) {
    SQUAT("深蹲", "完成指定次数的下蹲", "🏋️", true),
    PUSH_UP("俯卧撑", "完成指定次数的俯卧撑", "🤸", true),
    WAVE("挥手", "对着摄像头挥手", "👋", true),
    TIESHANKO("铁山靠", "侧身撞击，铁山靠舞步", "🕺", true),
    SHAKE("摇晃", "摇晃手机指定次数", "💪"),
    NUMBER_ORDER("数字排序", "按升序点击数字方块", "🔢"),
    REACTION("反应测试", "绿灯亮起时快速点击", "⚡"),
    RANDOM_ACTION("随机动作", "老虎机抽选动作任务", "🎰")
}

/**
 * 闹钟重复模式
 */
enum class RepeatMode(val label: String) {
    ONCE("仅一次"),
    DAILY("每天"),
    WEEKDAYS("工作日"),
    WEEKENDS("周末"),
    CUSTOM("自定义")
}

/**
 * 闹钟数据模型
 */
data class Alarm(
    val id: Long = System.currentTimeMillis(),
    val hour: Int = 7,
    val minute: Int = 0,
    val enabled: Boolean = true,
    val label: String = "闹钟",
    val missionType: MissionType = MissionType.SQUAT,
    val missionCount: Int = 10,
    val repeatMode: RepeatMode = RepeatMode.ONCE,
    val repeatDays: Set<Int> = emptySet(), // 1=周一..7=周日
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 5,
    val ringtoneName: String = "",
    val ringtoneUri: String = ""
)

/**
 * 数字排序题库 — 20轮不同排列
 */
class NumberOrderGenerator {
    private val rounds = mutableListOf<List<Int>>()
    fun nextRound(): List<Int> {
        if (rounds.isEmpty()) {
            val base = (1..6).toList()
            while (rounds.size < 20) {
                val shuffled = base.shuffled()
                if (shuffled !in rounds) rounds.add(shuffled)
            }
        }
        return rounds.removeFirst()
    }
}
