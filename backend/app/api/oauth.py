"""Provider connection endpoints (docs/API_CONTRACT.md).

GET /oauth/{connector}/start?agent_id=   (Bearer)  -> {auth_url}
GET /oauth/{connector}/callback          (no login; the single-use `state` is the proof) -> 302 to /oauth/done
GET /oauth/done                          static page; the Android App Link normally opens the app instead
GET /.well-known/assetlinks.json         lets Android verify that this host belongs to the app
"""

import uuid
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel

from app.auth.dependencies import CurrentUser
from app.crypto import Envelope
from app.db.access import Database, get_db
from app.errors import ApiError
from app.oauth import service
from app.oauth.service import Providers
from app.ratelimit import RateLimiter

router = APIRouter(tags=["oauth"])

Connector = Literal["github", "gmail", "calendar"]

_NO_STORE = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"}


def get_providers(request: Request) -> Providers:
    return getattr(request.app.state, "providers", None) or Providers()


def get_envelope(request: Request) -> Envelope:
    env: Envelope | None = getattr(request.app.state, "envelope", None)
    if env is None:
        raise ApiError("service_unavailable", 503)
    return env


def _public_base(request: Request) -> str:
    return request.app.state.settings.public_base_url


class StartResponse(BaseModel):
    auth_url: str


@router.get("/oauth/{connector}/start", response_model=StartResponse)
async def start(
    connector: Connector,
    user: CurrentUser,
    request: Request,
    db: Annotated[Database, Depends(get_db)],
    env: Annotated[Envelope, Depends(get_envelope)],
    providers: Annotated[Providers, Depends(get_providers)],
    agent_id: Annotated[uuid.UUID, Query()],
) -> StartResponse:
    request.app.state.oauth_limiter.check(f"oauth-start:{user.user_id}")
    url = await service.start(
        db,
        env,
        providers,
        user_id=user.user_id,
        agent_id=agent_id,
        connector=connector,
        public_base_url=_public_base(request),
    )
    return StartResponse(auth_url=url)


@router.get("/oauth/{connector}/callback", include_in_schema=False)
async def callback(
    connector: Connector,
    request: Request,
    db: Annotated[Database, Depends(get_db)],
    env: Annotated[Envelope, Depends(get_envelope)],
    providers: Annotated[Providers, Depends(get_providers)],
    # No length limits here on purpose: an over-long value must reach the service, which rejects it, so the
    # user still lands on the normal 'return to the app' page instead of a raw JSON error.
    code: Annotated[str | None, Query()] = None,
    state: Annotated[str | None, Query()] = None,
    error: Annotated[str | None, Query()] = None,
) -> RedirectResponse:
    limiter: RateLimiter = request.app.state.oauth_callback_limiter
    limiter.check("oauth-callback:" + (request.client.host if request.client else "unknown"))
    result = await service.complete(
        db,
        env,
        providers,
        connector=connector,
        code=code,
        state=state,
        provider_error=error,
        public_base_url=_public_base(request),
    )
    # Whatever happened, the browser goes to the same page with only ok/error and the agent id. No provider
    # text, code or state is ever echoed back.
    target = service.done_url(_public_base(request), ok=result.ok, agent_id=result.agent_id)
    return RedirectResponse(target, status_code=302, headers=_NO_STORE)


_DONE_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agent Hub</title>
<style>body{font-family:system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;
background:#101114;color:#f2f2f2;text-align:center;padding:24px}main{max-width:26rem}</style></head>
<body><main><h1>You can return to Agent Hub</h1>
<p>Close this tab and go back to the app to see the result.</p></main></body></html>"""


@router.get("/oauth/done", include_in_schema=False)
async def done() -> HTMLResponse:
    # Static on purpose: nothing from the query string is placed in the page.
    return HTMLResponse(_DONE_PAGE, headers={**_NO_STORE, "X-Content-Type-Options": "nosniff"})


@router.get("/.well-known/assetlinks.json", include_in_schema=False)
async def assetlinks(request: Request) -> JSONResponse:
    settings = request.app.state.settings
    fingerprints = [f.strip().upper() for f in settings.android_cert_sha256.split(",") if f.strip()]
    if not fingerprints:
        raise ApiError("not_found", 404)  # publish nothing rather than a file that verifies nobody
    return JSONResponse(
        [
            {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                    "namespace": "android_app",
                    "package_name": settings.android_package,
                    "sha256_cert_fingerprints": fingerprints,
                },
            }
        ],
        headers={"Cache-Control": "public, max-age=3600"},
    )
