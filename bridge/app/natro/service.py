"""NatroService — orchestrates the file/Discord adapters into normalized data.

One instance per account. Reads are cached briefly to avoid hammering disk and
the Discord API. Command dispatch is strictly allowlisted.
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from ..config import AccountConfig
from ..models import (
    AccountSummary,
    CommandResponse,
    LogEntry,
    LogsResponse,
    PlanterInfo,
    PlantersResponse,
    ReportImage,
    ReportsResponse,
    StatusResponse,
)
from .discord_adapter import DiscordAPIError, DiscordClient
from .ini_adapter import NatroIni
from .log_adapter import read_live_status

logger = logging.getLogger("macro_monitor.service")

# Strict allowlist of commands the bridge will forward. These map 1:1 to cases
# Natro's Status.ahk command receiver already handles.
ALLOWED_COMMANDS: dict[str, str] = {
    "start": "Start the macro",
    "stop": "Stop the macro",
    "pause": "Pause the macro",
    "resume": "Resume the macro",
    "close": "Close a window by title (Natro 'close' command)",
    "planters": "Show planter timers",
    "timers": "Show timer report",
    "status": "Show macro status",
    "help": "Show command help",
}

_CACHE_TTL = 5.0  # seconds
_START_HONEY_TTL = 900.0  # re-read the startup-report balance at most every 15 min
_BOT_ID_TTL = 3600.0  # bot identity rarely changes


# Human labels for the macro's current action (from the newest log line).
_ACTIVITY_LABELS: dict[str, str] = {
    "Begin": "Starting",
    "Startup": "Starting",
    "Gathering": "Gathering",
    "Traveling": "Traveling",
    "Resetting": "Resetting",
    "Converting": "Converting",
    "Placing": "Planting",
    "Collecting": "Collecting",
    "Holding": "Collecting",
    "Attacking": "Fighting",
    "Looting": "Looting",
    "Defeated": "Fighting",
    "Found": "Fighting",
    "Boosted": "Boosting",
    "Claimed": "Claiming",
    "Completed": "Completing",
    "Obtained": "Collecting",
    "Interrupted": "Interrupted",
    "Reporting": "Reporting",
    "Confirmed": "Confirming",
    "Keeping": "Keeping",
    "Detected": "Detected",
    "Attempting": "Attempting",
    "Paused": "Paused",
}

# States where Natro legitimately goes quiet in the log for a while (e.g.
# waiting for the balloon to refresh while converting at the hive). Give these a
# longer "expected update" window so a healthy macro isn't flagged as stale.
_BUSY_GRACE_SECONDS: dict[str, int] = {
    "Converting": 420,
    "Traveling": 240,
    "Resetting": 240,
    "Placing": 300,
    "Collecting": 300,
    "Holding": 300,
    "Attacking": 300,
    "Looting": 240,
}

# Detail strings that carry no useful information for the activity tile.
_UNINFORMATIVE_DETAILS = {"macro", "character 1", "character 2", "character 3"}


def _activity_label(state: str | None, detail: str | None) -> tuple[str | None, str | None]:
    """Map the newest log state to a short human activity label.

    Returns ``(label, detail)``. Both are ``None`` when the macro is idle/stopped
    or the newest line is not an activity (e.g. ``End: Macro``).
    """
    if not state:
        return None, None
    if state in {"End", "GUI", "Closing"}:
        return None, None
    text = (detail or "").strip()
    if state == "Gathering" and text.lower().startswith("ended"):
        return None, None
    label = _ACTIVITY_LABELS.get(state, state)
    if text.lower() in _UNINFORMATIVE_DETAILS:
        text = ""
    return label, (text or None)


def _now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class _Cache:
    value: StatusResponse
    at: float


class NatroService:
    def __init__(
        self,
        account: AccountConfig,
        discord_base: str,
        command_prefix: str = "?",
        stale_after_seconds: int = 180,
        http_timeout: float = 15.0,
    ) -> None:
        self.account = account
        self._dir = Path(account.natro_dir)
        self._settings_dir = self._dir / "settings"
        self._ini_path = self._settings_dir / "nm_config.ini"
        self._log_path = self._settings_dir / "debug_log.txt"
        self._discord_base = discord_base
        self._prefix = command_prefix
        self._stale_after = stale_after_seconds
        self._timeout = http_timeout
        self._status_cache: _Cache | None = None
        self._start_honey: tuple[float, float | None] | None = None
        self._bot_id: tuple[float, str | None] | None = None

    # -- config helpers -------------------------------------------------
    def _ini(self) -> NatroIni:
        return NatroIni(self._ini_path)

    def _bot_token(self, ini: NatroIni) -> str | None:
        token = ini.get("Status", "BotToken")
        if not token:
            return None
        token = token.strip()
        if token.lower() in {"", "xxxxx", "token", "none", "false", "0"}:
            return None
        # Reject placeholder tokens with control characters.
        if any(ord(c) < 32 for c in token):
            return None
        # A real Discord bot token has at least two dots (id.timestamp.hmac).
        if token.count(".") < 2:
            return None
        return token

    def _command_channel(self, ini: NatroIni) -> str | None:
        # Commands are read from MainChannelID by Natro's GetCommands poller.
        return (
            self.account.main_channel_id
            or ini.get("Status", "MainChannelID")
            or None
        )

    def _report_channel(self, ini: NatroIni) -> str | None:
        return (
            self.account.report_channel_id
            or ini.get("Status", "ReportChannelID")
            or ini.get("Status", "MainChannelID")
            or None
        )

    def _discord(self, ini: NatroIni) -> DiscordClient | None:
        token = self._bot_token(ini)
        if not token:
            return None
        return DiscordClient(self._discord_base, token, self._timeout)

    # -- summaries ------------------------------------------------------
    def summary(self) -> AccountSummary:
        ini = self._ini()
        hive = self.account.hive_level
        if hive is None:
            hive = ini.get_int("Status", "HiveLevel")
        return AccountSummary(
            id=self.account.id,
            name=self.account.name,
            hive_level=hive,
            command_ambiguous=self.account.command_ambiguous,
        )

    # -- status ---------------------------------------------------------
    def status(self, force: bool = False) -> StatusResponse:
        now = time.time()
        if not force and self._status_cache and now - self._status_cache.at < _CACHE_TTL:
            return self._status_cache.value

        ini = self._ini()
        live = read_live_status(self._log_path)

        honey_session = ini.get("Status", "SessionTotalHoney")
        honey_rate = ini.get("Status", "HoneyAverage")
        # Patched Natro builds persist the true in-game balance (OCR) every
        # minute as [Status] CurrentHoney. Prefer it; fall back to the session
        # gain only when the build has not been patched.
        current_honey = ini.get("Status", "CurrentHoney")
        # Session-scoped numbers only: never present stale all-time accumulators
        # (e.g. TotalDisconnects, TotalRuntime) as if they were current.
        runtime = ini.get_int("Status", "SessionRuntime")
        disconnects = ini.get_int("Status", "SessionDisconnects")
        planters = ini.get_int("Status", "SessionPlantersCollected")

        field = live.field

        activity, activity_detail = _activity_label(live.last_state, live.last_detail)

        last_updated = None
        if live.last_update_seen_at:
            last_updated = datetime.fromtimestamp(live.last_update_seen_at, tz=timezone.utc)

        # Natro can be silent in the log for the whole duration of a field
        # gather (only the start and end lines are written). When we know the
        # current field's time cap, treat the scheduled gather end as the last
        # "expected" update so a long, healthy gather is not flagged stale.
        # Conversion / travel / plant-collection waits can also be quiet for a
        # few minutes, so those states get a wider grace window.
        stale = False
        if live.last_update_seen_at:
            deadline = live.last_update_seen_at
            grace = self._stale_after
            if live.macro_state == "running":
                if (
                    live.last_state == "Gathering"
                    and live.field_since is not None
                    and live.field_limit_seconds
                ):
                    deadline = max(deadline, live.field_since + live.field_limit_seconds)
                grace = max(grace, _BUSY_GRACE_SECONDS.get(live.last_state or "", 0))
            stale = (now - deadline) > grace

        # Only claim a state we can prove. Once the log stops updating we no
        # longer know what the macro is doing, so drop the field/activity too
        # rather than showing a stale card next to the stale banner.
        macro_state = live.macro_state
        if stale:
            macro_state = "unknown"
            field = None
            activity = None
            activity_detail = None

        # Boost is derived from the log ("Boosted: <field>" / "Gathering ... - Boosted"),
        # never from a stale ini flag. None = we have no evidence either way.
        boosted: bool | None = None
        if live.boosted_field and field:
            boosted = live.boosted_field.lower() == field.lower()

        field_since = (
            datetime.fromtimestamp(live.field_since, tz=timezone.utc)
            if live.field_since
            else None
        )

        result = StatusResponse(
            id=self.account.id,
            name=self.account.name,
            hive_level=ini.get_int("Status", "HiveLevel"),
            macro_state=macro_state,  # type: ignore[arg-type]
            field=field,
            field_boosted=boosted,
            field_since=field_since,
            field_limit_seconds=live.field_limit_seconds if field else None,
            activity=activity,
            activity_detail=activity_detail,
            # ``CurrentHoney`` (patched builds) is the real balance. When it is
            # absent the sync reader falls back to the session value; the async
            # path then upgrades it via the Startup Report below.
            honey_total=current_honey or honey_session,
            honey_session=honey_session,
            honey_per_hour=honey_rate,
            runtime_seconds=runtime,
            disconnected_count=disconnects,
            planters_collected=planters,
            last_updated=last_updated,
            stale=stale,
            source="natro-files",
        )
        self._status_cache = _Cache(result, now)
        return result

    async def status_async(self, force: bool = False) -> StatusResponse:
        """Status plus the account's *real* current honey balance.

        Preferred source: ``[Status] CurrentHoney``, written directly by the
        patched Natro ``StatMonitor.ahk`` from the in-game OCR every minute.

        Fallback (unpatched builds): Natro only persists the session gain
        (``SessionTotalHoney``) and OCRs the balance once at startup into the
        Startup Report embed. Natro's invariant is
        ``session_gain = current_honey - start_honey``, so we reconstruct
        ``current_honey = start_honey + session_gain``.
        """
        base = self.status(force=force)
        ini = self._ini()

        if ini.get("Status", "CurrentHoney"):
            return base.model_copy(
                update={"notes": "Current balance read directly from Natro (in-game OCR)."}
            )

        channel = self._report_channel(ini)
        client = self._discord(ini)
        if not client or not channel:
            return base

        try:
            start = await self._start_honey_value(client, channel)
        except Exception as exc:  # noqa: BLE001 - never fail status on enrichment
            logger.warning("start-honey lookup failed for %s: %s", self.account.id, exc)
            start = None

        session = _parse_honey(base.honey_session)
        if start is not None and session is not None:
            return base.model_copy(
                update={
                    "honey_total": _format_honey(start + session),
                    "notes": (
                        "Current balance = startup-report OCR "
                        f"{_format_honey(start)} + session {_format_honey(session)}."
                    ),
                }
            )
        if start is not None:
            return base.model_copy(
                update={
                    "honey_total": _format_honey(start),
                    "notes": "Current balance from startup report; session gain unknown.",
                }
            )
        return base.model_copy(
            update={
                "honey_total": None,
                "notes": (
                    "Current honey unavailable: no readable Startup Report "
                    "(OCR) found yet. Showing session gain only."
                ),
            }
        )

    async def _start_honey_value(self, client: DiscordClient, channel: str) -> float | None:
        if self._start_honey and (time.time() - self._start_honey[0]) < _START_HONEY_TTL:
            return self._start_honey[1]
        # The Startup Report carrying the balance can be far back if the macro
        # has run for a while, so page backwards until we find one. The channel
        # is shared by two bots, so restrict to this account's own report.
        bot_id = await self._bot_user_id(client)
        value: float | None = None
        before: str | None = None
        for _ in range(5):  # up to ~500 messages
            messages = await client.fetch_messages(channel, limit=100, before=before)
            if not messages:
                break
            value = _select_start_honey(
                messages, bot_id=bot_id, author_name=self.account.name
            )
            if value is not None:
                break
            before = messages[-1].id
        self._start_honey = (time.time(), value)
        return value

    async def _bot_user_id(self, client: DiscordClient) -> str | None:
        if self._bot_id and (time.time() - self._bot_id[0]) < _BOT_ID_TTL:
            return self._bot_id[1]
        try:
            me = await client.me()
            value = str(me.get("id") or "") or None
        except Exception as exc:  # noqa: BLE001 - fall back to author-name match
            logger.warning("bot identity lookup failed for %s: %s", self.account.id, exc)
            value = None
        self._bot_id = (time.time(), value)
        return value

    # -- reports (Discord attachments) ---------------------------------
    async def reports(self, limit: int = 10) -> ReportsResponse:
        ini = self._ini()
        channel = self._report_channel(ini)
        client = self._discord(ini)
        if not client or not channel:
            return ReportsResponse(
                id=self.account.id,
                reports=[],
                detail="No bot token / report channel configured for this account.",
            )
        try:
            # Over-fetch: the report channel is shared by both bots, so after
            # filtering out the other instance we may need extra history.
            messages = await client.fetch_messages(channel, limit=max(limit * 4, 50))
        except DiscordAPIError as exc:
            logger.warning("fetch reports failed for %s: %s", self.account.id, exc)
            return ReportsResponse(
                id=self.account.id,
                reports=[],
                detail=f"Discord API {exc.status}: {exc.body[:160]}",
            )
        except Exception as exc:  # noqa: BLE001 - surface as empty + logged
            logger.warning("fetch reports failed for %s: %s", self.account.id, exc)
            return ReportsResponse(
                id=self.account.id, reports=[], detail="Failed to reach Discord."
            )

        bot_id = await self._bot_user_id(client)
        selected = _filter_report_messages(
            messages, bot_id=bot_id, author_name=self.account.name, limit=limit
        )

        reports: list[ReportImage] = []
        for m, url in selected:
            reports.append(
                ReportImage(
                    report_id=m.id,
                    arrival_time=m.timestamp,
                    image_url=url,
                    image_proxy_url=f"/api/v1/accounts/{self.account.id}/reports/{m.id}/image"
                    if url
                    else None,
                    author=m.author,
                    summary=((_first_embed_title(m.embeds) or "").strip("*") or None),
                )
            )
        return ReportsResponse(
            id=self.account.id,
            reports=reports,
            detail=None
            if reports
            else "No hourly reports found for this account in the report channel.",
        )

    async def report_image(self, report_id: str) -> tuple[str, bytes] | None:
        """Fetch a report image's bytes for proxying to the app."""
        ini = self._ini()
        channel = self._report_channel(ini)
        client = self._discord(ini)
        if not client or not channel:
            return None
        messages = await client.fetch_messages(channel, limit=50)
        for m in messages:
            if m.id != report_id:
                continue
            image = _message_image(m)
            if not image:
                return None
            url, ctype = image
            try:
                data = await client.fetch_bytes(url)
            except Exception as exc:  # noqa: BLE001
                logger.warning("fetch report image failed for %s: %s", report_id, exc)
                return None
            return ctype or "image/png", data
        return None

    # -- logs (status embeds) ------------------------------------------
    async def logs(self, limit: int = 30) -> LogsResponse:
        ini = self._ini()
        channel = self._command_channel(ini)
        client = self._discord(ini)
        if not client or not channel:
            return LogsResponse(
                id=self.account.id,
                logs=[],
                detail="No bot token / command channel configured for this account.",
            )
        try:
            # Over-fetch: Natro also posts recurring "Current Honey/Pollen"
            # snapshots that we filter out below, so ask for extra history to
            # still return ``limit`` real macro actions.
            messages = await client.fetch_messages(channel, limit=max(limit * 3, limit + 20))
        except DiscordAPIError as exc:
            logger.warning("fetch logs failed for %s: %s", self.account.id, exc)
            return LogsResponse(
                id=self.account.id,
                logs=[],
                detail=f"Discord API {exc.status}: {exc.body[:160]}",
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("fetch logs failed for %s: %s", self.account.id, exc)
            return LogsResponse(id=self.account.id, logs=[], detail="Failed to reach Discord.")

        logs: list[LogEntry] = []
        for m in messages:
            fallback_image = _message_image(m)
            fallback_url = fallback_image[0] if fallback_image else None
            if m.embeds:
                for i, e in enumerate(m.embeds):
                    title = e.get("title") or (e.get("author") or {}).get("name") or ""
                    body = e.get("description")
                    if _is_noise_log(title, body):
                        continue
                    logs.append(
                        LogEntry(
                            entry_id=f"{m.id}:{i}",
                            timestamp=m.timestamp,
                            title=title,
                            body=body,
                            color=_embed_color(e),
                            author=m.author,
                            image_url=(e.get("image") or {}).get("url") or fallback_url,
                        )
                    )
            elif m.content:
                if _is_noise_log(m.content, m.content):
                    continue
                logs.append(
                    LogEntry(
                        entry_id=m.id,
                        timestamp=m.timestamp,
                        title=m.content[:120],
                        body=m.content,
                        author=m.author,
                        image_url=fallback_url,
                    )
                )
        logs.sort(key=lambda x: x.timestamp or _now(), reverse=True)
        return LogsResponse(id=self.account.id, logs=logs[:limit])

    # -- planters -------------------------------------------------------
    def planters(self) -> PlantersResponse:
        ini = self._ini()
        if not ini.exists:
            return PlantersResponse(id=self.account.id, available=False, reason="config not found")
        planters: list[PlanterInfo] = []
        for i in (1, 2, 3):
            name = ini.get("Planters", f"PlanterName{i}")
            if not name:
                continue
            field = ini.get("Planters", f"PlanterField{i}")
            harvest = ini.get("Planters", f"PlanterHarvestTime{i}")
            pct = ini.get_float("Planters", f"PlanterEstPercent{i}")
            planters.append(
                PlanterInfo(
                    slot=i,
                    name=name,
                    field=field,
                    harvest_eta=_parse_dt(harvest),
                    est_percent=pct,
                )
            )
        if not planters:
            return PlantersResponse(
                id=self.account.id,
                available=False,
                reason="no active planters reported",
            )
        return PlantersResponse(id=self.account.id, available=True, planters=planters)

    # -- commands -------------------------------------------------------
    async def send_command(self, command: str, correlation_id: str | None) -> CommandResponse:
        raw = command.strip()
        if raw.startswith(self._prefix):
            raw = raw[len(self._prefix):]
        key = raw.lower()

        if key not in ALLOWED_COMMANDS:
            return CommandResponse(
                id=self.account.id,
                command=command,
                result="rejected",
                correlation_id=correlation_id,
                detail=f"command not in allowlist: {sorted(ALLOWED_COMMANDS)}",
            )

        if self.account.command_ambiguous:
            return CommandResponse(
                id=self.account.id,
                command=command,
                result="rejected",
                correlation_id=correlation_id,
                detail=(
                    "This account shares its bot token and channel with another "
                    "account, so a command could be consumed by either instance. "
                    "Refusing to avoid ambiguity."
                ),
            )

        ini = self._ini()
        channel = self._command_channel(ini)
        client = self._discord(ini)
        if not client or not channel:
            return CommandResponse(
                id=self.account.id,
                command=command,
                result="failed",
                correlation_id=correlation_id,
                detail="No bot token / command channel configured for this account.",
            )

        try:
            await client.send_command(channel, f"{self._prefix}{key}")
        except DiscordAPIError as exc:
            return CommandResponse(
                id=self.account.id,
                command=command,
                result="failed",
                correlation_id=correlation_id,
                detail=f"Discord API {exc.status}: {exc.body[:160]}",
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("send_command failed for %s: %s", self.account.id, exc)
            return CommandResponse(
                id=self.account.id,
                command=command,
                result="failed",
                correlation_id=correlation_id,
                detail="Failed to reach Discord.",
            )

        return CommandResponse(
            id=self.account.id,
            command=command,
            result="accepted",
            correlation_id=correlation_id,
            detail="Command posted to the macro's command channel.",
        )


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    # Natro stores most planter times as Unix epoch seconds.
    if value.strip().isdigit():
        try:
            return datetime.fromtimestamp(int(value), tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%m/%d/%Y %H:%M:%S", "%Y/%m/%d %H:%M:%S"):
        try:
            return datetime.strptime(value, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def _message_image(message) -> tuple[str, str | None] | None:
    """Locate a report image: message attachments first, then embed images."""
    for att in message.attachments:
        url = att.get("url")
        if url:
            return url, att.get("content_type")
    for e in message.embeds:
        image = e.get("image") or {}
        url = image.get("url")
        if url:
            return url, image.get("content_type")
    return None


def _first_embed_title(embeds: list[dict]) -> str | None:
    for e in embeds:
        if e.get("title"):
            return e["title"]
    return None


# Natro posts a recurring balance snapshot to the command channel; it is not a
# macro action, so it is dropped from the app's activity feed.
_NOISE_LOG_PHRASES = ("current honey/pollen",)


def _is_noise_log(title: str, body: str | None) -> bool:
    text = f"{title}\n{body or ''}".lower()
    return any(phrase in text for phrase in _NOISE_LOG_PHRASES)


def _embed_color(embed: dict) -> str | None:
    color = embed.get("color")
    if color is None:
        return None
    try:
        return f"#{int(color):06X}"
    except (TypeError, ValueError):
        return None


# -- honey parsing / report filtering (pure helpers, unit-tested) --------

_HONEY_SUFFIXES = ["", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"]
_HONEY_MULT = {s.upper(): 1000 ** i for i, s in enumerate(_HONEY_SUFFIXES)}
_HONEY_RE = re.compile(r"^([0-9][0-9.,]*)\s*([A-Za-z]*)$")
_START_HONEY_RE = re.compile(r"Current Honey:[\s*]*([0-9][0-9.,]*\s*[A-Za-z]*)")


def _parse_honey(value: str | None) -> float | None:
    """Parse Natro's formatted honey (e.g. "793.8 B", "467.1 M") to a float."""
    if not value:
        return None
    text = value.strip().replace(",", "")
    if text.lower() in {"n/a", "na", "none", "-", ""}:
        return None
    match = _HONEY_RE.match(text)
    if not match:
        return None
    try:
        number = float(match.group(1))
    except ValueError:
        return None
    mult = _HONEY_MULT.get(match.group(2).upper())
    if mult is None:
        return None
    return number * mult


def _format_honey(value: float | None) -> str | None:
    """Format a honey float the way Natro does ("829.34 B")."""
    if value is None:
        return None
    negative = value < 0
    value = abs(value)
    idx = 0
    while value >= 1000.0 and idx < len(_HONEY_SUFFIXES) - 1:
        value /= 1000.0
        idx += 1
    if idx == 0:
        text = str(int(round(value)))
    else:
        text = f"{value:.2f}".rstrip("0").rstrip(".")
    return f"{'-' if negative else ''}{text} {_HONEY_SUFFIXES[idx]}".strip()


def _matches_author(message, bot_id: str | None, base_name: str) -> bool:
    """True when a message was posted by this account's own bot.

    Prefer the author id (from GET /users/@me); fall back to the username when
    the id isn't available (e.g. offline/tests). ``base_name`` is the account
    display name with any "(PC nn)" suffix removed.
    """
    author_id = getattr(message, "author_id", "") or ""
    author = (getattr(message, "author", "") or "").lower()
    if bot_id:
        if author_id:
            return author_id == str(bot_id)
        return not base_name or author == base_name
    return not base_name or author == base_name


def _select_start_honey(messages, bot_id=None, author_name="") -> float | None:
    """Return the newest startup-report "Current Honey" value, or None.

    Messages are newest-first from Discord, so the first readable Startup
    Report is the balance captured at the start of the current session.
    """
    base_name = (author_name or "").split(" (")[0].strip().lower()
    for m in messages:
        if bot_id or base_name:
            if not _matches_author(m, bot_id, base_name):
                continue
        for e in getattr(m, "embeds", []) or []:
            description = e.get("description") or ""
            match = _START_HONEY_RE.search(description)
            if match:
                parsed = _parse_honey(match.group(1))
                if parsed is not None:
                    return parsed
    return None


def _filter_report_messages(messages, *, bot_id, author_name, limit):
    """Keep only this account's own hourly reports, newest per hour.

    A report channel can be shared by multiple macro instances (two bots post
    the same hourly image), so we match the message author to this account's
    bot id (preferred) or username, then keep a single entry per clock hour.
    Returns a list of ``(message, image_url)`` tuples.
    """
    base_name = (author_name or "").split(" (")[0].strip().lower()
    seen_hours: set[tuple[int, int, int, int]] = set()
    out: list[tuple[object, str]] = []
    for m in messages:
        image = _message_image(m)
        if not image:
            continue
        if not _matches_author(m, bot_id, base_name):
            continue
        ts = getattr(m, "timestamp", None)
        if ts is None:
            continue
        key = (ts.year, ts.month, ts.day, ts.hour)
        if key in seen_hours:
            continue
        seen_hours.add(key)
        out.append((m, image[0]))
        if len(out) >= limit:
            break
    return out
