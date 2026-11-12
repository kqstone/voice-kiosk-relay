package com.voicekiosk.app.ondevice.eval

import com.voicekiosk.app.ondevice.BenchmarkPrompt
import com.voicekiosk.app.ondevice.LlmEngine
import com.voicekiosk.app.ondevice.OnDeviceModel
import com.voicekiosk.app.ondevice.RoutingDefaults
import com.voicekiosk.app.ondevice.route.ModelIntentClassifier
import com.voicekiosk.app.ondevice.route.TurnOrchestrator
import java.io.File

/**
 * Loads a model with the routing defaults (same as the voice service's RouterModelHost:
 * prefix KV restored from / saved to [stateDir]), runs [RouteEval] and returns the report.
 * Shared by the phone benchmark screen ("路由评测") and the host JNI runner.
 */
object RouteEvalRunner {
    fun run(
        engine: LlmEngine,
        modelFile: File,
        model: OnDeviceModel,
        stateDir: File,
        threads: Int,
        config: TurnOrchestrator.Config = TurnOrchestrator.Config(),
        cases: List<RouteEvalSet.Case> = RouteEvalSet.CASES,
        cancelled: () -> Boolean = { false },
        modelAllCases: Boolean = true,
        beforeCase: () -> String = { "" },
        log: (String) -> Unit = {}
    ): RouteEval.Report {
        val cfg = LlmEngine.LoadConfig(threads = threads)
        val load = engine.load(modelFile, cfg).get()
        if (load is LlmEngine.LoadResult.Failure) throw IllegalStateException("load: ${load.reason}")
        log("loaded ${model.displayName} threads=$threads")
        val prefixText = BenchmarkPrompt.prefix(model.promptFormat)
        val key = RoutingDefaults.prefixStateKey(model.sha256 ?: "unverified-${model.fileName}", BenchmarkPrompt.VERSION, prefixText, cfg.kvType, RoutingDefaults.LLAMA_TAG)
        stateDir.mkdirs()
        val prefix = engine.setPrefix(prefixText, File(stateDir, "prefix-$key.state")).get()
        if (prefix is LlmEngine.PrefixResult.Failure) throw IllegalStateException("prefix: ${prefix.reason}")
        log("prefix ready: $prefix")
        val clf = ModelIntentClassifier(engine, model.promptFormat, ready = { true })
        // warm-up (first generation after load is slower)
        clf.classify("现在几点", "无", config.modelTimeoutMs)
        return RouteEval(clf, config, modelAllCases = modelAllCases).run(cases, model.displayName, cancelled, beforeCase) { i, n -> log("路由评测 ${i + 1}/$n") }
    }
}
