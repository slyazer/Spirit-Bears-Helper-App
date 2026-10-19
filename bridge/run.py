#!/usr/bin/env python
"""Run the Macro Monitor bridge.

Usage:
    python run.py                # from inside bridge/
    python bridge/run.py         # from project root

Binds to MM_HOST:MM_PORT (default 127.0.0.1:8787). When both MM_TLS_CERT_FILE and
MM_TLS_KEY_FILE are set the bridge serves HTTPS instead of HTTP; both must point
at readable files or startup fails with a clear error. Setting only one of the two
logs a warning and falls back to plain HTTP.
"""

from __future__ import annotations

import logging
import os

import uvicorn

from app.config import get_settings

logger = logging.getLogger("macro_monitor.bridge")


def _preflight_tls_file(label: str, path: str) -> None:
    """Raise a clear error when a configured TLS file is missing or unreadable.

    Called only when BOTH MM_TLS_CERT_FILE and MM_TLS_KEY_FILE are set, i.e. the
    operator explicitly asked for TLS. Aborting with an explanatory message is
    better than letting uvicorn fail later with an opaque traceback.
    """
    if not os.path.isfile(path):
        raise RuntimeError(
            f"{label} does not point at a readable file: {path!r}. Fix the path, "
            "or unset both MM_TLS_CERT_FILE and MM_TLS_KEY_FILE to serve plain HTTP."
        )
    try:
        with open(path, "rb"):
            pass
    except OSError as exc:
        raise RuntimeError(
            f"{label} exists but cannot be read ({exc}): {path!r}. Check the file "
            "permissions, or unset both TLS settings to serve plain HTTP."
        ) from exc


def _tls_kwargs(settings) -> dict:
    """Return uvicorn ssl options when a cert + key pair is configured.

    Both files are required: a lone cert or key is almost always a
    misconfiguration, so it is warned about and ignored (plain HTTP) rather than
    silently producing a half-broken listener. When both are set they must exist
    and be readable, otherwise startup fails with a clear error.
    """
    cert = settings.tls_cert_file
    key = settings.tls_key_file
    if cert and key:
        _preflight_tls_file("MM_TLS_CERT_FILE", cert)
        _preflight_tls_file("MM_TLS_KEY_FILE", key)
        return {"ssl_certfile": cert, "ssl_keyfile": key}
    if cert or key:
        logger.warning(
            "MM_TLS_CERT_FILE and MM_TLS_KEY_FILE must both be set for TLS; "
            "serving plain HTTP.",
        )
    return {}


def main() -> None:
    settings = get_settings()
    try:
        ssl_kwargs = _tls_kwargs(settings)
    except RuntimeError as exc:
        logger.error("TLS configuration error: %s", exc)
        raise SystemExit(2) from None
    scheme = "https" if ssl_kwargs else "http"
    logger.info(
        "Macro Monitor bridge serving %s on %s:%s",
        scheme,
        settings.host,
        settings.port,
    )
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_level="info",
        **ssl_kwargs,
    )


if __name__ == "__main__":
    main()
