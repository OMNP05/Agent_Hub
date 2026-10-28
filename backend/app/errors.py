"""One error format for the whole API: {"error": {"code": "..."}}.

Codes are the ones the Android app already maps (docs/API_CONTRACT.md). No message text, stack trace, provider
body or validation detail is ever returned, because any of them can contain user data or secrets.
"""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("agenthub.errors")


class ApiError(Exception):
    """Raise this from any handler. [code] is a stable machine-readable string."""

    def __init__(self, code: str, status_code: int = 400) -> None:
        super().__init__(code)
        self.code = code
        self.status_code = status_code


# Standard HTTP statuses that have a meaningful default code when nothing more specific was raised.
_STATUS_CODES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "conflict",
    413: "payload_too_large",
    422: "invalid_request",
    429: "rate_limited",
}


def error_response(code: str, status_code: int, headers: dict[str, str] | None = None) -> JSONResponse:
    return JSONResponse({"error": {"code": code}}, status_code=status_code, headers=headers)


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return error_response(exc.code, exc.status_code)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _STATUS_CODES.get(exc.status_code, "error")
        return error_response(code, exc.status_code, getattr(exc, "headers", None))

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, __: RequestValidationError) -> JSONResponse:
        # The default body echoes the rejected input, which can include secrets, so only the code is returned.
        return error_response("invalid_request", 422)

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled error", extra={"error_type": type(exc).__name__})
        return error_response("internal_error", 500)
