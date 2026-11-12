package com.voicekiosk.app.memory.eval

import com.voicekiosk.app.memory.MemoryFact
import com.voicekiosk.app.memory.SensitiveFilter

/** Scores extraction output on [MemoryEvalSet] (unit tests: rules only; host harness: rules + model). */
object MemoryEval {
    data class Row(val case: MemoryEvalSet.Case, val facts: List<MemoryFact>, val modelStatus: String, val modelMs: Long) {
        val wrong get() = facts.filter { it.content !in case.expected }
        val missed get() = case.expected - facts.map { it.content }.toSet()
        val leaked get() = facts.filter { f -> SensitiveFilter.isSensitive(f.content) || case.forbidden.any { f.content.contains(it) } }
    }

    data class Report(val rows: List<Row>) {
        private val all get() = rows.flatMap { r -> r.facts.map { r to it } }
        val autoFacts get() = all.filter { it.second.category.autoWrite }
        val autoCorrect get() = autoFacts.count { (r, f) -> f.content in r.case.expected }
        val autoPrecision get() = if (autoFacts.isEmpty()) 1.0 else autoCorrect.toDouble() / autoFacts.size
        val confirmFacts get() = all.filter { !it.second.category.autoWrite }
        val confirmCorrect get() = confirmFacts.count { (r, f) -> f.content in r.case.expected }
        val expectedTotal get() = rows.sumOf { it.case.expected.size }
        val found get() = rows.sumOf { it.case.expected.size - it.missed.size }
        val recall get() = if (expectedTotal == 0) 1.0 else found.toDouble() / expectedTotal
        val leaks get() = rows.flatMap { it.leaked }
        val falsePositivesOnNegatives get() = rows.filter { it.case.expected.isEmpty() && !it.case.sensitive }.sumOf { it.facts.size }

        fun summary(label: String): String = buildString {
            appendLine("memory eval [$label]: ${rows.size} cases (${rows.count { it.case.sensitive }} sensitive, ${rows.count { it.case.expected.isEmpty() && !it.case.sensitive }} negative)")
            appendLine("  auto-write precision: $autoCorrect/${autoFacts.size} = ${"%.3f".format(autoPrecision)}")
            appendLine("  confirm-candidates correct: $confirmCorrect/${confirmFacts.size}")
            appendLine("  recall: $found/$expectedTotal = ${"%.3f".format(recall)}")
            appendLine("  false positives on negatives: $falsePositivesOnNegatives")
            appendLine("  sensitive leakage: ${leaks.size}")
            val ms = rows.filter { it.modelMs > 0 }.map { it.modelMs }.sorted()
            if (ms.isNotEmpty()) appendLine("  model runs: ${ms.size}, p50 ${ms[ms.size / 2]}ms, max ${ms.last()}ms")
            rows.filter { it.wrong.isNotEmpty() || it.missed.isNotEmpty() || it.leaked.isNotEmpty() }.forEach { r ->
                appendLine("  ${r.case.id} ${r.case.utterances} wrong=${r.wrong.map { it.content }} missed=${r.missed} leaked=${r.leaked.map { it.content }} model=${r.modelStatus}")
            }
        }
    }
}
