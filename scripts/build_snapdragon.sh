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
OUT="$ROOT/build/snapdragon"; mkdir -p "$OUT"
# preset file must sit in the source root (untracked there)
cp -u "$SRC/docs/backend/snapdragon/CMakeUserPresets.json" "$SRC/CMakeUserPresets.json"
docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" \
  -v "$SRC:/workspace" -v "$OUT:/out" -w /workspace "$IMG" bash -c "
  cmake --preset $PRESET -B /out/build -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=ON -DLLAMA_CURL=OFF &&
  cmake --build /out/build -j${JOBS:-$(nproc)} &&
  cmake --install /out/build --prefix /out/pkg/llama.cpp"
echo "installed -> $OUT/pkg/llama.cpp"
