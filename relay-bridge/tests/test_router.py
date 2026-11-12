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


if __name__ == "__main__":
    unittest.main()
