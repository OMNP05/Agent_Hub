"""The migration runner: ordering, checksums, idempotence and the safety of a failed migration."""

from pathlib import Path

import psycopg
import pytest

from app.db import migrate
from app.db.migrate import Migration, MigrationError, load_migrations


def test_the_shipped_migrations_load_in_order():
    migrations = load_migrations()
    assert migrations, "no migrations found"
    assert [m.version for m in migrations] == [f"{i:04d}" for i in range(1, len(migrations) + 1)]
    assert migrations[0].name == "0001_init"


def test_bad_names_and_gaps_are_refused(tmp_path: Path):
    (tmp_path / "init.sql").write_text("select 1")
    with pytest.raises(MigrationError):
        load_migrations(tmp_path)
    (tmp_path / "init.sql").unlink()
    (tmp_path / "0001_a.sql").write_text("select 1")
    (tmp_path / "0003_c.sql").write_text("select 1")
    with pytest.raises(MigrationError):
        load_migrations(tmp_path)


def test_checksum_is_stable_and_changes_with_content():
    assert (
        Migration("0001", "0001_x", "select 1").checksum == Migration("0001", "0001_x", "select 1").checksum
    )
    assert (
        Migration("0001", "0001_x", "select 1").checksum != Migration("0001", "0001_x", "select 2").checksum
    )


def test_applying_twice_does_nothing_the_second_time(db):
    assert migrate.apply_pending(db.conn) == []  # the session fixture already applied everything


def test_every_migration_is_recorded_with_its_checksum(db):
    recorded = {v: c for v, c in db.all("select version, checksum from public.schema_migrations")}
    for m in load_migrations():
        assert recorded[m.version] == m.checksum


def test_editing_an_applied_migration_is_refused(db):
    original = load_migrations()
    tampered = [Migration(original[0].version, original[0].name, original[0].sql + "\n-- sneaky edit")]
    with pytest.raises(MigrationError, match="changed after it was applied"):
        migrate.apply_pending(db.conn, tampered)


def test_a_failing_migration_leaves_nothing_behind(db):
    bad = Migration("0002", "0002_bad", "create table public.half_done (id int); select 1/0;")
    db.run("savepoint attempt")
    with pytest.raises(psycopg.errors.DivisionByZero):
        migrate.apply_pending(db.conn, [*load_migrations(), bad])
    db.run("rollback to savepoint attempt")
    assert db.one("select to_regclass('public.half_done')")[0] is None
    assert db.one("select count(*) from public.schema_migrations where version = '0002'")[0] == 0


def test_bookkeeping_table_is_invisible_to_the_app_roles(db):
    db.run("set local role authenticated")
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        db.run("select * from public.schema_migrations")
