"""Discord REST adapter (via Windows curl.exe).

Natro Macro's own bot posts hourly report images and status embeds to its
Report/Main channel. Rather than re-implementing Natro internals, the bridge
reads those real messages back through the Discord API using the account's bot
token (stored in ``nm_config.ini`` under ``[Status] BotToken``).

Why curl.exe?  Discord is behind Cloudflare which TLS-fingerprints callers.
httpx/requests get ``403 error 1010`` (banned signature) from this machine,
but Windows' built-in ``curl.exe`` (schannel) is accepted. We shell out to it
for reliability; requests are small, low-volume and now work consistently.

Commands are dispatched the same way Natro already consumes them: by posting a
message starting with the configured command prefix (default ``?``) into the
account's MainChannelID, where Natro's ``GetCommands`` poller picks it up.
"""

from __future__ import annotations

import asyncio
import json
import logging
import shutil
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlencode

logger = logging.getLogger("macro_monitor.discord")

_FALLBACK_CURL = r"C:\Windows\System32\curl.exe"


class DiscordAPIError(RuntimeError):
    """Raised when Discord returns a non-2xx response (or the request fails)."""

    def __init__(self, status: int, body: str, url: str) -> None:
        super().__init__(f"Discord API {status}: {body[:200]}")
        self.status = status
        self.body = body
        self.url = url


@dataclass
class DiscordMessage:
    id: str
    channel_id: str
    author: str
    author_id: str
    timestamp: datetime
    content: str
    attachments: list[dict]
    embeds: list[dict]


def _find_curl() -> str:
    found = shutil.which("curl")
    if found:
        return found
    return _FALLBACK_CURL


class DiscordClient:
    def __init__(self, base_url: str, token: str, timeout: float = 15.0) -> None:
        self._base = base_url.rstrip("/")
        self._token = token
        self._timeout = timeout
        self._curl = _find_curl()

    # -- low-level -------------------------------------------------------
    @staticmethod
    def _run_blocking(args: list[str], timeout: float) -> tuple[int, bytes]:
        try:
            proc = subprocess.run(
                args,
                capture_output=True,
                timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired:
            raise DiscordAPIError(0, "bridge -> curl timed out", args[-1]) from None
        return proc.returncode, proc.stdout

    async def _request_bytes(self, url: str) -> bytes:
        args = [
            self._curl,
            "-sS",
            "-H",
            f"Authorization: Bot {self._token}",
            "--fail-with-body",
            url,
        ]
        rc, out = await asyncio.to_thread(self._run_blocking, args, self._timeout)
        if rc != 0:
            raise DiscordAPIError(rc or 599, out.decode("utf-8", "replace"), url)
        return out

    async def _request_json(self, method: str, url: str, body: dict | None = None) -> Any:
        args = [
            self._curl,
            "-sS",
            "-X",
            method,
            "-H",
            f"Authorization: Bot {self._token}",
            "-H",
            "Accept: application/json",
        ]
        if body is not None:
            args += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(body)]
        args.append("--fail-with-body")
        args.append(url)
        rc, out = await asyncio.to_thread(self._run_blocking, args, self._timeout)
        if rc != 0:
            raise DiscordAPIError(rc or 599, out.decode("utf-8", "replace"), url)
        return json.loads(out.decode("utf-8", "replace"))

    # -- API surface -----------------------------------------------------
    async def fetch_messages(
        self,
        channel_id: str,
        limit: int = 25,
        after: str | None = None,
        before: str | None = None,
    ) -> list[DiscordMessage]:
        params: dict[str, str | int] = {"limit": min(limit, 100)}
        if after:
            params["after"] = after
        if before:
            params["before"] = before
        url = f"{self._base}/channels/{channel_id}/messages?{urlencode(params)}"
        raw = await self._request_json("GET", url)
        result: list[DiscordMessage] = []
        for m in raw:
            ts = m.get("timestamp")
            author = m.get("author") or {}
            result.append(
                DiscordMessage(
                    id=m["id"],
                    channel_id=channel_id,
                    author=author.get("username", "unknown"),
                    author_id=str(author.get("id") or ""),
                    timestamp=_parse_iso(ts),
                    content=m.get("content") or "",
                    attachments=m.get("attachments") or [],
                    embeds=m.get("embeds") or [],
                )
            )
        return result

    async def me(self) -> dict:
        """Identify the bot account behind this token (GET /users/@me)."""
        url = f"{self._base}/users/@me"
        return await self._request_json("GET", url)

    async def send_command(self, channel_id: str, content: str) -> dict:
        url = f"{self._base}/channels/{channel_id}/messages"
        return await self._request_json("POST", url, {"content": content})

    async def fetch_bytes(self, url: str) -> bytes:
        return await self._request_bytes(url)


def _parse_iso(value: str | None) -> datetime:
    if not value:
        return datetime.now(timezone.utc)
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return datetime.now(timezone.utc)