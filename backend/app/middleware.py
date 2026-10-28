"""Request id: every request gets one, it appears in every log line, and it is returned to the caller."""

import re
import uuid

from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app import logging_setup

HEADER = "x-request-id"
# A caller-supplied id is accepted only if it is short and harmless, so it cannot inject into logs.
_SAFE = re.compile(r"^[A-Za-z0-9._-]{8,64}$")


class RequestIdMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        supplied = dict(scope["headers"]).get(HEADER.encode(), b"").decode("latin-1")
        rid = supplied if _SAFE.match(supplied) else uuid.uuid4().hex
        token = logging_setup.request_id.set(rid)

        async def send_with_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message)[HEADER] = rid
            await send(message)

        try:
            await self.app(scope, receive, send_with_id)
        finally:
            logging_setup.request_id.reset(token)
