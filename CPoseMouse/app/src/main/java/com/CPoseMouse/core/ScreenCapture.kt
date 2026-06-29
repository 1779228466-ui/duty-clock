package com.CPoseMouse.core

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min

class ScreenCapture(
    private val projection: MediaProjection?,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    var isRunning = false
        private set

    private var latestBitmap: Bitmap? = null
    @Volatile
    var frameVersion: Long = 0
        private set
    private val bitmapLock = Object()

    private val captureWidth = screenWidth
    private val captureHeight = screenHeight

    companion object {
        fun dummy(): ScreenCapture = ScreenCapture(null, 160, 160)
        private const val TAG = "ScreenCapture"
    }

    fun start() {
        if (isRunning) return
        val proj = projection ?: return
        captureThread = HandlerThread("ScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.i(TAG, "MediaProjection stopped")
                isRunning = false
            }
        }, captureHandler)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * captureWidth

                val w = captureWidth + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(w, captureHeight, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                val cropped = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight).also { bitmap.recycle() }
                } else {
                    bitmap
                }

                synchronized(bitmapLock) {
                    latestBitmap?.recycle()
                    latestBitmap = cropped
                    frameVersion++
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "capture frame error", e)
            } finally {
                image.close()
            }
        }, captureHandler)

        virtualDisplay = proj.createVirtualDisplay(
            "ScreenCapture",
            captureWidth, captureHeight, getDensityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, captureHandler
        )
        isRunning = true
        AppLog.i(TAG, "ScreenCapture started: ${captureWidth}x${captureHeight}")
    }

    @Volatile
    var cropOffsetX: Int = 0

    @Volatile
    var cropOffsetY: Int = 0

    fun getCroppedCenter(cropSize: Int): Bitmap? {
        synchronized(bitmapLock) {
            val src = latestBitmap ?: return null
            val cx = src.width / 2 + cropOffsetX
            val cy = src.height / 2 + cropOffsetY
            val halfCrop = cropSize / 2
            val left = (cx - halfCrop).coerceAtLeast(0)
            val top = (cy - halfCrop).coerceAtLeast(0)
            val w = min(cropSize, src.width - left)
            val h = min(cropSize, src.height - top)
            if (w <= 0 || h <= 0) return null
            return Bitmap.createBitmap(src, left, top, w, h)
        }
    }

    fun stop() {
        isRunning = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureHandler?.looper?.quitSafely()
        captureThread = null
        captureHandler = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        AppLog.i(TAG, "ScreenCapture stopped")
    }

    private fun getDensityDpi(): Int {
        return DisplayMetrics.DENSITY_DEFAULT
    }

}