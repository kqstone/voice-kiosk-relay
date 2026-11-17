"""Unit and concurrency tests for SQLite ScheduleStore.

Covers:
- Database initialization, schema tables, indexes, and WAL mode
- Unified ScheduleItem entity with alarm, countdown, and schedule types
- Monotonic cursor math {updated_at_utc}_{id}
- Full reset on empty, corrupted, and stale (>30 days) cursor
- Incremental delta synchronization and tombstone tracking
- 30-day tombstone pruning
- Terminal state protection (dismissed, completed, cancelled cannot be overwritten by stale active writes)
- Recurring item dismissal computes next target time and returns to active
- Candidate submission idempotency and deduplication
- Full CRUD and lifecycle actions (dismiss, snooze, complete, cancel)
- ThreadPoolExecutor concurrent writes
"""

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
import shutil
import tempfile
import time
import unittest
import zoneinfo

from app.config import settings
from app.schedule_store import (
    STATUS_ACTIVE,
    STATUS_CANCELLED,
    STATUS_COMPLETED,
    STATUS_DISMISSED,
    STATUS_RINGING,
    STATUS_SNOOZED,
    STATUS_RESULT_CREATED,
    STATUS_RESULT_DEDUPLICATED,
    STATUS_RESULT_SKIPPED_DELETED,
    STATUS_RESULT_SKIPPED_TERMINAL,
    STATUS_RESULT_UPDATED,
    TYPE_ALARM,
    TYPE_COUNTDOWN,
    TYPE_SCHEDULE,
    ScheduleStore,
    compute_next_target_time,
)


class TestScheduleStore(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.db_path = self.test_dir / "test_schedules.db"
        self.store = ScheduleStore(db_path=self.db_path)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_init_creates_tables_and_wal_mode(self) -> None:
        conn = self.store._connect()
        try:
            mode = conn.execute("PRAGMA journal_mode;").fetchone()[0]
            self.assertEqual(mode.lower(), "wal")

            tables = [
                row[0]
                for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table';"
                ).fetchall()
            ]
            self.assertIn("schedules", tables)
            self.assertIn("schedule_tombstones", tables)

            indexes = [
                row[0]
                for row in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='index';"
                ).fetchall()
            ]
            self.assertIn("idx_schedules_updated", indexes)
            self.assertIn("idx_schedules_type", indexes)
            self.assertIn("idx_schedules_status", indexes)
            self.assertIn("idx_schedules_target_time", indexes)
            self.assertIn("idx_schedule_tombstones_deleted_at", indexes)
        finally:
            conn.close()

    def test_recurrence_compute_next_target_time(self) -> None:
        tz = zoneinfo.ZoneInfo("Asia/Shanghai")
        # Tuesday 2026-11-17 07:00:00 Shanghai time
        base_dt = datetime(2026, 11, 17, 7, 0, 0, tzinfo=tz)
        base_ts = base_dt.timestamp()

        # 1. FREQ=DAILY
        next_daily = compute_next_target_time(base_ts, "FREQ=DAILY", from_utc=base_ts + 10, tz_name="Asia/Shanghai")
        self.assertIsNotNone(next_daily)
        next_daily_dt = datetime.fromtimestamp(next_daily, tz=tz)
        self.assertEqual(next_daily_dt.year, 2026)
        self.assertEqual(next_daily_dt.month, 11)
        self.assertEqual(next_daily_dt.day, 18)
        self.assertEqual(next_daily_dt.hour, 7)
        self.assertEqual(next_daily_dt.minute, 0)

        # 2. FREQ=DAILY;INTERVAL=2
        next_interval = compute_next_target_time(base_ts, "FREQ=DAILY;INTERVAL=2", from_utc=base_ts + 10, tz_name="Asia/Shanghai")
        self.assertIsNotNone(next_interval)
        next_interval_dt = datetime.fromtimestamp(next_interval, tz=tz)
        self.assertEqual(next_interval_dt.day, 19)

        # 3. FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR (Workdays)
        # 2026-11-20 is Friday 07:00:00
        fri_dt = datetime(2026, 11, 20, 7, 0, 0, tzinfo=tz)
        fri_ts = fri_dt.timestamp()
        next_workday = compute_next_target_time(fri_ts, "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", from_utc=fri_ts + 60, tz_name="Asia/Shanghai")
        self.assertIsNotNone(next_workday)
        next_workday_dt = datetime.fromtimestamp(next_workday, tz=tz)
        # Should skip Saturday and Sunday to Monday 2026-11-23 07:00:00
        self.assertEqual(next_workday_dt.weekday(), 0)  # Monday
        self.assertEqual(next_workday_dt.day, 23)
        self.assertEqual(next_workday_dt.hour, 7)

        # 4. FREQ=WEEKLY;BYDAY=SA,SU (Weekends)
        next_weekend = compute_next_target_time(base_ts, "FREQ=WEEKLY;BYDAY=SA,SU", from_utc=base_ts, tz_name="Asia/Shanghai")
        self.assertIsNotNone(next_weekend)
        next_weekend_dt = datetime.fromtimestamp(next_weekend, tz=tz)
        self.assertEqual(next_weekend_dt.weekday(), 5)  # Saturday
        self.assertEqual(next_weekend_dt.day, 21)

        # 5. Empty or invalid recurrence returns None
        self.assertIsNone(compute_next_target_time(base_ts, "", from_utc=base_ts))
        self.assertIsNone(compute_next_target_time(base_ts, "INVALID_RULE", from_utc=base_ts))

    def test_crud_single_item(self) -> None:
        created = self.store.create_schedule({
            "type": TYPE_ALARM,
            "title": "晨起闹钟",
            "target_time_utc": 1731884400.0,
            "duration_seconds": 0,
            "recurrence": "FREQ=DAILY",
            "metadata": {"label": "工作起床", "snooze_count": 0},
        })
        self.assertTrue(created["id"].startswith("sch_"))
        self.assertEqual(created["type"], TYPE_ALARM)
        self.assertEqual(created["title"], "晨起闹钟")
        self.assertEqual(created["status"], STATUS_ACTIVE)
        self.assertEqual(created["metadata"]["label"], "工作起床")

        # Get by ID
        fetched = self.store.get_schedule(created["id"])
        self.assertIsNotNone(fetched)
        self.assertEqual(fetched["id"], created["id"])

        # Update
        updated = self.store.update_schedule(created["id"], {
            "title": "更新后的晨起闹钟",
            "metadata": {"snooze_count": 1},
        })
        self.assertIsNotNone(updated)
        self.assertEqual(updated["title"], "更新后的晨起闹钟")
        self.assertEqual(updated["metadata"]["snooze_count"], 1)

        # List with filter
        alarms = self.store.list_schedules(type=TYPE_ALARM)
        self.assertEqual(len(alarms), 1)
        self.assertEqual(alarms[0]["id"], created["id"])

        countdowns = self.store.list_schedules(type=TYPE_COUNTDOWN)
        self.assertEqual(len(countdowns), 0)

        # Delete
        deleted = self.store.delete_schedule(created["id"])
        self.assertTrue(deleted)
        self.assertIsNone(self.store.get_schedule(created["id"]))

    def test_lifecycle_actions_and_recurring_dismiss(self) -> None:
        now = time.time()
        # 1. Recurring item dismissal computes next target time and returns to active
        rec_item = self.store.create_schedule({
            "id": "sch_rec_01",
            "type": TYPE_ALARM,
            "title": "每日闹钟",
            "target_time_utc": now,
            "recurrence": "FREQ=DAILY",
            "status": STATUS_RINGING,
            "metadata": {"snooze_count": 2},
        })
        dismissed_rec = self.store.apply_action("sch_rec_01", "dismiss")
        self.assertIsNotNone(dismissed_rec)
        self.assertEqual(dismissed_rec["status"], STATUS_ACTIVE)
        self.assertGreater(dismissed_rec["target_time_utc"], now)
        self.assertEqual(dismissed_rec["metadata"]["snooze_count"], 0)

        # 2. Non-recurring item dismissal enters terminal state dismissed
        one_shot = self.store.create_schedule({
            "id": "sch_oneshot_01",
            "type": TYPE_ALARM,
            "title": "单次闹钟",
            "target_time_utc": now,
            "recurrence": "",
            "status": STATUS_RINGING,
        })
        dismissed_one = self.store.apply_action("sch_oneshot_01", "dismiss")
        self.assertIsNotNone(dismissed_one)
        self.assertEqual(dismissed_one["status"], STATUS_DISMISSED)

        # 3. Snooze action
        snoozed = self.store.apply_action("sch_rec_01", "snooze", snooze_seconds=180)
        self.assertIsNotNone(snoozed)
        self.assertEqual(snoozed["status"], STATUS_SNOOZED)
        self.assertEqual(snoozed["metadata"]["snooze_count"], 1)

        # 4. Complete action
        completed = self.store.apply_action("sch_rec_01", "complete")
        self.assertIsNotNone(completed)
        self.assertEqual(completed["status"], STATUS_COMPLETED)
        self.assertIn("completed_at_utc", completed["metadata"])

        # 5. Cancel action
        cancelled = self.store.apply_action("sch_oneshot_01", "cancel")
        self.assertIsNotNone(cancelled)
        self.assertEqual(cancelled["status"], STATUS_CANCELLED)

        # 6. Invalid action raises ValueError
        with self.assertRaises(ValueError):
            self.store.apply_action("sch_rec_01", "invalid_action")

    def test_terminal_state_protection_against_stale_active_writes(self) -> None:
        # Create item and transition to terminal state 'completed'
        item = self.store.create_schedule({
            "id": "sch_term_01",
            "type": TYPE_SCHEDULE,
            "title": "接孩子放学",
            "target_time_utc": time.time(),
            "status": STATUS_COMPLETED,
        })
        self.assertEqual(item["status"], STATUS_COMPLETED)

        # A. Candidate submission with stale active status must not overwrite completed
        res = self.store.submit_candidates([
            {
                "id": "sch_term_01",
                "type": TYPE_SCHEDULE,
                "title": "接孩子放学",
                "status": STATUS_ACTIVE,
                "updated_at_utc": time.time() + 10,
            }
        ])
        self.assertFalse(res["changed"])
        self.assertEqual(res["results"][0]["status"], STATUS_RESULT_SKIPPED_TERMINAL)
        self.assertEqual(res["results"][0]["item"]["status"], STATUS_COMPLETED)

        # DB must still be completed
        cur_item = self.store.get_schedule("sch_term_01")
        self.assertEqual(cur_item["status"], STATUS_COMPLETED)

        # B. update_schedule with active status must raise ValueError
        with self.assertRaises(ValueError):
            self.store.update_schedule("sch_term_01", {"status": STATUS_ACTIVE})

        # C. update_schedule can update non-status fields while preserving terminal state
        updated = self.store.update_schedule("sch_term_01", {"title": "接孩子放学（更新备注）"})
        self.assertIsNotNone(updated)
        self.assertEqual(updated["title"], "接孩子放学（更新备注）")
        self.assertEqual(updated["status"], STATUS_COMPLETED)

    def test_cursor_monotonicity_and_incremental_fetch(self) -> None:
        # Create 3 items with distinct timestamps within retention window
        now = time.time()
        t1 = now - 300.0
        t2 = now - 200.0
        t3 = now - 100.0

        self.store.create_schedule({
            "id": "sch_01",
            "type": TYPE_ALARM,
            "title": "Item 1",
            "created_at_utc": t1,
            "updated_at_utc": t1,
        })
        self.store.create_schedule({
            "id": "sch_02",
            "type": TYPE_COUNTDOWN,
            "title": "Item 2",
            "created_at_utc": t2,
            "updated_at_utc": t2,
        })
        self.store.create_schedule({
            "id": "sch_03",
            "type": TYPE_SCHEDULE,
            "title": "Item 3",
            "created_at_utc": t3,
            "updated_at_utc": t3,
        })

        # Full snapshot
        snap0 = self.store.get_snapshot(since="")
        self.assertTrue(snap0["reset"])
        self.assertEqual(len(snap0["items"]), 3)
        self.assertEqual(snap0["cursor"], f"{t3}_sch_03")

        # Incremental fetch using cursor after Item 1
        snap1 = self.store.get_snapshot(since=f"{t1}_sch_01")
        self.assertFalse(snap1["reset"])
        self.assertEqual(len(snap1["items"]), 2)
        self.assertEqual(snap1["items"][0]["id"], "sch_02")
        self.assertEqual(snap1["items"][1]["id"], "sch_03")

        # Incremental fetch using latest cursor returns empty items
        snap_latest = self.store.get_snapshot(since=f"{t3}_sch_03")
        self.assertFalse(snap_latest["reset"])
        self.assertEqual(len(snap_latest["items"]), 0)
        self.assertEqual(len(snap_latest["deleted_ids"]), 0)
        self.assertEqual(snap_latest["cursor"], f"{t3}_sch_03")

    def test_full_reset_scenarios(self) -> None:
        self.store.create_schedule({
            "id": "sch_reset_01",
            "type": TYPE_ALARM,
            "title": "Reset Test",
            "created_at_utc": 1731800000.0,
            "updated_at_utc": 1731800000.0,
        })

        # 1. Empty cursor -> reset: True
        res_empty = self.store.get_snapshot(since="")
        self.assertTrue(res_empty["reset"])
        self.assertEqual(len(res_empty["items"]), 1)

        # 2. Corrupted cursor string -> reset: True
        res_corrupt = self.store.get_snapshot(since="corrupted-cursor-string")
        self.assertTrue(res_corrupt["reset"])
        self.assertEqual(len(res_corrupt["items"]), 1)

        # 3. Cursor older than 30 days -> reset: True
        old_ts = time.time() - (35 * 86400.0)
        res_old = self.store.get_snapshot(since=f"{old_ts}_sch_old")
        self.assertTrue(res_old["reset"])
        self.assertEqual(len(res_old["items"]), 1)

    def test_tombstone_pruning_and_incremental_deletion(self) -> None:
        t_create = time.time()
        self.store.create_schedule({
            "id": "sch_del_01",
            "type": TYPE_ALARM,
            "title": "To Delete",
            "created_at_utc": t_create,
            "updated_at_utc": t_create,
        })
        snap_before = self.store.get_snapshot(since="")
        cursor_before = snap_before["cursor"]

        # Delete item
        self.store.delete_schedule("sch_del_01")

        # Incremental snapshot reflects deleted_id
        snap_after = self.store.get_snapshot(since=cursor_before)
        self.assertFalse(snap_after["reset"])
        self.assertEqual(len(snap_after["items"]), 0)
        self.assertEqual(snap_after["deleted_ids"], ["sch_del_01"])

        # Insert old tombstone manually (> 30 days old)
        conn = self.store._connect()
        try:
            old_del_ts = time.time() - (40 * 86400.0)
            conn.execute(
                "INSERT INTO schedule_tombstones (id, type, deleted_at) VALUES (?, ?, ?)",
                ("sch_old_tomb", TYPE_ALARM, old_del_ts),
            )
            conn.commit()
        finally:
            conn.close()

        # Prune tombstones
        deleted_count = self.store.prune_tombstones(max_age_days=30)
        self.assertEqual(deleted_count, 1)

        # Recent tombstone must still exist
        conn = self.store._connect()
        try:
            recent_tomb = conn.execute(
                "SELECT id FROM schedule_tombstones WHERE id = ?", ("sch_del_01",)
            ).fetchone()
            self.assertIsNotNone(recent_tomb)
            old_tomb = conn.execute(
                "SELECT id FROM schedule_tombstones WHERE id = ?", ("sch_old_tomb",)
            ).fetchone()
            self.assertIsNone(old_tomb)
        finally:
            conn.close()

    def test_submit_candidates_idempotency_and_resubmission(self) -> None:
        candidate = {
            "id": "sch_cand_01",
            "type": TYPE_COUNTDOWN,
            "title": "煮面倒计时",
            "target_time_utc": 1731800300.0,
            "duration_seconds": 300,
            "recurrence": "",
            "status": STATUS_ACTIVE,
            "origin": "apk_voice",
            "created_at_utc": 1731800000.0,
            "updated_at_utc": 1731800000.0,
            "metadata": {"custom_tag": "kitchen"},
        }

        # 1. First submission -> created
        res1 = self.store.submit_candidates([candidate])
        self.assertTrue(res1["changed"])
        self.assertEqual(len(res1["results"]), 1)
        self.assertEqual(res1["results"][0]["status"], STATUS_RESULT_CREATED)
        self.assertEqual(res1["results"][0]["item"]["title"], "煮面倒计时")

        # 2. Resubmission of identical candidate -> deduplicated (changed: False)
        res2 = self.store.submit_candidates([candidate])
        self.assertFalse(res2["changed"])
        self.assertEqual(res2["results"][0]["status"], STATUS_RESULT_DEDUPLICATED)

        # 3. Resubmission of candidate after deletion -> skipped_deleted
        self.store.delete_schedule("sch_cand_01")
        res3 = self.store.submit_candidates([candidate])
        self.assertFalse(res3["changed"])
        self.assertEqual(res3["results"][0]["status"], STATUS_RESULT_SKIPPED_DELETED)

    def test_concurrent_writes(self) -> None:
        def worker(worker_id: int) -> None:
            for j in range(5):
                item_id = f"sch_conc_{worker_id}_{j}"
                self.store.create_schedule({
                    "id": item_id,
                    "type": TYPE_SCHEDULE,
                    "title": f"Concurrent task {worker_id}-{j}",
                    "target_time_utc": time.time(),
                })
                # Read snapshot
                self.store.get_snapshot(since="")

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(worker, i) for i in range(4)]
            for f in futures:
                f.result()

        all_items = self.store.list_schedules(limit=100)
        self.assertEqual(len(all_items), 20)
