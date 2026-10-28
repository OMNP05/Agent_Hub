import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import health
from app.config import Settings, get_settings
from app.errors import install_error_handlers
from app.logging_setup import configure_logging
from app.middleware import RequestIdMiddleware

log = logging.getLogger("agenthub")


def create_app(settings: Settings | None = None) -> FastAPI:
    """App factory. Tests pass their own Settings; production reads the environment."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        log.info("starting", extra={"environment": settings.environment})
        yield
        log.info("stopping")

    # No interactive docs in production: they would advertise the API surface to anyone.
    docs = None if settings.environment == "production" else "/docs"
    app = FastAPI(
        title="Agent Hub",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=docs,
        redoc_url=None,
        openapi_url=None if docs is None else "/openapi.json",
    )
    app.state.settings = settings
    app.add_middleware(RequestIdMiddleware)
    install_error_handlers(app)
    app.include_router(health.router)
    return app


app = create_app()
