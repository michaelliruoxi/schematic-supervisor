from __future__ import annotations

import json
import os
import socket
import threading
import unittest
from unittest.mock import patch

from supervisor_companion.actions import ControlAction
from supervisor_companion.control import ModControlClient
from supervisor_companion.http_json import post_json, post_text

from http_fixture import RecordingEndpoint


class FakeProxy:
    """An HTTP proxy that records each request it receives and answers it itself."""

    def __init__(self) -> None:
        self.requests: list[str] = []
        self._listener = socket.create_server(("127.0.0.1", 0))
        self.url = f"http://127.0.0.1:{self._listener.getsockname()[1]}"
        threading.Thread(target=self._serve, daemon=True).start()

    def close(self) -> None:
        self._listener.close()

    def _serve(self) -> None:
        while True:
            try:
                connection, _ = self._listener.accept()
            except OSError:
                return
            with connection:
                data = b""
                while b"\r\n\r\n" not in data:
                    chunk = connection.recv(65_536)
                    if not chunk:
                        break
                    data += chunk
                head, _, received = data.partition(b"\r\n\r\n")
                length = next((int(line.split(b":", 1)[1]) for line in head.split(b"\r\n")
                               if line.lower().startswith(b"content-length:")), 0)
                # Read the whole request; closing with unread data resets the connection.
                while len(received) < length:
                    chunk = connection.recv(65_536)
                    if not chunk:
                        break
                    received += chunk
                self.requests.append(head.decode("latin-1"))
                body = json.dumps({"proxied": True}).encode()
                connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                   + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body)


class ProxyPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.proxy = FakeProxy()
        self.addCleanup(self.proxy.close)
        environment = patch.dict(os.environ, {"HTTP_PROXY": self.proxy.url, "HTTPS_PROXY": self.proxy.url})
        environment.start()
        self.addCleanup(environment.stop)
        # An existing exemption on this computer would hide a proxied loopback request.
        for name in ("NO_PROXY", "no_proxy"):
            os.environ.pop(name, None)

    def test_loopback_controls_and_their_tokens_never_reach_a_proxy(self) -> None:
        with RecordingEndpoint() as endpoint:
            result = ModControlClient(endpoint.base_url, token="dummy-token").send(ControlAction.PAUSE)
            localhost = endpoint.base_url.replace("127.0.0.1", "localhost")
            self.assertEqual(200, post_text(localhost + "/topic", '{"message": "alert"}', timeout_seconds=3))
        self.assertTrue(result.accepted)
        self.assertEqual("dummy-token", endpoint.requests[0]["headers"]["X-Supervisor-Token"])
        self.assertEqual(2, len(endpoint.requests))
        self.assertEqual([], self.proxy.requests)

    def test_remote_endpoints_keep_the_configured_proxy(self) -> None:
        response = post_json("http://alerts.example.invalid/topic", {"message": "alert"}, timeout_seconds=3)
        self.assertEqual({"proxied": True}, response.body)
        self.assertEqual(200, post_text("http://alerts.example.invalid/topic", "alert", timeout_seconds=3))
        self.assertEqual(2, len(self.proxy.requests))
        self.assertTrue(all(request.startswith("POST http://alerts.example.invalid/topic HTTP/1.1")
                            for request in self.proxy.requests))


if __name__ == "__main__":
    unittest.main()
