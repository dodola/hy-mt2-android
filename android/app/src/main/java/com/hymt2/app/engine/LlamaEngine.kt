package com.hymt2.app.engine

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** llama.cpp-backed engine. A single dedicated thread owns the native handle so CPU pinning sticks. */
class LlamaEngine private constructor() : InferenceEngine {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "hymt2-engine") }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var handle: Long = 0L

    override val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(options: EngineOptions): Result<Unit> = withContext(dispatcher) {
        releaseHandle()
        val err = arrayOfNulls<String>(1)
        val h = NativeBridge.load(
            options.modelPath, options.nCtx, options.nThreads, options.cpus.toIntArray(),
            options.nThreadsBatch, options.batchCpus.toIntArray(),
            options.flashAttn, options.kvQ8, options.offload, err,
        )
        if (h == 0L) {
            Result.failure(IllegalStateException(err[0] ?: "unknown load error"))
        } else {
            handle = h
            Result.success(Unit)
        }
    }

    override suspend fun generate(
        prompt: String,
        sampling: SamplingOptions,
        onText: (String) -> Unit,
    ): GenerationStats = withContext(dispatcher) {
        val h = checkLoaded()
        val sink = object : PieceSink {
            override fun onPiece(bytes: ByteArray): Boolean {
                onText(String(bytes, Charsets.UTF_8))
                return true
            }
        }
        val stats = DoubleArray(5)
        NativeBridge.generate(
            h, prompt.toByteArray(Charsets.UTF_8), sampling.temperature, sampling.topP,
            sampling.topK, sampling.repeatPenalty, sampling.maxNewTokens, sink, stats,
        )
        GenerationStats(stats[0].toInt(), stats[1].toInt(), stats[2], stats[3], stats[4])
    }

    override suspend fun bench(promptTokens: Int, genTokens: Int): String = withContext(dispatcher) {
        NativeBridge.bench(checkLoaded(), promptTokens, genTokens)
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) NativeBridge.cancel(h)
    }

    override fun systemInfo(): String = handle.takeIf { it != 0L }?.let { NativeBridge.systemInfo(it) } ?: ""

    override fun close() {
        executor.submit { releaseHandle() }
        executor.shutdown()
    }

    private fun checkLoaded(): Long = handle.also { check(it != 0L) { "model not loaded" } }

    private fun releaseHandle() {
        val h = handle
        handle = 0L
        if (h != 0L) NativeBridge.free(h)
    }

    companion object {
        /** Registers ggml backends found in the app's native lib dir; returns their names. */
        fun initBackends(context: Context): List<String> =
            NativeBridge.initBackends(context.applicationInfo.nativeLibraryDir)
                .split(',').filter { it.isNotBlank() }

        fun create(): InferenceEngine = LlamaEngine()
    }
}
