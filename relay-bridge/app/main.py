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
from app.storage import board_storage

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

    boards_summary = format_board_summary(board_storage)
    system_prompt = build_chat_system_prompt(boards_summary)

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": payload.text.strip()},
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
