# CPU推理方案 — 进展与问题记录

## 方案概述

在 `D:\HuaWei\CPU_pose` 下创建完全独立的CPU推理Android项目，基于NCNN+CPU引擎，不修改以下原始项目：
- `D:\AS\PMouse` — 原始NPU项目
- `D:\AS\HidRemote` — 蓝牙HID参考项目
- `D:\AS\ncnn-android-vulkan` — NCNN Vulkan预编译库

---

## 硬件环境

| 设备 | 规格 |
|------|------|
| 手机 | vivo X100 (天玑9300/MT6989, Android 14/API 36) |
| PC | AMD Ryzen 9 8945HX, RTX 5060 Laptop, 2560×1600@240Hz |
| 连接 | USB共享网络 + 蓝牙HID |

### 天玑9300 CPU核心架构（全大核，无小核）

| 核心类型 | 数量 | 频率 | 架构 | L2缓存 |
|---------|------|------|------|--------|
| Cortex-X4 超大核 | 1 | 3.25 GHz | ARMv9.2 | 1MB |
| Cortex-A720 大核 | 3 | 2.85 GHz | ARMv9.2 | 512KB |
| Cortex-A720 中核 | 4 | 2.0 GHz | ARMv9.2 | 256KB |
| **共享L3** | | | | **8MB** |

**关键特征**：8核全大核，无Cortex-A520小核。`set_cpu_powersave(1)`（绑小核）无效。

---

## 技术栈

| 组件 | 选型 |
|------|------|
| 推理引擎 | NCNN CPU (`useVulkan=false`) |
| 模型 | yolo26n-pose-320 (NCNN param/bin) |
| 输入尺寸 | 320×320 (默认) |
| JNI库 | libposemouse_ncnn.so (NCNN+KCP) |
| NCNN预编译库 | ncnn-20260526-android (纯CPU版，无Vulkan) |
| 控制算法 | AdaptivePidController (自适应PID) |
| 鼠标控制 | BluetoothHidMouse (蓝牙HID) |
| 帧传输 | UDP/KCP (H.264编码) |
| 语言 | Kotlin + C++ (JNI) |

---

## 项目结构规划

```
D:\HuaWei\CPU_pose\CPoseMouse\          (独立项目根目录)
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/CPoseMouse/
│       │   ├── MainActivity.kt
│       │   ├── core/
│       │   │   ├── InferenceEngine.kt       (接口+数据类)
│       │   │   ├── NcnnEngine.kt            (NCNN CPU引擎)
│       │   │   ├── InferenceLoop.kt         (推理循环)
│       │   │   ├── BluetoothHidMouse.kt     (蓝牙HID)
│       │   │   ├── AdaptivePidController.kt (自适应PID)
│       │   │   ├── AlphaBetaPDController.kt (α-β PD备选)
│       │   │   ├── HxPdController.kt        (简单PD备选)
│       │   │   ├── BodyParts.kt             (瞄准部位)
│       │   │   ├── KalmanMultiTracker.kt    (卡尔曼跟踪)
│       │   │   ├── LowTextureSuppressor.kt  (低纹理抑制)
│       │   │   ├── UdpFrameReceiver.kt      (UDP帧接收)
│       │   │   ├── UdpTargetSender.kt       (UDP目标发送)
│       │   │   ├── H264Decoder.kt           (H.264解码)
│       │   │   ├── ScreenCapture.kt         (截屏)
│       │   │   ├── AppLog.kt                (日志系统)
│       │   │   └── InferenceServiceHolder.kt(服务状态)
│       │   └── service/
│       │       └── InferenceService.kt      (推理服务)
│       ├── cpp/
│       │   ├── CMakeLists.txt               (仅编译posemouse_ncnn)
│       │   ├── posemouse_ncnn_jni.cpp       (NCNN JNI + KCP)
│       │   ├── ikcp.c / ikcp.h              (KCP协议)
│       │   └── ncnn-20260526-android/       (纯CPU版NCNN预编译库)
│       ├── assets/
│       │   ├── yolo26n-pose-128.param/bin
│       │   ├── yolo26n-pose-160.param/bin
│       │   └── yolo26n-pose-320.param/bin
│       └── jniLibs/arm64-v8a/
│           (空 — NCNN静态链接，无需额外SO)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

---

## 与原项目的差异

| 维度 | 原项目 (PMouse) | CPU项目 (CPoseMouse) |
|------|-----------------|---------------------|
| 包名 | com.PMouse | com.CPoseMouse |
| 引擎 | 4种(ncnn/vulkan/litert/neuron) | 仅ncnn_cpu |
| NCNN库 | Vulkan版 (含glslang) | 纯CPU版 (无Vulkan依赖) |
| jniLibs | 3个LiteRT SO | 空 |
| Gradle依赖 | litert + onnxruntime | 无额外ML依赖 |
| CMake targets | 2个(posemouse_ncnn + neuron_direct) | 1个(posemouse_ncnn) |
| 模型文件 | 全部(含tflite/onnx/yolo11n) | 仅yolo26n-pose NCNN格式 |
| APK体积 | 较大 | 较小 |

---

## 研究成果

### NCNN多线程策略（天玑9300全大核架构）

| 策略 | num_threads | set_cpu_powersave | 预期效果 | 风险 |
|------|------------|-------------------|---------|------|
| 单线程（当前） | 1 | 不需要 | 稳定，~7ms@160 | 无 |
| 大核绑定（推荐尝试） | 4 | 2 | 可能提升2-3x | 需验证OMP SIGABRT |
| 全核 | 8 | 0 | 可能因缓存争抢反而更慢 | OMP SIGABRT |

**多线程安全启用步骤**：
1. 保留 `KMP_AFFINITY=disabled` 和 `OMP_PROC_BIND=false`（关键！）
2. 移除 `OMP_NUM_THREADS=1`，改为 `opt.num_threads = 4`
3. 在JNI_OnLoad中提前初始化OMP并捕获SIGABRT
4. 设置 `opt.openmp_blocktime = 0`（降低spin wait）
5. 设置 `OMP_WAIT_POLICY=PASSIVE` 降低CPU占用

**OMP SIGABRT根因**：MediaTek SoC上OpenMP的KMP_AFFINITY默认设置与天玑全大核/非对称架构不兼容。NDK已知问题：https://github.com/android/ndk/issues/1028

### NCNN CPU vs Vulkan性能对比（nano级模型）

| 维度 | 纯CPU | Vulkan (GPU) |
|------|-------|-------------|
| yolo26n-pose-160 | ~7ms (1线程) | 可能15-30ms |
| yolo26n-pose-320 | ~15-25ms (估计) | 可能20-40ms |
| 大模型(640x640) | 50-100ms | 30-50ms |
| 启动延迟 | 低 | 高（需编译shader） |
| 内存 | 低 | 高（需GPU显存） |

**结论**：nano级pose模型(≤320)，CPU路线正确。GPU仅640x640大模型才有优势。

### NCNN fp16/int8在ARMv9上的支持

| 特性 | Cortex-X4/A720支持 | NCNN默认 |
|------|-------------------|---------|
| fp16_storage | ✅ ARMv8.2-A FP16 | 开启 |
| fp16_packed | ✅ NEON FP16向量 | 开启 |
| int8_storage | ✅ ARMv8.2-A DOT指令 | 开启 |
| fp16_arithmetic | ✅ ARMv8.2-A | 关闭（精度风险） |

### H.264传输端到端延迟分析

| 环节 | 延迟 | 说明 |
|------|------|------|
| PC端DXGI截屏 | 1-3ms | Desktop Duplication |
| PC端H.264编码 | 5-15ms | NVENC/AMF硬件编码 |
| 网络传输(KCP/UDP) | 1-5ms | 局域网USB共享网络 |
| Android MediaCodec解码 | 5-15ms | 天玑9300硬件解码 |
| YUV→BGRA转换 | 2-5ms | 当前Java软件转换（瓶颈！） |
| **总延迟** | **14-43ms** | |

**关键瓶颈**：`H264Decoder.imageToBgra()` 是纯Java逐像素YUV420→BGRA转换。优化方案：
1. C++ NEON指令做YUV420→BGRA（预期提速5-10x）
2. MediaCodec Surface输出模式，跳过CPU转换
3. 直接传YUV给NCNN（需修改NCNN输入格式）

### UDP vs KCP选择

| 场景 | 推荐 | 理由 |
|------|------|------|
| USB共享网络 | UDP | 丢包率<0.1%，延迟更低 |
| WiFi局域网 | KCP | 丢包率1-5%，KCP自动重传 |

### 蓝牙HID注意事项

- HID报告格式：4字节 `[buttons, X, Y, Wheel]`，X/Y范围-127~127
- 设备名当前为"PoseMouse"，建议改为常见鼠标品牌名
- Android 14需运行时请求 `BLUETOOTH_CONNECT` 权限
- 前台服务类型必须指定 `connectedDevice`

### yolo26n-pose模型信息

- "yolo26"是YOLO系列最新命名（年份制），nano级最小尺寸
- 架构：C2f backbone + Pose head，与YOLOv8-pose/YOLO11-pose基本一致
- 输出：56通道 (4 bbox + 1 obj_conf + 17×3 keypoints)，2100 anchors (320×320)
- COCO 17关键点：0-4头部, 5-6肩膀, 7-8肘部, 9-10手腕, 11-12髋部, 13-14膝盖, 15-16脚踝

### 各框架在天玑9300上的对比

| 框架 | 适配性 | CPU性能 | 优势 | 劣势 |
|------|--------|---------|------|------|
| NCNN CPU | 优秀 | ~7ms@160 | 无依赖、fp16/int8 | OMP问题 |
| MNN | 优秀 | 接近NCNN | 阿里维护、ARM优化 | 社区小 |
| TFLite/LiteRT | 需NeuronDelegate | 较慢 | Google官方 | 天玑CPU路径慢 |
| ONNX Runtime | 一般 | 较慢 | 兼容性好 | 移动端优化不足 |
| NeuronDirect NPU | 最佳 | 最快 | 直接调天玑NPU | bytecode复杂 |

---

## 进展记录

### [ ] 阶段0：项目骨架搭建
- [ ] 创建Android项目目录结构
- [ ] 配置build.gradle.kts (移除litert/onnxruntime依赖)
- [ ] 配置CMakeLists.txt (仅posemouse_ncnn target，指向纯CPU版NCNN)
- [ ] 复制NCNN纯CPU预编译库到cpp目录
- [ ] 复制yolo26n-pose模型到assets
- [ ] 配置AndroidManifest.xml
- [ ] 验证：项目可在Android Studio中打开并sync成功

### [ ] 阶段1：核心代码移植
- [ ] 移植InferenceEngine.kt (接口+数据类)
- [ ] 移植NcnnEngine.kt (仅保留NCNN CPU逻辑)
- [ ] 移植posemouse_ncnn_jni.cpp + ikcp.c/h
- [ ] 移植InferenceLoop.kt
- [ ] 移植BluetoothHidMouse.kt
- [ ] 移植AdaptivePidController.kt
- [ ] 移植BodyParts.kt
- [ ] 移植UdpFrameReceiver.kt + H264Decoder.kt
- [ ] 移植ScreenCapture.kt
- [ ] 移植AppLog.kt
- [ ] 移植InferenceService.kt (简化引擎选择)
- [ ] 移植InferenceServiceHolder.kt
- [ ] 移植LowTextureSuppressor.kt
- [ ] 移植KalmanMultiTracker.kt
- [ ] 移植UdpTargetSender.kt
- [ ] 验证：编译通过，生成APK

### [ ] 阶段2：UI移植
- [ ] 移植MainActivity.kt (简化引擎选择UI)
- [ ] 移植res资源文件
- [ ] 验证：UI正常显示

### [ ] 阶段3：功能验证
- [ ] 蓝牙HID连接测试
- [ ] NCNN CPU推理测试 (截屏模式)
- [ ] UDP/KCP帧接收测试
- [ ] H.264解码测试
- [ ] 完整链路测试 (OBS→KCP→NCNN→PID→蓝牙HID)
- [ ] 性能基准测试

### [ ] 阶段4：优化
- [ ] 全局变量消除 (按"全局变量消除方案.md"执行)
- [ ] NCNN多线程探索 (1线程→4线程+大核绑定)
- [ ] 卡尔曼跟踪器集成到InferenceLoop
- [ ] H264Decoder YUV→BGRA NEON优化
- [ ] 蓝牙HID设备名伪装

---

## 遇到的问题

### 问题#1: NCNN OpenMP SIGABRT
- **日期**: 原项目已遇到
- **现象**: NCNN多线程推理时，OMP affinity导致SIGABRT崩溃
- **原因**: MediaTek天玑CPU拓扑与Intel OpenMP runtime默认affinity策略不兼容
- **当前解决**: 强制单线程 + `KMP_AFFINITY=disabled` + sigsetjmp捕获SIGABRT
- **待验证**: 4线程 + `KMP_AFFINITY=disabled` + `OMP_PROC_BIND=false` 是否安全
- **状态**: 绕过（单线程），待优化

### 问题#2: H264Decoder YUV→BGRA转换慢
- **日期**: 原项目已知
- **现象**: Java逐像素YUV420→BGRA转换耗时2-5ms
- **原因**: 纯Java实现，无NEON/硬件加速
- **解决**: 待实现C++ NEON优化或Surface直出
- **状态**: 未解决

### 问题#3: KalmanMultiTracker已实现但未集成
- **日期**: 原项目遗留
- **现象**: KalmanMultiTracker有完整实现但InferenceLoop未使用
- **原因**: 原项目优先级未到
- **解决**: 待集成到InferenceLoop的processPoses流程
- **状态**: 待实现

---

## 性能基准

| 配置 | 推理延迟 | FPS | 备注 |
|------|---------|-----|------|
| yolo26n-pose-160, NCNN CPU, 1线程 | ~7ms | ~140 | 原项目数据 |
| yolo26n-pose-320, NCNN CPU, 1线程 | ~15-25ms(估) | ~40-67(估) | 面积4倍于160 |
| yolo26n-pose-320, NCNN CPU, 4线程 | 待测 | 待测 | 大核绑定策略 |
| yolo26n-pose-320, NCNN CPU, 8线程 | 待测 | 待测 | 全核策略 |

---

## 关键决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| | 包名改为com.CPoseMouse | 与原NPU项目完全隔离 |
| | 使用纯CPU版NCNN | 无Vulkan依赖，APK更小，nano模型CPU更快 |
| | 仅保留yolo26n-pose模型 | CPU路线不需要tflite/onnx |
| | NCNN默认1线程 | 原项目因OMP SIGABRT而限制，后续可尝试4线程+大核绑定 |
| | USB共享网络场景用UDP | 丢包率<0.1%，延迟比KCP低1-2ms |
| | WiFi场景用KCP | 丢包率1-5%，KCP自动重传更稳定 |
| | 蓝牙HID设备名需伪装 | "PoseMouse"易被反作弊标记 |

---

## 已清理的无关文件

以下文件已从 `Pousemouse-main-extracted` 中移除（仅CPU路线不需要）：

**assets（移除19个）**：所有 yolo11n-* 文件、yolo26s-pose-*.tflite、yolo26s-pose-160 NCNN文件

**jniLibs（移除3个）**：libLiteRt.so、libLiteRtCompilerPlugin_MediaTek.so、libLiteRtDispatch_MediaTek.so

**Kotlin（移除5个）**：LiteRtEngine.kt、NeuronDirectEngine.kt、OnnxEngine.kt、TfliteNnapiEngine.kt.bak、TfliteNnapiEngine.kt.disabled

**C++（移除3项）**：neuron_direct_jni.cpp、neuropilot_include/目录、ncnn-20260526-android-vulkan/目录
