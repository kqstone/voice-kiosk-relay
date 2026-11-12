import hmac
from fastapi import Header, HTTPException, status
from app.config import settings


def extract_bearer_token(authorization: str | None = Header(default=None)) -> str:
    if not authorization:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header",
            headers={"WWW-Authenticate": "Bearer"},
        )
    parts = authorization.strip().split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Authorization header format. Expected Bearer <token>",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return parts[1]


def verify_push_token(authorization: str | None = Header(default=None)) -> str:
    token = extract_bearer_token(authorization)
    expected = settings.push_token
    if not expected or not hmac.compare_digest(token, expected):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid push token",
        )
    return token


def verify_device_token(authorization: str | None = Header(default=None)) -> str:
    token = extract_bearer_token(authorization)
    expected = settings.device_token
    if not expected or not hmac.compare_digest(token, expected):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid device token",
        )
    return token
