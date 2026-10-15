from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path

from app.pairing import (
    PAIRING_ALPHABET,
    build_payload,
    claim_pairing,
    create_pairing,
    parse_payload,
    pending_pairing,
    qr_ascii,
    qr_png,
)


def test_create_and_claim(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    info = create_pairing(path, ttl_seconds=600)
    assert len(info.code) == 8
    assert claim_pairing(path, info.code) is True


def test_code_is_single_use(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    info = create_pairing(path, ttl_seconds=600)
    assert claim_pairing(path, info.code) is True
    assert claim_pairing(path, info.code) is False


def test_wrong_code_rejected(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    create_pairing(path, ttl_seconds=600)
    assert claim_pairing(path, "ZZZZZZZZ") is False


def test_code_uses_unambiguous_alphabet(tmp_path: Path) -> None:
    info = create_pairing(tmp_path / "pairing.json", ttl_seconds=600)
    assert all(ch in PAIRING_ALPHABET for ch in info.code)


def test_code_is_case_insensitive(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    info = create_pairing(path, ttl_seconds=600)
    assert claim_pairing(path, info.code.lower()) is True


def test_expired_code_rejected(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    info = create_pairing(path, ttl_seconds=600)
    data = json.loads(path.read_text(encoding="utf-8"))
    from datetime import datetime, timezone

    data["expires_at"] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    path.write_text(json.dumps(data), encoding="utf-8")
    assert claim_pairing(path, info.code) is False


def test_pending_pairing_reflects_lifecycle(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    assert pending_pairing(path) is None
    create_pairing(path, ttl_seconds=600)
    assert pending_pairing(path) is not None
    assert pending_pairing(path).code == ""


def test_payload_roundtrip() -> None:
    payload = build_payload("192.168.1.100", 8787, "Macro Monitor Bridge", "ABCD2345")
    parsed = parse_payload(payload)
    assert parsed == {
        "host": "192.168.1.100",
        "port": 8787,
        "name": "Macro Monitor Bridge",
        "code": "ABCD2345",
    }


def test_payload_without_code() -> None:
    payload = build_payload("my-pc.local", 8787, "Bridge")
    parsed = parse_payload(payload)
    assert parsed is not None
    assert parsed["code"] is None


def test_parse_rejects_foreign_uri() -> None:
    assert parse_payload("https://example.com") is None


def test_qr_png_is_png_bytes() -> None:
    png = qr_png(build_payload("192.168.1.100", 8787, "Bridge", "ABCD2345"))
    assert png is not None
    assert png.startswith(b"\x89PNG\r\n\x1a\n")


def test_qr_ascii_renders_square_grid() -> None:
    lines = qr_ascii(build_payload("192.168.1.100", 8787, "Bridge"), unicode_ok=False).splitlines()
    assert len(lines) >= 21
    assert all(len(row) == len(lines[0]) for row in lines)
    assert "#" in "".join(lines)

