#!/usr/bin/env bash
# Copy the Snapdragon-only backend libraries (built by build_snapdragon.sh) into the app's jniLibs.
# The app ships its own libggml-base/libllama/CPU variants; only the optional backends come from here.
# They are loaded at runtime by ggml_backend_load_all_from_path and skipped on devices that lack the hardware.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/build/snapdragon/pkg/llama.cpp/lib}"
DST="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DST"
for f in libggml-opencl.so libggml-hexagon.so "$SRC"/libggml-htp-v*.so; do
  f="$(basename "$f")"
  [ -f "$SRC/$f" ] || { echo "missing $SRC/$f" >&2; exit 1; }
  cp -f "$SRC/$f" "$DST/$f"
  echo "staged $f"
done
