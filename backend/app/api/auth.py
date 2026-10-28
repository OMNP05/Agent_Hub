"""Sign-in, refresh and sign-out (docs/API_CONTRACT.md).

POST /auth/google   {id_token, nonce}      -> {access_token, refresh_token, expires_in}
POST /auth/refresh  {refresh_token}        -> same
POST /auth/logout   Authorization: Bearer  -> 204
GET  /me            Authorization: Bearer  -> {id}

The Google ID token is verified by Supabase Auth (signature, issuer, audience, expiry and nonce); the backend
never trusts it directly. Our own access tokens are verified locally in app.auth.jwt_verifier.
"""

from typing import Annotated

from fastapi import APIRouter, Depends, Header, Request, Response
from pydantic import BaseModel, ConfigDict, Field

from app.auth.dependencies import CurrentUser, bearer_token
from app.auth.supabase_client import SessionTokens, SupabaseAuth
from app.errors import ApiError
from app.ratelimit import RateLimiter

router = APIRouter(tags=["auth"])

# Token sizes are bounded so a huge body cannot be used to burn memory or log space.
_MAX_TOKEN = 8192


class _Strict(BaseModel):
    # Unknown fields are refused: a client cannot smuggle in a user_id or any other field.
    model_config = ConfigDict(extra="forbid")


class GoogleSignInBody(_Strict):
    id_token: str = Field(min_length=20, max_length=_MAX_TOKEN)
    nonce: str = Field(min_length=8, max_length=256)


class RefreshBody(_Strict):
    refresh_token: str = Field(min_length=8, max_length=_MAX_TOKEN)


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    expires_in: int | None = None


def get_supabase_auth(request: Request) -> SupabaseAuth:
    auth: SupabaseAuth | None = getattr(request.app.state, "supabase_auth", None)
    if auth is None:
        raise ApiError("auth_unavailable", 503)
    return auth


def get_limiter(request: Request) -> RateLimiter:
    return request.app.state.signin_limiter


def _client_key(request: Request) -> str:
    # Behind Cloud Run the real client address is the first X-Forwarded-For entry (uvicorn --proxy-headers
    # sets request.client from it).
    return request.client.host if request.client else "unknown"


def _response(tokens: SessionTokens) -> TokenResponse:
    return TokenResponse(
        access_token=tokens.access_token, refresh_token=tokens.refresh_token, expires_in=tokens.expires_in
    )


@router.post("/auth/google", response_model=TokenResponse)
async def sign_in_with_google(
    body: GoogleSignInBody,
    request: Request,
    auth: Annotated[SupabaseAuth, Depends(get_supabase_auth)],
    limiter: Annotated[RateLimiter, Depends(get_limiter)],
) -> TokenResponse:
    limiter.check("signin:" + _client_key(request))
    return _response(await auth.sign_in_with_google(body.id_token, body.nonce))


@router.post("/auth/refresh", response_model=TokenResponse)
async def refresh(
    body: RefreshBody,
    request: Request,
    auth: Annotated[SupabaseAuth, Depends(get_supabase_auth)],
    limiter: Annotated[RateLimiter, Depends(get_limiter)],
) -> TokenResponse:
    limiter.check("refresh:" + _client_key(request))
    return _response(await auth.refresh(body.refresh_token))


@router.post("/auth/logout", status_code=204)
async def logout(
    _: CurrentUser,
    auth: Annotated[SupabaseAuth, Depends(get_supabase_auth)],
    authorization: Annotated[str | None, Header()] = None,
) -> Response:
    """Revokes every session of the signed-in user. The client clears its own copy either way."""
    await auth.sign_out(bearer_token(authorization))
    return Response(status_code=204)


class MeResponse(BaseModel):
    id: str


@router.get("/me", response_model=MeResponse)
async def me(user: CurrentUser) -> MeResponse:
    """Who the server believes you are, taken from the verified token only."""
    return MeResponse(id=str(user.user_id))
