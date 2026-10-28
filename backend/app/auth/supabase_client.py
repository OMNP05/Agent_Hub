"""The few Supabase Auth calls the backend makes. Returns plain data and never raises raw HTTP errors.

Field names and paths were taken from supabase-js (auth-js) source:
  POST /auth/v1/token?grant_type=id_token       {provider, id_token, nonce}
  POST /auth/v1/token?grant_type=refresh_token  {refresh_token}
  POST /auth/v1/logout?scope=global             Authorization: Bearer <access token>
"""

import logging
from dataclasses import dataclass

import httpx

from app.errors import ApiError

log = logging.getLogger("agenthub.supabase")


@dataclass(frozen=True)
class SessionTokens:
    access_token: str
    refresh_token: str
    expires_in: int | None

    def __repr__(self) -> str:  # never let a token reach a log through repr
        return "SessionTokens(***)"


class SupabaseAuth:
    def __init__(self, base_url: str, publishable_key: str, client: httpx.AsyncClient | None = None) -> None:
        self._base = base_url.rstrip("/") + "/auth/v1"
        self._key = publishable_key
        self._client = client
        self._timeout = httpx.Timeout(10.0)

    def _headers(self, bearer: str | None = None) -> dict[str, str]:
        headers = {"apikey": self._key, "Content-Type": "application/json"}
        if bearer:
            headers["Authorization"] = f"Bearer {bearer}"
        return headers

    async def _post(self, path: str, json: dict | None, bearer: str | None = None) -> httpx.Response:
        client = self._client or httpx.AsyncClient(timeout=self._timeout)
        try:
            return await client.post(self._base + path, json=json, headers=self._headers(bearer))
        except httpx.HTTPError as e:
            log.warning("supabase auth unreachable", extra={"error_type": type(e).__name__})
            raise ApiError("auth_unavailable", 503) from None
        finally:
            if self._client is None:
                await client.aclose()

    @staticmethod
    def _tokens(response: httpx.Response) -> SessionTokens:
        try:
            data = response.json()
            return SessionTokens(
                access_token=str(data["access_token"]),
                refresh_token=str(data["refresh_token"]),
                expires_in=int(data["expires_in"]) if data.get("expires_in") is not None else None,
            )
        except (ValueError, KeyError, TypeError):
            log.warning("supabase auth returned an unexpected body")
            raise ApiError("auth_unavailable", 503) from None

    async def sign_in_with_google(self, id_token: str, nonce: str) -> SessionTokens:
        response = await self._post(
            "/token?grant_type=id_token", {"provider": "google", "id_token": id_token, "nonce": nonce}
        )
        if response.status_code in (400, 401, 403, 422):
            # Wrong audience, expired or reused token, bad nonce: all the same answer to the caller.
            raise ApiError("unauthorized", 401)
        if response.status_code >= 500 or response.status_code == 429:
            raise ApiError("auth_unavailable", 503)
        if response.status_code != 200:
            raise ApiError("unauthorized", 401)
        return self._tokens(response)

    async def refresh(self, refresh_token: str) -> SessionTokens:
        response = await self._post("/token?grant_type=refresh_token", {"refresh_token": refresh_token})
        if response.status_code in (400, 401, 403, 422):
            # Includes refresh-token reuse: Supabase revokes the session, and so does our answer.
            raise ApiError("unauthorized", 401)
        if response.status_code >= 500 or response.status_code == 429:
            raise ApiError("auth_unavailable", 503)
        if response.status_code != 200:
            raise ApiError("unauthorized", 401)
        return self._tokens(response)

    async def sign_out(self, access_token: str) -> None:
        """Revokes all of the user's sessions ('sign out everywhere', architecture 1.2). Best effort."""
        try:
            await self._post("/logout?scope=global", None, bearer=access_token)
        except ApiError:
            log.warning("sign out could not reach supabase")
