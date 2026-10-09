package com.hymt2.app.engine

/** Receives UTF-8 text chunks from native; return false to stop generation. Called from the engine thread. */
interface PieceSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/** Thin JNI surface. All calls for one handle must come from the same thread (affinity is set there). */
internal object NativeBridge {
    init {
        System.loadLibrary("hymt2_jni")
    }

    external fun initBackends(nativeLibDir: String): String

    /** Returns a handle, or 0 with a message in errOut[0]. */
    external fun load(
        path: String, nCtx: Int, nThreads: Int, cpus: IntArray, nThreadsBatch: Int, batchCpus: IntArray,
        flashAttn: Int, kvQ8: Boolean, offload: Boolean, errOut: Array<String?>,
    ): Long

    external fun free(handle: Long)
    external fun cancel(handle: Long)

    /** stats = [nPrompt, nGen, ttftMs, prefillMs, decodeMs] */
    external fun generate(
        handle: Long, prompt: ByteArray, temp: Float, topP: Float, topK: Int,
        repeat: Float, maxNew: Int, sink: PieceSink, stats: DoubleArray,
    )

    external fun bench(handle: Long, pp: Int, tg: Int): String
    external fun systemInfo(handle: Long): String
}
