package com.CPoseMouse.core

import android.content.Context
import android.graphics.Bitmap

class NcnnEngine(private val useVulkan: Boolean = false, private val inputSize: Int = 320) : InferenceEngine {
    override val name = if (useVulkan) "NCNN+Vulkan" else "NCNN+CPU"
    private var loaded = false
    private var lastInferMsVal = 0.0
    private var logCounter = 0

    companion object {
        private const val TAG = "NcnnEngine"

        init {

            System.loadLibrary("cposemouse_ncnn")
        }

        @JvmStatic
        external fun nativeLoadModel(assetManager: android.content.res.AssetManager, useGpu: Boolean, inputSize: Int): Boolean

        @JvmStatic
        external fun nativeDetect(pixels: IntArray, width: Int, height: Int, confThresh: Float): FloatArray?

        @JvmStatic
        external fun nativeDetectBitmap(bitmap: Bitmap, w: Int, h: Int, confThresh: Float): FloatArray?

        @JvmStatic
        external fun nativeDetectRawBgra(bgraData: ByteArray, w: Int, h: Int, confThresh: Float): FloatArray?

        @JvmStatic
        external fun nativeBenchmark(warmup: Int, runs: Int): Double

        @JvmStatic
        external fun nativeRelease()

        @JvmStatic
        external fun nativeInitThread()

        @JvmStatic
        external fun nativeBgraToRgba(bgraData: ByteArray, pixelCount: Int)

        @JvmStatic
        external fun nativeIsVulkanAvailable(): Boolean
    }


    override fun loadModel(context: Context): Boolean {
        try {
            nativeInitThread()
            val ok = nativeLoadModel(context.assets, useVulkan, inputSize)
            loaded = ok
            if (ok) {
                AppLog.i(TAG, "NCNN model loaded! Vulkan=$useVulkan")
                val dummyBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
                detect(dummyBitmap, 0.5f)
                dummyBitmap.recycle()
                AppLog.i(TAG, "NCNN warmup detect done")
                val avgMs = nativeBenchmark(5, 20)
                if (avgMs > 0) {
                    AppLog.i(TAG, String.format("BENCHMARK: 20 runs avg=%.1fms FPS=%.0f Vulkan=%b", avgMs, 1000.0 / avgMs, useVulkan))
                }
            } else {
                AppLog.e(TAG, "NCNN loadModel failed")
            }
            return ok
        } catch (e: Exception) {
            AppLog.e(TAG, "NCNN loadModel exception", e)
            return false
        }
    }


    private fun parseDetectResult(result: FloatArray, scaleX: Float, scaleY: Float): List<PoseResult> {
        if (result.size < 2) return emptyList()
        val channels = result[0].toInt()
        val count = result[1].toInt()
        val poses = mutableListOf<PoseResult>()
        var idx = 2
        while (idx < result.size) {
            val i = result[idx++].toInt()
            val objConf = result[idx++]
            val kps = mutableListOf<Keypoint>()
            for (k in 0 until 17) {
                val base = 5 + k * 3
                if (base + 2 < channels && idx + 2 < result.size) {
                    kps.add(Keypoint(result[idx++] * scaleX, result[idx++] * scaleY, result[idx++]))
                }
            }
            poses.add(PoseResult(objConf, kps))
        }
        return poses
    }

    override fun detect(bitmap: Bitmap, confThresh: Float): List<PoseResult> {
        if (!loaded) return emptyList()
        try {
            val w = bitmap.width
            val h = bitmap.height

            val t0 = System.nanoTime()
            val result = nativeDetectBitmap(bitmap, w, h, confThresh) ?: return emptyList()
            val t1 = System.nanoTime()
            lastInferMsVal = (t1 - t0) / 1e6
            logCounter++
            if (logCounter % 30 == 1) {
                AppLog.i(TAG, String.format("total=%.1fms Vulkan=%b", lastInferMsVal, useVulkan))
            }

            val scaleX = w.toFloat() / inputSize
            val scaleY = h.toFloat() / inputSize
            return parseDetectResult(result, scaleX, scaleY)
        } catch (e: Exception) {
            AppLog.e(TAG, "detect failed", e)
            return emptyList()
        }
    }

    override fun isGpuAvailable(): Boolean {
        return if (useVulkan) {
            try { nativeIsVulkanAvailable() } catch (_: Exception) { false }
        } else false
    }

    override fun isInferenceFast() = lastInferMsVal < 15.0
    override fun lastInferMs() = lastInferMsVal

    fun benchmarkOnly(context: Context, warmup: Int = 5, runs: Int = 20): Double? {
        try {
            nativeInitThread()
            val ok = nativeLoadModel(context.assets, useVulkan, inputSize)
            if (!ok) {
                AppLog.e(TAG, "BENCHMARK: loadModel failed")
                return null
            }
            AppLog.i(TAG, "BENCHMARK: model loaded, running warmup=$warmup runs=$runs")
            val dummyBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            detect(dummyBitmap, 0.5f)
            dummyBitmap.recycle()
            val avgMs = nativeBenchmark(warmup, runs)
            if (avgMs > 0) {
                val fps = 1000.0 / avgMs
                AppLog.i(TAG, String.format("BENCHMARK_RESULT: avg=%.1fms FPS=%.0f runs=%d size=%d", avgMs, fps, runs, inputSize))
            }
            nativeRelease()
            loaded = false
            return avgMs
        } catch (e: Exception) {
            AppLog.e(TAG, "BENCHMARK: exception", e)
            return null
        }
    }

    @Volatile var benchmarkRunning = false

    fun benchmarkContinuous(context: Context, durationSec: Int = 30) {
        Thread {
            try {
                nativeInitThread()
                val ok = nativeLoadModel(context.assets, useVulkan, inputSize)
                if (!ok) {
                    AppLog.e(TAG, "BENCH_C: loadModel failed")
                    benchmarkRunning = false
                    return@Thread
                }
                loaded = true
                val bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
                for (i in 0 until 3) detect(bitmap, 0.5f)
                AppLog.i(TAG, "BENCH_C: start duration=${durationSec}s size=$inputSize")

                var totalFrames = 0
                var totalMs = 0.0
                val startMs = System.currentTimeMillis()
                var lastReportMs = startMs
                var lastReportFrames = 0

                while (benchmarkRunning) {
                    val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                    if (elapsed >= durationSec) break

                    val t0 = System.nanoTime()
                    detect(bitmap, 0.5f)
                    val t1 = System.nanoTime()
                    val inferMs = (t1 - t0) / 1e6
                    totalFrames++
                    totalMs += inferMs

                    val now = System.currentTimeMillis()
                    if (now - lastReportMs >= 2000) {
                        val intervalFrames = totalFrames - lastReportFrames
                        val intervalSec = (now - lastReportMs) / 1000.0
                        val intervalFps = intervalFrames / intervalSec
                        val avgMs2 = totalMs / totalFrames
                        val overallFps = 1000.0 / avgMs2
                        AppLog.i(TAG, String.format("BENCH_C: t=%.0fs frames=%d avg=%.1fms instFPS=%.1f overallFPS=%.1f", elapsed, totalFrames, avgMs2, intervalFps, overallFps))
                        lastReportMs = now
                        lastReportFrames = totalFrames
                    }
                }

                bitmap.recycle()
                val avgMs = if (totalFrames > 0) totalMs / totalFrames else 0.0
                val fps = if (avgMs > 0) 1000.0 / avgMs else 0.0
                AppLog.i(TAG, String.format("BENCH_C_DONE: frames=%d avg=%.1fms FPS=%.1f duration=%.1fs", totalFrames, avgMs, fps, (System.currentTimeMillis() - startMs) / 1000.0))
                nativeRelease()
                loaded = false
            } catch (e: Exception) {
                AppLog.e(TAG, "BENCH_C: exception", e)
            } finally {
                benchmarkRunning = false
            }
        }.start()
    }

    override fun release() {
        nativeRelease()
        loaded = false
    }

    override fun detectBgra(bgraData: ByteArray, w: Int, h: Int, confThresh: Float): List<PoseResult> {
        if (!loaded) return emptyList()
        try {
            val t0 = System.nanoTime()
            val result = nativeDetectRawBgra(bgraData, w, h, confThresh) ?: return emptyList()
            val t1 = System.nanoTime()
            lastInferMsVal = (t1 - t0) / 1e6
            logCounter++
            if (logCounter % 30 == 1) {
                AppLog.i(TAG, String.format("raw=%.1fms Vulkan=%b", lastInferMsVal, useVulkan))
            }

            val scaleX = w.toFloat() / inputSize
            val scaleY = h.toFloat() / inputSize
            return parseDetectResult(result, scaleX, scaleY)
        } catch (e: Exception) {
            AppLog.e(TAG, "detectRawBgra failed", e)
            return emptyList()
        }
    }

}