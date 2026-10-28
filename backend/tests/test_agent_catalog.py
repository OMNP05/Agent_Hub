import re
from pathlib import Path

import pytest

from app.agents.catalog import (
    CALENDAR,
    GITHUB,
    GMAIL,
    TEMPLATES,
    InvalidPermissions,
    Mode,
    Risk,
    allowed_modes,
    defaults,
    manifest,
    sanitize,
)

ANDROID_CATALOG = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/kotlin/dev/agenthub/app/agents/catalog/TemplateCatalog.kt"
)


def test_exactly_three_templates():
    assert set(TEMPLATES) == {"github-assistant", "gmail-assistant", "calendar-assistant"}
    assert {t.connector for t in TEMPLATES.values()} == {"github", "gmail", "calendar"}


def test_tool_ids_are_unique_and_prefixed_by_their_connector():
    seen = set()
    for template in TEMPLATES.values():
        for tool in template.tools:
            assert tool.id not in seen
            seen.add(tool.id)
            assert tool.id.startswith(template.connector + ".")


def test_toggle_ids_unique_within_a_template_and_no_toggle_is_empty():
    for template in TEMPLATES.values():
        ids = [g.id for g in template.toggles]
        assert len(ids) == len(set(ids))
        assert all(g.tools for g in template.toggles)


def test_only_read_toggles_default_on():
    for template in TEMPLATES.values():
        for toggle in template.toggles:
            risks = {t.risk for t in toggle.tools}
            assert toggle.default_on == (risks == {Risk.READ}), toggle.id


def test_send_delete_and_invite_are_dangerous():
    assert GMAIL.tool("gmail.send_email").risk is Risk.DANGEROUS
    assert CALENDAR.tool("calendar.delete_event").risk is Risk.DANGEROUS
    assert CALENDAR.tool("calendar.create_event_with_attendees").risk is Risk.DANGEROUS
    assert CALENDAR.tool("calendar.update_event_with_attendees").risk is Risk.DANGEROUS


def test_allowed_modes_by_risk():
    assert allowed_modes(Risk.READ) == ()
    assert allowed_modes(Risk.WRITE) == (Mode.ASK, Mode.ALLOW)
    assert allowed_modes(Risk.DANGEROUS) == (Mode.ASK,)


@pytest.mark.parametrize("template", list(TEMPLATES.values()), ids=lambda t: t.id)
def test_defaults_are_safe(template):
    toggles, modes = defaults(template)
    assert set(toggles) == {g.id for g in template.toggles}
    assert all(mode == "ask" for mode in modes.values())
    assert set(modes) == {t.id for t in template.tools if t.risk is not Risk.READ}  # no mode for Read tools
    # Nothing that can change the outside world is on by default.
    for tool in template.tools:
        if tool.risk is not Risk.READ:
            assert toggles[template.toggle_of(tool.id).id] is False


def test_dangerous_allow_is_forced_back_to_ask():
    _, modes = sanitize(GMAIL, {"send_email": True}, {"gmail.send_email": "allow"})
    assert modes["gmail.send_email"] == "ask"
    _, modes = sanitize(CALENDAR, {}, {"calendar.delete_event": "allow", "calendar.create_event": "allow"})
    assert modes["calendar.delete_event"] == "ask" and modes["calendar.create_event"] == "allow"


def test_write_allow_is_kept():
    _, modes = sanitize(GITHUB, {"edit_profile": True}, {"github.update_profile": "allow"})
    assert modes["github.update_profile"] == "allow"


def test_missing_toggles_take_defaults_and_values_are_kept():
    toggles, _ = sanitize(GITHUB, {"edit_profile": True, "read_prs": False}, {})
    assert (
        toggles["edit_profile"] is True and toggles["read_prs"] is False and toggles["read_profile"] is True
    )


@pytest.mark.parametrize(
    ("toggles", "modes"),
    [
        ({"send_email": True}, {}),  # a toggle from another template
        ({"nonsense": True}, {}),
        ({"read_prs": "yes"}, {}),  # not a boolean
        ({"read_prs": 1}, {}),
        ({}, {"github.get_profile": "ask"}),  # Read tools have no mode
        ({}, {"github.delete_repo": "ask"}),  # a tool outside the catalog
        ({}, {"gmail.send_email": "ask"}),  # another template's tool
        ({}, {"github.update_profile": "yolo"}),  # unknown mode
        ({}, {"github.update_profile": "ALLOW"}),
        ({}, {"github.update_profile": None}),
    ],
)
def test_anything_outside_the_catalog_is_refused(toggles, modes):
    with pytest.raises(InvalidPermissions):
        sanitize(GITHUB, toggles, modes)


def test_manifest_default_deny_and_live_toggles():
    m = manifest(GMAIL, {}, {})
    assert m["gmail.search_emails"].enabled is True and m["gmail.search_emails"].mode is None
    assert m["gmail.send_email"].enabled is False
    assert "gmail.delete_everything" not in m  # not in the catalog, so not in the manifest
    on = manifest(GMAIL, {"send_email": True}, {"gmail.send_email": "allow"})
    assert on["gmail.send_email"].enabled is True and on["gmail.send_email"].mode is Mode.ASK


def test_manifest_covers_every_tool_exactly_once():
    for template in TEMPLATES.values():
        assert set(manifest(template, {}, {})) == {t.id for t in template.tools}


# -- the Android copy and the server catalog must say the same thing ---------------------------------------------


def _android_catalog() -> dict[str, dict[str, tuple[bool, dict[str, str]]]]:
    source = ANDROID_CATALOG.read_text(encoding="utf-8")
    out: dict[str, dict] = {}
    for block in re.split(r"AgentTemplate\(\s*\n\s*id = ", source)[1:]:
        template_id = re.match(r'"([a-z-]+)"', block).group(1)
        toggles: dict[str, tuple[bool, dict[str, str]]] = {}
        # Split on each ToggleDef( so a toggle's tools are exactly the ToolDef entries before the next one.
        for chunk in re.split(r"ToggleDef\(", block)[1:]:
            toggle_id = re.match(r'\s*"([a-z_]+)"', chunk).group(1)
            default_on = re.search(r'",\s*(true|false),\s*listOf\(', chunk).group(1) == "true"
            tools = dict(re.findall(r'ToolDef\("([a-z_.]+)",\s*"[^"]*",\s*(Read|Write|Dangerous)\)', chunk))
            toggles[toggle_id] = (default_on, tools)
        out[template_id] = toggles
    return out


def test_android_catalog_matches_the_server_catalog():
    if not ANDROID_CATALOG.exists():
        pytest.skip("android sources not present")
    android = _android_catalog()
    assert set(android) == set(TEMPLATES)
    for template_id, template in TEMPLATES.items():
        assert set(android[template_id]) == {g.id for g in template.toggles}, template_id
        for toggle in template.toggles:
            default_on, tools = android[template_id][toggle.id]
            assert default_on == toggle.default_on, (template_id, toggle.id)
            assert {k: v.lower() for k, v in tools.items()} == {t.id: t.risk.value for t in toggle.tools}, (
                template_id,
                toggle.id,
            )
