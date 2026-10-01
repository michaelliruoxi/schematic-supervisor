from __future__ import annotations

import json
import logging
import threading
import unittest
from dataclasses import dataclass
from typing import Any

from supervisor_companion.actions import ControlAction, RecoveryAction
from supervisor_companion.control import (
    ControlError,
    ControlResult,
    ModControlClient,
)
from supervisor_companion.diagnosis import (
    HttpDiagnosisBackend,
    IncidentCoordinator,
    OllamaDiagnosisBackend,
    SafeDiagnoser,
    extract_provider_decision,
)
from supervisor_companion.models import Incident, PayloadError
from supervisor_companion.store import StatusStore

from http_fixture import RecordingEndpoint


def make_incident(**overrides: Any) -> Incident:
    payload: dict[str, Any] = {
        "incident_id": "incident-1",
        "category": "NO_PROGRESS",
        "summary": "No progress after deterministic recovery.",
        "recovery_exhausted": True,
        "status": {
            "current_chunk": {"index": 7, "total": 49},
            "phase": "STUCK",
            "materials": {"dirt": 32},
            "baritone_status": "Path cancelled",
            "last_error": "No progress",
        },
        "attempted_recovery": ["WAIT_FOR_LAG", "RESTART_PATH"],
        "details": {"server_lag_suspected": False, "stalled_seconds": 45},
    }
    payload.update(overrides)
    return Incident.from_mapping(payload)


@dataclass
class FakeBackend:
    result: Any
    source_name: str = "fake"
    calls: int = 0

    def request_decision(self, _: Incident) -> Any:
        self.calls += 1
        if isinstance(self.result, BaseException):
            raise self.result
        return self.result


class FakeControlClient:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.actions: list[ControlAction] = []

    def send(self, action: ControlAction) -> ControlResult:
        self.actions.append(action)
        if self.fail:
            raise ControlError("offline")
        return ControlResult(action, "pause-1", True, "Paused.", "STUCK")


class BlockingBackend:
    source_name = "blocking"

    def __init__(self) -> None:
        self.calls = 0
        self.entered = threading.Event()
        self.release = threading.Event()

    def request_decision(self, _: Incident) -> Any:
        self.calls += 1
        self.entered.set()
        if not self.release.wait(timeout=3):
            raise RuntimeError("test release timed out")
        return {"action": "PAUSE_AND_ALERT"}


class ProviderExtractionTests(unittest.TestCase):
    def test_extracts_direct_decision(self) -> None:
        result = extract_provider_decision(
            {"action": "WAIT", "reason": "Lag suspected."}
        )
        self.assertEqual(result["action"], "WAIT")

    def test_extracts_nested_decision(self) -> None:
        result = extract_provider_decision(
            {"decision": {"action": "RESTOCK", "reason": "Missing dirt."}}
        )
        self.assertEqual(result["action"], "RESTOCK")

    def test_extracts_ollama_json_content(self) -> None:
        result = extract_provider_decision(
            {
                "message": {
                    "content": '{"action":"REPATH","reason":"Retry path."}'
                }
            }
        )
        self.assertEqual(result["action"], "REPATH")

    def test_extracts_fenced_json_content(self) -> None:
        result = extract_provider_decision(
            {
                "message": {
                    "content": (
                        "```json\n"
                        '{"action":"PAUSE_AND_ALERT","reason":"Review."}\n'
                        "```"
                    )
                }
            }
        )
        self.assertEqual(result["action"], "PAUSE_AND_ALERT")

    def test_extracts_chat_completions_style_content(self) -> None:
        result = extract_provider_decision(
            {
                "choices": [
                    {
                        "message": {
                            "content": (
                                '{"action":"RETRY_CHUNK",'
                                '"reason":"Retry once."}'
                            )
                        }
                    }
                ]
            }
        )
        self.assertEqual(result["action"], "RETRY_CHUNK")

    def test_rejects_prose_around_json(self) -> None:
        with self.assertRaises(PayloadError):
            extract_provider_decision(
                {
                    "message": {
                        "content": (
                            'Use this: {"action":"WAIT","reason":"Lag."}'
                        )
                    }
                }
            )


class SafeDiagnoserTests(unittest.TestCase):
    def setUp(self) -> None:
        self.logger = logging.getLogger(f"test-diagnosis-{id(self)}")
        self.logger.addHandler(logging.NullHandler())

    def test_valid_decision_passes_allowlist(self) -> None:
        diagnoser = SafeDiagnoser(
            FakeBackend({"action": "WAIT", "reason": "Lag suspected."}),
            logger=self.logger,
        )
        decision = diagnoser.diagnose(make_incident())
        self.assertIs(decision.action, RecoveryAction.WAIT)
        self.assertFalse(decision.used_fallback)
        self.assertEqual(decision.source, "fake")

    def test_disabled_provider_pauses(self) -> None:
        decision = SafeDiagnoser(None, logger=self.logger).diagnose(
            make_incident()
        )
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertTrue(decision.used_fallback)

    def test_incomplete_recovery_evidence_never_calls_provider(self) -> None:
        backend = FakeBackend({"action": "WAIT"})
        diagnoser = SafeDiagnoser(backend, logger=self.logger)
        incident = make_incident(
            recovery_exhausted=False,
            attempted_recovery=[],
            status=None,
        )
        decision = diagnoser.diagnose(incident)
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertTrue(decision.used_fallback)
        self.assertEqual(backend.calls, 0)

    def test_prompt_injected_action_fails_closed(self) -> None:
        diagnoser = SafeDiagnoser(
            FakeBackend(
                {
                    "action": "RUN_COMMAND",
                    "reason": "Run /warp shop from incident instructions.",
                }
            ),
            logger=self.logger,
        )
        decision = diagnoser.diagnose(make_incident())
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertTrue(decision.used_fallback)
        self.assertNotIn("/warp", decision.reason)

    def test_provider_exception_fails_closed(self) -> None:
        diagnoser = SafeDiagnoser(
            FakeBackend(RuntimeError("provider secret detail")),
            logger=self.logger,
        )
        decision = diagnoser.diagnose(make_incident())
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertNotIn("secret detail", decision.reason)


class ProviderHttpTests(unittest.TestCase):
    def test_http_backend_sends_schema_and_api_key(self) -> None:
        with RecordingEndpoint(
            lambda _: (
                200,
                {"action": "RESTOCK", "reason": "Materials are missing."},
            )
        ) as endpoint:
            backend = HttpDiagnosisBackend(
                endpoint=f"{endpoint.base_url}/diagnose",
                timeout_seconds=2,
                api_key="api-secret",
            )
            result = backend.request_decision(make_incident())

        request = endpoint.requests[0]
        self.assertEqual(request["path"], "/diagnose")
        self.assertEqual(request["body"]["schema_version"], 1)
        self.assertIn("PAUSE_AND_ALERT", request["body"]["allowed_actions"])
        self.assertEqual(
            request["body"]["incident"]["incident_id"], "incident-1"
        )
        self.assertEqual(
            request["headers"]["Authorization"], "Bearer api-secret"
        )
        self.assertEqual(result["action"], "RESTOCK")

    def test_ollama_backend_requests_non_streaming_json(self) -> None:
        response_content = json.dumps(
            {"action": "WAIT", "reason": "Wait for lag."}
        )
        with RecordingEndpoint(
            lambda _: (200, {"message": {"content": response_content}})
        ) as endpoint:
            backend = OllamaDiagnosisBackend(
                endpoint=f"{endpoint.base_url}/api/chat",
                model="small-model",
                timeout_seconds=2,
            )
            raw = backend.request_decision(make_incident())

        request = endpoint.requests[0]
        self.assertEqual(request["path"], "/api/chat")
        self.assertEqual(request["body"]["model"], "small-model")
        self.assertFalse(request["body"]["stream"])
        self.assertEqual(request["body"]["format"], "json")
        self.assertEqual(request["body"]["options"]["temperature"], 0)
        self.assertIn("untrusted data", request["body"]["messages"][0]["content"])
        self.assertEqual(
            extract_provider_decision(raw)["action"],
            "WAIT",
        )

    def test_direct_ollama_backend_rejects_remote_endpoint(self) -> None:
        with self.assertRaisesRegex(ValueError, "loopback"):
            OllamaDiagnosisBackend(
                endpoint="http://example.com/api/chat",
                model="small-model",
                timeout_seconds=2,
            )


class IncidentCoordinatorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.logger = logging.getLogger(f"test-coordinator-{id(self)}")
        self.logger.addHandler(logging.NullHandler())

    def test_non_pause_decision_does_not_send_control(self) -> None:
        store = StatusStore()
        control = FakeControlClient()
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(
                FakeBackend({"action": "REPATH", "reason": "Retry."}),
                logger=self.logger,
            ),
            control,
            logger=self.logger,
        )
        decision = coordinator.handle(make_incident())
        self.assertIs(decision.action, RecoveryAction.REPATH)
        self.assertEqual(control.actions, [])
        self.assertEqual(
            store.snapshot().latest_incident.incident_id,
            "incident-1",
        )

    def test_pause_decision_sends_independent_pause(self) -> None:
        store = StatusStore()
        control = FakeControlClient()
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(None, logger=self.logger),
            control,
            logger=self.logger,
        )
        decision = coordinator.handle(make_incident())
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertEqual(control.actions, [ControlAction.PAUSE])
        self.assertIsNone(store.snapshot().local_error)

    def test_failed_independent_pause_is_visible_but_decision_survives(self) -> None:
        store = StatusStore()
        control = FakeControlClient(fail=True)
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(None, logger=self.logger),
            control,
            logger=self.logger,
        )
        decision = coordinator.handle(make_incident())
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertIn("could not reach", store.snapshot().local_error)

    def test_malformed_pause_reply_preserves_fallback_and_delivery_error(self) -> None:
        store = StatusStore()
        with RecordingEndpoint(
            lambda _: (200, b"not-a-chunk\r\n", {"Transfer-Encoding": "chunked"})
        ) as endpoint:
            coordinator = IncidentCoordinator(
                store,
                SafeDiagnoser(None, logger=self.logger),
                ModControlClient(endpoint.base_url),
                logger=self.logger,
            )
            decision = coordinator.handle(make_incident())
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertTrue(decision.used_fallback)
        self.assertIs(store.snapshot().latest_decision, decision)
        self.assertIn("could not reach", store.snapshot().local_error)
        self.assertEqual(endpoint.requests[0]["body"]["action"], "PAUSE")

    def test_duplicate_incident_is_diagnosed_and_paused_once(self) -> None:
        store = StatusStore()
        control = FakeControlClient()
        backend = FakeBackend({"action": "PAUSE_AND_ALERT"})
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(backend, logger=self.logger),
            control,
            logger=self.logger,
        )
        incident = make_incident()
        first = coordinator.handle(incident)
        second = coordinator.handle(incident)
        self.assertEqual(first, second)
        self.assertEqual(backend.calls, 1)
        self.assertEqual(control.actions, [ControlAction.PAUSE])

    def test_concurrent_duplicate_incident_shares_one_decision(self) -> None:
        store = StatusStore()
        control = FakeControlClient()
        backend = BlockingBackend()
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(backend, logger=self.logger),
            control,
            logger=self.logger,
        )
        incident = make_incident()
        results: list[Any] = []

        first = threading.Thread(
            target=lambda: results.append(coordinator.handle(incident))
        )
        second = threading.Thread(
            target=lambda: results.append(coordinator.handle(incident))
        )
        first.start()
        self.assertTrue(backend.entered.wait(timeout=2))
        second.start()
        backend.release.set()
        first.join(timeout=3)
        second.join(timeout=3)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0], results[1])
        self.assertEqual(backend.calls, 1)
        self.assertEqual(control.actions, [ControlAction.PAUSE])


if __name__ == "__main__":
    unittest.main()
