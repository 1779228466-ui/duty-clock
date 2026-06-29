package com.PMouse.core

data class Target(val x: Float, val y: Float)

enum class AimPart(val label: String) {
    HEAD("头部"), TORSO("躯干"), LEGS("腿部")
}

class BodyParts(
    var aimPart: AimPart = AimPart.TORSO,
    var aimOffsetX: Float = 0f,
    var aimOffsetY: Float = 0f
) {
    fun computeTarget(kps: List<Keypoint>): Target? {
        return when (aimPart) {
            AimPart.HEAD -> computeHeadTarget(kps)
            AimPart.TORSO -> computeTorsoTarget(kps)
            AimPart.LEGS -> computeLegsTarget(kps)
        }
    }

    private fun computeHeadTarget(kps: List<Keypoint>): Target? {
        val headKps = listOfNotNull(kps.getOrNull(0), kps.getOrNull(1), kps.getOrNull(2), kps.getOrNull(3), kps.getOrNull(4))
            .filter { it.conf > 0.3f }
        if (headKps.isEmpty()) return null
        val cx = headKps.map { it.x }.average().toFloat()
        val cy = headKps.map { it.y }.average().toFloat()
        return Target(cx + aimOffsetX, cy + aimOffsetY)
    }

    private fun computeTorsoTarget(kps: List<Keypoint>): Target? {
        if (kps.size < 7) return null
        val lShoulder = kps[5]
        val rShoulder = kps[6]
        if (lShoulder.conf < 0.3f || rShoulder.conf < 0.3f) return null
        val midX = (lShoulder.x + rShoulder.x) / 2f
        val midY = (lShoulder.y + rShoulder.y) / 2f
        val shoulderW = kotlin.math.abs(rShoulder.x - lShoulder.x)
        val offsetY = shoulderW * 0.4f
        return Target(midX + aimOffsetX, midY + offsetY + aimOffsetY)
    }

    private fun computeLegsTarget(kps: List<Keypoint>): Target? {
        val legKps = listOfNotNull(kps.getOrNull(11), kps.getOrNull(12), kps.getOrNull(13), kps.getOrNull(14), kps.getOrNull(15), kps.getOrNull(16))
            .filter { it.conf > 0.3f }
        if (legKps.isEmpty()) return null
        val cx = legKps.map { it.x }.average().toFloat()
        val cy = legKps.map { it.y }.average().toFloat()
        return Target(cx + aimOffsetX, cy + aimOffsetY)
    }
}
