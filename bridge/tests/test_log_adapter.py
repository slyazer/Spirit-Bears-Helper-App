from __future__ import annotations

from pathlib import Path

from app.natro.log_adapter import read_live_status


def _write(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "debug_log.txt"
    p.write_text(text, encoding="utf-8")
    return p


def test_running_and_field(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:00:05] Gathering: Following - Strawberry - Limit 10:00 - Center - Upper Left\n",
    )
    st = read_live_status(log)
    assert st.macro_state == "running"
    assert st.field == "Strawberry"


def test_stopped(tmp_path: Path) -> None:
    log = _write(tmp_path, "[08/31][18:00:00] Begin: Macro\n[08/31][18:10:00] End: Macro\n")
    assert read_live_status(log).macro_state == "stopped"


def test_paused(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:05:00] Paused: Press F2 to resume\n",
    )
    assert read_live_status(log).macro_state == "paused"


def test_traveling_field_strips_attempt(tmp_path: Path) -> None:
    log = _write(tmp_path, "[08/31][19:00:00] Traveling: Spider (Attempt 2)\n")
    st = read_live_status(log)
    assert st.macro_state == "running"
    assert st.field == "Spider"


def test_missing_file(tmp_path: Path) -> None:
    st = read_live_status(tmp_path / "nope.txt")
    assert st.macro_state == "unknown"
    assert st.field is None


def test_field_limit_and_since(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:00:05] Traveling: Pine Tree\n"
        "[08/31][18:00:20] Gathering: Pine Tree - Limit 10:00 - Skillet - M - Upper Left 7\n",
    )
    st = read_live_status(log)
    assert st.field == "Pine Tree"
    assert st.field_limit_seconds == 600
    assert st.field_since is not None


def test_field_change_restarts_since(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:00:05] Gathering: Pine Tree - Limit 10:00 - Skillet - M\n"
        "[08/31][18:10:06] Traveling: Coconut\n"
        "[08/31][18:10:20] Gathering: Coconut - Limit 5:00 - Claw - M\n",
    )
    st = read_live_status(log)
    assert st.field == "Coconut"
    assert st.field_limit_seconds == 300


def test_bag_limit_has_no_seconds(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:00:05] Gathering: Rose - Bag Limit - Scythe - M\n",
    )
    st = read_live_status(log)
    assert st.field == "Rose"
    assert st.field_limit_seconds is None


def test_same_field_regather_restarts_since(tmp_path: Path) -> None:
    from datetime import datetime

    now = datetime.now()
    md = now.strftime("%m/%d")
    log = _write(
        tmp_path,
        f"[{md}][18:00:00] Begin: Macro\n"
        f"[{md}][18:00:05] Gathering: Pine Tree - Limit 10:00 - Skillet - M\n"
        f"[{md}][18:10:10] Resetting: Character 1\n"
        f"[{md}][18:10:30] Traveling: Pine Tree\n"
        f"[{md}][18:10:45] Gathering: Pine Tree - Limit 10:00 - Skillet - M\n",
    )
    st = read_live_status(log)
    assert st.field == "Pine Tree"
    assert st.field_limit_seconds == 600
    expected = now.replace(hour=18, minute=10, second=45, microsecond=0).timestamp()
    assert st.field_since == expected


def test_last_line_state_and_detail(tmp_path: Path) -> None:
    log = _write(
        tmp_path,
        "[08/31][18:00:00] Begin: Macro\n"
        "[08/31][18:00:05] Gathering: Pine Tree - Limit 10:00 - Skillet - M\n"
        "[08/31][18:10:06] Resetting: Character 1\n"
        "[08/31][18:10:20] Converting: Balloon\n",
    )
    st = read_live_status(log)
    assert st.last_state == "Converting"
    assert st.last_detail == "Balloon"
    # The last-gathered field is still known even while we convert at the hive.
    assert st.field == "Pine Tree"
