"""Password hashing, password policy and token helpers.

Passwords are hashed with **Argon2id** (via ``argon2-cffi``). If the optional
dependency is unavailable the module transparently falls back to the standard
library's ``hashlib.scrypt`` so the bridge never blocks on a missing package.
Both formats are self-describing strings, so existing hashes stay verifiable
after an upgrade.

The bridge never stores, logs or returns a plaintext password.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets

# ---------------------------------------------------------------------------
# Argon2 (preferred)
# ---------------------------------------------------------------------------
try:  # pragma: no cover - exercised implicitly depending on environment
    from argon2 import PasswordHasher
    from argon2.exceptions import (
        InvalidHashError,
        VerificationError,
        VerifyMismatchError,
    )

    _ARGON2 = PasswordHasher()
    _HAS_ARGON2 = True
except Exception:  # noqa: BLE001 - any import failure falls back to scrypt
    _ARGON2 = None
    _HAS_ARGON2 = False


# ---------------------------------------------------------------------------
# scrypt fallback (standard library)
# ---------------------------------------------------------------------------
_SCRYPT_N = 2**14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_DKLEN = 32
_SCRYPT_PREFIX = "scrypt"


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def _scrypt_hash(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
        dklen=_SCRYPT_DKLEN,
    )
    return f"{_SCRYPT_PREFIX}${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${_b64(salt)}${_b64(digest)}"


def _scrypt_verify(password: str, encoded: str) -> bool:
    try:
        _, n, r, p, salt_b64, digest_b64 = encoded.split("$")
        salt = _unb64(salt_b64)
        expected = _unb64(digest_b64)
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(expected),
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(actual, expected)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------
def hash_password(password: str) -> str:
    """Return a self-describing, salted password hash."""
    if _HAS_ARGON2 and _ARGON2 is not None:
        return _ARGON2.hash(password)
    return _scrypt_hash(password)


def verify_password(password: str, encoded: str | None) -> bool:
    """Constant-time-ish verification against either hash format."""
    if not encoded:
        return False
    if encoded.startswith(_SCRYPT_PREFIX + "$"):
        return _scrypt_verify(password, encoded)
    if _HAS_ARGON2 and _ARGON2 is not None:
        try:
            return _ARGON2.verify(encoded, password)
        except (VerifyMismatchError, VerificationError, InvalidHashError):
            return False
    return False


def needs_rehash(encoded: str | None) -> bool:
    """True when a stored hash uses outdated parameters (rehash on next login)."""
    if not encoded:
        return True
    if encoded.startswith(_SCRYPT_PREFIX + "$"):
        return f"${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}$" not in encoded
    if _HAS_ARGON2 and _ARGON2 is not None:
        try:
            return _ARGON2.check_needs_rehash(encoded)
        except Exception:  # noqa: BLE001
            return True
    return False


# ---------------------------------------------------------------------------
# Password policy
# ---------------------------------------------------------------------------
MIN_PASSWORD_LENGTH = 8
MAX_PASSWORD_LENGTH = 256


class PasswordPolicyError(ValueError):
    """Raised when a proposed password violates the policy."""


def validate_password(password: str) -> None:
    """Enforce a minimal, sensible password policy.

    The policy deliberately favours length/entropy advice over brittle
    composition rules. Raises :class:`PasswordPolicyError` when invalid.
    """
    if password is None:
        raise PasswordPolicyError("Password is required.")
    if len(password) < MIN_PASSWORD_LENGTH:
        raise PasswordPolicyError(
            f"Password must be at least {MIN_PASSWORD_LENGTH} characters."
        )
    if len(password) > MAX_PASSWORD_LENGTH:
        raise PasswordPolicyError(
            f"Password must be at most {MAX_PASSWORD_LENGTH} characters."
        )
    if password.strip() != password or not password.strip():
        raise PasswordPolicyError("Password must not be blank or padded with spaces.")


# ---------------------------------------------------------------------------
# Token helpers
# ---------------------------------------------------------------------------
def new_token(nbytes: int = 32) -> str:
    """A URL-safe, high-entropy opaque token (used for sessions)."""
    return secrets.token_urlsafe(nbytes)
