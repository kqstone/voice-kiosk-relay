from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
from typing import Any

from app.config import settings


class ConversationQueue:
    def __init__(self, base_dir: Path | None = None) -> None:
        self._base_dir = base_dir
        self._ensure_dirs()

    @property
    def base_dir(self) -> Path:
        return self._base_dir or settings.data_dir

    @base_dir.setter
    def base_dir(self, value: Path | None) -> None:
        self._base_dir = value

    @property
    def conversations_dir(self) -> Path:
        return self.base_dir / "conversations"

    @property
    def index_path(self) -> Path:
        return self.conversations_dir / "pending.json"

    def _ensure_dirs(self) -> None:
        self.conversations_dir.mkdir(parents=True, exist_ok=True)

    def _conversation_path(self, conversation_id: str) -> Path:
        safe_id = Path(conversation_id).name
        return self.conversations_dir / f"{safe_id}.json"

    def _load_pending_ids(self) -> list[str]:
        if not self.index_path.exists():
            return []
        try:
            with open(self.index_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    return [str(x) for x in data]
        except Exception:
            pass
        return []

    def _save_pending_ids(self, ids: list[str]) -> None:
        self._ensure_dirs()
        temp_path = self.conversations_dir / f"{self.index_path.name}.tmp"
        with open(temp_path, "w", encoding="utf-8") as f:
            json.dump(ids, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp_path, self.index_path)

    def _load_conversation(self, conversation_id: str) -> dict[str, Any] | None:
        if not conversation_id or conversation_id == "pending":
            return None
        path = self._conversation_path(conversation_id)
        if not path.exists():
            return None
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    return data
        except Exception:
            pass
        return None

    def _save_conversation(self, conv: dict[str, Any]) -> None:
        self._ensure_dirs()
        conv_id = conv["id"]
        target_path = self._conversation_path(conv_id)
        temp_path = self.conversations_dir / f"{target_path.name}.tmp"
        with open(temp_path, "w", encoding="utf-8") as f:
            json.dump(conv, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp_path, target_path)

    def _add_pending_id(self, conv_id: str) -> None:
        ids = self._load_pending_ids()
        if conv_id not in ids:
            ids.append(conv_id)
            self._save_pending_ids(ids)

    def _remove_pending_id(self, conv_id: str) -> None:
        ids = self._load_pending_ids()
        if conv_id in ids:
            ids = [x for x in ids if x != conv_id]
            self._save_pending_ids(ids)

    def create(
        self,
        text: str,
        session_id: str | None = None,
        keywords: list[str] | None = None,
        reason: str = "",
    ) -> str:
        self._ensure_dirs()
        conv_id = secrets.token_hex(6)
        while self._conversation_path(conv_id).exists():
            conv_id = secrets.token_hex(6)

        now_iso = datetime.now(timezone.utc).isoformat()
        record: dict[str, Any] = {
            "id": conv_id,
            "text": text,
            "session_id": session_id or "",
            "keywords": list(keywords) if keywords is not None else [],
            "reason": reason or "",
            "status": "pending",
            "reply": "",
            "created_at": now_iso,
            "updated_at": now_iso,
        }
        self._save_conversation(record)
        self._add_pending_id(conv_id)
        return conv_id

    def mark_timed_out(
        self,
        conversation_id: str,
        fallback_reply: str = "Muse 现在没回，稍后再试",
    ) -> dict[str, Any] | None:
        conv = self._load_conversation(conversation_id)
        if conv is None:
            return None
        conv["status"] = "timed_out"
        if not conv.get("reply"):
            conv["reply"] = fallback_reply
        conv["updated_at"] = datetime.now(timezone.utc).isoformat()
        self._save_conversation(conv)
        self._remove_pending_id(conversation_id)
        return conv

    def reply(
        self,
        conversation_id: str,
        reply_text: str,
    ) -> dict[str, Any] | None:
        conv = self._load_conversation(conversation_id)
        if conv is None:
            return None
        conv["status"] = "answered"
        conv["reply"] = reply_text
        conv["updated_at"] = datetime.now(timezone.utc).isoformat()
        self._save_conversation(conv)
        self._remove_pending_id(conversation_id)
        return conv

    def get(
        self,
        conversation_id: str,
        auto_timeout: bool = True,
    ) -> dict[str, Any] | None:
        conv = self._load_conversation(conversation_id)
        if conv is None:
            return None
        if auto_timeout and conv.get("status") == "pending":
            created_at_str = conv.get("created_at")
            if created_at_str:
                try:
                    dt = datetime.fromisoformat(created_at_str)
                    if dt.tzinfo is None:
                        dt = dt.replace(tzinfo=timezone.utc)
                    if (datetime.now(timezone.utc) - dt).total_seconds() > 120:
                        return self.mark_timed_out(
                            conversation_id,
                            fallback_reply="Muse 现在没回，稍后再试",
                        )
                except Exception:
                    pass
        return conv

    def list_pending(self) -> list[dict[str, Any]]:
        self._ensure_dirs()
        pending_ids = self._load_pending_ids()
        valid_ids: list[str] = []
        pending_list: list[dict[str, Any]] = []
        changed = False

        for cid in pending_ids:
            conv = self._load_conversation(cid)
            if conv is None:
                changed = True
                continue
            if conv.get("status") == "pending":
                created_at_str = conv.get("created_at")
                is_timed_out = False
                if created_at_str:
                    try:
                        dt = datetime.fromisoformat(created_at_str)
                        if dt.tzinfo is None:
                            dt = dt.replace(tzinfo=timezone.utc)
                        if (datetime.now(timezone.utc) - dt).total_seconds() > 120:
                            is_timed_out = True
                    except Exception:
                        pass
                if is_timed_out:
                    self.mark_timed_out(
                        cid,
                        fallback_reply="Muse 现在没回，稍后再试",
                    )
                    changed = True
                else:
                    valid_ids.append(cid)
                    pending_list.append(conv)
            else:
                changed = True

        if changed:
            self._save_pending_ids(valid_ids)

        pending_list.sort(key=lambda c: c.get("created_at", ""))
        return pending_list


conversation_queue = ConversationQueue()
