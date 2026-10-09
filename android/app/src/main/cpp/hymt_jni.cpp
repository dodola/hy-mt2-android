#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "hymt_engine.h"

namespace {

std::string to_std(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string bytes_to_std(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string out(n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<int> int_array(JNIEnv *env, jintArray a) {
    jsize n = env->GetArrayLength(a);
    std::vector<jint> ids(n);
    env->GetIntArrayRegion(a, 0, n, ids.data());
    return std::vector<int>(ids.begin(), ids.end());
}

hymt::Engine *engine_of(jlong h) { return reinterpret_cast<hymt::Engine *>(h); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hymt2_app_engine_NativeBridge_initBackends(JNIEnv *env, jobject, jstring dir) {
    return env->NewStringUTF(hymt::init_backends(to_std(env, dir)).c_str());
}

// Returns the engine handle, or 0 with *error* delivered through errOut[0].
JNIEXPORT jlong JNICALL
Java_com_hymt2_app_engine_NativeBridge_load(JNIEnv *env, jobject, jstring path, jint nCtx, jint nThreads,
                                            jintArray cpus, jint nThreadsBatch, jintArray batchCpus, jint flashAttn, jboolean kvQ8,
                                            jboolean offload, jobjectArray errOut) {
    hymt::EngineConfig cfg;
    cfg.model_path = to_std(env, path);
    cfg.n_ctx = nCtx;
    cfg.n_threads = nThreads;
    cfg.flash_attn = flashAttn;
    cfg.kv_q8 = kvQ8;
    cfg.offload = offload;
    cfg.n_threads_batch = nThreadsBatch;
    cfg.cpus = int_array(env, cpus);
    cfg.batch_cpus = int_array(env, batchCpus);

    auto engine = std::make_unique<hymt::Engine>();
    std::string err = engine->load(cfg);
    if (!err.empty()) {
        env->SetObjectArrayElement(errOut, 0, env->NewStringUTF(err.c_str()));
        return 0;
    }
    return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT void JNICALL
Java_com_hymt2_app_engine_NativeBridge_free(JNIEnv *, jobject, jlong h) { delete engine_of(h); }

JNIEXPORT void JNICALL
Java_com_hymt2_app_engine_NativeBridge_cancel(JNIEnv *, jobject, jlong h) { engine_of(h)->cancel(); }

// stats out: [nPrompt, nGen, ttftMs, prefillMs, decodeMs]; sink: object with `boolean onPiece(byte[])`.
JNIEXPORT void JNICALL
Java_com_hymt2_app_engine_NativeBridge_generate(JNIEnv *env, jobject, jlong h, jbyteArray prompt, jfloat temp,
                                                jfloat topP, jint topK, jfloat repeat, jint maxNew,
                                                jobject sink, jdoubleArray stats) {
    hymt::SamplingParams sp;
    sp.temp = temp; sp.top_p = topP; sp.top_k = topK; sp.repeat_penalty = repeat; sp.max_new_tokens = maxNew;

    jclass cls = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(cls, "onPiece", "([B)Z");
    auto cb = [&](const std::string &piece) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(piece.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(piece.size()), reinterpret_cast<const jbyte *>(piece.data()));
        jboolean keep = env->CallBooleanMethod(sink, on_piece, arr);
        env->DeleteLocalRef(arr);
        return keep == JNI_TRUE && !env->ExceptionCheck();
    };
    hymt::GenStats st = engine_of(h)->generate(bytes_to_std(env, prompt), sp, cb);
    jdouble out[5] = {double(st.n_prompt), double(st.n_gen), st.ttft_ms, st.prefill_ms, st.decode_ms};
    env->SetDoubleArrayRegion(stats, 0, 5, out);
}

JNIEXPORT jstring JNICALL
Java_com_hymt2_app_engine_NativeBridge_bench(JNIEnv *env, jobject, jlong h, jint pp, jint tg) {
    return env->NewStringUTF(engine_of(h)->bench(pp, tg).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hymt2_app_engine_NativeBridge_systemInfo(JNIEnv *env, jobject, jlong h) {
    return env->NewStringUTF(engine_of(h)->describe().c_str());
}

}  // extern "C"
