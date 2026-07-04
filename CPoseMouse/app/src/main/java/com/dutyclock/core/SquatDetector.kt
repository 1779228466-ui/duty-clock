package com.dutyclock.core

/**
 * 下蹲检测器 - 基于髋部/臀部Y坐标与膝盖Y坐标对比
 *
 * COCO 17关键点索引:
 *   11=左髋, 12=右髋, 13=左膝, 14=右膝, 15=左踝, 16=右踝
 *
 * 检测原理（坐标系：Y越大越靠下）:
 *   站立时 髋Y < 膝Y（髋在上方）
 *   蹲下时 髋Y > 膝Y（髋下降至膝盖下方）
 *   状态机: STANDING → SQUATTING → STANDING 计为1次
 */
class SquatDetector(
    val targetCount: Int = 10,
    val minConfidence: Float = 0.3f,
    val minSquatFrames: Int = 3,
    val minStandFrames: Int = 3
) {
    enum class SquatState { STANDING, SQUATTING, TRANSITIONING }

    private var state = SquatState.STANDING
    private var squatCount = 0
    private var stateFrameCount = 0
    private var lastLeftDist = 0f
    private var lastRightDist = 0f

    fun getCount(): Int = squatCount
    fun getState(): SquatState = state
    /** 髋-膝Y距离（正=髋低于膝即蹲下，负=髋高于膝即站立） */
    fun getLastAngles(): Pair<Float, Float> = Pair(lastLeftDist, lastRightDist)
    fun isCompleted(): Boolean = squatCount >= targetCount
    fun getProgress(): Float = (squatCount.toFloat() / targetCount).coerceIn(0f, 1f)

    fun reset() {
        state = SquatState.STANDING
        squatCount = 0
        stateFrameCount = 0
        lastLeftDist = 0f; lastRightDist = 0f
    }

    /**
     * 处理一帧姿态数据，返回本次是否完成一次完整下蹲
     */
    fun processFrame(keypoints: List<Keypoint>): Boolean {
        if (keypoints.size < 17) return false

        val leftHip = keypoints[11]; val rightHip = keypoints[12]
        val leftKnee = keypoints[13]; val rightKnee = keypoints[14]

        val leftValid = leftHip.conf > minConfidence && leftKnee.conf > minConfidence
        val rightValid = rightHip.conf > minConfidence && rightKnee.conf > minConfidence

        if (!leftValid && !rightValid) { stateFrameCount = 0; return false }

        // 髋Y - 膝Y：正值=髋低于膝（蹲下），负值=髋高于膝（站立）
        val leftDist = if (leftValid) leftHip.y - leftKnee.y else 0f
        val rightDist = if (rightValid) rightHip.y - rightKnee.y else 0f
        val avgDist = when { leftValid && rightValid -> (leftDist + rightDist) / 2f; leftValid -> leftDist; else -> rightDist }

        lastLeftDist = leftDist; lastRightDist = rightDist

        val isSquatting = avgDist > 5f   // 髋低于膝5px以上视为蹲下
        val isStanding = avgDist < -10f   // 髋高于膝10px以上视为站立

        return updateState(isSquatting, isStanding)
    }

    private fun updateState(isSquatting: Boolean, isStanding: Boolean): Boolean {
        when (state) {
            SquatState.STANDING -> {
                if (isSquatting) { stateFrameCount++; if (stateFrameCount >= minSquatFrames) { state = SquatState.SQUATTING; stateFrameCount = 0 } }
                else stateFrameCount = 0
            }
            SquatState.SQUATTING -> {
                if (isStanding) { stateFrameCount++; if (stateFrameCount >= minStandFrames) { squatCount++; state = SquatState.STANDING; stateFrameCount = 0; return true } }
                else if (!isSquatting) { state = SquatState.TRANSITIONING; stateFrameCount = 0 }
                else stateFrameCount = 0
            }
            SquatState.TRANSITIONING -> {
                if (isStanding) { stateFrameCount++; if (stateFrameCount >= minStandFrames) { squatCount++; state = SquatState.STANDING; stateFrameCount = 0; return true } }
                else if (isSquatting) { state = SquatState.SQUATTING; stateFrameCount = 0 }
                else stateFrameCount = 0
            }
        }
        return false
    }
}
