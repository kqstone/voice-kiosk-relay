package com.voicekiosk.app

import android.os.Build
import android.util.Log

/**
 * Converts Chinese text into pinyin initial + final tokens formatted
 * for the Sherpa-ONNX WenetSpeech Zipformer KWS model.
 *
 * Example:
 *   "嘿缪斯" -> ["h", "ēi", "m", "iù", "s", "ī"]
 *   "小爱同学" -> ["x", "iǎo", "ài", "t", "óng", "x", "ué"]
 */
object PinyinConverter {
    private const val TAG = "PinyinConverter"

    // 2-character initials
    private val INITIALS_2 = listOf("zh", "ch", "sh")

    // 1-character initials
    private val INITIALS_1 = listOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "r", "z", "c", "s", "y", "w"
    )

    /**
     * Convert Chinese characters into a list of pinyin syllables with tone diacritics.
     */
    fun toPinyinSyllables(text: String): List<String> {
        val cleanText = text.trim().replace("\\s+".toRegex(), "")
        if (cleanText.isEmpty()) return emptyList()

        // 1. Primary: Use JPinyin offline library
        try {
            val pinyin = com.github.stuxuhai.jpinyin.PinyinHelper.convertToPinyinString(
                cleanText,
                " ",
                com.github.stuxuhai.jpinyin.PinyinFormat.WITH_TONE_MARK
            )
            if (!pinyin.isNullOrBlank()) {
                val list = pinyin.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (list.isNotEmpty()) {
                    return list
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "JPinyin conversion failed, attempting ICU fallback: ${e.message}")
        }

        // 2. Secondary: Fallback to Android framework ICU Transliterator (API 24+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val transliterator = android.icu.text.Transliterator.getInstance("Han-Latin")
                val pinyin = transliterator.transliterate(cleanText)
                if (!pinyin.isNullOrBlank()) {
                    val list = pinyin.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (list.isNotEmpty()) {
                        return list
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ICU Transliterator fallback failed: ${e.message}")
        }

        return emptyList()
    }

    /**
     * Split a single pinyin syllable into initial + final-with-tone tokens.
     * Zero-initial syllables (e.g. "ài", "ān", "ér") remain a single token.
     */
    fun splitSyllable(syllable: String): List<String> {
        val s = syllable.trim().lowercase()
        if (s.isEmpty()) return emptyList()

        for (init in INITIALS_2) {
            if (s.startsWith(init) && s.length > init.length) {
                return listOf(init, s.substring(init.length))
            }
        }
        for (init in INITIALS_1) {
            if (s.startsWith(init) && s.length > init.length) {
                return listOf(init, s.substring(init.length))
            }
        }
        // Zero-initial syllable (e.g. ài, ān, ér, ōu)
        return listOf(s)
    }

    /**
     * Convert Chinese text into a full token list compatible with Sherpa-ONNX model.
     */
    fun textToTokens(text: String): List<String> {
        val syllables = toPinyinSyllables(text)
        if (syllables.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        for (syllable in syllables) {
            tokens.addAll(splitSyllable(syllable))
        }
        return tokens
    }
}
