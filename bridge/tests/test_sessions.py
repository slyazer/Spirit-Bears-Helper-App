from __future__ import annotations

from datetime import timedelta
from pathlib import Path

from app.sessions import SessionStore, _now


def test_create_and_get(tmp_path: Path) -> None:
    store = SessionStore(tmp_path / "sessions.json")
    session = store.create("alice", "phone")
    found = store.get(session.token)
    assert found is not None
    assert found.username == "alice"
    assert found.device == "phone"


def test_get_rejects_unknown_or_missing(tmp_path: Path) -> None:
    store = SessionStore(tmp_path / "sessions.json")
    assert store.get(None) is None
    assert store.get("") is None
    assert store.get("not-a-real-token") is None


def test_revoke(tmp_path: Path) -> None:
    store = SessionStore(tmp_path / "sessions.json")
    session = store.create("alice")
    assert store.revoke(session.token) is True
    assert store.get(session.token) is None
    assert store.revoke(session.token) is False


def test_revoke_all_for_user(tmp_path: Path) -> None:
    store = SessionStore(tmp_path / "sessions.json")
    store.create("alice")
    store.create("alice")
    other = store.create("bob")
    removed = store.revoke_all("ALICE")
    assert removed == 2
    assert store.get(other.token) is not None


def test_expired_session_is_dropped(tmp_path: Path) -> None:
    store = SessionStore(tmp_path / "sessions.json")
    session = store.create("alice")
    session.expires_at = _now() - timedelta(seconds=1)
    assert store.get(session.token) is None


def test_persistence_across_reload(tmp_path: Path) -> None:
    path = tmp_path / "sessions.json"
    store = SessionStore(path)
    session = store.create("alice")
    reloaded = SessionStore(path)
    assert reloaded.get(session.token) is not None
