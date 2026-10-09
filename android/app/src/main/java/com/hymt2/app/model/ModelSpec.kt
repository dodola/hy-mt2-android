package com.hymt2.app.model

import java.io.File

/** A known Hy-MT2 GGUF variant. */
data class ModelSpec(
    val id: String,
    val label: String,
    val fileNames: List<String>,
    val quant: String,
    /** Quant type is implemented by the OpenCL / Hexagon backends (STQ1_0 is CPU-only). */
    val accelFriendly: Boolean,
)

data class ModelEntry(val spec: ModelSpec, val file: File) {
    val sizeMb: Long get() = file.length() / (1024 * 1024)
}

object KnownModels {
    val all: List<ModelSpec> = listOf(
        ModelSpec("stq1_0", "Hy-MT2 1.8B · 1.25-bit (STQ1_0)", listOf("hymt2-1.8b-stq1_0.gguf"), "STQ1_0", false),
        ModelSpec("q4_0", "Hy-MT2 1.8B · Q4_0", listOf("hymt2-1.8b-q4_0.gguf", "Hy-MT2-1.8B-Q4_0.gguf"), "Q4_0", true),
        ModelSpec("q4_k", "Hy-MT2 1.8B · Q4_K_M", listOf("hymt2-1.8b-q4.gguf", "Hy-MT2-1.8B-Q4_K_M.gguf"), "Q4_K", false),
    )

    fun forFile(name: String): ModelSpec? = all.firstOrNull { name in it.fileNames }
}
