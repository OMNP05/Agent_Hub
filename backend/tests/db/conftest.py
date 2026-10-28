"""Database tests run against the real Supabase Postgres, inside ONE transaction that is always rolled back.

Nothing is committed: the migration, the test users and every row exist only until the session ends. Each test
runs inside a savepoint that is rolled back, so tests cannot affect each other. Without a DATABASE_URL these
tests are skipped (CI has no database), and the skip is reported, not hidden.
"""

import json
import os
import uuid
from pathlib import Path

import psycopg
import pytest

ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


def _database_url() -> str:
    if os.environ.get("TEST_DATABASE_URL"):
        return os.environ["TEST_DATABASE_URL"]
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            if line.startswith("DATABASE_URL="):
                return line.split("=", 1)[1].strip()
    return ""


@pytest.fixture(scope="session")
def database_url() -> str:
    url = _database_url()
    if not url:
        pytest.skip("no DATABASE_URL: database tests skipped")
    return url


@pytest.fixture(scope="session")
def shared_conn(database_url):
    """One connection, one outer transaction, migrations applied once. Rolled back at the end of the session."""
    from app.db.migrate import apply_pending

    connection = psycopg.connect(database_url, connect_timeout=20, prepare_threshold=None, autocommit=False)
    try:
        apply_pending(connection)
        yield connection
    finally:
        connection.rollback()
        connection.close()


class Db:
    """Small helpers so tests read as scenarios."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self.conn = conn

    def user(self) -> uuid.UUID:
        uid = uuid.uuid4()
        self.conn.execute(
            "insert into auth.users (id, aud, role, email) values (%s, 'authenticated', 'authenticated', %s)",
            (uid, f"{uid}@test.invalid"),
        )
        return uid

    def one(self, sql: str, *params):
        return self.conn.execute(sql, params).fetchone()

    def all(self, sql: str, *params):
        return self.conn.execute(sql, params).fetchall()

    def run(self, sql: str, *params):
        return self.conn.execute(sql, params)

    def agent(self, user_id, template="github-assistant", connector="github", **extra):
        return self.one(
            "insert into public.agents (user_id, name, template_id, connector, status) "
            "values (%s, %s, %s, %s, %s) returning id",
            user_id,
            extra.get("name", "Agent"),
            template,
            connector,
            extra.get("status", "active"),
        )[0]

    def conversation(self, user_id, agent_id):
        return self.one(
            "insert into public.conversations (user_id, agent_id) values (%s, %s) returning id",
            user_id,
            agent_id,
        )[0]

    def run_row(self, user_id, agent_id, conversation_id, status="running"):
        finished = "now()" if status in ("completed", "failed", "cancelled", "expired") else "null"
        return self.one(
            "insert into public.runs (user_id, agent_id, conversation_id, trigger, status, finished_at) "
            f"values (%s, %s, %s, 'user', %s, {finished}) returning id",
            user_id,
            agent_id,
            conversation_id,
            status,
        )[0]

    def approval(self, user_id, agent_id, run_id, tool="gmail.send_email", args=None):
        args = args or {"to": ["a@example.com"], "subject": "Hi"}
        return self.one(
            "insert into public.approvals (user_id, agent_id, run_id, tool_call_id, tool, args, args_hash, expires_at) "
            "values (%s, %s, %s, %s, %s, %s, %s, now() + interval '1 day') returning id",
            user_id,
            agent_id,
            run_id,
            uuid.uuid4().hex,
            tool,
            json.dumps(args),
            "a" * 64,
        )[0]

    def as_user(self, user_id):
        """Switch this transaction to the app's `authenticated` role as [user_id], like a real API request."""
        self.conn.execute("set local role authenticated")
        claims = json.dumps({"sub": str(user_id), "role": "authenticated"})
        self.conn.execute("select set_config('request.jwt.claims', %s, true)", (claims,))

    def as_anon(self):
        self.conn.execute("set local role anon")
        self.conn.execute("select set_config('request.jwt.claims', '{}', true)")

    def as_backend(self):
        self.conn.execute("reset role")

    def expect_error(self, error_type, sql, *params):
        """Run [sql] and require [error_type]; the failed statement is undone so the test can continue."""
        self.conn.execute("savepoint expect")
        try:
            with pytest.raises(error_type):
                self.conn.execute(sql, params)
        finally:
            self.conn.execute("rollback to savepoint expect")
            self.conn.execute("release savepoint expect")


@pytest.fixture
def db(shared_conn) -> Db:
    """A fresh view of the database for one test: whatever the test does is rolled back afterwards."""
    shared_conn.execute("savepoint test")
    try:
        yield Db(shared_conn)
    finally:
        shared_conn.execute("rollback to savepoint test")
        shared_conn.execute("release savepoint test")


@pytest.fixture(scope="session")
def seeded(shared_conn):
    """Two users, each with a full chain of data, so every table has exactly one row per user. Created once."""
    d = Db(shared_conn)
    out = {}
    for name in ("alice", "bob"):
        user = d.user()
        profile = d.one(
            "insert into public.llm_profiles (user_id, name, provider, model) "
            "values (%s, 'p', 'openai', 'gpt') returning id",
            user,
        )[0]
        d.run(
            "insert into public.llm_profile_secrets (profile_id, key_enc) values (%s, %s)", profile, b"cipher"
        )
        agent = d.agent(user)
        d.run("update public.agents set llm_profile_id = %s where id = %s", profile, agent)
        connection = d.one(
            "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'github') returning id",
            agent,
            user,
        )[0]
        d.run(
            "insert into public.connection_secrets (connection_id, access_token_enc) values (%s, %s)",
            connection,
            b"tok",
        )
        d.run(
            "insert into public.oauth_states (state_hash, user_id, agent_id, connector, code_verifier_enc, expires_at) "
            "values (%s, %s, %s, 'github', %s, now() + interval '10 minutes')",
            ("0" if name == "alice" else "1") * 64,
            user,
            agent,
            b"verifier",
        )
        conversation = d.conversation(user, agent)
        run = d.run_row(user, agent, conversation)
        d.run(
            "insert into public.messages (conversation_id, user_id, run_id, seq, role, content) "
            "values (%s, %s, %s, 1, 'user', '[]')",
            conversation,
            user,
            run,
        )
        d.run(
            "insert into public.run_events (run_id, user_id, seq, type) values (%s, %s, 1, 'run.started')",
            run,
            user,
        )
        d.approval(user, agent, run)
        d.run(
            "insert into public.audit_logs (agent_id, user_id, tool) values (%s, %s, 'github.get_profile')",
            agent,
            user,
        )
        d.run("insert into public.devices (user_id, fcm_token) values (%s, %s)", user, name * 10)
        d.run(
            "insert into public.schedules (agent_id, user_id, cron, time_zone) "
            "values (%s, %s, '0 9 * * *', 'Asia/Kolkata')",
            agent,
            user,
        )
        out[name] = {
            "user": user,
            "agent": agent,
            "conversation": conversation,
            "run": run,
            "profile": profile,
            "connection": connection,
        }
    return out
