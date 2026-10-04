"""Threaded, read-first desktop access to the local supervisor bridge."""

from __future__ import annotations

from collections import deque
from copy import deepcopy
from datetime import datetime, timezone
import logging
import math
from threading import Event, Lock, Thread
import time
from typing import Any, Callable
from uuid import uuid4

from .agent_bridge import ACTIONS, ACTIVE_ACTIONS, AgentBridge, EndpointError, _age_seconds
from .logging_setup import MONITOR_LOGGER_NAME, ThrottledExceptionLog
from .pace import PaceTracker
from .progress import Progress, ProgressError, parse_progress


MAX_EVENTS = 150
SAFETY_ACTIONS = {"PAUSE", "STOP"}
PROGRESS_REFRESH_SECONDS = 30.0
UNSUPPORTED_RECHECK_SECONDS = 300.0
OWN_REQUEST_SECONDS = 60.0
STALE_MESSAGE = "Minecraft stopped reporting. It may be on a loading screen or frozen."
_UNSET = object()
_STAGE_NAMES = {"STRUCTURE": "Structure", "LIGHTING": "Lights", "TILL": "Till", "PLANT": "Plant"}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _message(value: Any, fallback: str) -> str:
    if not isinstance(value, str) or not value.strip():
        return fallback
    return " ".join(value.split())[:1000]


class MonitorService:
    """Poll without blocking the interface; controls always require a button action.

    ``send_control`` reports whether an action was queued, not whether the mod
    accepted it. Delivery results appear in ``control_message`` and ``events``.
    Closing the monitor stops observation without changing the supervisor state.
    """

    def __init__(self, bridge: AgentBridge, poll_interval_seconds: float = 1.0, *,
                 logger: logging.Logger | None = None,
                 clock: Callable[[], float] = time.monotonic) -> None:
        if (isinstance(poll_interval_seconds, bool)
                or not isinstance(poll_interval_seconds, (int, float))
                or not math.isfinite(poll_interval_seconds)
                or not 0.05 <= poll_interval_seconds <= 60):
            raise ValueError("poll_interval_seconds must be between 0.05 and 60")
        self.bridge = bridge
        self.poll_interval_seconds = float(poll_interval_seconds)
        self._logger = logger or logging.getLogger(MONITOR_LOGGER_NAME)
        self._errors = ThrottledExceptionLog(self._logger)
        self._clock = clock
        self._lock = Lock()
        self._wake = Event()
        self._closed = Event()
        self._thread: Thread | None = None
        self._observation: dict[str, Any] | None = None
        self._connection = "connecting"
        self._detail: str | None = None
        self._message = "Waiting for the local supervisor."
        self._last_seen: str | None = None
        self._last_request_fresh = False
        self._paired = False
        self._control_message = ""
        self._sequence = 0
        self._latest_intent: tuple[int, str] | None = None
        self._pending: dict[int, str] = {}
        self._events: deque[dict[str, str]] = deque(maxlen=MAX_EVENTS)
        self._last_status: tuple[Any, ...] | None = None
        self._state_key: tuple[Any, Any] | None = None
        self._state_since: str | None = None
        self._stage_key: tuple[Any, ...] | None = None
        self._check_key: Any = _UNSET
        self._plan_id: Any = _UNSET
        self._was_online = False
        self._progress: Any = None
        self._progress_status = "unknown"
        self._fetched_revision: Any = _UNSET
        self._last_progress_fetch = -math.inf
        self._pace = PaceTracker()
        self._own_requests: dict[str, float] = {}

    def start(self) -> None:
        with self._lock:
            if self._closed.is_set() or self._thread is not None:
                return
            self._thread = Thread(target=self._poll, name="supervisor-monitor", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        # A bounded network request may still finish in the background. Existing
        # control workers must finish any required later Pause/Stop reassertion.
        self._closed.set()
        self._wake.set()

    def refresh(self) -> None:
        self._wake.set()

    def note_event(self, message: str) -> None:
        with self._lock:
            self._event_locked(_message(message, "Event."))

    def _event_locked(self, message: str) -> None:
        self._events.append({"time": _now(), "message": message})

    def _fresh_locked(self) -> tuple[bool, float | None]:
        age = _age_seconds((self._observation or {}).get("updated_at"))
        fresh = (not self._closed.is_set() and self._last_request_fresh
                 and age is not None and -5 <= age <= self.bridge.stale_after_seconds)
        return fresh, None if age is None else round(age, 3)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            fresh, age = self._fresh_locked()
            connection, message, detail = self._connection, self._message, self._detail
            if self._closed.is_set():
                connection, message, detail = "stopped", "Monitoring is closed.", None
            elif connection == "online" and not fresh:
                connection, message, detail = "stale", STALE_MESSAGE, "stale"
            now = self._clock()
            result = deepcopy({
                "connection": connection, "detail": detail, "message": message,
                "observation": self._observation, "fresh": fresh,
                "last_seen": self._last_seen, "age_seconds": age,
                "paired": self._paired, "pending_actions": list(self._pending.values()),
                "control_message": self._control_message, "events": list(self._events),
                "state_since": self._state_since, "progress_status": self._progress_status,
                "own_request_ids": [request for request, sent in self._own_requests.items()
                                    if now - sent < OWN_REQUEST_SECONDS],
            })
            progress = self._progress
            # Progress objects are immutable, so they are shared rather than copied.
            result["progress"] = progress
            result["pace"] = self._pace.pace(
                progress.remaining_actions if isinstance(progress, Progress) else None)
            return result

    def _poll(self) -> None:
        while not self._closed.is_set():
            self._wake.clear()
            try:
                try:
                    paired = bool(self.bridge.mod_token)
                except EndpointError:
                    # observe() reports an unreadable or invalid pairing token as configuration_error.
                    paired = False
                result = self.bridge.observe()
            except Exception as error:
                # Exception text can contain paths or configuration values; only the log gets details.
                self._errors.exception("Reading the supervisor failed", error)
                paired = False
                result = {"connection": "error", "detail": None, "fresh": False,
                          "message": "Could not read the local supervisor; details are in the log."}
            progress_result = None
            if self._progress_due(result):
                try:
                    progress_result = self.bridge.progress()
                except Exception as error:
                    self._errors.exception("Reading progress failed", error)
                    progress_result = {"ok": False, "connection": "error"}
            with self._lock:
                if self._closed.is_set():
                    return
                try:
                    self._apply_observation_locked(result, paired)
                    if progress_result is not None:
                        self._apply_progress_locked(progress_result, result)
                    self._sample_pace_locked()
                except Exception as error:
                    # Telemetry is data; an unexpected shape must not stop the monitor.
                    self._errors.exception("Updating the monitor failed", error)
            self._wake.wait(self.poll_interval_seconds)

    def _apply_observation_locked(self, result: dict[str, Any], paired: bool) -> None:
        self._paired = paired
        self._connection = result.get("connection", "error")
        self._detail = result.get("detail")
        self._message = _message(result.get("message"), "Connected to the local supervisor.")
        observation = result.get("observation")
        if isinstance(observation, dict):
            self._observation = deepcopy(observation)
            self._last_seen = _now()
        self._last_request_fresh = result.get("fresh") is True and isinstance(observation, dict)
        self._was_online = self._connection in ("online", "stale") and isinstance(observation, dict)
        current = self._observation or {}
        # The detail separates causes that share a connection, such as a refused or timed-out offline.
        status = (self._connection, self._detail, current.get("run_id"), current.get("state"))
        if status != self._last_status:
            self._last_status = status
            if self._connection == "online":
                self._event_locked(f"Connected · {current.get('state', 'unknown state')}")
            else:
                cause = (self._connection if self._detail in (None, self._connection)
                         else f"{self._connection} ({self._detail})")
                self._event_locked(f"{cause}: {self._message}")
            # The message is the capped text the Activity tab shows; bridge messages never contain a token.
            http_status = result.get("http_status")
            status_code = (f", HTTP {http_status}" if isinstance(http_status, int)
                           and not isinstance(http_status, bool) else "")
            self._logger.info("Connection %s (%s%s); state %s; message: %s", self._connection,
                              self._detail or "no detail", status_code, current.get("state"), self._message)
        if isinstance(observation, dict):
            self._note_state_locked(observation)

    def _note_state_locked(self, observation: dict[str, Any]) -> None:
        state_key = (observation.get("run_id"), observation.get("state"))
        if state_key != self._state_key:
            self._state_since = None if self._state_key is None else _now()
            self._state_key = state_key
        plan_id = observation.get("plan_id")
        if plan_id != self._plan_id:
            if self._plan_id is not _UNSET:
                self._event_locked("Plan loaded." if plan_id else "Plan unloaded.")
                self._logger.info("Plan changed to %s", plan_id)
            self._plan_id = plan_id
        # Progress, even last known, is shown only for the plan it describes; the pace restarts with it.
        # Older mods don't report plan_id.
        if ("plan_id" in observation and isinstance(self._progress, Progress)
                and self._progress.plan_id != plan_id):
            self._progress = None
            self._fetched_revision = _UNSET
        self._note_build_check_locked(observation.get("build_check"))
        layer = observation.get("current_layer")
        if not isinstance(layer, dict):
            return
        stage_key = (layer.get("stage"), layer.get("index"), layer.get("y"))
        if stage_key == self._stage_key:
            return
        if self._stage_key is not None and isinstance(layer.get("index"), int):
            stage = layer.get("stage")
            # Only text names a stage; any other value is shown like an unknown stage, without a name.
            name = (f" · {_STAGE_NAMES.get(stage, stage.title())}"
                    if isinstance(stage, str) and stage.strip() else "")
            y = f" · Y {layer['y']}" if isinstance(layer.get("y"), int) else ""
            self._event_locked(_message(f"Stage {layer['index']} of {layer.get('total')}{name}{y}",
                                        "Stage changed."))
            self._logger.info("Stage %s of %s (%s, Y %s)", layer.get("index"), layer.get("total"),
                              layer.get("stage"), layer.get("y"))
        self._stage_key = stage_key

    def _note_build_check_locked(self, check: Any) -> None:
        """Records each finished start build check once; the first observation only sets the baseline."""
        finished = isinstance(check, dict) and check.get("status") in ("COMPLETE", "FAILED")
        # Keys are only compared for equality, so unexpected JSON values are safe here.
        key = (check.get("started_at"), check.get("status")) if finished else None
        if key == self._check_key:
            return
        if finished and self._check_key is not _UNSET:
            summary = _message(check.get("summary"), "The build check finished.")
            self._event_locked(summary)
            self._logger.info("Build check %s: %s", str(check.get("status")).lower(), summary)
        self._check_key = key

    def _progress_due(self, result: dict[str, Any]) -> bool:
        observation = result.get("observation")
        if not isinstance(observation, dict) or result.get("connection") not in ("online", "stale"):
            return False
        with self._lock:
            elapsed = self._clock() - self._last_progress_fetch
            reconnected = not self._was_online
            if self._progress_status == "unsupported" and not reconnected:
                return elapsed >= UNSUPPORTED_RECHECK_SECONDS
            # Compare for difference, not increase: a restarted mod counts from 1 again.
            return (reconnected or observation.get("progress_revision") != self._fetched_revision
                    or elapsed >= PROGRESS_REFRESH_SECONDS)

    def _apply_progress_locked(self, progress_result: dict[str, Any], result: dict[str, Any]) -> None:
        self._last_progress_fetch = self._clock()
        observation = result.get("observation") or {}
        previous = self._progress_status
        if progress_result.get("ok"):
            try:
                parsed = parse_progress(progress_result.get("progress"))
            except ProgressError as error:
                self._progress_status = "error"
                self._fetched_revision = observation.get("progress_revision")
                if previous != "error":
                    self._logger.warning("Progress data was rejected: %s", error)
            else:
                if isinstance(parsed, Progress) and parsed.revision != self._fetched_revision:
                    self._logger.info("Progress revision %s: %s of %s actions", parsed.revision,
                                      parsed.done_actions, parsed.total_actions)
                self._progress = parsed
                self._progress_status = "ok" if isinstance(parsed, Progress) else "unavailable"
                self._fetched_revision = parsed.revision
        elif progress_result.get("http_status") == 404:
            self._progress = None
            self._progress_status = "unsupported"
            self._fetched_revision = observation.get("progress_revision")
        else:
            # Keep the last known progress; it is shown as last known until a fetch succeeds.
            self._progress_status = "error"
            self._fetched_revision = observation.get("progress_revision")
            if previous != "error":
                self._logger.warning("Progress data unavailable: %s",
                                     progress_result.get("message") or progress_result.get("connection"))
        if self._progress_status != previous:
            self._logger.info("Progress status %s", self._progress_status)

    def _sample_pace_locked(self) -> None:
        fresh, _ = self._fresh_locked()
        progress = self._progress if isinstance(self._progress, Progress) else None
        state = (self._observation or {}).get("state") if fresh else None
        identity = (progress.plan_id, progress.schedule_id) if progress is not None else None
        self._pace.observe(self._clock(), state, identity,
                           progress.done_actions if progress is not None else None)

    def _remember_request_locked(self) -> str:
        now = self._clock()
        for request, sent in list(self._own_requests.items()):
            if now - sent >= OWN_REQUEST_SECONDS:
                del self._own_requests[request]
        request = f"monitor-{uuid4().hex}"
        self._own_requests[request] = now
        return request

    def send_control(self, action: str, *, observation: dict[str, Any] | None = None) -> bool:
        """Queue a button action, optionally guarded by the last rendered view."""
        with self._lock:
            if self._closed.is_set():
                return False
            reason = None
            guards: dict[str, Any] = {}
            if action not in ACTIONS:
                reason = "This action is unavailable."
            elif action in ACTIVE_ACTIONS:
                fresh, _ = self._fresh_locked()
                current = self._observation or {}
                displayed = current if observation is None else observation
                displayed_age = _age_seconds(displayed.get("updated_at"))
                if not fresh:
                    reason = "A fresh observation is required before continuing."
                elif not self._paired:
                    reason = "The shared supervisor token is required before continuing."
                elif action not in current.get("allowed_actions", []):
                    reason = "The supervisor does not currently allow this action."
                elif self._pending:
                    reason = "Wait for the pending control to finish."
                elif (displayed_age is None or not -5 <= displayed_age <= self.bridge.stale_after_seconds
                      or action not in displayed.get("allowed_actions", [])
                      or displayed.get("run_id") != current.get("run_id")
                      or displayed.get("state") != current.get("state")
                      or not isinstance(displayed.get("last_control"), dict)
                      or displayed["last_control"].get("sequence") != current["last_control"]["sequence"]):
                    reason = "The displayed observation has changed or expired; refresh before continuing."
                else:
                    guards = {"expected_run_id": displayed["run_id"],
                              "expected_state": displayed["state"],
                              "expected_control_sequence": displayed["last_control"]["sequence"]}
            elif action in self._pending.values():
                reason = f"{action} is already pending."
            if reason:
                self._control_message = reason
                self._event_locked(reason)
                return False
            self._sequence += 1
            sequence = self._sequence
            self._latest_intent = (sequence, action)
            self._pending[sequence] = action
            self._control_message = f"Sending {action}…"
            self._event_locked(f"{action} requested.")
            request_id = self._remember_request_locked()
        Thread(target=self._control, args=(sequence, action, guards, request_id),
               # Closing the window must not abandon an already requested
               # Pause/Stop or the safety barrier after a slower active action.
               name=f"supervisor-control-{sequence}", daemon=False).start()
        return True

    def _deliver(self, action: str, guards: dict[str, Any], request_id: str) -> dict[str, Any]:
        try:
            result = self.bridge.control(action, request_id=request_id, **guards)
        except Exception as error:
            self._errors.exception(f"{action} delivery failed", error)
            return {"outcome": "unknown",
                    "message": "Control delivery failed; refresh to check the supervisor state before retrying."}
        self._logger.info("%s %s (request %s): %s", action, result.get("outcome"), request_id,
                          result.get("message"))
        return result

    @staticmethod
    def _result_message(action: str, result: dict[str, Any]) -> str:
        outcome = result.get("outcome", "unknown")
        detail = _message(result.get("message"), "Refresh to check the supervisor state.")
        return f"{action} {outcome}: {detail}"

    def _control(self, sequence: int, action: str, guards: dict[str, Any], request_id: str) -> None:
        result = self._deliver(action, guards, request_id)
        message = self._result_message(action, result)
        with self._lock:
            self._event_locked(message)
        if action in ACTIVE_ACTIONS:
            reasserted_sequence = sequence
            while True:
                with self._lock:
                    latest = self._latest_intent
                    if (latest is None or latest[0] <= reasserted_sequence
                            or latest[1] not in SAFETY_ACTIONS):
                        break
                    reasserted_sequence, safety_action = latest
                    barrier_request = self._remember_request_locked()
                # The active request may have arrived after a newer safety
                # action. Reassert safety even if the active outcome is unknown.
                # Keep this active request pending throughout the barrier, so no
                # new active intent can be registered until it is complete.
                barrier = self._deliver(safety_action, {}, barrier_request)
                message = "Safety reassertion · " + self._result_message(safety_action, barrier)
                with self._lock:
                    self._event_locked(message)
        with self._lock:
            self._pending.pop(sequence, None)
            latest = self._latest_intent
            if latest is None or latest[0] == sequence or action in ACTIVE_ACTIONS:
                self._control_message = message
        self.refresh()
