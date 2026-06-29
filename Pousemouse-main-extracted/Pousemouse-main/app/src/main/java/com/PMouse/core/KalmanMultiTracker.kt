package com.PMouse.core

import kotlin.math.abs
import kotlin.math.sqrt

class KalmanTrack(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var lastConf: Float = 0f,
    var age: Int = 0,
    var missedFrames: Int = 0,
    var hitStreak: Int = 0
) {
    private var pX = 10f; private var pY = 10f
    private var pVx = 10f; private var pVy = 10f

    fun predict(dt: Float) {
        x += vx * dt
        y += vy * dt
        pX += 1f; pY += 1f; pVx += 1f; pVy += 1f
        age++; missedFrames++
    }

    fun update(mx: Float, my: Float, conf: Float, dt: Float, qR: Float, vScale: Float) {
        val kX = pX / (pX + qR)
        val kY = pY / (pY + qR)
        val kVx = pVx / (pVx + qR * vScale)
        val kVy = pVy / (pVy + qR * vScale)
        val errX = mx - x
        val errY = my - y
        x += kX * errX
        y += kY * errY
        vx += kVx * errX / dt.coerceIn(0.001f, 0.1f)
        vy += kVy * errY / dt.coerceIn(0.001f, 0.1f)
        vx = vx.coerceIn(-500f, 500f)
        vy = vy.coerceIn(-500f, 500f)
        pX *= (1f - kX); pY *= (1f - kY)
        pVx *= (1f - kVx); pVy *= (1f - kVy)
        lastConf = conf
        missedFrames = 0
        hitStreak++
    }

    fun gatedDistance(mx: Float, my: Float, gatingDist: Float): Float {
        val dx = x - mx
        val dy = y - my
        val dist = sqrt(dx * dx + dy * dy)
        return if (dist < gatingDist) dist else Float.MAX_VALUE
    }
}

class HungarianAlgorithm(private val n: Int) {
    private val cost = Array(n) { FloatArray(n) { 0f } }
    private val u = FloatArray(n + 1)
    private val v = FloatArray(n + 1)
    private val p = IntArray(n + 1)
    private val way = IntArray(n + 1)
    private val used = BooleanArray(n + 1)
    private val minv = FloatArray(n + 1)

    fun setCost(i: Int, j: Int, c: Float) { cost[i][j] = c }

    fun solve(): IntArray {
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            minv.fill(Float.MAX_VALUE)
            used.fill(false)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Float.MAX_VALUE
                var j1 = 0
                for (j in 1..n) {
                    if (used[j]) continue
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..n) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta }
                    else { minv[j] -= delta }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] != 0) result[p[j] - 1] = j - 1
        }
        return result
    }
}

class KalmanMultiTracker(
    var measurementNoise: Float = 4.0f,
    var maxMissedFrames: Int = 5,
    var confirmFrames: Int = 2,
    var gatingDistance: Float = 200f,
    var maxPredictFrames: Int = 10,
    var leadTime: Float = 0.025f,
    var velocityNoiseScale: Float = 2.0f
) {
    private val tracks = mutableListOf<KalmanTrack>()
    private var nextId = 0
    private var prevTime = System.nanoTime()

    data class TrackedTarget(
        val id: Int,
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val confidence: Float,
        val hitStreak: Int,
        val isConfirmed: Boolean,
        val isPredicted: Boolean
    )

    fun update(poses: List<PoseResult>, bodyParts: BodyParts): List<TrackedTarget> {
        val now = System.nanoTime()
        val dt = (now - prevTime) / 1e9f
        prevTime = now

        val measurements = poses.mapNotNull { pose ->
            val target = bodyParts.computeTarget(pose.keypoints)
            target?.let { Triple(it.x, it.y, pose.confidence) }
        }

        for (t in tracks) t.predict(dt)

        if (measurements.isEmpty() || tracks.isEmpty()) {
            if (measurements.isNotEmpty()) {
                for ((mi, m) in measurements.withIndex()) {
                    val t = KalmanTrack(nextId++, m.first, m.second, lastConf = m.third)
                    t.update(m.first, m.second, m.third, dt.coerceIn(0.001f, 0.1f), measurementNoise, velocityNoiseScale)
                    tracks.add(t)
                }
            }
            val iter = tracks.iterator()
            while (iter.hasNext()) {
                if (iter.next().missedFrames > maxMissedFrames) iter.remove()
            }
            return tracks.map { t ->
                TrackedTarget(t.id, t.x, t.y, t.vx, t.vy, t.lastConf, t.hitStreak,
                    t.hitStreak >= confirmFrames, t.missedFrames > 0)
            }
        }

        val nT = tracks.size
        val nM = measurements.size
        val n = maxOf(nT, nM)

        val hungarian = HungarianAlgorithm(n)
        val largeCost = gatingDistance * 10f
        for (i in 0 until n) {
            for (j in 0 until n) {
                val c = if (i < nT && j < nM) {
                    tracks[i].gatedDistance(measurements[j].first, measurements[j].second, gatingDistance)
                } else largeCost
                hungarian.setCost(i, j, c)
            }
        }

        val assignment = hungarian.solve()

        val matchedTracks = mutableSetOf<Int>()
        val matchedMeas = mutableSetOf<Int>()

        for (ti in 0 until nT) {
            val mi = assignment.getOrElse(ti) { -1 }
            if (mi < 0 || mi >= nM) continue
            val dist = tracks[ti].gatedDistance(measurements[mi].first, measurements[mi].second, gatingDistance)
            if (dist < gatingDistance) {
                val m = measurements[mi]
                tracks[ti].update(m.first, m.second, m.third, dt.coerceIn(0.001f, 0.1f), measurementNoise, velocityNoiseScale)
                matchedTracks.add(ti)
                matchedMeas.add(mi)
            }
        }

        for (mi in 0 until nM) {
            if (mi in matchedMeas) continue
            val m = measurements[mi]
            val t = KalmanTrack(nextId++, m.first, m.second, lastConf = m.third)
            t.update(m.first, m.second, m.third, dt.coerceIn(0.001f, 0.1f), measurementNoise, velocityNoiseScale)
            tracks.add(t)
        }

        val iter = tracks.iterator()
        while (iter.hasNext()) {
            if (iter.next().missedFrames > maxMissedFrames) iter.remove()
        }

        return tracks.map { t ->
            TrackedTarget(t.id, t.x, t.y, t.vx, t.vy, t.lastConf, t.hitStreak,
                t.hitStreak >= confirmFrames, t.missedFrames > 0)
        }
    }

    fun getBestTarget(): TrackedTarget? {
        val confirmed = tracks.filter { it.hitStreak >= confirmFrames && it.missedFrames <= maxPredictFrames }
        val real = confirmed.filter { it.missedFrames == 0 }.maxByOrNull { it.lastConf }
        if (real != null) {
            val px = real.x + real.vx * leadTime
            val py = real.y + real.vy * leadTime
            return TrackedTarget(real.id, px, py, real.vx, real.vy, real.lastConf, real.hitStreak, true, false)
        }
        val predicted = confirmed.maxByOrNull { it.lastConf * (1f - it.missedFrames.toFloat() / maxPredictFrames) }
        if (predicted != null) {
            val px = predicted.x + predicted.vx * leadTime
            val py = predicted.y + predicted.vy * leadTime
            return TrackedTarget(predicted.id, px, py, predicted.vx, predicted.vy, predicted.lastConf, predicted.hitStreak, true, true)
        }
        return null
    }

    fun reset() {
        tracks.clear()
        nextId = 0
    }
}
