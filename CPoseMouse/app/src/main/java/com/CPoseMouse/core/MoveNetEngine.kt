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

class MoveNetEngine : InferenceEngine {

    override val name = "MoveNet+OpenCL"

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile private var loaded = false
    @Volatile private var lastInferMsVal = 0.0
    private var logCounter = 0
    private var useGpu = true

    companion object {
        private const val TAG = "MoveNetEngine"
        private const val MODEL_INPUT_SIZE = 256
        private const val INPUT_CHANNELS = 3
        private const val KEYPOINT_COUNT = 17
        private const val MODEL_FILE = "movenet_thunder.tflite"
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

            val modelBuffer = loadModelFile(context.assets, MODEL_FILE)
            val tfliteOptions = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, tfliteOptions)
            interpreter = interp

            val outTensor = interp.getOutputTensor(0)
            val shape = outTensor.shape()
            AppLog.i(TAG, "MoveNet GPU model loaded, output shape: ${shape.joinToString("x")}")

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
            val modelBuffer = loadModelFile(context.assets, MODEL_FILE)
            val tfliteOptions = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, tfliteOptions)
            interpreter = interp

            val outTensor = interp.getOutputTensor(0)
            val shape = outTensor.shape()
            AppLog.i(TAG, "MoveNet CPU fallback loaded, output shape: ${shape.joinToString("x")}")

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

    private fun warmup(interp: Interpreter) {
        val warmupInput = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS * 4)
        warmupInput.order(ByteOrder.nativeOrder())
        for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS) {
            warmupInput.putInt(128)
        }
        warmupInput.rewind()

        val outputBuf = ByteBuffer.allocateDirect(1 * 1 * KEYPOINT_COUNT * 3 * 4)
        outputBuf.order(ByteOrder.nativeOrder())

        interp.run(warmupInput, outputBuf)
        AppLog.i(TAG, "MoveNet warmup done")
    }

    private fun benchmark(interp: Interpreter, runs: Int) {
        val inputBuf = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS * 4)
        inputBuf.order(ByteOrder.nativeOrder())
        for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * INPUT_CHANNELS) {
            inputBuf.putInt(128)
        }
        inputBuf.rewind()
        val outputBuf = ByteBuffer.allocateDirect(1 * 1 * KEYPOINT_COUNT * 3 * 4)
        outputBuf.order(ByteOrder.nativeOrder())

        val start = System.nanoTime()
        for (i in 0 until runs) {
            inputBuf.rewind()
            outputBuf.rewind()
            interp.run(inputBuf, outputBuf)
        }
        val end = System.nanoTime()
        val avgMs = (end - start) / 1e6 / runs
        AppLog.i(TAG, String.format("MoveNet BENCHMARK: %d runs avg=%.1fms FPS=%.0f GPU=%b", runs, avgMs, 1000.0 / avgMs, useGpu))
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

                // MoveNet expects RGB order, input is BGRA
                // Channel 0: R (from BGRA offset 2)
                val chR = 2
                val r00 = (bgraData[idx00 + chR].toInt() and 0xFF)
                val r10 = (bgraData[idx10 + chR].toInt() and 0xFF)
                val r01 = (bgraData[idx01 + chR].toInt() and 0xFF)
                val r11 = (bgraData[idx11 + chR].toInt() and 0xFF)
                val r0 = r00 * (1 - fracX) + r10 * fracX
                val r1 = r01 * (1 - fracX) + r11 * fracX
                val r = (r0 * (1 - fracY) + r1 * fracY).toInt()
                outBuf.putInt(r)

                // Channel 1: G (from BGRA offset 1)
                val chG = 1
                val g00 = (bgraData[idx00 + chG].toInt() and 0xFF)
                val g10 = (bgraData[idx10 + chG].toInt() and 0xFF)
                val g01 = (bgraData[idx01 + chG].toInt() and 0xFF)
                val g11 = (bgraData[idx11 + chG].toInt() and 0xFF)
                val g0 = g00 * (1 - fracX) + g10 * fracX
                val g1 = g01 * (1 - fracX) + g11 * fracX
                val g = (g0 * (1 - fracY) + g1 * fracY).toInt()
                outBuf.putInt(g)

                // Channel 2: B (from BGRA offset 0)
                val chB = 0
                val b00 = (bgraData[idx00 + chB].toInt() and 0xFF)
                val b10 = (bgraData[idx10 + chB].toInt() and 0xFF)
                val b01 = (bgraData[idx01 + chB].toInt() and 0xFF)
                val b11 = (bgraData[idx11 + chB].toInt() and 0xFF)
                val b0 = b00 * (1 - fracX) + b10 * fracX
                val b1 = b01 * (1 - fracX) + b11 * fracX
                val b = (b0 * (1 - fracY) + b1 * fracY).toInt()
                outBuf.putInt(b)
            }
        }

        outBuf.rewind()
        return outBuf
    }

    private fun runInference(inputBuf: ByteBuffer): FloatArray {
        val interp = interpreter ?: return FloatArray(0)

        val outputBuf = ByteBuffer.allocateDirect(1 * 1 * KEYPOINT_COUNT * 3 * 4)
        outputBuf.order(ByteOrder.nativeOrder())

        interp.run(inputBuf, outputBuf)
        outputBuf.rewind()

        val result = FloatArray(1 * 1 * KEYPOINT_COUNT * 3)
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
                AppLog.i(TAG, String.format("infer=%.1fms GPU=%b", lastInferMsVal, useGpu))
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
                AppLog.i(TAG, String.format("BENCH_C_DONE: frames=%d avg=%.1fms FPS=%.1f GPU=%b duration=%.1fs", totalFrames, avgMs, fps, useGpu, (System.currentTimeMillis() - startMs) / 1000.0))
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
        AppLog.i(TAG, "MoveNet engine released")
    }
}