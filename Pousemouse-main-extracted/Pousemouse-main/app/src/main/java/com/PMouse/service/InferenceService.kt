package com.PMouse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.PMouse.core.*
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
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
        val inputSize = intent?.getIntExtra("inputSize", 320) ?: 320
        val useUdp = intent?.getBooleanExtra("useUdp", false) ?: false
        useUdpMode = useUdp
        startForegroundService()

        if (useUdp) {
            startUdpMode(engineType, inputSize)
        } else {
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data = intent?.getParcelableExtra<android.content.Intent>("data")
            if (resultCode == Activity.RESULT_CANCELED || data == null) {
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

            val det = when (engineType) {

                "litert_npu" -> LiteRtEngine("npu")
                "neuron_direct" -> NeuronDirectEngine()
                "ncnn_cpu" -> NcnnEngine(useVulkan = false, inputSize = inputSize)
                else -> NcnnEngine(useVulkan = false, inputSize = inputSize)
            }
            engine = det

            val ok = det.loadModel(this)
            if (!ok) {
                AppLog.e(TAG, "Model load failed")
                stopSelf()
                return@Thread
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
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)

        Thread {

            val sc = ScreenCapture(proj, dm.widthPixels, dm.heightPixels)
            capture = sc
            InferenceServiceHolder.capture = sc


            sc.start()
            AppLog.i(TAG, "ScreenCapture started ${dm.widthPixels}x${dm.heightPixels}")

            val det = when (engineType) {

                "litert_npu" -> LiteRtEngine("npu")
                "neuron_direct" -> NeuronDirectEngine()
                "ncnn" -> NcnnEngine(useVulkan = true, inputSize = inputSize)
                "ncnn_cpu" -> NcnnEngine(useVulkan = false, inputSize = inputSize)
                else -> NcnnEngine(useVulkan = false, inputSize = inputSize)
            }
            engine = det

            val ok = det.loadModel(this)
            if (!ok) {
                AppLog.e(TAG, "Model load failed")
                stopSelf()
                return@Thread
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
    }
}

private typealias Activity = android.app.Activity
