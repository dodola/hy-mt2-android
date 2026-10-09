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
| Qualcomm Snapdragon | **Built, not verified on hardware** | Hexagon NPU / Adreno OpenCL / CPU (i8mm) compile and are packaged in the APK. No Snapdragon device was available, so nothing was run; speeds in the docs are extrapolated estimates |

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
# 1. Fetch llama.cpp (pinned to master de7fa0a) and apply the patches
scripts/setup_llama.sh

# 2. After preparing a model (see below), build and install the app
cd android
./gradlew-run.sh :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Models

Model files are not in this repository. The 1.25-bit model comes from [AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF](https://huggingface.co/AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF),
Q4_0 from [unsloth/Hy-MT2-1.8B-GGUF](https://huggingface.co/unsloth/Hy-MT2-1.8B-GGUF). Follow the Hy-MT2 license when using them.

```bash
# Tencent's file uses type id 42 for STQ1_0, the upstream PR uses 43; only the header changes, tensor data is untouched
python3 -I scripts/remap_gguf_types.py hymt2-1.8b-stq1_0.gguf 42:43
# Optional: requantize token_embd to Q4_0/Q5_0 for faster decode
python3 -I scripts/requant_embd.py hymt2-1.8b-stq1_0.gguf hymt2-1.8b-stq1_0-embq4_0.gguf q4_0
# Put it in the app's private dir (files pushed to the sdcard with adb are not readable by the app)
adb push hymt2-1.8b-stq1_0-embq4_0.gguf /data/local/tmp/
adb shell run-as com.hymt2.app cp /data/local/tmp/hymt2-1.8b-stq1_0-embq4_0.gguf files/
```

You can also pick the file with the **Import** button in the app.

### Command-line benchmark

```bash
scripts/build_android_cpu.sh                      # armv8.2-a+dotprod+fp16 (RK3588 / A76)
adb push build/cpu-a76/bin/. /data/local/tmp/hymt2/lib/
adb shell "cd /data/local/tmp/hymt2 && LD_LIBRARY_PATH=lib taskset f0 ./lib/llama-bench -m <model>.gguf -t 4 -p 64 -n 32"
```

`taskset f0` pins the process to the RK3588 big cores (cpu4-7). Never use more threads than pinned cores: prefill collapses to about 10 t/s.

### Qualcomm

```bash
scripts/build_snapdragon.sh          # builds Hexagon / Adreno OpenCL / CPU (i8mm) inside Docker
scripts/stage_snapdragon_libs.sh     # copies them to android/app/src/main/jniLibs/arm64-v8a/
```

How to run, the quantization support matrix per backend, and a benchmark checklist are in [docs/snapdragon.md](docs/snapdragon.md).
According to the source, Hexagon and Adreno support Q4_0 but not STQ1_0, so STQ1_0 runs on the CPU only. **These paths have not been verified on a Snapdragon device.**

## Known limitations

- The 2-bit (Q2_0C) model is not supported: there is no upstream kernel and the format is not public.
- In-app decode is about 22 t/s versus 24.8 t/s on the command line.
- No unit tests yet; `SocDetector` and `CpuTopology` are written as pure functions to make testing easy.
- The Android UI follows interaction ideas from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery); no code was reused.

## Layout

```
android/            Kotlin + Compose app and JNI engine
patches/llama.cpp/  5 patches against llama.cpp
scripts/            build, model conversion, benchmark scripts
docs/               benchmarks / rk3588 / snapdragon
```

## License

The code in this repository is released under the [MIT](LICENSE) license. `patches/llama.cpp/` contains derivative changes to llama.cpp (MIT) and stays under its license;
`0001-0003` come from upstream PR #22836 (author jinlongsong). **Model weights are not included** and are governed by Hy-MT2's own license; please check it yourself.

## Acknowledgements

[Tencent Hunyuan Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2), [llama.cpp](https://github.com/ggml-org/llama.cpp),
and the author of the STQ1_0 kernel (PR #22836).
