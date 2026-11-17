"""Bridge-side tool contract and wrappers for schedule operations.

This module provides plain Python function wrappers for managing schedules (alarms,
countdown timers, and calendar reminders) stored in the bridge ScheduleStore.

=== Muse Interaction Mechanism ===
There is no RPC/function-calling framework between Muse and the relay bridge.
Instead, Muse interacts with the relay bridge via standard REST API over HTTP/SSH,
authenticating with the push token:
    Authorization: Bearer <PUSH_TOKEN>
(Device APKs authenticate with Authorization: Bearer <DEVICE_TOKEN>).

=== Exact REST Endpoints and Auth Contract ===

1. Create Schedule:
   - Function: create_schedule(...)
   - HTTP Method: POST
   - Endpoint: /api/v1/schedules
   - Auth: Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)
   - Payload:
     {
       "id": "sch_..." (optional, generated if omitted),
       "type": "alarm" | "countdown" | "schedule",
       "title": "...",
       "target_time_utc": float (optional, defaults to now or now+duration),
       "duration_seconds": int (optional, countdown only),
       "recurrence": "FREQ=DAILY" (optional, RFC 5545 RRULE),
       "status": "active" (optional),
       "origin": "muse_push" (optional),
       "metadata": {} (optional)
     }
   - Response: 201 Created with wire ScheduleItem object.

2. List Schedules:
   - Function: list_schedules(...)
   - HTTP Method: GET
   - Endpoint: /api/v1/schedules
   - Auth: Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)
   - Query Parameters:
     - type: "alarm" | "countdown" | "schedule"
     - status: "active" | "completed" | "dismissed" | ...
     - from_utc: float UTC timestamp (inclusive lower bound)
     - to_utc: float UTC timestamp (inclusive upper bound)
     - limit: integer (default 50)
     - offset: integer (default 0)
   - Response: 200 OK with list of wire ScheduleItem objects.

3. Update Schedule:
   - Function: update_schedule(...)
   - HTTP Method: PUT
   - Endpoint: /api/v1/schedules/{id}
   - Auth: Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)
   - Payload:
     {
       "title": "...",
       "target_time_utc": float,
       "duration_seconds": int,
       "recurrence": "...",
       "status": "...",
       "metadata": {}
     }
   - Response: 200 OK with wire ScheduleItem object (or 404 Not Found, 400 Bad Request).

4. Delete Schedule:
   - Function: delete_schedule(...)
   - HTTP Method: DELETE
   - Endpoint: /api/v1/schedules/{id}
   - Auth: Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)
   - Response: 200 OK with {"ok": True, "id": "{id}"} (or 404 Not Found).
"""

from __future__ import annotations

from datetime import datetime, timedelta
import logging
import time
from typing import Any
import zoneinfo

from app.config import settings
from app.schedule_store import (
    STATUS_ACTIVE,
    TYPE_SCHEDULE,
    ScheduleStore,
    schedule_store,
)

logger = logging.getLogger(__name__)


def get_today_and_tomorrow_range(
    now_utc: float | None = None,
    tz_name: str | None = None,
) -> tuple[float, float]:
    """Compute start-of-today and end-of-tomorrow UTC timestamps in the target timezone.

    Args:
        now_utc: Reference UTC timestamp (defaults to current time).
        tz_name: Timezone identifier (defaults to settings.tz or Asia/Shanghai).

    Returns:
        A tuple of (from_utc, to_utc) representing:
        - from_utc: 00:00:00.000000 of today in local timezone as UTC timestamp.
        - to_utc: 23:59:59.999999 of tomorrow in local timezone as UTC timestamp.
    """
    if tz_name is None:
        tz_name = settings.tz
    try:
        tz = zoneinfo.ZoneInfo(tz_name)
    except Exception:
        tz = zoneinfo.ZoneInfo("UTC")

    now_ts = now_utc if now_utc is not None else time.time()
    dt = datetime.fromtimestamp(now_ts, tz=tz)
    today_start = datetime(dt.year, dt.month, dt.day, 0, 0, 0, tzinfo=tz)
    tomorrow_end = today_start + timedelta(days=2) - timedelta(microseconds=1)
    return (today_start.timestamp(), tomorrow_end.timestamp())


def create_schedule(
    item_dict: dict[str, Any] | None = None,
    *,
    id: str | None = None,
    type: str = TYPE_SCHEDULE,
    title: str = "",
    target_time_utc: float | None = None,
    duration_seconds: int = 0,
    recurrence: str = "",
    status: str = STATUS_ACTIVE,
    origin: str = "muse_push",
    metadata: dict[str, Any] | None = None,
    store: ScheduleStore | None = None,
    **kwargs: Any,
) -> dict[str, Any]:
    """Create a new schedule item (alarm, countdown, or schedule reminder).

    Bridge REST Equivalent:
        POST /api/v1/schedules
    Authentication:
        Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)

    Args:
        item_dict: Optional dictionary containing schedule attributes.
        id: Optional custom schedule ID (e.g. 'sch_...'). Generated automatically if omitted.
        type: Entity type ('alarm', 'countdown', 'schedule'). Defaults to 'schedule'.
        title: Title or label of the schedule.
        target_time_utc: Target time as UTC Unix timestamp in seconds.
        duration_seconds: Initial duration in seconds (countdown only).
        recurrence: RFC 5545 RRULE string (e.g. 'FREQ=DAILY', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR').
        status: Lifecycle status ('active', 'ringing', 'snoozed', 'dismissed', 'completed', 'cancelled').
        origin: Origin identifier (defaults to 'muse_push').
        metadata: Custom JSON metadata dictionary.
        store: Optional ScheduleStore instance (defaults to global schedule_store singleton).
        **kwargs: Additional fields merged into payload.

    Returns:
        The created schedule item dictionary in wire format:
        {
            "id": str,
            "type": str,
            "title": str,
            "target_time_utc": float,
            "duration_seconds": int,
            "recurrence": str,
            "status": str,
            "origin": str,
            "created_at_utc": float,
            "updated_at_utc": float,
            "metadata": dict
        }
    """
    target_store = store or schedule_store
    payload: dict[str, Any] = {}
    if item_dict and isinstance(item_dict, dict):
        payload.update(item_dict)

    if id is not None:
        payload["id"] = id
    if "type" not in payload or type != TYPE_SCHEDULE:
        payload.setdefault("type", type)
    if "title" not in payload or title:
        payload.setdefault("title", title)
    if target_time_utc is not None:
        payload["target_time_utc"] = target_time_utc
    if duration_seconds != 0 or "duration_seconds" not in payload:
        payload.setdefault("duration_seconds", duration_seconds)
    if recurrence or "recurrence" not in payload:
        payload.setdefault("recurrence", recurrence)
    if status != STATUS_ACTIVE or "status" not in payload:
        payload.setdefault("status", status)
    if origin != "muse_push" or "origin" not in payload:
        payload.setdefault("origin", origin)
    if metadata is not None:
        payload["metadata"] = metadata
    elif "metadata" not in payload:
        payload["metadata"] = {}

    for k, v in kwargs.items():
        if v is not None:
            payload[k] = v

    return target_store.create_schedule(payload)


def list_schedules(
    type: str | None = None,
    status: str | None = None,
    from_utc: float | None = None,
    to_utc: float | None = None,
    limit: int = 50,
    offset: int = 0,
    today_plus_tomorrow: bool = False,
    now_utc: float | None = None,
    tz_name: str | None = None,
    store: ScheduleStore | None = None,
) -> list[dict[str, Any]]:
    """List schedule items with optional filtering by type, status, and target time window.

    Bridge REST Equivalent:
        GET /api/v1/schedules?type=...&status=...&from_utc=...&to_utc=...&limit=...&offset=...
    Authentication:
        Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)

    Args:
        type: Optional filter by type ('alarm', 'countdown', 'schedule').
        status: Optional filter by status ('active', 'completed', etc.).
        from_utc: Earliest target_time_utc (inclusive).
        to_utc: Latest target_time_utc (inclusive).
        limit: Maximum number of items to return (default 50).
        offset: Offset for pagination (default 0).
        today_plus_tomorrow: If True, calculates from_utc (start of today) and to_utc
                             (end of tomorrow) if they are not explicitly provided.
        now_utc: Reference time for computing today_plus_tomorrow range.
        tz_name: Timezone identifier (defaults to settings.tz).
        store: Optional ScheduleStore instance (defaults to global schedule_store singleton).

    Returns:
        List of schedule items matching the query in wire format.
    """
    target_store = store or schedule_store

    calc_from = from_utc
    calc_to = to_utc
    if today_plus_tomorrow:
        range_from, range_to = get_today_and_tomorrow_range(now_utc=now_utc, tz_name=tz_name)
        if calc_from is None:
            calc_from = range_from
        if calc_to is None:
            calc_to = range_to

    return target_store.list_schedules(
        type=type,
        status=status,
        from_utc=calc_from,
        to_utc=calc_to,
        limit=limit,
        offset=offset,
    )


def update_schedule(
    schedule_id: str,
    updates: dict[str, Any] | None = None,
    *,
    title: str | None = None,
    target_time_utc: float | None = None,
    duration_seconds: int | None = None,
    recurrence: str | None = None,
    status: str | None = None,
    metadata: dict[str, Any] | None = None,
    store: ScheduleStore | None = None,
    **kwargs: Any,
) -> dict[str, Any] | None:
    """Update an existing schedule item.

    Bridge REST Equivalent:
        PUT /api/v1/schedules/{id}
    Authentication:
        Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)

    Args:
        schedule_id: ID of the schedule item to update (e.g. 'sch_...').
        updates: Optional dictionary of field updates.
        title: Optional new title.
        target_time_utc: Optional new target time in UTC.
        duration_seconds: Optional new duration in seconds.
        recurrence: Optional new recurrence rule (RFC 5545 RRULE).
        status: Optional new status (terminal state protection applies).
        metadata: Optional metadata dictionary to merge.
        store: Optional ScheduleStore instance (defaults to global schedule_store singleton).
        **kwargs: Additional fields to update.

    Returns:
        The updated schedule item in wire format, or None if schedule_id was not found.

    Raises:
        ValueError: If attempting an illegal transition (e.g. active overwriting terminal state).
    """
    target_store = store or schedule_store
    payload: dict[str, Any] = {}
    if updates and isinstance(updates, dict):
        payload.update(updates)

    if title is not None:
        payload["title"] = title
    if target_time_utc is not None:
        payload["target_time_utc"] = target_time_utc
    if duration_seconds is not None:
        payload["duration_seconds"] = duration_seconds
    if recurrence is not None:
        payload["recurrence"] = recurrence
    if status is not None:
        payload["status"] = status
    if metadata is not None:
        payload["metadata"] = metadata

    for k, v in kwargs.items():
        if v is not None:
            payload[k] = v

    return target_store.update_schedule(schedule_id, payload)


def delete_schedule(
    schedule_id: str,
    store: ScheduleStore | None = None,
) -> dict[str, Any]:
    """Delete a schedule item by ID and record its tombstone.

    Bridge REST Equivalent:
        DELETE /api/v1/schedules/{id}
    Authentication:
        Authorization: Bearer <PUSH_TOKEN> (or DEVICE_TOKEN)

    Args:
        schedule_id: ID of the schedule item to delete.
        store: Optional ScheduleStore instance (defaults to global schedule_store singleton).

    Returns:
        Dictionary with deletion outcome in REST wire format:
        {"ok": True, "id": schedule_id} if successfully deleted,
        {"ok": False, "id": schedule_id} if not found.
    """
    target_store = store or schedule_store
    deleted = target_store.delete_schedule(schedule_id)
    return {"ok": bool(deleted), "id": schedule_id}
