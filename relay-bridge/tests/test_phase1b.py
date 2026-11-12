import shutil
import tempfile
import unittest
from pathlib import Path
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.storage import BoardStorage, board_storage


class TestPhase1b(unittest.TestCase):
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

        self.client = TestClient(app)

    def tearDown(self) -> None:
        board_storage.base_dir = self.orig_base_dir
        board_storage.boards_dir = self.orig_boards_dir
        settings.push_token = self.orig_push
        settings.device_token = self.orig_device
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_push_board_auth(self) -> None:
        payload = {
            "data": {
                "title": "安安作业",
                "items": ["数学口算", "语文默写"]
            },
            "source": "muse",
            "updated_at": "2026-11-12T11:00:00Z"
        }
        # 1. Missing token
        res = self.client.post("/api/v1/push/anan_homework", json=payload)
        self.assertEqual(res.status_code, 401)

        # 2. Wrong token
        res = self.client.post(
            "/api/v1/push/anan_homework",
            headers={"Authorization": "Bearer wrong-token"},
            json=payload,
        )
        self.assertEqual(res.status_code, 403)

        # 3. Using device token for push should fail (only push token allowed)
        res = self.client.post(
            "/api/v1/push/anan_homework",
            headers={"Authorization": "Bearer valid-device-token-5678"},
            json=payload,
        )
        self.assertEqual(res.status_code, 403)

        # 4. Valid push token
        res = self.client.post(
            "/api/v1/push/anan_homework",
            headers={"Authorization": "Bearer valid-push-token-1234"},
            json=payload,
        )
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertTrue(body.get("ok"))
        self.assertEqual(body.get("board_id"), "anan_homework")

        # Verify persisted
        stored = board_storage.get_board("anan_homework")
        self.assertIsNotNone(stored)
        self.assertEqual(stored["data"]["title"], "安安作业")
        self.assertEqual(stored["source"], "muse")

    def test_get_board_auth_and_retrieval(self) -> None:
        board_storage.save_board(
            board_id="anan_homework",
            data={"title": "安安今日作业", "items": ["作业1", "作业2"]},
            source="muse",
        )

        # Missing token -> 401
        res = self.client.get("/api/v1/boards/anan_homework")
        self.assertEqual(res.status_code, 401)

        # Wrong token -> 403
        res = self.client.get(
            "/api/v1/boards/anan_homework",
            headers={"Authorization": "Bearer wrong-token"},
        )
        self.assertEqual(res.status_code, 403)

        # Valid DEVICE token -> 200
        res = self.client.get(
            "/api/v1/boards/anan_homework",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["board_id"], "anan_homework")
        self.assertEqual(res.json()["data"]["title"], "安安今日作业")

        # Valid PUSH token -> 200
        res = self.client.get(
            "/api/v1/boards/anan_homework",
            headers={"Authorization": "Bearer valid-push-token-1234"},
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["board_id"], "anan_homework")

        # Non-existent board -> 404
        res = self.client.get(
            "/api/v1/boards/non_existent",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 404)

    def test_list_boards(self) -> None:
        board_storage.save_board("board_a", {"title": "A"})
        board_storage.save_board("anan_homework", {"title": "安安作业"})

        # Missing token -> 401
        res = self.client.get("/api/v1/boards")
        self.assertEqual(res.status_code, 401)

        # Valid token -> 200
        res = self.client.get(
            "/api/v1/boards",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 200)
        boards = res.json()
        self.assertIn("board_a", boards)
        self.assertIn("anan_homework", boards)

    def test_dashboard_html(self) -> None:
        board_storage.save_board(
            board_id="anan_homework",
            data={
                "title": "安安今日作业",
                "date": "2026-11-12",
                "items": [
                    "语文：朗读课文第15课",
                    "数学：口算练习第22页"
                ]
            },
            source="muse",
        )

        res = self.client.get("/")
        self.assertEqual(res.status_code, 200)
        self.assertIn("text/html", res.headers.get("content-type", ""))
        html_text = res.text
        self.assertIn("Voice Kiosk 看板", html_text)
        self.assertIn("安安今日作业", html_text)
        self.assertIn("2026-11-12", html_text)
        self.assertIn("语文：朗读课文第15课", html_text)
        self.assertIn("数学：口算练习第22页", html_text)
        self.assertIn("/ws", html_text)
        self.assertIn("viewport", html_text)

    def test_websocket_endpoint(self) -> None:
        with self.client.websocket_connect("/ws") as ws:
            welcome_msg = ws.receive_json()
            self.assertEqual(welcome_msg.get("type"), "welcome")
            self.assertIn("Voice Kiosk Relay Bridge", welcome_msg.get("message", ""))

            # Test push broadcast
            push_res = self.client.post(
                "/api/v1/push/test_live",
                headers={"Authorization": "Bearer valid-push-token-1234"},
                json={"data": {"title": "Live Test"}},
            )
            self.assertEqual(push_res.status_code, 200)

            event = ws.receive_json()
            self.assertEqual(event.get("type"), "board_update")
            self.assertEqual(event.get("board_id"), "test_live")


if __name__ == "__main__":
    unittest.main()
