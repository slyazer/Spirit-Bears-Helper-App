from __future__ import annotations

import asyncio

from app.config import AccountConfig
from app.natro.service import ALLOWED_COMMANDS, NatroService


def _svc(tmp_path, ambiguous: bool = False) -> NatroService:
    acc = AccountConfig(
        id="acct",
        name="acct",
        natro_dir=str(tmp_path),
        command_ambiguous=ambiguous,
    )
    return NatroService(acc, discord_base="https://discord.com/api/v10")


def test_allowlist_contains_expected() -> None:
    for cmd in ("start", "stop", "pause", "resume", "planters", "timers", "status", "help"):
        assert cmd in ALLOWED_COMMANDS


def test_rejects_unknown_command(tmp_path) -> None:
    svc = _svc(tmp_path)
    resp = asyncio.run(svc.send_command("rm -rf /", None))
    assert resp.result == "rejected"


def test_accepts_prefixed_command_normalization(tmp_path) -> None:
    # No bot token -> will fail gracefully, but must be recognized (not rejected).
    svc = _svc(tmp_path)
    resp = asyncio.run(svc.send_command("?start", None))
    assert resp.result == "failed"
    assert "token" in (resp.detail or "").lower()


def test_ambiguous_account_refused(tmp_path) -> None:
    svc = _svc(tmp_path, ambiguous=True)
    resp = asyncio.run(svc.send_command("start", None))
    assert resp.result == "rejected"
    assert "shared" in (resp.detail or "").lower() or "either" in (resp.detail or "").lower()
