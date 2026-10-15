"""FastAPI routers for the Macro Monitor bridge API (v1)."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response

from . import __version__
from .auth import require_session
from .models import (
    AccountsResponse,
    CommandRequest,
    CommandResponse,
    HealthResponse,
    InfoResponse,
    LogsResponse,
    PlantersResponse,
    ReportsResponse,
    StatusResponse,
)
from .config import get_settings
from .registry import AccountRegistry, get_registry

health_router = APIRouter(tags=["health"])
api = APIRouter(prefix="/api/v1", dependencies=[Depends(require_session)])


@health_router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(bridge_version=__version__, server_time=datetime.now(timezone.utc))


@health_router.get("/api/v1/info", response_model=InfoResponse)
async def info() -> InfoResponse:
    """Public, secret-free service descriptor used by the app after discovery."""
    settings = get_settings()
    return InfoResponse(
        name=settings.service_name,
        bridge_version=__version__,
        pairing_enabled=settings.enable_pairing,
        accounts=len(settings.load_accounts()),
    )


def _service(registry: AccountRegistry, account_id: str):
    svc = registry.get(account_id)
    if svc is None:
        raise HTTPException(status_code=404, detail=f"Unknown account id: {account_id}")
    return svc


@api.get("/accounts", response_model=AccountsResponse)
async def list_accounts(registry: AccountRegistry = Depends(get_registry)) -> AccountsResponse:
    return AccountsResponse(accounts=[s.summary() for s in registry.all()])


@api.get("/accounts/{account_id}/status", response_model=StatusResponse)
async def get_status(
    account_id: str, registry: AccountRegistry = Depends(get_registry)
) -> StatusResponse:
    return await _service(registry, account_id).status_async()


@api.get("/accounts/{account_id}/reports", response_model=ReportsResponse)
async def get_reports(
    account_id: str, limit: int = 10, registry: AccountRegistry = Depends(get_registry)
) -> ReportsResponse:
    return await _service(registry, account_id).reports(limit=limit)


@api.get("/accounts/{account_id}/reports/{report_id}/image")
async def get_report_image(
    account_id: str, report_id: str, registry: AccountRegistry = Depends(get_registry)
) -> Response:
    svc = _service(registry, account_id)
    result = await svc.report_image(report_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Report image not found")
    ctype, data = result
    return Response(content=data, media_type=ctype)


@api.get("/accounts/{account_id}/logs", response_model=LogsResponse)
async def get_logs(
    account_id: str, limit: int = 30, registry: AccountRegistry = Depends(get_registry)
) -> LogsResponse:
    return await _service(registry, account_id).logs(limit=limit)


@api.get("/accounts/{account_id}/planters", response_model=PlantersResponse)
async def get_planters(
    account_id: str, registry: AccountRegistry = Depends(get_registry)
) -> PlantersResponse:
    return _service(registry, account_id).planters()


@api.post("/accounts/{account_id}/commands", response_model=CommandResponse)
async def post_command(
    account_id: str,
    body: CommandRequest,
    registry: AccountRegistry = Depends(get_registry),
) -> CommandResponse:
    return await _service(registry, account_id).send_command(
        body.command, body.correlation_id
    )
