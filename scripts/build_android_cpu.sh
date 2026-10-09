#!/usr/bin/env bash
# Build llama.cpp CLI tools for Android arm64 (CPU only).
# usage: build_android_cpu.sh <march> <out-dir-name>
#   march example: armv8.2-a+dotprod+fp16   (RK3588 / Cortex-A76)
#                  armv8.6-a+i8mm+dotprod+fp16 (Snapdragon 8 Gen1+)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
MARCH="${1:-armv8.2-a+dotprod+fp16}"
NAME="${2:-cpu-a76}"
B="$ROOT/build/$NAME"
cmake -S "$ROOT/third_party/llama.cpp" -B "$B" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-march=$MARCH -O3" -DCMAKE_CXX_FLAGS="-march=$MARCH -O3" \
  -DGGML_NATIVE=OFF -DGGML_LLAMAFILE=ON -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build "$B" -j28 --target llama-bench llama-completion llama-gguf
