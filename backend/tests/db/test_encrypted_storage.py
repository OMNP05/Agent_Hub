"""Secrets reach the database only as ciphertext, are bound to their row, and survive key rotation."""

import os
import re
import uuid

import psycopg
import pytest

from app.crypto import DecryptionError, Envelope, KeyRing, store

API_KEY = "sk-test-0123456789abcdefghijklmnopqrstuv"
ACCESS = "gho_accessTokenValue0123456789"
REFRESH = "ghr_refreshTokenValue0123456789"


def make_env(*versions: int, current: int) -> Envelope:
    return Envelope(KeyRing(current, {v: os.urandom(32) for v in versions}))


def clear_secret_tables(db):
    """Other tests seed placeholder bytes; rotation walks every row, so start from empty (rolled back)."""
    for table in ("llm_profile_secrets", "connection_secrets", "oauth_states"):
        db.run(f"delete from public.{table}")


@pytest.fixture
def env():
    return make_env(1, current=1)


@pytest.fixture
def profile(db):
    user = db.user()
    return db.one(
        "insert into public.llm_profiles (user_id, name, provider, model) values (%s, 'p', 'openai', 'm') "
        "returning id",
        user,
    )[0]


@pytest.fixture
def connection(db):
    user = db.user()
    agent = db.agent(user)
    return (
        db.one(
            "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'github') returning id",
            agent,
            user,
        )[0],
        user,
        agent,
    )


def test_llm_key_round_trip_and_stored_as_ciphertext(db, env, profile):
    store.save_llm_key(db.conn, env, profile, API_KEY)
    raw = bytes(db.one("select key_enc from public.llm_profile_secrets where profile_id = %s", profile)[0])
    assert API_KEY.encode() not in raw
    assert db.one("select key_version from public.llm_profile_secrets where profile_id = %s", profile)[0] == 1
    assert store.load_llm_key(db.conn, env, profile) == API_KEY


def test_saving_again_replaces_the_key(db, env, profile):
    store.save_llm_key(db.conn, env, profile, API_KEY)
    store.save_llm_key(db.conn, env, profile, "sk-second-key-0123456789")
    assert store.load_llm_key(db.conn, env, profile) == "sk-second-key-0123456789"
    assert db.one("select count(*) from public.llm_profile_secrets where profile_id = %s", profile)[0] == 1


def test_missing_secret_returns_none(db, env, profile):
    assert store.load_llm_key(db.conn, env, profile) is None
    assert store.load_connection_tokens(db.conn, env, uuid.uuid4()) is None


def test_connection_tokens_round_trip_without_plaintext_in_db(db, env, connection):
    conn_id, _, _ = connection
    store.save_connection_tokens(db.conn, env, conn_id, ACCESS, REFRESH)
    row = db.one(
        "select access_token_enc, refresh_token_enc from public.connection_secrets where connection_id = %s",
        conn_id,
    )
    for blob in row:
        assert ACCESS.encode() not in bytes(blob) and REFRESH.encode() not in bytes(blob)
    assert store.load_connection_tokens(db.conn, env, conn_id) == (ACCESS, REFRESH)


def test_connection_without_refresh_token(db, env, connection):
    conn_id, _, _ = connection
    store.save_connection_tokens(db.conn, env, conn_id, ACCESS, None)
    assert store.load_connection_tokens(db.conn, env, conn_id) == (ACCESS, None)


def test_a_blob_copied_to_another_connection_cannot_be_read(db, env, connection):
    """Agent A's token pasted into agent B's row (a bug or an attacker with write access) must not decrypt."""
    a_conn, user, _ = connection
    b_agent = db.agent(user, template="gmail-assistant", connector="gmail")
    b_conn = db.one(
        "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'gmail') returning id",
        b_agent,
        user,
    )[0]
    store.save_connection_tokens(db.conn, env, a_conn, ACCESS, REFRESH)
    db.run(
        "insert into public.connection_secrets (connection_id, access_token_enc, refresh_token_enc) "
        "select %s, access_token_enc, refresh_token_enc from public.connection_secrets where connection_id = %s",
        b_conn,
        a_conn,
    )
    with pytest.raises(DecryptionError):
        store.load_connection_tokens(db.conn, env, b_conn)


def test_swapping_access_and_refresh_columns_fails(db, env, connection):
    conn_id, _, _ = connection
    store.save_connection_tokens(db.conn, env, conn_id, ACCESS, REFRESH)
    db.run(
        "update public.connection_secrets set access_token_enc = refresh_token_enc, "
        "refresh_token_enc = access_token_enc where connection_id = %s",
        conn_id,
    )
    with pytest.raises(DecryptionError):
        store.load_connection_tokens(db.conn, env, conn_id)


def test_code_verifier_round_trip_and_binding(env):
    state_hash = "ab" * 32
    blob = store.encrypt_code_verifier(env, state_hash, "verifier-123")
    assert b"verifier-123" not in blob
    assert store.decrypt_code_verifier(env, state_hash, blob) == "verifier-123"
    with pytest.raises(DecryptionError):
        store.decrypt_code_verifier(env, "cd" * 32, blob)  # another state's verifier


def test_code_verifier_stored_in_oauth_states(db, env, connection):
    _, user, agent = connection
    state_hash = "0a" * 32
    blob = store.encrypt_code_verifier(env, state_hash, "verifier-xyz")
    db.run(
        "insert into public.oauth_states (state_hash, user_id, agent_id, connector, code_verifier_enc, "
        "key_version, expires_at) values (%s, %s, %s, 'github', %s, %s, now() + interval '10 minutes')",
        state_hash,
        user,
        agent,
        blob,
        env.current_version,
    )
    stored = bytes(
        db.one("select code_verifier_enc from public.oauth_states where state_hash = %s", state_hash)[0]
    )
    assert store.decrypt_code_verifier(env, state_hash, stored) == "verifier-xyz"


def test_rotation_rewraps_everything_and_data_stays_readable(db, connection):
    conn_id, user, agent = connection
    clear_secret_tables(db)
    old_key = os.urandom(32)
    old = Envelope(KeyRing(1, {1: old_key}))
    profile = db.one(
        "insert into public.llm_profiles (user_id, name, provider, model) values (%s, 'p', 'openai', 'm') "
        "returning id",
        user,
    )[0]
    state_hash = "1f" * 32
    store.save_llm_key(db.conn, old, profile, API_KEY)
    store.save_connection_tokens(db.conn, old, conn_id, ACCESS, REFRESH)
    db.run(
        "insert into public.oauth_states (state_hash, user_id, agent_id, connector, code_verifier_enc, "
        "expires_at) values (%s, %s, %s, 'github', %s, now() + interval '10 minutes')",
        state_hash,
        user,
        agent,
        store.encrypt_code_verifier(old, state_hash, "ver"),
    )

    new_key = os.urandom(32)
    rotating = Envelope(KeyRing(2, {1: old_key, 2: new_key}))
    counts = store.rotate(db.conn, rotating)
    assert counts == {"llm_profile_secrets": 1, "connection_secrets": 1, "oauth_states": 1}

    # the old key is now gone: only the new key is needed
    new_only = Envelope(KeyRing(2, {2: new_key}))
    assert store.load_llm_key(db.conn, new_only, profile) == API_KEY
    assert store.load_connection_tokens(db.conn, new_only, conn_id) == (ACCESS, REFRESH)
    stored = bytes(
        db.one("select code_verifier_enc from public.oauth_states where state_hash = %s", state_hash)[0]
    )
    assert store.decrypt_code_verifier(new_only, state_hash, stored) == "ver"
    assert db.one("select key_version from public.llm_profile_secrets where profile_id = %s", profile)[0] == 2
    # a second rotation changes nothing for rows that are already current
    again = store.rotate(db.conn, rotating)
    assert again == {"llm_profile_secrets": 0, "connection_secrets": 0, "oauth_states": 0}


def test_rotation_fails_loudly_when_an_old_key_is_missing(db, env, connection):
    conn_id, _, _ = connection
    clear_secret_tables(db)
    store.save_connection_tokens(db.conn, env, conn_id, ACCESS, REFRESH)  # on key 1
    without_old = Envelope(KeyRing(2, {2: os.urandom(32)}))
    db.conn.execute("savepoint rot")
    with pytest.raises(DecryptionError):
        store.rotate(db.conn, without_old)
    db.conn.execute("rollback to savepoint rot")
    assert store.load_connection_tokens(db.conn, env, conn_id) == (ACCESS, REFRESH)  # untouched


def test_app_role_cannot_read_ciphertext(db, env, profile, connection):
    """RLS from B2: even the ciphertext is invisible to the signed-in user's role."""
    conn_id, user, _ = connection
    store.save_connection_tokens(db.conn, env, conn_id, ACCESS, REFRESH)
    db.as_user(user)
    db.conn.execute("savepoint s")
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        db.conn.execute("select access_token_enc from public.connection_secrets")
    db.conn.execute("rollback to savepoint s")
    db.as_backend()


def test_no_plaintext_secret_anywhere_in_the_secret_tables(db, env, profile, connection):
    """Greps every byte of every secret column for the known plaintexts."""
    conn_id, user, agent = connection
    store.save_llm_key(db.conn, env, profile, API_KEY)
    store.save_connection_tokens(db.conn, env, conn_id, ACCESS, REFRESH)
    state_hash = "2e" * 32
    db.run(
        "insert into public.oauth_states (state_hash, user_id, agent_id, connector, code_verifier_enc, "
        "expires_at) values (%s, %s, %s, 'github', %s, now() + interval '10 minutes')",
        state_hash,
        user,
        agent,
        store.encrypt_code_verifier(env, state_hash, "pkce-verifier-plain"),
    )
    needles = [API_KEY, ACCESS, REFRESH, "pkce-verifier-plain"]
    for table, _pk, columns in store.ENCRYPTED_COLUMNS:
        for column in columns:
            for (blob,) in db.all(f"select {column} from public.{table} where {column} is not null"):
                for needle in needles:
                    assert needle.encode() not in bytes(blob)
    # nor in any text column of the neighbouring tables
    text_cols = db.all(
        "select table_name, column_name from information_schema.columns where table_schema = 'public' "
        "and data_type in ('text','character varying') and table_name in ('connections','llm_profiles','agents')"
    )
    for table, column in text_cols:
        values = [v for (v,) in db.all(f'select "{column}" from public."{table}"') if v]
        assert not any(n in v for v in values for n in needles), (table, column)
    assert re.fullmatch(r"[0-9a-f]{64}", state_hash)
