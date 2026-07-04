package com.dutyclock.core

/**
 * 蔡徐坤铁山靠检测器 — 基于肩部侧向位移检测侧身撞击动作
 *
 * COCO 17关键点索引:
 *   5=左肩, 6=右肩, 11=左髋, 12=右髋, 0=鼻子(辅助参考)
 *
 * 检测原理:
 *   铁山靠是快速侧身/肩膀撞击动作：上身在水平方向相对髋部偏移
 *   肩中点X - 髋中点X > 阈值 → 偏向一侧（靠击）
 *   回到中立即计一次，标准：左右两侧交替靠击
 */
class TieShanKaoDetector(
    val targetCount: Int = 10,
    val minConfidence: Float = 0.25f,
    val minLeanFrames: Int = 2,
    val minCenterFrames: Int = 2,
    /** 肩相对髋的水平偏移阈值（像素，320坐标系） */
    val leanThreshold: Float = 15f
) {
    enum class LeanState { CENTER, LEANING_LEFT, LEANING_RIGHT, RETURNING }

    private var state = LeanState.CENTER
    private var count = 0
    private var stateFrames = 0
    private var lastLeanDir = 0 // -1=左, 0=中, 1=右
    private var lastOffsetX = 0f

    fun getCount(): Int = count
    fun getState(): LeanState = state
    fun getOffsetX(): Float = lastOffsetX
    fun isCompleted(): Boolean = count >= targetCount
    fun getProgress(): Float = (count.toFloat() / targetCount).coerceIn(0f, 1f)

    fun reset() {
        state = LeanState.CENTER
        count = 0
        stateFrames = 0
        lastOffsetX = 0f
        lastLeanDir = 0
    }

    /**
     * 处理一帧姿态数据
     * @return 本次是否完成一次完整靠击
     */
    fun processFrame(keypoints: List<Keypoint>): Boolean {
        if (keypoints.size < 13) return false

        val lShoulder = keypoints[5]; val rShoulder = keypoints[6]
        val lHip = keypoints[11]; val rHip = keypoints[12]

        val shoulderValid = lShoulder.conf > minConfidence && rShoulder.conf > minConfidence
        val hipValid = lHip.conf > minConfidence && rHip.conf > minConfidence
        if (!shoulderValid || !hipValid) { stateFrames = 0; return false }

        // 肩中点与髋中点的水平偏移
        val shoulderMidX = (lShoulder.x + rShoulder.x) / 2f
        val hipMidX = (lHip.x + rHip.x) / 2f
        val offsetX = shoulderMidX - hipMidX
        lastOffsetX = offsetX

        val leaningLeft = offsetX < -leanThreshold
        val leaningRight = offsetX > leanThreshold
        val isCenter = kotlin.math.abs(offsetX) < leanThreshold * 0.5f

        return updateState(leaningLeft, leaningRight, isCenter)
    }

    private fun updateState(leaningLeft: Boolean, leaningRight: Boolean, isCenter: Boolean): Boolean {
        when (state) {
            LeanState.CENTER -> {
                if (leaningLeft) { stateFrames++; if (stateFrames >= minLeanFrames) { state = LeanState.LEANING_LEFT; lastLeanDir = -1; stateFrames = 0 } }
                else if (leaningRight) { stateFrames++; if (stateFrames >= minLeanFrames) { state = LeanState.LEANING_RIGHT; lastLeanDir = 1; stateFrames = 0 } }
                else stateFrames = 0
            }
            LeanState.LEANING_LEFT, LeanState.LEANING_RIGHT -> {
                if (isCenter) { stateFrames++; if (stateFrames >= minCenterFrames) { count++; state = LeanState.CENTER; stateFrames = 0; return true } }
                else if (leaningRight && lastLeanDir == -1) { state = LeanState.LEANING_RIGHT; lastLeanDir = 1; stateFrames = 0 }
                else if (leaningLeft && lastLeanDir == 1) { state = LeanState.LEANING_LEFT; lastLeanDir = -1; stateFrames = 0 }
                else stateFrames = 0
            }
            LeanState.RETURNING -> {
                if (isCenter) { stateFrames++; if (stateFrames >= minCenterFrames) { count++; state = LeanState.CENTER; stateFrames = 0; return true } }
                else stateFrames = 0
            }
        }
        return false
    }
}
