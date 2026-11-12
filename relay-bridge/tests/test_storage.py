import shutil
import tempfile
import unittest
from pathlib import Path
from app.storage import BoardStorage


class TestBoardStorage(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.storage = BoardStorage(base_dir=self.test_dir)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_save_and_get_board(self) -> None:
        board_id = "anan_homework"
        payload = {"tasks": ["math", "chinese"], "title": "homework"}
        saved = self.storage.save_board(board_id=board_id, data=payload, source="muse")

        self.assertEqual(saved["board_id"], board_id)
        self.assertEqual(saved["data"], payload)
        self.assertEqual(saved["source"], "muse")
        self.assertIn("timestamp", saved)

        file_path = self.test_dir / "boards" / f"{board_id}.json"
        self.assertTrue(file_path.exists())

        loaded = self.storage.get_board(board_id)
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded["data"], payload)
        self.assertEqual(loaded["source"], "muse")
        self.assertIn("timestamp", loaded)

    def test_persistence_across_instances(self) -> None:
        board_id = "notices"
        self.storage.save_board(board_id=board_id, data={"text": "school notice"}, source="parent")

        # Simulate bridge restart with new storage instance pointing to same directory
        restarted_storage = BoardStorage(base_dir=self.test_dir)
        loaded = restarted_storage.get_board(board_id)
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded["data"], {"text": "school notice"})
        self.assertEqual(loaded["source"], "parent")
        self.assertIn(board_id, restarted_storage.list_boards())

    def test_delete_board(self) -> None:
        board_id = "temp_board"
        self.storage.save_board(board_id=board_id, data={"temp": True})
        self.assertTrue(self.storage.delete_board(board_id))
        self.assertIsNone(self.storage.get_board(board_id))
        self.assertFalse(self.storage.delete_board(board_id))


if __name__ == "__main__":
    unittest.main()
