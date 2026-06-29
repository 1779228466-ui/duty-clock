package com.CPoseMouse.core

import android.graphics.BitmapFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpFrameReceiver(private val port: Int = 19876) {
    @Volatile var isRunning = false
        private set
    @Volatile var frameCount = 0L
        private set
    @Volatile var latestFrameId = -1
        private set
    @Volatile var latestWidth = 0
        private set
    @Volatile var latestHeight = 0
        private set

    @Volatile private var stopped = false
    private val maxFrameSize = 1920 * 1080 * 4
    private val frameBuf = ByteArray(maxFrameSize)
    private val jpegBuf = ByteArray(maxFrameSize)
    private val h264Buf = ByteArray(maxFrameSize)
    private val lock = Object()
    private var useKcp = false
    private var kcpConv = 0x12345678
    private var h264Decoder: H264Decoder? = null

    fun setKcpMode(conv: Int = 0x12345678) {
        useKcp = true
        kcpConv = conv
    }

    fun start() {
        if (isRunning) return
        stopped = false

        if (useKcp) {
            startKcpMode()
            return
        }

        startLegacyMode()
    }

    private fun startKcpMode() {
        try {
            try { System.loadLibrary("cposemouse_ncnn") } catch (_: UnsatisfiedLinkError) {}
            nativeKcpStart(port, kcpConv)
            isRunning = true
            h264Decoder = H264Decoder()
            AppLog.i(TAG, "KCP模式启动 port=$port conv=0x${kcpConv.toString(16)}")

            Thread({
                while (!stopped) {
                    try {
                        val info = nativeKcpGetFrame(h264Buf)
                        if (info != null && info.size == 5) {
                            val fid = info[0]
                            val rawSize = info[1]
                            val compSize = info[2]
                            val dataLen = info[3]
                            val totalPkts = info[4]

                            if (dataLen > 0 && compSize > 0) {
                                val w = calcWidth(rawSize)
                                if (w > 0) {
                                    val decoder = h264Decoder
                                    if (decoder != null) {
                                        decoder.configure(w, w)
                                        val bgra = decoder.decodeFrame(h264Buf, 16, dataLen)
                                        if (bgra != null && bgra.size == rawSize) {
                                            synchronized(lock) {
                                                System.arraycopy(bgra, 0, frameBuf, 0, rawSize)
                                                latestFrameId = fid
                                                latestWidth = w
                                                latestHeight = w
                                            }
                                            frameCount++
                                        } else {
                                            if (frameCount % 60 == 0L) {
                                                AppLog.w(TAG, "H264 decode failed fid=$fid dataLen=$dataLen compSize=$compSize")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Thread.sleep(1)
                        }
                    } catch (e: Exception) {
                        if (!stopped) AppLog.w(TAG, "KCP getFrame error: ${e.message}")
                    }
                }
                isRunning = false
            }, "KcpRecv").start()
        } catch (e: Exception) {
            AppLog.e(TAG, "KCP启动失败", e)
        }
    }

    private fun processJpegFrame(frameId: Int, w: Int, jpegLen: Int) {
        try {
            val bmp = BitmapFactory.decodeByteArray(jpegBuf, 0, jpegLen)
            if (bmp != null && bmp.width == w && bmp.height == w) {
                val pixels = IntArray(w * w)
                bmp.getPixels(pixels, 0, w, 0, 0, w, w)
                val bgra = ByteArray(w * w * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    bgra[i * 4 + 0] = (p and 0xFF).toByte()
                    bgra[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
                    bgra[i * 4 + 2] = ((p shr 16) and 0xFF).toByte()
                    bgra[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
                }
                bmp.recycle()
                synchronized(lock) {
                    System.arraycopy(bgra, 0, frameBuf, 0, w * w * 4)
                    latestFrameId = frameId
                    latestWidth = w
                    latestHeight = w
                }
                frameCount++
            } else {
                if (bmp != null) bmp.recycle()
                AppLog.w(TAG, "jpeg decode mismatch exp=${w}x${w} got=${bmp?.width}x${bmp?.height}")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "jpeg decode error: ${e.message}")
        }
    }

    private fun startLegacyMode() {
        Thread({
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.receiveBufferSize = 16 * 1024 * 1024
                sock.bind(java.net.InetSocketAddress(port))
                isRunning = true
                AppLog.i(TAG, "UDP监听端口 $port (legacy)")

                val recvBuf = ByteArray(65536)
                val pkt = DatagramPacket(recvBuf, recvBuf.size)
                var logCount = 0L

                while (!stopped) {
                    try {
                        sock.receive(pkt)
                    } catch (e: Exception) {
                        if (!stopped) AppLog.w(TAG, "receive error: ${e.message}")
                        continue
                    }

                    val len = pkt.length
                    if (len < 16) continue

                    try {
                        val frameId = ByteBuffer.wrap(recvBuf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val pktIdx = ByteBuffer.wrap(recvBuf, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        val totalPkts = ByteBuffer.wrap(recvBuf, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        val rawSize = ByteBuffer.wrap(recvBuf, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val compSize = ByteBuffer.wrap(recvBuf, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int

                        if (rawSize <= 0 || rawSize > maxFrameSize) continue
                        if (totalPkts <= 0 || totalPkts > 2000 || pktIdx >= totalPkts) continue

                        val chunkLen = len - 16
                        if (chunkLen <= 0) continue

                        logCount++
                        if (logCount <= 5 || logCount % 500 == 0L) {
                            AppLog.i(TAG, "chunk fid=$frameId idx=$pktIdx/$totalPkts raw=$rawSize comp=$compSize chunkLen=$chunkLen")
                        }

                        val key = AssemblyKey(frameId, totalPkts)
                        var asm = assemblies[key]
                        if (asm == null) {
                            asm = Assembly(rawSize, compSize, totalPkts)
                            assemblies[key] = asm
                        }

                        if (!asm.chunks.containsKey(pktIdx)) {
                            val chunkData = ByteArray(chunkLen)
                            System.arraycopy(recvBuf, 16, chunkData, 0, chunkLen)
                            asm.chunks.put(pktIdx, chunkData)
                            asm.receivedBytes += chunkLen
                        }

                        if (!asm.done && asm.chunks.size() >= asm.totalPkts) {
                            asm.done = true
                            val fullSize = if (asm.compSize > 0) asm.compSize else asm.rawSize
                            val fullData = ByteArray(fullSize)
                            var off = 0
                            for (i in 0 until asm.totalPkts) {
                                val c = asm.chunks.get(i)
                                if (c == null) { asm.done = false; break }
                                System.arraycopy(c, 0, fullData, off, c.size)
                                off += c.size
                            }

                            if (asm.done && off == fullSize) {
                                val w = calcWidth(asm.rawSize)
                                if (w > 0) {
                                    val success = if (asm.compSize > 0) {
                                        processJpegFrameInline(frameId, w, fullData, asm.compSize)
                                    } else {
                                        synchronized(lock) {
                                            System.arraycopy(fullData, 0, frameBuf, 0, asm.rawSize)
                                            latestFrameId = frameId
                                            latestWidth = w
                                            latestHeight = w
                                        }
                                        true
                                    }
                                    if (success) frameCount++
                                }
                            }

                            assemblies.remove(key)
                            if (assemblies.size > 30) {
                                val cutoff = frameId - 10
                                val oldKeys = assemblies.keys.filter { it.frameId < cutoff }
                                oldKeys.forEach { assemblies.remove(it) }
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.w(TAG, "packet process error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!stopped) AppLog.e(TAG, "UDP接收异常", e)
            } finally {
                isRunning = false
            }
        }, "UdpRecv").start()
    }

    private fun processJpegFrameInline(frameId: Int, w: Int, data: ByteArray, len: Int): Boolean {
        try {
            val bmp = BitmapFactory.decodeByteArray(data, 0, len)
            if (bmp != null && bmp.width == w && bmp.height == w) {
                val pixels = IntArray(w * w)
                bmp.getPixels(pixels, 0, w, 0, 0, w, w)
                val bgra = ByteArray(w * w * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    bgra[i * 4 + 0] = (p and 0xFF).toByte()
                    bgra[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
                    bgra[i * 4 + 2] = ((p shr 16) and 0xFF).toByte()
                    bgra[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
                }
                bmp.recycle()
                synchronized(lock) {
                    System.arraycopy(bgra, 0, frameBuf, 0, w * w * 4)
                    latestFrameId = frameId
                    latestWidth = w
                    latestHeight = w
                }
                return true
            } else {
                if (bmp != null) bmp.recycle()
                return false
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "jpeg decode error: ${e.message}")
            return false
        }
    }

    private data class AssemblyKey(val frameId: Int, val totalPkts: Int)
    private data class Assembly(
        val rawSize: Int,
        val compSize: Int,
        val totalPkts: Int,
        val chunks: SparseArray = SparseArray(),
        var receivedBytes: Int = 0,
        var done: Boolean = false
    )
    private val assemblies = HashMap<AssemblyKey, Assembly>(8)

    private class SparseArray {
        private val map = HashMap<Int, ByteArray>()
        fun put(k: Int, v: ByteArray) { map[k] = v }
        fun get(k: Int): ByteArray? = map[k]
        fun size(): Int = map.size
        fun containsKey(k: Int): Boolean = map.containsKey(k)
    }

    private fun calcWidth(rawSize: Int): Int {
        return when {
            rawSize == 320 * 320 * 4 -> 320
            rawSize == 160 * 160 * 4 -> 160
            rawSize == 128 * 128 * 4 -> 128
            rawSize == 640 * 640 * 4 -> 640
            else -> {
                val s = Math.sqrt((rawSize / 4).toDouble()).toInt()
                if (s * s * 4 == rawSize) s else 0
            }
        }
    }

    fun getFrame(): Triple<Int, Int, ByteArray>? {
        synchronized(lock) {
            if (latestWidth == 0) return null
            return Triple(latestFrameId, latestWidth, frameBuf)
        }
    }

    fun stop() {
        stopped = true
        h264Decoder?.release()
        h264Decoder = null
        if (useKcp) {
            try { nativeKcpStop() } catch (_: Exception) {}
        }
        isRunning = false
    }

    private external fun nativeKcpStart(port: Int, conv: Int)
    private external fun nativeKcpGetFrame(outBuf: ByteArray): IntArray?
    private external fun nativeKcpStop()

    companion object { private const val TAG = "UdpFrameReceiver" }
}
