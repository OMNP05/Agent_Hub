"""How request handlers reach the database.

The backend connects as the project's own database role, which bypasses row-level security, so every query in
this code base must filter by the verified `user_id` itself (CLAUDE.md rule 7). Row-level security is the
second line of defence for any path that uses the app's `authenticated` role, not the first.

Handlers are async but psycopg's sync API is simple and well tested, so database work runs in a worker thread
through [run_db]. Tests replace the pool with one connection that is always rolled back.
"""

import contextlib
import logging
from collections.abc import Callable, Iterator
from typing import Annotated, Protocol

import psycopg
from fastapi import Depends, Request
from psycopg_pool import ConnectionPool
from starlette.concurrency import run_in_threadpool

from app.errors import ApiError

log = logging.getLogger("agenthub.db")


class Database(Protocol):
    def connection(self) -> contextlib.AbstractContextManager[psycopg.Connection]: ...


class PoolDatabase:
    """A small pool. One transaction per request: committed on success, rolled back on any exception."""

    def __init__(self, url: str, *, max_size: int = 4) -> None:
        self._pool = ConnectionPool(
            url,
            min_size=0,  # Cloud Run scales to zero; do not hold connections open for nothing
            max_size=max_size,
            kwargs={"prepare_threshold": None, "connect_timeout": 10},  # required for Supabase's pooler
            open=False,
            check=ConnectionPool.check_connection,
            timeout=10,
        )

    def open(self) -> None:
        self._pool.open(wait=False)

    def close(self) -> None:
        self._pool.close()

    def connection(self) -> contextlib.AbstractContextManager[psycopg.Connection]:
        return self._pool.connection()


class SingleConnectionDatabase:
    """Used by tests: every use is a savepoint on one outer connection, so nothing is ever committed."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    @contextlib.contextmanager
    def connection(self) -> Iterator[psycopg.Connection]:
        with self._conn.transaction():
            yield self._conn


def get_db(request: Request) -> Database:
    db: Database | None = getattr(request.app.state, "db", None)
    if db is None:
        raise ApiError("service_unavailable", 503)
    return db


Db = Annotated[Database, Depends(get_db)]


async def run_db[T](db: Database, fn: Callable[..., T], *args) -> T:
    """Runs fn(connection, *args) in a worker thread inside one transaction."""

    def call() -> T:
        try:
            with db.connection() as conn:
                return fn(conn, *args)
        except psycopg.OperationalError as e:
            # Pool timeout, network, paused project: a clean 503, never a raw driver error.
            log.error("database unavailable", extra={"error_type": type(e).__name__})
            raise ApiError("service_unavailable", 503) from None

    return await run_in_threadpool(call)


@contextlib.asynccontextmanager
async def transaction(db: Database):
    """One transaction held across awaits (for example around a provider call). The connection is entered and
    left in worker threads; commit on success, rollback on any exception, always released."""
    manager = db.connection()
    conn = await run_in_threadpool(manager.__enter__)
    try:
        yield conn
    except BaseException as e:
        suppressed = await run_in_threadpool(manager.__exit__, type(e), e, e.__traceback__)
        if not suppressed:
            raise
    else:
        await run_in_threadpool(manager.__exit__, None, None, None)


async def in_thread[T](fn: Callable[..., T], *args) -> T:
    """Runs a blocking database step on the connection held by [transaction]."""
    return await run_in_threadpool(fn, *args)
