import hmac
from fastapi import Header, HTTPException, Query, status
from app.config import settings


def extract_bearer_token(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> str:
    if isinstance(authorization, str) and authorization.strip():
        parts = authorization.strip().split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            return parts[1]
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Authorization header format. Expected Bearer <token>",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if isinstance(token, str) and token.strip():
        return token.strip()
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Missing Authorization header",
        headers={"WWW-Authenticate": "Bearer"},
    )


def verify_push_token(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> str:
    extracted = extract_bearer_token(authorization=authorization, token=token)
    expected = settings.push_token
    if not expected or not hmac.compare_digest(extracted, expected):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid push token",
        )
    return extracted


def verify_device_token(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> str:
    extracted = extract_bearer_token(authorization=authorization, token=token)
    expected = settings.device_token
    if not expected or not hmac.compare_digest(extracted, expected):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid device token",
        )
    return extracted


def verify_device_or_push_token(
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> str:
    extracted = extract_bearer_token(authorization=authorization, token=token)
    valid_push = bool(settings.push_token and hmac.compare_digest(extracted, settings.push_token))
    valid_device = bool(settings.device_token and hmac.compare_digest(extracted, settings.device_token))
    if not (valid_push or valid_device):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid token",
        )
    return extracted
