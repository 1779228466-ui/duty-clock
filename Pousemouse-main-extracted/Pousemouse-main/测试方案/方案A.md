# 方案A：修复LiteRT Runtime，让NPU完整推理链路正常工作

## 版本：v0.4.0
## 更新时间：2026-06-28

---

## 零、工作原则

1. **从根源解决问题**：不头痛医头脚痛医脚，找到问题的根本原因后从源头修复
2. **先完整理解再动手**：不追求捷径，出现代码问题先通读整篇代码理解，接着通读整篇与错误相关的代码章节
3. **选最稳妥的方法**：不选看起来最快的方法，选只要坚持走下去就一定能解决问题的方法
4. **一个错误不能用另一个错误去弥补**：每一步修复都必须基于对代码的完整理解，不能用hack绕过问题

---

## 一、目标

让天玑9300 NPU通过LiteRT runtime正确执行yolo26s-pose-320-int8.tflite模型，FPS>60

## 二、原理

1. LiteRT runtime（`libLiteRt.so`）加载tflite模型后，调用MediaTek编译器插件将模型编译成15个NPU partition的bytecode
2. 每个partition是独立的NPU子任务，由dispatch库（`libLiteRtDispatch_MediaTek.so`）调度执行
3. partition间用NeuronMemory（DMA-BUF/AHWB）零拷贝传递中间结果
4. 整个过程对APP透明——Kotlin代码只需调`CompiledModel.create()` + `compiledModel.run()`

### 15个Partition结构

| Partition | 输入数 | 输出数 | 功能 | 输入shape | 输出shape |
|-----------|--------|--------|------|-----------|-----------|
| P0 | 1 | 6 | backbone前半段 | [1,320,320,3] FP32 | 6个特征图INT8 |
| P1 | 6 | 9 | backbone/neck后半段 | 6个特征图 | 9个特征图 |
| P2 | 9 | 4 | head推理 | 9个特征图 | 4个预测结果 |
| P3-P14 | 1-3 | 1-3 | 后处理(NMS等) | 小tensor | 最终[1,300,57] |

**P0-P2是神经网络推理**，P3-P14是后处理。我们CPU端已有自己的后处理代码，所以只需要P0-P2的输出就够了。

### DAG映射关系

```
P0.out → P1.in:  kDagMapping[1] = {{0,5},{0,4},{0,3},{0,2},{0,1},{0,0}}
  P1.in[0] ← P0.out[5]  (T438: scale=0.05584275, zp=27)
  P1.in[1] ← P0.out[4]  (T436: scale=0.04713068, zp=33)
  P1.in[2] ← P0.out[3]  (T424: scale=0.04334762, zp=-34)
  P1.in[3] ← P0.out[2]  (T423: scale=0.04334762, zp=-34)
  P1.in[4] ← P0.out[1]  (T373: scale=0.01226816, zp=-105)
  P1.in[5] ← P0.out[0]  (T336: scale=0.01303506, zp=-107)

P1.out → P2.in:  kDagMapping[2] = {{1,8},{1,7},{1,6},{1,4},{1,5},{1,1},{1,3},{1,2},{1,0}}
  P2.in[0] ← P1.out[8]  (T620: scale=0.04567503, zp=0)
  P2.in[1] ← P1.out[7]  (T618: scale=0.03170529, zp=0)
  P2.in[2] ← P1.out[6]  (T606: scale=0.02276533, zp=-104)
  P2.in[3] ← P1.out[4]  (T598: scale=0.05458257, zp=-11)
  P2.in[4] ← P1.out[5]  (T599: scale=0.05458257, zp=-11)
  P2.in[5] ← P1.out[1]  (T537)
  P2.in[6] ← P1.out[3]  (T589)
  P2.in[7] ← P1.out[2]  (T575: scale=0.01414881, zp=-108)
  P2.in[8] ← P1.out[0]  (T523: scale=0.01924785, zp=-114)
```

### P2输出结构（CPU后处理需要的）

| 输出 | Shape | Size | 量化参数 | 用途 |
|------|-------|------|----------|------|
| P2.out[0] | [1,1,2100,56] | 117600 | scale=0.00444572, zp=-115 | 56个关键点坐标 |
| P2.out[1] | [1,1,2100,4] | 8400 | scale=0.00444572, zp=-115 | bbox坐标 |
| P2.out[2] | [1,1,2100,1] | 2100 | scale=0.00444572, zp=-115 | confidence logits |
| P2.out[3] | [1,1,1,2100] | 8400 | scale=0.00444572, zp=-115 | padding/未使用 |

后处理流程：P2.out[2]的INT8值 → dequantize(scale=0.00444572, zp=-115) → sigmoid → confidence

---

## 三、已定位并修复的Bug

### Bug 1：kTfLiteOptionalTensor导致nullptr（根因）

**现象**：P3 partition执行时，`TfLiteOpaqueNodeGetOutput(context, node, i)` 返回nullptr，导致`ComputeRequirements`报错，整个推理链路中断。

**根因**：P3的某些节点的output tensor index是`kTfLiteOptionalTensor`(-1)，这是TFLite delegate分区时的正常行为。`GetOutput()`内部调用`ValidateTensorIndexing`，当tensor_index为-1时返回nullptr。但LiteRT的`dispatch_delegate_kernel.cc`没有在所有相关函数中正确处理这种情况。

**影响**：P0-P2正常执行，P3报错后整个推理链路中断。

### Bug 2：port index错位（审查时发现的严重bug）

**现象**：之前的4处kTfLiteOptionalTensor修复只是"skip了optional tensor"，但循环变量`i`仍被当作port index传给Neuron dispatch库。

**根因**：当P3有optional tensor时，TFLite层面的port index包含optional位置（有空洞），但Neuron层面的port index是连续的（不含optional）。例如P3有3个output，其中output[1]是optional(-1)：
```
TFLite port: 0(valid), 1(optional), 2(valid)
Neuron port: 0(valid), 1(valid)  ← 连续编号
```
修复前：skip了i=1后，i=2的tensor被传给`AttachOutput(ctx, 2, handle)`，但Neuron期望port 1。

**影响**：所有涉及port index的dispatch调用都会错位，导致Neuron执行失败或结果错误。

### 修复的完整列表（dispatch_delegate_kernel.cc共9处修改）

| # | 函数 | 修改内容 | 修复脚本 |
|---|------|----------|----------|
| 1 | `ComputeRequirements` (825行) | skip nullptr tensor | 手动修改 |
| 2 | `ComputeTensorPortConnections` (728-745行) | skip kTfLiteOptionalTensor + `effective_port`计数器 | fix_port_index.py |
| 3 | `ComputeRequirements` (808-840行) | `effective_port`计数器代替原始`i`作为io_tensor_index | fix_port_index.py |
| 4 | `AttachBuffersToInvocationContextsIfNeeded` (1104-1165行) | skip kTfLiteOptionalTensor + `effective_port`计数器 + 检查find结果 | fix_port_index.py |
| 5 | `ScheduleAsyncExecution` (1191-1234行) | skip kTfLiteOptionalTensor + `effective_port`计数器 + `output_events.size()`改为非optional数量 | fix_port_index.py |
| 6 | `CreateNodeInvocationContext` (631-632行) | `num_inputs/num_outputs`改为只计算非optional tensor数量 | fix_port_index.py |

**核心修复模式**：在所有遍历input/output indices的循环中，用`effective_port`计数器代替循环变量`i`作为传给dispatch库的port index：
```cpp
// 修复前
for (auto i = 0; i < indices.size(); ++i) {
    if (indices[i] == kTfLiteOptionalTensor) continue;
    LiteRtDispatchAttachInput(ctx, i, handle);  // i包含空洞！
}

// 修复后
int effective_port = 0;
for (auto i = 0; i < indices.size(); ++i) {
    if (indices[i] == kTfLiteOptionalTensor) continue;
    LiteRtDispatchAttachInput(ctx, effective_port, handle);  // 连续编号
    ++effective_port;
}
```

---

## 四、修复决策

### 问题1：WSL2无法访问github.com
**唯一解决路径**：配置WSL2网络代理，使Bazel能下载编译依赖
**状态**：✅已解决。代理地址：`172.26.32.1:65532`，已写入`~/.bashrc`持久化

### 问题2：P3的`TfLiteOpaqueNodeGetOutput`返回nullptr
**唯一解决路径**：全面深入研究LiteRT runtime的所有代码和逻辑，定位tensor映射bug的根因并修复

**不采用的方案及原因**：
- ❌ Skip nullptr（continue/skip）：治标不治本，P3输出tensor未注册会导致P4及后续partition全部失效
- ❌ 截断P3-P14：绕过问题而非修复，且需要额外hack dispatch库
- ❌ 盲猜修复：没有深入理解就改代码，只会引入新问题

**为什么全面深入研究runtime能解决**：
1. 源码完整——LiteRT全部开源，包括编译器、runtime、dispatch库
2. bug位置已知——`dispatch_delegate_kernel.cc`的`ComputeRequirements`，`TfLiteOpaqueNodeGetOutput`返回nullptr
3. P0-P2正常——说明分区和映射的基本逻辑是对的，只是P3的特定情况出错，修复范围有限
4. 有对比参照——P0-P2的映射是正确的，可以对比P3哪里出了偏差

### 问题3：Bazel编译NDK兼容性问题（✅已解决）

**核心矛盾**：Bazel 7.7.0的`android_ndk_repository`只支持NDK r10-r21，但LiteRT源码需要较新的clang编译器。

**三方博弈**：
```
Bazel 7.7.0  ←兼容→  NDK r21e (clang 9)
                           ↑
                      但LiteRT源码需要 clang 14+
```

**各NDK版本尝试结果**：

| NDK版本 | 问题 | 原因 | 状态 |
|---------|------|------|------|
| r28c | `platforms/`目录不存在 | NDK从r22起移除了`platforms/`目录，Bazel 7.7.0要求该目录 | ❌不可用 |
| r25c | ~~`-gcc-toolchain`参数不兼容~~ | ~~Bazel把r25当r21处理~~ | ✅**实际可用！Bazel 7.4.1+Docker中r25c编译通过** |
| r21e | XNNPACK汇编`bti c`编译失败 | r21e的clang 9不支持ARM BTI指令 | ✅已通过注释assembly.h解决 |
| r21e | `linux/dma-heap.h`头文件缺失 | r21e的sysroot太旧 | ✅已通过从r25c复制头文件解决 |
| r21e | KleidiAI需要SVE2 | r21e的clang 9无法编译ARM SME2/SVE2代码 | ✅已通过修补kai_defs.bzl解决 |
| r21e | `std::filesystem`链接失败 | r21e的libc++不提供完整filesystem实现 | ❌r21e无法解决，需切换到r25c |
| r21e | `__isOSVersionAtLeast`未定义 | r21e的clang 9缺少此符号 | ❌r21e无法解决，需切换到r25c |

**关键发现**：之前认为r25c在Docker中会有`-gcc-toolchain`问题，但实际测试发现**Bazel 7.4.1 + Docker环境中r25c编译完全通过**，不需要修补CROSSTOOL。

**r25c的优势**：
- clang 14支持BTI指令，不需要注释`assembly.h`
- 自带`dma-heap.h`，不需要从其他NDK复制
- libc++完整支持`std::filesystem`和`__isOSVersionAtLeast`
- 编译速度更快（90秒 vs r21e的数分钟）

**本质原因**：LiteRT源码和构建系统版本脱节——源码持续更新引入了需要新编译器的代码（XNNPACK BTI、KleidiAI SVE2、dma-heap.h、std::filesystem），但`.bazelversion`还停留在7.7.0，`android_ndk_repository`只支持到r21。Google内部用自建构建基础设施，不依赖开源Bazel的NDK规则，所以这个矛盾在Google内部不存在。

**我们不需要XNNPACK和KleidiAI**——它们是CPU推理加速库，我们的目标是NPU推理。但`//litert/kotlin:LiteRt`目标通过TFLite JNI间接依赖了它们，无法绕过。

### 问题4：XNNPACK链接符号缺失（✅已解决）

**根因**：`xnnpack_archs()`之前被改成只返回`["aarch64"]`，但XNNPACK的C语言microkernel源文件在`PROD_C_SRCS_FOR_ARCH`字典中的key是`"neon_aarch64"`和`"neon"`而不是`"aarch64"`。导致neon_aarch64的所有C源文件（包括vdiv/vmax/vmin/vmul/vsub/vprelu/vcopysign等binary elementwise操作）没有被编译，链接时符号缺失。

**修复**：改为返回`["scalar", "neon", "neon_aarch64", "neonfma", "neonfma_aarch64", "neonv8", "neonfp16", "neonbf16", "aarch64"]`

---

## 五、编译与部署

### Docker编译环境

```bash
# 编译libLiteRt.so（NDK r25c，当前主力）
docker run --rm --security-opt seccomp=unconfined \
  -e HOME=/litert_build -e USER=root \
  -e USE_LOCAL_TF=true \
  -e TF_LOCAL_SOURCE_PATH=/litert_build/third_party/tensorflow \
  -e ANDROID_NDK_HOME=/android/ndk-r25c \
  -e http_proxy=http://172.26.32.1:65532 \
  -e https_proxy=http://172.26.32.1:65532 \
  -v /root/src/LiteRT-src:/litert_build \
  -v /root/src/android-ndk-r25c:/android/ndk-r25c \
  litert_build_env bash -c 'ln -sf /android/ndk-r25c /android/ndk && bazel --host_jvm_args=-Dhttps.proxyHost=172.26.32.1 --host_jvm_args=-Dhttps.proxyPort=65532 --host_jvm_args=-Dhttp.proxyHost=172.26.32.1 --host_jvm_args=-Dhttp.proxyPort=65532 build --config=android_arm64 --define=xnn_enable_kleidiai=false //litert/kotlin:LiteRt'
```

产出：`bazel-bin/litert/kotlin/libLiteRt.so`（5.6MB，83个JNI函数）

### 部署步骤

1. 编译出的`libLiteRt.so`复制到`D:\AS\PMouse\app\src\main\jniLibs\arm64-v8a\`
2. `build.gradle.kts`中已有的afterEvaluate patch task确保自定义dispatch库不被AAR版本覆盖
3. Windows编译APK：`$env:JAVA_HOME='D:\AS\AndroidStudio\jbr'; D:\AS\PMouse\gradlew.bat -p D:\AS\PMouse assembleDebug --no-configuration-cache`
4. ADB安装：`adb install -r app-debug.apk`

### Docker中Bazel缓存修补记录（NDK r25c路径）

| 文件 | 修改内容 | 修补脚本 |
|------|----------|----------|
| `external/XNNPACK/BUILD.bazel` | 所有`explicit_false→explicit_true`映射改为`explicit_false→explicit_false` | `fix_xnnpack.py force_disable` |
| `external/XNNPACK/build_params.bzl` | `xnnpack_archs()`返回9个aarch64相关arch | `fix_archs.py` |
| `external/XNNPACK/build_defs.bzl` | `xnnpack_configurable_defines()`硬编码所有feature为disabled | `fix_xnn_defines.py` |
| `external/XNNPACK/src/**/*.c/cc/h/inc` | `#if XNN_ENABLE_KLEIDIAI`→`#if 0` | `apply_all_patches.py` |
| `external/KleidiAI/kai_defs.bzl` | `kai_cpu_select()`返回`armv8-a`；`_kai_c_cxx_common()`创建空cc_library | `fix_kleidiai.py` |
| `litert/c/BUILD` | `--undefined-glob=LiteRt*`→`--undefined=LiteRt` | 手动修改（在源码树中） |
| `litert/kotlin/BUILD` | 添加3个LiteRT Next JNI deps | `fix_build2.py` |

**注意**：`bazel clean`后external仓库可能被重新解压，需要重新运行修补脚本。

### 修补脚本位置

| 脚本 | 路径 | 用途 |
|------|------|------|
| `fix_xnnpack.py` | `D:\HuaWei\text\fix_xnnpack.py` | 修复XNNPACK BUILD.bazel的false→true bug |
| `fix_kleidiai.py` | `D:\HuaWei\text\fix_kleidiai.py` | 修补KleidiAI/XNNPACK build_params |
| `fix_xnn_defines.py` | `D:\HuaWei\text\fix_xnn_defines.py` | 强制XNNPACK feature defines为disabled |
| `fix_configurable_defines.py` | `D:\HuaWei\text\fix_configurable_defines.py` | 修补xnnpack_configurable_defines/xnnpack_select_if |
| `fix_archs.py` | `D:\HuaWei\text\fix_archs.py` | 修补xnnpack_archs返回9个aarch64相关arch |
| `apply_all_patches.py` | `D:\HuaWei\text\apply_all_patches.py` | 一键应用所有修补（综合脚本） |
| `fix_build2.py` | `D:\HuaWei\text\fix_build2.py` | 修改litert/kotlin/BUILD添加LiteRT Next JNI deps |
| `fix_port_index.py` | `D:\HuaWei\text\fix_port_index.py` | 修复port index错位bug（6处修改） |
| `fix_diag.py` | `D:\HuaWei\text\fix_diag.py` | 第一轮诊断日志（EvalHelper/AllocateAndRegisterBuffer入口） |
| `fix_diag2.py` | `D:\HuaWei\text\fix_diag2.py` | 第二轮诊断日志（AllocateTensorBuffer内部参数） |
| `fix_diag3.py` | `D:\HuaWei\text\fix_diag3.py` | 第三轮诊断日志（litert_tensor_type维度信息） |

---

## 六、关键代码位置

### 已修改的LiteRT源码（WSL2中）

| 文件 | 位置 | 修改内容 |
|------|------|----------|
| `dispatch_delegate_kernel.cc` | `~/src/LiteRT-src/litert/runtime/dispatch/` | 9处修复：nullptr skip + kTfLiteOptionalTensor skip + effective_port计数器 + num_inputs/num_outputs排除optional + 3轮DIAG诊断日志 |
| `litert_dispatch_invocation_context.cc` | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | bytecode保存+HostMemory+INVOKE_DIAG诊断日志 |
| `litert_dispatch_invocation_context.h` | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | 添加output_host_addrs_成员变量 |
| `litert/c/BUILD` | `~/src/LiteRT-src/litert/c/` | `--undefined-glob`→`--undefined` |
| `litert/kotlin/BUILD` | `~/src/LiteRT-src/litert/kotlin/` | 添加3个LiteRT Next JNI deps |
| `WORKSPACE` | `~/src/LiteRT-src/` | 删除了所有25个android_ndk_repository定义 |

### APP中的关键文件

| 文件 | 位置 | 说明 |
|------|------|------|
| `LiteRtEngine.kt` | `app/src/main/java/com/PMouse/core/` | LiteRT引擎Kotlin层，调用CompiledModel API |
| `NeuronDirectEngine.kt` | `app/src/main/java/com/PMouse/core/` | NPU直连引擎（方案B，当前不可用） |
| `neuron_direct_jni.cpp` | `app/src/main/cpp/` | NPU直连JNI层（方案B） |
| `build.gradle.kts` | `app/` | afterEvaluate patch task，确保自定义dispatch库不被AAR覆盖 |

### APP中的库文件

| 文件 | 路径 | 说明 |
|------|------|------|
| libLiteRt.so | `app/src/main/jniLibs/arm64-v8a/` | 自编译版本5.6MB，含83个JNI函数 + 9处kTfLiteOptionalTensor修复 + 3轮DIAG诊断日志 |
| libLiteRtDispatch_MediaTek.so | `app/src/main/jniLibs/arm64-v8a/` | 修改后的dispatch库9.7MB（含bytecode保存+诊断日志） |
| libLiteRtCompilerPlugin_MediaTek.so | `app/src/main/jniLibs/arm64-v8a/` | NPU编译器插件2.2MB |

### WSL2编译环境

| 路径 | 说明 |
|------|------|
| `~/src/LiteRT-src/` | LiteRT完整源码（v2.2.0构建环境） |
| `~/src/android-ndk-r25c/` | Android NDK r25c（✅当前主力） |
| `~/src/android-ndk-r21e/` | Android NDK r21e（已弃用） |
| `~/src/android-ndk-r28c/` | Android NDK r28c（Bazel不兼容） |
| Docker `litert_build_env` | Bazel 7.4.1 + Clang 18.1.3（Docker编译用，当前主力） |

---

## 七、风险与应对

| 风险 | 说明 | 应对 |
|------|------|------|
| P3-P14仍有未发现的optional tensor问题 | port index修复后P3可能还有其他问题 | 观察logcat，逐partition验证 |
| Bazel缓存修补不持久 | `bazel clean`后external仓库重新解压 | 修补脚本保存在`D:\HuaWei\text\`，可重新运行 |
| NPU推理FPS不达标 | runtime开销可能比方案B大 | 先验证正确性，再优化性能 |
| dispatch库版本不匹配 | 自编译libLiteRt.so与AAR版本的dispatch库可能不兼容 | dispatch库也是自编译版本 |

---

## 八、方案B的教训（为什么方案B失败）

方案B（NPU直连）绕过了LiteRT runtime，手动用Neuron API拼装15个partition。失败原因：

1. **输入格式错误**：P0输入padded_size=1228800（FP32大小），但NPU期望INT8输入。修复后P0输出开始随输入变化
2. **P1输出不变**：P1的输入在变化但输出不变。根因是手动拼装缺少dispatch库的NeuronMemory零拷贝机制——dispatch库用`execution_set_input_from_memory`（NeuronMemory/DMA-BUF），我们用`execution_set_input`（host buffer），NPU内部走不同路径
3. **核心教训**：不应该绕过runtime手动拼装，而应该修复runtime本身

---

## 九、ADB操作备忘

```
ADB路径：D:\AS\SDK\platform-tools\adb.exe
设备ID：10ADB6162A0019E
增大logcat缓冲区：adb logcat -G 16M
查看日志：adb logcat -d -s NeuronDirect
安装APK：adb install -r app-debug.apk
启动APP：adb shell am start -n com.PMouse/.MainActivity

按钮位置（NPU直连）：中心约(512, 1277)
按钮位置（LiteRT+NPU）：中心约(243, 1277)
按钮位置（OBS+UDP）：中心约(900, 1557)
启动按钮位置：中心约(630, 659)
```

---

## 十、构建命令备忘

```bash
# Windows编译APK（PowerShell）
$env:JAVA_HOME='D:\AS\AndroidStudio\jbr'
D:\AS\PMouse\gradlew.bat -p D:\AS\PMouse assembleDebug --no-configuration-cache

# Docker编译libLiteRt.so（Bazel 7.4.1，当前主力）
# 见第五节Docker编译环境

# WSL2编译dispatch库（CMake，已验证可用）
cd /root/src/LiteRT-src/litert
cmake --preset android-arm64
cmake --build cmake_build_android_arm64 --target dispatch_api_MediaTek_so -j$(nproc)
```

---

## 十一、当前进度（2026-06-28 v0.4.0）

### 已完成
1. ✅ WSL2网络代理配置（172.26.32.1:65532）
2. ✅ Docker镜像`litert_build_env`构建成功
3. ✅ NDK r25c编译环境搭建完成（Bazel 7.4.1+Docker）
4. ✅ XNNPACK/KleidiAI编译问题全部修复（9项）
5. ✅ **libLiteRt.so编译成功**：5.6MB，83个JNI函数
6. ✅ **P3 bug根因定位**：kTfLiteOptionalTensor(-1)未正确处理
7. ✅ **nullptr skip修复**：ComputeRequirements中skip nullptr tensor
8. ✅ **port index错位bug定位与修复**：5个函数中用effective_port计数器代替原始循环变量i
9. ✅ **CreateNodeInvocationContext修复**：num_inputs/num_outputs排除optional tensor
10. ✅ **libLiteRt.so已重新编译**（含全部9处修复）
11. ✅ **APK编译成功并安装到手机**
12. ✅ **手机测试确认P0-P2执行成功**，P2输出数据正确
13. ✅ **方案A文档更新至v0.3.0**，包含完整代码理解和工作原则
14. ✅ **完整通读方案A涉及的所有代码**（13+个文件，包括编译器插件、TFLite delegate分区、runtime调度等）
15. ✅ **第一轮诊断日志已运行**，确认了3个delegate kernel的tensor_id分布和错误位置
16. ✅ **第二轮诊断日志已运行**，确认`AllocateTensorBuffer`参数：tensor_id=36的buffer_type=1(HostMemory), buffer_size=1200
17. ✅ **第三轮诊断日志已运行**，确认tensor_id=36的litert_tensor_type: rank=2, dim=[1,300], elem_size=4(float32)，参数完全合理但`LiteRtCreateManagedTensorBuffer`仍然失败
18. ✅ **修正了v0.3.0的错误分析**：确认不是buffer requirements缺失，而是`LiteRtCreateManagedTensorBuffer`本身失败

### 当前正在做
- ❌ **添加`LiteRtCreateManagedTensorBuffer`错误码捕获日志**：需要确认具体的失败原因（posix_memalign失败？IsValid检查失败？CreateFromHostMemory失败？）

### 待完成
1. ❌ 应用错误码捕获日志，重新编译libLiteRt.so，安装测试
2. ❌ 基于诊断日志确认`LiteRtCreateManagedTensorBuffer`失败的具体原因
3. ❌ 基于确认的事实确定修复方案
4. ❌ 实施修复，验证15个partition全部成功
5. ❌ FPS测试：确认NPU推理FPS>60

### 诊断日志演进史

| 轮次 | 脚本 | 添加位置 | 发现 |
|------|------|----------|------|
| 第1轮 | fix_diag.py | EvalHelper/AllocateAndRegisterBuffer入口 | 确认3个delegate kernel的tensor_id分布，错误在第三个kernel的tensor_id=36 |
| 第2轮 | fix_diag2.py | AllocateTensorBuffer内部 | 确认buffer_type=1, buffer_size=1200, num_supported_types=1 |
| 第3轮 | fix_diag3.py | AllocateTensorBuffer中litert_tensor_type维度 | 确认rank=2, dim=[1,300], elem_size=4(float32)，参数合理但仍然失败 |
| 第4轮 | 待写 | LiteRtCreateManagedTensorBuffer错误码捕获 | 待确认具体失败原因 |

### logcat查看注意事项

**重要bug**：使用`adb logcat -c`清空logcat后，APP的日志可能无法正常记录。解决方案：
- **不要使用`adb logcat -c`清空logcat**
- 重启APP后等待足够长时间（40秒以上），让模型编译完成
- 使用`adb shell "logcat -d | grep ..."`在设备上搜索日志，比在Windows上过滤更可靠
- `adb logcat -d`在Windows PowerShell中可能只返回少量行，用`adb shell "logcat -d"`更可靠

### Bazel编译问题演进史

1. **NDK r28c**：`platforms/`目录不存在 → Bazel 7.7.0要求该目录
2. **NDK r25c + 从r21e复制platforms/**：`-gcc-toolchain`参数不兼容 → Bazel把r25当r21处理
3. **NDK r21e**：XNNPACK汇编`bti c`编译失败 → clang 9不支持BTI → ✅已解决
4. **NDK r21e + 复制dma-heap.h**：KleidiAI SVE2编译失败 → ✅已通过修补kai_defs.bzl解决
5. **NDK r21e**：XNNPACK i8mm/fp16/sme/x86编译失败 → ✅已通过修补build_defs.bzl/BUILD.bazel解决
6. **NDK r21e**：XNNPACK `#if XNN_ENABLE_KLEIDIAI`仍include KleidiAI头文件 → ✅已通过替换为`#if 0`解决
7. **NDK r21e**：`--undefined-glob`链接选项不支持 → ✅已通过替换为`--undefined`解决
8. **NDK r21e**：`std::filesystem`和`__isOSVersionAtLeast`链接失败 → ✅切换到r25c解决
9. **NDK r25c（Docker）**：编译阶段全部通过 → ✅r25c不需要修补CROSSTOOL
10. **NDK r25c（Docker）**：XNNPACK链接符号缺失 → ✅根因：`xnnpack_archs()`漏掉`"neon"`和`"neon_aarch64"`
 11. **NDK r25c（Docker）**：libLiteRt.so编译成功 → ✅5.6MB，83个JNI函数

---

## 十二、完整代码理解（v0.2.0新增）

### 调用链总览

```
LiteRtEngine.kt (APP层)
  CompiledModel.create(modelPath, options, env)  → 编译模型
  CompiledModel.run(inputBuffers, outputBuffers)  → 推理

litert/runtime/compiled_model.cc (C++ Runtime层)
  LiteRtCompiledModelT::Run()
    1. RegisterBuffer() — 为每个input/output tensor注册buffer到buffer_context_
    2. interpreter->Invoke() — TFLite推理

TFLite Interpreter
  对每个DELEGATE node调用 DispatchDelegateKernel::Eval()

litert/runtime/dispatch/dispatch_delegate_kernel.cc (核心调度层)
  EvalHelper()
    1. AllocateTensorBuffersIfNeeded() — 为所有tensor分配buffer
    2. AttachBuffersToInvocationContextsIfNeeded() — 将buffer attach到NPU invocation context
    3. ScheduleSyncExecution() / ScheduleAsyncExecution() — 执行NPU推理

litert/vendors/mediatek/dispatch/litert_dispatch_invocation_context.cc (MediaTek dispatch层)
  AttachInput() / AttachOutput() — 将buffer绑定到NeuronExecution
  Invoke() — 调用NeuronExecution_compute()
```

### 关键数据结构

#### DispatchDelegateKernel 成员变量

| 变量 | 类型 | 含义 |
|------|------|------|
| `nodes_` | `vector<TfLiteOpaqueNode*>` | TFLite delegate分配给此kernel的所有node（每个node对应一个NPU partition） |
| `node_invocation_contexts_` | `vector<LiteRtDispatchInvocationContext>` | 每个node对应的Neuron invocation context |
| `node_input_tensor_indices_` | `vector<vector<int>>` | 每个node的input tensor index列表（可能包含kTfLiteOptionalTensor=-1） |
| `node_output_tensor_indices_` | `vector<vector<int>>` | 每个node的output tensor index列表（可能包含kTfLiteOptionalTensor=-1） |
| `input_tensor_ids_` | `vector<int>` | 模型输入tensor ID列表（来自params.input_tensors，不包含-1） |
| `output_tensor_ids_` | `vector<int>` | 模型输出tensor ID列表（来自params.output_tensors，不包含-1） |
| `internal_tensor_ids_` | `vector<int>` | 内部tensor ID列表（node间传递的中间结果，不包含-1） |
| `buffer_context_` | `ExternalLiteRtBufferContextT*` | 全局buffer管理器，存储buffer requirements和tensor buffers |
| `tensor_buffer_infos_` | `node_hash_map<int, TensorInfo>` | tensor_id → TensorInfo映射（包含tensor_buffer, buffer_handle, attached标志等） |
| `tensor_idx_to_handle_` | `unordered_map<int, LiteRtTensorBufferHandle>` | tensor_id → dispatch API buffer handle映射 |
| `io_tensors_port_connections_` | `flat_hash_map<int, vector<PortConnection>>` | tensor_id → port连接列表（哪个node的哪个port使用此tensor） |

#### TensorInfo 结构

| 字段 | 类型 | 含义 |
|------|------|------|
| `tensor_buffer` | `LiteRtTensorBufferPtr` | 智能指针，指向tensor buffer对象 |
| `buffer_handle` | `LiteRtTensorBufferHandle` | 在dispatch API中注册的handle（整数索引） |
| `maybe_sync_with_cpu` | `bool` | 是否需要CPU↔NPU数据同步 |
| `tensor_buffer_used_size` | `size_t` | buffer使用的字节数 |
| `attached` | `bool` | 是否已attach到invocation context |
| `out_fence_buffer` | `LiteRtTensorBufferPtr` | 用于延迟注销的fence buffer |

#### PortConnection 结构

| 字段 | 类型 | 含义 |
|------|------|------|
| `node_idx` | `int` | 哪个node（partition） |
| `port_idx` | `int` | 该node的哪个port（effective_port，已排除optional tensor） |
| `is_input_port` | `bool` | 是input port还是output port |

#### LiteRtExternalLiteRtBufferContextT 成员变量

| 变量 | 类型 | 含义 |
|------|------|------|
| `buffer_requirements_` | `unordered_map<TfLiteTensorIdentifier, unique_ptr<Requirements>>` | tensor → buffer requirements映射（由ComputeRequirements注册） |
| `tensor_buffers_` | `unordered_map<TfLiteTensorIdentifier, unique_ptr<TensorBuffer>>` | tensor → tensor buffer映射（由AllocateAndRegisterBuffer或RegisterBuffer注册） |

**关键**：`buffer_requirements_`和`tensor_buffers_`的key是`TfLiteTensorIdentifier`（基于tensor指针的标识符），不是tensor_id整数。

### 完整执行流程

#### 阶段1：Init（模型加载时，只执行一次）

```
Init(context, params)
  └→ InitHelper(context, params)
       ├→ GetInstance(context) → 获取buffer_context_
       ├→ GetNodes(context, params) → 获取所有delegate node
       ├→ 对每个node:
       │    ├→ CreateNodeInvocationContext(context, node)
       │    │    ├→ 读取bytecode offset/size从custom_options
       │    │    ├→ 计算num_node_inputs/num_node_outputs（排除optional tensor）
       │    │    └→ LiteRtDispatchInvocationContextCreate(...) → 创建Neuron invocation context
       │    ├→ TfLiteOpaqueNodeInputs → 保存node_input_tensor_indices_
       │    └→ TfLiteOpaqueNodeOutputs → 保存node_output_tensor_indices_
       ├→ 保存input_tensor_ids_, output_tensor_ids_
       ├→ 构建internal_tensor_ids_（遍历所有node的inputs/outputs，排除IO tensor）
       ├→ ComputeTensorPortConnections() → 构建io_tensors_port_connections_
       └→ ComputeRequirements() → 为每个非optional tensor注册buffer requirements
```

**关键细节**：
- `CreateNodeInvocationContext`中，`num_node_inputs/num_node_outputs`已修复为排除optional tensor
- `node_input_tensor_indices_`和`node_output_tensor_indices_`**保留**了原始值（包含-1），因为后续遍历时需要知道哪些位置是optional
- `internal_tensor_ids_`的构建中，`GetInternalTensors`已skip了nullptr tensor，所以-1对应的tensor不会被添加
- `ComputeTensorPortConnections`和`ComputeRequirements`中已修复为使用`effective_port`计数器

#### 阶段2：Eval（每次推理时执行）

```
Eval(context, node)
  └→ EvalHelper(context, node)
       ├→ AllocateTensorBuffersIfNeeded(context)
       │    ├→ ProcessDeferredUnregistrations()
       │    ├→ 对input_tensor_ids_: AllocateAndRegisterBuffer()
       │    ├→ 对output_tensor_ids_: AllocateAndRegisterBuffer()
       │    └→ 对internal_tensor_ids_: AllocateTensorBuffer() + RegisterBufferWithDispatchApi()
       ├→ AttachBuffersToInvocationContextsIfNeeded(context)
       │    └→ 对每个node的input/output: LiteRtDispatchAttachInput/AttachOutput()
       ├→ CPU→NPU数据同步（input tensor memcpy）
       ├→ ScheduleSyncExecution() / ScheduleAsyncExecution()
       │    └→ 对每个node: LiteRtDispatchInvokeAsync/Invoke → NeuronExecution_compute
       └→ NPU→CPU数据同步（output tensor memcpy）
```

#### AllocateAndRegisterBuffer 逻辑详解

```
AllocateAndRegisterBuffer(tensor_id, context, io_tensor_ids)
  ├→ TfLiteOpaqueContextGetOpaqueTensor(context, tensor_id) → 获取tfl_tensor
  ├→ if tensor_buffer_infos_中已有此tensor_id:
  │    ├→ if 不是IO tensor → return {} (internal tensor不需要更新)
  │    ├→ buffer_context_->GetTensorBuffer(tfl_tensor) → 获取已注册的buffer
  │    │    ⚠️ 如果GetTensorBuffer返回"not found" → 当前报错点！
  │    ├→ if buffer没变 → return {}
  │    ├→ 延迟注销旧buffer
  │    └→ RegisterBufferWithDispatchApi() 注册新buffer
  └→ if tensor_buffer_infos_中没有此tensor_id:
       ├→ buffer_context_->GetTensorBuffer(tfl_tensor)
       │    ├→ if 成功 → 使用已注册的buffer
       │    └→ if 失败 → AllocateTensorBuffer() 自己分配
       │         ├→ buffer_context_->GetBufferRequirements(tfl_tensor)
       │         │    ⚠️ 如果GetBufferRequirements也返回"not found" → 另一个潜在报错点！
       │         ├→ LiteRtCreateManagedTensorBuffer() → 创建buffer
       │         ├→ buffer_context_->RegisterTensorBuffer() → 注册到buffer_context_
       │         └→ RegisterBufferWithDispatchApi() → 注册到dispatch API
       └→ RegisterBufferWithDispatchApi() 注册buffer
```

### 当前报错的精确分析（v0.4.0更新——基于三轮诊断日志的确认事实）

**错误链**：`EvalHelper`(439) → `AllocateTensorBuffersIfNeeded`(1050) → `AllocateAndRegisterBuffer`(987) → `AllocateTensorBuffer`(1112)

**第三轮诊断日志确认的事实**（2026-06-28 23:19 logcat）：

第三个delegate kernel（P3及之后的partition）的tensor信息：

| tensor_id | 方向 | buffer_type | buffer_size | litert_tensor_type | dim | elem_size | 结果 |
|-----------|------|-------------|-------------|-------------------|-----|-----------|------|
| 34 | input | 1(HostMemory) | 1200 | rank=2 | [1,300] | 2(float16) | ✅成功 |
| 35 | output | 1(HostMemory) | 360000 | rank=2 | [300,1] | 4(float32) | ✅成功 |
| 36 | output | 1(HostMemory) | 1200 | rank=2 | [1,300] | 4(float32) | ❌失败 |

**关键观察**：
1. tensor_id=36的参数完全合理：buffer_type=1(HostMemory), buffer_size=1200, dim=[1,300], elem_size=4(float32)，1×300×4=1200字节，buffer_size匹配
2. tensor_id=34同样buffer_size=1200但elem_size=2(float16)，也成功了
3. tensor_id=35的dim=[300,1]很奇怪（正常应该是[1,300]），但buffer_size=360000远大于所需（300×1×4=1200），所以IsValid通过
4. **`LiteRtCreateManagedTensorBuffer`在tensor_id=36上失败，但参数看起来完全正常**

**v0.3.0分析的修正**：
- v0.3.0认为"skip nullptr tensor导致该tensor没有注册buffer requirements"是根因，但**诊断日志确认`GetBufferRequirements`成功了**（否则会在行1095报错，但实际在行1112报错）
- 真正的失败点在`LiteRtCreateManagedTensorBuffer`本身，而不是buffer requirements缺失
- `AllocateAndRegisterBuffer`走的是fallback分支（tensor_buffer_infos_没有记录 → GetTensorBuffer失败 → AllocateTensorBuffer fallback）

**下一步**：需要在`LiteRtCreateManagedTensorBuffer`调用前后添加错误码捕获，确认具体的失败原因（可能是`CreateManagedOnHostMemory`内部的`posix_memalign`失败，或`IsValid()`检查失败，或`CreateFromHostMemory`失败）

**v0.3.0旧分析（已部分过时，保留供参考）**：

~~**错误链**：`EvalHelper`(439) → `AllocateTensorBuffersIfNeeded`(1038) → `AllocateAndRegisterBuffer`(933)~~

~~**行933**：`buffer_context_->GetTensorBuffer(tfl_tensor)` 返回 "Tensor buffer not found"~~

~~**触发条件**：`tensor_buffer_infos_`中已有该tensor_id的记录（说明之前在`AllocateTensorBuffersIfNeeded`中为internal tensor分配过），且该tensor_id是IO tensor，但`buffer_context_->tensor_buffers_`中没有对应的buffer。~~

~~**可能的原因**（待验证）：~~
~~1. P3的某个output tensor在`ComputeRequirements`中被skip（因为`TfLiteOpaqueNodeGetOutput`返回nullptr），没有注册buffer requirements~~
~~2. 该tensor出现在`output_tensor_ids_`中（因为`params.output_tensors`包含了它）~~
~~3. 但`buffer_context_->tensor_buffers_`中没有它的buffer（因为`CompiledModel::RegisterBuffer`没有为它注册——可能因为`GetBufferRequirements`也返回not found，走了CPU sync路径）~~

~~**注意**：`AllocateAndRegisterBuffer`有两个分支调用`GetTensorBuffer`：~~
~~- 行933（tensor_buffer_infos_已有记录的分支）：`LITERT_ASSIGN_OR_RETURN`直接返回错误~~
~~- 行960（tensor_buffer_infos_没有记录的分支）：检查返回值，失败则走`AllocateTensorBuffer` fallback~~

~~行933没有fallback逻辑，这是bug所在。~~

### LiteRtDispatchDeviceContextT（NeuronMemory注册表）

```
RegisterTensorBuffer(tensor_buffer)
  ├→ 获取buffer_type (Ahwb/DmaBuf/HostMemory)
  ├→ HostMemory路径:
  │    ├→ get_tensor_buffer_host_memory() → 获取host_addr
  │    └→ neuron_memory_registry_.RegisterHostMemory(host_addr, size, offset)
  │         → 返回handle（数组索引）
  ├→ Ahwb路径:
  │    ├→ memory_create_from_ahwb() → 创建NeuronMemory
  │    └→ neuron_memory_registry_.Register(neuron_memory, size, offset)
  └→ DmaBuf路径:
       ├→ memory_create_from_fd() → 创建NeuronMemory
       └→ neuron_memory_registry_.Register(neuron_memory, size, offset)
```

**NeuronMemoryRegistry**：一个`vector<NeuronMemoryInfo>`，handle就是数组索引。
- `Register()`：找空槽或push_back，返回索引
- `RegisterHostMemory()`：同上，但设置`is_host_memory=true`和`host_addr`
- `Find()`：按索引直接访问

### LiteRtDispatchInvocationContextT（Neuron执行层）

```
Create(neuron_adapter_api, device_context, exec_type, bytecode, function_name, num_inputs, num_outputs)
  ├→ SchemaResolver::Initialize() → 解析bytecode
  ├→ CheckNeuronSdkVersionCompatibility()
  ├→ LoadFromCachedNetwork() → 尝试从bytecode恢复NeuronModel+NeuronCompilation
  │    └→ 如果失败 → LoadFromDlaBytecode() → 用num_inputs/num_outputs创建fake operands
  ├→ CreateExecution() → 创建NeuronExecution
  └→ 设置boost_hint=100

AttachInput(graph_input_index, tensor_buffer_handle)
  ├→ GetNeuronMemoryInfo(handle)
  ├→ if is_host_memory → execution_set_input(host_addr)
  └→ else → execution_set_input_from_memory(neuron_memory, offset, size)

AttachOutput(graph_output_index, tensor_buffer_handle)
  ├→ GetNeuronMemoryInfo(handle)
  ├→ if is_host_memory → execution_set_output(host_addr)
  └→ else → execution_set_output_from_memory(neuron_memory, offset, size)

Invoke()
  └→ NeuronExecution_compute()
```

### CompiledModel::Run → RegisterBuffer 逻辑

```
RegisterBuffer(runner, tensor, tensor_index, tensor_name, buffer, is_input, ...)
  ├→ buffer_context_->GetBufferRequirements(tensor)
  │    ├→ if 成功 → 检查supported_types是否匹配buffer_type
  │    │    ├→ 匹配 → RegisterTensorBuffer() + 标记kTfLiteNonCpu
  │    │    └→ 不匹配 → buffer_requires_cpu_sync = true
  │    └→ if 失败（requirements not found）→ buffer_requires_cpu_sync = true
  ├→ if buffer_requires_cpu_sync:
  │    ├→ Lock buffer → 获取host_mem_addr
  │    ├→ SetCustomAllocationForInputTensor/SetCustomAllocationForOutputTensor
  │    └→ 不注册到buffer_context_->tensor_buffers_
  └→ if CPU tensor → 类似CPU sync路径
```

**关键**：当`GetBufferRequirements`返回not found时（即`ComputeRequirements`中skip了该tensor），`RegisterBuffer`走CPU sync路径，**不会**调用`buffer_context_->RegisterTensorBuffer()`。这意味着`buffer_context_->tensor_buffers_`中没有该tensor的buffer。

然后当`AllocateAndRegisterBuffer`在行933调用`buffer_context_->GetTensorBuffer()`时，自然返回"not found"。

### 完整编译/加载/执行流程（v0.3.0新增）

#### 1. 编译阶段（CompiledModel.create()）

```
LiteRtEngine.kt: CompiledModel.create(modelPath, options, env)
  → JNI: nativeCreateFromFile()
    → LiteRtCompiledModelT::Create(env, model, options)
      ├→ InitializeModel()
      │    └→ ApplyPluginsWithCaching()
      │         └→ ApplyPlugin()
      │              ├→ TransformModel() — 编译器变换
      │              ├→ PartitionModel() — 分区
      │              │    ├→ compiler_plugin.Partition() — MediaTek插件选择哪些op可以NPU执行
      │              │    ├→ PartitionSubgraph() — 把选中的op分组为connected islands
      │              │    │    └→ OutlinePartition() — 每个island提取为新subgraph+dispatch op
      │              │    │         ├→ GraphSlicer::SlicePartitionFromGraph()
      │              │    │         │    ├→ CloneInto() — 克隆op和tensor到新subgraph
      │              │    │         │    ├→ MakeDispatchOp() — 将原op改为dispatch custom op
      │              │    │         │    └→ RerouteTensorsThroughCustomOp() — 重连tensor
      │              │    │         └→ 返回dispatch_op（在root subgraph中）
      │              │    └→ 返回PartitionResult{dispatch_ops, sliced_model}
      │              └→ ApplyPluginWithPartition()
      │                   ├→ compiler_plugin.Compile(&sliced_model) — 编译bytecode
      │                   └→ 注册bytecode到model.Buffers()，关联到dispatch op
      │
      ├→ InitializeRuntime()
      │    ├→ 创建TFLite Interpreter
      │    ├→ 创建buffer_context_（ExternalLiteRtBufferContextT）
      │    └→ interp_->SetExternalContext(kTfLiteLiteRtBufferContext, buffer_context_)
      │
      ├→ 设置DispatchDelegateOptions（alloc_base, fd, bytecode offset/size, JIT handles）
      │
      └→ 对每个accelerator调用CreateDelegate + ModifyGraphWithDelegate
           └→ DispatchDelegate::DelegatePrepare()
                ├→ IsNodeSupportedByDelegate() — 检查是否是dispatch custom op
                ├→ 收集所有supported_nodes
                └→ TfLiteOpaqueContextReplaceNodeSubsetsWithDelegateKernels()
                     ├→ PartitionGraphIntoIndependentNodeSubsets()
                     │    ⚠️ 构建NodeSubset时已跳过kTfLiteOptionalTensor
                     │    → input_tensors/output_tensors不包含optional tensor
                     └→ CreateOpaqueDelegateParams() — 构建delegate参数
                          → 每个partition创建一个DELEGATE node
                          → Init()被调用 → DispatchDelegateKernel::Init()
```

**关键理解**：
- 15个partition = 15个dispatch op = 15个TFLite DELEGATE node
- 但TFLite可能把多个dispatch op合并成一个delegate node subset
- `TfLiteOpaqueDelegateParams`的`input_tensors`/`output_tensors`是整个subset的IO，不包含optional tensor
- 每个DELEGATE node有自己的input/output tensor indices（可能包含optional tensor）

#### 2. Init阶段（DispatchDelegateKernel::Init()）

```
InitHelper(context, params)
  ├→ 获取buffer_context_
  ├→ GetNodes() — 获取所有DELEGATE node
  ├→ 对每个node:
  │    ├→ CreateNodeInvocationContext() — 创建Neuron invocation context
  │    │    ├→ 读取bytecode offset/size从custom_options
  │    │    ├→ num_node_inputs/num_node_outputs（排除optional tensor）
  │    │    └→ LiteRtDispatchInvocationContextCreate()
  │    ├→ TfLiteOpaqueNodeInputs/Outputs → 保存node_input/output_tensor_indices_
  │    │    ⚠️ 这些indices可能包含kTfLiteOptionalTensor(-1)
  │    └→ 保存到node_invocation_contexts_
  ├→ 保存input_tensor_ids_（来自params.input_tensors，不含-1）
  ├→ 保存output_tensor_ids_（来自params.output_tensors，不含-1）
  ├→ 构建internal_tensor_ids_（遍历node inputs/outputs，排除IO tensor）
  │    ⚠️ GetInternalTensors已skip nullptr，所以-1对应的tensor不会被添加
  ├→ ComputeTensorPortConnections() — 构建io_tensors_port_connections_
  │    ⚠️ 已修复：skip optional tensor + effective_port计数器
  └→ ComputeRequirements() — 为每个非optional tensor注册buffer requirements
       ⚠️ 已修复：skip nullptr tensor + effective_port计数器
       ⚠️ 但skip的tensor没有注册buffer requirements！
```

#### 3. Eval阶段（每次推理）

```
CompiledModel.run(inputBuffers, outputBuffers)
  ├→ RegisterBuffer() — 为每个input/output tensor注册buffer到buffer_context_
  │    ├→ GetBufferRequirements(tensor)
  │    │    ├→ 如果有requirements → 检查buffer_type匹配
  │    │    │    └→ 匹配 → RegisterTensorBuffer() + 标记kTfLiteNonCpu
  │    │    │       ⚠️ buffer被注册到buffer_context_->tensor_buffers_
  │    │    └→ 不匹配 → buffer_requires_cpu_sync = true
  │    │    └→ 如果没有requirements → buffer_requires_cpu_sync = true
  │    │       ⚠️ CPU sync路径不注册到buffer_context_->tensor_buffers_
  │    └→ CPU sync: Lock buffer → SetCustomAllocation → 不注册到tensor_buffers_
  │
  └→ interpreter->Invoke()
       └→ 对每个DELEGATE node调用 Eval()
            └→ EvalHelper()
                 ├→ AllocateTensorBuffersIfNeeded()
                 │    ├→ 对input_tensor_ids_: AllocateAndRegisterBuffer()
                 │    ├→ 对output_tensor_ids_: AllocateAndRegisterBuffer() ← 报错点！
                 │    └→ 对internal_tensor_ids_: AllocateTensorBuffer() + RegisterBufferWithDispatchApi()
                 ├→ AttachBuffersToInvocationContextsIfNeeded()
                 ├→ CPU→NPU数据同步
                 ├→ ScheduleSyncExecution() / ScheduleAsyncExecution()
                 └→ NPU→CPU数据同步
```

#### 关键发现：optional tensor的完整语义

1. **kTfLiteOptionalTensor(-1)的含义**：某个op的某个output position是optional的，意味着这个output不被任何后续op使用。tensor_index=-1表示这个position没有对应的tensor。

2. **TFLite分区层面**：`PartitionGraphIntoIndependentNodeSubsets`在构建`NodeSubset`时已跳过optional tensor。所以`params.input_tensors`和`params.output_tensors`不包含optional tensor对应的tensor_id。

3. **Node层面**：`TfLiteOpaqueNodeOutputs(node, &indices, &num)`返回的indices可能包含-1。`TfLiteOpaqueNodeGetOutput(context, node, i)`当indices[i]==-1时返回nullptr。

4. **当前报错的精确路径**：
   - `output_tensor_ids_`中的某个tensor_id对应的tensor，在`ComputeRequirements`中被skip了（因为`TfLiteOpaqueNodeGetOutput`返回nullptr）
   - 该tensor没有注册buffer requirements
   - `CompiledModel::RegisterBuffer`中`GetBufferRequirements`返回not found，走了CPU sync路径
   - CPU sync路径不注册到`buffer_context_->tensor_buffers_`
   - `AllocateAndRegisterBuffer`行933调用`GetTensorBuffer`返回"not found"

5. **根因**：`ComputeRequirements`中skip nullptr tensor后，该tensor没有buffer requirements。但该tensor是有效的（在`output_tensor_ids_`中），后续流程需要它的buffer。**skip不是正确的修复——应该确保所有有效的IO tensor都有buffer requirements**。

### 已读文件清单

| 文件 | 路径 | 行数 | 状态 |
|------|------|------|------|
| dispatch_delegate_kernel.cc | `~/src/LiteRT-src/litert/runtime/dispatch/` | ~1311 | ✅完整阅读 |
| dispatch_delegate_kernel.h | `~/src/LiteRT-src/litert/runtime/dispatch/` | ~200 | ✅完整阅读 |
| external_litert_buffer_context.cc | `~/src/LiteRT-src/litert/runtime/` | ~150 | ✅完整阅读 |
| external_litert_buffer_context.h | `~/src/LiteRT-src/litert/runtime/` | ~250 | ✅完整阅读 |
| litert_dispatch_device_context.cc | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | ~200 | ✅完整阅读 |
| litert_dispatch_device_context.h | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | ~120 | ✅完整阅读 |
| litert_dispatch_invocation_context.cc | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | ~680 | ✅完整阅读 |
| litert_dispatch_invocation_context.h | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | ~130 | ✅完整阅读 |
| dispatch_api.cc | `~/src/LiteRT-src/litert/vendors/mediatek/dispatch/` | ~400 | ✅完整阅读 |
| litert_dispatch.cc | `~/src/LiteRT-src/litert/runtime/dispatch/` | ~350 | ✅关键函数阅读 |
| litert_compiled_model.cc | `~/src/LiteRT-src/litert/c/` | ~部分 | ✅关键函数阅读 |
| compiled_model.cc | `~/src/LiteRT-src/litert/runtime/` | ~部分 | ✅Run/RegisterBuffer阅读 |
| LiteRtEngine.kt | `D:\AS\PMouse\app\src\main\java\com\PMouse\core\` | ~180 | ✅完整阅读 |
