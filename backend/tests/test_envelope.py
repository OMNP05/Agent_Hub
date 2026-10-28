import base64
import logging
import os
import struct

import pytest

from app.config import Settings
from app.crypto import CryptoError, DecryptionError, Envelope, KeyRing, context_for, parse_key
from app.crypto.envelope import parse_retired_keys


def new_key() -> bytes:
    return os.urandom(32)


def env_with(*versions: int, current: int | None = None) -> tuple[Envelope, dict[int, bytes]]:
    keys = {v: new_key() for v in versions}
    return Envelope(KeyRing(current or max(versions), keys)), keys


CTX = context_for("connection_secrets", "access_token_enc", "row-1")
SECRET = "gho_exampleTokenValue1234567890"


def test_round_trip_text_and_bytes():
    env, _ = env_with(1)
    assert env.decrypt_text(env.encrypt(SECRET, CTX), CTX) == SECRET
    assert env.decrypt(env.encrypt(b"\x00\xff", CTX), CTX) == b"\x00\xff"


def test_ciphertext_does_not_contain_plaintext_and_is_randomised():
    env, _ = env_with(1)
    a, b = env.encrypt(SECRET, CTX), env.encrypt(SECRET, CTX)
    assert SECRET.encode() not in a
    assert a != b  # fresh data key and nonces every time


def test_every_flipped_byte_is_detected():
    env, _ = env_with(1)
    blob = env.encrypt(SECRET, CTX)
    for i in range(len(blob)):
        broken = bytearray(blob)
        broken[i] ^= 0x01
        with pytest.raises(DecryptionError):
            env.decrypt(bytes(broken), CTX)


def test_truncated_and_junk_blobs_fail():
    env, _ = env_with(1)
    blob = env.encrypt(SECRET, CTX)
    for bad in (b"", b"\x01", blob[:-1], blob[:20], b"x" * 200, bytes([9]) + blob[1:]):
        with pytest.raises(DecryptionError):
            env.decrypt(bad, CTX)
    with pytest.raises(DecryptionError):
        env.decrypt("not bytes", CTX)  # type: ignore[arg-type]


def test_wrong_master_key_fails():
    a, _ = env_with(1)
    b, _ = env_with(1)
    with pytest.raises(DecryptionError):
        b.decrypt(a.encrypt(SECRET, CTX), CTX)


def test_blob_is_bound_to_its_row_and_column():
    env, _ = env_with(1)
    blob = env.encrypt(SECRET, CTX)
    for other in (
        context_for("connection_secrets", "access_token_enc", "row-2"),  # another row (another agent)
        context_for("connection_secrets", "refresh_token_enc", "row-1"),  # another column
        context_for("llm_profile_secrets", "access_token_enc", "row-1"),  # another table
    ):
        with pytest.raises(DecryptionError):
            env.decrypt(blob, other)


def test_unknown_key_version_fails_without_leaking():
    old, keys = env_with(1)
    blob = old.encrypt(SECRET, CTX)
    only_new = Envelope(KeyRing(2, {2: new_key()}))
    with pytest.raises(DecryptionError) as err:
        only_new.decrypt(blob, CTX)
    assert SECRET not in str(err.value) and base64.b64encode(keys[1]).decode() not in str(err.value)


def test_new_data_uses_current_version():
    env, _ = env_with(1, 2, current=2)
    assert env.key_version_of(env.encrypt(SECRET, CTX)) == 2


def test_rotation_keeps_data_readable_and_moves_to_new_key():
    old_key = new_key()
    old = Envelope(KeyRing(1, {1: old_key}))
    blob = old.encrypt(SECRET, CTX)
    rotated_env = Envelope(KeyRing(2, {1: old_key, 2: new_key()}))
    assert rotated_env.decrypt_text(blob, CTX) == SECRET  # still readable during rotation

    moved = rotated_env.rewrap(blob, CTX)
    assert rotated_env.key_version_of(moved) == 2
    assert moved != blob
    # after the old key is deleted the data is still readable
    new_only = Envelope(KeyRing(2, {2: rotated_env._ring.keys[2]}))
    assert new_only.decrypt_text(moved, CTX) == SECRET
    # and the old key alone can no longer read it
    with pytest.raises(DecryptionError):
        old.decrypt(moved, CTX)


def test_rewrap_is_idempotent_and_keeps_the_data_ciphertext():
    old_key = new_key()
    blob = Envelope(KeyRing(1, {1: old_key})).encrypt(SECRET, CTX)
    env = Envelope(KeyRing(2, {1: old_key, 2: new_key()}))
    once = env.rewrap(blob, CTX)
    assert env.rewrap(once, CTX) == once
    # the secret itself was never re-encrypted: only the header and wrapped key changed
    assert once[-(len(blob) - (1 + 4 + 12 + 48)) :] == blob[-(len(blob) - (1 + 4 + 12 + 48)) :]


def test_rewrap_refuses_wrong_context():
    old_key = new_key()
    blob = Envelope(KeyRing(1, {1: old_key})).encrypt(SECRET, CTX)
    env = Envelope(KeyRing(2, {1: old_key, 2: new_key()}))
    with pytest.raises(DecryptionError):
        env.rewrap(blob, context_for("x", "y", "z"))


def test_header_version_cannot_be_forged_to_pick_another_key():
    env, _ = env_with(1, 2, current=2)
    blob = bytearray(env.encrypt(SECRET, CTX))
    blob[1:5] = struct.pack(">I", 1)  # claim it was wrapped by key 1
    with pytest.raises(DecryptionError):
        env.decrypt(bytes(blob), CTX)


def test_oversized_secret_refused():
    env, _ = env_with(1)
    with pytest.raises(CryptoError):
        env.encrypt(b"x" * (64 * 1024 + 1), CTX)


def test_key_parsing():
    good = base64.b64encode(new_key()).decode()
    assert len(parse_key(good)) == 32
    for bad in (
        "",
        "not base64!!",
        base64.b64encode(b"short").decode(),
        base64.b64encode(new_key() * 2).decode(),
    ):
        with pytest.raises(CryptoError):
            parse_key(bad)
    assert set(parse_retired_keys(f"1:{good}, 3:{good}")) == {1, 3}
    for bad in ("x", "0:" + good, "1:", "a:" + good):
        with pytest.raises(CryptoError):
            parse_retired_keys(bad)


def test_error_messages_never_contain_keys(caplog):
    key = new_key()
    text = base64.b64encode(key).decode()
    with pytest.raises(CryptoError) as err:
        parse_key(text[:-4])
    assert text not in str(err.value)
    assert text not in repr(KeyRing(1, {1: key})) and key.hex() not in repr(KeyRing(1, {1: key}))


def test_logs_stay_clean_through_a_full_cycle(caplog):
    caplog.set_level(logging.DEBUG)
    env, keys = env_with(1)
    blob = env.encrypt(SECRET, CTX)
    env.decrypt_text(blob, CTX)
    with pytest.raises(DecryptionError):
        env.decrypt(blob[:-1], CTX)
    logged = caplog.text
    assert SECRET not in logged and base64.b64encode(keys[1]).decode() not in logged


def test_from_settings_requires_a_key():
    with pytest.raises(CryptoError):
        Envelope.from_settings("", 1)
    key = base64.b64encode(new_key()).decode()
    assert Envelope.from_settings(key, 3).current_version == 3


def test_ring_requires_current_key():
    with pytest.raises(CryptoError):
        KeyRing(2, {1: new_key()})


# -- production startup refuses bad keys ---------------------------------------------------------------------


def _prod(**over) -> Settings:
    base = dict(
        _env_file=None,
        environment="production",
        public_base_url="https://api.example.com",
        supabase_url="https://x.supabase.co",
        supabase_secret_key="sb_secret_x",
        database_url="postgresql://u:p@h/db",
        master_encryption_key=base64.b64encode(new_key()).decode(),
    )
    base.update(over)
    return Settings(**base)


def test_production_accepts_a_valid_key():
    assert _prod().key_version == 1


@pytest.mark.parametrize("bad", ["not base64!!", base64.b64encode(b"short").decode()])
def test_production_refuses_a_malformed_key(bad):
    with pytest.raises(ValueError) as err:
        _prod(master_encryption_key=bad)
    assert bad not in str(err.value)


def test_production_refuses_malformed_retired_keys():
    with pytest.raises(ValueError):
        _prod(retired_master_keys="garbage")
