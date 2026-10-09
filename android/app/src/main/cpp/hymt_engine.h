#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "llama.h"

namespace hymt {

struct EngineConfig {
    std::string model_path;
    int  n_ctx        = 2048;
    int  n_threads    = 4;
    std::vector<int> cpus;          // decode threadpool: CPU ids to pin to (empty = no pinning)
    int  n_threads_batch = 4;       // prefill threads
    std::vector<int> batch_cpus;    // prefill threadpool CPU ids (empty = same as decode)
    int  flash_attn   = -1;         // -1 auto, 0 off, 1 on
    bool kv_q8        = false;
    bool use_mmap     = true;
    bool offload      = false;      // true: let non-CPU backends (OpenCL/Hexagon) take the layers
};

struct SamplingParams {
    float temp = 0.7f;
    float top_p = 0.6f;
    int   top_k = 20;
    float repeat_penalty = 1.05f;
    int   max_new_tokens = 1024;
    uint32_t seed = 0xFFFFFFFFu;
};

struct GenStats {
    int    n_prompt   = 0;
    int    n_gen      = 0;
    double ttft_ms    = 0;
    double prefill_ms = 0;
    double decode_ms  = 0;
};

// Receives UTF-8-complete text; return false to stop generation.
using PieceCallback = std::function<bool(const std::string &)>;

// Registers and loads ggml backends found in `dir`; returns a description of what loaded.
// `adsp_dir` holds the Hexagon skeletons (libggml-htp-v*.so) for the DSP to load; keep its path free of '~'/'='.
std::string init_backends(const std::string &dir, const std::string &adsp_dir);

class Engine {
public:
    ~Engine();
    // Returns empty string on success, otherwise an error message.
    std::string load(const EngineConfig &cfg);
    GenStats generate(const std::string &prompt, const SamplingParams &sp, const PieceCallback &cb);
    // Returns "pp_tps,tg_tps".
    std::string bench(int pp, int tg);
    void cancel() { cancel_.store(true); }
    std::string describe() const;

private:
    bool decode_tokens(std::vector<llama_token> &toks, int &n_past);
    void create_threadpools(const EngineConfig &cfg);
    void warmup();

    llama_model   *model_ = nullptr;
    llama_context *ctx_   = nullptr;
    const llama_vocab *vocab_ = nullptr;
    struct ggml_threadpool *pool_ = nullptr;        // decode
    struct ggml_threadpool *pool_batch_ = nullptr;  // prefill
    void (*pool_free_)(struct ggml_threadpool *) = nullptr;
    EngineConfig cfg_;
    std::atomic<bool> cancel_{false};
};

}  // namespace hymt
