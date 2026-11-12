package com.voicekiosk.app.memory.eval

import com.voicekiosk.app.memory.ByteCodec
import com.voicekiosk.app.memory.LocalMemoryCache
import com.voicekiosk.app.memory.MemoryBackend
import com.voicekiosk.app.memory.MemoryCandidate
import com.voicekiosk.app.memory.MemoryManager
import com.voicekiosk.app.memory.MemorySnapshot
import com.voicekiosk.app.memory.ModelMemoryExtractor
import com.voicekiosk.app.memory.SubmitResult
import com.voicekiosk.app.ondevice.LlmEngine
import com.voicekiosk.app.ondevice.PromptFormat
import java.io.File

/**
 * Host / device eval of model memory extraction on [MemoryEvalSet] (S5). The engine must
 * already be loaded with the routing prefix set ([prefixText], [stateFile]); after every
 * extraction the prefix is restored exactly like RouterModelHost.runExclusive does.
 */
object MemoryEvalRunner {
    data class Result(val pipeline: MemoryEval.Report, val modelOnly: MemoryEval.Report, val restoreMs: List<Long>, val raw: String)

    private object NoBackend : MemoryBackend {
        override fun snapshot(since: String) = kotlin.Result.success(MemorySnapshot(emptyList()))
        override fun submit(candidates: List<MemoryCandidate>) = kotlin.Result.success(emptyList<SubmitResult>())
    }

    private object Plain : ByteCodec {
        override fun encode(plain: ByteArray) = plain
        override fun decode(stored: ByteArray) = stored
    }

    fun run(
        engine: LlmEngine, format: PromptFormat, prefixText: String, stateFile: File,
        cases: List<MemoryEvalSet.Case>, log: (String) -> Unit = {}
    ): Result {
        val ex = ModelMemoryExtractor(engine, format)
        val restore = mutableListOf<Long>()
        val raw = StringBuilder()
        fun restorePrefix() {
            val t = System.currentTimeMillis()
            val r = engine.setPrefix(prefixText, stateFile).get()
            restore += System.currentTimeMillis() - t
            if (r is LlmEngine.PrefixResult.Failure) log("prefix restore failed: ${r.reason}")
        }
        val pipe = mutableListOf<MemoryEval.Row>()
        val alone = mutableListOf<MemoryEval.Row>()
        for (c in cases) {
            val mm = MemoryManager(NoBackend, LocalMemoryCache(null, Plain), model = {
                { u: List<String> ->
                    ex.extract(u).also { o ->
                        raw.append("${c.id}\tpipe\t$u\t${o.status}\t${o.ms}\t${o.raw}\n")
                        restorePrefix()
                    }
                }
            }, executor = { it.run() })
            val r = mm.process(c.utterances, upload = false)
            pipe += MemoryEval.Row(c, r.facts, r.modelStatus, r.modelMs)
            val o = ex.extract(c.utterances)
            if (o.status != "no_input") restorePrefix()
            raw.append("${c.id}\tmodel\t${c.utterances}\t${o.status}\t${o.ms}\t${o.raw}\n")
            alone += MemoryEval.Row(c, o.facts, o.status, o.ms)
            log("${c.id} pipeline=${r.facts.map { it.content }} model=${o.status} ${o.ms}ms ${o.facts.map { it.content }}")
        }
        return Result(MemoryEval.Report(pipe), MemoryEval.Report(alone), restore, raw.toString())
    }
}
