package com.CPoseMouse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.CPoseMouse.core.*
import java.util.concurrent.CountDownLatch

class InferenceService : Service() {

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null

    private var loop: InferenceLoop? = null
    private var engine: InferenceEngine? = null
    private var mouse: BluetoothHidMouse? = null
    private var udpReceiver: UdpFrameReceiver? = null
    private var useUdpMode = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun startForegroundService() {
        if (foregroundStarted) return
        foregroundStarted = true
        val serviceType = if (useUdpMode) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(1, buildNotification(), serviceType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val engineType = intent?.getStringExtra("engine") ?: "ncnn_cpu"
        val inputSize = intent?.getIntExtra("inputSize", 256) ?: 256
        val useUdp = intent?.getBooleanExtra("useUdp", false) ?: false
        useUdpMode = useUdp
        startForegroundService()

        if (useUdp) {
            startUdpMode(engineType, inputSize)
        } else {
            val resultCode = intent?.getIntExtra("resultCode", android.app.Activity.RESULT_CANCELED) ?: android.app.Activity.RESULT_CANCELED
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra<Intent>("data")
            }
            if (resultCode == android.app.Activity.RESULT_CANCELED || data == null) {
                AppLog.e(TAG, "Invalid MediaProjection data")
                stopSelf()
                return START_NOT_STICKY
            }
            startCaptureMode(engineType, inputSize, resultCode, data)
        }

        return START_NOT_STICKY
    }

    private fun startUdpMode(engineType: String, inputSize: Int) {
        AppLog.i(TAG, "startUdpMode: engineType=$engineType inputSize=$inputSize")
        Thread {

            val det = createEngine(engineType, inputSize)
            engine = det

            if (!det.loadModel(this)) {
                AppLog.e(TAG, "Engine load failed: ${det.name}, trying CPU fallback")
                val cpuDet = NcnnEngine(useVulkan = false, inputSize = inputSize)
                engine = cpuDet
                if (!cpuDet.loadModel(this)) {
                    AppLog.e(TAG, "CPU fallback also failed")
                    stopSelf()
                    return@Thread
                }
            }

            val btMouse = BluetoothHidMouse(this@InferenceService)
            mouse = btMouse
            btMouse.register(this@InferenceService)
            for (i in 1..10) { if (btMouse.isRegistered) break; Thread.sleep(500) }
            if (btMouse.isRegistered) btMouse.autoConnect()

            val controller = AdaptivePidController()
            val bodyParts = BodyParts()
            val lts = LowTextureSuppressor()
            InferenceServiceHolder.adaptivePidController = controller
            InferenceServiceHolder.lts = lts

            val dummyCapture = ScreenCapture.dummy()
            val l = InferenceLoop(dummyCapture, det, controller, bodyParts, btMouse, lts, true, inputSize, true)


            val udpRecv = UdpFrameReceiver(19876)
            udpRecv.setKcpMode(0x12345678)
            udpReceiver = udpRecv
            udpRecv.start()
            l.setUdpReceiver(udpRecv)

            val udpSender = UdpTargetSender()
            l.udpTargetSender = udpSender
            udpSender.start()

            loop = l
            InferenceServiceHolder.loop = l
            Thread { l.run() }.start()

            AppLog.i(TAG, "UDP KCP mode started, listening on port 19876")
        }.start()
    }

    private fun startCaptureMode(engineType: String, inputSize: Int, resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            AppLog.e(TAG, "getMediaProjection failed", e)
            stopSelf()
            return
        }

        if (projection == null) {
            AppLog.e(TAG, "projection is null")
            stopSelf()
            return
        }

        val proj = projection!!
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        Thread {

            val sc = ScreenCapture(proj, metrics.widthPixels, metrics.heightPixels)
            capture = sc
            InferenceServiceHolder.capture = sc


            sc.start()
            AppLog.i(TAG, "ScreenCapture started ${metrics.widthPixels}x${metrics.heightPixels}")

            val det = createEngine(engineType, inputSize)
            engine = det

            if (!det.loadModel(this)) {
                AppLog.e(TAG, "Engine load failed: ${det.name}, trying CPU fallback")
                val cpuDet = NcnnEngine(useVulkan = false, inputSize = inputSize)
                engine = cpuDet
                if (!cpuDet.loadModel(this)) {
                    AppLog.e(TAG, "CPU fallback also failed")
                    stopSelf()
                    return@Thread
                }
            }

            val btMouse = BluetoothHidMouse(this@InferenceService)
            mouse = btMouse
            btMouse.register(this@InferenceService)
            for (i in 1..10) { if (btMouse.isRegistered) break; Thread.sleep(500) }
            if (btMouse.isRegistered) btMouse.autoConnect()

            val controller = AdaptivePidController()
            val bodyParts = BodyParts()
            val lts = LowTextureSuppressor()
            InferenceServiceHolder.adaptivePidController = controller
            InferenceServiceHolder.lts = lts
            val l = InferenceLoop(sc, det, controller, bodyParts, btMouse, lts, true, inputSize, false)

            loop = l
            InferenceServiceHolder.loop = l
            Thread { l.run() }.start()

            AppLog.i(TAG, "Capture mode started successfully")
        }.start()
    }

    override fun onDestroy() {
        loop?.running = false
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        loop?.udpTargetSender?.stop()
        udpReceiver?.stop()
        InferenceServiceHolder.loop = null
        InferenceServiceHolder.adaptivePidController = null
        InferenceServiceHolder.lts = null
        InferenceServiceHolder.capture = null
        capture?.stop()
        engine?.release()
        mouse?.unregister()
        projection?.stop()
        AppLog.i(TAG, "InferenceService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "PoseMouse", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PoseMouse")
            .setContentText("推理服务运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "posemouse_service"

        fun createEngine(engineType: String, inputSize: Int): InferenceEngine {
            return when (engineType) {
                "movenet_gpu" -> MoveNetEngine()
                "ncnn_cpu" -> NcnnEngine(useVulkan = false, inputSize = inputSize)
                else -> {
                    val movenet = MoveNetEngine()
                    if (movenet.isGpuAvailable()) movenet
                    else NcnnEngine(useVulkan = false, inputSize = inputSize)
                }
            }
        }
    }
}

