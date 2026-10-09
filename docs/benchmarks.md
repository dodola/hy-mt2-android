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

Not measured: any Snapdragon device (see docs/snapdragon.md), Mali GPU (unsupported), RK NPU (driver too old).
Reproduce: scripts/build_android_cpu.sh, then llama-bench with `taskset f0 -t 4`.
