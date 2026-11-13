package com.voicekiosk.app.ondevice.tools

import com.voicekiosk.app.ondevice.route.Intents
import com.voicekiosk.app.ondevice.route.RouteDecision
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Media actions the executor can send to the active third-party player. */
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/** Outcome of asking a third-party player to search and play. */
enum class PlaySearchResult { STARTED, NO_PLAYER, FAILED, BACKGROUND_NOT_SUPPORTED }

/**
 * Device side effects the whitelisted tools need. The Android implementation is
 * [AndroidLocalPlatform]; tests use a fake. Implementations must not throw for normal
 * failures; anything thrown makes the turn fall back to the cloud.
 */
interface LocalPlatform {
    fun nowMillis(): Long
    fun zone(): ZoneId

    /** Media (STREAM_MUSIC) volume as (current index, max index). */
    fun volume(): Pair<Int, Int>
    fun setVolumeIndex(index: Int)
    fun setMuted(muted: Boolean)
    fun isMuted(): Boolean

    fun sendMediaAction(action: MediaAction): Boolean
    fun playFromSearch(query: String, artist: String?, song: String?): Pair<PlaySearchResult, String?>

    /** Starts an in-app timer; returns false if it could not be scheduled. */
    fun startTimer(seconds: Int, label: String?): Boolean

    /** Cancels all running timers; returns how many were running. */
    fun cancelTimers(): Int
}

/**
 * @property handled the tool did what was asked (or answered honestly that it can't)
 *   and [reply] should be spoken.
 * @property fallbackToCloud the turn should go to the bridge instead (tool unavailable
 *   or failed in a way the cloud may handle).
 */
data class ToolResult(val handled: Boolean, val reply: String, val fallbackToCloud: Boolean = !handled) {
    companion object {
        fun ok(reply: String) = ToolResult(true, reply, false)
        fun fallback(reason: String) = ToolResult(false, reason, true)
    }
}

/**
 * Whitelisted local tools for S1: time / date, volume, media keys, play-from-search
 * (third-party players only), in-app timers. Only enumerated intents with validated
 * slots are executed; anything else is rejected by [validate] (the model can never
 * trigger an action outside this list).
 */
class LocalToolExecutor(
    private val platform: LocalPlatform,
    /** Null = no local weather (the intent then goes to the cloud). */
    private val weather: WeatherSource? = null,
    /** S5: favourite artist from the long-term memory cache ("放我喜欢的歌"). */
    private val favoriteArtist: () -> String? = { null },
    /** The settings' weather city, used when the utterance names none (kept last: trailing lambda). */
    private val defaultCity: () -> City? = { null }
) {

    /** Null if the decision can be executed locally, else why not. */
    fun validate(d: RouteDecision): String? = when (d.intent) {
        Intents.TIME -> {
            val kind = d.slots["kind"] ?: "time"
            val off = d.slots["day_offset"]?.toIntOrNull() ?: 0
            if (kind !in setOf("time", "date", "weekday")) "bad kind" else if (off !in -2..2) "bad day_offset" else null
        }
        Intents.VOLUME -> {
            val level = d.slots["level"]
            val dir = d.slots["direction"]
            when {
                level != null -> if (level.trim().toIntOrNull() in 0..100) null else "bad level"
                dir != null -> if (dir in DIRECTIONS) null else "bad direction"
                else -> "no level/direction"
            }
        }
        Intents.MEDIA_CONTROL -> if ((d.slots["action"] ?: "") in MEDIA_ACTIONS.keys) null else "bad action"
        Intents.MEDIA_PLAY -> {
            val names = listOfNotNull(d.slots["song"], d.slots["artist"], d.slots["query"]).map { it.trim() }.filter { it.isNotEmpty() }
            when {
                d.slots["favorite"] == "1" && d.source == com.voicekiosk.app.ondevice.route.RouteSource.RULE -> null
                names.isEmpty() -> "no song/artist"
                names.any { it.length > MAX_NAME } -> "name too long"
                else -> null
            }
        }
        Intents.WEATHER -> {
            val city = d.slots["city"]?.trim().orEmpty()
            when {
                weather == null -> "weather unavailable"
                normalizeDay(d.slots["day"]) == null -> "bad day"
                city.length > 10 -> "bad city"
                (d.slots["kind"] ?: "general") !in setOf("general", "rain", "temp") -> "bad kind"
                else -> null
            }
        }
        Intents.TIMER -> when {
            d.slots["action"] == "cancel" -> null
            timerSeconds(d) == null -> "bad duration"
            else -> null
        }
        else -> "not a local tool intent"
    }

    fun canHandle(d: RouteDecision): Boolean = validate(d) == null

    fun execute(d: RouteDecision): ToolResult {
        validate(d)?.let { return ToolResult.fallback(it) }
        return when (d.intent) {
            Intents.TIME -> time(d)
            Intents.VOLUME -> volume(d)
            Intents.MEDIA_CONTROL -> media(MEDIA_ACTIONS.getValue(d.slots.getValue("action")))
            Intents.MEDIA_PLAY -> play(d)
            Intents.TIMER -> timer(d)
            Intents.WEATHER -> weather(d)
            else -> ToolResult.fallback("not a local tool intent")
        }
    }

    private fun time(d: RouteDecision): ToolResult {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(platform.nowMillis()), platform.zone())
        val kind = d.slots["kind"] ?: "time"
        val off = d.slots["day_offset"]?.toIntOrNull() ?: 0
        if (kind == "time") return ToolResult.ok("现在是${spokenTime(now.hour, now.minute)}。")
        val day = now.plusDays(off.toLong())
        val dayWord = DAY_WORDS.getValue(off)
        val week = WEEKDAYS[day.dayOfWeek.value - 1]
        return if (kind == "weekday") ToolResult.ok("${dayWord}是$week。")
        else ToolResult.ok("${dayWord}是${day.year}年${day.monthValue}月${day.dayOfMonth}日，$week。")
    }

    private fun volume(d: RouteDecision): ToolResult {
        val (cur, max) = platform.volume()
        if (max <= 0) return ToolResult.fallback("no volume stream")
        fun pct(i: Int) = (i * 100.0 / max).roundToInt()
        d.slots["level"]?.trim()?.toIntOrNull()?.let { level ->
            val idx = (level / 100.0 * max).roundToInt().coerceIn(0, max)
            if (platform.isMuted() && idx > 0) platform.setMuted(false)
            platform.setVolumeIndex(idx)
            return ToolResult.ok(if (idx == 0) "已把音量调到最低。" else "音量已调到${pct(idx)}%。")
        }
        return when (d.slots.getValue("direction")) {
            "mute" -> { platform.setMuted(true); ToolResult.ok("已静音。") }
            "unmute" -> { platform.setMuted(false); ToolResult.ok("已取消静音。") }
            "up" -> {
                if (platform.isMuted()) platform.setMuted(false)
                if (cur >= max) return ToolResult.ok("已经是最大音量了。")
                val idx = (cur + step(max)).coerceAtMost(max)
                platform.setVolumeIndex(idx)
                ToolResult.ok("音量已调大到${pct(idx)}%。")
            }
            else -> {
                if (cur <= 0) return ToolResult.ok("已经是最小音量了。")
                // never go to 0 by "smaller": the spoken confirmation must stay audible
                val idx = (cur - step(max)).coerceAtLeast(1)
                platform.setVolumeIndex(idx)
                ToolResult.ok("音量已调小到${pct(idx)}%。")
            }
        }
    }

    /** About 10% of the range, at least one step. */
    private fun step(max: Int) = maxOf(1, (max / 10.0).roundToInt())

    private fun media(action: MediaAction): ToolResult {
        if (!platform.sendMediaAction(action)) return ToolResult.fallback("media key not delivered")
        return ToolResult.ok(
            when (action) {
                MediaAction.PLAY -> "好的，继续播放。"
                MediaAction.PAUSE -> "已暂停。"
                MediaAction.NEXT -> "好的，下一首。"
                MediaAction.PREVIOUS -> "好的，上一首。"
            }
        )
    }

    private fun play(d0: RouteDecision): ToolResult {
        var d = d0
        if (d.slots["favorite"] == "1") {
            val fav = favoriteArtist()?.takeIf { it.isNotBlank() }
                ?: return ToolResult.ok("我还不知道你喜欢听谁的歌，可以直接说「播放某某的歌」。")
            d = d.copy(slots = mapOf("artist" to fav))
        }
        val song = d.slots["song"]?.trim()?.takeIf { it.isNotEmpty() }
        val artist = d.slots["artist"]?.trim()?.takeIf { it.isNotEmpty() }
        val query = listOfNotNull(artist, song).joinToString(" ").ifEmpty { d.slots["query"]?.trim().orEmpty() }
        val (result, player) = platform.playFromSearch(query, artist, song)
        val what = when {
            artist != null && song != null -> "${artist}的「$song」"
            song != null -> "「$song」"
            artist != null -> "${artist}的歌"
            else -> "「$query」"
        }
        // Honest replies: we can only ask the player to search; we cannot see whether it played.
        return when (result) {
            PlaySearchResult.STARTED -> ToolResult.ok("已让${player ?: "播放器"}搜索播放$what。")
            PlaySearchResult.NO_PLAYER -> ToolResult.ok("本机没有找到支持语音点歌的播放器，可以先打开播放器，再说「继续播放」。")
            PlaySearchResult.FAILED -> ToolResult.ok("没能打开播放器，请先手动打开播放器再试。")
            PlaySearchResult.BACKGROUND_NOT_SUPPORTED -> {
                val target = if (!player.isNullOrBlank()) "【$player】" else "当前播放器"
                ToolResult.ok("${target}不支持后台静默点歌，请在设置中切换为网易云音乐或系统音乐。")
            }
        }
    }

    private fun weather(d: RouteDecision): ToolResult {
        val src = weather ?: return ToolResult.fallback("weather unavailable")
        val day = normalizeDay(d.slots["day"])!!
        val name = d.slots["city"]?.trim()?.takeIf { it.isNotEmpty() }
        val city = if (name == null) defaultCity() else CityTable.find(name) ?: src.geocode(name).getOrNull()
        if (city == null) return ToolResult.fallback("unknown city ${name ?: "(default)"}")
        val f = src.forecast(city).getOrElse { return ToolResult.fallback("forecast failed: ${it.message}") }
        val reply = WeatherSpeech.reply(f, day, d.slots["kind"] ?: "general") ?: return ToolResult.fallback("no forecast for $day")
        return ToolResult.ok(reply)
    }

    private fun normalizeDay(raw: String?): String? = when (raw?.trim()) {
        null, "", "today", "今天", "now" -> "today"
        "tomorrow", "明天" -> "tomorrow"
        "after_tomorrow", "后天" -> "after_tomorrow"
        else -> null
    }

    private fun timer(d: RouteDecision): ToolResult {
        if (d.slots["action"] == "cancel") {
            val n = platform.cancelTimers()
            return ToolResult.ok(if (n == 0) "现在没有正在进行的计时。" else "已取消计时。")
        }
        val secs = timerSeconds(d)!!
        val label = d.slots["label"]?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL)
        if (!platform.startTimer(secs, label)) return ToolResult.fallback("timer not scheduled")
        val dur = spokenDuration(secs)
        return ToolResult.ok(if (label != null) "好的，${dur}后提醒你$label。" else "好的，开始计时$dur。")
    }

    private fun timerSeconds(d: RouteDecision): Int? {
        val s = d.slots["seconds"]?.trim()?.toIntOrNull()
            ?: d.slots["minutes"]?.trim()?.toDoubleOrNull()?.let { (it * 60).roundToInt() }
            ?: return null
        return s.takeIf { it in 1..MAX_TIMER_SECONDS }
    }

    companion object {
        const val MAX_NAME = 24
        const val MAX_LABEL = 12
        const val MAX_TIMER_SECONDS = 3 * 3600
        private val DIRECTIONS = setOf("up", "down", "mute", "unmute")
        private val MEDIA_ACTIONS = mapOf(
            "pause" to MediaAction.PAUSE, "stop" to MediaAction.PAUSE, "resume" to MediaAction.PLAY,
            "play" to MediaAction.PLAY, "next" to MediaAction.NEXT, "prev" to MediaAction.PREVIOUS
        )
        private val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        private val DAY_WORDS = mapOf(-2 to "前天", -1 to "昨天", 0 to "今天", 1 to "明天", 2 to "后天")

        /** "上午9点", "下午3点05分" → spoken as "下午3点5分"; "中午12点整". */
        fun spokenTime(hour: Int, minute: Int): String {
            val period = when (hour) {
                in 0..4 -> "凌晨"; in 5..8 -> "早上"; in 9..11 -> "上午"; 12 -> "中午"; in 13..17 -> "下午"; else -> "晚上"
            }
            val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
            val m = if (minute == 0) "整" else "${minute}分"
            return "$period${h12}点$m"
        }

        fun spokenDuration(secs: Int): String {
            val h = secs / 3600
            val m = (secs % 3600) / 60
            val s = secs % 60
            if (h > 0 && m == 30 && s == 0) return "${h}个半小时"
            if (h == 0 && m == 30 && s == 0) return "半小时"
            return buildString {
                if (h > 0) append("${h}小时")
                if (m > 0) append("${m}分钟")
                if (s > 0) append("${s}秒")
            }
        }
    }
}
