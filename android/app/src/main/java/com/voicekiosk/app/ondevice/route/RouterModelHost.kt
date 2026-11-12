package com.voicekiosk.app.ondevice.route

import android.content.Context
import android.util.Log
import com.voicekiosk.app.ondevice.BenchmarkPrompt
import com.voicekiosk.app.ondevice.CpuTopology
import com.voicekiosk.app.ondevice.DeviceTier
import com.voicekiosk.app.ondevice.DeviceTierDetector
import com.voicekiosk.app.ondevice.LlmEngine
import com.voicekiosk.app.ondevice.OnDeviceModel
import com.voicekiosk.app.ondevice.OnDeviceModelCatalog
import com.voicekiosk.app.ondevice.OnDeviceModelDownloader
import com.voicekiosk.app.ondevice.RoutingDefaults
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Owns the L1 model inside the voice service process. Loads only when the device tier
 * is HIGH and the tier's default model (Qwen2.5-1.5B Q4_K_M) is already downloaded and
 * verified; it never downloads anything (that stays in the benchmark screen for now).
 *
 * Loading runs on a background thread with the S0.2 defaults (no mmap, repack, q8_0 KV,
 * FA, big-core threads, no pinning); the routing prefix KV is restored from / saved to
 * a state file, so warm restarts skip the ~3 s prefix build. Until [state] is READY the
 * orchestrator sends model-needing turns to the cloud.
 */
class RouterModelHost(private val context: Context) {

    enum class State { IDLE, UNAVAILABLE, LOADING, READY, FAILED }

    private val stateRef = AtomicReference(State.IDLE)
    @Volatile var statusText: String = "未加载"
        private set
    @Volatile private var engine: LlmEngine? = null
    @Volatile private var model: OnDeviceModel? = null
    @Volatile private var prefixText: String? = null
    @Volatile private var prefixStateFile: File? = null
    /** True while another prompt (memory extraction) owns the KV cache: routing goes to the cloud. */
    @Volatile private var busyExclusive = false

    val state: State get() = stateRef.get()

    /** Why L1 can or cannot run here, without loading anything (for the settings page). */
    fun availability(): Pair<OnDeviceModel?, String> = availability(context)

    fun classifier(): IntentClassifier? {
        val e = engine ?: return null
        val m = model ?: return null
        return ModelIntentClassifier(e, m.promptFormat, ready = { stateRef.get() == State.READY && !busyExclusive })
    }

    /** Starts loading in the background if allowed; returns false when L1 is unavailable. */
    fun startLoading(): Boolean {
        val (m, why) = availability(context)
        if (m == null) {
            stateRef.set(State.UNAVAILABLE)
            statusText = why
            Log.i(TAG, "L1 unavailable: $why")
            return false
        }
        if (!stateRef.compareAndSet(State.IDLE, State.LOADING) && !stateRef.compareAndSet(State.FAILED, State.LOADING)) return true
        model = m
        val e = LlmEngine(nativeLibDir = context.applicationInfo.nativeLibraryDir)
        engine = e
        statusText = "加载中…"
        thread(name = "VoiceKiosk-L1Load", isDaemon = true) {
            try {
                val file = OnDeviceModelDownloader(OnDeviceModelDownloader.defaultDir(context)).fileFor(m)
                val cfg = LlmEngine.LoadConfig(threads = CpuTopology.read().autoThreads())
                val t0 = System.currentTimeMillis()
                val load = e.load(file, cfg).get()
                if (load is LlmEngine.LoadResult.Failure) throw IllegalStateException("load: ${load.reason}")
                e.setCpuAffinity(emptyList()).get()
                val prefixText = BenchmarkPrompt.prefix(m.promptFormat)
                val key = RoutingDefaults.prefixStateKey(m.sha256 ?: "unverified-${m.fileName}", BenchmarkPrompt.VERSION, prefixText, cfg.kvType, RoutingDefaults.LLAMA_TAG)
                val stateFile = File(File(context.cacheDir, "llm-prefix-router").apply { mkdirs() }, "prefix-$key.state")
                this.prefixText = prefixText
                this.prefixStateFile = stateFile
                val prefix = e.setPrefix(prefixText, stateFile).get()
                if (prefix is LlmEngine.PrefixResult.Failure) throw IllegalStateException("prefix: ${prefix.reason}")
                if (stateRef.get() != State.LOADING) return@thread // released meanwhile
                stateRef.set(State.READY)
                statusText = "已就绪（${m.displayName}，${System.currentTimeMillis() - t0}ms）"
                Log.i(TAG, "L1 ready: $statusText")
            } catch (t: Throwable) {
                Log.w(TAG, "L1 load failed", t)
                statusText = "加载失败：${t.message}"
                stateRef.set(State.FAILED)
                try { e.release().get() } catch (_: Exception) {}
            }
        }
        return true
    }

    /**
     * S5: runs [block] (a full-prompt generation such as memory extraction) on the loaded
     * engine, then restores the routing prefix KV from its state file. While it runs the
     * classifier reports not-ready, so a turn arriving meanwhile goes straight to the cloud
     * instead of waiting. Returns null when the model is not ready.
     */
    fun <T> runExclusive(block: (LlmEngine, com.voicekiosk.app.ondevice.PromptFormat) -> T): T? {
        val e = engine ?: return null
        val m = model ?: return null
        val p = prefixText ?: return null
        synchronized(this) {
            if (stateRef.get() != State.READY || busyExclusive) return null
            busyExclusive = true
        }
        try {
            return block(e, m.promptFormat)
        } finally {
            try {
                val res = e.setPrefix(p, prefixStateFile).get()
                if (res is LlmEngine.PrefixResult.Failure) Log.w(TAG, "prefix restore failed: ${res.reason}")
            } catch (t: Throwable) {
                Log.w(TAG, "prefix restore failed", t)
            }
            busyExclusive = false
        }
    }

    /** Interrupts a running exclusive generation (a new turn started). */
    fun cancelExclusive() {
        if (busyExclusive) engine?.cancel()
    }

    /** Frees the model (service stop, memory pressure). */
    fun release() {
        stateRef.set(State.IDLE)
        statusText = "已卸载"
        val e = engine
        engine = null
        // close() waits for an in-flight load/generation; never block the caller (main thread)
        if (e != null) thread(name = "VoiceKiosk-L1Release", isDaemon = true) { try { e.close() } catch (_: Exception) {} }
    }

    companion object {
        private const val TAG = "RouterModelHost"

        fun availability(context: Context): Pair<OnDeviceModel?, String> {
            val decision = DeviceTierDetector.classify(DeviceTierDetector.collect(context))
            if (decision.tier != DeviceTier.HIGH) return null to "本机为${decision.tier.name}档，不使用端侧模型（仅规则+云端）"
            val m = OnDeviceModelCatalog.defaultFor(DeviceTier.HIGH) ?: return null to "没有高档默认模型"
            val dl = OnDeviceModelDownloader(OnDeviceModelDownloader.defaultDir(context))
            if (!dl.isReadyFast(m)) return null to "未下载 ${m.displayName}（请在「端侧模型基准测试」里下载）"
            return m to "可用：${m.displayName}"
        }
    }
}
