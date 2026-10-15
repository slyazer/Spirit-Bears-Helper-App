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


def _capture(svc: NatroService) -> dict:
    sent: dict = {}

    class _FakeClient:
        async def send_command(self, channel: str, payload: str) -> None:
            sent["channel"] = channel
            sent["payload"] = payload

    svc._discord = lambda ini: _FakeClient()  # type: ignore[assignment]
    svc._command_channel = lambda ini: "chan-1"  # type: ignore[assignment]
    return sent


def test_close_is_sent_with_roblox_target(tmp_path) -> None:
    svc = _svc(tmp_path)
    sent = _capture(svc)
    resp = asyncio.run(svc.send_command("close", None))
    assert resp.result == "accepted"
    # Natro's `close` needs a window title or it is a no-op.
    assert sent["payload"] == "?close Roblox"


def test_other_commands_sent_verbatim(tmp_path) -> None:
    svc = _svc(tmp_path)
    sent = _capture(svc)
    resp = asyncio.run(svc.send_command("start", None))
    assert resp.result == "accepted"
    assert sent["payload"] == "?start"
