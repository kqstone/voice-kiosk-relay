"""Sensitive content filter for long-term memory.

Port of Android SensitiveFilter (plan §4.3 & §5 Phase 2).
Re-validates all content and keys before storing. Anything matching is rejected.
Sensitive content must never be stored and never be logged in full.
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger(__name__)

# Patterns matching sensitive information:
# 1. API keys & bearer tokens
# 2. Passwords / secrets / credentials
# 3. ID numbers (18-digit resident ID, card/account digits)
# 4. Phone numbers / email / contact info
# 5. Precise addresses & locations
# 6. Health & medical conditions
# 7. Finance, banking, salary, accounts
# 8. School & class info
# 9. Religion, beliefs, political parties, sexual orientation
SENSITIVE_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"\bsk-[a-zA-Z0-9_\-]{12,}\b"),
    re.compile(r"\bgh[pousr]_[a-zA-Z0-9]{12,}\b"),
    re.compile(r"(?i)\bbearer\s+[a-zA-Z0-9_\-.=]{8,}"),
    re.compile(
        r"(?i)(api[_\-]?key|access[_\-]?token|auth[_\-]?token|secret[_\-]?key|private[_\-]?key|password|passwd|pwd)"
    ),
    re.compile(
        r"(?i)(密码|口令|密钥|私钥|助记词|令牌|验证码|银行卡|卡号|信用卡|社保|身份证|护照|驾照|证件|cvv|pin码)"
    ),
    re.compile(r"\d{17}[\dXx]"),
    re.compile(r"\d{11,19}"),
    re.compile(r"(?:\+?86[\s\-]?)?1[3-9]\d[\s\-]?\d{4}[\s\-]?\d{4}"),
    re.compile(r"(手机|电话|号码|座机|微信号|QQ号|邮箱|邮件地址)"),
    re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"),
    re.compile(r"(住址|地址|门牌|小区|街道|路\d|号楼|单元|室\b|栋|幢|楼\d|\d+号|定位|经纬度|坐标)"),
    re.compile(
        r"(病|医院|医生|看病|吃药|药|诊断|确诊|住院|手术|术后|过敏|体检|化验|怀孕|孕|流产|血压|血糖|血型|"
        r"抑郁|焦虑|癌|肿瘤|糖尿|艾滋|HIV|肝炎|疫苗|体重|身高|心理咨询)"
    ),
    re.compile(
        r"(工资|薪水|年薪|月薪|收入|存款|余额|贷款|房贷|车贷|欠款|欠债|负债|理财|股票|基金|转账|账号|账户|"
        r"支付宝|微信支付|用户名|登录|车牌|学号|工号|保险单|保单)"
    ),
    re.compile(r"(幼儿园|小学|中学|学校|年级|班级|[一二三四五六七八九十\d]+班)"),
    re.compile(r"(宗教|信仰|政党|性取向)"),
]


def is_sensitive(text: str) -> bool:
    """Return True if text contains any sensitive information.

    Checks both raw text (for patterns relying on spaces like `bearer <token>`)
    and whitespace-stripped text (for spaced-out numbers/keywords).
    """
    if not text:
        return False

    for pattern in SENSITIVE_PATTERNS:
        if pattern.search(text):
            return True

    stripped = re.sub(r"\s+", "", text)
    if stripped != text:
        for pattern in SENSITIVE_PATTERNS:
            if pattern.search(stripped):
                return True

    return False


class SensitiveFilter:
    """Class wrapper matching Android SensitiveFilter object interface."""

    @staticmethod
    def is_sensitive(text: str) -> bool:
        return is_sensitive(text)
