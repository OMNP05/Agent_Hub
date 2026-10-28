"""Deletes: deleting a parent removes the whole tree below it, and never anything belonging to anyone else."""

import pytest

OWNED = [
    "llm_profiles",
    "llm_profile_secrets",
    "agents",
    "connections",
    "connection_secrets",
    "oauth_states",
    "conversations",
    "runs",
    "messages",
    "run_events",
    "approvals",
    "audit_logs",
    "devices",
    "schedules",
]


def _counts(db):
    return {t: db.one(f"select count(*) from public.{t}")[0] for t in OWNED}


def test_account_deletion_removes_every_row_the_user_owns_and_only_theirs(db, seeded):
    db.as_backend()
    alice, bob = seeded["alice"]["user"], seeded["bob"]["user"]
    before = _counts(db)

    db.run("delete from auth.users where id = %s", alice)

    after = _counts(db)
    for table in OWNED:
        # exactly one row of each kind belonged to alice, so each table loses exactly one row
        assert after[table] == before[table] - 1, (
            f"{table}: expected one row removed, was {before[table]} now {after[table]}"
        )
    assert db.one("select count(*) from public.agents where user_id = %s", bob)[0] == 1
    assert (
        db.one(
            "select count(*) from public.connection_secrets where connection_id = %s",
            seeded["bob"]["connection"],
        )[0]
        == 1
    )


def test_deleting_an_agent_removes_its_chats_runs_approvals_logs_connection_and_schedule(db, seeded):
    db.as_backend()
    agent = seeded["alice"]["agent"]
    db.run("delete from public.agents where id = %s", agent)
    for table, column in [
        ("connections", "agent_id"),
        ("conversations", "agent_id"),
        ("runs", "agent_id"),
        ("approvals", "agent_id"),
        ("audit_logs", "agent_id"),
        ("schedules", "agent_id"),
        ("oauth_states", "agent_id"),
    ]:
        assert db.one(f"select count(*) from public.{table} where {column} = %s", agent)[0] == 0, table
    assert (
        db.one(
            "select count(*) from public.connection_secrets where connection_id = %s",
            seeded["alice"]["connection"],
        )[0]
        == 0
    )
    assert (
        db.one(
            "select count(*) from public.messages where conversation_id = %s", seeded["alice"]["conversation"]
        )[0]
        == 0
    )
    assert db.one("select count(*) from public.run_events where run_id = %s", seeded["alice"]["run"])[0] == 0
    # the user, their profile and the other user's agent survive
    assert (
        db.one("select count(*) from public.llm_profiles where user_id = %s", seeded["alice"]["user"])[0] == 1
    )
    assert db.one("select count(*) from public.agents where id = %s", seeded["bob"]["agent"])[0] == 1


def test_deleting_a_conversation_removes_its_messages_and_runs_but_not_the_agent(db, seeded):
    db.as_backend()
    conversation = seeded["alice"]["conversation"]
    db.run("delete from public.conversations where id = %s", conversation)
    assert db.one("select count(*) from public.messages where conversation_id = %s", conversation)[0] == 0
    assert db.one("select count(*) from public.runs where conversation_id = %s", conversation)[0] == 0
    assert db.one("select count(*) from public.agents where id = %s", seeded["alice"]["agent"])[0] == 1


def test_deleting_a_profile_removes_its_secret(db, seeded):
    db.as_backend()
    profile = seeded["alice"]["profile"]
    db.run("delete from public.llm_profiles where id = %s", profile)
    assert db.one("select count(*) from public.llm_profile_secrets where profile_id = %s", profile)[0] == 0


@pytest.mark.parametrize("table", ["llm_profile_secrets", "connection_secrets"])
def test_secrets_cannot_exist_without_their_owner_row(db, table):
    import psycopg

    column = "profile_id" if table == "llm_profile_secrets" else "connection_id"
    value = "key_enc" if table == "llm_profile_secrets" else "access_token_enc"
    import uuid

    db.expect_error(
        psycopg.errors.ForeignKeyViolation,
        f"insert into public.{table} ({column}, {value}) values (%s, %s)",
        uuid.uuid4(),
        b"x",
    )
