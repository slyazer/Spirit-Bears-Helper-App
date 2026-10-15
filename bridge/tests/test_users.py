from __future__ import annotations

from pathlib import Path

import pytest

from app.security import PasswordPolicyError
from app.users import UserStore, normalize_username, validate_username


def test_add_and_verify(tmp_path: Path) -> None:
    store = UserStore(tmp_path / "users.json")
    store.add("Alice", "a-good-password")
    assert store.exists("alice")
    assert store.verify("alice", "a-good-password") is not None
    assert store.verify("alice", "wrong") is None


def test_username_is_normalized(tmp_path: Path) -> None:
    store = UserStore(tmp_path / "users.json")
    user = store.add("Bob.Smith", "a-good-password")
    assert user.username == "bob.smith"
    assert normalize_username("  Bob.Smith ") == "bob.smith"


def test_duplicate_user_rejected(tmp_path: Path) -> None:
    store = UserStore(tmp_path / "users.json")
    store.add("alice", "a-good-password")
    with pytest.raises(ValueError):
        store.add("ALICE", "another-good-password")


def test_invalid_username_rejected() -> None:
    with pytest.raises(ValueError):
        validate_username("bad name!")


def test_weak_password_rejected(tmp_path: Path) -> None:
    store = UserStore(tmp_path / "users.json")
    with pytest.raises(PasswordPolicyError):
        store.add("alice", "short")


def test_unknown_user_verify_returns_none(tmp_path: Path) -> None:
    store = UserStore(tmp_path / "users.json")
    assert store.verify("ghost", "whatever-password") is None


def test_set_password(tmp_path: Path) -> None:
    store = UserStore(tmp_path / "users.json")
    store.add("alice", "a-good-password")
    store.set_password("alice", "a-better-password")
    assert store.verify("alice", "a-better-password") is not None
    assert store.verify("alice", "a-good-password") is None


def test_persistence_across_reload(tmp_path: Path) -> None:
    path = tmp_path / "users.json"
    store = UserStore(path)
    store.add("alice", "a-good-password")

    reloaded = UserStore(path)
    assert reloaded.count() == 1
    assert reloaded.verify("alice", "a-good-password") is not None
