# Muse Schedule Tools Contract and Integration Guide

This document defines how the Muse agent interacts with the Voice Kiosk Relay Bridge to manage schedules (clock-time alarms, countdown timers, and calendar reminders).

---

## 1. Interaction Architecture

There is **no separate function-calling framework or RPC layer** between Muse and the Relay Bridge.

Instead, Muse interacts with the Relay Bridge over HTTP/SSH via standard REST API endpoints, authenticated with the push token:

```http
Authorization: Bearer <PUSH_TOKEN>
```

This authentication and network channel matches the existing board push flow (`POST /api/v1/boards/{name}`) and conversation endpoints.

The bridge provides a thin helper module in Python (`app.tools.schedule`) exposing four plain wrapper functions (`create_schedule`, `list_schedules`, `update_schedule`, `delete_schedule`) that return the exact same wire format as the REST API.

---

## 2. Tools Specification

### 2.1 `create_schedule` (Create Schedule Item)

Creates a new alarm, countdown timer, or calendar schedule item.

- **HTTP Method**: `POST`
- **Endpoint**: `/api/v1/schedules`
- **Auth Header**: `Authorization: Bearer <PUSH_TOKEN>`
- **Python Wrapper**: `app.tools.schedule.create_schedule`

#### Request Payload Schema

```json
{
  "id": "sch_optional_custom_id",
  "type": "alarm",
  "title": "晨起闹钟",
  "target_time_utc": 1773014400.0,
  "duration_seconds": 0,
  "recurrence": "FREQ=DAILY",
  "status": "active",
  "origin": "muse_push",
  "metadata": {
    "label": "晨起",
    "ringtone": "gentle_chime"
  }
}
```

- `type` (required): `"alarm"` (clock alarm), `"countdown"` (timer), or `"schedule"` (reminder/event).
- `title` (required): Spoken and display label (e.g. `"关火"`, `"晨起闹钟"`).
- `target_time_utc` (optional): Trigger timestamp in UTC seconds. For countdown timers, defaults to `now + duration_seconds`.
- `duration_seconds` (optional): Initial countdown duration in seconds (for `countdown` type).
- `recurrence` (optional): RFC 5545 RRULE string (e.g. `"FREQ=DAILY"`, `"FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"`). Empty for single-instance items.
- `status` (optional): Defaults to `"active"`.
- `origin` (optional): Identifier for origin, defaults to `"muse_push"`.
- `metadata` (optional): Arbitrary JSON dictionary.

#### Response Schema (201 Created)

Returns the created schedule item wire shape:

```json
{
  "id": "sch_9f8e7d6c5b4a",
  "type": "alarm",
  "title": "晨起闹钟",
  "target_time_utc": 1773014400.0,
  "duration_seconds": 0,
  "recurrence": "FREQ=DAILY",
  "status": "active",
  "origin": "muse_push",
  "created_at_utc": 1772928000.0,
  "updated_at_utc": 1772928000.0,
  "metadata": {
    "label": "晨起",
    "ringtone": "gentle_chime"
  }
}
```

Upon creation, the bridge broadcasts a WebSocket notification (`{"type": "schedule_update", "action": "upsert", "item_id": "...", ...}`) to connected Android kiosks.

---

### 2.2 `list_schedules` (Query / Filter Schedule Items)

Lists schedule items with optional filtering by type, lifecycle status, and target time window.

- **HTTP Method**: `GET`
- **Endpoint**: `/api/v1/schedules`
- **Auth Header**: `Authorization: Bearer <PUSH_TOKEN>`
- **Python Wrapper**: `app.tools.schedule.list_schedules`

#### Query Parameters

- `type` (optional): Filter by entity type (`alarm`, `countdown`, `schedule`).
- `status` (optional): Filter by status (`active`, `dismissed`, `completed`, `cancelled`).
- `from_utc` (optional): Lower bound UTC timestamp for `target_time_utc` (inclusive).
- `to_utc` (optional): Upper bound UTC timestamp for `target_time_utc` (inclusive).
- `limit` (optional): Maximum items to return (default 50).
- `offset` (optional): Offset for pagination (default 0).

#### Response Schema (200 OK)

Returns an array of wire schedule item objects:

```json
[
  {
    "id": "sch_9f8e7d6c5b4a",
    "type": "schedule",
    "title": "牙医预约",
    "target_time_utc": 1773021600.0,
    "duration_seconds": 0,
    "recurrence": "",
    "status": "active",
    "origin": "muse_push",
    "created_at_utc": 1772928000.0,
    "updated_at_utc": 1772928000.0,
    "metadata": {}
  }
]
```

---

### 2.3 `update_schedule` (Update Schedule Item)

Updates fields of an existing schedule item.

- **HTTP Method**: `PUT`
- **Endpoint**: `/api/v1/schedules/{id}`
- **Auth Header**: `Authorization: Bearer <PUSH_TOKEN>`
- **Python Wrapper**: `app.tools.schedule.update_schedule`

#### Request Payload Schema

```json
{
  "title": "牙医预约（调整至下午）",
  "target_time_utc": 1773036000.0,
  "status": "active"
}
```

#### Response Schema (200 OK)

Returns the updated schedule item object. If the item ID does not exist, returns `404 Not Found`.

> [!NOTE]
> **Terminal State Protection**: Once an item is in a terminal state (`dismissed`, `completed`, `cancelled`), updating `status` back to `active` is rejected with `400 Bad Request`.
> For recurring items (`recurrence` is set), setting status to `dismissed` automatically computes the next occurrence target time and resets `status` to `active`.

---

### 2.4 `delete_schedule` (Delete Schedule Item)

Deletes a schedule item by ID and registers a tombstone for incremental synchronization.

- **HTTP Method**: `DELETE`
- **Endpoint**: `/api/v1/schedules/{id}`
- **Auth Header**: `Authorization: Bearer <PUSH_TOKEN>`
- **Python Wrapper**: `app.tools.schedule.delete_schedule`

#### Response Schema (200 OK)

```json
{
  "ok": true,
  "id": "sch_9f8e7d6c5b4a"
}
```

If the ID is not found, returns `404 Not Found`.

---

## 3. Worked Examples

### Example 1: Muse Creating an Alarm for the User

#### Scenario
The user asks: *"帮我定一个每天早上7点半的晨起闹钟"*. Muse determines the alarm configuration:
- `type`: `"alarm"`
- `title`: `"晨起闹钟"`
- `recurrence`: `"FREQ=DAILY"`
- `target_time_utc`: Next 07:30:00 in local timezone converted to UTC (e.g. `1773012600.0`)

#### Step 1: Tool Call / REST Request

Using curl over the authenticated bridge endpoint:

```bash
curl -X POST "http://${BRIDGE_HOST}:${PORT}/api/v1/schedules" \
  -H "Authorization: Bearer ${PUSH_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "alarm",
    "title": "晨起闹钟",
    "target_time_utc": 1773012600.0,
    "recurrence": "FREQ=DAILY",
    "status": "active",
    "origin": "muse_push",
    "metadata": {"label": "晨起"}
  }'
```

Or using the Python wrapper:

```python
from app.tools.schedule import create_schedule

alarm = create_schedule(
    type="alarm",
    title="晨起闹钟",
    target_time_utc=1773012600.0,
    recurrence="FREQ=DAILY",
    metadata={"label": "晨起"},
)
```

#### Step 2: Bridge Response

```json
{
  "id": "sch_3a8b2c1d0e9f",
  "type": "alarm",
  "title": "晨起闹钟",
  "target_time_utc": 1773012600.0,
  "duration_seconds": 0,
  "recurrence": "FREQ=DAILY",
  "status": "active",
  "origin": "muse_push",
  "created_at_utc": 1772926200.0,
  "updated_at_utc": 1772926200.0,
  "metadata": {"label": "晨起"}
}
```

#### Step 3: Device Sync
The bridge broadcasts `{"type": "schedule_update", "action": "upsert", "item_id": "sch_3a8b2c1d0e9f"}` over WebSocket. The Android kiosk receives this notification, synchronizes the item into `LocalScheduleCache`, and schedules the exact alarm via `AlarmManager`.

Muse replies to user:
> "好的，已为您设置每天早上 7:30 的晨起闹钟。"

---

### Example 2: Muse Answering What is on the Schedule Tomorrow

#### Scenario
The user asks: *"看看我明天有什么日程？"*.

#### Step 1: Calculate Tomorrow's Target Time Window

Given the bridge local timezone (from `settings.tz` or `Asia/Shanghai`):
- Start of tomorrow: `from_utc` = `1773014400.0` (00:00:00 local)
- End of tomorrow: `to_utc` = `1773100799.0` (23:59:59 local)

#### Step 2: Tool Call / REST Request

Using curl over the authenticated bridge endpoint:

```bash
curl -X GET "http://${BRIDGE_HOST}:${PORT}/api/v1/schedules?from_utc=1773014400.0&to_utc=1773100799.0&status=active" \
  -H "Authorization: Bearer ${PUSH_TOKEN}"
```

Or using the Python wrapper:

```python
from app.tools.schedule import list_schedules

items = list_schedules(
    from_utc=1773014400.0,
    to_utc=1773100799.0,
    status="active",
)
```

#### Step 3: Bridge Response

```json
[
  {
    "id": "sch_112233445566",
    "type": "schedule",
    "title": "带安安去图书馆",
    "target_time_utc": 1773046800.0,
    "duration_seconds": 0,
    "recurrence": "",
    "status": "active",
    "origin": "apk_voice",
    "created_at_utc": 1772900000.0,
    "updated_at_utc": 1772900000.0,
    "metadata": {}
  },
  {
    "id": "sch_778899aabbcc",
    "type": "schedule",
    "title": "家庭大扫除",
    "target_time_utc": 1773064800.0,
    "duration_seconds": 0,
    "recurrence": "",
    "status": "active",
    "origin": "muse_push",
    "created_at_utc": 1772910000.0,
    "updated_at_utc": 1772910000.0,
    "metadata": {}
  }
]
```

#### Step 4: Spoken Feedback Formulation

Muse converts the timestamps to local time and replies:
> "您明天共有 2 项日程：\n1. 上午 10:00：带安安去图书馆\n2. 下午 3:00：家庭大扫除"
