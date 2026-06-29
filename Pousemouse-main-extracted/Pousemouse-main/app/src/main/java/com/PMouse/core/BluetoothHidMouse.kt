package com.PMouse.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences

class BluetoothHidMouse(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    var isRegistered = false
        private set

    @Volatile
    var isConnecting = false
        private set

    @Volatile
    var statusMessage = ""
        private set

    var onStatusChanged: (() -> Unit)? = null

    @Volatile var sendCount = 0L
        private set
    @Volatile var lastSendMs = 0L
    @Volatile var avgSendIntervalMs = 0f

    private var lastSendTimeNs = 0L
    private var intervalSum = 0f
    private var intervalCount = 0

    @Volatile var moveLimit: Int = 127

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("posemouse_bt", Context.MODE_PRIVATE)

    private val MOUSE_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x02.toByte(),
        0xA1.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x01.toByte(),
        0xA1.toByte(), 0x00.toByte(),
        0x05.toByte(), 0x09.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x03.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x05.toByte(),
        0x81.toByte(), 0x03.toByte(),
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(),
        0x09.toByte(), 0x38.toByte(),
        0x15.toByte(), 0x81.toByte(),
        0x25.toByte(), 0x7F.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x81.toByte(), 0x06.toByte(),
        0xC0.toByte(),
        0xC0.toByte()
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    saveLastDevice(device)
                    statusMessage = "已连接到 ${try { device.name } catch (_: SecurityException) { device.address }}"
                    AppLog.i(TAG, "HID connected to ${device.address}")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    isConnecting = true
                    statusMessage = "正在连接..."
                    AppLog.i(TAG, "HID connecting")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    isConnecting = false
                    statusMessage = "已断开连接"
                    AppLog.i(TAG, "HID disconnected")
                }
            }
            onStatusChanged?.invoke()
        }

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            statusMessage = if (registered) "HID已注册，请连接PC" else "HID未注册"
            AppLog.i(TAG, "App registered=$registered")
            onStatusChanged?.invoke()
        }
    }

    fun register(context: Context) {
        if (!adapter.isEnabled) {
            statusMessage = "蓝牙未开启！"
            AppLog.e(TAG, "Bluetooth is not enabled!")
            onStatusChanged?.invoke()
            return
        }
        if (isRegistered) {
            AppLog.i(TAG, "Already registered")
            return
        }
        AppLog.i(TAG, "Starting getProfileProxy for HID_DEVICE...")
        val result = adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    AppLog.i(TAG, "onServiceConnected profile=$profile")
                    if (proxy is BluetoothHidDevice) {
                        hidDevice = proxy
                        val sdp = BluetoothHidDeviceAppSdpSettings(
                            "PoseMouse",
                            "BT Mouse",
                            "PoseMouse",
                            BluetoothHidDevice.SUBCLASS1_MOUSE,
                            MOUSE_DESCRIPTOR
                        )
                        val qos = BluetoothHidDeviceAppQosSettings(
                            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                            800, 9, 0, 11250, 11250
                        )
                        val ok = proxy.registerApp(sdp, null, qos, { it.run() }, callback)
                        AppLog.i(TAG, "registerApp result=$ok")
                        if (ok) {
                            isRegistered = true
                            statusMessage = "HID已注册，请连接PC"
                        } else {
                            statusMessage = "registerApp失败"
                        }
                    } else {
                        statusMessage = "proxy类型不对: ${proxy.javaClass.simpleName}"
                        AppLog.e(TAG, "Proxy is not BluetoothHidDevice, class=${proxy.javaClass.name}")
                    }
                    onStatusChanged?.invoke()
                }

                override fun onServiceDisconnected(profile: Int) {
                    hidDevice = null
                    isRegistered = false
                    statusMessage = "HID服务断开"
                    AppLog.i(TAG, "HID service disconnected")
                    onStatusChanged?.invoke()
                }
            },
            BluetoothProfile.HID_DEVICE
        )
        AppLog.i(TAG, "getProfileProxy returned $result")
        if (!result) {
            statusMessage = "getProfileProxy返回false"
            onStatusChanged?.invoke()
        }
    }

    fun autoConnect() {
        val addr = prefs.getString("last_device_addr", null)
        if (!addr.isNullOrEmpty()) {
            AppLog.i(TAG, "autoConnect to saved $addr")
            try {
                val device = adapter.getRemoteDevice(addr)
                connectToDevice(device)
                return
            } catch (e: Exception) {
                AppLog.e(TAG, "autoConnect to saved failed", e)
            }
        }
        try {
            val bonded = adapter.bondedDevices
            if (bonded.isNotEmpty()) {
                val device = bonded.first()
                AppLog.i(TAG, "autoConnect to bonded ${device.address} (${try { device.name } catch (_: SecurityException) { "?" }})")
                connectToDevice(device)
            } else {
                AppLog.i(TAG, "autoConnect: no bonded devices found")
            }
        } catch (e: SecurityException) {
            AppLog.e(TAG, "autoConnect: no permission for bonded devices", e)
        } catch (e: Exception) {
            AppLog.e(TAG, "autoConnect failed", e)
        }
    }

    private fun saveLastDevice(device: BluetoothDevice) {
        try {
            prefs.edit()
                .putString("last_device_addr", device.address)
                .putString("last_device_name", try { device.name } catch (_: SecurityException) { "" })
                .apply()
        } catch (_: SecurityException) {}
    }

    fun getLastDeviceName(): String = prefs.getString("last_device_name", "") ?: ""

    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            adapter.bondedDevices.toList()
        } catch (e: SecurityException) {
            AppLog.e(TAG, "No permission to get bonded devices", e)
            emptyList()
        }
    }

    fun startDiscovery(): Boolean {
        return try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            AppLog.e(TAG, "No permission for discovery", e)
            false
        }
    }

    fun stopDiscovery() {
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            AppLog.e(TAG, "No permission to cancel discovery", e)
        }
    }

    fun pairAndConnect(device: BluetoothDevice) {
        Thread {
            try {
                when (device.bondState) {
                    BluetoothDevice.BOND_NONE -> {
                        statusMessage = "正在配对..."
                        onStatusChanged?.invoke()
                        device.createBond()
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        statusMessage = "配对中..."
                        onStatusChanged?.invoke()
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        connectToDevice(device)
                    }
                }
            } catch (e: SecurityException) {
                statusMessage = "配对权限被拒"
                AppLog.e(TAG, "pair error", e)
                onStatusChanged?.invoke()
            } catch (e: Exception) {
                statusMessage = "配对异常: ${e.message}"
                AppLog.e(TAG, "pair error", e)
                onStatusChanged?.invoke()
            }
        }.start()
    }

    fun connectToDevice(device: BluetoothDevice) {
        Thread {
            try {
                stopDiscovery()
                val name = try { device.name } catch (_: SecurityException) { device.address }
                statusMessage = "正在连接 $name..."
                onStatusChanged?.invoke()
                val hid = hidDevice
                if (hid != null) {
                    AppLog.i(TAG, "hidDevice.connect($name) bondState=${device.bondState}")
                    val ok = hid.connect(device)
                    AppLog.i(TAG, "hidDevice.connect result=$ok")
                    if (!ok) {
                        statusMessage = "连接失败\n请确保:1.先注册HID再配对 2.PC已删除旧配对"
                        onStatusChanged?.invoke()
                    }
                } else {
                    statusMessage = "HID设备未就绪"
                    onStatusChanged?.invoke()
                }
            } catch (e: SecurityException) {
                statusMessage = "连接权限被拒"
                AppLog.e(TAG, "connect error", e)
                onStatusChanged?.invoke()
            } catch (e: Exception) {
                statusMessage = "连接异常: ${e.message}"
                AppLog.e(TAG, "connect error", e)
                onStatusChanged?.invoke()
            }
        }.start()
    }

    fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return

        val limit = moveLimit.coerceIn(1, 127)
        val dev = connectedDevice
        val hid = hidDevice
        if (dev == null || hid == null) return

        val sx = dx.coerceIn(-limit, limit)
        val sy = dy.coerceIn(-limit, limit)

        val report = byteArrayOf(0, sx.toByte(), sy.toByte(), 0)
        val ret = hid.sendReport(dev, 0, report)
        if (ret == false && sendCount % 100L == 0L) {
            AppLog.w(TAG, "sendReport FAILED dx=$sx dy=$sy")
        }

        val now = System.nanoTime()
        if (lastSendTimeNs > 0) {
            val interval = (now - lastSendTimeNs) / 1e6f
            intervalSum += interval
            intervalCount++
            if (intervalCount >= 50) {
                avgSendIntervalMs = intervalSum / intervalCount
                intervalSum = 0f
                intervalCount = 0
            }
        }
        lastSendTimeNs = now
        lastSendMs = now / 1_000_000
        sendCount++

        if (sendCount % 200L == 0L) {
            AppLog.i(TAG, "move diag: sent=$sendCount avgInterval=${String.format("%.1f", avgSendIntervalMs)}ms limit=$limit dev=${try{dev.address}catch(_:SecurityException){"?"}}")
        }
    }

    fun clickLeft() {
        val dev = connectedDevice ?: return
        hidDevice?.sendReport(dev, 0, byteArrayOf(1, 0, 0))
        Thread.sleep(30)
        hidDevice?.sendReport(dev, 0, byteArrayOf(0, 0, 0))
    }

    fun isConnected(): Boolean = connectedDevice != null

    fun unregister() {
        hidDevice?.unregisterApp()
        isRegistered = false
    }

    companion object {
        private const val TAG = "BtHidMouse"
        @Volatile
        var instance: BluetoothHidMouse? = null
            private set
    }

    init {
        instance = this
    }
}
