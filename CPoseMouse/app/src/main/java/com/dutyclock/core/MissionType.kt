package com.dutyclock.core

/**
 * 闹钟任务类型 - 仿 Alarmy 的多种唤醒任务
 */
enum class MissionType(val displayName: String, val description: String, val emoji: String) {
    SQUAT("深蹲", "完成指定次数的下蹲", "🏋️"),
    WAVE("挥手", "对着摄像头挥手", "👋"),
    MATH("数学", "解出数学题才能关闭", "🧮"),
    MEMORY("记忆", "记住并输入数字序列", "🧠"),
    SHAKE("摇晃", "摇晃手机指定次数", "💪"),
    STEPS("步行", "走够指定的步数", "🚶"),
    TYPING("打字", "准确输入显示的短语", "✍️"),
    PHOTO("拍照", "拍一张指定物品的照片", "📸"),
    QR_CODE("扫码", "扫描特定的二维码/条码", "🏷️")
}

/**
 * 任务难度
 */
enum class MissionDifficulty(val label: String, val multiplier: Float) {
    EASY("简单", 0.5f),
    NORMAL("普通", 1.0f),
    HARD("困难", 1.5f),
    EXTREME("极限", 2.0f)
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
    val difficulty: MissionDifficulty = MissionDifficulty.NORMAL,
    val repeatMode: RepeatMode = RepeatMode.ONCE,
    val repeatDays: Set<Int> = emptySet(), // 自定义重复: 1=周一..7=周日
    val vibrate: Boolean = true,
    val volume: Int = 80, // 0-100
    val snoozeMinutes: Int = 5, // 贪睡时间
    val snoozeEnabled: Boolean = true
)

/**
 * 数学任务问题生成
 */
class MathMission {
    data class Question(val expression: String, val answer: Int)

    fun generate(difficulty: MissionDifficulty): Question {
        return when (difficulty) {
            MissionDifficulty.EASY -> {
                val a = (1..20).random()
                val b = (1..20).random()
                val ops = listOf('+', '-')
                when (ops.random()) {
                    '+' -> Question("$a + $b = ?", a + b)
                    '-' -> Question("${a + b} - $b = ?", a)
                    else -> Question("$a + $b = ?", a + b)
                }
            }
            MissionDifficulty.NORMAL -> {
                val a = (10..99).random()
                val b = (10..99).random()
                val ops = listOf('+', '-', '×')
                when (ops.random()) {
                    '+' -> Question("$a + $b = ?", a + b)
                    '-' -> Question("${a + b} - $b = ?", a)
                    '×' -> {
                        val x = (2..12).random()
                        val y = (2..9).random()
                        Question("$x × $y = ?", x * y)
                    }
                    else -> Question("$a + $b = ?", a + b)
                }
            }
            MissionDifficulty.HARD -> {
                val a = (50..200).random()
                val b = (10..99).random()
                val c = (1..20).random()
                Question("$a + $b - $c = ?", a + b - c)
            }
            MissionDifficulty.EXTREME -> {
                val x = (3..15).random()
                val y = (3..15).random()
                val z = (10..50).random()
                Question("$x × $y + $z = ?", x * y + z)
            }
        }
    }
}

/**
 * 记忆任务生成
 */
class MemoryMission {
    data class Sequence(val digits: String)

    fun generate(difficulty: MissionDifficulty): Sequence {
        val length = when (difficulty) {
            MissionDifficulty.EASY -> 4
            MissionDifficulty.NORMAL -> 6
            MissionDifficulty.HARD -> 8
            MissionDifficulty.EXTREME -> 10
        }
        return Sequence((1..length).map { (0..9).random() }.joinToString(""))
    }
}

/**
 * 打字任务短语库
 */
class TypingMission {
    private val phrases = listOf(
        "早安，新的一天开始了",
        "今天也要加油哦",
        "早起的鸟儿有虫吃",
        "一日之计在于晨",
        "每个早晨都是新的开始",
        "用最好的状态迎接今天",
        "Good morning sunshine",
        "Rise and shine",
        "Make today amazing",
        "Wake up and be awesome"
    )

    fun getRandomPhrase(): String = phrases.random()
}
