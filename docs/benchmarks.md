# Benchmarks - Hy-MT2 1.8B on RK3588 (4x A76 @2.3GHz, taskset f0, llama-bench, quiet board)

| Model | Size | pp64 t/s | tg32 t/s | Notes |
|---|---|---|---|---|
| STQ1_0 (1.25bit, original kernel) | 436 MB | 32.5 | 22.4 | upstream PR #22836 |
| STQ1_0 + 2x2 dotprod tile (fad3c74) | 436 MB | 46.2 | 21.7 | prefill +43%, output bit-identical |
| STQ1_0 + embd Q5_0 | 404 MB | 46.3 | 23.5 | scripts/requant_embd.py |
| **STQ1_0 + embd Q4_0 (recommended)** | 375 MB | 46.5 | **24.8** | decode +14%, spot-checked zh/en/ja translations match |
| Q4_0 (repacked 4x4 dotprod) | 1.0 GB | 77.1 | 19.2 | best prefill |
| Q4_K_M | 1.05 GB | 60.5 | 17.8 | |

Take-aways
- Decode is memory-bound: smaller weights (incl. the 203 MB Q6_K tied embedding / lm_head) win.
- Prefill is compute-bound: Q4_0 wins; STQ1_0 recovers most of the gap with the tile kernel.
- Short sentences (~30 prompt tokens + ~30 out): STQ1_0+embQ4_0 is about as fast as Q4_0 end-to-end at 1/3 the memory.
- In-app (RK3588, BenchActivity, STQ1_0+embQ4_0): pp128=46.6, tg120=22.2 t/s (CLI 24.8). It was 17 t/s before two fixes:
  1. llama.cpp attached the threadpool to only one of the CPU backend instances; with GGML_BACKEND_DL the scheduler computed on
     another one, so every decode graph spawned a disposable threadpool (thread creation per token, cores dropping to 408 MHz).
     Fixed in third_party/llama.cpp commit caadec8 (set threadpool on every CPU backend).
  2. Worker busy-polling (poll=50) is needed (17.8 -> 22.9 t/s) and is safe when threads <= allowed cores; it is now the default.

Not measured on RK3588: Mali GPU (unsupported), RK NPU (driver too old).
Reproduce: scripts/build_android_cpu.sh, then llama-bench with `taskset f0 -t 4`.


# Benchmarks - Hy-MT2 1.8B on Snapdragon 8 Elite (SM8750)

Device: HONOR PPG-AN00, Android 17, 15 GB RAM. CPU 2x prime @4.47 GHz (cpu6/7) + 6x performance @3.53 GHz (cpu0-5), i8mm + dotprod.
Adreno 830 (OpenCL 3.0), Hexagon v79 (HVX 6 threads, HMX 1, VTCM 8 MB). Build: `SD_DL=1 scripts/build_snapdragon.sh`
(llama.cpp `fad3c74`, `-march=armv8.7a+fp16+dotprod+i8mm`). Single phone, quiet, no thermal soak; one run per cell unless a +/- is given.
`llama-bench` over adb unless marked "app" (BenchActivity, default RuntimePolicy plan).

## Backends, llama-bench pp128 / tg64 (t/s)

| Model | Backend | Threads | pp128 | tg64 |
|---|---|---|---|---|
| Q4_0 (1.0 GB) | **Hexagon HTP0** | - | **1916.3 +/- 7.2** | **43.6 +/- 0.2** |
| Q4_0 | CPU (repacked i8mm) | 6, unpinned | 162.6 +/- 6.3 | 42.1 +/- 0.4 |
| Q4_0 | Adreno OpenCL | - | 211.7 +/- 1.2 | 16.6 +/- 0.2 |
| STQ1_0 (436 MB) | CPU | 6, unpinned | 79.5 +/- 1.0 | 36.1 +/- 0.7 |
| STQ1_0 | CPU | 4, unpinned | 56.5 +/- 0.2 | 26.0 (tg32) |
| STQ1_0 | CPU | 2, pinned cpu6+7 | 21.6 +/- 0.1 | hangs |

In the app (BenchActivity, default plan), same model files:

| Model | Backend | pp128 | tg64 | Model load |
|---|---|---|---|---|
| Q4_0 | Hexagon HTP0 | 1605.2 | 43.1 | 2.9 s |
| STQ1_0 | CPU, cpu0-5, 6 threads | 67.9 | 29.0 | 1.2 s |

One short sentence (`今天天气很好，我们去公园散步吧。`, 29 prompt tokens, 16-17 output tokens) through the app:
Q4_0 on HTP0 ttft 49 ms, prefill 597 t/s, decode 41.0 t/s; STQ1_0 on CPU ttft 934 ms, prefill 31.1 t/s, decode 24.0 t/s.
Short prompts leave the pools cold, so these are below the steady-state rows above. Both translations were correct.

## CPU thread scaling and core pinning (STQ1_0, llama-bench)

Unpinned, pp32 / tg16:

| Threads | pp32 | tg16 |
|---|---|---|
| 1 | 15.0 | 6.9 |
| 2 | 28.8 | 13.4 |
| 3 | 42.5 | 19.8 |
| 5 | 69.7 | 31.6 |
| 7 | 9.0 | hangs |
| 8 | 8.6 | hangs |

Pinned with `taskset`, pp64 / tg32:

| CPUs (mask) | Threads | pp64 | tg32 |
|---|---|---|---|
| cpu0-5 (3f) | 6 | 80.2-80.4 | 36.3-36.4 |
| cpu0-5 (3f) | 5 | 68.9 | 31.6 |
| cpu0-4 (1f) | 5 | 69.2 | 31.5 |
| cpu0-4 + cpu7 (bf) | 6 | 79.5 | 36.6 |
| all 8 (ff) | 6 | 77.5 | 33.6 +/- 3.6 |
| cpu6 only (40) | 1 | 50.3 | 21.2 |
| cpu7 only (80) | 1 | 49.6 | 22.2 |
| cpu0-4 + cpu6 (5f) | 6 | 15.1 | hangs |
| cpu0-6 (7f) | 7 | 134.9 | hangs |
| cpu6 + cpu7 (c0) | 2 | 11.8 | hangs |

"hangs" = no tg result within 40-45 s (pp finished). One prime core alone is ~3x a performance core, but spinning ggml threads on
cpu6 or on both prime cores stall the pool; root cause not found. The app therefore pins to cpu0-5 on Qualcomm. The only cell that beat
that was cpu0-6 at 7 threads for prefill (134.9), and its decode hangs, so it is not used.

## Take-aways

- Prefill: the NPU is ~12x the CPU for Q4_0 (1916 vs 163) and ~24x STQ1_0 on the CPU (1916 vs 80).
- Decode is bandwidth-bound: Q4_0 gets 43.6 on the NPU and 42.1 on the CPU; the 1.25-bit model reaches 36 on 6 performance cores with 0.43x the bytes.
- Adreno OpenCL decode (16.6) is the slowest path; the app only uses OpenCL when there is no HTP device.
- Not measured: Q8_0 / Q4_K_M on HTP, sustained-load thermals, peak RSS, en->zh quality diffs against a CPU reference, other Snapdragon SoCs.
