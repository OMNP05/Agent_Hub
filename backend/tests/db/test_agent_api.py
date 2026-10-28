"""Agent endpoints end to end: real signed JWTs, the real Postgres schema (rolled back), no providers needed.

The security cases from CLAUDE.md that belong to this phase: a crafted request cannot enable anything outside
the catalog, a Dangerous tool can never be "allow", another user's agent is a 404, the kill switch pauses all.
"""

import os
import uuid

import pytest
from auth_helpers import ISSUER, CountingFetcher, TestKey, jwks_of
from fastapi.testclient import TestClient

from app.auth.jwt_verifier import JwtVerifier
from app.config import Settings
from app.crypto import Envelope, KeyRing, store
from app.db.access import SingleConnectionDatabase
from app.main import create_app


@pytest.fixture
def api(db):
    sign = TestKey("kid-agents")
    users = {"alice": db.user(), "bob": db.user()}
    envelope = Envelope(KeyRing(1, {1: os.urandom(32)}))
    app = create_app(
        Settings(_env_file=None, environment="test"),
        verifier=JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(sign))),
        db=SingleConnectionDatabase(db.conn),
        envelope=envelope,
    )
    client = TestClient(app, raise_server_exceptions=False)

    class Api:
        def __init__(self):
            self.db, self.users, self.app, self.client, self.envelope = db, users, app, client, envelope

        def headers(self, who="alice"):
            return {"Authorization": f"Bearer {sign.token(sub=str(users[who]))}"}

        def call(self, method, path, who="alice", **kw):
            return client.request(method, path, headers=self.headers(who), **kw)

        def create(self, who="alice", **over):
            payload = {"template_id": "gmail-assistant", "name": "Work Gmail"}
            payload.update(over)
            response = self.call("POST", "/agents", who, json=payload)
            assert response.status_code == 201, response.text
            return response.json()

        def profile(self, who="alice"):
            return self.db.one(
                "insert into public.llm_profiles (user_id, name, provider, model) values (%s,'p','openai','m') "
                "returning id",
                users[who],
            )[0]

    return Api()


# -- create ----------------------------------------------------------------------------------------------------


def test_create_uses_template_defaults_and_derives_the_connector(api):
    a = api.create(template_id="github-assistant", name="Code")
    assert a["connector"] == "github" and a["status"] == "draft" and a["connection"] is None
    assert a["toggles"] == {
        "read_prs": True, "read_followers": True, "read_profile": True,
        "edit_profile": False, "follow_unfollow": False,
    }  # fmt: skip
    assert set(a["approval_modes"].values()) == {"ask"}
    assert "Treat PR titles" in a["instructions"]  # the template's default instructions
    assert set(a) == {
        "id", "name", "template_id", "connector", "instructions", "status", "llm_profile_id", "time_zone",
        "toggles", "approval_modes", "connection", "last_activity_at",
    }  # fmt: skip


def test_create_keeps_valid_choices_and_custom_instructions(api):
    a = api.create(
        toggles={"draft_replies": True},
        approval_modes={"gmail.create_draft": "allow"},
        instructions="Be brief.\nSecond line.",
        time_zone="Asia/Kolkata",
    )
    assert a["toggles"]["draft_replies"] is True and a["approval_modes"]["gmail.create_draft"] == "allow"
    assert a["instructions"] == "Be brief.\nSecond line." and a["time_zone"] == "Asia/Kolkata"


def test_dangerous_tool_set_to_allow_is_forced_back_to_ask(api):
    a = api.create(
        toggles={"send_email": True},
        approval_modes={"gmail.send_email": "allow", "gmail.archive_email": "allow"},
    )
    assert a["approval_modes"]["gmail.send_email"] == "ask"
    assert a["approval_modes"]["gmail.archive_email"] == "allow"  # a Write tool may be allow
    stored = api.db.one("select approval_modes from public.agents where id = %s", a["id"])[0]
    assert stored["gmail.send_email"] == "ask"  # and the database agrees


@pytest.mark.parametrize(
    "override",
    [
        {"template_id": "slack-assistant"}, {"template_id": "custom"}, {"template_id": ""},
        {"template_id": "GITHUB-ASSISTANT"},
        {"toggles": {"made_up_toggle": True}},
        {"toggles": {"edit_profile": True}},  # a GitHub toggle on a Gmail agent
        {"toggles": {"send_email": "true"}}, {"toggles": {"send_email": 1}},
        {"approval_modes": {"github.update_profile": "allow"}},  # another template's tool
        {"approval_modes": {"gmail.search_emails": "ask"}},  # Read tools have no mode
        {"approval_modes": {"gmail.drop_database": "ask"}},  # not in the catalog
        {"approval_modes": {"gmail.send_email": "never"}},
        {"connector": "github"},  # derived, never accepted
        {"status": "active"}, {"user_id": str(uuid.uuid4())}, {"id": str(uuid.uuid4())},
        {"name": ""}, {"name": "x" * 61}, {"name": "bad\x00name"}, {"name": "two\nlines"},
        {"instructions": "x" * 2001}, {"instructions": "nul\x00"},
        {"time_zone": "Mars/Olympus"}, {"time_zone": "../../etc/passwd"}, {"time_zone": "x" * 65},
        {"llm_profile_id": "not-a-uuid"},
    ],
)  # fmt: skip
def test_crafted_create_requests_are_rejected_and_store_nothing(api, override):
    response = api.call("POST", "/agents", json={"template_id": "gmail-assistant", "name": "A", **override})
    assert response.status_code == 422 and response.json() == {"error": {"code": "invalid_request"}}
    assert api.call("GET", "/agents").json() == []


def test_a_foreign_or_missing_llm_profile_is_refused(api):
    bobs = api.profile("bob")
    for pid in (bobs, uuid.uuid4()):
        response = api.call(
            "POST",
            "/agents",
            json={"template_id": "gmail-assistant", "name": "A", "llm_profile_id": str(pid)},
        )
        assert response.status_code == 422
    mine = api.profile("alice")
    assert api.create(llm_profile_id=str(mine))["llm_profile_id"] == str(mine)


def test_per_user_agent_cap(api):
    for i in range(20):
        api.create(name=f"a{i}")
    response = api.call("POST", "/agents", json={"template_id": "gmail-assistant", "name": "extra"})
    assert response.status_code == 409 and response.json() == {"error": {"code": "agent_limit_reached"}}
    api.create("bob")


# -- list / get / ownership --------------------------------------------------------------------------------------


def test_list_and_get_only_show_my_agents(api):
    mine = api.create(name="Mine")
    theirs = api.create("bob", name="Theirs")
    assert [a["id"] for a in api.call("GET", "/agents").json()] == [mine["id"]]
    assert api.call("GET", f"/agents/{mine['id']}").json()["name"] == "Mine"
    foreign = api.call("GET", f"/agents/{theirs['id']}")
    missing = api.call("GET", f"/agents/{uuid.uuid4()}")
    assert foreign.status_code == 404 and (foreign.status_code, foreign.json()) == (
        missing.status_code,
        missing.json(),
    )


def test_every_operation_on_another_users_agent_is_not_found_and_changes_nothing(api):
    theirs = api.create("bob", name="Theirs")
    aid = theirs["id"]
    attempts = (
        ("PATCH", f"/agents/{aid}", {"json": {"name": "hijacked"}}),
        ("PATCH", f"/agents/{aid}", {"json": {"status": "paused"}}),
        ("DELETE", f"/agents/{aid}", {}),
        ("DELETE", f"/agents/{aid}/connection", {}),
    )
    for method, path, kw in attempts:
        response = api.call(method, path, "alice", **kw)
        assert response.status_code == 404 and response.json() == {"error": {"code": "not_found"}}, (
            method,
            path,
        )
    again = api.call("GET", f"/agents/{aid}", "bob").json()
    assert again["name"] == "Theirs" and again["status"] == "draft"


def test_the_owner_comes_from_the_token_never_the_request(api):
    response = api.call(
        "POST",
        "/agents",
        json={"template_id": "gmail-assistant", "name": "A", "user_id": str(api.users["bob"])},
    )
    assert response.status_code == 422
    api.create(name="A")
    assert api.call("GET", "/agents", "bob").json() == []


def test_every_route_requires_a_token(api):
    aid = str(uuid.uuid4())
    for method, path in (
        ("GET", "/agents"), ("POST", "/agents"), ("POST", "/agents/kill-switch"), ("GET", f"/agents/{aid}"),
        ("PATCH", f"/agents/{aid}"), ("DELETE", f"/agents/{aid}"), ("DELETE", f"/agents/{aid}/connection"),
        ("GET", "/agent-templates"),
    ):  # fmt: skip
        response = api.client.request(method, path, json={})
        assert response.status_code == 401 and response.json() == {"error": {"code": "unauthorized"}}, path


# -- update -------------------------------------------------------------------------------------------------------


def test_partial_update_changes_only_what_was_sent(api):
    a = api.create(toggles={"draft_replies": True}, approval_modes={"gmail.create_draft": "allow"})
    b = api.call("PATCH", f"/agents/{a['id']}", json={"name": "Renamed"}).json()
    assert b["name"] == "Renamed" and b["instructions"] == a["instructions"]
    assert b["toggles"] == a["toggles"] and b["approval_modes"] == a["approval_modes"]


def test_toggle_update_merges_with_stored_values(api):
    a = api.create(toggles={"draft_replies": True})
    b = api.call("PATCH", f"/agents/{a['id']}", json={"toggles": {"organize_email": True}}).json()
    assert b["toggles"]["draft_replies"] is True and b["toggles"]["organize_email"] is True


def test_update_cannot_enable_dangerous_allow(api):
    a = api.create()
    b = api.call(
        "PATCH",
        f"/agents/{a['id']}",
        json={"toggles": {"send_email": True}, "approval_modes": {"gmail.send_email": "allow"}},
    ).json()
    assert b["approval_modes"]["gmail.send_email"] == "ask" and b["toggles"]["send_email"] is True


@pytest.mark.parametrize(
    "patch",
    [
        {"toggles": {"made_up": True}}, {"approval_modes": {"gmail.search_emails": "ask"}},
        {"approval_modes": {"nope.tool": "ask"}}, {"status": "draft"}, {"status": "deleted"}, {"status": "ACTIVE"},
        {"connector": "github"}, {"template_id": "github-assistant"}, {"user_id": str(uuid.uuid4())},
        {"name": ""}, {"time_zone": "Nowhere/City"}, {"instructions": "x" * 2001},
    ],
)  # fmt: skip
def test_crafted_updates_are_rejected_and_change_nothing(api, patch):
    a = api.create()
    response = api.call("PATCH", f"/agents/{a['id']}", json=patch)
    assert response.status_code in (409, 422)
    assert api.call("GET", f"/agents/{a['id']}").json() == a


def test_explicit_nulls_do_not_wipe_required_fields(api):
    a = api.create()
    b = api.call(
        "PATCH", f"/agents/{a['id']}", json={"name": None, "instructions": None, "toggles": None}
    ).json()
    assert b == a


def test_llm_profile_can_be_set_and_cleared_but_only_to_my_own(api):
    a = api.create()
    mine, bobs = api.profile("alice"), api.profile("bob")
    assert api.call("PATCH", f"/agents/{a['id']}", json={"llm_profile_id": str(bobs)}).status_code == 422
    assert api.call("PATCH", f"/agents/{a['id']}", json={"llm_profile_id": str(mine)}).json()[
        "llm_profile_id"
    ] == str(mine)
    assert (
        api.call("PATCH", f"/agents/{a['id']}", json={"llm_profile_id": None}).json()["llm_profile_id"]
        is None
    )


def test_activating_requires_an_active_connection(api):
    a = api.create()
    response = api.call("PATCH", f"/agents/{a['id']}", json={"status": "active"})
    assert response.status_code == 409 and response.json() == {"error": {"code": "connection_needs_reauth"}}
    api.db.run(
        "insert into public.connections (agent_id, user_id, connector) values (%s, %s, 'gmail')",
        a["id"],
        api.users["alice"],
    )
    assert api.call("PATCH", f"/agents/{a['id']}", json={"status": "active"}).json()["status"] == "active"
    assert api.call("PATCH", f"/agents/{a['id']}", json={"status": "paused"}).json()["status"] == "paused"


def test_a_connection_that_needs_reauth_cannot_activate(api):
    a = api.create()
    api.db.run(
        "insert into public.connections (agent_id, user_id, connector, status) values (%s, %s, 'gmail', 'needs_reauth')",
        a["id"], api.users["alice"],
    )  # fmt: skip
    assert api.call("PATCH", f"/agents/{a['id']}", json={"status": "active"}).status_code == 409


def test_connection_state_is_reported_without_any_token(api):
    a = api.create()
    uid = api.users["alice"]
    cid = api.db.one(
        "insert into public.connections (agent_id, user_id, connector, scopes_granted) values (%s, %s, 'gmail', "
        "array['https://www.googleapis.com/auth/gmail.readonly']) returning id", a["id"], uid,
    )[0]  # fmt: skip
    store.save_connection_tokens(api.db.conn, api.envelope, cid, "ya29.SECRET-ACCESS", "1//SECRET-REFRESH")
    response = api.call("GET", f"/agents/{a['id']}")
    assert response.json()["connection"] == {
        "status": "active", "scopes_granted": ["https://www.googleapis.com/auth/gmail.readonly"],
    }  # fmt: skip
    assert "SECRET" not in response.text and "SECRET" not in api.call("GET", "/agents").text


# -- delete / disconnect / kill switch --------------------------------------------------------------------------------


def test_delete_cascades_to_everything_the_agent_owned(api):
    a = api.create()
    uid, aid = api.users["alice"], a["id"]
    cid = api.db.one(
        "insert into public.connections (agent_id, user_id, connector) values (%s,%s,'gmail') returning id",
        aid,
        uid,
    )[0]
    store.save_connection_tokens(api.db.conn, api.envelope, cid, "tok", "ref")
    conv = api.db.conversation(uid, aid)
    run = api.db.run_row(uid, aid, conv)
    api.db.approval(uid, aid, run)
    assert api.call("DELETE", f"/agents/{aid}").status_code == 204
    for table, column in (
        ("agents", "id"), ("connections", "agent_id"), ("conversations", "agent_id"), ("runs", "agent_id"),
        ("approvals", "agent_id"),
    ):  # fmt: skip
        assert api.db.one(f"select count(*) from public.{table} where {column} = %s", aid)[0] == 0, table
    assert api.db.one("select count(*) from public.connection_secrets where connection_id = %s", cid)[0] == 0
    assert api.call("GET", f"/agents/{aid}").status_code == 404


def test_deleting_one_agent_leaves_the_others_and_other_users_alone(api):
    keep, gone, bobs = api.create(name="keep"), api.create(name="gone"), api.create("bob", name="bobs")
    api.call("DELETE", f"/agents/{gone['id']}")
    assert [a["id"] for a in api.call("GET", "/agents").json()] == [keep["id"]]
    assert api.call("GET", "/agents", "bob").json()[0]["id"] == bobs["id"]


def test_disconnect_removes_the_grant_and_tokens_and_deactivates(api):
    a = api.create()
    uid = api.users["alice"]
    cid = api.db.one(
        "insert into public.connections (agent_id, user_id, connector) values (%s,%s,'gmail') returning id",
        a["id"],
        uid,
    )[0]
    store.save_connection_tokens(api.db.conn, api.envelope, cid, "tok", "ref")
    api.call("PATCH", f"/agents/{a['id']}", json={"status": "active"})
    assert api.call("DELETE", f"/agents/{a['id']}/connection").status_code == 204
    after = api.call("GET", f"/agents/{a['id']}").json()
    assert after["connection"] is None and after["status"] == "draft"
    assert api.db.one("select count(*) from public.connection_secrets where connection_id = %s", cid)[0] == 0


def test_kill_switch_pauses_all_my_agents_and_nobody_elses(api):
    uid = api.users["alice"]
    a1, a2 = api.create(name="one"), api.create(name="two")
    bobs = api.create("bob", name="bobs")
    for agent in (a1, a2):
        api.db.run(
            "insert into public.connections (agent_id, user_id, connector) values (%s,%s,'gmail')",
            agent["id"],
            uid,
        )
        api.call("PATCH", f"/agents/{agent['id']}", json={"status": "active"})
    api.db.run(
        "insert into public.connections (agent_id, user_id, connector) values (%s,%s,'gmail')",
        bobs["id"],
        api.users["bob"],
    )
    api.call("PATCH", f"/agents/{bobs['id']}", "bob", json={"status": "active"})

    assert api.call("POST", "/agents/kill-switch").status_code == 204
    assert {a["status"] for a in api.call("GET", "/agents").json()} == {"paused"}
    assert api.call("GET", f"/agents/{bobs['id']}", "bob").json()["status"] == "active"
    assert api.call("POST", "/agents/kill-switch").status_code == 204  # safe to press twice


def test_kill_switch_is_not_mistaken_for_an_agent_id(api):
    assert api.call("POST", "/agents/kill-switch").status_code == 204
    assert api.call("GET", "/agents/kill-switch").status_code == 422  # a GET is read as an (invalid) id


def test_deleting_an_llm_profile_leaves_the_agent_without_one(api):
    profile = api.profile()
    a = api.create(llm_profile_id=str(profile))
    api.call("DELETE", f"/llm-profiles/{profile}")
    assert api.call("GET", f"/agents/{a['id']}").json()["llm_profile_id"] is None


# -- the catalog endpoint -----------------------------------------------------------------------------------------


def test_templates_endpoint_lists_exactly_the_three_agents(api):
    data = api.call("GET", "/agent-templates").json()
    assert [t["id"] for t in data] == ["github-assistant", "gmail-assistant", "calendar-assistant"]
    tools = {tool["id"]: tool["risk"] for t in data for g in t["toggles"] for tool in g["tools"]}
    assert tools["gmail.send_email"] == "dangerous" and tools["gmail.search_emails"] == "read"
    assert len(tools) == 26


def test_missing_database_fails_closed(db):
    sign = TestKey("k")
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(sign)))
    uid = db.user()
    client = TestClient(
        create_app(Settings(_env_file=None, environment="test"), verifier=verifier),
        raise_server_exceptions=False,
    )
    assert (
        client.get("/agents", headers={"Authorization": f"Bearer {sign.token(sub=str(uid))}"}).status_code
        == 503
    )
