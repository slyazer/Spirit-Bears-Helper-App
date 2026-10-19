"""Shared, process-wide service singletons.

Kept separate from the routers so tests can override individual dependencies
via FastAPI's ``dependency_overrides``.
"""

from __future__ import annotations

from functools import lru_cache

from .config import get_settings, resolve_path
from .ratelimit import LoginLimiter
from .roblox_population import PopulationStore
from .sessions import SessionStore
from .users import UserStore


@lru_cache
def get_user_store() -> UserStore:
    return UserStore(resolve_path(get_settings().users_file))


@lru_cache
def get_session_store() -> SessionStore:
    settings = get_settings()
    return SessionStore(
        resolve_path(settings.sessions_file),
        ttl_seconds=settings.session_ttl_seconds,
        idle_seconds=settings.session_idle_seconds,
    )


@lru_cache
def get_login_limiter() -> LoginLimiter:
    settings = get_settings()
    return LoginLimiter(
        max_attempts=settings.login_max_attempts,
        window_seconds=settings.login_window_seconds,
        lockout_seconds=settings.login_lockout_seconds,
    )


@lru_cache
def get_population_store() -> PopulationStore:
    return PopulationStore(
        stale_after_seconds=get_settings().roblox_population_stale_seconds
    )


def reset_state() -> None:
    """Drop cached stores (used by tests and after config changes)."""
    get_user_store.cache_clear()
    get_session_store.cache_clear()
    get_login_limiter.cache_clear()
    get_population_store.cache_clear()
