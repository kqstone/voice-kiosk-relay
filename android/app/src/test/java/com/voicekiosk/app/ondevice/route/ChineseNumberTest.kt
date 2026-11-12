package com.voicekiosk.app.ondevice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseNumberTest {
    @Test
    fun parses() {
        val cases = mapOf(
            "30" to 30, "0" to 0, "三十" to 30, "三十五" to 35, "十" to 10, "十五" to 15, "一百" to 100,
            "两" to 2, "二十" to 20, "一百零五" to 105, "一百二十" to 120, "零" to 0, "九十九" to 99
        )
        for ((s, n) in cases) assertEquals(s, n, ChineseNumber.parse(s))
    }

    @Test
    fun rejects() {
        for (s in listOf("", "三五", "abc", "十十", "百百", "二五")) assertNull(s, ChineseNumber.parse(s))
    }
}
