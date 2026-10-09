# Hy-MT2 on Snapdragon (Android) — build, support matrix, run

Status: **built and statically verified, NOT run on a Snapdragon device** (only an RK3588 board was available).
Treat all Snapdragon performance claims as unmeasured until the checklist at the bottom is done.

## Build

    scripts/build_snapdragon.sh          # needs Docker; pulls ghcr.io/snapdragon-toolchain/arm64-android:v0.7 (7.4 GB, has NDK r29 + OpenCL SDK + Hexagon SDK)
    # -> build/snapdragon/pkg/llama.cpp/{bin,lib}

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
Android 12+ may require the unsigned-PD setup for HTP; if the session fails to open, the log says `unsigned PD` / `fastrpc` — run with `GGML_HEXAGON_VERBOSE=1`.

## Benchmark checklist (needs a Snapdragon device)
- [ ] `--list-devices` shows HTP0 and GPUOpenCL; note SoC (`getprop ro.soc.model`), HTP arch line in log (v73/75/79/81).
- [ ] llama-bench pp128/tg64 for: STQ1_0@CPU, Q4_0@CPU, Q4_0@HTP0, Q4_0@GPUOpenCL, Q8_0@HTP0, Q4_K_M@HTP0.
- [ ] Output sanity: translate one zh->en and en->zh sentence with each backend (`llama-completion --jinja`), compare against CPU F16/Q8_0 output (HTP/OpenCL kernels must not diverge).
- [ ] Thermal: 5-min loop, record tg drop; compare NPU vs CPU.
- [ ] Peak RSS (`dumpsys meminfo`), load time.
- [ ] Record results in docs/results.md.
