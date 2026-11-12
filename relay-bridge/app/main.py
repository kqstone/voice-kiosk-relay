from typing import Any
from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect, status
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from app.auth import (
    verify_device_or_push_token,
    verify_device_token,
    verify_push_token,
)
from app.dashboard import render_dashboard
from app.llm import (
    BridgeLLMError,
    build_chat_system_prompt,
    format_board_summary,
    llm_client,
)
from app.queue import conversation_queue
from app.router import route_question
from app.storage import board_storage, routing_config_storage

app = FastAPI(title="Voice Kiosk Relay Bridge", version="0.1.0")


class PushBoardRequest(BaseModel):
    data: Any = None
    updated_at: str | None = None
    source: str = "muse"


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
    system_prompt = build_chat_system_prompt(boards_summary)

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


@app.get("/", response_class=HTMLResponse)
def dashboard() -> HTMLResponse:
    board_ids = board_storage.list_boards()
    boards: list[dict[str, Any]] = []
    for bid in board_ids:
        b = board_storage.get_board(bid)
        if b is not None:
            boards.append(b)
    html_content = render_dashboard(boards)
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
