package com.PMouse.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tanh

class AdaptivePidController(
    var xKp: Float = 0.3f,
    var xKi: Float = 0.01f,
    var xKd: Float = 0.02f,
    var yKp: Float = 0.3f,
    var yKi: Float = 0.01f,
    var yKd: Float = 0.02f,
    var outputLimit: Float = 50.0f,
    var integralLimit: Float = 100.0f,
    var deadZone: Float = 0.3f,
    var integralDeadzone: Float = 1.0f,
    var integralGainThreshold: Float = 50.0f,
    var integralGainRate: Float = 0.015f
) {
    private val axisX = AdaptivePidAxis(true, this)
    private val axisY = AdaptivePidAxis(false, this)

    fun compute(errX: Float, errY: Float): Pair<Int, Int> {
        val ox = axisX.update(errX)
        val oy = axisY.update(errY)
        return Pair(ox.toInt(), oy.toInt())
    }

    fun reset() {
        axisX.reset()
        axisY.reset()
    }

    private class AdaptivePidAxis(
        private val isX: Boolean,
        private val cfg: AdaptivePidController
    ) {
        private var integral = 0f
        private var lastError = 0f
        private var integralGain = 0f

        fun update(error: Float): Float {
            val kp = if (isX) cfg.xKp else cfg.yKp
            val ki = if (isX) cfg.xKi else cfg.yKi
            val kd = if (isX) cfg.xKd else cfg.yKd

            var err = error
            if (abs(err) < cfg.deadZone) err = 0f

            val pOut = kp * err

            if (adjustIntegral(err, lastError)) {
                integral += err
                integral = integral.coerceIn(-cfg.integralLimit, cfg.integralLimit)
            } else {
                integral = 0f
            }

            val iOut = if (abs(integral) > cfg.integralDeadzone) ki * integral else 0f
            val dOut = kd * (err - lastError)

            lastError = err

            var output = pOut + iOut + dOut
            output = output.coerceIn(-cfg.outputLimit, cfg.outputLimit)
            return output
        }

        private fun adjustIntegral(error: Float, lastErr: Float): Boolean {
            val errDeriv = abs(error - lastErr)
            val threshold = cfg.integralGainThreshold
            val rate = cfg.integralGainRate

            if (abs(error) < threshold) {
                var adaptRate = rate * (1f - errDeriv / (threshold * 2f))
                adaptRate = adaptRate.coerceIn(0f, rate)
                integralGain = min(1f, integralGain + adaptRate)
            } else {
                val decay = 0.1f + 0.9f * tanh(abs(error) / (threshold * 2f)).toFloat()
                integralGain *= (1f - 0.05f * decay)
            }

            integralGain = integralGain.coerceIn(0f, 1f)
            return integralGain > 0f
        }

        fun reset() {
            integral = 0f
            lastError = 0f
            integralGain = 0f
        }
    }
}
