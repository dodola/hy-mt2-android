#!/usr/bin/env bash
# Cross-build llama.cpp (patched, STQ1_0) for Snapdragon Android: CPU (armv8.7a+i8mm),
# Adreno OpenCL, Hexagon HTP (NPU). Runs inside Qualcomm's toolchain image, which bundles
# NDK, OpenCL SDK and Hexagon SDK -- nothing else to install except Docker.
# Output: build/snapdragon/pkg/llama.cpp/{bin,lib}   (idempotent; re-run = incremental)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/llama.cpp"
IMG="${SD_IMAGE:-ghcr.io/snapdragon-toolchain/arm64-android:v0.7}"
PRESET="${SD_PRESET:-arm64-android-snapdragon-release}"
# SD_DL=1 builds the backends as dlopen-able plugins (GGML_BACKEND_DL=ON, exports ggml_backend_init), which is
# what the Android app needs; the default monolithic build is for the adb llama-bench package.
SD_DL="${SD_DL:-0}"
OUT="${SD_OUT:-$ROOT/build/snapdragon$([ "$SD_DL" = 1 ] && echo -dl)}"; mkdir -p "$OUT"
EXTRA=""; [ "$SD_DL" = 1 ] && EXTRA="-DGGML_BACKEND_DL=ON"
# preset file must sit in the source root (untracked there)
cp -u "$SRC/docs/backend/snapdragon/CMakeUserPresets.json" "$SRC/CMakeUserPresets.json"
docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" \
  -v "$SRC:/workspace" -v "$OUT:/out" -w /workspace "$IMG" bash -c "
  cmake --preset $PRESET -B /out/build -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=ON -DLLAMA_CURL=OFF $EXTRA &&
  cmake --build /out/build -j${JOBS:-$(nproc)} &&
  cmake --install /out/build --prefix /out/pkg/llama.cpp"
echo "installed -> $OUT/pkg/llama.cpp"
