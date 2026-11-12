from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient

from app.config import settings
from app.llm import llm_client
from app.main import app, get_next_ack, manager
from app.queue import ConversationQueue, conversation_queue


class TestQueueStorage(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.queue = ConversationQueue(base_dir=self.test_dir)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_queue_create_and_get(self) -> None:
        conv_id = self.queue.create(
            text="安安下周有什么安排？",
            session_id="sess-test-1",
            keywords=["安安", "安排"],
            reason="需要查询安安日程",
        )
        self.assertTrue(bool(conv_id))
        self.assertIsInstance(conv_id, str)
        self.assertNotEqual(conv_id, "pending")

        conv = self.queue.get(conv_id)
        self.assertIsNotNone(conv)
        self.assertEqual(conv["id"], conv_id)
        self.assertEqual(conv["text"], "安安下周有什么安排？")
        self.assertEqual(conv["session_id"], "sess-test-1")
        self.assertEqual(conv["keywords"], ["安安", "安排"])
        self.assertEqual(conv["reason"], "需要查询安安日程")
        self.assertEqual(conv["status"], "pending")
        self.assertEqual(conv["reply"], "")
        self.assertIn("created_at", conv)
        self.assertIn("updated_at", conv)

        # Non-existent or pending index file as id
        self.assertIsNone(self.queue.get("non_existent_id"))
        self.assertIsNone(self.queue.get("pending"))

    def test_queue_list_pending(self) -> None:
        cid1 = self.queue.create(text="问题1", session_id="s1")
        cid2 = self.queue.create(text="问题2", session_id="s2")

        pending = self.queue.list_pending()
        self.assertEqual(len(pending), 2)
        pending_ids = [c["id"] for c in pending]
        self.assertIn(cid1, pending_ids)
        self.assertIn(cid2, pending_ids)

        # Reply to cid1
        self.queue.reply(cid1, "回答1")
        updated_pending = self.queue.list_pending()
        self.assertEqual(len(updated_pending), 1)
        self.assertEqual(updated_pending[0]["id"], cid2)

    def test_queue_reply_marks_answered(self) -> None:
        conv_id = self.queue.create(text="明天几点接娃？")
        res = self.queue.reply(conv_id, "下午四点半接。")
        self.assertIsNotNone(res)
        self.assertEqual(res["status"], "answered")
        self.assertEqual(res["reply"], "下午四点半接。")

        # Confirm via get
        conv = self.queue.get(conv_id)
        self.assertIsNotNone(conv)
        self.assertEqual(conv["status"], "answered")
        self.assertEqual(conv["reply"], "下午四点半接。")

    def test_get_marks_old_pending_as_timed_out_after_120_seconds(self) -> None:
        conv_id = self.queue.create(text="超时的古老问题")
        conv_path = self.queue._conversation_path(conv_id)

        # Alter created_at to 150 seconds in the past
        past_dt = datetime.now(timezone.utc) - timedelta(seconds=150)
        with open(conv_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        data["created_at"] = past_dt.isoformat()
        with open(conv_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)

        # Calling get should detect >120s and mark timed_out
        timed_out_conv = self.queue.get(conv_id)
        self.assertIsNotNone(timed_out_conv)
        self.assertEqual(timed_out_conv["status"], "timed_out")
        self.assertEqual(timed_out_conv["reply"], "Muse 现在没回，稍后再试")

        # It should no longer appear in list_pending
        self.assertEqual(len(self.queue.list_pending()), 0)


class TestQueueAndChatEndpoints(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.orig_base_dir = conversation_queue.base_dir
        conversation_queue.base_dir = self.test_dir

        self.orig_push = settings.push_token
        self.orig_device = settings.device_token
        settings.push_token = "test-push-token-1111"
        settings.device_token = "test-device-token-2222"

        self.client = TestClient(app)

    def tearDown(self) -> None:
        conversation_queue.base_dir = self.orig_base_dir
        settings.push_token = self.orig_push
        settings.device_token = self.orig_device
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_create_conversation_endpoint(self) -> None:
        res = self.client.post(
            "/api/v1/conversations",
            headers={"Authorization": "Bearer test-device-token-2222"},
            json={
                "text": "测试手动创建会话",
                "session_id": "manual-sess",
                "keywords": ["测试"],
                "reason": "手动测试",
            },
        )
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertEqual(data.get("status"), "pending")
        conv_id = data.get("conversation_id")
        self.assertTrue(bool(conv_id))

        # Retrieve it
        res_get = self.client.get(
            f"/api/v1/conversations/{conv_id}",
            headers={"Authorization": "Bearer test-device-token-2222"},
        )
        self.assertEqual(res_get.status_code, 200)
        self.assertEqual(res_get.json().get("text"), "测试手动创建会话")

    def test_list_pending_endpoint(self) -> None:
        cid = conversation_queue.create(text="待办待处理问题", session_id="p-1")
        res = self.client.get(
            "/api/v1/conversations/pending",
            headers={"Authorization": "Bearer test-device-token-2222"},
        )
        self.assertEqual(res.status_code, 200)
        items = res.json()
        self.assertTrue(isinstance(items, list))
        self.assertTrue(any(i.get("id") == cid for i in items))

    def test_reply_endpoint_marks_answered_and_broadcasts(self) -> None:
        cid = conversation_queue.create(
            text="安安美术课材料买了吗？",
            session_id="session-art-1",
        )

        with patch.object(manager, "broadcast", new_callable=AsyncMock) as mock_broadcast:
            res = self.client.post(
                f"/api/v1/conversations/{cid}/reply",
                headers={"Authorization": "Bearer test-push-token-1111"},
                json={"reply": "已经买好了，在书包里。"},
            )
            self.assertEqual(res.status_code, 200)
            body = res.json()
            self.assertTrue(body.get("ok"))
            self.assertEqual(body.get("status"), "answered")

            mock_broadcast.assert_called_once_with({
                "type": "conversation_reply",
                "conversation_id": cid,
                "session_id": "session-art-1",
                "reply": "已经买好了，在书包里。",
            })

        # Verify get endpoint returns answered
        res_get = self.client.get(
            f"/api/v1/conversations/{cid}",
            headers={"Authorization": "Bearer test-device-token-2222"},
        )
        self.assertEqual(res_get.status_code, 200)
        conv_data = res_get.json()
        self.assertEqual(conv_data.get("status"), "answered")
        self.assertEqual(conv_data.get("reply"), "已经买好了，在书包里。")

    def test_reply_endpoint_not_found(self) -> None:
        res = self.client.post(
            "/api/v1/conversations/non_existent_id/reply",
            headers={"Authorization": "Bearer test-push-token-1111"},
            json={"reply": "answer"},
        )
        self.assertEqual(res.status_code, 404)

    def test_get_marks_old_pending_as_timed_out_via_endpoint(self) -> None:
        cid = conversation_queue.create(text="超时待办")
        conv_path = conversation_queue._conversation_path(cid)

        # Alter created_at to 200 seconds ago
        past_dt = datetime.now(timezone.utc) - timedelta(seconds=200)
        with open(conv_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        data["created_at"] = past_dt.isoformat()
        with open(conv_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)

        res = self.client.get(
            f"/api/v1/conversations/{cid}",
            headers={"Authorization": "Bearer test-device-token-2222"},
        )
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertEqual(data.get("status"), "timed_out")
        self.assertEqual(data.get("reply"), "Muse 现在没回，稍后再试")

    def test_chat_endpoint_with_mocked_router_local(self) -> None:
        with patch("app.main.route_question", new_callable=AsyncMock) as mock_route:
            mock_route.return_value = {
                "keywords": ["天气"],
                "route": "local",
                "reason": "简单常识天气",
            }
            with patch.object(llm_client, "chat", new_callable=AsyncMock) as mock_llm:
                mock_llm.return_value = "明天天气晴，适合外出。"

                res = self.client.post(
                    "/api/v1/chat",
                    headers={"Authorization": "Bearer test-device-token-2222"},
                    json={"text": "明天天气怎么样？", "session_id": "s-weather"},
                )
                self.assertEqual(res.status_code, 200)
                body = res.json()
                self.assertEqual(body.get("route"), "local")
                self.assertEqual(body.get("reply"), "明天天气晴，适合外出。")
                self.assertIsNone(body.get("conversation_id"))
                mock_llm.assert_called_once()

    def test_chat_endpoint_with_mocked_router_muse(self) -> None:
        with patch("app.main.route_question", new_callable=AsyncMock) as mock_route:
            mock_route.return_value = {
                "keywords": ["安安", "学习规划"],
                "route": "muse",
                "reason": "需要Muse深度规划",
            }
            with patch.object(llm_client, "chat", new_callable=AsyncMock) as mock_llm:
                res = self.client.post(
                    "/api/v1/chat",
                    headers={"Authorization": "Bearer test-device-token-2222"},
                    json={"text": "请帮安安规划一下下周的复习计划", "session_id": "sess-plan"},
                )
                self.assertEqual(res.status_code, 200)
                body = res.json()
                self.assertEqual(body.get("route"), "muse")
                conv_id = body.get("conversation_id")
                self.assertTrue(bool(conv_id))

                # Check ack message contains 稍等 or 问问
                reply = body.get("reply", "")
                self.assertTrue("稍等" in reply or "问问" in reply)

                # Bridge LLM chat should NOT be called for fast answer
                mock_llm.assert_not_called()

                # Verify queue has the created conversation
                conv = conversation_queue.get(conv_id)
                self.assertIsNotNone(conv)
                self.assertEqual(conv["text"], "请帮安安规划一下下周的复习计划")
                self.assertEqual(conv["session_id"], "sess-plan")
                self.assertEqual(conv["keywords"], ["安安", "学习规划"])
                self.assertEqual(conv["reason"], "需要Muse深度规划")
                self.assertEqual(conv["status"], "pending")

    def test_ack_rotation_produces_different_messages(self) -> None:
        ack1 = get_next_ack()
        ack2 = get_next_ack()
        ack3 = get_next_ack()
        ack4 = get_next_ack()

        self.assertNotEqual(ack1, ack2)
        self.assertNotEqual(ack2, ack3)
        self.assertNotEqual(ack3, ack4)

        for ack in [ack1, ack2, ack3, ack4]:
            self.assertTrue("稍等" in ack or "问问" in ack)


class TestPollerScript(unittest.TestCase):
    def test_poller_script_exists_and_is_executable(self) -> None:
        repo_root = Path(__file__).resolve().parent.parent
        script_path = repo_root / "scripts" / "poll_pending.sh"

        self.assertTrue(script_path.exists())
        self.assertTrue(os.access(script_path, os.X_OK))

    def test_poller_script_runs_single_shot(self) -> None:
        repo_root = Path(__file__).resolve().parent.parent
        script_path = repo_root / "scripts" / "poll_pending.sh"

        # Run script with ONCE=1 and non-existent port to test clean exit
        env = dict(os.environ)
        env["ONCE"] = "1"
        env["BRIDGE_URL"] = "http://127.0.0.1:59999"

        proc = subprocess.run(
            [str(script_path)],
            env=env,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(proc.returncode, 0)


if __name__ == "__main__":
    unittest.main()
