"""A small in-memory sliding-window rate limiter.

It is per instance. Cloud Run is capped at a couple of instances (plan B12), so this bounds abuse without a
shared store; it is a speed bump, and Supabase's own limits still apply behind it. Keys are never logged.
"""

import time
from collections import defaultdict, deque
from collections.abc import Callable

from app.errors import ApiError


class RateLimiter:
    def __init__(
        self, limit: int, window_seconds: float, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self._limit = limit
        self._window = window_seconds
        self._clock = clock
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def check(self, key: str) -> None:
        now = self._clock()
        hits = self._hits[key]
        while hits and now - hits[0] >= self._window:
            hits.popleft()
        if len(hits) >= self._limit:
            raise ApiError("rate_limited", 429)
        hits.append(now)
        # Keep the table from growing forever with one-off keys.
        if len(self._hits) > 10_000:
            for stale in [k for k, v in self._hits.items() if not v or now - v[-1] >= self._window]:
                del self._hits[stale]
