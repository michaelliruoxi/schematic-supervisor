from __future__ import annotations

from datetime import datetime, timedelta, timezone
import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
import unittest
from unittest.mock import patch

from supervisor_companion.agent_bridge import AgentBridge, MAX_MESSAGE_BYTES, McpSession, serve_stdio
from supervisor_companion.config import AppConfig, ConfigurationError
from http_fixture import RecordingEndpoint


def observation(**changes):
    data = {"protocol_version": 1, "run_id": "run-one", "state": "IDLE",
            "updated_at": datetime.now(timezone.utc).isoformat(), "ready": True,
            "allowed_actions": ["START", "PAUSE", "STOP", "SCAN_DEPOTS"],
            "last_control": {"sequence": 2, "action": "STOP", "request_id": "operator"}}
    data.update(changes)
    return data


def make_bridge(endpoint, *, token=True, stale_after_seconds=15):
    data = {"mod": {"base_url": endpoint.base_url, "timeout_seconds": 0.2,
                     "token_env": "BRIDGE_TEST_TOKEN" if token else ""}}
    with patch.dict(os.environ, {"BRIDGE_TEST_TOKEN": "shared-test-value"}):
        return AgentBridge(AppConfig.from_mapping(data), stale_after_seconds=stale_after_seconds)


class AgentBridgeTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"BRIDGE_TEST_TOKEN": "shared-test-value", "SCHEMATIC_PROTOCOL_TOKEN": ""})
        environment.start()
        self.addCleanup(environment.stop)

    def test_reads_direct_observation_without_companion(self):
        with RecordingEndpoint(lambda _: (200, observation())) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertTrue(result["ok"])
        self.assertEqual(result["connection"], "online")
        self.assertEqual(result["observation"]["state"], "IDLE")
        self.assertEqual(len(endpoint.requests), 1)
        self.assertEqual(endpoint.requests[0]["path"], "/v1/observation")
        self.assertEqual(endpoint.requests[0]["headers"]["X-Supervisor-Token"], "shared-test-value")

    def test_preserves_optional_inventory_shop_and_player_observations(self):
        inventory = {"available": True, "main_slots": [{"slot": 0, "item_id": "minecraft:dirt", "count": 32}],
                     "main_material_totals": {"dirt": 32}}
        shop = {"available": True, "state": "FAILED", "entries": [{"slot": 4, "item_id": "minecraft:dirt"}]}
        player = {"x": 10.5, "y": -61.0, "z": -20.5, "flying": True}
        with RecordingEndpoint(lambda _: (200, observation(inventory=inventory, shop=shop, player=player))) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertTrue(result["ok"])
        self.assertEqual(result["observation"]["inventory"], inventory)
        self.assertEqual(result["observation"]["shop"], shop)
        self.assertEqual(result["observation"]["player"], player)
        self.assertEqual(len(endpoint.requests), 1)

    def test_active_control_forwards_snapshot_guards_and_requires_acknowledgment(self):
        def reply(request):
            return (200, observation() if request["method"] == "GET" else
                    {"accepted": True, "state": "PLANNING", "message": "Started."})
        with RecordingEndpoint(reply) as endpoint:
            result = make_bridge(endpoint).control("START", request_id="run-action-1")
        self.assertEqual(result["outcome"], "accepted")
        self.assertTrue(result["acknowledged"])
        payload = endpoint.requests[1]["body"]
        self.assertEqual(payload["expected_run_id"], "run-one")
        self.assertEqual(payload["expected_state"], "IDLE")
        self.assertEqual(payload["expected_control_sequence"], 2)
        self.assertEqual(payload["request_id"], "run-action-1")

    def test_stale_invalid_and_future_timestamps_block_active_controls(self):
        timestamps = [None, "bad", "2026-01-01", "2026-01-01T01:01:01",
                      (datetime.now(timezone.utc) - timedelta(seconds=30)).isoformat(),
                      (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat()]
        for timestamp in timestamps:
            with self.subTest(timestamp=timestamp), RecordingEndpoint(
                    lambda _: (200, observation(updated_at=timestamp))) as endpoint:
                bridge = make_bridge(endpoint)
                self.assertFalse(bridge.observe()["fresh"])
                self.assertEqual(bridge.control("START")["outcome"], "not_sent")
                self.assertTrue(all(request["method"] == "GET" for request in endpoint.requests))

    def test_unknown_protocol_or_schema_is_not_online(self):
        for changes in ({"protocol_version": 2}, {"ready": "yes"},
                        {"allowed_actions": ["CHAT"]}, {"run_id": None}):
            with self.subTest(changes=changes), RecordingEndpoint(
                    lambda _: (200, observation(**changes))) as endpoint:
                self.assertEqual(make_bridge(endpoint).observe()["connection"], "invalid")

    def test_read_unauthorized_is_distinct_from_offline(self):
        with RecordingEndpoint(lambda _: (401, {"error": "Unauthorized"})) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertEqual(result["connection"], "unauthorized")
        self.assertEqual(result["http_status"], 401)

    def test_control_allowed_actions_and_prior_snapshot_are_checked(self):
        for arguments in ({"action": "RESUME"},
                          {"action": "START", "expected_run_id": "old", "expected_state": "IDLE"},
                          {"action": "START", "expected_control_sequence": 1}):
            with self.subTest(arguments=arguments), RecordingEndpoint(
                    lambda _: (200, observation())) as endpoint:
                self.assertEqual(make_bridge(endpoint).control(**arguments)["outcome"], "not_sent")
                self.assertEqual(len(endpoint.requests), 1)

    def test_active_action_without_token_is_not_sent(self):
        with RecordingEndpoint() as endpoint:
            self.assertEqual(make_bridge(endpoint, token=False).control("START")["outcome"], "not_sent")
        self.assertEqual(endpoint.requests, [])

    def test_pause_and_stop_do_not_depend_on_fresh_observation(self):
        for action in ("PAUSE", "STOP"):
            with self.subTest(action=action), RecordingEndpoint() as endpoint:
                result = make_bridge(endpoint, token=False).control(action)
            self.assertTrue(result["ok"])
            self.assertEqual(len(endpoint.requests), 1)
            self.assertEqual(endpoint.requests[0]["method"], "POST")
            self.assertNotIn("expected_state", endpoint.requests[0]["body"])

    def test_rejected_control_preserves_mod_message(self):
        for code in (200, 409):
            with self.subTest(code=code), RecordingEndpoint(
                    lambda _: (code, {"accepted": False, "message": "State changed."})) as endpoint:
                result = make_bridge(endpoint).control("PAUSE")
            self.assertEqual(result["outcome"], "rejected")
            self.assertTrue(result["acknowledged"])
            self.assertFalse(result["ok"])
            self.assertEqual(result["message"], "State changed.")

    def test_timeout_and_invalid_acknowledgment_have_unknown_outcome(self):
        for code, body in ((200, {}), (200, {"accepted": "true"}),
                           (204, b""), (504, {"accepted": False, "message": "Outcome is unknown."})):
            with self.subTest(code=code, body=body), RecordingEndpoint(
                    lambda _: (code, body)) as endpoint:
                result = make_bridge(endpoint).control("STOP")
            self.assertEqual(result["outcome"], "unknown")
            self.assertFalse(result["acknowledged"])
            self.assertIn("next_step", result)

    def test_redirect_is_not_followed_and_token_not_forwarded(self):
        with RecordingEndpoint() as target, RecordingEndpoint(
                lambda _: (302, {}, {"Location": target.base_url})) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertFalse(result["ok"])
        self.assertEqual(target.requests, [])

    def test_config_rejects_remote_and_invalid_freshness(self):
        with self.assertRaises(ConfigurationError):
            AppConfig.from_mapping({"mod": {"base_url": "https://example.com"}})
        for value in (True, float("nan"), 0, 121):
            with self.subTest(value=value), self.assertRaises(ConfigurationError):
                AgentBridge(AppConfig(), stale_after_seconds=value)

    def test_bad_control_arguments_do_not_use_network(self):
        with RecordingEndpoint() as endpoint:
            bridge = make_bridge(endpoint)
            for kwargs in ({"action": "CHAT"}, {"action": "PAUSE", "request_id": ""},
                           {"action": "PAUSE", "expected_run_id": "run"},
                           {"action": "PAUSE", "expected_control_sequence": True}):
                self.assertEqual(bridge.control(**kwargs)["outcome"], "not_sent")
        self.assertEqual(endpoint.requests, [])

    def test_token_file_is_read_dynamically_and_environment_takes_priority(self):
        with tempfile.TemporaryDirectory() as directory, RecordingEndpoint(
                lambda _: (200, observation())) as endpoint:
            path = Path(directory) / "protocol-token.txt"
            config = AppConfig.from_mapping({"mod": {"base_url": endpoint.base_url,
                                                     "token_env": "LATE_BRIDGE_TEST_TOKEN"}})
            with patch.dict(os.environ, {}, clear=True):
                bridge = AgentBridge(config, token_file=path)
                self.assertIsNone(bridge.mod_token)
                path.write_text("a" * 64, encoding="ascii")
                self.assertTrue(bridge.observe()["ok"])
                path.write_text("b" * 64, encoding="ascii")
                self.assertTrue(bridge.observe()["ok"])
                with patch.dict(os.environ, {"LATE_BRIDGE_TEST_TOKEN": "environment-token"}):
                    self.assertTrue(bridge.observe()["ok"])
            self.assertEqual([item["headers"]["X-Supervisor-Token"] for item in endpoint.requests],
                             ["a" * 64, "b" * 64, "environment-token"])

    def test_invalid_token_file_returns_configuration_error_without_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "protocol-token.txt"
            path.write_text("secret\nheader:bad", encoding="ascii")
            bridge = AgentBridge(AppConfig(), token_file=path)
            self.assertEqual(bridge.observe()["connection"], "configuration_error")
            self.assertNotIn("secret", bridge.control("START")["message"])
            path.write_text("a" * 257, encoding="ascii")
            self.assertEqual(bridge.observe()["connection"], "configuration_error")

    def test_pairing_errors_name_the_file_or_variable_but_never_the_value(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "protocol-token.txt"
            expected = f"The pairing file at {path.resolve()} is invalid."
            for content in (b"secret\nheader:bad", "café-secret".encode("utf-8"), b"s" * 257):
                with self.subTest(content=content[:12]):
                    path.write_bytes(content)
                    bridge = AgentBridge(AppConfig(), token_file=path)
                    result = bridge.observe()
                    self.assertEqual((result["connection"], result["message"]), ("configuration_error", expected))
                    self.assertEqual(bridge.control("START")["message"], expected)
            # A folder where the file should be can be found but not read.
            folder = Path(directory)
            unreadable = AgentBridge(AppConfig(), token_file=folder).observe()
            self.assertEqual((unreadable["connection"], unreadable["message"]),
                             ("configuration_error", f"Could not read the pairing file at {folder.resolve()}."))
        config = AppConfig.from_mapping({"mod": {"token_env": "BRIDGE_TEST_TOKEN"}})
        with patch.dict(os.environ, {"BRIDGE_TEST_TOKEN": "secret value"}):
            result = AgentBridge(config).observe()
        self.assertEqual((result["connection"], result["message"]),
                         ("configuration_error", "The pairing token in environment variable BRIDGE_TEST_TOKEN "
                                                 "is invalid."))

    def test_default_mod_environment_override_matches_mod_without_config(self):
        with patch.dict(os.environ, {"SCHEMATIC_PROTOCOL_TOKEN": "environment-token"}):
            self.assertEqual(AgentBridge(AppConfig()).mod_token, "environment-token")

    def test_refused_connection_names_the_likely_cause(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        listener.close()
        # Windows retries a refused loopback connection for about 2 s before reporting it.
        config = AppConfig.from_mapping({"mod": {"base_url": f"http://127.0.0.1:{port}", "timeout_seconds": 5.0}})
        result = AgentBridge(config).observe()
        self.assertEqual(result["connection"], "offline")
        self.assertEqual(result["detail"], "refused")
        self.assertIn("isn't running", result["message"])

    def test_slow_mod_reports_a_timeout(self):
        def slow(_request):
            time.sleep(0.6)
            return 200, observation()
        with RecordingEndpoint(slow) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertEqual(result["connection"], "offline")
        self.assertEqual(result["detail"], "timeout")
        self.assertIn("didn't answer", result["message"])

    def test_dropped_connection_reports_reset(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def drop():
            connection, _ = listener.accept()
            connection.recv(4096)
            connection.close()

        worker = threading.Thread(target=drop, daemon=True)
        worker.start()
        try:
            config = AppConfig.from_mapping({"mod": {"base_url": f"http://127.0.0.1:{port}", "timeout_seconds": 2.0}})
            result = AgentBridge(config).observe()
        finally:
            worker.join(timeout=5)
            listener.close()
        self.assertEqual(result["connection"], "offline")
        self.assertEqual(result["detail"], "reset")

    def test_unauthorized_detail_distinguishes_missing_and_rejected_tokens(self):
        with RecordingEndpoint(lambda _: (401, {"accepted": False, "message": "Unauthorized."})) as endpoint:
            rejected = make_bridge(endpoint).observe()
            missing = make_bridge(endpoint, token=False).observe()
        self.assertEqual(rejected["connection"], "unauthorized")
        self.assertEqual(rejected["detail"], "token_rejected")
        self.assertEqual(missing["connection"], "unauthorized")
        self.assertEqual(missing["detail"], "token_missing")
        self.assertIn("Launch the game once", missing["message"])

    def test_starting_mod_reports_not_ready(self):
        body = {"accepted": False, "message": "Observation is not ready; wait for a client tick."}
        with RecordingEndpoint(lambda _: (503, body)) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertEqual(result["connection"], "http_error")
        self.assertEqual(result["detail"], "not_ready")

    def test_stale_observation_reports_stale_detail(self):
        old = (datetime.now(timezone.utc) - timedelta(seconds=60)).isoformat()
        with RecordingEndpoint(lambda _: (200, observation(updated_at=old))) as endpoint:
            result = make_bridge(endpoint).observe()
        self.assertEqual(result["connection"], "stale")
        self.assertEqual(result["detail"], "stale")

    def test_progress_reads_the_endpoint_with_the_token(self):
        payload = {"protocol_version": 1, "available": False, "revision": 3, "reason": "No plan is loaded."}
        with RecordingEndpoint(lambda _: (200, payload)) as endpoint:
            result = make_bridge(endpoint).progress()
        self.assertTrue(result["ok"])
        self.assertEqual(result["progress"], payload)
        self.assertEqual(endpoint.requests[0]["path"], "/v1/progress")
        self.assertEqual(endpoint.requests[0]["headers"]["X-Supervisor-Token"], "shared-test-value")

    def test_progress_on_an_older_mod_reports_http_404(self):
        with RecordingEndpoint(lambda _: (404, {"accepted": False, "message": "Not found."})) as endpoint:
            result = make_bridge(endpoint).progress()
        self.assertFalse(result["ok"])
        self.assertEqual(result["connection"], "http_error")
        self.assertEqual(result["http_status"], 404)


class McpProtocolTests(unittest.TestCase):
    def make_session(self):
        return McpSession(AgentBridge(AppConfig()))

    @staticmethod
    def request(method, params=None, request_id=1):
        return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}

    def test_handshake_discovery_and_offline_call(self):
        session = self.make_session()
        initialize = session.handle(self.request("initialize", {"protocolVersion": "2025-06-18"}))
        self.assertEqual(initialize["result"]["protocolVersion"], "2025-06-18")
        self.assertIsNone(session.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))
        self.assertEqual(session.handle(self.request("ping"))["result"], {})
        tools = session.handle(self.request("tools/list"))["result"]["tools"]
        self.assertEqual([item["name"] for item in tools], ["supervisor_observe", "supervisor_control"])
        with patch.object(session.bridge, "observe", return_value={"ok": False, "connection": "offline"}):
            result = session.handle(self.request("tools/call", {"name": "supervisor_observe"}))["result"]
        self.assertTrue(result["isError"])
        self.assertEqual(json.loads(result["content"][0]["text"]), result["structuredContent"])

    def test_no_tool_execution_before_initialize_or_by_notification(self):
        session = self.make_session()
        with patch.object(session.bridge, "control") as control:
            message = self.request("tools/call", {"name": "supervisor_control", "arguments": {"action": "STOP"}})
            self.assertIn("error", session.handle(message))
            message.pop("id")
            self.assertIsNone(session.handle(message))
            control.assert_not_called()

    def test_invalid_requests_and_unknown_tools(self):
        session = self.make_session()
        session.handle(self.request("initialize", {"protocolVersion": "future"}))
        for message in ([], {"method": "ping"}, self.request("tools/call", {"name": "missing"}),
                        self.request("tools/call", {"name": "supervisor_observe", "arguments": {"extra": True}}),
                        self.request("tools/call", {"name": "supervisor_observe", "arguments": {"include_companion": "yes"}})):
            self.assertIn("error", session.handle(message))

    def test_stdio_handles_malformed_and_overlong_lines_then_recovers(self):
        messages = ("not json\n" + "x" * (MAX_MESSAGE_BYTES + 5) + "\n" +
                    json.dumps(self.request("ping")) + "\n")
        output = io.StringIO()
        serve_stdio(AgentBridge(AppConfig()), io.StringIO(messages), output)
        responses = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(len(responses), 3)
        self.assertEqual(responses[0]["error"]["code"], -32700)
        self.assertEqual(responses[1]["error"]["code"], -32700)
        self.assertEqual(responses[2]["result"], {})

    def test_real_stdio_process_exits_on_eof_and_stdout_is_only_json(self):
        messages = [self.request("initialize", {"protocolVersion": "2025-11-25"}),
                    {"jsonrpc": "2.0", "method": "notifications/initialized"},
                    self.request("tools/list", request_id=2)]
        completed = subprocess.run([sys.executable, "-m", "supervisor_companion.agent_bridge"],
                                   input="".join(json.dumps(item) + "\n" for item in messages),
                                   text=True, capture_output=True, timeout=10)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stderr, "")
        lines = completed.stdout.splitlines()
        self.assertEqual(len(lines), 2)
        self.assertEqual([json.loads(line)["id"] for line in lines], [1, 2])


if __name__ == "__main__":
    unittest.main()
