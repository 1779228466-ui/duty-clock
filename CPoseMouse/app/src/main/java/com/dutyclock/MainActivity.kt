package com.dutyclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import com.dutyclock.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dutyclock.core.*
import com.dutyclock.ui.theme.DutyClockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.ImageFormat
import android.graphics.Color as AndroidColor
import java.util.*

// ======================== 主题系统 ========================
enum class AppTheme { DARK, LIGHT }

private val LightBg = Color(0xFFF8F9FA)
private val LightCard = Color(0xFFFFFFFF)
private val LightCard2 = Color(0xFFF0F2F5)
private val LightSurface = Color(0xFFE8EAED)
private val LightTextPrimary = Color(0xFF1A1A2E)
private val LightTextSecondary = Color(0xFF555570)
private val LightTextDim = Color(0xFF9090A0)
private val DarkBg = Color(0xFF0D0D14)
private val DarkCard = Color(0xFF1A1A2E)
private val DarkCard2 = Color(0xFF222244)
private val DarkSurface = Color(0xFF2A2A3E)
private val DarkTextWhite = Color(0xFFF5F5F5)
private val DarkTextGray = Color(0xFFA0A0B0)
private val DarkTextDim = Color(0xFF666680)

private val PrimaryOrange = Color(0xFFFF6B35)
private val PrimaryOrangeLight = Color(0xFFFF8C5A)
private val SecondaryPurple = Color(0xFF7B68EE)
private val TertiaryCyan = Color(0xFF6FEDD6)
private val SuccessGreen = Color(0xFF4CAF50)
private val WarningYellow = Color(0xFFFFC107)
private val ErrorRed = Color(0xFFFF5252)

// ======================== 骨骼绘制颜色 ========================
private val SkeletonLineColor = Color(0xFF00E5FF)
private val SkeletonPointColor = Color(0xFFFF6B35)
private val SkeletonPaintColor = AndroidColor.argb(200, 0, 229, 255)
private val PointPaintColor = AndroidColor.argb(255, 255, 107, 53)

// COCO骨骼连接对
private val SKELETON_PAIRS = listOf(
    5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10, // 上半身
    5 to 11, 6 to 12, 11 to 12, // 躯干
    11 to 13, 13 to 15, 12 to 14, 14 to 16 // 下半身
)

// ======================== MainActivity ========================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val alarmTriggered = intent?.getBooleanExtra("alarm_triggered", false) ?: false
        if (alarmTriggered) {
            DutyAlarmManager.getInstance().startRinging(applicationContext)
        }
        requestPermissionsIfNeeded()
        setContent {
            DutyClockTheme {
                DutyApp(initialAlarmTriggered = alarmTriggered)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requestPermissions(perms.toTypedArray(), 100)
    }
}

enum class AppScreen { HOME, ALARM_EDIT, MISSION_ACTIVE, MISSION_COMPLETE, SLEEP_SOUNDS }

// ======================== 主应用 ========================
@Composable
fun DutyApp(initialAlarmTriggered: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val alarmRepo = remember { AlarmRepository(context) }

    var theme by remember { mutableStateOf(AppTheme.LIGHT) }
    var currentScreen by remember { mutableStateOf(if (initialAlarmTriggered) AppScreen.MISSION_ACTIVE else AppScreen.HOME) }
    var currentAlarm by remember { mutableStateOf<Alarm?>(null) }

    // 返回拦截
    var showExitDialog by remember { mutableStateOf(false) }
    val backDispatcher = (context as? ComponentActivity)?.onBackPressedDispatcher
    DisposableEffect(backDispatcher) {
        val callback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    AppScreen.HOME -> showExitDialog = true
                    AppScreen.MISSION_ACTIVE -> showExitDialog = true
                    else -> currentScreen = AppScreen.HOME
                }
            }
        }
        backDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    // 退出确认对话框
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("确认退出", color = if (theme == AppTheme.DARK) DarkTextWhite else LightTextPrimary) },
            text = { Text("闹钟正在运行中，确定要退出吗？", color = if (theme == AppTheme.DARK) DarkTextGray else LightTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (context as? ComponentActivity)?.finish()
                }) { Text("退出", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("取消", color = PrimaryOrange)
                }
            },
            containerColor = if (theme == AppTheme.DARK) DarkCard else LightCard
        )
    }

    // 引擎
    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var engineReady by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("准备就绪") }
    var poses by remember { mutableStateOf<List<PoseResult>>(emptyList()) }

    // 深蹲状态
    var squatDetector by remember { mutableStateOf(SquatDetector()) }
    var squatCount by remember { mutableStateOf(0) }
    var squatState by remember { mutableStateOf(SquatDetector.SquatState.STANDING) }
    var currentAngle by remember { mutableStateOf(0f) }
    var fps by remember { mutableStateOf(0f) }
    var lastInferMs by remember { mutableStateOf(0.0) }
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var missionKey by remember { mutableStateOf(0) }

    // 其他任务状态
    var mathQuestion by remember { mutableStateOf<MathMission.Question?>(null) }
    var mathAnswer by remember { mutableStateOf("") }
    var mathSolved by remember { mutableStateOf(0) }
    var mathTotal by remember { mutableStateOf(3) }
    var mathWrong by remember { mutableStateOf(false) }

    var memorySequence by remember { mutableStateOf("") }
    var memoryInput by remember { mutableStateOf("") }
    var memoryShowing by remember { mutableStateOf(true) }
    var memoryCorrect by remember { mutableStateOf(0) }
    var memoryTotal by remember { mutableStateOf(3) }

    var shakeCount by remember { mutableStateOf(0) }
    var stepCount by remember { mutableStateOf(0) }
    var waveCount by remember { mutableStateOf(0) }

    var typingPhrase by remember { mutableStateOf("") }
    var typingInput by remember { mutableStateOf("") }
    var typingCorrect by remember { mutableStateOf(0) }
    var typingTotal by remember { mutableStateOf(2) }

    // 加载模型
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                isLoading = true; statusText = "加载姿态模型..."
                val eng = NcnnEngine(useVulkan = false, inputSize = 320)
                if (eng.loadModel(context)) { engine = eng; engineReady = true; statusText = "模型就绪" }
                else statusText = "模型加载失败"
            } catch (e: Exception) {
                statusText = "加载异常: ${e.message}"
                AppLog.e("DutyApp", "Model load failed", e)
            } finally { isLoading = false }
        }
    }

    // 推理循环 - 深蹲 + 挥手
    LaunchedEffect(Unit) {
        var frameCount = 0; var fpsTime = System.nanoTime()
        var prevWristY = 0f; var waveDirection = 0; var waveFrames = 0
        while (isActive) {
            if (currentScreen != AppScreen.MISSION_ACTIVE || !engineReady) { delay(100); continue }
            val alarm = currentAlarm ?: run { delay(100); continue }
            if (alarm.missionType != MissionType.SQUAT && alarm.missionType != MissionType.WAVE) { delay(100); continue }
            val det = engine ?: run { delay(100); continue }
            val bmp = displayBitmap
            if (bmp == null || bmp.isRecycled) { delay(33); continue }
            try {
                val t0 = System.nanoTime()
                val results = det.detect(bmp, 0.5f)
                val t1 = System.nanoTime()
                lastInferMs = (t1 - t0) / 1e6
                poses = results
                frameCount++; val now = System.nanoTime()
                if (now - fpsTime > 1e9) { fps = frameCount.toFloat(); frameCount = 0; fpsTime = now }
                if (results.isNotEmpty()) {
                    val best = results.maxByOrNull { it.confidence }
                    if (best != null) {
                        val kps = best.keypoints
                        if (alarm.missionType == MissionType.SQUAT) {
                            squatDetector.processFrame(kps)
                            squatCount = squatDetector.getCount()
                            squatState = squatDetector.getState()
                            val angles = squatDetector.getLastAngles()
                            if (angles.first > 0f || angles.second > 0f) currentAngle = (angles.first + angles.second) / 2f
                            if (squatDetector.isCompleted()) {
                                currentScreen = AppScreen.MISSION_COMPLETE
                                DutyAlarmManager.getInstance().stopRinging()
                            }
                        } else if (alarm.missionType == MissionType.WAVE && kps.size >= 10) {
                            // 挥手检测：手腕(9,10) Y 坐标上下摆动
                            val wristY = (kps[9].y + kps[10].y) / 2f
                            if (kps[9].conf > 0.3f && kps[10].conf > 0.3f && prevWristY > 0f) {
                                val delta = wristY - prevWristY
                                if (delta < -15f && waveDirection <= 0) { waveDirection = 1; waveFrames = 0 }
                                else if (delta > 15f && waveDirection >= 1) { waveDirection = 2; waveFrames = 0 }
                                else if (delta < -15f && waveDirection >= 2) {
                                    // 完成一次上下挥手
                                    waveCount++
                                    waveDirection = 0
                                    if (waveCount >= alarm.missionCount) {
                                        currentScreen = AppScreen.MISSION_COMPLETE
                                        DutyAlarmManager.getInstance().stopRinging()
                                    }
                                }
                                waveFrames++
                                if (waveFrames > 20) { waveDirection = 0; waveFrames = 0 }
                            }
                            prevWristY = wristY
                        }
                    }
                }
            } catch (e: Exception) { AppLog.e("DutyApp", "Inference error", e) }
            delay(33)
        }
    }

    // 主题颜色
    val bg = if (theme == AppTheme.DARK) DarkBg else LightBg
    val card = if (theme == AppTheme.DARK) DarkCard else LightCard
    val card2 = if (theme == AppTheme.DARK) DarkCard2 else LightCard2
    val surface = if (theme == AppTheme.DARK) DarkSurface else LightSurface
    val txtWhite = if (theme == AppTheme.DARK) DarkTextWhite else LightTextPrimary
    val txtGray = if (theme == AppTheme.DARK) DarkTextGray else LightTextSecondary
    val txtDim = if (theme == AppTheme.DARK) DarkTextDim else LightTextDim

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        when (currentScreen) {
            AppScreen.HOME -> HomeScreen(
                alarmRepo, isLoading, statusText, engineReady, theme,
                onToggleTheme = { theme = if (theme == AppTheme.DARK) AppTheme.LIGHT else AppTheme.DARK },
                onAddAlarm = { currentAlarm = Alarm(); currentScreen = AppScreen.ALARM_EDIT },
                onEditAlarm = { currentAlarm = it; currentScreen = AppScreen.ALARM_EDIT },
                onDeleteAlarm = { alarmRepo.deleteAlarm(it) },
                onSleepSounds = { currentScreen = AppScreen.SLEEP_SOUNDS },
                bg = bg, card = card, card2 = card2, surface = surface,
                txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim
            )
            AppScreen.ALARM_EDIT -> AlarmEditScreen(
                alarm = currentAlarm ?: Alarm(),
                onSave = { alarm -> alarmRepo.saveAlarm(alarm); DutyAlarmManager.getInstance().apply { setConfig(DutyAlarmManager.AlarmConfig(alarm.hour, alarm.minute, alarm.missionCount)); scheduleAlarm(context) }; currentScreen = AppScreen.HOME; Toast.makeText(context, "闹钟已保存", Toast.LENGTH_SHORT).show() },
                onDelete = { currentAlarm?.let { alarmRepo.deleteAlarm(it.id) }; currentScreen = AppScreen.HOME },
                onBack = { currentScreen = AppScreen.HOME },
                onTestMission = { alarm -> currentAlarm = alarm; startMission(alarm, context) { missionKey++; currentScreen = AppScreen.MISSION_ACTIVE } },
                bg = bg, card = card, card2 = card2, surface = surface,
                txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim
            )
            AppScreen.MISSION_ACTIVE -> {
                val alarm = currentAlarm ?: return@Box
                when (alarm.missionType) {
                    MissionType.SQUAT -> SquatMissionScreen(
                        lifecycleOwner, context, missionKey, cameraManager, { cameraManager = it },
                        engineReady, isLoading, squatCount, alarm.missionCount, squatState,
                        currentAngle, fps, lastInferMs, displayBitmap, poses,
                        { displayBitmap = it },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.WAVE -> SquatMissionScreen(
                        lifecycleOwner, context, missionKey, cameraManager, { cameraManager = it },
                        engineReady, isLoading, waveCount, alarm.missionCount, SquatDetector.SquatState.STANDING,
                        0f, fps, lastInferMs, displayBitmap, poses,
                        { displayBitmap = it },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.MATH -> MathMissionScreen(
                        mathQuestion ?: MathMission.Question("", 0), mathAnswer,
                        { mathAnswer = it }, mathSolved, mathTotal, mathWrong,
                        {
                            if (mathAnswer.toIntOrNull() == mathQuestion?.answer) { mathSolved++; mathWrong = false; mathAnswer = ""; if (mathSolved >= mathTotal) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } else mathQuestion = MathMission().generate(alarm.difficulty) }
                            else { mathWrong = true; mathAnswer = "" }
                        },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.MEMORY -> MemoryMissionScreen(
                        memorySequence, memoryInput, { memoryInput = it }, memoryShowing,
                        memoryCorrect, memoryTotal,
                        { if (memoryInput == memorySequence) { memoryCorrect++; memoryInput = ""; if (memoryCorrect >= memoryTotal) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } else { val seq = MemoryMission().generate(alarm.difficulty); memorySequence = seq.digits; memoryShowing = true } } else { memoryInput = ""; memoryShowing = true } },
                        { memoryShowing = false },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.SHAKE -> ShakeMissionScreen(
                        shakeCount, alarm.missionCount,
                        { shakeCount++; if (shakeCount >= alarm.missionCount) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.STEPS -> StepsMissionScreen(
                        stepCount, alarm.missionCount,
                        { stepCount++; if (stepCount >= alarm.missionCount) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.TYPING -> TypingMissionScreen(
                        typingPhrase, typingInput, { typingInput = it }, typingCorrect, typingTotal,
                        { if (typingInput.trim() == typingPhrase) { typingCorrect++; typingInput = ""; if (typingCorrect >= typingTotal) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } else typingPhrase = TypingMission().getRandomPhrase() } else typingInput = "" },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.PHOTO -> PhotoMissionScreen(
                        context, alarm.missionCount,
                        { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.QR_CODE -> QrMissionScreen(
                        alarm.missionCount,
                        { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE },
                        { stopMission { currentScreen = AppScreen.HOME } },
                        bg, card, surface, txtWhite, txtGray, txtDim
                    )
                }
            }
            AppScreen.MISSION_COMPLETE -> CompleteScreen(
                { stopMission(); currentScreen = AppScreen.HOME },
                bg, txtWhite, txtGray
            )
            AppScreen.SLEEP_SOUNDS -> SleepSoundsScreen(
                { currentScreen = AppScreen.HOME },
                bg, card, txtWhite, txtGray
            )
        }
    }
    DisposableEffect(Unit) { onDispose { cameraManager?.release(); engine?.release() } }
}

private fun stopMission(onComplete: () -> Unit = {}) { DutyAlarmManager.getInstance().stopRinging(); onComplete() }
private fun startMission(alarm: Alarm, context: Context, onStart: () -> Unit) {
    // 测试任务时不启动闹钟铃声，只进入任务页面
    onStart()
}

// ======================== 首页 ========================
@Composable
fun HomeScreen(
    alarmRepo: AlarmRepository, isLoading: Boolean, statusText: String, engineReady: Boolean,
    theme: AppTheme, onToggleTheme: () -> Unit,
    onAddAlarm: () -> Unit, onEditAlarm: (Alarm) -> Unit, onDeleteAlarm: (Long) -> Unit,
    onSleepSounds: () -> Unit,
    bg: Color, card: Color, card2: Color, surface: Color,
    txtWhite: Color, txtGray: Color, txtDim: Color
) {
    var alarms by remember { mutableStateOf(alarmRepo.getAllAlarms()) }
    LaunchedEffect(Unit) { while (true) { alarms = alarmRepo.getAllAlarms(); delay(2000) } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                Image(painter = painterResource(id = R.drawable.app_cover), contentDescription = "Cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x80000000), bg))))
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).statusBarsPadding(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("Duty Clock", style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DarkTextWhite, letterSpacing = 1.sp)); Text("用任务唤醒每一天", style = TextStyle(fontSize = 14.sp, color = DarkTextGray)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = onToggleTheme, modifier = Modifier.size(40.dp).background(card2, CircleShape)) { Icon(if (theme == AppTheme.DARK) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = WarningYellow, modifier = Modifier.size(20.dp)) }
                            IconButton(onClick = onSleepSounds, modifier = Modifier.size(40.dp).background(card2, CircleShape)) { Icon(Icons.Outlined.Nightlight, null, tint = TertiaryCyan, modifier = Modifier.size(20.dp)) }
                            IconButton(onClick = onAddAlarm, modifier = Modifier.size(40.dp).background(PrimaryOrange, CircleShape)) { Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card2.copy(alpha = 0.6f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isLoading) { CircularProgressIndicator(color = PrimaryOrange, modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(statusText, fontSize = 12.sp, color = DarkTextGray) }
                        else if (engineReady) { Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("AI 姿态引擎已就绪", fontSize = 12.sp, color = SuccessGreen) }
                        else { Icon(Icons.Filled.Warning, null, tint = WarningYellow, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(statusText, fontSize = 12.sp, color = WarningYellow) }
                    }
                }
            }
            if (alarms.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Alarm, null, tint = txtDim, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp)); Text("还没有闹钟", fontSize = 18.sp, color = txtGray, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp)); Text("点击右上角 + 添加你的第一个任务闹钟", fontSize = 13.sp, color = txtDim)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onAddAlarm, colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(24.dp)) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加闹钟") }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(alarms, key = { it.id }) { alarm ->
                        Card(Modifier.fillMaxWidth().clickable { onEditAlarm(alarm) }, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(String.format("%02d:%02d", alarm.hour, alarm.minute), style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, color = if (alarm.enabled) txtWhite else txtDim, fontFamily = FontFamily.Default))
                                    Text(if (alarm.hour < 12) "AM" else "PM", fontSize = 12.sp, color = if (alarm.enabled) PrimaryOrange else txtDim)
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(alarm.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (alarm.enabled) txtWhite else txtDim)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${alarm.missionType.emoji} ${alarm.missionType.displayName} · ${alarm.missionCount}次", fontSize = 13.sp, color = txtGray)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Repeat, null, tint = txtDim, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(2.dp))
                                        Text(alarm.repeatMode.label, fontSize = 12.sp, color = txtDim); Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Outlined.Speed, null, tint = txtDim, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(2.dp))
                                        Text(alarm.difficulty.label, fontSize = 12.sp, color = txtDim)
                                    }
                                }
                                Switch(checked = alarm.enabled, onCheckedChange = {
                                    val updated = alarm.copy(enabled = !alarm.enabled); alarmRepo.saveAlarm(updated); alarms = alarmRepo.getAllAlarms()
                                }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryOrange, uncheckedThumbColor = txtDim, uncheckedTrackColor = surface))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

// ======================== 编辑页 ========================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarm: Alarm, onSave: (Alarm) -> Unit, onDelete: () -> Unit, onBack: () -> Unit, onTestMission: (Alarm) -> Unit,
    bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color
) {
    var editAlarm by remember { mutableStateOf(alarm) }
    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = txtWhite); Text("返回", color = txtWhite) }
            Text("编辑闹钟", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = txtWhite)
            Row { TextButton(onClick = onDelete) { Text("删除", color = ErrorRed) }; TextButton(onClick = { onSave(editAlarm) }) { Text("保存", color = PrimaryOrange, fontWeight = FontWeight.Bold) } }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("设置时间", fontSize = 14.sp, color = txtGray); Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TimePickerColumn(editAlarm.hour, 0..23, { editAlarm = editAlarm.copy(hour = it) }, "时", card2, txtWhite, txtGray, txtDim)
                    Text(":", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange, modifier = Modifier.padding(horizontal = 8.dp))
                    TimePickerColumn(editAlarm.minute, 0..59, { editAlarm = editAlarm.copy(minute = it) }, "分", card2, txtWhite, txtGray, txtDim)
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Label, null, tint = PrimaryOrange, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                TextField(editAlarm.label, { editAlarm = editAlarm.copy(label = it) }, Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedTextColor = txtWhite, unfocusedTextColor = txtWhite, cursorColor = PrimaryOrange, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), placeholder = { Text("闹钟标签", color = txtDim) }, singleLine = true)
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("任务类型", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = txtWhite); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MissionType.entries.take(4).forEach { MissionChip(editAlarm.missionType == it, it, { editAlarm = editAlarm.copy(missionType = it) }) } }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MissionType.entries.drop(4).forEach { MissionChip(editAlarm.missionType == it, it, { editAlarm = editAlarm.copy(missionType = it) }) } }
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("任务次数", fontSize = 14.sp, color = txtWhite)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ val n = editAlarm.missionCount - getStepForType(editAlarm.missionType); if (n >= 1) editAlarm = editAlarm.copy(missionCount = n) }, Modifier.size(32.dp)) { Icon(Icons.Filled.Remove, null, tint = txtGray, modifier = Modifier.size(16.dp)) }
                        Text("${editAlarm.missionCount}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange, modifier = Modifier.padding(horizontal = 16.dp))
                        IconButton({ editAlarm = editAlarm.copy(missionCount = editAlarm.missionCount + getStepForType(editAlarm.missionType)) }, Modifier.size(32.dp)) { Icon(Icons.Filled.Add, null, tint = PrimaryOrange, modifier = Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(12.dp)); Text("难度等级", fontSize = 14.sp, color = txtWhite); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MissionDifficulty.entries.forEach { FilterChip(selected = editAlarm.difficulty == it, onClick = { editAlarm = editAlarm.copy(difficulty = it) }, label = { Text(it.label, fontSize = 13.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PrimaryOrange.copy(alpha = 0.3f), selectedLabelColor = PrimaryOrange, containerColor = surface, labelColor = txtGray), shape = RoundedCornerShape(8.dp)) }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("重复", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = txtWhite); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RepeatMode.ONCE, RepeatMode.DAILY, RepeatMode.WEEKDAYS).forEach { FilterChip(selected = editAlarm.repeatMode == it, onClick = { editAlarm = editAlarm.copy(repeatMode = it) }, label = { Text(it.label, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SecondaryPurple.copy(alpha = 0.3f), selectedLabelColor = SecondaryPurple, containerColor = surface, labelColor = txtGray), shape = RoundedCornerShape(8.dp)) }
                }
            }
        }
        Button({ onTestMission(editAlarm) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("立即测试任务", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(32.dp))
    }
}

private fun getStepForType(type: MissionType) = when (type) { MissionType.SQUAT -> 5; MissionType.SHAKE -> 10; MissionType.STEPS -> 10; else -> 1 }

@Composable
fun RowScope.MissionChip(selected: Boolean, type: MissionType, onClick: () -> Unit) {
    Surface(Modifier.weight(1f).clickable { onClick() }, shape = RoundedCornerShape(12.dp), color = if (selected) PrimaryOrange.copy(alpha = 0.2f) else DarkSurface, border = if (selected) BorderStroke(1.5.dp, PrimaryOrange) else null) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(type.emoji, fontSize = 22.sp); Spacer(Modifier.height(4.dp)); Text(type.displayName, fontSize = 11.sp, color = if (selected) PrimaryOrange else DarkTextGray) }
    }
}

@Composable
fun TimePickerColumn(value: Int, range: IntRange, onValueChange: (Int) -> Unit, label: String, card2: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton({ if (value < range.last) onValueChange(value + 1) }) { Icon(Icons.Filled.KeyboardArrowUp, null, tint = txtGray, modifier = Modifier.size(28.dp)) }
        Surface(shape = RoundedCornerShape(12.dp), color = card2) { Text(String.format("%02d", value), fontSize = 42.sp, fontWeight = FontWeight.Bold, color = txtWhite, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        IconButton({ if (value > range.first) onValueChange(value - 1) }) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = txtGray, modifier = Modifier.size(28.dp)) }
        Text(label, fontSize = 12.sp, color = txtDim)
    }
}

// ======================== 深蹲任务页(含骨骼绘制) ========================
@Composable
fun SquatMissionScreen(
    lifecycleOwner: LifecycleOwner, context: Context, key: Int, cameraManager: CameraManager?, onCameraReady: (CameraManager) -> Unit,
    engineReady: Boolean, isLoading: Boolean, squatCount: Int, targetSquats: Int, squatState: SquatDetector.SquatState,
    currentAngle: Float, fps: Float, lastInferMs: Double, displayBitmap: Bitmap?, poses: List<PoseResult>,
    onFrameAvailable: (Bitmap) -> Unit, onStop: () -> Unit,
    bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color
) {
    val previewView = remember(key) { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    LaunchedEffect(key) { if (cameraManager == null) { val cm = CameraManager(context, lifecycleOwner, previewView, onFrameAvailable); onCameraReady(cm); cm.start() } }

    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().background(card).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FitnessCenter, null, tint = PrimaryOrange, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text("深蹲任务", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = txtWhite); Text("完成 ${targetSquats} 次下蹲关闭闹钟", fontSize = 12.sp, color = txtGray) }
                TextButton(onClick = onStop) { Text("停止", color = ErrorRed) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (engineReady) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // 骨骼叠加 Canvas
                // CameraManager 输出固定 320x320，关键点坐标在 [0,320] 范围
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (poses.isEmpty()) return@Canvas
                    val best = poses.maxByOrNull { it.confidence } ?: return@Canvas
                    val kps = best.keypoints
                    if (kps.size < 17) return@Canvas

                    // 320x320 正方形等比缩放到画布，居中
                    val scale = minOf(size.width, size.height) / 320f
                    val offsetX = (size.width - 320f * scale) / 2f
                    val offsetY = (size.height - 320f * scale) / 2f

                    for ((a, b) in SKELETON_PAIRS) {
                        if (a >= kps.size || b >= kps.size) continue
                        val p1 = kps[a]; val p2 = kps[b]
                        if (p1.conf > 0.2f && p2.conf > 0.2f) {
                            drawLine(SkeletonLineColor,
                                Offset(p1.x * scale + offsetX, p1.y * scale + offsetY),
                                Offset(p2.x * scale + offsetX, p2.y * scale + offsetY),
                                strokeWidth = 3f)
                        }
                    }
                    for (kp in kps) {
                        if (kp.conf > 0.2f) {
                            drawCircle(SkeletonPointColor, 6f, Offset(kp.x * scale + offsetX, kp.y * scale + offsetY))
                            drawCircle(Color.White, 3f, Offset(kp.x * scale + offsetX, kp.y * scale + offsetY))
                        }
                    }
                }
            } else if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PrimaryOrange) }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("$squatCount", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange)
                    Text(" / $targetSquats", fontSize = 24.sp, color = txtGray, modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Text(when (squatState) { SquatDetector.SquatState.STANDING -> "站立"; SquatDetector.SquatState.SQUATTING -> "蹲下 ✓"; SquatDetector.SquatState.TRANSITIONING -> "过渡中" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (squatState == SquatDetector.SquatState.SQUATTING) SuccessGreen else txtWhite)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { (squatCount.toFloat() / targetSquats).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = PrimaryOrange, trackColor = surface)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { InfoChip("角度", "${currentAngle.toInt()}°", txtDim, txtWhite); InfoChip("FPS", "${fps.toInt()}", txtDim, txtWhite); InfoChip("推理", "${lastInferMs.toInt()}ms", txtDim, txtWhite) }
            }
        }
    }
}

// ======================== 数学任务 ========================
@Composable
fun MathMissionScreen(question: MathMission.Question, answer: String, onAnswerChange: (String) -> Unit, solved: Int, total: Int, wrong: Boolean, onSubmit: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    MissionPageWrapper("数学任务", "解出数学题才能关闭闹钟", "$solved / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LinearProgressIndicator({ solved.toFloat() / total }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = PrimaryOrange, trackColor = surface)
            Spacer(Modifier.height(48.dp))
            Text("第 ${solved + 1} 题", fontSize = 14.sp, color = txtGray); Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = card2), shape = RoundedCornerShape(24.dp)) { Text(question.expression, Modifier.padding(horizontal = 32.dp, vertical = 24.dp), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = txtWhite) }
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(answer, { if (it.length <= 8 && it.all { c -> c.isDigit() || c == '-' }) onAnswerChange(it) }, Modifier.fillMaxWidth(0.7f), textStyle = TextStyle(fontSize = 32.sp, textAlign = TextAlign.Center, color = txtWhite, fontWeight = FontWeight.Bold), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = if (wrong) ErrorRed else PrimaryOrange, unfocusedBorderColor = if (wrong) ErrorRed.copy(alpha = 0.5f) else surface, cursorColor = PrimaryOrange, focusedContainerColor = card, unfocusedContainerColor = card), singleLine = true, isError = wrong, supportingText = if (wrong) {{ Text("答案错误，请重试", color = ErrorRed) }} else null, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(32.dp))
            Button(onSubmit, Modifier.fillMaxWidth(0.6f).height(52.dp), enabled = answer.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(26.dp)) { Text("确认", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ======================== 记忆任务 ========================
@Composable
fun MemoryMissionScreen(sequence: String, input: String, onInputChange: (String) -> Unit, showing: Boolean, correct: Int, total: Int, onSubmit: () -> Unit, onShowDone: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    LaunchedEffect(showing) { if (showing && sequence.isNotEmpty()) { delay(3000); onShowDone() } }
    MissionPageWrapper("记忆任务", "记住并输入数字序列", "$correct / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LinearProgressIndicator({ correct.toFloat() / total }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = PrimaryOrange, trackColor = surface)
            Spacer(Modifier.height(48.dp)); Text("第 ${correct + 1} 组", fontSize = 14.sp, color = txtGray); Spacer(Modifier.height(16.dp))
            if (showing) {
                Card(colors = CardDefaults.cardColors(containerColor = card2), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(horizontal = 40.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(sequence, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange, letterSpacing = 12.sp); Spacer(Modifier.height(12.dp)); Text("记住这个序列...", fontSize = 14.sp, color = txtGray) } }
            } else {
                Text("请输入你记住的数字", fontSize = 16.sp, color = txtGray); Spacer(Modifier.height(16.dp))
                OutlinedTextField(input, { if (it.length <= sequence.length && it.all { c -> c.isDigit() }) onInputChange(it) }, Modifier.fillMaxWidth(0.7f), textStyle = TextStyle(fontSize = 32.sp, textAlign = TextAlign.Center, color = txtWhite, fontWeight = FontWeight.Bold, letterSpacing = 8.sp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryOrange, unfocusedBorderColor = surface, cursorColor = PrimaryOrange, focusedContainerColor = card, unfocusedContainerColor = card), singleLine = true, shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(24.dp))
                Button(onSubmit, Modifier.fillMaxWidth(0.5f).height(48.dp), enabled = input.length == sequence.length, colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(24.dp)) { Text("确认", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ======================== 摇晃任务(真实传感器) ========================
@Composable
fun ShakeMissionScreen(count: Int, total: Int, onShake: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
            private var lastTime = 0L
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                val now = System.currentTimeMillis()
                if (now - lastTime < 200) return
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val delta = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ)
                if (delta > 25f) onShake()
                lastX = x; lastY = y; lastZ = z; lastTime = now
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    MissionPageWrapper("摇晃任务", "用力摇晃手机！", "$count / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$count", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange)
            Text("/ $total 次", fontSize = 24.sp, color = txtGray); Spacer(Modifier.height(24.dp))
            LinearProgressIndicator({ (count.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)), color = PrimaryOrange, trackColor = surface)
            Spacer(Modifier.height(32.dp)); Text("用力摇晃手机！", fontSize = 18.sp, color = txtGray)
        }
    }
}

// ======================== 步数任务(真实传感器) ========================
@Composable
fun StepsMissionScreen(count: Int, total: Int, onStep: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val target = stepCounter ?: stepDetector
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) { event ?: return; onStep() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (target != null) {
            sensorManager.registerListener(listener, target, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            Toast.makeText(context, "设备不支持计步传感器", Toast.LENGTH_LONG).show()
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    MissionPageWrapper("步行任务", "走够指定步数", "$count / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$count", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = TertiaryCyan)
            Text("/ $total 步", fontSize = 24.sp, color = txtGray); Spacer(Modifier.height(24.dp))
            LinearProgressIndicator({ (count.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)), color = TertiaryCyan, trackColor = surface)
            Spacer(Modifier.height(32.dp)); Text("开始走路吧！", fontSize = 18.sp, color = txtGray)
        }
    }
}

// ======================== 打字任务 ========================
@Composable
fun TypingMissionScreen(phrase: String, input: String, onInputChange: (String) -> Unit, correct: Int, total: Int, onSubmit: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    MissionPageWrapper("打字任务", "准确输入显示的短语", "$correct / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LinearProgressIndicator({ correct.toFloat() / total }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = SecondaryPurple, trackColor = surface)
            Spacer(Modifier.height(40.dp))
            Card(colors = CardDefaults.cardColors(containerColor = card2), shape = RoundedCornerShape(20.dp)) { Text(phrase, Modifier.padding(24.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = txtWhite, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(input, onInputChange, Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 18.sp, color = txtWhite), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SecondaryPurple, unfocusedBorderColor = surface, cursorColor = SecondaryPurple, focusedContainerColor = card, unfocusedContainerColor = card), placeholder = { Text("在此输入...", color = txtDim) }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(16.dp))
            Button(onSubmit, Modifier.fillMaxWidth().height(48.dp), enabled = input.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple), shape = RoundedCornerShape(24.dp)) { Text("提交", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ======================== 拍照任务 ========================
@Composable
fun PhotoMissionScreen(context: Context, total: Int, onComplete: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    var count by remember { mutableStateOf(0) }
    MissionPageWrapper("拍照任务", "拍一张照片完成任务", "$count / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.CameraAlt, null, tint = PrimaryOrange, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("拍一张照片来关闭闹钟", fontSize = 18.sp, color = txtGray)
            Spacer(Modifier.height(8.dp))
            Text("完成 $count / $total 次", fontSize = 14.sp, color = txtDim)
            Spacer(Modifier.height(32.dp))
            Button({
                count++
                if (count >= total) onComplete()
            }, Modifier.fillMaxWidth(0.6f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(26.dp)) {
                Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("拍照", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ======================== 扫码任务 ========================
@Composable
fun QrMissionScreen(total: Int, onComplete: () -> Unit, onStop: () -> Unit, bg: Color, card: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color) {
    var count by remember { mutableStateOf(0) }
    MissionPageWrapper("扫码任务", "扫描一个二维码完成任务", "$count / $total", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.QrCodeScanner, null, tint = SecondaryPurple, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("扫描二维码来关闭闹钟", fontSize = 18.sp, color = txtGray)
            Spacer(Modifier.height(8.dp))
            Text("完成 $count / $total 次", fontSize = 14.sp, color = txtDim)
            Spacer(Modifier.height(32.dp))
            Button({
                count++
                if (count >= total) onComplete()
            }, Modifier.fillMaxWidth(0.6f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple), shape = RoundedCornerShape(26.dp)) {
                Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("扫码", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ======================== 通用包装 ========================
@Composable
fun MissionPageWrapper(title: String, subtitle: String, progress: String, onStop: () -> Unit, bg: Color, card: Color, txtWhite: Color, txtGray: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().background(card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = txtWhite); Text(subtitle, fontSize = 12.sp, color = txtGray) }
            Text(progress, fontSize = 14.sp, color = PrimaryOrange, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp))
            TextButton(onClick = onStop) { Text("停止", color = ErrorRed) }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

// ======================== 完成页 ========================
@Composable
fun CompleteScreen(onBack: () -> Unit, bg: Color, txtWhite: Color, txtGray: Color) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1A0A2E), Color(0xFF16213E), bg))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(100.dp).clip(CircleShape).background(SuccessGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(56.dp)) }
            Spacer(Modifier.height(24.dp)); Text("任务完成！", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = txtWhite)
            Spacer(Modifier.height(8.dp)); Text("闹钟已关闭，你成功了", fontSize = 16.sp, color = txtGray)
            Spacer(Modifier.height(48.dp))
            Button(onBack, Modifier.fillMaxWidth(0.6f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(26.dp)) { Text("返回首页", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ======================== 助眠声音 ========================
@Composable
fun SleepSoundsScreen(onBack: () -> Unit, bg: Color, card: Color, txtWhite: Color, txtGray: Color) {
    val sounds = listOf(Triple("雨声", "🌧️", TertiaryCyan), Triple("海浪", "🌊", TertiaryCyan), Triple("森林", "🌲", SuccessGreen), Triple("白噪音", "📻", txtGray), Triple("篝火", "🔥", PrimaryOrange), Triple("心跳", "💓", ErrorRed))
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = txtWhite); Text("返回", color = txtWhite) }; Spacer(Modifier.width(8.dp)); Text("助眠声音", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = txtWhite) }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sounds) { (name, emoji, color) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(emoji, fontSize = 32.sp); Spacer(Modifier.width(12.dp)); Text(name, fontSize = 16.sp, color = txtWhite, modifier = Modifier.weight(1f)); Icon(Icons.Filled.PlayArrow, null, tint = color, modifier = Modifier.size(28.dp)) } }
            }
        }
    }
}

@Composable
fun InfoChip(label: String, value: String, txtDim: Color, txtWhite: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontSize = 11.sp, color = txtDim); Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = txtWhite) }
}
