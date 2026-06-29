package com.CPoseMouse.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class InferenceLoop(
    private val capture: ScreenCapture,
    @Volatile var detector: InferenceEngine,
    val adaptivePidController: AdaptivePidController,
    val bodyParts: BodyParts,
    val mouse: BluetoothHidMouse,
    val lowTextureSuppressor: LowTextureSuppressor,
    var useLowTexture: Boolean = true,
    @Volatile var cropSize: Int = 320,
    @Volatile var useUdpMode: Boolean = false,
    @Volatile var confThreshold: Float = 0.6f,
    @Volatile var showFrameBitmap: Boolean = true
) {
    @Volatile var udpTargetSender: UdpTargetSender? = null
    @Volatile var running = false
    @Volatile var enabled = true
    @Volatile var fps = 0
    @Volatile var statusText = "等待数据..."
    @Volatile var latestPoses: List<PoseResult> = emptyList()
    @Volatile var latestTarget: Pair<Float, Float>? = null
    @Volatile var latestCompensatedKps: List<Keypoint>? = null
    @Volatile var latestFrameBitmap: Bitmap? = null
    @Volatile var frameVersion: Long = 0
    private var missCount = 0
    private var prevTime = 0L
    private var reusableBitmap: Bitmap? = null
    private var inferBitmap: Bitmap? = null
    private var lastProcessedFrameId = -1
    private val localBuf = ByteArray(1920 * 1080 * 4)
    private val skeletonPaint = Paint().apply { color = Color.GREEN; strokeWidth = 2f; style = Paint.Style.STROKE }
    private val pointPaint = Paint().apply { color = Color.RED; strokeWidth = 4f; style = Paint.Style.FILL }
    private val targetPaint = Paint().apply { color = Color.YELLOW; strokeWidth = 3f; style = Paint.Style.FILL }

    private val SKELETON_CONNECTIONS = arrayOf(
        intArrayOf(0,1), intArrayOf(0,2), intArrayOf(1,3), intArrayOf(2,4),
        intArrayOf(5,6), intArrayOf(5,7), intArrayOf(7,9), intArrayOf(6,8), intArrayOf(8,10),
        intArrayOf(5,11), intArrayOf(6,12), intArrayOf(11,12),
        intArrayOf(11,13), intArrayOf(13,15), intArrayOf(12,14), intArrayOf(14,16)
    )

    private var udpReceiver: UdpFrameReceiver? = null

    fun setUdpReceiver(receiver: UdpFrameReceiver?) { udpReceiver = receiver }

    private var logFrameCount = 0

    fun run() {
        running = true
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        if (detector is NcnnEngine) {
            try { NcnnEngine.nativeInitThread() } catch (_: Exception) {}
        }
        prevTime = System.nanoTime()
        var frameCount = 0
        var fpsTime = System.nanoTime()
        var lastFrameVersion: Long = -1

        while (running) {
            if (useUdpMode) {
                if (!runUdpFrame()) {
                    Thread.sleep(1)
                    logFrameCount++
                    if (logFrameCount % 1000 == 0) {
                        AppLog.w(TAG, "runUdpFrame returned false, sleep count=$logFrameCount")
                    }
                    continue
                }
            } else {
                val currentVersion = capture.frameVersion
                if (currentVersion == lastFrameVersion) {
                    Thread.sleep(1)
                    continue
                }
                lastFrameVersion = currentVersion

                val cropped = capture.getCroppedCenter(cropSize)
                if (cropped == null) {
                    Thread.sleep(1)
                    continue
                }

                var poses = detector.detect(cropped, 0.6f)

                if (useLowTexture && poses.isNotEmpty()) {
                    try {
                        poses = lowTextureSuppressor.filter(poses, cropped, 1f, 1f)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "lowTexture filter error", e)
                    }
                }

                cropped.recycle()
                processPoses(poses, System.nanoTime())
            }

            frameCount++
            val now = System.nanoTime()
            if (now - fpsTime > 1e9) {
                fps = frameCount; frameCount = 0; fpsTime = now
            }
        }
    }

    private fun runUdpFrame(): Boolean {
        val recv = udpReceiver ?: return false

        val t0 = System.nanoTime()
        val frame = recv.getFrame() ?: return false
        val t1 = System.nanoTime()
        val frameId = frame.first
        val w = frame.second
        val buf = frame.third

        if (frameId == lastProcessedFrameId) {
            return false
        }
        lastProcessedFrameId = frameId

        val size = w * w * 4
        System.arraycopy(buf, 0, localBuf, 0, size)
        val t2 = System.nanoTime()

        val ncnn = detector as? NcnnEngine
        val poses = if (ncnn != null) {
            ncnn.detectRawRgba(localBuf, w, w, confThreshold)
        } else {
            var inferBmp = inferBitmap
            if (inferBmp == null || inferBmp.width != w || inferBmp.height != w) {
                inferBmp?.recycle()
                inferBmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
                inferBitmap = inferBmp
            }
            NcnnEngine.nativeBgraToRgba(localBuf, w * w)
            val bb = java.nio.ByteBuffer.wrap(localBuf, 0, w * w * 4)
            inferBmp.copyPixelsFromBuffer(bb)
            val result = detector.detect(inferBmp, confThreshold)
            if (showFrameBitmap) {
                var dispBmp = reusableBitmap
                if (dispBmp == null || dispBmp.width != w || dispBmp.height != w) {
                    dispBmp?.recycle()
                    dispBmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
                    reusableBitmap = dispBmp
                }
                val canvas = android.graphics.Canvas(dispBmp)
                canvas.drawBitmap(inferBmp, 0f, 0f, null)
            }
            result
        }
        val t3 = System.nanoTime()

        processPoses(poses, System.nanoTime())
        val t4 = System.nanoTime()

        if (showFrameBitmap) {
            var bmp = reusableBitmap
            if (bmp == null || bmp.width != w || bmp.height != w) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
                reusableBitmap = bmp
            }

            if (ncnn != null) {
                NcnnEngine.nativeBgraToRgba(localBuf, w * w)
                val bb = java.nio.ByteBuffer.wrap(localBuf, 0, w * w * 4)
                bmp.copyPixelsFromBuffer(bb)
            }
            bmp.prepareToDraw()

        val canvas = Canvas(bmp)
        val currentPoses = latestPoses
        for (pose in currentPoses) {
            val kps = pose.keypoints
            for (conn in SKELETON_CONNECTIONS) {
                val a = kps.getOrNull(conn[0])
                val b = kps.getOrNull(conn[1])
                if (a != null && b != null && a.conf > 0.3f && b.conf > 0.3f) {
                    canvas.drawLine(a.x, a.y, b.x, b.y, skeletonPaint)
                }
            }
            for (kp in kps) {
                if (kp.conf > 0.3f) {
                    canvas.drawPoint(kp.x, kp.y, pointPaint)
                }
            }
        }

        val tgt = latestTarget
        if (tgt != null) {
            canvas.drawCircle(tgt.first, tgt.second, 4f, targetPaint)
        }

        latestFrameBitmap = bmp
        frameVersion++

        } else {
            latestFrameBitmap = null
        }
        val t5 = System.nanoTime()

        if (logFrameCount % 30 == 0) {
            AppLog.i(TAG, String.format("frame: getFrame=%.2fms copy=%.2fms infer=%.2fms bitmap=%.2fms pose=%.2fms total=%.2fms",
                (t1-t0)/1e6, (t2-t1)/1e6, (t3-t2)/1e6, (t4-t3)/1e6, (t5-t4)/1e6, (t5-t0)/1e6))
        }
        logFrameCount++

        return true
    }

    private fun resetController() {
        adaptivePidController.reset()

    }

    private fun processPoses(poses: List<PoseResult>, now: Long) {
        val dt = (now - prevTime) / 1e9f
        prevTime = now

        if (!enabled) {
            resetController()
            statusText = "已暂停"
            latestPoses = poses
            latestTarget = null
            latestCompensatedKps = null
            return
        }

        processAdaptivePid(poses, dt)
    }

    private fun processAdaptivePid(poses: List<PoseResult>, dt: Float) {
        if (poses.isEmpty()) {
            adaptivePidController.reset()
            statusText = "未检测到人体"
            latestPoses = poses
            latestTarget = null
            latestCompensatedKps = null
            val sender = udpTargetSender
            if (sender != null && sender.isRunning) {
                sender.sendTarget(0f, 0f, false)
            }
            return
        }

        val best = poses.maxByOrNull { it.confidence } ?: return
        val target = bodyParts.computeTarget(best.keypoints)
        if (target == null) {
            adaptivePidController.reset()
            statusText = "无法计算目标点"
            latestPoses = poses
            latestTarget = null
            latestCompensatedKps = null
            return
        }

        val errX = target.x - cropSize / 2f
        val errY = target.y - cropSize / 2f

        val (dx, dy) = adaptivePidController.compute(errX, errY)

        val sender = udpTargetSender
        if (sender != null && sender.isRunning) {
            sender.sendTarget(target.x, target.y, true)
        }
        if (mouse.isConnected()) {
            mouse.move(dx, dy)
        }

        latestCompensatedKps = null

        val mode = if (useUdpMode) "UDP" else "CAP"
        statusText = "跟踪中 conf=${String.format("%.2f", best.confidence)} dx=$dx dy=$dy err=${String.format("%.0f", errX)},${String.format("%.0f", errY)} [APID|$mode]"
        latestPoses = poses
        latestTarget = Pair(target.x, target.y)

        if (logFrameCount % 30 == 0) {
            AppLog.i(TAG, String.format("apid dx=%d dy=%d err=%.0f,%.0f dt=%.3f sendInt=%.1fms", dx, dy, errX, errY, dt, mouse.avgSendIntervalMs))
        }
    }

    companion object { private const val TAG = "InferenceLoop" }
}
