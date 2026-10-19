"""Tests for the read-only Roblox private-server population feature.

Two layers:
* ``PopulationStore`` alone, with an injected clock so staleness is deterministic.
* The HTTP contract through ``TestClient`` - auth required, validation, stale
  behavior, and the exact JSON shape the Android client deserializes.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import create_app
from app.roblox_population import PopulationStore
from app.ratelimit import LoginLimiter
from app.sessions import SessionStore
from app.state import (
    get_login_limiter,
    get_population_store,
    get_session_store,
    get_user_store,
)
from app.users import UserStore


class FakeClock:
    def __init__(self, start: datetime) -> None:
        self.now = start

    def __call__(self) -> datetime:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now = self.now + timedelta(seconds=seconds)


# -- store unit tests ------------------------------------------------------
def test_snapshot_empty_is_unavailable() -> None:
    store = PopulationStore()
    snap = store.snapshot()
    assert snap.available is False
    assert snap.playing is None
    assert snap.max_players is None
    assert snap.stale is False
    assert snap.detail


def test_record_then_snapshot_is_available_and_fresh() -> None:
    clock = FakeClock(datetime(2026, 10, 18, 12, 0, tzinfo=timezone.utc))
    store = PopulationStore(stale_after_seconds=900, clock=clock)
    store.record(playing=3, max_players=6)
    snap = store.snapshot()
    assert snap.available is True
    assert snap.playing == 3
    assert snap.max_players == 6
    assert snap.stale is False
    assert snap.age_seconds == 0
    assert snap.received_at == clock.now


def test_sample_becomes_stale_past_the_window() -> None:
    clock = FakeClock(datetime(2026, 10, 18, 12, 0, tzinfo=timezone.utc))
    store = PopulationStore(stale_after_seconds=900, clock=clock)
    store.record(playing=2, max_players=6)

    clock.advance(899)
    assert store.snapshot().stale is False
    clock.advance(2)
    snap = store.snapshot()
    assert snap.stale is True
    # A stale sample is still surfaced (last-known) rather than dropped.
    assert snap.available is True
    assert snap.playing == 2


def test_record_replaces_previous_sample() -> None:
    clock = FakeClock(datetime(2026, 10, 18, 12, 0, tzinfo=timezone.utc))
    store = PopulationStore(clock=clock)
    store.record(playing=1, max_players=6)
    clock.advance(30)
    store.record(playing=4, max_players=6)
    snap = store.snapshot()
    assert snap.playing == 4
    assert snap.age_seconds == 0


def test_clear_drops_the_sample() -> None:
    store = PopulationStore()
    store.record(playing=1, max_players=6)
    store.clear()
    assert store.snapshot().available is False


# -- HTTP contract ---------------------------------------------------------
@pytest.fixture
def env(tmp_path: Path):
    settings = Settings(
        accounts_file="config/doesnotexist_accounts.json",
        users_file=str(tmp_path / "users.json"),
        sessions_file=str(tmp_path / "sessions.json"),
        pairing_file=str(tmp_path / "pairing.json"),
        enable_mdns=False,
    )
    users = UserStore(tmp_path / "users.json")
    users.add("alice", "correct-horse-battery")
    sessions = SessionStore(tmp_path / "sessions.json")
    limiter = LoginLimiter(max_attempts=3, window_seconds=300, lockout_seconds=300)
    store = PopulationStore(stale_after_seconds=900)

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_user_store] = lambda: users
    app.dependency_overrides[get_session_store] = lambda: sessions
    app.dependency_overrides[get_login_limiter] = lambda: limiter
    app.dependency_overrides[get_population_store] = lambda: store
    client = TestClient(app)
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    return client, {"Authorization": f"Bearer {token}"}, store


def test_population_requires_session(env) -> None:
    client, _, _ = env
    assert client.get("/api/v1/roblox/population").status_code == 401
    assert (
        client.post(
            "/api/v1/roblox/population", json={"playing": 1, "max_players": 6}
        ).status_code
        == 401
    )


def test_population_empty_contract(env) -> None:
    client, headers, _ = env
    r = client.get("/api/v1/roblox/population", headers=headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {
        "available",
        "playing",
        "max_players",
        "captured_at",
        "received_at",
        "age_seconds",
        "stale",
        "source",
        "detail",
    }
    assert body["available"] is False
    assert body["playing"] is None
    assert body["max_players"] is None
    assert body["stale"] is False


def test_population_ingest_then_read(env) -> None:
    client, headers, _ = env
    captured = "2026-10-18T12:00:00+00:00"
    r = client.post(
        "/api/v1/roblox/population",
        headers=headers,
        json={"playing": 3, "max_players": 6, "captured_at": captured},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["available"] is True
    assert body["playing"] == 3
    assert body["max_players"] == 6
    assert body["stale"] is False
    datetime.fromisoformat(body["captured_at"])
    datetime.fromisoformat(body["received_at"])

    again = client.get("/api/v1/roblox/population", headers=headers).json()
    assert again["playing"] == 3
    assert again["max_players"] == 6


@pytest.mark.parametrize(
    "payload",
    [
        {"playing": -1, "max_players": 6},  # negative
        {"playing": 7, "max_players": 6},  # playing > max_players
        {"playing": 3, "max_players": 0},  # max_players below 1
        {"playing": 3},  # missing max_players
        {"max_players": 6},  # missing playing
        {"playing": "three", "max_players": 6},  # non-int
        {"playing": 3, "max_players": 5000},  # implausibly large
    ],
)
def test_population_ingest_rejects_invalid(env, payload) -> None:
    client, headers, store = env
    r = client.post("/api/v1/roblox/population", headers=headers, json=payload)
    assert r.status_code == 422
    # A rejected ingest must not overwrite the store.
    assert store.snapshot().available is False


def test_population_stale_flagging_through_api(tmp_path: Path) -> None:
    settings = Settings(
        accounts_file="config/doesnotexist_accounts.json",
        users_file=str(tmp_path / "users.json"),
        sessions_file=str(tmp_path / "sessions.json"),
        pairing_file=str(tmp_path / "pairing.json"),
        enable_mdns=False,
    )
    users = UserStore(tmp_path / "users.json")
    users.add("alice", "correct-horse-battery")
    sessions = SessionStore(tmp_path / "sessions.json")
    limiter = LoginLimiter(max_attempts=3, window_seconds=300, lockout_seconds=300)
    clock = FakeClock(datetime(2026, 10, 18, 12, 0, tzinfo=timezone.utc))
    store = PopulationStore(stale_after_seconds=60, clock=clock)

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_user_store] = lambda: users
    app.dependency_overrides[get_session_store] = lambda: sessions
    app.dependency_overrides[get_login_limiter] = lambda: limiter
    app.dependency_overrides[get_population_store] = lambda: store
    client = TestClient(app)
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    client.post(
        "/api/v1/roblox/population",
        headers=headers,
        json={"playing": 5, "max_players": 6},
    )
    assert client.get("/api/v1/roblox/population", headers=headers).json()["stale"] is False

    clock.advance(120)
    body = client.get("/api/v1/roblox/population", headers=headers).json()
    assert body["stale"] is True
    # Last-known value is preserved and clearly marked stale.
    assert body["playing"] == 5
    assert body["max_players"] == 6
