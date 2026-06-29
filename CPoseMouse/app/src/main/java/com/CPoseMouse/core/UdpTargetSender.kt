package com.CPoseMouse.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpTargetSender(private val pcIp: String = "10.181.9.68", private val pcPort: Int = 19878) {
    @Volatile var isRunning = false
        private set
    @Volatile var sendCount = 0L
        private set

    private var socket: DatagramSocket? = null
    private var pcAddr: InetAddress? = null
    @Volatile private var stopped = false

    fun start() {
        if (isRunning) return
        stopped = false
        try {
            pcAddr = InetAddress.getByName(pcIp)
            val sock = DatagramSocket()
            sock.reuseAddress = true
            socket = sock
            isRunning = true
            AppLog.i(TAG, "UDP目标发送器启动 $pcIp:$pcPort")
        } catch (e: Exception) {
            AppLog.e(TAG, "UDP目标发送器启动失败", e)
        }
    }

    fun sendTarget(cropX: Float, cropY: Float, hasTarget: Boolean) {
        val sock = socket ?: return
        val addr = pcAddr ?: return
        try {
            val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(if (hasTarget) 1.toByte() else 0.toByte())
            buf.putFloat(cropX)
            buf.putFloat(cropY)
            val data = buf.array()
            val pkt = DatagramPacket(data, data.size, addr, pcPort)
            sock.send(pkt)
            sendCount++
        } catch (e: Exception) {
            if (sendCount % 1000 == 0L) {
                AppLog.w(TAG, "sendTarget error: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        isRunning = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object { private const val TAG = "UdpTargetSender" }
}