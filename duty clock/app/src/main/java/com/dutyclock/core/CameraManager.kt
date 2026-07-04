package com.dutyclock.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.YuvImage
import android.media.Image
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * CameraX 摄像头管理器
 * 输出与设备方向一致、已镜像的原始分辨率 Bitmap，用于预览与推理。
 * 模型需要的 320x320 缩放交给推理引擎内部处理，避免预览画质下降。
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameAvailable: (Bitmap) -> Unit
) {
    enum class CameraFacing { FRONT, BACK }

    companion object {
        private const val TAG = "CameraManager"
        fun hasCameraPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
    }

    private var cameraFacing = CameraFacing.FRONT
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isRunning = false

    fun isActive(): Boolean = isRunning
    fun getFacing(): CameraFacing = cameraFacing

    fun start() {
        if (isRunning) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
                isRunning = true
                AppLog.i(TAG, "Camera started: ${cameraFacing.name}")
            } catch (e: Exception) {
                AppLog.e(TAG, "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
        if (isRunning) { unbindCamera(); bindCamera() }
        AppLog.i(TAG, "Camera switched to: ${cameraFacing.name}")
    }

    fun stop() {
        isRunning = false
        unbindCamera()
        AppLog.i(TAG, "Camera stopped")
    }

    fun release() {
        stop()
        cameraExecutor.shutdown()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(
                if (cameraFacing == CameraFacing.FRONT) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            )
            .build()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        imageAnalysis = analysis

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
        } catch (e: Exception) {
            AppLog.e(TAG, "bindCamera failed", e)
        }
    }

    private fun unbindCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camera = null
        imageAnalysis = null
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) onFrameAvailable(bitmap)
        } catch (e: Exception) {
            AppLog.e(TAG, "processImageProxy error", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * YUV_420_888 → RGBA Bitmap → 旋转/镜像 → 强制缩放为 320x320
     * 输出 Bitmap 固定 320x320，与模型输入坐标系完全一致
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val rawBitmap = yuvToBitmap(imageProxy) ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (cameraFacing == CameraFacing.FRONT) postScale(-1f, 1f)
        }

        val oriented = try {
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } catch (e: Exception) {
            AppLog.e(TAG, "rotate bitmap failed", e)
            rawBitmap
        }
        if (oriented !== rawBitmap) rawBitmap.recycle()

        // 强制缩放为 320x320，与模型输入完全一致
        val scaled = Bitmap.createScaledBitmap(oriented, 320, 320, true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    /**
     * YUV_420_888 → JPEG → Bitmap（原始解码，未旋转/缩放）
     */
    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap? {
        val planes = imageProxy.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()

        val width = imageProxy.width
        val height = imageProxy.height
        val nv21 = ByteArray(width * height * 3 / 2)

        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        val uvWidth = width / 2; val uvHeight = height / 2
        val vRowStride = vPlane.rowStride; val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride; val uPixelStride = uPlane.pixelStride

        var uvOffset = width * height
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                nv21[uvOffset++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                nv21[uvOffset++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        try {
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
            return android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
    }
}
