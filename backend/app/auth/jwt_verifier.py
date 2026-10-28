"""Verifies Supabase access tokens locally against the project's public signing keys.

CLAUDE.md rule 7: user identity comes from the verified JWT only. This module is the only place a token
is turned into a user id. Rules:
  * only asymmetric algorithms are accepted (ES256 / RS256). A token that claims HS256 or "none" is refused,
    which closes the classic "sign it with the public key as an HMAC secret" attack;
  * signature, expiry, issuer and audience are all required and checked;
  * the signing key is picked by `kid` from the project's JWKS, cached, and re-fetched at most once per
    cooldown when an unknown `kid` appears (key rotation) so a flood of forged tokens cannot hammer Supabase.
"""

import logging
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import httpx
import jwt
from jwt import PyJWK

log = logging.getLogger("agenthub.auth")

ALLOWED_ALGORITHMS = ("ES256", "RS256")
AUDIENCE = "authenticated"


class InvalidToken(Exception):
    """The token is missing, malformed, expired, forged or for another project. No detail, on purpose."""


class KeyFetchError(Exception):
    """The signing keys could not be fetched; the caller should fail closed (401), never accept the token."""


@dataclass(frozen=True)
class Identity:
    user_id: uuid.UUID
    role: str
    email: str | None = None


JwksFetcher = Callable[[], Awaitable[dict]]


def http_jwks_fetcher(
    jwks_url: str, client_factory: Callable[[], httpx.AsyncClient] | None = None
) -> JwksFetcher:
    async def fetch() -> dict:
        factory = client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(5.0)))
        async with factory() as client:
            try:
                response = await client.get(jwks_url)
                response.raise_for_status()
                data = response.json()
            except (httpx.HTTPError, ValueError) as e:
                raise KeyFetchError(type(e).__name__) from e
        if not isinstance(data, dict) or not isinstance(data.get("keys"), list):
            raise KeyFetchError("malformed jwks")
        return data

    return fetch


class JwtVerifier:
    def __init__(
        self,
        *,
        issuer: str,
        fetch_jwks: JwksFetcher,
        audience: str = AUDIENCE,
        leeway_seconds: int = 10,
        refresh_cooldown_seconds: float = 30.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._issuer = issuer
        self._fetch = fetch_jwks
        self._audience = audience
        self._leeway = leeway_seconds
        self._cooldown = refresh_cooldown_seconds
        self._clock = clock
        self._keys: dict[str, PyJWK] = {}
        self._last_fetch: float | None = None

    async def _refresh(self) -> None:
        data = await self._fetch()
        keys: dict[str, PyJWK] = {}
        for raw in data["keys"]:
            try:
                key = PyJWK.from_dict(raw)
            except Exception:  # noqa: S112 - one unusable key must not hide the others
                continue
            kid = raw.get("kid")
            if kid and raw.get("alg", key.algorithm_name) in ALLOWED_ALGORITHMS:
                keys[kid] = key
        self._keys = keys
        self._last_fetch = self._clock()

    async def _key_for(self, kid: str) -> PyJWK:
        if kid not in self._keys:
            recently = self._last_fetch is not None and self._clock() - self._last_fetch < self._cooldown
            if not recently:
                await self._refresh()
        key = self._keys.get(kid)
        if key is None:
            raise InvalidToken
        return key

    async def verify(self, token: str) -> Identity:
        if not token or len(token) > 8192:
            raise InvalidToken
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as e:
            raise InvalidToken from e
        alg, kid = header.get("alg"), header.get("kid")
        if alg not in ALLOWED_ALGORITHMS or not isinstance(kid, str) or not kid:
            raise InvalidToken

        try:
            key = await self._key_for(kid)
        except KeyFetchError:
            log.warning("signing keys unavailable")
            raise InvalidToken from None

        try:
            claims = jwt.decode(
                token,
                key.key,
                algorithms=[alg],
                audience=self._audience,
                issuer=self._issuer,
                leeway=self._leeway,
                options={"require": ["exp", "sub", "aud", "iss"]},
            )
        except jwt.PyJWTError as e:
            raise InvalidToken from e

        try:
            user_id = uuid.UUID(str(claims["sub"]))
        except ValueError as e:
            raise InvalidToken from e
        # A token for a signed-in user has role "authenticated"; anonymous and service tokens are not users.
        role = claims.get("role")
        if role != "authenticated" or claims.get("is_anonymous") is True:
            raise InvalidToken
        email = claims.get("email")
        return Identity(user_id=user_id, role=role, email=email if isinstance(email, str) else None)
