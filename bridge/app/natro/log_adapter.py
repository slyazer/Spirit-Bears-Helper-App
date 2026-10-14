"""Parses Natro Macro's ``settings/debug_log.txt``.

Format (written by ``Status.ahk`` -> ``nm_status`` at line ~685)::

    [MM/DD][HH:MM:SS] State: Detail - extra detail
    [08/31][18:43:07] Gathering: Following - Strawberry - Limit 10:00 - ...
    [08/31][18:56:14] End: Macro

The file is appended continuously and trimmed to 4 MiB by the macro, so we
read from the tail. From the most recent meaningful line we derive the live
macro state (running/paused/stopped) and the current field.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

_LINE_RE = re.compile(
    r"^\[(?P<md>\d{2}/\d{2})\]\[(?P<hms>\d{2}:\d{2}:\d{2})\]\s*(?P<body>.*)$"
)

# States that indicate the macro is doing something (running).
_RUNNING_STATES = {
    "Begin",
    "Gathering",
    "Traveling",
    "Resetting",
    "Converting",
    "Placing",
    "Collecting",
    "Holding",
    "Attacking",
    "Looting",
    "Defeated",
    "Found",
    "Boosted",
    "Claimed",
    "Completed",
    "Obtained",
    "Starting",
    "Interrupted",
    "Reporting",
    "Warning",
    "Keeping",
    "Confirmed",
    "Detected",
    "Attempting",
}

_STOPPED_STATES = {"End", "Startup", "GUI", "Closing"}


@dataclass
class LogLine:
    timestamp: datetime | None
    state: str
    detail: str
    raw: str


@dataclass
class LiveStatus:
    macro_state: str  # running | paused | stopped | unknown
    field: str | None
    last_line_time: datetime | None
    last_update_seen_at: float | None  # file mtime
    recent_lines: list[LogLine] = field(default_factory=list)
    boosted_field: str | None = None  # field of the last "Boosted: X" / "Gathering ... - Boosted" line
    field_since: float | None = None  # epoch when the current field was entered
    field_limit_seconds: int | None = None  # "Limit MM:SS" cap for the current field
    last_state: str | None = None  # state of the most recent log line
    last_detail: str | None = None  # detail of the most recent log line


def _parse_ts(md: str, hms: str, now: datetime | None = None) -> datetime | None:
    now = now or datetime.now()
    try:
        month, day = (int(x) for x in md.split("/"))
        h, m, s = (int(x) for x in hms.split(":"))
        candidate = now.replace(
            month=month, day=day, hour=h, minute=m, second=s, microsecond=0
        )
        # Natro logs without a year; if the date is far in the future, assume
        # it was the previous year (year rollover around New Year).
        if candidate - now > timedelta(days=2):
            candidate = candidate.replace(year=candidate.year - 1)
        return candidate
    except (ValueError, TypeError):
        return None


def _extract_field(state: str, detail: str) -> str | None:
    """Best-effort current-field extraction from a status line.

    Examples:
      ``Gathering: Following - Strawberry - Limit 10:00 - ...``  -> Strawberry
      ``Gathering: Spider - Limit 10:00 - ...``                  -> Spider
      ``Traveling: Spider (Attempt 2)``                          -> Spider
      ``Gathering: Planter - Spider - ...``                      -> Spider
    """
    if state not in {"Gathering", "Traveling"}:
        return None
    detail = detail.strip()
    if not detail:
        return None
    parts = [p.strip() for p in detail.split(" - ")]
    first = parts[0]
    # "Gathering: Ended - ..." is the end of a cycle, not a field.
    if first == "Ended":
        return None
    # "Following" / "Planter" / "Active" prefixes mean the real field is next.
    if state == "Gathering" and first in {"Following", "Planter", "Active"} and len(parts) > 1:
        candidate = parts[1]
    else:
        candidate = first
    # Traveling may be "Spider (Attempt 2)".
    candidate = re.sub(r"\s*\(.*?\)\s*$", "", candidate).strip()
    if not candidate or candidate.lower() in {"wealth clock", "none", "n/a"}:
        return None
    return candidate


_LIMIT_RE = re.compile(r"\bLimit\s+(\d{1,3}):(\d{2})\b")


def _parse_limit(detail: str) -> int | None:
    """Parse a ``Limit MM:SS`` segment into seconds (e.g. ``Limit 10:00`` -> 600)."""
    m = _LIMIT_RE.search(detail)
    if not m:
        return None
    return int(m.group(1)) * 60 + int(m.group(2))


def read_live_status(path: Path, tail_bytes: int = 500_000) -> LiveStatus:
    """Read the tail of the debug log and derive the current macro state."""
    if not path.exists():
        return LiveStatus("unknown", None, None, None, [])

    mtime = path.stat().st_mtime
    size = path.stat().st_size
    with path.open("rb") as fh:
        if size > tail_bytes:
            fh.seek(size - tail_bytes)
            fh.readline()  # discard partial first line
        data = fh.read().decode("utf-8", errors="replace")

    lines = data.splitlines()
    recent: list[LogLine] = []
    field: str | None = None
    boosted_field: str | None = None
    field_since: float | None = None
    field_limit_seconds: int | None = None
    prev_state: str | None = None
    last_state: str | None = None
    last_detail: str | None = None
    for raw in lines:
        m = _LINE_RE.match(raw.rstrip("\r"))
        if not m:
            continue
        ts = _parse_ts(m.group("md"), m.group("hms"))
        body = m.group("body")
        if ": " in body:
            state, detail = body.split(": ", 1)
        elif ":" in body:
            state, detail = body.split(":", 1)
        else:
            state, detail = body, ""
        state = state.strip()
        detail = detail.strip()
        if state:
            last_state = state
            last_detail = detail
        recent.append(LogLine(ts, state, detail, raw))
        if state in {"Begin", "End", "Startup"}:
            field = None
            boosted_field = None
            field_since = None
            field_limit_seconds = None
        elif state in {"Gathering", "Traveling"}:
            extracted = _extract_field(state, detail)
            if extracted:
                # A fresh gather (Gathering preceded by anything other than
                # another Gathering) restarts the window even if the macro
                # cycles back into the same field.
                fresh_gather = state == "Gathering" and prev_state != "Gathering"
                if field is None or extracted.lower() != field.lower() or fresh_gather:
                    field = extracted
                    field_since = ts.timestamp() if ts else None
                    field_limit_seconds = _parse_limit(detail)
                else:
                    # Same field; adopt a limit we may have missed earlier.
                    limit = _parse_limit(detail)
                    if limit is not None:
                        field_limit_seconds = limit
                # "Gathering: Pine Tree - Boosted - ..." marks an actively-boosted field.
                if state == "Gathering" and "boosted" in detail.lower():
                    boosted_field = extracted
        elif state == "Boosted":
            candidate = _clean_boost_detail(detail)
            if candidate:
                boosted_field = candidate
        if state:
            prev_state = state

    state = _derive_state(recent)
    last_time = recent[-1].timestamp if recent else None
    return LiveStatus(
        state,
        field,
        last_time,
        mtime,
        boosted_field=boosted_field,
        recent_lines=recent[-50:],
        field_since=field_since,
        field_limit_seconds=field_limit_seconds,
        last_state=last_state,
        last_detail=last_detail,
    )


def _clean_boost_detail(detail: str) -> str | None:
    """Normalize the field name in a ``Boosted: <field>`` line.

    Examples: ``Pine Tree`` -> ``Pine Tree``, ``Pine Tree (Attempt 2)`` -> ``Pine Tree``.
    """
    candidate = detail.split(" - ", 1)[0].strip()
    candidate = re.sub(r"\s*\(.*?\)\s*$", "", candidate).strip()
    if not candidate or candidate.lower() in {"boosted", "none", "n/a", "wealth clock"}:
        return None
    return candidate


def _derive_state(lines: list[LogLine]) -> str:
    """Scan backwards for the most recent state-changing line."""
    for line in reversed(lines):
        s = line.state
        if s == "Paused":
            return "paused"
        if s in _STOPPED_STATES:
            return "stopped"
        if s in _RUNNING_STATES:
            return "running"
    return "unknown"
