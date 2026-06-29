package com.CPoseMouse.core


import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

class H264Decoder {
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    @Volatile var isReady = false
        private set
    private var decodeCount = 0L
    private var spsPps: ByteArray? = null
    private var formatChanged = false

    fun configure(w: Int, h: Int) {
        if (codec != null && width == w && height == h) return
        release()
        width = w
        height = h
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3)

            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            isReady = true
            formatChanged = false
            AppLog.i(TAG, "MediaCodec H.264 decoder configured: ${w}x${h}")
        } catch (e: Exception) {
            AppLog.e(TAG, "MediaCodec configure failed", e)
            isReady = false
        }
    }

    private fun findNaluStart(data: ByteArray, offset: Int, len: Int, startFrom: Int = 0): Int {
        for (i in startFrom until len - 3) {
            if (data[offset + i] == 0.toByte() &&
                data[offset + i + 1] == 0.toByte() &&
                data[offset + i + 2] == 0.toByte() &&
                data[offset + i + 3] == 1.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun extractSpsPps(data: ByteArray, offset: Int, len: Int): ByteArray? {
        var spsStart = -1
        var ppsStart = -1
        var idrStart = -1
        var pos = 0

        while (pos < len - 4) {
            val naluPos = findNaluStart(data, offset, len, pos)
            if (naluPos < 0) break

            if (naluPos + 4 >= len) break
            val naluType = data[offset + naluPos + 4].toInt() and 0x1F

            when {
                naluType == 7 && spsStart < 0 -> spsStart = naluPos
                naluType == 8 && ppsStart < 0 -> ppsStart = naluPos
                naluType == 5 && idrStart < 0 -> idrStart = naluPos
            }

            pos = naluPos + 4
        }

        if (spsStart >= 0 && ppsStart >= 0) {
            val end = if (idrStart >= 0) idrStart else len
            val spsPpsLen = end - spsStart
            val result = ByteArray(spsPpsLen)
            System.arraycopy(data, offset + spsStart, result, 0, spsPpsLen)
            return result
        }

        return null
    }

    private fun hasSpsPps(data: ByteArray, offset: Int, len: Int): Boolean {
        var hasSps = false
        var hasPps = false
        var pos = 0

        while (pos < len - 4) {
            val naluPos = findNaluStart(data, offset, len, pos)
            if (naluPos < 0) break

            if (naluPos + 4 < len) {
                val naluType = data[offset + naluPos + 4].toInt() and 0x1F
                if (naluType == 7) hasSps = true
                if (naluType == 8) hasPps = true
            }

            pos = naluPos + 4
        }

        return hasSps && hasPps
    }

    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 10

    fun decodeFrame(h264Data: ByteArray, offset: Int, len: Int): ByteArray? {
        val c = codec ?: return null
        try {
            if (decodeCount < 5) {
                val hex = StringBuilder()
                val dumpLen = minOf(32, len)
                for (i in 0 until dumpLen) {
                    hex.append(String.format("%02X ", h264Data[offset + i].toInt() and 0xFF))
                }
                AppLog.i(TAG, "H264 frame #$decodeCount len=$len first32: $hex")
            }

            val extracted = extractSpsPps(h264Data, offset, len)
            if (extracted != null) {
                spsPps = extracted
            }

            var dataToFeed: ByteArray
            var feedOffset: Int
            var feedLen: Int

            if (!hasSpsPps(h264Data, offset, len)) {
                val saved = spsPps
                if (saved != null) {
                    dataToFeed = ByteArray(saved.size + len)
                    System.arraycopy(saved, 0, dataToFeed, 0, saved.size)
                    System.arraycopy(h264Data, offset, dataToFeed, saved.size, len)
                    feedOffset = 0
                    feedLen = dataToFeed.size
                } else {
                    dataToFeed = h264Data
                    feedOffset = offset
                    feedLen = len
                }
            } else {
                dataToFeed = h264Data
                feedOffset = offset
                feedLen = len
            }

            val inputIdx = c.dequeueInputBuffer(10000)
            if (inputIdx < 0) {
                consecutiveFailures++
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    AppLog.w(TAG, "Too many input failures ($consecutiveFailures), resetting decoder")
                    resetDecoder()
                }
                return null
            }

            val buf = c.getInputBuffer(inputIdx) ?: return null
            buf.clear()
            buf.put(dataToFeed, feedOffset, feedLen)
            c.queueInputBuffer(inputIdx, 0, feedLen, decodeCount * 1000000 / 60, 0)
            decodeCount++

            val info = MediaCodec.BufferInfo()
            val outIdx = c.dequeueOutputBuffer(info, 10000)
            when {
                outIdx >= 0 -> {
                    consecutiveFailures = 0
                    try {
                        val image = c.getOutputImage(outIdx)
                        if (image != null) {
                            val bgra = imageToBgra(image, width, height)
                            image.close()
                            c.releaseOutputBuffer(outIdx, false)
                            return bgra
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "getOutputImage failed: ${e.message}")
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    formatChanged = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        AppLog.w(TAG, "Too many output timeouts ($consecutiveFailures), resetting decoder")
                        resetDecoder()
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "decode error len=$len: ${e.message}", e)
            consecutiveFailures++
            if (consecutiveFailures >= maxConsecutiveFailures) {
                resetDecoder()
            }
        }
        return null
    }

    private fun resetDecoder() {
        consecutiveFailures = 0
        val w = width
        val h = height
        release()
        if (w > 0 && h > 0) {
            configure(w, h)
        }
    }


    private fun imageToBgra(image: Image, w: Int, h: Int): ByteArray {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val bgra = ByteArray(w * h * 4)

        for (j in 0 until h) {
            for (i in 0 until w) {
                val yIdx = j * yRowStride + i
                val uvIdx = (j / 2) * uvRowStride + (i / 2) * uvPixelStride

                val y = (yBuf.get(yIdx).toInt() and 0xFF).toFloat()
                val u = (uBuf.get(uvIdx).toInt() and 0xFF).toFloat() - 128f
                val v = (vBuf.get(uvIdx).toInt() and 0xFF).toFloat() - 128f

                var r = y + 1.402f * v
                var g = y - 0.344136f * u - 0.714136f * v
                var b = y + 1.772f * u

                r = r.coerceIn(0f, 255f)
                g = g.coerceIn(0f, 255f)
                b = b.coerceIn(0f, 255f)

                val outIdx = (j * w + i) * 4
                bgra[outIdx] = b.toInt().toByte()
                bgra[outIdx + 1] = g.toInt().toByte()
                bgra[outIdx + 2] = r.toInt().toByte()
                bgra[outIdx + 3] = -1
            }
        }
        return bgra
    }


    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        isReady = false
        spsPps = null
    }

    companion object { private const val TAG = "H264Decoder" }
}
