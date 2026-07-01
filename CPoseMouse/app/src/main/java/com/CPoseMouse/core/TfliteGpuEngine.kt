package com.CPoseMouse.core

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TfliteGpuEngine(private val inputSize: Int = 320) : InferenceEngine {

    override val name = "TFLite+OpenCL"

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile private var loaded = false
    @Volatile private var lastInferMsVal = 0.0
    private var logCounter = 0
    @Volatile private var outputShape: IntArray? = null
    private var featuresPerDet = 56

    companion object {
        private const val TAG = "TfliteGpuEngine"
        private const val MODEL_INPUT_SIZE = 320
        private const val INPUT_CHANNELS = 3
        private const val KEYPOINT_COUNT = 17
        private const val COORDS_PER_KEYPOINT = 3
        private const val IDX_BBOX_CONF = 4
        private const val IDX_KP_START = 5
        private const val BBOX_CONF_THRESHOLD = 0.5f
        private const val DEFAULT_FEATURES_PER_DET = 56
    }

    private fun loadModelFile(assetManager: AssetManager, filename: String): MappedByteBuffer {
        val fd = assetManager.openFd(filename)
        return FileInputStream(fd.fileDescriptor).use { inputStream ->
            inputStream.channel.use { fileChannel ->
                fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    override fun loadModel(context: Context): Boolean {
        try {
            val delegate = GpuDelegate()
            gpuDelegate = delegate

            val modelBuffer = loadModelFile(context.assets, "yolo26s-pose-320.tflite")
            val tfliteOptions = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, tfliteOptions)
            interpreter = interp

            val outTensor = interp.getOutputTensor(0)
            val shape = outTensor.shape()
            outputShape = shape

            if (shape.size >= 2) {
                featuresPerDet = shape[shape.size - 1]
            }
            AppLog.i(TAG, "TFLite GPU model loaded, output shape: ${shape.joinToString("x")} featuresPerDet=$featuresPerDet")

            val warmupInput = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS * 4)
            warmupInput.order(ByteOrder.nativeOrder())
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS) {
                warmupInput.putFloat(0f)
            }
            warmupInput.rewind()
            val warmupOutput = allocateOutputBuffer(shape)
            interp.run(warmupInput, warmupOutput)
            AppLog.i(TAG, "TFLite GPU warmup done")

            val benchStart = System.nanoTime()
            var benchRuns = 0
            while (benchRuns < 10) {
                warmupInput.rewind()
                warmupOutput.rewind()
                interp.run(warmupInput, warmupOutput)
                benchRuns++
            }
            val benchEnd = System.nanoTime()
            val avgMs = (benchEnd - benchStart) / 1e6 / benchRuns
            AppLog.i(TAG, String.format("TFLite GPU BENCHMARK: 10 runs avg=%.1fms FPS=%.0f", avgMs, 1000.0 / avgMs))

            loaded = true
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "TFLite GPU init failed, trying CPU fallback", e)
            try { interpreter?.close() } catch (_: Exception) {}
            try { gpuDelegate?.close() } catch (_: Exception) {}
            interpreter = null
            gpuDelegate = null
            return tryLoadCpu(context)
        }
    }

    private fun tryLoadCpu(context: Context): Boolean {
        try {
            val modelBuffer = loadModelFile(context.assets, "yolo26s-pose-320.tflite")
            val tfliteOptions = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, tfliteOptions)
            interpreter = interp

            val outTensor = interp.getOutputTensor(0)
            val shape = outTensor.shape()
            outputShape = shape
            if (shape.size >= 2) {
                featuresPerDet = shape[shape.size - 1]
            }
            AppLog.i(TAG, "TFLite CPU fallback loaded, output shape: ${shape.joinToString("x")} featuresPerDet=$featuresPerDet")

            val warmupInput = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS * 4)
            warmupInput.order(ByteOrder.nativeOrder())
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS) {
                warmupInput.putFloat(0f)
            }
            warmupInput.rewind()
            val warmupOutput = allocateOutputBuffer(shape)
            interp.run(warmupInput, warmupOutput)

            loaded = true
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "TFLite CPU fallback also failed", e)
            return false
        }
    }

    private fun allocateOutputBuffer(shape: IntArray): ByteBuffer {
        var elements = 1
        for (d in shape) elements *= d
        val buf = ByteBuffer.allocateDirect(elements * 4)
        buf.order(ByteOrder.nativeOrder())
        return buf
    }

    private fun preprocessBgra(bgraData: ByteArray, w: Int, h: Int): ByteBuffer {
        require(bgraData.size >= w * h * 4) { "bgraData size ${bgraData.size} < expected ${w * h * 4}" }

        val outBuf = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS * 4)
        outBuf.order(ByteOrder.nativeOrder())

        val scaleX = w.toFloat() / MODEL_INPUT_SIZE
        val scaleY = h.toFloat() / MODEL_INPUT_SIZE

        for (ty in 0 until MODEL_INPUT_SIZE) {
            for (tx in 0 until MODEL_INPUT_SIZE) {
                val srcX = tx * scaleX
                val srcY = ty * scaleY

                val x0 = srcX.toInt().coerceIn(0, w - 1)
                val y0 = srcY.toInt().coerceIn(0, h - 1)
                val x1 = (x0 + 1).coerceIn(0, w - 1)
                val y1 = (y0 + 1).coerceIn(0, h - 1)

                val fracX = srcX - x0
                val fracY = srcY - y0

                val idx00 = (y0 * w + x0) * 4
                val idx10 = (y0 * w + x1) * 4
                val idx01 = (y1 * w + x0) * 4
                val idx11 = (y1 * w + x1) * 4

                for (c in 0..2) {
                    val chBgra = when (c) {
                        0 -> 2
                        1 -> 1
                        2 -> 0
                        else -> 0
                    }

                    val v00 = (bgraData[idx00 + chBgra].toInt() and 0xFF) / 255.0f
                    val v10 = (bgraData[idx10 + chBgra].toInt() and 0xFF) / 255.0f
                    val v01 = (bgraData[idx01 + chBgra].toInt() and 0xFF) / 255.0f
                    val v11 = (bgraData[idx11 + chBgra].toInt() and 0xFF) / 255.0f

                    val v0 = v00 * (1 - fracX) + v10 * fracX
                    val v1 = v01 * (1 - fracX) + v11 * fracX
                    val v = v0 * (1 - fracY) + v1 * fracY

                    outBuf.putFloat(v)
                }
            }
        }

        outBuf.rewind()
        return outBuf
    }

    private fun runInference(inputBuf: ByteBuffer): FloatArray {
        val interp = interpreter ?: return FloatArray(0)
        val shape = outputShape ?: return FloatArray(0)

        val outputBuf = allocateOutputBuffer(shape)
        interp.run(inputBuf, outputBuf)
        outputBuf.rewind()

        var elements = 1
        for (d in shape) elements *= d
        val result = FloatArray(elements)
        for (i in 0 until elements) {
            result[i] = outputBuf.getFloat()
        }
        return result
    }

    private fun postprocessYoloPose(rawOutput: FloatArray, numDet: Int): List<PoseResult> {
        if (numDet == 0) return emptyList()

        val poses = mutableListOf<PoseResult>()

        for (d in 0 until numDet) {
            val baseIdx = d * featuresPerDet
            if (baseIdx + featuresPerDet > rawOutput.size) break

            val bboxConf = rawOutput[baseIdx + IDX_BBOX_CONF]
            if (bboxConf < BBOX_CONF_THRESHOLD) continue

            val keypoints = mutableListOf<Keypoint>()
            var maxKpConf = 0f
            for (k in 0 until KEYPOINT_COUNT) {
                val kpIdx = baseIdx + IDX_KP_START + k * COORDS_PER_KEYPOINT
                if (kpIdx + 2 >= rawOutput.size) break
                val kpX = rawOutput[kpIdx] / MODEL_INPUT_SIZE
                val kpY = rawOutput[kpIdx + 1] / MODEL_INPUT_SIZE
                val kpConf = rawOutput[kpIdx + 2]
                if (kpConf > maxKpConf) maxKpConf = kpConf
                keypoints.add(Keypoint(kpX, kpY, kpConf))
            }

            val overallConf = bboxConf * maxKpConf
            poses.add(PoseResult(overallConf, keypoints))
        }

        return poses.sortedByDescending { it.confidence }
    }

    private fun bitmapToBgra(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bgra = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val idx = i * 4
            bgra[idx] = (pixel and 0xFF).toByte()
            bgra[idx + 1] = ((pixel shr 8) and 0xFF).toByte()
            bgra[idx + 2] = ((pixel shr 16) and 0xFF).toByte()
            bgra[idx + 3] = ((pixel shr 24) and 0xFF).toByte()
        }
        return bgra
    }

    override fun detect(bitmap: Bitmap, confThresh: Float): List<PoseResult> {
        if (!loaded) return emptyList()
        try {
            val bgra = bitmapToBgra(bitmap)
            return detectBgra(bgra, bitmap.width, bitmap.height, confThresh)
        } catch (e: Exception) {
            AppLog.e(TAG, "detect failed", e)
            return emptyList()
        }
    }

    override fun detectBgra(bgraData: ByteArray, w: Int, h: Int, confThresh: Float): List<PoseResult> {
        if (!loaded) return emptyList()
        try {
            val t0 = System.nanoTime()

            val inputBuf = preprocessBgra(bgraData, w, h)
            val rawOutput = runInference(inputBuf)
            if (rawOutput.isEmpty()) return emptyList()

            val t1 = System.nanoTime()
            lastInferMsVal = (t1 - t0) / 1e6

            logCounter++
            if (logCounter % 30 == 1) {
                AppLog.i(TAG, String.format("infer=%.1fms rawLen=%d", lastInferMsVal, rawOutput.size))
            }

            val shape = outputShape
            val numDet = if (shape != null && shape.size >= 2) shape[1] else 0

            val poses = postprocessYoloPose(rawOutput, numDet)

            return if (poses.isEmpty()) {
                emptyList()
            } else {
                poses.filter { it.confidence >= confThresh * BBOX_CONF_THRESHOLD }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "detectBgra failed", e)
            return emptyList()
        }
    }

    override fun isGpuAvailable(): Boolean {
        return try {
            val delegate = GpuDelegate()
            delegate.close()
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "GPU Delegate not available: ${e.message}")
            false
        }
    }

    @Volatile var benchmarkRunning = false

    fun benchmarkContinuous(context: Context, durationSec: Int = 30) {
        Thread {
            try {
                val ok = loadModel(context)
                if (!ok) {
                    AppLog.e(TAG, "BENCH_C: loadModel failed")
                    benchmarkRunning = false
                    return@Thread
                }
                val bitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
                for (i in 0 until 3) detect(bitmap, 0.5f)
                AppLog.i(TAG, "BENCH_C: start duration=${durationSec}s size=$inputSize engine=$name")

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
                        val runningAvgMs = totalMs / totalFrames
                        val overallFps = 1000.0 / runningAvgMs
                        AppLog.i(TAG, String.format("BENCH_C: t=%.0fs frames=%d avg=%.1fms instFPS=%.1f overallFPS=%.1f", elapsed, totalFrames, runningAvgMs, intervalFps, overallFps))
                        lastReportMs = now
                        lastReportFrames = totalFrames
                    }
                }

                bitmap.recycle()
                val avgMs = if (totalFrames > 0) totalMs / totalFrames else 0.0
                val fps = if (avgMs > 0) 1000.0 / avgMs else 0.0
                AppLog.i(TAG, String.format("BENCH_C_DONE: frames=%d avg=%.1fms FPS=%.1f duration=%.1fs", totalFrames, avgMs, fps, (System.currentTimeMillis() - startMs) / 1000.0))
                release()
            } catch (e: Exception) {
                AppLog.e(TAG, "BENCH_C: exception", e)
            } finally {
                benchmarkRunning = false
            }
        }.start()
    }

    override fun isInferenceFast() = lastInferMsVal < 20.0

    override fun lastInferMs() = lastInferMsVal

    override fun release() {
        try { interpreter?.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
        loaded = false
        AppLog.i(TAG, "TFLite GPU engine released")
    }
}
