package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.conversation.ConversationContext
import com.voicekiosk.app.conversation.TurnSource
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import com.voicekiosk.app.ondevice.tools.MediaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnOrchestratorTest {
    private val platform = FakePlatform()
    private val cloudCalls = mutableListOf<String>()
    private val cloudRequests = mutableListOf<com.voicekiosk.app.conversation.CloudRequest>()
    private var ackedIds: List<String>? = null
    private var cloudResult: (String) -> Result<String> = { Result.success("云端回答：$it") }
    private val ctx = ConversationContext()

    private val weather = FakeWeather()

    private class FakeClassifier(
        var ready: Boolean = true,
        var reply: (String) -> ClassifyResult = { ClassifyResult(null, "no reply") },
        var answer: (String, String) -> ClassifyResult
    ) : IntentClassifier {
        val calls = mutableListOf<Pair<String, String>>()
        val chitchatCalls = mutableListOf<String>()
        override val isReady get() = ready
        override fun classify(text: String, recent: String, timeoutMs: Long, chitchat: Boolean): ClassifyResult {
            if (chitchat) { chitchatCalls += text; return reply(text) }
            calls += text to recent
            return answer(text, recent)
        }
    }

    private fun chat(reply: String, route: Route = Route.LOCAL, conf: Double = 0.95) =
        ClassifyResult(RouteDecision(Intents.CHITCHAT, emptyMap(), route, conf, RouteSource.MODEL), "ok", ms = 1000, reply = reply)

    private fun model(intent: String, route: Route, conf: Double, vararg slots: Pair<String, String>) =
        ClassifyResult(RouteDecision(intent, mapOf(*slots), route, conf, RouteSource.MODEL), "ok", ms = 900)

    private fun orch(
        classifier: IntentClassifier? = null, modelEnabled: Boolean = true, chitchat: Boolean = true,
        chitchatModel: Boolean = false, speculative: Boolean = false
    ) = TurnOrchestrator(
        TurnOrchestrator.Config(modelRoutingEnabled = modelEnabled, chitchatEnabled = chitchat, chitchatModel = chitchatModel, speculativeCloud = speculative),
        RuleRouter(), classifier, LocalToolExecutor(platform, weather) { com.voicekiosk.app.ondevice.tools.City("上海", 31.2, 121.5) }, ctx,
        cloud = { req -> cloudRequests += req; cloudCalls += req.text; cloudResult(req.text).map { com.voicekiosk.app.conversation.CloudReply(it, ackedIds) } }
    )

    @Test
    fun ruleHitRunsLocalToolWithoutModelOrCloud() {
        val clf = FakeClassifier { _, _ -> error("model must not run") }
        val r = orch(clf).handle("把音量调到三十")
        assertEquals(TurnSource.LOCAL_RULE, r.source)
        assertEquals("音量已调到33%。", r.reply)
        assertEquals(emptyList<String>(), cloudCalls)
        assertEquals(0, clf.calls.size)
        assertEquals("skipped", r.trace.modelStatus)
        assertEquals(1, ctx.unsyncedLocalTurns().size)
    }

    @Test
    fun ruleCloudShortcutSkipsModel() {
        val clf = FakeClassifier { _, _ -> error("model must not run") }
        val r = orch(clf).handle("天气预报为什么不准")
        assertEquals(TurnSource.CLOUD, r.source)
        assertEquals(listOf("天气预报为什么不准"), cloudCalls)
        assertEquals(0, clf.calls.size)
    }

    @Test
    fun modelLocalAboveThresholdRunsTool() {
        val clf = FakeClassifier { _, _ -> model(Intents.MEDIA_PLAY, Route.LOCAL, 0.95, "artist" to "周杰伦", "song" to "七里香") }
        val r = orch(clf).handle("周董那首七里香给我整一个")
        assertEquals(TurnSource.LOCAL_MODEL, r.source)
        assertEquals(Triple("周杰伦 七里香", "周杰伦", "七里香"), platform.lastSearch)
        assertEquals(emptyList<String>(), cloudCalls)
        assertEquals("ok", r.trace.modelStatus)
    }

    @Test
    fun modelBelowThresholdOrCloudRouteGoesToCloud() {
        var answer = model(Intents.VOLUME, Route.LOCAL, 0.8, "direction" to "up")
        val clf = FakeClassifier { _, _ -> answer }
        val o = orch(clf)
        assertEquals(TurnSource.CLOUD, o.handle("那个声音能不能稍微往上提提").source)
        answer = model(Intents.CLOUD_QUESTION, Route.CLOUD, 0.9)
        assertEquals(TurnSource.CLOUD, o.handle("今天英伟达股价多少").source)
        // weather ellipsis without a weather turn before it: no evidence → cloud
        answer = model(Intents.WEATHER, Route.LOCAL, 0.95, "city" to "上海")
        assertEquals(TurnSource.CLOUD, o.handle("那上海那边呢").source)
        // time is rule-only (the model cannot tell "纽约几点" from "现在几点")
        answer = model(Intents.TIME, Route.LOCAL, 0.95)
        assertEquals(TurnSource.CLOUD, o.handle("纽约现在几点").source)
        assertEquals(4, cloudCalls.size)
        assertTrue(platform.mediaActions.isEmpty())
    }

    @Test
    fun modelInvalidSlotsOrQuestionFormGoToCloud() {
        var answer = model(Intents.VOLUME, Route.LOCAL, 0.95, "level" to "300")
        val clf = FakeClassifier { _, _ -> answer }
        val o = orch(clf)
        assertEquals(TurnSource.CLOUD, o.handle("音量开到三百吧").source)
        answer = model(Intents.VOLUME, Route.LOCAL, 0.95, "direction" to "up")
        assertEquals(TurnSource.CLOUD, o.handle("音量开大了会不会伤耳朵").source)
        assertEquals(7, platform.volIndex)
    }

    @Test
    fun modelFailuresFallBackToCloud() {
        for (status in listOf("timeout", "bad_json", "error: x")) {
            cloudCalls.clear()
            val clf = FakeClassifier { _, _ -> ClassifyResult(null, status) }
            val r = orch(clf).handle("把那个声音往上提提")
            assertEquals(TurnSource.CLOUD, r.source)
            assertEquals(listOf("把那个声音往上提提"), cloudCalls)
            assertEquals(status, r.trace.modelStatus)
        }
        val throwing = FakeClassifier { _, _ -> throw IllegalStateException("native crash") }
        assertEquals(TurnSource.CLOUD, orch(throwing).handle("把那个声音往上提提").source)
    }

    @Test
    fun modelNotReadyOrDisabledGoesStraightToCloud() {
        val clf = FakeClassifier(ready = false) { _, _ -> error("not ready") }
        assertEquals("not_ready", orch(clf).handle("讲个故事").trace.modelStatus)
        val clf2 = FakeClassifier { _, _ -> error("disabled") }
        assertEquals("off", orch(clf2, modelEnabled = false).handle("讲个故事").trace.modelStatus)
        assertEquals("off", orch(null).handle("讲个故事").trace.modelStatus)
        assertEquals(3, cloudCalls.size)
    }

    @Test
    fun cloudErrorIsReturnedNotSpoken() {
        cloudResult = { Result.failure(IllegalStateException("网络不可用，无法连接语音桥接服务")) }
        val r = orch().handle("今天有什么新闻")
        assertNull(r.reply)
        assertEquals("网络不可用，无法连接语音桥接服务", r.error)
        // local commands still work offline
        assertEquals(TurnSource.LOCAL_RULE, orch().handle("暂停").source)
        assertEquals(listOf(MediaAction.PAUSE), platform.mediaActions)
    }

    @Test
    fun toolFailureFallsBackToCloud() {
        platform.mediaOk = false
        val r = orch().handle("下一首")
        assertEquals(TurnSource.CLOUD, r.source)
        assertEquals(listOf("下一首"), cloudCalls)
        platform.throwOnVolume = true
        assertEquals(TurnSource.CLOUD, orch().handle("声音大一点").source)
    }

    @Test
    fun resetClearsContextAndForwardsBridgeCommand() {
        val o = orch()
        o.handle("声音大一点")
        assertEquals(1, ctx.turns().size)
        val r = o.handle("我们重新开始吧")
        assertEquals(0, ctx.turns().size)
        assertEquals(listOf(TurnOrchestrator.BRIDGE_RESET_TEXT), cloudCalls)
        assertEquals("云端回答：重新开始", r.reply)
        cloudResult = { Result.failure(IllegalStateException("offline")) }
        val offline = o.handle("换个话题")
        assertEquals(TurnOrchestrator.LOCAL_RESET_REPLY, offline.reply)
        assertEquals(TurnSource.LOCAL_RULE, offline.source)
    }

    @Test
    fun previousTurnFeedsEllipsis() {
        val clf = FakeClassifier { _, _ -> ClassifyResult(null, "bad_json") }
        val o = orch(clf)
        o.handle("声音大一点")
        // bare "再大点" is a rule hit only because the previous turn was volume
        assertEquals(TurnSource.LOCAL_RULE, o.handle("再大点").source)
        o.handle("暂停")
        assertEquals(TurnSource.LOCAL_RULE, o.handle("继续").source)
        o.handle("把那个声音往上提提")
        // the model sees the last two turns' state, oldest first
        assertEquals("media_control action=pause；media_control action=resume", clf.calls.last().second)
        assertEquals(TurnSource.CLOUD, o.handle("继续").source)
        assertEquals("media_control action=resume；cloud_question", clf.calls.last().second)
    }

    @Test
    fun guardsRejectConfidentModelMistakes() {
        // all seen in the S2 route eval with confidence 0.95
        var answer = model(Intents.VOLUME, Route.LOCAL, 0.95, "direction" to "up")
        val clf = FakeClassifier { _, _ -> answer }
        val o = orch(clf)
        assertEquals(TurnSource.CLOUD, o.handle("你能帮我调亮屏幕吗").source)
        answer = model(Intents.TIMER, Route.LOCAL, 0.95, "minutes" to "7")
        assertEquals(TurnSource.CLOUD, o.handle("设个明天早上七点的闹钟").source)
        answer = model(Intents.WEATHER, Route.LOCAL, 0.95, "day" to "after_tomorrow")
        assertEquals(TurnSource.CLOUD, o.handle("下周天气怎么样").source)
        assertEquals(TurnSource.CLOUD, o.handle("明天适合去爬山吗").source)
        answer = model(Intents.MEDIA_PLAY, Route.LOCAL, 0.95, "song" to "夜曲")
        assertEquals(TurnSource.CLOUD, o.handle("我想放松一下").source)
        assertTrue(platform.timers.isEmpty())
        assertTrue(weather.asked.isEmpty())
        assertEquals(null, platform.lastSearch)
    }

    @Test
    fun weatherRuleAnswersLocallyWithDefaultCityAndEllipsis() {
        val o = orch()
        val r = o.handle("今天天气怎么样")
        assertEquals(TurnSource.LOCAL_RULE, r.source)
        assertEquals("上海今天多云，现在12度，9到15度。", r.reply)
        val r2 = o.handle("那明天北京呢")
        assertEquals("北京明天有小雨，6到11度。降水概率80%，很可能下雨，出门记得带伞。", r2.reply)
        val r3 = o.handle("后天呢")
        assertEquals("北京后天晴，零下1到零下6度。", r3.reply)
        assertEquals(listOf("上海", "北京", "北京"), weather.asked)
        assertTrue(cloudCalls.isEmpty())
    }

    @Test
    fun modelWeatherUsesCityFromTextOnly() {
        val clf = FakeClassifier { _, _ -> model(Intents.WEATHER, Route.LOCAL, 0.95, "city" to "北京", "day" to "after_tomorrow") }
        // the model invented 北京 and the day; both are re-derived from the text
        val r = orch(clf).handle("外头明儿个凉不凉快")
        assertEquals(TurnSource.LOCAL_MODEL, r.source)
        assertEquals(listOf("上海"), weather.asked)
        assertTrue(r.reply!!.startsWith("上海明天"))
    }

    @Test
    fun weatherFailureFallsBackToCloud() {
        weather.fail = true
        val r = orch().handle("明天会下雨吗")
        assertEquals(TurnSource.CLOUD, r.source)
        assertEquals(listOf("明天会下雨吗"), cloudCalls)
        // the cloud turn keeps the weather state so "那后天呢" is still a weather ellipsis
        weather.fail = false
        assertEquals(TurnSource.LOCAL_RULE, orch().handle("那后天呢").source)
    }

    @Test
    fun smallTalkUsesModelReplyOnlyWhenAllowed() {
        val clf = FakeClassifier(reply = { chat("你好呀，有什么可以帮你？") }) { _, _ -> error("intent model not needed") }
        val r = orch(clf, chitchatModel = true).handle("你好")
        assertEquals(TurnSource.LOCAL_MODEL, r.source)
        assertEquals("你好呀，有什么可以帮你？", r.reply)
        assertEquals(Intents.CHITCHAT, ctx.lastDecision()?.intent)
        assertTrue(cloudCalls.isEmpty())

        // setting off / no model / not ready → cloud, model untouched
        assertEquals(TurnSource.CLOUD, orch(clf, chitchat = false, chitchatModel = true).handle("谢谢").source)
        assertEquals(TurnSource.CLOUD, orch(null, chitchatModel = true).handle("谢谢").source)
        assertEquals(1, clf.chitchatCalls.size)

        // unusable replies → cloud
        for (bad in listOf(chat("好"), chat("作为AI我很好"), chat("今天是11月10日"), chat("你好", Route.CLOUD), chat("你好", conf = 0.6))) {
            clf.reply = { bad }
            assertEquals(TurnSource.CLOUD, orch(clf, chitchatModel = true).handle("你好").source)
        }
        // not small talk per the rules: the model never writes the reply
        clf.answer = { _, _ -> chat("我叫小智") }
        assertEquals(TurnSource.CLOUD, orch(clf, chitchatModel = true).handle("你叫什么名字").source)
    }

    @Test
    fun smallTalkUsesTemplatesWithoutModel() {
        val clf = FakeClassifier { _, _ -> error("no model for small talk") }
        val o = orch(clf)
        val replies = (1..6).map { o.handle("谢谢").reply!! }
        assertTrue(replies.all { it in SmallTalkReplies.options("thanks", "谢谢", 12) })
        replies.zipWithNext().forEach { (a, b) -> assertTrue("no immediate repeat", a != b) }
        assertEquals(TurnSource.LOCAL_RULE, o.handle("你好").source)
        assertEquals(0, clf.calls.size + clf.chitchatCalls.size)
        assertTrue(cloudCalls.isEmpty())
        // works without any model (all tiers); setting off → cloud
        assertEquals(TurnSource.LOCAL_RULE, orch(null).handle("再见").source)
        assertEquals(TurnSource.CLOUD, orch(null, chitchat = false).handle("再见").source)
    }

    @Test
    fun preGateSendsNonActionSentencesStraightToCloud() {
        val clf = FakeClassifier { _, _ -> model(Intents.VOLUME, Route.LOCAL, 0.95, "direction" to "up") }
        val o = orch(clf)
        for (t in listOf("讲个笑话", "今天有什么新闻", "怎么把音量调大", "你是谁", "明天适合爬山吗", "设个明天早上七点的闹钟")) {
            val r = o.handle(t)
            assertEquals(t, TurnSource.CLOUD, r.source)
            assertEquals(t, "gated", r.trace.modelStatus)
            assertEquals(false, r.trace.modelInvoked)
        }
        assertEquals(0, clf.calls.size)
        // an action-domain sentence still reaches the model
        assertEquals(TurnSource.LOCAL_MODEL, o.handle("把那个声音往上提提").source)
        assertEquals(1, clf.calls.size)
    }

    @Test
    fun speculativeCloudIsDiscardedWhenLocalWinsAndUsedOtherwise() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var answer = model(Intents.VOLUME, Route.LOCAL, 0.95, "direction" to "up")
        val clf = FakeClassifier { _, _ -> answer }
        val o = TurnOrchestrator(
            TurnOrchestrator.Config(speculativeCloud = true), RuleRouter(), clf, LocalToolExecutor(platform, weather), ctx,
            cloud = { calls += it.text; Result.success(com.voicekiosk.app.conversation.CloudReply("云端：${it.text}")) }, cloudExecutor = exec
        )
        val local = o.handle("把那个声音往上提提")
        assertEquals(TurnSource.LOCAL_MODEL, local.source)
        assertTrue(local.trace.cloudDiscarded)
        assertTrue(local.reply!!.startsWith("音量已调大"))
        answer = model(Intents.CLOUD_QUESTION, Route.CLOUD, 0.9)
        val cloud = o.handle("把那个声音往下压压")
        assertEquals("云端：把那个声音往下压压", cloud.reply)
        assertEquals(false, cloud.trace.cloudDiscarded)
        // gated sentence: no model, no speculation, exactly one cloud call
        o.handle("讲个笑话")
        exec.shutdown(); exec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        // one bridge call per turn: the discarded one, the used one, the gated one
        assertEquals(listOf("把那个声音往上提提", "把那个声音往下压压", "讲个笑话"), calls)
    }

    // ---- S4: continuity between local and cloud turns -------------------------------------

    private fun sentUsers(i: Int) = cloudRequests[i].localTurns.map { it.userText }

    @Test
    fun alternatingTenTurnScriptCarriesLocalTurnsToTheCloudInOrder() {
        val o = orch()
        assertEquals(TurnSource.LOCAL_RULE, o.handle("播放周杰伦的晴天").source)
        val q1 = o.handle("刚才那首是谁唱的")
        assertEquals(TurnSource.CLOUD, q1.source)
        assertEquals(listOf("播放周杰伦的晴天"), sentUsers(0))
        val action = com.voicekiosk.app.conversation.LocalTurnsCodec.action(cloudRequests[0].localTurns[0])
        assertEquals("media_play artist=周杰伦 song=晴天", action)

        assertEquals(TurnSource.LOCAL_RULE, o.handle("大声点").source)
        assertEquals(TurnSource.LOCAL_RULE, o.handle("北京明天天气怎么样").source)
        val ell = o.handle("那后天呢")
        assertEquals(TurnSource.LOCAL_RULE, ell.source)
        assertEquals("北京", ell.decision!!.slots["city"])
        assertEquals(TurnSource.CLOUD, o.handle("后天适合去爬山吗").source)
        assertEquals(listOf("大声点", "北京明天天气怎么样", "那后天呢"), sentUsers(1))

        assertEquals(TurnSource.LOCAL_RULE, o.handle("谢谢").source)
        // the ellipsis right after small talk is not a weather follow-up locally: sticky to the
        // cloud, which now has the weather turns in its history
        assertEquals(TurnSource.CLOUD, o.handle("那上海呢").source)
        assertEquals(listOf("谢谢"), sentUsers(2))
        // nothing is sent twice
        assertEquals(5, cloudRequests.sumOf { it.localTurns.size })
        assertEquals(cloudRequests.flatMap { it.localTurns.map { t -> t.id } }.toSet().size, 5)

        assertEquals(TurnSource.LOCAL_RULE, o.handle("暂停").source)
        o.handle("重新开始")
        assertEquals(TurnOrchestrator.BRIDGE_RESET_TEXT, cloudRequests[3].text)
        assertTrue("reset drops pending local turns", cloudRequests[3].localTurns.isEmpty())
        assertTrue(ctx.turns().isEmpty())
        o.handle("讲个笑话")
        assertTrue(cloudRequests[4].localTurns.isEmpty())
        assertEquals(false, cloudRequests[4].resetSession)
    }

    @Test
    fun failedCloudCallKeepsLocalTurnsPendingAndResendsThem() {
        val o = orch()
        o.handle("下一首")
        cloudResult = { Result.failure(IllegalStateException("offline")) }
        assertEquals(null, o.handle("这首歌叫什么").reply)
        assertEquals(1, ctx.unsyncedLocalTurns().size)
        cloudResult = { Result.success("云端回答：$it") }
        o.handle("这首歌叫什么")
        assertEquals(cloudRequests[0].localTurns.map { it.id }, cloudRequests[1].localTurns.map { it.id })
        assertTrue(ctx.unsyncedLocalTurns().isEmpty())
    }

    @Test
    fun oldBridgeWithoutAcksStillMarksTurnsSynced() {
        ackedIds = null
        val o = orch()
        o.handle("大声点")
        o.handle("讲个笑话")
        o.handle("再讲一个")
        assertEquals(1, cloudRequests[0].localTurns.size)
        assertTrue(cloudRequests[1].localTurns.isEmpty())
    }

    @Test
    fun onlyTheNewestPendingTurnsAreSentAndOlderOnesAreNotResentLater() {
        val o = orch()
        repeat(12) { o.handle(if (it % 2 == 0) "大声点" else "小声点") }
        o.handle("讲个笑话")
        assertEquals(com.voicekiosk.app.conversation.ConversationContext.MAX_PENDING_SENT, cloudRequests[0].localTurns.size)
        o.handle("再讲一个")
        assertTrue(cloudRequests[1].localTurns.isEmpty())
    }

    @Test
    fun resetWhileBridgeIsDownIsReplayedOnTheNextCall() {
        val o = orch()
        o.handle("大声点")
        cloudResult = { Result.failure(IllegalStateException("offline")) }
        val r = o.handle("重新开始")
        assertEquals(TurnOrchestrator.LOCAL_RESET_REPLY, r.reply)
        assertTrue(ctx.bridgeResetPending)
        cloudResult = { Result.success("云端回答：$it") }
        o.handle("讲个笑话")
        assertEquals(true, cloudRequests.last().resetSession)
        assertTrue(cloudRequests.last().localTurns.isEmpty())
        o.handle("再讲一个")
        assertEquals(false, cloudRequests.last().resetSession)
    }

    @Test
    fun ruleStateStaysCoherentAfterCloudTurns() {
        val o = orch()
        o.handle("北京明天天气怎么样")
        o.handle("北京有什么好玩的")          // cloud turn in between
        // ellipsis right after an unrelated cloud turn is not resolved locally (sticky cloud)
        assertEquals(TurnSource.CLOUD, o.handle("那后天呢").source)
        // a full weather command is local again, and a following ellipsis uses its city
        assertEquals(TurnSource.LOCAL_RULE, o.handle("上海今天天气").source)
        val e = o.handle("那明天呢")
        assertEquals(TurnSource.LOCAL_RULE, e.source)
        assertEquals("上海", e.decision!!.slots["city"])
        // volume ellipsis after a cloud turn still has no previous volume turn → no local "再大点"
        o.handle("讲个笑话")
        assertEquals(TurnSource.CLOUD, o.handle("再大点").source)
    }

    @Test
    fun openQuestionSecondOptionIsLocalAndNotCloud() {
        val question = com.voicekiosk.app.conversation.OpenQuestion(
            id = "q1", prompt = "选班", options = listOf("甲班", "乙班", "丙班")
        )
        val posted = mutableListOf<String>()
        val o = TurnOrchestrator(
            TurnOrchestrator.Config(modelRoutingEnabled = false),
            RuleRouter(), null, LocalToolExecutor(platform, weather), ctx,
            cloud = { req -> cloudCalls += req.text; Result.success(com.voicekiosk.app.conversation.CloudReply("云")) },
            openQuestion = { question },
            postAnswer = { q, m -> posted += "${q.id}:${m.option}"; Result.success(Unit) }
        )
        val r = o.handle("第二个")
        assertEquals(TurnSource.LOCAL_RULE, r.source)
        assertEquals("好的，已选择乙班", r.reply)
        assertEquals(listOf("q1:乙班"), posted)
        assertEquals(emptyList<String>(), cloudCalls)
        assertEquals(Intents.QUESTION_ANSWER, r.decision!!.intent)
    }

    @Test
    fun approvalVoiceOpensPinAndNeverPosts() {
        val approval = com.voicekiosk.app.conversation.OpenApproval(id = "ap1", risk = "medium")
        val pins = mutableListOf<Pair<String, Boolean>>()
        var posted = 0
        val o = TurnOrchestrator(
            TurnOrchestrator.Config(modelRoutingEnabled = false),
            RuleRouter(), null, LocalToolExecutor(platform, weather), ctx,
            cloud = { req -> cloudCalls += req.text; Result.success(com.voicekiosk.app.conversation.CloudReply("云")) },
            openApproval = { approval },
            postAnswer = { _, _ -> posted += 1; Result.success(Unit) },
            onApprovalVoice = { ap, approve -> pins += ap.id to approve }
        )
        val r = o.handle("批准")
        assertEquals("请在屏幕上输入审批 PIN", r.reply)
        assertEquals(listOf("ap1" to true), pins)
        assertEquals(0, posted)
        assertEquals(emptyList<String>(), cloudCalls)
        val reject = o.handle("拒绝")
        assertEquals(Intents.APPROVAL_PIN, reject.decision!!.intent)
        assertEquals(listOf("ap1" to true, "ap1" to false), pins)
    }

    @Test
    fun failedPostQueuesAnswer() {
        val question = com.voicekiosk.app.conversation.OpenQuestion(
            id = "q2", options = listOf("是", "不用"), kind = "confirm", expiresAt = 9_999.0
        )
        val queue = com.voicekiosk.app.conversation.AnswerQueue(clock = { 1_000L })
        val o = TurnOrchestrator(
            TurnOrchestrator.Config(modelRoutingEnabled = false),
            RuleRouter(), null, LocalToolExecutor(platform, weather), ctx,
            cloud = { Result.success(com.voicekiosk.app.conversation.CloudReply("云")) },
            openQuestion = { question },
            postAnswer = { _, _ -> Result.failure(IllegalStateException("offline")) },
            answerQueue = queue
        )
        o.handle("好的")
        assertEquals(1, queue.size())
        assertEquals("是", queue.pending().single().option)
    }
}
