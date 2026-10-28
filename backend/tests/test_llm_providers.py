import logging

import httpx2 as httpx
import pytest

from app.llm.net import UnsafeDestination
from app.llm.providers import (
    ANTHROPIC_VERSION,
    KeyCheck,
    ProfileSpec,
    Provider,
    build_chat_model,
    check_key,
)

KEY = "sk-test-SECRETVALUE-0123456789"


def spec(provider="openai", base=None) -> ProfileSpec:
    return ProfileSpec(Provider(provider), base, "some-model", 0.2, 500)


def client_returning(status=200, body=b"{}", capture=None, error=None) -> httpx.AsyncClient:
    def handler(request: httpx.Request) -> httpx.Response:
        if capture is not None:
            capture.append(request)
        if error is not None:
            raise error
        return httpx.Response(status, content=body)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_openai_checks_models_with_bearer_key():
    seen: list[httpx.Request] = []
    assert await check_key(spec("openai"), KEY, client_returning(capture=seen)) is KeyCheck.VALID
    request = seen[0]
    assert str(request.url) == "https://api.openai.com/v1/models" and request.method == "GET"
    assert request.headers["authorization"] == f"Bearer {KEY}"


async def test_anthropic_checks_models_with_api_key_header_and_version():
    seen: list[httpx.Request] = []
    assert await check_key(spec("anthropic"), KEY, client_returning(capture=seen)) is KeyCheck.VALID
    request = seen[0]
    assert str(request.url) == "https://api.anthropic.com/v1/models"
    assert request.headers["x-api-key"] == KEY and request.headers["anthropic-version"] == ANTHROPIC_VERSION
    assert "authorization" not in request.headers


async def test_anthropic_base_ending_in_v1_does_not_double_the_path():
    seen: list[httpx.Request] = []
    await check_key(spec("anthropic", "https://gw.example.com/v1"), KEY, client_returning(capture=seen))
    assert str(seen[0].url) == "https://gw.example.com/v1/models"


async def test_custom_endpoint_uses_its_own_base():
    seen: list[httpx.Request] = []
    await check_key(
        spec("openai_compatible", "https://gw.example.com/llm/"), KEY, client_returning(capture=seen)
    )
    assert str(seen[0].url) == "https://gw.example.com/llm/models"


@pytest.mark.parametrize("status", [401, 403])
async def test_rejected_key_is_invalid(status):
    assert await check_key(spec(), KEY, client_returning(status)) is KeyCheck.INVALID


@pytest.mark.parametrize("status", [400, 404, 408, 429, 500, 502, 503])
async def test_other_failures_never_blame_the_key(status):
    # A provider outage, a rate limit or a gateway without /models must not tell the user their key is wrong.
    assert await check_key(spec(), KEY, client_returning(status)) is KeyCheck.UNREACHABLE


async def test_redirect_is_not_success():
    assert await check_key(spec(), KEY, client_returning(302)) is KeyCheck.UNREACHABLE


@pytest.mark.parametrize(
    "error", [httpx.ConnectError("x"), httpx.ReadTimeout("x"), UnsafeDestination("blocked"), OSError("x")]
)
async def test_network_problems_are_unreachable(error):
    assert await check_key(spec(), KEY, client_returning(error=error)) is KeyCheck.UNREACHABLE


async def test_unsafe_custom_url_is_unreachable_without_any_call():
    seen: list[httpx.Request] = []
    for base in ("https://127.0.0.1/v1", "http://gw.example.com/v1", "https://169.254.169.254/"):
        assert await check_key(spec("openai_compatible", base), KEY, client_returning(capture=seen)) is (
            KeyCheck.UNREACHABLE
        )
    assert seen == []


async def test_missing_custom_url_is_unreachable():
    assert await check_key(spec("openai_compatible", None), KEY, client_returning()) is KeyCheck.UNREACHABLE


async def test_provider_body_and_key_never_reach_the_logs(caplog):
    caplog.set_level(logging.DEBUG)
    body = b'{"error":{"message":"Incorrect API key provided: sk-test-SECRETVALUE"}}'
    await check_key(spec(), KEY, client_returning(401, body))
    await check_key(spec(), KEY, client_returning(500, body))
    await check_key(spec(), KEY, client_returning(error=httpx.ConnectError("sk-test-SECRETVALUE")))
    assert "SECRETVALUE" not in caplog.text and "Incorrect API key" not in caplog.text


# -- the LangChain models must use the guarded client, never a default one -------------------------------------


@pytest.mark.parametrize(
    ("provider", "base"),
    [("openai", None), ("openai_compatible", "https://gw.example.com/v1"), ("anthropic", None)],
)
async def test_chat_models_are_built_on_the_safe_client(provider, base):
    from app.llm.net import SafeTransport, make_safe_client

    client = make_safe_client()
    model = build_chat_model(spec(provider, base), KEY, http_client=client)
    inner = model._async_client if provider == "anthropic" else model.root_async_client
    sdk_http = inner._client
    assert isinstance(sdk_http._transport, SafeTransport)
    assert sdk_http.follow_redirects is False
    await client.aclose()


async def test_synchronous_clients_cannot_connect():
    model = build_chat_model(spec("openai"), KEY)
    with pytest.raises(UnsafeDestination):
        model.root_client._client.get("https://api.openai.com/v1/models")


def test_model_settings_are_applied_and_the_key_is_not_in_repr():
    model = build_chat_model(spec("openai"), KEY)
    assert model.temperature == 0.2 and model.max_retries == 0
    assert "SECRETVALUE" not in repr(model) and "SECRETVALUE" not in str(model.openai_api_key)
