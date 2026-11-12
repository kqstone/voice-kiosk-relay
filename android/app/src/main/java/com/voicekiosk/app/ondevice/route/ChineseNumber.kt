package com.voicekiosk.app.ondevice.route

/** Parses the small numbers ASR produces: "30", "三十", "三十五", "一百", "两", "一百零五". */
object ChineseNumber {
    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** Regex fragment matching a number token [parse] accepts. */
    const val PATTERN = "(?:[0-9]{1,4}|[零〇一二两三四五六七八九十百]{1,6})"

    fun parse(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.all { it in '0'..'9' }) return s.toIntOrNull()
        var total = 0
        var cur = -1
        var last = ' '
        for (c in s) {
            when {
                c in DIGITS -> {
                    // two plain digits in a row ("二五") are ambiguous: reject
                    if (cur >= 0 && last in DIGITS && DIGITS[last] != 0) return null
                    cur = DIGITS.getValue(c)
                }
                c == '十' -> { if (last == '十' || last == '百' && cur > 0) return null; total += (if (cur <= 0) 1 else cur) * 10; cur = -1 }
                c == '百' -> { if (cur == 0 || last == '十' || last == '百') return null; total += (if (cur < 0) 1 else cur) * 100; cur = -1 }
                else -> return null
            }
            last = c
        }
        return total + maxOf(cur, 0)
    }
}
