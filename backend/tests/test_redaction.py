import io
import json
import logging

import pytest

from app import logging_setup
from app.logging_setup import REDACTED, JsonFormatter, redact, redact_text

# Fake credentials built at runtime so no secret-shaped literal exists in the repository.
JWT = ".".join(["eyJ" + "a" * 20, "b" * 20, "c" * 20])
FAKE_VALUES = {
    "jwt": JWT,
    "bearer": "Bearer " + "x" * 30,
    "supabase_secret": "sb_secret_" + "A1b2C3d4" * 3,
    "openai": "sk-" + "a1B2c3D4" * 4,
    "anthropic": "sk-ant-" + "a1B2c3D4" * 4,
    "github": "ghp_" + "a1B2c3D4e5" * 4,
    "google_key": "AIza" + "a1B2c3D4e5" * 4,
    "google_secret": "GOCSPX-" + "a1B2c3D4" * 3,
    "google_access": "ya29." + "a1B2c3D4" * 4,
}


@pytest.mark.parametrize("name", FAKE_VALUES)
def test_credential_shapes_are_removed_from_free_text(name):
    value = FAKE_VALUES[name]
    cleaned = redact_text(f"calling provider with {value} now")
    assert value not in cleaned
    assert REDACTED in cleaned


def test_the_password_inside_a_connection_url_is_removed():
    cleaned = redact_text(
        "connecting to postgresql://postgres.abc:SuperSecretPw1@host.example.com:5432/postgres"
    )
    assert "SuperSecretPw1" not in cleaned
    assert "host.example.com" in cleaned  # the host is useful and not secret


@pytest.mark.parametrize(
    "key",
    [
        "authorization",
        "Authorization",
        "password",
        "access_token",
        "refresh_token",
        "api_key",
        "apiKey",
        "client_secret",
        "code_verifier",
        "Cookie",
        "body",
        "snippet",
        "private_key",
    ],
)
def test_sensitive_field_names_hide_their_whole_value(key):
    assert redact({key: "anything at all"})[key] == REDACTED


def test_nested_structures_are_redacted():
    data = {"outer": {"inner": [{"access_token": "abc"}, f"text {FAKE_VALUES['openai']}"]}, "count": 3}
    cleaned = redact(data)
    assert cleaned["outer"]["inner"][0]["access_token"] == REDACTED
    assert FAKE_VALUES["openai"] not in json.dumps(cleaned)
    assert cleaned["count"] == 3


def test_non_secret_data_is_left_alone():
    assert redact({"tool": "github.list_followers", "items": 20, "ok": True}) == {
        "tool": "github.list_followers",
        "items": 20,
        "ok": True,
    }


def _logger_with_buffer():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    logger = logging.getLogger("agenthub.test." + str(id(stream)))
    logger.handlers[:] = [handler]
    logger.propagate = False
    logger.setLevel(logging.DEBUG)
    return logger, stream


def test_log_lines_are_json_with_correlation_ids():
    logger, stream = _logger_with_buffer()
    tokens = [
        logging_setup.request_id.set("req-123456789"),
        logging_setup.run_id.set("run-1"),
        logging_setup.agent_id.set("agent-1"),
        logging_setup.user_id.set("user-1"),
    ]
    try:
        logger.info("tool finished", extra={"tool": "github.list_followers", "items": 5})
    finally:
        for var, token in zip(
            (logging_setup.request_id, logging_setup.run_id, logging_setup.agent_id, logging_setup.user_id),
            tokens,
            strict=True,
        ):
            var.reset(token)
    line = json.loads(stream.getvalue())
    assert line["message"] == "tool finished"
    assert line["request_id"] == "req-123456789"
    assert (line["run_id"], line["agent_id"], line["user_id"]) == ("run-1", "agent-1", "user-1")
    assert line["tool"] == "github.list_followers" and line["items"] == 5


def test_secrets_in_messages_and_extras_never_reach_the_output():
    logger, stream = _logger_with_buffer()
    logger.info(
        "got %s back",
        FAKE_VALUES["jwt"],
        extra={
            "authorization": FAKE_VALUES["bearer"],
            "email_body": "Dear Sam, your PIN is 4821",
            "detail": FAKE_VALUES["github"],
        },
    )
    out = stream.getvalue()
    for secret in (FAKE_VALUES["jwt"], FAKE_VALUES["bearer"], FAKE_VALUES["github"], "4821"):
        assert secret not in out


def test_exception_logs_contain_only_the_exception_type():
    logger, stream = _logger_with_buffer()
    try:
        raise ValueError(f"bad token {FAKE_VALUES['openai']}")
    except ValueError:
        logger.exception("failed")
    line = json.loads(stream.getvalue())
    assert line["exception"] == "ValueError"
    assert FAKE_VALUES["openai"] not in stream.getvalue()


def test_ids_are_omitted_when_not_set():
    logger, stream = _logger_with_buffer()
    logger.info("plain")
    line = json.loads(stream.getvalue())
    assert "request_id" not in line and "run_id" not in line
