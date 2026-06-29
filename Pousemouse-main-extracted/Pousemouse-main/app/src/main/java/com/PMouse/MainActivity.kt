package com.PMouse

import android.graphics.Bitmap
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.PMouse.core.*
import com.PMouse.ui.theme.PMouseTheme

enum class AppTab(val label: String) {
    Monitor("监控"), Function("功能"), Settings("设置")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PMouseTheme { MainApp() } }
    }
}

private val BgColor = Color(0xFFF5F5F5)
private val CardBg = Color.White
private val TextPrimary = Color(0xFF555555)
private val TextSecondary = Color(0xFF7E7E7E)
private val TextDisabled = Color(0xFFCCCCCC)
private val DividerColor = Color(0xFFE2E2E2)
private val StopRed = Color(0xFFC8102E)
private val SurfaceGray = Color(0xFFE7E7E7)
private val NavIndicator = Color(0xFFEEEEEE)
private val StatusGreen = Color(0xFF4CAF50)
private val StatusRed = Color(0xFFE53935)
private val StatusYellow = Color(0xFFFFA726)

@Composable
fun MainApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppTab.Monitor) }
    var isRunning by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var hidRegistered by remember { mutableStateOf(false) }
    var btConnected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("未初始化") }
    var fpsText by remember { mutableStateOf("") }

    val btMouse = remember { BluetoothHidMouse(context) }
    var engineType by remember { mutableStateOf("litert_npu") }
    var useUdpMode by remember { mutableStateOf(true) }
    var capture by remember { mutableStateOf<ScreenCapture?>(null) }
    var loop by remember { mutableStateOf<InferenceLoop?>(null) }

    val adaptivePidController = remember { AdaptivePidController() }
    val bodyParts = remember { BodyParts() }
    val lowTextureSuppressor = remember { LowTextureSuppressor() }

    var useLowTexture by remember { mutableStateOf(true) }
    var aimPart by remember { mutableStateOf(AimPart.TORSO) }
    var confThreshold by remember { mutableStateOf(0.6f) }
    var showFrameBitmap by remember { mutableStateOf(true) }
    var frameBitmapState by remember { mutableStateOf<Pair<Bitmap?, Long>>(null to 0L) }

    var apidXKp by remember { mutableStateOf(adaptivePidController.xKp) }
    var apidXKi by remember { mutableStateOf(adaptivePidController.xKi) }
    var apidXKd by remember { mutableStateOf(adaptivePidController.xKd) }
    var apidYKp by remember { mutableStateOf(adaptivePidController.yKp) }
    var apidYKi by remember { mutableStateOf(adaptivePidController.yKi) }
    var apidYKd by remember { mutableStateOf(adaptivePidController.yKd) }
    var apidOutputLimit by remember { mutableStateOf(adaptivePidController.outputLimit) }
    var apidIntegralLimit by remember { mutableStateOf(adaptivePidController.integralLimit) }
    var apidDeadZone by remember { mutableStateOf(adaptivePidController.deadZone) }
    var apidIntegralDeadzone by remember { mutableStateOf(adaptivePidController.integralDeadzone) }
    var apidIntegralGainThreshold by remember { mutableStateOf(adaptivePidController.integralGainThreshold) }
    var apidIntegralGainRate by remember { mutableStateOf(adaptivePidController.integralGainRate) }
    var btMoveLimit by remember { mutableStateOf(127f) }


    var ltThreshold by remember { mutableStateOf(lowTextureSuppressor.threshold) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (!perms.all { it.value }) Toast.makeText(context, "需要蓝牙和位置权限", Toast.LENGTH_LONG).show()
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, com.PMouse.service.InferenceService::class.java)
            serviceIntent.putExtra("resultCode", result.resultCode)
            serviceIntent.putExtra("data", result.data!!)
            serviceIntent.putExtra("engine", engineType)
            serviceIntent.putExtra("inputSize", if (engineType.startsWith("litert")) 320 else 160)
            serviceIntent.putExtra("useUdp", useUdpMode)
            context.startForegroundService(serviceIntent)
            isRunning = true; statusText = "服务启动中..."
        } else {
            statusText = "截屏权限被拒绝"
        }
    }

    LaunchedEffect(Unit) {
        AppLog.init(context); AppLog.startLogServer()
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(Unit) {
        while (true) {
            val serviceBt = BluetoothHidMouse.instance
            val serviceLoop = InferenceServiceHolder.loop
            hidRegistered = serviceBt?.isRegistered ?: btMouse.isRegistered
            btConnected = serviceBt?.isConnected() ?: btMouse.isConnected()
            if (isRunning) {
                val activeLoop = serviceLoop ?: loop
                statusText = activeLoop?.statusText ?: statusText
                fpsText = "FPS: ${activeLoop?.fps ?: 0}"
                frameBitmapState = if (showFrameBitmap) Pair(activeLoop?.latestFrameBitmap, activeLoop?.frameVersion ?: 0L) else Pair(null, 0L)
            } else {
                statusText = (BluetoothHidMouse.instance ?: btMouse).statusMessage.ifEmpty { "未初始化" }
            }
            kotlinx.coroutines.delay(if (isRunning) 16 else 200)
        }
    }

    Scaffold(
        containerColor = BgColor,
        bottomBar = {
            NavigationBar(containerColor = Color.White, contentColor = TextPrimary) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            when (tab) {
                                AppTab.Monitor -> Icon(Icons.Outlined.Visibility, contentDescription = tab.label)
                                AppTab.Function -> Icon(Icons.Outlined.Extension, contentDescription = tab.label)
                                AppTab.Settings -> Icon(Icons.Outlined.Settings, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = NavIndicator, selectedIconColor = TextPrimary, selectedTextColor = TextPrimary, unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(BgColor)) {
            when (selectedTab) {
                AppTab.Monitor -> MonitorScreen(context, isRunning, isLoading, hidRegistered, btConnected, statusText, fpsText, engineType, useUdpMode, btMouse, capture, loop, lowTextureSuppressor, adaptivePidController, useLowTexture, showFrameBitmap, frameBitmapState, { engineType = it }, { useUdpMode = it }, { isRunning = it }, { statusText = it }, { isLoading = it }, mediaProjectionLauncher)
                AppTab.Function -> FunctionScreen(loop, bodyParts, lowTextureSuppressor, useLowTexture, aimPart, confThreshold, showFrameBitmap, ltThreshold, { useLowTexture = it; loop?.useLowTexture = it; InferenceServiceHolder.loop?.useLowTexture = it }, { aimPart = it; bodyParts.aimPart = it; loop?.bodyParts?.aimPart = it; InferenceServiceHolder.loop?.bodyParts?.aimPart = it }, { confThreshold = it; loop?.confThreshold = it; InferenceServiceHolder.loop?.confThreshold = it }, { showFrameBitmap = it; loop?.showFrameBitmap = it; InferenceServiceHolder.loop?.showFrameBitmap = it }, { ltThreshold = it; lowTextureSuppressor.threshold = it; InferenceServiceHolder.lts?.threshold = it })
                AppTab.Settings -> SettingsScreen(context, engineType, apidXKp, apidXKi, apidXKd, apidYKp, apidYKi, apidYKd, apidOutputLimit, apidIntegralLimit, apidDeadZone, apidIntegralDeadzone, apidIntegralGainThreshold, apidIntegralGainRate, btMoveLimit, { engineType = it }, { apidXKp = it; adaptivePidController.xKp = it; InferenceServiceHolder.adaptivePidController?.xKp = it }, { apidXKi = it; adaptivePidController.xKi = it; InferenceServiceHolder.adaptivePidController?.xKi = it }, { apidXKd = it; adaptivePidController.xKd = it; InferenceServiceHolder.adaptivePidController?.xKd = it }, { apidYKp = it; adaptivePidController.yKp = it; InferenceServiceHolder.adaptivePidController?.yKp = it }, { apidYKi = it; adaptivePidController.yKi = it; InferenceServiceHolder.adaptivePidController?.yKi = it }, { apidYKd = it; adaptivePidController.yKd = it; InferenceServiceHolder.adaptivePidController?.yKd = it }, { apidOutputLimit = it; adaptivePidController.outputLimit = it; InferenceServiceHolder.adaptivePidController?.outputLimit = it }, { apidIntegralLimit = it; adaptivePidController.integralLimit = it; InferenceServiceHolder.adaptivePidController?.integralLimit = it }, { apidDeadZone = it; adaptivePidController.deadZone = it; InferenceServiceHolder.adaptivePidController?.deadZone = it }, { apidIntegralDeadzone = it; adaptivePidController.integralDeadzone = it; InferenceServiceHolder.adaptivePidController?.integralDeadzone = it }, { apidIntegralGainThreshold = it; adaptivePidController.integralGainThreshold = it; InferenceServiceHolder.adaptivePidController?.integralGainThreshold = it }, { apidIntegralGainRate = it; adaptivePidController.integralGainRate = it; InferenceServiceHolder.adaptivePidController?.integralGainRate = it }, { btMoveLimit = it; btMouse.moveLimit = it.toInt(); BluetoothHidMouse.instance?.moveLimit = it.toInt() })
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MonitorScreen(
    context: Context, isRunning: Boolean, isLoading: Boolean,
    hidRegistered: Boolean, btConnected: Boolean,
    statusText: String, fpsText: String,
    engineType: String, useUdpMode: Boolean,
    btMouse: BluetoothHidMouse, capture: ScreenCapture?,
    loop: InferenceLoop?,
    lowTextureSuppressor: LowTextureSuppressor, adaptivePidController: AdaptivePidController,
    useLowTexture: Boolean, showFrameBitmap: Boolean, frameBitmapState: Pair<Bitmap?, Long>,
    onEngineTypeChange: (String) -> Unit,
    onUdpModeChange: (Boolean) -> Unit,
    onRunningChange: (Boolean) -> Unit, onStatusChange: (String) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("PoseMouse", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("UU远程模式", fontSize = 12.sp, color = TextSecondary)
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(when {
                        isRunning && btConnected -> StatusGreen; isRunning -> StatusYellow; hidRegistered -> StatusYellow; else -> TextDisabled
                    }))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(when { isRunning && btConnected -> "运行中"; isRunning -> "蓝牙未连接"; hidRegistered -> "待启动"; else -> "已停止" }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(statusText, fontSize = 12.sp, color = TextDisabled)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp)); HorizontalDivider(color = DividerColor); Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isRunning) {
                        MonitorToolButton(label = if (isLoading) "初始化中..." else "启动", enabled = !isLoading, onClick = {
                            if (useUdpMode) {
                                val serviceIntent = Intent(context, com.PMouse.service.InferenceService::class.java)
                                serviceIntent.putExtra("engine", engineType)
            serviceIntent.putExtra("inputSize", if (engineType.startsWith("litert")) 320 else 160)
                                serviceIntent.putExtra("useUdp", true)
                                context.startForegroundService(serviceIntent)
                                onRunningChange(true); onStatusChange("UDP模式启动中...")
                            } else {
                                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                            }
                        }, modifier = Modifier.weight(1f))
                    } else {
                        MonitorToolButton(label = if (loop?.enabled == true) "暂停" else "恢复", enabled = true, onClick = { loop?.enabled = !(loop?.enabled ?: true) }, modifier = Modifier.weight(1f))
                        MonitorToolButton(label = "停止", enabled = true, isStop = true, onClick = {
                            context.stopService(Intent(context, com.PMouse.service.InferenceService::class.java))
                            onRunningChange(false); onStatusChange("已停止")
                        }, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusBadge("BT", when { btConnected -> "已连接"; hidRegistered -> "未连接"; else -> "未注册" }, btConnected)
                    TechBadge(when (engineType) { "ncnn_cpu" -> "NCNN+CPU"; "ncnn" -> "NCNN+Vulkan"; "litert_npu" -> "LiteRT+NPU"; "neuron_direct" -> "NPU直连"; else -> "NCNN+CPU" })
                    TechBadge(if (useUdpMode) "UDP模式" else "截屏模式")
                }
                if (isRunning) { Spacer(modifier = Modifier.height(4.dp)); Text(fpsText, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary) }
            }
        }
        if (isRunning && showFrameBitmap) {
            val frameBmp = frameBitmapState.first
            val frameVer = frameBitmapState.second
            if (frameBmp != null && !frameBmp.isRecycled) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("实时图像", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Spacer(modifier = Modifier.height(8.dp))
                        key(frameVer) {
                            Image(bitmap = frameBmp.asImageBitmap(), contentDescription = "实时图像", modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
        }
        if (!isRunning) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("推理引擎", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { EngineChip("ncnn_cpu", "NCNN+CPU", engineType == "ncnn_cpu", onEngineTypeChange); EngineChip("ncnn", "NCNN+Vulkan", engineType == "ncnn", onEngineTypeChange) }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { EngineChip("litert_npu", "LiteRT+NPU", engineType == "litert_npu", onEngineTypeChange); EngineChip("neuron_direct", "NPU直连", engineType == "neuron_direct", onEngineTypeChange) }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("图传模式", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { SegmentButton("截屏模式", !useUdpMode, { onUdpModeChange(false) }, Modifier.weight(1f)); SegmentButton("OBS+UDP", useUdpMode, { onUdpModeChange(true) }, Modifier.weight(1f)) }
                }
            }
        } else { Spacer(modifier = Modifier.weight(1f)) }
    }
}

@Composable
fun FunctionScreen(
    loop: InferenceLoop?, bodyParts: BodyParts, lowTextureSuppressor: LowTextureSuppressor,
    useLowTexture: Boolean, aimPart: AimPart, confThreshold: Float, showFrameBitmap: Boolean, ltThreshold: Float,
    onUseLowTextureChange: (Boolean) -> Unit, onAimPartChange: (AimPart) -> Unit,
    onConfThresholdChange: (Float) -> Unit, onShowFrameBitmapChange: (Boolean) -> Unit, onLtThresholdChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("功能", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("控制算法: 自适应PID", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("瞄准部位", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AimPart.entries.forEach { part ->
                        SegmentButton(part.label, aimPart == part, { onAimPartChange(part) }, Modifier.weight(1f))
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("检测参数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                ParamSlider("置信度阈值", confThreshold, 0.1f..0.95f, onConfThresholdChange)
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("显示", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                GraySwitch("实时图像", showFrameBitmap, onShowFrameBitmapChange)
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("低纹理误检抑制", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                GraySwitch("启用低纹理抑制", useLowTexture, onUseLowTextureChange)
                Spacer(modifier = Modifier.height(8.dp))
                ParamSlider("抑制阈值", ltThreshold, 5f..50f, onLtThresholdChange)
            }
        }
    }
}

@Composable
fun SettingsScreen(context: Context, engineType: String, apidXKp: Float, apidXKi: Float, apidXKd: Float, apidYKp: Float, apidYKi: Float, apidYKd: Float, apidOutputLimit: Float, apidIntegralLimit: Float, apidDeadZone: Float, apidIntegralDeadzone: Float, apidIntegralGainThreshold: Float, apidIntegralGainRate: Float, btMoveLimit: Float, onEngineTypeChange: (String) -> Unit, onApidXKpChange: (Float) -> Unit, onApidXKiChange: (Float) -> Unit, onApidXKdChange: (Float) -> Unit, onApidYKpChange: (Float) -> Unit, onApidYKiChange: (Float) -> Unit, onApidYKdChange: (Float) -> Unit, onApidOutputLimitChange: (Float) -> Unit, onApidIntegralLimitChange: (Float) -> Unit, onApidDeadZoneChange: (Float) -> Unit, onApidIntegralDeadzoneChange: (Float) -> Unit, onApidIntegralGainThresholdChange: (Float) -> Unit, onApidIntegralGainRateChange: (Float) -> Unit, onBtMoveLimitChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) { Column(modifier = Modifier.padding(16.dp)) { Text("推理引擎", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(8.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { EngineChip("ncnn_cpu", "NCNN+CPU", engineType == "ncnn_cpu", onEngineTypeChange); EngineChip("ncnn", "NCNN+Vulkan", engineType == "ncnn", onEngineTypeChange) }; Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { EngineChip("litert_npu", "LiteRT+NPU", engineType == "litert_npu", onEngineTypeChange) } } }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("自适应PID参数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(8.dp))
                Text("X轴 PID", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(4.dp))
                ParamSlider("X Kp", apidXKp, 0.0f..2.0f, onApidXKpChange); ParamSlider("X Ki", apidXKi, 0.0f..1.0f, onApidXKiChange); ParamSlider("X Kd", apidXKd, 0.0f..1.0f, onApidXKdChange)
                Spacer(modifier = Modifier.height(4.dp)); Text("Y轴 PID", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(4.dp))
                ParamSlider("Y Kp", apidYKp, 0.0f..2.0f, onApidYKpChange); ParamSlider("Y Ki", apidYKi, 0.0f..1.0f, onApidYKiChange); ParamSlider("Y Kd", apidYKd, 0.0f..1.0f, onApidYKdChange)
                Spacer(modifier = Modifier.height(4.dp)); Text("限幅与死区", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(4.dp))
                ParamSlider("输出限幅", apidOutputLimit, 1.0f..127.0f, onApidOutputLimitChange); ParamSlider("积分限幅", apidIntegralLimit, 1.0f..500.0f, onApidIntegralLimitChange)
                ParamSlider("死区(px)", apidDeadZone, 0.0f..10.0f, onApidDeadZoneChange); ParamSlider("积分死区", apidIntegralDeadzone, 0.0f..10.0f, onApidIntegralDeadzoneChange)
                Spacer(modifier = Modifier.height(4.dp)); Text("自适应积分", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(4.dp))
                ParamSlider("积分阈值", apidIntegralGainThreshold, 1.0f..200.0f, onApidIntegralGainThresholdChange); ParamSlider("积分增益率", apidIntegralGainRate, 0.001f..0.1f, onApidIntegralGainRateChange)

            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("蓝牙HID参数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Spacer(modifier = Modifier.height(8.dp))
                ParamSlider("单步移动上限", btMoveLimit, 1.0f..127.0f, onBtMoveLimitChange)
            }
        }

    }
}

@Composable
fun MonitorToolButton(label: String, enabled: Boolean, isStop: Boolean = false, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(42.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isStop) StopRed else Color(0xFF555555), disabledContainerColor = SurfaceGray, disabledContentColor = Color(0xFF8A8A8A)), shape = RoundedCornerShape(8.dp)) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun EngineChip(value: String, label: String, selected: Boolean, onSelect: (String) -> Unit) {
    Surface(modifier = Modifier.clickable { onSelect(value) }, shape = RoundedCornerShape(6.dp), color = if (selected) Color(0xFF555555) else SurfaceGray) { Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, color = if (selected) Color.White else TextSecondary) }
}

@Composable
fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(6.dp), color = if (selected) Color(0xFF555555) else SurfaceGray) { Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text(label, fontSize = 12.sp, color = if (selected) Color.White else TextSecondary) } }
}

@Composable
fun GraySwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF555555), uncheckedTrackColor = SurfaceGray)) }
}

@Composable
fun ParamSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f)); Text(String.format("%.3f", value), fontSize = 11.sp, color = TextPrimary) }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun TechBadge(text: String) { Surface(shape = RoundedCornerShape(4.dp), color = SurfaceGray) { Text(text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = TextSecondary) } }

@Composable
fun StatusBadge(label: String, value: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (connected) StatusGreen else StatusRed)); Spacer(modifier = Modifier.width(4.dp)); Text("$label: $value", fontSize = 12.sp, color = TextSecondary) }
}
