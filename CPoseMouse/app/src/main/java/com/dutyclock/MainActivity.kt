package com.dutyclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.RingtoneManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.net.Uri
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Size
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
import com.dutyclock.core.Alarm
import com.dutyclock.core.MissionType
import com.dutyclock.core.RepeatMode
import com.dutyclock.core.SquatDetector
import com.dutyclock.core.TieShanKaoDetector
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import java.util.*
import kotlin.random.Random

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

// ======================== 骨骼绘制 — 白色卡通风格 ========================
private val CartoonBoneColor = Color(0xFFFFFFFF)          // 骨骼线白色
private val CartoonBoneOutline = Color(0x33000000)        // 骨骼线暗色描边
private val CartoonPointColor = Color(0xFFFFFFEE)         // 关键点暖白
private val CartoonPointOutline = Color(0x44000000)       // 关键点暗色外圈
private val CartoonHandFootColor = Color(0xFFFFF176)      // 手脚高亮黄色
private val CartoonHeadColor = Color(0xFFFFCC80)          // 头部橙色
// 骨骼指引色（半透明黄）
private val GuidanceYellow = Color(0x99FFD600)              // 动作指引线（黄色）
private val GuidancePointYellow = Color(0xBBFFEB3B)         // 动作指引点（黄色）

// COCO骨骼连接对（完整17点连接）
private val SKELETON_PAIRS = listOf(
    0 to 1, 0 to 2, 1 to 3, 2 to 4,  // 头部
    5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10, // 上半身+手臂
    5 to 11, 6 to 12, 11 to 12, // 躯干
    11 to 13, 13 to 15, 12 to 14, 14 to 16 // 下半身+腿
)
// 极端点索引（需绘制三角标记）
private val HAND_INDICES = setOf(9, 10)   // 手腕
private val FOOT_INDICES = setOf(15, 16)  // 脚踝
private val HEAD_INDICES = setOf(0, 1, 2, 3, 4)

// ======================== MainActivity ========================
class MainActivity : ComponentActivity() {

    // 用于 Compose 层感知闹钟触发的响应式状态
    private val _alarmTriggerEvent = androidx.compose.runtime.mutableIntStateOf(0)
    private val _skipDialog = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        processAlarmIntent(intent)
        requestPermissionsIfNeeded()
        setContent {
            DutyClockTheme {
                DutyApp(
                    initialAlarmTriggered = _alarmTriggerEvent.intValue > 0,
                    alarmTriggerVersion = _alarmTriggerEvent.intValue,
                    skipAlarmDialog = _skipDialog.value
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { processAlarmIntent(it) }
    }

    private fun processAlarmIntent(intent: Intent?) {
        val triggered = intent?.getBooleanExtra("alarm_triggered", false) ?: false
        if (triggered) {
            _skipDialog.value = intent?.getBooleanExtra("start_mission", false) ?: false
            DutyAlarmManager.getInstance().startRinging(applicationContext)
            _alarmTriggerEvent.intValue++
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

enum class AppScreen { HOME, ALARM_EDIT, MISSION_ACTIVE, MISSION_COMPLETE, SLEEP_SOUNDS, SLOT_MACHINE, ALARM_DIALOG }

// ======================== 主应用 ========================
@Composable
fun DutyApp(initialAlarmTriggered: Boolean = false, alarmTriggerVersion: Int = 0, skipAlarmDialog: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val alarmRepo = remember { AlarmRepository(context) }

    var theme by remember { mutableStateOf(AppTheme.LIGHT) }
    var currentScreen by remember { mutableStateOf(
        if (initialAlarmTriggered) {
            if (skipAlarmDialog) AppScreen.MISSION_ACTIVE else AppScreen.ALARM_DIALOG
        } else AppScreen.HOME
    )}
    var currentAlarm by remember { mutableStateOf<Alarm?>(null) }

    // 前台运行时闹钟再次触发（通过 onNewIntent 驱动 alarmTriggerVersion++）
    // skipAlarmDialog 由 MainActivity.processAlarmIntent 设置，用后即弃
    var pendingSkip by remember { mutableStateOf(skipAlarmDialog) }
    LaunchedEffect(alarmTriggerVersion) {
        if (alarmTriggerVersion > 0) {
            val doSkip = pendingSkip
            currentScreen = if (doSkip) AppScreen.MISSION_ACTIVE else AppScreen.ALARM_DIALOG
            pendingSkip = false
        }
    }

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
    var pushUpCount by remember { mutableStateOf(0) }
    var waveCount by remember { mutableStateOf(0) }
    var shakeCount by remember { mutableStateOf(0) }
    var tieshankoCount by remember { mutableStateOf(0) }
    var tieshankoState by remember { mutableStateOf(TieShanKaoDetector.LeanState.CENTER) }
    var tieshankoOffsetX by remember { mutableStateOf(0f) }
    var tieshankoDetector by remember { mutableStateOf(TieShanKaoDetector()) }

    // 老虎机状态
    var slotMode by remember { mutableStateOf("") } // "action" or "ringtone"
    var slotResult by remember { mutableStateOf<MissionType?>(null) }
    var slotRingtoneName by remember { mutableStateOf("") }

    // 数字排序任务状态
    var numberTiles by remember { mutableStateOf(listOf<Int>()) }
    var nextNumber by remember { mutableStateOf(1) }

    // 反应测试任务状态
    var reactionState by remember { mutableStateOf(0) }  // 0=红屏等待，1=绿屏可点击
    var reactionCount by remember { mutableStateOf(0) }
    var reactionTooEarly by remember { mutableStateOf(false) }

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

    // 推理循环 - 深蹲 + 挥手 + 俯卧撑
    LaunchedEffect(Unit) {
        var frameCount = 0; var fpsTime = System.nanoTime()
        var prevWristY = 0f; var waveDirection = 0; var waveFrames = 0
        var prevPushUpY = 0f; var pushUpPhase = 0; var pushUpFrames = 0
        while (isActive) {
            if (currentScreen != AppScreen.MISSION_ACTIVE || !engineReady) { delay(100); continue }
            val alarm = currentAlarm ?: run { delay(100); continue }
            val isPose = alarm.missionType in listOf(MissionType.SQUAT, MissionType.WAVE, MissionType.PUSH_UP, MissionType.TIESHANKO)
            if (!isPose) { delay(100); continue }
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
                            // 挥手：手腕(9,10) Y坐标上下摆动，放宽阈值，增加超时
                            val wristY = (kps[9].y + kps[10].y) / 2f
                            if (kps[9].conf > 0.25f && kps[10].conf > 0.25f && prevWristY > 0f) {
                                val delta = wristY - prevWristY
                                // 上移（挥手向上，坐标变小）
                                if (delta < -10f && waveDirection <= 0) { waveDirection = 1; waveFrames = 0 }
                                // 下移（挥手向下，坐标变大）
                                else if (delta > 10f && waveDirection >= 1) { waveDirection = 2; waveFrames = 0 }
                                // 再次上移 = 完成一次上下挥手
                                else if (delta < -10f && waveDirection >= 2) {
                                    waveCount++
                                    waveDirection = 0
                                    if (waveCount >= alarm.missionCount) {
                                        currentScreen = AppScreen.MISSION_COMPLETE
                                        DutyAlarmManager.getInstance().stopRinging()
                                    }
                                }
                                waveFrames++
                                // 30帧(~1秒)内无有效动作则重置
                                if (waveFrames > 30) { waveDirection = 0; waveFrames = 0 }
                            }
                            prevWristY = wristY
                        } else if (alarm.missionType == MissionType.TIESHANKO && kps.size >= 13) {
                            // 铁山靠：肩相对髋的水平偏移
                            tieshankoDetector.processFrame(kps)
                            tieshankoCount = tieshankoDetector.getCount()
                            tieshankoState = tieshankoDetector.getState()
                            tieshankoOffsetX = tieshankoDetector.getOffsetX()
                            if (tieshankoDetector.isCompleted()) {
                                currentScreen = AppScreen.MISSION_COMPLETE
                                DutyAlarmManager.getInstance().stopRinging()
                            }
                        } else if (alarm.missionType == MissionType.PUSH_UP && kps.size >= 14) {
                            // 俯卧撑：肩(5,6) Y坐标上下起伏，Y变小=下降，Y变大=推起
                            val shoulderY = (kps[5].y + kps[6].y) / 2f
                            if (kps[5].conf > 0.25f && kps[6].conf > 0.25f && prevPushUpY > 0f) {
                                val delta = shoulderY - prevPushUpY
                                if (delta > 8f && pushUpPhase <= 0) { pushUpPhase = 1; pushUpFrames = 0 }
                                else if (delta < -8f && pushUpPhase >= 1) {
                                    pushUpCount++; pushUpPhase = 0; pushUpFrames = 0
                                    if (pushUpCount >= alarm.missionCount) { currentScreen = AppScreen.MISSION_COMPLETE; DutyAlarmManager.getInstance().stopRinging() }
                                }
                                pushUpFrames++; if (pushUpFrames > 30) { pushUpPhase = 0; pushUpFrames = 0 }
                            }
                            prevPushUpY = shoulderY
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
            AppScreen.ALARM_EDIT -> {
                AlarmEditScreen(
                    alarm = currentAlarm ?: Alarm(),
                    onSave = { alarm -> alarmRepo.saveAlarm(alarm); DutyAlarmManager.getInstance().apply { setConfig(DutyAlarmManager.AlarmConfig(alarm.hour, alarm.minute, alarm.missionCount)); scheduleAlarm(context) }; currentScreen = AppScreen.HOME; Toast.makeText(context, "闹钟已保存", Toast.LENGTH_SHORT).show() },
                    onDelete = { currentAlarm?.let { alarmRepo.deleteAlarm(it.id) }; currentScreen = AppScreen.HOME },
                    onBack = { currentScreen = AppScreen.HOME },
                    onTestMission = { alarm ->
                        cameraManager?.stop(); cameraManager?.release(); cameraManager = null; displayBitmap = null
                        currentAlarm = alarm
                        when (alarm.missionType) {
                            MissionType.NUMBER_ORDER -> { numberTiles = NumberOrderGenerator().nextRound(); nextNumber = 1 }
                            MissionType.REACTION -> { reactionState = 0; reactionCount = 0; reactionTooEarly = false }
                            MissionType.TIESHANKO -> { tieshankoDetector = TieShanKaoDetector(targetCount = alarm.missionCount); tieshankoCount = 0; tieshankoState = TieShanKaoDetector.LeanState.CENTER }
                            MissionType.RANDOM_ACTION -> { slotMode = "action"; currentScreen = AppScreen.SLOT_MACHINE; return@AlarmEditScreen }
                            else -> {}
                        }
                        startMission(alarm, context) { missionKey++; currentScreen = AppScreen.MISSION_ACTIVE }
                    },
                    onOpenSlotRingtone = { _ -> slotMode = "ringtone"; currentScreen = AppScreen.SLOT_MACHINE },
                    bg = bg, card = card, card2 = card2, surface = surface,
                    txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim
                )
            }
            AppScreen.MISSION_ACTIVE -> {
                val alarm = currentAlarm ?: return@Box
                when (alarm.missionType) {
                    MissionType.SQUAT -> SquatMissionScreen(
                        lifecycleOwner, context, missionKey, cameraManager, { cameraManager = it },
                        engineReady, isLoading, squatCount, alarm.missionCount, squatState,
                        currentAngle, fps, lastInferMs, displayBitmap, poses,
                        { displayBitmap = it },
                            { cameraManager?.stop(); cameraManager?.release(); cameraManager = null; displayBitmap = null; poses = emptyList(); stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.PUSH_UP -> SquatMissionScreen(
                        lifecycleOwner, context, missionKey, cameraManager, { cameraManager = it },
                        engineReady, isLoading, pushUpCount, alarm.missionCount, SquatDetector.SquatState.STANDING,
                        0f, fps, lastInferMs, displayBitmap, poses,
                        { displayBitmap = it },
                        { cameraManager?.stop(); cameraManager?.release(); cameraManager = null; displayBitmap = null; poses = emptyList(); stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim,
                        missionTitle = "俯卧撑任务", missionSubtitle = "完成 ${alarm.missionCount} 次俯卧撑关闭闹钟",
                        missionType = MissionType.PUSH_UP
                    )
                    MissionType.WAVE -> SquatMissionScreen(
                        lifecycleOwner, context, missionKey, cameraManager, { cameraManager = it },
                        engineReady, isLoading, waveCount, alarm.missionCount, SquatDetector.SquatState.STANDING,
                        0f, fps, lastInferMs, displayBitmap, poses,
                        { displayBitmap = it },
                        { cameraManager?.stop(); cameraManager?.release(); cameraManager = null; displayBitmap = null; poses = emptyList(); stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim,
                        missionTitle = "挥手任务", missionSubtitle = "完成 ${alarm.missionCount} 次挥手关闭闹钟",
                        missionType = MissionType.WAVE
                    )
                    MissionType.SHAKE -> ShakeMissionScreen(
                        shakeCount, alarm.missionCount,
                        { shakeCount++; if (shakeCount >= alarm.missionCount) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } },
                        { stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg, card, surface, txtWhite, txtGray, txtDim
                    )
                    MissionType.NUMBER_ORDER -> NumberOrderScreen(
                        tiles = numberTiles, nextNumber = nextNumber,
                        onTileClick = { num ->
                            if (num == nextNumber) { nextNumber++; if (nextNumber > 6) { DutyAlarmManager.getInstance().stopRinging(context); currentScreen = AppScreen.MISSION_COMPLETE } }
                        },
                        onStop = { stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg = bg, card = card, txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim
                    )
                    MissionType.REACTION -> ReactionScreen(
                        state = reactionState, count = reactionCount, tooEarly = reactionTooEarly,
                        onTap = {
                            if (reactionState == 1) { reactionCount++; reactionState = 0; reactionTooEarly = false; if (reactionCount >= 5) { DutyAlarmManager.getInstance().stopRinging(); currentScreen = AppScreen.MISSION_COMPLETE } }
                            else if (reactionState == 0) { reactionTooEarly = true }
                        },
                        onStateChange = { reactionState = it; reactionTooEarly = false },
                        onStop = { stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg = bg, txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim
                    )
                    MissionType.TIESHANKO -> SquatMissionScreen(
                        lifecycleOwner, context, missionKey, cameraManager, { cameraManager = it },
                        engineReady, isLoading, tieshankoCount, alarm.missionCount, if (tieshankoState != TieShanKaoDetector.LeanState.CENTER) SquatDetector.SquatState.SQUATTING else SquatDetector.SquatState.STANDING,
                        tieshankoOffsetX, fps, lastInferMs, displayBitmap, poses,
                        { displayBitmap = it },
                        { cameraManager?.stop(); cameraManager?.release(); cameraManager = null; displayBitmap = null; poses = emptyList(); stopMission(context) { currentScreen = AppScreen.HOME } },
                        bg, card, card2, surface, txtWhite, txtGray, txtDim,
                        missionTitle = "铁山靠任务", missionSubtitle = "侧身撞击 ${alarm.missionCount} 次关闭闹钟",
                        missionType = MissionType.TIESHANKO,
                        stateText = when (tieshankoState) {
                            TieShanKaoDetector.LeanState.CENTER -> "居中准备"
                            TieShanKaoDetector.LeanState.LEANING_LEFT -> "左靠 ✓"
                            TieShanKaoDetector.LeanState.LEANING_RIGHT -> "右靠 ✓"
                            TieShanKaoDetector.LeanState.RETURNING -> "回到中央"
                        },
                        infoLabel = "偏移量", infoValue = "${tieshankoOffsetX.toInt()}"
                    )
                    MissionType.RANDOM_ACTION -> {
                        cameraManager?.stop(); cameraManager?.release(); cameraManager = null; displayBitmap = null; poses = emptyList()
                        slotMode = "action"; currentScreen = AppScreen.SLOT_MACHINE
                    }
                }
            }
            AppScreen.SLOT_MACHINE -> SlotMachineScreen(
                mode = slotMode,
                onActionResult = { selectedType ->
                    slotResult = selectedType
                    if (currentAlarm != null) {
                        val updated = currentAlarm!!.copy(missionType = selectedType)
                        currentAlarm = updated
                        when (selectedType) {
                            MissionType.NUMBER_ORDER -> { numberTiles = NumberOrderGenerator().nextRound(); nextNumber = 1 }
                            MissionType.REACTION -> { reactionState = 0; reactionCount = 0; reactionTooEarly = false }
                            MissionType.TIESHANKO -> { tieshankoDetector = TieShanKaoDetector(targetCount = updated.missionCount); tieshankoCount = 0; tieshankoState = TieShanKaoDetector.LeanState.CENTER }
                            else -> {}
                        }
                        startMission(updated, context) { missionKey++; currentScreen = AppScreen.MISSION_ACTIVE }
                    }
                },
                onRingtoneResult = { name ->
                    slotRingtoneName = name
                    if (currentAlarm != null) {
                        currentAlarm = currentAlarm!!.copy(ringtoneName = name)
                    }
                    currentScreen = AppScreen.ALARM_EDIT
                },
                onBack = { if (slotMode == "ringtone") currentScreen = AppScreen.ALARM_EDIT else currentScreen = AppScreen.HOME },
                bg = bg, card = card, card2 = card2, surface = surface,
                txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim, txtAccent = PrimaryOrange
            )
            AppScreen.ALARM_DIALOG -> AlarmDialogScreen(
                alarmRepo = alarmRepo,
                onStartMission = { alarm ->
                    currentAlarm = alarm
                    when (alarm.missionType) {
                        MissionType.NUMBER_ORDER -> { numberTiles = NumberOrderGenerator().nextRound(); nextNumber = 1 }
                        MissionType.REACTION -> { reactionState = 0; reactionCount = 0; reactionTooEarly = false }
                        MissionType.TIESHANKO -> { tieshankoDetector = TieShanKaoDetector(targetCount = alarm.missionCount); tieshankoCount = 0; tieshankoState = TieShanKaoDetector.LeanState.CENTER }
                        MissionType.RANDOM_ACTION -> { slotMode = "action"; currentScreen = AppScreen.SLOT_MACHINE; return@AlarmDialogScreen }
                        else -> {}
                    }
                    startMission(alarm, context) { missionKey++; currentScreen = AppScreen.MISSION_ACTIVE }
                },
                onDismiss = { stopMission(context); currentScreen = AppScreen.HOME },
                bg = bg, card = card, txtWhite = txtWhite, txtGray = txtGray, txtDim = txtDim
            )
            AppScreen.MISSION_COMPLETE -> CompleteScreen(
                { stopMission(context); currentScreen = AppScreen.HOME },
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

private fun stopMission(context: Context, onComplete: () -> Unit = {}) { DutyAlarmManager.getInstance().stopRinging(context); onComplete() }
private fun startMission(alarm: Alarm, context: Context, onStart: () -> Unit) {
    // 测试任务时不启动闹钟铃声，只进入任务页面
    onStart()
}

// ======================== 闹钟响铃提示框 ========================
@Composable
fun AlarmDialogScreen(
    alarmRepo: AlarmRepository,
    onStartMission: (Alarm) -> Unit,
    onDismiss: () -> Unit,
    bg: Color, card: Color, txtWhite: Color, txtGray: Color, txtDim: Color
) {
    val context = LocalContext.current
    // 匹配当前时间最近的已启用闹钟
    var matchedAlarm by remember { mutableStateOf<Alarm?>(null) }
    LaunchedEffect(Unit) {
        val nowCal = java.util.Calendar.getInstance()
        val curH = nowCal.get(java.util.Calendar.HOUR_OF_DAY)
        val curM = nowCal.get(java.util.Calendar.MINUTE)
        val alarms = alarmRepo.getAllAlarms().filter { it.enabled }
        // 优先完全匹配小时+分钟，否则取最相近的
        matchedAlarm = alarms.firstOrNull { it.hour == curH && it.minute == curM }
            ?: alarms.minByOrNull { kotlin.math.abs(it.hour * 60 + it.minute - curH * 60 - curM) }
    }

    val alarm = matchedAlarm

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xCC000000)) // 半透明黑色遮罩
            .clickable(enabled = false) {}, // 阻止点击穿透
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp).widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 闹钟图标
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(PrimaryOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Alarm, null, tint = PrimaryOrange, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(16.dp))

                Text("闹钟响起", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = txtWhite)
                Spacer(Modifier.height(8.dp))

                if (alarm != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            String.format("%02d:%02d", alarm.hour, alarm.minute),
                            fontSize = 36.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange
                        )
                        Text(
                            alarm.label,
                            fontSize = 14.sp, color = txtGray
                        )
                        Text(
                            "${alarm.missionType.emoji} ${alarm.missionType.displayName} × ${alarm.missionCount}次",
                            fontSize = 13.sp, color = txtDim
                        )
                    }
                } else {
                    Text("未找到匹配闹钟", fontSize = 14.sp, color = txtDim)
                }
                Spacer(Modifier.height(24.dp))

                // 开始做动作按钮
                Button(
                    onClick = {
                        if (alarm != null) {
                            onStartMission(alarm)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    Icon(Icons.Filled.FitnessCenter, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始做动作", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(Modifier.height(10.dp))

                // 关闭闹钟按钮
                TextButton(onClick = onDismiss) {
                    Text("关闭闹钟", fontSize = 14.sp, color = txtDim)
                }
            }
        }
    }

    // 拦截返回键
    DisposableEffect(Unit) {
        val callback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* 不允许返回 */ }
        }
        (context as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
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

    // 文件导入
    val context = LocalContext.current
    var importMsg by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val reader = context.contentResolver.openInputStream(uri)?.bufferedReader() ?: return@rememberLauncherForActivityResult
                var count = 0
                reader.useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
                        // 格式: HH:MM,标签,动作类型,次数,重复模式[,重复日(可选)]
                        val parts = trimmed.split(",").map { it.trim() }
                        if (parts.size >= 5) {
                            val timeParts = parts[0].split(":")
                            if (timeParts.size == 2) {
                                val h = timeParts[0].toIntOrNull() ?: return@forEach
                                val m = timeParts[1].toIntOrNull() ?: return@forEach
                                val lbl = parts[1]
                                val mt = MissionType.entries.firstOrNull { it.displayName == parts[2] || it.name.equals(parts[2], ignoreCase = true) } ?: MissionType.SQUAT
                                val mc = parts[3].toIntOrNull() ?: 10
                                val rm = com.dutyclock.core.RepeatMode.entries.firstOrNull { it.label == parts[4] || it.name.equals(parts[4], ignoreCase = true) } ?: com.dutyclock.core.RepeatMode.ONCE
                                val rd = if (parts.size >= 6) parts[5].split(";").mapNotNull { it.trim().toIntOrNull() }.toSet() else emptySet()
                                alarmRepo.saveAlarm(Alarm(
                                    hour = h.coerceIn(0, 23), minute = m.coerceIn(0, 59),
                                    label = lbl, missionType = mt, missionCount = mc,
                                    repeatMode = rm, repeatDays = rd
                                ))
                                count++
                            }
                        }
                    }
                }
                alarms = alarmRepo.getAllAlarms()
                importMsg = "成功导入 $count 个闹钟"
            } catch (e: Exception) {
                importMsg = "导入失败：${e.message}"
            }
        }
    }

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
                            IconButton(onClick = { filePicker.launch(arrayOf("text/*", "*/*")) }, modifier = Modifier.size(40.dp).background(card2, CircleShape)) { Icon(Icons.Filled.FileOpen, null, tint = SecondaryPurple, modifier = Modifier.size(20.dp)) }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onAddAlarm, colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(24.dp)) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加闹钟") }
                            OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "*/*")) }, shape = RoundedCornerShape(24.dp)) { Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("从文件导入") }
                        }
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
                                        Text(alarm.repeatMode.label, fontSize = 12.sp, color = txtDim)
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
        // 导入结果提示
        if (importMsg.isNotEmpty()) {
            LaunchedEffect(importMsg) { delay(2500); importMsg = "" }
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(shape = RoundedCornerShape(12.dp), color = SuccessGreen.copy(alpha = 0.9f)) {
                    Text(importMsg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ======================== 编辑页(Slider时间选择 + 铃声双选项) ========================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarm: Alarm, onSave: (Alarm) -> Unit, onDelete: () -> Unit, onBack: () -> Unit, onTestMission: (Alarm) -> Unit,
    onOpenSlotRingtone: ((Alarm) -> Unit)? = null,
    bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color
) {
    var editAlarm by remember { mutableStateOf(alarm) }
    val context = LocalContext.current

    // 系统铃声选择器（使用 ACTION_RINGTONE_PICKER）
    val ringtonePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(context, uri)
                val name = ringtone?.getTitle(context) ?: "自定义铃声"
                editAlarm = editAlarm.copy(ringtoneName = name, ringtoneUri = uri.toString())
            }
        }
    }

    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = txtWhite); Text("返回", color = txtWhite) }
            Text("编辑闹钟", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = txtWhite)
            Row { TextButton(onClick = onDelete) { Text("删除", color = ErrorRed) }; TextButton(onClick = { onSave(editAlarm) }) { Text("保存", color = PrimaryOrange, fontWeight = FontWeight.Bold) } }
        }
        // Slider 时间选择器
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("设置时间", fontSize = 14.sp, color = txtGray)
                Spacer(Modifier.height(12.dp))
                // 居中时间显示
                Text(
                    String.format("%02d:%02d", editAlarm.hour, editAlarm.minute),
                    fontSize = 52.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                // 小时滑块
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("时", fontSize = 13.sp, color = txtGray, modifier = Modifier.width(24.dp))
                    Slider(
                        value = editAlarm.hour.toFloat(),
                        onValueChange = { editAlarm = editAlarm.copy(hour = it.toInt()) },
                        valueRange = 0f..23f,
                        steps = 22,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryOrange, activeTrackColor = PrimaryOrange,
                            inactiveTrackColor = surface, activeTickColor = txtDim
                        )
                    )
                    Text("23", fontSize = 13.sp, color = txtDim, modifier = Modifier.width(24.dp))
                }
                // 分钟滑块
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("分", fontSize = 13.sp, color = txtGray, modifier = Modifier.width(24.dp))
                    Slider(
                        value = editAlarm.minute.toFloat(),
                        onValueChange = { editAlarm = editAlarm.copy(minute = it.toInt()) },
                        valueRange = 0f..59f,
                        steps = 59,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryOrange, activeTrackColor = PrimaryOrange,
                            inactiveTrackColor = surface, activeTickColor = txtDim
                        )
                    )
                    Text("59", fontSize = 13.sp, color = txtDim, modifier = Modifier.width(24.dp))
                }
            }
        }
        // 标签
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Label, null, tint = PrimaryOrange, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                TextField(editAlarm.label, { editAlarm = editAlarm.copy(label = it) }, Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedTextColor = txtWhite, unfocusedTextColor = txtWhite, cursorColor = PrimaryOrange, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), placeholder = { Text("闹钟标签", color = txtDim) }, singleLine = true)
            }
        }
        // 任务类型
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("任务类型", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = txtWhite); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MissionType.entries.take(4).forEach { MissionChip(editAlarm.missionType == it, it, { editAlarm = editAlarm.copy(missionType = it) }) } }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MissionType.entries.drop(4).take(4).forEach { MissionChip(editAlarm.missionType == it, it, { editAlarm = editAlarm.copy(missionType = it) }) } }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MissionType.entries.drop(8).forEach { MissionChip(editAlarm.missionType == it, it, { editAlarm = editAlarm.copy(missionType = it) }) } }
            }
        }
        // 任务次数
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
            }
        }
        // 重复模式
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("自定义重复时间", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = txtWhite); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(com.dutyclock.core.RepeatMode.ONCE, com.dutyclock.core.RepeatMode.DAILY, com.dutyclock.core.RepeatMode.WEEKDAYS, com.dutyclock.core.RepeatMode.WEEKENDS, com.dutyclock.core.RepeatMode.CUSTOM)
                    modes.forEach { mode: com.dutyclock.core.RepeatMode ->
                        FilterChip(selected = editAlarm.repeatMode == mode, onClick = { editAlarm = editAlarm.copy(repeatMode = mode) }, label = { Text(mode.label, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SecondaryPurple.copy(alpha = 0.3f), selectedLabelColor = SecondaryPurple, containerColor = surface, labelColor = txtGray), shape = RoundedCornerShape(8.dp))
                    }
                }
                if (editAlarm.repeatMode == com.dutyclock.core.RepeatMode.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        dayNames.forEachIndexed { idx, name ->
                            val dayNum = idx + 1
                            val selected = dayNum in editAlarm.repeatDays
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape)
                                    .background(if (selected) PrimaryOrange else surface)
                                    .clickable {
                                        val newDays = if (selected) editAlarm.repeatDays - dayNum
                                        else editAlarm.repeatDays + dayNum
                                        editAlarm = editAlarm.copy(repeatDays = newDays)
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text(name, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) Color.White else txtGray) }
                        }
                    }
                }
            }
        }
        // 铃声 — 两个独立按钮
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("铃声", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = txtWhite)
                if (editAlarm.ringtoneName.isNotEmpty()) {
                    Text(editAlarm.ringtoneName, fontSize = 12.sp, color = txtDim)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 1) 自定义铃声选择
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹钟铃声")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            }
                            ringtonePicker.launch(intent)
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TertiaryCyan),
                        border = BorderStroke(1.dp, TertiaryCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.LibraryMusic, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("选择铃声", fontSize = 13.sp)
                    }
                    // 2) 铃声抽奖
                    OutlinedButton(
                        onClick = { onOpenSlotRingtone?.invoke(editAlarm) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryPurple),
                        border = BorderStroke(1.dp, SecondaryPurple),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Casino, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("铃声抽奖", fontSize = 13.sp)
                    }
                }
            }
        }
        // 测试按钮
        Button({ onTestMission(editAlarm) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange), shape = RoundedCornerShape(26.dp)) { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("立即测试任务", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(32.dp))
    }

}

private fun getStepForType(type: MissionType) = when (type) { MissionType.SQUAT -> 5; MissionType.PUSH_UP -> 5; MissionType.WAVE -> 5; MissionType.TIESHANKO -> 5; MissionType.SHAKE -> 10; else -> 1 }

@Composable
fun RowScope.MissionChip(selected: Boolean, type: MissionType, onClick: () -> Unit) {
    Surface(Modifier.weight(1f).clickable { onClick() }, shape = RoundedCornerShape(12.dp), color = if (selected) PrimaryOrange.copy(alpha = 0.2f) else DarkSurface, border = if (selected) BorderStroke(1.5.dp, PrimaryOrange) else null) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(type.emoji, fontSize = 22.sp); Spacer(Modifier.height(4.dp)); Text(type.displayName, fontSize = 11.sp, color = if (selected) PrimaryOrange else DarkTextGray) }
    }
}

// ======================== 姿态任务页(含白色卡通骨骼 + 动作指引) ========================
@Composable
fun SquatMissionScreen(
    lifecycleOwner: LifecycleOwner, context: Context, key: Int, cameraManager: CameraManager?, onCameraReady: (CameraManager) -> Unit,
    engineReady: Boolean, isLoading: Boolean, squatCount: Int, targetSquats: Int, squatState: SquatDetector.SquatState,
    currentAngle: Float, fps: Float, lastInferMs: Double, displayBitmap: Bitmap?, poses: List<PoseResult>,
    onFrameAvailable: (Bitmap) -> Unit, onStop: () -> Unit,
    bg: Color, card: Color, card2: Color, surface: Color, txtWhite: Color, txtGray: Color, txtDim: Color,
    missionTitle: String = "深蹲任务", missionSubtitle: String = "",
    missionType: MissionType = MissionType.SQUAT,
    stateText: String = "", infoLabel: String = "髋膝差", infoValue: String = ""
) {
    val previewView = remember(key) { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    LaunchedEffect(key) { if (cameraManager == null) { val cm = CameraManager(context, lifecycleOwner, previewView, onFrameAvailable); onCameraReady(cm); cm.start() } }

    val guidanceText: String = when (missionType) {
        MissionType.SQUAT -> "深蹲：保持身体直立，膝盖弯曲下蹲至大腿与地面平行，恢复站立"
        MissionType.PUSH_UP -> "俯卧撑：身体保持直线，屈肘下降胸部贴近地面，推起还原"
        MissionType.WAVE -> "挥手：单臂抬高至头顶上方，上下摆动完成挥手动作"
        MissionType.TIESHANKO -> "铁山靠：身体侧倾，肩膀发力向一侧快速撞击并回正"
        else -> ""
    }

    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().background(card).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FitnessCenter, null, tint = PrimaryOrange, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text(missionTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = txtWhite); Text(missionSubtitle.ifEmpty { "完成 ${targetSquats} 次关闭闹钟" }, fontSize = 12.sp, color = txtGray) }
                TextButton(onClick = onStop) { Text("停止", color = ErrorRed) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (engineReady) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // 左上角黄色文字动作说明
                if (guidanceText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC202020))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            guidanceText,
                            color = Color(0xFFFFD600),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            lineHeight = 16.sp
                        )
                    }
                }
                // 白色卡通骨骼叠加 Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (poses.isEmpty() || poses.maxByOrNull { it.confidence } == null) return@Canvas
                    val best = poses.maxByOrNull { it.confidence } ?: return@Canvas
                    val kps = best.keypoints
                    if (kps.size < 17) return@Canvas

                    val scale = minOf(size.width, size.height) / 320f
                    val offsetX = (size.width - 320f * scale) / 2f
                    val offsetY = (size.height - 320f * scale) / 2f

                    // 绘制白色卡通骨骼线（加粗 + 暗色外描边）
                    for ((a, b) in SKELETON_PAIRS) {
                        if (a >= kps.size || b >= kps.size) continue
                        val p1 = kps[a]; val p2 = kps[b]
                        if (p1.conf > 0.2f && p2.conf > 0.2f) {
                            val start = Offset(p1.x * scale + offsetX, p1.y * scale + offsetY)
                            val end = Offset(p2.x * scale + offsetX, p2.y * scale + offsetY)
                            // 暗色外描边
                            drawLine(CartoonBoneOutline, start, end, strokeWidth = 8f, cap = StrokeCap.Round)
                            // 白色内线
                            drawLine(CartoonBoneColor, start, end, strokeWidth = 5f, cap = StrokeCap.Round)
                        }
                    }
                    // 3. 绘制关键点（卡通圆点）
                    for ((i, kp) in kps.withIndex()) {
                        if (kp.conf > 0.15f) {
                            val pos = Offset(kp.x * scale + offsetX, kp.y * scale + offsetY)
                            when (i) {
                                in HAND_INDICES -> {
                                    // 手部：黄色大三角
                                    drawTriangle(pos, 12f, CartoonHandFootColor, 0f)
                                }
                                in FOOT_INDICES -> {
                                    // 脚部：黄色大三角
                                    drawTriangle(pos, 12f, CartoonHandFootColor, 180f)
                                }
                                in HEAD_INDICES -> {
                                    // 头部：橙色圆点
                                    drawCircle(CartoonHeadColor, 6f, pos)
                                    drawCircle(CartoonHeadColor.copy(alpha = 0.6f), 8f, pos)
                                }
                                else -> {
                                    // 身体：白色圆点 + 暗色外圈
                                    drawCircle(CartoonPointOutline, 7f, pos)
                                    drawCircle(CartoonPointColor, 4.5f, pos)
                                }
                            }
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
                    Text(stateText.ifEmpty {
                        when (squatState) { SquatDetector.SquatState.STANDING -> "站立"; SquatDetector.SquatState.SQUATTING -> "检测到 ✓"; SquatDetector.SquatState.TRANSITIONING -> "过渡中" }
                    }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (squatState == SquatDetector.SquatState.SQUATTING) SuccessGreen else txtWhite)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { (squatCount.toFloat() / targetSquats).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = PrimaryOrange, trackColor = surface)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    InfoChip(infoLabel.ifEmpty { "髋膝差" }, infoValue.ifEmpty { "${currentAngle.toInt()}" }, txtDim, txtWhite)
                    InfoChip("FPS", "${fps.toInt()}", txtDim, txtWhite)
                    InfoChip("推理", "${lastInferMs.toInt()}ms", txtDim, txtWhite)
                }
            }
        }
    }
}

// 视觉引导线已移除，各动作对应左上角黄色文字说明


// ======================== 摇晃任务(真实传感器) ========================

/** 绘制手脚三角指示器 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTriangle(
    center: Offset, size: Float, color: Color, rotation: Float
) {
    val angleRad = Math.toRadians(rotation.toDouble()).toFloat()
    val cos = kotlin.math.cos(angleRad); val sin = kotlin.math.sin(angleRad)
    val halfW = size * 0.6f; val h = size * 1.1f
    val p1 = Offset(center.x + cos * h, center.y + sin * h)
    val p2 = Offset(center.x + cos * (-halfW) + sin * (-halfW), center.y + sin * (-halfW) - cos * (-halfW))
    val p3 = Offset(center.x - cos * (-halfW) + sin * halfW, center.y - sin * (-halfW) - cos * halfW)
    val path = Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close() }
    drawPath(path, color)
    drawPath(path, CartoonPointOutline, style = Stroke(width = 2f))
}

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

// ======================== 数字排序任务 ========================
@Composable
fun NumberOrderScreen(
    tiles: List<Int>, nextNumber: Int,
    onTileClick: (Int) -> Unit, onStop: () -> Unit,
    bg: Color, card: Color, txtWhite: Color, txtGray: Color, txtDim: Color
) {
    MissionPageWrapper("数字排序", "按升序点击数字方块", "${nextNumber - 1} / 6", onStop, bg, card, txtWhite, txtGray) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("下一个: $nextNumber", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrimaryOrange)
            Spacer(Modifier.height(24.dp))
            // 3×2 网格显示6个方块
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (col in 0..2) {
                            val idx = row * 3 + col
                            if (idx < tiles.size) {
                                val num = tiles[idx]
                                val alreadyClicked = num < nextNumber
                                Button(
                                    onClick = { if (num >= nextNumber) onTileClick(num) },
                                    modifier = Modifier.size(80.dp),
                                    enabled = num >= nextNumber,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when { alreadyClicked -> SuccessGreen.copy(alpha = 0.3f); num == nextNumber -> PrimaryOrange; else -> card },
                                        disabledContainerColor = when { alreadyClicked -> SuccessGreen.copy(alpha = 0.15f); else -> DarkSurface }
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("$num", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (alreadyClicked) SuccessGreen else txtWhite)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================== 反应测试任务（红屏随机延迟 → 绿屏固定300ms） ========================
@Composable
fun ReactionScreen(
    state: Int, count: Int, tooEarly: Boolean,
    onTap: () -> Unit, onStateChange: (Int) -> Unit, onStop: () -> Unit,
    bg: Color, txtWhite: Color, txtGray: Color, txtDim: Color
) {
    // state 0=红屏等待，state 1=绿屏可点击
    // 红屏：随机800~2500ms后变绿
    // 绿屏：固定300ms内可点击，超时自动变回红屏（不计成绩）
    // 使用 Unit key 避免绿屏定时器被 state 变化取消
    LaunchedEffect(Unit) {
        while (count < 5) {
            delay(Random.nextLong(800, 2501)) // 红屏随机延迟
            onStateChange(1) // 变绿
            delay(300) // 绿屏持续300ms
            // 超时：自动回到红屏，重新循环
            onStateChange(0)
        }
    }

    val bgColor = when (state) { 1 -> Color(0xFF1B5E20); else -> Color(0xFF5E1B1B) }
    MissionPageWrapper("反应测试", if (state == 1) "快点击！（300ms）" else "等待绿灯...", "$count / 5", onStop, bg, card = DarkCard, txtWhite, txtGray) {
        Box(
            modifier = Modifier.fillMaxSize().background(bgColor).clickable { onTap() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(count.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("/ 5 次", fontSize = 22.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(16.dp))
                if (state == 1) Text("点击屏幕！300ms倒计时", fontSize = 20.sp, color = Color.White)
                else if (tooEarly) Text("太早了！等绿灯", fontSize = 16.sp, color = WarningYellow)
                else Text("等待随机延迟...", fontSize = 14.sp, color = txtDim)
                Spacer(Modifier.height(8.dp))
                if (state == 1) {
                    // 300ms倒计时进度条
                    var greenProgress by remember { mutableStateOf(1f) }
                    LaunchedEffect(Unit) {
                        val start = System.currentTimeMillis()
                        while (greenProgress > 0f) {
                            greenProgress = (1f - (System.currentTimeMillis() - start) / 300f).coerceIn(0f, 1f)
                            delay(16)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { greenProgress },
                        modifier = Modifier.fillMaxWidth(0.6f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

// ======================== 老虎机抽奖页面（平滑动画 + 自选池） ========================
@Composable
fun SlotMachineScreen(
    mode: String, // "action" or "ringtone"
    onActionResult: (MissionType) -> Unit,
    onRingtoneResult: (String) -> Unit,
    onBack: () -> Unit,
    bg: Color, card: Color, card2: Color, surface: Color,
    txtWhite: Color, txtGray: Color, txtDim: Color, txtAccent: Color
) {
    val isActionMode = mode == "action"

    val allActionItems = remember { MissionType.entries.filter { it != MissionType.RANDOM_ACTION } }
    val allRingtoneItems = remember { listOf(
        "经典铃声", "电子音乐", "钢琴曲", "摇滚乐", "爵士乐",
        "鸟鸣声", "海浪声", "吉他", "小提琴", "嘻哈节奏",
        "科技感", "复古风", "轻音乐", "流行曲", "自然声"
    ) }

    // 自选池状态
    var poolActions by remember { mutableStateOf(allActionItems.toSet()) }
    var poolRingtones by remember { mutableStateOf(allRingtoneItems.toSet()) }
    var showPoolEditor by remember { mutableStateOf(false) }

    val activeActionItems by remember { derivedStateOf { allActionItems.filter { it in poolActions } } }
    val activeRingtoneItems by remember { derivedStateOf { allRingtoneItems.filter { it in poolRingtones } } }

    val items = if (isActionMode) activeActionItems.map { it.emoji + " " + it.displayName }
               else activeRingtoneItems
    val itemCount = items.size

    // 老虎机状态
    var isSpinning by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    var displayOffset by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    val animOffset = remember { Animatable(0f) }

    // 当池子变化时重新选取一个安全的初始索引
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && selectedIndex >= itemCount) {
            selectedIndex = Random.nextInt(itemCount)
        }
    }

    // 平滑滚动动画
    LaunchedEffect(isSpinning) {
        if (!isSpinning || itemCount == 0) return@LaunchedEffect
        showResult = false
        val targetIdx = Random.nextInt(itemCount)
        selectedIndex = targetIdx
        // 滚动圈数 + 到达目标
        val totalDist = (Random.nextInt(5, 9) * itemCount + targetIdx).toFloat()
        val startOffset = displayOffset
        val durationMs = (totalDist * 50).toInt().coerceIn(1500, 3500)

        animOffset.snapTo(startOffset)
        animOffset.animateTo(
            targetValue = startOffset + totalDist,
            animationSpec = tween(
                durationMillis = durationMs,
                easing = FastOutSlowInEasing
            )
        )
        // 校准到整数位置
        val finalOffset = startOffset + totalDist
        displayOffset = finalOffset - finalOffset.toInt()
        isSpinning = false
        showResult = true
    }

    // 当前显示项
    val visibleIndex = if (isSpinning) {
        (selectedIndex + abs((animOffset.value % itemCount).toInt())) % itemCount
    } else selectedIndex
    val prevIdx = ((visibleIndex - 1 + itemCount) % itemCount)
    val nextIdx = ((visibleIndex + 1) % itemCount)

    // 动画闪烁效果
    val glowAlpha by animateFloatAsState(
        targetValue = if (isSpinning) 0.6f else 0.2f,
        animationSpec = tween(200),
        label = "glow"
    )

    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState())) {
        // 标题栏
        Row(Modifier.fillMaxWidth().statusBarsPadding().background(card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = txtWhite); Text("返回", color = txtWhite) }
            Spacer(Modifier.width(8.dp))
            Text(if (isActionMode) "🎰 随机动作抽选" else "🎵 随机铃声抽选", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = txtWhite)
            Spacer(Modifier.weight(1f))
            if (!isSpinning && !showResult) {
                TextButton(onClick = { showPoolEditor = !showPoolEditor }) {
                    Text(if (showPoolEditor) "收起" else "筛选", fontSize = 13.sp, color = SecondaryPurple)
                }
            }
        }

        // 自选池编辑器
        AnimatedVisibility(visible = showPoolEditor && !isSpinning && !showResult) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = card2),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("选择抽选范围", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = txtWhite)
                    Spacer(Modifier.height(8.dp))
                    if (isActionMode) {
                        val chunked = allActionItems.chunked(3)
                        chunked.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { mt ->
                                    val checked = mt in poolActions
                                    FilterChip(
                                        selected = checked,
                                        onClick = { poolActions = if (checked) poolActions - mt else poolActions + mt },
                                        label = { Text(mt.emoji + " " + mt.displayName, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PrimaryOrange.copy(alpha = 0.25f),
                                            selectedLabelColor = PrimaryOrange,
                                            containerColor = surface,
                                            labelColor = txtGray
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    } else {
                        val chunkedR = allRingtoneItems.chunked(3)
                        chunkedR.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { rt ->
                                    val checked = rt in poolRingtones
                                    FilterChip(
                                        selected = checked,
                                        onClick = { poolRingtones = if (checked) poolRingtones - rt else poolRingtones + rt },
                                        label = { Text(rt, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SecondaryPurple.copy(alpha = 0.25f),
                                            selectedLabelColor = SecondaryPurple,
                                            containerColor = surface,
                                            labelColor = txtGray
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 老虎机主体
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.fillMaxWidth().height(220.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(surface)
                    .border(3.dp, if (showResult) SuccessGreen else PrimaryOrange.copy(alpha = 0.5f + glowAlpha * 0.5f), RoundedCornerShape(24.dp))
            )

            // 渐变遮罩
            Box(Modifier.fillMaxWidth().height(50.dp).align(Alignment.TopCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0.95f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(50.dp).align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Brush.verticalGradient(listOf(Color.Transparent, surface.copy(alpha = 0.95f)))))

            // 选中高亮
            Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.Center)
                .background(PrimaryOrange.copy(alpha = if (showResult) 0.25f else 0.1f)))

            // 三行显示
            if (itemCount > 0) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(items[prevIdx], fontSize = 18.sp, color = txtDim.copy(alpha = 0.4f),
                        modifier = Modifier.graphicsLayer { alpha = 0.35f; scaleX = 0.85f; scaleY = 0.85f },
                        textAlign = TextAlign.Center, maxLines = 1)
                    Text(items[visibleIndex], fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = if (showResult) SuccessGreen else txtWhite,
                        modifier = Modifier.graphicsLayer {
                            scaleX = if (isSpinning) 1.0f else 1.08f
                            scaleY = if (isSpinning) 1.0f else 1.08f
                        },
                        textAlign = TextAlign.Center, maxLines = 1)
                    Text(items[nextIdx], fontSize = 18.sp, color = txtDim.copy(alpha = 0.4f),
                        modifier = Modifier.graphicsLayer { alpha = 0.35f; scaleX = 0.85f; scaleY = 0.85f },
                        textAlign = TextAlign.Center, maxLines = 1)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("请至少选择一个备选项", color = txtDim, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // 结果展示
        if (showResult && itemCount > 0) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎉 抽选结果", fontSize = 14.sp, color = txtGray)
                    Spacer(Modifier.height(8.dp))
                    val safeIdx = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    Text(items.getOrElse(safeIdx) { "—" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val si = selectedIndex.coerceIn(0, (activeActionItems.size - 1).coerceAtLeast(0))
                        if (isActionMode && activeActionItems.isNotEmpty()) {
                            onActionResult(activeActionItems[si])
                        } else if (!isActionMode && activeRingtoneItems.isNotEmpty()) {
                            val ri = selectedIndex.coerceIn(0, (activeRingtoneItems.size - 1).coerceAtLeast(0))
                            onRingtoneResult(activeRingtoneItems[ri])
                        }
                    }, modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("确认使用", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        } else if (!isSpinning) {
            Button(onClick = { if (itemCount > 0) isSpinning = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                shape = RoundedCornerShape(28.dp),
                enabled = itemCount > 0
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始抽选", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 64.dp), contentAlignment = Alignment.Center) {
                Text("抽选中...", fontSize = 16.sp, color = txtGray)
            }
        }

        Spacer(Modifier.height(24.dp))
        if (!isSpinning && !showResult && itemCount > 0) {
            Text("从 ${itemCount} 个候选中随机抽取一个${if (isActionMode) "动作任务" else "铃声"}",
                fontSize = 13.sp, color = txtDim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@Composable
fun InfoChip(label: String, value: String, txtDim: Color, txtWhite: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontSize = 11.sp, color = txtDim); Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = txtWhite) }
}
