import os
import unittest
from unittest.mock import AsyncMock, patch
import httpx

os.environ.setdefault("LLM_BASE_URL", "http://test-llm.example.local")
os.environ.setdefault("LLM_MODEL", "test-chat-model")
os.environ.setdefault("LLM_API_KEY", "test-llm-key")

from app.config import Settings, settings
from app.llm import BridgeLLMClient, build_chat_system_prompt
from app.search import (
    SEARCH_DEGRADE_INSTRUCTION,
    TavilySearchClient,
    clean_tts_text,
    extract_source_name,
    format_search_results,
    is_time_sensitive_query,
)


class TestTriggerRules(unittest.TestCase):
    def test_weather_triggers(self) -> None:
        queries = [
            "今天杭州天气怎么样",
            "明天会下雨吗",
            "今天的气温多少度",
            "台风什么时候登陆",
            "现在的空气质量如何",
            "今天北京穿衣指数是多少",
            "明天下雪吗",
            "后天是阴天还是晴天",
            "目前降水量多少",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertTrue(is_time_sensitive_query(q), f"Should trigger search: {q}")

    def test_news_triggers(self) -> None:
        queries = [
            "今天有什么最新新闻",
            "看一下头条新闻",
            "最新的热点资讯",
            "科技快讯",
            "今日要闻有哪些",
            "今天发生了什么大事",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertTrue(is_time_sensitive_query(q), f"Should trigger search: {q}")

    def test_realtime_data_triggers(self) -> None:
        queries = [
            "美元兑人民币汇率是多少",
            "今天的黄金价格",
            "现在金价多少一克",
            "今日国内油价调整了吗",
            "92号汽油多少钱一升",
            "今日股市大盘走势",
            "上证指数今天涨了吗",
            "查询苹果公司股票股价",
            "世界杯比赛比分是多少",
            "今晚欧冠最新赛程",
            "当前电影实时票房榜",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertTrue(is_time_sensitive_query(q), f"Should trigger search: {q}")

    def test_explicit_search_triggers(self) -> None:
        queries = [
            "帮我查一下西湖十景有哪些",
            "搜一下杭州特产",
            "上网查一下这个药怎么吃",
            "帮我搜一下附近的公园",
            "查一下明天高铁票还有吗",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertTrue(is_time_sensitive_query(q), f"Should trigger search: {q}")

    def test_board_skips(self) -> None:
        queries = [
            "安安今日作业有什么",
            "今天的打卡清单",
            "今日安排是什么",
            "帮我查一下今日安排",
            "看下看板上的内容",
            "有新的通知公告吗",
            "打卡完成了吗",
            "查一下安安作业",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertFalse(is_time_sensitive_query(q), f"Must NOT trigger search (board data): {q}")

    def test_smalltalk_skips(self) -> None:
        queries = [
            "你好",
            "早上好",
            "晚上好",
            "你是谁",
            "给我讲个笑话",
            "我觉得好无聊啊",
            "谢谢你",
            "再见",
            "哈哈哈哈",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertFalse(is_time_sensitive_query(q), f"Must NOT trigger search (smalltalk): {q}")

    def test_common_knowledge_and_math_skips(self) -> None:
        queries = [
            "1+1等于几",
            "25乘以4等于多少",
            "100 / 5 等于几",
            "床前明月光的下一句是什么",
            "背诵静夜思",
            "唐诗三百首第一首是什么",
            "李白是哪个朝代的诗人",
            "杜甫的代表作是什么",
            "apple用英语怎么说",
            "怎么翻译人工智能",
            "英文怎么说你好",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertFalse(is_time_sensitive_query(q), f"Must NOT trigger search (common/math): {q}")

    def test_device_control_skips(self) -> None:
        queries = [
            "调大音量",
            "调小音量",
            "把音量调到50",
            "静音",
            "停止播放音乐",
            "暂停播放",
            "继续播放",
            "屏幕亮度调高一点",
            "关闭屏幕",
            "关机",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertFalse(is_time_sensitive_query(q), f"Must NOT trigger search (device control): {q}")

    def test_memory_skips(self) -> None:
        queries = [
            "我的名字叫什么",
            "我叫什么名字",
            "我住在哪里",
            "我的生日是哪天",
            "我多大了",
            "我喜欢什么水果",
        ]
        for q in queries:
            with self.subTest(query=q):
                self.assertFalse(is_time_sensitive_query(q), f"Must NOT trigger search (memory): {q}")

    def test_empty_and_blank_queries(self) -> None:
        self.assertFalse(is_time_sensitive_query(""))
        self.assertFalse(is_time_sensitive_query("   "))
        self.assertFalse(is_time_sensitive_query(None))


class TestTTSFormatting(unittest.TestCase):
    def test_clean_tts_text_strips_urls(self) -> None:
        raw = "请查看天气预报 https://weather.com/hz 获取更多，也可访问 http://www.gov.cn/news 及 www.example.org 。"
        cleaned = clean_tts_text(raw)
        self.assertNotIn("http://", cleaned)
        self.assertNotIn("https://", cleaned)
        self.assertNotIn("www.", cleaned)
        self.assertIn("请查看天气预报", cleaned)
        self.assertIn("获取更多", cleaned)

    def test_clean_tts_text_strips_markdown(self) -> None:
        raw = "参考 [杭州气象台](https://hzqx.gov.cn/warn) 的 **降雨预警** 与 *快报*，包含 `温度代码` 和 <https://link>。"
        cleaned = clean_tts_text(raw)
        self.assertNotIn("[", cleaned)
        self.assertNotIn("]", cleaned)
        self.assertNotIn("https://", cleaned)
        self.assertNotIn("**", cleaned)
        self.assertNotIn("`", cleaned)
        self.assertNotIn("<", cleaned)
        self.assertIn("杭州气象台", cleaned)
        self.assertIn("降雨预警", cleaned)

    def test_extract_source_name(self) -> None:
        self.assertEqual(extract_source_name("杭州气象台 - 天气预报"), "杭州气象台")
        self.assertEqual(extract_source_name("都市快报 | 实时新闻"), "都市快报")
        self.assertEqual(extract_source_name("新华社 — 权威发布"), "新华社")
        self.assertEqual(extract_source_name("央视新闻_新闻频道"), "央视新闻")
        self.assertEqual(extract_source_name("", url="https://weather.cctv.com/forecast"), "weather.cctv.com")
        self.assertEqual(extract_source_name(None, url=None), "网络来源")

    def test_format_search_results_limits(self) -> None:
        long_text = "这是非常长的一段新闻摘要。" * 20  # ~280 chars
        results = [
            {"title": "杭州气象台 - 预报", "url": "https://hz.gov.cn/1", "content": long_text},
            {"title": "都市快报 | 新闻", "url": "https://dskb.com/2", "content": "今天白天多云转阴。"},
            {"title": "钱江晚报", "url": "https://qjwb.com/3", "content": "夜间气温略有下降。"},
            {"title": "第四家媒体", "url": "https://extra.com/4", "content": "这条应该被截断不展示。"},
            {"title": "第五家媒体", "url": "https://extra.com/5", "content": "这条也不展示。"},
        ]

        formatted = format_search_results(results, max_results=3)

        # Must have top 3 only
        self.assertIn("1. [杭州气象台]：", formatted)
        self.assertIn("2. [都市快报]：", formatted)
        self.assertIn("3. [钱江晚报]：", formatted)
        self.assertNotIn("第四家媒体", formatted)
        self.assertNotIn("第五家媒体", formatted)

        # No URLs present in formatted prompt
        self.assertNotIn("http", formatted)
        self.assertNotIn("hz.gov.cn", formatted)

        # Section 3.2.3 prompt headers and answer requirement lines
        self.assertIn("【联网搜索最新信息】", formatted)
        self.assertIn("【回答要求】", formatted)
        self.assertIn("严禁输出任何网址链接、英文标点或 Markdown 格式", formatted)
        self.assertIn("仅需口语化提及机构名", formatted)

        # First item length should be capped (150 chars + "...")
        first_line = formatted.split("\n")[1]
        self.assertTrue(len(first_line) < 200)
        self.assertTrue(first_line.endswith("..."))

    def test_format_search_results_empty(self) -> None:
        self.assertEqual(format_search_results([]), "")


class TestTavilySearchClient(unittest.IsolatedAsyncioTestCase):
    async def test_search_mocked_success(self) -> None:
        client = TavilySearchClient(
            api_key="mock-tavily-key",
            api_url="http://mock-search.local/search",
            timeout=2.5,
            max_results=3,
        )

        mock_data = {
            "results": [
                {
                    "title": "杭州气象台 - 天气预报",
                    "url": "https://hz.gov.cn/forecast",
                    "content": "今天白天多云转阴，最高气温 22 度。",
                },
                {
                    "title": "都市快报",
                    "url": "https://dskb.com/weather",
                    "content": "受弱冷空气影响，夜间气温将降至 14 度。",
                },
            ]
        }

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            resp = AsyncMock()
            resp.status_code = 200
            resp.raise_for_status = lambda: None
            resp.json = lambda: mock_data
            mock_post.return_value = resp

            results = await client.search("杭州天气")
            self.assertEqual(len(results), 2)
            self.assertEqual(results[0]["title"], "杭州气象台 - 天气预报")

            mock_post.assert_called_once()
            call_kwargs = mock_post.call_args[1]
            self.assertEqual(call_kwargs["json"]["query"], "杭州天气")
            self.assertEqual(call_kwargs["json"]["api_key"], "mock-tavily-key")

    async def test_search_timeout_silent_degrade(self) -> None:
        client = TavilySearchClient(
            api_key="mock-tavily-key",
            api_url="http://mock-search.local/search",
            timeout=2.5,
        )

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.side_effect = httpx.TimeoutException("Search timed out")

            # Must NOT raise exception, must silently degrade
            results = await client.search("今天天气")
            self.assertEqual(results, [])

    async def test_search_http_error_silent_degrade(self) -> None:
        client = TavilySearchClient(
            api_key="mock-tavily-key",
            api_url="http://mock-search.local/search",
        )

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            req = httpx.Request("POST", "http://mock-search.local/search")
            resp = httpx.Response(500, request=req, text="Internal Server Error")
            mock_post.side_effect = httpx.HTTPStatusError("500", request=req, response=resp)

            # Must NOT raise, returns empty list
            results = await client.search("今天天气")
            self.assertEqual(results, [])

    async def test_search_network_error_silent_degrade(self) -> None:
        client = TavilySearchClient(
            api_key="mock-tavily-key",
            api_url="http://mock-search.local/search",
        )

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.side_effect = httpx.ConnectError("Connection refused")

            results = await client.search("今天天气")
            self.assertEqual(results, [])

    async def test_search_missing_api_key_silent_degrade(self) -> None:
        client = TavilySearchClient(api_key="", api_url="http://mock-search.local/search")
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            results = await client.search("今天天气")
            self.assertEqual(results, [])
            mock_post.assert_not_called()

    async def test_search_hourly_rate_limit(self) -> None:
        client = TavilySearchClient(
            api_key="mock-key",
            api_url="http://mock-search.local/search",
            hourly_limit=2,
        )

        mock_data = {"results": [{"title": "T", "url": "U", "content": "C"}]}

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            resp = AsyncMock()
            resp.status_code = 200
            resp.raise_for_status = lambda: None
            resp.json = lambda: mock_data
            mock_post.return_value = resp

            # First two calls within limit
            r1 = await client.search("q1")
            r2 = await client.search("q2")
            self.assertEqual(len(r1), 1)
            self.assertEqual(len(r2), 1)

            # Third call exceeds limit, silently skipped
            r3 = await client.search("q3")
            self.assertEqual(r3, [])
            self.assertEqual(mock_post.call_count, 2)

    async def test_search_for_chat_weather_success(self) -> None:
        client = TavilySearchClient(
            api_key="mock-key",
            api_url="http://mock-search.local/search",
        )

        mock_data = {
            "results": [
                {
                    "title": "杭州气象台",
                    "url": "https://weather.hz.gov.cn",
                    "content": "今天白天多云转阴，最高气温 22 度。",
                }
            ]
        }

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            resp = AsyncMock()
            resp.status_code = 200
            resp.raise_for_status = lambda: None
            resp.json = lambda: mock_data
            mock_post.return_value = resp

            prompt_block = await client.search_for_chat("今天杭州天气怎么样？")
            self.assertIsNotNone(prompt_block)
            self.assertIn("【联网搜索最新信息】", prompt_block)
            self.assertIn("杭州气象台", prompt_block)
            self.assertIn("多云转阴", prompt_block)

    async def test_search_for_chat_skips_smalltalk(self) -> None:
        client = TavilySearchClient(api_key="mock-key", api_url="http://mock-search.local/search")
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            res = await client.search_for_chat("你好，你是谁？")
            self.assertIsNone(res)
            mock_post.assert_not_called()

    async def test_search_for_chat_degrade_on_failure(self) -> None:
        client = TavilySearchClient(api_key="mock-key", api_url="http://mock-search.local/search")
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.side_effect = httpx.TimeoutException("Timeout")

            res = await client.search_for_chat("今天天气怎么样？")
            self.assertIsNotNone(res)
            self.assertIn(SEARCH_DEGRADE_INSTRUCTION, res)

    async def test_search_for_chat_when_disabled(self) -> None:
        client = TavilySearchClient(api_key="mock-key", api_url="http://mock-search.local/search")
        orig_enabled = settings.search_enabled
        try:
            settings.search_enabled = False
            res = await client.search_for_chat("今天天气怎么样？")
            self.assertIsNone(res)
        finally:
            settings.search_enabled = orig_enabled


class TestConfigFailFast(unittest.TestCase):
    def test_missing_llm_base_url_raises(self) -> None:
        with patch.dict(os.environ, {"LLM_BASE_URL": "", "LLM_MODEL": "test-model"}):
            with self.assertRaises(RuntimeError) as ctx:
                Settings()
            self.assertIn("LLM_BASE_URL", str(ctx.exception))

    def test_missing_llm_model_raises(self) -> None:
        with patch.dict(os.environ, {"LLM_BASE_URL": "http://example.local", "LLM_MODEL": ""}):
            with self.assertRaises(RuntimeError) as ctx:
                Settings()
            self.assertIn("LLM_MODEL", str(ctx.exception))

    def test_valid_env_loads_settings(self) -> None:
        with patch.dict(
            os.environ,
            {
                "LLM_BASE_URL": "http://my-llm.internal:9000",
                "LLM_MODEL": "custom-chat-model",
                "SEARCH_TIMEOUT": "2.0",
                "SEARCH_MAX_RESULTS": "5",
                "SEARCH_ENABLED": "false",
            },
        ):
            s = Settings()
            self.assertEqual(s.llm_base_url, "http://my-llm.internal:9000")
            self.assertEqual(s.llm_model, "custom-chat-model")
            self.assertEqual(s.search_timeout, 2.0)
            self.assertEqual(s.search_max_results, 5)
            self.assertFalse(s.search_enabled)


class TestLLMSearchIntegration(unittest.IsolatedAsyncioTestCase):
    async def test_chat_injects_search_results_for_weather(self) -> None:
        mock_search = AsyncMock()
        mock_search.search_for_chat.return_value = "【联网搜索最新信息】\n1. [杭州气象台]：今天最高 22 度。"

        client = BridgeLLMClient(
            base_url="http://example.com",
            api_key="test-key",
            search_client=mock_search,
        )

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            resp = AsyncMock()
            resp.status_code = 200
            resp.raise_for_status = lambda: None
            resp.json = lambda: {
                "choices": [{"message": {"role": "assistant", "content": "今天最高气温22度。"}}]
            }
            mock_post.return_value = resp

            messages = [
                {"role": "system", "content": "你是智能语音助手。"},
                {"role": "user", "content": "今天杭州天气怎么样？"},
            ]
            reply = await client.chat(messages=messages)
            self.assertEqual(reply, "今天最高气温22度。")

            mock_search.search_for_chat.assert_called_once_with("今天杭州天气怎么样？")
            mock_post.assert_called_once()
            call_kwargs = mock_post.call_args[1]
            sent_messages = call_kwargs["json"]["messages"]

            # Search block must be injected into system prompt
            system_msg = sent_messages[0]["content"]
            self.assertIn("你是智能语音助手。", system_msg)
            self.assertIn("【联网搜索最新信息】", system_msg)
            self.assertIn("杭州气象台", system_msg)

    async def test_chat_injects_degrade_instruction_on_search_degrade(self) -> None:
        mock_search = AsyncMock()
        mock_search.search_for_chat.return_value = f"【说明】{SEARCH_DEGRADE_INSTRUCTION}"

        client = BridgeLLMClient(
            base_url="http://example.com",
            api_key="test-key",
            search_client=mock_search,
        )

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            resp = AsyncMock()
            resp.status_code = 200
            resp.raise_for_status = lambda: None
            resp.json = lambda: {
                "choices": [{"message": {"role": "assistant", "content": "暂未查到最新天气信息。"}}]
            }
            mock_post.return_value = resp

            messages = [
                {"role": "system", "content": "你是智能语音助手。"},
                {"role": "user", "content": "今天天气怎么样？"},
            ]
            await client.chat(messages=messages)

            mock_post.assert_called_once()
            call_kwargs = mock_post.call_args[1]
            sent_messages = call_kwargs["json"]["messages"]
            system_msg = sent_messages[0]["content"]
            self.assertIn(SEARCH_DEGRADE_INSTRUCTION, system_msg)

    async def test_chat_does_not_inject_for_smalltalk(self) -> None:
        mock_search = AsyncMock()
        mock_search.search_for_chat.return_value = None

        client = BridgeLLMClient(
            base_url="http://example.com",
            api_key="test-key",
            search_client=mock_search,
        )

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            resp = AsyncMock()
            resp.status_code = 200
            resp.raise_for_status = lambda: None
            resp.json = lambda: {
                "choices": [{"message": {"role": "assistant", "content": "你好！有什么我可以帮你的？"}}]
            }
            mock_post.return_value = resp

            messages = [
                {"role": "system", "content": "系统提示词。"},
                {"role": "user", "content": "你好"},
            ]
            await client.chat(messages=messages)

            mock_post.assert_called_once()
            sent_messages = mock_post.call_args[1]["json"]["messages"]
            self.assertEqual(sent_messages[0]["content"], "系统提示词。")

    def test_build_chat_system_prompt_with_and_without_search(self) -> None:
        p1 = build_chat_system_prompt("看板内容")
        self.assertIn("Voice Kiosk", p1)
        self.assertIn("看板内容", p1)
        self.assertNotIn("联网搜索", p1)

        p2 = build_chat_system_prompt("看板内容", "【联网搜索最新信息】\n1. [气象台]：阴天。")
        self.assertIn("看板内容", p2)
        self.assertIn("【联网搜索最新信息】", p2)
        self.assertIn("气象台", p2)


if __name__ == "__main__":
    unittest.main()
