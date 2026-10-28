"""Structured JSON logging with redaction.

CLAUDE.md: never log tokens, keys, email bodies or full tool results. Redaction is a safety net on top of
not logging them in the first place: every message and every extra field passes through [redact] before
it is written.
"""

import json
import logging
import re
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any

# Correlation ids, set per request or per run. Missing ones are simply left out of the line.
request_id: ContextVar[str | None] = ContextVar("request_id", default=None)
run_id: ContextVar[str | None] = ContextVar("run_id", default=None)
agent_id: ContextVar[str | None] = ContextVar("agent_id", default=None)
user_id: ContextVar[str | None] = ContextVar("user_id", default=None)

REDACTED = "[REDACTED]"

# Field names whose values are never written, whatever they contain.
_SENSITIVE_KEYS = re.compile(
    r"(authorization|password|passwd|secret|token|api[_-]?key|apikey|cookie|credential|"
    r"private[_-]?key|client[_-]?secret|code[_-]?verifier|refresh|body|snippet|bearer)",
    re.IGNORECASE,
)

# Shapes of credentials that may appear inside free text.
_VALUE_PATTERNS = [
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),  # JWT
    re.compile(r"\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{8,}"),
    re.compile(r"\bsk-[A-Za-z0-9_-]{16,}"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{30,}"),
    re.compile(r"\bGOCSPX-[A-Za-z0-9_-]{16,}"),
    re.compile(r"\bya29\.[A-Za-z0-9_-]{20,}"),  # Google access token
    re.compile(r"(?i)(postgres(?:ql)?://[^:/\s]+:)[^@\s]+(@)"),  # password inside a connection URL
]


def redact_text(text: str) -> str:
    out = text
    for pattern in _VALUE_PATTERNS:
        if pattern.groups:
            out = pattern.sub(lambda m: f"{m.group(1)}{REDACTED}{m.group(2)}", out)
        else:
            out = pattern.sub(REDACTED, out)
    return out


def redact(value: Any, key: str | None = None) -> Any:
    """Redact a value of any JSON-like shape. A sensitive key hides its whole value."""
    if key is not None and _SENSITIVE_KEYS.search(key):
        return REDACTED
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return {str(k): redact(v, str(k)) for k, v in value.items()}
    if isinstance(value, list | tuple | set):
        return [redact(v) for v in value]
    if isinstance(value, int | float | bool) or value is None:
        return value
    # Anything else (objects, exceptions) is stringified and then scanned.
    return redact_text(str(value))


_STANDARD_ATTRS = set(logging.makeLogRecord({}).__dict__) | {"message", "asctime"}


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "time": datetime.fromtimestamp(record.created, tz=UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "message": redact_text(record.getMessage()),
        }
        for name, var in (
            ("request_id", request_id),
            ("run_id", run_id),
            ("agent_id", agent_id),
            ("user_id", user_id),
        ):
            value = var.get()
            if value:
                payload[name] = value
        for key, value in record.__dict__.items():
            if key not in _STANDARD_ATTRS and not key.startswith("_"):
                payload[key] = redact(value, key)
        if record.exc_info:
            # Stack traces can contain argument values, so only the exception type is logged.
            payload["exception"] = record.exc_info[0].__name__ if record.exc_info[0] else "Exception"
        return json.dumps(payload, ensure_ascii=False, default=str)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    # Access logs would print full URLs, which can carry OAuth codes and state.
    logging.getLogger("uvicorn.access").disabled = True
