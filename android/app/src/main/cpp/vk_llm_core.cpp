#include "vk_llm_core.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <mutex>
#include <sched.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "ggml-backend.h"
#include "llama.h"

namespace vk {
namespace {

using Clock = std::chrono::steady_clock;

// Max logits rows per decode: 1 sampled token + up to 15 grammar-draft tokens.
constexpr int kMaxOutputs = 16;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

std::once_flag g_backend_once;
std::string g_backend_summary;

}  // namespace

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancel{false};
    std::atomic<int64_t> deadline_us{0};  // steady-clock µs, 0 = none
    LoadParams params;
    std::vector<llama_token> prefix;  // tokens of the fixed prompt prefix (may be empty)
    bool prefix_in_kv = false;        // KV cells [0, prefix.size()) hold the prefix
    // Printable ASCII chars that are a single vocab token (char, token), probed against
    // the grammar to find forced continuations.
    std::vector<std::pair<char, llama_token>> ascii_tokens;
};

namespace {

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               Clock::now().time_since_epoch())
        .count();
}

bool should_abort(const Engine *e) {
    if (e->cancel.load(std::memory_order_relaxed)) return true;
    const int64_t dl = e->deadline_us.load(std::memory_order_relaxed);
    return dl > 0 && now_us() > dl;
}

bool abort_cb(void *data) { return should_abort(static_cast<Engine *>(data)); }

bool file_looks_like_gguf(const char *path, std::string &err) {
    struct stat st {};
    if (path == nullptr || stat(path, &st) != 0) {
        err = "model file not found";
        return false;
    }
    if (!S_ISREG(st.st_mode)) {
        err = "model path is not a regular file";
        return false;
    }
    if (st.st_size < 1024) {
        err = "model file too small";
        return false;
    }
    FILE *f = std::fopen(path, "rb");
    if (!f) {
        err = "model file not readable";
        return false;
    }
    char magic[4] = {0, 0, 0, 0};
    const size_t n = std::fread(magic, 1, 4, f);
    std::fclose(f);
    if (n != 4 || magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
        err = "not a GGUF file (bad magic)";
        return false;
    }
    return true;
}

// Captures llama.cpp's "... buffer size = N MiB" lines while a load is in progress.
std::mutex g_capture_mu;
std::string *g_capture = nullptr;

void quiet_log(ggml_log_level level, const char *text, void * /*user*/) {
    // Only warnings and errors reach logcat; the platform layer installs its own sink.
    if (text == nullptr) return;
    {
        std::lock_guard<std::mutex> lk(g_capture_mu);
        if (g_capture != nullptr && std::strstr(text, "MiB") != nullptr &&
            (std::strstr(text, "buffer size") != nullptr || std::strstr(text, "size =") != nullptr)) {
            std::string line(text);
            while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
            size_t b = line.find_first_not_of(' ');
            g_capture->append(b == std::string::npos ? line : line.substr(b)).push_back('\n');
        }
    }
#ifndef __ANDROID__
    if (std::getenv("VK_LLM_VERBOSE") != nullptr) { std::fputs(text, stderr); return; }
#endif
    if (level < GGML_LOG_LEVEL_WARN && level != GGML_LOG_LEVEL_CONT) return;
#ifdef __ANDROID__
    __android_log_write(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                        "VkLlm", text);
#else
    std::fputs(text, stderr);
#endif
}

// CPU kernel variants built by GGML_CPU_ALL_VARIANTS for Android, most capable first.
// Each variant exports ggml_backend_score(), which returns 0 when the running CPU lacks
// a required feature (checked via getauxval HWCAPs), and ggml_backend_load() refuses
// such a variant before executing any of its kernels. The first accepted variant is
// therefore the best one the device supports; android_armv8.0_1 is plain armv8-a.
const char *const kCpuVariants[] = {
    "android_armv9.2_2", "android_armv9.2_1", "android_armv9.0_1", "android_armv8.6_1",
    "android_armv8.2_2", "android_armv8.2_1", "android_armv8.0_1",
};

bool file_exists(const std::string &p) {
    struct stat st {};
    return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string load_best_cpu_variant(const char *dir) {
    const std::string d = (dir != nullptr) ? std::string(dir) : std::string();
    for (const char *v : kCpuVariants) {
        const std::string lib = std::string("libggml-cpu-") + v + ".so";
        // Extracted libs (legacy packaging) live in nativeLibraryDir; otherwise the
        // bare soname lets the Android linker map it straight from the APK.
        const std::string path = (!d.empty() && file_exists(d + "/" + lib)) ? d + "/" + lib : lib;
        if (ggml_backend_load(path.c_str()) != nullptr) return v;
    }
    // Last resort: let ggml scan the directory itself (extracted-libs layout).
    if (!d.empty()) {
        ggml_backend_load_all_from_path(d.c_str());
        if (ggml_backend_dev_count() > 0) return "scanned:" + d;
    }
    return {};
}

}  // namespace

std::string backend_init(const char *backend_dir) {
    std::call_once(g_backend_once, [backend_dir]() {
        llama_log_set(quiet_log, nullptr);
        std::string variant;
        if (ggml_backend_dev_count() == 0) {  // static builds (host tests) already have CPU
            variant = load_best_cpu_variant(backend_dir);
        }
        llama_backend_init();
        std::string s = variant.empty() ? std::string() : "variant=" + variant + "; ";
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (i > 0) s += "; ";
            s += ggml_backend_dev_name(dev);
            s += " (";
            s += ggml_backend_dev_description(dev);
            s += ")";
        }
        g_backend_summary = ggml_backend_dev_count() == 0 ? "no backend loaded" : s;
    });
    return g_backend_summary;
}

std::string system_info() {
    const char *s = llama_print_system_info();
    return s ? std::string(s) : std::string();
}

namespace {
struct CaptureScope {
    explicit CaptureScope(std::string *out) {
        std::lock_guard<std::mutex> lk(g_capture_mu);
        g_capture = out;
    }
    ~CaptureScope() {
        std::lock_guard<std::mutex> lk(g_capture_mu);
        g_capture = nullptr;
    }
};
}  // namespace

Engine *load(const char *model_path, const LoadParams &params, std::string &err,
             double &load_ms, std::string *memory_log) {
    load_ms = 0;
    CaptureScope capture(memory_log);
    if (ggml_backend_dev_count() == 0) {
        err = "no ggml backend available (backend_init not called or CPU variant missing)";
        return nullptr;
    }
    if (!file_looks_like_gguf(model_path, err)) return nullptr;
    if (params.n_ctx < 128 || params.n_ctx > 32768) {
        err = "n_ctx out of range";
        return nullptr;
    }
    if (params.n_batch < 8 || params.n_batch > 2048 || params.n_threads < 1 || params.n_threads > 64) {
        err = "n_batch / n_threads out of range";
        return nullptr;
    }
    if (params.kv_type != kKvTypeF16 && params.kv_type != kKvTypeQ8_0) {
        err = "unsupported kv_type";
        return nullptr;
    }
    if (params.kv_type == kKvTypeQ8_0 && params.flash_attn == 0) {
        err = "q8_0 KV cache requires flash attention (flash_attn must be auto or on)";
        return nullptr;
    }
    const auto t0 = Clock::now();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_extra_bufts = params.use_extra_bufts;
    // With REPACK on, repacked tensors are copied into an anonymous CPU_REPACK buffer but
    // their mmap pages stay mapped (llama.cpp only unmaps the ends of the file), so RSS
    // counts those weights twice until the kernel reclaims the clean file pages. Without
    // mmap every weight is read exactly once into an anonymous buffer.
    mp.load_mode = params.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    llama_model *model = llama_model_load_from_file(model_path, mp);
    if (!model) {
        err = "llama_model_load_from_file failed (corrupt or unsupported GGUF)";
        return nullptr;
    }

    auto *e = new Engine();
    e->model = model;
    e->vocab = llama_model_get_vocab(model);
    e->params = params;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(params.n_ctx);
    cp.n_batch = static_cast<uint32_t>(params.n_batch);
    cp.n_ubatch = static_cast<uint32_t>(params.n_batch);
    cp.n_threads = params.n_threads;
    cp.n_threads_batch = params.n_threads;
    cp.n_seq_max = 1;
    // Logits are needed for the last prompt token, or for every token of a fast-forward
    // batch (1 + up to kMaxOutputs-1 draft tokens). Reserving kMaxOutputs instead of
    // n_batch outputs keeps the compute buffer small.
    cp.n_outputs_max = kMaxOutputs;
    cp.type_k = static_cast<ggml_type>(params.kv_type);
    cp.type_v = static_cast<ggml_type>(params.kv_type);
    cp.flash_attn_type = static_cast<llama_flash_attn_type>(params.flash_attn);
    cp.no_perf = true;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = e;
    e->ctx = llama_init_from_model(model, cp);
    if (!e->ctx) {
        err = "llama_init_from_model failed (out of memory?)";
        llama_model_free(model);
        delete e;
        return nullptr;
    }
    for (int c = 0x20; c < 0x7f; ++c) {
        const char ch = static_cast<char>(c);
        llama_token tok[4];
        const int nt = llama_tokenize(e->vocab, &ch, 1, tok, 4, false, false);
        if (nt == 1) e->ascii_tokens.emplace_back(ch, tok[0]);
    }
    load_ms = ms_since(t0);
    return e;
}

std::string model_description(Engine *e) {
    if (!e || !e->model) return {};
    char buf[256];
    const int n = llama_model_desc(e->model, buf, sizeof(buf));
    return n > 0 ? std::string(buf, static_cast<size_t>(std::min<int>(n, sizeof(buf) - 1)))
                 : std::string();
}

uint64_t model_size_bytes(Engine *e) { return (e && e->model) ? llama_model_size(e->model) : 0; }

void request_cancel(Engine *e) {
    if (e) e->cancel.store(true, std::memory_order_relaxed);
}

namespace {

struct SamplerSet {
    llama_sampler *chain = nullptr;    // top-k/top-p/temp/dist, or greedy
    llama_sampler *grammar = nullptr;  // optional
    ~SamplerSet() {
        if (chain) llama_sampler_free(chain);
        if (grammar) llama_sampler_free(grammar);
    }
};

// Grammar-constrained sampling in the style of llama.cpp common_sampler:
// sample from the unconstrained chain first, check the candidate against the
// grammar, and only on rejection apply the grammar to the full vocabulary and
// resample. This keeps per-token cost low for mostly-valid outputs.
llama_token sample_token(Engine *e, SamplerSet &s, std::vector<llama_token_data> &cur,
                         int32_t logits_idx = -1) {
    const int n_vocab = llama_vocab_n_tokens(e->vocab);
    const float *logits = llama_get_logits_ith(e->ctx, logits_idx);
    if (!logits) return LLAMA_TOKEN_NULL;

    auto fill = [&]() {
        cur.resize(static_cast<size_t>(n_vocab));
        for (int i = 0; i < n_vocab; ++i) cur[i] = llama_token_data{i, logits[i], 0.0f};
    };

    fill();
    llama_token_data_array arr{cur.data(), cur.size(), -1, false};
    llama_sampler_apply(s.chain, &arr);
    if (arr.selected < 0 || arr.selected >= static_cast<int64_t>(arr.size)) return LLAMA_TOKEN_NULL;
    llama_token id = arr.data[arr.selected].id;

    if (s.grammar) {
        llama_token_data single{id, 1.0f, 0.0f};
        llama_token_data_array one{&single, 1, -1, false};
        llama_sampler_apply(s.grammar, &one);
        if (std::isinf(single.logit) && single.logit < 0) {
            fill();
            llama_token_data_array full{cur.data(), cur.size(), -1, false};
            llama_sampler_apply(s.grammar, &full);
            llama_sampler_apply(s.chain, &full);
            if (full.selected < 0 || full.selected >= static_cast<int64_t>(full.size)) {
                return LLAMA_TOKEN_NULL;
            }
            id = full.data[full.selected].id;
            if (std::isinf(full.data[full.selected].logit) && full.data[full.selected].logit < 0) {
                return LLAMA_TOKEN_NULL;  // grammar left no valid continuation
            }
        }
        llama_sampler_accept(s.grammar, id);
    }
    llama_sampler_accept(s.chain, id);
    return id;
}

bool append_piece(const llama_vocab *vocab, llama_token id, std::string &out) {
    char buf[256];
    int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::vector<char> big(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, id, big.data(), static_cast<int32_t>(big.size()), 0, false);
        if (n < 0) return false;
        out.append(big.data(), static_cast<size_t>(n));
        return true;
    }
    out.append(buf, static_cast<size_t>(n));
    return true;
}

// Longest byte string the grammar forces from its current state: repeatedly probe the
// single-char ASCII tokens on a clone of the grammar and stop as soon as zero or more
// than one is admitted. Only a heuristic draft — every token is verified afterwards.
std::string forced_ascii(Engine *e, const llama_sampler *grammar, size_t max_len) {
    std::string out;
    if (!grammar || e->ascii_tokens.empty()) return out;
    llama_sampler *g = llama_sampler_clone(grammar);
    if (!g) return out;
    std::vector<llama_token_data> cand(e->ascii_tokens.size());
    while (out.size() < max_len) {
        for (size_t i = 0; i < e->ascii_tokens.size(); ++i) {
            cand[i] = llama_token_data{e->ascii_tokens[i].second, 0.0f, 0.0f};
        }
        llama_token_data_array arr{cand.data(), cand.size(), -1, false};
        llama_sampler_apply(g, &arr);
        int allowed = -1, n_allowed = 0;
        for (size_t i = 0; i < arr.size; ++i) {
            if (!(std::isinf(arr.data[i].logit) && arr.data[i].logit < 0)) {
                if (++n_allowed > 1) break;
                allowed = static_cast<int>(i);
            }
        }
        if (n_allowed != 1) break;
        const llama_token tok = arr.data[allowed].id;
        char ch = 0;
        for (const auto &pr : e->ascii_tokens) {
            if (pr.second == tok) { ch = pr.first; break; }
        }
        out.push_back(ch);
        llama_sampler_accept(g, tok);
    }
    llama_sampler_free(g);
    return out;
}

bool tokenize(const llama_vocab *vocab, const std::string &text, bool add_special,
              std::vector<llama_token> &out, std::string &err) {
    out.clear();
    if (text.empty()) return true;
    const int32_t len = static_cast<int32_t>(text.size());
    int32_t n = llama_tokenize(vocab, text.data(), len, nullptr, 0, add_special, true);
    if (n == INT32_MIN) {
        err = "text too long to tokenize";
        return false;
    }
    out.resize(static_cast<size_t>(n < 0 ? -n : n));
    if (!out.empty()) {
        n = llama_tokenize(vocab, text.data(), len, out.data(), static_cast<int32_t>(out.size()),
                           add_special, true);
    }
    if (n < 0) {
        err = "tokenize failed";
        return false;
    }
    out.resize(static_cast<size_t>(n));
    return true;
}

// Evaluates tokens in n_batch chunks at the next free positions of sequence 0.
// Returns llama_decode's code (0 ok, 2 aborted, other = error).
int decode_tokens(Engine *e, std::vector<llama_token> &tokens) {
    const int n = static_cast<int>(tokens.size());
    const int n_batch = static_cast<int>(llama_n_batch(e->ctx));
    for (int i = 0; i < n; i += n_batch) {
        if (should_abort(e)) return 2;
        const int len = std::min(n_batch, n - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, len);
        const int32_t rc = llama_decode(e->ctx, batch);
        if (rc != 0) return rc;
    }
    return 0;
}

}  // namespace

PrefixResult set_prefix(Engine *e, const std::string &prefix, const std::string &state_path,
                        bool force_rebuild) {
    PrefixResult r;
    if (!e || !e->ctx) {
        r.error = "model not loaded";
        return r;
    }
    e->cancel.store(false, std::memory_order_relaxed);
    llama_memory_t mem = llama_get_memory(e->ctx);
    llama_memory_clear(mem, true);
    e->prefix.clear();
    e->prefix_in_kv = false;
    if (prefix.empty()) {
        r.ok = true;  // clearing the prefix is allowed
        return r;
    }

    std::vector<llama_token> tokens;
    const auto t_tok = Clock::now();
    if (!tokenize(e->vocab, prefix, true, tokens, r.error)) return r;
    r.tokenize_ms = ms_since(t_tok);
    r.n_tokens = static_cast<int>(tokens.size());
    if (tokens.empty()) {
        r.error = "prefix tokenized to zero tokens";
        return r;
    }
    if (r.n_tokens + 64 > static_cast<int>(llama_n_ctx(e->ctx))) {
        r.error = "prefix (" + std::to_string(r.n_tokens) + " tokens) does not fit n_ctx";
        return r;
    }

    if (!state_path.empty() && !force_rebuild && file_exists(state_path)) {
        const auto t_restore = Clock::now();
        std::vector<llama_token> stored(tokens.size() + 1);
        size_t n_stored = 0;
        const size_t nread = llama_state_seq_load_file(e->ctx, state_path.c_str(), 0, stored.data(),
                                                       stored.size(), &n_stored);
        const bool same = nread > 0 && n_stored == tokens.size() &&
                          std::equal(tokens.begin(), tokens.end(), stored.begin()) &&
                          llama_memory_seq_pos_max(mem, 0) == r.n_tokens - 1;
        if (same) {
            r.restored_from_file = true;
            r.restore_ms = ms_since(t_restore);
            r.state_bytes = nread;
            e->prefix = std::move(tokens);
            e->prefix_in_kv = true;
            r.ok = true;
            return r;
        }
        llama_memory_clear(mem, true);  // stale / foreign file: rebuild below
    }

    const auto t_build = Clock::now();
    const int rc = decode_tokens(e, tokens);
    if (rc != 0) {
        llama_memory_clear(mem, true);
        r.error = rc == 2 ? "prefix build aborted" : "llama_decode(prefix) failed: " + std::to_string(rc);
        return r;
    }
    r.build_ms = ms_since(t_build);
    e->prefix = std::move(tokens);
    e->prefix_in_kv = true;
    r.ok = true;

    if (!state_path.empty()) {
        const auto t_save = Clock::now();
        const std::string tmp = state_path + ".tmp";
        const size_t nw = llama_state_seq_save_file(e->ctx, tmp.c_str(), 0, e->prefix.data(),
                                                    e->prefix.size());
        if (nw > 0 && std::rename(tmp.c_str(), state_path.c_str()) == 0) {
            r.saved_to_file = true;
            r.state_bytes = nw;
        } else {
            std::remove(tmp.c_str());
        }
        r.save_ms = ms_since(t_save);
    }
    return r;
}

void set_threads(Engine *e, int n_threads) {
    if (!e || !e->ctx || n_threads < 1 || n_threads > 64) return;
    e->params.n_threads = n_threads;
    llama_set_n_threads(e->ctx, n_threads, n_threads);
}

int set_affinity(const int *cpus, int n_cpus) {
    // The thread's original mask (Android cpuset of the app) is saved before the first
    // pin. "Unpin" restores it instead of forcing every CPU (which sched_setaffinity
    // rejects with EINVAL when the cpuset excludes cores), and pins are intersected
    // with it so a requested core outside the cpuset is dropped rather than failing.
    thread_local bool saved = false;
    thread_local cpu_set_t original;
    if (cpus == nullptr || n_cpus <= 0) {
        if (!saved) return 0;  // never pinned: nothing to undo
        return sched_setaffinity(0, sizeof(original), &original) == 0 ? 0 : errno;
    }
    if (!saved) {
        CPU_ZERO(&original);
        if (sched_getaffinity(0, sizeof(original), &original) != 0) return errno;
        saved = true;
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < n_cpus; ++i) {
        if (cpus[i] >= 0 && cpus[i] < CPU_SETSIZE && CPU_ISSET(cpus[i], &original)) CPU_SET(cpus[i], &set);
    }
    if (CPU_COUNT(&set) == 0) return EINVAL;
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? 0 : errno;
}

GenResult generate(Engine *e, const GenParams &p) {
    GenResult r;
    const auto t_start = Clock::now();
    if (!e || !e->ctx) {
        r.error = "model not loaded";
        return r;
    }
    e->cancel.store(false, std::memory_order_relaxed);
    e->deadline_us.store(p.timeout_ms > 0 ? now_us() + p.timeout_ms * 1000 : 0,
                         std::memory_order_relaxed);
    struct DeadlineReset {
        Engine *e;
        ~DeadlineReset() { e->deadline_us.store(0, std::memory_order_relaxed); }
    } deadline_reset{e};

    auto finish_abort = [&](GenResult &res) {
        res.status = e->cancel.load() ? GenStatus::kCancelled : GenStatus::kTimeout;
        res.total_ms = ms_since(t_start);
        return res;
    };

    if (p.max_tokens <= 0 || p.max_tokens > 4096) {
        r.error = "max_tokens out of range";
        return r;
    }

    llama_memory_t mem = llama_get_memory(e->ctx);
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));

    // Tokenize (parse_special so ChatML control tokens are recognised). A full prompt
    // gets BOS handling; a suffix never does (it continues the prefix).
    const auto t_tok = Clock::now();
    std::vector<llama_token> suffix;
    if (!tokenize(e->vocab, p.prompt, p.prefix_mode == PrefixMode::kNone, suffix, r.error)) {
        return r;
    }
    r.tokenize_ms = ms_since(t_tok);

    // Tokens this call must evaluate, and the KV position they start at.
    std::vector<llama_token> eval;
    if (p.prefix_mode == PrefixMode::kNone) {
        llama_memory_clear(mem, true);
        e->prefix_in_kv = false;
        eval = std::move(suffix);
        r.n_suffix_tokens = static_cast<int>(eval.size());
    } else {
        if (e->prefix.empty()) {
            r.error = "prefix mode requested but no prefix set";
            return r;
        }
        r.n_prefix_tokens = static_cast<int>(e->prefix.size());
        r.n_suffix_tokens = static_cast<int>(suffix.size());
        const bool reuse = p.prefix_mode == PrefixMode::kCached && e->prefix_in_kv &&
                           llama_memory_seq_pos_max(mem, 0) >= r.n_prefix_tokens - 1 &&
                           llama_memory_seq_rm(mem, 0, r.n_prefix_tokens, -1);
        if (reuse) {
            eval = std::move(suffix);
        } else {
            // Cold (or the cached prefix was lost): rebuild from exactly the same tokens.
            llama_memory_clear(mem, true);
            e->prefix_in_kv = false;
            eval.reserve(e->prefix.size() + suffix.size());
            eval.insert(eval.end(), e->prefix.begin(), e->prefix.end());
            eval.insert(eval.end(), suffix.begin(), suffix.end());
        }
    }
    const int n = static_cast<int>(eval.size());
    r.n_prompt_tokens = n;
    if (n <= 0) {
        r.error = "prompt tokenized to zero tokens";
        return r;
    }
    const int n_past = std::max(0, static_cast<int>(llama_memory_seq_pos_max(mem, 0)) + 1);
    if (n_past + n + p.max_tokens > n_ctx) {
        r.status = GenStatus::kContextFull;
        r.error = "prompt (" + std::to_string(n_past + n) + ") + max_tokens exceeds n_ctx (" +
                  std::to_string(n_ctx) + ")";
        return r;
    }

    SamplerSet samplers;
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    samplers.chain = llama_sampler_chain_init(sp);
    if (p.temperature <= 0.0f) {
        llama_sampler_chain_add(samplers.chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(samplers.chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(samplers.chain, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(samplers.chain, llama_sampler_init_temp(p.temperature));
        llama_sampler_chain_add(samplers.chain, llama_sampler_init_dist(p.seed));
    }
    if (!p.grammar.empty()) {
        samplers.grammar = llama_sampler_init_grammar(e->vocab, p.grammar.c_str(), "root");
        if (!samplers.grammar) {
            r.error = "invalid GBNF grammar";
            return r;
        }
    }

    // Prompt evaluation in n_batch chunks.
    const auto t_prompt = Clock::now();
    {
        const int rc = decode_tokens(e, eval);
        if (rc == 2 || should_abort(e)) {
            e->prefix_in_kv = false;
            return finish_abort(r);
        }
        if (rc != 0) {
            e->prefix_in_kv = false;
            r.error = "llama_decode(prompt) failed: " + std::to_string(rc);
            return r;
        }
    }
    r.prompt_eval_ms = ms_since(t_prompt);
    // The prefix is now in KV (it was either kept or just evaluated as part of eval).
    if (p.prefix_mode != PrefixMode::kNone) e->prefix_in_kv = true;

    std::vector<llama_token_data> cur;
    Clock::time_point t_first{};
    const bool ff = p.fast_forward && samplers.grammar != nullptr && p.temperature <= 0.0f;
    llama_batch batch = llama_batch_init(kMaxOutputs, 0, 1);
    struct BatchFree {
        llama_batch &b;
        ~BatchFree() { llama_batch_free(b); }
    } batch_free{batch};
    std::vector<llama_token> draft;

    // Samples from the logits at idx and records the token. Returns false when
    // generation is over (EOG / max tokens) or failed (r.error set).
    auto take = [&](int32_t idx, llama_token &out) -> bool {
        out = sample_token(e, samplers, cur, idx);
        if (out == LLAMA_TOKEN_NULL) {
            r.error = "sampling failed";
            return false;
        }
        if (r.n_gen_tokens == 0 && t_first == Clock::time_point{}) {
            t_first = Clock::now();
            r.ttft_ms = ms_since(t_start);
        }
        if (llama_vocab_is_eog(e->vocab, out)) {
            r.status = GenStatus::kEndOfGeneration;
            return false;
        }
        r.n_gen_tokens++;
        if (!append_piece(e->vocab, out, r.text)) {
            r.error = "detokenize failed";
            return false;
        }
        if (r.n_gen_tokens >= p.max_tokens) {
            r.status = GenStatus::kMaxTokens;
            return false;
        }
        return true;
    };

    llama_token id;  // sampled, accepted by the grammar, not yet decoded
    bool more = take(-1, id);
    while (more) {
        if (should_abort(e)) {
            r.decode_ms = ms_since(t_first);
            return finish_abort(r);
        }
        // Draft = tokenization of the byte string the grammar now forces.
        draft.clear();
        if (ff) {
            const auto t_d = Clock::now();
            const std::string forced = forced_ascii(e, samplers.grammar, 64);
            std::string terr;
            if (!forced.empty() && tokenize(e->vocab, forced, false, draft, terr)) {
                const int room = std::min({kMaxOutputs - 1, p.max_tokens - r.n_gen_tokens,
                                           static_cast<int>(llama_n_batch(e->ctx)) - 1});
                if (static_cast<int>(draft.size()) > room) draft.resize(static_cast<size_t>(std::max(0, room)));
            } else {
                draft.clear();
            }
            r.draft_ms += ms_since(t_d);
        }
        const int n_past = static_cast<int>(llama_memory_seq_pos_max(mem, 0)) + 1;
        if (n_past + 1 + static_cast<int>(draft.size()) > n_ctx) draft.clear();

        // Decode [id, draft...] in one call, with logits at every position.
        batch.n_tokens = 0;
        auto push = [&](llama_token t) {
            const int i = batch.n_tokens++;
            batch.token[i] = t;
            batch.pos[i] = n_past + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = true;
        };
        push(id);
        for (llama_token t : draft) push(t);
        const int32_t rc = llama_decode(e->ctx, batch);
        r.n_decode_calls++;
        if (rc == 2 || should_abort(e)) {
            r.decode_ms = ms_since(t_first);
            return finish_abort(r);
        }
        if (rc != 0) {
            r.error = "llama_decode(token) failed: " + std::to_string(rc);
            r.total_ms = ms_since(t_start);
            return r;
        }
        r.n_draft_tokens += static_cast<int>(draft.size());

        // Verify with the plain path's exact sampling rule: logits at batch index j are
        // the prediction after batch.token[j]. A draft token is kept only if it is what
        // constrained greedy sampling picks there; the first mismatch (or the position
        // after the last draft) yields the next pending token.
        for (size_t j = 0; j <= draft.size(); ++j) {
            llama_token s;
            more = take(static_cast<int32_t>(j), s);
            const bool matched = j < draft.size() && s == draft[j];
            if (matched) r.n_forced_tokens++;
            if (!matched && j < draft.size()) {
                // Rejected draft: drop it and the rest from the KV cache.
                llama_memory_seq_rm(mem, 0, n_past + 1 + static_cast<int>(j), -1);
            }
            if (!more) break;
            if (!matched) {
                id = s;
                break;
            }
        }
        if (!r.error.empty()) {
            r.total_ms = ms_since(t_start);
            return r;
        }
    }
    if (!r.error.empty()) {
        r.total_ms = ms_since(t_start);
        return r;
    }
    if (r.n_gen_tokens > 0 || r.status == GenStatus::kEndOfGeneration) {
        r.decode_ms = (t_first == Clock::time_point{}) ? 0 : ms_since(t_first);
    }
    if (r.status == GenStatus::kError && r.error.empty()) r.status = GenStatus::kMaxTokens;
    r.total_ms = ms_since(t_start);
    return r;
}

void release(Engine *e) {
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // namespace vk
