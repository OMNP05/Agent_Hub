"""OAuth `state` and PKCE (CLAUDE.md: OAuth is run by the backend, code + PKCE + state, one grant per agent).

* `state` is 256 random bits sent to the provider. Only its SHA-256 hash is stored, so a database leak cannot
  be replayed into a callback. The row binds the state to one user, one agent and one connector.
* The PKCE verifier is created and kept server-side, encrypted at rest (B4); the provider only ever sees the
  S256 challenge. A stolen authorization code is useless without the verifier.
* A state is single use (claimed with one atomic UPDATE ... WHERE used_at IS NULL AND expires_at > now()) and
  expires after 10 minutes. Forged, reused, expired and unknown states are all the same answer.
* Starting a new connect for an agent invalidates that agent's earlier pending states.
"""

import base64
import hashlib
import secrets
import uuid
from dataclasses import dataclass

import psycopg

from app.crypto import DecryptionError, Envelope, store

STATE_TTL_MINUTES = 10
MAX_PENDING_PER_USER = 20


class InvalidState(Exception):
    """Unknown, expired, reused or mismatched state. Carries no detail on purpose."""


def new_state() -> str:
    return secrets.token_urlsafe(32)


def hash_state(state: str) -> str:
    return hashlib.sha256(state.encode()).hexdigest()


def new_verifier() -> str:
    # 43 to 128 characters from the unreserved set (RFC 7636); 64 random bytes gives 86.
    return secrets.token_urlsafe(64)


def challenge_for(verifier: str) -> str:
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


@dataclass(frozen=True)
class Claimed:
    user_id: uuid.UUID
    agent_id: uuid.UUID
    connector: str
    scopes: list[str]
    verifier: str


def create(
    conn: psycopg.Connection,
    env: Envelope,
    *,
    user_id: uuid.UUID,
    agent_id: uuid.UUID,
    connector: str,
    scopes: list[str],
) -> tuple[str, str]:
    """Stores a pending handshake and returns (state, code_challenge) for the provider URL."""
    # Housekeeping and a per-agent reset: a fresh start voids older links for the same agent.
    conn.execute("delete from public.oauth_states where expires_at < now() - interval '1 day'")
    conn.execute(
        "delete from public.oauth_states where user_id = %s and agent_id = %s and used_at is null",
        (user_id, agent_id),
    )
    pending = conn.execute(
        "select count(*) from public.oauth_states where user_id = %s and used_at is null", (user_id,)
    ).fetchone()[0]
    if pending >= MAX_PENDING_PER_USER:
        from app.errors import ApiError

        raise ApiError("rate_limited", 429)
    state, verifier = new_state(), new_verifier()
    state_hash = hash_state(state)
    conn.execute(
        "insert into public.oauth_states (state_hash, user_id, agent_id, connector, scopes, "
        "code_verifier_enc, key_version, expires_at) "
        "values (%s, %s, %s, %s, %s, %s, %s, now() + make_interval(mins => %s))",
        (
            state_hash,
            user_id,
            agent_id,
            connector,
            scopes,
            store.encrypt_code_verifier(env, state_hash, verifier),
            env.current_version,
            STATE_TTL_MINUTES,
        ),
    )
    return state, challenge_for(verifier)


def claim(conn: psycopg.Connection, env: Envelope, state: str, connector: str) -> Claimed:
    """Atomically marks the state used and returns what it was bound to. Raises InvalidState for anything
    wrong. The connector in the callback URL must be the one the state was created for."""
    if not state or len(state) > 200:
        raise InvalidState
    state_hash = hash_state(state)
    row = conn.execute(
        "update public.oauth_states set used_at = now() "
        "where state_hash = %s and connector = %s and used_at is null and expires_at > now() "
        "returning user_id, agent_id, connector, scopes, code_verifier_enc",
        (state_hash, connector),
    ).fetchone()
    if row is None:
        raise InvalidState
    try:
        verifier = store.decrypt_code_verifier(env, state_hash, bytes(row[4]))
    except DecryptionError:
        raise InvalidState from None
    return Claimed(row[0], row[1], row[2], list(row[3]), verifier)
