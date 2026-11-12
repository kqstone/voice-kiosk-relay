package com.voicekiosk.app.conversation

import com.voicekiosk.app.ondevice.route.ChineseNumber

/**
 * Maps a short spoken answer onto an open question's options (L0, no model).
 *
 * Covers: 「第N个 / 第一个」, 「选&lt;option&gt;」 (fuzzy unique substring),
 * yes/no (是/对/好的 vs 不用/不要) and 批准/拒绝/取消. Negation of an ordinal
 * (「不要第二个」) does not pick that option.
 *
 * 「批准」 on a yes/no question maps to the yes option. Callers that have an
 * open *approval* must intercept 批准/拒绝 before posting an answer — voice
 * never submits an approval PIN.
 */
object QuestionAnswerMatcher {
    private val YES = setOf(
        "是", "是的", "对", "对的", "好", "好的", "好啊", "要", "可以", "行", "嗯",
        "yes", "y", "ok", "确认", "同意", "批准"
    )
    private val NO = setOf(
        "不用", "不要", "否", "不是", "不", "算了", "no", "n", "取消", "拒绝"
    )
    private val YES_OPTION = setOf(
        "是", "对", "好", "好的", "要", "可以", "行", "确认", "批准", "同意", "yes", "y", "ok"
    )
    private val NO_OPTION = setOf(
        "不用", "不要", "否", "不是", "不", "算了", "取消", "拒绝", "no", "n"
    )

    private val ORDINAL = Regex("^第\\s*([一二两三四五六七八九十\\d]+)\\s*[个項项]?$")
    private val SELECT_LETTER = Regex("^(?:选|选项)\\s*([A-Za-z])$")
    private val BARE_LETTER = Regex("^([A-Za-z])$")
    private val SELECT_TEXT = Regex("^(?:选|选项)\\s*(.+)$")
    private val NEGATE_ORDINAL = Regex("^(?:不要|不是|别选)\\s*第\\s*([一二两三四五六七八九十\\d]+)\\s*[个項项]?$")
    private val WS = Regex("[\\s，,。.！!？?～~、]+")

    fun match(text: String, question: OpenQuestion): QuestionMatch? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        val options = question.options.map { it.trim() }.filter { it.isNotEmpty() }

        if (NEGATE_ORDINAL.matches(t)) return null

        for (opt in options) {
            if (t == normalize(opt)) return QuestionMatch(option = opt)
        }

        ORDINAL.matchEntire(t)?.let { m ->
            val idx = indexFromOrdinal(m.groupValues[1]) ?: return@let
            if (idx in 1..options.size) return QuestionMatch(option = options[idx - 1])
        }

        (SELECT_LETTER.matchEntire(t) ?: BARE_LETTER.matchEntire(t))?.let { m ->
            if (options.isNotEmpty()) {
                val i = m.groupValues[1].uppercase()[0] - 'A'
                if (i in options.indices) return QuestionMatch(option = options[i])
            }
        }

        SELECT_TEXT.matchEntire(t)?.let { m ->
            fuzzyUnique(m.groupValues[1], options)?.let { return QuestionMatch(option = it) }
        }

        if (t.length >= 2) {
            fuzzyUnique(t, options)?.let { return QuestionMatch(option = it) }
        }

        if (isYesNo(question) && options.isNotEmpty()) {
            mapYesNo(t, options)?.let { return it }
        }
        return null
    }

    /** Voice 批准 / 拒绝 against an open approval (PIN pad only; never a decision). */
    fun matchApproval(text: String): QuestionMatch? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        if (t in setOf("批准", "同意", "批准吧")) return QuestionMatch(approveVoice = true)
        if (t in setOf("拒绝", "取消", "不要批准", "不批准")) return QuestionMatch(rejectVoice = true)
        return null
    }

    fun isYesNo(question: OpenQuestion): Boolean {
        val kind = question.kind.trim().lowercase()
        if (kind == "confirm") return true
        val options = question.options.map { it.trim() }.filter { it.isNotEmpty() }
        if (options.size < 2) return false
        var yesish = 0
        var noish = 0
        for (o in options) {
            val n = normalize(o)
            when {
                n in YES_OPTION -> yesish++
                n in NO_OPTION -> noish++
                else -> return false
            }
        }
        return yesish >= 1 && noish >= 1
    }

    private fun mapYesNo(token: String, options: List<String>): QuestionMatch? {
        if (token in YES) {
            for (o in options) if (normalize(o) in YES_OPTION) return QuestionMatch(option = o)
            return QuestionMatch(option = options[0])
        }
        if (token in NO) {
            for (o in options) if (normalize(o) in NO_OPTION) return QuestionMatch(option = o)
            return if (options.size >= 2) QuestionMatch(option = options[1]) else null
        }
        return null
    }

    /** Unique option whose normalized text contains [needle] (or vice versa), length ≥ 2. */
    fun fuzzyUnique(raw: String, options: List<String>): String? {
        val needle = normalize(raw)
        if (needle.length < 2) return null
        val hits = options.filter { opt ->
            val n = normalize(opt)
            n.contains(needle) || (n.length >= 2 && needle.contains(n))
        }
        return hits.singleOrNull()
    }

    fun indexFromOrdinal(token: String): Int? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if (t.all { it in '0'..'9' }) {
            val n = t.toIntOrNull() ?: return null
            return if (n >= 1) n else null
        }
        return ChineseNumber.parse(t)?.takeIf { it >= 1 }
    }

    fun normalize(s: String): String =
        s.trim().replace(WS, "").lowercase()
}
