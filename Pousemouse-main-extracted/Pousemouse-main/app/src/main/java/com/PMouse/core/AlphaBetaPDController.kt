package com.PMouse.core

import kotlin.math.abs
import kotlin.math.roundToInt

class AlphaBetaPDController(
    var kp: Float = 0.14f,
    var ki: Float = 0.25f,
    var kd: Float = 0.0f,
    var alpha: Float = 1.0f,
    var beta: Float = 0.08f,
    var lead: Float = 0.025f,
    var deadZone: Float = 1.0f,
    var vMax: Float = 127.0f,
    var aMax: Float = 4000.0f
) {
    private var estX = 0f
    private var estY = 0f
    private var velX = 0f
    private var velY = 0f
    private var cmdPreX = 0f
    private var cmdPreY = 0f
    private var prevErrX = 0f
    private var prevErrY = 0f
    private var intX = 0f
    private var intY = 0f
    private var zeroHold = 0

    fun compute(tx: Float, ty: Float, dt: Float): Pair<Int, Int> {
        val rx = tx
        val ry = ty

        if (abs(rx) < 1e-6f && abs(ry) < 1e-6f) {
            zeroHold++
            if (zeroHold >= 3) {
                estX = 0f; estY = 0f; velX = 0f; velY = 0f
                cmdPreX = 0f; cmdPreY = 0f
            }
        } else {
            zeroHold = 0
        }

        val residX = rx - estX
        estX += velX * dt + alpha * residX
        velX += (beta / dt) * residX

        val residY = ry - estY
        estY += velY * dt + alpha * residY
        velY += (beta / dt) * residY

        val epx = estX + velX * lead
        val epy = estY + velY * lead

        var uPreX = if (abs(epx) < deadZone) 0f else kp * epx + kd * velX
        var uPreY = if (abs(epy) < deadZone) 0f else kp * epy + kd * velY

        intX += epx * dt
        intY += epy * dt
        uPreX += ki * intX
        uPreY += ki * intY

        uPreX = uPreX.coerceIn(-vMax, vMax)
        uPreY = uPreY.coerceIn(-vMax, vMax)

        val dvMax = aMax * dt
        val dxCmd = uPreX - cmdPreX
        val dyCmd = uPreY - cmdPreY
        cmdPreX += dxCmd.coerceIn(-dvMax, dvMax)
        cmdPreY += dyCmd.coerceIn(-dvMax, dvMax)

        return Pair(cmdPreX.roundToInt(), cmdPreY.roundToInt())
    }

    fun reset() {
        estX = 0f; estY = 0f; velX = 0f; velY = 0f
        cmdPreX = 0f; cmdPreY = 0f
        prevErrX = 0f; prevErrY = 0f; intX = 0f; intY = 0f
        zeroHold = 0
    }
}
