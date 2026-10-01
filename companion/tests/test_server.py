from __future__ import annotations

import json
import logging
import socket
import unittest
from contextlib import contextmanager
from typing import Any, Iterator
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from supervisor_companion.actions import ControlAction
from supervisor_companion.control import ControlResult
from supervisor_companion.diagnosis import IncidentCoordinator, SafeDiagnoser
from supervisor_companion.server import CompanionService
from supervisor_companion.store import StatusStore

from test_diagnosis import FakeBackend


class FakeControl:
    def __init__(self) -> None:
        self.actions: list[ControlAction] = []

    def send(self, action: ControlAction) -> ControlResult:
        self.actions.append(action)
        return ControlResult(action, "control-1", True, "Accepted.")


@contextmanager
def running_service(
    *,
    provider_result: Any = None,
    token: str | None = None,
    max_request_bytes: int = 262_144,
) -> Iterator[tuple[str, StatusStore, FakeControl]]:
    logger = logging.getLogger(f"test-server-{id(provider_result)}")
    logger.addHandler(logging.NullHandler())
    store = StatusStore()
    control = FakeControl()
    backend = FakeBackend(
        provider_result
        if provider_result is not None
        else {"action": "REPATH", "reason": "Retry path."}
    )
    coordinator = IncidentCoordinator(
        store,
        SafeDiagnoser(backend, logger=logger),
        control,
        logger=logger,
    )
    service = CompanionService(
        "127.0.0.1",
        0,
        store=store,
        incident_coordinator=coordinator,
        max_request_bytes=max_request_bytes,
        token=token,
        logger=logger,
    )
    with service:
        host, port = service.address
        yield f"http://{host}:{port}", store, control


def request_json(
    url: str,
    *,
    method: str = "GET",
    body: Any = None,
    token: str | None = None,
    content_type: str = "application/json",
) -> tuple[int, Any]:
    encoded = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json"}
    if encoded is not None:
        headers["Content-Type"] = content_type
    if token is not None:
        headers["X-Supervisor-Token"] = token
    request = Request(url, data=encoded, headers=headers, method=method)
    try:
        response = urlopen(request, timeout=2)
    except HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8"))
    with response:
        return response.status, json.loads(response.read().decode("utf-8"))


def valid_status() -> dict[str, Any]:
    return {
        "current_chunk": {"index": 2, "total": 49},
        "current_layer": {"order": "LAYERS", "stage": "STRUCTURE", "index": 3, "total": 20,
                          "y": 66, "chunk_index": 2, "chunk_total": 45},
        "phase": "BUILDING",
        "materials": {
            "dirt": {"available": 12, "required": 20},
            "food": 6,
        },
        "baritone_status": "Walking",
        "last_error": None,
    }


def valid_incident() -> dict[str, Any]:
    return {
        "incident_id": "incident-2",
        "category": "NO_PROGRESS",
        "summary": "No progress after recovery.",
        "recovery_exhausted": True,
        "status": valid_status(),
        "attempted_recovery": ["WAIT_FOR_LAG", "RESTART_PATH"],
        "details": {"stalled_seconds": 45},
    }


def raw_request(base_url: str, request: bytes) -> bytes:
    """Send exact framing and read all responses until the connection closes."""
    address = urlsplit(base_url)
    with socket.create_connection(
        (address.hostname, address.port), timeout=2
    ) as client:
        client.sendall(request)
        client.shutdown(socket.SHUT_WR)
        response = bytearray()
        while data := client.recv(4096):
            response.extend(data)
        return bytes(response)


class CompanionServiceTests(unittest.TestCase):
    def test_health_is_available(self) -> None:
        with running_service() as (base_url, _, _):
            status, body = request_json(f"{base_url}/v1/health")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"status": "ok"})

    def test_status_post_updates_in_memory_store(self) -> None:
        with running_service() as (base_url, store, _):
            status, body = request_json(
                f"{base_url}/v1/status",
                method="POST",
                body=valid_status(),
            )
            snapshot = store.snapshot()
        self.assertEqual(status, 202)
        self.assertTrue(body["accepted"])
        self.assertEqual(snapshot.status.current_chunk.index, 2)
        self.assertEqual(snapshot.status.current_layer.y, 66)
        self.assertEqual(snapshot.status.materials["dirt"].missing, 8)

    def test_status_get_returns_latest_view(self) -> None:
        with running_service() as (base_url, _, _):
            request_json(
                f"{base_url}/v1/status",
                method="POST",
                body=valid_status(),
            )
            status, body = request_json(f"{base_url}/v1/status")
        self.assertEqual(status, 200)
        self.assertEqual(body["status"]["phase"], "BUILDING")
        self.assertEqual(body["status"]["current_layer"], valid_status()["current_layer"])
        self.assertIsNone(body["latest_incident_id"])

    def test_incident_returns_constrained_provider_decision(self) -> None:
        with running_service(
            provider_result={"action": "RESTOCK", "reason": "Dirt missing."}
        ) as (base_url, store, control):
            status, body = request_json(
                f"{base_url}/v1/incidents",
                method="POST",
                body=valid_incident(),
            )
            snapshot = store.snapshot()
        self.assertEqual(status, 200)
        self.assertEqual(body["action"], "RESTOCK")
        self.assertFalse(body["used_fallback"])
        self.assertEqual(snapshot.latest_incident.incident_id, "incident-2")
        self.assertEqual(control.actions, [])

    def test_invalid_provider_action_returns_pause_and_sends_pause(self) -> None:
        with running_service(
            provider_result={"action": "RUN_COMMAND", "reason": "Unsafe"}
        ) as (base_url, _, control):
            status, body = request_json(
                f"{base_url}/v1/incidents",
                method="POST",
                body=valid_incident(),
            )
        self.assertEqual(status, 200)
        self.assertEqual(body["action"], "PAUSE_AND_ALERT")
        self.assertTrue(body["used_fallback"])
        self.assertEqual(control.actions, [ControlAction.PAUSE])

    def test_identical_http_retry_reuses_decision_and_pause_side_effect(self) -> None:
        with running_service(
            provider_result={
                "action": "PAUSE_AND_ALERT",
                "reason": "Review.",
            }
        ) as (base_url, _, control):
            first_status, first_body = request_json(
                f"{base_url}/v1/incidents",
                method="POST",
                body=valid_incident(),
            )
            second_status, second_body = request_json(
                f"{base_url}/v1/incidents",
                method="POST",
                body=valid_incident(),
            )
        self.assertEqual(first_status, 200)
        self.assertEqual(second_status, 200)
        self.assertEqual(first_body, second_body)
        self.assertEqual(control.actions, [ControlAction.PAUSE])

    def test_reused_incident_id_with_changed_data_returns_conflict(self) -> None:
        changed = valid_incident()
        changed["summary"] = "Different incident data."
        with running_service() as (base_url, _, _):
            first_status, _ = request_json(
                f"{base_url}/v1/incidents",
                method="POST",
                body=valid_incident(),
            )
            second_status, body = request_json(
                f"{base_url}/v1/incidents",
                method="POST",
                body=changed,
            )
        self.assertEqual(first_status, 200)
        self.assertEqual(second_status, 409)
        self.assertIn("reused", body["error"])

    def test_token_is_required_for_status_but_not_health(self) -> None:
        with running_service(token="shared-token") as (base_url, _, _):
            health_status, _ = request_json(f"{base_url}/v1/health")
            denied_status, _ = request_json(f"{base_url}/v1/status")
            allowed_status, _ = request_json(
                f"{base_url}/v1/status",
                token="shared-token",
            )
        self.assertEqual(health_status, 200)
        self.assertEqual(denied_status, 401)
        self.assertEqual(allowed_status, 200)

    def test_invalid_status_payload_returns_400(self) -> None:
        with running_service() as (base_url, _, _):
            status, body = request_json(
                f"{base_url}/v1/status",
                method="POST",
                body={"current_chunk": {"index": 0, "total": 49}},
            )
        self.assertEqual(status, 400)
        self.assertIn("at least 1", body["error"])

    def test_wrong_content_type_returns_415(self) -> None:
        with running_service() as (base_url, _, _):
            status, _ = request_json(
                f"{base_url}/v1/status",
                method="POST",
                body=valid_status(),
                content_type="text/plain",
            )
        self.assertEqual(status, 415)

    def test_rejected_body_cannot_be_parsed_as_another_request(self) -> None:
        embedded_request = (
            b"POST /v1/status HTTP/1.1\r\nHost: localhost\r\n"
            b"Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
        )
        request = (
            b"POST /v1/status HTTP/1.1\r\nHost: localhost\r\n"
            b"Content-Type: text/plain\r\nContent-Length: "
            + str(len(embedded_request)).encode("ascii")
            + b"\r\n\r\n"
            + embedded_request
        )
        with running_service() as (base_url, store, _):
            original_status = store.snapshot().status
            response = raw_request(base_url, request)
            self.assertIs(store.snapshot().status, original_status)
        self.assertTrue(response.startswith(b"HTTP/1.1 415 "))
        self.assertIn(b"Connection: close\r\n", response)
        self.assertEqual(response.count(b"HTTP/1.1 "), 1)

    def test_ambiguous_or_invalid_request_framing_is_rejected(self) -> None:
        framing_headers = (
            b"Content-Length: 2\r\nContent-Length: 3\r\n",
            b"Content-Length: 2\r\nTransfer-Encoding: chunked\r\n",
            b"Content-Length: +2\r\n",
            b"Content-Length: 0_2\r\n",
        )
        with running_service() as (base_url, store, _):
            original_status = store.snapshot().status
            for framing in framing_headers:
                with self.subTest(framing=framing):
                    response = raw_request(
                        base_url,
                        b"POST /v1/status HTTP/1.1\r\nHost: localhost\r\n"
                        b"Content-Type: application/json\r\n"
                        + framing
                        + b"\r\n{}",
                    )
                    self.assertTrue(response.startswith(b"HTTP/1.1 400 "))
                    self.assertIs(store.snapshot().status, original_status)

    def test_truncated_body_is_rejected_even_when_received_json_is_valid(self) -> None:
        with running_service() as (base_url, store, _):
            original_status = store.snapshot().status
            response = raw_request(
                base_url,
                b"POST /v1/status HTTP/1.1\r\nHost: localhost\r\n"
                b"Content-Type: application/json\r\nContent-Length: 10\r\n\r\n{}",
            )
            self.assertIs(store.snapshot().status, original_status)
        self.assertTrue(response.startswith(b"HTTP/1.1 400 "))

    def test_request_larger_than_configured_limit_returns_413(self) -> None:
        with running_service(max_request_bytes=64) as (base_url, _, _):
            status, _ = request_json(
                f"{base_url}/v1/status",
                method="POST",
                body=valid_status(),
            )
        self.assertEqual(status, 413)

    def test_unknown_path_returns_404(self) -> None:
        with running_service() as (base_url, _, _):
            status, body = request_json(f"{base_url}/v1/missing")
        self.assertEqual(status, 404)
        self.assertEqual(body["error"], "not found")

    def test_service_start_and_stop_are_idempotent(self) -> None:
        logger = logging.getLogger("test-server-idempotent")
        logger.addHandler(logging.NullHandler())
        store = StatusStore()
        control = FakeControl()
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(None, logger=logger),
            control,
            logger=logger,
        )
        service = CompanionService(
            "127.0.0.1",
            0,
            store=store,
            incident_coordinator=coordinator,
            logger=logger,
        )
        service.start()
        service.start()
        service.stop()
        service.stop()

    def test_direct_service_rejects_non_loopback_bind(self) -> None:
        logger = logging.getLogger("test-server-bind")
        logger.addHandler(logging.NullHandler())
        store = StatusStore()
        control = FakeControl()
        coordinator = IncidentCoordinator(
            store,
            SafeDiagnoser(None, logger=logger),
            control,
            logger=logger,
        )
        with self.assertRaisesRegex(ValueError, "loopback"):
            CompanionService(
                "0.0.0.0",
                0,
                store=store,
                incident_coordinator=coordinator,
                logger=logger,
            )


if __name__ == "__main__":
    unittest.main()
