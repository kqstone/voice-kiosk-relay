package com.voicekiosk.app.ondevice.route

import kotlin.random.Random

/**
 * S3.1: fixed, natural replies for the safe small talk [RuleRouter] recognises (whole
 * utterance only). ~0 ms, no model. Several variants per phrase, never the same reply
 * twice in a row, and time-of-day aware ("早安" at 3 pm gets a gentle "下午好").
 */
class SmallTalkReplies(
    private val hour: () -> Int = { java.time.LocalTime.now().hour },
    private val random: Random = Random.Default
) {
    private var last: String? = null

    /** [kind] / [phrase] come from the rule decision's slots. Null for unknown kinds. */
    @Synchronized
    fun reply(kind: String, phrase: String): String? {
        val options = options(kind, phrase, hour()).takeIf { it.isNotEmpty() } ?: return null
        val pool = options.filter { it != last }.ifEmpty { options }
        return pool[random.nextInt(pool.size)].also { last = it }
    }

    companion object {
        private fun part(h: Int) = when (h) {
            in 5..10 -> "morning"
            in 11..13 -> "noon"
            in 14..17 -> "afternoon"
            in 18..21 -> "evening"
            else -> "night"
        }

        private val GENERIC = listOf("你好呀！有什么需要随时叫我。", "你好！我在呢。", "嗨，我在，请说。")
        private fun nowGreeting(h: Int): List<String> = when (part(h)) {
            "morning" -> listOf("早上好！今天也要顺顺利利哦。", "早安！有什么需要随时叫我。", "早上好呀，新的一天开始啦。")
            "noon" -> listOf("中午好！记得按时吃午饭哦。", "中午好呀，有什么需要随时叫我。")
            "afternoon" -> listOf("下午好！累了就歇一会儿吧。", "下午好呀，有什么需要随时叫我。")
            "evening" -> listOf("晚上好！今天辛苦啦。", "晚上好呀，有什么需要随时叫我。")
            else -> listOf("这么晚还没睡呀，早点休息哦。", "夜深啦，有什么需要叫我。")
        }

        /** Greeting for a time word; if it does not fit the clock, answer for the actual time. */
        private fun timed(word: String, h: Int): List<String> {
            val now = part(h)
            val fits = when (word) {
                "morning" -> now == "morning"
                "noon" -> now == "noon"
                "afternoon" -> now == "afternoon" || now == "noon"
                "evening" -> now == "evening" || now == "night"
                else -> true
            }
            if (fits) return nowGreeting(h)
            val actual = when (now) {
                "morning" -> "现在还是早上呢，早上好！"
                "noon" -> "已经中午啦，中午好！"
                "afternoon" -> "现在已经是下午啦，下午好！"
                "evening" -> "现在已经是晚上啦，晚上好！"
                else -> "已经很晚啦，早点休息哦。"
            }
            return listOf(actual)
        }

        fun options(kind: String, phrase: String, h: Int): List<String> = when (kind) {
            "greeting" -> when (phrase) {
                "早", "早安", "早上好", "上午好" -> timed("morning", h)
                "中午好" -> timed("noon", h)
                "下午好" -> timed("afternoon", h)
                "晚上好" -> timed("evening", h)
                "在", "在不在", "你在吗", "你在" -> listOf("我在呢，请说。", "在的，有什么需要？")
                else -> GENERIC
            }
            "thanks" -> when (phrase) {
                "辛苦了", "辛苦你了" -> listOf("不辛苦，随时为你服务。", "不辛苦，能帮上忙我很开心。")
                "麻烦你了" -> listOf("不麻烦，随时叫我。", "一点都不麻烦。")
                else -> listOf("不客气！", "不用谢，随时叫我。", "不客气，能帮上忙就好。")
            }
            "how_are_you" -> listOf("我挺好的，谢谢关心！", "我很好，随时准备帮你。", "挺好的，谢谢你惦记。")
            "bye" -> when (phrase) {
                "晚安" -> if (part(h) == "night" || h >= 20) listOf("晚安，好梦！", "晚安，早点休息哦。") else listOf("好的，休息一下吧，晚安。")
                "明天见" -> listOf("明天见！", "好的，明天见。")
                "没事了", "好了没事了", "先这样" -> listOf("好的，有需要再叫我。", "好嘞，随时叫我。")
                else -> listOf("再见！", "拜拜，随时叫我。", "好的，回头见！")
            }
            "praise" -> listOf("谢谢夸奖，我会继续努力的！", "过奖啦，能帮上忙就好。", "嘿嘿，谢谢你！")
            else -> emptyList()
        }
    }
}
