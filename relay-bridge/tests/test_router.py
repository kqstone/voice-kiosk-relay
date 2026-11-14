from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
import json

from fastapi.testclient import TestClient

from app.config import settings
from app.llm import BridgeLLMError
from app.main import app
from app.router import (
    OVERRIDE_LOCAL_PHRASES,
    OVERRIDE_MUSE_PHRASES,
    ROUTER_SYSTEM_PROMPT,
    build_router_messages,
    check_explicit_override,
    clear_corrections,
    format_keyword_hints,
    lookup_correction,
    parse_router_response,
    record_correction,
    route_question,
)
from app.storage import routing_config_storage


class TestRouter(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.orig_base_dir = routing_config_storage.base_dir
        routing_config_storage.base_dir = self.test_dir

        self.orig_push = settings.push_token
        self.orig_device = settings.device_token
        settings.push_token = "test-push-token-1111"
        settings.device_token = "test-device-token-2222"

        clear_corrections()
        self.client = TestClient(app)

    def tearDown(self) -> None:
        routing_config_storage.base_dir = self.orig_base_dir
        settings.push_token = self.orig_push
        settings.device_token = self.orig_device
        clear_corrections()
        shutil.rmtree(self.test_dir, ignore_errors=True)

    async def test_router_returns_valid_json(self) -> None:
        mock_llm_response = json.dumps({
            "keywords": ["安安", "学习规划"],
            "route": "muse",
            "reason": "需要针对安安的学习做深度规划",
        })

        with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
            mock_chat.return_value = mock_llm_response

            result = await route_question("帮安安做下周的学习规划")
            self.assertEqual(result.get("route"), "muse")
            self.assertEqual(result.get("keywords"), ["安安", "学习规划"])
            self.assertEqual(result.get("reason"), "需要针对安安的学习做深度规划")
            mock_chat.assert_called_once()

    async def test_explicit_ask_muse_override_returns_muse_without_llm_call(self) -> None:
        sample_questions = [
            "ask Muse for help",
            "go ask Muse please",
            "这个问题请去问 Muse",
            "问问 Muse 明天几点出发",
        ]

        with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
            for question in sample_questions:
                result = await route_question(question)
                self.assertEqual(result.get("route"), "muse")
                self.assertIn("explicit override", result.get("reason", ""))
                mock_chat.assert_not_called()

    async def test_explicit_do_not_ask_muse_override_returns_local_without_llm_call(self) -> None:
        sample_questions = [
            "do not ask Muse, answer directly",
            "answer directly please",
            "这个问题不用问 Muse，直接答",
            "直接答，今天几号",
        ]

        with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
            for question in sample_questions:
                result = await route_question(question)
                self.assertEqual(result.get("route"), "local")
                self.assertIn("explicit override", result.get("reason", ""))
                mock_chat.assert_not_called()

    async def test_empty_keyword_config_does_not_crash(self) -> None:
        mock_llm_response = json.dumps({
            "keywords": ["天气"],
            "route": "local",
            "reason": "常见天气查询",
        })

        with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
            mock_chat.return_value = mock_llm_response

            # Test with empty dict
            res_empty_dict = await route_question("今天天气如何？", keyword_config={})
            self.assertEqual(res_empty_dict.get("route"), "local")
            self.assertEqual(res_empty_dict.get("keywords"), ["天气"])

            # Test with None
            res_none = await route_question("今天天气如何？", keyword_config=None)
            self.assertEqual(res_none.get("route"), "local")
            self.assertEqual(res_none.get("keywords"), ["天气"])

            # Verify that prompts constructed do not have keyword hint section
            messages = build_router_messages("今天天气如何？", keyword_config={})
            user_msg = messages[1]["content"]
            self.assertNotIn("参考关键词配置", user_msg)

    async def test_invalid_llm_json_output_falls_back_to_local(self) -> None:
        invalid_outputs = [
            "Not valid JSON at all",
            "{broken json: true",
            json.dumps({"keywords": ["test"]}),  # lacks route
            json.dumps({"route": "invalid_route", "keywords": []}),  # invalid route string
            "",
        ]

        for bad_out in invalid_outputs:
            with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
                mock_chat.return_value = bad_out
                result = await route_question("测试问题")
                self.assertEqual(result.get("route"), "local")
                self.assertEqual(result.get("reason"), "fallback")

        # Also test LLM raising an error
        with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
            mock_chat.side_effect = BridgeLLMError("LLM service unavailable")
            result = await route_question("测试问题")
            self.assertEqual(result.get("route"), "local")
            self.assertEqual(result.get("reason"), "fallback")

    async def test_correction_lookup_returns_forced_route(self) -> None:
        question = "安安这道奥数题怎么解答"
        forced_route = "muse"

        # Record correction
        record_correction(question, forced_route)
        self.assertEqual(lookup_correction(question), "muse")
        self.assertIsNone(lookup_correction("未记录过的问题"))

        # route_question should return forced route without LLM call
        with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
            result = await route_question(question)
            self.assertEqual(result.get("route"), "muse")
            self.assertIn("correction", result.get("reason", ""))
            mock_chat.assert_not_called()

    def test_keyword_config_endpoint_stores_and_reads(self) -> None:
        # 1. GET before any config pushed -> version 0 and empty muse_keywords
        res_get_initial = self.client.get(
            "/api/v1/config/routing",
            headers={"Authorization": "Bearer test-device-token-2222"},
        )
        self.assertEqual(res_get_initial.status_code, 200)
        data_initial = res_get_initial.json()
        self.assertEqual(data_initial.get("version"), 0)
        self.assertEqual(data_initial.get("muse_keywords"), [])

        # 2. POST without token -> 401
        sample_payload = {
            "version": 1,
            "updated_at": "2026-11-12T13:00:00Z",
            "muse_keywords": [
                {"keyword": "作业", "topic": "学习", "note": "作业规划及辅导"},
                {"keyword": "打印", "topic": "工具", "note": "打印相关任务"},
            ],
        }
        res_no_token = self.client.post("/api/v1/config/routing", json=sample_payload)
        self.assertEqual(res_no_token.status_code, 401)

        # 3. POST with invalid token -> 403
        res_bad_token = self.client.post(
            "/api/v1/config/routing",
            headers={"Authorization": "Bearer wrong-token"},
            json=sample_payload,
        )
        self.assertEqual(res_bad_token.status_code, 403)

        # 4. POST with device token -> 403 (push token required)
        res_device_token = self.client.post(
            "/api/v1/config/routing",
            headers={"Authorization": "Bearer test-device-token-2222"},
            json=sample_payload,
        )
        self.assertEqual(res_device_token.status_code, 403)

        # 5. POST with valid push token -> 200, ok true and version
        res_post = self.client.post(
            "/api/v1/config/routing",
            headers={"Authorization": "Bearer test-push-token-1111"},
            json=sample_payload,
        )
        self.assertEqual(res_post.status_code, 200)
        post_data = res_post.json()
        self.assertTrue(post_data.get("ok"))
        self.assertEqual(post_data.get("version"), 1)

        # 6. GET with device token or push token -> stored config
        res_get = self.client.get(
            "/api/v1/config/routing",
            headers={"Authorization": "Bearer test-device-token-2222"},
        )
        self.assertEqual(res_get.status_code, 200)
        stored = res_get.json()
        self.assertEqual(stored.get("version"), 1)
        self.assertEqual(len(stored.get("muse_keywords", [])), 2)
        self.assertEqual(stored.get("muse_keywords")[0]["keyword"], "作业")

        # 7. Verify file on disk
        saved_file = routing_config_storage.config_path
        self.assertTrue(saved_file.exists())
        with open(saved_file, "r", encoding="utf-8") as f:
            disk_data = json.load(f)
        self.assertEqual(disk_data.get("version"), 1)
        self.assertEqual(len(disk_data.get("muse_keywords", [])), 2)

    def test_router_system_prompt_specifications(self) -> None:
        """Verify prompt directs fast factual/memory queries to local, and deep/action tasks to Muse."""
        # 1. Local channel capabilities and factual queries
        self.assertIn("联网搜索", ROUTER_SYSTEM_PROMPT)
        self.assertIn("长期记忆", ROUTER_SYSTEM_PROMPT)
        self.assertIn("新闻", ROUTER_SYSTEM_PROMPT)
        self.assertIn("比分", ROUTER_SYSTEM_PROMPT)
        self.assertIn("汇率", ROUTER_SYSTEM_PROMPT)
        self.assertTrue("股票价格" in ROUTER_SYSTEM_PROMPT or "股价" in ROUTER_SYSTEM_PROMPT)

        # 2. Local personal info / memory queries
        self.assertIn("偏好", ROUTER_SYSTEM_PROMPT)
        self.assertIn("习惯", ROUTER_SYSTEM_PROMPT)
        self.assertIn("家庭成员", ROUTER_SYSTEM_PROMPT)
        self.assertIn("城市", ROUTER_SYSTEM_PROMPT)
        self.assertIn("作息", ROUTER_SYSTEM_PROMPT)

        # 3. Muse channels for multi-step, deep analysis, and action tasks
        self.assertIn("多步骤", ROUTER_SYSTEM_PROMPT)
        self.assertTrue("批改与纠错" in ROUTER_SYSTEM_PROMPT or "批改" in ROUTER_SYSTEM_PROMPT)
        self.assertIn("深度分析", ROUTER_SYSTEM_PROMPT)
        self.assertIn("发送消息", ROUTER_SYSTEM_PROMPT)
        self.assertIn("打印", ROUTER_SYSTEM_PROMPT)
        self.assertIn("日程或提醒", ROUTER_SYSTEM_PROMPT)
        self.assertTrue("完整智能体" in ROUTER_SYSTEM_PROMPT or "full agent" in ROUTER_SYSTEM_PROMPT)

        # 4. Muse section should NOT direct external network search or personal memory to Muse
        muse_section_start = ROUTER_SYSTEM_PROMPT.find("路由至 muse")
        self.assertGreater(muse_section_start, 0)
        muse_section_end = ROUTER_SYSTEM_PROMPT.find("【关键词提取要求】", muse_section_start)
        muse_section_text = ROUTER_SYSTEM_PROMPT[muse_section_start:muse_section_end]
        self.assertNotIn("查询外部网络", muse_section_text)
        self.assertNotIn("需要记忆或个人信息", muse_section_text)

    async def test_time_sensitive_and_memory_queries_route_local(self) -> None:
        """Verify news, scores, exchange rates, stock prices, and memory queries route to local."""
        cases = [
            # News
            ("今天的头条新闻是什么？", ["头条新闻"], "时效性事实查询，中继桥可联网搜索"),
            # Scores
            ("昨晚湖人对勇士的比分是多少？", ["湖人", "勇士", "比分"], "比赛比分事实查询，中继桥可联网搜索"),
            # Exchange rate
            ("今天美元对人民币汇率是多少？", ["美元", "人民币", "汇率"], "汇率事实查询，中继桥可联网搜索"),
            # Stock price
            ("苹果今天的股票价格是多少？", ["苹果", "股票价格"], "股票价格事实查询，中继桥可联网搜索"),
            # Memory - preferences
            ("爸爸平时最喜欢喝什么茶？", ["爸爸", "喝茶偏好"], "家庭成员偏好查询，中继桥具备长期记忆"),
            # Memory - habits
            ("安安晚上睡觉前有什么习惯？", ["安安", "睡前习惯"], "家庭成员生活习惯查询，中继桥具备长期记忆"),
            # Memory - family
            ("我们家有哪些家庭成员？", ["家庭成员"], "家庭成员信息查询，中继桥具备长期记忆"),
            # Memory - city
            ("我们家住在哪个城市？", ["居住城市"], "家庭常住城市查询，中继桥具备长期记忆"),
            # Memory - routines
            ("我们家平时的日常作息时间是怎样的？", ["日常作息"], "日常作息规律查询，中继桥具备长期记忆"),
        ]

        for question, keywords, reason in cases:
            mock_llm_response = json.dumps({
                "keywords": keywords,
                "route": "local",
                "reason": reason,
            })
            with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
                mock_chat.return_value = mock_llm_response
                result = await route_question(question)
                self.assertEqual(result.get("route"), "local", f"Failed for question: {question}")
                self.assertEqual(result.get("keywords"), keywords)
                self.assertEqual(result.get("reason"), reason)

                # Ensure system prompt in message sent to LLM contains fast path capabilities
                call_args = mock_chat.call_args
                self.assertIsNotNone(call_args)
                messages = call_args.kwargs.get("messages") or call_args.args[0]
                self.assertEqual(messages[0]["role"], "system")
                self.assertEqual(messages[0]["content"], ROUTER_SYSTEM_PROMPT)
                self.assertIn(question, messages[1]["content"])

    async def test_complex_and_action_tasks_route_to_muse(self) -> None:
        """Verify multi-step tasks, homework correction, messaging, printing, and reminders route to Muse."""
        cases = [
            # Multi-step comprehensive task
            ("帮我调研国内夏令营并制定全家出行方案和详细预算", ["夏令营", "出行方案", "预算"], "多步骤综合规划任务，需要完整智能体深度处理"),
            # Homework correction and detailed analysis
            ("帮安安批改这道奥数题并详细分析错误原因进行讲解纠错", ["安安", "奥数题", "批改纠错"], "看板作业批改与深度辅导，需要完整智能体深度处理"),
            # Sending message
            ("帮我发一条微信消息给妈妈说我今天晚点回家", ["妈妈", "发送消息"], "执行外部发送消息操作，需要完整智能体"),
            # Printing
            ("帮我把下周的英语词汇表打印出来", ["英语词汇表", "打印"], "执行外部打印操作，需要完整智能体"),
            # Adding schedule / reminder
            ("帮我添加明天上午十点的牙医预约日程提醒", ["牙医预约", "日程提醒"], "添加日程或提醒操作，需要完整智能体"),
        ]

        for question, keywords, reason in cases:
            mock_llm_response = json.dumps({
                "keywords": keywords,
                "route": "muse",
                "reason": reason,
            })
            with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
                mock_chat.return_value = mock_llm_response
                result = await route_question(question)
                self.assertEqual(result.get("route"), "muse", f"Failed for question: {question}")
                self.assertEqual(result.get("keywords"), keywords)
                self.assertEqual(result.get("reason"), reason)

    def test_parse_router_response_various_scenarios(self) -> None:
        """Verify parse_router_response handles both local and muse outputs, including markdown fences."""
        # Local news response
        local_news = json.dumps({
            "keywords": ["科技新闻"],
            "route": "local",
            "reason": "时效性新闻查询",
        })
        parsed_local = parse_router_response(local_news)
        self.assertEqual(parsed_local["route"], "local")
        self.assertEqual(parsed_local["keywords"], ["科技新闻"])

        # Local memory response wrapped in markdown fences
        fence = chr(96) * 3
        inner_json = json.dumps({"keywords": ["爸爸", "偏好"], "route": "local", "reason": "长期记忆偏好"})
        local_memory_fenced = f"{fence}json\n{inner_json}\n{fence}"
        parsed_memory = parse_router_response(local_memory_fenced)
        self.assertEqual(parsed_memory["route"], "local")
        self.assertEqual(parsed_memory["keywords"], ["爸爸", "偏好"])

        # Muse homework correction response
        muse_homework = json.dumps({
            "keywords": ["数学作业", "批改"],
            "route": "muse",
            "reason": "作业批改与纠错",
        })
        parsed_muse = parse_router_response(muse_homework)
        self.assertEqual(parsed_muse["route"], "muse")
        self.assertEqual(parsed_muse["keywords"], ["数学作业", "批改"])

        # Muse printing action
        muse_print = json.dumps({
            "keywords": ["试卷", "打印"],
            "route": "muse",
            "reason": "执行打印操作",
        })
        parsed_print = parse_router_response(muse_print)
        self.assertEqual(parsed_print["route"], "muse")


if __name__ == "__main__":
    unittest.main()
