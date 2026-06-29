package com.PMouse.core

import kotlin.math.abs
import kotlin.math.roundToInt


class HxPdController(
    var xKp: Float = 0.2f,
    var xKd: Float = 0.01f,
    var yKp: Float = 0.1f,
    var yKd: Float = 0.01f,
    var xSmooth: Float = 0.5f,
    var ySmooth: Float = 0.2f,
    var xDeadzone: Int = 0,
    var yDeadzone: Int = 0,
    var xMaxOut: Int = 200,
    var yMaxOut: Int = 50,
    var sensitivity: Float = 1.0f
) {
    private var pidX = PdAxis(xKp, xKd)
    private var pidY = PdAxis(yKp, yKd)
    private var smoothedOutX = 0f
    private var smoothedOutY = 0f

    private class PdAxis(var kp: Float, var kd: Float) {
        private var lastErr = 0f

        fun next(err: Float): Float {
            val dErr = err - lastErr
            lastErr = err
            return kp * err + kd * dErr
        }

        fun reset() { lastErr = 0f }
    }

    fun compute(errX: Float, errY: Float): Pair<Int, Int> {
        pidX.kp = xKp; pidX.kd = xKd
        pidY.kp = yKp; pidY.kd = yKd

        val rawX = if (abs(errX) <= xDeadzone) 0f else pidX.next(errX) * sensitivity
        val rawY = if (abs(errY) <= yDeadzone) 0f else pidY.next(errY) * sensitivity

        val clampedXSmooth = xSmooth.coerceIn(0f, 0.999f)
        val clampedYSmooth = ySmooth.coerceIn(0f, 0.999f)

        smoothedOutX = if (abs(rawX) < 0.01f) 0f else smoothedOutX * clampedXSmooth + rawX * (1f - clampedXSmooth)
        smoothedOutY = if (abs(rawY) < 0.01f) 0f else smoothedOutY * clampedYSmooth + rawY * (1f - clampedYSmooth)

        val outX = (smoothedOutX.toDouble().roundToInt()).coerceIn(-xMaxOut, xMaxOut)
        val outY = (smoothedOutY.toDouble().roundToInt()).coerceIn(-yMaxOut, yMaxOut)

        return Pair(outX, outY)
    }

    fun reset() {
        pidX.reset(); pidY.reset()
        smoothedOutX = 0f; smoothedOutY = 0f
    }
}