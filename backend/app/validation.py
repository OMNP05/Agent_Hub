"""Shared input checks. Postgres text cannot hold a NUL character (it raises, which would be a 500), and
control characters in names or log-visible fields are never legitimate, so they are refused at the edge."""


def single_line(value: str) -> str:
    """For names and short labels: no control characters at all (so no newlines, tabs or NUL)."""
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError("control characters not allowed")
    return value


def multi_line(value: str) -> str:
    """For free text such as instructions: newlines and tabs are fine, other control characters are not."""
    if any((ord(c) < 32 and c not in "\n\r\t") or ord(c) == 127 for c in value):
        raise ValueError("control characters not allowed")
    return value
