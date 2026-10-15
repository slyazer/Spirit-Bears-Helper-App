"""Bearer-token session authentication for the bridge API.

Users sign in with a **username and password** (see ``POST /api/v1/auth/login``)
and receive an opaque session token. Every protected route requires that token
as ``Authorization: Bearer <token>``. There is no shared, static API token to
copy around any more.

Public routes: ``GET /health`` and the ``/api/v1/auth/*`` endpoints that
establish a session. Everything else under ``/api/v1`` requires a live session.
"""

from __future__ import annotations

from fastapi import Depends, HTTPException, Request, status

from .sessions import Session, SessionStore
from .state import get_session_store


def client_ip(request: Request) -> str:
    """Best-effort client address (honours a trusted proxy's X-Forwarded-For)."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value:
        return None
    return value.strip()


def require_session(
    request: Request,
    sessions: SessionStore = Depends(get_session_store),
) -> Session:
    token = bearer_token(request)
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    session = sessions.get(token)
    if session is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired session.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return session
