# Models: download, sources, manual install

The app downloads models itself; nothing needs to be built or converted. Model weights are **not** in this repository and stay
under the Hy-MT2 license (Apache-2.0 on the Hugging Face pages of both repos below; read it before redistributing).

## In-app download

Open the app. Models that are not installed yet are listed under **Download a model**, with the one that suits the device first:

| Device | Recommended | Why |
|---|---|---|
| Snapdragon with the Hexagon backend loaded | Q4_0 (1.0 GB) | Runs on the NPU: pp 1916 / tg 43.6 t/s on SM8750 |
| Everything else (RK3588, Snapdragon without HTP, ...) | 1.25-bit STQ1_0 (440 MB) | CPU-only, smallest, fastest decode off the NPU |

Tap **Download**. The first model downloaded is loaded automatically. Use Wi-Fi, the files are 0.4–1.1 GB.

| Model | File saved as | Size | Source repo |
|---|---|---|---|
| 1.25-bit (STQ1_0) | `hymt2-1.8b-stq1_0.gguf` | 461,860,800 B | `AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF` / `Hy-MT2-1.8B-1.25Bit.gguf` |
| Q4_0 | `hymt2-1.8b-q4_0.gguf` | 1,079,997,408 B | `unsloth/Hy-MT2-1.8B-GGUF` / `Hy-MT2-1.8B-Q4_0.gguf` |

SHA-256 (identical on every source below, checked by the app before the file is used):

```
cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93  Hy-MT2-1.8B-1.25Bit.gguf
5458acf22435287fb9aa7a5bf6cc041806df15d54cea020cbad25ac95e2d8a21  Hy-MT2-1.8B-Q4_0.gguf
```

Only these two are offered because they are the ones benchmarked on a device. The repo also has a `Hy-MT2-1.8B-1.25bit-v2.gguf`
of the same size that has **not** been tested here, so it is not in the catalog.

### Download sources

Tried in this order, first success wins (`ModelSources.default` in `model/ModelSpec.kt`):

1. **ModelScope** (魔搭) - `https://modelscope.cn/models/<repo>/resolve/master/<file>`. Same repo ids and file names, same SHA-256,
   served from a mainland-China CDN (`cdn-lfs-cn-*.modelscope.cn`). This is the default because `huggingface.co` and `github.com`
   are often slow or unreachable from China.
2. **hf-mirror.com** - `https://hf-mirror.com/<repo>/resolve/main/<file>`. Fallback. Note it may redirect large files to Hugging Face's
   own CDN, so it is not a reliable route inside China.
3. **Hugging Face** - `https://huggingface.co/<repo>/resolve/main/<file>`. Fallback.

A source that cannot connect within 8 s, returns an error, or drops the connection is skipped and the next one **continues the same
partial file**. If every source fails, the error lists each one by name.

### Behaviour worth knowing

- **Resumable.** Bytes go to `<name>.gguf.part` in the app's files dir. Tap **Pause**, lose the connection, or have the process killed:
  the next **Download** continues with an HTTP `Range` request. If a server ignores `Range`, the download restarts from zero.
- **Verified, then published.** After the last byte the app checks size and SHA-256. A mismatch deletes the partial file and reports an
  error; nothing appears under the real name until the file is complete and correct.
- **Type id fix.** Tencent's 1.25-bit file stores the STQ1_0 tensor type as id 42, while the llama.cpp build in this repo uses 43. After
  the checksum passes, the app rewrites those 4-byte fields in the GGUF header in place (`GgufTypeRemap`; the same job as
  `scripts/remap_gguf_types.py 42:43`). The tensor data is untouched. The result no longer matches the published SHA-256, which is expected.
- **Storage.** The app needs the remaining bytes plus 64 MB free, otherwise it stops before downloading. Files live in the app's private
  `files/`; uninstalling the app removes them.
- **Not a background service.** A download runs while the app is alive. If Android kills the app, reopen it and tap Download to resume.
- **Permission.** The app declares `INTERNET`, used only for these downloads. On some phones (HONOR, Android 17) `adb install` of an
  update that adds a permission shows a confirmation dialog on the phone; tap allow, or the install fails with `User rejected permissions`.

## Manual install (offline, other quantizations)

If the phone has no route to any source, or you want something else, fetch the file on a PC (ModelScope: `modelscope download`, or the
browser; Hugging Face: `huggingface-cli download` or `HF_ENDPOINT=https://hf-mirror.com`) and use either way:

```bash
# Import button in the app (system file picker), or:
adb push Hy-MT2-1.8B-Q4_0.gguf /data/local/tmp/
adb shell run-as com.hymt2.app cp /data/local/tmp/Hy-MT2-1.8B-Q4_0.gguf files/hymt2-1.8b-q4_0.gguf
```

Tencent's STQ1_0 files need the type remap first (the in-app download does this for you):

```bash
python3 -I scripts/remap_gguf_types.py Hy-MT2-1.8B-1.25Bit.gguf 42:43
```

Optional, faster decode on the 1.25-bit model (`token_embd` Q6_K -> Q4_0, see [benchmarks.md](benchmarks.md)):

```bash
python3 -I scripts/requant_embd.py hymt2-1.8b-stq1_0.gguf hymt2-1.8b-stq1_0-embq4_0.gguf q4_0
```

The app lists every `*.gguf` it finds in `/sdcard/Android/data/com.hymt2.app/files/`, the app's `files/`, and `/data/local/tmp/hymt2/`
(the last one is usually unreadable by the app). Unknown file names are shown as-is and run on the CPU.

## Building without GitHub / Hugging Face

The app itself never contacts GitHub. The build scripts do, in two places:

| Step | Override |
|---|---|
| `scripts/setup_llama.sh` clones llama.cpp | `LLAMA_GIT_URL=<mirror or local clone> scripts/setup_llama.sh` |
| `scripts/build_snapdragon.sh` pulls the toolchain image from `ghcr.io` | `SD_IMAGE=<your registry>/arm64-android:v0.7` |

Gradle/Maven dependencies come from Google and Maven Central; use a mirror in `~/.gradle/init.gradle` if those are blocked too.
