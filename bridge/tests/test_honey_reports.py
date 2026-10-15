"""Regression tests for real current-honey math and report filtering.

Natro persists only the session honey gain. The true balance lives in the
Startup Report embed ("Current Honey: ..."), and ``current = start + session``.
Report channels are shared by two bots, so reports must be filtered by the
account's own bot identity and de-duplicated per hour.
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from pathlib import Path

from app.config import AccountConfig
from app.natro.discord_adapter import DiscordMessage
from app.natro.service import (
    NatroService,
    _filter_report_messages,
    _format_honey,
    _parse_honey,
    _select_start_honey,
)


def _msg(mid, author, author_id, ts, image_url=None, description=None, embeds=None):
    if embeds is None:
        embeds = []
    if description is not None:
        embeds = [{"description": description}]
    elif image_url is not None:
        embeds = [{"image": {"url": image_url}}]
    return DiscordMessage(
        id=mid,
        channel_id="1",
        author=author,
        author_id=author_id,
        timestamp=ts,
        content="",
        attachments=[],
        embeds=embeds,
    )


def _ts(hour, minute=0, day=1):
    return datetime(2024, 1, day, hour, minute, 0, tzinfo=timezone.utc)


# -- honey parsing ------------------------------------------------------


def test_parse_and_format_honey_round_trip() -> None:
    assert _parse_honey("793.8 B") == 793.8e9
    assert _parse_honey("467.1 M") == 467.1e6
    assert _parse_honey("35.54 B") == 35.54e9
    assert _parse_honey("1,234") == 1234.0
    assert _parse_honey("N/A") is None
    assert _parse_honey("") is None
    assert _format_honey(793.8e9 + 35.54e9) == "829.34 B"
    assert _format_honey(467.1e6) == "467.1 M"


# -- startup-report extraction -----------------------------------------


def test_select_start_honey_picks_newest() -> None:
    messages = [
        _msg("3", "b", "2", _ts(20), image_url="x"),  # hourly, no text
        _msg("2", "b", "2", _ts(19), description="**Current Honey:** 793.8 B"),
        _msg("1", "b", "2", _ts(18), description="Current Honey: 500 B"),
    ]
    assert _select_start_honey(messages) == 793.8e9


def test_select_start_honey_handles_na() -> None:
    messages = [_msg("1", "b", "2", _ts(18), description="Current Honey: **N/A**")]
    assert _select_start_honey(messages) is None


def test_select_start_honey_filters_by_bot_id() -> None:
    messages = [
        _msg("1", "follower", "222", _ts(20), description="Current Honey: **33.0 T**"),
        _msg("2", "leader", "111", _ts(19), description="Current Honey: **793.8 B**"),
    ]
    assert _select_start_honey(messages, bot_id="111", author_name="leader (PC A)") == 793.8e9


# -- report filtering ---------------------------------------------------


def test_filter_reports_by_bot_id_dedupes_hour() -> None:
    messages = [
        _msg("1", "follower", "111", _ts(19), image_url="a"),  # keep
        _msg("2", "leader", "222", _ts(19), image_url="b"),  # other bot
        _msg("3", "follower", "111", _ts(18, 30), image_url="d"),  # keep (newest hour 18)
        _msg("4", "follower", "111", _ts(18), image_url="c"),  # dup hour 18
    ]
    out = _filter_report_messages(messages, bot_id="111", author_name="follower", limit=10)
    kept = [m.id for m, _ in out]
    assert kept == ["1", "3"]


def test_filter_reports_falls_back_to_author_name() -> None:
    messages = [
        _msg("1", "follower", "", _ts(19), image_url="a"),  # keep (no id, name match)
        _msg("2", "leader", "", _ts(19), image_url="b"),  # name mismatch
    ]
    out = _filter_report_messages(
        messages, bot_id=None, author_name="follower (PC B)", limit=10
    )
    assert [m.id for m, _ in out] == ["1"]


# -- async status enrichment -------------------------------------------


class _FakeDiscord:
    def __init__(self, messages):
        self._messages = messages

    async def fetch_messages(self, channel_id, limit=25, before=None):
        return self._messages

    async def me(self):
        return {"id": "111", "username": "follower"}


def _write_ini(tmp_path: Path, text: str) -> None:
    p = tmp_path / "settings" / "nm_config.ini"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def test_status_async_computes_current_honey(tmp_path: Path) -> None:
    _write_ini(
        tmp_path,
        "[Status]\nSessionTotalHoney=35.54 B\nHoneyAverage=109.9 B\n",
    )
    svc = NatroService(
        AccountConfig(
            id="follower",
            name="follower (PC B)",
            natro_dir=str(tmp_path),
            report_channel_id="999",
        ),
        discord_base="https://discord.com/api/v10",
    )
    svc._discord = lambda ini: _FakeDiscord(  # type: ignore[assignment]
        [_msg("1", "follower", "111", _ts(18), description="**Current Honey:** 793.8 B")]
    )
    st = asyncio.run(svc.status_async(force=True))
    assert st.honey_total == "829.34 B"  # 793.8 + 35.54
    assert st.honey_session == "35.54 B"
    assert st.honey_per_hour == "109.9 B"


def test_status_async_without_startup_report_is_honest(tmp_path: Path) -> None:
    _write_ini(tmp_path, "[Status]\nSessionTotalHoney=35.54 B\n")
    svc = NatroService(
        AccountConfig(
            id="follower",
            name="follower (PC B)",
            natro_dir=str(tmp_path),
            report_channel_id="999",
        ),
        discord_base="https://discord.com/api/v10",
    )
    svc._discord = lambda ini: _FakeDiscord([])  # type: ignore[assignment]
    st = asyncio.run(svc.status_async(force=True))
    assert st.honey_total is None
    assert st.honey_session == "35.54 B"
    assert st.notes and "unavailable" in st.notes


def test_status_prefers_current_honey_from_patched_build(tmp_path: Path) -> None:
    # Patched StatMonitor.ahk writes the real balance directly to the ini.
    _write_ini(
        tmp_path,
        "[Status]\nCurrentHoney=941.54 B\n"
        "SessionTotalHoney=43.24 B\nHoneyAverage=171.5 B\n",
    )
    svc = NatroService(
        AccountConfig(
            id="leader",
            name="leader (PC A)",
            natro_dir=str(tmp_path),
            report_channel_id="999",
        ),
        discord_base="https://discord.com/api/v10",
    )

    def _explode(ini):  # noqa: ANN001
        raise AssertionError("Discord must not be consulted when CurrentHoney is present")

    svc._discord = _explode  # type: ignore[assignment]
    st = asyncio.run(svc.status_async(force=True))
    assert st.honey_total == "941.54 B"
    assert st.honey_session == "43.24 B"
    assert st.honey_per_hour == "171.5 B"
    assert st.notes and "directly" in st.notes
    # Sync path must also surface the balance without any network access.
    assert svc.status(force=True).honey_total == "941.54 B"


# -- activity-log filtering --------------------------------------------


def _content_msg(mid, text, ts, author="leader", author_id="111"):
    return DiscordMessage(
        id=mid,
        channel_id="1",
        author=author,
        author_id=author_id,
        timestamp=ts,
        content=text,
        attachments=[],
        embeds=[],
    )


def test_logs_drop_honey_pollen_snapshots(tmp_path: Path) -> None:
    svc = NatroService(
        AccountConfig(
            id="leader",
            name="leader (PC A)",
            natro_dir=str(tmp_path),
            main_channel_id="999",
        ),
        discord_base="https://discord.com/api/v10",
    )
    svc._discord = lambda ini: _FakeDiscord(  # type: ignore[assignment]
        [
            _content_msg("1", "[21:43:46] Current Honey/Pollen", _ts(21, 43)),
            _content_msg("2", "[21:41:36] Converting: Backpack", _ts(21, 41)),
            _content_msg("3", "[21:30:41] Traveling: Pine Tree", _ts(21, 30)),
        ]
    )
    resp = asyncio.run(svc.logs(limit=10))
    bodies = [entry.body or "" for entry in resp.logs]
    assert len(resp.logs) == 2
    assert all("Current Honey/Pollen" not in body for body in bodies)


# -- field-aware staleness ---------------------------------------------


def test_long_gather_is_not_stale(tmp_path: Path) -> None:
    import os
    import time

    settings = tmp_path / "settings"
    settings.mkdir()
    now = datetime.now()
    md, hms = now.strftime("%m/%d"), now.strftime("%H:%M:%S")
    log = settings / "debug_log.txt"
    log.write_text(
        f"[{md}][{hms}] Begin: Macro\n"
        f"[{md}][{hms}] Gathering: Pine Tree - Limit 10:00 - Skillet - M\n",
        encoding="utf-8",
    )
    old = time.time() - 300  # file untouched for 5 min (> 180s stale window)
    os.utime(log, (old, old))

    svc = NatroService(
        AccountConfig(id="leader", name="leader (PC A)", natro_dir=str(tmp_path)),
        discord_base="https://discord.com/api/v10",
    )
    st = svc.status(force=True)
    assert st.field == "Pine Tree"
    assert st.field_limit_seconds == 600
    assert st.field_since is not None
    assert st.stale is False


def test_converting_is_reported_as_activity(tmp_path: Path) -> None:
    import os
    import time

    settings = tmp_path / "settings"
    settings.mkdir()
    now = datetime.now()
    md, hms = now.strftime("%m/%d"), now.strftime("%H:%M:%S")
    log = settings / "debug_log.txt"
    log.write_text(
        f"[{md}][{hms}] Begin: Macro\n"
        f"[{md}][{hms}] Gathering: Pine Tree - Limit 10:00 - Skillet - M\n"
        f"[{md}][{hms}] Resetting: Character 1\n"
        f"[{md}][{hms}] Converting: Balloon\n",
        encoding="utf-8",
    )
    # Natro can be silent through a long balloon refresh while converting.
    old = time.time() - 300  # 5 min > the default 180s stale window
    os.utime(log, (old, old))

    svc = NatroService(
        AccountConfig(id="leader", name="leader (PC A)", natro_dir=str(tmp_path)),
        discord_base="https://discord.com/api/v10",
    )
    st = svc.status(force=True)
    assert st.activity == "Converting"
    assert st.activity_detail == "Balloon"
    # Converting is a legitimately quiet state: not stale, and the last field is
    # retained so the app has context.
    assert st.stale is False
    assert st.field == "Pine Tree"


def test_stale_drops_activity_and_field(tmp_path: Path) -> None:
    import os
    import time

    settings = tmp_path / "settings"
    settings.mkdir()
    now = datetime.now()
    md, hms = now.strftime("%m/%d"), now.strftime("%H:%M:%S")
    log = settings / "debug_log.txt"
    log.write_text(
        f"[{md}][{hms}] Begin: Macro\n"
        f"[{md}][{hms}] Converting: Balloon\n",
        encoding="utf-8",
    )
    old = time.time() - 1200  # 20 min, well past the converting grace
    os.utime(log, (old, old))

    svc = NatroService(
        AccountConfig(id="leader", name="leader (PC A)", natro_dir=str(tmp_path)),
        discord_base="https://discord.com/api/v10",
    )
    st = svc.status(force=True)
    assert st.stale is True
    assert st.activity is None
    assert st.field is None

