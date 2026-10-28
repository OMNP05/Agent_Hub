"""Scopes, PKCE, and the provider conversations, with mocked HTTP. No real GitHub or Google is ever called."""

import base64
import logging
import re
from urllib.parse import parse_qs, urlsplit

import httpx2 as httpx
import pytest

from app.agents.catalog import TEMPLATES
from app.llm.net import UnsafeDestination
from app.oauth import scopes
from app.oauth.providers import (
    ALLOWED_HOSTS,
    GitHubApp,
    GoogleConnector,
    ProviderHttp,
    ProviderRejected,
    ProviderUnavailable,
)
from app.oauth.state import challenge_for, hash_state, new_state, new_verifier

REDIRECT = "https://api.example.com/oauth/github/callback"


# -- scopes ----------------------------------------------------------------------------------------------------


def test_default_gmail_agent_asks_only_for_read():
    assert scopes.required_scopes("gmail-assistant", {}) == [scopes.GMAIL_READONLY]


def test_each_toggle_adds_exactly_its_scope():
    on = {"read_email": True, "organize_email": True, "draft_replies": True, "send_email": True}
    assert scopes.required_scopes("gmail-assistant", on) == [
        scopes.GMAIL_READONLY, scopes.GMAIL_MODIFY, scopes.GMAIL_COMPOSE, scopes.GMAIL_SEND,
    ]  # fmt: skip
    assert scopes.required_scopes("gmail-assistant", {"read_email": False, "send_email": True}) == [
        scopes.GMAIL_SEND
    ]


def test_calendar_write_toggles_share_one_scope_without_duplicates():
    on = {"edit_events": True, "invite_people": True, "delete_events": True}
    assert scopes.required_scopes("calendar-assistant", on) == [
        scopes.CALENDAR_READONLY,
        scopes.CALENDAR_EVENTS,
    ]


def test_everything_off_asks_for_nothing_and_github_has_no_scopes():
    off = {"read_email": False}
    assert scopes.required_scopes("gmail-assistant", off) == []
    assert scopes.required_scopes("github-assistant", {"edit_profile": True}) == []


def test_every_google_toggle_has_a_scope_or_is_github():
    for template in TEMPLATES.values():
        if template.connector == "github":
            continue
        for toggle in template.toggles:
            assert scopes.required_scopes(template.id, {toggle.id: True}), toggle.id


def test_scope_urls_are_the_official_ones():
    assert {
        "https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.compose", "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/calendar.readonly", "https://www.googleapis.com/auth/calendar.events",
    } == scopes.ALL_GOOGLE_SCOPES  # fmt: skip


# -- state and PKCE ---------------------------------------------------------------------------------------------


def test_state_is_long_random_and_only_its_hash_is_stored():
    states = {new_state() for _ in range(200)}
    assert len(states) == 200 and all(len(s) >= 43 for s in states)
    s = new_state()
    assert re.fullmatch(r"[0-9a-f]{64}", hash_state(s)) and s not in hash_state(s)


def test_pkce_challenge_matches_rfc_7636_example():
    # RFC 7636 appendix B
    assert (
        challenge_for("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    )


def test_verifier_meets_rfc_length_and_alphabet():
    for _ in range(50):
        v = new_verifier()
        assert 43 <= len(v) <= 128 and re.fullmatch(r"[A-Za-z0-9_-]+", v)
    assert base64.urlsafe_b64decode(challenge_for(new_verifier()) + "=")  # 32 bytes of SHA-256


# -- authorization URLs -----------------------------------------------------------------------------------------


def q(url):
    return {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}


def test_github_url_has_state_pkce_and_no_scope():
    url = GitHubApp("Iv1.abc", "secret").authorize_url(state="S", challenge="C", redirect_uri=REDIRECT)
    assert url.startswith("https://github.com/login/oauth/authorize?")
    p = q(url)
    assert p["client_id"] == "Iv1.abc" and p["state"] == "S" and p["redirect_uri"] == REDIRECT
    assert p["code_challenge"] == "C" and p["code_challenge_method"] == "S256"
    assert "scope" not in p and "secret" not in url


def test_google_url_is_offline_consent_pkce_and_not_incremental():
    wanted = [scopes.GMAIL_READONLY, scopes.GMAIL_SEND]
    url = GoogleConnector("cid.apps", "secret").authorize_url(
        state="S", challenge="C", redirect_uri=REDIRECT, scopes=wanted
    )
    assert url.startswith("https://accounts.google.com/o/oauth2/v2/auth?")
    p = q(url)
    assert p["response_type"] == "code" and p["access_type"] == "offline" and p["prompt"] == "consent"
    assert p["scope"].split() == wanted and p["state"] == "S" and p["code_challenge_method"] == "S256"
    assert p["include_granted_scopes"] == "false"  # never fold in the user's other agents' scopes
    assert "secret" not in url


# -- token exchange (mocked) -------------------------------------------------------------------------------------


def http_with(handler) -> ProviderHttp:
    return ProviderHttp(httpx.AsyncClient(transport=httpx.MockTransport(handler)))


def respond(status=200, json=None, content=None):
    return lambda request: (
        httpx.Response(status, json=json) if content is None else httpx.Response(status, content=content)
    )


async def test_github_exchange_sends_pkce_and_secret_and_parses_tokens():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json={
            "access_token": "ghu_ACCESS", "expires_in": 28800, "refresh_token": "ghr_REFRESH",
            "refresh_token_expires_in": 15897600, "scope": "", "token_type": "bearer",
        })  # fmt: skip

    app = GitHubApp("Iv1.abc", "SECRET", http_with(handler))
    tokens = await app.exchange(code="CODE", verifier="VER", redirect_uri=REDIRECT)
    assert (tokens.access_token, tokens.refresh_token, tokens.expires_in, tokens.scopes) == (
        "ghu_ACCESS",
        "ghr_REFRESH",
        28800,
        [],
    )
    body = parse_qs(seen[0].content.decode())
    assert str(seen[0].url) == "https://github.com/login/oauth/access_token"
    assert (
        body["code_verifier"] == ["VER"] and body["client_secret"] == ["SECRET"] and body["code"] == ["CODE"]
    )
    assert seen[0].headers["accept"] == "application/json"


async def test_github_reports_errors_with_http_200():
    # GitHub answers 200 with an error field for a bad code
    app = GitHubApp("id", "s", http_with(respond(200, {"error": "bad_verification_code"})))
    with pytest.raises(ProviderRejected):
        await app.exchange(code="x", verifier="v", redirect_uri=REDIRECT)


async def test_github_refresh_rotates_and_requires_the_new_refresh_token():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(
            200, json={"access_token": "ghu_NEW", "expires_in": 28800, "refresh_token": "ghr_NEW"}
        )

    tokens = await GitHubApp("id", "s", http_with(handler)).refresh("ghr_OLD")
    assert tokens.refresh_token == "ghr_NEW" and tokens.access_token == "ghu_NEW"
    body = parse_qs(seen[0].content.decode())
    assert body["grant_type"] == ["refresh_token"] and body["refresh_token"] == ["ghr_OLD"]

    no_rotation = GitHubApp("id", "s", http_with(respond(200, {"access_token": "ghu_NEW"})))
    with pytest.raises(
        ProviderUnavailable
    ):  # a rotation without the new refresh token must not be half-saved
        await no_rotation.refresh("ghr_OLD")


async def test_google_exchange_records_the_scopes_actually_granted():
    granted = f"{scopes.GMAIL_READONLY}"  # the user unticked "send"
    app = GoogleConnector("cid", "SECRET", http_with(respond(200, {
        "access_token": "ya29.A", "expires_in": 3599, "refresh_token": "1//R", "scope": granted, "token_type": "Bearer",
    })))  # fmt: skip
    tokens = await app.exchange(code="C", verifier="V", redirect_uri=REDIRECT)
    assert tokens.scopes == [scopes.GMAIL_READONLY] and tokens.refresh_token == "1//R"


async def test_google_exchange_sends_the_verifier_and_grant_type():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json={"access_token": "ya29.A", "scope": scopes.GMAIL_READONLY})

    await GoogleConnector("cid", "SECRET", http_with(handler)).exchange(
        code="C", verifier="V", redirect_uri=REDIRECT
    )
    body = parse_qs(seen[0].content.decode())
    assert str(seen[0].url) == "https://oauth2.googleapis.com/token"
    assert body["grant_type"] == ["authorization_code"] and body["code_verifier"] == ["V"]


async def test_google_refresh_keeps_the_old_refresh_token():
    app = GoogleConnector("cid", "s", http_with(respond(200, {"access_token": "ya29.B", "expires_in": 3599})))
    tokens = await app.refresh("1//R")
    assert tokens.access_token == "ya29.B" and tokens.refresh_token is None  # caller keeps the stored one


@pytest.mark.parametrize("error", ["invalid_grant", "bad_refresh_token", "invalid_client", "access_denied"])
@pytest.mark.parametrize("status", [200, 400, 401])
async def test_revoked_or_wrong_grants_are_rejected_not_unavailable(error, status):
    app = GoogleConnector("cid", "s", http_with(respond(status, {"error": error})))
    with pytest.raises(ProviderRejected):
        await app.refresh("1//R")


@pytest.mark.parametrize("status", [429, 500, 502, 503])
async def test_outages_and_rate_limits_never_look_like_revocation(status):
    app = GoogleConnector("cid", "s", http_with(respond(status, {"error": "invalid_grant"})))
    with pytest.raises(ProviderUnavailable):  # even a provider outage that mentions invalid_grant
        await app.refresh("1//R")


async def test_unrecognised_errors_and_garbage_are_not_treated_as_revocation():
    for handler in (
        respond(400, {"error": "something_new"}), respond(200, content=b"<html>oops</html>"),
        respond(200, {"nothing": "useful"}), respond(200, content=b"[1,2]"),
    ):  # fmt: skip
        with pytest.raises(ProviderUnavailable):
            await GoogleConnector("cid", "s", http_with(handler)).refresh("1//R")


async def test_network_failure_is_unavailable():
    def boom(request):
        raise httpx.ConnectError("down")

    with pytest.raises(ProviderUnavailable):
        await GitHubApp("id", "s", http_with(boom)).refresh("ghr_x")


# -- revoke ---------------------------------------------------------------------------------------------------------


async def test_github_revoke_uses_basic_auth_and_the_token_endpoint():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(204)

    assert await GitHubApp("Iv1.abc", "SECRET", http_with(handler)).revoke("ghu_TOKEN") is True
    request = seen[0]
    assert (
        request.method == "DELETE" and str(request.url) == "https://api.github.com/applications/Iv1.abc/token"
    )
    assert request.headers["authorization"].startswith("Basic ")
    assert b"ghu_TOKEN" in request.content


async def test_google_revoke_posts_the_token():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200)

    assert await GoogleConnector("cid", "s", http_with(handler)).revoke("1//R") is True
    assert str(seen[0].url) == "https://oauth2.googleapis.com/revoke" and parse_qs(
        seen[0].content.decode()
    ) == {"token": ["1//R"]}


async def test_revoke_failures_report_false_never_raise():
    assert (
        await GoogleConnector("c", "s", http_with(respond(400, {"error": "invalid_token"}))).revoke("t")
        is False
    )

    def boom(request):
        raise httpx.ConnectError("down")

    assert await GitHubApp("i", "s", http_with(boom)).revoke("t") is False


# -- host allowlist (CLAUDE.md rule 9) -------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "url",
    [
        "https://evil.example.com/token", "https://github.com.evil.com/login", "https://oauth2.googleapis.com.evil.io/",
        "http://github.com/login/oauth/access_token", "https://169.254.169.254/", "https://127.0.0.1/",
        "https://www.googleapis.com/gmail/v1", "https://gmail.googleapis.com/", "ftp://github.com/",
    ],
)  # fmt: skip
async def test_only_allowlisted_https_hosts_can_be_called(url):
    called = []

    def handler(request):
        called.append(request)
        return httpx.Response(200, json={})

    with pytest.raises(UnsafeDestination):
        await http_with(handler).request("GET", url)
    assert called == []


def test_allowlist_is_exactly_the_oauth_hosts():
    assert {"github.com", "api.github.com", "accounts.google.com", "oauth2.googleapis.com"} == ALLOWED_HOSTS


async def test_no_secret_or_provider_text_reaches_the_logs(caplog):
    caplog.set_level(logging.DEBUG)
    body = {
        "error": "invalid_grant",
        "error_description": "token ya29.LEAKME1234567890abcdef refresh 1//LEAKREFRESH",
    }
    for status in (400, 500):
        with pytest.raises((ProviderRejected, ProviderUnavailable)):
            await GoogleConnector("cid", "CLIENTSECRET", http_with(respond(status, body))).refresh(
                "1//LEAKREFRESH"
            )

    def boom(request):
        raise httpx.ConnectError("CLIENTSECRET")

    with pytest.raises(ProviderUnavailable):
        await GoogleConnector("cid", "CLIENTSECRET", http_with(boom)).refresh("1//LEAKREFRESH")
    assert "LEAK" not in caplog.text and "CLIENTSECRET" not in caplog.text


def test_token_set_does_not_print_tokens():
    from app.oauth.providers import TokenSet

    t = TokenSet("ya29.SECRETACCESS", "1//SECRETREFRESH", 3600, [])
    assert "SECRET" not in repr(t) and "SECRET" not in str(t)
