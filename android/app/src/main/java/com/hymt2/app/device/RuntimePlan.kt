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
    // SM8750 STQ1_0 decode: 2 threads on the prime cores stalls, 4 -> 26 t/s, 6 -> 36 t/s.
    private const val QUALCOMM_MAX_DECODE_THREADS = 6
    private const val QUALCOMM_MAX_FREQ_DROP = 0.25
    private const val MIN_NON_PRIME_CORES = 4

    /**
     * SM8750 (HONOR PPG-AN00, Android 17): spinning ggml threads on the two prime cores (cpu6/7) stall the whole
     * pool - 7-8 threads give pp 9 t/s and decode never finishes, cpu6 with five others gives pp 15 - while
     * cpu0-5 alone run pp 80 / tg 36 t/s (STQ1_0). Drop the top frequency tier when enough cores remain.
     */
    internal fun withoutPrimeCores(freqs: Map<Int, Long>, perf: List<Int>): List<Int> {
        val top = perf.maxOfOrNull { freqs[it] ?: 0L } ?: return perf
        val rest = perf.filter { (freqs[it] ?: 0L) < top }
        return if (rest.size >= MIN_NON_PRIME_CORES) rest else perf
    }

    /**
     * Which downloadable model to suggest first. Q4_0 on the Hexagon NPU measured pp 1916 / tg 43.6 t/s on SM8750,
     * ahead of STQ1_0 on the CPU (80 / 36); with only OpenCL or CPU, STQ1_0 is faster at decode and 2.3x smaller.
     */
    fun recommendedModelId(soc: SocInfo, loadedBackends: List<String>): String =
        if (soc.vendor == SocVendor.QUALCOMM && loadedBackends.any { it.contains("HTP", true) || it.contains("Hexagon", true) }) "q4_0"
        else "stq1_0"

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
        val qualcomm = soc.vendor == SocVendor.QUALCOMM
        val perf = CpuTopology.performanceCores(freqs, if (qualcomm) QUALCOMM_MAX_FREQ_DROP else 0.15)
        val maxThreads = if (qualcomm) QUALCOMM_MAX_DECODE_THREADS else MAX_DECODE_THREADS
        // Pin to the fastest few cores: ggml partitions work evenly, so mixing tiers stalls on the slowest.
        val cpus = (if (qualcomm) withoutPrimeCores(freqs, perf) else perf).let { cores ->
            freqs.entries.filter { it.key in cores }
                .sortedByDescending { it.value }.take(maxThreads).map { it.key }.sorted()
        }
        val threads = cpus.size.takeIf { it > 0 } ?: maxThreads
        // Prefill is compute-bound and scales with extra cores: on RK3588 all 8 beat the 4 big ones
        // (pp 52.9 vs 46.4 t/s). On SM8750 extra threads land on the prime cores and collapse (see below).
        val batchCpus = when {
            soc.vendor == SocVendor.ROCKCHIP -> freqs.keys.sorted()
            qualcomm -> cpus
            else -> perf
        }
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
