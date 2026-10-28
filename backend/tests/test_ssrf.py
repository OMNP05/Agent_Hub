"""The SSRF guard: every blocked range, DNS rebinding, redirects, oversized and compressed responses.

No real network: the "wire" is a scripted fake backend, and name resolution is a fake resolver.
"""

import httpcore2 as httpcore
import httpx2 as httpx
import pytest

from app.llm.net import (
    ResponseTooLarge,
    SafeNetworkBackend,
    SafeTransport,
    UnsafeDestination,
    is_blocked_ip,
    make_safe_client,
    system_resolver,
    validate_base_url,
)

BLOCKED = [
    "127.0.0.1", "127.255.255.254", "0.0.0.0", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
    "192.168.1.1", "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.255", "192.0.0.1", "192.0.2.1",
    "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "239.255.255.255", "240.0.0.1",
    "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd00:ec2::254", "ff02::1",
    "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::7f00:1", "2002:7f00:1::1",
    "fe80::1%eth0", "not-an-ip", "", "999.1.1.1",
]  # fmt: skip
ALLOWED = [
    "8.8.8.8",
    "1.1.1.1",
    "104.18.7.192",
    "151.101.1.69",
    "2606:4700:4700::1111",
    "2a00:1450:4009:81f::200e",
]


@pytest.mark.parametrize("address", BLOCKED)
def test_blocked_addresses(address):
    assert is_blocked_ip(address) is True


@pytest.mark.parametrize("address", ALLOWED)
def test_public_addresses_allowed(address):
    assert is_blocked_ip(address) is False


@pytest.mark.parametrize(
    "url",
    [
        "http://api.example.com/v1",  # not https
        "ftp://api.example.com",
        "file:///etc/passwd",
        "//api.example.com",
        "api.example.com/v1",
        "https://user:pass@api.example.com/v1",  # credentials
        "https://api.example.com@evil.com/v1",
        "https://127.0.0.1/v1",
        "https://[::1]/v1",
        "https://169.254.169.254/latest/meta-data",
        "https://10.1.2.3",
        "https://192.168.0.1:8443",
        "https://[::ffff:127.0.0.1]/v1",
        "https://localhost/v1",
        "https://localhost.:443/v1",
        "https://metadata.google.internal/computeMetadata/v1",
        "https://service.local",
        "https://printer.lan",
        "https://intranet",  # no dot: an internal name
        "https://2130706433/",  # 127.0.0.1 as one decimal number
        "https://0x7f.0.0.1/",
        "https://017700000001/",
        "https://api.example.com/v1?x=1",
        "https://api.example.com/v1#frag",
        "https://api.example.com:notaport/v1",
        "https://api.example.com/v\n1",  # a control character inside the url
        "https://api.example.com/v1\r\nHost: evil",  # header injection attempt
        "https://api.example.com/ v1",
        "https://",
        "",
        "   ",
        "https://" + "a" * 600 + ".com",
    ],
)
def test_validate_base_url_rejects(url):
    with pytest.raises(ValueError):
        validate_base_url(url)


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("https://api.openai.com/v1", "https://api.openai.com/v1"),
        ("https://api.openai.com/v1/", "https://api.openai.com/v1"),
        ("https://api.openai.com/v1\n", "https://api.openai.com/v1"),  # surrounding whitespace is trimmed
        ("  https://gateway.company.com/llm  ", "https://gateway.company.com/llm"),
        ("https://gateway.company.com:8443/v1", "https://gateway.company.com:8443/v1"),
        ("https://8.8.8.8/v1", "https://8.8.8.8/v1"),
    ],
)
def test_validate_base_url_accepts(url, expected):
    assert validate_base_url(url) == expected


# -- the connect-time guard -------------------------------------------------------------------------------


class RecordingBackend(httpcore.AsyncMockBackend):
    """Fake wire: scripted bytes, and a record of every host/port the guard actually connected to."""

    def __init__(self, buffer):
        super().__init__(buffer)
        self.connects: list[tuple[str, int]] = []

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        self.connects.append((host, port))
        return await super().connect_tcp(host, port, timeout, local_address, socket_options)


def resolver_for(mapping: dict[str, list[str]]):
    async def resolve(host, port):
        return mapping[host]

    return resolve


def client_over(wire: RecordingBackend, resolver, max_bytes=1024):
    guard = SafeNetworkBackend(inner=wire, resolver=resolver)
    return httpx.AsyncClient(
        transport=SafeTransport(network_backend=guard, max_response_bytes=max_bytes), follow_redirects=False
    )


OK = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"


async def test_public_name_connects_to_the_checked_ip_not_the_name():
    wire = RecordingBackend([OK])
    client = client_over(wire, resolver_for({"api.example.com": ["93.184.216.34"]}))
    response = await client.get("https://api.example.com/models")
    assert response.status_code == 200
    assert wire.connects == [("93.184.216.34", 443)]  # the IP that was checked, never the hostname again


@pytest.mark.parametrize("answer", [["127.0.0.1"], ["169.254.169.254"], ["10.0.0.5"], ["::1"], []])
async def test_name_resolving_to_a_private_address_is_refused(answer):
    wire = RecordingBackend([OK])
    client = client_over(wire, resolver_for({"evil.example.com": answer}))
    with pytest.raises(UnsafeDestination):
        await client.get("https://evil.example.com/models")
    assert wire.connects == []  # nothing was ever dialled


async def test_a_mixed_answer_is_refused_if_any_address_is_private():
    wire = RecordingBackend([OK])
    client = client_over(wire, resolver_for({"mixed.example.com": ["93.184.216.34", "127.0.0.1"]}))
    with pytest.raises(UnsafeDestination):
        await client.get("https://mixed.example.com/models")
    assert wire.connects == []


async def test_dns_rebinding_cannot_swap_the_address_after_the_check():
    """The name answers 'public' first and 'loopback' afterwards. Only the first, checked, answer is used."""
    answers = iter([["93.184.216.34"], ["127.0.0.1"], ["127.0.0.1"]])

    async def rebinding(host, port):
        return next(answers)

    wire = RecordingBackend([OK])
    client = client_over(wire, rebinding)
    assert (await client.get("https://rebind.example.com/models")).status_code == 200
    assert wire.connects == [("93.184.216.34", 443)]  # the loopback answer was never used to connect


@pytest.mark.parametrize("host", ["127.0.0.1", "169.254.169.254", "10.0.0.1", "[::1]", "localhost"])
async def test_literal_and_localhost_destinations_are_refused_with_the_real_resolver(host):
    wire = RecordingBackend([OK])
    guard = SafeNetworkBackend(inner=wire, resolver=system_resolver)
    client = httpx.AsyncClient(transport=SafeTransport(network_backend=guard))
    with pytest.raises((UnsafeDestination, httpx.ConnectError)):
        await client.get(f"https://{host}/models")
    assert wire.connects == []


async def test_redirect_is_not_followed_into_a_blocked_range():
    redirect = b"HTTP/1.1 302 Found\r\nLocation: https://169.254.169.254/latest/\r\nContent-Length: 0\r\n\r\n"
    wire = RecordingBackend([redirect])
    client = client_over(wire, resolver_for({"api.example.com": ["93.184.216.34"]}))
    response = await client.get("https://api.example.com/models")
    assert response.status_code == 302  # handed back as an ordinary (failed) answer
    assert wire.connects == [("93.184.216.34", 443)]  # and no second connection was made


def test_safe_client_never_follows_redirects_or_reads_proxy_settings(monkeypatch):
    monkeypatch.setenv("HTTPS_PROXY", "http://proxy.internal:3128")
    client = make_safe_client()
    assert client.follow_redirects is False


async def test_oversized_declared_length_is_refused():
    big = b"HTTP/1.1 200 OK\r\nContent-Length: 5000000\r\n\r\n"
    client = client_over(RecordingBackend([big]), resolver_for({"a.example.com": ["93.184.216.34"]}))
    with pytest.raises(ResponseTooLarge):
        await client.get("https://a.example.com/models")


async def test_oversized_streamed_body_is_cut_off():
    chunk = b"x" * 400
    body = b"".join(b"190\r\n" + chunk + b"\r\n" for _ in range(10)) + b"0\r\n\r\n"  # 4000 bytes, no length
    wire = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + body
    client = client_over(RecordingBackend([wire]), resolver_for({"a.example.com": ["93.184.216.34"]}))
    with pytest.raises(ResponseTooLarge):
        await client.get("https://a.example.com/models")


async def test_compressed_responses_are_refused():
    gz = b"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\n\x1f\x8b"
    client = client_over(RecordingBackend([gz]), resolver_for({"a.example.com": ["93.184.216.34"]}))
    with pytest.raises(ResponseTooLarge):
        await client.get("https://a.example.com/models")


async def test_plain_http_is_refused_by_the_transport_itself():
    client = client_over(RecordingBackend([OK]), resolver_for({"a.example.com": ["93.184.216.34"]}))
    with pytest.raises(UnsafeDestination):
        await client.get("http://a.example.com/models")


async def test_unix_sockets_are_refused():
    guard = SafeNetworkBackend(inner=RecordingBackend([]), resolver=resolver_for({}))
    with pytest.raises(UnsafeDestination):
        await guard.connect_unix_socket("/var/run/docker.sock")
