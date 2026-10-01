"""Reusable temporary JSON HTTP endpoint for offline tests."""

from __future__ import annotations

import json
from contextlib import AbstractContextManager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from typing import Any, Callable


class RecordingEndpoint(AbstractContextManager["RecordingEndpoint"]):
    def __init__(
        self,
        responder: Callable[
            [dict[str, Any]],
            tuple[int, Any] | tuple[int, Any, dict[str, str]],
        ]
        | None = None,
    ) -> None:
        self.requests: list[dict[str, Any]] = []
        self._responder = responder or (
            lambda _: (200, {"accepted": True, "message": "Accepted."})
        )
        endpoint = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self, method: str, body: Any) -> None:
                request: dict[str, Any] = {
                    "method": method,
                    "path": self.path,
                    "headers": dict(self.headers.items()),
                    "body": body,
                }
                endpoint.requests.append(request)
                result = endpoint._responder(request)
                if len(result) == 2:
                    status, response = result
                    response_headers: dict[str, str] = {}
                else:
                    status, response, response_headers = result
                encoded = (
                    response
                    if isinstance(response, bytes)
                    else json.dumps(response).encode("utf-8")
                )
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                for header, value in response_headers.items():
                    self.send_header(header, value)
                self.send_header("Content-Length", str(len(encoded)))
                try:
                    self.end_headers()
                    self.wfile.write(encoded)
                except ConnectionError:
                    # The client already hung up, for example after its own timeout.
                    self.close_connection = True

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                self._handle("POST", json.loads(raw.decode("utf-8")))

            def do_GET(self) -> None:
                self._handle("GET", None)

            def log_message(self, *_: Any) -> None:
                return

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        host, port = self._server.server_address
        return f"http://{host}:{port}"

    def __enter__(self) -> "RecordingEndpoint":
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)
