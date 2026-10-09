package com.hymt2.app.device

import com.hymt2.app.model.KnownModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePolicyTest {
    // HONOR PPG-AN00 / SM8750: 6 performance cores @3.53 GHz + 2 prime cores @4.47 GHz.
    private val sm8750 = (0..5).associateWith { 3_532_800L } + mapOf(6 to 4_473_600L, 7 to 4_473_600L)
    private val qcom = SocInfo("QTI", "SM8750", "sun", SocVendor.QUALCOMM)

    // RK3588: 4x A55 @1.8 GHz + 4x A76 @2.35 GHz.
    private val rk3588 = (0..3).associateWith { 1_800_000L } + (4..7).associateWith { 2_352_000L }
    private val rockchip = SocInfo("Rockchip", "RK3588", "rk3588", SocVendor.ROCKCHIP)

    private fun spec(id: String) = KnownModels.all.first { it.id == id }

    @Test fun `sm8750 pins to the six performance cores and skips the prime pair`() {
        val plan = RuntimePolicy.plan(qcom, spec("stq1_0"), listOf("CPU"), sm8750, emptySet())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plan.cpus)
        assertEquals(6, plan.threads)
    }

    @Test fun `sm8750 prefill uses the same cores as decode`() {
        val plan = RuntimePolicy.plan(qcom, spec("stq1_0"), listOf("CPU"), sm8750, emptySet())
        assertEquals(plan.cpus, plan.batchCpus)
        assertEquals(6, plan.batchThreads)
    }

    @Test fun `qualcomm offloads accel friendly models when a backend is loaded`() {
        val plan = RuntimePolicy.plan(qcom, spec("q4_0"), listOf("OpenCL", "HTP", "CPU"), sm8750, emptySet())
        assertTrue(plan.offload)
    }

    @Test fun `qualcomm keeps stq1_0 on cpu even with accelerators loaded`() {
        val plan = RuntimePolicy.plan(qcom, spec("stq1_0"), listOf("OpenCL", "HTP", "CPU"), sm8750, emptySet())
        assertFalse(plan.offload)
    }

    @Test fun `qualcomm without accelerator backends stays on cpu`() {
        assertFalse(RuntimePolicy.plan(qcom, spec("q4_0"), listOf("CPU"), sm8750, emptySet()).offload)
    }

    @Test fun `rk3588 keeps four big cores for decode and all eight for prefill`() {
        val plan = RuntimePolicy.plan(rockchip, spec("stq1_0"), listOf("CPU"), rk3588, emptySet())
        assertEquals(listOf(4, 5, 6, 7), plan.cpus)
        assertEquals((0..7).toList(), plan.batchCpus)
    }

    @Test fun `withoutPrimeCores keeps the prime tier when too few cores would remain`() {
        val freqs = mapOf(0 to 2_000_000L, 1 to 2_000_000L, 2 to 3_000_000L, 3 to 3_000_000L)
        assertEquals(listOf(0, 1, 2, 3), RuntimePolicy.withoutPrimeCores(freqs, listOf(0, 1, 2, 3)))
    }

    @Test fun `performanceCores widens the tier gap on request`() {
        assertEquals(listOf(6, 7), CpuTopology.performanceCores(sm8750))
        assertEquals((0..7).toList(), CpuTopology.performanceCores(sm8750, maxDrop = 0.25))
    }

    @Test fun `recommends Q4_0 only when the Hexagon NPU is loaded`() {
        assertEquals("q4_0", RuntimePolicy.recommendedModelId(qcom, listOf("OpenCL", "HTP", "CPU")))
        // OpenCL decode (16.6 t/s) is slower than STQ1_0 on the CPU (36 t/s) on SM8750.
        assertEquals("stq1_0", RuntimePolicy.recommendedModelId(qcom, listOf("OpenCL", "CPU")))
        assertEquals("stq1_0", RuntimePolicy.recommendedModelId(qcom, listOf("CPU")))
        assertEquals("stq1_0", RuntimePolicy.recommendedModelId(rockchip, listOf("CPU")))
    }

    @Test fun `soc classification recognises SM8750`() {
        assertEquals(SocVendor.QUALCOMM, SocDetector.classify("QTI", "SM8750", "sun"))
        assertEquals(SocVendor.ROCKCHIP, SocDetector.classify("Rockchip", "RK3588", "rk3588"))
    }
}
