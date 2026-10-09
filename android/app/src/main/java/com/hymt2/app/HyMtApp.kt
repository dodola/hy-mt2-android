package com.hymt2.app

import android.app.Application
import com.hymt2.app.device.SocDetector
import com.hymt2.app.device.SocInfo
import com.hymt2.app.engine.InferenceEngine
import com.hymt2.app.engine.LlamaEngine

class HyMtApp : Application() {
    val soc: SocInfo by lazy { SocDetector.detect() }

    /** ggml backend names registered at startup ("CPU" plus any optional OpenCL/Hexagon libs). */
    val backends: List<String> by lazy { LlamaEngine.initBackends(this) }

    val engine: InferenceEngine by lazy { backends; LlamaEngine.create() }
}
