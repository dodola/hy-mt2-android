# Hy-MT2 Android app

On-device Hy-MT2 1.8B translation (Kotlin + Compose UI, C++/JNI engine on the patched
`third_party/llama.cpp`, branch `hymt2`, which carries the STQ1_0 1.25-bit kernel).

## Build

Requires the Android SDK (`~/Android/Sdk`), NDK `28.2.13676358`, CMake 3.22.1 (SDK).

```bash
cd android
./gradlew-run.sh :app:assembleDebug     # wrapper that ignores a dead global gradle proxy
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The native build uses `GGML_BACKEND_DL=ON` + `GGML_CPU_ALL_VARIANTS=ON`: seven
`libggml-cpu-android_armv*.so` variants ship in the APK and ggml picks the best one for the
CPU at startup (RK3588 / Cortex-A76 → `armv8.2_2`, dotprod). Optional GPU/NPU backends
(`libggml-opencl.so`, `libggml-hexagon.so`, `libggml-htp-v*.so`) are picked up automatically if
dropped into `app/src/main/jniLibs/arm64-v8a/`; absent backends are simply skipped.
Run `scripts/build_snapdragon.sh` then `scripts/stage_snapdragon_libs.sh` to stage the Snapdragon set (Adreno OpenCL, Hexagon
NPU + HTP skeletons); the manifest declares `libOpenCL.so`/`libcdsprpc.so` and the engine sets `ADSP_LIBRARY_PATH`.
Verified only that the APK with these libs installs and runs unchanged on RK3588 (backends don't register there); the
Hexagon/OpenCL offload itself has **not** been run on a Snapdragon device.

## Models

Searched in: `/sdcard/Android/data/com.hymt2.app/files/`, the app's `files/`, `/data/local/tmp/hymt2/`
(the latter is not readable by the app on most devices). Files pushed with `adb push` to the
sdcard dir are root-owned and **not readable** on the RK3588 box, so for a debug build copy via
`run-as`:

```bash
adb push models/hymt2-1.8b-stq1_0.gguf /data/local/tmp/hymt2/
adb shell run-as com.hymt2.app cp /data/local/tmp/hymt2/hymt2-1.8b-stq1_0.gguf files/
```

or use the in-app **Import** button (system file picker). Known names: `hymt2-1.8b-stq1_0.gguf`
(1.25-bit, gguf type id 43; remap Tencent's original with `scripts/remap_gguf_types.py file 42:43`),
`hymt2-1.8b-q4_0.gguf`, `hymt2-1.8b-q4.gguf` (Q4_K_M).

## Runtime policy (`device/`)

* `SocDetector` – classifies Qualcomm / Rockchip / other from `ro.soc.*`, `ro.board.platform`.
* `CpuTopology` – per-CPU max frequency from sysfs → fastest cluster; also reads
  `Cpus_allowed_list` because the app's cpuset can be narrower than the machine (the RK3588 box
  confines top-app to cpu4-7).
* `RuntimePolicy` – decode threadpool = up to 4 fastest allowed cores, strictly pinned; prefill
  pool = all allowed cores on Rockchip, performance cluster elsewhere. Qualcomm offloads to
  OpenCL/Hexagon only when those backends loaded **and** the model quant is supported
  (Q4_0; STQ1_0 stays on CPU).

## Headless benchmark / smoke test

```bash
adb shell am start -n com.hymt2.app/.bench.BenchActivity \
  --es model /data/user/0/com.hymt2.app/files/hymt2-1.8b-stq1_0.gguf \
  --es text '今天天气真好，我们一起去公园散步吧。' --es target en \
  [--ei pp 128] [--ei tg 64] [--ei threads 4] [--ei threads_batch 8] [--ez nopin true] [--ez kvq8 true]
adb logcat -d -s HYMT_BENCH
```

Prints `RESULT ... pp128=… tg64=…`, the translation and `STATS` (TTFT, prefill/decode tok/s).
The activity shows a window on purpose: a windowless process is demoted to a background cpuset.

Threadpool defaults (measured on RK3588): non-strict pinning, and busy-polling (`poll=50`) only when the
process has spare cores beyond the pool size; with `strict=1` or polling on a full cpuset, warm-up was
10-20x slower. Override without rebuilding: `adb shell setprop debug.hymt.strict 0|1`, `debug.hymt.poll 0..100`
(`scripts/pool_matrix.sh` sweeps them).

## Layout

`engine/` `InferenceEngine` interface + `LlamaEngine` (single owner thread) + `NativeBridge` (JNI) ·
`device/` SoC/CPU detection and policy · `model/` known models, discovery, import ·
`translate/` language list and official Hy-MT2 prompt templates · `ui/` Compose screen + ViewModel ·
`bench/` headless benchmark · `cpp/` JNI + llama.cpp wrapper.
