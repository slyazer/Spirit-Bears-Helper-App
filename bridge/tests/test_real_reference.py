"""Integration test against the real Natro reference copies.

Skipped automatically when the reference copies are not present (e.g. in CI).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.config import AccountConfig
from app.natro.service import NatroService

ROOT = Path(__file__).resolve().parents[2]
FOLLOWER = ROOT / "natro_reference" / "THIS ONE - 67 FOLLOWER"
LEADER = ROOT / "natro_reference" / "THIS ONE - 61 LEADER"

pytestmark = pytest.mark.skipif(
    not FOLLOWER.exists(), reason="Natro reference copy not present"
)


def _svc(dir: Path, account_id: str) -> NatroService:
    return NatroService(
        AccountConfig(id=account_id, name=account_id, natro_dir=str(dir)),
        discord_base="https://discord.com/api/v10",
    )


def test_follower_status_reads_real_values() -> None:
    st = _svc(FOLLOWER, "follower67").status(force=True)
    assert st.macro_state in {"running", "paused", "stopped", "unknown"}
    assert st.honey_total  # e.g. "2.644 B"
    assert st.honey_per_hour  # e.g. "6.189 B"
    assert st.runtime_seconds is not None


def test_follower_planters_read_real_values() -> None:
    resp = _svc(FOLLOWER, "follower67").planters()
    if not resp.available:
        pytest.skip("no active planters in reference copy")
    assert len(resp.planters) >= 1
    for p in resp.planters:
        assert p.name
        assert p.field


@pytest.mark.skipif(not LEADER.exists(), reason="61 leader copy not present")
def test_leader_status_reads_real_values() -> None:
    st = _svc(LEADER, "leader61").status(force=True)
    assert st.macro_state in {"running", "paused", "stopped", "unknown"}
