"""LLM profile storage. Every statement filters by the verified user id (CLAUDE.md rule 7); a profile that
belongs to someone else is indistinguishable from one that does not exist (404 for both).

The API key is encrypted on the way in (app.crypto.store) and is only ever read back inside [load_for_test],
for the single outbound check, in memory. Responses carry `key_last4` and nothing else about the key.
"""

import uuid
from dataclasses import dataclass
from typing import Any

import psycopg

from app.crypto import DecryptionError, Envelope, store
from app.errors import ApiError
from app.llm.providers import ProfileSpec, Provider

MAX_PROFILES_PER_USER = 20

_COLUMNS = "id, name, provider, base_url, model, temperature, max_tokens, key_last4, is_default"


@dataclass(frozen=True)
class ProfileData:
    name: str
    provider: str
    base_url: str | None
    model: str
    temperature: float
    max_tokens: int
    is_default: bool


def _public(row: tuple) -> dict[str, Any]:
    return {
        "id": str(row[0]),
        "name": row[1],
        "provider": row[2],
        "base_url": row[3],
        "model": row[4],
        "temperature": float(row[5]),
        "max_tokens": row[6],
        "key_last4": row[7],
        "is_default": row[8],
    }


def key_tail(api_key: str) -> str:
    return api_key[-4:]


def list_profiles(conn: psycopg.Connection, user_id: uuid.UUID) -> list[dict[str, Any]]:
    rows = conn.execute(
        f"select {_COLUMNS} from public.llm_profiles where user_id = %s order by created_at, id",  # noqa: S608
        (user_id,),
    ).fetchall()
    return [_public(r) for r in rows]


def get_profile(conn: psycopg.Connection, user_id: uuid.UUID, profile_id: uuid.UUID) -> dict[str, Any]:
    row = conn.execute(
        f"select {_COLUMNS} from public.llm_profiles where id = %s and user_id = %s",  # noqa: S608
        (profile_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("not_found", 404)
    return _public(row)


def _clear_default(conn: psycopg.Connection, user_id: uuid.UUID, except_id: uuid.UUID) -> None:
    conn.execute(
        "update public.llm_profiles set is_default = false where user_id = %s and id <> %s and is_default",
        (user_id, except_id),
    )


def create_profile(
    conn: psycopg.Connection, env: Envelope, user_id: uuid.UUID, data: ProfileData, api_key: str
) -> dict[str, Any]:
    # The advisory lock serialises two simultaneous creates by one user, so the per-user cap cannot be raced.
    conn.execute("select pg_advisory_xact_lock(hashtextextended(%s, 0))", (f"llm_profiles:{user_id}",))
    count = conn.execute(
        "select count(*) from public.llm_profiles where user_id = %s", (user_id,)
    ).fetchone()[0]
    if count >= MAX_PROFILES_PER_USER:
        raise ApiError("profile_limit_reached", 409)
    make_default = data.is_default or count == 0  # the first profile is the default
    profile_id = conn.execute(
        "insert into public.llm_profiles (user_id, name, provider, base_url, model, temperature, max_tokens, "
        "key_last4, is_default) values (%s, %s, %s, %s, %s, %s, %s, %s, false) returning id",
        (
            user_id,
            data.name,
            data.provider,
            data.base_url,
            data.model,
            data.temperature,
            data.max_tokens,
            key_tail(api_key),
        ),
    ).fetchone()[0]
    store.save_llm_key(conn, env, profile_id, api_key)
    if make_default:
        _clear_default(conn, user_id, profile_id)
        conn.execute("update public.llm_profiles set is_default = true where id = %s", (profile_id,))
    return get_profile(conn, user_id, profile_id)


def update_profile(
    conn: psycopg.Connection,
    env: Envelope,
    user_id: uuid.UUID,
    profile_id: uuid.UUID,
    data: ProfileData,
    api_key: str | None,
) -> dict[str, Any]:
    owned = conn.execute(
        "select is_default from public.llm_profiles where id = %s and user_id = %s for update",
        (profile_id, user_id),
    ).fetchone()
    if owned is None:
        raise ApiError("not_found", 404)
    if data.is_default:
        _clear_default(conn, user_id, profile_id)
    # Leaving "default" off never removes the default: only choosing another profile does that.
    is_default = True if owned[0] else data.is_default
    conn.execute(
        "update public.llm_profiles set name = %s, provider = %s, base_url = %s, model = %s, "
        "temperature = %s, max_tokens = %s, is_default = %s where id = %s and user_id = %s",
        (
            data.name,
            data.provider,
            data.base_url,
            data.model,
            data.temperature,
            data.max_tokens,
            is_default,
            profile_id,
            user_id,
        ),
    )
    if api_key:
        store.save_llm_key(conn, env, profile_id, api_key)
        conn.execute(
            "update public.llm_profiles set key_last4 = %s where id = %s and user_id = %s",
            (key_tail(api_key), profile_id, user_id),
        )
    return get_profile(conn, user_id, profile_id)


def delete_profile(conn: psycopg.Connection, user_id: uuid.UUID, profile_id: uuid.UUID) -> None:
    # The secret row goes with it (on delete cascade); agents that used it fall back to no profile.
    deleted = conn.execute(
        "delete from public.llm_profiles where id = %s and user_id = %s returning id", (profile_id, user_id)
    ).fetchone()
    if deleted is None:
        raise ApiError("not_found", 404)


def load_for_test(
    conn: psycopg.Connection, env: Envelope, user_id: uuid.UUID, profile_id: uuid.UUID
) -> tuple[ProfileSpec, str]:
    """The spec and the decrypted key of one of the user's own profiles. The key stays in memory."""
    row = conn.execute(
        "select provider, base_url, model, temperature, max_tokens from public.llm_profiles "
        "where id = %s and user_id = %s",
        (profile_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("not_found", 404)
    try:
        key = store.load_llm_key(conn, env, profile_id)
    except DecryptionError:
        # The stored key cannot be read (for example the master key changed): the user must enter it again.
        raise ApiError("llm_key_invalid", 400) from None
    if key is None:
        raise ApiError("llm_key_invalid", 400)
    return spec_from_row(row), key


def spec_from_row(row: tuple) -> ProfileSpec:
    return ProfileSpec(Provider(row[0]), row[1], row[2], float(row[3]), row[4])
