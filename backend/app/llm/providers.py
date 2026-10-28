"""Turns a saved profile into (a) a key check and (b) a LangChain chat model for the agent runtime (B9).

Every outbound call, for official providers too, goes through the SSRF-safe client in app.llm.net, so there is
one network path to audit. LangChain is used only for the provider abstraction (CLAUDE.md).

The key check is a cheap authenticated GET of the model list, which costs nothing and spends no tokens:
  OpenAI and OpenAI-compatible:  GET {base}/models        Authorization: Bearer <key>
  Anthropic:                     GET {base}/v1/models     x-api-key: <key>, anthropic-version
Only the HTTP status is used. Provider response bodies are never read into an error, a log or an API reply.
"""

import logging
from dataclasses import dataclass
from enum import StrEnum

import anthropic
import httpx2 as httpx
from langchain_anthropic import ChatAnthropic
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from app.llm.net import (
    DEFAULT_TIMEOUT,
    RefuseSyncTransport,
    ResponseTooLarge,
    UnsafeDestination,
    make_safe_client,
    validate_base_url,
)

log = logging.getLogger("agenthub.llm")

OPENAI_BASE = "https://api.openai.com/v1"
ANTHROPIC_BASE = "https://api.anthropic.com"
# Version header from the official docs (platform.claude.com/docs/en/api/versioning, checked 2026-10-28).
# 2023-06-01 is the long-supported one; the newer 2026-10-21 removes the temperature parameter that profiles
# expose, so it is deliberately not used here.
ANTHROPIC_VERSION = "2023-06-01"
_CHECK_TIMEOUT = httpx.Timeout(connect=8.0, read=12.0, write=8.0, pool=5.0)


class Provider(StrEnum):
    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    OPENAI_COMPATIBLE = "openai_compatible"


class KeyCheck(StrEnum):
    VALID = "valid"
    INVALID = "invalid"  # the provider said the key is wrong or not permitted
    UNREACHABLE = (
        "unreachable"  # network, timeout, blocked address, or a provider outage: nothing about the key
    )


@dataclass(frozen=True)
class ProfileSpec:
    """The part of a profile needed to talk to a provider. The key is passed separately, never stored here."""

    provider: Provider
    base_url: str | None
    model: str
    temperature: float
    max_tokens: int

    @property
    def effective_base_url(self) -> str:
        if self.provider is Provider.OPENAI:
            return validate_base_url(self.base_url) if self.base_url else OPENAI_BASE
        if self.provider is Provider.ANTHROPIC:
            return validate_base_url(self.base_url) if self.base_url else ANTHROPIC_BASE
        if not self.base_url:
            raise ValueError("base url required")
        return validate_base_url(self.base_url)


def _check_request(spec: ProfileSpec, api_key: str) -> tuple[str, dict[str, str]]:
    base = spec.effective_base_url
    if spec.provider is Provider.ANTHROPIC:
        # A base that already ends in /v1 is accepted so both spellings work.
        path = "/models" if base.endswith("/v1") else "/v1/models"
        return base + path, {"x-api-key": api_key, "anthropic-version": ANTHROPIC_VERSION}
    return base + "/models", {"Authorization": f"Bearer {api_key}"}


async def check_key(spec: ProfileSpec, api_key: str, client: httpx.AsyncClient | None = None) -> KeyCheck:
    """Never raises and never returns provider text. 401/403 mean a bad key; anything else is 'unreachable'
    rather than 'invalid', so a provider outage or an unusual gateway does not tell the user their good key is
    wrong (and a wrong 'valid' is impossible: only a 2xx counts)."""
    try:
        url, headers = _check_request(spec, api_key)
    except ValueError:
        return KeyCheck.UNREACHABLE
    owns = client is None
    client = client or make_safe_client(_CHECK_TIMEOUT, max_response_bytes=2 * 1024 * 1024)
    try:
        response = await client.get(url, headers={**headers, "Accept": "application/json"})
        await response.aread()
    except (httpx.HTTPError, UnsafeDestination, ResponseTooLarge, OSError) as e:
        log.info("key check could not complete", extra={"error_type": type(e).__name__})
        return KeyCheck.UNREACHABLE
    finally:
        if owns:
            await client.aclose()
    if 200 <= response.status_code < 300:
        return KeyCheck.VALID
    if response.status_code in (401, 403):
        return KeyCheck.INVALID
    log.info("key check got an unexpected status", extra={"status": response.status_code})
    return KeyCheck.UNREACHABLE


def build_chat_model(spec: ProfileSpec, api_key: str, *, http_client: httpx.AsyncClient | None = None):
    """A LangChain chat model whose network traffic goes through the SSRF-safe client. The caller closes the
    client when the run ends. LangChain's own retries are off: the runtime owns retry policy (plan B9)."""
    base = spec.effective_base_url
    client = http_client or make_safe_client()
    sync_client = httpx.Client(transport=RefuseSyncTransport(), trust_env=False)
    if spec.provider is Provider.ANTHROPIC:
        # The Anthropic SDK appends /v1 itself, so a base that ends in /v1 is trimmed to avoid /v1/v1.
        sdk_base = base.removesuffix("/v1")
        model = ChatAnthropic(
            model=spec.model,
            api_key=SecretStr(api_key),
            base_url=sdk_base,
            temperature=spec.temperature,
            max_tokens=spec.max_tokens,
            max_retries=0,
            timeout=DEFAULT_TIMEOUT.read,
        )
        # ChatAnthropic has no field for a custom HTTP client, but builds its SDK clients lazily in cached
        # properties. Filling them in first means its default (unguarded) client is never created.
        common = {"api_key": api_key, "base_url": sdk_base, "max_retries": 0, "timeout": DEFAULT_TIMEOUT.read}
        model.__dict__["_async_client"] = anthropic.AsyncClient(**common, http_client=client)
        model.__dict__["_client"] = anthropic.Client(**common, http_client=sync_client)
        return model
    return ChatOpenAI(
        model=spec.model,
        api_key=SecretStr(api_key),
        base_url=base,
        temperature=spec.temperature,
        max_tokens=spec.max_tokens,
        max_retries=0,
        timeout=DEFAULT_TIMEOUT.read,
        http_async_client=client,
        http_client=sync_client,
    )
