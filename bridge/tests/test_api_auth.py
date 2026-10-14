from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def _client(token: str = "test-token-123") -> TestClient:
    app = create_app()
    from app import auth, config, registry

    settings = Settings(
        api_token=token,
        accounts_file="config/doesnotexist_accounts.json",
    )
    test_registry = registry.AccountRegistry(settings)
    app.dependency_overrides[config.get_settings] = lambda: settings
    app.dependency_overrides[auth.get_settings] = lambda: settings
    app.dependency_overrides[registry.get_registry] = lambda: test_registry
    return TestClient(app)


def test_health_is_public() -> None:
    client = _client()
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_accounts_requires_token() -> None:
    client = _client()
    assert client.get("/api/v1/accounts").status_code == 401


def test_accounts_with_bad_token() -> None:
    client = _client()
    r = client.get("/api/v1/accounts", headers={"Authorization": "Bearer wrong"})
    assert r.status_code == 401


def test_accounts_with_good_token() -> None:
    client = _client()
    r = client.get("/api/v1/accounts", headers={"Authorization": "Bearer test-token-123"})
    assert r.status_code == 200
    assert r.json() == {"accounts": []}
