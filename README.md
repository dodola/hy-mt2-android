# Hy-MT2 on Android (Rockchip / Qualcomm)

在 Android 手机和开发板上本地运行腾讯混元翻译模型 [Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2) 1.8B 的 App 与工具链。
基于 llama.cpp，包含 1.25-bit（STQ1_0）内核移植、预填充优化、线程池修复，以及按 SoC 自动选择后端和线程策略的 Kotlin/Compose 应用。

Run Tencent's Hy-MT2 1.8B translation model fully on-device on Android: a llama.cpp-based JNI engine with the 1.25-bit
STQ1_0 kernel, a faster prefill kernel, a threadpool fix, and a Compose app that picks backend and core pinning per SoC.

## 状态 / Status

| 平台 | 状态 | 说明 |
|---|---|---|
| Rockchip RK3588 | **已在真机实测** | CPU（NEON dotprod）路径，见下方数据 |
| Qualcomm Snapdragon | **已构建，未在真机验证** | Hexagon NPU / Adreno OpenCL / CPU(i8mm) 已编译并打进 APK；没有骁龙设备，没跑过，文档中的速度只是外推估算 |

RK3588 的 Mali GPU 和 NPU 在测试固件上走不通（OpenCL 后端不接受 Mali、Vulkan 只有 1.1、NPU 驱动 v0.8.2 过旧）。详见 [docs/rk3588.md](docs/rk3588.md)。

## 实测数据（RK3588，4×Cortex-A76 大核）

| 模型 | 大小 | 预填充 pp (t/s) | 解码 tg (t/s) |
|---|---|---|---|
| **STQ1_0 1.25bit + `token_embd` 降为 Q4_0（推荐）** | 375 MB | 46.5 | **24.8**（命令行）/ 22.2（App 内） |
| STQ1_0 原版 | 436 MB | 32.5（优化后 46.2） | 21.7–22.4 |
| Q4_0（4x4 repack） | 1.0 GB | **77.1** | 19.2（App 内 18.0） |
| Q4_K_M | 1.05 GB | 60.5 | 17.8 |

解码受内存带宽限制，模型越小越快；预填充受计算限制，Q4_0 最快。完整表格与复现方法：[docs/benchmarks.md](docs/benchmarks.md)。

## 做了什么

- **STQ1_0 内核移植**：Tencent 的 1.25-bit 模型依赖上游 llama.cpp 未合并的 [PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)，
  这里把它移植到较新的 master（`patches/llama.cpp/0001–0003`，原作者 jinlongsong）。
- **预填充 +43%**：给 STQ1_0 写了 2×2 dotprod tile 内核（每个权重块解码一次、与两列激活相乘），输出与原内核逐字节一致（`0004`）。
- **解码 +14%**：`token_embd`（同时是 lm_head，占模型近一半）从 Q6_K 降为 Q4_0（`scripts/requant_embd.py`），抽样的中/英/日译文基本一致。
- **线程池修复**：`GGML_BACKEND_DL` 构建下，llama.cpp 只给其中一个 CPU 后端设置线程池，调度器却在另一个后端上计算，
  导致每个 token 都新建一批线程、核心被降到最低频。修复后 App 内解码 17 → 22 t/s（`0005`）。
- **Android App**（`android/`）：JNI 引擎 + Compose 翻译界面、38 种语言、官方 Hy-MT2 提示词模板、流式输出、SoC 检测、
  大核绑定、双线程池策略、无头基准（`BenchActivity`）。

## 快速开始

需要：Android SDK + NDK 28.2.13676358、CMake、Ninja、JDK 17。

```bash
# 1. 获取并打补丁 llama.cpp（固定在 master de7fa0a）
scripts/setup_llama.sh

# 2. 准备模型（见下）后，构建并安装 App
cd android
./gradlew-run.sh :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 模型

模型文件不在仓库里。1.25-bit 模型来自 [AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF](https://huggingface.co/AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF)，
Q4_0 来自 [unsloth/Hy-MT2-1.8B-GGUF](https://huggingface.co/unsloth/Hy-MT2-1.8B-GGUF)。使用前请遵守 Hy-MT2 的许可。

```bash
# Tencent 文件里 STQ1_0 的类型 id 是 42，上游 PR 里是 43，改头部即可（数据不动）
python3 -I scripts/remap_gguf_types.py hymt2-1.8b-stq1_0.gguf 42:43
# 可选：把 token_embd 降为 Q4_0/Q5_0，解码更快
python3 -I scripts/requant_embd.py hymt2-1.8b-stq1_0.gguf hymt2-1.8b-stq1_0-embq4_0.gguf q4_0
# 推到 App 私有目录（adb push 到 sdcard 的文件 App 读不了）
adb push hymt2-1.8b-stq1_0-embq4_0.gguf /data/local/tmp/
adb shell run-as com.hymt2.app cp /data/local/tmp/hymt2-1.8b-stq1_0-embq4_0.gguf files/
```

也可以用 App 里的 **Import** 按钮选择文件。

### 命令行基准

```bash
scripts/build_android_cpu.sh                      # armv8.2-a+dotprod+fp16（RK3588 / A76）
adb push build/cpu-a76/bin/. /data/local/tmp/hymt2/lib/
adb shell "cd /data/local/tmp/hymt2 && LD_LIBRARY_PATH=lib taskset f0 ./lib/llama-bench -m <model>.gguf -t 4 -p 64 -n 32"
```

`taskset f0` 把进程限制在 RK3588 的大核（cpu4–7）。线程数不要超过绑定的核数，否则预填充会塌到约 10 t/s。

### 高通

```bash
scripts/build_snapdragon.sh          # Docker 里构建 Hexagon / Adreno OpenCL / CPU(i8mm)
scripts/stage_snapdragon_libs.sh     # 拷到 android/app/src/main/jniLibs/arm64-v8a/
```

运行方式、各量化格式在各后端的支持矩阵、基准清单见 [docs/snapdragon.md](docs/snapdragon.md)。
按源码，Hexagon 和 Adreno 支持 Q4_0 但不支持 STQ1_0；STQ1_0 只走 CPU。**这些路径尚未在骁龙真机上验证。**

## 已知限制

- 2-bit（Q2_0C）模型不支持：上游没有对应内核，格式未公开。
- App 内解码约 22 t/s，命令行 24.8 t/s，还差一点。
- 没有单元测试；`SocDetector`、`CpuTopology` 已写成便于测试的纯函数。
- 本仓库的 Android 前端参考了 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) 的交互思路，没有复用其代码。

## 目录

```
android/            Kotlin + Compose 应用与 JNI 引擎
patches/llama.cpp/  对 llama.cpp 的 5 个补丁
scripts/            构建、模型转换、基准脚本
docs/               benchmarks / rk3588 / snapdragon
```

## 致谢

[Tencent Hunyuan Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2)、[llama.cpp](https://github.com/ggml-org/llama.cpp)、
STQ1_0 内核作者（PR #22836）。
