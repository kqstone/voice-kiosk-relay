"""Unit tests for SensitiveFilter covering every sensitive pattern and safe utterances.

Verifies:
- Passwords, secrets, credentials, API keys, tokens
- Phone numbers, email addresses, contact details
- ID numbers (18-digit ID cards, social security, passports)
- Precise addresses and location keywords
- Health, medical conditions, medications, biometrics
- Financial records, salary, loans, accounts, bank cards
- School and class information
- Religion, beliefs, parties, sexual orientation
- Spaced evasion attempts (spaces between digits/words)
- Negative controls: typical user memory facts that must NOT be rejected
"""

import unittest

from app.sensitive import SensitiveFilter, is_sensitive


class TestSensitiveFilter(unittest.TestCase):
    def test_passwords_and_tokens(self) -> None:
        patterns = [
            "sk-1234567890abcdef12",
            "ghp_1234567890abcdef12",
            "gho_1234567890abcdef12",
            "ghu_1234567890abcdef12",
            "ghs_1234567890abcdef12",
            "ghr_1234567890abcdef12",
            "Bearer abcdefgh123456",
            "bearer abcdefgh123456",
            "api_key=secret-key-123",
            "my api-key is secret",
            "access_token: token123",
            "auth_token: token123",
            "secret_key=12345",
            "private_key",
            "我的密码是abc12345",
            "password123",
            "passwd: 1234",
            "pwd = 123",
            "口令是1234",
            "私钥不要告诉别人",
            "钱包助记词是twelve words",
            "令牌已失效",
            "短信验证码是8888",
            "信用卡cvv是123",
            "pin码是0000",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch password/token pattern: {text}")
            self.assertTrue(SensitiveFilter.is_sensitive(text))

    def test_phone_and_contact(self) -> None:
        patterns = [
            "我的手机号是13812345678",
            "+86 139 1234 5678",
            "+86-138-1234-5678",
            "18612345678",
            "我的手机是13812345678",
            "我的电话是0571-88888888",
            "座机号码是88888888",
            "微信号是wxid_12345",
            "QQ号是12345678",
            "我的邮箱是user@example.com",
            "联系邮件地址",
            "test.user+tag@domain.co.uk",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch phone/contact pattern: {text}")

    def test_id_numbers(self) -> None:
        patterns = [
            "我的身份证号是110101199003072345",
            "身份证11010119900307234X",
            "身份证11010119900307234x",
            "需要出示身份证",
            "我的社保卡丢了",
            "护照办理流程",
            "驾照扣了三分",
            "有效证件原件",
            "6222021234567890123",  # 19 digits bank card
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch ID pattern: {text}")

    def test_precise_addresses(self) -> None:
        patterns = [
            "我住在杭州市西湖区文三路100号",
            "我家在阳光小区三号楼",
            "住在幸福街道",
            "门牌号是42",
            "302室",
            "8号楼2单元",
            "1号栋",
            "2幢",
            "楼3",
            "路5",
            "详细地址在滨江",
            "家庭定位开启",
            "经纬度120.12,30.28",
            "当前坐标位置",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch address pattern: {text}")

    def test_health_and_medical(self) -> None:
        patterns = [
            "我有高血压",
            "得了糖尿病",
            "去医院看病",
            "医生说要多休息",
            "平时要吃药",
            "我喜欢吃药膳",
            "看病花了五百",
            "医生下的诊断",
            "确诊轻度贫血",
            "上个月住院了",
            "做了个小手术",
            "术后康复期",
            "对海鲜过敏",
            "明天去做体检",
            "血液化验结果",
            "老婆怀孕六个月",
            "孕期要注意休息",
            "不幸流产了",
            "血糖偏高",
            "血型是O型",
            "最近有点抑郁",
            "经常感到焦虑",
            "早期胃癌",
            "良性肿瘤",
            "艾滋病预防",
            "HIV检测阴性",
            "慢性肝炎",
            "打流感疫苗",
            "体重65公斤",
            "身高一米八",
            "做过心理咨询",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch health pattern: {text}")

    def test_finance_and_accounts(self) -> None:
        patterns = [
            "我月薪两万",
            "年薪三十万",
            "每个月工资八千",
            "年薪五十万左右",
            "家庭月收入",
            "银行存款十万",
            "账户余额不足",
            "还有商业贷款",
            "每月还房贷五千",
            "车贷还剩一年",
            "欠款五万元",
            "欠债要还钱",
            "目前没有任何负债",
            "买了银行理财",
            "在炒股票",
            "买了易方达基金",
            "微信转账两百元",
            "我的账号是admin",
            "银行账户已锁定",
            "支持支付宝付款",
            "微信支付密码",
            "我的用户名是user1",
            "登录系统",
            "我的车牌是浙A12345",
            "我的学号是20230101",
            "员工工号是9527",
            "家庭保险单",
            "人寿保单",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch finance pattern: {text}")

    def test_school_and_class_info(self) -> None:
        patterns = [
            "我女儿在阳光幼儿园",
            "就读于实验小学",
            "文三路第一中学",
            "学校组织的活动",
            "三年级学生",
            "小学三年级二班",
            "初中七班",
            "高二8班",
            "三班班长",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch school/class pattern: {text}")

    def test_religion_and_beliefs(self) -> None:
        patterns = [
            "有宗教信仰",
            "我的个人信仰是佛教",
            "政党活动",
            "讨论性取向",
        ]
        for text in patterns:
            self.assertTrue(is_sensitive(text), f"Failed to catch religion/beliefs pattern: {text}")

    def test_spaced_and_case_evasions(self) -> None:
        # Evasions with spaces between digits or keywords
        evasions = [
            "1 3 8 1 2 3 4 5 6 7 8",
            "密 码 是 1 2 3",
            "高 血 压",
            "学 校 活 动",
            "BEARER abcdefgh1234",
            "PASSWORD",
            "PassWord",
        ]
        for text in evasions:
            self.assertTrue(is_sensitive(text), f"Failed to catch spaced/cased evasion: {text}")

    def test_safe_non_sensitive_facts(self) -> None:
        safe_facts = [
            "喜欢听周杰伦的歌",
            "特别喜欢听邓丽君",
            "最喜欢的歌手是王菲",
            "希望被称呼为小明",
            "通常早上七点起床",
            "通常晚上十一点睡觉",
            "住在杭州",
            "住在成都",
            "住在北京",
            "希望说话慢一点",
            "希望回答简短一些",
            "女儿叫朵朵",
            "儿子叫阳阳",
            "家里的猫叫咪咪",
            "家里的小狗叫豆豆",
            "喜欢喝绿茶",
            "喜欢喝龙井茶",
            "喜欢喝咖啡",
            "喜欢吃红烧肉",
            "通常下午六点下班",
            "通常晚上七点吃晚饭",
        ]
        for text in safe_facts:
            self.assertFalse(is_sensitive(text), f"False positive on safe fact: {text}")
            self.assertFalse(SensitiveFilter.is_sensitive(text))

    def test_empty_and_whitespace(self) -> None:
        self.assertFalse(is_sensitive(""))
        self.assertFalse(is_sensitive("   "))


if __name__ == "__main__":
    unittest.main()
