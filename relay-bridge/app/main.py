from datetime import datetime, timezone
import threading
import time
from typing import Any
from fastapi import Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from app.auth import (
    verify_device_or_push_token,
    verify_device_token,
    verify_push_token,
)
from app.config import settings
from app.dashboard import parse_board_to_components, render_dashboard
from app.llm import (
    BridgeLLMError,
    build_chat_system_prompt,
    format_board_summary,
    format_memory_summary,
    llm_client,
)
from app.memory_store import memory_store
from app.queue import conversation_queue
from app.router import route_question
from app.storage import board_storage, routing_config_storage

app = FastAPI(title="Voice Kiosk Relay Bridge", version="0.1.0")


class PushBoardRequest(BaseModel):
    data: Any = None
    updated_at: str | None = None
    source: str = "muse"


class ToggleItemRequest(BaseModel):
    completed: bool | None = None


class ToggleItemResponse(BaseModel):
    ok: bool = True
    board_id: str
    item_id: str
    completed: bool


class ChatRequest(BaseModel):
    text: str
    session_id: str | None = None


class ChatResponse(BaseModel):
    reply: str
    route: str = "local"
    conversation_id: str | None = None


class MuseKeywordItem(BaseModel):
    keyword: str
    topic: str = ""
    note: str = ""


class RoutingConfigRequest(BaseModel):
    version: int = 1
    updated_at: str | None = None
    muse_keywords: list[MuseKeywordItem] = []


class RoutingConfigResponse(BaseModel):
    ok: bool = True
    version: int


class CreateConversationRequest(BaseModel):
    text: str
    session_id: str | None = None
    keywords: list[str] = []
    reason: str = ""


class CreateConversationResponse(BaseModel):
    conversation_id: str
    status: str = "pending"


class ReplyConversationRequest(BaseModel):
    reply: str


ACK_TEMPLATES: list[str] = [
    "好，我去问问 Muse，稍等一下。",
    "正在问，稍等。",
    "这个问题我问问 Muse，马上回来。",
    "稍等，我去问问 Muse。",
]

_ack_index: int = 0


def get_next_ack() -> str:
    global _ack_index
    msg = ACK_TEMPLATES[_ack_index % len(ACK_TEMPLATES)]
    _ack_index += 1
    return msg


class ConnectionManager:
    def __init__(self) -> None:
        self.active_connections: list[WebSocket] = []

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket) -> None:
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast(self, message: dict[str, Any]) -> None:
        for connection in list(self.active_connections):
            try:
                await connection.send_json(message)
            except Exception:
                self.disconnect(connection)


manager = ConnectionManager()


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ok": True}


@app.post(
    "/api/v1/chat",
    response_model=ChatResponse,
    dependencies=[Depends(verify_device_token)],
)
async def chat_endpoint(payload: ChatRequest) -> ChatResponse:
    if not payload.text or not payload.text.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Text cannot be empty",
        )

    clean_text = payload.text.strip()
    keyword_config = routing_config_storage.get_config()
    route_decision = await route_question(clean_text, keyword_config=keyword_config)
    decision_route = route_decision.get("route", "local")

    if decision_route == "muse":
        keywords = route_decision.get("keywords", [])
        reason = route_decision.get("reason", "")
        conv_id = conversation_queue.create(
            text=clean_text,
            session_id=payload.session_id,
            keywords=keywords,
            reason=reason,
        )
        ack = get_next_ack()
        return ChatResponse(reply=ack, route="muse", conversation_id=conv_id)

    boards_summary = format_board_summary(board_storage)
    memory_summary = format_memory_summary(memory_store, user_query=clean_text)
    system_prompt = build_chat_system_prompt(
        boards_summary=boards_summary,
        memory_summary=memory_summary,
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": clean_text},
    ]

    try:
        reply = await llm_client.chat(messages=messages, temperature=0.7)
    except BridgeLLMError as e:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Bridge LLM error: {e}",
        )
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Unexpected bridge error: {e}",
        )

    return ChatResponse(reply=reply, route="local")


@app.post(
    "/api/v1/conversations",
    response_model=CreateConversationResponse,
    dependencies=[Depends(verify_device_or_push_token)],
)
def create_conversation(
    payload: CreateConversationRequest,
) -> CreateConversationResponse:
    if not payload.text or not payload.text.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Text cannot be empty",
        )
    conv_id = conversation_queue.create(
        text=payload.text.strip(),
        session_id=payload.session_id,
        keywords=payload.keywords,
        reason=payload.reason,
    )
    return CreateConversationResponse(conversation_id=conv_id, status="pending")


@app.get(
    "/api/v1/conversations/pending",
    dependencies=[Depends(verify_device_or_push_token)],
)
def list_pending_conversations() -> list[dict[str, Any]]:
    return conversation_queue.list_pending()


@app.get(
    "/api/v1/conversations/{conversation_id}",
    dependencies=[Depends(verify_device_or_push_token)],
)
def get_conversation(conversation_id: str) -> dict[str, Any]:
    conv = conversation_queue.get(conversation_id)
    if conv is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Conversation '{conversation_id}' not found",
        )
    return conv


@app.post(
    "/api/v1/conversations/{conversation_id}/reply",
    dependencies=[Depends(verify_push_token)],
)
async def reply_conversation(
    conversation_id: str,
    payload: ReplyConversationRequest,
) -> dict[str, Any]:
    existing = conversation_queue.get(conversation_id, auto_timeout=False)
    if existing is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Conversation '{conversation_id}' not found",
        )
    updated = conversation_queue.reply(conversation_id, payload.reply)
    session_id = (
        updated.get("session_id", "")
        if updated
        else existing.get("session_id", "")
    )
    await manager.broadcast({
        "type": "conversation_reply",
        "conversation_id": conversation_id,
        "session_id": session_id,
        "reply": payload.reply,
    })
    return {"ok": True, "status": "answered"}


@app.post("/api/v1/push/{board_id}", dependencies=[Depends(verify_push_token)])
async def push_board(
    board_id: str,
    payload: PushBoardRequest = PushBoardRequest(),
) -> dict[str, Any]:
    board_data = payload.data if payload.data is not None else {}
    board_storage.save_board(
        board_id=board_id,
        data=board_data,
        source=payload.source,
        timestamp=payload.updated_at,
    )
    await manager.broadcast({
        "type": "board_update",
        "board_id": board_id,
    })
    return {"ok": True, "board_id": board_id}


# Alias for backward-compatibility with Phase 1a tests
push_board_skeleton = push_board


@app.get("/api/v1/boards/{board_id}", dependencies=[Depends(verify_device_or_push_token)])
def get_board(board_id: str) -> dict[str, Any]:
    board = board_storage.get_board(board_id)
    if board is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Board '{board_id}' not found",
        )
    return board


@app.get("/api/v1/boards", dependencies=[Depends(verify_device_or_push_token)])
def list_boards() -> list[str]:
    return board_storage.list_boards()


@app.post(
    "/api/v1/boards/{board_id}/items/{item_id}/toggle",
    response_model=ToggleItemResponse,
    dependencies=[Depends(verify_device_or_push_token)],
)
async def toggle_board_item(
    board_id: str,
    item_id: str,
    payload: ToggleItemRequest = ToggleItemRequest(),
) -> ToggleItemResponse:
    board = board_storage.get_board(board_id)
    if board is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Board '{board_id}' not found",
        )

    data = board.get("data")
    target_completed = payload.completed

    found = False
    new_state = False

    items_list: list[Any] | None = None
    if isinstance(data, list):
        items_list = data
    elif isinstance(data, dict):
        for candidate_key in ["tasks", "items", "checklist", "todo", "content", "list"]:
            val = data.get(candidate_key)
            if isinstance(val, list):
                items_list = val
                break

    if items_list is not None:
        for idx, it in enumerate(items_list):
            is_match = False
            if isinstance(it, dict):
                dict_id = str(it.get("id") or "")
                if dict_id and dict_id == item_id:
                    is_match = True
                elif item_id in (f"item_{idx}", str(idx)):
                    is_match = True
            elif isinstance(it, str):
                if item_id in (f"item_{idx}", str(idx), it):
                    is_match = True

            if is_match:
                found = True
                if isinstance(it, dict):
                    curr = bool(it.get("completed") or it.get("done") or it.get("checked"))
                    new_state = not curr if target_completed is None else bool(target_completed)
                    it["completed"] = new_state
                    if "id" not in it:
                        it["id"] = item_id
                else:
                    curr = False
                    new_state = not curr if target_completed is None else bool(target_completed)
                    assigned_id = item_id if (item_id.startswith("item_") or not item_id.isdigit()) else f"item_{idx}"
                    items_list[idx] = {
                        "id": assigned_id,
                        "text": it,
                        "completed": new_state,
                    }
                break

    if not found:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Item '{item_id}' not found in board '{board_id}'",
        )

    now_iso = datetime.now(timezone.utc).isoformat()
    source = str(board.get("source", "muse"))
    board_storage.save_board(
        board_id=board_id,
        data=data,
        source=source,
        timestamp=now_iso,
    )

    await manager.broadcast({
        "type": "board_update",
        "board_id": board_id,
        "action": "item_toggle",
        "item_id": item_id,
        "completed": new_state,
    })

    return ToggleItemResponse(
        ok=True,
        board_id=board_id,
        item_id=item_id,
        completed=new_state,
    )


@app.post(
    "/api/v1/config/routing",
    response_model=RoutingConfigResponse,
    dependencies=[Depends(verify_push_token)],
)
def save_routing_config(payload: RoutingConfigRequest) -> RoutingConfigResponse:
    stored = payload.model_dump() if hasattr(payload, "model_dump") else payload.dict()
    routing_config_storage.save_config(stored)
    return RoutingConfigResponse(ok=True, version=payload.version)


@app.get(
    "/api/v1/config/routing",
    dependencies=[Depends(verify_device_or_push_token)],
)
def get_routing_config() -> dict[str, Any]:
    return routing_config_storage.get_config()


class CandidatesRequest(BaseModel):
    candidates: list[dict[str, Any]] = []


class DeleteMemoryRequest(BaseModel):
    id: int | None = None


_idempotency_cache: dict[str, tuple[float, dict[str, Any]]] = {}
_idempotency_lock = threading.Lock()
IDEMPOTENCY_TTL_SECONDS = 86400  # 24 hours
MAX_IDEMPOTENCY_ENTRIES = 1000


@app.get(
    "/api/v1/memory/snapshot",
    dependencies=[Depends(verify_device_token)],
)
def get_memory_snapshot(
    since: str = "",
) -> dict[str, Any]:
    return memory_store.get_snapshot(since=since)


@app.post(
    "/api/v1/memory/candidates",
    dependencies=[Depends(verify_device_token)],
)
def submit_memory_candidates(
    payload: CandidatesRequest,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
) -> dict[str, Any]:
    key = idempotency_key.strip() if idempotency_key else ""
    if key:
        with _idempotency_lock:
            now = time.time()
            expired = [k for k, (ts, _) in _idempotency_cache.items() if now - ts > IDEMPOTENCY_TTL_SECONDS]
            for k in expired:
                del _idempotency_cache[k]
            if key in _idempotency_cache:
                return _idempotency_cache[key][1]

    raw_candidates = [
        c if isinstance(c, dict) else c.model_dump()
        for c in payload.candidates
    ]
    res = memory_store.submit_candidates(raw_candidates)

    if key:
        with _idempotency_lock:
            if len(_idempotency_cache) >= MAX_IDEMPOTENCY_ENTRIES:
                oldest_k = min(_idempotency_cache.keys(), key=lambda k: _idempotency_cache[k][0])
                del _idempotency_cache[oldest_k]
            _idempotency_cache[key] = (time.time(), res)

    return res


@app.get(
    "/api/v1/memories",
    dependencies=[Depends(verify_device_token)],
)
def list_memories(
    limit: int = 100,
    offset: int = 0,
    category: str | None = None,
) -> list[dict[str, Any]]:
    return memory_store.list_memories(limit=limit, offset=offset, category=category)


@app.get(
    "/api/v1/memories/{memory_id}",
    dependencies=[Depends(verify_device_token)],
)
def get_memory_by_id(memory_id: int) -> dict[str, Any]:
    mem = memory_store.get_memory(memory_id)
    if mem is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Memory '{memory_id}' not found",
        )
    return mem


@app.delete(
    "/api/v1/memories/{memory_id}",
    dependencies=[Depends(verify_device_token)],
)
def delete_memory_by_path(memory_id: int) -> dict[str, Any]:
    deleted = memory_store.delete_memory(memory_id)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Memory '{memory_id}' not found",
        )
    return {"ok": True, "id": memory_id}


@app.delete(
    "/api/v1/memories",
    dependencies=[Depends(verify_device_token)],
)
def delete_memory(
    id: int | None = Query(default=None),
    payload: DeleteMemoryRequest | None = None,
) -> dict[str, Any]:
    target_id = id
    if target_id is None and payload is not None:
        target_id = payload.id
    if target_id is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Memory ID is required via query param '?id=...' or request body '{\"id\": ...}'",
        )
    deleted = memory_store.delete_memory(target_id)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Memory '{target_id}' not found",
        )
    return {"ok": True, "id": target_id}


@app.get("/", response_class=HTMLResponse)
def dashboard() -> HTMLResponse:
    board_ids = board_storage.list_boards()
    boards: list[dict[str, Any]] = []
    for bid in board_ids:
        b = board_storage.get_board(bid)
        if b is not None:
            boards.append(b)
    memories = memory_store.list_memories() if settings.memory_enabled else []
    html_content = render_dashboard(boards, memories=memories)
    return HTMLResponse(content=html_content)


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    await manager.connect(websocket)
    try:
        await websocket.send_json({
            "type": "welcome",
            "message": "Voice Kiosk Relay Bridge WS connected",
        })
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket)
    except Exception:
        manager.disconnect(websocket)
