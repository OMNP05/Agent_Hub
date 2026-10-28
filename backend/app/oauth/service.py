"""Connecting an agent to a provider, keeping the grant fresh, and tearing it down.

One grant per agent (architecture 1.3). The phone never sees a token: tokens are encrypted at rest (B4) and
decrypted in memory only for the call that needs them. Every statement filters by user id AND agent id.
"""

import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from urllib.parse import urlencode

import psycopg

from app.crypto import DecryptionError, Envelope, store
from app.db.access import Database, in_thread, transaction
from app.errors import ApiError
from app.oauth import scopes as scope_rules
from app.oauth import state as oauth_state
from app.oauth.providers import (
    GitHubApp,
    GoogleConnector,
    ProviderRejected,
    ProviderUnavailable,
    TokenSet,
)

log = logging.getLogger("agenthub.oauth")

REFRESH_MARGIN = timedelta(seconds=60)
CONNECTORS = ("github", "gmail", "calendar")


@dataclass
class Providers:
    """The configured provider clients. A connector whose settings are missing is simply absent."""

    github: GitHubApp | None = None
    google: GoogleConnector | None = None

    def for_connector(self, connector: str) -> GitHubApp | GoogleConnector:
        provider = self.github if connector == "github" else self.google
        if provider is None:
            raise ApiError("oauth_not_configured", 503)
        return provider


def redirect_uri(public_base_url: str, connector: str) -> str:
    return f"{public_base_url.rstrip('/')}/oauth/{connector}/callback"


def done_url(public_base_url: str, *, ok: bool, agent_id: uuid.UUID | None) -> str:
    query = {"status": "ok" if ok else "error"}
    if agent_id is not None:
        query["agent_id"] = str(agent_id)
    return f"{public_base_url.rstrip('/')}/oauth/done?{urlencode(query)}"


# -- start ---------------------------------------------------------------------------------------------------


def _prepare_start(
    conn: psycopg.Connection, env: Envelope, user_id: uuid.UUID, agent_id: uuid.UUID, connector: str
):
    row = conn.execute(
        "select template_id, connector, toggles from public.agents where id = %s and user_id = %s",
        (agent_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("not_found", 404)
    template_id, agent_connector, toggles = row
    if agent_connector != connector:  # an agent can only be connected to its own provider
        raise ApiError("invalid_request", 422)
    needed = scope_rules.required_scopes(template_id, toggles)
    if connector != "github" and not needed:
        raise ApiError("invalid_request", 422)  # nothing is switched on, so there is nothing to consent to
    state, challenge = oauth_state.create(
        conn, env, user_id=user_id, agent_id=agent_id, connector=connector, scopes=needed
    )
    return state, challenge, needed


async def start(
    db: Database,
    env: Envelope,
    providers: Providers,
    *,
    user_id: uuid.UUID,
    agent_id: uuid.UUID,
    connector: str,
    public_base_url: str,
) -> str:
    provider = providers.for_connector(connector)
    async with transaction(db) as conn:
        state, challenge, needed = await in_thread(_prepare_start, conn, env, user_id, agent_id, connector)
    uri = redirect_uri(public_base_url, connector)
    if isinstance(provider, GitHubApp):
        return provider.authorize_url(state=state, challenge=challenge, redirect_uri=uri)
    return provider.authorize_url(state=state, challenge=challenge, redirect_uri=uri, scopes=needed)


# -- callback ------------------------------------------------------------------------------------------------


def _claim(conn: psycopg.Connection, env: Envelope, state: str, connector: str) -> oauth_state.Claimed:
    return oauth_state.claim(conn, env, state, connector)


def _store_connection(
    conn: psycopg.Connection, env: Envelope, claimed: oauth_state.Claimed, tokens: TokenSet
) -> None:
    owned = conn.execute(
        "select 1 from public.agents where id = %s and user_id = %s and connector = %s for update",
        (claimed.agent_id, claimed.user_id, claimed.connector),
    ).fetchone()
    if owned is None:
        raise oauth_state.InvalidState  # the agent was deleted while the user was consenting
    expires_at = _expiry(tokens.expires_in)
    connection_id = conn.execute(
        "insert into public.connections (agent_id, user_id, connector, status, scopes_granted, expires_at) "
        "values (%s, %s, %s, 'active', %s, %s) "
        "on conflict (agent_id) do update set status = 'active', scopes_granted = excluded.scopes_granted, "
        "expires_at = excluded.expires_at returning id",
        (claimed.agent_id, claimed.user_id, claimed.connector, tokens.scopes, expires_at),
    ).fetchone()[0]
    store.save_connection_tokens(conn, env, connection_id, tokens.access_token, tokens.refresh_token)
    # A freshly connected draft agent becomes usable; a paused one stays paused.
    conn.execute(
        "update public.agents set status = 'active' where id = %s and user_id = %s and status = 'draft'",
        (claimed.agent_id, claimed.user_id),
    )


def _expiry(expires_in: int | None) -> datetime | None:
    return datetime.now(UTC) + timedelta(seconds=expires_in) if expires_in else None


@dataclass(frozen=True)
class CallbackResult:
    ok: bool
    agent_id: uuid.UUID | None


async def complete(
    db: Database,
    env: Envelope,
    providers: Providers,
    *,
    connector: str,
    code: str | None,
    state: str | None,
    provider_error: str | None,
    public_base_url: str,
) -> CallbackResult:
    """Handles the browser coming back from the provider. Never raises for bad input: every failure is a
    CallbackResult(ok=False), shown to the user as one generic message. The state is burned FIRST and
    committed, so a code that fails to exchange cannot be retried with the same link."""
    if not state:
        return CallbackResult(False, None)
    try:
        async with transaction(db) as conn:
            claimed = await in_thread(_claim, conn, env, state, connector)
    except (oauth_state.InvalidState, ApiError):
        return CallbackResult(False, None)

    if provider_error or not code or len(code) > 2048:
        return CallbackResult(False, claimed.agent_id)  # the user said no, or the provider sent no code
    try:
        provider = providers.for_connector(connector)
        tokens = await provider.exchange(
            code=code, verifier=claimed.verifier, redirect_uri=redirect_uri(public_base_url, connector)
        )
    except (ProviderRejected, ProviderUnavailable, ApiError):
        return CallbackResult(False, claimed.agent_id)

    if connector != "github" and (not tokens.refresh_token or not tokens.scopes):
        # No refresh token: the agent would stop after an hour. No scopes: nothing was granted.
        log.info("google grant unusable", extra={"has_refresh": bool(tokens.refresh_token)})
        return CallbackResult(False, claimed.agent_id)
    try:
        async with transaction(db) as conn:
            await in_thread(_store_connection, conn, env, claimed, tokens)
    except oauth_state.InvalidState:
        return CallbackResult(False, None)
    return CallbackResult(True, claimed.agent_id)


# -- using the grant -----------------------------------------------------------------------------------------


def _lock_and_read(conn: psycopg.Connection, env: Envelope, user_id: uuid.UUID, agent_id: uuid.UUID):
    row = conn.execute(
        "select c.id, c.status, c.connector, c.expires_at from public.connections c "
        "where c.agent_id = %s and c.user_id = %s",
        (agent_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("connection_needs_reauth", 409)
    # One refresh at a time per connection: a second caller waits here, then finds the fresh token.
    conn.execute("select pg_advisory_xact_lock(hashtextextended(%s, 0))", (f"conn:{row[0]}",))
    row = conn.execute(
        "select c.id, c.status, c.connector, c.expires_at from public.connections c "
        "where c.agent_id = %s and c.user_id = %s",
        (agent_id, user_id),
    ).fetchone()
    if row is None or row[1] != "active":
        raise ApiError("connection_needs_reauth", 409)
    try:
        loaded = store.load_connection_tokens_versioned(conn, env, row[0])
    except DecryptionError:
        _mark_needs_reauth(conn, row[0])
        return row, None
    if loaded is None:
        _mark_needs_reauth(conn, row[0])
        return row, None
    return row, loaded


def _mark_needs_reauth(conn: psycopg.Connection, connection_id: uuid.UUID) -> None:
    conn.execute("update public.connections set status = 'needs_reauth' where id = %s", (connection_id,))


def _save_refreshed(
    conn: psycopg.Connection, env: Envelope, connection_id: uuid.UUID, version: object, tokens: TokenSet
) -> None:
    saved = store.replace_connection_tokens_if_unchanged(
        conn, env, connection_id, version, tokens.access_token, tokens.refresh_token
    )
    if not saved:  # cannot happen while the advisory lock is held; refuse rather than overwrite
        raise ApiError("internal_error", 500)
    conn.execute(
        "update public.connections set expires_at = %s where id = %s",
        (_expiry(tokens.expires_in), connection_id),
    )


async def get_access_token(
    db: Database, env: Envelope, providers: Providers, *, user_id: uuid.UUID, agent_id: uuid.UUID
) -> str:
    """A valid provider access token for this agent's grant, refreshing it first if it expires within a
    minute. The only function (besides disconnect) that turns stored ciphertext back into a token; the
    Tool Gateway (B8) is its only caller. Raises connection_needs_reauth (409) if the grant is gone."""
    needs_reauth = False
    token: str | None = None
    async with transaction(db) as conn:
        row, loaded = await in_thread(_lock_and_read, conn, env, user_id, agent_id)
        if loaded is None:
            needs_reauth = True
        else:
            access, refresh, version = loaded
            expires_at: datetime | None = row[3]
            fresh = expires_at is None or expires_at - datetime.now(UTC) > REFRESH_MARGIN
            if fresh:
                token = access
            elif refresh is None:
                await in_thread(_mark_needs_reauth, conn, row[0])
                needs_reauth = True
            else:
                try:
                    provider = providers.for_connector(row[2])
                    tokens = await provider.refresh(refresh)
                    await in_thread(_save_refreshed, conn, env, row[0], version, tokens)
                    token = tokens.access_token
                except ProviderRejected:
                    # Revoked or expired at the provider. Recorded and committed before we report it.
                    await in_thread(_mark_needs_reauth, conn, row[0])
                    needs_reauth = True
                except ProviderUnavailable:
                    raise ApiError("provider_unavailable", 503) from None
    if needs_reauth or token is None:
        raise ApiError("connection_needs_reauth", 409)
    return token


# -- tearing down ----------------------------------------------------------------------------------------


def _read_for_revoke(conn: psycopg.Connection, env: Envelope, user_id: uuid.UUID, agent_id: uuid.UUID):
    row = conn.execute(
        "select id, connector from public.connections where agent_id = %s and user_id = %s",
        (agent_id, user_id),
    ).fetchone()
    if row is None:
        return None
    try:
        tokens = store.load_connection_tokens(conn, env, row[0])
    except DecryptionError:
        tokens = None
    others = 0
    if row[1] in ("gmail", "calendar"):
        others = conn.execute(
            "select count(*) from public.connections where user_id = %s and agent_id <> %s "
            "and connector in ('gmail', 'calendar')",
            (user_id, agent_id),
        ).fetchone()[0]
    return row[1], tokens, others


async def revoke_at_provider(
    db: Database, env: Envelope, providers: Providers, *, user_id: uuid.UUID, agent_id: uuid.UUID
) -> bool:
    """Best-effort: asks the provider to invalidate this agent's tokens. Returns whether it was confirmed.
    The local rows are removed by the caller either way, so an unreachable provider never keeps a grant alive
    on our side (the tokens are deleted).

    Google's revoke endpoint is documented to remove the user's grant for the whole project, which could also
    cut off the user's other Google agents. So Google tokens are revoked only when this is the user's last
    Google connection; otherwise they are just forgotten locally."""
    async with transaction(db) as conn:
        info = await in_thread(_read_for_revoke, conn, env, user_id, agent_id)
    if info is None or info[1] is None:
        return False
    connector, (access, refresh), others = info[0], info[1], info[2]
    try:
        provider = providers.for_connector(connector)
    except ApiError:
        return False
    if isinstance(provider, GitHubApp):
        return await provider.revoke(access)
    if others > 0:
        return False
    return await provider.revoke(refresh or access)


def tokens_cover(template_id: str, toggles: dict[str, bool], granted: list[str]) -> bool:
    """True when the granted scopes include everything the enabled toggles need (used to decide whether to ask
    the user to consent again after they switch a new toggle on)."""
    needed = set(scope_rules.required_scopes(template_id, toggles))
    return needed.issubset(set(granted))
