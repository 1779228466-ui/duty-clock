# Duty Clock - 使命闹钟

基于 YOLO26n-pose 姿态识别 + NCNN CPU 推理的 Android 使命闹钟应用。仿 Alarmy 设计，支持 9 种唤醒任务（深蹲、挥手、数学、记忆、摇晃、步数、打字、拍照、扫码），闹钟响起后必须完成任务才能关闭。

---

## 功能特性

- 🏋️ **9 种唤醒任务**：深蹲 | 挥手 | 数学 | 记忆 | 摇晃 | 步数 | 打字 | 拍照 | 扫码
- 🎥 **实时姿态识别**：YOLO26n-pose + NCNN CPU 推理，320×320 固定分辨率，47 FPS
- 🦴 **骨骼关键点可视化**：Canvas 实时绘制 17 个 COCO 关键点 + 骨骼连线
- ⏰ **系统闹钟**：AlarmManager 精确唤醒 + MediaPlayer 铃声 + WakeLock 锁屏唤醒
- 🎨 **双主题**：亮色/暗色主题切换，默认亮色
- 📱 **摄像头**：CameraX 前置摄像头，320×320 固定输出，旋转/镜像自适应
- 📝 **闹钟管理**：持久化存储，支持标签、重复、难度调节
- 🌙 **助眠声音**：雨声/海浪/森林/白噪音等
- 🔙 **返回拦截**：任务进行中按返回键弹出确认对话框

## 工作流程

```
1. 首页设置闹钟时间 + 选择任务类型 + 次数/难度
         │
         ▼
2. 保存闹钟 → 闹钟时间到 → 播放铃声 → 打开任务页面
         │
         ▼
3. 完成任务（深蹲/挥手需摄像头姿态识别）
         │
         ▼
4. 完成目标次数 → 停止铃声 → 显示完成页
```

## 项目结构

```
app/src/main/
├── java/com/dutyclock/
│   ├── MainActivity.kt              # 主界面（6页 + 推理循环 + 骨骼绘制）
│   └── core/
│       ├── DutyAlarmManager.kt      # 闹钟调度 + 铃声 + WakeLock
│       ├── CameraManager.kt         # CameraX 320×320 固定输出
│       ├── SquatDetector.kt         # 下蹲检测（膝关节角度 + 状态机）
│       ├── NcnnEngine.kt            # NCNN 推理引擎封装
│       ├── InferenceEngine.kt       # 推理接口 + 数据类
│       ├── MissionType.kt           # 9种任务类型 + 难度 + 数学/记忆/打字生成器
│       ├── AlarmRepository.kt       # 闹钟持久化（SharedPreferences + JSON）
│       └── AppLog.kt                # 日志工具
├── cpp/
│   ├── CMakeLists.txt               # C++ 编译配置（OMP/NEON/ARMv8.4-A）
│   └── cposemouse_ncnn_jni.cpp      # JNI 层（模型加载 + 推理）
└── assets/
    ├── yolo26n-pose-128.param/bin   # 128×128 模型（速度最快）
    ├── yolo26n-pose-160.param/bin   # 160×160 模型（平衡）
    └── yolo26n-pose-320.param/bin   # 320×320 模型（精度最高，默认）
```

## 核心模块

### 姿态推理
- **模型**: YOLO26n-pose, 3,668,948 参数, 7.5 GFLOPs
- **优化**: OMP 4线程, FP16/INT8, Winograd卷积, 大核绑定(CPU4-7)
- **推理速度**: 320×320 约 21ms (47 FPS)
- **坐标系统**: CameraManager 固定输出 320×320 → 模型输入 320×320 → 关键点 [0,320] → Canvas `size/320` 缩放

### 下蹲检测
- 髋(11/12)-膝(13/14)-踝(15/16) 三点角度计算
- 阈值: <90° 蹲下, >150° 站立
- 状态机: STANDING → SQUATTING → STANDING = 1次, 连续3帧防抖

### 挥手检测
- 手腕关键点(9,10) Y 坐标上下摆动识别
- 阈值: Y 变化 >15px 为一次方向变化
- 上→下→上 = 1次挥手

### 摇晃/步数任务
- 接入 Android 加速度传感器和计步传感器

## 构建与运行

### 环境要求
- Android Studio 2024+
- Android SDK 28+ (minSdk 28)
- NDK (arm64-v8a)
- CMake 3.22.1+

### 构建
```bash
# Android Studio 打开 CPU_pose/CPoseMouse 目录
# Gradle Sync → Build → Build APK
# 安装到 arm64-v8a 设备
```

### 模型导出（可选）
```bash
pip install ultralytics ncnn pnnx
python -c "
from ultralytics import YOLO
model = YOLO('yolo26n-pose.pt')
model.export(format='ncnn', imgsz=320, half=True, device='cpu')
"
# 将 model.ncnn.param/bin 复制到 app/src/main/assets/
```

## 技术栈

| 层面 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| 摄像头 | CameraX 320×320 固定分辨率 |
| 推理引擎 | NCNN (腾讯开源) |
| 姿态模型 | YOLO26n-pose (Ultralytics) |
| 原生加速 | OMP + NEON SIMD + ARMv8.4-A |
| 构建 | Gradle KTS + CMake |
| 语言 | Kotlin + C++ (JNI) |

## 项目来源

从 [PoseMouse](https://github.com) 重构而来，移除 PC 通信/蓝牙 HID/鼠标控制等模块，新增 CameraX 摄像头接入、多任务系统、骨骼可视化和闹钟系统。
