"""SQLite storage engine for unified schedules (alarms, count-down timers, and calendar reminders).

Implements Phase 1 of ALARM-TIMER-SCHEDULE-PLAN.md (§2, §3, §4, §8):
- SQLite at DATA_DIR / schedules.db with WAL mode
- Tables `schedules` and `schedule_tombstones`
- Single unified ScheduleItem entity with type alarm, countdown, or schedule
- Monotonic cursor math {updated_at_utc}_{id} for incremental sync
- 30-day tombstone retention and pruning
- Terminal state protection (dismissed, completed, cancelled cannot be overwritten by stale active writes)
- Recurring item auto-advance on dismissal to next occurrence (returns to active)
- Candidate batch submission with deduplication and idempotency
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
import json
import logging
from pathlib import Path
import secrets
import sqlite3
import threading
import time
from typing import Any
import zoneinfo

from app.config import settings

logger = logging.getLogger(__name__)

# Valid entity types per plan §2.1
TYPE_ALARM = "alarm"
TYPE_COUNTDOWN = "countdown"
TYPE_SCHEDULE = "schedule"
VALID_TYPES = {TYPE_ALARM, TYPE_COUNTDOWN, TYPE_SCHEDULE}

# Lifecycle status values per plan §2.1 & §2.2
STATUS_ACTIVE = "active"
STATUS_RINGING = "ringing"
STATUS_SNOOZED = "snoozed"
STATUS_DISMISSED = "dismissed"
STATUS_COMPLETED = "completed"
STATUS_CANCELLED = "cancelled"

VALID_STATUSES = {
    STATUS_ACTIVE,
    STATUS_RINGING,
    STATUS_SNOOZED,
    STATUS_DISMISSED,
    STATUS_COMPLETED,
    STATUS_CANCELLED,
}

TERMINAL_STATUSES = {
    STATUS_DISMISSED,
    STATUS_COMPLETED,
    STATUS_CANCELLED,
}

NON_TERMINAL_STATUSES = {
    STATUS_ACTIVE,
    STATUS_RINGING,
    STATUS_SNOOZED,
}

# Candidate submission result statuses
STATUS_RESULT_CREATED = "created"
STATUS_RESULT_UPDATED = "updated"
STATUS_RESULT_DEDUPLICATED = "deduplicated"
STATUS_RESULT_SKIPPED_TERMINAL = "skipped_terminal"
STATUS_RESULT_SKIPPED_DELETED = "skipped_deleted"

# Day name mapping for RFC 5545 RRULE BYDAY parsing
RRULE_DAY_MAP = {
    "MO": 0,
    "TU": 1,
    "WE": 2,
    "TH": 3,
    "FR": 4,
    "SA": 5,
    "SU": 6,
}


def compute_next_target_time(
    target_time_utc: float,
    recurrence: str,
    from_utc: float | None = None,
    tz_name: str | None = None,
) -> float | None:
    """Compute the next occurrence target time (UTC timestamp) for a recurring item.

    Supports RFC 5545 RRULE subsets:
    - FREQ=DAILY (with optional INTERVAL)
    - FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR (workdays)
    - FREQ=WEEKLY;BYDAY=SA,SU (weekends)
    - FREQ=WEEKLY;BYDAY=... (arbitrary day subsets)
    - FREQ=WEEKLY (same weekday)

    Returns the next UTC timestamp strictly greater than max(target_time_utc, from_utc),
    or None if recurrence is empty, unsupported, or no matching slot found within 366 days.
    """
    if not recurrence or not recurrence.strip():
        return None

    if from_utc is None:
        from_utc = time.time()

    if tz_name is None:
        tz_name = settings.tz

    try:
        tz = zoneinfo.ZoneInfo(tz_name)
    except Exception:
        tz = zoneinfo.ZoneInfo("UTC")

    parts = dict(p.split("=", 1) for p in recurrence.strip().split(";") if "=" in p)
    freq = parts.get("FREQ", "").upper()
    if not freq:
        return None

    try:
        interval = max(1, int(parts.get("INTERVAL", "1")))
    except ValueError:
        interval = 1

    byday_str = parts.get("BYDAY", "")
    bydays = [
        RRULE_DAY_MAP[d.strip().upper()]
        for d in byday_str.split(",")
        if d.strip().upper() in RRULE_DAY_MAP
    ]

    base_dt = datetime.fromtimestamp(target_time_utc, tz=tz)
    from_dt = datetime.fromtimestamp(from_utc, tz=tz)
    threshold_ts = max(target_time_utc, from_utc)

    # Search starting from whichever date is greater: base date or reference date
    start_date = max(base_dt.date(), from_dt.date())
    curr_date = start_date

    for _ in range(366):
        cand_dt = datetime(
            curr_date.year,
            curr_date.month,
            curr_date.day,
            base_dt.hour,
            base_dt.minute,
            base_dt.second,
            tzinfo=tz,
        )
        cand_ts = cand_dt.timestamp()

        matches_rule = False
        if freq == "DAILY":
            days_diff = (curr_date - base_dt.date()).days
            if days_diff >= 0 and days_diff % interval == 0:
                matches_rule = True
        elif freq == "WEEKLY":
            if bydays:
                if curr_date.weekday() in bydays:
                    matches_rule = True
            else:
                if curr_date.weekday() == base_dt.weekday():
                    matches_rule = True

        if matches_rule and cand_ts > threshold_ts:
            return cand_ts

        curr_date += timedelta(days=1)

    return None


@dataclass
class ScheduleItem:
    """Unified ScheduleItem entity per plan §2.1."""

    id: str
    type: str
    title: str
    target_time_utc: float
    duration_seconds: int
    recurrence: str
    status: str
    origin: str
    created_at_utc: float
    updated_at_utc: float
    metadata: dict[str, Any]

    def to_wire_item(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "type": self.type,
            "title": self.title,
            "target_time_utc": self.target_time_utc,
            "duration_seconds": self.duration_seconds,
            "recurrence": self.recurrence,
            "status": self.status,
            "origin": self.origin,
            "created_at_utc": self.created_at_utc,
            "updated_at_utc": self.updated_at_utc,
            "metadata": self.metadata,
        }


class ScheduleStore:
    """SQLite engine for unified schedules and incremental synchronization."""

    def __init__(self, db_path: Path | str | None = None) -> None:
        self._db_path = db_path
        self._initialized = False
        self._init_lock = threading.Lock()

    @property
    def db_path(self) -> Path | str:
        if self._db_path is not None:
            return self._db_path
        return settings.schedule_db_path

    def _raw_connect(self) -> sqlite3.Connection:
        """Create a raw SQLite connection configured with WAL and busy timeout."""
        db_target = str(self.db_path)
        conn = sqlite3.connect(db_target, timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA busy_timeout=5000;")
        conn.execute("PRAGMA foreign_keys=ON;")
        return conn

    def _connect(self) -> sqlite3.Connection:
        """Create a new SQLite connection after ensuring schema initialization."""
        self._ensure_init()
        return self._raw_connect()

    def _ensure_init(self) -> None:
        """Ensure database tables and indexes are created (thread-safe, lazy)."""
        if not self._initialized:
            with self._init_lock:
                if not self._initialized:
                    self._init_db()
                    self._initialized = True

    def _init_db(self) -> None:
        """Create tables and indexes per plan §2.1 & §3.3."""
        if str(self.db_path) != ":memory:":
            Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)

        conn = self._raw_connect()
        try:
            conn.executescript(
                """
                -- Active schedules table
                CREATE TABLE IF NOT EXISTS schedules (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    target_time_utc REAL NOT NULL DEFAULT 0.0,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    recurrence TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'active',
                    origin TEXT NOT NULL DEFAULT '',
                    created_at_utc REAL NOT NULL,
                    updated_at_utc REAL NOT NULL,
                    metadata TEXT NOT NULL DEFAULT '{}'
                );

                CREATE INDEX IF NOT EXISTS idx_schedules_updated ON schedules (updated_at_utc DESC, id DESC);
                CREATE INDEX IF NOT EXISTS idx_schedules_type ON schedules (type);
                CREATE INDEX IF NOT EXISTS idx_schedules_status ON schedules (status);
                CREATE INDEX IF NOT EXISTS idx_schedules_target_time ON schedules (target_time_utc);

                -- Tombstones table (for incremental snapshot deleted_ids)
                CREATE TABLE IF NOT EXISTS schedule_tombstones (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL DEFAULT '',
                    deleted_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_schedule_tombstones_deleted_at ON schedule_tombstones (deleted_at DESC);
                """
            )
        finally:
            conn.close()

    @staticmethod
    def _row_to_item(row: sqlite3.Row | dict[str, Any]) -> dict[str, Any]:
        """Convert a database row into the wire item format."""
        raw_meta = row["metadata"] if "metadata" in row.keys() else row.get("metadata", "{}")
        if isinstance(raw_meta, str):
            try:
                metadata = json.loads(raw_meta) if raw_meta else {}
            except Exception:
                metadata = {}
        elif isinstance(raw_meta, dict):
            metadata = raw_meta
        else:
            metadata = {}

        return {
            "id": str(row["id"]),
            "type": str(row["type"]),
            "title": str(row["title"]),
            "target_time_utc": float(row["target_time_utc"]),
            "duration_seconds": int(row["duration_seconds"]),
            "recurrence": str(row["recurrence"]),
            "status": str(row["status"]),
            "origin": str(row["origin"]),
            "created_at_utc": float(row["created_at_utc"]),
            "updated_at_utc": float(row["updated_at_utc"]),
            "metadata": metadata,
        }

    @staticmethod
    def _parse_cursor(cursor_str: str) -> tuple[float, str] | None:
        """Parse cursor string '{timestamp}_{id}' or '{timestamp}' into (timestamp, id)."""
        try:
            s = cursor_str.strip()
            if not s:
                return None
            if "_" in s:
                parts = s.split("_", 1)
                return (float(parts[0]), parts[1])
            return (float(s), "")
        except Exception:
            return None

    def get_snapshot(
        self,
        since: str = "",
        type_filter: str | None = None,
        max_age_days: int = 30,
    ) -> dict[str, Any]:
        """Fetch incremental snapshot for synchronization per plan §3.3 & §4.2.

        Returns:
        {
            "items": [...],
            "deleted_ids": [...],
            "cursor": "...",
            "reset": False/True,
            "server_time_utc": ...
        }
        """
        self._ensure_init()
        conn = self._connect()
        try:
            # 1. Compute current latest cursor from active schedules and tombstones
            max_sched = conn.execute(
                "SELECT updated_at_utc, id FROM schedules ORDER BY updated_at_utc DESC, id DESC LIMIT 1"
            ).fetchone()
            max_tomb = conn.execute(
                "SELECT deleted_at, id FROM schedule_tombstones ORDER BY deleted_at DESC, id DESC LIMIT 1"
            ).fetchone()

            sched_tuple = (
                (float(max_sched["updated_at_utc"]), str(max_sched["id"]))
                if max_sched
                else (0.0, "")
            )
            tomb_tuple = (
                (float(max_tomb["deleted_at"]), str(max_tomb["id"]))
                if max_tomb
                else (0.0, "")
            )

            latest_tuple = max(sched_tuple, tomb_tuple)
            latest_cursor = (
                f"{latest_tuple[0]}_{latest_tuple[1]}" if latest_tuple > (0.0, "") else ""
            )

            def full_snapshot() -> dict[str, Any]:
                if type_filter:
                    rows = conn.execute(
                        "SELECT * FROM schedules WHERE type = ? ORDER BY updated_at_utc ASC, id ASC",
                        (type_filter.strip().lower(),),
                    ).fetchall()
                else:
                    rows = conn.execute(
                        "SELECT * FROM schedules ORDER BY updated_at_utc ASC, id ASC"
                    ).fetchall()
                return {
                    "items": [self._row_to_item(r) for r in rows],
                    "deleted_ids": [],
                    "cursor": latest_cursor,
                    "reset": True,
                    "server_time_utc": time.time(),
                }

            since_str = since.strip() if since else ""
            if not since_str:
                return full_snapshot()

            parsed = self._parse_cursor(since_str)
            if parsed is None:
                # Corrupted or unparseable cursor -> full reset
                return full_snapshot()

            cursor_ts, cursor_id = parsed
            now = time.time()
            cutoff = now - (max_age_days * 86400.0)

            if cursor_ts < cutoff:
                # Cursor older than retention period -> full reset
                return full_snapshot()

            # Incremental query: items updated strictly after cursor
            if type_filter:
                item_rows = conn.execute(
                    """
                    SELECT * FROM schedules
                    WHERE ((updated_at_utc > ?) OR (updated_at_utc = ? AND id > ?))
                      AND type = ?
                    ORDER BY updated_at_utc ASC, id ASC
                    """,
                    (cursor_ts, cursor_ts, cursor_id, type_filter.strip().lower()),
                ).fetchall()
                tomb_rows = conn.execute(
                    """
                    SELECT id, deleted_at FROM schedule_tombstones
                    WHERE ((deleted_at > ?) OR (deleted_at = ? AND id > ?))
                      AND (type = ? OR type = '')
                    ORDER BY deleted_at ASC, id ASC
                    """,
                    (cursor_ts, cursor_ts, cursor_id, type_filter.strip().lower()),
                ).fetchall()
            else:
                item_rows = conn.execute(
                    """
                    SELECT * FROM schedules
                    WHERE (updated_at_utc > ?) OR (updated_at_utc = ? AND id > ?)
                    ORDER BY updated_at_utc ASC, id ASC
                    """,
                    (cursor_ts, cursor_ts, cursor_id),
                ).fetchall()
                tomb_rows = conn.execute(
                    """
                    SELECT id, deleted_at FROM schedule_tombstones
                    WHERE (deleted_at > ?) OR (deleted_at = ? AND id > ?)
                    ORDER BY deleted_at ASC, id ASC
                    """,
                    (cursor_ts, cursor_ts, cursor_id),
                ).fetchall()

            items = [self._row_to_item(r) for r in item_rows]
            deleted_ids = [str(r["id"]) for r in tomb_rows]
            next_cursor = latest_cursor if latest_cursor else since_str

            return {
                "items": items,
                "deleted_ids": deleted_ids,
                "cursor": next_cursor,
                "reset": False,
                "server_time_utc": time.time(),
            }
        finally:
            conn.close()

    def submit_candidates(self, candidates: list[dict[str, Any]]) -> dict[str, Any]:
        """Submit a batch of schedule candidates with conflict arbitration and terminal protection.

        Returns matching plan §4.2:
        {
            "results": [
                {"id": "...", "status": "created" | "updated" | "deduplicated" | ..., "item": {...}},
                ...
            ],
            "changed": True/False
        }
        """
        self._ensure_init()
        results: list[dict[str, Any]] = []
        any_changed = False
        now = time.time()

        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            for cand in candidates:
                cand_id = str(cand.get("id") or "").strip()
                if not cand_id or cand_id == "None":
                    cand_id = f"sch_{secrets.token_hex(6)}"

                cand_type = str(cand.get("type") or TYPE_SCHEDULE).strip().lower()
                if cand_type not in VALID_TYPES:
                    cand_type = TYPE_SCHEDULE

                title = str(cand.get("title") or "").strip()
                duration_seconds = int(cand.get("duration_seconds") or 0)
                target_time_utc = float(cand.get("target_time_utc") or 0.0)
                if target_time_utc <= 0.0:
                    if cand_type == TYPE_COUNTDOWN and duration_seconds > 0:
                        target_time_utc = now + duration_seconds
                    else:
                        target_time_utc = now

                recurrence = str(cand.get("recurrence") or "").strip()
                cand_status = str(cand.get("status") or STATUS_ACTIVE).strip().lower()
                if cand_status not in VALID_STATUSES:
                    cand_status = STATUS_ACTIVE

                origin = str(cand.get("origin") or "apk_voice").strip()
                created_at = float(cand.get("created_at_utc") or now)
                cand_updated_at = float(cand.get("updated_at_utc") or now)

                raw_meta = cand.get("metadata") or {}
                metadata_dict = raw_meta if isinstance(raw_meta, dict) else {}
                metadata_str = json.dumps(metadata_dict)

                # 1. Deleted tombstone protection: deleted items cannot be resurrected by candidate sync
                tomb = conn.execute(
                    "SELECT id FROM schedule_tombstones WHERE id = ?", (cand_id,)
                ).fetchone()
                if tomb:
                    results.append({"id": cand_id, "status": STATUS_RESULT_SKIPPED_DELETED})
                    continue

                # 2. Check existing record
                existing_row = conn.execute(
                    "SELECT * FROM schedules WHERE id = ?", (cand_id,)
                ).fetchone()

                if existing_row:
                    existing = self._row_to_item(existing_row)
                    existing_status = existing["status"]

                    # Terminal state protection: terminal state must NEVER be overwritten by active write
                    if existing_status in TERMINAL_STATUSES and cand_status in NON_TERMINAL_STATUSES:
                        results.append({
                            "id": cand_id,
                            "status": STATUS_RESULT_SKIPPED_TERMINAL,
                            "item": existing,
                        })
                        continue

                    # Recurring item dismissal check
                    effective_status = cand_status
                    effective_target_time = target_time_utc
                    effective_recurrence = recurrence if recurrence else existing["recurrence"]
                    if cand_status == STATUS_DISMISSED and effective_recurrence:
                        next_target = compute_next_target_time(
                            target_time_utc=existing["target_time_utc"],
                            recurrence=effective_recurrence,
                            from_utc=now,
                        )
                        if next_target is not None:
                            effective_target_time = next_target
                            effective_status = STATUS_ACTIVE
                            if "snooze_count" in metadata_dict:
                                metadata_dict["snooze_count"] = 0
                                metadata_str = json.dumps(metadata_dict)

                    # Deduplication check: identical item
                    is_identical = (
                        existing["type"] == cand_type
                        and existing["title"] == title
                        and abs(existing["target_time_utc"] - effective_target_time) < 1e-3
                        and existing["duration_seconds"] == duration_seconds
                        and existing["recurrence"] == recurrence
                        and existing["status"] == effective_status
                        and existing["metadata"] == metadata_dict
                    )
                    if is_identical:
                        results.append({
                            "id": cand_id,
                            "status": STATUS_RESULT_DEDUPLICATED,
                            "item": existing,
                        })
                        continue

                    # Stale write check: incoming write is older than existing DB updated_at
                    if cand_updated_at < existing["updated_at_utc"]:
                        results.append({
                            "id": cand_id,
                            "status": STATUS_RESULT_DEDUPLICATED,
                            "item": existing,
                        })
                        continue

                    # Update existing record
                    update_ts = max(now, cand_updated_at)
                    conn.execute(
                        """
                        UPDATE schedules
                        SET type = ?, title = ?, target_time_utc = ?, duration_seconds = ?,
                            recurrence = ?, status = ?, origin = ?, updated_at_utc = ?, metadata = ?
                        WHERE id = ?
                        """,
                        (
                            cand_type,
                            title,
                            effective_target_time,
                            duration_seconds,
                            recurrence,
                            effective_status,
                            origin,
                            update_ts,
                            metadata_str,
                            cand_id,
                        ),
                    )
                    updated_row = conn.execute(
                        "SELECT * FROM schedules WHERE id = ?", (cand_id,)
                    ).fetchone()
                    any_changed = True
                    results.append({
                        "id": cand_id,
                        "status": STATUS_RESULT_UPDATED,
                        "item": self._row_to_item(updated_row),
                    })
                else:
                    # New record insertion
                    effective_status = cand_status
                    effective_target_time = target_time_utc
                    if cand_status == STATUS_DISMISSED and recurrence:
                        next_target = compute_next_target_time(
                            target_time_utc=target_time_utc,
                            recurrence=recurrence,
                            from_utc=now,
                        )
                        if next_target is not None:
                            effective_target_time = next_target
                            effective_status = STATUS_ACTIVE
                            if "snooze_count" in metadata_dict:
                                metadata_dict["snooze_count"] = 0
                                metadata_str = json.dumps(metadata_dict)

                    insert_ts = max(now, cand_updated_at)
                    conn.execute(
                        """
                        INSERT INTO schedules
                        (id, type, title, target_time_utc, duration_seconds, recurrence, status, origin, created_at_utc, updated_at_utc, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            cand_id,
                            cand_type,
                            title,
                            effective_target_time,
                            duration_seconds,
                            recurrence,
                            effective_status,
                            origin,
                            created_at,
                            insert_ts,
                            metadata_str,
                        ),
                    )
                    new_row = conn.execute(
                        "SELECT * FROM schedules WHERE id = ?", (cand_id,)
                    ).fetchone()
                    any_changed = True
                    results.append({
                        "id": cand_id,
                        "status": STATUS_RESULT_CREATED,
                        "item": self._row_to_item(new_row),
                    })

            conn.commit()
            return {"results": results, "changed": any_changed}
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def create_schedule(self, item_dict: dict[str, Any]) -> dict[str, Any]:
        """Create a new schedule item via REST API."""
        self._ensure_init()
        now = time.time()

        item_id = str(item_dict.get("id") or "").strip()
        if not item_id or item_id == "None":
            item_id = f"sch_{secrets.token_hex(6)}"

        item_type = str(item_dict.get("type") or TYPE_SCHEDULE).strip().lower()
        if item_type not in VALID_TYPES:
            raise ValueError(f"Invalid type '{item_type}'. Must be one of: {', '.join(sorted(VALID_TYPES))}")

        title = str(item_dict.get("title") or "").strip()
        duration_seconds = int(item_dict.get("duration_seconds") or 0)
        target_time_utc = float(item_dict.get("target_time_utc") or 0.0)
        if target_time_utc <= 0.0:
            if item_type == TYPE_COUNTDOWN and duration_seconds > 0:
                target_time_utc = now + duration_seconds
            else:
                target_time_utc = now

        recurrence = str(item_dict.get("recurrence") or "").strip()
        status = str(item_dict.get("status") or STATUS_ACTIVE).strip().lower()
        if status not in VALID_STATUSES:
            status = STATUS_ACTIVE

        origin = str(item_dict.get("origin") or "muse_push").strip()
        created_at = float(item_dict.get("created_at_utc") or now)
        updated_at = float(item_dict.get("updated_at_utc") or now)

        raw_meta = item_dict.get("metadata") or {}
        metadata_dict = raw_meta if isinstance(raw_meta, dict) else {}
        metadata_str = json.dumps(metadata_dict)

        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                """
                INSERT INTO schedules
                (id, type, title, target_time_utc, duration_seconds, recurrence, status, origin, created_at_utc, updated_at_utc, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    item_id,
                    item_type,
                    title,
                    target_time_utc,
                    duration_seconds,
                    recurrence,
                    status,
                    origin,
                    created_at,
                    updated_at,
                    metadata_str,
                ),
            )
            row = conn.execute("SELECT * FROM schedules WHERE id = ?", (item_id,)).fetchone()
            conn.commit()
            return self._row_to_item(row)
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def get_schedule(self, schedule_id: str) -> dict[str, Any] | None:
        """Get a single schedule item by ID."""
        self._ensure_init()
        conn = self._connect()
        try:
            row = conn.execute("SELECT * FROM schedules WHERE id = ?", (schedule_id,)).fetchone()
            if row:
                return self._row_to_item(row)
            return None
        finally:
            conn.close()

    def list_schedules(
        self,
        type: str | None = None,
        status: str | None = None,
        from_utc: float | None = None,
        to_utc: float | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """List schedule items with optional filtering by type, status, and target time range."""
        self._ensure_init()
        clauses: list[str] = []
        params: list[Any] = []

        if type:
            clauses.append("type = ?")
            params.append(type.strip().lower())
        if status:
            clauses.append("status = ?")
            params.append(status.strip().lower())
        if from_utc is not None:
            clauses.append("target_time_utc >= ?")
            params.append(float(from_utc))
        if to_utc is not None:
            clauses.append("target_time_utc <= ?")
            params.append(float(to_utc))

        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        sql = f"""
            SELECT * FROM schedules
            {where_sql}
            ORDER BY target_time_utc ASC, id ASC
            LIMIT ? OFFSET ?
        """
        params.extend([max(1, limit), max(0, offset)])

        conn = self._connect()
        try:
            rows = conn.execute(sql, params).fetchall()
            return [self._row_to_item(r) for r in rows]
        finally:
            conn.close()

    def update_schedule(
        self,
        schedule_id: str,
        updates: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Update an existing schedule item with terminal state protection and recurring dismiss support."""
        self._ensure_init()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT * FROM schedules WHERE id = ?", (schedule_id,)).fetchone()
            if not row:
                conn.commit()
                return None

            item = self._row_to_item(row)
            now = time.time()

            # Terminal state protection
            new_status = updates.get("status")
            if new_status is not None:
                new_status = str(new_status).strip().lower()
                if item["status"] in TERMINAL_STATUSES and new_status in NON_TERMINAL_STATUSES:
                    raise ValueError(
                        f"Cannot overwrite terminal state '{item['status']}' with active status"
                    )
            else:
                new_status = item["status"]

            new_title = (
                str(updates["title"]).strip()
                if "title" in updates and updates["title"] is not None
                else item["title"]
            )
            new_duration = (
                int(updates["duration_seconds"])
                if "duration_seconds" in updates and updates["duration_seconds"] is not None
                else item["duration_seconds"]
            )
            new_recurrence = (
                str(updates["recurrence"]).strip()
                if "recurrence" in updates and updates["recurrence"] is not None
                else item["recurrence"]
            )
            new_target_time = (
                float(updates["target_time_utc"])
                if "target_time_utc" in updates and updates["target_time_utc"] is not None
                else item["target_time_utc"]
            )

            metadata = dict(item["metadata"])
            if "metadata" in updates and isinstance(updates["metadata"], dict):
                metadata.update(updates["metadata"])

            # Check recurring item dismissal
            if new_status == STATUS_DISMISSED and new_recurrence:
                next_target = compute_next_target_time(
                    target_time_utc=new_target_time,
                    recurrence=new_recurrence,
                    from_utc=now,
                )
                if next_target is not None:
                    new_status = STATUS_ACTIVE
                    new_target_time = next_target
                    if "snooze_count" in metadata:
                        metadata["snooze_count"] = 0

            metadata_str = json.dumps(metadata)
            conn.execute(
                """
                UPDATE schedules
                SET title = ?, duration_seconds = ?, recurrence = ?, target_time_utc = ?,
                    status = ?, updated_at_utc = ?, metadata = ?
                WHERE id = ?
                """,
                (
                    new_title,
                    new_duration,
                    new_recurrence,
                    new_target_time,
                    new_status,
                    now,
                    metadata_str,
                    schedule_id,
                ),
            )
            updated_row = conn.execute("SELECT * FROM schedules WHERE id = ?", (schedule_id,)).fetchone()
            conn.commit()
            return self._row_to_item(updated_row)
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def delete_schedule(self, schedule_id: str) -> bool:
        """Physically delete a schedule item and record its tombstone."""
        self._ensure_init()
        now = time.time()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT type FROM schedules WHERE id = ?", (schedule_id,)).fetchone()
            if row:
                item_type = str(row["type"])
                conn.execute("DELETE FROM schedules WHERE id = ?", (schedule_id,))
                conn.execute(
                    "INSERT OR REPLACE INTO schedule_tombstones (id, type, deleted_at) VALUES (?, ?, ?)",
                    (schedule_id, item_type, now),
                )
                conn.commit()
                return True
            conn.commit()
            return False
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def apply_action(
        self,
        schedule_id: str,
        action: str,
        snooze_seconds: int = 300,
    ) -> dict[str, Any] | None:
        """Apply a lifecycle action (dismiss, snooze, complete, cancel) to a schedule item."""
        self._ensure_init()
        act = action.strip().lower()
        if act not in ("dismiss", "snooze", "complete", "cancel"):
            raise ValueError(f"Invalid action '{action}'. Must be one of: dismiss, snooze, complete, cancel")

        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT * FROM schedules WHERE id = ?", (schedule_id,)).fetchone()
            if not row:
                conn.commit()
                return None

            item = self._row_to_item(row)
            now = time.time()
            metadata = dict(item["metadata"])
            new_status = item["status"]
            new_target_time = item["target_time_utc"]

            if act == "dismiss":
                recurrence = item["recurrence"]
                if recurrence:
                    next_target = compute_next_target_time(
                        target_time_utc=item["target_time_utc"],
                        recurrence=recurrence,
                        from_utc=now,
                    )
                    if next_target is not None:
                        new_status = STATUS_ACTIVE
                        new_target_time = next_target
                        if "snooze_count" in metadata:
                            metadata["snooze_count"] = 0
                    else:
                        new_status = STATUS_DISMISSED
                        metadata["dismissed_at_utc"] = now
                else:
                    new_status = STATUS_DISMISSED
                    metadata["dismissed_at_utc"] = now

            elif act == "snooze":
                new_status = STATUS_SNOOZED
                new_target_time = now + max(1, snooze_seconds)
                metadata["snooze_count"] = int(metadata.get("snooze_count", 0)) + 1

            elif act == "complete":
                new_status = STATUS_COMPLETED
                metadata["completed_at_utc"] = now

            elif act == "cancel":
                new_status = STATUS_CANCELLED
                metadata["cancelled_at_utc"] = now

            metadata_str = json.dumps(metadata)
            conn.execute(
                """
                UPDATE schedules
                SET status = ?, target_time_utc = ?, updated_at_utc = ?, metadata = ?
                WHERE id = ?
                """,
                (new_status, new_target_time, now, metadata_str, schedule_id),
            )
            updated_row = conn.execute("SELECT * FROM schedules WHERE id = ?", (schedule_id,)).fetchone()
            conn.commit()
            return self._row_to_item(updated_row)
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def prune_tombstones(self, max_age_days: int = 30) -> int:
        """Remove tombstones older than max_age_days. Returns count of deleted rows."""
        self._ensure_init()
        cutoff = time.time() - (max_age_days * 86400.0)
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("DELETE FROM schedule_tombstones WHERE deleted_at < ?", (cutoff,))
            conn.commit()
            return cur.rowcount
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def clear_all(self) -> None:
        """Clear all schedules and tombstones (for test isolation)."""
        self._ensure_init()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("DELETE FROM schedules")
            conn.execute("DELETE FROM schedule_tombstones")
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()


schedule_store = ScheduleStore()
