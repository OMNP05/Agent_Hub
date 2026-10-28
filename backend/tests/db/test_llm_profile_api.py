"""LLM profile endpoints end to end: real signed JWTs, the real Postgres schema (rolled back), mocked providers.

Isolation: ownership is proved from the outside (user B gets 404 for user A's profile) and from the inside
(the key column holds ciphertext, the API never echoes the key).
"""

import os
import uuid

import httpx2
import pytest
from auth_helpers import ISSUER, CountingFetcher, TestKey, jwks_of
from fastapi.testclient import TestClient

from app.auth.jwt_verifier import JwtVerifier
from app.config import Settings
from app.crypto import Envelope, KeyRing, store
from app.db.access import SingleConnectionDatabase
from app.main import create_app

SECRET_KEY = "sk-live-SUPERSECRETKEYVALUE-9f3a"
SECRET_KEY_2 = "sk-live-ANOTHERSECRETKEY-77bc"


@pytest.fixture
def sign():
    return TestKey("kid-llm")


@pytest.fixture
def envelope():
    return Envelope(KeyRing(1, {1: os.urandom(32)}))


@pytest.fixture
def api(db, sign, envelope):
    """The app on top of the rolled-back test connection; two real users exist in auth.users."""
    users = {"alice": db.user(), "bob": db.user()}
    app = create_app(
        Settings(_env_file=None, environment="test"),
        verifier=JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(sign))),
        db=SingleConnectionDatabase(db.conn),
        envelope=envelope,
    )
    client = TestClient(app, raise_server_exceptions=False)

    class Api:
        def __init__(self):
            self.users = users
            self.db = db
            self.app = app
            self.client = client

        def headers(self, who="alice"):
            return {"Authorization": f"Bearer {sign.token(sub=str(users[who]))}"}

        def call(self, method, path, who="alice", **kw):
            return client.request(method, path, headers=self.headers(who), **kw)

    return Api()


def body(**over):
    base = {
        "name": "Work",
        "provider": "openai",
        "model": "gpt-test",
        "temperature": 0.3,
        "max_tokens": 800,
        "api_key": SECRET_KEY,
    }
    base.update(over)
    return base


def create(api, who="alice", **over):
    response = api.call("POST", "/llm-profiles", who, json=body(**over))
    assert response.status_code == 201, response.text
    return response.json()


# -- create / list -----------------------------------------------------------------------------------------


def test_create_returns_last4_and_never_the_key(api):
    p = create(api)
    assert p["key_last4"] == SECRET_KEY[-4:]
    assert set(p) == {
        "id", "name", "provider", "base_url", "model", "temperature", "max_tokens", "key_last4", "is_default",
    }  # fmt: skip
    assert "api_key" not in p and SECRET_KEY not in str(p)
    assert p["is_default"] is True  # the first profile is the default
    assert p["temperature"] == 0.3 and isinstance(p["temperature"], float)


def test_key_is_stored_encrypted_and_bound_to_the_profile(api, envelope):
    p = create(api)
    raw = bytes(
        api.db.one("select key_enc from public.llm_profile_secrets where profile_id = %s", p["id"])[0]
    )
    assert SECRET_KEY.encode() not in raw
    assert store.load_llm_key(api.db.conn, envelope, uuid.UUID(p["id"])) == SECRET_KEY


def test_list_shows_only_my_profiles_and_never_a_key(api):
    mine = create(api, name="Mine")
    create(api, "bob", name="Bobs", api_key=SECRET_KEY_2)
    response = api.call("GET", "/llm-profiles")
    assert [p["id"] for p in response.json()] == [mine["id"]]
    assert SECRET_KEY not in response.text and SECRET_KEY_2 not in response.text


def test_only_one_default_and_choosing_another_moves_it(api):
    first = create(api, name="One")
    second = create(api, name="Two", is_default=True)
    listing = {p["id"]: p["is_default"] for p in api.call("GET", "/llm-profiles").json()}
    assert listing == {first["id"]: False, second["id"]: True}
    third = create(api, name="Three")  # not asked to be default
    assert {p["name"]: p["is_default"] for p in api.call("GET", "/llm-profiles").json()}["Three"] is False
    assert third["is_default"] is False


def test_create_requires_a_key(api):
    for payload in (
        body(api_key=None),
        body(api_key=""),
        {k: v for k, v in body().items() if k != "api_key"},
    ):
        assert api.call("POST", "/llm-profiles", json=payload).status_code == 422


def test_per_user_profile_cap(api):
    for i in range(20):
        create(api, name=f"p{i}")
    response = api.call("POST", "/llm-profiles", json=body(name="one too many"))
    assert response.status_code == 409 and response.json() == {"error": {"code": "profile_limit_reached"}}
    create(api, "bob")  # another user is unaffected


# -- validation --------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "override",
    [
        {"name": ""}, {"name": "x" * 61}, {"model": ""}, {"model": "m" * 201},
        {"provider": "gemini"}, {"provider": "OpenAI"},
        {"temperature": -0.1}, {"temperature": 2.1}, {"temperature": "hot"},
        {"max_tokens": 0}, {"max_tokens": 200_001}, {"max_tokens": 1.5},
        {"api_key": "short"}, {"api_key": "has space in the key 123"}, {"api_key": "k" * 600},
        {"api_key": "line\nbreak-key-1234"},
        {"provider": "openai_compatible"},  # needs a base url
        {"base_url": "http://api.example.com/v1"}, {"base_url": "https://127.0.0.1/v1"},
        {"base_url": "https://169.254.169.254/latest"}, {"base_url": "https://localhost/v1"},
        {"base_url": "https://user:pw@api.example.com"}, {"base_url": "https://2130706433/"},
        {"user_id": str(uuid.uuid4())}, {"key_last4": "evil"}, {"id": str(uuid.uuid4())},
    ],
)  # fmt: skip
def test_invalid_input_is_rejected_with_a_uniform_error(api, override):
    response = api.call("POST", "/llm-profiles", json=body(**override))
    assert response.status_code == 422
    assert response.json() == {"error": {"code": "invalid_request"}}
    assert SECRET_KEY not in response.text  # validation errors never echo the input
    assert api.call("GET", "/llm-profiles").json() == []  # and nothing was stored


def test_a_custom_endpoint_is_accepted_and_trailing_slash_trimmed(api):
    p = create(api, provider="openai_compatible", base_url="https://gateway.company.com/llm/")
    assert p["base_url"] == "https://gateway.company.com/llm"


# -- ownership -----------------------------------------------------------------------------------------------


def test_another_users_profile_is_not_found_for_every_operation(api):
    alices = create(api)
    for method, path, kw in (
        ("PATCH", f"/llm-profiles/{alices['id']}", {"json": body(name="hijack")}),
        ("DELETE", f"/llm-profiles/{alices['id']}", {}),
        ("POST", f"/llm-profiles/{alices['id']}/test", {}),
    ):
        response = api.call(method, path, "bob", **kw)
        assert response.status_code == 404, (method, path)
        assert response.json() == {"error": {"code": "not_found"}}
    # untouched
    again = api.call("GET", "/llm-profiles").json()
    assert [p["name"] for p in again] == ["Work"]
    raw = bytes(
        api.db.one("select key_enc from public.llm_profile_secrets where profile_id = %s", alices["id"])[0]
    )
    assert raw  # the secret is still there


def test_nonexistent_and_foreign_profiles_look_identical(api):
    alices = create(api)
    foreign = api.call("DELETE", f"/llm-profiles/{alices['id']}", "bob")
    missing = api.call("DELETE", f"/llm-profiles/{uuid.uuid4()}", "bob")
    assert (foreign.status_code, foreign.json()) == (missing.status_code, missing.json())


def test_body_cannot_set_the_owner(api):
    response = api.call("POST", "/llm-profiles", json=body(user_id=str(api.users["bob"])))
    assert response.status_code == 422  # unknown field refused
    assert api.call("GET", "/llm-profiles", "bob").json() == []


def test_every_route_requires_a_token(api):
    pid = str(uuid.uuid4())
    for method, path in (
        ("GET", "/llm-profiles"),
        ("POST", "/llm-profiles"),
        ("POST", "/llm-profiles/test"),
        ("PATCH", f"/llm-profiles/{pid}"),
        ("DELETE", f"/llm-profiles/{pid}"),
        ("POST", f"/llm-profiles/{pid}/test"),
    ):
        response = api.client.request(method, path, json=body())
        assert response.status_code == 401 and response.json() == {"error": {"code": "unauthorized"}}, path


# -- update / delete -----------------------------------------------------------------------------------------


def test_update_without_a_key_keeps_the_stored_key(api, envelope):
    p = create(api)
    payload = body(name="Renamed", model="other")
    del payload["api_key"]
    updated = api.call("PATCH", f"/llm-profiles/{p['id']}", json=payload).json()
    assert updated["name"] == "Renamed" and updated["model"] == "other"
    assert updated["key_last4"] == SECRET_KEY[-4:]
    assert store.load_llm_key(api.db.conn, envelope, uuid.UUID(p["id"])) == SECRET_KEY


def test_update_with_a_key_replaces_it(api, envelope):
    p = create(api)
    updated = api.call("PATCH", f"/llm-profiles/{p['id']}", json=body(api_key=SECRET_KEY_2)).json()
    assert updated["key_last4"] == SECRET_KEY_2[-4:]
    assert store.load_llm_key(api.db.conn, envelope, uuid.UUID(p["id"])) == SECRET_KEY_2
    assert api.db.one("select count(*) from public.llm_profile_secrets")[0] >= 1


def test_blank_key_on_update_means_unchanged(api, envelope):
    p = create(api)
    api.call("PATCH", f"/llm-profiles/{p['id']}", json=body(api_key=""))
    assert store.load_llm_key(api.db.conn, envelope, uuid.UUID(p["id"])) == SECRET_KEY


def test_default_flag_rules_on_update(api):
    first, second = create(api, name="A"), create(api, name="B")
    # unticking "default" on the default profile does not leave the user with none
    kept = api.call("PATCH", f"/llm-profiles/{first['id']}", json=body(name="A", is_default=False)).json()
    assert kept["is_default"] is True
    # choosing another moves it
    api.call("PATCH", f"/llm-profiles/{second['id']}", json=body(name="B", is_default=True))
    flags = {p["name"]: p["is_default"] for p in api.call("GET", "/llm-profiles").json()}
    assert flags == {"A": False, "B": True}


def test_delete_removes_profile_and_secret_and_agents_fall_back(api):
    p = create(api)
    agent = api.db.agent(api.users["alice"])
    api.db.run("update public.agents set llm_profile_id = %s where id = %s", p["id"], agent)
    assert api.call("DELETE", f"/llm-profiles/{p['id']}").status_code == 204
    assert (
        api.db.one("select count(*) from public.llm_profile_secrets where profile_id = %s", p["id"])[0] == 0
    )
    assert api.db.one("select llm_profile_id from public.agents where id = %s", agent)[0] is None
    assert api.call("GET", "/llm-profiles").json() == []


# -- testing a key (provider mocked) ---------------------------------------------------------------------------


def mock_provider(monkeypatch, status=200, error=None, calls=None):
    """Replaces the guarded client with a mock transport so no request leaves the process."""

    def handler(request):
        if calls is not None:
            calls.append(request)
        if error:
            raise error
        return httpx2.Response(status, content=b'{"error":"Incorrect API key sk-live-SUPERSECRETKEYVALUE"}')

    monkeypatch.setattr(
        "app.llm.providers.make_safe_client",
        lambda *a, **k: httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )


def test_saved_profile_test_valid(api, monkeypatch):
    calls: list = []
    mock_provider(monkeypatch, 200, calls=calls)
    p = create(api)
    response = api.call("POST", f"/llm-profiles/{p['id']}/test")
    assert response.status_code == 200 and response.json() == {"ok": True}
    assert calls[0].headers["authorization"] == f"Bearer {SECRET_KEY}"  # the stored key was used, decrypted


def test_invalid_key_maps_to_llm_key_invalid_without_provider_text(api, monkeypatch):
    mock_provider(monkeypatch, 401)
    p = create(api)
    response = api.call("POST", f"/llm-profiles/{p['id']}/test")
    assert response.status_code == 400 and response.json() == {"error": {"code": "llm_key_invalid"}}
    assert "Incorrect API key" not in response.text and SECRET_KEY not in response.text


def test_provider_outage_is_ok_false_not_key_invalid(api, monkeypatch):
    mock_provider(monkeypatch, 503)
    p = create(api)
    assert api.call("POST", f"/llm-profiles/{p['id']}/test").json() == {"ok": False}


def test_draft_test_uses_the_form_values_and_stores_nothing(api, monkeypatch):
    calls: list = []
    mock_provider(monkeypatch, 200, calls=calls)
    response = api.call("POST", "/llm-profiles/test", json=body(api_key=SECRET_KEY_2))
    assert response.json() == {"ok": True}
    assert calls[0].headers["authorization"] == f"Bearer {SECRET_KEY_2}"
    assert api.call("GET", "/llm-profiles").json() == []
    assert (
        api.db.one("select count(*) from public.llm_profiles where user_id = %s", api.users["alice"])[0] == 0
    )


def test_draft_test_needs_a_key_and_valid_url(api, monkeypatch):
    mock_provider(monkeypatch, 200)
    assert api.call("POST", "/llm-profiles/test", json=body(api_key=None)).status_code == 422
    assert (
        api.call("POST", "/llm-profiles/test", json=body(base_url="https://10.0.0.1/v1")).status_code == 422
    )


def test_unreadable_stored_key_asks_the_user_to_re_enter_it(api, monkeypatch):
    mock_provider(monkeypatch, 200)
    p = create(api)
    api.app.state.envelope = Envelope(KeyRing(1, {1: os.urandom(32)}))  # a different master key
    response = api.call("POST", f"/llm-profiles/{p['id']}/test")
    assert response.status_code == 400 and response.json() == {"error": {"code": "llm_key_invalid"}}


def test_key_tests_are_rate_limited_per_user(api, monkeypatch):
    mock_provider(monkeypatch, 200)
    p = create(api)
    codes = [api.call("POST", f"/llm-profiles/{p['id']}/test").status_code for _ in range(12)]
    assert codes[:10] == [200] * 10 and codes[10:] == [429, 429]
    assert api.call("POST", "/llm-profiles/test", "bob", json=body()).status_code == 200  # others unaffected


def test_logs_never_contain_the_key(api, monkeypatch, caplog):
    import logging

    caplog.set_level(logging.DEBUG)
    mock_provider(monkeypatch, 401)
    p = create(api)
    api.call("POST", f"/llm-profiles/{p['id']}/test")
    api.call("PATCH", f"/llm-profiles/{p['id']}", json=body(api_key=SECRET_KEY_2))
    api.call("POST", "/llm-profiles", json=body(api_key="bad key"))
    assert "SUPERSECRET" not in caplog.text and "ANOTHERSECRET" not in caplog.text


def test_missing_encryption_or_database_fails_closed(db, sign):
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(sign)))
    uid = db.user()
    headers = {"Authorization": f"Bearer {sign.token(sub=str(uid))}"}
    no_env = TestClient(
        create_app(
            Settings(_env_file=None, environment="test"),
            verifier=verifier,
            db=SingleConnectionDatabase(db.conn),
        ),
        raise_server_exceptions=False,
    )
    assert no_env.post("/llm-profiles", headers=headers, json=body()).status_code == 503
    no_db = TestClient(
        create_app(Settings(_env_file=None, environment="test"), verifier=verifier),
        raise_server_exceptions=False,
    )
    assert no_db.get("/llm-profiles", headers=headers).status_code == 503
