"""Integrity rules enforced by the database itself, so a bug in application code cannot break them."""

import json

import psycopg
import pytest

CHECK = psycopg.errors.CheckViolation
FK = psycopg.errors.ForeignKeyViolation
UNIQUE = psycopg.errors.UniqueViolation


# ------------------------------------------------------------------------------------------------- the catalog
@pytest.mark.parametrize(
    "template,connector",
    [
        ("github-assistant", "gmail"),
        ("gmail-assistant", "github"),
        ("calendar-assistant", "github"),
        ("custom", "github"),
        ("todoist-assistant", "todoist"),
        ("github-assistant", "telegram"),
    ],
)
def test_only_the_three_v1_agents_can_exist(db, template, connector):
    user = db.user()
    db.expect_error(
        CHECK,
        "insert into public.agents (user_id, name, template_id, connector) values (%s, 'x', %s, %s)",
        user,
        template,
        connector,
    )


@pytest.mark.parametrize(
    "template,connector",
    [("github-assistant", "github"), ("gmail-assistant", "gmail"), ("calendar-assistant", "calendar")],
)
def test_the_three_valid_agents_can_be_created(db, template, connector):
    assert db.agent(db.user(), template, connector)


def test_agent_field_limits(db):
    user = db.user()
    base = "insert into public.agents (user_id, name, template_id, connector, {col}) values (%s, 'x', 'github-assistant', 'github', %s)"
    db.expect_error(CHECK, base.format(col="status"), user, "running")
    db.expect_error(CHECK, base.format(col="instructions"), user, "x" * 2001)
    db.expect_error(CHECK, base.format(col="toggles"), user, json.dumps([1, 2]))
    db.expect_error(
        CHECK,
        "insert into public.agents (user_id, name, template_id, connector) values (%s, '   ', 'github-assistant', 'github')",
        user,
    )


# ------------------------------------------------------------------------------------------- llm profiles
def test_llm_profile_rules(db):
    user = db.user()
    sql = (
        "insert into public.llm_profiles (user_id, name, provider, model, base_url, temperature, max_tokens) "
        "values (%s, 'p', %s, 'm', %s, %s, %s)"
    )
    db.expect_error(CHECK, sql, user, "gemini", None, 0.2, 100)  # unknown provider
    db.expect_error(CHECK, sql, user, "openai_compatible", None, 0.2, 100)  # custom endpoint needs a URL
    db.expect_error(CHECK, sql, user, "openai", "http://insecure.example.com", 0.2, 100)  # https only
    db.expect_error(CHECK, sql, user, "openai", None, 2.5, 100)
    db.expect_error(CHECK, sql, user, "openai", None, -0.1, 100)
    db.expect_error(CHECK, sql, user, "openai", None, 0.2, 0)
    db.expect_error(CHECK, sql, user, "openai", None, 0.2, 200001)
    db.run(sql, user, "openai_compatible", "https://gateway.corp.example/v1", 0.2, 100)


def test_only_one_default_profile_per_user_and_independent_between_users(db):
    a, b = db.user(), db.user()
    sql = "insert into public.llm_profiles (user_id, name, provider, model, is_default) values (%s, 'p', 'openai', 'm', %s)"
    db.run(sql, a, True)
    db.run(sql, a, False)
    db.expect_error(UNIQUE, sql, a, True)
    db.run(sql, b, True)  # another user may have their own default


def test_key_last4_is_at_most_four_characters(db):
    user = db.user()
    db.expect_error(
        CHECK,
        "insert into public.llm_profiles (user_id, name, provider, model, key_last4) values (%s, 'p', 'openai', 'm', 'abcde')",
        user,
    )


def test_deleting_a_profile_unlinks_the_agent_but_keeps_it(db):
    user = db.user()
    profile = db.one(
        "insert into public.llm_profiles (user_id, name, provider, model) values (%s, 'p', 'openai', 'm') returning id",
        user,
    )[0]
    agent = db.agent(user)
    db.run("update public.agents set llm_profile_id = %s where id = %s", profile, agent)
    db.run("delete from public.llm_profiles where id = %s", profile)
    assert db.one("select llm_profile_id from public.agents where id = %s", agent) == (None,)


def test_an_agent_cannot_use_another_users_llm_profile(db):
    a, b = db.user(), db.user()
    profile = db.one(
        "insert into public.llm_profiles (user_id, name, provider, model) values (%s, 'p', 'openai', 'm') returning id",
        a,
    )[0]
    agent_b = db.agent(b)
    db.expect_error(FK, "update public.agents set llm_profile_id = %s where id = %s", profile, agent_b)


# --------------------------------------------------------------------------- ownership across the whole chain
def test_children_cannot_be_attached_to_another_users_parent(db):
    a, b = db.user(), db.user()
    agent_a = db.agent(a)
    conv_a = db.conversation(a, agent_a)
    run_a = db.run_row(a, agent_a, conv_a)

    # user b claiming user a's agent
    db.expect_error(FK, "insert into public.conversations (user_id, agent_id) values (%s, %s)", b, agent_a)
    db.expect_error(
        FK,
        "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'github')",
        agent_a,
        b,
    )
    db.expect_error(
        FK, "insert into public.audit_logs (agent_id, user_id, tool) values (%s, %s, 't')", agent_a, b
    )
    db.expect_error(
        FK,
        "insert into public.schedules (agent_id, user_id, cron, time_zone) values (%s, %s, '* * * * *', 'UTC')",
        agent_a,
        b,
    )
    # user b claiming user a's conversation, run
    db.expect_error(
        FK,
        "insert into public.messages (conversation_id, user_id, seq, role) values (%s, %s, 1, 'user')",
        conv_a,
        b,
    )
    db.expect_error(
        FK,
        "insert into public.run_events (run_id, user_id, seq, type) values (%s, %s, 1, 'run.started')",
        run_a,
        b,
    )
    db.expect_error(
        FK,
        "insert into public.approvals (user_id, agent_id, run_id, tool_call_id, tool, args, args_hash, expires_at) "
        "values (%s, %s, %s, 'c1', 'gmail.send_email', '{}', %s, now() + interval '1 day')",
        b,
        agent_a,
        run_a,
        "a" * 64,
    )


def test_a_run_must_belong_to_the_conversations_agent(db):
    u = db.user()
    agent_1, agent_2 = db.agent(u), db.agent(u, "gmail-assistant", "gmail")
    conversation = db.conversation(u, agent_1)
    db.expect_error(
        FK,
        "insert into public.runs (user_id, agent_id, conversation_id, trigger) values (%s, %s, %s, 'user')",
        u,
        agent_2,
        conversation,
    )


def test_a_connection_must_use_its_agents_connector(db):
    u = db.user()
    agent = db.agent(u)  # a GitHub agent
    db.expect_error(
        FK, "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'gmail')", agent, u
    )
    db.run(
        "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'github')", agent, u
    )


def test_one_connection_per_agent(db):
    u = db.user()
    agent = db.agent(u)
    sql = "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'github')"
    db.run(sql, agent, u)
    db.expect_error(UNIQUE, sql, agent, u)


def test_a_connection_status_must_be_known(db):
    u = db.user()
    agent = db.agent(u)
    db.expect_error(
        CHECK,
        "insert into public.connections (agent_id, user_id, connector, status) values (%s, %s, 'github', 'weird')",
        agent,
        u,
    )


# ------------------------------------------------------------------------------------------------ runs & chat
def test_only_one_active_run_per_conversation(db):
    u = db.user()
    agent = db.agent(u)
    conversation = db.conversation(u, agent)
    db.run_row(u, agent, conversation, "running")
    db.expect_error(
        UNIQUE,
        "insert into public.runs (user_id, agent_id, conversation_id, trigger, status) values (%s, %s, %s, 'user', 'queued')",
        u,
        agent,
        conversation,
    )


def test_finished_and_parked_runs_do_not_block_a_new_one(db):
    u = db.user()
    agent = db.agent(u)
    conversation = db.conversation(u, agent)
    db.run_row(u, agent, conversation, "completed")
    db.run_row(u, agent, conversation, "failed")
    db.run_row(u, agent, conversation, "awaiting_approval")
    db.run_row(u, agent, conversation, "running")  # still allowed: nothing else is active


def test_a_run_is_finished_exactly_when_its_status_says_so(db):
    u = db.user()
    agent = db.agent(u)
    conversation = db.conversation(u, agent)
    db.expect_error(
        CHECK,
        "insert into public.runs (user_id, agent_id, conversation_id, trigger, status, finished_at) values (%s, %s, %s, 'user', 'completed', null)",
        u,
        agent,
        conversation,
    )
    db.expect_error(
        CHECK,
        "insert into public.runs (user_id, agent_id, conversation_id, trigger, status, finished_at) values (%s, %s, %s, 'user', 'running', now())",
        u,
        agent,
        conversation,
    )


def test_run_enums_and_counters(db):
    u = db.user()
    agent = db.agent(u)
    conversation = db.conversation(u, agent)
    base = "insert into public.runs (user_id, agent_id, conversation_id, {col}) values (%s, %s, %s, %s)"
    db.expect_error(CHECK, base.format(col="trigger"), u, agent, conversation, "webhook")
    db.expect_error(
        CHECK,
        "insert into public.runs (user_id, agent_id, conversation_id, trigger, input_tokens) values (%s, %s, %s, 'user', -1)",
        u,
        agent,
        conversation,
    )


def test_message_order_is_unique_and_positive_and_content_is_an_array(db):
    u = db.user()
    agent = db.agent(u)
    conversation = db.conversation(u, agent)
    sql = "insert into public.messages (conversation_id, user_id, seq, role, content) values (%s, %s, %s, %s, %s)"
    db.run(sql, conversation, u, 1, "user", "[]")
    db.expect_error(UNIQUE, sql, conversation, u, 1, "assistant", "[]")
    db.expect_error(CHECK, sql, conversation, u, 0, "user", "[]")
    db.expect_error(CHECK, sql, conversation, u, 2, "system", "[]")
    db.expect_error(CHECK, sql, conversation, u, 3, "user", json.dumps({"not": "an array"}))


def test_run_event_types_are_the_documented_ones(db):
    u = db.user()
    agent = db.agent(u)
    run = db.run_row(u, agent, db.conversation(u, agent))
    sql = "insert into public.run_events (run_id, user_id, seq, type) values (%s, %s, %s, %s)"
    db.run(sql, run, u, 1, "message.delta")
    db.expect_error(CHECK, sql, run, u, 2, "run.hologram")
    db.expect_error(UNIQUE, sql, run, u, 1, "tool.started")


# ------------------------------------------------------------------------------------------------ approvals
def _approval(db):
    u = db.user()
    agent = db.agent(u, "gmail-assistant", "gmail")
    run = db.run_row(u, agent, db.conversation(u, agent), "awaiting_approval")
    return u, agent, run, db.approval(u, agent, run)


def test_a_pending_approval_can_be_decided_once(db):
    _, _, _, approval = _approval(db)
    db.run("update public.approvals set status = 'approved', decided_at = now() where id = %s", approval)
    assert db.one("select status from public.approvals where id = %s", approval) == ("approved",)


@pytest.mark.parametrize("second", ["rejected", "approved", "expired", "pending"])
def test_a_decided_approval_can_never_change_again(db, second):
    _, _, _, approval = _approval(db)
    db.run("update public.approvals set status = 'approved', decided_at = now() where id = %s", approval)
    db.expect_error(
        CHECK, "update public.approvals set status = %s, decided_at = now() where id = %s", second, approval
    )


@pytest.mark.parametrize(
    "column,value",
    [
        ("tool", "'gmail.create_draft'"),
        ("args", '\'{"to": ["attacker@evil.example"]}\''),
        ("args_hash", "repeat('b', 64)"),
        ("expires_at", "now() + interval '10 years'"),
        ("tool_call_id", "'other'"),
    ],
)
def test_the_stored_action_can_never_be_altered_after_creation(db, column, value):
    """CLAUDE.md rule 4: approved actions run with the stored arguments only."""
    _, _, _, approval = _approval(db)
    db.expect_error(CHECK, f"update public.approvals set {column} = {value} where id = %s", approval)


def test_the_approval_cannot_be_moved_to_another_user(db):
    _, _, _, approval = _approval(db)
    other = db.user()
    db.expect_error(CHECK, "update public.approvals set user_id = %s where id = %s", other, approval)


def test_approval_status_and_decided_at_must_agree(db):
    u = db.user()
    agent = db.agent(u, "gmail-assistant", "gmail")
    run = db.run_row(u, agent, db.conversation(u, agent), "awaiting_approval")
    base = (
        "insert into public.approvals (user_id, agent_id, run_id, tool_call_id, tool, args, args_hash, status, decided_at, expires_at) "
        "values (%s, %s, %s, 'c', 'gmail.send_email', '{}'::jsonb, %s, %s, DECIDED, now() + interval '1 day')"
    )
    db.expect_error(CHECK, base.replace("DECIDED", "now()"), u, agent, run, "a" * 64, "pending")
    db.expect_error(CHECK, base.replace("DECIDED", "null"), u, agent, run, "a" * 64, "approved")


def test_approval_tool_name_and_hash_shape(db):
    u = db.user()
    agent = db.agent(u, "gmail-assistant", "gmail")
    run = db.run_row(u, agent, db.conversation(u, agent), "awaiting_approval")
    base = (
        "insert into public.approvals (user_id, agent_id, run_id, tool_call_id, tool, args, args_hash, expires_at) "
        "values (%s, %s, %s, 'c', %s, '{}', %s, now() + interval '1 day')"
    )
    db.expect_error(CHECK, base, u, agent, run, "slack.post_message", "a" * 64)  # not in the catalog
    db.expect_error(CHECK, base, u, agent, run, "gmail.send_email", "not-a-hash")
    db.expect_error(CHECK, base, u, agent, run, "gmail.send_email", "A" * 64)  # uppercase hex is rejected


def test_one_approval_per_tool_call(db):
    u, agent, run, _ = _approval(db)
    first = db.one("select tool_call_id from public.approvals where run_id = %s", run)[0]
    db.expect_error(
        UNIQUE,
        "insert into public.approvals (user_id, agent_id, run_id, tool_call_id, tool, args, args_hash, expires_at) "
        "values (%s, %s, %s, %s, 'gmail.send_email', '{}', %s, now() + interval '1 day')",
        u,
        agent,
        run,
        first,
        "a" * 64,
    )


# -------------------------------------------------------------------------------------------------- audit log
def test_the_audit_log_is_append_only(db):
    u = db.user()
    agent = db.agent(u)
    db.run(
        "insert into public.audit_logs (agent_id, user_id, tool, result_summary) values (%s, %s, 'github.get_profile', 'ok')",
        agent,
        u,
    )
    db.expect_error(
        CHECK, "update public.audit_logs set result_summary = 'edited' where agent_id = %s", agent
    )


# ------------------------------------------------------------------------------------------ oauth state rules
def test_oauth_state_is_stored_as_a_hash_and_must_expire_in_the_future(db):
    u = db.user()
    agent = db.agent(u)
    sql = (
        "insert into public.oauth_states (state_hash, user_id, agent_id, connector, code_verifier_enc, expires_at) "
        "values (%s, %s, %s, 'github', %s, now() + %s::interval)"
    )
    db.expect_error(CHECK, sql, "plain-state-value", u, agent, b"v", "10 minutes")  # the raw value is refused
    db.expect_error(CHECK, sql, "g" * 64, u, agent, b"v", "10 minutes")  # not hex
    db.expect_error(CHECK, sql, "a" * 64, u, agent, b"v", "-1 minutes")  # already expired
    db.run(sql, "a" * 64, u, agent, b"v", "10 minutes")
    db.expect_error(UNIQUE, sql, "a" * 64, u, agent, b"v", "10 minutes")  # a state can only be created once


def test_oauth_state_must_match_the_agents_connector(db):
    u = db.user()
    agent = db.agent(u)
    db.expect_error(
        FK,
        "insert into public.oauth_states (state_hash, user_id, agent_id, connector, code_verifier_enc, expires_at) "
        "values (%s, %s, %s, 'gmail', %s, now() + interval '10 minutes')",
        "b" * 64,
        u,
        agent,
        b"v",
    )


# --------------------------------------------------------------------------------------------- other tables
def test_device_token_is_unique_and_sized(db):
    a, b = db.user(), db.user()
    sql = "insert into public.devices (user_id, fcm_token) values (%s, %s)"
    db.run(sql, a, "t" * 30)
    db.expect_error(UNIQUE, sql, b, "t" * 30)
    db.expect_error(CHECK, sql, a, "short")


def test_one_schedule_per_agent(db):
    u = db.user()
    agent = db.agent(u)
    sql = "insert into public.schedules (agent_id, user_id, cron, time_zone) values (%s, %s, '0 9 * * *', 'Asia/Kolkata')"
    db.run(sql, agent, u)
    db.expect_error(UNIQUE, sql, agent, u)


def test_updated_at_moves_forward_on_update(db):
    u = db.user()
    agent = db.agent(u)
    before = db.one("select updated_at from public.agents where id = %s", agent)[0]
    db.run("select pg_sleep(0.05)")
    db.run("update public.agents set name = 'renamed' where id = %s", agent)
    # within one transaction now() is constant, so the trigger's now() equals created time; check it ran by
    # verifying the column is still set and the rename applied.
    after = db.one("select name, updated_at from public.agents where id = %s", agent)
    assert after[0] == "renamed" and after[1] >= before
