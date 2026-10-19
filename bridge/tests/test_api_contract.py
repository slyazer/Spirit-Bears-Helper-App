"""API contract tests: pin the JSON shape of the public info endpoint and the
authenticated data responses.

These hit the real FastAPI application through ``TestClient`` so the actual
route registration and Pydantic ``response_model`` serialization path are
exercised - not a hand-built dictionary. Field names here are the contract the
Android client (``android/.../data/api/ApiModels.kt``) deserializes.
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import registry
from app.config import Settings, get_settings
from app.main import create_app
from app.models import (
    AccountSummary,
    CommandResponse,
    LogsResponse,
    LogEntry,
    PlantersResponse,
    PlanterInfo,
    NectarInfo,
    ReportImage,
    ReportsResponse,
    StatusResponse,
)
from app.ratelimit import LoginLimiter
from app.sessions import SessionStore
from app.state import get_login_limiter, get_session_store, get_user_store
from app.users import UserStore

ACCOUNT_ID = "leader"


class _StubService:
    """Minimal stand-in for NatroService returning canned, fully-populated
    models so serialization of every documented field is exercised."""

    def summary(self) -> AccountSummary:
        return AccountSummary(
            id=ACCOUNT_ID, name="Leader", hive_level=16, command_ambiguous=False
        )

    async def status_async(self) -> StatusResponse:
        return StatusResponse(
            id=ACCOUNT_ID,
            name="Leader",
            hive_level=16,
            macro_state="running",
            field="Pine Tree Forest",
            field_boosted=False,
            field_since=datetime(2026, 10, 15, 12, 0, tzinfo=timezone.utc),
            field_limit_seconds=300,
            activity="Gathering",
            activity_detail=None,
            honey_total="2.644 B",
            honey_session="51.2 M",
            honey_per_hour="6.189 B",
            runtime_seconds=3600,
            disconnected_count=0,
            planters_collected=2,
            last_updated=datetime(2026, 10, 15, 12, 5, tzinfo=timezone.utc),
            stale=False,
            source="natro-files",
            notes=None,
        )

    async def reports(self, limit: int = 10) -> ReportsResponse:
        return ReportsResponse(
            id=ACCOUNT_ID,
            reports=[
                ReportImage(
                    report_id="r-1",
                    arrival_time=datetime(2026, 10, 15, 11, 0, tzinfo=timezone.utc),
                    image_url="https://cdn.example/r-1.png",
                    image_proxy_url=f"api/v1/accounts/{ACCOUNT_ID}/reports/r-1/image",
                    author="leader",
                    summary="7.1 B honey",
                )
            ],
            detail=None,
        )

    async def logs(self, limit: int = 30) -> LogsResponse:
        return LogsResponse(
            id=ACCOUNT_ID,
            logs=[
                LogEntry(
                    entry_id="l-1",
                    timestamp=datetime(2026, 10, 15, 11, 0, tzinfo=timezone.utc),
                    title="Started gathering",
                    body="Pine Tree Forest",
                    color="#8BE7FF",
                    author="leader",
                    image_url=None,
                )
            ],
            detail=None,
        )

    def planters(self) -> PlantersResponse:
        return PlantersResponse(
            id=ACCOUNT_ID,
            available=True,
            reason=None,
            planters=[
                PlanterInfo(
                    slot=0,
                    name="Red",
                    field="Pine Tree Forest",
                    harvest_eta=datetime(2026, 10, 15, 12, 30, tzinfo=timezone.utc),
                    est_percent=42.5,
                )
            ],
            nectars=[NectarInfo(name="Comforting", level=80.0)],
        )

    async def send_command(
        self, command: str, correlation_id: str | None
    ) -> CommandResponse:
        return CommandResponse(
            id=ACCOUNT_ID,
            command=command,
            result="accepted",
            correlation_id=correlation_id,
            detail=None,
        )


class _StubRegistry:
    def __init__(self) -> None:
        self._svc = _StubService()

    def all(self) -> list[_StubService]:
        return [self._svc]

    def get(self, account_id: str) -> _StubService | None:
        return self._svc if account_id == ACCOUNT_ID else None


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

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_user_store] = lambda: users
    app.dependency_overrides[get_session_store] = lambda: sessions
    app.dependency_overrides[get_login_limiter] = lambda: limiter
    app.dependency_overrides[registry.get_registry] = lambda: _StubRegistry()
    client = TestClient(app)
    token = client.post(
        "/api/v1/auth/login",
        json={"username": "alice", "password": "correct-horse-battery"},
    ).json()["token"]
    return client, {"Authorization": f"Bearer {token}"}


# -- public endpoints ------------------------------------------------------
def test_health_contract(env) -> None:
    client, _ = env
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"status", "bridge_version", "server_time", "auth"}
    assert body["status"] == "ok"
    assert body["auth"] == "ok"
    assert isinstance(body["bridge_version"], str)
    # ISO-8601 timestamp
    datetime.fromisoformat(body["server_time"])


def test_info_contract_is_public_and_secretless(env) -> None:
    client, _ = env
    r = client.get("/api/v1/info")  # no Authorization header
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {
        "name",
        "bridge_version",
        "pairing_enabled",
        "accounts",
        "scheme",
        "api_version",
    }
    assert body["scheme"] == "macromonitor"
    assert body["api_version"] == "v1"
    assert isinstance(body["pairing_enabled"], bool)
    assert isinstance(body["accounts"], int)
    # No secrets may ever appear in the public descriptor.
    lowered = r.text.lower()
    for forbidden in ("token", "password", "secret", "authorization"):
        assert forbidden not in lowered


# -- authenticated data endpoints -----------------------------------------
def test_accounts_contract(env) -> None:
    client, headers = env
    r = client.get("/api/v1/accounts", headers=headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"accounts"}
    assert len(body["accounts"]) == 1
    assert set(body["accounts"][0]) == {"id", "name", "hive_level", "command_ambiguous"}
    assert body["accounts"][0]["id"] == ACCOUNT_ID


def test_status_contract_field_names_and_types(env) -> None:
    client, headers = env
    r = client.get(f"/api/v1/accounts/{ACCOUNT_ID}/status", headers=headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {
        "id",
        "name",
        "hive_level",
        "macro_state",
        "field",
        "field_boosted",
        "field_since",
        "field_limit_seconds",
        "activity",
        "activity_detail",
        "honey_total",
        "honey_session",
        "honey_per_hour",
        "runtime_seconds",
        "disconnected_count",
        "planters_collected",
        "last_updated",
        "stale",
        "source",
        "notes",
    }
    assert body["macro_state"] in {"running", "paused", "stopped", "unknown"}
    assert isinstance(body["stale"], bool)
    assert isinstance(body["field_limit_seconds"], int)
    assert isinstance(body["honey_per_hour"], str)
    # datetime fields serialize as ISO-8601
    datetime.fromisoformat(body["field_since"])
    datetime.fromisoformat(body["last_updated"])


def test_status_nullable_fields_are_json_null(env) -> None:
    client, headers = env
    body = client.get(f"/api/v1/accounts/{ACCOUNT_ID}/status", headers=headers).json()
    assert body["activity_detail"] is None
    assert body["notes"] is None


def test_reports_contract(env) -> None:
    client, headers = env
    r = client.get(f"/api/v1/accounts/{ACCOUNT_ID}/reports", headers=headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"id", "reports", "detail"}
    assert body["detail"] is None
    report = body["reports"][0]
    assert set(report) == {
        "report_id",
        "arrival_time",
        "image_url",
        "image_proxy_url",
        "author",
        "summary",
    }
    datetime.fromisoformat(report["arrival_time"])


def test_logs_contract(env) -> None:
    client, headers = env
    r = client.get(f"/api/v1/accounts/{ACCOUNT_ID}/logs", headers=headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"id", "logs", "detail"}
    entry = body["logs"][0]
    assert set(entry) == {
        "entry_id",
        "timestamp",
        "title",
        "body",
        "color",
        "author",
        "image_url",
    }
    datetime.fromisoformat(entry["timestamp"])


def test_planters_contract_includes_nectars(env) -> None:
    client, headers = env
    r = client.get(f"/api/v1/accounts/{ACCOUNT_ID}/planters", headers=headers)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"id", "available", "reason", "planters", "nectars"}
    assert set(body["planters"][0]) == {
        "slot",
        "name",
        "field",
        "harvest_eta",
        "est_percent",
    }
    assert set(body["nectars"][0]) == {"name", "level"}


def test_command_contract(env) -> None:
    client, headers = env
    r = client.post(
        f"/api/v1/accounts/{ACCOUNT_ID}/commands",
        headers=headers,
        json={"command": "?start", "correlation_id": "abc-123"},
    )
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"id", "command", "result", "correlation_id", "detail"}
    assert body["result"] in {"accepted", "rejected", "failed"}
    assert body["correlation_id"] == "abc-123"


def test_unknown_account_is_404(env) -> None:
    client, headers = env
    r = client.get("/api/v1/accounts/nope/status", headers=headers)
    assert r.status_code == 404


def test_data_endpoints_require_session(env) -> None:
    client, _ = env
    for path in (
        "/api/v1/accounts",
        f"/api/v1/accounts/{ACCOUNT_ID}/status",
        "/api/v1/auth/me",
    ):
        assert client.get(path).status_code == 401, path
