package com.hymt2.app.device

import com.hymt2.app.model.ModelSpec

/** How the engine should be configured on this device for a given model. */
data class RuntimePlan(
    val threads: Int,
    val cpus: List<Int>,
    val batchThreads: Int,
    val batchCpus: List<Int>,
    val offload: Boolean,
    val reason: String,
)

object RuntimePolicy {
    private const val MAX_DECODE_THREADS = 4

    /**
     * @param loadedBackends ggml backend names registered at startup (e.g. "CPU", "OpenCL", "HTP0").
     */
    fun plan(
        soc: SocInfo,
        spec: ModelSpec?,
        loadedBackends: List<String>,
        allFreqs: Map<Int, Long> = CpuTopology.maxFrequencies(),
        allowed: Set<Int> = CpuTopology.allowedCpus(),
    ): RuntimePlan {
        val freqs = if (allowed.isEmpty()) allFreqs else allFreqs.filterKeys { it in allowed }
        val perf = CpuTopology.performanceCores(freqs)
        // Pin to the fastest few cores: ggml partitions work evenly, so mixing tiers stalls on the slowest.
        val cpus = freqs.entries.filter { it.key in perf }
            .sortedByDescending { it.value }.take(MAX_DECODE_THREADS).map { it.key }.sorted()
        val threads = cpus.size.takeIf { it > 0 } ?: MAX_DECODE_THREADS
        // Prefill is compute-bound and scales with extra cores: on RK3588 all 8 beat the 4 big ones
        // (pp 52.9 vs 46.4 t/s). Elsewhere stay on the performance cluster until measured.
        val batchCpus = if (soc.vendor == SocVendor.ROCKCHIP) freqs.keys.sorted() else perf
        val batchThreads = batchCpus.size.takeIf { it > 0 } ?: threads

        val accel = loadedBackends.any { it.contains("OpenCL", true) || it.contains("Hexagon", true) || it.contains("HTP", true) }
        return when {
            soc.vendor == SocVendor.QUALCOMM && accel && spec?.accelFriendly == true ->
                RuntimePlan(threads, cpus, batchThreads, batchCpus, true, "Qualcomm: offload to ${loadedBackends.filter { it != "CPU" }.joinToString("/")}")
            soc.vendor == SocVendor.QUALCOMM ->
                RuntimePlan(threads, cpus, batchThreads, batchCpus, false, "Qualcomm: CPU (model/backend not accelerated)")
            soc.vendor == SocVendor.ROCKCHIP ->
                RuntimePlan(threads, cpus, batchThreads, batchCpus, false, "Rockchip: CPU big cores $cpus")
            else -> RuntimePlan(threads, cpus, batchThreads, batchCpus, false, "CPU")
        }
    }
}
