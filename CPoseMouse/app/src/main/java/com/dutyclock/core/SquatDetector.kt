package com.dutyclock.core

import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * 下蹲检测器 - 基于YOLO姿态关键点分析髋-膝-踝角度
 *
 * COCO 17关键点索引:
 *   5=左肩, 6=右肩, 11=左髋, 12=右髋
 *   13=左膝, 14=右膝, 15=左踝, 16=右踝
 *
 * 下蹲检测原理:
 *   计算髋-膝-踝三点形成的角度，当角度小于阈值时为"蹲下"状态
 *   使用状态机: STANDING -> SQUATTING -> STANDING 计为一次完整下蹲
 */
class SquatDetector(
    val targetCount: Int = 10,
    val squatAngleThreshold: Float = 90f,     // 小于此角度视为蹲下
    val standAngleThreshold: Float = 150f,    // 大于此角度视为站立
    val minConfidence: Float = 0.4f,          // 关键点最小置信度
    val minSquatFrames: Int = 3,              // 最少连续蹲下帧数才确认
    val minStandFrames: Int = 3               // 最少连续站立帧数才确认
) {
    enum class SquatState {
        STANDING,      // 站立状态
        SQUATTING,     // 蹲下状态
        TRANSITIONING  // 过渡中
    }

    private var state = SquatState.STANDING
    private var squatCount = 0
    private var stateFrameCount = 0
    private var lastLeftAngle = 0f
    private var lastRightAngle = 0f

    /** 获取当前下蹲计数 */
    fun getCount(): Int = squatCount

    /** 获取当前状态 */
    fun getState(): SquatState = state

    /** 获取最近一次左右膝角度 */
    fun getLastAngles(): Pair<Float, Float> = Pair(lastLeftAngle, lastRightAngle)

    /** 是否已完成目标 */
    fun isCompleted(): Boolean = squatCount >= targetCount

    /** 获取进度 (0.0 ~ 1.0) */
    fun getProgress(): Float = (squatCount.toFloat() / targetCount).coerceIn(0f, 1f)

    /** 重置计数器 */
    fun reset() {
        state = SquatState.STANDING
        squatCount = 0
        stateFrameCount = 0
        lastLeftAngle = 0f
        lastRightAngle = 0f
    }

    /**
     * 处理一帧姿态数据，返回本次是否完成了一次下蹲
     * @param keypoints 17个关键点列表
     * @return 如果本次完成了一次完整下蹲则返回true
     */
    fun processFrame(keypoints: List<Keypoint>): Boolean {
        if (keypoints.size < 17) return false

        // 获取关键点
        val leftHip = keypoints[11]
        val rightHip = keypoints[12]
        val leftKnee = keypoints[13]
        val rightKnee = keypoints[14]
        val leftAnkle = keypoints[15]
        val rightAnkle = keypoints[16]

        // 检查置信度
        val leftValid = leftHip.conf > minConfidence && leftKnee.conf > minConfidence && leftAnkle.conf > minConfidence
        val rightValid = rightHip.conf > minConfidence && rightKnee.conf > minConfidence && rightAnkle.conf > minConfidence

        if (!leftValid && !rightValid) {
            stateFrameCount = 0
            return false
        }

        // 计算左右膝角度
        var leftAngle = 0f
        var rightAngle = 0f

        if (leftValid) {
            leftAngle = computeKneeAngle(leftHip, leftKnee, leftAnkle)
        }
        if (rightValid) {
            rightAngle = computeKneeAngle(rightHip, rightKnee, rightAnkle)
        }

        // 取有效侧的平均角度
        val avgAngle = when {
            leftValid && rightValid -> (leftAngle + rightAngle) / 2f
            leftValid -> leftAngle
            else -> rightAngle
        }

        lastLeftAngle = if (leftValid) leftAngle else 0f
        lastRightAngle = if (rightValid) rightAngle else 0f

        return updateState(avgAngle)
    }

    /**
     * 计算膝关节角度 (髋-膝-踝)
     * 站立时约160-180度，蹲下时约60-90度
     */
    private fun computeKneeAngle(hip: Keypoint, knee: Keypoint, ankle: Keypoint): Float {
        // 向量: 膝->髋 和 膝->踝
        val v1x = hip.x - knee.x
        val v1y = hip.y - knee.y
        val v2x = ankle.x - knee.x
        val v2y = ankle.y - knee.y

        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)

        if (mag1 < 1e-6f || mag2 < 1e-6f) return 180f

        val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle.toDouble())).toFloat()
    }

    private fun acos(x: Double): Double {
        return atan2(sqrt(1.0 - x * x), x)
    }

    /**
     * 状态机更新
     */
    private fun updateState(angle: Float): Boolean {
        when (state) {
            SquatState.STANDING -> {
                if (angle < squatAngleThreshold) {
                    stateFrameCount++
                    if (stateFrameCount >= minSquatFrames) {
                        state = SquatState.SQUATTING
                        stateFrameCount = 0
                    }
                } else {
                    stateFrameCount = 0
                }
            }
            SquatState.SQUATTING -> {
                if (angle > standAngleThreshold) {
                    stateFrameCount++
                    if (stateFrameCount >= minStandFrames) {
                        // 完成一次下蹲
                        squatCount++
                        state = SquatState.STANDING
                        stateFrameCount = 0
                        return true
                    }
                } else if (angle >= squatAngleThreshold) {
                    // 在阈值之间，过渡状态
                    state = SquatState.TRANSITIONING
                    stateFrameCount = 0
                } else {
                    stateFrameCount = 0
                }
            }
            SquatState.TRANSITIONING -> {
                if (angle > standAngleThreshold) {
                    stateFrameCount++
                    if (stateFrameCount >= minStandFrames) {
                        squatCount++
                        state = SquatState.STANDING
                        stateFrameCount = 0
                        return true
                    }
                } else if (angle < squatAngleThreshold) {
                    state = SquatState.SQUATTING
                    stateFrameCount = 0
                } else {
                    stateFrameCount = 0
                }
            }
        }
        return false
    }
}