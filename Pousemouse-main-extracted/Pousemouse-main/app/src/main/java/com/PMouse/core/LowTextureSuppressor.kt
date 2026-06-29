package com.PMouse.core

import android.graphics.Bitmap

class LowTextureSuppressor(
    var enabled: Boolean = true,
    var threshold: Float = 15.0f,
    var sampleStep: Int = 4
) {
    fun isLowTexture(bitmap: Bitmap, cx: Float, cy: Float, boxW: Float, boxH: Float): Boolean {
        if (!enabled) return false
        val w = bitmap.width
        val h = bitmap.height
        val x0 = ((cx - boxW / 2f).toInt().coerceIn(0, w - 1))
        val y0 = ((cy - boxH / 2f).toInt().coerceIn(0, h - 1))
        val x1 = ((cx + boxW / 2f).toInt().coerceIn(0, w - 1))
        val y1 = ((cy + boxH / 2f).toInt().coerceIn(0, h - 1))
        if (x1 - x0 < sampleStep * 2 || y1 - y0 < sampleStep * 2) return false

        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var sumR2 = 0L; var sumG2 = 0L; var sumB2 = 0L
        var count = 0
        val pixels = IntArray((x1 - x0) * (y1 - y0))
        bitmap.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
        for (py in 0 until (y1 - y0) step sampleStep) {
            for (px in 0 until (x1 - x0) step sampleStep) {
                val pixel = pixels[py * (x1 - x0) + px]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sumR += r; sumG += g; sumB += b
                sumR2 += r * r.toLong(); sumG2 += g * g.toLong(); sumB2 += b * b.toLong()
                count++
            }
        }
        if (count < 4) return false
        val n = count.toFloat()
        val varR = sumR2 / n - (sumR / n) * (sumR / n)
        val varG = sumG2 / n - (sumG / n) * (sumG / n)
        val varB = sumB2 / n - (sumB / n) * (sumB / n)
        val avgVar = (varR + varG + varB) / 3f
        return avgVar < threshold
    }

    fun filter(poses: List<PoseResult>, bitmap: Bitmap, scaleX: Float, scaleY: Float): List<PoseResult> {
        if (!enabled || poses.isEmpty()) return poses
        return poses.filter { pose ->
            val kps = pose.keypoints
            if (kps.size < 7) return@filter true
            val lSh = kps[5]; val rSh = kps[6]
            val lHi = kps.getOrNull(11); val rHi = kps.getOrNull(12)
            if (lSh.conf < 0.3f || rSh.conf < 0.3f) return@filter true
            val cx = (lSh.x + rSh.x) / 2f * scaleX
            val cy = if (lHi != null && rHi != null && lHi.conf > 0.3f && rHi.conf > 0.3f) {
                ((lSh.y + rSh.y) / 2f + (lHi.y + rHi.y) / 2f) / 2f * scaleY
            } else {
                (lSh.y + rSh.y) / 2f * scaleY
            }
            val boxW = kotlin.math.abs(rSh.x - lSh.x) * scaleX
            val boxH = if (lHi != null && rHi != null && lHi.conf > 0.3f && rHi.conf > 0.3f) {
                kotlin.math.abs((lHi.y + rHi.y) / 2f - (lSh.y + rSh.y) / 2f) * scaleY
            } else {
                boxW * 1.5f
            }
            !isLowTexture(bitmap, cx, cy, boxW, boxH)
        }
    }
}