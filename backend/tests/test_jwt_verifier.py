import base64
import json
import time
import uuid

import jwt
import pytest
from auth_helpers import ISSUER, CountingFetcher, TestKey, jwks_of
from cryptography.hazmat.primitives import serialization

from app.auth.jwt_verifier import Identity, InvalidToken, JwtVerifier


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def key() -> TestKey:
    return TestKey("kid-1")


@pytest.fixture
def fetcher(key) -> CountingFetcher:
    return CountingFetcher(jwks_of(key))


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def verifier(fetcher, clock) -> JwtVerifier:
    return JwtVerifier(issuer=ISSUER, fetch_jwks=fetcher, clock=clock)


async def test_a_valid_token_yields_the_user_id_from_its_subject(verifier, key):
    user = uuid.uuid4()
    identity = await verifier.verify(key.token(sub=str(user)))
    assert identity == Identity(user_id=user, role="authenticated", email="person@example.com")


async def test_rsa_signed_tokens_work_too(clock):
    rsa_key = TestKey("rsa-1", kind="rsa")
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks_of(rsa_key)), clock=clock)
    assert (await verifier.verify(rsa_key.token())).role == "authenticated"


@pytest.mark.parametrize("bad", ["", "   ", "abc", "a.b", "a.b.c", "Bearer x", "x" * 9000])
async def test_garbage_is_rejected(verifier, bad):
    with pytest.raises(InvalidToken):
        await verifier.verify(bad)


async def test_an_expired_token_is_rejected(verifier, key):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(exp=int(time.time()) - 3600))


async def test_a_token_expiring_within_the_leeway_is_still_accepted(verifier, key):
    assert await verifier.verify(key.token(exp=int(time.time()) - 3))


async def test_a_token_without_an_expiry_is_rejected(verifier, key):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(exp=None))


@pytest.mark.parametrize("claim", ["sub", "aud", "iss"])
async def test_a_token_missing_a_required_claim_is_rejected(verifier, key, claim):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(**{claim: None}))


async def test_wrong_audience_is_rejected(verifier, key):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(aud="someone-else"))


async def test_a_token_from_another_project_is_rejected(verifier, key):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(iss="https://other.supabase.co/auth/v1"))


async def test_a_token_signed_with_an_unknown_key_is_rejected(verifier):
    attacker = TestKey("kid-1")  # same kid, different private key
    with pytest.raises(InvalidToken):
        await verifier.verify(attacker.token())


async def test_a_tampered_payload_is_rejected(verifier, key):
    header, payload, signature = key.token().split(".")
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    claims["sub"] = str(uuid.uuid4())  # swap in a different user
    forged = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    with pytest.raises(InvalidToken):
        await verifier.verify(f"{header}.{forged}.{signature}")


async def test_alg_none_is_rejected(verifier):
    token = jwt.encode(
        {
            "iss": ISSUER,
            "aud": "authenticated",
            "sub": str(uuid.uuid4()),
            "role": "authenticated",
            "exp": int(time.time()) + 600,
        },
        key=None,
        algorithm="none",
        headers={"kid": "kid-1"},
    )
    with pytest.raises(InvalidToken):
        await verifier.verify(token)


async def test_hs256_signed_with_the_public_key_as_secret_is_rejected(verifier, key):
    """The classic algorithm-confusion attack: sign an HMAC token using the public key bytes as the secret."""
    public_pem = key.private.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    with pytest.raises(InvalidToken):
        await verifier.verify(_hs256_forgery(public_pem))


def _hs256_forgery(secret: bytes) -> str:
    import hashlib
    import hmac

    def b64(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

    header = b64(json.dumps({"alg": "HS256", "typ": "JWT", "kid": "kid-1"}).encode())
    body = b64(
        json.dumps(
            {
                "iss": ISSUER,
                "aud": "authenticated",
                "sub": str(uuid.uuid4()),
                "role": "authenticated",
                "exp": int(time.time()) + 600,
            }
        ).encode()
    )
    signature = b64(hmac.new(secret, f"{header}.{body}".encode(), hashlib.sha256).digest())
    return f"{header}.{body}.{signature}"


async def test_a_token_without_a_kid_is_rejected(verifier, key):
    claims = {
        "iss": ISSUER,
        "aud": "authenticated",
        "sub": str(uuid.uuid4()),
        "role": "authenticated",
        "exp": int(time.time()) + 600,
    }
    with pytest.raises(InvalidToken):
        await verifier.verify(jwt.encode(claims, key.private, algorithm="ES256"))


async def test_a_non_uuid_subject_is_rejected(verifier, key):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(sub="not-a-uuid"))


@pytest.mark.parametrize(
    "claims", [{"role": "anon"}, {"role": "service_role"}, {"role": None}, {"is_anonymous": True}]
)
async def test_only_signed_in_users_count_as_users(verifier, key, claims):
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token(**claims))


async def test_keys_are_cached_between_requests(verifier, key, fetcher):
    await verifier.verify(key.token())
    await verifier.verify(key.token())
    assert fetcher.calls == 1


async def test_a_new_key_is_picked_up_after_rotation(key, fetcher, clock):
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=fetcher, clock=clock, refresh_cooldown_seconds=30)
    await verifier.verify(key.token())
    new_key = TestKey("kid-2")
    fetcher.jwks = jwks_of(key, new_key)
    clock.now += 31
    assert await verifier.verify(new_key.token())
    assert fetcher.calls == 2


async def test_forged_unknown_kids_cannot_hammer_the_key_endpoint(verifier, key, fetcher, clock):
    await verifier.verify(key.token())  # first fetch
    for i in range(50):
        with pytest.raises(InvalidToken):
            await verifier.verify(TestKey(f"unknown-{i}").token())
    assert fetcher.calls == 1, "unknown kids inside the cooldown must not trigger refetches"
    clock.now += 31
    with pytest.raises(InvalidToken):
        await verifier.verify(TestKey("unknown-again").token())
    assert fetcher.calls == 2


async def test_an_outage_of_the_key_endpoint_fails_closed(key, clock):
    fetcher = CountingFetcher(jwks_of(key))
    fetcher.fail = True
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=fetcher, clock=clock)
    with pytest.raises(InvalidToken):
        await verifier.verify(key.token())


async def test_unusable_keys_in_the_set_do_not_hide_good_ones(key, clock):
    jwks = {"keys": [{"kty": "oct", "k": "AAAA", "kid": "weird", "alg": "HS256"}, {"junk": True}, key.jwk]}
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks), clock=clock)
    assert await verifier.verify(key.token())


async def test_symmetric_keys_in_the_set_are_never_used(key, clock):
    jwks = {
        "keys": [
            {
                "kty": "oct",
                "k": base64.urlsafe_b64encode(b"secret-secret-secret-secret-1234").decode(),
                "kid": "hmac",
                "alg": "HS256",
            }
        ]
    }
    verifier = JwtVerifier(issuer=ISSUER, fetch_jwks=CountingFetcher(jwks), clock=clock)
    with pytest.raises(InvalidToken):
        await verifier.verify(_hs256_forgery(b"secret-secret-secret-secret-1234").replace("kid-1", "hmac"))
