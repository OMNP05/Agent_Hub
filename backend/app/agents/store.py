"""Agent storage. Every statement filters by the verified user id (CLAUDE.md rule 7); an agent that belongs to
someone else is indistinguishable from one that does not exist (404 for both).

Permissions are stored as two JSON maps (toggles, approval_modes) and are ALWAYS passed through the catalog's
sanitize() on the way in, so the database never holds a toggle or tool the catalog does not have.
"""

import json
import uuid
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import psycopg

from app.agents import catalog
from app.agents.catalog import InvalidPermissions
from app.errors import ApiError

MAX_AGENTS_PER_USER = 20

_COLUMNS = (
    "a.id, a.name, a.template_id, a.connector, a.instructions, a.status, a.llm_profile_id, a.time_zone, "
    "a.toggles, a.approval_modes, a.last_activity_at, c.status, c.scopes_granted"
)
_FROM = "from public.agents a left join public.connections c on c.agent_id = a.id and c.user_id = a.user_id"


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def _public(row: tuple) -> dict[str, Any]:
    connection = None if row[11] is None else {"status": row[11], "scopes_granted": list(row[12] or [])}
    return {
        "id": str(row[0]),
        "name": row[1],
        "template_id": row[2],
        "connector": row[3],
        "instructions": row[4],
        "status": row[5],
        "llm_profile_id": str(row[6]) if row[6] else None,
        "time_zone": row[7],
        "toggles": row[8],
        "approval_modes": row[9],
        "connection": connection,
        "last_activity_at": _iso(row[10]),
    }


def valid_time_zone(value: str) -> str:
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError, OSError):
        raise ValueError("unknown time zone") from None
    return value


def list_agents(conn: psycopg.Connection, user_id: uuid.UUID) -> list[dict[str, Any]]:
    rows = conn.execute(
        f"select {_COLUMNS} {_FROM} where a.user_id = %s order by a.created_at desc, a.id",
        (user_id,),
    ).fetchall()
    return [_public(r) for r in rows]


def get_agent(conn: psycopg.Connection, user_id: uuid.UUID, agent_id: uuid.UUID) -> dict[str, Any]:
    row = conn.execute(
        f"select {_COLUMNS} {_FROM} where a.id = %s and a.user_id = %s",
        (agent_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("not_found", 404)
    return _public(row)


def require_owned_agent(conn: psycopg.Connection, user_id: uuid.UUID, agent_id: uuid.UUID) -> tuple:
    """The ownership check every agent-scoped operation starts with: (template_id, connector, status)."""
    row = conn.execute(
        "select template_id, connector, status from public.agents where id = %s and user_id = %s",
        (agent_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("not_found", 404)
    return row


def _own_profile_or_none(conn: psycopg.Connection, user_id: uuid.UUID, profile_id: uuid.UUID | None) -> None:
    """A profile id must belong to the same user. A foreign or missing one is refused like any bad input."""
    if profile_id is None:
        return
    row = conn.execute(
        "select 1 from public.llm_profiles where id = %s and user_id = %s", (profile_id, user_id)
    ).fetchone()
    if row is None:
        raise ApiError("invalid_request", 422)


def _template_or_422(template_id: str) -> catalog.Template:
    template = catalog.TEMPLATES.get(template_id)
    if template is None:
        raise ApiError("invalid_request", 422)
    return template


def create_agent(
    conn: psycopg.Connection,
    user_id: uuid.UUID,
    *,
    template_id: str,
    name: str,
    instructions: str | None,
    toggles: dict[str, bool],
    approval_modes: dict[str, str],
    llm_profile_id: uuid.UUID | None,
    time_zone: str | None,
) -> dict[str, Any]:
    template = _template_or_422(template_id)
    try:
        clean_toggles, clean_modes = catalog.sanitize(template, toggles, approval_modes)
    except InvalidPermissions:
        raise ApiError("invalid_request", 422) from None
    # Serialises two simultaneous creates by one user so the per-user cap cannot be raced.
    conn.execute("select pg_advisory_xact_lock(hashtextextended(%s, 0))", (f"agents:{user_id}",))
    count = conn.execute("select count(*) from public.agents where user_id = %s", (user_id,)).fetchone()[0]
    if count >= MAX_AGENTS_PER_USER:
        raise ApiError("agent_limit_reached", 409)
    _own_profile_or_none(conn, user_id, llm_profile_id)
    agent_id = conn.execute(
        "insert into public.agents (user_id, name, template_id, connector, instructions, status, "
        "llm_profile_id, time_zone, toggles, approval_modes) "
        "values (%s, %s, %s, %s, %s, 'draft', %s, %s, %s, %s) returning id",
        (
            user_id,
            name,
            template.id,
            template.connector,  # always derived from the template, never from the request
            template.default_instructions if instructions is None else instructions,
            llm_profile_id,
            time_zone,
            json.dumps(clean_toggles),
            json.dumps(clean_modes),
        ),
    ).fetchone()[0]
    return get_agent(conn, user_id, agent_id)


def update_agent(
    conn: psycopg.Connection, user_id: uuid.UUID, agent_id: uuid.UUID, fields: dict[str, Any]
) -> dict[str, Any]:
    """[fields] holds only what the client sent. status may only be changed between active and paused;
    'draft' is reached by creating, and 'active' needs a connection (set by the OAuth flow in B7)."""
    row = conn.execute(
        "select template_id, status, toggles, approval_modes from public.agents "
        "where id = %s and user_id = %s for update",
        (agent_id, user_id),
    ).fetchone()
    if row is None:
        raise ApiError("not_found", 404)
    template = _template_or_422(row[0])
    sets: dict[str, Any] = {}

    for key in ("name", "instructions", "time_zone"):
        if key in fields:
            sets[key] = fields[key]
    if "llm_profile_id" in fields:
        _own_profile_or_none(conn, user_id, fields["llm_profile_id"])
        sets["llm_profile_id"] = fields["llm_profile_id"]
    if "toggles" in fields or "approval_modes" in fields:
        # A partial update merges onto what is stored, then the whole result is validated again.
        merged_toggles = {**row[2], **(fields.get("toggles") or {})}
        merged_modes = {**row[3], **(fields.get("approval_modes") or {})}
        try:
            clean_toggles, clean_modes = catalog.sanitize(template, merged_toggles, merged_modes)
        except InvalidPermissions:
            raise ApiError("invalid_request", 422) from None
        sets["toggles"] = json.dumps(clean_toggles)
        sets["approval_modes"] = json.dumps(clean_modes)
    if "status" in fields:
        wanted = fields["status"]
        if wanted not in ("active", "paused"):
            raise ApiError("invalid_request", 422)
        if wanted == "active":
            connected = conn.execute(
                "select 1 from public.connections where agent_id = %s and user_id = %s and status = 'active'",
                (agent_id, user_id),
            ).fetchone()
            if connected is None:
                raise ApiError("connection_needs_reauth", 409)
        sets["status"] = wanted

    if sets:
        assignments = ", ".join(
            f"{column} = %s" for column in sets
        )  # column names come from the fixed list above
        conn.execute(
            f"update public.agents set {assignments} where id = %s and user_id = %s",  # noqa: S608
            (*sets.values(), agent_id, user_id),
        )
    return get_agent(conn, user_id, agent_id)


def delete_agent(conn: psycopg.Connection, user_id: uuid.UUID, agent_id: uuid.UUID) -> None:
    # Foreign keys cascade: connection and its secrets, conversations, messages, runs, events, approvals,
    # schedules. (Revoking the grant at the provider is added in B7 where the provider clients exist.)
    deleted = conn.execute(
        "delete from public.agents where id = %s and user_id = %s returning id", (agent_id, user_id)
    ).fetchone()
    if deleted is None:
        raise ApiError("not_found", 404)


def kill_switch(conn: psycopg.Connection, user_id: uuid.UUID) -> int:
    """Pauses every one of the user's agents. Runs read the status live, so they stop at their next Gateway
    call (B8). Returns how many agents were changed."""
    rows = conn.execute(
        "update public.agents set status = 'paused' where user_id = %s and status <> 'paused' returning id",
        (user_id,),
    ).fetchall()
    return len(rows)


def disconnect(conn: psycopg.Connection, user_id: uuid.UUID, agent_id: uuid.UUID) -> None:
    """Removes the agent's provider grant and pauses the agent. Tokens are deleted with the connection row
    (cascade). Revoking at the provider is added in B7."""
    require_owned_agent(conn, user_id, agent_id)
    conn.execute("delete from public.connections where agent_id = %s and user_id = %s", (agent_id, user_id))
    conn.execute(
        "update public.agents set status = 'draft' where id = %s and user_id = %s", (agent_id, user_id)
    )
