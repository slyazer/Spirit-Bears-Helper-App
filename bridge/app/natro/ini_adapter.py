"""Reads Natro Macro's ``settings/nm_config.ini``.

The file is a standard INI with case-sensitive keys (e.g. ``BoosterCheck``),
so we parse it manually rather than via configparser (which lowercases keys).
Only a read-only view is exposed; the bridge never writes to Natro's files.
"""

from __future__ import annotations

import configparser
from pathlib import Path


class NatroIni:
    """Read-only accessor for a Natro ``nm_config.ini``."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._parser = configparser.ConfigParser()
        # Preserve key case (Natro uses e.g. "SessionTotalHoney").
        self._parser.optionxform = str  # type: ignore[assignment]
        if path.exists():
            self._parser.read(path, encoding="utf-8")
        self._mtime = path.stat().st_mtime if path.exists() else None

    @property
    def exists(self) -> bool:
        return self._path.exists()

    @property
    def mtime(self) -> float | None:
        return self._mtime

    def get(self, section: str, key: str, default: str | None = None) -> str | None:
        if self._parser.has_option(section, key):
            value = self._parser.get(section, key)
            return value.strip() if value is not None else default
        return default

    def get_int(self, section: str, key: str, default: int | None = None) -> int | None:
        raw = self.get(section, key)
        if raw is None or raw == "":
            return default
        try:
            return int(float(raw))
        except ValueError:
            return default

    def get_float(self, section: str, key: str, default: float | None = None) -> float | None:
        raw = self.get(section, key)
        if raw is None or raw == "":
            return default
        try:
            return float(raw)
        except ValueError:
            return default

    def has_section(self, section: str) -> bool:
        return self._parser.has_section(section)
