"""In-memory store for the owner's Roblox private-server population.

A small, read-only feature: a Windows capture agent (``tools/roblox-population-*``)
opens the private-servers page in the owner's already-logged-in browser, OCRs the
two integer fields it shows, and POSTs ``playing`` / ``max_players`` here. The
Android app reads the latest sample through ``GET /api/v1/roblox/population``.

Only the two integers are ever accepted or retained: no usernames, player ids,
player tokens, access codes, or screenshots. The store keeps the last *valid*
sample when a refresh fails and simply marks it ``stale`` once it is older than
the configured window, so a transient capture failure never fabricates a value.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable

from .models import PopulationResponse

# Guard the two integers against nonsensical OCR output even before the
# playing<=max_players relationship is checked.
MAX_PLAUSIBLE_PLAYERS = 1000


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class PopulationSample:
    playing: int
    max_players: int
    captured_at: datetime | None
    received_at: datetime


class PopulationStore:
    """Thread-safe holder for the most recent population sample."""

    def __init__(
        self,
        stale_after_seconds: int = 900,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._stale_after_seconds = max(1, int(stale_after_seconds))
        self._clock = clock or _utcnow
        self._lock = threading.Lock()
        self._sample: PopulationSample | None = None

    def record(
        self,
        playing: int,
        max_players: int,
        captured_at: datetime | None = None,
    ) -> None:
        """Replace the current sample with a newly validated one."""
        now = self._clock()
        sample = PopulationSample(
            playing=int(playing),
            max_players=int(max_players),
            captured_at=captured_at,
            received_at=now,
        )
        with self._lock:
            self._sample = sample

    def snapshot(self) -> PopulationResponse:
        """Return the current view, marking an old sample as stale."""
        with self._lock:
            sample = self._sample
        now = self._clock()

        if sample is None:
            return PopulationResponse(
                available=False,
                stale=False,
                source="roblox-web-screenshot",
                detail="No population sample has been captured yet.",
            )

        age_seconds = max(0, int((now - sample.received_at).total_seconds()))
        stale = age_seconds > self._stale_after_seconds
        return PopulationResponse(
            available=True,
            playing=sample.playing,
            max_players=sample.max_players,
            captured_at=sample.captured_at,
            received_at=sample.received_at,
            age_seconds=age_seconds,
            stale=stale,
            source="roblox-web-screenshot",
            detail=(
                "Last capture is older than the freshness window."
                if stale
                else None
            ),
        )

    def clear(self) -> None:
        with self._lock:
            self._sample = None
