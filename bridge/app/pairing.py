"""QR pairing: let a phone join the bridge without typing an address.

The PC (setup wizard / ``python -m app.cli pair``) mints a **short-lived,
single-use** pairing code and renders a QR that encodes both the address and the
code. A phone that scans it calls ``POST /api/v1/auth/pair`` and receives a
normal session — no password typing for first-run setup.

The code is only ever a convenience *in addition to* username/password auth:
pairing can be disabled entirely, and any phone that prefers to type an address
can still sign in manually. Only a salted hash of the code is written to disk,
the file is deleted the moment it is claimed, and the code expires quickly.

Payload format (a custom URI so scanners can hand it to the app):

    macromonitor://pair?h=<host>&p=<port>&n=<name>[&c=<code>]

If ``c`` is omitted the app uses the address and then asks for credentials.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import secrets
import socket
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode

logger = logging.getLogger("macro_monitor.pairing")

PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 8
DEFAULT_TTL_SECONDS = 600
PAIRING_SCHEME = "macromonitor"
PAIRING_HOST = "pair"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _hash_code(code: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{code}".encode("utf-8")).hexdigest()


@dataclass
class PairingInfo:
    code: str
    expires_at: datetime


def new_code() -> str:
    return "".join(secrets.choice(PAIRING_ALPHABET) for _ in range(CODE_LENGTH))


def create_pairing(path: Path, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> PairingInfo:
    """Mint a fresh code and store only its salted hash (atomic write)."""
    code = new_code()
    salt = secrets.token_hex(16)
    expires = _now() + timedelta(seconds=max(ttl_seconds, 30))
    payload = {
        "version": 1,
        "salt": salt,
        "code_hash": _hash_code(code, salt),
        "created_at": _now().isoformat(),
        "expires_at": expires.isoformat(),
    }
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return PairingInfo(code=code, expires_at=expires)


def _read(path: Path) -> dict | None:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def pending_pairing(path: Path) -> PairingInfo | None:
    """Return metadata for an unexpired pending code (code itself is unknown)."""
    data = _read(path)
    if not data:
        return None
    try:
        expires = datetime.fromisoformat(data["expires_at"])
    except (KeyError, ValueError):
        return None
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if _now() >= expires:
        return None
    return PairingInfo(code="", expires_at=expires)


def claim_pairing(path: Path, code: str) -> bool:
    """Validate and consume a pairing code. Deletes the file on success/expiry."""
    path = Path(path)
    data = _read(path)
    if not data:
        return False
    try:
        expires = datetime.fromisoformat(data["expires_at"])
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
        salt = data["salt"]
        expected = data["code_hash"]
    except (KeyError, ValueError):
        _unlink(path)
        return False
    if _now() >= expires:
        _unlink(path)
        return False
    provided = _hash_code((code or "").strip().upper(), salt)
    ok = hmac.compare_digest(provided, expected)
    if ok:
        _unlink(path)
    return ok


def clear_pairing(path: Path) -> None:
    _unlink(Path(path))


def _unlink(path: Path) -> None:
    try:
        path.unlink()
    except OSError:
        pass


def build_payload(
    host: str, port: int, name: str, code: str | None = None
) -> str:
    params = {"h": host, "p": str(port), "n": name}
    if code:
        params["c"] = code
    return f"{PAIRING_SCHEME}://{PAIRING_HOST}?{urlencode(params)}"


def parse_payload(payload: str) -> dict | None:
    """Parse a pairing URI (used by tests / future tooling)."""
    from urllib.parse import parse_qs, urlsplit

    try:
        parts = urlsplit(payload)
    except ValueError:
        return None
    if parts.scheme != PAIRING_SCHEME or parts.netloc != PAIRING_HOST:
        return None
    query = parse_qs(parts.query)
    host = (query.get("h") or [""])[0]
    if not host:
        return None
    return {
        "host": host,
        "port": int((query.get("p") or ["8787"])[0]),
        "name": (query.get("n") or ["Macro Monitor Bridge"])[0],
        "code": (query.get("c") or [None])[0],
    }


def local_ipv4_addresses() -> list[str]:
    """Best-effort list of this machine's non-loopback IPv4 addresses."""
    addresses: list[str] = []

    # The primary outbound interface (does not actually send anything).
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(("8.8.8.8", 80))
            addresses.append(probe.getsockname()[0])
        finally:
            probe.close()
    except OSError:
        pass

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in addresses:
                addresses.append(ip)
    except OSError:
        pass

    return [ip for ip in addresses if not ip.startswith("127.")]


def primary_host() -> str:
    addresses = local_ipv4_addresses()
    return addresses[0] if addresses else "127.0.0.1"


def qr_png(payload: str, scale: int = 6) -> bytes | None:
    """Render a QR PNG using segno, or ``None`` if segno is not installed."""
    try:
        import io

        import segno  # type: ignore
    except Exception:  # noqa: BLE001
        return None
    buffer = io.BytesIO()
    segno.make(payload, error="m").save(buffer, kind="png", scale=scale, border=2)
    return buffer.getvalue()


def qr_ascii(payload: str, unicode_ok: bool | None = None) -> str | None:
    """Render a QR as block characters for the console, or ``None`` if unavailable."""
    try:
        import sys

        import segno  # type: ignore
    except Exception:  # noqa: BLE001
        return None

    if unicode_ok is None:
        encoding = (getattr(sys.stdout, "encoding", "") or "").lower()
        unicode_ok = "utf" in encoding

    on = "\u2588\u2588" if unicode_ok else "##"
    off = "  "
    border = 2

    qr = segno.make(payload, error="m")
    rows = [list(row) for row in qr.matrix]
    size = len(rows)
    width = size + border * 2
    blank = off * width

    lines: list[str] = [blank] * border
    for row in rows:
        body = "".join(on if cell else off for cell in row)
        lines.append(off * border + body + off * border)
    lines.extend([blank] * border)
    return "\n".join(lines)
