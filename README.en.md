[English](README.en.md) | [简体中文](README.md)

# Hy-MT2 on Android (Rockchip / Qualcomm)

Run Tencent's [Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2) 1.8B translation model fully on-device on Android phones and dev boards.
It is a llama.cpp-based JNI engine and Kotlin/Compose app that includes a port of the 1.25-bit (STQ1_0) kernel, a faster prefill kernel,
a threadpool fix, and per-SoC selection of backend and core pinning.

<p align="center"><img src="docs/images/app-translate.png" width="320" alt="Hy-MT2 Translate on RK3588"></p>
<p align="center"><sub>Screenshot from an RK3588 board: English to Chinese, 1.25-bit model, 4 big cores. The stats line comes from a very short text
(41 prompt tokens, 12 output tokens), where time-to-first-token and warm-up dominate, so it is slower than the steady-state benchmarks below.</sub></p>

## Status

| Platform | Status | Notes |
|---|---|---|
| Rockchip RK3588 | **Measured on a real device** | CPU (NEON dotprod) path, numbers below |
| Qualcomm Snapdragon | **Verified on one device** (Snapdragon 8 Elite) | Hexagon NPU, Adreno OpenCL and CPU all run; other SoCs (HTP v73/v75/v81, Adreno 7xx) untested, see [docs/snapdragon.md](docs/snapdragon.md) |

On the tested RK3588 firmware the Mali GPU and the NPU are not usable: the llama.cpp OpenCL backend rejects Mali, Vulkan is only 1.1, and the NPU driver (v0.8.2) is too old.
See [docs/rk3588.md](docs/rk3588.md).

## Measured results (RK3588, 4x Cortex-A76 big cores)

| Model | Size | Prefill pp (t/s) | Decode tg (t/s) |
|---|---|---|---|
| **STQ1_0 1.25-bit + `token_embd` as Q4_0 (recommended)** | 375 MB | 46.5 | **24.8** (CLI) / 22.2 (in app) |
| STQ1_0 original | 436 MB | 32.5 (46.2 after optimization) | 21.7-22.4 |
| Q4_0 (4x4 repack) | 1.0 GB | **77.1** | 19.2 (18.0 in app) |
| Q4_K_M | 1.05 GB | 60.5 | 17.8 |

Decode is memory-bandwidth bound, so smaller weights are faster; prefill is compute bound, where Q4_0 wins.
Full tables and how to reproduce them: [docs/benchmarks.md](docs/benchmarks.md).

## Measured results (Snapdragon 8 Elite / SM8750, HONOR PPG-AN00, Android 17)

| Model | Backend | Prefill pp128 (t/s) | Decode tg64 (t/s) |
|---|---|---|---|
| **Q4_0 (1.0 GB, recommended)** | **Hexagon NPU (HTP v79)** | **1916** (1605 in app) | **43.6** (43.1 in app) |
| Q4_0 | CPU, 6 threads | 163 | 42.1 |
| Q4_0 | Adreno 830 OpenCL | 212 | 16.6 |
| **STQ1_0 1.25-bit (436 MB)** | CPU, cpu0-5, 6 threads | 80 (68 in app) | 36 (29 in app) |

- NPU prefill is about 12x the CPU. Decode is bandwidth-bound, so NPU and CPU are close, and the 1.25-bit model reaches 36 t/s with under half the bytes.
- On the 8 Elite the ggml thread pool stalls when spinning threads share the two prime cores (cpu6/7) with others (prefill drops to 9-15 t/s, decode
  hangs), so the app pins to cpu0-5 only. Every pinning combination tried is in [docs/benchmarks.md](docs/benchmarks.md).
- One phone, quiet room, no sustained-load thermal test. Q8_0 / Q4_K_M on the NPU, peak memory and other Snapdragon SoCs are not measured.

## What was done

- **STQ1_0 kernel port.** Tencent's 1.25-bit model depends on the unmerged upstream llama.cpp [PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836).
  It is ported to a recent master here (`patches/llama.cpp/0001-0003`, original author jinlongsong).
- **Prefill +43%.** A 2x2 dotprod tile kernel for STQ1_0 decodes each weight block once and multiplies it with two activation columns.
  Output is byte-identical to the original kernel (`0004`).
- **Decode +14%.** `token_embd` (also the lm_head, almost half of the model) is requantized from Q6_K to Q4_0 (`scripts/requant_embd.py`).
  Spot-checked zh/en/ja translations are essentially unchanged.
- **Threadpool fix.** With `GGML_BACKEND_DL`, llama.cpp attached the threadpool to only one CPU backend instance while the scheduler computed on another,
  so every token spawned a fresh set of threads and the cores dropped to their lowest clock. After the fix, in-app decode went from 17 to 22 t/s (`0005`).
- **Android app** (`android/`). JNI engine and Compose translate screen, 38 languages, the official Hy-MT2 prompt templates, streaming output,
  SoC detection, big-core pinning, a two-threadpool policy, and a headless benchmark (`BenchActivity`).

## Quick start

Requires the Android SDK, NDK 28.2.13676358, CMake, Ninja and JDK 17.

```bash
# 1. Fetch llama.cpp (pinned to master de7fa0a) and apply the patches; if github.com is blocked set LLAMA_GIT_URL=<mirror>
scripts/setup_llama.sh

# 2. After preparing a model (see below), build and install the app
cd android
./gradlew-run.sh :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Models

**Download in the app**: open it, pick a model under "Download a model" and tap Download. Downloads resume, the SHA-256 is
checked, and the 1.25-bit file's type id (42 -> 43) is fixed automatically. Snapdragon with the Hexagon NPU is pointed at Q4_0; every other device at the 1.25-bit model.

**The default source is ModelScope**, reachable from mainland China; hf-mirror.com and Hugging Face are fallbacks only. All three serve
the same file names and SHA-256. Repos: [AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF](https://modelscope.cn/models/AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF),
[unsloth/Hy-MT2-1.8B-GGUF](https://modelscope.cn/models/unsloth/Hy-MT2-1.8B-GGUF). Model files are not in this repository; follow the Hy-MT2 license.

No network, or want another quantization? Manual install, checksums, and how to build without GitHub: [docs/models.md](docs/models.md).
The **Import** button also takes a local file.

### Command-line benchmark

```bash
scripts/build_android_cpu.sh                      # armv8.2-a+dotprod+fp16 (RK3588 / A76)
adb push build/cpu-a76/bin/. /data/local/tmp/hymt2/lib/
adb shell "cd /data/local/tmp/hymt2 && LD_LIBRARY_PATH=lib taskset f0 ./lib/llama-bench -m <model>.gguf -t 4 -p 64 -n 32"
```

`taskset f0` pins the process to the RK3588 big cores (cpu4-7). Never use more threads than pinned cores: prefill collapses to about 10 t/s.

### Qualcomm

```bash
SD_DL=1 scripts/build_snapdragon.sh # builds Hexagon / Adreno OpenCL / CPU (i8mm) inside Docker
scripts/stage_snapdragon_libs.sh     # copies them to android/app/src/main/jniLibs/arm64-v8a/
```

How to run, the quantization support matrix per backend, and a benchmark checklist are in [docs/snapdragon.md](docs/snapdragon.md).
According to the source, Hexagon and Adreno support Q4_0 but not STQ1_0, so STQ1_0 runs on the CPU only. **Verified on a Snapdragon 8 Elite (HONOR PPG-AN00, Android 17): Q4_0 on the Hexagon NPU gives about 1900 t/s prefill and 43 t/s decode; STQ1_0 on the CPU about 36 t/s decode.** The app needs the plugin-style backends from `SD_DL=1 scripts/build_snapdragon.sh`, not the default monolithic build.

## Known limitations

- The 2-bit (Q2_0C) model is not supported: there is no upstream kernel and the format is not public.
- In-app decode is about 22 t/s versus 24.8 t/s on the command line.
- Unit tests only cover `RuntimePolicy`, `CpuTopology` and `SocDetector` (9 JVM cases); the JNI engine and UI are untested.
- On Snapdragon 8 Elite the ggml thread pool stalls when threads land on the prime cores (cpu6/7); the app avoids them, root cause not found.
- The Android UI follows interaction ideas from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery); no code was reused.

## Layout

```
android/            Kotlin + Compose app and JNI engine
patches/llama.cpp/  5 patches against llama.cpp
scripts/            build, model conversion, benchmark scripts
docs/               models / benchmarks / rk3588 / snapdragon
```

## License

The code in this repository is released under the [MIT](LICENSE) license. `patches/llama.cpp/` contains derivative changes to llama.cpp (MIT) and stays under its license;
`0001-0003` come from upstream PR #22836 (author jinlongsong). **Model weights are not included** and are governed by Hy-MT2's own license; please check it yourself.

## Acknowledgements

[Tencent Hunyuan Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2), [llama.cpp](https://github.com/ggml-org/llama.cpp),
and the author of the STQ1_0 kernel (PR #22836).
