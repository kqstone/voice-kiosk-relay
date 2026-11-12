from fastapi import Depends, FastAPI
from app.auth import verify_push_token

app = FastAPI(title="Voice Kiosk Relay Bridge", version="0.1.0")


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ok": True}


@app.post("/api/v1/push/{board_id}", dependencies=[Depends(verify_push_token)])
def push_board_skeleton(board_id: str) -> dict[str, str | bool]:
    return {"ok": True, "detail": "push endpoint skeleton"}
