#!/usr/bin/env python
"""Run the Macro Monitor bridge.

Usage:
    python -m app.run            # from inside bridge/
    python bridge/run.py         # from project root

Binds to MM_HOST:MM_PORT (default 127.0.0.1:8787).
"""

from __future__ import annotations

import uvicorn

from app.config import get_settings


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
