import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import auth as auth_routes
from app.api import health
from app.api import llm_profiles as llm_profile_routes
from app.auth.jwt_verifier import JwtVerifier, http_jwks_fetcher
from app.auth.supabase_client import SupabaseAuth
from app.config import Settings, get_settings
from app.crypto import CryptoError, Envelope
from app.db.access import Database, PoolDatabase
from app.errors import install_error_handlers
from app.logging_setup import configure_logging
from app.middleware import RequestIdMiddleware
from app.ratelimit import RateLimiter

log = logging.getLogger("agenthub")


def create_app(
    settings: Settings | None = None,
    *,
    verifier: JwtVerifier | None = None,
    db: Database | None = None,
    envelope: Envelope | None = None,
) -> FastAPI:
    """App factory. Tests pass their own Settings and verifier; production reads the environment."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        log.info("starting", extra={"environment": settings.environment})
        if isinstance(app.state.db, PoolDatabase):
            app.state.db.open()
        yield
        if isinstance(app.state.db, PoolDatabase):
            app.state.db.close()
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
    # Each key check makes an outbound call to a provider, so it is limited per user.
    app.state.llm_test_limiter = RateLimiter(limit=10, window_seconds=60)

    database_url = settings.database_url.get_secret_value()
    app.state.db = db or (PoolDatabase(database_url) if database_url else None)
    app.state.envelope = envelope
    if envelope is None and settings.master_encryption_key.get_secret_value():
        try:
            app.state.envelope = Envelope.from_settings(
                settings.master_encryption_key.get_secret_value(),
                settings.key_version,
                settings.retired_master_keys.get_secret_value(),
            )
        except CryptoError:
            log.error("encryption key settings are invalid")  # routes that need it answer 503

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
    app.include_router(llm_profile_routes.router)
    return app


app = create_app()
