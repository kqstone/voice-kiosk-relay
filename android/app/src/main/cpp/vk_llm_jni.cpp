// JNI bridge for com.voicekiosk.app.ondevice.JniNativeLlm.
// Never throws Java exceptions for recoverable errors: failures are returned as
// status codes / error strings so the Kotlin layer can degrade gracefully.
#include <android/log.h>
#include <jni.h>

#include <string>

#include "vk_llm_core.h"

#define VK_LOG_TAG "VkLlm"

namespace {

vk::Engine *as_engine(jlong h) { return reinterpret_cast<vk::Engine *>(h); }

std::string to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    if (!c) return {};
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java strings → UTF-8 via byte[] to avoid JNI "modified UTF-8" issues with emoji.
std::string bytes_to_std(JNIEnv *env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray std_to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

void set_err(JNIEnv *env, jobjectArray errOut, const std::string &msg) {
    if (!errOut || env->GetArrayLength(errOut) < 1) return;
    jbyteArray b = std_to_bytes(env, msg);
    env->SetObjectArrayElement(errOut, 0, b);
}

}  // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeBackendInit(JNIEnv *env, jclass, jstring dir) {
    const std::string d = to_std(env, dir);
    const std::string summary = vk::backend_init(d.c_str());
    __android_log_print(ANDROID_LOG_INFO, VK_LOG_TAG, "backends: %s", summary.c_str());
    return std_to_bytes(env, summary);
}

JNIEXPORT jbyteArray JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeSystemInfo(JNIEnv *env, jclass) {
    return std_to_bytes(env, vk::system_info());
}

// metricsOut[0] = load_ms, [1] = model size bytes. textOut[0] = UTF-8 error bytes,
// textOut[1] = llama.cpp buffer-size log lines (memory breakdown).
JNIEXPORT jlong JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeLoad(JNIEnv *env, jclass, jstring path,
                                                        jint nCtx, jint nThreads, jint nBatch,
                                                        jint kvType, jint flashAttn,
                                                        jboolean useExtraBufts,
                                                        jboolean useMmap,
                                                        jdoubleArray metricsOut,
                                                        jobjectArray errOut) {
    vk::LoadParams lp;
    lp.n_ctx = nCtx;
    lp.n_threads = nThreads;
    lp.n_batch = nBatch;
    lp.kv_type = kvType;
    lp.flash_attn = flashAttn;
    lp.use_extra_bufts = useExtraBufts == JNI_TRUE;
    lp.use_mmap = useMmap == JNI_TRUE;
    std::string err;
    std::string memlog;
    double load_ms = 0;
    const std::string p = to_std(env, path);
    vk::Engine *e = vk::load(p.c_str(), lp, err, load_ms, &memlog);
    if (errOut && env->GetArrayLength(errOut) >= 2) {
        env->SetObjectArrayElement(errOut, 1, std_to_bytes(env, memlog));
    }
    if (!e) {
        __android_log_print(ANDROID_LOG_WARN, VK_LOG_TAG, "load failed: %s", err.c_str());
        set_err(env, errOut, err);
        return 0;
    }
    if (metricsOut && env->GetArrayLength(metricsOut) >= 2) {
        const jdouble m[2] = {load_ms, static_cast<jdouble>(vk::model_size_bytes(e))};
        env->SetDoubleArrayRegion(metricsOut, 0, 2, m);
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jbyteArray JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeModelDescription(JNIEnv *env, jobject,
                                                                    jlong h) {
    return std_to_bytes(env, vk::model_description(as_engine(h)));
}

// metricsOut[0]=n_tokens [1]=restored(0/1) [2]=saved(0/1) [3]=tokenize_ms [4]=build_ms
// [5]=restore_ms [6]=save_ms [7]=state_bytes. Returns true on success.
JNIEXPORT jboolean JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeSetPrefix(JNIEnv *env, jclass, jlong h,
                                                             jbyteArray prefix, jstring statePath,
                                                             jboolean forceRebuild,
                                                             jdoubleArray metricsOut,
                                                             jobjectArray errOut) {
    const vk::PrefixResult r = vk::set_prefix(as_engine(h), bytes_to_std(env, prefix),
                                              to_std(env, statePath), forceRebuild == JNI_TRUE);
    if (metricsOut && env->GetArrayLength(metricsOut) >= 8) {
        const jdouble m[8] = {static_cast<jdouble>(r.n_tokens),
                              r.restored_from_file ? 1.0 : 0.0,
                              r.saved_to_file ? 1.0 : 0.0,
                              r.tokenize_ms,
                              r.build_ms,
                              r.restore_ms,
                              r.save_ms,
                              static_cast<jdouble>(r.state_bytes)};
        env->SetDoubleArrayRegion(metricsOut, 0, 8, m);
    }
    if (!r.error.empty()) set_err(env, errOut, r.error);
    return r.ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeSetThreads(JNIEnv *, jclass, jlong h,
                                                              jint n) {
    vk::set_threads(as_engine(h), n);
}

// Pins the calling (inference) thread; ggml worker threads inherit it. Returns errno.
JNIEXPORT jint JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeSetAffinity(JNIEnv *env, jclass,
                                                               jintArray cpus) {
    if (!cpus) return vk::set_affinity(nullptr, 0);
    const jsize n = env->GetArrayLength(cpus);
    std::string buf(static_cast<size_t>(n) * sizeof(jint), '\0');
    if (n > 0) env->GetIntArrayRegion(cpus, 0, n, reinterpret_cast<jint *>(buf.data()));
    return vk::set_affinity(reinterpret_cast<const int *>(buf.data()), n);
}

// metricsOut layout (size >= 14):
// [0]=status [1]=n_prompt(evaluated) [2]=n_gen [3]=tokenize_ms [4]=prompt_eval_ms
// [5]=ttft_ms [6]=decode_ms [7]=total_ms [8]=n_prefix [9]=n_suffix [10]=decode_calls
// [11]=forced_tokens [12]=draft_tokens [13]=draft_ms.
// errOut[0] = UTF-8 error bytes.
// Returns generated text as UTF-8 bytes.
JNIEXPORT jbyteArray JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeGenerate(JNIEnv *env, jclass, jlong h,
                                                            jbyteArray prompt, jint prefixMode,
                                                            jint maxTokens,
                                                            jbyteArray grammar, jfloat temperature,
                                                            jlong timeoutMs, jint seed,
                                                            jboolean fastForward,
                                                            jdoubleArray metricsOut,
                                                            jobjectArray errOut) {
    vk::GenParams gp;
    gp.prompt = bytes_to_std(env, prompt);
    gp.prefix_mode = static_cast<vk::PrefixMode>(prefixMode < 0 || prefixMode > 2 ? 0 : prefixMode);
    gp.max_tokens = maxTokens;
    gp.grammar = bytes_to_std(env, grammar);
    gp.temperature = temperature;
    gp.timeout_ms = timeoutMs;
    gp.seed = static_cast<uint32_t>(seed);
    gp.fast_forward = fastForward == JNI_TRUE;
    const vk::GenResult r = vk::generate(as_engine(h), gp);
    if (metricsOut && env->GetArrayLength(metricsOut) >= 14) {
        const jdouble m[14] = {static_cast<jdouble>(static_cast<int>(r.status)),
                              static_cast<jdouble>(r.n_prompt_tokens),
                              static_cast<jdouble>(r.n_gen_tokens),
                              r.tokenize_ms,
                              r.prompt_eval_ms,
                              r.ttft_ms,
                              r.decode_ms,
                              r.total_ms,
                              static_cast<jdouble>(r.n_prefix_tokens),
                              static_cast<jdouble>(r.n_suffix_tokens),
                              static_cast<jdouble>(r.n_decode_calls),
                              static_cast<jdouble>(r.n_forced_tokens),
                              static_cast<jdouble>(r.n_draft_tokens),
                              r.draft_ms};
        env->SetDoubleArrayRegion(metricsOut, 0, 14, m);
    }
    if (!r.error.empty()) set_err(env, errOut, r.error);
    return std_to_bytes(env, r.text);
}

JNIEXPORT void JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeCancel(JNIEnv *, jclass, jlong h) {
    vk::request_cancel(as_engine(h));
}

JNIEXPORT void JNICALL
Java_com_voicekiosk_app_ondevice_JniNativeLlm_nativeRelease(JNIEnv *, jclass, jlong h) {
    vk::release(as_engine(h));
}

}  // extern "C"
