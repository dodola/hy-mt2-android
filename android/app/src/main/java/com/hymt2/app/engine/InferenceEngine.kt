package com.hymt2.app.engine

data class EngineOptions(
    val modelPath: String,
    val nCtx: Int = 2048,
    val nThreads: Int = 4,
    /** Decode threadpool pin set (empty = unpinned). */
    val cpus: List<Int> = emptyList(),
    val nThreadsBatch: Int = nThreads,
    /** Prefill threadpool pin set (empty = same as [cpus]). */
    val batchCpus: List<Int> = emptyList(),
    /** -1 auto, 0 off, 1 on */
    val flashAttn: Int = -1,
    val kvQ8: Boolean = false,
    /** Let non-CPU ggml backends (OpenCL / Hexagon) take the model layers. */
    val offload: Boolean = false,
)

data class SamplingOptions(
    val temperature: Float = 0.7f,
    val topP: Float = 0.6f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.05f,
    val maxNewTokens: Int = 1024,
)

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMs: Double,
    val prefillMs: Double,
    val decodeMs: Double,
) {
    val prefillTps: Double get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0
    val decodeTps: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
}

/** Backend-agnostic translation engine; llama.cpp today, other runtimes (e.g. RKNN) can implement it. */
interface InferenceEngine : AutoCloseable {
    val isLoaded: Boolean

    /** Loads a model, replacing any previous one. */
    suspend fun load(options: EngineOptions): Result<Unit>

    /** Runs a fully formatted prompt, streaming decoded text to [onText]. */
    suspend fun generate(
        prompt: String,
        sampling: SamplingOptions = SamplingOptions(),
        onText: (String) -> Unit,
    ): GenerationStats

    /** Returns "pp_tps,tg_tps". */
    suspend fun bench(promptTokens: Int, genTokens: Int): String

    fun cancel()
    fun systemInfo(): String
}
