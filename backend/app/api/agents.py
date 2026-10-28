"""Agent endpoints (docs/API_CONTRACT.md). The user id comes only from the verified token. The connector is
derived from the template, the toggles and approval modes are validated against the server's catalog, and a
Dangerous tool can never be set to "allow"."""

import uuid
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator

from app.agents import store
from app.auth.dependencies import CurrentUser
from app.db.access import Db, run_db
from app.ratelimit import RateLimiter
from app.validation import multi_line, single_line

router = APIRouter(tags=["agents"])


class _Strict(BaseModel):
    # Unknown fields are refused: a client cannot send connector, user_id, status on create, and so on.
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


def _name(value: str) -> str:
    return single_line(value)


def _zone(value: str | None) -> str | None:
    return None if value in (None, "") else store.valid_time_zone(value)


class CreateAgentBody(_Strict):
    template_id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=60)
    instructions: str | None = Field(default=None, max_length=2000)
    toggles: dict[str, StrictBool] = Field(default_factory=dict, max_length=32)
    approval_modes: dict[str, str] = Field(default_factory=dict, max_length=64)
    llm_profile_id: uuid.UUID | None = None
    time_zone: str | None = Field(default=None, max_length=64)

    _n = field_validator("name")(_name)
    _z = field_validator("time_zone")(_zone)

    @field_validator("instructions")
    @classmethod
    def _instructions(cls, value: str | None) -> str | None:
        return None if value is None else multi_line(value)


class UpdateAgentBody(_Strict):
    """Only the fields that are present are changed (`exclude_unset`)."""

    name: str | None = Field(default=None, min_length=1, max_length=60)
    instructions: str | None = Field(default=None, max_length=2000)
    toggles: dict[str, StrictBool] | None = Field(default=None, max_length=32)
    approval_modes: dict[str, str] | None = Field(default=None, max_length=64)
    llm_profile_id: uuid.UUID | None = None
    time_zone: str | None = Field(default=None, max_length=64)
    status: str | None = None

    @field_validator("name")
    @classmethod
    def _n(cls, value: str | None) -> str | None:
        return None if value is None else single_line(value)

    @field_validator("instructions")
    @classmethod
    def _i(cls, value: str | None) -> str | None:
        return None if value is None else multi_line(value)

    _z = field_validator("time_zone")(_zone)


def get_kill_limiter(request: Request) -> RateLimiter:
    return request.app.state.kill_limiter


@router.get("/agents")
async def list_agents(user: CurrentUser, db: Db) -> list[dict[str, Any]]:
    return await run_db(db, store.list_agents, user.user_id)


@router.post("/agents", status_code=201)
async def create_agent(body: CreateAgentBody, user: CurrentUser, db: Db) -> dict[str, Any]:
    def work(conn):
        return store.create_agent(
            conn,
            user.user_id,
            template_id=body.template_id,
            name=body.name,
            instructions=body.instructions,
            toggles=body.toggles,
            approval_modes=body.approval_modes,
            llm_profile_id=body.llm_profile_id,
            time_zone=body.time_zone,
        )

    return await run_db(db, work)


# Fixed paths are declared before /agents/{agent_id} so "kill-switch" is never read as an id.
@router.post("/agents/kill-switch", status_code=204)
async def kill_switch(
    user: CurrentUser, db: Db, limiter: Annotated[RateLimiter, Depends(get_kill_limiter)]
) -> Response:
    limiter.check(f"kill:{user.user_id}")
    await run_db(db, store.kill_switch, user.user_id)
    return Response(status_code=204)


@router.get("/agents/{agent_id}")
async def get_agent(agent_id: uuid.UUID, user: CurrentUser, db: Db) -> dict[str, Any]:
    return await run_db(db, store.get_agent, user.user_id, agent_id)


@router.patch("/agents/{agent_id}")
async def update_agent(
    agent_id: uuid.UUID, body: UpdateAgentBody, user: CurrentUser, db: Db
) -> dict[str, Any]:
    fields = body.model_dump(exclude_unset=True)
    # An explicit null on a required text field would clear it: only the nullable ones may be nulled.
    for key in ("name", "instructions", "status", "toggles", "approval_modes"):
        if key in fields and fields[key] is None:
            del fields[key]
    return await run_db(db, store.update_agent, user.user_id, agent_id, fields)


@router.delete("/agents/{agent_id}", status_code=204)
async def delete_agent(agent_id: uuid.UUID, user: CurrentUser, db: Db) -> Response:
    await run_db(db, store.delete_agent, user.user_id, agent_id)
    return Response(status_code=204)


@router.delete("/agents/{agent_id}/connection", status_code=204)
async def disconnect_agent(agent_id: uuid.UUID, user: CurrentUser, db: Db) -> Response:
    await run_db(db, store.disconnect, user.user_id, agent_id)
    return Response(status_code=204)


@router.get("/agent-templates")
async def list_templates(user: CurrentUser) -> list[dict[str, Any]]:
    """The catalog as the server knows it, so a client can verify its own copy."""
    from app.agents.catalog import TEMPLATES

    return [
        {
            "id": t.id,
            "connector": t.connector,
            "default_instructions": t.default_instructions,
            "toggles": [
                {
                    "id": g.id,
                    "default_on": g.default_on,
                    "tools": [{"id": tool.id, "risk": tool.risk.value} for tool in g.tools],
                }
                for g in t.toggles
            ],
        }
        for t in TEMPLATES.values()
    ]
