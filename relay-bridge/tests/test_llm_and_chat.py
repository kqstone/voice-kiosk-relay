from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
import httpx
from fastapi.testclient import TestClient

from app.config import settings
from app.llm import (
    BridgeLLMClient,
    BridgeLLMError,
    build_chat_system_prompt,
    format_board_summary,
    llm_client,
)
from app.main import app
from app.storage import BoardStorage, board_storage


class TestLLMClient(unittest.IsolatedAsyncioTestCase):
    def test_endpoint_url_construction(self) -> None:
        client1 = BridgeLLMClient(base_url="http://example.com:8317")
        self.assertEqual(client1.endpoint_url, "http://example.com:8317/v1/chat/completions")

        client2 = BridgeLLMClient(base_url="http://example.com:8317/")
        self.assertEqual(client2.endpoint_url, "http://example.com:8317/v1/chat/completions")

        client3 = BridgeLLMClient(base_url="http://example.com:8317/v1")
        self.assertEqual(client3.endpoint_url, "http://example.com:8317/v1/chat/completions")

    async def test_chat_without_api_key(self) -> None:
        client = BridgeLLMClient(base_url="http://example.com", api_key="")
        with self.assertRaises(BridgeLLMError) as ctx:
            await client.chat(messages=[{"role": "user", "content": "hi"}])
        self.assertIn("not configured", str(ctx.exception))

    async def test_chat_mocked_success(self) -> None:
        client = BridgeLLMClient(base_url="http://example.com", api_key="secret-key")
        mock_response_data = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "Hello! I am Voice Kiosk.",
                    }
                }
            ]
        }

        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_resp = AsyncMock()
            mock_resp.status_code = 200
            mock_resp.raise_for_status = lambda: None
            mock_resp.json = lambda: mock_response_data
            mock_post.return_value = mock_resp

            result = await client.chat(
                messages=[{"role": "user", "content": "hello"}],
                temperature=0.7,
            )
            self.assertEqual(result, "Hello! I am Voice Kiosk.")

            mock_post.assert_called_once()
            call_args, call_kwargs = mock_post.call_args
            self.assertEqual(call_args[0], "http://example.com/v1/chat/completions")
            self.assertEqual(call_kwargs["json"]["model"], "gemini-3.5-flash-lite")
            self.assertEqual(call_kwargs["json"]["temperature"], 0.7)
            self.assertEqual(
                call_kwargs["headers"]["Authorization"],
                "Bearer secret-key",
            )

    async def test_chat_mocked_http_error(self) -> None:
        client = BridgeLLMClient(base_url="http://example.com", api_key="secret-key")
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            req = httpx.Request("POST", "http://example.com/v1/chat/completions")
            resp = httpx.Response(500, request=req, text="Internal Server Error")
            mock_post.side_effect = httpx.HTTPStatusError("500", request=req, response=resp)

            with self.assertRaises(BridgeLLMError) as ctx:
                await client.chat(messages=[{"role": "user", "content": "hello"}])
            self.assertIn("status 500", str(ctx.exception))

    async def test_chat_mocked_empty_choices(self) -> None:
        client = BridgeLLMClient(base_url="http://example.com", api_key="secret-key")
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_resp = AsyncMock()
            mock_resp.raise_for_status = lambda: None
            mock_resp.json = lambda: {"choices": []}
            mock_post.return_value = mock_resp

            with self.assertRaises(BridgeLLMError) as ctx:
                await client.chat(messages=[{"role": "user", "content": "hello"}])
            self.assertIn("no choices", str(ctx.exception))


class TestBoardSummaryAndPrompt(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.storage = BoardStorage(base_dir=self.test_dir)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_summary_empty(self) -> None:
        summary = format_board_summary(self.storage)
        self.assertIn("当前无看板数据", summary)

    def test_summary_with_anan_homework(self) -> None:
        self.storage.save_board(
            board_id="other_board",
            data={"title": "其他通知"},
        )
        self.storage.save_board(
            board_id="anan_homework",
            data={
                "title": "安安今日作业",
                "date": "2026-11-12",
                "items": ["数学口算", "语文背诵"],
            },
        )
        summary = format_board_summary(self.storage)
        self.assertIn("anan_homework", summary)
        self.assertIn("安安今日作业", summary)
        self.assertIn("数学口算", summary)
        self.assertIn("语文背诵", summary)
        anan_pos = summary.find("anan_homework")
        other_pos = summary.find("other_board")
        self.assertTrue(anan_pos < other_pos)

    def test_summary_with_list_data(self) -> None:
        self.storage.save_board(
            board_id="list_board",
            data=["alpha", "beta"],
        )
        summary = format_board_summary(self.storage)
        self.assertIn("list_board", summary)
        self.assertIn("alpha", summary)
        self.assertIn("beta", summary)

    def test_system_prompt_builder(self) -> None:
        prompt = build_chat_system_prompt("看板内容样例")
        self.assertIn("Voice Kiosk", prompt)
        self.assertIn("看板内容样例", prompt)


class TestChatEndpoint(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.orig_base_dir = board_storage.base_dir
        self.orig_boards_dir = board_storage.boards_dir
        board_storage.base_dir = self.test_dir
        board_storage.boards_dir = self.test_dir / "boards"
        board_storage._ensure_dirs()

        self.orig_push = settings.push_token
        self.orig_device = settings.device_token
        settings.push_token = "valid-push-token-1234"
        settings.device_token = "valid-device-token-5678"

        self.route_patcher = patch("app.main.route_question", new_callable=AsyncMock)
        self.mock_route = self.route_patcher.start()
        self.mock_route.return_value = {"keywords": [], "route": "local", "reason": "local"}

        self.client = TestClient(app)

    def tearDown(self) -> None:
        self.route_patcher.stop()
        board_storage.base_dir = self.orig_base_dir
        board_storage.boards_dir = self.orig_boards_dir
        settings.push_token = self.orig_push
        settings.device_token = self.orig_device
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_chat_without_token_returns_401(self) -> None:
        res = self.client.post("/api/v1/chat", json={"text": "你好"})
        self.assertEqual(res.status_code, 401)

    def test_chat_with_invalid_token_returns_403(self) -> None:
        res = self.client.post(
            "/api/v1/chat",
            headers={"Authorization": "Bearer bad-token"},
            json={"text": "你好"},
        )
        self.assertEqual(res.status_code, 403)

    def test_chat_with_push_token_returns_403(self) -> None:
        res = self.client.post(
            "/api/v1/chat",
            headers={"Authorization": "Bearer valid-push-token-1234"},
            json={"text": "你好"},
        )
        self.assertEqual(res.status_code, 403)

    def test_chat_empty_text_returns_400(self) -> None:
        res = self.client.post(
            "/api/v1/chat",
            headers={"Authorization": "Bearer valid-device-token-5678"},
            json={"text": "  "},
        )
        self.assertEqual(res.status_code, 400)

    def test_chat_success(self) -> None:
        with patch.object(llm_client, "chat", new_callable=AsyncMock) as mock_chat:
            mock_chat.return_value = "今天北京天气晴朗。"

            res = self.client.post(
                "/api/v1/chat",
                headers={"Authorization": "Bearer valid-device-token-5678"},
                json={"text": "今天天气怎么样？", "session_id": "sess-1"},
            )
            self.assertEqual(res.status_code, 200)
            body = res.json()
            self.assertEqual(body.get("reply"), "今天北京天气晴朗。")
            self.assertEqual(body.get("route"), "local")

            mock_chat.assert_called_once()
            call_kwargs = mock_chat.call_args[1]
            messages = call_kwargs["messages"]
            self.assertEqual(messages[0]["role"], "system")
            self.assertEqual(messages[1]["role"], "user")
            self.assertEqual(messages[1]["content"], "今天天气怎么样？")

    def test_chat_board_question_context(self) -> None:
        board_storage.save_board(
            board_id="anan_homework",
            data={
                "title": "安安今日作业",
                "items": ["练习册第5页", "朗读课文"],
            },
        )

        with patch.object(llm_client, "chat", new_callable=AsyncMock) as mock_chat:
            mock_chat.return_value = "安安今天的作业包括练习册第5页和朗读课文。"

            res = self.client.post(
                "/api/v1/chat",
                headers={"Authorization": "Bearer valid-device-token-5678"},
                json={"text": "安安今天有什么作业？"},
            )
            self.assertEqual(res.status_code, 200)
            body = res.json()
            self.assertEqual(body.get("route"), "local")
            self.assertIn("练习册第5页", body.get("reply"))

            mock_chat.assert_called_once()
            messages = mock_chat.call_args[1]["messages"]
            sys_msg = messages[0]["content"]
            self.assertIn("anan_homework", sys_msg)
            self.assertIn("练习册第5页", sys_msg)

    def test_chat_llm_failure_returns_502(self) -> None:
        with patch.object(llm_client, "chat", new_callable=AsyncMock) as mock_chat:
            mock_chat.side_effect = BridgeLLMError("Upstream connection refused")

            res = self.client.post(
                "/api/v1/chat",
                headers={"Authorization": "Bearer valid-device-token-5678"},
                json={"text": "你好"},
            )
            self.assertEqual(res.status_code, 502)
            self.assertIn("Upstream connection refused", res.json().get("detail", ""))


if __name__ == "__main__":
    unittest.main()
