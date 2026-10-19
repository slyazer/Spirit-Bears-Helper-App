"""Normalized, Natro-agnostic data models returned by the bridge API.

These are deliberately decoupled from Natro's file formats so the Android app
(and any future web dashboard) only sees a clean, documented schema.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, model_validator

MacroState = Literal["running", "paused", "stopped", "unknown"]


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    bridge_version: str
    server_time: datetime
    auth: Literal["ok"] = "ok"


class InfoResponse(BaseModel):
    """Public service descriptor (no secrets) for discovery / handshake."""

    name: str
    bridge_version: str
    pairing_enabled: bool = True
    accounts: int = 0
    scheme: str = "macromonitor"
    api_version: str = "v1"


class AccountSummary(BaseModel):
    id: str
    name: str
    hive_level: int | None = None
    command_ambiguous: bool = False


class AccountsResponse(BaseModel):
    accounts: list[AccountSummary]


class StatusResponse(BaseModel):
    id: str
    name: str
    hive_level: int | None = None
    macro_state: MacroState = "unknown"
    field: str | None = None
    field_boosted: bool | None = None
    # When the macro entered the current field, and that field's time cap in
    # seconds (from the "Limit MM:SS" segment). The app ticks the timer locally.
    field_since: datetime | None = None
    field_limit_seconds: int | None = None
    # Short human label for the macro's current action, derived from the newest
    # log line (e.g. "Converting", "Traveling", "Resetting", "Gathering"). Lets
    # the app show what the macro is doing at the hive/while converting instead
    # of only the last-gathered field.
    activity: str | None = None
    activity_detail: str | None = None
    # Current balance (all-time honey the account actually has), when known.
    honey_total: str | None = None
    # Honey gained during the current macro session (SessionTotalHoney).
    honey_session: str | None = None
    honey_per_hour: str | None = None
    runtime_seconds: int | None = None
    disconnected_count: int | None = None
    planters_collected: int | None = None
    last_updated: datetime | None = None
    stale: bool = False
    source: str = "natro-files"
    notes: str | None = None


class ReportImage(BaseModel):
    report_id: str
    arrival_time: datetime
    image_url: str | None = None
    image_proxy_url: str | None = None
    author: str | None = None
    summary: str | None = None


class ReportsResponse(BaseModel):
    id: str
    reports: list[ReportImage]
    detail: str | None = None  # set when the fetch failed, so apps can show why


class LogEntry(BaseModel):
    entry_id: str
    timestamp: datetime | None = None
    title: str
    body: str | None = None
    color: str | None = None
    author: str | None = None
    image_url: str | None = None


class LogsResponse(BaseModel):
    id: str
    logs: list[LogEntry]
    detail: str | None = None  # set when the fetch failed, so apps can show why


class PlanterInfo(BaseModel):
    slot: int
    name: str | None = None
    field: str | None = None
    harvest_eta: datetime | None = None
    est_percent: float | None = None


class NectarInfo(BaseModel):
    """One nectar type's current level (0-100), OCR'd from BSS's buff bar."""

    name: str
    level: float | None = None


class PlantersResponse(BaseModel):
    id: str
    available: bool
    reason: str | None = None
    planters: list[PlanterInfo] = Field(default_factory=list)
    # The five BSS nectars (Comforting, Motivating, Satisfying, Refreshing,
    # Invigorating), when the patched StatMonitor has written them. Empty list
    # = the build has not been patched to persist nectar yet.
    nectars: list[NectarInfo] = Field(default_factory=list)


class CommandRequest(BaseModel):
    command: str = Field(..., description="Allowlisted command, e.g. '?start'")
    correlation_id: str | None = None


CommandResult = Literal["accepted", "rejected", "failed"]


class CommandResponse(BaseModel):
    id: str
    command: str
    result: CommandResult
    correlation_id: str | None = None
    detail: str | None = None


# -- Roblox private-server population (read-only) --------------------------
# The capture agent sends only the two integers it read from the page. The
# store rejects anything that is not a plausible population reading so the app
# never has to defend against malformed upstream data.

MAX_PLAUSIBLE_PLAYERS = 1000


class PopulationIngest(BaseModel):
    """A normalized population sample posted by the Windows capture agent."""

    playing: int = Field(..., ge=0, le=MAX_PLAUSIBLE_PLAYERS)
    max_players: int = Field(..., ge=1, le=MAX_PLAUSIBLE_PLAYERS)
    # When the screenshot was taken (agent clock); optional.
    captured_at: datetime | None = None

    @model_validator(mode="after")
    def _check_bounds(self) -> "PopulationIngest":
        if self.playing > self.max_players:
            raise ValueError("playing cannot exceed max_players")
        return self


class PopulationResponse(BaseModel):
    """Latest known private-server population, or a reason it is unavailable."""

    available: bool
    playing: int | None = None
    max_players: int | None = None
    captured_at: datetime | None = None
    received_at: datetime | None = None
    age_seconds: int | None = None
    stale: bool = False
    source: str = "roblox-web-screenshot"
    detail: str | None = None
