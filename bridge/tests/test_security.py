from __future__ import annotations

import pytest

from app.security import (
    PasswordPolicyError,
    hash_password,
    needs_rehash,
    validate_password,
    verify_password,
)


def test_hash_and_verify_roundtrip() -> None:
    hashed = hash_password("hunter2hunter2")
    assert hashed != "hunter2hunter2"
    assert verify_password("hunter2hunter2", hashed)
    assert not verify_password("wrong-password", hashed)


def test_hashes_are_salted() -> None:
    assert hash_password("same-password") != hash_password("same-password")


def test_needs_rehash_accepts_own_output() -> None:
    assert needs_rehash(hash_password("some-long-password")) is False


def test_password_policy_rejects_short() -> None:
    with pytest.raises(PasswordPolicyError):
        validate_password("short")


def test_password_policy_rejects_blank() -> None:
    with pytest.raises(PasswordPolicyError):
        validate_password("         ")


def test_password_policy_accepts_reasonable() -> None:
    validate_password("a-perfectly-fine-password")


def test_verify_against_garbage_hash_is_false() -> None:
    assert verify_password("whatever", "not-a-real-hash") is False
