"""SSRF-safe outbound HTTP for user-supplied LLM endpoints (CLAUDE.md security rule 8).

A user can type any base URL, so the backend must never be a way to reach internal services (the Cloud Run
metadata server, other containers, private networks). The guard sits where it cannot be bypassed:

  * the check happens at connect time, on the addresses the name really resolves to, and the connection is
    then made to that exact checked IP. Resolving once and connecting to the result closes DNS rebinding
    (a name that answers "public" to a pre-check and "127.0.0.1" to the real connection);
  * if ANY resolved address is non-public the destination is refused, so a mixed answer cannot slip through;
  * https only, no credentials in the URL, redirects are never followed (a 3xx is just a failed call);
  * every response is size capped, compressed responses are refused (decompression bombs), timeouts are set.
"""

import asyncio
import ipaddress
import re
import socket
import ssl
from collections.abc import AsyncIterator, Awaitable, Callable
from urllib.parse import urlsplit

import httpcore2 as httpcore
import httpx2 as httpx

MAX_RESPONSE_BYTES = 8 * 1024 * 1024
DEFAULT_TIMEOUT = httpx.Timeout(connect=10.0, read=60.0, write=30.0, pool=10.0)
MAX_URL_LENGTH = 500


class UnsafeDestination(Exception):
    """The address is not allowed. Carries no host or address, so it is safe to log and to map to an error."""


class ResponseTooLarge(Exception):
    pass


# -- address classification ---------------------------------------------------------------------------------

# Tunnelling prefixes that embed an IPv4 address: nobody legitimate serves an LLM API from them.
_EMBEDDING_NETWORKS = tuple(
    ipaddress.ip_network(n) for n in ("64:ff9b::/96", "64:ff9b:1::/48", "2002::/16", "2001::/32")
)


def is_blocked_ip(value: str) -> bool:
    """True for anything that is not an ordinary public unicast address. Unparseable input is blocked."""
    if "%" in value:  # a scoped IPv6 address is link-local by definition
        return True
    try:
        ip = ipaddress.ip_address(value)
    except ValueError:
        return True
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return is_blocked_ip(str(ip.ipv4_mapped))
        if any(ip in network for network in _EMBEDDING_NETWORKS):
            return True
    return (
        not ip.is_global
        or ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )


_BLOCKED_SUFFIXES = (
    ".local",
    ".localhost",
    ".internal",
    ".localdomain",
    ".lan",
    ".home",
    ".corp",
    ".intranet",
)
_NUMERIC_HOST = re.compile(r"^[0-9a-fx.]+$", re.IGNORECASE)  # decimal / octal / hex spellings of an IPv4


def validate_base_url(value: str) -> str:
    """Cheap static check used when a profile is saved. It gives early, friendly rejection; the connect-time
    guard above is what actually protects, because names can change what they resolve to later."""
    url = value.strip()
    if not url or len(url) > MAX_URL_LENGTH or any(ord(c) < 33 or ord(c) == 127 for c in url):
        raise ValueError("invalid url")
    parts = urlsplit(url)
    if parts.scheme != "https":
        raise ValueError("https required")
    if parts.username is not None or parts.password is not None or "@" in parts.netloc:
        raise ValueError("credentials in url")
    if parts.query or parts.fragment or "?" in url or "#" in url:
        raise ValueError("query or fragment not allowed")
    try:
        host = parts.hostname
        parts.port  # noqa: B018 - raises ValueError for a bad port
    except ValueError:
        raise ValueError("invalid url") from None
    if not host:
        raise ValueError("host required")
    host = host.lower().rstrip(".")
    if _is_ip_literal(host):
        if is_blocked_ip(host):
            raise ValueError("address not allowed")
    elif _NUMERIC_HOST.match(host) or "." not in host or host.endswith(_BLOCKED_SUFFIXES):
        # Odd spellings of an IPv4 (2130706433, 0x7f.1), bare names like "localhost" and internal suffixes.
        raise ValueError("host not allowed")
    return url.rstrip("/")


def _is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return False
    return True


# -- the guarded network layer ------------------------------------------------------------------------------

Resolver = Callable[[str, int], Awaitable[list[str]]]


async def system_resolver(host: str, port: int) -> list[str]:
    loop = asyncio.get_running_loop()
    try:
        infos = await loop.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        raise httpcore.ConnectError("name resolution failed") from None
    return list(dict.fromkeys(info[4][0] for info in infos))


class SafeNetworkBackend(httpcore.AsyncNetworkBackend):
    def __init__(
        self, inner: httpcore.AsyncNetworkBackend | None = None, resolver: Resolver = system_resolver
    ) -> None:
        self._inner = inner or httpcore.AnyIOBackend()
        self._resolve = resolver

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        addresses = await self._resolve(host, port)
        if not addresses or any(is_blocked_ip(a) for a in addresses):
            raise UnsafeDestination("destination not allowed")
        last: Exception | None = None
        for address in addresses:
            try:
                # Connect to the address that was just checked, never to the name again.
                return await self._inner.connect_tcp(
                    address, port, timeout=timeout, local_address=local_address, socket_options=socket_options
                )
            except (httpcore.ConnectError, httpcore.ConnectTimeout) as e:
                last = e
        raise last or httpcore.ConnectError("connect failed")

    async def connect_unix_socket(self, path, timeout=None, socket_options=None):
        raise UnsafeDestination("destination not allowed")

    async def sleep(self, seconds: float) -> None:
        await self._inner.sleep(seconds)


class _CappedStream(httpx.AsyncByteStream):
    def __init__(self, inner: httpx.AsyncByteStream, limit: int) -> None:
        self._inner = inner
        self._limit = limit

    async def __aiter__(self) -> AsyncIterator[bytes]:
        total = 0
        async for chunk in self._inner:
            total += len(chunk)
            if total > self._limit:
                raise ResponseTooLarge("response too large")
            yield chunk

    async def aclose(self) -> None:
        await self._inner.aclose()


class SafeTransport(httpx.AsyncHTTPTransport):
    def __init__(
        self,
        *,
        network_backend: httpcore.AsyncNetworkBackend | None = None,
        max_response_bytes: int = MAX_RESPONSE_BYTES,
        ssl_context: ssl.SSLContext | None = None,
    ) -> None:
        super().__init__()
        self._max_bytes = max_response_bytes
        # Replace the parent's pool with one that connects through the guard. No proxies, no HTTP/2.
        self._pool = httpcore.AsyncConnectionPool(
            ssl_context=ssl_context or httpx.create_ssl_context(),
            max_connections=20,
            max_keepalive_connections=5,
            keepalive_expiry=30.0,
            http1=True,
            http2=False,
            network_backend=network_backend or SafeNetworkBackend(),
        )

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        if request.url.scheme != "https" or request.url.userinfo:
            raise UnsafeDestination("destination not allowed")
        # Ask for an uncompressed body so the byte cap below is also a cap on the decoded size.
        request.headers["accept-encoding"] = "identity"
        response = await super().handle_async_request(request)
        length = response.headers.get("content-length", "")
        encoding = response.headers.get("content-encoding", "identity").lower()
        if (length.isdigit() and int(length) > self._max_bytes) or encoding not in ("", "identity"):
            await response.aclose()
            raise ResponseTooLarge("response refused")
        return httpx.Response(
            status_code=response.status_code,
            headers=response.headers,
            stream=_CappedStream(response.stream, self._max_bytes),  # type: ignore[arg-type]
            extensions=response.extensions,
        )


def make_safe_client(
    timeout: httpx.Timeout | None = None,
    *,
    max_response_bytes: int = MAX_RESPONSE_BYTES,
    network_backend: httpcore.AsyncNetworkBackend | None = None,
) -> httpx.AsyncClient:
    """The only way the backend talks to a user-chosen host."""
    return httpx.AsyncClient(
        transport=SafeTransport(network_backend=network_backend, max_response_bytes=max_response_bytes),
        timeout=timeout or DEFAULT_TIMEOUT,
        follow_redirects=False,  # a redirect is a failed call: it could point anywhere
        trust_env=False,  # ignore HTTP(S)_PROXY and friends
    )


class RefuseSyncTransport(httpx.BaseTransport):
    """Some SDKs also build a synchronous client. It must never be used, so it is made unable to connect."""

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        raise UnsafeDestination("synchronous calls are not allowed")
