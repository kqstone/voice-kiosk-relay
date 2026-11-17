"""Tests for Schedules REST API and WebSocket events.

Covers:
- Auth rejection (missing token, invalid token, device-only endpoints vs dual-auth endpoints)
- Normal CRUD (create, list with filters, get by id, update, delete)
- Lifecycle actions (dismiss, snooze, complete, cancel, invalid action)
- Idempotent candidate submission (with and without Idempotency-Key)
- Incremental fetch (snapshot delta, type filtering, cursor progression)
- WebSocket broadcasts on create, update, delete, action, and candidate sync
"""

import os
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient

# Ensure required environment variables exist without hardcoded deployment values
os.environ.setdefault("LLM_BASE_URL", "http://test-llm.local")
os.environ.setdefault("LLM_MODEL", "test-chat-model")
os.environ.setdefault("LLM_API_KEY", "test-llm-key")

from app.config import settings
from app.main import (
    app,
    manager,
    _schedule_idempotency_cache,
    _schedule_idempotency_lock,
)
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
    schedule_store,
)


class TestScheduleApi(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp())
        self.test_db_path = self.temp_dir / "test_schedules_api.db"

        # Save original settings
        self.orig_device_token = settings.device_token
        self.orig_push_token = settings.push_token
        self.orig_db_path = schedule_store._db_path

        settings.device_token = "valid-device-token-abc"
        settings.push_token = "valid-push-token-xyz"

        # Point schedule_store singleton to temporary test database
        schedule_store._db_path = self.test_db_path
        schedule_store._initialized = False

        # Clear schedule idempotency cache
        with _schedule_idempotency_lock:
            _schedule_idempotency_cache.clear()

        self.client = TestClient(app)

    def tearDown(self) -> None:
        schedule_store._db_path = self.orig_db_path
        schedule_store._initialized = False
        settings.device_token = self.orig_device_token
        settings.push_token = self.orig_push_token
        with _schedule_idempotency_lock:
            _schedule_idempotency_cache.clear()
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    # -------------------------------------------------------------------------
    # Authentication Tests
    # -------------------------------------------------------------------------

    def test_candidates_endpoint_auth(self) -> None:
        # Missing auth header -> 401
        res = self.client.post("/api/v1/schedules/candidates", json={"candidates": []})
        self.assertEqual(res.status_code, 401)

        # Invalid token -> 403
        res = self.client.post(
            "/api/v1/schedules/candidates",
            headers={"Authorization": "Bearer invalid-token"},
            json={"candidates": []},
        )
        self.assertEqual(res.status_code, 403)

        # Push token rejected on device-token-only candidates endpoint -> 403
        res = self.client.post(
            "/api/v1/schedules/candidates",
            headers={"Authorization": "Bearer valid-push-token-xyz"},
            json={"candidates": []},
        )
        self.assertEqual(res.status_code, 403)

        # Valid device token -> 200
        res = self.client.post(
            "/api/v1/schedules/candidates",
            headers={"Authorization": "Bearer valid-device-token-abc"},
            json={"candidates": []},
        )
        self.assertEqual(res.status_code, 200)

    def test_snapshot_endpoint_auth(self) -> None:
        # Missing auth -> 401
        res = self.client.get("/api/v1/schedules/snapshot")
        self.assertEqual(res.status_code, 401)

        # Invalid token -> 403
        res = self.client.get(
            "/api/v1/schedules/snapshot",
            headers={"Authorization": "Bearer wrong-token"},
        )
        self.assertEqual(res.status_code, 403)

        # Device token accepted -> 200
        res = self.client.get(
            "/api/v1/schedules/snapshot",
            headers={"Authorization": "Bearer valid-device-token-abc"},
        )
        self.assertEqual(res.status_code, 200)

        # Push token accepted -> 200
        res = self.client.get(
            "/api/v1/schedules/snapshot",
            headers={"Authorization": "Bearer valid-push-token-xyz"},
        )
        self.assertEqual(res.status_code, 200)

    def test_crud_endpoints_auth(self) -> None:
        # Missing auth on POST /api/v1/schedules -> 401
        res = self.client.post("/api/v1/schedules", json={"type": "alarm", "title": "Test"})
        self.assertEqual(res.status_code, 401)

        # Missing auth on GET /api/v1/schedules -> 401
        res = self.client.get("/api/v1/schedules")
        self.assertEqual(res.status_code, 401)

        # Missing auth on GET /api/v1/schedules/{id} -> 401
        res = self.client.get("/api/v1/schedules/sch_test_01")
        self.assertEqual(res.status_code, 401)

        # Missing auth on PUT /api/v1/schedules/{id} -> 401
        res = self.client.put("/api/v1/schedules/sch_test_01", json={"title": "Updated"})
        self.assertEqual(res.status_code, 401)

        # Missing auth on DELETE /api/v1/schedules/{id} -> 401
        res = self.client.delete("/api/v1/schedules/sch_test_01")
        self.assertEqual(res.status_code, 401)

        # Missing auth on POST /api/v1/schedules/{id}/action -> 401
        res = self.client.post("/api/v1/schedules/sch_test_01/action", json={"action": "dismiss"})
        self.assertEqual(res.status_code, 401)

    # -------------------------------------------------------------------------
    # Normal CRUD and Lifecycle Action Tests
    # -------------------------------------------------------------------------

    @patch.object(manager, "broadcast", new_callable=AsyncMock)
    def test_crud_lifecycle_and_websocket_broadcasts(self, mock_broadcast: AsyncMock) -> None:
        headers = {"Authorization": "Bearer valid-push-token-xyz"}

        # 1. Create schedule item (POST /api/v1/schedules) -> 201 Created
        now = time.time()
        create_payload = {
            "type": TYPE_ALARM,
            "title": "晨起闹钟",
            "target_time_utc": now + 3600,
            "duration_seconds": 0,
            "recurrence": "FREQ=DAILY",
            "metadata": {"label": "起床", "snooze_count": 0},
        }
        res_create = self.client.post("/api/v1/schedules", headers=headers, json=create_payload)
        self.assertEqual(res_create.status_code, 201)
        item = res_create.json()
        item_id = item["id"]
        self.assertTrue(item_id.startswith("sch_"))
        self.assertEqual(item["type"], TYPE_ALARM)
        self.assertEqual(item["title"], "晨起闹钟")
        self.assertEqual(item["status"], STATUS_ACTIVE)

        # Verify WebSocket broadcast for create
        mock_broadcast.assert_called()
        self.assertEqual(mock_broadcast.call_args[0][0]["type"], "schedule_update")
        self.assertEqual(mock_broadcast.call_args[0][0]["action"], "upsert")
        self.assertEqual(mock_broadcast.call_args[0][0]["item_id"], item_id)

        # 2. Get schedule by ID (GET /api/v1/schedules/{id})
        res_get = self.client.get(f"/api/v1/schedules/{item_id}", headers=headers)
        self.assertEqual(res_get.status_code, 200)
        self.assertEqual(res_get.json()["id"], item_id)

        # Get nonexistent -> 404
        res_not_found = self.client.get("/api/v1/schedules/sch_nonexistent", headers=headers)
        self.assertEqual(res_not_found.status_code, 404)

        # 3. List schedules with filter (GET /api/v1/schedules)
        res_list = self.client.get("/api/v1/schedules?type=alarm&status=active", headers=headers)
        self.assertEqual(res_list.status_code, 200)
        self.assertEqual(len(res_list.json()), 1)
        self.assertEqual(res_list.json()[0]["id"], item_id)

        res_list_empty = self.client.get("/api/v1/schedules?type=countdown", headers=headers)
        self.assertEqual(res_list_empty.status_code, 200)
        self.assertEqual(len(res_list_empty.json()), 0)

        # 4. Update schedule (PUT /api/v1/schedules/{id})
        mock_broadcast.reset_mock()
        res_put = self.client.put(
            f"/api/v1/schedules/{item_id}",
            headers=headers,
            json={"title": "晨起闹钟（更新）"},
        )
        self.assertEqual(res_put.status_code, 200)
        self.assertEqual(res_put.json()["title"], "晨起闹钟（更新）")

        # Verify WebSocket broadcast for update
        mock_broadcast.assert_called()
        self.assertEqual(mock_broadcast.call_args[0][0]["type"], "schedule_update")
        self.assertEqual(mock_broadcast.call_args[0][0]["action"], "upsert")
        self.assertEqual(mock_broadcast.call_args[0][0]["item_id"], item_id)

        # 5. Lifecycle Actions (POST /api/v1/schedules/{id}/action)
        # Snooze action
        res_snooze = self.client.post(
            f"/api/v1/schedules/{item_id}/action",
            headers=headers,
            json={"action": "snooze", "snooze_seconds": 300},
        )
        self.assertEqual(res_snooze.status_code, 200)
        self.assertEqual(res_snooze.json()["status"], STATUS_SNOOZED)
        self.assertEqual(res_snooze.json()["metadata"]["snooze_count"], 1)

        # Dismiss recurring item -> computes next target time, returns to active
        res_dismiss = self.client.post(
            f"/api/v1/schedules/{item_id}/action",
            headers=headers,
            json={"action": "dismiss"},
        )
        self.assertEqual(res_dismiss.status_code, 200)
        self.assertEqual(res_dismiss.json()["status"], STATUS_ACTIVE)
        self.assertEqual(res_dismiss.json()["metadata"]["snooze_count"], 0)

        # Complete action
        res_complete = self.client.post(
            f"/api/v1/schedules/{item_id}/action",
            headers=headers,
            json={"action": "complete"},
        )
        self.assertEqual(res_complete.status_code, 200)
        self.assertEqual(res_complete.json()["status"], STATUS_COMPLETED)

        # Cannot overwrite terminal completed state with active via PUT
        res_illegal = self.client.put(
            f"/api/v1/schedules/{item_id}",
            headers=headers,
            json={"status": STATUS_ACTIVE},
        )
        self.assertEqual(res_illegal.status_code, 400)

        # Cancel action
        res_cancel = self.client.post(
            f"/api/v1/schedules/{item_id}/action",
            headers=headers,
            json={"action": "cancel"},
        )
        self.assertEqual(res_cancel.status_code, 200)
        self.assertEqual(res_cancel.json()["status"], STATUS_CANCELLED)

        # Invalid action -> 400
        res_bad_action = self.client.post(
            f"/api/v1/schedules/{item_id}/action",
            headers=headers,
            json={"action": "unknown_action"},
        )
        self.assertEqual(res_bad_action.status_code, 400)

        # 6. Delete schedule (DELETE /api/v1/schedules/{id})
        mock_broadcast.reset_mock()
        res_delete = self.client.delete(f"/api/v1/schedules/{item_id}", headers=headers)
        self.assertEqual(res_delete.status_code, 200)
        self.assertEqual(res_delete.json(), {"ok": True, "id": item_id})

        # Verify WebSocket broadcast for delete
        mock_broadcast.assert_called()
        self.assertEqual(mock_broadcast.call_args[0][0]["type"], "schedule_update")
        self.assertEqual(mock_broadcast.call_args[0][0]["action"], "delete")
        self.assertEqual(mock_broadcast.call_args[0][0]["item_id"], item_id)

        # Delete nonexistent -> 404
        res_del_404 = self.client.delete("/api/v1/schedules/sch_nonexistent", headers=headers)
        self.assertEqual(res_del_404.status_code, 404)

    # -------------------------------------------------------------------------
    # Candidates Submission and Idempotency Tests
    # -------------------------------------------------------------------------

    @patch.object(manager, "broadcast", new_callable=AsyncMock)
    def test_candidates_idempotency_key(self, mock_broadcast: AsyncMock) -> None:
        headers = {
            "Authorization": "Bearer valid-device-token-abc",
            "Idempotency-Key": "test-uuid-11223344",
        }
        now = time.time()
        candidate_payload = {
            "candidates": [
                {
                    "id": "sch_apk_cand_01",
                    "type": TYPE_COUNTDOWN,
                    "title": "煮蛋倒计时",
                    "target_time_utc": now + 600,
                    "duration_seconds": 600,
                    "status": STATUS_ACTIVE,
                    "created_at_utc": now,
                    "updated_at_utc": now,
                }
            ]
        }

        # 1. First submission with Idempotency-Key
        res1 = self.client.post("/api/v1/schedules/candidates", headers=headers, json=candidate_payload)
        self.assertEqual(res1.status_code, 200)
        data1 = res1.json()
        self.assertTrue(data1["changed"])
        self.assertEqual(data1["results"][0]["status"], "created")
        self.assertEqual(mock_broadcast.call_count, 1)

        # 2. Resubmission with same Idempotency-Key returns cached response without duplicate broadcast
        mock_broadcast.reset_mock()
        res2 = self.client.post("/api/v1/schedules/candidates", headers=headers, json=candidate_payload)
        self.assertEqual(res2.status_code, 200)
        data2 = res2.json()
        self.assertEqual(data1, data2)
        # Broadcast must not be called again because response came from idempotency cache
        mock_broadcast.assert_not_called()

        # 3. Resubmission without Idempotency-Key returns deduplicated result (changed: False)
        res3 = self.client.post(
            "/api/v1/schedules/candidates",
            headers={"Authorization": "Bearer valid-device-token-abc"},
            json=candidate_payload,
        )
        self.assertEqual(res3.status_code, 200)
        data3 = res3.json()
        self.assertFalse(data3["changed"])
        self.assertEqual(data3["results"][0]["status"], "deduplicated")

    # -------------------------------------------------------------------------
    # Incremental Snapshot Synchronization Tests
    # -------------------------------------------------------------------------

    def test_incremental_snapshot_flow(self) -> None:
        headers = {"Authorization": "Bearer valid-device-token-abc"}

        # Empty store -> full snapshot reset: True
        snap0 = self.client.get("/api/v1/schedules/snapshot", headers=headers).json()
        self.assertTrue(snap0["reset"])
        self.assertEqual(len(snap0["items"]), 0)
        self.assertIn("server_time_utc", snap0)

        # Create two items
        self.client.post(
            "/api/v1/schedules",
            headers=headers,
            json={"id": "sch_snap_01", "type": TYPE_ALARM, "title": "Alarm 1"},
        )
        self.client.post(
            "/api/v1/schedules",
            headers=headers,
            json={"id": "sch_snap_02", "type": TYPE_COUNTDOWN, "title": "Countdown 2"},
        )

        # Full snapshot
        snap1 = self.client.get("/api/v1/schedules/snapshot", headers=headers).json()
        self.assertTrue(snap1["reset"])
        self.assertEqual(len(snap1["items"]), 2)
        cursor1 = snap1["cursor"]
        self.assertTrue(bool(cursor1))

        # Query with since=cursor1 -> empty delta, reset: False
        snap2 = self.client.get(f"/api/v1/schedules/snapshot?since={cursor1}", headers=headers).json()
        self.assertFalse(snap2["reset"])
        self.assertEqual(len(snap2["items"]), 0)
        self.assertEqual(len(snap2["deleted_ids"]), 0)
        self.assertEqual(snap2["cursor"], cursor1)

        # Create third item
        self.client.post(
            "/api/v1/schedules",
            headers=headers,
            json={"id": "sch_snap_03", "type": TYPE_SCHEDULE, "title": "Schedule 3"},
        )

        # Query with since=cursor1 -> only Item 3 returned
        snap3 = self.client.get(f"/api/v1/schedules/snapshot?since={cursor1}", headers=headers).json()
        self.assertFalse(snap3["reset"])
        self.assertEqual(len(snap3["items"]), 1)
        self.assertEqual(snap3["items"][0]["id"], "sch_snap_03")
        cursor3 = snap3["cursor"]

        # Delete Item 1
        self.client.delete("/api/v1/schedules/sch_snap_01", headers=headers)

        # Query with since=cursor3 -> reflects deleted_id
        snap4 = self.client.get(f"/api/v1/schedules/snapshot?since={cursor3}", headers=headers).json()
        self.assertFalse(snap4["reset"])
        self.assertEqual(len(snap4["items"]), 0)
        self.assertEqual(snap4["deleted_ids"], ["sch_snap_01"])

        # Type filter snapshot: query type=countdown
        snap_type = self.client.get("/api/v1/schedules/snapshot?type=countdown", headers=headers).json()
        self.assertTrue(snap_type["reset"])
        self.assertEqual(len(snap_type["items"]), 1)
        self.assertEqual(snap_type["items"][0]["id"], "sch_snap_02")
