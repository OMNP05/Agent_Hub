"""Helpers that mint real, signed Supabase-style tokens with throwaway keys. No network, no real secrets."""

import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from jwt.algorithms import ECAlgorithm, RSAAlgorithm

ISSUER = "https://testproject.supabase.co/auth/v1"


class TestKey:
    __test__ = False  # not a pytest class

    def __init__(self, kid: str, kind: str = "ec") -> None:
        self.kid = kid
        if kind == "ec":
            self.private = ec.generate_private_key(ec.SECP256R1())
            self.alg = "ES256"
            public_jwk = json.loads(ECAlgorithm.to_jwk(self.private.public_key()))
        else:
            self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            self.alg = "RS256"
            public_jwk = json.loads(RSAAlgorithm.to_jwk(self.private.public_key()))
        self.jwk = {**public_jwk, "kid": kid, "alg": self.alg, "use": "sig"}

    def token(self, **overrides) -> str:
        now = int(time.time())
        claims = {
            "iss": ISSUER,
            "aud": "authenticated",
            "sub": str(uuid.uuid4()),
            "role": "authenticated",
            "email": "person@example.com",
            "iat": now,
            "exp": now + 3600,
        }
        claims.update(overrides)
        # None removes a claim, so tests can build tokens that lack one.
        claims = {k: v for k, v in claims.items() if v is not None}
        return jwt.encode(claims, self.private, algorithm=self.alg, headers={"kid": self.kid})


def jwks_of(*keys: TestKey) -> dict:
    return {"keys": [k.jwk for k in keys]}


class CountingFetcher:
    """A JWKS fetcher that counts calls and can be swapped to simulate key rotation or an outage."""

    def __init__(self, jwks: dict) -> None:
        self.jwks = jwks
        self.calls = 0
        self.fail = False

    async def __call__(self) -> dict:
        from app.auth.jwt_verifier import KeyFetchError

        self.calls += 1
        if self.fail:
            raise KeyFetchError("down")
        return self.jwks
