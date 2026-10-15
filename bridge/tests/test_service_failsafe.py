from __future__ import annotations

from pathlib import Path

import pytest

from app.natro.log_adapter import read_live_status
from app.natro.service import NatroService
from app.config import AccountConfig


def _write(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "settings" / "debug_log.txt"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    return p


def _write_ini(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "settings" / "nm_config.ini"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    return p


def _svc(tmp_path: Path) -> NatroService:
    return NatroService(
        AccountConfig(id="a", name="a", natro_dir=str(tmp_path)),
        discord_base="https://discord.com/api/v10",
        stale_after_seconds=60,
    )


# -- boost detection from the log --------------------------------------


def test_boosted_field_from_boosted_line(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:01:00] Boosted: Pine Tree\n"
        "[08/31][18:01:05] Gathering: Pine Tree - Boosted - Limit 10:00 - Skillet - M\n",
    )
    st = read_live_status(log)
    assert st.boosted_field == "Pine Tree"
    assert st.field == "Pine Tree"


def test_boosted_field_reset_on_end(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:01:00] Boosted: Pine Tree\n"
        "[08/31][19:00:00] End: Macro\n"
        "[08/31][19:01:00] Begin: Macro\n"
        "[08/31][19:02:00] Gathering: Clover - Limit 10:00\n",
    )
    st = read_live_status(log)
    assert st.boosted_field is None
    assert st.field == "Clover"


# -- session-only stats + stale failsafe ---------------------------------


def test_status_uses_session_scoped_stats(tmp_path: Path) -> None:
    _write_ini(
        tmp_path,
        "[Status]\n"
        "SessionRuntime=120\n"
        "SessionDisconnects=0\n"
        "SessionTotalHoney=1.0 B\n"
        "HoneyAverage=144.5 B\n"
        "SessionPlantersCollected=3\n"
        "TotalDisconnects=69\n"
        "TotalRuntime=3000000\n",
    )
    _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:01:00] Gathering: Pine Tree - Limit 10:00\n"
        "[08/31][18:02:00] Gathering: Pine Tree - Limit 10:00\n",
    )
    st = _svc(tmp_path).status(force=True)
    assert st.runtime_seconds == 120  # not TotalRuntime
    assert st.disconnected_count == 0  # not TotalDisconnects=69
    assert st.planters_collected == 3  # not all-time


# -- nectar (written by the patched StatMonitor) -------------------------


def test_planters_exposes_nectar_when_present(tmp_path: Path) -> None:
    _write_ini(
        tmp_path,
        "[Status]\n"
        "NectarComforting=42\n"
        "NectarMotivating=0\n"
        "NectarSatisfying=88.5\n"
        "NectarRefreshing=10\n"
        "NectarInvigorating=100\n",
    )
    resp = _svc(tmp_path).planters()
    levels = {n.name: n.level for n in resp.nectars}
    assert levels == {
        "Comforting": 42.0,
        "Motivating": 0.0,
        "Satisfying": 88.5,
        "Refreshing": 10.0,
        "Invigorating": 100.0,
    }


def test_planters_nectar_empty_when_unpatched(tmp_path: Path) -> None:
    _write_ini(tmp_path, "[Status]\nSessionTotalHoney=1.0 B\n")
    resp = _svc(tmp_path).planters()
    assert resp.nectars == []


def test_status_unknown_when_stale(tmp_path: Path) -> None:
    _write_ini(tmp_path, "[Status]\nSessionTotalHoney=1.0 B\nHoneyAverage=10.0 B\n")
    _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:01:00] Gathering: Pine Tree - Limit 10:00\n",
    )
    # Rewind the log's mtime so it is far older than the stale threshold.
    log = tmp_path / "settings" / "debug_log.txt"
    import os
    import time as _t

    old = _t.time() - 7200
    os.utime(log, (old, old))
    st = _svc(tmp_path).status(force=True)
    assert st.stale is True
    assert st.macro_state == "unknown"
    assert st.field_boosted is None