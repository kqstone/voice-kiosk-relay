"""SQLite storage engine for long-term memory.

Implements plan §4.2, §4.3 & §5 Phase 2:
- SQLite at DATA_DIR / memory.db with WAL mode
- Tables `memories` and `memory_tombstones` per plan DDL
- Zero-trust re-validation, content normalization, deduplication
- Single-value conflict arbitration and overwrite
- Incremental snapshot query with monotonic cursor math and tombstone tracking
"""

from __future__ import annotations

import logging
import re
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.config import settings
from app.sensitive import is_sensitive

logger = logging.getLogger(__name__)

# Valid categories per Android MemoryModels.kt & plan §4.3
VALID_CATEGORIES = {
    "music",
    "nickname",
    "routine",
    "device",
    "city",
    "family",
    "preference",
}

# Auto-writable low-risk categories
AUTO_WRITE_CATEGORIES = {
    "music",
    "nickname",
    "routine",
    "device",
    "city",
}

# High-risk categories requiring voice confirmation
CONFIRM_CATEGORIES = {
    "family",
    "preference",
}

# Status enum values per plan §4.1 & §4.3
STATUS_CREATED = "created"
STATUS_DEDUPLICATED = "deduplicated"
STATUS_UPDATED = "updated"
STATUS_REJECTED_SENSITIVE = "rejected_sensitive"
STATUS_REJECTED_NEEDS_CONFIRMATION = "rejected_needs_confirmation"
STATUS_SKIPPED_CONFLICT = "skipped_conflict"


def normalize_content(content: str) -> str:
    r"""Normalize content by removing whitespace and Chinese/English punctuation and lowercasing.

    Per plan §4.3 item 3:
    re.sub(r"[\s，,。.！!？?、；;：:\"'“”‘’]", "", content).lower()
    """
    return re.sub(r"[\s，,。.！!？?、；;：:\"'“”‘’]", "", content).lower()


@dataclass
class MemoryRecord:
    id: int
    key: str
    content: str
    category: str
    created_at: float
    updated_at: float

    def to_wire_item(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "key": self.key,
            "content": self.content,
            "updated_at": self.updated_at,
        }


class MemoryStore:
    """SQLite engine for long-term memory persistence and incremental synchronization."""

    def __init__(self, db_path: Path | str | None = None) -> None:
        self._db_path = db_path
        self._initialized = False
        self._init_lock = threading.Lock()

    @property
    def db_path(self) -> Path | str:
        if self._db_path is not None:
            return self._db_path
        return settings.memory_db_path

    def _raw_connect(self) -> sqlite3.Connection:
        """Create a new raw SQLite connection configured with WAL and busy timeout."""
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
        """Create tables and indexes per plan §4.2 DDL."""
        if str(self.db_path) != ":memory:":
            Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)

        conn = self._raw_connect()
        try:
            conn.executescript(
                """
                -- Active memories table
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    memory_key TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL,
                    category TEXT NOT NULL,
                    normalized_content TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_memories_key ON memories (memory_key);
                CREATE INDEX IF NOT EXISTS idx_memories_norm ON memories (normalized_content);
                CREATE INDEX IF NOT EXISTS idx_memories_updated ON memories (updated_at DESC, id DESC);

                -- Tombstones table (for incremental snapshot deleted_ids)
                CREATE TABLE IF NOT EXISTS memory_tombstones (
                    id INTEGER PRIMARY KEY,
                    deleted_at REAL NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_tombstones_deleted_at ON memory_tombstones (deleted_at DESC);
                """
            )
        finally:
            conn.close()

    @staticmethod
    def _row_to_item(row: sqlite3.Row | dict[str, Any]) -> dict[str, Any]:
        """Convert a database row into the wire item format for Android/API."""
        key = row["memory_key"] if "memory_key" in row.keys() else row.get("key", "")
        return {
            "id": int(row["id"]),
            "key": str(key),
            "content": str(row["content"]),
            "updated_at": float(row["updated_at"]),
        }

    @staticmethod
    def _parse_cursor(cursor_str: str) -> tuple[float, int] | None:
        """Parse cursor string '{timestamp}_{id}' or '{timestamp}' into (timestamp, id)."""
        try:
            s = cursor_str.strip()
            if not s:
                return None
            if "_" in s:
                parts = s.split("_", 1)
                return (float(parts[0]), int(parts[1]))
            return (float(s), 0)
        except Exception:
            return None

    def submit_candidates(self, candidates: list[dict[str, Any]]) -> dict[str, Any]:
        """Process a batch of memory candidates with zero-trust validation and conflict arbitration.

        Returns matching plan §4.1:
        {
            "results": [
                {"id": "...", "status": "...", "item": {...}}, ...
            ],
            "changed": True/False
        }
        """
        results: list[dict[str, Any]] = []
        any_changed = False

        self._ensure_init()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            for cand in candidates:
                cand_id = str(cand.get("id", ""))
                content = str(cand.get("content", "")).strip()
                category = str(cand.get("category", "")).strip()
                origin = str(cand.get("origin", "auto")).strip().lower()
                key = str(cand.get("key", "")).strip()

                # 1. Sensitive filter validation (zero trust: never log sensitive content in full)
                if is_sensitive(content) or (key and is_sensitive(key)):
                    logger.warning(
                        "Candidate memory rejected: sensitive content detected (candidate_id=%s, length=%d)",
                        cand_id,
                        len(content),
                    )
                    results.append({"id": cand_id, "status": STATUS_REJECTED_SENSITIVE})
                    continue

                # 2. Category validation & confirmation requirements
                if category not in VALID_CATEGORIES:
                    logger.info(
                        "Candidate memory rejected: invalid category '%s' (candidate_id=%s)",
                        category,
                        cand_id,
                    )
                    results.append({"id": cand_id, "status": STATUS_REJECTED_NEEDS_CONFIRMATION})
                    continue

                if category in CONFIRM_CATEGORIES and origin != "confirmed":
                    logger.info(
                        "Candidate memory rejected: unconfirmed category '%s' (candidate_id=%s)",
                        category,
                        cand_id,
                    )
                    results.append({"id": cand_id, "status": STATUS_REJECTED_NEEDS_CONFIRMATION})
                    continue

                # 3. Content normalization
                norm_content = normalize_content(content)
                if not norm_content:
                    logger.info(
                        "Candidate memory rejected: empty content after normalization (candidate_id=%s)",
                        cand_id,
                    )
                    results.append({"id": cand_id, "status": STATUS_REJECTED_NEEDS_CONFIRMATION})
                    continue

                # 4. Conflict arbitration & deduplication
                now = time.time()
                if key:
                    # Single-valued fact (key is non-empty)
                    cur = conn.execute("SELECT * FROM memories WHERE memory_key = ? LIMIT 1", (key,))
                    existing = cur.fetchone()
                    if existing:
                        if existing["normalized_content"] == norm_content:
                            # Harmless duplicate with identical content
                            results.append({
                                "id": cand_id,
                                "status": STATUS_DEDUPLICATED,
                                "item": self._row_to_item(existing),
                            })
                        else:
                            # Existing record has different content for this single-value key
                            if origin == "confirmed":
                                # User confirmed overwrite
                                conn.execute(
                                    """
                                    UPDATE memories
                                    SET content = ?, category = ?, normalized_content = ?, updated_at = ?
                                    WHERE id = ?
                                    """,
                                    (content, category, norm_content, now, existing["id"]),
                                )
                                updated_row = conn.execute(
                                    "SELECT * FROM memories WHERE id = ?", (existing["id"],)
                                ).fetchone()
                                any_changed = True
                                results.append({
                                    "id": cand_id,
                                    "status": STATUS_UPDATED,
                                    "item": self._row_to_item(updated_row),
                                })
                            else:
                                # origin == "auto" -> do not overwrite, prompt confirmation
                                results.append({
                                    "id": cand_id,
                                    "status": STATUS_SKIPPED_CONFLICT,
                                })
                    else:
                        # No existing record with this key: check if same normalized_content exists
                        cur = conn.execute(
                            "SELECT * FROM memories WHERE normalized_content = ? LIMIT 1",
                            (norm_content,),
                        )
                        existing_norm = cur.fetchone()
                        if existing_norm:
                            results.append({
                                "id": cand_id,
                                "status": STATUS_DEDUPLICATED,
                                "item": self._row_to_item(existing_norm),
                            })
                        else:
                            cur = conn.execute(
                                """
                                INSERT INTO memories
                                (memory_key, content, category, normalized_content, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                                (key, content, category, norm_content, now, now),
                            )
                            new_id = cur.lastrowid
                            new_row = conn.execute(
                                "SELECT * FROM memories WHERE id = ?", (new_id,)
                            ).fetchone()
                            any_changed = True
                            results.append({
                                "id": cand_id,
                                "status": STATUS_CREATED,
                                "item": self._row_to_item(new_row),
                            })
                else:
                    # Multi-valued fact (key == "")
                    cur = conn.execute(
                        "SELECT * FROM memories WHERE normalized_content = ? LIMIT 1",
                        (norm_content,),
                    )
                    existing = cur.fetchone()
                    if existing:
                        results.append({
                            "id": cand_id,
                            "status": STATUS_DEDUPLICATED,
                            "item": self._row_to_item(existing),
                        })
                    else:
                        cur = conn.execute(
                            """
                            INSERT INTO memories
                            (memory_key, content, category, normalized_content, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                            ("", content, category, norm_content, now, now),
                        )
                        new_id = cur.lastrowid
                        new_row = conn.execute(
                            "SELECT * FROM memories WHERE id = ?", (new_id,)
                        ).fetchone()
                        any_changed = True
                        results.append({
                            "id": cand_id,
                            "status": STATUS_CREATED,
                            "item": self._row_to_item(new_row),
                        })

            conn.commit()
            return {"results": results, "changed": any_changed}
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def submit_candidate(self, candidate: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
        """Convenience method to submit a single candidate."""
        res = self.submit_candidates([candidate])
        item_res = res["results"][0]
        return (item_res["status"], item_res.get("item"))

    def delete_memory(self, memory_id: int) -> bool:
        """Delete an active memory by ID and record a tombstone.

        Returns True if the item was found and deleted, False otherwise.
        """
        self._ensure_init()
        now = time.time()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
            if cur.rowcount > 0:
                conn.execute(
                    "INSERT OR REPLACE INTO memory_tombstones (id, deleted_at) VALUES (?, ?)",
                    (memory_id, now),
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

    def get_memory(self, memory_id: int) -> dict[str, Any] | None:
        """Get a single active memory item by ID."""
        self._ensure_init()
        conn = self._connect()
        try:
            row = conn.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
            if row:
                return {
                    "id": int(row["id"]),
                    "key": str(row["memory_key"]),
                    "content": str(row["content"]),
                    "category": str(row["category"]),
                    "created_at": float(row["created_at"]),
                    "updated_at": float(row["updated_at"]),
                }
            return None
        finally:
            conn.close()

    def list_memories(
        self,
        limit: int = 100,
        offset: int = 0,
        category: str | None = None,
    ) -> list[dict[str, Any]]:
        """List active memories ordered by updated_at DESC, id DESC."""
        self._ensure_init()
        conn = self._connect()
        try:
            if category:
                rows = conn.execute(
                    """
                    SELECT * FROM memories
                    WHERE category = ?
                    ORDER BY updated_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """,
                    (category, limit, offset),
                ).fetchall()
            else:
                rows = conn.execute(
                    """
                    SELECT * FROM memories
                    ORDER BY updated_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """,
                    (limit, offset),
                ).fetchall()
            return [
                {
                    "id": int(r["id"]),
                    "key": str(r["memory_key"]),
                    "content": str(r["content"]),
                    "category": str(r["category"]),
                    "created_at": float(r["created_at"]),
                    "updated_at": float(r["updated_at"]),
                }
                for r in rows
            ]
        finally:
            conn.close()

    def count_memories(self, category: str | None = None) -> int:
        """Count active memories."""
        self._ensure_init()
        conn = self._connect()
        try:
            if category:
                row = conn.execute(
                    "SELECT COUNT(*) FROM memories WHERE category = ?", (category,)
                ).fetchone()
            else:
                row = conn.execute("SELECT COUNT(*) FROM memories").fetchone()
            return int(row[0]) if row else 0
        finally:
            conn.close()

    def prune_tombstones(self, max_age_days: int = 30) -> int:
        """Remove tombstones older than max_age_days. Returns count of deleted rows."""
        self._ensure_init()
        cutoff = time.time() - (max_age_days * 86400.0)
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("DELETE FROM memory_tombstones WHERE deleted_at < ?", (cutoff,))
            conn.commit()
            return cur.rowcount
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def get_snapshot(self, since: str = "", max_age_days: int = 30) -> dict[str, Any]:
        """Fetch incremental snapshot for synchronization per plan §4.1 & §4.2.

        Returns:
        {
            "items": [...],
            "deleted_ids": [...],
            "cursor": "...",
            "reset": False/True
        }
        """
        self._ensure_init()
        conn = self._connect()
        try:
            # 1. Compute current latest cursor from active memories and tombstones
            max_mem = conn.execute(
                "SELECT updated_at, id FROM memories ORDER BY updated_at DESC, id DESC LIMIT 1"
            ).fetchone()
            max_tomb = conn.execute(
                "SELECT deleted_at, id FROM memory_tombstones ORDER BY deleted_at DESC, id DESC LIMIT 1"
            ).fetchone()

            mem_tuple = (float(max_mem["updated_at"]), int(max_mem["id"])) if max_mem else (0.0, 0)
            tomb_tuple = (float(max_tomb["deleted_at"]), int(max_tomb["id"])) if max_tomb else (0.0, 0)

            latest_tuple = max(mem_tuple, tomb_tuple)
            latest_cursor = (
                f"{latest_tuple[0]}_{latest_tuple[1]}" if latest_tuple > (0.0, 0) else ""
            )

            # Helper for full snapshot reset response
            def full_snapshot() -> dict[str, Any]:
                rows = conn.execute(
                    "SELECT id, memory_key, content, updated_at FROM memories ORDER BY updated_at ASC, id ASC"
                ).fetchall()
                items = [self._row_to_item(r) for r in rows]
                return {
                    "items": items,
                    "deleted_ids": [],
                    "cursor": latest_cursor,
                    "reset": True,
                }

            since_str = since.strip() if since else ""
            if not since_str:
                return full_snapshot()

            parsed = self._parse_cursor(since_str)
            if parsed is None:
                # Corrupted or unparseable cursor -> reset
                return full_snapshot()

            cursor_ts, cursor_id = parsed
            now = time.time()
            cutoff = now - (max_age_days * 86400.0)

            if cursor_ts < cutoff:
                # Cursor older than retention period -> reset
                return full_snapshot()

            # Incremental query: items updated after cursor (monotonic tuple comparison)
            item_rows = conn.execute(
                """
                SELECT id, memory_key, content, updated_at
                FROM memories
                WHERE (updated_at > ?) OR (updated_at = ? AND id > ?)
                ORDER BY updated_at ASC, id ASC
                """,
                (cursor_ts, cursor_ts, cursor_id),
            ).fetchall()

            # Incremental query: tombstones deleted after cursor
            tomb_rows = conn.execute(
                """
                SELECT id, deleted_at
                FROM memory_tombstones
                WHERE (deleted_at > ?) OR (deleted_at = ? AND id > ?)
                ORDER BY deleted_at ASC, id ASC
                """,
                (cursor_ts, cursor_ts, cursor_id),
            ).fetchall()

            deleted_ids = [int(r["id"]) for r in tomb_rows]
            items = [self._row_to_item(r) for r in item_rows]

            next_cursor = latest_cursor if latest_cursor else since_str

            return {
                "items": items,
                "deleted_ids": deleted_ids,
                "cursor": next_cursor,
                "reset": False,
            }
        finally:
            conn.close()

    def clear_all(self) -> None:
        """Clear all memories and tombstones (mainly for testing)."""
        self._ensure_init()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute("DELETE FROM memories")
            conn.execute("DELETE FROM memory_tombstones")
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()


memory_store = MemoryStore()
