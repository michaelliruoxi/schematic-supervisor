from __future__ import annotations

import copy
from dataclasses import replace
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from supervisor_companion.agent_bridge import AgentBridge
from supervisor_companion.agent_runner import (
    AgentCancelled, DECISION_SCHEMA, REPAIR_SCHEMA, LocalAgentBackend, RunnerError, RunnerOptions,
    SingleRunner, SupervisorRunner, live_observation, resolve_agent_executable, semantic_key, validate_decision,
    validate_repair,
)
from supervisor_companion.config import AppConfig

from http_fixture import RecordingEndpoint


def observation(state="BUILDING", *, sequence=0, action=None, request_id=None, **updates):
    result = {
        "protocol_version": 1, "run_id": "test-run", "state": state,
        "updated_at": datetime.now(timezone.utc).isoformat(), "ready": True,
        "allowed_actions": ["PAUSE", "STOP"] if state == "BUILDING" else ["START", "RESUME", "PAUSE", "STOP"],
        "last_control": {"sequence": sequence, "action": action, "request_id": request_id},
        "stable_verification_passes": 0, "last_error": None,
        "current_chunk": {"index": 1, "total": 49}, "phase": "BUILDING",
    }
    result.update(updates)
    return result


def waiting_observation(state="IDLE", **updates):
    defaults = {
        "ready": False, "world_connected": True, "context_matches": True,
        "control_token_configured": True, "allowed_actions": ["SCAN_DEPOTS", "STOP"],
        "blockers": ["This floating schematic requires flight to already be active before starting."],
    }
    return observation(state, **(defaults | updates))


class MemoryBridge:
    def __init__(self, state=None):
        self.state = state or observation()
        self.controls = []
        self.fresh = True
        self.accept_controls = True

    def observe(self):
        return {"ok": self.fresh, "fresh": self.fresh, "observation": copy.deepcopy(self.state)}

    def control(self, action, **guards):
        self.controls.append((action, guards))
        if not self.accept_controls:
            return {"ok": False, "accepted": False, "outcome": "unknown"}
        self.state["state"] = {"START": "BUILDING", "RESUME": "BUILDING", "PAUSE": "PAUSED", "STOP": "STOPPED", "SCAN_DEPOTS": self.state["state"]}[action]
        self.state["last_control"] = {
            "sequence": self.state["last_control"]["sequence"] + 1,
            "action": action, "request_id": guards.get("request_id"),
        }
        return {"ok": True, "accepted": True, "state": self.state["state"]}


class ScriptedBackend:
    def __init__(self, action="WAIT", on_decide=None):
        self.action = action
        self.on_decide = on_decide
        self.decisions = 0
        self.repairs = []

    def decide(self, state, objective, cancel_check):
        self.decisions += 1
        if self.on_decide:
            self.on_decide(state, cancel_check)
        return {"action": self.action, "reason": "Evidence from current observation."}

    def repair(self, state, reason, cancel_check):
        self.repairs.append(copy.deepcopy(state))
        return {"status": "repaired", "summary": "A source fix was staged.",
                "tests": ["Targeted checks passed."], "restart_required": True}


class SupervisorRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.options = RunnerOptions(workspace=Path(self.temp.name), max_cycles=2, model_policy="continuous")
        self.events = []
        self.now = 0.0

    def sleep(self, duration):
        self.now += duration

    def runner(self, bridge, backend, **options):
        return SupervisorRunner(bridge, backend, replace(self.options, **options),
                                emit=self.events.append, sleeper=self.sleep, clock=lambda: self.now)

    def test_cp1252_console_json_and_utf8_audit_round_trip_unicode(self):
        decision = {"action": "PAUSE", "reason": "Inventory 94\u219293; \u4e0a\u5c42 \U0001f33e; cafe\u0301; quoted \"path\"\nnext line"}
        runner = SupervisorRunner(MemoryBridge(), ScriptedBackend(), self.options)
        output = io.BytesIO()
        with io.TextIOWrapper(output, encoding="cp1252", errors="strict") as console:
            with patch.object(sys, "stdout", console):
                runner.emit("decision", decision=decision, state="PAUSED")
            console.flush()
            encoded = output.getvalue()
        self.assertTrue(encoded.isascii())
        self.assertEqual(len(encoded.splitlines()), 1)
        emitted = json.loads(encoded)
        self.assertEqual(emitted["decision"], decision)
        audit = (self.options.run_directory / "supervision.jsonl").read_text(encoding="utf-8")
        self.assertIn("94\u219293", audit)
        self.assertIn("\u4e0a\u5c42 \U0001f33e", audit)
        self.assertEqual(json.loads(audit), emitted)

    def test_cp1252_console_logging_does_not_prevent_unicode_decision_dispatch(self):
        decision = {"action": "PAUSE", "reason": "Prediction still pending: 94\u219293; \u9700\u6838\u5bf9 \U0001f33e"}
        bridge = MemoryBridge(observation("PAUSED", last_error="Uncertain placement receipt"))
        backend = ScriptedBackend("PAUSE")
        runner = SupervisorRunner(bridge, backend, replace(self.options, model_policy="on-error"),
                                  sleeper=self.sleep, clock=lambda: self.now)
        output = io.BytesIO()
        with io.TextIOWrapper(output, encoding="cp1252", errors="strict") as console:
            with patch.object(sys, "stdout", console), patch.object(backend, "decide", return_value=decision) as decide:
                result = runner.run()
            console.flush()
            emitted = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(result, "agent_paused")
        decide.assert_called_once()
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])
        self.assertEqual([record["event"] for record in emitted], ["decision", "control", "agent_paused"])
        self.assertEqual(emitted[0]["decision"], decision)
        self.assertEqual(emitted[-1]["message"], decision["reason"])
        audit = [json.loads(line) for line in
                 (self.options.run_directory / "supervision.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(audit, emitted)

    def test_unarmed_stopped_session_does_not_launch_agent(self):
        bridge, backend = MemoryBridge(observation("STOPPED")), ScriptedBackend("START")
        self.assertEqual(self.runner(bridge, backend).run(), "awaiting_start")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_unarmed_paused_session_does_not_launch_agent(self):
        bridge, backend = MemoryBridge(observation("PAUSED")), ScriptedBackend("RESUME")
        self.assertEqual(self.runner(bridge, backend).run(), "awaiting_resume")
        self.assertEqual(backend.decisions, 0)

    def test_initial_flight_wait_uses_no_model_calls_and_logs_once(self):
        bridge, backend = MemoryBridge(waiting_observation()), ScriptedBackend("PAUSE")
        result = self.runner(bridge, backend, allow_start=True, max_cycles=5).run()
        self.assertEqual(result, "cycle_limit")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])
        waits = [event for event in self.events if event["event"] == "waiting_for_readiness"]
        self.assertEqual(len(waits), 1)
        self.assertEqual(waits[0]["blockers"], bridge.state["blockers"])
        self.assertEqual(self.now, 5 * self.options.poll_seconds)

    def test_initial_wait_starts_or_resumes_when_ready_with_same_permission(self):
        for state, action, arm in (("IDLE", "START", "allow_start"),
                                   ("STOPPED", "START", "allow_start"),
                                   ("PAUSED", "RESUME", "allow_resume")):
            with self.subTest(state=state):
                bridge = MemoryBridge(waiting_observation(state, sequence=7))
                backend = ScriptedBackend(action)
                runner = self.runner(bridge, backend, **{arm: True}, max_cycles=2)

                def become_ready(duration):
                    self.sleep(duration)
                    bridge.state.update(ready=True, blockers=[], allowed_actions=[action, "PAUSE", "STOP"])

                runner.sleep = become_ready
                self.assertEqual(runner.run(), "cycle_limit")
                self.assertEqual(backend.decisions, 1)
                sent, guards = bridge.controls[0]
                self.assertEqual(sent, action)
                self.assertEqual(guards["expected_state"], state)
                self.assertEqual(guards["expected_run_id"], "test-run")
                self.assertEqual(guards["expected_control_sequence"], 7)
                self.assertFalse(runner.initial_activation)

    def test_readiness_wait_obeys_external_pause_stop_and_run_identity(self):
        for action, expected in (("STOP", "operator_stop"), ("PAUSE", "operator_pause"),
                                 ("RESET", "operator_reset"), ("UNLOAD", "operator_unload"),
                                 (None, "mod_restarted")):
            with self.subTest(action=action):
                bridge, backend = MemoryBridge(waiting_observation()), ScriptedBackend("START")
                runner = self.runner(bridge, backend, allow_start=True, max_cycles=3)

                def intervene(duration):
                    self.sleep(duration)
                    if action is None:
                        bridge.state["run_id"] = "different-run"
                    else:
                        bridge.state["last_control"] = {"sequence": 1, "action": action,
                                                        "request_id": "operator"}

                runner.sleep = intervene
                self.assertEqual(runner.run(), expected)
                self.assertEqual(backend.decisions, 0)
                self.assertEqual(bridge.controls, [])

    def test_readiness_wait_still_rejects_stale_observations(self):
        bridge, backend = MemoryBridge(waiting_observation()), ScriptedBackend("START")
        runner = self.runner(bridge, backend, allow_start=True, max_cycles=5)

        def stale(duration):
            self.sleep(duration)
            bridge.fresh = False

        runner.sleep = stale
        self.assertEqual(runner.run(), "failed")
        self.assertEqual(backend.decisions, 0)
        self.assertFalse(any(action == "START" for action, _ in bridge.controls))

    def test_depot_scan_wait_transitions_to_flight_wait_without_model(self):
        flight = waiting_observation()["blockers"]
        scan = ["Wait for registered-depot scans to finish."]
        bridge = MemoryBridge(waiting_observation(blockers=scan))
        backend = ScriptedBackend("PAUSE")
        runner = self.runner(bridge, backend, allow_start=True, max_cycles=4)
        waits = 0

        def advance(duration):
            nonlocal waits
            self.sleep(duration)
            waits += 1
            if waits == 2:
                bridge.state["blockers"] = flight
            elif waits == 3:
                bridge.state["state"] = "STOPPED"

        runner.sleep = advance
        self.assertEqual(runner.run(), "cycle_limit")
        events = [event for event in self.events if event["event"] == "waiting_for_readiness"]
        self.assertEqual([(e["state"], e["blockers"]) for e in events],
                         [("IDLE", scan), ("IDLE", flight), ("STOPPED", flight)])
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_initial_wait_does_not_mask_errors_or_unknown_blockers(self):
        examples = [
            {"state": "ERROR"},
            {"last_error": "Observed code defect"},
            {"blockers": ["The checkpoint requires reconciliation; use an explicit reset before restarting."]},
            {"blockers": waiting_observation()["blockers"] + ["Unknown readiness defect"]},
        ]
        for update in examples:
            with self.subTest(update=update):
                bridge, backend = MemoryBridge(waiting_observation(**update)), ScriptedBackend("PAUSE")
                self.runner(bridge, backend, allow_start=True, max_cycles=1).run()
                self.assertEqual(backend.decisions, 1)

    def test_initial_paused_food_shortage_still_reaches_agent(self):
        bridge = MemoryBridge(waiting_observation(
            "PAUSED", last_error="Missing materials: food=1",
            materials={"food": {"available": 0, "required": 1, "missing": 1}},
        ))
        backend = ScriptedBackend("PAUSE")
        self.assertEqual(self.runner(bridge, backend, allow_resume=True).run(), "agent_paused")
        self.assertEqual(backend.decisions, 1)
        self.assertFalse(any(event["event"] == "waiting_for_readiness" for event in self.events))

    def test_readiness_wait_requires_matching_initial_arm_and_no_active_run(self):
        for state, arm, expected in (("IDLE", "allow_resume", "awaiting_start"),
                                     ("PAUSED", "allow_start", "awaiting_resume")):
            with self.subTest(state=state):
                bridge, backend = MemoryBridge(waiting_observation(state)), ScriptedBackend("START")
                runner = self.runner(bridge, backend, **{arm: True})
                self.assertEqual(runner.run(), expected)
                self.assertEqual(backend.decisions, 0)
                self.assertFalse(any(event["event"] == "waiting_for_readiness" for event in self.events))
        bridge, backend = MemoryBridge(waiting_observation("PAUSED")), ScriptedBackend("PAUSE")
        runner = self.runner(bridge, backend, allow_resume=True, max_cycles=1)
        runner.active_run_observed = True
        self.assertEqual(runner.run(), "agent_paused")
        self.assertEqual(backend.decisions, 1)

    def test_once_cycle_bounds_initial_readiness_wait(self):
        bridge, backend = MemoryBridge(waiting_observation()), ScriptedBackend("START")
        self.assertEqual(self.runner(bridge, backend, allow_start=True, max_cycles=1).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])
        self.assertEqual([event["event"] for event in self.events], ["waiting_for_readiness", "cycle_limit"])

    def test_initial_start_has_run_state_and_operator_sequence_guards(self):
        bridge, backend = MemoryBridge(observation("STOPPED", sequence=7)), ScriptedBackend("START")
        self.runner(bridge, backend, allow_start=True, max_cycles=1).run()
        action, guards = bridge.controls[0]
        self.assertEqual(action, "START")
        self.assertEqual(guards["expected_run_id"], "test-run")
        self.assertEqual(guards["expected_state"], "STOPPED")
        self.assertEqual(guards["expected_control_sequence"], 7)
        self.assertTrue(guards["request_id"].startswith("agent-"))
        self.assertEqual(bridge.controls[-1][0], "PAUSE")

    def test_wait_does_not_spawn_model_each_poll(self):
        bridge, backend = MemoryBridge(), ScriptedBackend()
        self.runner(bridge, backend, max_cycles=10).run()
        self.assertEqual(backend.decisions, 1)
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])

    def test_new_run_created_by_own_start_remains_supervised(self):
        class NewRunBridge(MemoryBridge):
            def control(self, action, **guards):
                result = super().control(action, **guards)
                if action == "START":
                    self.state["run_id"] = "started-run"
                return result

        bridge = NewRunBridge(observation("IDLE"))
        backend = ScriptedBackend("START")

        def next_action(state, _cancel):
            backend.action = "START" if state["state"] == "IDLE" else "WAIT"

        backend.on_decide = next_action
        runner = self.runner(bridge, backend, allow_start=True)
        self.assertEqual(runner.run(), "cycle_limit")
        self.assertEqual(runner.run_id, "started-run")
        self.assertEqual(backend.decisions, 2)

    def test_depot_scan_does_not_consume_initial_start_permission(self):
        bridge = MemoryBridge(observation("IDLE", allowed_actions=["START", "SCAN_DEPOTS", "STOP"]))
        backend = ScriptedBackend("SCAN_DEPOTS")

        def next_action(state, _cancel):
            backend.action = "SCAN_DEPOTS" if state["last_control"]["sequence"] == 0 else "START"

        backend.on_decide = next_action
        self.runner(bridge, backend, allow_start=True).run()
        self.assertEqual([action for action, _ in bridge.controls], ["SCAN_DEPOTS", "START", "PAUSE"])

    def test_old_start_request_cannot_disguise_later_process_restart(self):
        bridge, backend = MemoryBridge(), ScriptedBackend()
        runner = self.runner(bridge, backend)
        runner.run_id = "old-run"
        runner._pending_start = ("own-start", 1)
        fresh = observation("BUILDING", sequence=2, action="START", request_id="own-start")
        fresh["run_id"] = "different-run"
        self.assertEqual(runner._operator_stop(fresh), "mod_restarted")

    def test_stopped_by_operator_during_decision_does_not_restart(self):
        bridge = MemoryBridge(observation("STOPPED"))

        def stop(_state, _cancel):
            bridge.state = observation("STOPPED", sequence=1, action="STOP", request_id="operator")

        backend = ScriptedBackend("START", stop)
        self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "operator_stop")
        self.assertEqual(bridge.controls, [])

    def test_engine_error_pause_can_resume_under_bounded_recovery_budget(self):
        bridge = MemoryBridge()
        backend = ScriptedBackend()

        def next_action(state, _cancel):
            if state["state"] == "BUILDING":
                backend.action = "WAIT"
                bridge.state.update(state="PAUSED", last_error="Deterministic recovery exhausted",
                                    allowed_actions=["RESUME", "PAUSE", "STOP"])
            else:
                backend.action = "RESUME"
                self.assertTrue(state["runner_policy"]["resume_armed"])

        backend.on_decide = next_action
        runner = self.runner(bridge, backend)
        self.assertEqual(runner.run(), "cycle_limit")
        self.assertEqual(runner.recovery_resumes, 1)
        self.assertEqual([action for action, _ in bridge.controls], ["RESUME", "PAUSE"])

    def test_engine_recovery_cannot_override_manual_error_pause(self):
        bridge = MemoryBridge()
        backend = ScriptedBackend()

        def operator_pause(_state, _cancel):
            bridge.state = observation("PAUSED", sequence=1, action="PAUSE", request_id="operator",
                                       last_error="Existing error")

        backend.on_decide = operator_pause
        self.assertEqual(self.runner(bridge, backend).run(), "operator_pause")
        self.assertEqual(bridge.controls, [])

    def test_engine_recovery_budget_exhaustion_does_not_blindly_resume(self):
        bridge = MemoryBridge(observation("PAUSED", last_error="Persistent fault"))
        runner = self.runner(bridge, ScriptedBackend("RESUME"), max_recovery_resumes=1)
        runner.active_run_observed = True
        runner.initial_activation = False
        runner.recovery_resumes = 1
        self.assertEqual(runner.run(), "failed")
        self.assertEqual(bridge.controls, [])

    def test_pause_then_resume_by_operator_invalidates_old_decision(self):
        bridge = MemoryBridge()

        def controls(_state, _cancel):
            bridge.state = observation("BUILDING", sequence=2, action="RESUME", request_id="operator-resume")

        backend = ScriptedBackend("PAUSE", controls)
        self.runner(bridge, backend, max_cycles=1).run()
        self.assertIn("decision_discarded", [event["event"] for event in self.events])
        self.assertFalse(any("expected_state" in guards for _action, guards in bridge.controls))

    def test_fresh_state_change_discards_action(self):
        bridge = MemoryBridge()

        def progress(_state, _cancel):
            bridge.state["current_chunk"] = {"index": 2, "total": 49}

        backend = ScriptedBackend("STOP", progress)
        self.runner(bridge, backend, max_cycles=1).run()
        self.assertFalse(any(action == "STOP" for action, _ in bridge.controls))

    def test_layer_advance_discards_action_even_with_same_chunk_and_phase(self):
        layer = {
            "order": "LAYERS", "stage": "STRUCTURE", "index": 1, "total": 20,
            "y": 64, "chunk_index": 1, "chunk_total": 1,
        }
        bridge = MemoryBridge(observation(current_layer=layer))

        def progress(_state, _cancel):
            bridge.state["current_layer"] = layer | {"index": 2, "y": 65}

        self.runner(bridge, ScriptedBackend("STOP", progress), max_cycles=1).run()
        self.assertFalse(any(action == "STOP" for action, _ in bridge.controls))
        self.assertIn("decision_discarded", [event["event"] for event in self.events])

    def test_pause_cancels_inflight_agent(self):
        bridge = MemoryBridge()

        def pause(_state, cancel):
            bridge.state = observation("PAUSED", sequence=1, action="PAUSE", request_id="operator")
            reason = cancel()
            if reason:
                raise AgentCancelled(reason)

        backend = ScriptedBackend("WAIT", pause)
        self.assertEqual(self.runner(bridge, backend).run(), "agent_cancelled")
        self.assertEqual(bridge.controls, [])

    def test_stale_telemetry_prevents_agent_and_activation(self):
        bridge, backend = MemoryBridge(), ScriptedBackend("START")
        bridge.fresh = False
        self.assertEqual(self.runner(bridge, backend, max_cycles=10).run(), "failed")
        self.assertEqual(backend.decisions, 0)
        self.assertTrue(all(action == "PAUSE" for action, _ in bridge.controls))
        self.assertEqual(len([event for event in self.events if event["event"] == "error"]), 3)

    def test_model_failure_pauses_and_does_not_retry_activation(self):
        bridge = MemoryBridge()

        def fail(_state, _cancel):
            raise RunnerError("Authentication or quota unavailable.")

        backend = ScriptedBackend(on_decide=fail)
        self.assertEqual(self.runner(bridge, backend).run(), "failed")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])

    def test_a_full_disk_does_not_stop_the_failure_pause(self):
        def fail(_state, _cancel):
            raise RunnerError("Agent decision failed with exit code 1.")

        real_open = Path.open

        def disk_full(path, *args, **kwargs):
            if path.name == "supervision.jsonl":
                raise OSError(28, "No space left on device")
            return real_open(path, *args, **kwargs)

        bridge = MemoryBridge()
        runner = SupervisorRunner(bridge, ScriptedBackend(on_decide=fail), self.options,
                                  sleeper=self.sleep, clock=lambda: self.now)
        console = io.StringIO()
        with patch.object(Path, "open", disk_full), patch.object(sys, "stdout", console):
            self.assertEqual(runner.run(), "failed")
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])
        events = [json.loads(line)["event"] for line in console.getvalue().splitlines()]
        self.assertEqual(events.count("audit_log_unavailable"), 1)
        self.assertLess(events.index("error"), events.index("failure_pause"))

    def test_a_closed_console_does_not_stop_supervision_or_its_audit_log(self):
        def fail(_state, _cancel):
            raise RunnerError("Agent decision failed with exit code 1.")

        bridge = MemoryBridge()
        runner = SupervisorRunner(bridge, ScriptedBackend(on_decide=fail), self.options,
                                  sleeper=self.sleep, clock=lambda: self.now)
        console = io.StringIO()
        console.close()
        with patch.object(sys, "stdout", console):
            self.assertEqual(runner.run(), "failed")
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])
        audit = (self.options.run_directory / "supervision.jsonl").read_text(encoding="utf-8")
        self.assertIn("failure_pause", [json.loads(line)["event"] for line in audit.splitlines()])

    def test_a_failing_event_sink_still_pauses(self):
        def fail(_state, _cancel):
            raise RunnerError("Agent decision failed with exit code 1.")

        def sink(record):
            if record["event"] == "error":
                raise RuntimeError("event sink unavailable")
            self.events.append(record)

        bridge = MemoryBridge()
        runner = SupervisorRunner(bridge, ScriptedBackend(on_decide=fail), self.options, emit=sink,
                                  sleeper=self.sleep, clock=lambda: self.now)
        with self.assertRaisesRegex(RuntimeError, "event sink unavailable"):
            runner.run()
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])
        self.assertIn("failure_pause", [event["event"] for event in self.events])

    def test_an_unexpected_fault_pauses_before_the_runner_exits(self):
        def fail(_state, _cancel):
            raise TypeError("unexpected observation shape")

        bridge = MemoryBridge()
        with self.assertRaisesRegex(TypeError, "unexpected observation shape"):
            self.runner(bridge, ScriptedBackend(on_decide=fail)).run()
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])

    def test_a_pause_whose_log_fails_is_not_reported_as_unconfirmed(self):
        def sink(record):
            if record["event"] == "failure_pause":
                raise OSError("log unavailable")
            self.events.append(record)

        bridge = MemoryBridge()
        runner = SupervisorRunner(bridge, ScriptedBackend(), self.options, emit=sink,
                                  sleeper=self.sleep, clock=lambda: self.now)
        with self.assertRaisesRegex(OSError, "log unavailable"):
            runner._pause_on_failure()
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])
        self.assertNotIn("failure_pause_unconfirmed", [event["event"] for event in self.events])

    def test_unknown_start_ack_is_not_retried(self):
        bridge = MemoryBridge(observation("STOPPED"))
        bridge.accept_controls = False
        backend = ScriptedBackend("START")
        self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "failed")
        self.assertEqual([action for action, _ in bridge.controls], ["START"])

    def test_repair_requires_confirmed_pause_and_leaves_paused(self):
        bridge, backend = MemoryBridge(observation("STUCK", last_error="Observed bug")), ScriptedBackend("REPAIR")
        self.assertEqual(self.runner(bridge, backend, allow_repair=True).run(), "repair_finished")
        self.assertEqual([action for action, _ in bridge.controls], ["PAUSE"])
        self.assertEqual(backend.repairs[0]["state"], "PAUSED")
        self.assertEqual(bridge.state["state"], "PAUSED")

    def test_failed_pause_prevents_repair(self):
        bridge, backend = MemoryBridge(observation("STUCK")), ScriptedBackend("REPAIR")
        bridge.accept_controls = False
        self.assertEqual(self.runner(bridge, backend, allow_repair=True).run(), "failed")
        self.assertEqual(backend.repairs, [])

    def test_disabled_repair_pauses_without_edits(self):
        bridge, backend = MemoryBridge(observation("STUCK")), ScriptedBackend("REPAIR")
        self.assertEqual(self.runner(bridge, backend).run(), "repair_disabled")
        self.assertEqual(backend.repairs, [])
        self.assertEqual(bridge.state["state"], "PAUSED")

    def test_new_operator_pause_at_pre_repair_read_prevents_edits(self):
        bridge = MemoryBridge(observation("PAUSED", last_error="Observed code defect"))
        backend = ScriptedBackend("REPAIR")
        runner = self.runner(bridge, backend, allow_repair=True, allow_resume=True)
        original_read = runner._read
        reads = 0

        def read():
            nonlocal reads
            reads += 1
            if reads == 3:
                bridge.state["last_control"] = {"sequence": 1, "action": "PAUSE", "request_id": "operator"}
            return original_read()

        runner._read = read
        self.assertEqual(runner.run(), "failed")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(backend.repairs, [])
        self.assertEqual(runner.repairs, 0)
        self.assertEqual(bridge.controls, [])
        self.assertIn("fresh, confirmed PAUSED", self.events[-1]["message"])

    def test_done_is_verified_without_a_model_call(self):
        bridge = MemoryBridge(observation("DONE", stable_verification_passes=2))
        backend = ScriptedBackend()
        self.assertEqual(self.runner(bridge, backend).run(), "complete")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_model_cannot_claim_premature_completion(self):
        bridge, backend = MemoryBridge(), ScriptedBackend("COMPLETE")
        self.assertEqual(self.runner(bridge, backend).run(), "failed")
        self.assertFalse(any(event["event"] == "complete" for event in self.events))

    def test_mod_restart_invalidates_decision(self):
        bridge = MemoryBridge()

        def restart(_state, _cancel):
            bridge.state["run_id"] = "new-run"

        backend = ScriptedBackend("STOP", restart)
        self.assertEqual(self.runner(bridge, backend).run(), "mod_restarted")
        self.assertEqual(bridge.controls, [])

    def test_real_http_control_receives_matching_sequence_guard(self):
        state = observation("STOPPED", sequence=4)

        def respond(request):
            if request["method"] == "GET":
                return 200, state
            body = request["body"]
            state["state"] = "BUILDING" if body["action"] == "START" else "PAUSED"
            state["last_control"] = {"sequence": state["last_control"]["sequence"] + 1,
                                     "action": body["action"], "request_id": body["request_id"]}
            return 200, {"accepted": True, "message": "Accepted", "state": state["state"]}

        with RecordingEndpoint(respond) as endpoint, patch.dict(os.environ, {"SUPERVISOR_TEST_TOKEN": "test-token"}):
            config = AppConfig.from_mapping({"mod": {"base_url": endpoint.base_url, "token_env": "SUPERVISOR_TEST_TOKEN"}})
            self.runner(AgentBridge(config), ScriptedBackend("START"), allow_start=True, max_cycles=1).run()
        start = next(request for request in endpoint.requests if request["method"] == "POST")
        self.assertEqual(start["body"]["action"], "START")
        self.assertEqual(start["body"]["expected_control_sequence"], 4)
        self.assertEqual(start["headers"]["X-Supervisor-Token"], "test-token")


class BundledExecutableResolutionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.bundle = self.root / "bin"
        self.bundle.mkdir()
        self.stale = self.bundle / "0000000000000000" / "fakeagent.exe"

    def executable(self, version, *, modified=100, name="fakeagent.exe"):
        target = self.bundle / version / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"test executable placeholder")
        target.chmod(0o700)
        os.utime(target, (modified, modified))
        return target

    def test_existing_explicit_executable_wins_over_newer_sibling(self):
        existing = self.executable("0000000000000000")
        self.executable("2222222222222222", modified=200)
        self.assertEqual(Path(resolve_agent_executable(str(existing))), existing)

    def test_expired_bundle_selects_newest_same_name_sibling(self):
        self.executable("1111111111111111", modified=100)
        expected = self.executable("2222222222222222", modified=200)
        self.executable("3333333333333333", modified=300, name="different.exe")
        self.executable("not-a-version", modified=400)
        self.executable("4444444444444444/nested", modified=500)
        self.executable("too-long-5555555555555555", modified=600)
        backend = LocalAgentBackend(RunnerOptions(workspace=self.root, executable=str(self.stale)))
        self.assertEqual(backend.executable, str(expected))

    def test_invalid_nonversioned_and_nonbundle_paths_do_not_fall_back(self):
        self.executable("1111111111111111")
        examples = [self.bundle / "current" / "fakeagent.exe",
                    self.root / "other" / "0000000000000000" / "fakeagent.exe",
                    self.bundle / "0000000000000000" / "fakeagent.cmd",
                    Path("bin") / "0000000000000000" / "fakeagent.exe"]
        for requested in examples:
            with self.subTest(requested=requested):
                self.assertIsNone(resolve_agent_executable(str(requested)))

    def test_missing_or_directory_candidates_are_not_executables(self):
        self.assertIsNone(resolve_agent_executable(str(self.stale)))
        (self.bundle / "1111111111111111" / "fakeagent.exe").mkdir(parents=True)
        self.assertIsNone(resolve_agent_executable(str(self.stale)))

    def test_existing_unusable_explicit_path_does_not_fall_back(self):
        self.stale.mkdir(parents=True)
        self.executable("1111111111111111")
        self.assertIsNone(resolve_agent_executable(str(self.stale)))

    def test_candidate_resolving_outside_its_sibling_directory_is_rejected(self):
        candidate = self.executable("1111111111111111")
        outside = self.root / "outside" / "fakeagent.exe"
        original_resolve = Path.resolve

        def resolved(path, *args, **kwargs):
            return outside if path == candidate else original_resolve(path, *args, **kwargs)

        with patch.object(Path, "resolve", resolved):
            self.assertIsNone(resolve_agent_executable(str(self.stale)))

    def test_directory_inspection_has_a_fixed_bound(self):
        for index in range(257):
            (self.bundle / f"{index + 1:016x}").mkdir()
        self.assertIsNone(resolve_agent_executable(str(self.stale)))


class RunnerBoundaryTests(unittest.TestCase):
    def test_invalid_decisions_and_repairs_are_rejected(self):
        for value in ({"action": "SHELL", "reason": "x"}, {"action": "WAIT", "reason": ""},
                      {"action": [], "reason": "x"}, {"action": "WAIT", "reason": "x", "command": "x"}):
            with self.subTest(value=value), self.assertRaises(RunnerError):
                validate_decision(value)
        with self.assertRaises(RunnerError):
            validate_repair({"status": [], "summary": "x", "tests": [], "restart_required": False})

    def test_missing_operator_metadata_is_not_safe_for_automation(self):
        state = observation()
        del state["last_control"]
        with self.assertRaisesRegex(RunnerError, "operator control sequence"):
            live_observation({"ok": True, "fresh": True, "observation": state})

    def test_invalid_layer_progress_prevents_live_observation(self):
        state = observation(current_layer={
            "order": "LAYERS", "stage": "STRUCTURE", "index": 2, "total": 1,
            "y": 64, "chunk_index": 1, "chunk_total": 1,
        })
        with self.assertRaisesRegex(RunnerError, "Live layer progress"):
            live_observation({"ok": True, "fresh": True, "observation": state})

    def test_layer_stage_and_chunk_changes_trigger_new_semantic_key(self):
        layer = {
            "order": "LAYERS", "stage": "STRUCTURE", "index": 1, "total": 20,
            "y": 64, "chunk_index": 1, "chunk_total": 49,
        }
        first = observation(current_layer=layer)
        for update in ({"stage": "LIGHTING"}, {"y": 65, "index": 2}, {"chunk_index": 2}):
            second = copy.deepcopy(first)
            second["current_layer"].update(update)
            with self.subTest(update=update):
                self.assertNotEqual(semantic_key(first), semantic_key(second))

    def test_timestamp_and_inventory_noise_do_not_trigger_model(self):
        first = observation(materials={"stone": {"available": 10}})
        second = copy.deepcopy(first)
        second.update(updated_at="later", materials={"stone": {"available": 9}})
        self.assertEqual(semantic_key(first), semantic_key(second))

    def test_single_runner_lock_rejects_duplicate_and_releases(self):
        with tempfile.TemporaryDirectory() as directory:
            location = Path(directory)
            with SingleRunner(location):
                with self.assertRaisesRegex(RunnerError, "Another supervisor"):
                    with SingleRunner(location):
                        self.fail("Duplicate runner acquired lock")
            with SingleRunner(location):
                pass

    def test_cli_is_argument_array_and_decisions_use_read_only_sandbox(self):
        with tempfile.TemporaryDirectory() as directory:
            options = RunnerOptions(workspace=Path(directory), executable=sys.executable)
            backend = LocalAgentBackend(options)
            recorded = []
            real_popen = subprocess.Popen

            def launch(args, **kwargs):
                recorded.append(args)
                output_path = args[args.index("--output-last-message") + 1]
                code = "import json,sys; sys.stdin.read(); open(sys.argv[1], 'w').write(json.dumps({'action':'WAIT','reason':'Progress continues.'}))"
                return real_popen([sys.executable, "-c", code, output_path], **kwargs)

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                decision = backend.decide(observation(), "Finish loaded plan", lambda: None)
            self.assertEqual(decision["action"], "WAIT")
            self.assertIn("read-only", recorded[0])
            self.assertNotIn("--approve-for-me", recorded[0])
            self.assertNotIn("-m", recorded[0])
            self.assertNotIn("--ignore-rules", recorded[0])
            self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", recorded[0])

    def test_executor_fault_prompt_invites_bounded_inspection_and_runner_repair(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            state = observation("PAUSED", last_error="Navigation exhausted without reaching the target",
                                allowed_actions=["RESUME", "PAUSE", "STOP"],
                                runner_policy={"repair_allowed": True, "repair_attempts_remaining": 1})
            objective = "Finish the structure; do not plant seeds."
            expected = {"action": "REPAIR", "reason": "Relevant source and runtime evidence identify a defect."}
            cancel_check = lambda: None
            with patch.object(backend, "_invoke", return_value=expected) as invoke:
                self.assertEqual(backend.decide(state, objective, cancel_check), expected)
            invoke.assert_called_once()
            prompt, schema, kind, cancellation = invoke.call_args.args
            instructions, encoded_state = prompt.split("\nObservation:\n", 1)
            self.assertEqual(json.loads(encoded_state), state)
            self.assertIs(schema, DECISION_SCHEMA)
            self.assertEqual(kind, "decision")
            self.assertIs(cancellation, cancel_check)
            self.assertIn("bounded read-only inspection within this single decision call", instructions)
            self.assertIn("WAIT, REPAIR, and COMPLETE are runner decisions, not mod controls", instructions)
            self.assertIn("REPAIR requires runner_policy.repair_allowed", instructions)
            self.assertIn("source path/code behavior and matching runtime evidence", instructions)
            self.assertIn("Do not force a repair", instructions)
            self.assertIn("missing resources, absent world", instructions)
            self.assertIn("never retry a failed operation blindly or override an operator Pause or Stop", instructions)
            self.assertIn("Do not execute any HTTP control", instructions)
            self.assertIn("Do not run builds, tests", instructions)
            self.assertIn("state the specific missing evidence or required action", instructions)
            self.assertIn(objective, instructions)
            self.assertNotIn("PAUSE when uncertain", instructions)

    def test_explicit_model_is_per_process_for_decisions_and_repairs(self):
        with tempfile.TemporaryDirectory() as directory:
            options = RunnerOptions(workspace=Path(directory), executable=sys.executable,
                                    model="test-model", reasoning_effort="medium")
            backend = LocalAgentBackend(options)
            recorded = []
            real_popen = subprocess.Popen

            def launch(args, **kwargs):
                recorded.append(args)
                output_path = args[args.index("--output-last-message") + 1]
                code = "import sys; sys.stdin.read(); open(sys.argv[1], 'w').write('{}')"
                return real_popen([sys.executable, "-c", code, output_path], **kwargs)

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                backend._invoke("Decide", DECISION_SCHEMA, "decision", lambda: None)
                backend._invoke("Repair", DECISION_SCHEMA, "repair", lambda: None)
            for arguments in recorded:
                self.assertEqual(arguments[arguments.index("-m") + 1], "test-model")
                self.assertIn("model_reasoning_effort=medium", arguments)
                self.assertIn("mcp_servers.schematic-supervisor.enabled=false", arguments)
            self.assertIn("read-only", recorded[0])
            self.assertNotIn("--sandbox", recorded[1])
            self.assertIn("--approve-for-me", recorded[1])
            self.assertNotIn("--ignore-rules", recorded[1])
            self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", recorded[1])

    def test_early_cli_rejection_reports_exit_and_log_even_when_prompt_pipe_breaks(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            real_popen = subprocess.Popen

            def launch(_args, **kwargs):
                code = "import sys; sys.stderr.write('incompatible test arguments'); sys.exit(2)"
                return real_popen([sys.executable, "-c", code], **kwargs)

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                with self.assertRaisesRegex(RunnerError, r"Agent repair failed with exit code 2; inspect repair-.*\.stderr\.log"):
                    backend._invoke("x" * 262_144, DECISION_SCHEMA, "repair", lambda: None)
            logs = list(backend.directory.glob("repair-*.stderr.log"))
            self.assertEqual(len(logs), 1)
            self.assertEqual(logs[0].read_text(), "incompatible test arguments")
            self.assertEqual(list(backend.directory.glob("repair-*.json")), [backend.directory / "repair-schema.json"])

    def test_cli_launch_failure_is_not_reported_as_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=OSError("private diagnostic")):
                with self.assertRaisesRegex(RunnerError, "could not start the CLI \\(OSError\\)") as raised:
                    backend._invoke("Repair", DECISION_SCHEMA, "repair", lambda: None)
            self.assertNotIn("private diagnostic", str(raised.exception))

    def test_repair_round_trip_accepts_only_the_reported_final_result(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            expected = {"status": "blocked", "summary": "The relevant fixture does not establish a code defect.",
                        "tests": [], "restart_required": False}
            real_popen = subprocess.Popen
            captured = {}

            def launch(args, **kwargs):
                captured["schema"] = json.loads(Path(args[args.index("--output-schema") + 1]).read_text())
                output_path = args[args.index("--output-last-message") + 1]
                code = "import sys; sys.stdin.read(); open(sys.argv[1], 'w').write(sys.argv[2])"
                return real_popen([sys.executable, "-c", code, output_path, json.dumps(expected)], **kwargs)

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                result = backend.repair(observation("PAUSED"), "Inspect the observed source/runtime mismatch.", lambda: None)
            self.assertEqual(result, expected)
            self.assertEqual(captured["schema"], REPAIR_SCHEMA)

    def test_success_exit_without_accepting_prompt_is_not_a_valid_repair(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            real_popen = subprocess.Popen

            def launch(_args, **kwargs):
                return real_popen([sys.executable, "-c", "pass"], **kwargs)

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                with self.assertRaisesRegex(RunnerError, "closed prompt input before accepting the request"):
                    backend._invoke("x" * 262_144, REPAIR_SCHEMA, "repair", lambda: None)

    def test_final_response_errors_are_distinct_from_process_failure(self):
        for response, message in ((None, "missing or too large"), (b"{broken", "not valid UTF-8 JSON"),
                                  (b"\xff", "not valid UTF-8 JSON"), (b" " * 131_073, "missing or too large")):
            with self.subTest(message=message, size=None if response is None else len(response)):
                with tempfile.TemporaryDirectory() as directory:
                    backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
                    real_popen = subprocess.Popen

                    def launch(args, **kwargs):
                        if response is not None:
                            Path(args[args.index("--output-last-message") + 1]).write_bytes(response)
                        return real_popen([sys.executable, "-c", "import sys; sys.stdin.read()"], **kwargs)

                    with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                        with self.assertRaisesRegex(RunnerError, "Agent repair final response.*" + message):
                            backend._invoke("Repair", DECISION_SCHEMA, "repair", lambda: None)

    def test_cli_cancellation_before_launch_does_not_create_process(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            with patch("supervisor_companion.agent_runner.subprocess.Popen") as launch:
                with self.assertRaisesRegex(AgentCancelled, "operator_pause"):
                    backend._invoke("Repair", DECISION_SCHEMA, "repair", lambda: "operator_pause")
            launch.assert_not_called()

    def test_operator_pause_after_process_exit_invalidates_the_final_response(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            children = []
            real_popen = subprocess.Popen

            def launch(args, **kwargs):
                output_path = args[args.index("--output-last-message") + 1]
                code = "import sys; sys.stdin.read(); open(sys.argv[1], 'w').write('{}')"
                child = real_popen([sys.executable, "-c", code, output_path], **kwargs)
                children.append(child)
                return child

            def cancelled():
                return "operator_pause" if children and children[0].poll() is not None else None

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                with self.assertRaisesRegex(AgentCancelled, "operator_pause"):
                    backend._invoke("Repair", REPAIR_SCHEMA, "repair", cancelled)

    def test_invalid_model_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for model in ("", " ", "model\nname", "m" * 129):
                with self.subTest(model=model), self.assertRaises(RunnerError):
                    RunnerOptions(workspace=Path(directory), model=model)

    def test_cli_cancellation_terminates_process(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable))
            children = []
            real_popen = subprocess.Popen

            def launch(_args, **kwargs):
                if _args[0] == "taskkill":
                    return real_popen(_args, **kwargs)
                process = real_popen([sys.executable, "-c", "import sys,time; sys.stdin.read(); time.sleep(60)"], **kwargs)
                children.append(process)
                return process

            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                with self.assertRaisesRegex(AgentCancelled, "operator_stop"):
                    backend._invoke("Observe", DECISION_SCHEMA, "decision", lambda: "operator_stop" if children else None)
            self.assertIsNotNone(children[0].poll())

    def test_cli_timeout_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = LocalAgentBackend(RunnerOptions(workspace=Path(directory), executable=sys.executable, decision_timeout=1))
            real_popen = subprocess.Popen

            def launch(_args, **kwargs):
                if _args[0] == "taskkill":
                    return real_popen(_args, **kwargs)
                return real_popen([sys.executable, "-c", "import sys,time; sys.stdin.read(); time.sleep(60)"], **kwargs)

            started = time.monotonic()
            with patch("supervisor_companion.agent_runner.subprocess.Popen", side_effect=launch):
                with self.assertRaisesRegex(RunnerError, "timeout"):
                    backend._invoke("Observe", DECISION_SCHEMA, "decision", lambda: None)
            self.assertLess(time.monotonic() - started, 15)


if __name__ == "__main__":
    unittest.main()
