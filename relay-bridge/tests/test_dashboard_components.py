import unittest
from typing import Any

from app.dashboard import parse_board_to_components, render_dashboard


class TestDashboardComponents(unittest.TestCase):
    def test_parse_board_with_string_items(self) -> None:
        raw_board = {
            "board_id": "test_board_1",
            "data": {
                "title": "Test Title",
                "tag": "Test Tag",
                "subtitle": "Test Subtitle",
                "items": ["Sample Item Alpha", "Sample Item Beta"],
            },
        }
        components = parse_board_to_components("test_board_1", raw_board)
        self.assertEqual(len(components), 2)

        title_comp = components[0]
        self.assertEqual(title_comp["type"], "title")
        self.assertEqual(title_comp["title"], "Test Title")
        self.assertEqual(title_comp["tag"], "Test Tag")
        self.assertEqual(title_comp["subtitle"], "Test Subtitle")
        self.assertEqual(title_comp["badge"], "0 / 2 完成")

        checklist_comp = components[1]
        self.assertEqual(checklist_comp["type"], "checklist")
        items = checklist_comp["items"]
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]["id"], "item_0")
        self.assertEqual(items[0]["text"], "Sample Item Alpha")
        self.assertFalse(items[0]["completed"])
        # Verify no estimated_time or meta field exists in checklist item
        self.assertNotIn("estimated_time", items[0])
        self.assertNotIn("meta", items[0])
        self.assertTrue(items[0]["color_hint"].startswith("#"))

    def test_parse_board_with_dict_items_and_completion(self) -> None:
        raw_board = {
            "board_id": "test_board_2",
            "data": {
                "title": "Custom Checklist",
                "items": [
                    {
                        "id": "task_1",
                        "label": "Cat A",
                        "text": "First action",
                        "completed": True,
                        "color": "#10B981",
                    },
                    {
                        "id": "task_2",
                        "label": "Cat B",
                        "text": "Second action",
                        "completed": False,
                    },
                ],
            },
        }
        components = parse_board_to_components("test_board_2", raw_board)
        title_comp = components[0]
        self.assertEqual(title_comp["badge"], "1 / 2 完成")

        checklist_comp = components[1]
        items = checklist_comp["items"]
        self.assertEqual(items[0]["id"], "task_1")
        self.assertEqual(items[0]["label"], "Cat A")
        self.assertEqual(items[0]["text"], "First action")
        self.assertTrue(items[0]["completed"])
        self.assertEqual(items[0]["color_hint"], "#10B981")

        self.assertEqual(items[1]["id"], "task_2")
        self.assertEqual(items[1]["label"], "Cat B")
        self.assertFalse(items[1]["completed"])
        self.assertNotIn("estimated_time", items[1])
        self.assertNotIn("meta", items[1])

    def test_parse_board_with_generic_list(self) -> None:
        raw_board = {
            "board_id": "test_board_list",
            "data": {
                "type": "list",
                "title": "Notice Board",
                "items": ["First notice", "Second notice"],
            },
        }
        components = parse_board_to_components("test_board_list", raw_board)
        self.assertEqual(len(components), 2)
        self.assertEqual(components[0]["type"], "title")
        self.assertEqual(components[0]["badge"], "2 项")
        self.assertEqual(components[1]["type"], "list")
        self.assertEqual(len(components[1]["items"]), 2)
        self.assertEqual(components[1]["items"][0]["text"], "First notice")

    def test_parse_board_with_key_value(self) -> None:
        raw_board = {
            "board_id": "test_board_kv",
            "data": {
                "title": "System Status",
                "status": "online",
                "version": "1.0",
                "mode": "active",
            },
        }
        components = parse_board_to_components("test_board_kv", raw_board)
        self.assertEqual(len(components), 2)
        self.assertEqual(components[0]["type"], "title")
        self.assertEqual(components[1]["type"], "key_value")
        pairs = components[1]["pairs"]
        self.assertTrue(any(p["key"] == "status" and p["value"] == "online" for p in pairs))
        self.assertTrue(any(p["key"] == "mode" and p["value"] == "active" for p in pairs))

    def test_parse_board_fallback_raw_data(self) -> None:
        # Bare list data
        components = parse_board_to_components("bare_board", ["item 1", "item 2"])
        self.assertEqual(len(components), 2)
        self.assertEqual(components[0]["title"], "bare_board")
        self.assertEqual(components[1]["type"], "checklist")

        # Bare string data
        components_str = parse_board_to_components("str_board", "Simple text announcement")
        self.assertEqual(len(components_str), 2)
        self.assertEqual(components_str[0]["title"], "str_board")
        self.assertEqual(components_str[1]["type"], "list")

    def test_render_dashboard_empty(self) -> None:
        html_out = render_dashboard([])
        self.assertIn("<!DOCTYPE html>", html_out)
        self.assertIn("#000000", html_out)
        self.assertIn("#141416", html_out)
        self.assertIn("暂无看板数据", html_out)

    def test_render_dashboard_oled_dark_styles(self) -> None:
        boards = [
            {
                "board_id": "board_1",
                "data": {
                    "title": "Alpha Board",
                    "tag": "Group 1",
                    "subtitle": "Subtitle 1",
                    "items": [
                        {"id": "item_0", "label": "Cat A", "text": "Pending item", "completed": False},
                        {"id": "item_1", "label": "Cat B", "text": "Done item", "completed": True},
                    ],
                },
                "source": "muse",
                "updated_at": "2026-11-13T10:00:00Z",
            },
            {
                "board_id": "board_2",
                "data": {
                    "type": "list",
                    "title": "Beta Board",
                    "items": ["General note"],
                },
                "source": "system",
            },
        ]
        html_out = render_dashboard(boards)

        # OLED True black and dark card colors
        self.assertIn("--bg-color: #000000", html_out)
        self.assertIn("--card-bg: #141416", html_out)
        self.assertIn("--border-color: #27272a", html_out)
        self.assertIn("--badge-bg: #2e1065", html_out)
        self.assertIn("--badge-text: #e0e7ff", html_out)

        # Strikethrough style for completed items
        self.assertIn("line-through", html_out)

        # Board 1 contents
        self.assertIn("Alpha Board", html_out)
        self.assertIn("Group 1", html_out)
        self.assertIn("Subtitle 1", html_out)
        self.assertIn("1 / 2 完成", html_out)
        self.assertIn("Pending item", html_out)
        self.assertIn("Done item", html_out)
        self.assertIn("completed", html_out)
        self.assertIn("checked", html_out)

        # Board 2 contents
        self.assertIn("Beta Board", html_out)
        self.assertIn("General note", html_out)

        # Each board has its own section
        self.assertIn('id="board-board_1"', html_out)
        self.assertIn('id="board-board_2"', html_out)


if __name__ == "__main__":
    unittest.main()
