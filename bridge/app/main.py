"""Macro Monitor bridge — FastAPI application factory."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import __version__
from .auth_routes import router as auth_router
from .config import get_settings
from .discovery import MdnsAdvertiser
from .pairing import local_ipv4_addresses
from .routers import api, health_router
from .state import get_session_store, get_user_store

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("macro_monitor")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    users = get_user_store()
    logger.info(
        "Macro Monitor bridge %s starting on %s:%s (%d accounts, %d users)",
        __version__,
        settings.host,
        settings.port,
        len(settings.load_accounts()),
        users.count(),
    )
    if users.count() == 0:
        logger.warning(
            "No users configured. Run the setup wizard or "
            "'python -m app.cli create-user <name>' before signing in."
        )

    advertiser: MdnsAdvertiser | None = None
    if settings.enable_mdns:
        advertiser = MdnsAdvertiser(
            port=settings.port,
            service_name=settings.service_name,
            version=__version__,
            addresses=local_ipv4_addresses(),
        )
        await advertiser.start()

    try:
        yield
    finally:
        if advertiser is not None:
            await advertiser.stop()


def create_app() -> FastAPI:
    app = FastAPI(
        title="Macro Monitor Bridge",
        version=__version__,
        description="Private bridge exposing Natro Macro data to the Macro Monitor app.",
        lifespan=lifespan,
    )
    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(api)
    return app


app = create_app()
