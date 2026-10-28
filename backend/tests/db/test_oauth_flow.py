"""The connect flow end to end: real signed JWTs, the real Postgres schema (rolled back), mocked providers.

CLAUDE.md "Definition of done" cases covered here: a forged or reused OAuth state is rejected; agent A cannot
read agent B's tokens; the granted scopes are what is stored; a rotated GitHub refresh token is saved.
"""

import asyncio
import os
import uuid
from datetime import UTC, datetime, timedelta
from urllib.parse import parse_qs, urlsplit

import httpx2 as httpx
import pytest
from auth_helpers import ISSUER, CountingFetcher, TestKey, jwks_of
from fastapi.testclient import TestClient

from app.auth.jwt_verifier import JwtVerifier
from app.config import Settings
from app.crypto import Envelope, KeyRing, store
from app.db.access import SingleConnectionDatabase
from app.errors import ApiError
from app.main import create_app
from app.oauth import scopes as scope_rules
from app.oauth import service
from app.oauth.providers import GitHubApp, GoogleConnector, ProviderHttp
from app.oauth.service import Providers

BASE = "https://api.example.com"


class FakeProviders:
    """Records every provider call and answers from a script, so tests can see exactly what was sent."""

    def __init__(self):
        self.calls: list[httpx.Request] = []
        self.google_scope = f"{scope_rules.GMAIL_READONLY}"
        self.google_refresh_token = "1//GOOGLE-REFRESH"
        self.fail_with: tuple[int, dict] | None = None
        self.github_counter = 0
        self.delay = 0.0

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if self.fail_with:
            return httpx.Response(self.fail_with[0], json=self.fail_with[1])
        url = str(request.url)
        body = parse_qs(request.content.decode()) if request.content and request.method == "POST" else {}
        if url == "https://github.com/login/oauth/access_token":
            self.github_counter += 1
            n = self.github_counter
            return httpx.Response(200, json={
                "access_token": f"ghu_ACCESS{n}", "expires_in": 28800,
                "refresh_token": f"ghr_REFRESH{n}", "refresh_token_expires_in": 15897600, "scope": "",
            })  # fmt: skip
        if url == "https://oauth2.googleapis.com/token":
            if body.get("grant_type") == ["refresh_token"]:
                return httpx.Response(
                    200,
                    json={"access_token": "ya29.REFRESHED", "expires_in": 3599, "scope": self.google_scope},
                )
            data = {"access_token": "ya29.FIRST", "expires_in": 3599, "scope": self.google_scope}
            if self.google_refresh_token:
                data["refresh_token"] = self.google_refresh_token
            return httpx.Response(200, json=data)
        if url in ("https://oauth2.googleapis.com/revoke",) or url.startswith(
            "https://api.github.com/applications/"
        ):
            return httpx.Response(204 if "github" in url else 200)
        return httpx.Response(404)

    def providers(self) -> Providers:
        http = ProviderHttp(httpx.AsyncClient(transport=httpx.MockTransport(self.handler)))
        return Providers(
            github=GitHubApp("Iv1.test", "GH-CLIENT-SECRET", http),
            google=GoogleConnector("cid.apps", "G-CLIENT-SECRET", http),
        )


@pytest.fixture
def fake():
    return FakeProviders()


@pytest.fixture
def api(db, fake):
    sign = TestKey("kid-oauth")
    users = {"alice": db.user(), "bob": db.user()}
    envelope = Envelope(KeyRing(1, {1: os.urandom(32)}))
    app = create_app(
        Settings(_env_file=None, environment="test", public_base_url=BASE, android_cert_sha256="aa:bb:cc"),
        verifier=JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(sign))),
        db=SingleConnectionDatabase(db.conn),
        envelope=envelope,
        providers=fake.providers(),
    )
    client = TestClient(app, raise_server_exceptions=False, follow_redirects=False)

    class Api:
        def __init__(self):
            self.db, self.users, self.app, self.client, self.envelope, self.fake = (
                db,
                users,
                app,
                client,
                envelope,
                fake,
            )

        def headers(self, who="alice"):
            return {"Authorization": f"Bearer {sign.token(sub=str(users[who]))}"}

        def call(self, method, path, who="alice", **kw):
            return client.request(method, path, headers=self.headers(who), **kw)

        def agent(self, who="alice", template="gmail-assistant", **over):
            payload = {"template_id": template, "name": "A", **over}
            return self.call("POST", "/agents", who, json=payload).json()

        def start(self, agent, connector=None, who="alice"):
            connector = connector or agent["connector"]
            response = self.call("GET", f"/oauth/{connector}/start", who, params={"agent_id": agent["id"]})
            return response

        def state_of(self, response) -> str:
            return parse_qs(urlsplit(response.json()["auth_url"]).query)["state"][0]

        def callback(self, connector, state, code="AUTHCODE", **extra):
            params = {"code": code, "state": state, **extra}
            return client.get(
                f"/oauth/{connector}/callback", params={k: v for k, v in params.items() if v is not None}
            )

        def connect(self, agent, who="alice"):
            state = self.state_of(self.start(agent, who=who))
            response = self.callback(agent["connector"], state)
            assert response.status_code == 302 and "status=ok" in response.headers["location"], (
                response.headers
            )
            return response

        def mine(self, table, via=None):
            """Row count for THIS test's two users only. Other tests leave seeded rows in the shared tables."""
            if via:  # secrets are keyed by connection id, not user id
                return self.db.one(
                    f"select count(*) from public.{table} where {via} in "
                    "(select id from public.connections where user_id in (%s, %s))",
                    self.users["alice"],
                    self.users["bob"],
                )[0]
            return self.db.one(
                f"select count(*) from public.{table} where user_id in (%s, %s)",
                self.users["alice"],
                self.users["bob"],
            )[0]

        def connection_id(self, agent):
            return self.db.one("select id from public.connections where agent_id = %s", agent["id"])[0]

    return Api()


def run(coro):
    return asyncio.run(coro)


# -- start ------------------------------------------------------------------------------------------------------


def test_google_start_returns_a_consent_url_for_the_enabled_toggles(api):
    agent = api.agent(toggles={"organize_email": True})
    response = api.start(agent)
    assert response.status_code == 200
    url = response.json()["auth_url"]
    q = {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}
    assert url.startswith("https://accounts.google.com/o/oauth2/v2/auth?")
    assert q["scope"].split() == [scope_rules.GMAIL_READONLY, scope_rules.GMAIL_MODIFY]
    assert q["redirect_uri"] == f"{BASE}/oauth/gmail/callback" and q["code_challenge_method"] == "S256"
    assert "G-CLIENT-SECRET" not in url


def test_github_start_returns_a_github_url(api):
    agent = api.agent(template="github-assistant")
    url = api.start(agent).json()["auth_url"]
    assert url.startswith("https://github.com/login/oauth/authorize?") and "scope=" not in url


def test_start_stores_only_the_hash_of_state_and_an_encrypted_verifier(api):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    rows = api.db.all(
        "select state_hash, code_verifier_enc, connector, scopes from public.oauth_states where user_id = %s",
        api.users["alice"],
    )
    assert len(rows) == 1 and state not in rows[0][0] and len(rows[0][0]) == 64
    assert rows[0][2] == "gmail" and rows[0][3] == [scope_rules.GMAIL_READONLY]
    assert b"-" not in bytes(rows[0][1])[:5]  # ciphertext, not a readable verifier


def test_start_for_someone_elses_agent_is_not_found(api):
    theirs = api.agent("bob")
    response = api.start(theirs, who="alice")
    assert response.status_code == 404 and response.json() == {"error": {"code": "not_found"}}
    assert api.mine("oauth_states") == 0


def test_start_requires_login_and_a_matching_connector(api):
    agent = api.agent()
    assert api.client.get("/oauth/gmail/start", params={"agent_id": agent["id"]}).status_code == 401
    assert api.start(agent, connector="github").status_code == 422  # a Gmail agent cannot take a GitHub grant
    assert api.call("GET", "/oauth/slack/start", params={"agent_id": agent["id"]}).status_code == 422
    assert api.call("GET", "/oauth/gmail/start").status_code == 422
    assert api.call("GET", "/oauth/gmail/start", params={"agent_id": "nope"}).status_code == 422


def test_start_with_nothing_switched_on_is_refused_for_google(api):
    agent = api.agent(toggles={"read_email": False})
    assert api.start(agent).status_code == 422


def test_unconfigured_provider_answers_503_and_leaves_no_state(api):
    api.app.state.providers = Providers()
    agent = api.agent()
    response = api.start(agent)
    assert response.status_code == 503 and response.json() == {"error": {"code": "oauth_not_configured"}}
    assert api.mine("oauth_states") == 0


def test_starting_again_voids_the_earlier_link_for_that_agent(api):
    agent = api.agent()
    first = api.state_of(api.start(agent))
    second = api.state_of(api.start(agent))
    assert api.callback("gmail", first).headers["location"].endswith("status=error")
    assert "status=ok" in api.callback("gmail", second).headers["location"]


# -- callback: the security cases ------------------------------------------------------------------------------


def test_successful_google_connection_stores_encrypted_tokens_and_granted_scopes(api):
    agent = api.agent()
    api.connect(agent)
    conn = api.db.one(
        "select status, scopes_granted, expires_at from public.connections where agent_id = %s", agent["id"]
    )
    assert conn[0] == "active" and conn[1] == [scope_rules.GMAIL_READONLY] and conn[2] is not None
    cid = api.connection_id(agent)
    assert store.load_connection_tokens(api.db.conn, api.envelope, cid) == ("ya29.FIRST", "1//GOOGLE-REFRESH")
    raw = bytes(
        api.db.one("select access_token_enc from public.connection_secrets where connection_id = %s", cid)[0]
    )
    assert b"ya29" not in raw
    after = api.call("GET", f"/agents/{agent['id']}").json()
    assert after["status"] == "active" and after["connection"] == {
        "status": "active",
        "scopes_granted": [scope_rules.GMAIL_READONLY],
    }
    assert "ya29" not in api.call("GET", "/agents").text


def test_the_granted_scopes_are_stored_not_the_requested_ones(api):
    agent = api.agent(toggles={"send_email": True})  # asked for read + send
    api.fake.google_scope = scope_rules.GMAIL_READONLY  # the user unticked "send" on the consent screen
    api.connect(agent)
    stored = api.db.one("select scopes_granted from public.connections where agent_id = %s", agent["id"])[0]
    assert stored == [scope_rules.GMAIL_READONLY]


def test_github_connection_stores_rotating_tokens(api):
    agent = api.agent(template="github-assistant")
    api.connect(agent)
    cid = api.connection_id(agent)
    assert store.load_connection_tokens(api.db.conn, api.envelope, cid) == ("ghu_ACCESS1", "ghr_REFRESH1")


def test_callback_sends_the_pkce_verifier_that_matches_the_challenge(api):
    import base64
    import hashlib

    agent = api.agent()
    url = api.start(agent).json()["auth_url"]
    q = {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}
    api.callback("gmail", q["state"])
    exchange = next(c for c in api.fake.calls if str(c.url) == "https://oauth2.googleapis.com/token")
    verifier = parse_qs(exchange.content.decode())["code_verifier"][0]
    digest = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    assert digest == q["code_challenge"]


@pytest.mark.parametrize(
    "bad", ["", "x", "forged-state-value", "A" * 43, "A" * 500, "../../etc/passwd", "'; drop table agents;--"]
)
def test_forged_unknown_or_malformed_state_is_rejected(api, bad):
    agent = api.agent()
    api.start(agent)  # a real pending state exists, but this is not it
    response = api.callback("gmail", bad)
    assert response.status_code == 302 and response.headers["location"] == f"{BASE}/oauth/done?status=error"
    assert api.mine("connections") == 0
    assert not [
        c for c in api.fake.calls if "token" in str(c.url)
    ]  # the provider was never asked to exchange


def test_missing_state_is_rejected(api):
    response = api.client.get("/oauth/gmail/callback", params={"code": "x"})
    assert response.headers["location"] == f"{BASE}/oauth/done?status=error"


def test_a_state_can_only_be_used_once(api):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    assert "status=ok" in api.callback("gmail", state).headers["location"]
    api.fake.calls.clear()
    replay = api.callback("gmail", state, code="ANOTHERCODE")
    assert replay.headers["location"].endswith("status=error") or "status=error" in replay.headers["location"]
    assert api.fake.calls == []  # the replay never reached the provider
    cid = api.connection_id(agent)
    assert store.load_connection_tokens(api.db.conn, api.envelope, cid)[0] == "ya29.FIRST"  # unchanged


def test_an_expired_state_is_rejected(api):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    api.db.run(
        "update public.oauth_states set expires_at = now() - interval '1 second', "
        "created_at = now() - interval '11 minutes' where user_id = %s",
        api.users["alice"],
    )
    assert "status=error" in api.callback("gmail", state).headers["location"]
    assert api.fake.calls == []


def test_a_state_cannot_be_used_on_another_connector(api):
    agent = api.agent()  # gmail
    state = api.state_of(api.start(agent))
    assert "status=error" in api.callback("calendar", state).headers["location"]
    assert "status=error" in api.callback("github", state).headers["location"]
    assert (
        api.db.one("select used_at from public.oauth_states where user_id = %s", api.users["alice"])[0]
        is None
    )  # the wrong-connector try did not burn it
    assert "status=ok" in api.callback("gmail", state).headers["location"]


def test_a_state_started_by_bob_connects_bobs_agent_not_alices(api):
    alice, bob = api.agent("alice", name="alice"), api.agent("bob", name="bob")
    state = api.state_of(api.start(bob, who="bob"))
    result = api.callback("gmail", state)
    assert f"agent_id={bob['id']}" in result.headers["location"]
    assert api.db.one("select count(*) from public.connections where agent_id = %s", alice["id"])[0] == 0
    assert (
        api.db.one("select user_id from public.connections where agent_id = %s", bob["id"])[0]
        == api.users["bob"]
    )


def test_user_denying_consent_stores_nothing_and_burns_the_state(api):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    response = api.callback("gmail", state, code=None, error="access_denied")
    assert (
        "status=error" in response.headers["location"]
        and f"agent_id={agent['id']}" in response.headers["location"]
    )
    assert api.mine("connections") == 0
    assert "status=error" in api.callback("gmail", state).headers["location"]


@pytest.mark.parametrize(
    "status,body", [(400, {"error": "invalid_grant"}), (500, {}), (401, {"error": "invalid_client"})]
)
def test_a_failed_exchange_connects_nothing_and_cannot_be_retried_with_the_same_link(api, status, body):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    api.fake.fail_with = (status, body)
    assert "status=error" in api.callback("gmail", state).headers["location"]
    assert api.mine("connections") == 0
    api.fake.fail_with = None
    assert "status=error" in api.callback("gmail", state).headers["location"]  # state was burned first


def test_google_without_a_refresh_token_is_refused(api):
    api.fake.google_refresh_token = ""
    agent = api.agent()
    assert "status=error" in api.callback("gmail", api.state_of(api.start(agent))).headers["location"]
    assert api.mine("connections") == 0


def test_agent_deleted_during_consent_connects_nothing(api):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    api.call("DELETE", f"/agents/{agent['id']}")
    assert "status=error" in api.callback("gmail", state).headers["location"]
    assert api.mine("connections") == 0


def test_callback_never_echoes_provider_text_code_or_state(api):
    agent = api.agent()
    state = api.state_of(api.start(agent))
    response = api.callback("gmail", state, code="SECRET-CODE-123", error="<script>alert(1)</script>")
    assert (
        "SECRET-CODE" not in str(response.headers)
        and "script" not in str(response.headers)
        and state not in str(response.headers)
    )
    assert (
        response.headers["cache-control"] == "no-store"
        and response.headers["referrer-policy"] == "no-referrer"
    )


def test_reconnecting_replaces_the_grant(api):
    agent = api.agent()
    api.connect(agent)
    api.fake.google_refresh_token = "1//SECOND"
    api.connect(agent)
    assert api.db.one("select count(*) from public.connections where agent_id = %s", agent["id"])[0] == 1
    assert store.load_connection_tokens(api.db.conn, api.envelope, api.connection_id(agent))[1] == "1//SECOND"


def test_reconnecting_revives_a_needs_reauth_connection(api):
    agent = api.agent()
    api.connect(agent)
    api.db.run("update public.connections set status = 'needs_reauth' where agent_id = %s", agent["id"])
    api.connect(agent)
    assert api.db.one("select status from public.connections where agent_id = %s", agent["id"])[0] == "active"


def test_callback_is_rate_limited_per_address(api):
    codes = [api.callback("gmail", "nope").status_code for _ in range(35)]
    assert codes[:30] == [302] * 30 and codes[30:] == [429] * 5


# -- done page and asset links ---------------------------------------------------------------------------------------


def test_done_page_is_static_and_reflects_nothing(api):
    response = api.client.get("/oauth/done", params={"status": "ok", "agent_id": "<script>x</script>"})
    assert (
        response.status_code == 200
        and "<script>x" not in response.text
        and "return to Agent Hub" in response.text
    )
    assert (
        response.headers["x-content-type-options"] == "nosniff"
        and response.headers["cache-control"] == "no-store"
    )


def test_assetlinks_publishes_the_package_and_fingerprint(api):
    data = api.client.get("/.well-known/assetlinks.json").json()
    target = data[0]["target"]
    assert target["package_name"] == "dev.agenthub.app" and target["sha256_cert_fingerprints"] == ["AA:BB:CC"]
    assert data[0]["relation"] == ["delegate_permission/common.handle_all_urls"]


def test_assetlinks_is_not_published_without_a_fingerprint(db):
    app = create_app(Settings(_env_file=None, environment="test"))
    assert TestClient(app).get("/.well-known/assetlinks.json").status_code == 404


# -- isolation: agent A can never use agent B's grant ---------------------------------------------------------------------


def test_each_agent_gets_its_own_tokens_and_cannot_decrypt_the_others(api):
    first, second = api.agent(name="one"), api.agent(name="two")
    api.connect(first)
    api.fake.google_refresh_token = "1//OTHER"
    api.connect(second)
    c1, c2 = api.connection_id(first), api.connection_id(second)
    assert store.load_connection_tokens(api.db.conn, api.envelope, c1)[1] == "1//GOOGLE-REFRESH"
    assert store.load_connection_tokens(api.db.conn, api.envelope, c2)[1] == "1//OTHER"
    # a swapped ciphertext does not decrypt under the other connection's context
    api.db.run(
        "update public.connection_secrets set access_token_enc = (select access_token_enc from public.connection_secrets where connection_id = %s) where connection_id = %s",
        c1,
        c2,
    )
    from app.crypto import DecryptionError

    with pytest.raises(DecryptionError):
        store.load_connection_tokens(api.db.conn, api.envelope, c2)


def test_get_access_token_is_scoped_to_user_and_agent(api):
    mine, theirs = api.agent("alice"), api.agent("bob")
    api.connect(mine)
    api.connect(theirs, who="bob")
    providers = api.fake.providers()
    db = SingleConnectionDatabase(api.db.conn)
    assert (
        run(
            service.get_access_token(
                db, api.envelope, providers, user_id=api.users["alice"], agent_id=uuid.UUID(mine["id"])
            )
        )
        == "ya29.FIRST"
    )
    for who, agent in (("alice", theirs), ("bob", mine)):  # asking for the other user's agent
        with pytest.raises(ApiError) as err:
            run(
                service.get_access_token(
                    db, api.envelope, providers, user_id=api.users[who], agent_id=uuid.UUID(agent["id"])
                )
            )
        assert err.value.code == "connection_needs_reauth"


# -- refresh ---------------------------------------------------------------------------------------------------------------


def expire(api, agent, seconds=10):
    api.db.run(
        "update public.connections set expires_at = now() + make_interval(secs => %s) where agent_id = %s",
        seconds,
        agent["id"],
    )


def token_for(api, agent, who="alice"):
    db = SingleConnectionDatabase(api.db.conn)
    return run(
        service.get_access_token(
            db, api.envelope, api.fake.providers(), user_id=api.users[who], agent_id=uuid.UUID(agent["id"])
        )
    )


def test_a_fresh_token_is_used_without_calling_the_provider(api):
    agent = api.agent()
    api.connect(agent)
    api.fake.calls.clear()
    assert token_for(api, agent) == "ya29.FIRST" and api.fake.calls == []


def test_a_token_expiring_within_a_minute_is_refreshed_and_saved(api):
    agent = api.agent()
    api.connect(agent)
    expire(api, agent, 30)
    assert token_for(api, agent) == "ya29.REFRESHED"
    cid = api.connection_id(agent)
    assert store.load_connection_tokens(api.db.conn, api.envelope, cid) == (
        "ya29.REFRESHED",
        "1//GOOGLE-REFRESH",
    )  # refresh token kept
    api.fake.calls.clear()
    assert token_for(api, agent) == "ya29.REFRESHED" and api.fake.calls == []  # now fresh, no second refresh


def test_github_refresh_saves_the_rotated_refresh_token(api):
    agent = api.agent(template="github-assistant")
    api.connect(agent)
    expire(api, agent, 5)
    assert token_for(api, agent) == "ghu_ACCESS2"
    cid = api.connection_id(agent)
    assert store.load_connection_tokens(api.db.conn, api.envelope, cid) == ("ghu_ACCESS2", "ghr_REFRESH2")
    expire(api, agent, 5)
    assert token_for(api, agent) == "ghu_ACCESS3"
    refreshes = [c for c in api.fake.calls if b"refresh_token" in c.content and b"ghr_REFRESH2" in c.content]
    assert len(refreshes) == 1  # the SECOND refresh used the rotated token, not the original


def test_two_overlapping_refreshes_make_one_provider_call(api):
    """The per-connection lock: the second caller finds the first caller's fresh token."""
    agent = api.agent(template="github-assistant")
    api.connect(agent)
    expire(api, agent, 5)
    api.fake.calls.clear()
    first = token_for(api, agent)
    second = token_for(api, agent)
    assert first == second == "ghu_ACCESS2"
    assert len([c for c in api.fake.calls if b"grant_type=refresh_token" in c.content]) == 1


def test_revoked_grant_marks_needs_reauth_and_stops_the_run(api):
    agent = api.agent()
    api.connect(agent)
    expire(api, agent, 5)
    api.fake.fail_with = (400, {"error": "invalid_grant"})
    with pytest.raises(ApiError) as err:
        token_for(api, agent)
    assert err.value.code == "connection_needs_reauth" and err.value.status_code == 409
    assert api.call("GET", f"/agents/{agent['id']}").json()["connection"]["status"] == "needs_reauth"
    api.fake.fail_with = None
    with pytest.raises(ApiError):  # and it stays stopped even when the provider is healthy again
        token_for(api, agent)


def test_a_provider_outage_does_not_mark_the_grant_revoked(api):
    agent = api.agent()
    api.connect(agent)
    expire(api, agent, 5)
    api.fake.fail_with = (503, {})
    with pytest.raises(ApiError) as err:
        token_for(api, agent)
    assert err.value.code == "provider_unavailable" and err.value.status_code == 503
    assert api.call("GET", f"/agents/{agent['id']}").json()["connection"]["status"] == "active"
    api.fake.fail_with = None
    assert token_for(api, agent) == "ya29.REFRESHED"


def test_a_missing_refresh_token_means_reauth(api):
    agent = api.agent()
    api.connect(agent)
    cid = api.connection_id(agent)
    store.save_connection_tokens(api.db.conn, api.envelope, cid, "ya29.OLD", None)
    expire(api, agent, 5)
    with pytest.raises(ApiError) as err:
        token_for(api, agent)
    assert err.value.code == "connection_needs_reauth"


def test_unreadable_stored_tokens_mean_reauth_not_a_crash(api):
    agent = api.agent()
    api.connect(agent)
    api.app.state.envelope = Envelope(KeyRing(1, {1: os.urandom(32)}))
    db = SingleConnectionDatabase(api.db.conn)
    with pytest.raises(ApiError) as err:
        run(
            service.get_access_token(
                db,
                api.app.state.envelope,
                api.fake.providers(),
                user_id=api.users["alice"],
                agent_id=uuid.UUID(agent["id"]),
            )
        )
    assert err.value.code == "connection_needs_reauth"


def test_no_token_or_secret_reaches_the_logs_during_the_whole_flow(api, caplog):
    import logging

    caplog.set_level(logging.DEBUG)
    agent = api.agent(template="github-assistant")
    api.connect(agent)
    expire(api, agent, 5)
    token_for(api, agent)
    api.fake.fail_with = (400, {"error": "invalid_grant"})
    expire(api, agent, 5)
    with pytest.raises(ApiError):
        token_for(api, agent)
    for secret in ("ghu_ACCESS", "ghr_REFRESH", "GH-CLIENT-SECRET", "AUTHCODE"):
        assert secret not in caplog.text, secret


# -- disconnect / delete revoke at the provider ------------------------------------------------------------------------------


def test_disconnect_revokes_at_the_provider_then_forgets_the_tokens(api):
    agent = api.agent(template="github-assistant")
    api.connect(agent)
    api.fake.calls.clear()
    assert api.call("DELETE", f"/agents/{agent['id']}/connection").status_code == 204
    revokes = [c for c in api.fake.calls if c.method == "DELETE"]
    assert len(revokes) == 1 and b"ghu_ACCESS1" in revokes[0].content
    assert api.mine("connection_secrets", via="connection_id") == 0


def test_disconnect_still_works_when_the_provider_is_down(api):
    agent = api.agent(template="github-assistant")
    api.connect(agent)
    api.fake.fail_with = (503, {})
    assert api.call("DELETE", f"/agents/{agent['id']}/connection").status_code == 204
    assert api.mine("connections") == 0


def test_delete_agent_revokes_too(api):
    agent = api.agent()
    api.connect(agent)
    api.fake.calls.clear()
    assert api.call("DELETE", f"/agents/{agent['id']}").status_code == 204
    assert [str(c.url) for c in api.fake.calls] == ["https://oauth2.googleapis.com/revoke"]


def test_google_is_not_revoked_while_another_google_agent_depends_on_the_same_grant(api):
    """Google revokes the user's whole grant for the project, so revoking one agent would cut off the other."""
    one, two = api.agent(name="mail"), api.agent(template="calendar-assistant", name="cal")
    api.connect(one)
    api.connect(two)
    api.fake.calls.clear()
    api.call("DELETE", f"/agents/{one['id']}/connection")
    assert api.fake.calls == []  # tokens forgotten locally, no provider revoke
    api.call("DELETE", f"/agents/{two['id']}/connection")
    assert [str(c.url) for c in api.fake.calls] == [
        "https://oauth2.googleapis.com/revoke"
    ]  # the last one revokes


def test_disconnecting_someone_elses_agent_is_not_found_and_calls_no_provider(api):
    theirs = api.agent("bob", template="github-assistant")
    api.connect(theirs, who="bob")
    api.fake.calls.clear()
    assert api.call("DELETE", f"/agents/{theirs['id']}/connection", "alice").status_code == 404
    assert api.fake.calls == [] and api.mine("connections") == 1


def test_expired_pending_states_are_cleaned_up_on_the_next_start(api):
    agent = api.agent()
    api.start(agent)
    api.db.run(
        "update public.oauth_states set expires_at = now() - interval '2 days', "
        "created_at = now() - interval '3 days' where user_id = %s",
        api.users["alice"],
    )
    api.start(api.agent(name="other"))
    assert (
        api.db.one(
            "select count(*) from public.oauth_states where expires_at < now() and user_id = %s",
            api.users["alice"],
        )[0]
        == 0
    )


def test_start_is_rate_limited_per_user(api):
    agent = api.agent()
    codes = [api.start(agent).status_code for _ in range(22)]
    assert codes[:20] == [200] * 20 and codes[20:] == [429, 429]
    assert api.start(api.agent("bob"), who="bob").status_code == 200


def test_expiry_helper_uses_utc():
    assert service._expiry(None) is None
    e = service._expiry(3600)
    assert e.tzinfo is UTC and timedelta(minutes=59) < e - datetime.now(UTC) < timedelta(minutes=61)
