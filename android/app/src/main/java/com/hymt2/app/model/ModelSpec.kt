package com.hymt2.app.model

import java.io.File

/** Where a model can be downloaded from; `sha256` is the LFS hash of the file as published. */
data class RemoteModel(
    val repo: String,
    val remoteFile: String,
    val sizeBytes: Long,
    val sha256: String,
    /** (from, to) ggml type id rewrite applied in the GGUF header after the checksum passes. */
    val typeRemap: Pair<Int, Int>? = null,
)

/** A known Hy-MT2 GGUF variant. */
data class ModelSpec(
    val id: String,
    val label: String,
    val fileNames: List<String>,
    val quant: String,
    /** Quant type is implemented by the OpenCL / Hexagon backends (STQ1_0 is CPU-only). */
    val accelFriendly: Boolean,
    val remote: RemoteModel? = null,
)

data class ModelEntry(val spec: ModelSpec, val file: File) {
    val sizeMb: Long get() = file.length() / (1024 * 1024)
}

object KnownModels {
    /**
     * Tencent's STQ1_0 files use ggml type id 42; the llama.cpp PR #22836 build we ship (and its Q2_0 follow-up) puts
     * STQ1_0 at 43, so the header is rewritten after download (same as scripts/remap_gguf_types.py).
     */
    private const val STQ1_0_TENCENT_ID = 42
    private const val STQ1_0_LOCAL_ID = 43

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            "stq1_0", "Hy-MT2 1.8B · 1.25-bit (STQ1_0)", listOf("hymt2-1.8b-stq1_0.gguf"), "STQ1_0", false,
            RemoteModel(
                repo = "AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF", remoteFile = "Hy-MT2-1.8B-1.25Bit.gguf",
                sizeBytes = 461_860_800L,
                sha256 = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
                typeRemap = STQ1_0_TENCENT_ID to STQ1_0_LOCAL_ID,
            ),
        ),
        ModelSpec(
            "q4_0", "Hy-MT2 1.8B · Q4_0", listOf("hymt2-1.8b-q4_0.gguf", "Hy-MT2-1.8B-Q4_0.gguf"), "Q4_0", true,
            RemoteModel(
                repo = "unsloth/Hy-MT2-1.8B-GGUF", remoteFile = "Hy-MT2-1.8B-Q4_0.gguf",
                sizeBytes = 1_079_997_408L,
                sha256 = "5458acf22435287fb9aa7a5bf6cc041806df15d54cea020cbad25ac95e2d8a21",
            ),
        ),
        ModelSpec("q4_k", "Hy-MT2 1.8B · Q4_K_M", listOf("hymt2-1.8b-q4.gguf", "Hy-MT2-1.8B-Q4_K_M.gguf"), "Q4_K", false),
    )

    fun forFile(name: String): ModelSpec? = all.firstOrNull { name in it.fileNames }

    val downloadable: List<ModelSpec> get() = all.filter { it.remote != null }
}

/** One place a model file can be fetched from. `template` uses {repo} and {file}; every source serves the same bytes. */
data class ModelSource(val name: String, val template: String) {
    fun url(remote: RemoteModel): String = template.replace("{repo}", remote.repo).replace("{file}", remote.remoteFile)
}

/**
 * Download sources, tried in order. ModelScope is the default: it mirrors the same repo ids and file names with
 * identical SHA-256 and is served from a mainland-China CDN, so it works where huggingface.co and github.com do not.
 * Hugging Face and hf-mirror.com are only fallbacks. The catalog's checksum is verified whichever source delivers the bytes.
 */
object ModelSources {
    val MODELSCOPE = ModelSource("ModelScope", "https://modelscope.cn/models/{repo}/resolve/master/{file}")
    val HF_MIRROR = ModelSource("hf-mirror", "https://hf-mirror.com/{repo}/resolve/main/{file}")
    val HUGGING_FACE = ModelSource("Hugging Face", "https://huggingface.co/{repo}/resolve/main/{file}")

    val default: List<ModelSource> = listOf(MODELSCOPE, HF_MIRROR, HUGGING_FACE)
}
