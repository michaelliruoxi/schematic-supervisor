"""Application composition kept separate from the Tk interface."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from threading import Lock

from .actions import ControlAction
from .config import AppConfig
from .control import ControlError, ControlResult, ModControlClient
from .diagnosis import (
    HttpDiagnosisBackend,
    IncidentCoordinator,
    OllamaDiagnosisBackend,
    SafeDiagnoser,
)
from .logging_setup import configure_logging
from .server import CompanionService
from .store import StatusStore


class OperatorController:
    """Deliver button actions and surface local delivery errors in the store."""

    def __init__(
        self,
        control_client: ModControlClient,
        store: StatusStore,
        *,
        logger: logging.Logger,
    ) -> None:
        self._control_client = control_client
        self._store = store
        self._logger = logger
        self._intent_lock = Lock()
        self._next_sequence = 0
        self._latest_intent: tuple[int, ControlAction] | None = None

    def send(self, action: ControlAction) -> ControlResult:
        with self._intent_lock:
            self._next_sequence += 1
            sequence = self._next_sequence
            self._latest_intent = (sequence, action)
        result: ControlResult | None = None
        primary_error: ControlError | None = None
        try:
            result = self._deliver(action)
        except ControlError as error:
            primary_error = error

        barrier_error: ControlError | None = None
        barrier_result: ControlResult | None = None
        if action in {ControlAction.START, ControlAction.RESUME}:
            # A later Pause/Stop may have overtaken this slower request. Hold
            # the intent lock while reasserting the latest safety action so a
            # still-newer Start/Resume is registered only after the barrier.
            with self._intent_lock:
                latest = self._latest_intent
                if (
                    latest is not None
                    and latest[0] > sequence
                    and latest[1] in {ControlAction.PAUSE, ControlAction.STOP}
                ):
                    try:
                        barrier_result = self._deliver(latest[1])
                    except ControlError as error:
                        barrier_error = ControlError(
                            f"{latest[1].value} safety reassertion failed"
                        )
                        barrier_error.__cause__ = error
        if barrier_error is not None:
            raise barrier_error
        if primary_error is not None:
            raise primary_error
        if barrier_result is not None:
            return barrier_result
        assert result is not None
        return result

    def _deliver(self, action: ControlAction) -> ControlResult:
        try:
            result = self._control_client.send(action)
        except ControlError as error:
            self._store.set_local_error(str(error))
            self._logger.warning("Operator control %s failed", action.value)
            raise
        self._store.set_local_error(None)
        self._logger.info(
            "Operator control %s accepted as request %s",
            action.value,
            result.request_id,
        )
        return result


@dataclass
class CompanionRuntime:
    """Runtime dependencies with explicit start and stop lifecycle."""

    config: AppConfig
    logger: logging.Logger
    store: StatusStore
    controller: OperatorController
    service: CompanionService

    def start(self) -> None:
        self.service.start()

    def stop(self) -> None:
        self.service.stop()


def build_runtime(
    config: AppConfig,
    *,
    logger: logging.Logger | None = None,
    protocol_token: str | None = None,
) -> CompanionRuntime:
    actual_logger = logger or configure_logging(config)
    store = StatusStore()
    control_client = ModControlClient(
        config.mod.base_url,
        timeout_seconds=config.mod.timeout_seconds,
        token=config.mod.resolve_token() or protocol_token,
    )
    if config.ai.provider == "http":
        backend = HttpDiagnosisBackend(
            endpoint=config.ai.endpoint,
            timeout_seconds=config.ai.timeout_seconds,
            api_key=config.ai.resolve_api_key(),
            model=config.ai.model,
        )
    elif config.ai.provider == "ollama":
        backend = OllamaDiagnosisBackend(
            endpoint=config.ai.endpoint,
            model=config.ai.model,
            timeout_seconds=config.ai.timeout_seconds,
        )
    else:
        backend = None
    diagnoser = SafeDiagnoser(backend, logger=actual_logger)
    incident_coordinator = IncidentCoordinator(
        store,
        diagnoser,
        control_client,
        logger=actual_logger,
    )
    service = CompanionService(
        config.server.host,
        config.server.port,
        store=store,
        incident_coordinator=incident_coordinator,
        max_request_bytes=config.server.max_request_bytes,
        token=config.server.resolve_token() or protocol_token,
        logger=actual_logger,
    )
    controller = OperatorController(
        control_client,
        store,
        logger=actual_logger,
    )
    return CompanionRuntime(
        config=config,
        logger=actual_logger,
        store=store,
        controller=controller,
        service=service,
    )
