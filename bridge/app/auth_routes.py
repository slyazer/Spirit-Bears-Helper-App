"""Authentication endpoints: login, logout, identity, password change, pairing."""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from .auth import bearer_token, client_ip, require_session
from .config import Settings, get_settings, resolve_path
from .pairing import claim_pairing
from .ratelimit import LoginLimiter
from .security import PasswordPolicyError
from .sessions import Session, SessionStore
from .state import get_login_limiter, get_session_store, get_user_store
from .users import UserStore

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


# -- request / response models ---------------------------------------------
class LoginRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=1, max_length=256)
    device_name: str | None = Field(default=None, max_length=64)


class PairRequest(BaseModel):
    code: str = Field(..., min_length=4, max_length=32)
    device_name: str | None = Field(default=None, max_length=64)


class ChangePasswordRequest(BaseModel):
    current_password: str = Field(..., min_length=1, max_length=256)
    new_password: str = Field(..., min_length=1, max_length=256)


class TokenResponse(BaseModel):
    token: str
    token_type: str = "Bearer"
    username: str
    expires_at: datetime


class MeResponse(BaseModel):
    username: str
    role: str
    device: str
    created_at: datetime
    expires_at: datetime


# -- helpers ----------------------------------------------------------------
def _locked(limiter: LoginLimiter, keys: list[str]) -> float:
    return max((limiter.locked_for(k) for k in keys), default=0.0)


def _too_many(retry_after: float) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail="Too many attempts. Please wait and try again.",
        headers={"Retry-After": str(int(retry_after) + 1)},
    )


def _owner_username(users: UserStore) -> str:
    owner = next((u for u in users.all() if u.role == "owner"), None)
    if owner is None:
        all_users = users.all()
        owner = all_users[0] if all_users else None
    if owner is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No user accounts exist yet. Run the setup wizard on the PC.",
        )
    return owner.username


# -- endpoints --------------------------------------------------------------
@router.post("/login", response_model=TokenResponse)
async def login(
    body: LoginRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
    users: UserStore = Depends(get_user_store),
    sessions: SessionStore = Depends(get_session_store),
    limiter: LoginLimiter = Depends(get_login_limiter),
) -> TokenResponse:
    ip_key = f"ip:{client_ip(request)}"
    user_key = f"user:{body.username.strip().lower()}"
    retry = _locked(limiter, [ip_key, user_key])
    if retry > 0:
        raise _too_many(retry)

    user = users.verify(body.username, body.password)
    if user is None:
        limiter.record_failure(ip_key)
        limiter.record_failure(user_key)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password.",
        )

    limiter.reset(ip_key)
    limiter.reset(user_key)
    session = sessions.create(user.username, body.device_name or client_ip(request))
    return TokenResponse(
        token=session.token, username=user.username, expires_at=session.expires_at
    )


@router.post("/pair", response_model=TokenResponse)
async def pair(
    body: PairRequest,
    request: Request,
    settings: Settings = Depends(get_settings),
    users: UserStore = Depends(get_user_store),
    sessions: SessionStore = Depends(get_session_store),
    limiter: LoginLimiter = Depends(get_login_limiter),
) -> TokenResponse:
    if not settings.enable_pairing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Pairing is disabled."
        )
    ip_key = f"ip:{client_ip(request)}"
    retry = limiter.locked_for(ip_key)
    if retry > 0:
        raise _too_many(retry)

    if not claim_pairing(resolve_path(settings.pairing_file), body.code):
        limiter.record_failure(ip_key)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired pairing code.",
        )

    limiter.reset(ip_key)
    username = _owner_username(users)
    session = sessions.create(username, body.device_name or f"paired:{client_ip(request)}")
    return TokenResponse(
        token=session.token, username=username, expires_at=session.expires_at
    )


@router.get("/me", response_model=MeResponse)
async def me(session: Session = Depends(require_session)) -> MeResponse:
    return MeResponse(
        username=session.username,
        role="owner",
        device=session.device,
        created_at=session.created_at,
        expires_at=session.expires_at,
    )


@router.post("/logout")
async def logout(
    request: Request,
    session: Session = Depends(require_session),
    sessions: SessionStore = Depends(get_session_store),
) -> dict:
    token = bearer_token(request)
    if token:
        sessions.revoke(token)
    return {"status": "ok"}


@router.post("/password")
async def change_password(
    body: ChangePasswordRequest,
    request: Request,
    session: Session = Depends(require_session),
    users: UserStore = Depends(get_user_store),
    sessions: SessionStore = Depends(get_session_store),
) -> dict:
    if users.verify(session.username, body.current_password) is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Current password is incorrect.",
        )
    try:
        users.set_password(session.username, body.new_password)
    except PasswordPolicyError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    # Invalidate every other session for this user; keep the current one.
    current = bearer_token(request)
    sessions.revoke_all(session.username)
    if current:
        sessions.create(session.username, session.device)
    return {"status": "ok"}
