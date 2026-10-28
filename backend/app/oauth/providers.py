"""The OAuth conversations with GitHub and Google. Endpoints and field names are from the providers' docs
(GitHub: generating and refreshing user access tokens; Google: OAuth 2.0 for web server applications), checked
2026-10-28.

Rules enforced here:
  * host allowlist (CLAUDE.md rule 9): only the hosts below can be called, over https, through the SSRF-safe
    client; anything else raises before a request is made;
  * provider response bodies are parsed for known fields only and never copied into errors or logs;
  * a rejected code or refresh token (`invalid_grant`, `bad_refresh_token`, ...) is ProviderRejected (the user
    must reconnect); network trouble, 5xx and 429 are ProviderUnavailable (try again later). The two are never
    mixed up, so an outage cannot make a good connection look revoked.
"""

import logging
from dataclasses import dataclass
from urllib.parse import urlencode, urlsplit

import httpx2 as httpx

from app.llm.net import ResponseTooLarge, UnsafeDestination, make_safe_client

log = logging.getLogger("agenthub.oauth")

ALLOWED_HOSTS = frozenset({"github.com", "api.github.com", "accounts.google.com", "oauth2.googleapis.com"})
_TIMEOUT = httpx.Timeout(connect=8.0, read=15.0, write=8.0, pool=5.0)
_MAX_BODY = 256 * 1024

GITHUB_AUTHORIZE = "https://github.com/login/oauth/authorize"
GITHUB_EXCHANGE_ENDPOINT = "https://github.com/login/oauth/access_token"
GITHUB_API = "https://api.github.com"
GOOGLE_AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_EXCHANGE_ENDPOINT = "https://oauth2.googleapis.com/token"
GOOGLE_REVOKE = "https://oauth2.googleapis.com/revoke"

# `error` values that mean "this code or refresh token is no good": reconnect.
_REJECTED = {
    "invalid_grant",
    "bad_verification_code",
    "bad_refresh_token",
    "invalid_request",
    "unauthorized_client",
    "access_denied",
    "incorrect_client_credentials",
    "redirect_uri_mismatch",
    "invalid_client",
}


class ProviderRejected(Exception):
    """The provider refused the code or token (revoked, expired, wrong). The user must reconnect."""


class ProviderUnavailable(Exception):
    """Could not complete the call (network, timeout, 5xx, 429). Nothing is known about the token."""


@dataclass(frozen=True)
class TokenSet:
    access_token: str
    refresh_token: str | None
    expires_in: int | None  # seconds
    scopes: list[str]

    def __repr__(self) -> str:  # never print tokens through repr
        return "TokenSet(***)"


class ProviderHttp:
    """POST/GET with the allowlist enforced. Tests pass a client over httpx2.MockTransport."""

    def __init__(self, client: httpx.AsyncClient | None = None) -> None:
        self._client = client

    @staticmethod
    def _check(url: str) -> None:
        parts = urlsplit(url)
        if parts.scheme != "https" or parts.hostname not in ALLOWED_HOSTS:
            raise UnsafeDestination("host not allowed")

    async def request(self, method: str, url: str, **kwargs) -> httpx.Response:
        self._check(url)
        owns = self._client is None
        client = self._client or make_safe_client(_TIMEOUT, max_response_bytes=_MAX_BODY)
        try:
            response = await client.request(method, url, **kwargs)
            await response.aread()
            return response
        except (httpx.HTTPError, UnsafeDestination, ResponseTooLarge, OSError) as e:
            log.info("provider call failed", extra={"error_type": type(e).__name__})
            raise ProviderUnavailable from None
        finally:
            if owns:
                await client.aclose()


def _json(response: httpx.Response) -> dict:
    try:
        data = response.json()
    except ValueError:
        raise ProviderUnavailable from None
    if not isinstance(data, dict):
        raise ProviderUnavailable
    return data


def _to_tokens(data: dict, *, scopes: list[str]) -> TokenSet:
    access = data.get("access_token")
    if not isinstance(access, str) or not access:
        raise ProviderUnavailable
    refresh = data.get("refresh_token")
    expires = data.get("expires_in")
    return TokenSet(
        access_token=access,
        refresh_token=refresh if isinstance(refresh, str) and refresh else None,
        expires_in=int(expires)
        if isinstance(expires, int | float | str) and str(expires).isdigit()
        else None,
        scopes=scopes,
    )


def _classify(response: httpx.Response, data: dict | None) -> None:
    """Raises for anything that is not a clean success."""
    if response.status_code == 429 or response.status_code >= 500:
        raise ProviderUnavailable
    error = (data or {}).get("error") if isinstance(data, dict) else None
    if error is not None or response.status_code >= 400:
        if isinstance(error, str) and error in _REJECTED:
            raise ProviderRejected
        if response.status_code in (400, 401, 403) and error is None:
            raise ProviderRejected
        # Unknown error names are not assumed to mean a revoked token.
        log.info("provider returned an unrecognised error", extra={"status": response.status_code})
        raise ProviderUnavailable


class GitHubApp:
    """A GitHub App's user-to-server flow. Tokens last 8 hours; the refresh token lasts 6 months and
    ROTATES on every use, so the new one must be saved before the old one is forgotten."""

    def __init__(self, client_id: str, client_secret: str, http: ProviderHttp | None = None) -> None:
        self._id, self._secret = client_id, client_secret
        self._http = http or ProviderHttp()

    def authorize_url(self, *, state: str, challenge: str, redirect_uri: str) -> str:
        query = {
            "client_id": self._id,
            "redirect_uri": redirect_uri,
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "allow_signup": "false",
        }
        return f"{GITHUB_AUTHORIZE}?{urlencode(query)}"

    async def exchange(self, *, code: str, verifier: str, redirect_uri: str) -> TokenSet:
        response = await self._http.request(
            "POST",
            GITHUB_EXCHANGE_ENDPOINT,
            data={
                "client_id": self._id,
                "client_secret": self._secret,
                "code": code,
                "redirect_uri": redirect_uri,
                "code_verifier": verifier,
            },
            headers={"Accept": "application/json"},
        )
        data = _json(response) if response.content else {}
        _classify(response, data)
        return _to_tokens(data, scopes=[])  # GitHub Apps report no scopes: permissions live on the App

    async def refresh(self, refresh_token: str) -> TokenSet:
        response = await self._http.request(
            "POST",
            GITHUB_EXCHANGE_ENDPOINT,
            data={
                "client_id": self._id,
                "client_secret": self._secret,
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
            },
            headers={"Accept": "application/json"},
        )
        data = _json(response) if response.content else {}
        _classify(response, data)
        tokens = _to_tokens(data, scopes=[])
        if tokens.refresh_token is None:
            raise ProviderUnavailable  # a rotation without a new refresh token must not be half-saved
        return tokens

    async def revoke(self, access_token: str) -> bool:
        """Deletes this one token (DELETE /applications/{client_id}/token, basic auth). Best effort."""
        try:
            response = await self._http.request(
                "DELETE",
                f"{GITHUB_API}/applications/{self._id}/token",
                json={"access_token": access_token},
                auth=(self._id, self._secret),
                headers={"Accept": "application/vnd.github+json"},
            )
        except ProviderUnavailable:
            return False
        return response.status_code in (204, 404)


class GoogleConnector:
    """Google consent for Gmail and Calendar: authorization code + PKCE + offline access."""

    def __init__(self, client_id: str, client_secret: str, http: ProviderHttp | None = None) -> None:
        self._id, self._secret = client_id, client_secret
        self._http = http or ProviderHttp()

    def authorize_url(self, *, state: str, challenge: str, redirect_uri: str, scopes: list[str]) -> str:
        query = {
            "client_id": self._id,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": " ".join(scopes),
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "access_type": "offline",  # a refresh token, needed for schedules and long runs
            "prompt": "consent",  # always return a refresh token, even on a repeat consent
            "include_granted_scopes": "false",  # this agent's grant only; never fold in other agents' scopes
        }
        return f"{GOOGLE_AUTHORIZE}?{urlencode(query)}"

    async def exchange(self, *, code: str, verifier: str, redirect_uri: str) -> TokenSet:
        response = await self._http.request(
            "POST",
            GOOGLE_EXCHANGE_ENDPOINT,
            data={
                "client_id": self._id,
                "client_secret": self._secret,
                "code": code,
                "code_verifier": verifier,
                "grant_type": "authorization_code",
                "redirect_uri": redirect_uri,
            },
        )
        data = _json(response) if response.content else {}
        _classify(response, data)
        # The `scope` field lists what the user ACTUALLY granted (they can untick scopes), so that is what we
        # record, not what we asked for.
        granted = str(data.get("scope", "")).split()
        return _to_tokens(data, scopes=granted)

    async def refresh(self, refresh_token: str) -> TokenSet:
        response = await self._http.request(
            "POST",
            GOOGLE_EXCHANGE_ENDPOINT,
            data={
                "client_id": self._id,
                "client_secret": self._secret,
                "refresh_token": refresh_token,
                "grant_type": "refresh_token",
            },
        )
        data = _json(response) if response.content else {}
        _classify(response, data)
        # Google does not return a new refresh token on refresh: the stored one stays valid.
        return _to_tokens(data, scopes=str(data.get("scope", "")).split())

    async def revoke(self, token: str) -> bool:
        """Revokes the token (and its sibling). Revocation applies to the user's grant for this project."""
        try:
            response = await self._http.request(
                "POST",
                GOOGLE_REVOKE,
                data={"token": token},
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
        except ProviderUnavailable:
            return False
        return response.status_code == 200
