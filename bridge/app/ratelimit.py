"""In-memory login rate limiting.

Two dimensions are tracked independently so neither a single misbehaving client
nor a password-spray against one username can grind the bridge:

* **per client IP** — stops one host from hammering login.
* **per username** — stops a distributed spray against a single account.

A lockout is applied once the failure count within the rolling window reaches
the configured maximum; a successful login clears the counters for both keys.
State is intentionally in-memory: it resets on restart, which is acceptable for
a single-owner LAN service and avoids writing attacker-controlled data to disk.
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque


class LoginLimiter:
    def __init__(
        self,
        max_attempts: int = 8,
        window_seconds: int = 300,
        lockout_seconds: int = 300,
    ) -> None:
        self._max_attempts = max(1, max_attempts)
        self._window = max(1, window_seconds)
        self._lockout = max(1, lockout_seconds)
        self._failures: dict[str, deque[float]] = defaultdict(deque)
        self._locked_until: dict[str, float] = {}
        self._lock = threading.Lock()

    def locked_for(self, key: str) -> float:
        """Seconds remaining before ``key`` may try again (0 when not locked)."""
        now = time.monotonic()
        with self._lock:
            until = self._locked_until.get(key)
            if until is None:
                return 0.0
            remaining = until - now
            if remaining <= 0:
                self._locked_until.pop(key, None)
                return 0.0
            return remaining

    def record_failure(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            bucket = self._failures[key]
            bucket.append(now)
            cutoff = now - self._window
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= self._max_attempts:
                self._locked_until[key] = now + self._lockout
                bucket.clear()

    def reset(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
            self._locked_until.pop(key, None)
