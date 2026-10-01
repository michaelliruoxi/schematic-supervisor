"""Edge-triggered attention alerts from successive monitor snapshots."""

from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Callable, Collection

ACTIVE_STATES = frozenset({"BUILDING", "RESTOCKING", "VERIFYING", "STUCK", "LOADING", "CHECKING"})
OUTAGE_SECONDS = 15.0
_MESSAGES = {
    "PAUSED": "The build paused.",
    "ERROR": "The mod reported an error.",
    "DONE": "The build is finished.",
}


@dataclass(frozen=True)
class Alert:
    kind: str
    message: str


class AttentionTracker:
    """Decides when to alert. The first online snapshot only sets the baseline."""

    def __init__(self, *, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._has_baseline = False
        self._state: str | None = None
        self._offline_since: float | None = None
        self._outage_alerted = False

    def update(self, *, online: bool, state: str | None, last_action: str | None, last_request_id: str | None,
               own_request_ids: Collection[str]) -> Alert | None:
        now = self._clock()
        if online:
            previous, first = self._state, not self._has_baseline
            self._has_baseline = True
            self._state = state
            self._offline_since = None
            self._outage_alerted = False
            if first or state == previous or state not in _MESSAGES:
                return None
            # Only this monitor's own Pause is quiet; its other controls leave the request ID
            # in place when the mod later pauses by itself.
            if state == "PAUSED" and last_action == "PAUSE" and last_request_id in own_request_ids:
                return None
            return Alert(state.lower(), _MESSAGES[state])
        if self._offline_since is None:
            self._offline_since = now
        if (self._has_baseline and not self._outage_alerted and self._state in ACTIVE_STATES
                and now - self._offline_since >= OUTAGE_SECONDS):
            self._outage_alerted = True
            return Alert("connection", "Lost contact with Minecraft during the build.")
        return None
