"""Account registry: loads accounts and builds one NatroService per account."""

from __future__ import annotations

from functools import lru_cache

from .config import Settings, get_settings
from .natro.service import NatroService


class AccountRegistry:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._services: dict[str, NatroService] = {}
        self._load()

    def _load(self) -> None:
        accounts = self._settings.load_accounts()
        # Detect accounts that share (bot token, channel): commands to them are
        # ambiguous because Natro's receiver polls a shared channel.
        self._services = {}
        for acc in accounts:
            svc = NatroService(
                account=acc,
                discord_base=self._settings.discord_api_base,
                command_prefix=self._settings.command_prefix,
                stale_after_seconds=self._settings.stale_after_seconds,
                http_timeout=self._settings.http_timeout,
            )
            self._services[acc.id] = svc

    def all(self) -> list[NatroService]:
        return list(self._services.values())

    def get(self, account_id: str) -> NatroService | None:
        return self._services.get(account_id)


@lru_cache
def get_registry() -> AccountRegistry:
    return AccountRegistry(get_settings())
