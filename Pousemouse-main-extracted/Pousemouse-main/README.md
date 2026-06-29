# PMouse - 人体姿态鼠标控制系统 v0.0.1-beta

## 项目简介

PMouse是一个通过人体姿态识别控制鼠标的系统，目标是在FPS游戏中实现自动瞄准辅助。系统由PC端和手机端两部分组成，手机端运行NPU加速的YOLO姿态检测模型，通过蓝牙HID将鼠标控制指令发送到PC。

## 系统架构

```
摄像头/OBS截屏 → 手机端NPU推理(YOLO pose) → 蓝牙HID鼠标指令 → PC端鼠标控制
```

## 硬件环境

- **手机**：vivo X100（天玑9300 APU790/MT6989, Android 14/API 36, OriginOS）
- **PC**：AMD Ryzen 9 8945HX, RTX 5060 Laptop, 2560x1600@240Hz
- **连接**：USB共享网络 + 蓝牙HID

## 项目结构

### 手机端（本项目）

```
app/src/main/
├── java/com/PMouse/
│   ├── MainActivity.kt              # 主界面，引擎选择
│   ├── core/
│   │   ├── InferenceEngine.kt       # 推理引擎接口+数据类
│   │   ├── InferenceLoop.kt         # 推理循环
│   │   ├── NeuronDirectEngine.kt    # NPU直连引擎（方案B）
│   │   ├── LiteRtEngine.kt         # LiteRT引擎（方案A）
│   │   ├── NcnnEngine.kt           # NCNN+CPU引擎
│   │   ├── BluetoothHidMouse.kt    # 蓝牙HID鼠标控制
│   │   ├── AdaptivePidController.kt # 自适应PID控制器
│   │   ├── UdpFrameReceiver.kt     # KCP模式+H.264解码
│   │   ├── H264Decoder.kt          # MediaCodec硬件解码
│   │   ├── BodyParts.kt            # 瞄准部位计算
│   │   └── AppLog.kt               # 日志系统
│   └── service/
│       └── InferenceService.kt      # 推理服务
├── cpp/
│   ├── neuron_direct_jni.cpp        # NPU直连JNI层
│   ├── CMakeLists.txt               # CMake构建
│   └── neuropilot_include/          # Neuron API头文件
├── assets/
│   ├── yolo26s-pose-320-int8.tflite # INT8量化模型
│   └── *.bin/*.param                # NCNN模型文件
└── jniLibs/arm64-v8a/
    ├── libLiteRt.so                  # LiteRT runtime（5.3MB，含JNI）
    ├── libLiteRtDispatch_MediaTek.so # 修改后的dispatch库（9.7MB）
    └── libLiteRtCompilerPlugin_MediaTek.so # NPU编译器插件
```

### PC端（独立项目）

- 路径：`D:\HuaWei\text\pose_mouse\`
- 功能：TensorRT-YOLO推理 + DXGI截屏 + 4种控制器 + 自动开火 + 控制面板

### OBS UDP输出插件（独立项目）

- 路径：`D:\HuaWei\text\obs-udp-plugin\`
- 功能：OBS截屏→H.264 NVENC编码→UDP输出

## 三种手机端推理方案

| 方案 | 引擎 | 状态 | FPS | 说明 |
|------|------|------|-----|------|
| NCNN+CPU | NcnnEngine | ✅可用 | ~140 | yolo26n-pose 160×160，精度低 |
| 方案A | LiteRtEngine | ❌P3报错 | - | LiteRT+NPU，P3 partition bug |
| 方案B | NeuronDirectEngine | ❌输出不变 | 139 | NPU直连，P1输出不随输入变化 |

## 控制流程

1. 手机摄像头采集画面 / OBS截屏通过UDP发送到手机
2. 画面缩放到320×320，送入推理引擎
3. 检测人体关键点（17个），计算瞄准部位坐标
4. 自适应PID控制器计算鼠标偏移量
5. 通过蓝牙HID发送鼠标移动指令到PC

## 构建说明

```bash
# 需要Android Studio，NDK r28c
# 编译需要 --no-configuration-cache 参数（因afterEvaluate patch task不兼容configuration cache）
gradlew assembleDebug --no-configuration-cache

# 安装到手机
gradlew installDebug
```

## 关键约束

- 不能用adb调试（项目功能不能依赖adb，但开发调试阶段可以用）
- 不能运行第三方软件（除了OBS和UU远程）
- 蓝牙HID是唯一不被ACE反作弊检测的鼠标控制方案
- 手机不能root
- 置信度默认0.6，快捷键p键toggle鼠标控制
