package com.voicekiosk.app.ondevice.eval

import com.voicekiosk.app.ondevice.route.ClassifyResult
import com.voicekiosk.app.ondevice.route.IntentClassifier
import com.voicekiosk.app.ondevice.route.Intents
import com.voicekiosk.app.ondevice.route.Route
import com.voicekiosk.app.ondevice.route.RouteDecision
import com.voicekiosk.app.ondevice.route.RouteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEvalTest {
    private class Fixed(val d: (String) -> RouteDecision?) : IntentClassifier {
        override val isReady = true
        override fun classify(text: String, recent: String, timeoutMs: Long, chitchat: Boolean): ClassifyResult =
            if (chitchat) ClassifyResult(RouteDecision(Intents.CHITCHAT, emptyMap(), Route.LOCAL, 0.95, RouteSource.MODEL), "ok", ms = 5, reply = "你好呀")
            else ClassifyResult(d(text), "ok", ms = 5)
    }

    private val candidates: List<(String) -> RouteDecision> = listOf(
        { _ -> d(Intents.VOLUME, "direction" to "up") },
        { _ -> d(Intents.VOLUME, "level" to "50") },
        { _ -> d(Intents.MEDIA_CONTROL, "action" to "next") },
        { _ -> d(Intents.MEDIA_CONTROL, "action" to "pause") },
        { t -> d(Intents.MEDIA_PLAY, "song" to t.take(20)) },
        { t -> d(Intents.MEDIA_PLAY, "artist" to t.take(2)) },
        { _ -> d(Intents.TIMER, "minutes" to "5") },
        { _ -> d(Intents.RESET) },
        { _ -> d(Intents.WEATHER, "day" to "today") },
        { _ -> d(Intents.WEATHER, "city" to "北京", "day" to "tomorrow") },
        { _ -> d(Intents.TIME) },
        { _ -> d(Intents.CHITCHAT) }
    )

    @Test
    fun evalSetIsWellFormed() {
        val cases = RouteEvalSet.CASES
        assertTrue(cases.size >= 200)
        val q = RouteEvalSet.quick()
        assertEquals(60, q.size)
        assertEquals(q, RouteEvalSet.quick())
        assertTrue(q.count { it.local } in 20..40)
        assertEquals(cases.map { it.category }.toSet(), q.map { it.category }.toSet())
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        assertTrue("need many must-cloud negatives", cases.count { !it.local } >= 90)
        assertTrue(cases.any { it.history.isNotEmpty() && it.local })
        assertTrue(cases.any { it.history.isNotEmpty() && !it.local })
        val intents = (com.voicekiosk.app.ondevice.BenchmarkPrompt.INTENTS).toSet()
        cases.forEach { assertTrue(it.id, it.intent in intents) }
    }

    @Test
    fun rulesOnlyNeverGoLocalOnMustCloud() {
        val r = RouteEval(null).run()
        assertEquals(r.falseLocal.joinToString("\n") { it.line() }, 0, r.falseLocal.size)
        assertEquals(r.wrongAction.joinToString("\n") { it.line() }, 0, r.wrongAction.size)
        // most local commands are rule hits (small talk needs the model)
        assertTrue(r.summary(), r.localRecall > 0.75)
    }

    /**
     * Worst-case model: for every case, a classifier that confidently claims each
     * model-local intent in turn. The deterministic guards alone must keep every
     * must-cloud case in the cloud.
     */
    @Test
    fun adversarialModelCannotCauseFalseLocal() {
        val bad = mutableSetOf<String>()
        for (c in candidates) {
            val r = RouteEval(Fixed(c)).run()
            r.falseLocal.forEach { bad += it.line() }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    /** The S3.1 pre-gate never drops a sentence the post-guards would accept (any candidate decision). */
    @Test
    fun preGateIsExactWithRespectToGuards() {
        val intents = com.voicekiosk.app.ondevice.route.TurnOrchestrator.MODEL_LOCAL_INTENTS
        val extra = listOf("把那个声音往上提提", "周董那首七里香给我整一个", "外头明儿个凉不凉快", "帮我计个时八分钟", "我们从头再来", "跳过这首")
        for (text in RouteEvalSet.CASES.map { it.text to it.history.lastOrNull() } + extra.map { it to null }) {
            for (c in candidates) {
                val d = c(text.first)
                if (com.voicekiosk.app.ondevice.route.ModelGuards.check(text.first, d, text.second) != null && d.intent in intents) {
                    assertTrue("gate drops ${text.first} (${d.stateLine()})", com.voicekiosk.app.ondevice.route.ModelGuards.mayBeLocal(text.first, text.second, intents))
                }
            }
        }
        // and it actually filters: most must-cloud sentences never reach the model
        val gated = RouteEvalSet.CASES.filter { !it.local }.count { !com.voicekiosk.app.ondevice.route.ModelGuards.mayBeLocal(it.text, it.history.lastOrNull(), intents) }
        assertTrue("gated $gated", gated >= 100)
    }

    @Test
    fun reportCountsFalseLocalAndWrongAction() {
        val clf = Fixed { t -> if (t == "讲个笑话") d(Intents.MEDIA_PLAY, "song" to "讲个笑话") else null }
        val r = RouteEval(clf).run(RouteEvalSet.CASES.filter { it.text == "讲个笑话" || it.text == "声音大一点" })
        assertEquals(0, r.falseLocal.size) // guard: no play verb
        assertTrue(r.summary().contains("false-local 0/1"))
        assertTrue(r.tsv().lines().size >= 3)
    }

    private fun d(intent: String, vararg slots: Pair<String, String>) =
        RouteDecision(intent, mapOf(*slots), Route.LOCAL, 0.95, RouteSource.MODEL)
}
