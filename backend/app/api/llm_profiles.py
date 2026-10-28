"""LLM profile endpoints (docs/API_CONTRACT.md). The API key is write-only: accepted here, stored encrypted,
never returned, never logged. Responses carry `key_last4` only."""

import re
import uuid
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.auth.dependencies import CurrentUser
from app.crypto import Envelope
from app.db.access import Db, run_db
from app.errors import ApiError
from app.llm import profiles
from app.llm.net import validate_base_url
from app.llm.profiles import ProfileData
from app.llm.providers import KeyCheck, ProfileSpec, Provider, check_key
from app.ratelimit import RateLimiter
from app.validation import single_line

router = APIRouter(tags=["llm-profiles"])

_KEY_OK = re.compile(r"^[\x21-\x7e]{8,512}$")  # printable ASCII, no spaces or control characters


def get_envelope(request: Request) -> Envelope:
    env: Envelope | None = getattr(request.app.state, "envelope", None)
    if env is None:
        raise ApiError("service_unavailable", 503)
    return env


def get_test_limiter(request: Request) -> RateLimiter:
    return request.app.state.llm_test_limiter


Env = Annotated[Envelope, Depends(get_envelope)]


class ProfileBody(BaseModel):
    # Unknown fields are refused so a client cannot slip in user_id, key_last4, is_admin and the like.
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    name: str = Field(min_length=1, max_length=60)
    provider: Literal["openai", "anthropic", "openai_compatible"]
    base_url: str | None = Field(default=None, max_length=500)
    model: str = Field(min_length=1, max_length=200)
    temperature: float = Field(default=0.2, ge=0, le=2, allow_inf_nan=False)
    max_tokens: int = Field(default=1000, ge=1, le=200_000)
    api_key: str | None = Field(default=None, repr=False)
    is_default: bool = False

    @field_validator("name", "model")
    @classmethod
    def _single_line(cls, value: str) -> str:
        return single_line(value)

    @field_validator("base_url")
    @classmethod
    def _base_url(cls, value: str | None) -> str | None:
        if value is None or value == "":
            return None
        return validate_base_url(value)  # raises ValueError -> 422 invalid_request

    @field_validator("api_key")
    @classmethod
    def _key(cls, value: str | None) -> str | None:
        if value is None or value == "":
            return None
        if not _KEY_OK.match(value):
            raise ValueError("invalid key")
        return value

    @model_validator(mode="after")
    def _custom_needs_url(self) -> "ProfileBody":
        if self.provider == "openai_compatible" and not self.base_url:
            raise ValueError("base_url required")
        return self

    def data(self) -> ProfileData:
        return ProfileData(
            self.name,
            self.provider,
            self.base_url,
            self.model,
            self.temperature,
            self.max_tokens,
            self.is_default,
        )

    def spec(self) -> ProfileSpec:
        return ProfileSpec(
            Provider(self.provider), self.base_url, self.model, self.temperature, self.max_tokens
        )


class TestResult(BaseModel):
    ok: bool


@router.get("/llm-profiles")
async def list_llm_profiles(user: CurrentUser, db: Db) -> list[dict]:
    return await run_db(db, profiles.list_profiles, user.user_id)


@router.post("/llm-profiles", status_code=201)
async def create_llm_profile(body: ProfileBody, user: CurrentUser, db: Db, env: Env) -> dict:
    if not body.api_key:
        raise ApiError("invalid_request", 422)
    return await run_db(db, profiles.create_profile, env, user.user_id, body.data(), body.api_key)


# Registered before /llm-profiles/{id}/... so the fixed path is never read as an id.
@router.post("/llm-profiles/test", response_model=TestResult)
async def test_draft(
    body: ProfileBody,
    user: CurrentUser,
    limiter: Annotated[RateLimiter, Depends(get_test_limiter)],
) -> TestResult:
    """Tests values from the form before saving. The key is used once and not kept."""
    if not body.api_key:
        raise ApiError("invalid_request", 422)
    limiter.check(f"llm-test:{user.user_id}")
    return _result(await check_key(body.spec(), body.api_key))


@router.patch("/llm-profiles/{profile_id}")
async def update_llm_profile(
    profile_id: uuid.UUID, body: ProfileBody, user: CurrentUser, db: Db, env: Env
) -> dict:
    # An omitted api_key keeps the stored key (contract).
    return await run_db(db, profiles.update_profile, env, user.user_id, profile_id, body.data(), body.api_key)


@router.delete("/llm-profiles/{profile_id}", status_code=204)
async def delete_llm_profile(profile_id: uuid.UUID, user: CurrentUser, db: Db) -> Response:
    await run_db(db, profiles.delete_profile, user.user_id, profile_id)
    return Response(status_code=204)


@router.post("/llm-profiles/{profile_id}/test", response_model=TestResult)
async def test_saved(
    profile_id: uuid.UUID,
    user: CurrentUser,
    db: Db,
    env: Env,
    limiter: Annotated[RateLimiter, Depends(get_test_limiter)],
) -> TestResult:
    limiter.check(f"llm-test:{user.user_id}")
    spec, key = await run_db(db, profiles.load_for_test, env, user.user_id, profile_id)
    return _result(await check_key(spec, key))


def _result(outcome: KeyCheck) -> TestResult:
    if outcome is KeyCheck.INVALID:
        raise ApiError("llm_key_invalid", 400)
    return TestResult(ok=outcome is KeyCheck.VALID)
