#include "hymt_engine.h"

#include <android/log.h>
#include <cstdlib>
#include <sched.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "ggml-backend.h"
#include "ggml-cpu.h"

#define TAG "HyMT2Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace hymt {
namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                             : ANDROID_LOG_INFO;
    __android_log_write(prio, "llama", text);
}

// Length of the longest prefix of `s` that does not end inside a UTF-8 sequence.
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= std::min<size_t>(3, n); ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;           // continuation byte, keep looking
        size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return need > back ? n - back : n;
    }
    return n;
}

// Tuning hook: `adb shell setprop debug.hymt.<name> <int>`; returns `def` when unset.
int tuning_prop(const char *name, int def) {
    char key[PROP_NAME_MAX], val[PROP_VALUE_MAX] = {0};
    snprintf(key, sizeof(key), "debug.hymt.%s", name);
    return __system_property_get(key, val) > 0 ? atoi(val) : def;
}

// CPUs this process may run on. The app cpuset can be much narrower than the machine
// (RK3588's top-app cpuset is cpu4-7).
int allowed_cpu_count() {
    cpu_set_t set;
    CPU_ZERO(&set);
    return sched_getaffinity(0, sizeof(set), &set) == 0 ? CPU_COUNT(&set) : 1;
}

ggml_backend_dev_t find_cpu_device() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto *dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) return dev;
    }
    return nullptr;
}

}  // namespace

std::string init_backends(const std::string &dir) {
    llama_log_set(log_callback, nullptr);
    // The Hexagon DSP finds libggml-htp-v*.so (skeletons) through ADSP_LIBRARY_PATH; the stock vendor dirs stay.
    const std::string adsp = dir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 0);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    std::string out;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        if (!out.empty()) out += ",";
        out += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto *dev = ggml_backend_dev_get(i);
        LOGI("device %zu: %s (%s)", i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }
    LOGI("%s", llama_print_system_info());
    return out;
}

Engine::~Engine() {
    if (ctx_) llama_free(ctx_);
    if (pool_free_) {
        if (pool_) pool_free_(pool_);
        if (pool_batch_) pool_free_(pool_batch_);
    }
    if (model_) llama_model_free(model_);
}

std::string Engine::describe() const { return llama_print_system_info(); }

void Engine::create_threadpools(const EngineConfig &cfg) {
    if (cfg.cpus.empty()) return;
    auto *reg = ggml_backend_dev_backend_reg(find_cpu_device());
    auto *pool_new = (struct ggml_threadpool *(*)(struct ggml_threadpool_params *))
        ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
    pool_free_ = (void (*)(struct ggml_threadpool *))
        ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
    if (!pool_new || !pool_free_) return;

    // Busy-polling avoids a futex wake-up per graph node (decode 17.8 -> 22.9 t/s on RK3588 with 4
    // threads on a 4-core cpuset). It is only harmful when the pool has more threads than cores.
    const int max_threads = std::max(cfg.n_threads, cfg.n_threads_batch);
    const int poll = tuning_prop("poll", allowed_cpu_count() >= max_threads ? 50 : 0);
    const bool strict = tuning_prop("strict", 0) != 0;

    auto make = [&](int n, const std::vector<int> &cpus, bool paused) {
        struct ggml_threadpool_params p = {};
        p.n_threads = n;
        p.prio = GGML_SCHED_PRIO_NORMAL;
        p.poll = poll;
        p.strict_cpu = strict;
        p.paused = paused;  // the context resumes whichever pool it needs; two spinning pools starve each other
        for (int c : cpus) if (c < GGML_MAX_N_THREADS) p.cpumask[c] = true;
        return pool_new(&p);
    };
    const std::vector<int> &batch_cpus = cfg.batch_cpus.empty() ? cfg.cpus : cfg.batch_cpus;
    const bool same = cfg.n_threads == cfg.n_threads_batch && cfg.cpus == batch_cpus;
    // Mirrors llama.cpp's common: with two different pools the decode one starts paused and the
    // context resumes whichever it needs; a single shared pool is passed as the decode pool only.
    if (same) {
        pool_ = make(cfg.n_threads, cfg.cpus, false);
        if (pool_) llama_attach_threadpool(ctx_, pool_, nullptr);
    } else {
        pool_batch_ = make(cfg.n_threads_batch, batch_cpus, false);
        pool_ = make(cfg.n_threads, cfg.cpus, true);
        if (pool_ && pool_batch_) llama_attach_threadpool(ctx_, pool_, pool_batch_);
    }
}

// Touch every weight page and spin the threadpools up before the first user request.
void Engine::warmup() {
    std::vector<llama_token> toks(32, llama_vocab_bos(vocab_) >= 0 ? llama_vocab_bos(vocab_) : 1);
    int n_past = 0;
    decode_tokens(toks, n_past);
    llama_token t = toks[0];
    llama_decode(ctx_, llama_batch_get_one(&t, 1));
    llama_memory_clear(llama_get_memory(ctx_), true);
}

std::string Engine::load(const EngineConfig &cfg) {
    cfg_ = cfg;

    llama_model_params mp = llama_model_default_params();
    mp.load_mode = cfg.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    ggml_backend_dev_t cpu_only[2] = {nullptr, nullptr};
    if (cfg.offload) {
        mp.n_gpu_layers = 99;
    } else {
        cpu_only[0] = find_cpu_device();
        mp.devices = cpu_only;
        mp.n_gpu_layers = 0;
    }
    model_ = llama_model_load_from_file(cfg.model_path.c_str(), mp);
    if (!model_) return "failed to load model: " + cfg.model_path;
    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = cfg.n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = cfg.n_threads;
    cp.n_threads_batch = cfg.n_threads_batch;
    cp.flash_attn_type = static_cast<llama_flash_attn_type>(cfg.flash_attn);
    if (cfg.kv_q8) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) return "failed to create context";
    create_threadpools(cfg);
    warmup();
    return "";
}

bool Engine::decode_tokens(std::vector<llama_token> &toks, int &n_past) {
    const int n_batch = static_cast<int>(llama_n_batch(ctx_));
    for (size_t i = 0; i < toks.size(); i += n_batch) {
        if (cancel_.load()) return false;
        int n = static_cast<int>(std::min<size_t>(n_batch, toks.size() - i));
        if (llama_decode(ctx_, llama_batch_get_one(toks.data() + i, n)) != 0) return false;
        n_past += n;
    }
    return true;
}

GenStats Engine::generate(const std::string &prompt, const SamplingParams &sp, const PieceCallback &cb) {
    cancel_.store(false);
    GenStats st;

    std::vector<llama_token> toks(prompt.size() + 8);
    int n = llama_tokenize(vocab_, prompt.c_str(), prompt.size(), toks.data(), toks.size(), false, true);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(vocab_, prompt.c_str(), prompt.size(), toks.data(), toks.size(), false, true);
    }
    if (n <= 0) return st;
    toks.resize(n);
    st.n_prompt = n;
    if (n >= cfg_.n_ctx - 8) { LOGE("prompt too long: %d", n); return st; }

    llama_memory_clear(llama_get_memory(ctx_), true);

    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), 64, sp.repeat_penalty, 0.f, 0.f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sp.top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sp.top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(sp.temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(sp.seed));

    const auto t0 = Clock::now();
    int n_past = 0;
    if (!decode_tokens(toks, n_past)) { llama_sampler_free(smpl); return st; }
    st.prefill_ms = ms_since(t0);

    std::string pending;
    char buf[256];
    auto last_flush = Clock::now();
    const auto t_gen = Clock::now();
    bool stop = false;
    const int max_new = std::min(sp.max_new_tokens, cfg_.n_ctx - n_past - 1);

    for (int i = 0; i < max_new && !stop && !cancel_.load(); ++i) {
        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, tok)) break;
        llama_sampler_accept(smpl, tok);
        if (i == 0) st.ttft_ms = ms_since(t0);

        int len = llama_token_to_piece(vocab_, tok, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, len);

        const bool first = (i == 0);
        if (first || ms_since(last_flush) >= 40.0) {
            size_t k = utf8_complete_prefix(pending);
            if (k > 0) {
                if (!cb(pending.substr(0, k))) stop = true;
                pending.erase(0, k);
                last_flush = Clock::now();
            }
        }
        st.n_gen++;
        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) break;
        n_past++;
    }
    st.decode_ms = ms_since(t_gen);
    if (!pending.empty()) cb(pending);
    llama_sampler_free(smpl);
    return st;
}

std::string Engine::bench(int pp, int tg) {
    cancel_.store(false);
    const int n_vocab = llama_vocab_n_tokens(vocab_);
    pp = std::min(pp, cfg_.n_ctx - tg - 1);

    std::vector<llama_token> toks(pp);
    for (int i = 0; i < pp; ++i) toks[i] = 1000 + (i * 7919) % (n_vocab - 2000);

    llama_memory_clear(llama_get_memory(ctx_), true);
    int n_past = 0;
    auto t0 = Clock::now();
    decode_tokens(toks, n_past);
    const double pp_s = ms_since(t0) / 1000.0;

    llama_token tok = toks.back();
    t0 = Clock::now();
    for (int i = 0; i < tg; ++i) {
        tok = 1000 + (tok * 31 + i) % (n_vocab - 2000);
        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    const double tg_s = ms_since(t0) / 1000.0;
    char out[96];
    snprintf(out, sizeof(out), "%.2f,%.2f", pp / pp_s, tg / tg_s);
    return out;
}

}  // namespace hymt
