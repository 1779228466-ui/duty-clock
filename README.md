# 使命闹钟 (Duty Clock)

基于 YOLO26n-pose + NCNN CPU 推理的 Android 使命闹钟应用。闹钟响起后必须完成指定任务才能关闭。

---

## 功能特性

- 🏋️ **多种唤醒任务**：深蹲 | 俯卧撑 | 挥手 | 铁山靠 | 摇晃 | 数字排序 | 反应测试 | 随机动作
- 🎥 **实时姿态识别**：YOLO26n-pose + NCNN CPU 推理，320×320 固定分辨率
- 🦴 **骨骼可视化**：Canvas 实时绘制 17 个 COCO 关键点 + 白色卡通骨骼线
- ⏰ **系统闹钟**：AlarmManager 精确唤醒 + 前台服务 + FullScreenIntent 锁屏弹窗
- 🎨 **双主题**：亮色/暗色切换，默认亮色
- 📱 **CameraX**：前置摄像头，320×320 固定输出
- 🎰 **老虎机抽选**：随机动作 + 随机铃声，支持自定义候选池
- 📂 **文件导入闹钟**：支持从 txt/csv 文件批量导入闹钟时间
- 🔔 **开机自启调度**：重启后自动恢复闹钟

## 项目结构

```
app/src/main/
├── java/com/dutyclock/
│   ├── MainActivity.kt                # 主界面 + 推理循环 + 骨骼绘制
│   └── core/
│       ├── DutyAlarmManager.kt        # 闹钟调度 + 铃声
│       ├── AlarmForegroundService.kt  # 前台服务 + FullScreenIntent
│       ├── BootReceiver.kt            # 开机自启闹钟恢复
│       ├── AlarmReceiver.kt           # 闹钟触发广播
│       ├── CameraManager.kt           # CameraX 320×320 输出
│       ├── SquatDetector.kt           # 深蹲检测
│       ├── TieShanKaoDetector.kt      # 铁山靠检测
│       ├── NcnnEngine.kt              # NCNN 推理引擎
│       ├── InferenceEngine.kt         # 推理接口 + 数据类
│       ├── MissionType.kt             # 任务类型 + 数据模型
│       ├── AlarmRepository.kt         # 闹钟持久化
│       └── AppLog.kt                  # 日志工具
├── cpp/
│   ├── CMakeLists.txt                 # C++ 编译配置
│   └── dutyclock_ncnn_jni.cpp         # JNI 层（模型加载 + 推理）
└── assets/
    └── yolo26n-pose-*.param/bin       # YOLO 模型
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
