from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import registry
from app.config import Settings, get_settings
from app.main import create_app
from app.pairing import create_pairing
from app.ratelimit import LoginLimiter
from app.sessions import SessionStore
from app.state import get_login_limiter, get_session_store, get_user_store
from app.users import UserStore


@pytest.fixture
def env(tmp_path: Path):
    settings = Settings(
        accounts_file="config/doesnotexist_accounts.json",
        users_file=str(tmp_path / "users.json"),
        sessions_file=str(tmp_path / "sessions.json"),
        pairing_file=str(tmp_path / "pairing.json"),
        login_max_attempts=3,
        login_window_seconds=300,
        login_lockout_seconds=300,
    )
    users = UserStore(tmp_path / "users.json")
    users.add("alice", "correct-horse-battery")
    sessions = SessionStore(tmp_path / "sessions.json")
    limiter = LoginLimiter(max_attempts=3, window_seconds=300, lockout_seconds=300)

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_user_store] = lambda: users
    app.dependency_overrides[get_session_store] = lambda: sessions
    app.dependency_overrides[get_login_limiter] = lambda: limiter
    app.dependency_overrides[registry.get_registry] = lambda: registry.AccountRegistry(settings)
    client = TestClient(app)
    return client, settings, users, sessions, limiter


def test_health_is_public(env) -> None:
    client, *_ = env
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_info_is_public_and_secretless(env) -> None:
    client, *_ = env
    r = client.get("/api/v1/info")
    assert r.status_code == 200
    body = r.json()
    assert body["pairing_enabled"] is True
    assert "token" not in body and "password" not in body


def test_protected_route_requires_session(env) -> None:
    client, *_ = env
    assert client.get("/api/v1/accounts").status_code == 401


def test_protected_route_rejects_bad_token(env) -> None:
    client, *_ = env
    r = client.get("/api/v1/accounts", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401


def test_login_and_access(env) -> None:
    client, *_ = env
    r = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    )
    assert r.status_code == 200
    token = r.json()["token"]
    assert r.json()["username"] == "alice"

    ok = client.get("/api/v1/accounts", headers={"Authorization": f"Bearer {token}"})
    assert ok.status_code == 200
    assert ok.json() == {"accounts": []}


def test_login_is_case_insensitive(env) -> None:
    client, *_ = env
    r = client.post(
        "/api/v1/auth/login",
        json={"username": "ALICE", "password": "correct-horse-battery"},
    )
    assert r.status_code == 200


def test_login_wrong_password(env) -> None:
    client, *_ = env
    r = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "wrong"},
    )
    assert r.status_code == 401
    assert "password" in r.json()["detail"].lower()


def test_login_rate_limit_locks_out(env) -> None:
    client, _, _, _, _ = env
    for _ in range(3):
        r = client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "wrong"},
        )
        assert r.status_code == 401
    locked = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    )
    assert locked.status_code == 429
    assert "Retry-After" in locked.headers


def test_logout_revokes_session(env) -> None:
    client, *_ = env
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    assert client.post("/api/v1/auth/logout", headers=headers).status_code == 200
    assert client.get("/api/v1/accounts", headers=headers).status_code == 401


def test_me_reports_identity(env) -> None:
    client, *_ = env
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery", "device_name": "phone"},
    ).json()["token"]
    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["username"] == "alice"
    assert r.json()["device"] == "phone"


def test_me_reports_owner_role_from_user_store(env) -> None:
    client, *_ = env
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["role"] == "owner"


def test_me_reports_non_owner_role_from_user_store(env) -> None:
    client, _, users, *_ = env
    users.add("bob", "another-long-passphrase", role="viewer")
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "bob", "password": "another-long-passphrase"},
    ).json()["token"]
    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["username"] == "bob"
    assert r.json()["role"] == "viewer"


def test_me_reports_unknown_role_for_orphaned_session(env) -> None:
    client, _, _, sessions, *_ = env
    # Mint a session for a username that has no matching user record.
    session = sessions.create("ghost", device="phone")
    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {session.token}"})
    assert r.status_code == 200
    assert r.json()["username"] == "ghost"
    assert r.json()["role"] == "unknown"


def test_change_password(env) -> None:
    client, _, users, *_ = env
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    bad = client.post(
        "/api/v1/auth/password",
        headers=headers,
        json={"current_password": "nope", "new_password": "brand-new-password"},
    )
    assert bad.status_code == 401

    good = client.post(
        "/api/v1/auth/password",
        headers=headers,
        json={"current_password": "correct-horse-battery", "new_password": "brand-new-password"},
    )
    assert good.status_code == 200
    assert users.verify("alice", "brand-new-password") is not None


def test_change_password_rejects_weak(env) -> None:
    client, *_ = env
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    r = client.post(
        "/api/v1/auth/password",
        headers={"Authorization": f"Bearer {token}"},
        json={"current_password": "correct-horse-battery", "new_password": "short"},
    )
    assert r.status_code == 422


def test_pair_with_valid_code(env) -> None:
    client, settings, *_ = env
    info = create_pairing(Path(settings.pairing_file), ttl_seconds=600)
    r = client.post(
        "/api/v1/auth/pair",
        json={"code": info.code, "device_name": "pixel"},
    )
    assert r.status_code == 200
    token = r.json()["token"]
    assert r.json()["username"] == "alice"
    assert client.get(
        "/api/v1/accounts", headers={"Authorization": f"Bearer {token}"}
    ).status_code == 200


def test_pair_code_is_single_use(env) -> None:
    client, settings, *_ = env
    info = create_pairing(Path(settings.pairing_file), ttl_seconds=600)
    assert client.post("/api/v1/auth/pair", json={"code": info.code}).status_code == 200
    assert client.post("/api/v1/auth/pair", json={"code": info.code}).status_code == 401


def test_pair_with_bad_code(env) -> None:
    client, *_ = env
    assert client.post("/api/v1/auth/pair", json={"code": "ZZZZZZZZ"}).status_code == 401


def test_pair_disabled(env) -> None:
    client, settings, *_ = env
    # Re-override settings with pairing off.
    from app.config import get_settings as gs

    disabled = settings.model_copy(update={"enable_pairing": False})
    client.app.dependency_overrides[gs] = lambda: disabled
    info = create_pairing(Path(settings.pairing_file), ttl_seconds=600)
    r = client.post("/api/v1/auth/pair", json={"code": info.code})
    assert r.status_code == 404
