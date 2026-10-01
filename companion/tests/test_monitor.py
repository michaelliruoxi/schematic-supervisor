from __future__ import annotations

from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from threading import Event, Lock
import time
import unittest
from unittest.mock import patch

from http_fixture import RecordingEndpoint
from log_capture import capture
from supervisor_companion.agent_bridge import AgentBridge
from supervisor_companion.config import AppConfig
from supervisor_companion.monitor import MAX_EVENTS, MonitorService
from test_progress import progress_payload


def observation(**changes):
    value = {"protocol_version": 1, "run_id": "monitor-run", "state": "PAUSED",
             "updated_at": datetime.now(timezone.utc).isoformat(), "ready": True,
             "allowed_actions": ["RESUME", "PAUSE", "STOP"],
             "last_control": {"sequence": 7}, "confirmed_placements": 100}
    value.update(changes)
    return value


def wait_for(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.01)
    raise AssertionError("The monitor did not reach the expected state.")


class MonitorTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"MONITOR_TEST_TOKEN": "test-pairing-value"})
        environment.start()
        self.addCleanup(environment.stop)

    def service(self, endpoint, *, poll_interval_seconds=60, clock=time.monotonic, **kwargs):
        config = AppConfig.from_mapping({"mod": {"base_url": endpoint.base_url,
                                                  "token_env": "MONITOR_TEST_TOKEN",
                                                  "timeout_seconds": 1.0}})
        # Collect log records so expected warnings and tracebacks stay out of the test output.
        logger, self.log_records = capture("test.monitor")
        service = MonitorService(AgentBridge(config, **kwargs), poll_interval_seconds=poll_interval_seconds,
                                 logger=logger, clock=clock)
        self.addCleanup(service.stop)
        service.start()
        return service

    def test_live_http_refresh_updates_state_and_does_not_send_controls(self):
        current = observation()
        with RecordingEndpoint(lambda _: (200, dict(current))) as endpoint:
            service = self.service(endpoint)
            first = wait_for(lambda: service.snapshot()["fresh"] and service.snapshot())
            self.assertEqual(first["observation"]["state"], "PAUSED")
            self.assertTrue(first["paired"])
            self.assertIsNotNone(first["last_seen"])
            current.update(state="EXECUTING", confirmed_placements=112)
            service.refresh()
            wait_for(lambda: service.snapshot()["observation"]["state"] == "EXECUTING")
            self.assertEqual(service.snapshot()["observation"]["confirmed_placements"], 112)
            self.assertTrue(all(request["method"] == "GET" for request in endpoint.requests))
            first["observation"]["state"] = "BROKEN"
            self.assertEqual(service.snapshot()["observation"]["state"], "EXECUTING")
            service.stop()

    def test_periodic_polling_observes_progress_without_manual_refresh(self):
        placement = [100]
        with RecordingEndpoint(lambda _: (200, observation(confirmed_placements=placement[0]))) as endpoint:
            service = self.service(endpoint, poll_interval_seconds=0.05)
            wait_for(lambda: service.snapshot()["fresh"])
            placement[0] = 125
            wait_for(lambda: service.snapshot()["observation"]["confirmed_placements"] == 125)
            self.assertGreaterEqual(len(endpoint.requests), 2)
            self.assertTrue(all(request["method"] == "GET" for request in endpoint.requests))
            service.stop()

    def test_resume_captures_displayed_guards_and_never_auto_retries(self):
        def reply(request):
            if request["method"] == "GET":
                return 200, observation()
            return 200, {"accepted": True, "message": "Resumed.", "state": "EXECUTING"}
        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            self.assertTrue(service.send_control("RESUME"))
            wait_for(lambda: not service.snapshot()["pending_actions"])
            posts = [request for request in endpoint.requests if request["method"] == "POST"]
            self.assertEqual(len(posts), 1)
            self.assertEqual(posts[0]["body"]["expected_run_id"], "monitor-run")
            self.assertEqual(posts[0]["body"]["expected_state"], "PAUSED")
            self.assertEqual(posts[0]["body"]["expected_control_sequence"], 7)
            self.assertIn("RESUME accepted", service.snapshot()["control_message"])
            service.stop()

    def test_changed_control_sequence_rejects_displayed_decision_without_post(self):
        sequence = [7]
        with RecordingEndpoint(lambda _: (200, observation(last_control={"sequence": sequence[0]}))) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            sequence[0] = 8
            self.assertTrue(service.send_control("RESUME"))
            wait_for(lambda: not service.snapshot()["pending_actions"])
            self.assertIn("not_sent", service.snapshot()["control_message"])
            self.assertTrue(all(request["method"] == "GET" for request in endpoint.requests))
            service.stop()

    def test_poll_update_between_render_and_click_rejects_unseen_control_sequence(self):
        sequence = [7]
        with RecordingEndpoint(lambda _: (200, observation(last_control={"sequence": sequence[0]}))) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            displayed = service.snapshot()["observation"]
            sequence[0] = 8
            service.refresh()
            wait_for(lambda: service.snapshot()["observation"]["last_control"]["sequence"] == 8)
            self.assertFalse(service.send_control("RESUME", observation=displayed))
            self.assertIn("displayed observation has changed", service.snapshot()["control_message"])
            self.assertTrue(all(request["method"] == "GET" for request in endpoint.requests))
            service.stop()

    def test_outage_retains_observation_but_blocks_resume_and_recovers(self):
        response = [200, observation()]
        with RecordingEndpoint(lambda _: tuple(response)) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            response[:] = [503, {"message": "unavailable"}]
            service.refresh()
            wait_for(lambda: service.snapshot()["connection"] == "http_error")
            snapshot = service.snapshot()
            self.assertFalse(snapshot["fresh"])
            self.assertEqual(snapshot["observation"]["run_id"], "monitor-run")
            self.assertFalse(service.send_control("RESUME"))
            self.assertFalse(any(request["method"] == "POST" for request in endpoint.requests))
            response[:] = [200, observation(state="WAITING_MATERIALS")]
            service.refresh()
            wait_for(lambda: service.snapshot()["fresh"])
            self.assertEqual(service.snapshot()["observation"]["state"], "WAITING_MATERIALS")
            service.stop()

    def test_observation_ages_without_another_poll(self):
        stamp = (datetime.now(timezone.utc) - timedelta(seconds=0.3)).isoformat()
        with RecordingEndpoint(lambda _: (200, observation(updated_at=stamp))) as endpoint:
            service = self.service(endpoint, stale_after_seconds=1)
            wait_for(lambda: service.snapshot()["fresh"])
            wait_for(lambda: not service.snapshot()["fresh"])
            self.assertEqual(service.snapshot()["connection"], "stale")
            self.assertFalse(service.send_control("RESUME"))
            service.stop()

    def test_closed_endpoint_marks_retained_observation_offline(self):
        with RecordingEndpoint(lambda _: (200, observation())) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
        service.refresh()
        wait_for(lambda: service.snapshot()["connection"] == "offline")
        self.assertFalse(service.snapshot()["fresh"])
        self.assertEqual(service.snapshot()["observation"]["run_id"], "monitor-run")
        self.assertFalse(service.send_control("RESUME"))
        service.stop()

    def test_pause_overtakes_delayed_resume_and_is_reasserted(self):
        entered, release = Event(), Event()
        control_lock = Lock()
        applied = []
        def reply(request):
            if request["method"] == "GET":
                return 200, observation()
            action = request["body"]["action"]
            if action == "RESUME":
                entered.set()
                release.wait(0.9)
            with control_lock:
                applied.append(action)
            return 200, {"accepted": True, "message": "Applied."}
        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            self.assertTrue(service.send_control("RESUME"))
            self.assertTrue(entered.wait(1))
            self.assertFalse(service.send_control("RESUME"))
            self.assertTrue(service.send_control("PAUSE"))
            wait_for(lambda: applied == ["PAUSE"])
            release.set()
            wait_for(lambda: not service.snapshot()["pending_actions"])
            self.assertEqual(applied, ["PAUSE", "RESUME", "PAUSE"])
            self.assertIn("Safety reassertion", service.snapshot()["control_message"])
            service.stop()

    def test_process_exit_waits_for_pending_pause_safety_reassertion(self):
        resume_entered, pause_entered, release_resume = Event(), Event(), Event()
        applied = []
        control_lock = Lock()
        def reply(request):
            if request["method"] == "GET":
                return 200, observation()
            action = request["body"]["action"]
            if action == "RESUME":
                resume_entered.set()
                release_resume.wait(2)
            with control_lock:
                applied.append(action)
            if action == "PAUSE":
                pause_entered.set()
            return 200, {"accepted": True, "message": "Applied."}
        script = """
import sys
import time
from supervisor_companion.agent_bridge import AgentBridge
from supervisor_companion.config import AppConfig
from supervisor_companion.monitor import MonitorService
config = AppConfig.from_mapping({"mod": {"base_url": sys.argv[1],
    "token_env": "MONITOR_TEST_TOKEN", "timeout_seconds": 3.0}})
service = MonitorService(AgentBridge(config), poll_interval_seconds=60)
service.start()
deadline = time.monotonic() + 3
while not service.snapshot()["fresh"]:
    if time.monotonic() > deadline:
        raise RuntimeError("No observation")
    time.sleep(0.01)
assert service.send_control("RESUME")
print("RESUME queued", flush=True)
sys.stdin.readline()
assert service.send_control("PAUSE")
service.stop()
print("Main exiting", flush=True)
"""
        with RecordingEndpoint(reply) as endpoint:
            process = subprocess.Popen(
                [sys.executable, "-c", script, endpoint.base_url],
                cwd=Path(__file__).resolve().parents[1], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            try:
                self.assertTrue(resume_entered.wait(2))
                process.stdin.write("close\n")
                process.stdin.flush()
                self.assertTrue(pause_entered.wait(2))
                # Main has returned, but the pending active request and its
                # required trailing Pause must keep the process alive.
                wait_for(lambda: process.stdout.readline().strip() == "Main exiting")
                self.assertIsNone(process.poll())
                release_resume.set()
                _, error = process.communicate(timeout=5)
                self.assertEqual(process.returncode, 0, error)
                self.assertEqual(applied, ["PAUSE", "RESUME", "PAUSE"])
            finally:
                release_resume.set()
                if process.poll() is None:
                    process.kill()
                    process.communicate(timeout=5)

    def test_safety_is_available_without_freshness_or_token(self):
        def reply(request):
            if request["method"] == "GET":
                return 503, {}
            return 200, {"accepted": True, "message": "Paused."}
        with patch.dict(os.environ, {"MONITOR_TEST_TOKEN": ""}), RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["connection"] == "http_error")
            self.assertFalse(service.snapshot()["paired"])
            self.assertTrue(service.send_control("PAUSE"))
            wait_for(lambda: not service.snapshot()["pending_actions"])
            post = next(request for request in endpoint.requests if request["method"] == "POST")
            self.assertNotIn("expected_state", post["body"])
            service.stop()

    def test_errors_are_bounded_and_do_not_expose_exception_details(self):
        with RecordingEndpoint(lambda _: (200, observation())) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            with patch.object(service.bridge, "control", side_effect=RuntimeError("secret-credential-value")):
                self.assertTrue(service.send_control("STOP"))
                wait_for(lambda: not service.snapshot()["pending_actions"])
            self.assertNotIn("secret-credential-value", str(service.snapshot()))
            self.assertIn("unknown", service.snapshot()["control_message"])
            for _ in range(MAX_EVENTS + 20):
                self.assertFalse(service.send_control("UNSUPPORTED"))
            self.assertEqual(len(service.snapshot()["events"]), MAX_EVENTS)
            service.stop()
            self.assertFalse(service.send_control("RESUME"))

    def poll_twice(self, service, endpoint):
        """Wait until two more full poll cycles have run."""
        for _ in range(2):
            before = sum(1 for request in endpoint.requests if request["path"] == "/v1/observation")
            service.refresh()
            wait_for(lambda: sum(1 for request in endpoint.requests
                                 if request["path"] == "/v1/observation") > before)

    def test_progress_is_refetched_when_revision_changes_including_restarts(self):
        revision = [5]

        def reply(request):
            if request["path"] == "/v1/progress":
                return 200, progress_payload(revision=revision[0])
            return 200, observation(progress_revision=revision[0])

        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["progress_status"] == "ok")
            progress_calls = lambda: sum(1 for request in endpoint.requests if request["path"] == "/v1/progress")
            self.assertEqual(progress_calls(), 1)
            self.poll_twice(service, endpoint)
            self.assertEqual(progress_calls(), 1)
            revision[0] = 1
            service.refresh()
            wait_for(lambda: service.snapshot()["progress"].revision == 1)
            self.assertEqual(progress_calls(), 2)
            self.assertIn("pace", service.snapshot())
            service.stop()

    def test_missing_progress_endpoint_is_marked_unsupported_without_refetching(self):
        def reply(request):
            if request["path"] == "/v1/progress":
                return 404, {"accepted": False, "message": "Not found."}
            return 200, observation()

        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["progress_status"] == "unsupported")
            self.poll_twice(service, endpoint)
            self.assertEqual(sum(1 for request in endpoint.requests if request["path"] == "/v1/progress"), 1)
            self.assertIsNone(service.snapshot()["progress"])
            service.stop()

    def test_controls_carry_monitor_request_ids(self):
        def reply(request):
            if request["method"] == "GET":
                return 200, observation()
            return 200, {"accepted": True, "message": "Resumed.", "state": "BUILDING"}

        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            self.assertTrue(service.send_control("RESUME"))
            wait_for(lambda: not service.snapshot()["pending_actions"])
            post = next(request for request in endpoint.requests if request["method"] == "POST")
            self.assertTrue(post["body"]["request_id"].startswith("monitor-"))
            self.assertIn(post["body"]["request_id"], service.snapshot()["own_request_ids"])
            service.stop()

    def test_stage_changes_and_state_age_are_recorded(self):
        layer = [{"order": "LAYERS", "stage": "STRUCTURE", "index": 29, "total": 76, "y": -35,
                  "chunk_index": 49, "chunk_total": 49}]
        state = ["BUILDING"]
        with RecordingEndpoint(lambda _: (200, observation(state=state[0], current_layer=layer[0]))) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            self.assertIsNone(service.snapshot()["state_since"])
            layer[0] = dict(layer[0], index=30, y=-34, chunk_index=1)
            state[0] = "PAUSED"
            service.refresh()
            wait_for(lambda: any("Stage 30 of 76 · Structure · Y -34" in event["message"]
                                 for event in service.snapshot()["events"]))
            self.assertIsNotNone(service.snapshot()["state_since"])
            service.stop()

    def test_a_finished_build_check_is_recorded_once(self):
        current = [observation(state="STOPPED", build_check=None)]
        with RecordingEndpoint(lambda _: (200, current[0])) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            current[0] = observation(state="CHECKING", build_check={
                "status": "RUNNING", "progress": 0.4, "started_at": "2026-09-28T16:17:36Z",
                "summary": "Checking the build before starting."})
            self.poll_twice(service, endpoint)
            summary = "Build check (2.3 s): 49 of 49 chunks checked; 57% built."
            current[0] = observation(state="BUILDING", build_check={
                "status": "COMPLETE", "progress": 1.0, "started_at": "2026-09-28T16:17:36Z", "summary": summary})
            self.poll_twice(service, endpoint)
            self.poll_twice(service, endpoint)
            messages = [event["message"] for event in service.snapshot()["events"]]
            self.assertEqual(messages.count(summary), 1)
            self.assertFalse(any("Checking the build" in message for message in messages))
            self.assertTrue(any(record.getMessage() == "Build check complete: " + summary
                                for record in self.log_records))
            service.stop()

    def test_a_check_seen_on_first_contact_is_only_the_baseline(self):
        check = {"status": "COMPLETE", "started_at": "2026-09-28T16:17:36Z", "summary": "Build check (1.0 s): old."}
        with RecordingEndpoint(lambda _: (200, observation(state="BUILDING", build_check=check))) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            self.poll_twice(service, endpoint)
            self.assertFalse(any(event["message"] == check["summary"] for event in service.snapshot()["events"]))
            service.stop()

    def test_note_event_appears_in_activity(self):
        with RecordingEndpoint(lambda _: (200, observation())) as endpoint:
            service = self.service(endpoint)
            service.note_event("Alert · The build paused.")
            self.assertTrue(any(event["message"] == "Alert · The build paused."
                                for event in service.snapshot()["events"]))
            service.stop()

    def progress_requests(self, endpoint):
        return sum(1 for request in endpoint.requests if request["path"] == "/v1/progress")

    def test_a_stage_that_is_not_text_does_not_stop_polling(self):
        layer = [{"stage": "STRUCTURE", "index": 1, "total": 4, "y": -63}]
        with RecordingEndpoint(lambda _: (200, observation(current_layer=layer[0]))) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            for index, stage in ((2, ["STRUCTURE"]), (3, {"kind": "LIGHTING"})):
                layer[0] = {"stage": stage, "index": index, "total": 4, "y": -62}
                self.poll_twice(service, endpoint)
            layer[0] = {"stage": "TILL", "index": 4, "total": 4, "y": -63}
            service.refresh()
            wait_for(lambda: any(event["message"] == "Stage 4 of 4 · Till · Y -63"
                                 for event in service.snapshot()["events"]))
            messages = [event["message"] for event in service.snapshot()["events"]]
            self.assertIn("Stage 2 of 4 · Y -62", messages)
            self.assertIn("Stage 3 of 4 · Y -62", messages)
            self.assertFalse(any(record.exc_info for record in self.log_records))
            service.stop()

    def test_stage_event_text_is_capped(self):
        layer = [{"stage": "STRUCTURE", "index": 1, "total": 4, "y": -63}]
        with RecordingEndpoint(lambda _: (200, observation(current_layer=layer[0]))) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["fresh"])
            layer[0] = {"stage": "X" * 5000, "index": 2, "total": 4, "y": -62}
            service.refresh()
            message = wait_for(lambda: next((event["message"] for event in service.snapshot()["events"]
                                             if event["message"].startswith("Stage 2 of 4")), None))
            self.assertEqual(len(message), 1000)
            service.stop()

    def test_an_unexpected_update_error_is_logged_and_polling_continues(self):
        placements = [100]
        with RecordingEndpoint(lambda _: (200, observation(confirmed_placements=placements[0]))) as endpoint, \
                patch("supervisor_companion.monitor.parse_progress", side_effect=RuntimeError("unexpected")):
            service = self.service(endpoint)
            failure = wait_for(lambda: next((record for record in self.log_records if record.exc_info), None))
            self.assertEqual(failure.getMessage(), "Updating the monitor failed")
            self.assertIs(failure.exc_info[0], RuntimeError)
            placements[0] = 125
            service.refresh()
            wait_for(lambda: service.snapshot()["observation"]["confirmed_placements"] == 125)
            service.stop()

    def test_an_invalid_pairing_file_is_reported_as_a_configuration_error(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"MONITOR_TEST_TOKEN": ""}), \
                RecordingEndpoint(lambda _: (200, observation())) as endpoint:
            token_file = Path(temporary) / "protocol-token.txt"
            token_file.write_bytes(b"not valid\n")
            service = self.service(endpoint, token_file=token_file)
            wait_for(lambda: service.snapshot()["connection"] == "configuration_error")
            snapshot = service.snapshot()
            self.assertFalse(snapshot["paired"])
            self.assertEqual(snapshot["message"], f"The pairing file at {token_file.resolve()} is invalid.")
            service.stop()

    def test_progress_is_refetched_after_reconnecting(self):
        online = [True]

        def reply(request):
            if not online[0]:
                return 503, {}
            if request["path"] == "/v1/progress":
                return 200, progress_payload(revision=5)
            return 200, observation(progress_revision=5)

        with RecordingEndpoint(reply) as endpoint:
            # A stopped clock rules out the 30 s refresh, and the revision never changes.
            service = self.service(endpoint, clock=lambda: 1000.0)
            wait_for(lambda: service.snapshot()["progress_status"] == "ok")
            online[0] = False
            service.refresh()
            wait_for(lambda: service.snapshot()["connection"] == "http_error")
            self.assertEqual(self.progress_requests(endpoint), 1)
            online[0] = True
            self.poll_twice(service, endpoint)
            self.assertEqual(self.progress_requests(endpoint), 2)
            service.stop()

    def test_progress_is_refreshed_every_thirty_seconds(self):
        now = [1000.0]

        def reply(request):
            if request["path"] == "/v1/progress":
                return 200, progress_payload(revision=5)
            return 200, observation(progress_revision=5)

        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint, clock=lambda: now[0])
            wait_for(lambda: service.snapshot()["progress_status"] == "ok")
            now[0] += 29
            self.poll_twice(service, endpoint)
            self.assertEqual(self.progress_requests(endpoint), 1)
            now[0] += 1
            self.poll_twice(service, endpoint)
            self.assertEqual(self.progress_requests(endpoint), 2)
            service.stop()

    def test_unsupported_progress_is_rechecked_after_five_minutes_or_on_reconnect(self):
        now = [1000.0]
        online = [True]

        def reply(request):
            if not online[0]:
                return 503, {}
            if request["path"] == "/v1/progress":
                return 404, {"accepted": False, "message": "Not found."}
            return 200, observation()

        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint, clock=lambda: now[0])
            wait_for(lambda: service.snapshot()["progress_status"] == "unsupported")
            now[0] += 299
            self.poll_twice(service, endpoint)
            self.assertEqual(self.progress_requests(endpoint), 1)
            now[0] += 1
            self.poll_twice(service, endpoint)
            self.assertEqual(self.progress_requests(endpoint), 2)
            online[0] = False
            service.refresh()
            wait_for(lambda: service.snapshot()["connection"] == "http_error")
            online[0] = True
            self.poll_twice(service, endpoint)
            self.assertEqual(self.progress_requests(endpoint), 3)
            service.stop()

    def test_own_request_ids_cover_only_the_last_sixty_seconds(self):
        now = [1000.0]

        def reply(request):
            if request["method"] == "GET":
                return 200, observation()
            return 200, {"accepted": True, "message": "Paused.", "state": "PAUSED"}

        with RecordingEndpoint(reply) as endpoint:
            service = self.service(endpoint, clock=lambda: now[0])
            wait_for(lambda: service.snapshot()["fresh"])
            self.assertTrue(service.send_control("PAUSE"))
            wait_for(lambda: not service.snapshot()["pending_actions"])
            post = next(request for request in endpoint.requests if request["method"] == "POST")
            now[0] += 59
            self.assertEqual(service.snapshot()["own_request_ids"], [post["body"]["request_id"]])
            now[0] += 1
            self.assertEqual(service.snapshot()["own_request_ids"], [])
            service.stop()

    def test_connection_changes_that_differ_only_in_detail_are_recorded(self):
        status = [503]
        with RecordingEndpoint(lambda _: (status[0], {"message": "unavailable"})) as endpoint:
            service = self.service(endpoint)
            wait_for(lambda: service.snapshot()["detail"] == "not_ready")
            status[0] = 500
            service.refresh()
            wait_for(lambda: service.snapshot()["detail"] == "http_error")
            self.assertEqual([event["message"] for event in service.snapshot()["events"]],
                             ["http_error (not_ready): The mod is starting and waiting for its first game tick.",
                              "http_error: The mod returned HTTP 500."])
            self.assertEqual([record.getMessage() for record in self.log_records
                              if record.getMessage().startswith("Connection ")],
                             ["Connection http_error (not_ready, HTTP 503); state None; "
                              "message: The mod is starting and waiting for its first game tick.",
                              "Connection http_error (http_error, HTTP 500); state None; "
                              "message: The mod returned HTTP 500."])
            self.assertFalse(any("test-pairing-value" in record.getMessage() for record in self.log_records))
            service.stop()

    def test_connection_log_caps_the_message_and_omits_a_missing_http_status(self):
        long_message = "Nothing is listening.\n" + "x" * 5000
        result = {"ok": False, "connection": "offline", "detail": "refused", "fresh": False,
                  "age_seconds": None, "observation": None, "message": long_message}
        with RecordingEndpoint(lambda _: (200, observation())) as endpoint, \
                patch.object(AgentBridge, "observe", return_value=result):
            service = self.service(endpoint)
            line = wait_for(lambda: next((record.getMessage() for record in self.log_records
                                          if record.getMessage().startswith("Connection ")), None))
            event = wait_for(lambda: next((event["message"] for event in service.snapshot()["events"]
                                           if event["message"].startswith("offline (refused): ")), None))
            service.stop()
        prefix = "Connection offline (refused); state None; message: "
        self.assertTrue(line.startswith(prefix + "Nothing is listening. xxx"), line[:120])
        # The message is capped like the Activity entry for the same change.
        self.assertEqual(line[len(prefix):], event[len("offline (refused): "):])
        self.assertEqual(len(line) - len(prefix), 1000)
        self.assertFalse(any("test-pairing-value" in record.getMessage() for record in self.log_records))


if __name__ == "__main__":
    unittest.main()
