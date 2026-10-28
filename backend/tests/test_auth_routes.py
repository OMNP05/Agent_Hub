import json
import time
import uuid

import httpx
import pytest
import respx
from auth_helpers import ISSUER, CountingFetcher, TestKey, jwks_of
from fastapi.testclient import TestClient

from app.auth.jwt_verifier import JwtVerifier
from app.config import Settings
from app.main import create_app

SUPABASE = "https://testproject.supabase.co"
GOOGLE_BODY = {"id_token": "g" * 40, "nonce": "nonce-1234567890"}


@pytest.fixture
def key() -> TestKey:
    return TestKey("kid-1")


@pytest.fixture
def client(key):
    settings = Settings(
        _env_file=None,
        environment="test",
        supabase_url=SUPABASE,
        supabase_publishable_key="sb_publishable_test_key_0123",
    )
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(key)))
    return TestClient(create_app(settings, verifier=verifier), raise_server_exceptions=False)


def bearer(key: TestKey, **claims) -> dict[str, str]:
    return {"Authorization": f"Bearer {key.token(**claims)}"}


SESSION = {
    "access_token": "acc-token-value",
    "refresh_token": "ref-token-value",
    "expires_in": 3600,
    "token_type": "bearer",
    "user": {"id": "x"},
}


# ---------------------------------------------------------------------------------------------------- /me
def test_me_returns_the_id_from_the_verified_token_only(client, key):
    user = uuid.uuid4()
    response = client.get("/me", headers=bearer(key, sub=str(user)))
    assert response.status_code == 200
    assert response.json() == {"id": str(user)}


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": ""},
        {"Authorization": "Bearer"},
        {"Authorization": "Bearer "},
        {"Authorization": "Basic abc"},
        {"Authorization": "Bearer a b"},
        {"Authorization": "Bearer not.a.jwt"},
        {"Authorization": "token abc.def.ghi"},
    ],
)
def test_missing_or_malformed_credentials_are_401(client, headers):
    response = client.get("/me", headers=headers)
    assert response.status_code == 401
    assert response.json() == {"error": {"code": "unauthorized"}}


def test_an_expired_token_is_401(client, key):
    assert client.get("/me", headers=bearer(key, exp=int(time.time()) - 3600)).status_code == 401


def test_a_token_signed_by_someone_else_is_401(client):
    assert client.get("/me", headers=bearer(TestKey("kid-1"))).status_code == 401


def test_a_client_supplied_user_id_is_ignored(client, key):
    user = uuid.uuid4()
    other = uuid.uuid4()
    response = client.get(
        f"/me?user_id={other}", headers={**bearer(key, sub=str(user)), "X-User-Id": str(other)}
    )
    assert response.json() == {"id": str(user)}


def test_no_verifier_configured_fails_closed():
    app = create_app(Settings(_env_file=None, environment="test"))
    response = TestClient(app, raise_server_exceptions=False).get(
        "/me", headers={"Authorization": "Bearer abc.def.ghi"}
    )
    assert response.status_code == 503
    assert response.json() == {"error": {"code": "auth_unavailable"}}


def test_the_401_does_not_reveal_why(client, key):
    expired = client.get("/me", headers=bearer(key, exp=int(time.time()) - 3600)).text
    forged = client.get("/me", headers=bearer(TestKey("kid-1"))).text
    assert expired == forged


# -------------------------------------------------------------------------------------------------- sign in
@respx.mock
def test_google_sign_in_forwards_the_token_and_nonce_and_returns_a_session(client):
    route = respx.post(f"{SUPABASE}/auth/v1/token", params={"grant_type": "id_token"}).mock(
        return_value=httpx.Response(200, json=SESSION)
    )
    response = client.post("/auth/google", json=GOOGLE_BODY)
    assert response.status_code == 200
    assert response.json() == {
        "access_token": "acc-token-value",
        "refresh_token": "ref-token-value",
        "expires_in": 3600,
    }
    sent = json.loads(route.calls.last.request.content)
    assert sent == {"provider": "google", "id_token": GOOGLE_BODY["id_token"], "nonce": GOOGLE_BODY["nonce"]}
    assert route.calls.last.request.headers["apikey"] == "sb_publishable_test_key_0123"
    assert "user" not in response.json(), "the Supabase user object is not passed through"


@respx.mock
@pytest.mark.parametrize("upstream", [400, 401, 403, 422])
def test_a_rejected_google_token_is_401(client, upstream):
    respx.post(f"{SUPABASE}/auth/v1/token").mock(
        return_value=httpx.Response(upstream, json={"msg": "Nonces mismatch", "detail": "secret-ish"})
    )
    response = client.post("/auth/google", json=GOOGLE_BODY)
    assert response.status_code == 401
    assert response.json() == {"error": {"code": "unauthorized"}}
    assert "Nonces" not in response.text and "secret-ish" not in response.text


@respx.mock
@pytest.mark.parametrize("upstream", [429, 500, 502, 503])
def test_supabase_outages_are_503(client, upstream):
    respx.post(f"{SUPABASE}/auth/v1/token").mock(
        return_value=httpx.Response(upstream, text="<html>bad gateway</html>")
    )
    response = client.post("/auth/google", json=GOOGLE_BODY)
    assert response.status_code == 503
    assert response.json() == {"error": {"code": "auth_unavailable"}}


@respx.mock
def test_a_network_failure_to_supabase_is_503(client):
    respx.post(f"{SUPABASE}/auth/v1/token").mock(side_effect=httpx.ConnectError("boom with password=hunter2"))
    response = client.post("/auth/google", json=GOOGLE_BODY)
    assert response.status_code == 503
    assert "hunter2" not in response.text


@respx.mock
def test_a_garbled_supabase_answer_is_503_not_a_crash(client):
    respx.post(f"{SUPABASE}/auth/v1/token").mock(return_value=httpx.Response(200, json={"unexpected": True}))
    assert client.post("/auth/google", json=GOOGLE_BODY).status_code == 503


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"id_token": "short", "nonce": "nonce-1234567890"},
        {"id_token": "g" * 40},
        {"id_token": "g" * 40, "nonce": "n"},
        {**GOOGLE_BODY, "user_id": str(uuid.uuid4())},
        {**GOOGLE_BODY, "extra": "field"},
        {"id_token": "g" * 9000, "nonce": "nonce-1234567890"},
    ],
)
def test_sign_in_bodies_are_validated_strictly(client, body):
    response = client.post("/auth/google", json=body)
    assert response.status_code == 422
    assert response.json() == {"error": {"code": "invalid_request"}}


@respx.mock
def test_the_google_token_never_appears_in_logs(client, caplog):
    import logging

    caplog.set_level(logging.DEBUG)
    respx.post(f"{SUPABASE}/auth/v1/token").mock(return_value=httpx.Response(200, json=SESSION))
    client.post(
        "/auth/google",
        json={"id_token": "eyJ" + "A" * 30 + "." + "B" * 30 + "." + "C" * 30, "nonce": "nonce-1234567890"},
    )
    text = "\n".join(r.getMessage() for r in caplog.records)
    assert "BBBBBBBBBB" not in text and "acc-token-value" not in text


@respx.mock
def test_sign_in_is_rate_limited_per_client(client):
    respx.post(f"{SUPABASE}/auth/v1/token").mock(return_value=httpx.Response(200, json=SESSION))
    statuses = [client.post("/auth/google", json=GOOGLE_BODY).status_code for _ in range(12)]
    assert statuses[:10] == [200] * 10
    assert statuses[10:] == [429, 429]
    assert client.post("/auth/google", json=GOOGLE_BODY).json() == {"error": {"code": "rate_limited"}}


# --------------------------------------------------------------------------------------------------- refresh
@respx.mock
def test_refresh_rotates_the_session(client):
    route = respx.post(f"{SUPABASE}/auth/v1/token", params={"grant_type": "refresh_token"}).mock(
        return_value=httpx.Response(200, json={**SESSION, "refresh_token": "ref-2"})
    )
    response = client.post("/auth/refresh", json={"refresh_token": "ref-token-value"})
    assert response.status_code == 200
    assert response.json()["refresh_token"] == "ref-2"
    assert json.loads(route.calls.last.request.content) == {"refresh_token": "ref-token-value"}


@respx.mock
def test_a_reused_or_revoked_refresh_token_is_401(client):
    respx.post(f"{SUPABASE}/auth/v1/token").mock(
        return_value=httpx.Response(400, json={"error": "invalid_grant", "error_description": "Already Used"})
    )
    response = client.post("/auth/refresh", json={"refresh_token": "old-refresh-token"})
    assert response.status_code == 401
    assert "Already" not in response.text


def test_refresh_needs_a_token(client):
    assert client.post("/auth/refresh", json={}).status_code == 422


# ---------------------------------------------------------------------------------------------------- logout
@respx.mock
def test_logout_revokes_all_sessions_with_the_callers_token(client, key):
    route = respx.post(f"{SUPABASE}/auth/v1/logout", params={"scope": "global"}).mock(
        return_value=httpx.Response(204)
    )
    headers = bearer(key)
    response = client.post("/auth/logout", headers=headers)
    assert response.status_code == 204
    assert route.calls.last.request.headers["authorization"] == headers["Authorization"]


def test_logout_requires_a_valid_token(client):
    assert client.post("/auth/logout").status_code == 401
    assert client.post("/auth/logout", headers={"Authorization": "Bearer nope"}).status_code == 401


@respx.mock
def test_logout_succeeds_even_if_supabase_is_down(client, key):
    respx.post(f"{SUPABASE}/auth/v1/logout").mock(side_effect=httpx.ConnectError("down"))
    assert client.post("/auth/logout", headers=bearer(key)).status_code == 204
