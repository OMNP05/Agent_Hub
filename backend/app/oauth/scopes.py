"""Which provider scopes an agent needs, computed from the toggles that are ON (CLAUDE.md rule 11).

Scopes are the minimum for the enabled toggles. Google scopes are broader than our toggles (for example
gmail.compose can also send), so the Tool Gateway (B8) still enforces the toggles; this module only decides
what to ask the user to consent to. Each agent has its own grant, so only that agent's toggles are considered
(and Google's include_granted_scopes is never used: it would add scopes from the user's other agents).

Scope URLs verified against Google's scope list on 2026-10-27 (see BACKEND_PHASE_WISE_CREATION.md 4.4).
GitHub Apps have no scope parameter: their permissions are set on the App itself.
"""

from app.agents.catalog import TEMPLATES

GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
CALENDAR_READONLY = "https://www.googleapis.com/auth/calendar.readonly"
CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"

# (connector, toggle id) -> scope. A toggle that is not here needs no extra scope.
_TOGGLE_SCOPES: dict[tuple[str, str], str] = {
    ("gmail", "read_email"): GMAIL_READONLY,
    ("gmail", "organize_email"): GMAIL_MODIFY,
    ("gmail", "draft_replies"): GMAIL_COMPOSE,
    ("gmail", "send_email"): GMAIL_SEND,
    ("calendar", "read_calendar"): CALENDAR_READONLY,
    ("calendar", "edit_events"): CALENDAR_EVENTS,
    ("calendar", "invite_people"): CALENDAR_EVENTS,
    ("calendar", "delete_events"): CALENDAR_EVENTS,
}

ALL_GOOGLE_SCOPES = frozenset(_TOGGLE_SCOPES.values())


def required_scopes(template_id: str, toggles: dict[str, bool]) -> list[str]:
    """Scopes for the toggles that are on, in a stable order, without duplicates. GitHub returns []."""
    template = TEMPLATES[template_id]
    if template.connector == "github":
        return []
    scopes: list[str] = []
    for toggle in template.toggles:
        if toggles.get(toggle.id, toggle.default_on):
            scope = _TOGGLE_SCOPES.get((template.connector, toggle.id))
            if scope and scope not in scopes:
                scopes.append(scope)
    return scopes
