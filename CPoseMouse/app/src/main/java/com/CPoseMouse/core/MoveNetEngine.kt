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

class MoveNetEngine(private val modelVariant: String = "lightning") : InferenceEngine {

    override val name = "MoveNet+OpenCL($modelVariant)"

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile private var loaded = false
    @Volatile private var lastInferMsVal = 0.0
    private var logCounter = 0
    private var useGpu = true

    private val inputSize: Int
    private val modelFile: String

    private var reusableInputBuf: ByteBuffer? = null
    private var reusableOutputBuf: ByteBuffer? = null

    init {
        if (modelVariant == "lightning") {
            inputSize = 192
            modelFile = "movenet_lightning.tflite"
        } else {
            inputSize = 256
            modelFile = "movenet_thunder.tflite"
        }
    }

    companion object {
        private const val TAG = "MoveNetEngine"
        private const val INPUT_CHANNELS = 3
        private const val KEYPOINT_COUNT = 17

        init {
            System.loadLibrary("cposemouse_ncnn")
        }

        @JvmStatic
        external fun nativePreprocessBgra(bgraData: ByteArray, srcW: Int, srcH: Int, dstSize: Int, outBuf: ByteBuffer)
    }

    private fun loadModelFile(assetManager: AssetManager, filename: String): MappedByteBuffer {
        val fd = assetManager.openFd(filename)
        return FileInputStream(fd.fileDescriptor).use { inputStream ->
            inputStream.channel.use { fileChannel ->
                fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun createGpuDelegate(): GpuDelegate {
        return try {
            val options = GpuDelegate.Options()
            options.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            options.setPrecisionLossAllowed(true)
            GpuDelegate(options)
        } catch (e: Exception) {
            AppLog.w(TAG, "GpuDelegate.Options failed, fallback to default: ${e.message}")
            GpuDelegate()
        }
    }

    override fun loadModel(context: Context): Boolean {
        try {
            val delegate = createGpuDelegate()
            gpuDelegate = delegate

            val modelBuffer = loadModelFile(context.assets, modelFile)
            val tfliteOptions = Interpreter.Options().apply {
                addDelegate(delegate)
            }
            val interp = Interpreter(modelBuffer, tfliteOptions)
            interpreter = interp

            val inTensor = interp.getInputTensor(0)
            val outTensor = interp.getOutputTensor(0)
            AppLog.i(TAG, "MoveNet GPU model loaded: ${modelFile} input=${inTensor.shape().joinToString("x")} output=${outTensor.shape().joinToString("x")}")

            initBuffers()
            warmup(interp)
            benchmark(interp, 10)

            loaded = true
            useGpu = true
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "MoveNet GPU init failed, trying CPU fallback", e)
            try { interpreter?.close() } catch (_: Exception) {}
            try { gpuDelegate?.close() } catch (_: Exception) {}
            interpreter = null
            gpuDelegate = null
            return tryLoadCpu(context)
        }
    }

    private fun tryLoadCpu(context: Context): Boolean {
        try {
            val modelBuffer = loadModelFile(context.assets, modelFile)
            val tfliteOptions = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, tfliteOptions)
            interpreter = interp

            val outTensor = interp.getOutputTensor(0)
            AppLog.i(TAG, "MoveNet CPU fallback loaded, output shape: ${outTensor.shape().joinToString("x")}")

            initBuffers()
            warmup(interp)
            benchmark(interp, 10)

            loaded = true
            useGpu = false
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "MoveNet CPU fallback also failed", e)
            return false
        }
    }

    private fun initBuffers() {
        reusableInputBuf = ByteBuffer.allocateDirect(1 * inputSize * inputSize * INPUT_CHANNELS * 4)
            .order(ByteOrder.nativeOrder())
        reusableOutputBuf = ByteBuffer.allocateDirect(1 * 1 * KEYPOINT_COUNT * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }

    private fun warmup(interp: Interpreter) {
        val inputBuf = reusableInputBuf!!
        inputBuf.rewind()
        for (i in 0 until inputSize * inputSize * INPUT_CHANNELS) {
            inputBuf.putFloat(0.5f)
        }
        inputBuf.rewind()

        val outputBuf = reusableOutputBuf!!
        outputBuf.rewind()

        interp.run(inputBuf, outputBuf)
        AppLog.i(TAG, "MoveNet warmup done")
    }

    private fun benchmark(interp: Interpreter, runs: Int) {
        val inputBuf = reusableInputBuf!!
        inputBuf.rewind()
        for (i in 0 until inputSize * inputSize * INPUT_CHANNELS) {
            inputBuf.putFloat(0.5f)
        }
        inputBuf.rewind()
        val outputBuf = reusableOutputBuf!!

        val start = System.nanoTime()
        for (i in 0 until runs) {
            inputBuf.rewind()
            outputBuf.rewind()
            interp.run(inputBuf, outputBuf)
        }
        val end = System.nanoTime()
        val avgMs = (end - start) / 1e6 / runs
        AppLog.i(TAG, String.format("MoveNet BENCHMARK: %d runs avg=%.1fms FPS=%.0f GPU=%b model=%s", runs, avgMs, 1000.0 / avgMs, useGpu, modelVariant))
    }

    private fun preprocessBgra(bgraData: ByteArray, w: Int, h: Int): ByteBuffer {
        require(bgraData.size >= w * h * 4) { "bgraData size ${bgraData.size} < expected ${w * h * 4}" }

        val outBuf = reusableInputBuf!!
        outBuf.rewind()

        nativePreprocessBgra(bgraData, w, h, inputSize, outBuf)

        outBuf.rewind()
        return outBuf
    }

    private fun runInference(inputBuf: ByteBuffer): FloatArray {
        val interp = interpreter ?: return FloatArray(0)
        val outputBuf = reusableOutputBuf!!
        outputBuf.rewind()

        interp.run(inputBuf, outputBuf)
        outputBuf.rewind()

        val result = FloatArray(KEYPOINT_COUNT * 3)
        for (i in result.indices) {
            result[i] = outputBuf.getFloat()
        }
        return result
    }

    private fun postprocessMoveNet(rawOutput: FloatArray): List<PoseResult> {
        if (rawOutput.size < KEYPOINT_COUNT * 3) return emptyList()

        val keypoints = mutableListOf<Keypoint>()
        var totalConf = 0f
        var validCount = 0

        for (k in 0 until KEYPOINT_COUNT) {
            val baseIdx = k * 3
            val y = rawOutput[baseIdx]
            val x = rawOutput[baseIdx + 1]
            val conf = rawOutput[baseIdx + 2]
            keypoints.add(Keypoint(x, y, conf))
            if (conf > 0.3f) {
                totalConf += conf
                validCount++
            }
        }

        val overallConf = if (validCount > 0) totalConf / validCount else 0f
        return listOf(PoseResult(overallConf, keypoints))
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
                AppLog.i(TAG, String.format("infer=%.1fms GPU=%b model=%s", lastInferMsVal, useGpu, modelVariant))
            }

            val poses = postprocessMoveNet(rawOutput)
            return poses.filter { it.confidence >= confThresh }
        } catch (e: Exception) {
            AppLog.e(TAG, "detectBgra failed", e)
            return emptyList()
        }
    }

    override fun isGpuAvailable(): Boolean {
        return try {
            val delegate = createGpuDelegate()
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
                val bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
                for (i in 0 until 3) detect(bitmap, 0.5f)
                AppLog.i(TAG, "BENCH_C: start duration=${durationSec}s engine=$name GPU=$useGpu")

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
                AppLog.i(TAG, String.format("BENCH_C_DONE: frames=%d avg=%.1fms FPS=%.1f GPU=%b model=%s duration=%.1fs", totalFrames, avgMs, fps, useGpu, modelVariant, (System.currentTimeMillis() - startMs) / 1000.0))
                release()
            } catch (e: Exception) {
                AppLog.e(TAG, "BENCH_C: exception", e)
            } finally {
                benchmarkRunning = false
            }
        }.start()
    }

    override fun isInferenceFast() = lastInferMsVal < 15.0

    override fun lastInferMs() = lastInferMsVal

    override fun release() {
        try { interpreter?.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        interpreter = null
        gpuDelegate = null
        reusableInputBuf = null
        reusableOutputBuf = null
        loaded = false
        AppLog.i(TAG, "MoveNet engine released")
    }
}
