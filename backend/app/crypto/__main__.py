"""Operator commands. Prints counts only, never keys or data.

    python -m app.crypto generate-key    prints a new random master key (store it in Secret Manager / .env)
    python -m app.crypto rotate          re-wraps every stored secret under the current KEY_VERSION
    python -m app.crypto status          counts rows per master key version

To rotate: generate a key, set MASTER_ENCRYPTION_KEY to it and KEY_VERSION to old+1, put the old key in
RETIRED_MASTER_KEYS as `<old version>:<old key>`, run `rotate`, then (once `status` shows only the new
version) delete the old key.
"""

import base64
import os
import sys

import psycopg

from app.config import get_settings
from app.crypto.envelope import CryptoError, Envelope
from app.crypto.store import ENCRYPTED_COLUMNS, rotate


def _envelope() -> Envelope:
    s = get_settings()
    return Envelope.from_settings(
        s.master_encryption_key.get_secret_value(), s.key_version, s.retired_master_keys.get_secret_value()
    )


def _connect() -> psycopg.Connection:
    url = get_settings().database_url.get_secret_value()
    if not url:
        raise CryptoError("DATABASE_URL is not set")
    return psycopg.connect(url, connect_timeout=20, prepare_threshold=None)


def main(argv: list[str]) -> int:
    command = argv[0] if argv else ""
    try:
        if command == "generate-key":
            print(base64.b64encode(os.urandom(32)).decode())
            return 0
        if command == "rotate":
            env = _envelope()
            with _connect() as conn:  # commits on success, rolls back on any error
                counts = rotate(conn, env)
            print(f"rotated to key version {env.current_version}: {counts}")
            return 0
        if command == "status":
            with _connect() as conn:
                for table, _pk, _cols in ENCRYPTED_COLUMNS:
                    # table names come from the fixed ENCRYPTED_COLUMNS list, never from input
                    query = f"select key_version, count(*) from public.{table} group by 1 order by 1"  # noqa: S608
                    rows = conn.execute(query).fetchall()
                    print(f"{table}: {dict(rows) or 'empty'}")
            return 0
    except CryptoError as e:
        print(f"error: {e}")
        return 1
    except psycopg.Error as e:
        # Only the error class: connection strings and SQL text can contain secrets.
        print(f"database error: {type(e).__name__}")
        return 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
