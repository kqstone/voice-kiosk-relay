package com.voicekiosk.app.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BenchmarkPromptTest {
    @Test
    fun chatmlFormatting() {
        val p = PromptFormat.CHATML.format("sys", "hi")
        assertEquals("<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n", p)
        val q3 = PromptFormat.CHATML_QWEN3_NO_THINK.format("sys", "hi")
        assertTrue(q3.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun prefixPlusSuffixIsExactlyTheFullPrompt() {
        for (f in PromptFormat.values()) {
            for (c in BenchmarkPrompt.CASES) {
                assertEquals(BenchmarkPrompt.prompt(f, c), BenchmarkPrompt.prefix(f) + BenchmarkPrompt.suffix(f, c))
            }
            // The split sits on a special-token boundary and the prefix has no per-request data.
            assertTrue(BenchmarkPrompt.prefix(f).endsWith("<|im_start|>user\n"))
            for (c in BenchmarkPrompt.CASES) assertFalse(BenchmarkPrompt.prefix(f).contains(c.utterance))
        }
        // Qwen3's no-think block belongs to the suffix; prefixes are identical across formats.
        assertEquals(BenchmarkPrompt.prefix(PromptFormat.CHATML), BenchmarkPrompt.prefix(PromptFormat.CHATML_QWEN3_NO_THINK))
    }

    @Test
    fun suffixCarriesDialogStateAndUtterance() {
        val s = BenchmarkPrompt.suffix(PromptFormat.CHATML, BenchmarkPrompt.CASES[0])
        assertEquals("最近：weather_query city=北京 day=today\n用户：那明天上海呢？<|im_end|>\n<|im_start|>assistant\n", s)
        // Short: the suffix is what every request pays for.
        for (c in BenchmarkPrompt.CASES) assertTrue(c.id, BenchmarkPrompt.suffix(PromptFormat.CHATML_QWEN3_NO_THINK, c).length < 110)
    }

    @Test
    fun grammarCoversEveryIntentAndRouteAndIsCompact() {
        val g = BenchmarkPrompt.GRAMMAR
        assertTrue(g.startsWith("root ::= "))
        for (i in BenchmarkPrompt.INTENTS) {
            assertTrue(i, g.contains("\"$i\""))
            assertTrue(i, BenchmarkPrompt.SYSTEM.contains(i))
        }
        assertTrue(g.contains("\"\\\"local\\\"\" | \"\\\"cloud\\\"\""))
        val defined = Regex("^([a-z]+) ::=", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("root", "intent", "slots", "kv", "key", "str", "chr", "num", "route", "conf"), defined)
        assertFalse("no optional whitespace → fewer generated tokens", g.contains("ws"))
        // slot keys are enumerated (no invented keys)
        for (k in BenchmarkPrompt.SLOT_KEYS) assertTrue(k, g.contains("\"$k\""))
    }

    @Test
    fun chitchatGrammarForcesIntentAndBoundsReply() {
        val g = BenchmarkPrompt.CHITCHAT_GRAMMAR
        assertTrue(g.contains("chitchat"))
        assertTrue(g.contains("rchr{2,${BenchmarkPrompt.REPLY_MAX_CHARS}}"))
        assertTrue("no digits / latin in replies", g.contains("0-9a-zA-Z"))
        assertEquals(setOf("root", "rchr", "route", "conf"), Regex("^([a-z]+) ::=", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun promptVersionAndSize() {
        assertEquals("s2-route-v3", BenchmarkPrompt.VERSION)
        assertEquals(48, BenchmarkPrompt.MAX_TOKENS)
        // ~600 Qwen tokens incl. 6 few-shot examples; guards accidental growth.
        assertTrue(BenchmarkPrompt.SYSTEM.length.toString(), BenchmarkPrompt.SYSTEM.length in 600..1400)
        // Few-shot answers must themselves satisfy the compact format the grammar forces.
        val shots = BenchmarkPrompt.SYSTEM.lines().filter { it.startsWith("{\"intent\"") }
        assertEquals(6, shots.size)
        shots.forEach { assertFalse(it, it.contains(" ")) }
    }

    @Test
    fun casesCoverLocalCloudAndEllipsis() {
        val c = BenchmarkPrompt.CASES
        assertEquals(4, c.size)
        assertEquals(c.map { it.id }.toSet().size, c.size)
        assertTrue(c.any { it.expectedRoute == "cloud" })
        assertTrue(c.any { it.previousTurn != "无" })
        c.forEach { assertTrue(it.expectedIntent in BenchmarkPrompt.INTENTS) }
    }

    @Test
    fun routeOutputCheck() {
        val case = BenchmarkPrompt.CASES[0]
        val good = """{"intent":"weather_query","slots":{"city":"上海","day":"tomorrow"},"route":"local","confidence":0.9}"""
        assertTrue(RouteOutputCheck.check(good, case).ok)
        // S0 output (with spaces) is also accepted.
        assertTrue(RouteOutputCheck.check("""{"intent": "weather_query", "slots": {"city": "上海", "day": "tomorrow"}, "route": "local", "confidence": 0.9}""", case).ok)
        assertEquals("slot city=北京 want 上海", RouteOutputCheck.check(good.replace("上海", "北京"), case).reason)
        assertFalse(RouteOutputCheck.check(good.replace("\"local\"", "\"cloud\""), case).ok)
        assertFalse(RouteOutputCheck.check(good.replace("weather_query", "chitchat"), case).ok)
        assertFalse(RouteOutputCheck.check("{\"intent\":\"weather_query\"", case).ok)
        assertEquals("missing slot day", RouteOutputCheck.check(good.replace(",\"day\":\"tomorrow\"", ""), case).reason)
        val vol = BenchmarkPrompt.CASES[1]
        assertTrue(RouteOutputCheck.check("""{"intent":"volume_set","slots":{"level":30},"route":"local","confidence":0.9}""", vol).ok)
        assertTrue(RouteOutputCheck.check("""{"intent":"volume_set","slots":{"level":"30"},"route":"local","confidence":0.9}""", vol).ok)
    }

    /** Writes the exact prompt pieces for the native host smoke test when VK_DUMP_PROMPTS is set. */
    @Test
    fun dumpPromptsForHostSmokeTest() {
        val dir = System.getenv("VK_DUMP_PROMPTS") ?: return
        val d = File(dir).apply { mkdirs() }
        for (f in PromptFormat.values()) {
            File(d, "prefix_${f.name}.txt").writeText(BenchmarkPrompt.prefix(f))
            BenchmarkPrompt.CASES.forEach { c -> File(d, "suffix_${f.name}_${c.id}.txt").writeText(BenchmarkPrompt.suffix(f, c)) }
        }
        File(d, "grammar.gbnf").writeText(BenchmarkPrompt.GRAMMAR)
    }
}
