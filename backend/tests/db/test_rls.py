"""Row-level security. These tests are the definition of "agent A cannot read agent B's data" (CLAUDE.md)."""

import psycopg
import pytest

# Tables a signed-in user may read their own rows of.
READABLE = [
    "llm_profiles",
    "agents",
    "connections",
    "conversations",
    "runs",
    "messages",
    "run_events",
    "approvals",
    "audit_logs",
    "devices",
    "schedules",
]
# Tables that hold ciphertext or handshakes: the app can never read them, not even its own.
SECRET = ["llm_profile_secrets", "connection_secrets", "oauth_states"]
APP_TABLES = READABLE + SECRET

TABLES_SQL = (
    "select c.relname, c.relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace "
    "where n.nspname = 'public' and c.relkind in ('r', 'p') order by 1"
)


def test_every_public_table_has_row_level_security_and_the_expected_policies(db):
    """Fails the day anyone adds a table without RLS, or a policy that should not exist."""
    tables = dict(db.all(TABLES_SQL))
    missing = sorted(name for name, rls in tables.items() if not rls)
    assert not missing, f"tables without row-level security: {missing}"

    expected = set(APP_TABLES) | {"schema_migrations"}
    assert set(tables) == expected, f"unexpected tables: {sorted(set(tables) ^ expected)}"

    by_table: dict[str, list] = {}
    for table, cmd, roles in db.all(
        "select tablename, cmd, roles::text[] from pg_policies where schemaname = 'public'"
    ):
        by_table.setdefault(table, []).append((cmd, roles))
    for table in [*SECRET, "schema_migrations"]:
        assert table not in by_table, f"{table} must have no policy at all"
    for table in READABLE:
        assert by_table[table] == [("SELECT", ["authenticated"])], (
            f"{table} should only allow SELECT for authenticated"
        )


def test_the_guard_really_catches_a_table_without_rls(db):
    """Proves the check above is not vacuous: a table added without RLS is reported."""
    db.run("create table public.sneaky (id int)")
    tables = dict(db.all(TABLES_SQL))
    assert tables["sneaky"] is False
    assert [name for name, rls in tables.items() if not rls] == ["sneaky"]


def test_anon_has_no_privileges_on_any_table(db):
    assert (
        db.all(
            "select table_name from information_schema.role_table_grants where table_schema = 'public' and grantee = 'anon'"
        )
        == []
    )


def test_authenticated_has_select_only_and_nothing_on_secret_tables(db):
    rows = db.all(
        "select table_name, privilege_type from information_schema.role_table_grants "
        "where table_schema = 'public' and grantee = 'authenticated'"
    )
    assert {p for _, p in rows} == {"SELECT"}
    assert {t for t, _ in rows} == set(READABLE)


@pytest.mark.parametrize("table", READABLE)
def test_a_user_sees_only_their_own_rows(db, seeded, table):
    alice, bob = seeded["alice"]["user"], seeded["bob"]["user"]
    db.as_user(alice)
    assert db.all(f"select user_id from public.{table}") == [(alice,)]
    # asking for the other user's rows explicitly still returns nothing
    assert db.one(f"select count(*) from public.{table} where user_id = %s", bob)[0] == 0


@pytest.mark.parametrize("table", SECRET)
def test_secret_tables_are_unreadable_by_their_owner(db, seeded, table):
    db.as_user(seeded["alice"]["user"])
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        db.run(f"select * from public.{table}")


@pytest.mark.parametrize("table", APP_TABLES)
def test_anon_cannot_read_any_table(db, seeded, table):
    db.as_anon()
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        db.run(f"select * from public.{table}")


# A column that exists in every table, so each UPDATE is valid SQL and is refused for privileges, not for syntax.
ANY_COLUMN = {
    "llm_profile_secrets": "key_version",
    "connection_secrets": "key_version",
    "oauth_states": "key_version",
}


@pytest.mark.parametrize("table", APP_TABLES)
@pytest.mark.parametrize("kind", ["insert", "update", "delete"])
def test_the_app_role_cannot_write_to_any_table(db, seeded, table, kind):
    column = ANY_COLUMN.get(table, "user_id")
    statement = {
        "insert": f"insert into public.{table} default values",
        "update": f"update public.{table} set {column} = {column}",
        "delete": f"delete from public.{table}",
    }[kind]
    db.as_user(seeded["alice"]["user"])
    db.expect_error(psycopg.errors.InsufficientPrivilege, statement)


def test_a_user_with_no_data_sees_nothing(db, seeded):
    nobody = db.user()
    db.as_user(nobody)
    for table in READABLE:
        assert db.one(f"select count(*) from public.{table}")[0] == 0


def test_a_request_without_a_user_sees_nothing(db, seeded):
    """An authenticated role with no `sub` claim matches no rows (auth.uid() is null)."""
    db.run("set local role authenticated")
    db.run("select set_config('request.jwt.claims', '{}', true)")
    for table in READABLE:
        assert db.one(f"select count(*) from public.{table}")[0] == 0


def test_the_backend_connection_can_see_both_users_data(db, seeded):
    db.as_backend()
    ids = {row[0] for row in db.all("select id from public.agents")}
    assert {seeded["alice"]["agent"], seeded["bob"]["agent"]} <= ids
    assert (
        db.one(
            "select count(*) from public.connection_secrets where connection_id = any(%s)",
            [seeded["alice"]["connection"], seeded["bob"]["connection"]],
        )[0]
        == 2
    )
