from datetime import datetime, timezone
import json
import os
from pathlib import Path
from typing import Any
from app.config import settings


class BoardStorage:
    def __init__(self, base_dir: Path | None = None) -> None:
        self.base_dir = base_dir or settings.data_dir
        self.boards_dir = self.base_dir / "boards"
        self._ensure_dirs()

    def _ensure_dirs(self) -> None:
        self.boards_dir.mkdir(parents=True, exist_ok=True)

    def _board_path(self, board_id: str) -> Path:
        safe_id = Path(board_id).name
        return self.boards_dir / f"{safe_id}.json"

    def save_board(
        self,
        board_id: str,
        data: Any,
        source: str = "muse",
        timestamp: str | None = None,
    ) -> dict[str, Any]:
        self._ensure_dirs()
        now_iso = timestamp or datetime.now(timezone.utc).isoformat()
        record: dict[str, Any] = {
            "board_id": board_id,
            "data": data,
            "timestamp": now_iso,
            "source": source,
            "updated_at": now_iso,
        }
        target_path = self._board_path(board_id)
        temp_path = self.boards_dir / f"{target_path.name}.tmp"

        with open(temp_path, "w", encoding="utf-8") as f:
            json.dump(record, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())

        os.replace(temp_path, target_path)
        return record

    def get_board(self, board_id: str) -> dict[str, Any] | None:
        target_path = self._board_path(board_id)
        if not target_path.exists():
            return None
        with open(target_path, "r", encoding="utf-8") as f:
            return json.load(f)

    def list_boards(self) -> list[str]:
        if not self.boards_dir.exists():
            return []
        board_ids: list[str] = []
        for file_path in self.boards_dir.glob("*.json"):
            if not file_path.name.endswith(".tmp"):
                board_ids.append(file_path.stem)
        return sorted(board_ids)

    def delete_board(self, board_id: str) -> bool:
        target_path = self._board_path(board_id)
        if target_path.exists():
            target_path.unlink()
            return True
        return False


board_storage = BoardStorage()
