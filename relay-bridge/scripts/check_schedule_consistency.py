#!/usr/bin/env python3
"""Verification script for bridge-side schedules and tombstones consistency.

Checks:
1. Tombstone conflicts: items present in both `schedules` and `schedule_tombstones`.
2. Duplicate active items: multiple active/ringing/snoozed items with identical type, title, and target time.
3. Orphaned or corrupted items: invalid timestamps, illegal types, non-positive countdown durations,
   stale un-transitioned countdowns, and invalid JSON metadata.
4. Terminal-state violations: illegal statuses and recurring items in terminal state without auto-advancing.

Exit code:
- 0: Database is completely consistent.
- 1: One or more consistency violations detected.

Usage:
  python3 scripts/check_schedule_consistency.py [--db-path PATH] [--json] [--verbose]
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, field
import json
import os
from pathlib import Path
import sqlite3
import sys
import time
from typing import Any

# Ensure bridge package is importable
CURRENT_DIR = Path(__file__).resolve().parent
BRIDGE_ROOT = CURRENT_DIR.parent
if str(BRIDGE_ROOT) not in sys.path:
    sys.path.insert(0, str(BRIDGE_ROOT))

try:
    from app.schedule_store import (
        TERMINAL_STATUSES,
        VALID_STATUSES,
        VALID_TYPES,
        TYPE_COUNTDOWN,
    )
except Exception:
    # Standalone fallback definitions
    VALID_TYPES = {"alarm", "countdown", "schedule"}
    VALID_STATUSES = {"active", "ringing", "snoozed", "dismissed", "completed", "cancelled"}
    TERMINAL_STATUSES = {"dismissed", "completed", "cancelled"}
    TYPE_COUNTDOWN = "countdown"

settings = None
try:
    from app.config import settings
except Exception:
    pass


@dataclass
class ConsistencyReport:
    db_path: str
    total_schedules: int = 0
    total_tombstones: int = 0
    total_active: int = 0
    tombstone_conflicts: list[dict[str, Any]] = field(default_factory=list)
    duplicate_active_items: list[dict[str, Any]] = field(default_factory=list)
    orphaned_items: list[dict[str, Any]] = field(default_factory=list)
    terminal_state_violations: list[dict[str, Any]] = field(default_factory=list)

    @property
    def total_violations(self) -> int:
        return (
            len(self.tombstone_conflicts)
            + len(self.duplicate_active_items)
            + len(self.orphaned_items)
            + len(self.terminal_state_violations)
        )

    @property
    def is_clean(self) -> bool:
        return self.total_violations == 0

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["total_violations"] = self.total_violations
        data["is_clean"] = self.is_clean
        return data


def resolve_db_path(cli_path: str | None = None) -> Path:
    """Resolve database path from CLI, environment, or settings without hardcoding."""
    if cli_path:
        return Path(cli_path).resolve()
    env_path = os.environ.get("SCHEDULE_DB_PATH")
    if env_path:
        return Path(env_path).resolve()
    if settings is not None and getattr(settings, "schedule_db_path", None):
        return Path(settings.schedule_db_path).resolve()
    # Default to data dir under relay-bridge or current directory
    data_dir = os.environ.get("DATA_DIR", str(BRIDGE_ROOT / "data"))
    return (Path(data_dir) / "schedules.db").resolve()


def check_consistency(db_path: Path | str | None = None) -> ConsistencyReport:
    """Execute consistency checks against the specified SQLite schedules database."""
    target_path = Path(db_path) if db_path else resolve_db_path()
    report = ConsistencyReport(db_path=str(target_path))

    if not target_path.exists():
        report.orphaned_items.append({
            "error": "database_not_found",
            "message": f"Database file does not exist: {target_path}",
        })
        return report

    conn = sqlite3.connect(str(target_path), timeout=5.0)
    conn.row_factory = sqlite3.Row
    try:
        tables = [
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        ]
        if "schedules" not in tables:
            report.orphaned_items.append({
                "error": "missing_schedules_table",
                "message": "Table 'schedules' does not exist in database",
            })
            return report

        has_tombstones = "schedule_tombstones" in tables

        # Count totals
        row = conn.execute("SELECT count(*) FROM schedules").fetchone()
        report.total_schedules = int(row[0]) if row else 0

        row = conn.execute("SELECT count(*) FROM schedules WHERE status IN ('active', 'ringing', 'snoozed')").fetchone()
        report.total_active = int(row[0]) if row else 0

        if has_tombstones:
            row = conn.execute("SELECT count(*) FROM schedule_tombstones").fetchone()
            report.total_tombstones = int(row[0]) if row else 0

        now_sec = time.time()

        # 1. Tombstone conflicts: Item exists in both schedules and schedule_tombstones
        if has_tombstones:
            conflict_rows = conn.execute(
                """
                SELECT s.id, s.type, s.status, s.title, t.deleted_at
                FROM schedules s
                INNER JOIN schedule_tombstones t ON s.id = t.id
                """
            ).fetchall()
            for r in conflict_rows:
                report.tombstone_conflicts.append({
                    "id": r["id"],
                    "type": r["type"],
                    "status": r["status"],
                    "title": r["title"],
                    "deleted_at": r["deleted_at"],
                    "issue": "Item exists in both active schedules and schedule_tombstones tables",
                })

        # 2. Duplicate active items: Same type, title, and target time active at once
        dup_rows = conn.execute(
            """
            SELECT type, title, target_time_utc, count(*) as cnt, group_concat(id) as ids
            FROM schedules
            WHERE status IN ('active', 'ringing', 'snoozed')
            GROUP BY type, title, target_time_utc
            HAVING cnt > 1
            """
        ).fetchall()
        for r in dup_rows:
            report.duplicate_active_items.append({
                "type": r["type"],
                "title": r["title"],
                "target_time_utc": r["target_time_utc"],
                "count": r["cnt"],
                "ids": r["ids"].split(",") if r["ids"] else [],
                "issue": "Duplicate active schedules sharing identical type, title, and target_time",
            })

        # 3. Orphaned or corrupted items in schedules and tombstones
        sched_rows = conn.execute("SELECT * FROM schedules").fetchall()
        for r in sched_rows:
            item_id = str(r["id"])
            item_type = str(r["type"])
            status = str(r["status"])
            created_at = float(r["created_at_utc"])
            updated_at = float(r["updated_at_utc"])
            target_time = float(r["target_time_utc"])
            duration = int(r["duration_seconds"])
            recurrence = str(r["recurrence"])
            raw_meta = str(r["metadata"])

            # Timestamp validity
            if created_at <= 0 or updated_at < created_at or target_time < 0:
                report.orphaned_items.append({
                    "id": item_id,
                    "created_at_utc": created_at,
                    "updated_at_utc": updated_at,
                    "target_time_utc": target_time,
                    "issue": "Invalid or inverted timestamps (created_at <= 0 or updated_at < created_at)",
                })

            # Type validity
            if item_type not in VALID_TYPES:
                report.orphaned_items.append({
                    "id": item_id,
                    "type": item_type,
                    "issue": f"Invalid item type '{item_type}'. Must be one of {sorted(VALID_TYPES)}",
                })

            # Countdown duration validity
            if item_type == TYPE_COUNTDOWN and duration <= 0:
                report.orphaned_items.append({
                    "id": item_id,
                    "duration_seconds": duration,
                    "issue": "Countdown entity has non-positive duration_seconds",
                })

            # Stale un-transitioned active countdowns (> 24 hours in past)
            if item_type == TYPE_COUNTDOWN and status == "active" and (now_sec - target_time) > 86400:
                report.orphaned_items.append({
                    "id": item_id,
                    "target_time_utc": target_time,
                    "now_utc": now_sec,
                    "issue": "Active countdown expired more than 24 hours ago without transition",
                })

            # Metadata JSON validity
            try:
                json.loads(raw_meta)
            except Exception as json_err:
                report.orphaned_items.append({
                    "id": item_id,
                    "metadata": raw_meta,
                    "issue": f"Malformed metadata JSON: {json_err}",
                })

            # 4. Terminal-state violations
            if status not in VALID_STATUSES:
                report.terminal_state_violations.append({
                    "id": item_id,
                    "status": status,
                    "issue": f"Invalid status '{status}'. Must be one of {sorted(VALID_STATUSES)}",
                })
            elif status in TERMINAL_STATUSES:
                # If recurring item is dismissed/completed, it should have been auto-advanced to active
                # unless recurrence parsing failed or schedule was explicitly cancelled
                if recurrence and status in ("dismissed", "completed"):
                    report.terminal_state_violations.append({
                        "id": item_id,
                        "status": status,
                        "recurrence": recurrence,
                        "issue": f"Recurring schedule item left in terminal status '{status}' instead of auto-advancing",
                    })

        # Tombstone timestamps check
        if has_tombstones:
            tomb_rows = conn.execute("SELECT id, deleted_at FROM schedule_tombstones").fetchall()
            for tr in tomb_rows:
                if float(tr["deleted_at"]) <= 0:
                    report.orphaned_items.append({
                        "id": tr["id"],
                        "deleted_at": tr["deleted_at"],
                        "issue": "Tombstone has non-positive deleted_at timestamp",
                    })

    finally:
        conn.close()

    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Check consistency of schedules and tombstones tables.")
    parser.add_argument("--db-path", type=str, default=None, help="Path to schedules.db SQLite file")
    parser.add_argument("--json", action="store_true", help="Output machine-readable JSON")
    parser.add_argument("--verbose", action="store_true", help="Print verbose issue details")
    args = parser.parse_args()

    report = check_consistency(args.db_path)

    if args.json:
        print(json.dumps(report.to_dict(), indent=2, ensure_ascii=False))
        return 0 if report.is_clean else 1

    print(f"=== Schedule Consistency Report ===")
    print(f"Database: {report.db_path}")
    print(f"Total schedules: {report.total_schedules} (Active: {report.total_active})")
    print(f"Total tombstones: {report.total_tombstones}")
    print(f"Status: {'CLEAN (No issues found)' if report.is_clean else f'INCONSISTENT ({report.total_violations} violations)'}")

    if not report.is_clean:
        if report.tombstone_conflicts:
            print(f"\n[!] Tombstone Conflicts ({len(report.tombstone_conflicts)}):")
            for c in report.tombstone_conflicts:
                print(f"  - ID: {c['id']}, Type: {c['type']}, Status: {c['status']}, DeletedAt: {c['deleted_at']}")

        if report.duplicate_active_items:
            print(f"\n[!] Duplicate Active Items ({len(report.duplicate_active_items)}):")
            for d in report.duplicate_active_items:
                print(f"  - Type: {d['type']}, Title: '{d['title']}', TargetUTC: {d['target_time_utc']}, IDs: {d['ids']}")

        if report.orphaned_items:
            print(f"\n[!] Orphaned / Corrupted Items ({len(report.orphaned_items)}):")
            for o in report.orphaned_items:
                print(f"  - {o.get('id', 'N/A')}: {o.get('issue', o.get('message', ''))}")

        if report.terminal_state_violations:
            print(f"\n[!] Terminal-State Violations ({len(report.terminal_state_violations)}):")
            for v in report.terminal_state_violations:
                print(f"  - {v['id']}: Status '{v.get('status')}', Issue: {v['issue']}")

        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
