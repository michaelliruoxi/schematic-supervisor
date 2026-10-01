"""Build speed and time remaining, measured over active building time only."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass

ACTIVE_STATES = frozenset({"BUILDING", "RESTOCKING", "STUCK"})
# The start build check can mark many pieces done at once; that jump is not build speed.
REBASELINE_STATES = frozenset({"CHECKING"})


@dataclass(frozen=True)
class Pace:
    per_minute: float
    remaining_seconds: float | None


class PaceTracker:
    """Actions finished per minute of active time, over the most recent active window."""

    def __init__(self, *, window_seconds: float = 600.0, minimum_seconds: float = 180.0) -> None:
        self._window = window_seconds
        self._minimum = minimum_seconds
        self._samples: deque[tuple[float, int]] = deque()
        self._identity: tuple[str, str] | None = None
        self._active_clock = 0.0
        self._last_time: float | None = None
        self._last_done: int | None = None

    def observe(self, now: float, state: str | None, identity: tuple[str, str] | None,
                done: int | None) -> None:
        """Record one poll; `now` is monotonic seconds."""
        if identity != self._identity or (done is not None and self._last_done is not None
                                          and done < self._last_done):
            self._reset(identity)
        if state is None or done is None or state in REBASELINE_STATES:
            # Work may continue while telemetry is unavailable. A new baseline keeps that
            # unobserved work out of a rate whose clock excludes the outage.
            self._reset(identity)
            return
        if state not in ACTIVE_STATES:
            # Time outside active states doesn't count toward the rate.
            self._last_time = None
            return
        if self._last_time is not None:
            self._active_clock += max(0.0, now - self._last_time)
        self._last_time = now
        self._last_done = done
        self._samples.append((self._active_clock, done))
        while self._samples and self._active_clock - self._samples[0][0] > self._window:
            self._samples.popleft()

    def pace(self, remaining_actions: int | None) -> Pace | None:
        if len(self._samples) < 2:
            return None
        (start, done_start), (end, done_end) = self._samples[0], self._samples[-1]
        span = end - start
        if span < self._minimum:
            return None
        per_minute = (done_end - done_start) / span * 60.0
        remaining = None
        if remaining_actions is not None and per_minute > 0:
            remaining = remaining_actions / per_minute * 60.0
        return Pace(per_minute, remaining)

    def _reset(self, identity: tuple[str, str] | None) -> None:
        self._identity = identity
        self._samples.clear()
        self._active_clock = 0.0
        self._last_time = None
        self._last_done = None


def format_remaining(seconds: float) -> str:
    minutes = int(round(seconds / 60))
    if minutes < 1:
        return "under a minute"
    days, minutes = divmod(minutes, 24 * 60)
    hours, minutes = divmod(minutes, 60)
    if days:
        return f"{days} d {hours} h"
    if hours:
        return f"{hours} h {minutes} m"
    return f"{minutes} m"
