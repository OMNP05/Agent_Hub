import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import auth as auth_routes
from app.api import health
from app.auth.jwt_verifier import JwtVerifier, http_jwks_fetcher
from app.auth.supabase_client import SupabaseAuth
from app.config import Settings, get_settings
from app.errors import install_error_handlers
from app.logging_setup import configure_logging
from app.middleware import RequestIdMiddleware
from app.ratelimit import RateLimiter

log = logging.getLogger("agenthub")


def create_app(settings: Settings | None = None, *, verifier: JwtVerifier | None = None) -> FastAPI:
    """App factory. Tests pass their own Settings and verifier; production reads the environment."""
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
    app.state.signin_limiter = RateLimiter(limit=10, window_seconds=60)

    if settings.supabase_url:
        base = settings.supabase_url.rstrip("/")
        app.state.verifier = verifier or JwtVerifier(
            issuer=f"{base}/auth/v1",
            fetch_jwks=http_jwks_fetcher(f"{base}/auth/v1/.well-known/jwks.json"),
        )
        app.state.supabase_auth = SupabaseAuth(base, settings.supabase_publishable_key)
    elif verifier is not None:
        app.state.verifier = verifier
    # With neither, authenticated routes answer 503 auth_unavailable instead of letting anyone in.

    app.add_middleware(RequestIdMiddleware)
    install_error_handlers(app)
    app.include_router(health.router)
    app.include_router(auth_routes.router)
    return app


app = create_app()
