"""Local user accounts.

A single JSON file holds the (very small) list of accounts allowed to sign in
to this bridge. The default deployment is one **owner** account, but the store
supports several users so the same PC can host more than one login if desired.

Only password *hashes* are stored. The file is created with the owner account
by the Windows setup wizard (or ``python -m app.cli create-user``) and is
git-ignored. All saves are atomic (temp file + ``os.replace``).
"""

from __future__ import annotations

import json
import logging
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from pydantic import BaseModel, Field

from .security import (
    hash_password,
    needs_rehash,
    validate_password,
    verify_password,
)

logger = logging.getLogger("macro_monitor.users")

USERNAME_RE = re.compile(r"^[a-zA-Z0-9_.-]{1,32}$")


def normalize_username(username: str) -> str:
    return (username or "").strip().lower()


def validate_username(username: str) -> str:
    normalized = normalize_username(username)
    if not USERNAME_RE.match(normalized):
        raise ValueError(
            "Username must be 1-32 characters: letters, digits, '_', '.', '-' only."
        )
    return normalized


class User(BaseModel):
    username: str
    password_hash: str
    role: str = "owner"
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class UserStore:
    """Read/write access to the users JSON file."""

    def __init__(self, path: Path) -> None:
        self._path = Path(path)
        self._users: dict[str, User] = {}
        self._load()

    # -- persistence ----------------------------------------------------
    def _load(self) -> None:
        self._users = {}
        if not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            logger.error("Could not read users file %s: %s", self._path, exc)
            return
        raw = data.get("users", []) if isinstance(data, dict) else data
        for entry in raw:
            try:
                user = User(**entry)
            except Exception:  # noqa: BLE001 - skip malformed entries
                continue
            self._users[normalize_username(user.username)] = user

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 1,
            "users": [u.model_dump(mode="json") for u in self._users.values()],
        }
        fd, tmp = tempfile.mkstemp(dir=str(self._path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, indent=2)
            os.replace(tmp, self._path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)
        _harden(self._path)

    # -- queries --------------------------------------------------------
    def all(self) -> list[User]:
        return list(self._users.values())

    def count(self) -> int:
        return len(self._users)

    def exists(self, username: str) -> bool:
        return normalize_username(username) in self._users

    def get(self, username: str) -> User | None:
        return self._users.get(normalize_username(username))

    # -- mutations ------------------------------------------------------
    def add(self, username: str, password: str, role: str = "owner") -> User:
        key = validate_username(username)
        if key in self._users:
            raise ValueError(f"User already exists: {key}")
        validate_password(password)
        user = User(username=key, password_hash=hash_password(password), role=role)
        self._users[key] = user
        self._save()
        logger.info("Created user %r (role=%s)", key, role)
        return user

    def set_password(self, username: str, new_password: str) -> None:
        key = normalize_username(username)
        user = self._users.get(key)
        if user is None:
            raise ValueError(f"No such user: {key}")
        validate_password(new_password)
        user.password_hash = hash_password(new_password)
        self._save()
        logger.info("Password changed for %r", key)

    def verify(self, username: str, password: str) -> User | None:
        """Return the user on a correct password, else ``None``.

        Runs a dummy hash when the user does not exist so responses do not leak
        whether a username is valid (timing/enumeration resistance).
        """
        user = self._users.get(normalize_username(username))
        if user is None:
            verify_password(password, hash_password("dummy-timing-equalizer"))
            return None
        if not verify_password(password, user.password_hash):
            return None
        if needs_rehash(user.password_hash):
            user.password_hash = hash_password(password)
            self._save()
        return user


def _harden(path: Path) -> None:
    """Best-effort tightening of file permissions (no-op where unsupported)."""
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
