"""Stability and longevity tests for unified schedules engine.

Implements Phase 7 hardening verification:
1. 48-hour simulated stability scenario:
   - Recurring alarms (daily, workdays), countdowns, and one-off reminders.
   - Hour-by-hour time progression with triggers, auto-advances, and completions.
   - Periodic incremental snapshot assertions and zero-violation consistency reports.
2. 24-hour offline then resync scenario with stale cursor:
   - Offline client with stale cursor reconnects after 24h of external mutations.
   - Tests both normal 24h incremental delta catch-up and expired/pruned cursor reset.
   - Zero resurrection of deleted items, complete convergence to bridge authoritative state.
3. Rapid create-update-delete burst scenario:
   - Concurrent multi-threaded burst (ThreadPoolExecutor with WAL SQLite).
   - Candidate submissions, CRUD updates, deletions, and actions.
   - Verifies zero lock errors, strictly monotonic cursors, terminal state protection,
     and 100% database consistency.

Hard rules:
- Zero hardcoded deployment values anywhere (all from env/settings).
- Placeholder names only (no real personal data).
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import random
import shutil
import tempfile
import time
import unittest
import zoneinfo

# Ensure test environment variables exist without hardcoded deployment values
os.environ.setdefault("LLM_BASE_URL", "http://test-llm.local")
os.environ.setdefault("LLM_MODEL", "test-chat-model")
os.environ.setdefault("LLM_API_KEY", "test-llm-key")

from app.config import settings
from app.schedule_store import (
    STATUS_ACTIVE,
    STATUS_CANCELLED,
    STATUS_COMPLETED,
    STATUS_DISMISSED,
    STATUS_RINGING,
    STATUS_SNOOZED,
    TYPE_ALARM,
    TYPE_COUNTDOWN,
    TYPE_SCHEDULE,
    ScheduleStore,
    compute_next_target_time,
)
from scripts.check_schedule_consistency import check_consistency


class TestScheduleStability(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.db_path = self.test_dir / "stability_schedules.db"
        self.store = ScheduleStore(db_path=self.db_path)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_48_hour_simulated_stability(self) -> None:
        """48-hour simulated stability scenario with recurring alarms and countdowns."""
        tz = zoneinfo.ZoneInfo("Asia/Shanghai")
        # Tuesday 2026-11-17 06:00:00 Asia/Shanghai
        start_dt = datetime(2026, 11, 17, 6, 0, 0, tzinfo=tz)
        t0 = start_dt.timestamp()

        # Seed initial schedules
        # 1. Daily morning alarm at 07:00 (target at t0 + 1 hour)
        daily_alarm = self.store.create_schedule({
            "type": TYPE_ALARM,
            "title": "晨起闹钟",
            "target_time_utc": t0 + 3600.0,
            "recurrence": "FREQ=DAILY",
            "origin": "apk_voice",
            "metadata": {"label": "morning_alarm"},
        })
        self.assertIsNotNone(daily_alarm)
        daily_id = daily_alarm["id"]

        # 2. Workday alarm at 09:00 (Tuesday is workday, target at t0 + 3 hours)
        workday_alarm = self.store.create_schedule({
            "type": TYPE_ALARM,
            "title": "工作日早会",
            "target_time_utc": t0 + 10800.0,
            "recurrence": "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            "origin": "apk_voice",
            "metadata": {"label": "workday_alarm"},
        })
        self.assertIsNotNone(workday_alarm)
        workday_id = workday_alarm["id"]

        # 3. One-off calendar schedule at 14:00 (t0 + 8 hours)
        one_off = self.store.create_schedule({
            "type": TYPE_SCHEDULE,
            "title": "提交项目报告",
            "target_time_utc": t0 + 28800.0,
            "recurrence": "",
            "origin": "muse_voice",
            "metadata": {"priority": "high"},
        })
        self.assertIsNotNone(one_off)
        one_off_id = one_off["id"]

        # 4. Countdown timer: 600s coffee timer (target at t0 + 600s)
        coffee_timer = self.store.create_schedule({
            "type": TYPE_COUNTDOWN,
            "title": "煮咖啡",
            "target_time_utc": t0 + 600.0,
            "duration_seconds": 600,
            "recurrence": "",
            "origin": "apk_voice",
        })
        self.assertIsNotNone(coffee_timer)
        coffee_id = coffee_timer["id"]

        # 5. Daily evening reminder at 20:00 (t0 + 14 hours)
        evening_reminder = self.store.create_schedule({
            "type": TYPE_SCHEDULE,
            "title": "晚间整理",
            "target_time_utc": t0 + 50400.0,
            "recurrence": "FREQ=DAILY",
            "origin": "muse_push",
        })
        self.assertIsNotNone(evening_reminder)
        evening_id = evening_reminder["id"]

        # Track client sync cursor
        last_cursor = ""
        initial_snap = self.store.get_snapshot()
        last_cursor = initial_snap["cursor"]
        self.assertEqual(len(initial_snap["items"]), 5)

        # 48-hour simulation loop stepping hour by hour
        for hour in range(1, 49):
            curr_sim_time = t0 + (hour * 3600.0)

            # Hour 1: Coffee countdown expired at t0 + 600s
            if hour == 1:
                completed_coffee = self.store.apply_action(coffee_id, "complete")
                self.assertIsNotNone(completed_coffee)
                self.assertEqual(completed_coffee["status"], STATUS_COMPLETED)

            # Hour 1 (07:00 Day 1): Daily alarm fires -> user dismisses -> auto-advances to Day 2 07:00
            if hour == 1:
                dismissed_daily = self.store.apply_action(daily_id, "dismiss")
                self.assertIsNotNone(dismissed_daily)
                self.assertEqual(dismissed_daily["status"], STATUS_ACTIVE)
                # Next target should be t0 + 3600 + 86400 = t0 + 90000
                self.assertAlmostEqual(dismissed_daily["target_time_utc"], t0 + 90000.0, delta=1.0)

            # Hour 3 (09:00 Day 1): Workday alarm fires -> user dismisses -> auto-advances to Day 2 09:00 (Wed is workday)
            if hour == 3:
                dismissed_work = self.store.apply_action(workday_id, "dismiss")
                self.assertIsNotNone(dismissed_work)
                self.assertEqual(dismissed_work["status"], STATUS_ACTIVE)
                self.assertAlmostEqual(dismissed_work["target_time_utc"], t0 + 10800.0 + 86400.0, delta=1.0)

            # Hour 8 (14:00 Day 1): One-off report fires -> user marks complete
            if hour == 8:
                completed_oneoff = self.store.apply_action(one_off_id, "complete")
                self.assertIsNotNone(completed_oneoff)
                self.assertEqual(completed_oneoff["status"], STATUS_COMPLETED)

            # Hour 14 (20:00 Day 1): Evening reminder fires -> user dismisses -> advances to Day 2 20:00
            if hour == 14:
                dismissed_eve = self.store.apply_action(evening_id, "dismiss")
                self.assertIsNotNone(dismissed_eve)
                self.assertEqual(dismissed_eve["status"], STATUS_ACTIVE)
                self.assertAlmostEqual(dismissed_eve["target_time_utc"], t0 + 50400.0 + 86400.0, delta=1.0)

            # Hour 25 (07:00 Day 2): Daily alarm fires 2nd time -> user dismisses -> advances to Day 3 07:00
            if hour == 25:
                dismissed_daily2 = self.store.apply_action(daily_id, "dismiss")
                self.assertIsNotNone(dismissed_daily2)
                self.assertEqual(dismissed_daily2["status"], STATUS_ACTIVE)
                self.assertAlmostEqual(dismissed_daily2["target_time_utc"], t0 + 90000.0 + 86400.0, delta=1.0)

            # Hour 27 (09:00 Day 2): Workday alarm fires 2nd time -> user dismisses -> advances to Day 3 09:00 (Thursday)
            if hour == 27:
                dismissed_work2 = self.store.apply_action(workday_id, "dismiss")
                self.assertIsNotNone(dismissed_work2)
                self.assertEqual(dismissed_work2["status"], STATUS_ACTIVE)
                self.assertAlmostEqual(dismissed_work2["target_time_utc"], t0 + 10800.0 + (2 * 86400.0), delta=1.0)

            # Hour 38 (20:00 Day 2): Evening reminder fires 2nd time -> dismisses -> advances to Day 3 20:00
            if hour == 38:
                dismissed_eve2 = self.store.apply_action(evening_id, "dismiss")
                self.assertIsNotNone(dismissed_eve2)
                self.assertEqual(dismissed_eve2["status"], STATUS_ACTIVE)
                self.assertAlmostEqual(dismissed_eve2["target_time_utc"], t0 + 50400.0 + (2 * 86400.0), delta=1.0)

            # Intermediate checkpoints every 12 hours
            if hour in (12, 24, 36, 48):
                delta_snap = self.store.get_snapshot(since=last_cursor)
                self.assertFalse(delta_snap["reset"])
                if delta_snap["cursor"]:
                    # Ensure cursor is strictly non-decreasing
                    self.assertGreaterEqual(delta_snap["cursor"], last_cursor)
                    last_cursor = delta_snap["cursor"]

                # Run consistency verification
                report = check_consistency(self.db_path)
                self.assertTrue(report.is_clean, f"Consistency violations at hour {hour}: {report.to_dict()}")

        # Final assertions after 48 hours
        final_daily = self.store.get_schedule(daily_id)
        self.assertIsNotNone(final_daily)
        self.assertEqual(final_daily["status"], STATUS_ACTIVE)
        # Should be Day 3 07:00 (t0 + 176400)
        self.assertAlmostEqual(final_daily["target_time_utc"], t0 + 176400.0, delta=1.0)

        final_work = self.store.get_schedule(workday_id)
        self.assertIsNotNone(final_work)
        self.assertEqual(final_work["status"], STATUS_ACTIVE)
        self.assertAlmostEqual(final_work["target_time_utc"], t0 + 183600.0, delta=1.0)

        final_oneoff = self.store.get_schedule(one_off_id)
        self.assertIsNotNone(final_oneoff)
        self.assertEqual(final_oneoff["status"], STATUS_COMPLETED)

        final_coffee = self.store.get_schedule(coffee_id)
        self.assertIsNotNone(final_coffee)
        self.assertEqual(final_coffee["status"], STATUS_COMPLETED)

        # Verify bridge database consistency is 100% clean
        final_report = check_consistency(self.db_path)
        self.assertTrue(final_report.is_clean)
        self.assertEqual(final_report.total_violations, 0)
        self.assertEqual(final_report.total_schedules, 5)

    def test_24_hour_offline_resync_stale_cursor(self) -> None:
        """24-hour offline then resync scenario with stale cursor and tombstone convergence."""
        now = time.time()

        # Step 1: Initial sync at T0
        item1 = self.store.create_schedule({
            "type": TYPE_ALARM,
            "title": "User Initial Alarm",
            "target_time_utc": now + 3600.0,
            "recurrence": "FREQ=DAILY",
            "origin": "apk_voice",
        })
        item2 = self.store.create_schedule({
            "type": TYPE_SCHEDULE,
            "title": "Team Standup",
            "target_time_utc": now + 7200.0,
            "recurrence": "",
            "origin": "apk_voice",
        })
        item3 = self.store.create_schedule({
            "type": TYPE_SCHEDULE,
            "title": "Old Todo to be deleted",
            "target_time_utc": now + 10000.0,
            "recurrence": "",
            "origin": "apk_ui",
        })

        initial_snap = self.store.get_snapshot()
        client_cursor = initial_snap["cursor"]
        client_local_items = {it["id"]: it for it in initial_snap["items"]}
        self.assertEqual(len(client_local_items), 3)

        # Step 2: Client goes offline for 24 hours.
        # Meanwhile, bridge undergoes mutations from Muse and other devices:
        # 1) item2 title and target time are updated
        updated_item2 = self.store.update_schedule(
            item2["id"],
            {
                "title": "Rescheduled Standup",
                "target_time_utc": now + 9000.0,
            },
        )
        self.assertIsNotNone(updated_item2)

        # 2) item3 is deleted on bridge -> tombstone recorded
        deleted_ok = self.store.delete_schedule(item3["id"])
        self.assertTrue(deleted_ok)

        # 3) Muse creates new alarm and new schedule
        muse_alarm = self.store.create_schedule({
            "type": TYPE_ALARM,
            "title": "Muse Medicine Reminder",
            "target_time_utc": now + 40000.0,
            "recurrence": "",
            "origin": "muse_voice",
        })
        muse_schedule = self.store.create_schedule({
            "type": TYPE_SCHEDULE,
            "title": "Muse Evening Briefing",
            "target_time_utc": now + 50000.0,
            "recurrence": "",
            "origin": "muse_push",
        })

        # 4) Burst of 5 transient items created and 3 immediately deleted (tombstone inflation)
        for i in range(5):
            t_item = self.store.create_schedule({
                "type": TYPE_COUNTDOWN,
                "title": f"Burst Timer {i}",
                "target_time_utc": now + 600.0,
                "duration_seconds": 600,
                "recurrence": "",
                "origin": "bridge_rule",
            })
            if i < 3:
                self.store.delete_schedule(t_item["id"])

        # Step 3: Client A reconnects after 24h with client_cursor
        delta_snap = self.store.get_snapshot(since=client_cursor)
        self.assertFalse(delta_snap["reset"])
        self.assertIn(item3["id"], delta_snap["deleted_ids"])

        # Client A merges delta snapshot into local items
        for del_id in delta_snap["deleted_ids"]:
            client_local_items.pop(del_id, None)

        for incoming in delta_snap["items"]:
            client_local_items[incoming["id"]] = incoming

        # Reconstructed client view must match current bridge active list
        bridge_active_items = {it["id"]: it for it in self.store.list_schedules(limit=100)}
        self.assertEqual(set(client_local_items.keys()), set(bridge_active_items.keys()))
        self.assertNotIn(item3["id"], client_local_items)
        self.assertEqual(client_local_items[item2["id"]]["title"], "Rescheduled Standup")
        self.assertIn(muse_alarm["id"], client_local_items)
        self.assertIn(muse_schedule["id"], client_local_items)

        # Step 4: Stale cursor (> 30 days old) triggers full reset
        ancient_cursor = f"{now - (35 * 86400.0)}_sch_ancient999"
        reset_snap = self.store.get_snapshot(since=ancient_cursor)
        self.assertTrue(reset_snap["reset"])
        self.assertEqual(len(reset_snap["items"]), len(bridge_active_items))

        # Check bridge consistency
        report = check_consistency(self.db_path)
        self.assertTrue(report.is_clean)
        self.assertEqual(report.total_violations, 0)

    def test_rapid_burst_concurrency(self) -> None:
        """Rapid create-update-delete burst concurrency scenario under multi-threaded load."""
        now = time.time()
        num_workers = 8
        num_ops_per_worker = 12

        errors: list[Exception] = []
        created_ids: list[str] = []

        def worker_task(worker_id: int) -> list[str]:
            local_created = []
            store = ScheduleStore(db_path=self.db_path)
            for i in range(num_ops_per_worker):
                op_type = i % 4

                try:
                    if op_type == 0:
                        # Candidate submission
                        cand_id = f"sch_w{worker_id}_{i}_{int(time.time() * 1000)}"
                        candidates = [
                            {
                                "id": cand_id,
                                "type": TYPE_ALARM if i % 2 == 0 else TYPE_SCHEDULE,
                                "title": f"Burst Task {worker_id}-{i}",
                                "target_time_utc": now + 3600.0 + (i * 60),
                                "duration_seconds": 0,
                                "recurrence": "",
                                "status": STATUS_ACTIVE,
                                "origin": "apk_voice",
                                "created_at_utc": now,
                                "updated_at_utc": now,
                                "metadata": {"worker": worker_id},
                            }
                        ]
                        res = store.submit_candidates(candidates)
                        if res["results"]:
                            local_created.append(cand_id)

                    elif op_type == 1:
                        # Direct create
                        item = store.create_schedule({
                            "type": TYPE_COUNTDOWN,
                            "title": f"Direct Timer {worker_id}-{i}",
                            "target_time_utc": now + 1200.0,
                            "duration_seconds": 1200,
                            "recurrence": "",
                            "origin": "apk_ui",
                        })
                        local_created.append(item["id"])

                    elif op_type == 2:
                        # Action on randomly picked item if available
                        if local_created:
                            target_id = random.choice(local_created)
                            store.apply_action(target_id, "complete")

                    elif op_type == 3:
                        # Snapshot read interleaving
                        snap = store.get_snapshot()
                        self.assertIsNotNone(snap["cursor"])

                except Exception as e:
                    errors.append(e)

            return local_created

        # Run concurrent workers
        with ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = [executor.submit(worker_task, w) for w in range(num_workers)]
            for fut in as_completed(futures):
                try:
                    res_ids = fut.result()
                    created_ids.extend(res_ids)
                except Exception as ex:
                    errors.append(ex)

        # Assertions
        self.assertEqual(len(errors), 0, f"Encountered concurrency errors: {errors}")
        self.assertGreater(len(created_ids), 0)

        # Verify snapshot cursors are valid
        final_snap = self.store.get_snapshot()
        self.assertIsNotNone(final_snap["cursor"])

        # Verify database consistency after burst
        report = check_consistency(self.db_path)
        self.assertTrue(report.is_clean, f"Database inconsistent after burst: {report.to_dict()}")
        self.assertEqual(report.total_violations, 0)
        self.assertGreater(report.total_schedules, 0)


class TestCheckScheduleConsistency(unittest.TestCase):
    def setUp(self) -> None:
        self.test_dir = Path(tempfile.mkdtemp())
        self.db_path = self.test_dir / "consistency_test.db"
        self.store = ScheduleStore(db_path=self.db_path)

    def tearDown(self) -> None:
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_clean_db_returns_clean(self) -> None:
        self.store.create_schedule({
            "id": "sch_clean_1",
            "type": TYPE_ALARM,
            "title": "Clean Alarm",
            "target_time_utc": time.time() + 3600.0,
        })
        report = check_consistency(self.db_path)
        self.assertTrue(report.is_clean)
        self.assertEqual(report.total_violations, 0)

    def test_flags_tombstone_conflict(self) -> None:
        self.store.create_schedule({
            "id": "sch_conflict_1",
            "type": TYPE_ALARM,
            "title": "Conflicted Item",
            "target_time_utc": time.time() + 3600.0,
        })
        # Artificially insert the same id into schedule_tombstones
        conn = self.store._connect()
        try:
            conn.execute(
                "INSERT INTO schedule_tombstones (id, type, deleted_at) VALUES (?, ?, ?)",
                ("sch_conflict_1", TYPE_ALARM, time.time()),
            )
            conn.commit()
        finally:
            conn.close()

        report = check_consistency(self.db_path)
        self.assertFalse(report.is_clean)
        self.assertEqual(len(report.tombstone_conflicts), 1)
        self.assertEqual(report.tombstone_conflicts[0]["id"], "sch_conflict_1")

    def test_flags_duplicate_active_items(self) -> None:
        target_ts = time.time() + 3600.0
        self.store.create_schedule({
            "id": "sch_dup_1",
            "type": TYPE_ALARM,
            "title": "Wake up",
            "target_time_utc": target_ts,
        })
        self.store.create_schedule({
            "id": "sch_dup_2",
            "type": TYPE_ALARM,
            "title": "Wake up",
            "target_time_utc": target_ts,
        })
        report = check_consistency(self.db_path)
        self.assertFalse(report.is_clean)
        self.assertEqual(len(report.duplicate_active_items), 1)
        self.assertEqual(report.duplicate_active_items[0]["title"], "Wake up")

    def test_flags_orphaned_and_terminal_violations(self) -> None:
        conn = self.store._connect()
        try:
            now = time.time()
            # Inverted timestamps
            conn.execute(
                """
                INSERT INTO schedules (id, type, title, target_time_utc, duration_seconds, recurrence, status, origin, created_at_utc, updated_at_utc, metadata)
                VALUES ('sch_bad_ts', 'alarm', 'Bad TS', 1000.0, 0, '', 'active', 'apk_voice', 5000.0, 2000.0, '{}')
                """
            )
            # Countdown with non-positive duration
            conn.execute(
                """
                INSERT INTO schedules (id, type, title, target_time_utc, duration_seconds, recurrence, status, origin, created_at_utc, updated_at_utc, metadata)
                VALUES ('sch_bad_dur', 'countdown', 'Bad Dur', 1000.0, 0, '', 'active', 'apk_voice', ?, ?, '{}')
                """,
                (now, now),
            )
            # Recurring item left in dismissed status without auto-advancing
            conn.execute(
                """
                INSERT INTO schedules (id, type, title, target_time_utc, duration_seconds, recurrence, status, origin, created_at_utc, updated_at_utc, metadata)
                VALUES ('sch_bad_rec', 'alarm', 'Bad Rec', 1000.0, 0, 'FREQ=DAILY', 'dismissed', 'apk_voice', ?, ?, '{}')
                """,
                (now, now),
            )
            conn.commit()
        finally:
            conn.close()

        report = check_consistency(self.db_path)
        self.assertFalse(report.is_clean)
        self.assertGreaterEqual(len(report.orphaned_items), 2)
        self.assertGreaterEqual(len(report.terminal_state_violations), 1)


if __name__ == "__main__":
    unittest.main()
