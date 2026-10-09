package com.hymt2.app.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.hymt2.app.HyMtApp
import com.hymt2.app.device.RuntimePolicy
import com.hymt2.app.engine.EngineOptions
import com.hymt2.app.model.KnownModels
import com.hymt2.app.translate.Languages
import com.hymt2.app.translate.PromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Headless benchmark / smoke test, driven from adb:
 *   am start -n com.hymt2.app/.bench.BenchActivity --es model /path/model.gguf [--ei threads 4]
 *       [--ei threads_batch 8] [--ei pp 128] [--ei tg 64] [--ez nopin true] [--ez kvq8 true] [--ei fa 1] [--ez offload true] [--es text "你好" --es target en]
 * Results are logged under tag HYMT_BENCH.
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A visible window keeps the process in the foreground cpuset; otherwise the scheduler
        // confines it to little cores and results are meaningless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Hy-MT2 benchmark running…"; textSize = 20f; setPadding(48, 96, 48, 48) })
        val i = intent
        val app = application as HyMtApp
        val path = i.getStringExtra("model") ?: run { Log.e(TAG, "missing --es model"); finish(); return }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                run(app, path)
            } catch (e: Exception) {
                Log.e(TAG, "bench failed", e)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private suspend fun run(app: HyMtApp, path: String) {
        val i = intent
        val spec = KnownModels.forFile(path.substringAfterLast('/'))
        val plan = RuntimePolicy.plan(app.soc, spec, app.backends)
        val threads = i.getIntExtra("threads", plan.threads)
        val cpus = if (i.hasExtra("threads")) plan.cpus.take(threads) else plan.cpus
        val batchThreads = i.getIntExtra("threads_batch", plan.batchThreads)
        val batchCpus = if (i.hasExtra("threads_batch")) plan.batchCpus.take(batchThreads) else plan.batchCpus
        val opts = EngineOptions(
            modelPath = path, nThreads = threads,
            cpus = if (i.getBooleanExtra("nopin", false)) emptyList() else cpus,
            nThreadsBatch = batchThreads, batchCpus = if (i.getBooleanExtra("nopin", false)) emptyList() else batchCpus,
            flashAttn = i.getIntExtra("fa", -1), kvQ8 = i.getBooleanExtra("kvq8", false),
            offload = i.getBooleanExtra("offload", plan.offload),
        )
        Log.i(TAG, "soc=${app.soc} backends=${app.backends} plan=$plan opts=$opts")
        val t0 = System.nanoTime()
        app.engine.load(opts).getOrThrow()
        Log.i(TAG, "load_ms=${(System.nanoTime() - t0) / 1_000_000}")

        val pp = i.getIntExtra("pp", 128)
        val tg = i.getIntExtra("tg", 64)
        if (pp > 0) {
            val (ppTps, tgTps) = app.engine.bench(pp, tg).split(',')
            Log.i(TAG, "RESULT model=${path.substringAfterLast('/')} threads=$threads pp$pp=$ppTps tg$tg=$tgTps")
        }
        i.getStringExtra("text")?.let { text ->
            val target = Languages.byCode(i.getStringExtra("target") ?: "en") ?: Languages.byCode("en")!!
            val out = StringBuilder()
            val st = app.engine.generate(PromptBuilder.build(text, null, target)) { out.append(it) }
            Log.i(TAG, "TRANSLATION: $out")
            Log.i(TAG, "STATS prompt=${st.promptTokens} gen=${st.generatedTokens} ttft_ms=${"%.0f".format(st.ttftMs)} " +
                "prefill_tps=${"%.1f".format(st.prefillTps)} decode_tps=${"%.1f".format(st.decodeTps)}")
        }
    }

    private companion object { const val TAG = "HYMT_BENCH" }
}
