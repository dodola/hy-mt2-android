package com.hymt2.app.device

import android.os.Build
import java.io.File

enum class SocVendor { QUALCOMM, ROCKCHIP, OTHER }

data class SocInfo(
    val manufacturer: String,
    val model: String,
    val platform: String,
    val vendor: SocVendor,
) {
    val label: String get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { platform }
}

object SocDetector {
    fun detect(): SocInfo {
        val manufacturer = prop("ro.soc.manufacturer").ifBlank { socManufacturer() }
        val model = prop("ro.soc.model").ifBlank { socModel() }
        val platform = prop("ro.board.platform").ifBlank { Build.BOARD }
        return SocInfo(manufacturer, model, platform, classify(manufacturer, model, platform))
    }

    internal fun classify(manufacturer: String, model: String, platform: String): SocVendor {
        val all = "$manufacturer $model $platform".lowercase()
        return when {
            "qualcomm" in all || "qti" in all || Regex("""\b(sm|sdm|kalama|pineapple|lahaina|taro|crow|waipio)\w*""").containsMatchIn(all) -> SocVendor.QUALCOMM
            "rockchip" in all || Regex("""\brk3\d{3}""").containsMatchIn(all) -> SocVendor.ROCKCHIP
            else -> SocVendor.OTHER
        }
    }

    private fun socManufacturer(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
    private fun socModel(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""

    private fun prop(key: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        p.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")
}

/** Per-CPU max frequencies from sysfs. */
object CpuTopology {
    /** cpu id -> max kHz; empty if sysfs is unreadable. */
    fun maxFrequencies(root: File = File("/sys/devices/system/cpu")): Map<Int, Long> =
        (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { id ->
            val f = File(root, "cpu$id/cpufreq/cpuinfo_max_freq")
            runCatching { f.readText().trim().toLong() }.getOrNull()?.let { id to it }
        }.toMap()

    /**
     * CPUs this process may run on (the app's cpuset can be narrower than the machine: the RK3588
     * box confines top-app to cpu4-7). Pinning outside this set oversubscribes the allowed cores.
     */
    fun allowedCpus(statusFile: File = File("/proc/self/status")): Set<Int> = runCatching {
        val line = statusFile.readLines().first { it.startsWith("Cpus_allowed_list:") }
        parseCpuList(line.substringAfter(':').trim())
    }.getOrDefault(emptySet())

    internal fun parseCpuList(list: String): Set<Int> = list.split(',').filter { it.isNotBlank() }.flatMap { part ->
        val bounds = part.trim().split('-').map { it.toInt() }
        if (bounds.size == 2) (bounds[0]..bounds[1]).toList() else listOf(bounds[0])
    }.toSet()

    /**
     * Fastest cores: walk the distinct frequency tiers from the top and stop at the first
     * drop larger than [maxDrop] (default 15%), so e.g. RK3588 (2352/2304 vs 1800) yields cpu4-7.
     * Snapdragon 8 Elite (2x4473 + 6x3532 MHz) needs a wider [maxDrop] to include the 6 performance cores.
     */
    fun performanceCores(freqs: Map<Int, Long> = maxFrequencies(), maxDrop: Double = 0.15): List<Int> {
        if (freqs.isEmpty()) return emptyList()
        val tiers = freqs.values.distinct().sortedDescending()
        var cutoff = tiers.first()
        for (i in 1 until tiers.size) {
            if (tiers[i].toDouble() < tiers[i - 1] * (1.0 - maxDrop)) break
            cutoff = tiers[i]
        }
        return freqs.filterValues { it >= cutoff }.keys.sorted()
    }
}
