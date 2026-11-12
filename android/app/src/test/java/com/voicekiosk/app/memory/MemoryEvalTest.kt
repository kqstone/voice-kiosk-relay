package com.voicekiosk.app.memory

import com.voicekiosk.app.memory.eval.MemoryEval
import com.voicekiosk.app.memory.eval.MemoryEvalSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules-only (MID/LOW tier) extraction on the labeled set; the model run is the host eval. */
class MemoryEvalTest {
    private fun run(cases: List<MemoryEvalSet.Case>) = MemoryEval.Report(cases.map { c ->
        val m = MemoryManager(FakeBackend(), LocalMemoryCache(null, IdentityCodec), executor = DirectExecutor)
        val r = m.process(c.utterances, upload = false)
        MemoryEval.Row(c, r.facts, r.modelStatus, r.modelMs)
    })

    @Test
    fun heldOutHasNoLeaks() {
        val rep = run(MemoryEvalSet.HELDOUT)
        println(rep.summary("rules only, held-out"))
        assertEquals(0, rep.leaks.size)
    }

    @Test
    fun rulesOnlyHasNoLeaksAndPreciseAutoWrites() {
        val rep = run(MemoryEvalSet.CASES)
        println(rep.summary("rules only"))
        assertTrue(MemoryEvalSet.CASES.size >= 80)
        assertTrue(MemoryEvalSet.CASES.count { it.sensitive } >= 25)
        assertEquals(0, rep.leaks.size)
        assertEquals(1.0, rep.autoPrecision, 0.0)
        assertEquals(0, rep.falsePositivesOnNegatives)
        assertTrue("recall ${rep.recall}", rep.recall >= 0.6)
    }
}
