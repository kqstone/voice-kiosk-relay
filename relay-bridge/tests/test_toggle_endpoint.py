import shutil
import tempfile
import unittest
from pathlib import Path
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.storage import board_storage


class TestToggleEndpoint(unittest.TestCase):
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

    def test_toggle_auth(self) -> None:
        board_storage.save_board(
            board_id="board_auth_test",
            data={"title": "Test Title", "items": ["Item One", "Item Two"]},
        )

        # 1. Missing token -> 401
        res = self.client.post("/api/v1/boards/board_auth_test/items/item_0/toggle")
        self.assertEqual(res.status_code, 401)

        # 2. Wrong token -> 403
        res = self.client.post(
            "/api/v1/boards/board_auth_test/items/item_0/toggle",
            headers={"Authorization": "Bearer bad-token"},
        )
        self.assertEqual(res.status_code, 403)

        # 3. Valid device token -> 200
        res = self.client.post(
            "/api/v1/boards/board_auth_test/items/item_0/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertTrue(body.get("ok"))
        self.assertTrue(body.get("completed"))

        # 4. Valid push token -> 200 (flips back to False)
        res = self.client.post(
            "/api/v1/boards/board_auth_test/items/item_0/toggle",
            headers={"Authorization": "Bearer valid-push-token-1234"},
        )
        self.assertEqual(res.status_code, 200)
        self.assertFalse(res.json().get("completed"))

    def test_toggle_board_not_found(self) -> None:
        res = self.client.post(
            "/api/v1/boards/non_existent_board/items/item_0/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 404)

    def test_toggle_item_not_found(self) -> None:
        board_storage.save_board(
            board_id="board_item_test",
            data={"title": "Test Title", "items": ["Item One"]},
        )
        res = self.client.post(
            "/api/v1/boards/board_item_test/items/item_99/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 404)

    def test_toggle_plain_string_item_persistence_and_backward_compatibility(self) -> None:
        # Push format with plain string items
        board_storage.save_board(
            board_id="board_string_test",
            data={
                "title": "String Board",
                "items": ["First Plain Item", "Second Plain Item"],
            },
            source="muse",
        )

        # Toggle item_0
        res = self.client.post(
            "/api/v1/boards/board_string_test/items/item_0/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertEqual(data["board_id"], "board_string_test")
        self.assertEqual(data["item_id"], "item_0")
        self.assertTrue(data["completed"])

        # Check persistence via GET /api/v1/boards/{board_id}
        get_res = self.client.get(
            "/api/v1/boards/board_string_test",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(get_res.status_code, 200)
        board_data = get_res.json()["data"]
        items = board_data["items"]
        self.assertEqual(len(items), 2)

        # Item 0 was converted to a dict preserving text and completion
        self.assertIsInstance(items[0], dict)
        self.assertEqual(items[0]["text"], "First Plain Item")
        self.assertTrue(items[0]["completed"])

        # Item 1 remains backward compatible
        self.assertEqual(items[1], "Second Plain Item")

        # Toggle item_0 again -> flips back to False
        res2 = self.client.post(
            "/api/v1/boards/board_string_test/items/item_0/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertEqual(res2.status_code, 200)
        self.assertFalse(res2.json()["completed"])

        get_res2 = self.client.get(
            "/api/v1/boards/board_string_test",
            headers={"Authorization": "Bearer valid-device-token-5678"},
        )
        self.assertFalse(get_res2.json()["data"]["items"][0]["completed"])

    def test_toggle_with_explicit_completed_payload(self) -> None:
        board_storage.save_board(
            board_id="board_explicit",
            data={
                "items": [
                    {"id": "t1", "text": "Task One", "completed": False},
                ],
            },
        )

        # Set explicitly to true
        res = self.client.post(
            "/api/v1/boards/board_explicit/items/t1/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
            json={"completed": True},
        )
        self.assertEqual(res.status_code, 200)
        self.assertTrue(res.json()["completed"])

        # Calling again with completed=True keeps it True
        res = self.client.post(
            "/api/v1/boards/board_explicit/items/t1/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
            json={"completed": True},
        )
        self.assertEqual(res.status_code, 200)
        self.assertTrue(res.json()["completed"])

        # Set explicitly to false
        res = self.client.post(
            "/api/v1/boards/board_explicit/items/t1/toggle",
            headers={"Authorization": "Bearer valid-device-token-5678"},
            json={"completed": False},
        )
        self.assertEqual(res.status_code, 200)
        self.assertFalse(res.json()["completed"])

    def test_toggle_broadcasts_over_websocket(self) -> None:
        board_storage.save_board(
            board_id="board_ws_test",
            data={"items": ["Broadcast Item"]},
        )

        with self.client.websocket_connect("/ws") as ws:
            welcome = ws.receive_json()
            self.assertEqual(welcome.get("type"), "welcome")

            toggle_res = self.client.post(
                "/api/v1/boards/board_ws_test/items/item_0/toggle",
                headers={"Authorization": "Bearer valid-device-token-5678"},
            )
            self.assertEqual(toggle_res.status_code, 200)

            msg = ws.receive_json()
            self.assertEqual(msg.get("type"), "board_update")
            self.assertEqual(msg.get("board_id"), "board_ws_test")
            self.assertEqual(msg.get("action"), "item_toggle")
            self.assertEqual(msg.get("item_id"), "item_0")
            self.assertTrue(msg.get("completed"))


if __name__ == "__main__":
    unittest.main()
