"""The only code that writes or reads encrypted columns, so no other module handles ciphertext or the key.

Each function takes an open psycopg connection that uses the backend's own database role (never the app's
`authenticated` role: these tables have no policy for it). Plaintext exists only as a return value in memory.
"""

import uuid

import psycopg

from app.crypto.envelope import CryptoError, Envelope, context_for

# (table, primary key column, encrypted columns). Rotation walks exactly this list.
ENCRYPTED_COLUMNS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("llm_profile_secrets", "profile_id", ("key_enc",)),
    ("connection_secrets", "connection_id", ("access_token_enc", "refresh_token_enc")),
    ("oauth_states", "state_hash", ("code_verifier_enc",)),
)


def _ctx(table: str, column: str, row_id: object) -> bytes:
    return context_for(table, column, row_id)


# -- LLM profile keys --------------------------------------------------------------------------------------


def save_llm_key(conn: psycopg.Connection, env: Envelope, profile_id: uuid.UUID, api_key: str) -> None:
    if not api_key:
        raise CryptoError("empty key")
    blob = env.encrypt(api_key, _ctx("llm_profile_secrets", "key_enc", profile_id))
    conn.execute(
        "insert into public.llm_profile_secrets (profile_id, key_enc, key_version) values (%s, %s, %s) "
        "on conflict (profile_id) do update set key_enc = excluded.key_enc, "
        "key_version = excluded.key_version, updated_at = now()",
        (profile_id, blob, env.current_version),
    )


def load_llm_key(conn: psycopg.Connection, env: Envelope, profile_id: uuid.UUID) -> str | None:
    row = conn.execute(
        "select key_enc from public.llm_profile_secrets where profile_id = %s", (profile_id,)
    ).fetchone()
    if row is None:
        return None
    return env.decrypt_text(bytes(row[0]), _ctx("llm_profile_secrets", "key_enc", profile_id))


# -- provider tokens ---------------------------------------------------------------------------------------


def save_connection_tokens(
    conn: psycopg.Connection,
    env: Envelope,
    connection_id: uuid.UUID,
    access_token: str,
    refresh_token: str | None,
) -> None:
    if not access_token:
        raise CryptoError("empty token")
    access = env.encrypt(access_token, _ctx("connection_secrets", "access_token_enc", connection_id))
    refresh = (
        env.encrypt(refresh_token, _ctx("connection_secrets", "refresh_token_enc", connection_id))
        if refresh_token
        else None
    )
    conn.execute(
        "insert into public.connection_secrets "
        "(connection_id, access_token_enc, refresh_token_enc, key_version) values (%s, %s, %s, %s) "
        "on conflict (connection_id) do update set access_token_enc = excluded.access_token_enc, "
        "refresh_token_enc = excluded.refresh_token_enc, key_version = excluded.key_version, "
        "updated_at = now()",
        (connection_id, access, refresh, env.current_version),
    )


def load_connection_tokens(
    conn: psycopg.Connection, env: Envelope, connection_id: uuid.UUID
) -> tuple[str, str | None] | None:
    row = conn.execute(
        "select access_token_enc, refresh_token_enc from public.connection_secrets where connection_id = %s",
        (connection_id,),
    ).fetchone()
    if row is None:
        return None
    access = env.decrypt_text(bytes(row[0]), _ctx("connection_secrets", "access_token_enc", connection_id))
    refresh = (
        env.decrypt_text(bytes(row[1]), _ctx("connection_secrets", "refresh_token_enc", connection_id))
        if row[1] is not None
        else None
    )
    return access, refresh


# -- OAuth PKCE verifier -----------------------------------------------------------------------------------


def encrypt_code_verifier(env: Envelope, state_hash: str, verifier: str) -> bytes:
    return env.encrypt(verifier, _ctx("oauth_states", "code_verifier_enc", state_hash))


def decrypt_code_verifier(env: Envelope, state_hash: str, blob: bytes) -> str:
    return env.decrypt_text(bytes(blob), _ctx("oauth_states", "code_verifier_enc", state_hash))


# -- rotation ----------------------------------------------------------------------------------------------


def rotate(conn: psycopg.Connection, env: Envelope) -> dict[str, int]:
    """Re-wraps every stored secret under the current master key. Runs in the caller's transaction, so a
    failure on any row leaves the whole database untouched. Returns {table: rows changed}; no secret data."""
    changed: dict[str, int] = {}
    for table, pk, columns in ENCRYPTED_COLUMNS:
        changed[table] = 0
        selected = ", ".join(columns)
        for row in conn.execute(f"select {pk}, {selected} from public.{table}").fetchall():  # noqa: S608
            row_id, blobs = row[0], row[1:]
            new_blobs = []
            for column, blob in zip(columns, blobs, strict=True):
                if blob is None:
                    new_blobs.append(None)
                    continue
                new_blobs.append(env.rewrap(bytes(blob), _ctx(table, column, row_id)))
            if all(old is None or bytes(old) == new for old, new in zip(blobs, new_blobs, strict=True)):
                continue
            assignments = ", ".join(f"{c} = %s" for c in columns)
            conn.execute(
                f"update public.{table} set {assignments}, key_version = %s where {pk} = %s",  # noqa: S608
                (*new_blobs, env.current_version, row_id),
            )
            changed[table] += 1
    return changed


# -- provider tokens with a version for safe concurrent refresh ---------------------------------------------


def load_connection_tokens_versioned(
    conn: psycopg.Connection, env: Envelope, connection_id: uuid.UUID
) -> tuple[str, str | None, object] | None:
    """Like load_connection_tokens, plus the row's `updated_at` as a version for compare-and-swap."""
    row = conn.execute(
        "select access_token_enc, refresh_token_enc, updated_at from public.connection_secrets "
        "where connection_id = %s",
        (connection_id,),
    ).fetchone()
    if row is None:
        return None
    access = env.decrypt_text(bytes(row[0]), _ctx("connection_secrets", "access_token_enc", connection_id))
    refresh = (
        env.decrypt_text(bytes(row[1]), _ctx("connection_secrets", "refresh_token_enc", connection_id))
        if row[1] is not None
        else None
    )
    return access, refresh, row[2]


def replace_connection_tokens_if_unchanged(
    conn: psycopg.Connection,
    env: Envelope,
    connection_id: uuid.UUID,
    expected_version: object,
    access_token: str,
    refresh_token: str | None,
) -> bool:
    """Saves new tokens only if nobody else saved since [expected_version] was read. Returns False when
    another refresh won the race (the caller then re-reads and uses the winner's tokens)."""
    access = env.encrypt(access_token, _ctx("connection_secrets", "access_token_enc", connection_id))
    refresh = (
        env.encrypt(refresh_token, _ctx("connection_secrets", "refresh_token_enc", connection_id))
        if refresh_token
        else None
    )
    changed = conn.execute(
        "update public.connection_secrets set access_token_enc = %s, "
        "refresh_token_enc = coalesce(%s, refresh_token_enc), key_version = %s, "
        "updated_at = clock_timestamp() "
        "where connection_id = %s and updated_at = %s returning 1",
        (access, refresh, env.current_version, connection_id, expected_version),
    ).fetchone()
    return changed is not None
