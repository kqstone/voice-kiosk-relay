"""Unit tests for Phase 6 schedule tools contract and router prompt extension.

Covers:
- create_schedule: alarm, countdown, schedule entity creation, wire format validation, invalid type handling
- list_schedules: basic listing, filtering by type/status, pagination
- list_schedules with today-plus-tomorrow filtering (past/today/tomorrow/future partitioning)
- update_schedule: field updates, terminal state protection, recurring auto-advance, nonexistent handling
- delete_schedule: deletion outcome wire format, tombstone recording, nonexistent handling
- ROUTER_SYSTEM_PROMPT validation: schedule local/muse boundary and untouched factual/memory rules
- 10+ routing test cases: simple schedule questions/actions stay local, complex planning routes to Muse
"""

from __future__ import annotations

from datetime import datetime, timedelta
import json
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch
import zoneinfo

from app.config import settings
from app.router import (
    ROUTER_SYSTEM_PROMPT,
    clear_corrections,
    route_question,
)
from app.schedule_store import (
    STATUS_ACTIVE,
    STATUS_CANCELLED,
    STATUS_COMPLETED,
    STATUS_DISMISSED,
    TYPE_ALARM,
    TYPE_COUNTDOWN,
    TYPE_SCHEDULE,
    ScheduleStore,
    schedule_store,
)
from app.tools.schedule import (
    create_schedule,
    delete_schedule,
    get_today_and_tomorrow_range,
    list_schedules,
    update_schedule,
)


class TestScheduleTools(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.test_db_path = self.temp_dir / "test_schedule_tools.db"
        self.orig_db_path = schedule_store._db_path

        # Direct schedule_store to temporary test database
        schedule_store._db_path = self.test_db_path
        schedule_store._initialized = False

    def tearDown(self) -> None:
        schedule_store._db_path = self.orig_db_path
        schedule_store._initialized = False
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_create_alarm_tool(self) -> None:
        """Verify create_schedule for alarms returns correct wire format."""
        target_ts = time.time() + 3600.0
        res = create_schedule(
            type=TYPE_ALARM,
            title="测试闹钟",
            target_time_utc=target_ts,
            recurrence="FREQ=DAILY",
            metadata={"label": "起床"},
        )

        self.assertIsInstance(res, dict)
        self.assertTrue(res["id"].startswith("sch_"))
        self.assertEqual(res["type"], TYPE_ALARM)
        self.assertEqual(res["title"], "测试闹钟")
        self.assertEqual(res["target_time_utc"], target_ts)
        self.assertEqual(res["duration_seconds"], 0)
        self.assertEqual(res["recurrence"], "FREQ=DAILY")
        self.assertEqual(res["status"], STATUS_ACTIVE)
        self.assertEqual(res["origin"], "muse_push")
        self.assertIsInstance(res["created_at_utc"], float)
        self.assertIsInstance(res["updated_at_utc"], float)
        self.assertEqual(res["metadata"], {"label": "起床"})

    def test_create_countdown_tool(self) -> None:
        """Verify create_schedule for countdown computes target_time_utc from duration."""
        before = time.time()
        res = create_schedule(
            type=TYPE_COUNTDOWN,
            title="测试倒计时",
            duration_seconds=600,
        )
        after = time.time()

        self.assertEqual(res["type"], TYPE_COUNTDOWN)
        self.assertEqual(res["duration_seconds"], 600)
        self.assertGreaterEqual(res["target_time_utc"], before + 600)
        self.assertLessEqual(res["target_time_utc"], after + 600)

    def test_create_schedule_invalid_type(self) -> None:
        """Verify create_schedule raises ValueError for invalid entity type."""
        with self.assertRaises(ValueError):
            create_schedule(type="invalid_type", title="非法事项")

    def test_list_schedules_basic_and_filters(self) -> None:
        """Verify list_schedules filters by type, status, and supports pagination."""
        now = time.time()
        create_schedule(type=TYPE_ALARM, title="闹钟1", target_time_utc=now + 100)
        create_schedule(type=TYPE_ALARM, title="闹钟2", target_time_utc=now + 200)
        create_schedule(type=TYPE_SCHEDULE, title="日程1", target_time_utc=now + 300)
        c4 = create_schedule(type=TYPE_SCHEDULE, title="日程2", target_time_utc=now + 400)

        # Mark c4 as completed
        update_schedule(c4["id"], status=STATUS_COMPLETED)

        # Filter by type
        alarms = list_schedules(type=TYPE_ALARM)
        self.assertEqual(len(alarms), 2)
        for a in alarms:
            self.assertEqual(a["type"], TYPE_ALARM)

        schedules = list_schedules(type=TYPE_SCHEDULE)
        self.assertEqual(len(schedules), 2)

        # Filter by status
        active_items = list_schedules(status=STATUS_ACTIVE)
        self.assertEqual(len(active_items), 3)

        completed_items = list_schedules(status=STATUS_COMPLETED)
        self.assertEqual(len(completed_items), 1)
        self.assertEqual(completed_items[0]["id"], c4["id"])

        # Pagination
        paged = list_schedules(limit=2, offset=0)
        self.assertEqual(len(paged), 2)
        paged_next = list_schedules(limit=2, offset=2)
        self.assertEqual(len(paged_next), 2)
        self.assertNotEqual(paged[0]["id"], paged_next[0]["id"])

    def test_list_schedules_today_plus_tomorrow_filtering(self) -> None:
        """Verify today-plus-tomorrow filtering correctly includes today/tomorrow and excludes past/future."""
        tz = zoneinfo.ZoneInfo("Asia/Shanghai")
        dt_now = datetime(2026, 11, 17, 14, 0, 0, tzinfo=tz)
        ref_ts = dt_now.timestamp()

        # Compute today and tomorrow ranges
        today_start_dt = datetime(2026, 11, 17, 0, 0, 0, tzinfo=tz)
        tomorrow_dt = datetime(2026, 11, 18, 12, 0, 0, tzinfo=tz)
        yesterday_dt = datetime(2026, 11, 16, 18, 0, 0, tzinfo=tz)
        future_dt = datetime(2026, 11, 20, 9, 0, 0, tzinfo=tz)

        # 1. Past item (yesterday)
        create_schedule(
            type=TYPE_SCHEDULE,
            title="昨日已过事项",
            target_time_utc=yesterday_dt.timestamp(),
        )

        # 2. Today item
        today_item = create_schedule(
            type=TYPE_SCHEDULE,
            title="今日待办事项",
            target_time_utc=today_start_dt.timestamp() + 3600.0,
        )

        # 3. Tomorrow item
        tomorrow_item = create_schedule(
            type=TYPE_SCHEDULE,
            title="明日待办事项",
            target_time_utc=tomorrow_dt.timestamp(),
        )

        # 4. Future item (3 days later)
        create_schedule(
            type=TYPE_SCHEDULE,
            title="大后天事项",
            target_time_utc=future_dt.timestamp(),
        )

        # Verify get_today_and_tomorrow_range computation
        range_from, range_to = get_today_and_tomorrow_range(now_utc=ref_ts, tz_name="Asia/Shanghai")
        self.assertEqual(range_from, today_start_dt.timestamp())
        tomorrow_end_expected = (today_start_dt + timedelta(days=2) - timedelta(microseconds=1)).timestamp()
        self.assertAlmostEqual(range_to, tomorrow_end_expected, places=4)

        # Query using today_plus_tomorrow flag
        results = list_schedules(
            today_plus_tomorrow=True,
            now_utc=ref_ts,
            tz_name="Asia/Shanghai",
        )

        result_ids = {r["id"] for r in results}
        self.assertEqual(len(results), 2)
        self.assertIn(today_item["id"], result_ids)
        self.assertIn(tomorrow_item["id"], result_ids)

        # Explicit from_utc and to_utc window produces identical results
        explicit_results = list_schedules(
            from_utc=range_from,
            to_utc=range_to,
        )
        self.assertEqual({r["id"] for r in explicit_results}, result_ids)

    def test_update_schedule_tool(self) -> None:
        """Verify update_schedule modifies fields, enforces terminal state protection and recurring dismiss."""
        now = time.time()
        created = create_schedule(
            type=TYPE_ALARM,
            title="初始闹钟",
            target_time_utc=now + 3600,
            recurrence="FREQ=DAILY",
        )
        item_id = created["id"]

        # 1. Normal field update
        updated = update_schedule(item_id, title="修改后闹钟", duration_seconds=0)
        self.assertIsNotNone(updated)
        self.assertEqual(updated["title"], "修改后闹钟")

        # 2. Dismiss recurring item -> auto-advances to next occurrence and remains active
        dismissed = update_schedule(item_id, status=STATUS_DISMISSED)
        self.assertIsNotNone(dismissed)
        self.assertEqual(dismissed["status"], STATUS_ACTIVE)
        self.assertGreater(dismissed["target_time_utc"], now + 3600)

        # 3. Create non-recurring item, complete it, and verify terminal state protection
        single_item = create_schedule(
            type=TYPE_SCHEDULE,
            title="单次任务",
            target_time_utc=now + 1000,
            recurrence="",
        )
        update_schedule(single_item["id"], status=STATUS_COMPLETED)
        with self.assertRaises(ValueError):
            update_schedule(single_item["id"], status=STATUS_ACTIVE)

        # 4. Updating nonexistent item returns None
        self.assertIsNone(update_schedule("sch_nonexistent_id", title="无效更新"))

    def test_delete_schedule_tool(self) -> None:
        """Verify delete_schedule deletes item, records tombstone, and returns wire shape."""
        now = time.time()
        created = create_schedule(type=TYPE_ALARM, title="待删除闹钟", target_time_utc=now + 500)
        item_id = created["id"]

        snap_before = schedule_store.get_snapshot()
        cursor_before = snap_before["cursor"]

        # Delete existing item
        del_res = delete_schedule(item_id)
        self.assertEqual(del_res, {"ok": True, "id": item_id})

        # Verify item no longer exists in store
        self.assertIsNone(schedule_store.get_schedule(item_id))

        # Verify incremental snapshot reports deleted_ids
        snap_after = schedule_store.get_snapshot(since=cursor_before)
        self.assertIn(item_id, snap_after["deleted_ids"])

        # Delete nonexistent item returns ok: False
        del_missing = delete_schedule("sch_not_found")
        self.assertEqual(del_missing, {"ok": False, "id": "sch_not_found"})


class TestRouterScheduleIntegration(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        clear_corrections()

    def tearDown(self) -> None:
        clear_corrections()

    def test_router_system_prompt_specifications(self) -> None:
        """Verify ROUTER_SYSTEM_PROMPT directs simple schedule questions/actions to local,

        complex planning to Muse, and preserves existing time-sensitive factual and memory rules.
        """
        # 1. Simple schedule queries and operations in local section
        self.assertIn("明天有什么日程", ROUTER_SYSTEM_PROMPT)
        self.assertIn("定一个十点闹钟", ROUTER_SYSTEM_PROMPT)
        self.assertIn("取消闹钟", ROUTER_SYSTEM_PROMPT)

        # 2. Complex planning, rescheduling, and conditional changes in Muse section
        self.assertIn("规划我整周", ROUTER_SYSTEM_PROMPT)
        self.assertIn("围绕会议重新规划", ROUTER_SYSTEM_PROMPT)
        self.assertIn("周期性日程变更", ROUTER_SYSTEM_PROMPT)
        self.assertIn("日程或提醒", ROUTER_SYSTEM_PROMPT)

        # 3. Preserved time-sensitive factual queries
        self.assertIn("联网搜索", ROUTER_SYSTEM_PROMPT)
        self.assertIn("新闻", ROUTER_SYSTEM_PROMPT)
        self.assertIn("比分", ROUTER_SYSTEM_PROMPT)
        self.assertIn("汇率", ROUTER_SYSTEM_PROMPT)
        self.assertTrue("股票价格" in ROUTER_SYSTEM_PROMPT or "股价" in ROUTER_SYSTEM_PROMPT)

        # 4. Preserved long-term memory queries
        self.assertIn("长期记忆", ROUTER_SYSTEM_PROMPT)
        self.assertIn("偏好", ROUTER_SYSTEM_PROMPT)
        self.assertIn("习惯", ROUTER_SYSTEM_PROMPT)
        self.assertIn("家庭成员", ROUTER_SYSTEM_PROMPT)
        self.assertIn("城市", ROUTER_SYSTEM_PROMPT)
        self.assertIn("作息", ROUTER_SYSTEM_PROMPT)

        # 5. Preserved other Muse full agent actions
        self.assertIn("多步骤", ROUTER_SYSTEM_PROMPT)
        self.assertTrue("批改与纠错" in ROUTER_SYSTEM_PROMPT or "批改" in ROUTER_SYSTEM_PROMPT)
        self.assertIn("深度分析", ROUTER_SYSTEM_PROMPT)
        self.assertIn("发送消息", ROUTER_SYSTEM_PROMPT)
        self.assertIn("打印", ROUTER_SYSTEM_PROMPT)
        self.assertTrue("完整智能体" in ROUTER_SYSTEM_PROMPT or "full agent" in ROUTER_SYSTEM_PROMPT)

    async def test_ten_schedule_routing_cases(self) -> None:
        """Verify at least 10 schedule routing cases:

        - 6 Simple schedule questions/operations stay local.
        - 6 Complex multi-step planning and conditional changes route to Muse.
        """
        cases: list[tuple[str, list[str], str, str]] = [
            # Local cases (simple schedule questions and single-step alarm operations)
            (
                "明天有什么日程安排？",
                ["明天", "日程安排"],
                "local",
                "简单日程查询，中继桥已直连日程数据库并快速答复",
            ),
            (
                "查一下我今天的日程待办",
                ["今天", "日程待办"],
                "local",
                "今日待办查询，中继桥直接检索看板与日程",
            ),
            (
                "帮我定一个十点钟闹钟",
                ["十点", "闹钟"],
                "local",
                "单步明确时点闹钟设定，由设备与中继桥快速落库",
            ),
            (
                "设一个明天早上八点的起床闹钟",
                ["明天早上八点", "起床闹钟"],
                "local",
                "单步明确时点闹钟设定，快速响应",
            ),
            (
                "取消刚才设定的闹钟",
                ["取消", "闹钟"],
                "local",
                "单步取消闹钟操作，直接落库",
            ),
            (
                "把明早七点的闹钟关掉",
                ["明早七点", "关闭闹钟"],
                "local",
                "单步关闭闹钟指令，直接落库",
            ),
            # Muse cases (complex planning, rescheduling around meetings, conditional changes)
            (
                "帮我规划我整周的行程安排并给出每日重点建议",
                ["整周行程", "行程规划", "建议"],
                "muse",
                "多步骤综合规划任务，需要完整智能体深度处理",
            ),
            (
                "明天下午临时增加了一个重要会议，请帮我把所有的日程重新规划错开时间",
                ["会议", "重新规划", "错开时间"],
                "muse",
                "围绕会议重新规划全部日程，涉及多步冲突协调与推理",
            ),
            (
                "如果明天天气晴朗就提醒我早上七点去晨跑，要是下雨就改到周日去",
                ["天气条件", "晨跑", "条件提醒"],
                "muse",
                "带复杂外部条件与分支判断的周期性日程编排",
            ),
            (
                "根据我的长期作息习惯和邮件通知，为我定制一份下个月的复习与锻炼时间表",
                ["作息习惯", "邮件通知", "定制时间表"],
                "muse",
                "结合多项外部上下文与长期偏好的复杂深度规划",
            ),
            (
                "梳理我本周的待办完成情况，重新调整下周的每日任务优先级和提醒时段",
                ["待办完成情况", "调整优先级", "任务规划"],
                "muse",
                "分析复盘与下周任务深度重新排期",
            ),
            (
                "分析家庭成员的时间表，为全家制定一个周末出行和活动日程计划",
                ["家庭成员", "时间表", "周末出行计划"],
                "muse",
                "多成员综合协调与复杂活动行程规划",
            ),
        ]

        # Verify we test at least 10 cases (12 in total)
        self.assertGreaterEqual(len(cases), 10)

        for question, keywords, expected_route, reason in cases:
            mock_llm_response = json.dumps({
                "keywords": keywords,
                "route": expected_route,
                "reason": reason,
            })
            with patch("app.router.llm_client.chat", new_callable=AsyncMock) as mock_chat:
                mock_chat.return_value = mock_llm_response
                result = await route_question(question)

                self.assertEqual(
                    result.get("route"),
                    expected_route,
                    f"Routing mismatch for question: '{question}'. Expected {expected_route}, got {result.get('route')}",
                )
                self.assertEqual(result.get("keywords"), keywords)
                self.assertEqual(result.get("reason"), reason)

                # Ensure system prompt passed to LLM contains full prompt with schedule rules
                mock_chat.assert_called_once()
                call_args = mock_chat.call_args
                messages = call_args.kwargs.get("messages") or call_args.args[0]
                self.assertEqual(messages[0]["role"], "system")
                self.assertEqual(messages[0]["content"], ROUTER_SYSTEM_PROMPT)
                self.assertIn(question, messages[1]["content"])


if __name__ == "__main__":
    unittest.main()
