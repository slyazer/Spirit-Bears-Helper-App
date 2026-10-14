"""Bearer-token authentication for the bridge API.

The token is provided via `MM_API_TOKEN`. Comparison is constant-time to avoid
timing leaks. `/health` is the only unauthenticated route.
"""

from __future__ import annotations

import hmac

from fastapi import Depends, HTTPException, Request, status

from .config import Settings, get_settings


def require_token(
    request: Request, settings: Settings = Depends(get_settings)
) -> None:
    expected = settings.api_token
    if not expected or expected == "CHANGEME":
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Bridge API token is not configured.",
        )
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not hmac.compare_digest(value, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
