# Hy-MT2 on Snapdragon (Android) — build, support matrix, run

Status: **verified on one device** - HONOR PPG-AN00, Snapdragon 8 Elite (SM8750), Hexagon v79, Adreno 830, 15 GB, Android 17.
CPU, Adreno OpenCL and Hexagon HTP all run, from `llama-bench` and from the app (`BenchActivity`). Numbers below are from that
one phone; other SoCs (v73/v75/v81 HTP, Adreno 7xx) are still untested.

## Measured results (SM8750, Hy-MT2 1.8B)

`llama-bench`, pp128 / tg64 (STQ1_0 rows: pp64 / tg32 with `taskset`), quiet phone, no thermal soak:

| Model | Backend | pp (t/s) | tg (t/s) |
|---|---|---|---|
| Q4_0 | **Hexagon HTP0** | **1916** | **43.6** |
| Q4_0 | CPU, 6 threads | 162.6 | 42.1 |
| Q4_0 | Adreno OpenCL | 211.7 | 16.6 |
| STQ1_0 (1.25-bit) | CPU, cpu0-5 | 80.4 | 36.3 |

In the app (`BenchActivity`, default plan): Q4_0 on HTP0 pp128 1605 / tg64 43.1 (model load 2.9 s); STQ1_0 on CPU pp128 67.9 / tg64 29.0.
Both translate `今天天气很好，我们去公园散步吧。` correctly (*The weather is great today. Let's go for a walk in the park.* /
*...nice today; let's go for a walk in the park.*).

Full tables (thread scaling, every pinning mask, in-app and short-sentence numbers, test conditions): [benchmarks.md](benchmarks.md#benchmarks---hy-mt2-18b-on-snapdragon-8-elite-sm8750).

Takeaways: HTP is the clear prefill winner (~12x CPU) and ties the CPU on decode, which is bandwidth-bound; OpenCL decode is slow, so the app
prefers HTP and only falls back to OpenCL when there is no HTP device. STQ1_0 stays CPU-only (no HTP/OpenCL kernel) and is the smallest option.

### Prime cores stall the thread pool (SM8750)
cpu6/cpu7 are the 4.47 GHz prime cores. One of them alone is fast (1 thread: pp 50 / tg 21 t/s, 3x a performance core), but ggml's spinning
pool stalls when threads land on them together with others:

| Threads / cores | pp64 (t/s) | tg32 (t/s) |
|---|---|---|
| 6 on cpu0-5 | 80.4 | 36.3 |
| 6 on cpu0-4 + cpu7 | 79.5 | 36.6 |
| 6 on cpu0-4 + cpu6 | 15.1 | hangs |
| 7-8 threads (cpu6/7 included) | 9 | hangs |
| 2 on cpu6+cpu7 | 11.8 | hangs |

So the app pins to the performance tier only (`RuntimePolicy.withoutPrimeCores`) and uses the same cores for prefill. Cause not root-caused
(scheduler/OEM policy vs. the ggml barrier); the symptom is reproducible with plain `llama-bench` and `taskset`.

## Build

    scripts/build_snapdragon.sh          # monolithic build for the adb llama-bench package. Needs Docker; pulls ghcr.io/snapdragon-toolchain/arm64-android:v0.7 (7.4 GB, has NDK r29 + OpenCL SDK + Hexagon SDK)
    # -> build/snapdragon/pkg/llama.cpp/{bin,lib}

For the **Android app** build the backends as plugins instead: `SD_DL=1 scripts/build_snapdragon.sh` (-> `build/snapdragon-dl`, plugins in
`pkg/llama.cpp/bin`), then `scripts/stage_snapdragon_libs.sh`. The monolithic build's `libggml-opencl.so`/`libggml-hexagon.so` do **not** export
`ggml_backend_init`, so the app's `GGML_BACKEND_DL` loader skips them silently (release builds log nothing; the app now retries and logs the error).

Uses the upstream preset `arm64-android-snapdragon-release`: `-march=armv8.7a+fp16+dotprod+i8mm`, GGML_OPENCL=ON (Adreno kernels),
GGML_HEXAGON=ON (HTP skeletons v73/v75/v79/v81), plus the STQ1_0 patch (llama.cpp PR #22836) on branch `hymt2`.
**The CPU backend in this package requires i8mm** (Snapdragon 8 Gen 1 and newer; 8+ Gen1, 8 Gen2/3, 8 Elite...).
It will SIGILL on older/non-i8mm cores (e.g. RK3588 A76) — use the `GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS` build (build/allvariants) for those / for a single APK.

Verified: all `.so` are aarch64 ELF (HTP skeletons are Hexagon DSP ELF), export `ggml_backend_{cpu,opencl,hexagon}_reg`,
and `libggml-cpu.so` contains `ggml_vec_dot_stq1_0_q8_K` (NEON/i8mm-dotprod) + STQ1_0 quantize/dequantize.

## Which quant runs where (from source: ggml-hexagon.cpp, ggml-opencl.cpp, ggml-cpu)

| Hy-MT2-1.8B GGUF | CPU (i8mm) | Adreno OpenCL | Hexagon NPU (HTP) |
|---|---|---|---|
| STQ1_0 (1.25-bit, 440 MB) | **yes** (PR #22836 NEON kernel) | no (falls back to CPU) | no |
| Q2_0C 2-bit (Tencent) | no kernel upstream | no | no |
| Q4_0 | yes (repacked 4x8 i8mm) | **yes, Adreno-optimised GEMV+GEMM** | **yes (repack, HMX/HVX)** |
| Q8_0 | yes | yes (GEMM) | yes |
| Q4_K_M / Q5_K / Q6_K | yes | yes (Adreno GEMM; A7x lm_head falls back to CPU) | yes (Q2_K..Q6_K, MXFP4, IQ4_NL, Q4_1 too) |
| Q1_0 | yes | yes (mul_mat) | no |
| MXFP4 | yes | yes | yes |

Note: token_embd in the Tencent GGUFs is Q6_K (type 14); output uses tied embeddings, so the lm_head runs as Q6_K — supported on all three backends.

### Recommendation
1. **Best throughput, any recent Snapdragon: `Q4_0` on Hexagon NPU (`--device HTP0`, `-ngl 99`)** — upstream reports ~170 pp / ~50 tg t/s for a 1.2B Q4_0 on v79; 1.8B should be roughly 100 pp / 30–35 tg (estimate, unmeasured). Lowest CPU/thermal load.
2. **Q4_0 on Adreno OpenCL (`--device GPUOpenCL`)** for devices where HTP skel is unavailable (e.g. no signed/unsigned PD access) — Adreno 7xx/8xx only; A6x phones are likely unsupported.
3. **STQ1_0 on CPU (4 prime/perf cores)** for smallest footprint (440 MB) and lowest memory bandwidth; decode is bandwidth-bound so it is fast, prefill is slower than Q4_0-on-NPU.
Run `llama-bench` for all three and pick per-device (checklist below); the app should do this once and cache the choice.

Models: the app downloads them itself, see [models.md](models.md). The commands below are for the adb `llama-bench` workflow.

## Run (adb)

    adb push build/snapdragon/pkg/llama.cpp /data/local/tmp/llama.cpp
    adb push models/hymt2-1.8b-q4_0.gguf     /data/local/tmp/gguf/
    adb push models/hymt2-1.8b-stq1_0.gguf   /data/local/tmp/gguf/     # remapped type ids, see scripts/remap_gguf_types.py
    adb shell
    cd /data/local/tmp/llama.cpp
    export LD_LIBRARY_PATH=$PWD/lib ADSP_LIBRARY_PATH=$PWD/lib        # ADSP_LIBRARY_PATH is how the DSP finds libggml-htp-v*.so

    # Hexagon NPU
    ./bin/llama-bench -m /data/local/tmp/gguf/hymt2-1.8b-q4_0.gguf --device HTP0 -ngl 99 -p 128 -n 64
    #   knobs: GGML_HEXAGON_NDEV=1  GGML_HEXAGON_VERBOSE=1  GGML_HEXAGON_ARCH=v79 (force)  GGML_HEXAGON_NHVX / NHMX
    #   multi-session for >3.5 GB models: --device HTP0:0,HTP0:1   (not needed for 1.8B)
    # Adreno GPU
    ./bin/llama-bench -m .../hymt2-1.8b-q4_0.gguf --device GPUOpenCL -ngl 99 -p 128 -n 64
    # CPU (STQ1_0), pin to perf cores: check `cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`
    taskset <mask of prime+perf cores> ./bin/llama-bench -m .../hymt2-1.8b-stq1_0.gguf --device none -ngl 0 -t 4 -p 128 -n 64
    # list devices
    ./bin/llama-bench --list-devices

`scripts/snapdragon/run.py --target adb --devices HTP0 -- llama-bench ...` in the llama.cpp tree does the same env setup.
In an app the DSP skeleton must be found through `ADSP_LIBRARY_PATH`. Pointing it at `nativeLibraryDir` (`/data/app/~~...==/...`) fails with
`failed to open session ... error 0x80000406`; the app copies `libggml-htp-v*.so` to `files/htp` and uses that. Android 12+ may require the unsigned-PD setup for HTP; if the session fails to open, the log says `unsigned PD` / `fastrpc` — run with `GGML_HEXAGON_VERBOSE=1`.

## Benchmark checklist (SM8750 done where ticked)
- [x] `--list-devices` shows HTP0 and GPUOpenCL; SM8750, HTP arch v79.
- [x] llama-bench for STQ1_0@CPU, Q4_0@CPU, Q4_0@HTP0, Q4_0@GPUOpenCL. Still open: Q8_0@HTP0, Q4_K_M@HTP0.
- [x] In-app translation zh->en on HTP0 (Q4_0) and CPU (STQ1_0). Still open: en->zh, OpenCL, and a diff against CPU F16/Q8_0 output (HTP/OpenCL kernels must not diverge).
- [ ] Thermal: 5-min loop, record tg drop; compare NPU vs CPU.
- [ ] Peak RSS (`dumpsys meminfo`), load time.
- [ ] Record results in docs/results.md.
