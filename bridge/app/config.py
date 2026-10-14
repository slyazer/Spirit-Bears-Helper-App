"""Configuration: environment settings + account registry.

Secrets (API token, bot tokens) are never logged or returned by the API.
Account definitions live in a separate JSON file that is git-ignored.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class AccountConfig(BaseModel):
    """One Natro Macro instance on the host PC.

    `natro_dir` is the folder that contains `settings/nm_config.ini` and
    `settings/debug_log.txt` (i.e. the "THIS ONE" directory).
    """

    id: str = Field(..., description="Stable id used in URLs, e.g. 'slyazer'")
    name: str = Field(..., description="Display name shown in the app")
    natro_dir: str = Field(..., description="Absolute path to a Natro Macro install")
    hive_level: int | None = Field(default=None, description="Known hive level, if set")

    # Optional overrides; normally discovered from nm_config.ini [Status].
    discord_mode: int | None = Field(default=None, description="0=webhook, 1=bot")
    main_channel_id: str | None = None
    report_channel_id: str | None = None

    # Flagged when two accounts share (bot token, channel) and thus cannot be
    # addressed individually through the Discord command receiver.
    command_ambiguous: bool = False
    notes: str | None = None


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_prefix="MM_", extra="ignore", case_sensitive=False
    )

    host: str = "127.0.0.1"
    port: int = 8787
    api_token: str = "CHANGEME"
    accounts_file: str = "config/accounts.json"
    stale_after_seconds: int = 180
    discord_api_base: str = "https://discord.com/api/v10"
    rate_limit_per_minute: int = 120
    command_prefix: str = "?"

    # Request timeouts (seconds)
    http_timeout: float = 15.0
    # Whether to allow HTTP (no TLS) — expected True only over a private tailnet.
    allow_insecure_transport: bool = True

    def load_accounts(self) -> list[AccountConfig]:
        path = Path(self.accounts_file)
        if not path.is_absolute():
            path = Path(__file__).resolve().parent.parent / path
        if not path.exists():
            return []
        data = json.loads(path.read_text(encoding="utf-8"))
        raw = data["accounts"] if isinstance(data, dict) else data
        return [AccountConfig(**a) for a in raw]


@lru_cache
def get_settings() -> Settings:
    return Settings()
