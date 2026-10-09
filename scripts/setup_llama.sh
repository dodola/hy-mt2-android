#!/usr/bin/env bash
# Clone llama.cpp at the pinned master commit and apply our patches (STQ1_0 kernel from upstream PR #22836,
# 2x2 dotprod tile, threadpool fix). Result: third_party/llama.cpp on branch hymt2.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=de7fa0a
DIR="${LLAMA_DIR:-$ROOT/third_party/llama.cpp}"
if [ ! -d "$DIR/.git" ]; then
  mkdir -p "$(dirname "$DIR")"
  git clone https://github.com/ggml-org/llama.cpp "$DIR"
fi
cd "$DIR"
git checkout -q -B hymt2 "$BASE"
git -c user.name=hymt2 -c user.email=hymt2@local am --3way "$ROOT"/patches/llama.cpp/*.patch
echo "llama.cpp ready: $(git log --oneline | head -1)"
