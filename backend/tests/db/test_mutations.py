"""Mutation checks: break the schema on purpose, in a rolled-back transaction, and prove the safety nets notice.

A guard that cannot fail protects nothing. Each test here weakens one protection and asserts the matching check
(the same SQL the real tests use) detects it.
"""

import pytest


def test_dropping_rls_on_a_table_is_detected(db):
    db.run("alter table public.devices disable row level security")
    assert (
        dict(
            db.all(
                "select c.relname, c.relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                "where n.nspname = 'public' and c.relkind = 'r'"
            )
        )["devices"]
        is False
    )


def test_a_policy_that_lets_users_see_everyones_rows_is_detected(db, seeded):
    db.as_backend()
    db.run("create policy leak on public.agents for select to authenticated using (true)")
    db.as_user(seeded["alice"]["user"])
    # with the leaky policy added, alice sees bob's agent: exactly what test_a_user_sees_only_their_own_rows forbids
    assert db.one("select count(*) from public.agents")[0] == 2


def test_granting_write_access_to_the_app_role_is_detected(db, seeded):
    db.as_backend()
    db.run("grant insert on public.agents to authenticated")
    granted = {
        p
        for _, p in db.all(
            "select table_name, privilege_type from information_schema.role_table_grants "
            "where table_schema = 'public' and grantee = 'authenticated'"
        )
    }
    assert granted != {"SELECT"}


def test_an_unlocked_secret_table_is_detected(db, seeded):
    db.as_backend()
    db.run("grant select on public.connection_secrets to authenticated")
    db.run("create policy open on public.connection_secrets for select to authenticated using (true)")
    db.as_user(seeded["alice"]["user"])
    assert db.one("select count(*) from public.connection_secrets")[0] == 2  # both users' ciphertext readable


def test_removing_the_approval_guard_would_allow_the_stored_action_to_change(db):
    import json

    u = db.user()
    agent = db.agent(u, "gmail-assistant", "gmail")
    run = db.run_row(u, agent, db.conversation(u, agent), "awaiting_approval")
    approval = db.approval(u, agent, run)
    db.run("drop trigger approvals_guard on public.approvals")
    db.run(
        "update public.approvals set args = %s where id = %s",
        json.dumps({"to": ["attacker@evil.example"]}),
        approval,
    )
    assert db.one("select args from public.approvals where id = %s", approval)[0] == {
        "to": ["attacker@evil.example"]
    }


def test_removing_the_one_active_run_index_would_allow_two_concurrent_runs(db):
    u = db.user()
    agent = db.agent(u)
    conversation = db.conversation(u, agent)
    db.run("drop index public.runs_one_active_per_conversation")
    db.run_row(u, agent, conversation, "running")
    db.run_row(u, agent, conversation, "running")  # now allowed, which is exactly the hole the index closes
    assert db.one("select count(*) from public.runs where conversation_id = %s", conversation)[0] == 2


@pytest.mark.parametrize("function", ["set_updated_at", "approvals_guard", "audit_logs_no_update"])
def test_helper_functions_are_not_callable_by_the_app_roles(db, function):
    rows = db.all(
        "select grantee from information_schema.routine_privileges "
        "where routine_schema = 'public' and routine_name = %s and grantee in ('anon', 'authenticated', 'PUBLIC')",
        function,
    )
    assert rows == []
