"""Forward-only SQL migrations.

Files in app/db/migrations named NNNN_name.sql are applied in order, each in its own transaction together with
its bookkeeping row, so a failing migration leaves nothing half-applied. An applied file's checksum is stored;
editing it afterwards is refused, because the database would no longer match the code.

    python -m app.db.migrate            apply everything pending
    python -m app.db.migrate --status   show applied and pending migrations
"""

import hashlib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import psycopg

MIGRATIONS_DIR = Path(__file__).parent / "migrations"
_NAME = re.compile(r"^(\d{4})_[a-z0-9_]+\.sql$")
# Arbitrary constant: serialises concurrent runs (for example two deploys at once).
_LOCK_ID = 7_431_900_001


class MigrationError(Exception):
    pass


@dataclass(frozen=True)
class Migration:
    version: str
    name: str
    sql: str

    @property
    def checksum(self) -> str:
        return hashlib.sha256(self.sql.encode("utf-8")).hexdigest()


def load_migrations(directory: Path = MIGRATIONS_DIR) -> list[Migration]:
    found: list[Migration] = []
    for path in sorted(directory.glob("*.sql")):
        match = _NAME.match(path.name)
        if not match:
            raise MigrationError(f"bad migration file name: {path.name}")
        found.append(Migration(match.group(1), path.stem, path.read_text(encoding="utf-8")))
    versions = [m.version for m in found]
    if len(versions) != len(set(versions)):
        raise MigrationError("duplicate migration version")
    if versions and versions != [f"{i:04d}" for i in range(1, len(versions) + 1)]:
        raise MigrationError("migration versions must be consecutive starting at 0001")
    return found


def _ensure_table(conn: psycopg.Connection) -> None:
    conn.execute(
        """
        create table if not exists public.schema_migrations (
            version text primary key,
            name text not null,
            checksum text not null,
            applied_at timestamptz not null default now()
        )
        """
    )
    # Bookkeeping must be invisible to the API roles like everything else.
    conn.execute("alter table public.schema_migrations enable row level security")
    conn.execute("revoke all on public.schema_migrations from anon, authenticated")


def applied(conn: psycopg.Connection) -> dict[str, str]:
    _ensure_table(conn)
    return {row[0]: row[1] for row in conn.execute("select version, checksum from public.schema_migrations")}


def apply_pending(conn: psycopg.Connection, migrations: list[Migration] | None = None) -> list[str]:
    """Applies pending migrations on [conn] without committing; the caller decides (tests roll back)."""
    migrations = load_migrations() if migrations is None else migrations
    conn.execute("select pg_advisory_xact_lock(%s)", (_LOCK_ID,))
    done = applied(conn)
    newly: list[str] = []
    for m in migrations:
        if m.version in done:
            if done[m.version] != m.checksum:
                raise MigrationError(
                    f"{m.name} was changed after it was applied; add a new migration instead"
                )
            continue
        conn.execute(m.sql)
        conn.execute(
            "insert into public.schema_migrations (version, name, checksum) values (%s, %s, %s)",
            (m.version, m.name, m.checksum),
        )
        newly.append(m.name)
    return newly


def main(argv: list[str]) -> int:
    from app.config import get_settings

    url = get_settings().database_url.get_secret_value()
    if not url:
        print("DATABASE_URL is not set")
        return 2
    try:
        # prepare_threshold=None: a transaction-mode pooler cannot hold prepared statements.
        with psycopg.connect(url, connect_timeout=15, prepare_threshold=None) as conn:
            if "--status" in argv:
                done = applied(conn)
                for m in load_migrations():
                    print(("applied  " if m.version in done else "PENDING  ") + m.name)
                conn.commit()
                return 0
            newly = apply_pending(conn)
            conn.commit()
            print("applied: " + (", ".join(newly) if newly else "nothing to do"))
            return 0
    except MigrationError as e:
        print(f"migration error: {e}")
        return 1
    except psycopg.Error as e:
        # Only the error class and SQLSTATE: the message text can contain parts of the connection string.
        print(f"database error: {type(e).__name__} {getattr(e, 'sqlstate', None) or ''}".strip())
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
