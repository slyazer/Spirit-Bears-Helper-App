"""Opaque, revocable login sessions.

A session is an unguessable random token mapped to a username. Sessions are
persisted to a git-ignored JSON file so a bridge restart (or the auto-restart
guard) does not sign everyone out. Tokens are validated with a constant-time
comparison, expire on an absolute TTL, and are also dropped after a period of
inactivity.
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from pydantic import BaseModel, Field

from .security import new_token

logger = logging.getLogger("macro_monitor.sessions")


def _now() -> datetime:
    return datetime.now(timezone.utc)


class Session(BaseModel):
    token: str
    username: str
    device: str = "unknown"
    created_at: datetime = Field(default_factory=_now)
    last_seen: datetime = Field(default_factory=_now)
    expires_at: datetime


class SessionStore:
    def __init__(
        self,
        path: Path,
        ttl_seconds: int = 2_592_000,
        idle_seconds: int = 604_800,
    ) -> None:
        self._path = Path(path)
        self._ttl = timedelta(seconds=max(ttl_seconds, 60))
        self._idle = timedelta(seconds=max(idle_seconds, 60))
        self._sessions: dict[str, Session] = {}
        self._load()

    # -- persistence ----------------------------------------------------
    def _load(self) -> None:
        self._sessions = {}
        if not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            logger.error("Could not read sessions file %s: %s", self._path, exc)
            return
        for entry in (data.get("sessions", []) if isinstance(data, dict) else data):
            try:
                session = Session(**entry)
            except Exception:  # noqa: BLE001
                continue
            self._sessions[session.token] = session
        self._purge()
        self._save()

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 1,
            "sessions": [s.model_dump(mode="json") for s in self._sessions.values()],
        }
        fd, tmp = tempfile.mkstemp(dir=str(self._path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, indent=2)
            os.replace(tmp, self._path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)
        try:
            os.chmod(self._path, 0o600)
        except OSError:
            pass

    # -- helpers --------------------------------------------------------
    def _expired(self, session: Session, now: datetime) -> bool:
        return now >= session.expires_at or (now - session.last_seen) >= self._idle

    def _purge(self) -> None:
        now = _now()
        stale = [t for t, s in self._sessions.items() if self._expired(s, now)]
        for token in stale:
            self._sessions.pop(token, None)

    # -- API ------------------------------------------------------------
    def create(self, username: str, device: str = "unknown") -> Session:
        session = Session(
            token=new_token(32),
            username=username,
            device=(device or "unknown")[:64],
            expires_at=_now() + self._ttl,
        )
        self._sessions[session.token] = session
        self._save()
        return session

    def get(self, token: str | None) -> Session | None:
        """Return a live session and refresh its last-seen time."""
        if not token:
            return None
        match: Session | None = None
        for stored_token, session in self._sessions.items():
            if hmac.compare_digest(stored_token, token):
                match = session
                break
        if match is None:
            return None
        now = _now()
        if self._expired(match, now):
            self._sessions.pop(match.token, None)
            self._save()
            return None
        match.last_seen = now
        self._save()
        return match

    def revoke(self, token: str) -> bool:
        if token in self._sessions:
            self._sessions.pop(token, None)
            self._save()
            return True
        return False

    def revoke_all(self, username: str) -> int:
        key = (username or "").strip().lower()
        removed = [t for t, s in self._sessions.items() if s.username == key]
        for token in removed:
            self._sessions.pop(token, None)
        if removed:
            self._save()
        return len(removed)

    def active_count(self) -> int:
        self._purge()
        return len(self._sessions)
