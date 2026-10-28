"""FastAPI dependencies. Every authenticated route takes [current_user]; nothing else may supply a user id."""

import logging
import uuid
from typing import Annotated

from fastapi import Depends, Header, Request

from app import logging_setup
from app.auth.jwt_verifier import Identity, InvalidToken, JwtVerifier
from app.errors import ApiError

log = logging.getLogger("agenthub.auth")


def get_verifier(request: Request) -> JwtVerifier:
    verifier: JwtVerifier | None = getattr(request.app.state, "verifier", None)
    if verifier is None:
        # Misconfiguration must fail closed, never let a request through.
        raise ApiError("auth_unavailable", 503)
    return verifier


def bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise ApiError("unauthorized", 401)
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip() or " " in token.strip():
        raise ApiError("unauthorized", 401)
    return token.strip()


async def current_user(
    verifier: Annotated[JwtVerifier, Depends(get_verifier)],
    authorization: Annotated[str | None, Header()] = None,
) -> Identity:
    token = bearer_token(authorization)
    try:
        identity = await verifier.verify(token)
    except InvalidToken:
        raise ApiError("unauthorized", 401) from None
    # From here on every log line of this request carries the verified user id.
    logging_setup.user_id.set(str(identity.user_id))
    return identity


CurrentUser = Annotated[Identity, Depends(current_user)]


def user_id_of(identity: Identity) -> uuid.UUID:
    return identity.user_id
