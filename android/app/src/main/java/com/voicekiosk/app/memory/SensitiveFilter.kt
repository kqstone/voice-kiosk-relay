package com.voicekiosk.app.memory

/**
 * Deterministic sensitive-content check used before and after extraction. A port of
 * nas-bridge memory_store SENSITIVE_PATTERNS (credentials, IDs, card numbers, phone /
 * email, precise addresses, health) plus DEVICE_CANDIDATE_EXTRA_SENSITIVE (finance,
 * accounts, plates, precise location, schools, beliefs). The bridge re-checks everything.
 */
object SensitiveFilter {
    private val PATTERNS = listOf(
        Regex("\\bsk-[a-zA-Z0-9_\\-]{12,}\\b"),
        Regex("\\bgh[pousr]_[a-zA-Z0-9]{12,}\\b"),
        Regex("(?i)\\bbearer\\s+[a-zA-Z0-9_\\-.=]{8,}"),
        Regex("(?i)(api[_\\-]?key|access[_\\-]?token|auth[_\\-]?token|secret[_\\-]?key|private[_\\-]?key|password|passwd|pwd)"),
        Regex("(?i)(密码|口令|密钥|私钥|助记词|令牌|验证码|银行卡|卡号|信用卡|社保|身份证|护照|驾照|证件|cvv|pin码)"),
        Regex("\\d{17}[\\dXx]"),
        Regex("\\d{11,19}"),
        Regex("(?:\\+?86[\\s\\-]?)?1[3-9]\\d[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}"),
        Regex("(手机|电话|号码|座机|微信号|QQ号|邮箱|邮件地址)"),
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        Regex("(住址|地址|门牌|小区|街道|路\\d|号楼|单元|室\\b|栋|幢|楼\\d|\\d+号|定位|经纬度|坐标)"),
        Regex(
            "(病|医院|医生|看病|吃药|药|诊断|确诊|住院|手术|术后|过敏|体检|化验|怀孕|孕|流产|血压|血糖|血型|" +
                "抑郁|焦虑|癌|肿瘤|糖尿|艾滋|HIV|肝炎|疫苗|体重|身高|心理咨询)"
        ),
        Regex(
            "(工资|薪水|年薪|月薪|收入|存款|余额|贷款|房贷|车贷|欠款|欠债|负债|理财|股票|基金|转账|账号|账户|" +
                "支付宝|微信支付|用户名|登录|车牌|学号|工号|保险单|保单)"
        ),
        // where a child goes every day is precise location
        Regex("(幼儿园|小学|中学|学校|年级|班级|[一二三四五六七八九十\\d]+班)"),
        Regex("(宗教|信仰|政党|性取向)")
    )

    fun isSensitive(text: String): Boolean {
        val t = text.replace(Regex("\\s+"), "")
        return PATTERNS.any { it.containsMatchIn(t) }
    }
}
