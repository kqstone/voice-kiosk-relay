"""Unit and concurrency tests for SQLite long-term MemoryStore.

Covers:
- Database initialization, schema tables and indexes, WAL mode
- Zero-trust sensitive filtering before write
- Category and confirmation validation
- Content normalization and deduplication
- Single-value conflict arbitration and overwrite
- Cursor math and incremental synchronization (full snapshot, incremental delta, tombstones, reset)
- Concurrent writes via ThreadPoolExecutor
- All tests run on temporary database paths
"""

import shutil
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from app.memory_store import (
    STATUS_CREATED,
    STATUS_DEDUPLICATED,
    STATUS_REJECTED_NEEDS_CONFIRMATION,
    STATUS_REJECTED_SENSITIVE,
    STATUS_SKIPPED_CONFLICT,
    STATUS_UPDATED,
    MemoryStore,
    normalize_content,
)


class TestMemoryStore(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.db_path = self.test_dir / "test_memory.db"
        self.store = MemoryStore(db_path=self.db_path)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_init_creates_tables_and_wal_mode(self) -> None:
        # Trigger lazy init
        conn = self.store._connect()
        try:
            # Check WAL journal mode
            mode = conn.execute("PRAGMA journal_mode;").fetchone()[0]
            self.assertEqual(mode.lower(), "wal")

            # Check tables exist
            tables = [
                row[0]
                for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table';"
                ).fetchall()
            ]
            self.assertIn("memories", tables)
            self.assertIn("memory_tombstones", tables)

            # Check indexes exist
            indexes = [
                row[0]
                for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='index';"
                ).fetchall()
            ]
            self.assertIn("idx_memories_key", indexes)
            self.assertIn("idx_memories_norm", indexes)
            self.assertIn("idx_memories_updated", indexes)
            self.assertIn("idx_tombstones_deleted_at", indexes)
        finally:
            conn.close()

    def test_content_normalization(self) -> None:
        self.assertEqual(normalize_content(" 住在杭州。 "), "住在杭州")
        self.assertEqual(normalize_content("喜欢听周杰伦的歌！"), "喜欢听周杰伦的歌")
        self.assertEqual(normalize_content("通常早上7点起床，"), "通常早上7点起床")
        self.assertEqual(normalize_content("“你好”；："), "你好")

    def test_write_auto_category_success(self) -> None:
        res = self.store.submit_candidates([
            {
                "id": "c1",
                "content": "住在杭州",
                "category": "city",
                "origin": "auto",
                "key": "所在城市",
            }
        ])
        self.assertTrue(res["changed"])
        self.assertEqual(len(res["results"]), 1)
        r = res["results"][0]
        self.assertEqual(r["id"], "c1")
        self.assertEqual(r["status"], STATUS_CREATED)
        self.assertIn("item", r)
        self.assertEqual(r["item"]["key"], "所在城市")
        self.assertEqual(r["item"]["content"], "住在杭州")
        self.assertGreater(r["item"]["id"], 0)

        # Verify persisted in database
        mem = self.store.get_memory(r["item"]["id"])
        self.assertIsNotNone(mem)
        self.assertEqual(mem["content"], "住在杭州")
        self.assertEqual(mem["category"], "city")

    def test_write_confirmed_category_success(self) -> None:
        res = self.store.submit_candidates([
            {
                "id": "f1",
                "content": "女儿叫朵朵",
                "category": "family",
                "origin": "confirmed",
            }
        ])
        self.assertTrue(res["changed"])
        self.assertEqual(res["results"][0]["status"], STATUS_CREATED)
        self.assertEqual(res["results"][0]["item"]["content"], "女儿叫朵朵")

    def test_unconfirmed_non_auto_category_rejected(self) -> None:
        # family with origin="auto" must be rejected
        res = self.store.submit_candidates([
            {
                "id": "f2",
                "content": "女儿叫朵朵",
                "category": "family",
                "origin": "auto",
            }
        ])
        self.assertFalse(res["changed"])
        self.assertEqual(res["results"][0]["status"], STATUS_REJECTED_NEEDS_CONFIRMATION)
        self.assertNotIn("item", res["results"][0])
        self.assertEqual(self.store.count_memories(), 0)

        # preference with origin="auto" must be rejected
        res2 = self.store.submit_candidates([
            {
                "id": "p1",
                "content": "喜欢喝绿茶",
                "category": "preference",
                "origin": "auto",
            }
        ])
        self.assertFalse(res2["changed"])
        self.assertEqual(res2["results"][0]["status"], STATUS_REJECTED_NEEDS_CONFIRMATION)
        self.assertEqual(self.store.count_memories(), 0)

    def test_invalid_category_rejected(self) -> None:
        res = self.store.submit_candidates([
            {
                "id": "bad1",
                "content": "测试数据",
                "category": "invalid_category",
                "origin": "auto",
            }
        ])
        self.assertFalse(res["changed"])
        self.assertEqual(res["results"][0]["status"], STATUS_REJECTED_NEEDS_CONFIRMATION)
        self.assertEqual(self.store.count_memories(), 0)

    def test_sensitive_content_rejected_zero_trust(self) -> None:
        sensitive_samples = [
            ("我的手机号是13812345678", "routine"),
            ("我的密码是abc12345", "device"),
            ("我住在西湖区文三路100号", "city"),
            ("我有高血压", "preference"),
            ("我月薪两万", "routine"),
            ("在实验小学三年级二班上学", "family"),
        ]
        for content, cat in sensitive_samples:
            res = self.store.submit_candidates([
                {"id": "sens", "content": content, "category": cat, "origin": "confirmed"}
            ])
            self.assertFalse(res["changed"])
            self.assertEqual(res["results"][0]["status"], STATUS_REJECTED_SENSITIVE)
            self.assertNotIn("item", res["results"][0])

        # Verify nothing was written to the database
        self.assertEqual(self.store.count_memories(), 0)

    def test_sensitive_key_rejected(self) -> None:
        res = self.store.submit_candidates([
            {
                "id": "sens_key",
                "content": "杭州",
                "category": "city",
                "origin": "auto",
                "key": "身份证号",
            }
        ])
        self.assertFalse(res["changed"])
        self.assertEqual(res["results"][0]["status"], STATUS_REJECTED_SENSITIVE)
        self.assertEqual(self.store.count_memories(), 0)

    def test_deduplication_multivalued_content(self) -> None:
        # Submit first time
        res1 = self.store.submit_candidates([
            {
                "id": "m1",
                "content": "喜欢听周杰伦的歌",
                "category": "music",
                "origin": "auto",
            }
        ])
        self.assertTrue(res1["changed"])
        self.assertEqual(res1["results"][0]["status"], STATUS_CREATED)
        first_id = res1["results"][0]["item"]["id"]

        # Submit second time with punctuation variations
        res2 = self.store.submit_candidates([
            {
                "id": "m2",
                "content": " 喜欢听周杰伦的歌！ ",
                "category": "music",
                "origin": "auto",
            }
        ])
        self.assertFalse(res2["changed"])
        self.assertEqual(res2["results"][0]["status"], STATUS_DEDUPLICATED)
        self.assertEqual(res2["results"][0]["item"]["id"], first_id)
        self.assertEqual(self.store.count_memories(), 1)

    def test_single_value_conflict_and_overwrite(self) -> None:
        # Step 1: Initial creation of single-valued fact
        res1 = self.store.submit_candidates([
            {
                "id": "c1",
                "key": "所在城市",
                "content": "住在杭州",
                "category": "city",
                "origin": "auto",
            }
        ])
        self.assertEqual(res1["results"][0]["status"], STATUS_CREATED)
        item_id = res1["results"][0]["item"]["id"]
        self.assertEqual(self.store.count_memories(), 1)

        # Step 2: Auto submission with identical content -> deduplicated
        res_dup = self.store.submit_candidates([
            {
                "id": "c_dup",
                "key": "所在城市",
                "content": "住在杭州",
                "category": "city",
                "origin": "auto",
            }
        ])
        self.assertFalse(res_dup["changed"])
        self.assertEqual(res_dup["results"][0]["status"], STATUS_DEDUPLICATED)
        self.assertEqual(res_dup["results"][0]["item"]["id"], item_id)
        self.assertEqual(self.store.count_memories(), 1)

        # Step 3: Auto submission with conflicting new content -> skipped_conflict
        res_conflict = self.store.submit_candidates([
            {
                "id": "c2",
                "key": "所在城市",
                "content": "住在成都",
                "category": "city",
                "origin": "auto",
            }
        ])
        self.assertFalse(res_conflict["changed"])
        self.assertEqual(res_conflict["results"][0]["status"], STATUS_SKIPPED_CONFLICT)
        self.assertNotIn("item", res_conflict["results"][0])

        # DB content should still be "住在杭州"
        mem = self.store.get_memory(item_id)
        self.assertEqual(mem["content"], "住在杭州")
        self.assertEqual(self.store.count_memories(), 1)

        # Step 4: Confirmed submission with new content -> updated in place
        time.sleep(0.01)  # Ensure updated_at moves forward
        res_overwrite = self.store.submit_candidates([
            {
                "id": "c3",
                "key": "所在城市",
                "content": "住在成都",
                "category": "city",
                "origin": "confirmed",
            }
        ])
        self.assertTrue(res_overwrite["changed"])
        self.assertEqual(res_overwrite["results"][0]["status"], STATUS_UPDATED)
        updated_item = res_overwrite["results"][0]["item"]
        self.assertEqual(updated_item["id"], item_id)
        self.assertEqual(updated_item["content"], "住在成都")
        self.assertEqual(self.store.count_memories(), 1)

        # Verify DB row updated
        mem_updated = self.store.get_memory(item_id)
        self.assertEqual(mem_updated["content"], "住在成都")
        self.assertGreaterEqual(mem_updated["updated_at"], mem["updated_at"])

    def test_delete_memory_and_tombstones(self) -> None:
        status, item = self.store.submit_candidate({
            "id": "del_1",
            "content": "喜欢喝绿茶",
            "category": "preference",
            "origin": "confirmed",
        })
        self.assertEqual(status, STATUS_CREATED)
        mem_id = item["id"]

        # Delete existing item
        self.assertTrue(self.store.delete_memory(mem_id))
        self.assertIsNone(self.store.get_memory(mem_id))
        self.assertEqual(self.store.count_memories(), 0)

        # Delete non-existent item returns False
        self.assertFalse(self.store.delete_memory(mem_id))

        # Check tombstone recorded
        conn = self.store._connect()
        try:
            tomb = conn.execute(
                "SELECT * FROM memory_tombstones WHERE id = ?", (mem_id,)
            ).fetchone()
            self.assertIsNotNone(tomb)
            self.assertEqual(tomb["id"], mem_id)
        finally:
            conn.close()

    def test_cursor_math_and_snapshot_lifecycle(self) -> None:
        # 1. Empty database snapshot
        snap_empty = self.store.get_snapshot(since="")
        self.assertTrue(snap_empty["reset"])
        self.assertEqual(snap_empty["items"], [])
        self.assertEqual(snap_empty["deleted_ids"], [])
        self.assertEqual(snap_empty["cursor"], "")

        # 2. Add first item
        self.store.submit_candidate({
            "id": "item1",
            "key": "所在城市",
            "content": "住在杭州",
            "category": "city",
            "origin": "auto",
        })

        # Full snapshot
        snap_full = self.store.get_snapshot(since="")
        self.assertTrue(snap_full["reset"])
        self.assertEqual(len(snap_full["items"]), 1)
        self.assertEqual(snap_full["items"][0]["content"], "住在杭州")
        cursor1 = snap_full["cursor"]
        self.assertTrue(len(cursor1) > 0)

        # Incremental call with cursor1: nothing changed
        snap_incr1 = self.store.get_snapshot(since=cursor1)
        self.assertFalse(snap_incr1["reset"])
        self.assertEqual(snap_incr1["items"], [])
        self.assertEqual(snap_incr1["deleted_ids"], [])
        self.assertEqual(snap_incr1["cursor"], cursor1)

        # 3. Add second item
        time.sleep(0.01)
        self.store.submit_candidate({
            "id": "item2",
            "content": "喜欢听周杰伦的歌",
            "category": "music",
            "origin": "auto",
        })

        # Incremental sync from cursor1: receives item2 only
        snap_incr2 = self.store.get_snapshot(since=cursor1)
        self.assertFalse(snap_incr2["reset"])
        self.assertEqual(len(snap_incr2["items"]), 1)
        self.assertEqual(snap_incr2["items"][0]["content"], "喜欢听周杰伦的歌")
        self.assertEqual(snap_incr2["deleted_ids"], [])
        cursor2 = snap_incr2["cursor"]
        self.assertNotEqual(cursor1, cursor2)

        # 4. Delete item1
        time.sleep(0.01)
        item1_id = snap_full["items"][0]["id"]
        self.store.delete_memory(item1_id)

        # Incremental sync from cursor2: receives deletion of item1
        snap_incr3 = self.store.get_snapshot(since=cursor2)
        self.assertFalse(snap_incr3["reset"])
        self.assertEqual(snap_incr3["items"], [])
        self.assertEqual(snap_incr3["deleted_ids"], [item1_id])
        cursor3 = snap_incr3["cursor"]

        # Incremental sync from cursor3: nothing changed
        snap_incr4 = self.store.get_snapshot(since=cursor3)
        self.assertFalse(snap_incr4["reset"])
        self.assertEqual(snap_incr4["items"], [])
        self.assertEqual(snap_incr4["deleted_ids"], [])
        self.assertEqual(snap_incr4["cursor"], cursor3)

    def test_snapshot_expired_cursor_triggers_reset(self) -> None:
        self.store.submit_candidate({
            "id": "active",
            "content": "住在杭州",
            "category": "city",
            "origin": "auto",
        })
        # Cursor older than 30 days
        expired_ts = time.time() - (35 * 86400.0)
        expired_cursor = f"{expired_ts}_1"

        snap = self.store.get_snapshot(since=expired_cursor)
        self.assertTrue(snap["reset"])
        self.assertEqual(len(snap["items"]), 1)
        self.assertEqual(snap["deleted_ids"], [])

    def test_snapshot_corrupted_cursor_triggers_reset(self) -> None:
        self.store.submit_candidate({
            "id": "active",
            "content": "住在杭州",
            "category": "city",
            "origin": "auto",
        })
        snap = self.store.get_snapshot(since="corrupted-non-numeric-cursor")
        self.assertTrue(snap["reset"])
        self.assertEqual(len(snap["items"]), 1)
        self.assertEqual(snap["deleted_ids"], [])

    def test_prune_tombstones(self) -> None:
        status, item = self.store.submit_candidate({
            "id": "temp_item",
            "content": "通常七点起床",
            "category": "routine",
            "origin": "auto",
        })
        self.store.delete_memory(item["id"])

        # Prune with 0 days (remove all tombstones before now)
        pruned = self.store.prune_tombstones(max_age_days=0)
        self.assertEqual(pruned, 1)

        conn = self.store._connect()
        try:
            count = conn.execute("SELECT COUNT(*) FROM memory_tombstones").fetchone()[0]
            self.assertEqual(count, 0)
        finally:
            conn.close()

    def test_list_and_count_memories(self) -> None:
        self.store.submit_candidate({"id": "1", "content": "歌1", "category": "music", "origin": "auto"})
        self.store.submit_candidate({"id": "2", "content": "歌2", "category": "music", "origin": "auto"})
        self.store.submit_candidate({"id": "3", "content": "杭州", "category": "city", "origin": "auto"})

        self.assertEqual(self.store.count_memories(), 3)
        self.assertEqual(self.store.count_memories(category="music"), 2)
        self.assertEqual(self.store.count_memories(category="city"), 1)

        all_mems = self.store.list_memories()
        self.assertEqual(len(all_mems), 3)

        music_mems = self.store.list_memories(category="music")
        self.assertEqual(len(music_mems), 2)

    def test_concurrent_writes(self) -> None:
        # Verify multiple threads can submit candidates simultaneously in WAL mode without locking errors
        num_workers = 8
        items_per_worker = 10

        def worker_task(worker_id: int) -> None:
            # Each worker uses store to submit unique facts
            for j in range(items_per_worker):
                fact_content = f"偏好爱好_{worker_id}_{j}"
                res = self.store.submit_candidates([
                    {
                        "id": f"w_{worker_id}_{j}",
                        "content": fact_content,
                        "category": "music",
                        "origin": "auto",
                    }
                ])
                self.assertEqual(res["results"][0]["status"], STATUS_CREATED)

        with ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = [executor.submit(worker_task, i) for i in range(num_workers)]
            for f in futures:
                f.result()  # Will re-raise exceptions if any worker crashed

        expected_total = num_workers * items_per_worker
        self.assertEqual(self.store.count_memories(), expected_total)

        # Test concurrent conflict overwrite on the same key
        def overwrite_worker(worker_id: int) -> None:
            self.store.submit_candidates([
                {
                    "id": f"cw_{worker_id}",
                    "key": "共同键",
                    "content": f"城市_{worker_id}",
                    "category": "city",
                    "origin": "confirmed",
                }
            ])

        with ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = [executor.submit(overwrite_worker, i) for i in range(num_workers)]
            for f in futures:
                f.result()

        # The key "共同键" must have exactly 1 record in the database
        conn = self.store._connect()
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM memories WHERE memory_key = '共同键'"
            ).fetchone()[0]
            self.assertEqual(count, 1)
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
