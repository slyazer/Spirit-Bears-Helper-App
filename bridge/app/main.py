"""Macro Monitor bridge — FastAPI application factory."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import __version__
from .config import get_settings
from .routers import api, health_router

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("macro_monitor")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    logger.info(
        "Macro Monitor bridge %s starting on %s:%s (%d accounts)",
        __version__,
        settings.host,
        settings.port,
        len(settings.load_accounts()),
    )
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="Macro Monitor Bridge",
        version=__version__,
        description="Private bridge exposing Natro Macro data to the Macro Monitor app.",
        lifespan=lifespan,
    )
    app.include_router(health_router)
    app.include_router(api)
    return app


app = create_app()
