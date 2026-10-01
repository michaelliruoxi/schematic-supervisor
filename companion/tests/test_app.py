from __future__ import annotations

import io
import json
import logging
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from urllib.request import Request, urlopen

from supervisor_companion.__main__ import main
from supervisor_companion.actions import ControlAction
from supervisor_companion.app import OperatorController, build_runtime
from supervisor_companion.config import (
    AIConfig,
    AppConfig,
    LogConfig,
    ModConfig,
    ServerConfig,
)
from supervisor_companion.control import ControlError, ControlResult
from supervisor_companion.logging_setup import configure_logging
from supervisor_companion.store import StatusStore

from http_fixture import RecordingEndpoint


class StubControlClient:
    def __init__(self, *, error: bool = False) -> None:
        self.error = error

    def send(self, action: ControlAction) -> ControlResult:
        if self.error:
            raise ControlError("mod unavailable")
        return ControlResult(action, "request-1", True, "Accepted.")


class DelayedStartControlClient:
    def __init__(self) -> None:
        self.start_entered = threading.Event()
        self.release_start = threading.Event()
        self.actions: list[ControlAction] = []
        self._lock = threading.Lock()

    def send(self, action: ControlAction) -> ControlResult:
        if action is ControlAction.START:
            self.start_entered.set()
            if not self.release_start.wait(timeout=3):
                raise ControlError("test start timed out")
        with self._lock:
            self.actions.append(action)
            request_id = f"request-{len(self.actions)}"
        return ControlResult(action, request_id, True, "Accepted.")


class OperatorControllerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.logger = logging.getLogger(f"test-app-{id(self)}")
        self.logger.addHandler(logging.NullHandler())

    def test_success_clears_previous_local_error(self) -> None:
        store = StatusStore()
        store.set_local_error("old")
        controller = OperatorController(
            StubControlClient(),
            store,
            logger=self.logger,
        )
        controller.send(ControlAction.START)
        self.assertIsNone(store.snapshot().local_error)

    def test_failure_is_visible_in_store(self) -> None:
        store = StatusStore()
        controller = OperatorController(
            StubControlClient(error=True),
            store,
            logger=self.logger,
        )
        with self.assertRaises(ControlError):
            controller.send(ControlAction.STOP)
        self.assertEqual(store.snapshot().local_error, "mod unavailable")

    def test_later_pause_is_reasserted_after_delayed_start(self) -> None:
        store = StatusStore()
        client = DelayedStartControlClient()
        controller = OperatorController(client, store, logger=self.logger)
        failures: list[BaseException] = []
        results: list[ControlResult] = []

        def send_start() -> None:
            try:
                results.append(controller.send(ControlAction.START))
            except BaseException as error:
                failures.append(error)

        worker = threading.Thread(target=send_start)
        worker.start()
        self.assertTrue(client.start_entered.wait(timeout=2))
        controller.send(ControlAction.PAUSE)
        client.release_start.set()
        worker.join(timeout=3)

        self.assertFalse(worker.is_alive())
        self.assertEqual(failures, [])
        self.assertEqual(results[0].action, ControlAction.PAUSE)
        self.assertEqual(
            client.actions,
            [ControlAction.PAUSE, ControlAction.START, ControlAction.PAUSE],
        )


class RuntimeTests(unittest.TestCase):
    def test_disabled_ai_runtime_binds_ephemeral_loopback_only(self) -> None:
        logger = logging.getLogger("test-runtime")
        logger.addHandler(logging.NullHandler())
        config = AppConfig(
            server=ServerConfig(port=0),
            mod=ModConfig(base_url="http://127.0.0.1:1", timeout_seconds=0.1),
            ai=AIConfig(provider="disabled"),
        )
        runtime = build_runtime(config, logger=logger)
        try:
            host, port = runtime.service.address
            self.assertEqual(host, "127.0.0.1")
            self.assertGreater(port, 0)
            runtime.start()
        finally:
            runtime.stop()

    def test_end_to_end_invalid_provider_response_pauses_local_mod(self) -> None:
        logger = logging.getLogger("test-runtime-end-to-end")
        logger.addHandler(logging.NullHandler())
        with RecordingEndpoint(
            lambda _: (200, {"action": "EXECUTE_COMMAND"})
        ) as provider, RecordingEndpoint(
            lambda _: (200, {"accepted": True, "message": "Paused."})
        ) as mod:
            config = AppConfig(
                server=ServerConfig(port=0),
                mod=ModConfig(base_url=mod.base_url, timeout_seconds=2),
                ai=AIConfig(
                    provider="http",
                    endpoint=f"{provider.base_url}/diagnose",
                    timeout_seconds=2,
                ),
            )
            runtime = build_runtime(config, logger=logger)
            runtime.start()
            try:
                host, port = runtime.service.address
                incident = {
                    "incident_id": "end-to-end-1",
                    "category": "NO_PROGRESS",
                    "summary": "Recovery was exhausted.",
                    "recovery_exhausted": True,
                    "status": {
                        "current_chunk": {"index": 1, "total": 49},
                        "phase": "STUCK",
                        "materials": {},
                        "baritone_status": "Path cancelled",
                    },
                    "attempted_recovery": ["RESTART_PATH"],
                    "details": {"stalled_seconds": 45},
                }
                encoded = json.dumps(incident).encode("utf-8")
                request = Request(
                    f"http://{host}:{port}/v1/incidents",
                    data=encoded,
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urlopen(request, timeout=3) as response:
                    decision = json.loads(response.read().decode("utf-8"))
            finally:
                runtime.stop()

        self.assertEqual(decision["action"], "PAUSE_AND_ALERT")
        self.assertTrue(decision["used_fallback"])
        self.assertEqual(provider.requests[0]["path"], "/diagnose")
        self.assertEqual(mod.requests[0]["path"], "/v1/control")
        self.assertEqual(mod.requests[0]["body"]["action"], "PAUSE")


class LoggingTests(unittest.TestCase):
    def test_rotating_log_creates_bounded_backups(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = AppConfig(
                logging=LogConfig(
                    directory=directory,
                    filename="test.log",
                    max_bytes=1_024,
                    backup_count=2,
                )
            )
            logger = configure_logging(config)
            for index in range(200):
                logger.info("line %s %s", index, "x" * 80)
            for handler in logger.handlers:
                handler.flush()
            files = sorted(Path(directory).glob("test.log*"))
            self.assertGreaterEqual(len(files), 2)
            self.assertLessEqual(len(files), 3)
            for handler in list(logger.handlers):
                handler.close()
                logger.removeHandler(handler)


class CliTests(unittest.TestCase):
    def test_check_config_does_not_start_runtime_or_gui(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text(
                json.dumps(
                    {
                        "ai": {"provider": "disabled"},
                        "server": {"port": 8766},
                    }
                ),
                encoding="utf-8",
            )
            output = io.StringIO()
            with redirect_stdout(output):
                result = main(["--check-config", "--config", str(path)])
        self.assertEqual(result, 0)
        summary = json.loads(output.getvalue())
        self.assertEqual(summary["ai"]["provider"], "disabled")
        self.assertEqual(summary["server"]["port"], 8766)


if __name__ == "__main__":
    unittest.main()
