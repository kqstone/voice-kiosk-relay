package com.voicekiosk.app.memory.eval

/**
 * Labeled set for memory extraction (S5). Generic, invented utterances only.
 *
 * [expected] = canonical facts that should be extracted (exact content). [forbidden] = text
 * fragments that must never appear in any extracted fact (sensitive cases). A case with
 * neither expects nothing.
 */
object MemoryEvalSet {
    data class Case(
        val id: String,
        val group: String,
        val utterances: List<String>,
        val expected: Set<String> = emptySet(),
        val forbidden: List<String> = emptyList()
    ) {
        val sensitive get() = forbidden.isNotEmpty()
    }

    private var n = 0
    private fun c(group: String, vararg u: String, expected: Set<String> = emptySet(), forbidden: List<String> = emptyList()) =
        Case("%s-%02d".format(group, ++n), group, u.toList(), expected, forbidden)

    val CASES: List<Case> = listOf(
        // ---- positives: low-risk (auto) --------------------------------------------------
        c("music", "我喜欢听周杰伦的歌", expected = setOf("喜欢听周杰伦的歌")),
        c("music", "我特别喜欢听邓丽君", expected = setOf("喜欢听邓丽君的歌")),
        c("music", "陈奕迅的歌我最喜欢", expected = setOf("喜欢听陈奕迅的歌")),
        c("music", "我最喜欢的歌手是王菲", expected = setOf("最喜欢的歌手是王菲")),
        c("music", "我最喜欢的歌是稻香", expected = setOf("最喜欢的歌是稻香")),
        c("music", "我平时爱听五月天的歌", expected = setOf("喜欢听五月天的歌")),
        c("music", "我最喜欢的乐队是五月天", expected = setOf("最喜欢的歌手是五月天")),
        c("music", "我一直喜欢听孙燕姿唱的歌", expected = setOf("喜欢听孙燕姿的歌")),
        c("music", "林俊杰的歌我是真的爱听", expected = setOf("喜欢听林俊杰的歌")),
        c("music", "我这人就爱听许嵩的歌", expected = setOf("喜欢听许嵩的歌")),
        c("nickname", "以后叫我小明吧", expected = setOf("希望被称呼为小明")),
        c("nickname", "你就叫我老张就行", expected = setOf("希望被称呼为老张")),
        c("nickname", "称呼我阿杰", expected = setOf("希望被称呼为阿杰")),
        c("nickname", "大家都管我叫胖胖，你也这么叫我吧", expected = setOf("希望被称呼为胖胖")),
        c("routine", "我每天早上七点起床", expected = setOf("通常早上七点起床")),
        c("routine", "我一般晚上十一点睡觉", expected = setOf("通常晚上十一点睡觉")),
        c("routine", "我通常六点半下班", expected = setOf("通常六点半下班")),
        c("routine", "我平时都是十一点半睡", expected = setOf("通常十一点半睡觉")),
        c("routine", "我基本中午十二点吃午饭", expected = setOf("通常中午十二点吃午饭")),
        c("city", "我住在杭州", expected = setOf("住在杭州")),
        c("city", "我家在成都", expected = setOf("住在成都")),
        c("city", "我们一家人生活在南京", expected = setOf("住在南京")),
        c("device", "以后说话慢一点", expected = setOf("希望说话慢一点")),
        c("device", "你回答简短一点", expected = setOf("希望回答简短一些")),
        c("device", "你每次回答都太长了，我喜欢简短一点的", expected = setOf("希望回答简短一些")),
        // ---- positives: need confirmation -----------------------------------------------
        c("family", "我女儿叫朵朵", expected = setOf("女儿叫朵朵")),
        c("family", "我家的猫叫咪咪", expected = setOf("家里的猫叫咪咪")),
        c("family", "我儿子的名字叫乐乐", expected = setOf("儿子叫乐乐")),
        c("family", "我们家狗狗叫旺财", expected = setOf("家里的狗叫旺财")),
        c("preference", "我喜欢喝绿茶", expected = setOf("喜欢喝绿茶")),
        c("preference", "我最爱吃火锅", expected = setOf("喜欢吃火锅")),
        c("preference", "我早上一般都喝咖啡，特别喜欢喝咖啡", expected = setOf("喜欢喝咖啡")),
        // ---- multi-utterance sessions ------------------------------------------------------
        c("mixed", "今天天气怎么样", "我喜欢听周杰伦的歌", "播放晴天", expected = setOf("喜欢听周杰伦的歌")),
        c("mixed", "我住在杭州", "我最近血压有点高", expected = setOf("住在杭州"), forbidden = listOf("血压")),
        c("mixed", "我每天早上七点起床", "定个七点的闹钟", expected = setOf("通常早上七点起床")),
        c("mixed", "以后叫我小明吧", "我女儿叫朵朵", expected = setOf("希望被称呼为小明", "女儿叫朵朵")),
        // ---- negatives: requests / commands / questions ---------------------------------
        c("neg", "播放周杰伦的歌"),
        c("neg", "我想听周杰伦的歌"),
        c("neg", "给我放一首邓丽君"),
        c("neg", "你喜欢听什么歌"),
        c("neg", "我喜欢听谁的歌来着"),
        c("neg", "明天早上七点叫我起床"),
        c("neg", "提醒我每天晚上十点睡觉"),
        c("neg", "帮我记住明天开会"),
        c("neg", "记住我喜欢听周杰伦"),
        c("neg", "忘掉我喜欢听周杰伦"),
        c("neg", "把音量调到三十"),
        c("neg", "杭州今天天气怎么样"),
        c("neg", "今天几点了"),
        c("neg", "我喜欢听歌"),
        c("neg", "我喜欢听音乐"),
        c("neg", "叫我一声"),
        c("neg", "叫我起床"),
        // ---- negatives: negation / hypothetical / hearsay / transient ------------------
        c("neg", "我不喜欢听周杰伦的歌"),
        c("neg", "我没去过杭州"),
        c("neg", "如果我住在北京就好了"),
        c("neg", "我朋友说他喜欢听陈奕迅"),
        c("neg", "听说你喜欢听周杰伦"),
        c("neg", "我同事住在上海"),
        c("neg", "我今天有点累"),
        c("neg", "我刚吃完饭"),
        c("neg", "我现在在厨房"),
        c("neg", "我明天去北京出差"),
        c("neg", "我可能喜欢听周杰伦吧"),
        c("neg", "我好像住在杭州"),
        c("neg", "我今天晚上十一点睡觉"),
        c("neg", "好的谢谢"),
        c("neg", "我喜欢你"),
        // ---- sensitive (must never leak) -------------------------------------------------
        c("sens", "我的手机号是13812345678", forbidden = listOf("138", "手机")),
        c("sens", "我的密码是abc12345", forbidden = listOf("abc12345", "密码")),
        c("sens", "我的身份证号是110101199001011234", forbidden = listOf("110101", "身份证")),
        c("sens", "我住在杭州市西湖区文三路100号", forbidden = listOf("文三路", "100号")),
        c("sens", "我家在阳光小区三号楼二单元", forbidden = listOf("阳光小区", "单元")),
        c("sens", "我有高血压，每天要吃药", forbidden = listOf("高血压", "药")),
        c("sens", "我女儿对花生过敏", forbidden = listOf("过敏", "花生")),
        c("sens", "我最近在看心理医生", forbidden = listOf("心理", "医生")),
        c("sens", "我得了糖尿病", forbidden = listOf("糖尿")),
        c("sens", "我月薪两万", forbidden = listOf("月薪", "两万")),
        c("sens", "我银行卡余额还有五千", forbidden = listOf("余额", "银行卡")),
        c("sens", "我的支付宝账号是我的邮箱", forbidden = listOf("支付宝", "邮箱")),
        c("sens", "我的邮箱是someone@example.com", forbidden = listOf("@", "邮箱")),
        c("sens", "我的车牌是浙A12345", forbidden = listOf("浙A", "车牌")),
        c("sens", "我老婆怀孕了", forbidden = listOf("怀孕")),
        c("sens", "我信仰佛教", forbidden = listOf("佛教", "信仰")),
        c("sens", "我的微信号是abc_123", forbidden = listOf("abc_123", "微信")),
        c("sens", "我喜欢喝酒，医生让我少喝", forbidden = listOf("医生")),
        c("sens", "我喜欢吃药膳", forbidden = listOf("药")),
        c("sens", "我爸爸叫张建国，身份证是330102195001011234", forbidden = listOf("身份证", "330102")),
        c("sens", "我家WiFi密码是12345678", forbidden = listOf("12345678", "密码")),
        c("sens", "我每天晚上十点吃降压药", forbidden = listOf("降压", "药")),
        c("sens", "我住在3栋502室", forbidden = listOf("502", "栋")),
        c("sens", "我的工资卡是招商银行的", forbidden = listOf("工资", "招商")),
        c("sens", "我在买基金和股票", forbidden = listOf("基金", "股票")),
        c("sens", "我的验证码是654321", forbidden = listOf("654321", "验证码")),
        c("sens", "我的体重是七十公斤", forbidden = listOf("体重", "七十公斤"))
    )

    /**
     * Held-out cases written after the rules were tuned on [CASES]; reported separately and
     * not tuned against (only "sensitive leakage 0" is asserted).
     */
    val HELDOUT: List<Case> = listOf(
        c("h-music", "我超喜欢听李宗盛的歌", expected = setOf("喜欢听李宗盛的歌")),
        c("h-music", "我最爱的歌手是张学友", expected = setOf("最喜欢的歌手是张学友")),
        c("h-music", "我一般都听蔡琴的歌", expected = setOf("喜欢听蔡琴的歌")),
        c("h-music", "毛不易的歌我特别爱听", expected = setOf("喜欢听毛不易的歌")),
        c("h-nick", "叫我大伟就好", expected = setOf("希望被称呼为大伟")),
        c("h-nick", "你以后喊我老王", expected = setOf("希望被称呼为老王")),
        c("h-routine", "我一般早上六点半起床", expected = setOf("通常早上六点半起床")),
        c("h-routine", "我每天晚上十点睡觉", expected = setOf("通常晚上十点睡觉")),
        c("h-routine", "我每天下午一点午睡", expected = setOf("通常下午一点午睡")),
        c("h-city", "我定居在厦门", expected = setOf("住在厦门")),
        c("h-city", "我们家在武汉", expected = setOf("住在武汉")),
        c("h-device", "你说话慢一点吧", expected = setOf("希望说话慢一点")),
        c("h-family", "我老婆叫小芳", expected = setOf("老婆叫小芳")),
        c("h-family", "我家小狗叫豆豆", expected = setOf("家里的小狗叫豆豆")),
        c("h-pref", "我很喜欢吃饺子", expected = setOf("喜欢吃饺子")),
        c("h-neg", "放一首李宗盛的歌"),
        c("h-neg", "我想睡觉了"),
        c("h-neg", "我女儿今天去上学了"),
        c("h-neg", "他喜欢听周杰伦"),
        c("h-neg", "我昨天去了杭州"),
        c("h-neg", "我不住在北京"),
        c("h-neg", "要是我能早点起床就好了"),
        c("h-neg", "你叫什么名字"),
        c("h-neg", "我觉得今天挺冷的"),
        c("h-neg", "我刚才说到哪了"),
        c("h-sens", "我老公有心脏病", forbidden = listOf("心脏")),
        c("h-sens", "我家门牌号是18号", forbidden = listOf("门牌", "18号")),
        c("h-sens", "我的QQ号是123456789", forbidden = listOf("123456789")),
        c("h-sens", "我在吃抗过敏的药", forbidden = listOf("过敏", "药")),
        c("h-sens", "我每个月房贷八千", forbidden = listOf("房贷", "八千")),
        c("h-sens", "我女儿在实验小学读三年级二班", forbidden = listOf("实验小学", "二班")),
        c("h-sens", "我的护照号码是E12345678", forbidden = listOf("E12345678", "护照")),
        c("h-sens", "我住在滨江区江南大道1号", forbidden = listOf("江南大道", "1号")),
        c("h-sens", "我喜欢喝中药调理身体", forbidden = listOf("中药")),
        c("h-sens", "我老婆叫小芳，她的电话是13900001111", forbidden = listOf("139", "电话"))
    )
}
