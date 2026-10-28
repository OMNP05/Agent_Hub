"""Envelope encryption for provider tokens and LLM keys (CLAUDE.md security rule 5).

Every secret gets its own random 256-bit data key (DEK). The secret is encrypted with AES-256-GCM under the
DEK, and the DEK is itself encrypted ("wrapped") with AES-256-GCM under the master key. Only the wrapped DEK
is stored, next to the ciphertext, so:
  * the master key lives outside the database (Secret Manager / env): a database leak alone reveals nothing;
  * rotating the master key only re-wraps small DEKs, the secrets are never re-encrypted;
  * every blob carries the version of the master key that wrapped it.

A `context` string (table, column and row id) is bound into both encryptions as associated data, so a blob
copied from one row to another, or one column to another, fails to decrypt instead of leaking.

Blob layout (all binary, stored in a bytea column):
    1 byte  format (1) | 4 bytes master key version | 12 wrap nonce | 48 wrapped DEK (32 + 16 tag)
    | 12 data nonce | ciphertext + 16 byte tag
"""

import base64
import binascii
import os
import struct
from collections.abc import Mapping
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

FORMAT = 1
_NONCE = 12
_KEY_LEN = 32
_WRAPPED = _KEY_LEN + 16
_HEADER = 1 + 4
_MIN_LEN = _HEADER + _NONCE + _WRAPPED + _NONCE + 16
_MAX_PLAINTEXT = 64 * 1024


class CryptoError(Exception):
    """Encryption or decryption failed. The message never contains key material or plaintext."""


class DecryptionError(CryptoError):
    """Wrong key, wrong context, or the blob was tampered with. Deliberately gives no further detail."""


def parse_key(value: str) -> bytes:
    """A master key is 32 random bytes in base64. Anything else is refused up front."""
    try:
        raw = base64.b64decode(value.strip(), validate=True)
    except (binascii.Error, ValueError):
        raise CryptoError("master key is not valid base64") from None
    if len(raw) != _KEY_LEN:
        raise CryptoError("master key must be exactly 32 bytes")
    return raw


def parse_retired_keys(value: str) -> dict[int, bytes]:
    """`1:<base64>,2:<base64>` -> {1: key, 2: key}. Old keys are kept only so old data stays readable."""
    keys: dict[int, bytes] = {}
    for item in filter(None, (part.strip() for part in value.split(","))):
        version_text, _, key_text = item.partition(":")
        if not version_text.isdigit() or int(version_text) < 1 or not key_text:
            raise CryptoError("retired keys must look like 1:<base64>,2:<base64>")
        keys[int(version_text)] = parse_key(key_text)
    return keys


def context_for(table: str, column: str, row_id: object) -> bytes:
    """The associated data that ties a ciphertext to exactly one place in the database."""
    return f"agenthub:v1:{table}:{column}:{row_id}".encode()


@dataclass(frozen=True)
class KeyRing:
    current_version: int
    keys: Mapping[int, bytes]

    def __post_init__(self) -> None:
        if self.current_version not in self.keys:
            raise CryptoError("current key version is missing from the key ring")

    def __repr__(self) -> str:  # never print key bytes
        return f"KeyRing(current_version={self.current_version}, versions={sorted(self.keys)})"


class Envelope:
    def __init__(self, ring: KeyRing) -> None:
        self._ring = ring

    @classmethod
    def from_settings(cls, master_key: str, key_version: int, retired: str = "") -> "Envelope":
        if not master_key:
            raise CryptoError("MASTER_ENCRYPTION_KEY is not set")
        keys = parse_retired_keys(retired)
        keys[key_version] = parse_key(master_key)
        return cls(KeyRing(key_version, keys))

    @property
    def current_version(self) -> int:
        return self._ring.current_version

    def encrypt(self, plaintext: str | bytes, context: bytes) -> bytes:
        data = plaintext.encode() if isinstance(plaintext, str) else plaintext
        if len(data) > _MAX_PLAINTEXT:
            raise CryptoError("secret is too large")
        version = self._ring.current_version
        dek = AESGCM.generate_key(bit_length=256)
        wrap_nonce, data_nonce = os.urandom(_NONCE), os.urandom(_NONCE)
        wrapped = AESGCM(self._ring.keys[version]).encrypt(wrap_nonce, dek, self._wrap_aad(version, context))
        ciphertext = AESGCM(dek).encrypt(data_nonce, data, context)
        return struct.pack(">BI", FORMAT, version) + wrap_nonce + wrapped + data_nonce + ciphertext

    def decrypt(self, blob: bytes, context: bytes) -> bytes:
        dek, parts = self._unwrap(blob, context)
        try:
            return AESGCM(dek).decrypt(parts.data_nonce, parts.ciphertext, context)
        except InvalidTag:
            raise DecryptionError("cannot decrypt") from None

    def decrypt_text(self, blob: bytes, context: bytes) -> str:
        try:
            return self.decrypt(blob, context).decode()
        except UnicodeDecodeError:
            raise DecryptionError("cannot decrypt") from None

    def key_version_of(self, blob: bytes) -> int:
        return self._parse(blob).version

    def rewrap(self, blob: bytes, context: bytes) -> bytes:
        """Moves the blob to the current master key by re-wrapping only the data key. The secret itself is
        neither decrypted nor changed. Blobs already on the current key are returned unchanged."""
        dek, parts = self._unwrap(blob, context)
        if parts.version == self._ring.current_version:
            return blob
        version = self._ring.current_version
        wrap_nonce = os.urandom(_NONCE)
        wrapped = AESGCM(self._ring.keys[version]).encrypt(wrap_nonce, dek, self._wrap_aad(version, context))
        return (
            struct.pack(">BI", FORMAT, version) + wrap_nonce + wrapped + parts.data_nonce + parts.ciphertext
        )

    # -- internals ----------------------------------------------------------------------------------------

    @staticmethod
    def _wrap_aad(version: int, context: bytes) -> bytes:
        return b"wrap:" + struct.pack(">I", version) + b":" + context

    @dataclass(frozen=True)
    class _Parts:
        version: int
        wrap_nonce: bytes
        wrapped: bytes
        data_nonce: bytes
        ciphertext: bytes

    def _parse(self, blob: bytes) -> "_Parts":
        if not isinstance(blob, bytes | bytearray | memoryview):
            raise DecryptionError("cannot decrypt")
        blob = bytes(blob)
        if len(blob) < _MIN_LEN or blob[0] != FORMAT:
            raise DecryptionError("cannot decrypt")
        version = struct.unpack(">I", blob[1:_HEADER])[0]
        at = _HEADER
        wrap_nonce = blob[at : at + _NONCE]
        at += _NONCE
        wrapped = blob[at : at + _WRAPPED]
        at += _WRAPPED
        data_nonce = blob[at : at + _NONCE]
        at += _NONCE
        return self._Parts(version, wrap_nonce, wrapped, data_nonce, blob[at:])

    def _unwrap(self, blob: bytes, context: bytes) -> tuple[bytes, "_Parts"]:
        parts = self._parse(blob)
        master = self._ring.keys.get(parts.version)
        if master is None:
            raise DecryptionError("cannot decrypt")
        try:
            dek = AESGCM(master).decrypt(
                parts.wrap_nonce, parts.wrapped, self._wrap_aad(parts.version, context)
            )
        except InvalidTag:
            raise DecryptionError("cannot decrypt") from None
        return dek, parts
