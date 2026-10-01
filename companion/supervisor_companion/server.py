"""Loopback-only HTTP service for mod status and incident submissions."""

from __future__ import annotations

import hmac
import ipaddress
import json
import logging
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from typing import Any, Mapping

from .diagnosis import IncidentConflictError, IncidentCoordinator
from .models import Incident, PayloadError, StatusSnapshot
from .store import StatusStore


def _is_loopback_address(value: str) -> bool:
    try:
        return ipaddress.ip_address(value).is_loopback
    except ValueError:
        return False


def _is_supported_bind_host(value: str) -> bool:
    normalized = value.strip().lower().rstrip(".")
    if normalized == "localhost":
        return True
    try:
        address = ipaddress.ip_address(normalized)
    except ValueError:
        return False
    return address.version == 4 and address.is_loopback


class CompanionHttpServer(ThreadingHTTPServer):
    """HTTP server carrying dependencies used by request handlers."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        *,
        store: StatusStore,
        incident_coordinator: IncidentCoordinator,
        max_request_bytes: int,
        token: str | None,
        logger: logging.Logger,
    ) -> None:
        self.store = store
        self.incident_coordinator = incident_coordinator
        self.max_request_bytes = max_request_bytes
        self.required_token = token
        self.app_logger = logger
        super().__init__(server_address, CompanionRequestHandler)


class CompanionRequestHandler(BaseHTTPRequestHandler):
    """Strict JSON protocol handler; browser cross-origin access is unsupported."""

    server: CompanionHttpServer
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:
        self.server.app_logger.debug("HTTP " + format, *args)

    def do_GET(self) -> None:
        if not self._client_is_loopback():
            self._send_error(HTTPStatus.FORBIDDEN, "loopback clients only")
            return
        if self.path == "/v1/health":
            self._send_json(HTTPStatus.OK, {"status": "ok"})
        elif self.path == "/v1/status":
            if not self._authorized():
                self._send_error(HTTPStatus.UNAUTHORIZED, "unauthorized")
                return
            self._send_json(HTTPStatus.OK, self.server.store.snapshot().to_dict())
        else:
            self._send_error(HTTPStatus.NOT_FOUND, "not found")

    def do_POST(self) -> None:
        if not self._client_is_loopback():
            self._send_error(HTTPStatus.FORBIDDEN, "loopback clients only")
            return
        if self.path not in {"/v1/status", "/v1/incidents"}:
            self._send_error(HTTPStatus.NOT_FOUND, "not found")
            return
        if not self._authorized():
            self._send_error(HTTPStatus.UNAUTHORIZED, "unauthorized")
            return
        try:
            payload = self._read_json_object()
            if self.path == "/v1/status":
                status = StatusSnapshot.from_mapping(payload)
                self.server.store.update_status(status)
                self._send_json(
                    HTTPStatus.ACCEPTED,
                    {"accepted": True, "updated_at": status.updated_at},
                )
            else:
                incident = Incident.from_mapping(payload)
                decision = self.server.incident_coordinator.handle(incident)
                self._send_json(
                    HTTPStatus.OK,
                    decision.to_dict(incident.incident_id),
                )
        except PayloadError as error:
            self._send_error(HTTPStatus.BAD_REQUEST, str(error))
        except IncidentConflictError as error:
            self._send_error(HTTPStatus.CONFLICT, str(error))
        except json.JSONDecodeError:
            self._send_error(HTTPStatus.BAD_REQUEST, "request body is not valid JSON")
        except UnicodeDecodeError:
            self._send_error(HTTPStatus.BAD_REQUEST, "request body is not UTF-8")
        except RequestTooLarge:
            self._send_error(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "request body exceeds the size limit",
            )
        except RequestLengthRequired:
            self._send_error(
                HTTPStatus.LENGTH_REQUIRED,
                "Content-Length is required",
            )
        except UnsupportedContentType:
            self._send_error(
                HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type must be application/json",
            )
        except Exception:
            self.server.app_logger.exception("Unhandled companion HTTP error")
            self._send_error(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                "internal server error",
            )

    def do_OPTIONS(self) -> None:
        self._send_error(HTTPStatus.METHOD_NOT_ALLOWED, "method not allowed")

    def _client_is_loopback(self) -> bool:
        return _is_loopback_address(self.client_address[0])

    def _authorized(self) -> bool:
        required = self.server.required_token
        if required is None:
            return True
        supplied = self.headers.get("X-Supervisor-Token", "")
        return hmac.compare_digest(required, supplied)

    def _read_json_object(self) -> Mapping[str, Any]:
        if self.headers.get_all("Transfer-Encoding"):
            raise PayloadError("Transfer-Encoding is not supported")
        content_type = self.headers.get("Content-Type", "")
        if content_type.split(";", 1)[0].strip().lower() != "application/json":
            raise UnsupportedContentType
        lengths = self.headers.get_all("Content-Length", [])
        if not lengths:
            raise RequestLengthRequired
        if len(lengths) != 1:
            raise PayloadError("exactly one Content-Length is required")
        raw_length = lengths[0].strip()
        if not raw_length.isascii() or not raw_length.isdecimal():
            raise PayloadError("Content-Length must contain decimal digits only")
        try:
            length = int(raw_length)
        except ValueError as error:
            raise PayloadError("Content-Length must be an integer") from error
        if length > self.server.max_request_bytes:
            raise RequestTooLarge
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise PayloadError("request body is shorter than Content-Length")
        decoded = json.loads(raw.decode("utf-8"))
        if not isinstance(decoded, Mapping):
            raise PayloadError("request body must be a JSON object")
        return decoded

    def _send_error(self, status: HTTPStatus, message: str) -> None:
        # A rejected request can leave an unread body. Never parse those bytes
        # as a new request on a persistent connection.
        self.close_connection = True
        self._send_json(status, {"error": message, "status": status.value})

    def _send_json(self, status: HTTPStatus, payload: Mapping[str, Any]) -> None:
        body = json.dumps(
            payload,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)


class RequestTooLarge(Exception):
    pass


class RequestLengthRequired(Exception):
    pass


class UnsupportedContentType(Exception):
    pass


class CompanionService:
    """Lifecycle wrapper used by the GUI and headless tests."""

    def __init__(
        self,
        host: str,
        port: int,
        *,
        store: StatusStore,
        incident_coordinator: IncidentCoordinator,
        max_request_bytes: int = 262_144,
        token: str | None = None,
        logger: logging.Logger | None = None,
    ) -> None:
        if not _is_supported_bind_host(host):
            raise ValueError(
                "companion service host must be localhost or IPv4 loopback"
            )
        self._logger = logger or logging.getLogger(__name__)
        self._server = CompanionHttpServer(
            (host, port),
            store=store,
            incident_coordinator=incident_coordinator,
            max_request_bytes=max_request_bytes,
            token=token,
            logger=self._logger,
        )
        self._thread: Thread | None = None

    @property
    def address(self) -> tuple[str, int]:
        host, port = self._server.server_address[:2]
        return str(host), int(port)

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = Thread(
            target=self._server.serve_forever,
            name="companion-http",
            daemon=True,
        )
        self._thread.start()
        self._logger.info(
            "Companion HTTP service listening on %s:%s",
            *self.address,
        )

    def stop(self) -> None:
        thread = self._thread
        if thread is None:
            self._server.server_close()
            return
        self._server.shutdown()
        self._server.server_close()
        thread.join(timeout=5)
        self._thread = None
        self._logger.info("Companion HTTP service stopped")

    def __enter__(self) -> "CompanionService":
        self.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self.stop()
