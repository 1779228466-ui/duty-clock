# CPU推理方案 — 进展与问题记录

## 方案概述

在 `D:\HuaWei\CPU_pose` 下创建完全独立的CPU推理Android项目，基于NCNN+CPU引擎，**唯一鼠标算法为自适应PID**，不修改以下原始项目：
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
| 模型 | yolo26s-pose-320 (NCNN param/bin, 11.8M参数) |
| 输入尺寸 | 320×320 (默认) |
| JNI库 | libcposemouse_ncnn.so (NCNN+KCP) |
| NCNN预编译库 | ncnn-20260526-android (纯CPU版，无Vulkan) |
| NCNN线程数 | 4 (绑高频簇CPU4-7) |
| NCNN优化 | FP16存储+算术, openmp_blocktime=0 |
| **鼠标算法** | **AdaptivePidController (自适应PID) — 唯一算法** |
| 鼠标控制 | BluetoothHidMouse (蓝牙HID) |
| 帧传输 | UDP/KCP (H.264编码) |
| 语言 | Kotlin + C++ (JNI) |

---

## 自适应PID算法详解（唯一鼠标算法）

### 算法架构

```
目标点坐标 → 自适应EMA平滑 → 误差计算(目标-画面中心) → 自适应PID → 速度前馈叠加 → 输出限幅 → 蓝牙HID鼠标移动
```

### 核心模块

#### 1. AdaptivePidController（自适应PID）

**参数（PC端验证最优值）**：

| 参数 | X轴 | Y轴 | 说明 |
|------|-----|-----|------|
| Kp | 1.0 | 0.3 | 比例增益，X轴需更高因水平移动更频繁 |
| Ki | 0.027 | 0.01 | 积分增益，消除稳态误差 |
| Kd | 0.02 | 0.02 | 微分增益，抑制超调 |
| output_limit | 50 | 50 | 单次最大移动像素 |
| integral_limit | 100 | 100 | 积分累积上限 |
| dead_zone | 0.12 | 0.12 | 死区，低于此误差不输出 |
| integral_deadzone | 0.1 | 0.1 | 积分输出死区 |
| integral_gain_threshold | 80 | 80 | 自适应积分阈值 |
| integral_gain_rate | 0.027 | 0.027 | 自适应积分增长率 |

**自适应积分机制**：
- 小误差（`|error| < threshold`）：积分增益缓慢增长，消除稳态误差
- 大误差：积分增益按tanh衰减，防止积分饱和/超调
- 误差变化率大时：降低积分增长率，防止过冲

**Android端当前参数 vs PC端最优参数**：

| 参数 | Android当前 | PC最优 | 差异原因 |
|------|-----------|--------|---------|
| xKp | 0.3 | 1.0 | 蓝牙HID延迟>PC Interception，需降低 |
| yKp | 0.3 | 0.3 | 一致 |
| xKi | 0.01 | 0.027 | 蓝牙HID场景积分可稍增 |
| yKi | 0.01 | 0.01 | 一致 |
| deadZone | 0.3 | 0.12 | 蓝牙HID抖动更明显，需更大死区 |
| outputLimit | 50 | 50 | 一致 |

**策略**：初始使用Android当前参数，后续通过AutoTuner自动优化。

#### 2. 自适应EMA平滑（待移植）

关键点输出后、PID输入前的平滑层：

| 目标移动距离 | EMA alpha | 效果 |
|-------------|-----------|------|
| > 20 px | 0.9 | 弱平滑，快速跟随 |
| 5-20 px | 0.6 | 中等平滑 |
| < 5 px | 0.2 | 强平滑，去抖动 |

**实现位置**：在 `BodyParts.computeTarget()` 输出后、`InferenceLoop.processAdaptivePid()` 输入前。

#### 3. 速度前馈（待移植）

```
速度 = (当前目标屏幕坐标 - 上帧目标屏幕坐标) / dt
前馈量 = 速度 * dt * 0.3
```

前馈增益0.3，叠加到PID输出上，减少快速移动时的跟踪滞后。

**实现位置**：在 `InferenceLoop` 中计算目标点位移，叠加到 `mouse.move()` 的dx/dy上。

#### 4. AutoTuner自动调参器（待移植）

- 滑动窗口300帧，每5秒评估一次
- **振荡检测**（osc>0.15）：降Kp/Ki，增Kd/dead_zone
- **滞后检测**（bias>target*0.5）：增Kp/Ki，降dead_zone
- **最优回退**：连续3次振荡+3次滞后交替时，回退到历史最优参数
- **参数安全边界**：Kp≤2.0, Ki≤0.15, Kd≤0.5, outputLimit≤127

#### 5. 射击控制（待移植）

- **持续模式**：目标存在时按住左键
- **点射模式**：随机化按下/释放时间(130ms~286ms/40ms~100ms)
- 通过蓝牙HID的 `clickLeft()` / `clickLeftDown()` / `clickLeftUp()` 实现

### 不采用的算法及理由

| 算法 | 不采用理由 |
|------|----------|
| Alpha-Beta PD | 自适应PID已包含其前导补偿思想，且更简洁；参数更多调参困难 |
| HxPdController | 简单PD，无积分项，无法消除稳态误差 |
| 数物卡尔曼 | 依赖networkx二部图匹配，Android端需替换为匈牙利算法；复杂度高，收益不确定 |
| Chimera混合 | 参数过多(9个)，调参复杂；自适应PID已足够稳定 |

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
│       │   │   ├── InferenceLoop.kt         (推理循环+速度前馈)
│       │   │   ├── BluetoothHidMouse.kt     (蓝牙HID)
│       │   │   ├── AdaptivePidController.kt (自适应PID — 唯一鼠标算法)
│       │   │   ├── AdaptiveEmaSmoother.kt   (自适应EMA平滑)
│       │   │   ├── AutoTuner.kt             (自动调参器)
│       │   │   ├── FireController.kt        (射击控制)
│       │   │   ├── BodyParts.kt             (瞄准部位)
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

**与原项目结构差异**：
- ❌ 移除 `AlphaBetaPDController.kt`、`HxPdController.kt`、`KalmanMultiTracker.kt`
- ✅ 新增 `AdaptiveEmaSmoother.kt`、`AutoTuner.kt`、`FireController.kt`

---

## 与原项目的差异

| 维度 | 原项目 (PMouse) | CPU项目 (CPoseMouse) |
|------|-----------------|---------------------|
| 包名 | com.PMouse | com.CPoseMouse |
| 引擎 | 4种(ncnn/vulkan/litert/neuron) | 仅ncnn_cpu |
| 鼠标算法 | 3种(APID/α-β PD/HxPD) | 仅自适应PID |
| NCNN库 | Vulkan版 (含glslang) | 纯CPU版 (无Vulkan依赖) |
| jniLibs | 3个LiteRT SO | 空 |
| Gradle依赖 | litert + onnxruntime | 无额外ML依赖 |
| CMake targets | 2个(posemouse_ncnn + neuron_direct) | 1个(posemouse_ncnn) |
| 模型文件 | 全部(含tflite/onnx/yolo11n) | 仅yolo26n-pose NCNN格式 |
| EMA平滑 | 无 | 自适应EMA（从PC端移植） |
| 速度前馈 | 无 | 有（从PC端移植） |
| 自动调参 | 无 | AutoTuner（从PC端移植） |
| 射击控制 | 无 | FireController（从PC端移植） |
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

**结论**：nano级pose模型(≤320)，CPU路线正确。

### H.264传输端到端延迟分析

| 环节 | 延迟 | 说明 |
|------|------|------|
| PC端DXGI截屏 | 1-3ms | Desktop Duplication |
| PC端H.264编码 | 5-15ms | NVENC/AMF硬件编码 |
| 网络传输(KCP/UDP) | 1-5ms | 局域网USB共享网络 |
| Android MediaCodec解码 | 5-15ms | 天玑9300硬件解码 |
| YUV→BGRA转换 | 2-5ms | 当前Java软件转换（瓶颈！） |
| **总延迟** | **14-43ms** | |

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

### yolo26s-pose模型信息

- small级，参数量11.8M（nano级2.3M的5倍）
- GFLOPs: 23.9 (320×320)
- 精度显著高于nano：COCO pose AP约高5-8个点
- 代价：推理时间约为nano的5-6倍

---

## CPU推理优化方案（保守方案）

> 目标：yolo26s-pose-320 从125ms降到30ms以下

### 已实施的优化

| 优化项 | 具体做法 | 预期加速 |
|--------|---------|---------|
| 4线程+绑核 | `num_threads=4`, `sched_setaffinity`绑CPU4-7(X4+3×A720@2.85G) | 3-3.5x |
| FP16算术 | `use_fp16_arithmetic=true`，X4/A720原生FP16 FMLA | 1.3-1.5x |
| OMP优化 | `openmp_blocktime=0`, `OMP_WAIT_POLICY=PASSIVE` | 减少spin wait |
| 环境变量 | `KMP_AFFINITY=disabled`, `OMP_PROC_BIND=false` | 防止SIGABRT |

### 后备优化（如保守方案不达标）

| 优化项 | 预期加速 | 难度 |
|--------|---------|------|
| INT8量化(混合精度) | 2-3x | 中 |
| 自编译NCNN(armv8.2+fp16+dotprod) | 1.1-1.2x | 中 |
| 降低分辨率(320→256) | 1.5-2x | 低 |
| YUV→RGB NEON优化 | 节省2-5ms | 中 |

---

## 进展记录

### [x] 阶段0：项目骨架搭建
- [x] 创建Android项目目录结构
- [x] 配置build.gradle.kts (移除litert/onnxruntime依赖)
- [x] 配置CMakeLists.txt (仅cposemouse_ncnn target，指向纯CPU版NCNN)
- [x] 复制NCNN纯CPU预编译库到cpp目录
- [x] 复制yolo26n-pose模型到assets
- [x] 配置AndroidManifest.xml
- [x] 验证：assembleDebug编译通过(BUILD SUCCESSFUL)
- [x] 推送到GitHub: https://github.com/1779228466-ui/cpu_mouse

### [x] 阶段1：核心代码移植
- [x] 移植全部Kotlin核心文件（13个）+ InferenceEngine接口
- [x] 移植C++ JNI（cposemouse_ncnn_jni.cpp + ikcp.c/h）
- [x] 移植InferenceService + MainActivity + UI theme
- [x] 包名改为com.CPoseMouse，JNI函数名适配
- [x] 移除litert/neuron_direct/Vulkan引擎，仅NCNN+CPU
- [x] 移除AlphaBetaPD/HxPD/KalmanMultiTracker，仅自适应PID
- [x] 代码规范性检查并修复41个问题（24个已修复）
- [x] 验证：编译通过，安装到手机运行正常

### [x] 阶段2：模型替换与安装测试
- [x] 替换模型为yolo26s-pose-320（ultralytics导出NCNN格式）
- [x] 默认inputSize改为320
- [x] 安装测试：NCNN模型加载成功，蓝牙HID自动连接PC
- [x] Benchmark基线：yolo26s-pose-320, 1线程FP32, **125ms/帧(8 FPS)**

### [ ] 阶段3：CPU推理性能优化（保守方案）
- [x] 验证天玑9300 CPU拓扑：CPU0-3=A720@2.0G, CPU4-6=A720@2.85G, CPU7=X4@3.25G
- [x] 启用4线程 + sched_setaffinity绑高频簇(CPU4-7) + openmp_blocktime=0
- [x] 启用FP16算术(use_fp16_arithmetic=true)，X4/A720原生FP16 FMLA
- [x] 移除OMP_NUM_THREADS=1限制，保留KMP_AFFINITY=disabled
- [ ] 实测benchmark（待设备重连）
- [ ] 如不达标：INT8量化 / 自编译NCNN / 降低分辨率

### [ ] 阶段4：PC端增强功能移植
- [ ] 新增AdaptiveEmaSmoother.kt (自适应EMA平滑，从PC端移植)
- [ ] 新增AutoTuner.kt (自动调参器，从PC端移植)
- [ ] 新增FireController.kt (射击控制，从PC端移植)
- [ ] InferenceLoop集成速度前馈

### [ ] 阶段5：功能验证
- [ ] 蓝牙HID连接测试
- [ ] NCNN CPU推理测试 (截屏模式)
- [ ] UDP/KCP帧接收测试
- [ ] H.264解码测试
- [ ] 完整链路测试 (OBS→KCP→NCNN→自适应PID→蓝牙HID)
- [ ] 性能基准测试
- [ ] AutoTuner自动调参验证

### [ ] 阶段6：深度优化
- [ ] 全局变量消除 (按"全局变量消除方案.md"执行)
- [ ] H264Decoder YUV→BGRA NEON优化
- [ ] INT8量化（混合精度，backbone INT8 + pose head FP32）
- [ ] 自编译NCNN（-march=armv8.2-a+fp16+dotprod -O3 -flto）
- [ ] 蓝牙HID设备名伪装
- [ ] 自适应PID参数微调（基于AutoTuner实测数据）

---

## 遇到的问题

### 问题#1: NCNN OpenMP SIGABRT
- **日期**: 原项目已遇到
- **现象**: NCNN多线程推理时，OMP affinity导致SIGABRT崩溃
- **原因**: MediaTek天玑CPU拓扑与Intel OpenMP runtime默认affinity策略不兼容
- **当前解决**: `KMP_AFFINITY=disabled` + `OMP_PROC_BIND=false` + `OMP_WAIT_POLICY=PASSIVE` + sigsetjmp捕获SIGABRT
- **已验证**: 4线程 + 上述环境变量 + sched_setaffinity绑高频簇，编译通过
- **状态**: 已从1线程升级到4线程，待实测验证

### 问题#4: yolo26s-pose-320 CPU推理太慢
- **日期**: 2026-06-29
- **现象**: yolo26s-pose-320在1线程FP32下125ms/帧(8 FPS)，无法实时跟踪
- **原因**: yolo26s参数量11.8M（yolo26n仅2.3M），320分辨率计算量大
- **优化方案（保守方案，预期3-5x加速）**:
  1. ✅ 4线程 + sched_setaffinity绑高频簇(CPU4-7: X4@3.25G + 3×A720@2.85G)
  2. ✅ FP16算术(use_fp16_arithmetic=true)，X4/A720原生FP16 FMLA指令
  3. ✅ openmp_blocktime=0 + OMP_WAIT_POLICY=PASSIVE
  4. ⏳ INT8量化(混合精度) — 如保守方案不达标
  5. ⏳ 自编译NCNN(-march=armv8.2-a+fp16+dotprod) — 如保守方案不达标
- **预期**: 125ms → 25-35ms (4线程+FP16)
- **状态**: 代码已修改，编译通过，待实测

### 天玑9300 CPU拓扑（实测确认）
- CPU0-3: Cortex-A720 @ 2.0GHz（中频簇）
- CPU4-6: Cortex-A720 @ 2.85GHz（高频簇）
- CPU7: Cortex-X4 @ 3.25GHz（超大核）
- 全大核架构，无小核，`set_cpu_powersave(1)`无效
- sched_setaffinity绑定CPU4-7为推理线程最优选择

### 问题#2: H264Decoder YUV→BGRA转换慢
- **日期**: 原项目已知
- **现象**: Java逐像素YUV420→BGRA转换耗时2-5ms
- **原因**: 纯Java实现，无NEON/硬件加速
- **解决**: 待实现C++ NEON优化或Surface直出
- **状态**: 未解决

### 问题#3: 自适应PID参数需针对蓝牙HID调整
- **日期**: 方案制定时发现
- **现象**: PC端最优参数(Kp=1.0, dead_zone=0.12)可能不适用于蓝牙HID场景
- **原因**: 蓝牙HID有额外延迟(~8-15ms)和量化误差(-127~127)，PC端Interception驱动延迟<1ms
- **解决**: 初始使用Android当前参数，通过AutoTuner自动优化
- **状态**: 待验证

---

## 代码规范性检查报告

> 检查日期：2026-06-29，基于编译通过的初始骨架代码

### 高严重度问题（7个）

| 编号 | 文件 | 行号 | 问题 | 状态 |
|------|------|------|------|------|
| H-01 | UdpFrameReceiver.kt / cposemouse_ncnn_jni.cpp | 322 / 597 | JNI风格不一致：UdpFrameReceiver用实例方法+`jobject thiz`操作全局状态，应改`@JvmStatic companion object`+`jclass` | ✅已修复 |
| H-02 | InferenceLoop.kt | 89 | **功能Bug**：`detector.detect(cropped, 0.6f)`硬编码0.6f，未用`confThreshold`，用户调整置信度对截屏模式无效 | ✅已修复 |
| H-03 | BluetoothHidMouse.kt | 18-19, 300-335 | `connectedDevice`/`hidDevice`非`@Volatile`，callback线程写、推理线程读，存在可见性竞态 | ✅已修复 |
| H-04 | BluetoothHidMouse.kt | 351-360 | `instance`在`init`中赋值，多实例创建会互相覆盖 | ✅已修复 |
| H-05 | InferenceLoop.kt | 29-34, 199 | `reusableBitmap`/`inferBitmap`/`latestFrameBitmap`未`recycle()`，长时间运行内存泄漏 | ✅已修复 |
| H-06 | UdpTargetSender.kt | 9 | 硬编码IP `"10.181.9.68"`，换网络即失效 | ✅已修复 |
| H-07 | cposemouse_ncnn_jni.cpp | 99-465 | 16个static全局变量，缺乏封装 | ⏳阶段4重构 |

### 中严重度问题（19个）

| 编号 | 文件 | 行号 | 问题 | 状态 |
|------|------|------|------|------|
| M-01 | MainActivity.kt | 56-67 | 顶层私有变量`BgColor`等应为camelCase | ✅已修复 |
| M-02 | BluetoothHidMouse.kt | 49 | `MOUSE_DESCRIPTOR`应为`mouseDescriptor` | ✅已修复 |
| M-03 | InferenceLoop.kt | 41 | `SKELETON_CONNECTIONS`应为`skeletonConnections` | ✅已修复 |
| M-04 | H264Decoder.kt | 108 | `MAX_CONSECUTIVE_FAILURES`应为`maxConsecutiveFailures` | ✅已修复 |
| M-05 | 多文件 | - | 硬编码端口号/魔法数字 | ✅已修复 |
| M-06 | InferenceService.kt | 141 | `defaultDisplay`废弃API | ✅已修复 |
| M-07 | InferenceService.kt | 62 | `getParcelableExtra`泛型形式废弃 | ✅已修复 |
| M-08 | ScreenCapture.kt | 92 | `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`废弃 | ⏳需API 33+替代 |
| M-09 | NcnnEngine.kt | 89-109 vs 137-157 | `detect()`和`detectRawRgba()`结果解析逻辑重复 | ✅已修复 |
| M-10 | cposemouse_ncnn_jni.cpp | 248-261等 | 三个detect函数结果提取循环重复 | ✅已修复 |
| M-11 | UdpFrameReceiver.kt | 104-133 vs 241-271 | `processJpegFrame`和`processJpegFrameInline`逻辑重复 | ✅已修复 |
| M-12 | H264Decoder.kt | 259-298 | `yuv420ToBgra()`从未被调用（死代码） | ✅已修复 |
| M-13 | InferenceService.kt | 74-120 vs 122-182 | `startUdpMode`和`startCaptureMode`初始化代码重复 | ✅已修复 |
| M-14 | 多文件 | - | 多处`catch (_: Exception) {}`静默吞没异常 | ✅已修复 |
| M-15 | AppLog.kt | 27, 137, 162 | `logFileWriter`无同步保护 | ✅已修复 |
| M-16 | H264Decoder.kt | 10 | `codec`字段无同步保护 | ⏳单线程调用，低优先 |
| M-17 | MainActivity.kt | 195 | `@SuppressLint("SetJavaScriptEnabled")`遗留注解 | ✅已修复 |
| M-18 | MainActivity.kt | 197-211等 | 函数参数过多(20+个) | ⏳需重构UI状态管理 |
| M-19 | InferenceService.kt | 224 | `typealias Activity`位置不当 | ✅已修复 |

### 低严重度问题（15个）

| 编号 | 问题 | 状态 |
|------|------|------|
| L-01 | 包名`CPoseMouse`用PascalCase，惯例应全小写 | ⏳重构成本高，暂保持 |
| L-02 | 缺`kotlinOptions { jvmTarget = "11" }` | ✅已修复 |
| L-03 | `compileSdk`使用非标准块语法 | ⏳需确认AGP 9.x兼容性 |
| L-04 | `useLegacyPackaging = true`可能不必要 | ⏳待测试移除 |
| L-05 | 单行多语句(分号分隔) | ✅已修复 |
| L-06 | 超长行(300+字符) | ✅已修复 |
| L-07 | 缩进不一致 | ✅已修复 |
| L-08 | Color.kt定义的Compose Color未使用 | ⏳低优先 |
| L-09 | C++ SIGABRT处理机制hacky | ⏳阶段4优化 |
| L-10 | C++栈上分配64KB缓冲区 | ✅已修复 |
| L-11 | ikcp.c vsprintf缓冲区溢出风险 | ✅已修复 |
| L-12 | 通配符import | ✅已修复 |
| L-13 | ScreenCapture.getDensityDpi始终返回默认值 | ⏳低优先 |
| L-14 | InferenceLoop冗余Bitmap创建 | ✅已修复 |
| L-15 | InferenceService未关闭UdpTargetSender | ✅已修复 |

---

## 性能基准

| 配置 | 推理延迟 | FPS | 备注 |
|------|---------|-----|------|
| yolo26n-pose-160, NCNN CPU, 1线程 | ~7ms | ~140 | 原项目数据 |
| yolo26n-pose-320, NCNN CPU, 1线程 | ~15-25ms(估) | ~40-67(估) | 面积4倍于160 |
| yolo26s-pose-320, NCNN CPU, 1线程FP32 | **125ms** | **8** | **实测，当前基线** |
| yolo26s-pose-320, NCNN CPU, 4线程+FP16 | ~25-35ms(估) | ~28-40(估) | 待实测 |

---

## 关键决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| | 包名改为com.CPoseMouse | 与原NPU项目完全隔离 |
| | 使用纯CPU版NCNN | 无Vulkan依赖，APK更小，nano模型CPU更快 |
| | 仅保留yolo26n-pose模型 | CPU路线不需要tflite/onnx |
| | **唯一鼠标算法：自适应PID** | PC端验证最优(Kp=1.0/0.3, 误差<5px)；其他算法增加复杂度但收益有限 |
| | 移除AlphaBetaPD/HxPD/KalmanMultiTracker | 算法统一减少维护成本；自适应PID+EMA+前馈已覆盖所有需求 |
| | 移植自适应EMA平滑 | 解决关键点抖动问题，PC端已验证有效 |
| | 移植速度前馈 | 减少快速移动时跟踪滞后，PC端已验证有效 |
| | 移植AutoTuner | 自动优化PID参数，避免手动调参 |
| | 移植FireController | 射击控制是核心功能 |
| | NCNN默认1线程 | 原项目因OMP SIGABRT而限制，后续可尝试4线程+大核绑定 |
| | NCNN升级到4线程+FP16+绑高频簇 | 保守方案预期3-5x加速(125ms→25-35ms) |
| | 替换模型为yolo26s-pose-320 | 精度显著高于nano，代价是推理慢5-6x |
| | USB共享网络场景用UDP | 丢包率<0.1%，延迟比KCP低1-2ms |
| | WiFi场景用KCP | 丢包率1-5%，KCP自动重传更稳定 |
| | 蓝牙HID设备名需伪装 | "PoseMouse"易被反作弊标记 |

---

## PC端算法参考（pc-dxgi-yolo- 项目）

已提取到 `D:\HuaWei\CPU_pose\pc-dxgi-yolo-algorithms\`，包含4种控制算法和完整辅助模块。

### 4种控制算法对比（仅自适应PID被采用）

| 算法 | 核心思想 | 是否采用 | 理由 |
|------|---------|---------|------|
| **自适应PID** | **自适应积分增益+死区+限幅** | **✅ 唯一采用** | **PC端验证最优，简单有效** |
| Alpha-Beta PD | α-β滤波器平滑+前导补偿+PID | ❌ | 自适应PID已含前导思想，更简洁 |
| 数物卡尔曼 | 6维卡尔曼滤波+二部图匹配 | ❌ | 依赖networkx，复杂度高 |
| Chimera混合 | 归一化误差+接近度滤波+阻尼 | ❌ | 参数过多(9个)，调参困难 |

### PC端最优参数（自适应PID，auto_tune达标时平均误差<5px）

| 参数 | X轴 | Y轴 |
|------|-----|-----|
| Kp | 1.0 | 0.3 |
| Ki | 0.027 | 0.01 |
| Kd | 0.02 | 0.02 |
| output_limit | 50 | 50 |
| dead_zone | 0.12 | 0.12 |

### 速度前馈

```
速度 = (当前屏幕坐标 - 上帧屏幕坐标) / dt
前馈量 = 速度 * dt * 0.3
```

### 自适应EMA平滑

| 目标移动距离 | EMA alpha | 效果 |
|-------------|-----------|------|
| > 20 px | 0.9 | 弱平滑，快速跟随 |
| 5-20 px | 0.6 | 中等平滑 |
| < 5 px | 0.2 | 强平滑，去抖动 |

### 自动调参器

- 滑动窗口300帧，每5秒评估一次
- 振荡(osc>0.15): 降Kp/Ki，增Kd/dead_zone
- 滞后(bias>target*0.5): 增Kp/Ki，降dead_zone
- 连续3次振荡+3次滞后交替: 回退到历史最优参数
- 参数安全边界: Kp≤2.0, Ki≤0.15, Kd≤0.5

### 射击控制

- 持续模式: 目标存在时按住左键
- 点射模式: 随机化按下/释放时间(130ms~286ms/40ms~100ms)

---

## 已清理的无关文件

以下文件已从 `Pousemouse-main-extracted` 中移除（仅CPU路线不需要）：

**assets（移除19个）**：所有 yolo11n-* 文件、yolo26s-pose-*.tflite、yolo26s-pose-160 NCNN文件

**jniLibs（移除3个）**：libLiteRt.so、libLiteRtCompilerPlugin_MediaTek.so、libLiteRtDispatch_MediaTek.so

**Kotlin（移除5个）**：LiteRtEngine.kt、NeuronDirectEngine.kt、OnnxEngine.kt、TfliteNnapiEngine.kt.bak、TfliteNnapiEngine.kt.disabled

**C++（移除3项）**：neuron_direct_jni.cpp、neuropilot_include/目录、ncnn-20260526-android-vulkan/目录

**后续需移除（原项目有但本项目不需要）**：AlphaBetaPDController.kt、HxPdController.kt、KalmanMultiTracker.kt
