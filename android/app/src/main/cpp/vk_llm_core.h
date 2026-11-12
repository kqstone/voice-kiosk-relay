// Voice Kiosk on-device LLM core: a small, JNI-free C++ layer over llama.cpp.
// All functions report failures through return values; none of them throw or abort
// on user-controlled input (missing/corrupt files, bad grammar, oversize prompts).
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace vk {

enum class GenStatus : int {
    kEndOfGeneration = 0,  // model emitted an end-of-generation token
    kMaxTokens = 1,        // stopped at max_tokens
    kCancelled = 2,        // cancel() was called
    kTimeout = 3,          // deadline exceeded
    kError = 4,            // see GenResult::error
    kContextFull = 5,      // prompt + generation would overflow n_ctx
};

// GGML type ids for the KV cache (subset we expose; values match enum ggml_type).
constexpr int kKvTypeF16 = 1;
constexpr int kKvTypeQ8_0 = 8;

struct LoadParams {
    int n_ctx = 1024;
    int n_threads = 4;
    int n_batch = 256;          // logical batch == physical ubatch (bounds the compute buffer)
    int kv_type = kKvTypeF16;   // kKvTypeF16 or kKvTypeQ8_0 (q8_0 V requires flash attention)
    int flash_attn = -1;        // -1 auto, 0 off, 1 on (llama_flash_attn_type)
    bool use_extra_bufts = true;  // false disables CPU_REPACK (weights stay in the mmap)
    bool use_mmap = true;         // false reads weights into anonymous buffers (no file pages)
};

// How the prompt of a GenParams is interpreted.
enum class PrefixMode : int {
    kNone = 0,    // prompt is the full formatted prompt; KV cleared first (S0 behaviour)
    kCold = 1,    // prompt is the suffix; KV cleared, then prefix tokens + suffix evaluated
    kCached = 2,  // prompt is the suffix; prefix KV (from set_prefix) reused, only suffix evaluated
};

struct PrefixResult {
    bool ok = false;
    std::string error;
    int n_tokens = 0;
    bool restored_from_file = false;  // true → state file was valid and loaded
    bool saved_to_file = false;
    double tokenize_ms = 0;
    double build_ms = 0;    // llama_decode over the prefix (0 if restored)
    double restore_ms = 0;  // llama_state_seq_load_file (0 if built)
    double save_ms = 0;
    uint64_t state_bytes = 0;
};

struct GenParams {
    std::string prompt;        // full prompt (kNone) or suffix (kCold / kCached)
    PrefixMode prefix_mode = PrefixMode::kNone;
    int max_tokens = 64;
    std::string grammar;       // optional GBNF; empty = unconstrained
    float temperature = 0.0f;  // <= 0 → greedy
    int64_t timeout_ms = 0;    // <= 0 → no timeout
    uint32_t seed = 42;
    // Grammar fast-forward (greedy + grammar only): when the grammar admits a single
    // next byte sequence, its tokenization is decoded as a draft in one batch and each
    // draft token is kept only if constrained greedy sampling picks exactly it at that
    // position (speculative verification), so the output equals plain decoding.
    bool fast_forward = true;
};

struct GenResult {
    GenStatus status = GenStatus::kError;
    std::string text;  // raw UTF-8 bytes (may be truncated mid-codepoint only on error)
    std::string error;
    int n_prompt_tokens = 0;   // tokens evaluated by this call (cached: suffix only)
    int n_prefix_tokens = 0;   // prefix tokens in context (cold: evaluated, cached: reused)
    int n_suffix_tokens = 0;
    int n_gen_tokens = 0;
    double tokenize_ms = 0;
    double prompt_eval_ms = 0;  // llama_decode over the prompt
    double ttft_ms = 0;         // start of generate() → first token sampled
    double decode_ms = 0;       // first token sampled → last token sampled
    double total_ms = 0;
    int n_decode_calls = 0;     // llama_decode calls after the prompt
    int n_forced_tokens = 0;    // generated tokens that came from accepted grammar drafts
    int n_draft_tokens = 0;     // draft tokens proposed (accepted + rejected)
    double draft_ms = 0;        // time spent building drafts from the grammar
};

struct Engine;

// Load CPU backend variants from dir (the app's nativeLibraryDir) and init llama.
// Idempotent. Returns a human-readable summary of the selected backend(s).
std::string backend_init(const char *backend_dir);

// Returns llama_print_system_info() (CPU features compiled into the selected variant).
std::string system_info();

// Returns nullptr and fills err on failure.
// memory_log receives llama.cpp's buffer-size lines (model/REPACK/KV/compute, MiB) for
// the memory breakdown in benchmark reports.
Engine *load(const char *model_path, const LoadParams &params, std::string &err,
             double &load_ms, std::string *memory_log = nullptr);

// Tokenizes and evaluates the fixed prompt prefix into sequence 0, keeping its KV for
// PrefixMode::kCached. If state_path is non-empty and the file holds the KV of exactly
// these tokens it is restored instead (unless force_rebuild); after a build the state is
// saved there. Any failure leaves the engine usable (prefix cleared).
PrefixResult set_prefix(Engine *engine, const std::string &prefix, const std::string &state_path,
                        bool force_rebuild);

// Changes generation / batch thread counts without reloading.
void set_threads(Engine *engine, int n_threads);

// Pins the calling thread (and threads it creates later, e.g. ggml workers) to cpus.
// Empty list → restore the mask from before the first pin (no-op if never pinned). Cpus
// outside the original mask are ignored. Returns 0 or errno.
int set_affinity(const int *cpus, int n_cpus);

std::string model_description(Engine *engine);
uint64_t model_size_bytes(Engine *engine);

GenResult generate(Engine *engine, const GenParams &params);

// Thread-safe; may be called from any thread while generate() runs.
void request_cancel(Engine *engine);

void release(Engine *engine);

}  // namespace vk
