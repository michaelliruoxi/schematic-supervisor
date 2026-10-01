from __future__ import annotations

from dataclasses import replace
from pathlib import Path
import tempfile
import unittest

from supervisor_companion.agent_runner import RunnerError, RunnerOptions, SupervisorRunner
from test_agent_runner import MemoryBridge, ScriptedBackend, observation


class MinimalInterventionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.options = RunnerOptions(workspace=Path(temporary.name), max_cycles=200)
        self.events = []
        self.now = 0.0
        self.on_sleep = lambda: None

    def sleep(self, duration):
        self.now += duration
        self.on_sleep()

    def runner(self, bridge, backend, **options):
        return SupervisorRunner(bridge, backend, replace(self.options, **options),
                                sleeper=self.sleep, clock=lambda: self.now, emit=self.events.append)

    @staticmethod
    def capacity_observation(**updates):
        detail = "Inventory capacity blocked: a whole dirt stack requires an empty inventory slot."
        return observation("PAUSED", **({"ready": False, "last_error": detail, "blockers": [detail],
            "allowed_actions": ["PAUSE", "STOP"],
            "inventory": {"available": True, "empty_main_slots": 0, "normal_dirt_capacity": 0}} | updates))

    @staticmethod
    def disconnected_observation(state="IDLE", **updates):
        return observation(state, **({"ready": False, "world_connected": False,
            "context_matches": False, "control_token_configured": True,
            "blockers": ["Join the target world before starting or resuming."],
            "allowed_actions": ["STOP"]} | updates))

    def test_armed_start_waits_for_disconnected_world_without_calls(self):
        for policy in ("on-error", "disabled"):
            for state in ("IDLE", "STOPPED"):
                with self.subTest(policy=policy, state=state):
                    self.events.clear()
                    bridge = MemoryBridge(self.disconnected_observation(state))
                    backend = ScriptedBackend("STOP")
                    runner = self.runner(bridge, backend, model_policy=policy, allow_start=True, max_cycles=6)
                    self.assertEqual(runner.run(), "cycle_limit")
                    self.assertEqual(backend.decisions, 0)
                    self.assertEqual(bridge.controls, [])
                    self.assertTrue(runner.initial_activation)
                    self.assertFalse(runner.active_run_observed)
                    self.assertEqual(sum(event["event"] == "waiting_for_readiness" for event in self.events), 1)

    def test_disconnected_start_waits_through_login_scans_then_starts(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                bridge = MemoryBridge(self.disconnected_observation(sequence=7))
                backend = ScriptedBackend("STOP")
                polls = 0

                def login():
                    nonlocal polls
                    polls += 1
                    if polls == 2:
                        bridge.state.update(run_id="joined-world", world_connected=True, context_matches=True,
                                            blockers=["Wait for registered-depot scans to finish."])
                    elif polls == 5:
                        bridge.state.update(ready=True, blockers=[], allowed_actions=["START", "STOP"])
                    elif bridge.state["state"] == "BUILDING":
                        bridge.state.update(state="DONE", stable_verification_passes=2)

                self.on_sleep = login
                self.assertEqual(self.runner(bridge, backend, model_policy=policy, allow_start=True).run(), "complete")
                self.assertEqual(backend.decisions, 0)
                self.assertEqual([action for action, _ in bridge.controls], ["START"])
                self.assertEqual(bridge.controls[0][1]["expected_control_sequence"], 7)
                self.assertEqual(bridge.controls[0][1]["expected_state"], "IDLE")
                self.assertEqual(bridge.controls[0][1]["expected_run_id"], "joined-world")

    def test_first_login_identity_change_cannot_override_external_stop(self):
        bridge = MemoryBridge(self.disconnected_observation())
        backend = ScriptedBackend("START")
        self.on_sleep = lambda: bridge.state.update(run_id="joined-world", world_connected=True,
            context_matches=True, ready=True, blockers=[], allowed_actions=["START", "STOP"],
            last_control={"sequence": 1, "action": "STOP", "request_id": "operator"})
        self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "mod_restarted")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_connected_startup_context_change_keeps_restart_boundary(self):
        bridge = MemoryBridge(observation("IDLE", world_connected=True, context_matches=True,
            ready=False, control_token_configured=True,
            blockers=["Wait for registered-depot scans to finish."], allowed_actions=["STOP"]))
        backend = ScriptedBackend("START")
        self.on_sleep = lambda: bridge.state.update(run_id="different-world")
        self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "mod_restarted")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_disconnected_saved_checkpoint_preserves_initial_resume(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                bridge = MemoryBridge(self.disconnected_observation("PAUSED", last_error="Saved moss failure"))
                backend = ScriptedBackend("STOP")
                polls = 0

                def login():
                    nonlocal polls
                    polls += 1
                    if polls == 3:
                        bridge.state.update(world_connected=True, context_matches=True,
                                            ready=True, blockers=[], allowed_actions=["RESUME", "STOP"])
                    elif bridge.state["state"] == "BUILDING":
                        bridge.state.update(state="DONE", stable_verification_passes=2)

                self.on_sleep = login
                self.assertEqual(self.runner(bridge, backend, model_policy=policy, allow_resume=True).run(), "complete")
                self.assertEqual(backend.decisions, 0)
                self.assertEqual([action for action, _ in bridge.controls], ["RESUME"])

    def test_disconnected_startup_wait_preserves_operator_stop_and_pause(self):
        for action in ("STOP", "PAUSE"):
            with self.subTest(action=action):
                bridge = MemoryBridge(self.disconnected_observation())
                backend = ScriptedBackend("START")
                self.on_sleep = lambda: bridge.state.update(
                    last_control={"sequence": 1, "action": action, "request_id": "operator"})
                self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "operator_" + action.lower())
                self.assertEqual(backend.decisions, 0)
                self.assertEqual(bridge.controls, [])

    def test_active_run_disconnect_is_not_treated_as_initial_readiness(self):
        bridge, backend = MemoryBridge(observation("BUILDING")), ScriptedBackend("WAIT")
        self.on_sleep = lambda: bridge.state.update(self.disconnected_observation(
            "PAUSED", last_error="World disconnected during construction"))
        runner = self.runner(bridge, backend, allow_start=True, allow_resume=True, max_cycles=6)
        self.assertEqual(runner.run(), "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])
        self.assertFalse(runner.initial_activation)
        self.assertTrue(runner.active_run_observed)
        self.assertFalse(any(event["event"] == "waiting_for_readiness" for event in self.events))

    def test_continuous_disconnected_start_preserves_existing_model_behavior(self):
        bridge = MemoryBridge(self.disconnected_observation())
        backend = ScriptedBackend("WAIT")
        self.assertEqual(self.runner(bridge, backend, model_policy="continuous", allow_start=True,
                                     max_cycles=6).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])
        self.assertFalse(any(event["event"] == "waiting_for_readiness" for event in self.events))

    def test_disconnected_start_does_not_mask_other_blockers_or_missing_token(self):
        for token_configured, extra in ((True, ["Unsupported schematic palette"]), (False, [])):
            with self.subTest(token_configured=token_configured):
                bridge = MemoryBridge(self.disconnected_observation(
                    control_token_configured=token_configured,
                    blockers=["Join the target world before starting or resuming."] + extra))
                backend = ScriptedBackend("WAIT")
                self.assertEqual(self.runner(bridge, backend, allow_start=True, max_cycles=6).run(), "cycle_limit")
                self.assertEqual(backend.decisions, 1)
                self.assertEqual(bridge.controls, [])
                self.assertFalse(any(event["event"] == "waiting_for_readiness" for event in self.events))

    def test_known_capacity_block_waits_without_model_or_repair_despite_slot_and_status_churn(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                self.events.clear()
                bridge = MemoryBridge(self.capacity_observation())
                backend = ScriptedBackend("REPAIR")

                def inventory_churn():
                    bridge.state["inventory"]["main_slots"] = [{"slot": 0, "count": int(self.now) % 64}]
                    bridge.state["materials"] = {"dirt": {"available": int(self.now)}}
                    bridge.state["last_message"] = f"Inventory observation {self.now}"

                self.on_sleep = inventory_churn
                runner = self.runner(bridge, backend, model_policy=policy, allow_resume=True, allow_repair=True,
                                     max_cycles=100)
                self.assertEqual(runner.run(), "cycle_limit")
                self.assertEqual(backend.decisions, 0)
                self.assertEqual(backend.repairs, [])
                self.assertEqual(bridge.controls, [])
                self.assertTrue(runner.initial_activation)
                self.assertEqual(sum(event["event"] == "waiting_for_inventory_capacity" for event in self.events), 1)

    def test_capacity_resolution_preserves_explicit_initial_resume_and_control_guards(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                bridge = MemoryBridge(self.capacity_observation(sequence=17))
                backend = ScriptedBackend("REPAIR")
                polls = 0

                def capacity_freed():
                    nonlocal polls
                    polls += 1
                    if polls == 3:
                        bridge.state.update(ready=True, blockers=[], allowed_actions=["RESUME", "PAUSE", "STOP"])
                        bridge.state["inventory"].update(empty_main_slots=1, normal_dirt_capacity=64)
                    elif bridge.state["state"] == "BUILDING":
                        bridge.state.update(state="DONE", stable_verification_passes=2)

                self.on_sleep = capacity_freed
                self.assertEqual(self.runner(bridge, backend, model_policy=policy, allow_resume=True).run(), "complete")
                self.assertEqual(backend.decisions, 0)
                self.assertEqual([action for action, _ in bridge.controls], ["RESUME"])
                self.assertEqual(bridge.controls[0][1]["expected_control_sequence"], 17)
                self.assertEqual(bridge.controls[0][1]["expected_state"], "PAUSED")
                self.assertEqual(bridge.controls[0][1]["expected_run_id"], "test-run")

    def test_capacity_resolution_allows_one_fresh_decision_for_an_earlier_unchanged_fault(self):
        original = observation("PAUSED", last_error="Other unresolved execution fault", blockers=[])
        bridge, backend = MemoryBridge(original.copy()), ScriptedBackend("WAIT")
        polls = 0

        def capacity_changes():
            nonlocal polls
            polls += 1
            if polls == 2:
                bridge.state = self.capacity_observation()
            elif polls == 5:
                bridge.state = original.copy()

        self.on_sleep = capacity_changes
        self.assertEqual(self.runner(bridge, backend, max_cycles=12).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 2)
        self.assertEqual(bridge.controls, [])
        self.assertEqual(sum(event["event"] == "inventory_capacity_blocker_cleared" for event in self.events), 1)

    def test_old_capacity_error_without_current_blocker_does_not_suppress_other_faults(self):
        bridge = MemoryBridge(self.capacity_observation(blockers=[]))
        backend = ScriptedBackend("WAIT")
        self.assertEqual(self.runner(bridge, backend, max_cycles=6).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertFalse(any(event["event"] == "waiting_for_inventory_capacity" for event in self.events))

    def test_capacity_blocker_does_not_hide_an_additional_unresolved_fault(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                self.events.clear()
                state = self.capacity_observation()
                state["blockers"].append("A material purchase receipt requires reconciliation.")
                bridge, backend = MemoryBridge(state), ScriptedBackend("WAIT")
                expected = "cycle_limit" if policy == "on-error" else "attention_required"
                self.assertEqual(self.runner(bridge, backend, model_policy=policy, max_cycles=8).run(), expected)
                self.assertEqual(backend.decisions, 1 if policy == "on-error" else 0)
                self.assertEqual(bridge.controls, [])
                self.assertFalse(any(event["event"] == "waiting_for_inventory_capacity" for event in self.events))

    def test_new_fault_during_capacity_wait_triggers_only_one_decision(self):
        bridge, backend = MemoryBridge(self.capacity_observation()), ScriptedBackend("WAIT")
        polls = 0

        def new_fault():
            nonlocal polls
            polls += 1
            if polls == 3:
                bridge.state["blockers"].append("A material purchase receipt requires reconciliation.")
            bridge.state["last_message"] = f"Inventory refresh {polls}"
            bridge.state["inventory"]["main_slots"] = [{"slot": 0, "count": polls % 64}]

        self.on_sleep = new_fault
        self.assertEqual(self.runner(bridge, backend, max_cycles=100).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])
        self.assertEqual(sum(event["event"] == "waiting_for_inventory_capacity" for event in self.events), 1)

    def test_continuous_policy_keeps_existing_capacity_decision_behavior(self):
        bridge, backend = MemoryBridge(self.capacity_observation()), ScriptedBackend("WAIT")
        self.assertEqual(self.runner(bridge, backend, model_policy="continuous", max_cycles=40).run(), "cycle_limit")
        self.assertGreater(backend.decisions, 0)
        self.assertFalse(any(event["event"] == "waiting_for_inventory_capacity" for event in self.events))
        self.assertEqual(bridge.controls, [])

    def test_capacity_wait_preserves_operator_and_freshness_guards(self):
        for changed in ("STOP", "PAUSE", "stale"):
            with self.subTest(changed=changed):
                bridge, backend = MemoryBridge(self.capacity_observation()), ScriptedBackend("RESUME")

                def intervene():
                    if changed == "stale":
                        bridge.fresh = False
                    else:
                        bridge.state["last_control"] = {"sequence": 1, "action": changed, "request_id": "operator"}

                self.on_sleep = intervene
                expected = "failed" if changed == "stale" else "operator_" + changed.lower()
                self.assertEqual(self.runner(bridge, backend, allow_resume=True).run(), expected)
                self.assertEqual(backend.decisions, 0)
                self.assertFalse(any(action == "RESUME" for action, _ in bridge.controls))

    def test_default_policy_only_uses_model_for_faults(self):
        self.assertEqual(self.options.model_policy, "on-error")
        with self.assertRaises(RunnerError):
            replace(self.options, model_policy="sometimes")

    def test_full_mod_progress_including_recovery_and_verification_needs_no_model(self):
        bridge, backend = MemoryBridge(observation("LOADING")), ScriptedBackend("PAUSE")
        states = ["BUILDING", "RESTOCKING", "STUCK", "BUILDING", "VERIFYING"]
        polls = 0

        def progress():
            nonlocal polls
            polls += 1
            bridge.state.update(state=states[min(polls // 20, len(states) - 1)],
                                last_message=f"Progress {polls}", baritone_status=f"Waypoint {polls}",
                                current_chunk={"index": min(49, polls), "total": 49},
                                inventory={"main_slots": [{"slot": 0, "count": polls % 64}]},
                                material_shop={"stage": "SETTLING", "pending": True,
                                               "detail": f"Waiting for receipt {polls}"})
            if polls == 120:
                bridge.state.update(state="DONE", stable_verification_passes=2)

        self.on_sleep = progress
        self.assertEqual(self.runner(bridge, backend).run(), "complete")
        self.assertGreater(self.now, self.options.decision_seconds)
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_explicit_start_is_guarded_and_deterministic(self):
        bridge, backend = MemoryBridge(observation("STOPPED", sequence=7)), ScriptedBackend("STOP")
        self.on_sleep = lambda: bridge.state.update(state="DONE", stable_verification_passes=2)
        self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "complete")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual([item[0] for item in bridge.controls], ["START"])
        guards = bridge.controls[0][1]
        self.assertEqual(guards["expected_control_sequence"], 7)
        self.assertEqual(guards["expected_state"], "STOPPED")
        self.assertEqual(guards["expected_run_id"], "test-run")

    def test_armed_start_can_resume_the_loaded_checkpoint_without_model(self):
        class LoadingBridge(MemoryBridge):
            def control(self, action, **guards):
                result = super().control(action, **guards)
                if action == "START":
                    self.state["state"] = "LOADING"
                return result

        bridge, backend = LoadingBridge(observation("IDLE")), ScriptedBackend("STOP")

        def progress():
            if bridge.state["state"] == "LOADING":
                bridge.state.update(state="PAUSED", allowed_actions=["RESUME", "PAUSE", "STOP"])
            elif bridge.state["state"] == "BUILDING":
                bridge.state.update(state="DONE", stable_verification_passes=2)

        self.on_sleep = progress
        self.assertEqual(self.runner(bridge, backend, allow_start=True, allow_resume=True).run(), "complete")
        self.assertEqual([item[0] for item in bridge.controls], ["START", "RESUME"])
        self.assertEqual(backend.decisions, 0)

    def test_unchanged_fault_does_not_repeat_model_calls_on_time_or_status_updates(self):
        bridge, backend = MemoryBridge(observation("PAUSED", last_error="Unresolved shop layout")), ScriptedBackend()
        self.on_sleep = lambda: bridge.state.update(last_message=f"Observed at {self.now}",
                                                   baritone_status=f"Idle at {self.now}")
        self.assertEqual(self.runner(bridge, backend).run(), "cycle_limit")
        self.assertGreater(self.now, self.options.decision_seconds * 3)
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])

    def test_inventory_and_shop_refreshes_do_not_repeat_an_unchanged_fault_decision(self):
        bridge, backend = MemoryBridge(observation("PAUSED", last_error="Unresolved shop layout")), ScriptedBackend()

        def refresh_metadata():
            bridge.state.update(inventory={"main_slots": [{"slot": 0, "count": int(self.now) % 64}]},
                                material_shop={"stage": "FAILED", "pending": True,
                                               "detail": f"Observation {self.now}"})

        self.on_sleep = refresh_metadata
        self.assertEqual(self.runner(bridge, backend).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])

    def test_the_start_build_check_is_mod_work_that_needs_no_model_or_second_start(self):
        bridge, backend = MemoryBridge(observation("IDLE")), ScriptedBackend("PAUSE")
        states = iter(["LOADING", "CHECKING", "CHECKING", "BUILDING"])

        def progress():
            state = next(states, None)
            if state is not None:
                bridge.state.update(state=state, allowed_actions=["PAUSE", "STOP"])
            else:
                bridge.state.update(state="DONE", stable_verification_passes=2)

        self.on_sleep = progress
        self.assertEqual(self.runner(bridge, backend, allow_start=True).run(), "complete")
        self.assertEqual([item[0] for item in bridge.controls], ["START"])
        self.assertEqual(backend.decisions, 0)

    def test_loaded_checkpoint_waits_for_readiness_before_armed_resume(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                bridge, backend = MemoryBridge(observation("IDLE")), ScriptedBackend("PAUSE")
                polls = 0

                def progress():
                    nonlocal polls
                    polls += 1
                    if polls == 1:
                        bridge.state.update(state="LOADING")
                    elif polls == 2:
                        bridge.state.update(state="PAUSED", ready=False, world_connected=True,
                                            context_matches=True, control_token_configured=True,
                                            last_error="Saved Moss obstruction from the previous client",
                                            blockers=["Wait for registered-depot scans to finish."],
                                            allowed_actions=["PAUSE", "STOP"])
                    elif polls == 5:
                        bridge.state.update(ready=True, blockers=[], allowed_actions=["RESUME", "PAUSE", "STOP"])
                    elif polls > 5 and bridge.state["state"] == "BUILDING":
                        bridge.state.update(state="DONE", stable_verification_passes=2)

                self.on_sleep = progress
                self.assertEqual(self.runner(bridge, backend, allow_start=True, allow_resume=True,
                                             model_policy=policy).run(), "complete")
                self.assertEqual([item[0] for item in bridge.controls], ["START", "RESUME"])
                self.assertEqual(backend.decisions, 0)

    def test_new_inventory_evidence_can_trigger_one_new_fault_decision(self):
        bridge = MemoryBridge(observation("PAUSED", last_error="Material unavailable",
                                          materials={"dirt": {"available": 0, "required": 1, "missing": 1}}))
        backend = ScriptedBackend()

        def supplied():
            if self.now >= 20:
                bridge.state["materials"] = {"dirt": {"available": 64, "required": 1, "missing": 0}}

        self.on_sleep = supplied
        self.assertEqual(self.runner(bridge, backend).run(), "cycle_limit")
        self.assertEqual(backend.decisions, 2)

    def test_explicit_initial_resume_with_saved_error_uses_no_model_and_keeps_control_guards(self):
        for policy in ("on-error", "disabled"):
            with self.subTest(policy=policy):
                bridge = MemoryBridge(observation("PAUSED", sequence=17,
                                                  last_error="Saved Moss obstruction from the previous client"))
                backend = ScriptedBackend("PAUSE")
                self.on_sleep = lambda: bridge.state.update(state="DONE", stable_verification_passes=2)
                runner = self.runner(bridge, backend, allow_resume=True, model_policy=policy)
                self.assertEqual(runner.run(), "complete")
                self.assertEqual(backend.decisions, 0)
                self.assertEqual([action for action, _ in bridge.controls], ["RESUME"])
                guards = bridge.controls[0][1]
                self.assertEqual(guards["expected_run_id"], "test-run")
                self.assertEqual(guards["expected_state"], "PAUSED")
                self.assertEqual(guards["expected_control_sequence"], 17)
                self.assertFalse(runner.initial_activation)
                self.assertFalse(runner._startup_resume_pending)

    def test_own_start_loading_then_saved_error_uses_exactly_one_startup_resume(self):
        class LoadingBridge(MemoryBridge):
            def control(self, action, **guards):
                result = super().control(action, **guards)
                if action == "START":
                    self.state["state"] = "LOADING"
                return result

        bridge, backend = LoadingBridge(observation("IDLE")), ScriptedBackend("PAUSE")
        loading_polls = 0

        def loaded():
            nonlocal loading_polls
            if bridge.state["state"] == "LOADING":
                loading_polls += 1
                if loading_polls == 2:
                    bridge.state.update(state="PAUSED", last_error="Saved Moss obstruction",
                                        allowed_actions=["RESUME", "PAUSE", "STOP"])
            elif bridge.state["state"] == "BUILDING":
                bridge.state.update(state="DONE", stable_verification_passes=2)

        self.on_sleep = loaded
        runner = self.runner(bridge, backend, allow_start=True, allow_resume=True)
        self.assertEqual(runner.run(), "complete")
        self.assertEqual(loading_polls, 2)
        self.assertEqual([action for action, _ in bridge.controls], ["START", "RESUME"])
        self.assertEqual(backend.decisions, 0)
        self.assertFalse(runner._startup_resume_pending)

    def test_known_readiness_blocker_can_wait_with_saved_error_only_for_armed_initial_resume(self):
        for armed in (True, False):
            with self.subTest(armed=armed):
                bridge = MemoryBridge(observation("PAUSED", ready=False, world_connected=True,
                    context_matches=True, control_token_configured=True, last_error="Saved Moss obstruction",
                    blockers=["This floating schematic requires flight to already be active before starting."],
                    allowed_actions=["PAUSE", "STOP"]))
                backend = ScriptedBackend("WAIT")
                polls = 0

                def ready():
                    nonlocal polls
                    polls += 1
                    if polls == 3:
                        bridge.state.update(ready=True, blockers=[], allowed_actions=["RESUME", "PAUSE", "STOP"])
                    if bridge.state["state"] == "BUILDING":
                        bridge.state.update(state="DONE", stable_verification_passes=2)

                self.on_sleep = ready
                result = self.runner(bridge, backend, allow_resume=armed, max_cycles=8).run()
                self.assertEqual(result, "complete" if armed else "cycle_limit")
                self.assertEqual([action for action, _ in bridge.controls], ["RESUME"] if armed else [])
                self.assertEqual(backend.decisions, 0 if armed else 2)

    def test_saved_error_without_resume_permission_cannot_resume_automatically(self):
        bridge = MemoryBridge(observation("PAUSED", last_error="Saved Moss obstruction"))
        backend = ScriptedBackend("WAIT")
        runner = self.runner(bridge, backend, allow_resume=False, max_cycles=5)
        self.assertEqual(runner.run(), "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])
        self.assertFalse(runner.active_run_observed)

    def test_attaching_to_active_work_consumes_startup_resume_before_a_fresh_error(self):
        for state in ("BUILDING", "RESTOCKING", "VERIFYING", "STUCK"):
            with self.subTest(state=state):
                bridge, backend = MemoryBridge(observation(state)), ScriptedBackend("WAIT")

                def fault():
                    bridge.state.update(state="PAUSED", last_error="New server rejection after work",
                                        allowed_actions=["RESUME", "PAUSE", "STOP"])

                self.on_sleep = fault
                runner = self.runner(bridge, backend, allow_resume=True, max_cycles=8)
                self.assertEqual(runner.run(), "cycle_limit")
                self.assertEqual(backend.decisions, 1)
                self.assertEqual(bridge.controls, [])
                self.assertFalse(runner.initial_activation)
                self.assertFalse(runner._startup_resume_pending)
                self.assertTrue(runner.active_run_observed)

    def test_start_still_requires_no_error_even_when_resume_is_armed(self):
        bridge = MemoryBridge(observation("IDLE", last_error="Unresolved startup fault"))
        backend = ScriptedBackend("WAIT")
        self.assertEqual(self.runner(bridge, backend, allow_start=True, allow_resume=True, max_cycles=4).run(),
                         "cycle_limit")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(bridge.controls, [])

    def test_operator_pause_interrupts_mod_wait_without_a_model_call(self):
        bridge, backend = MemoryBridge(), ScriptedBackend("RESUME")
        self.on_sleep = lambda: bridge.state.update(
            state="PAUSED", last_control={"sequence": 1, "action": "PAUSE", "request_id": "operator"})
        self.assertEqual(self.runner(bridge, backend).run(), "operator_pause")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(bridge.controls, [])

    def test_disabled_policy_reports_fault_without_launching_model(self):
        bridge, backend = MemoryBridge(observation("PAUSED", last_error="Unknown menu")), ScriptedBackend("REPAIR")
        self.assertEqual(self.runner(bridge, backend, model_policy="disabled").run(), "attention_required")
        self.assertEqual(backend.decisions, 0)
        self.assertEqual(backend.repairs, [])
        self.assertEqual(bridge.controls, [])

    def test_on_error_repair_is_a_runner_action_without_blind_resume(self):
        bridge = MemoryBridge(observation("PAUSED", last_error="Executor navigation exhausted",
                                          allowed_actions=["RESUME", "PAUSE", "STOP"]))
        backend = ScriptedBackend("REPAIR")

        def check_policy(state, _cancel):
            self.assertTrue(state["runner_policy"]["repair_allowed"])
            self.assertEqual(state["runner_policy"]["repair_attempts_remaining"], 1)
            self.assertNotIn("REPAIR", state["allowed_actions"])

        backend.on_decide = check_policy
        runner = self.runner(bridge, backend, allow_repair=True, max_repairs=1)
        self.assertEqual(runner.run(), "repair_finished")
        self.assertEqual(backend.decisions, 1)
        self.assertEqual(len(backend.repairs), 1)
        self.assertEqual(backend.repairs[0]["state"], "PAUSED")
        self.assertEqual(bridge.controls, [])
        self.assertEqual(bridge.state["state"], "PAUSED")
        self.assertEqual(runner.repairs, 1)

    def test_start_permission_does_not_arm_saved_checkpoint_error_recovery(self):
        bridge, backend = MemoryBridge(observation("IDLE")), ScriptedBackend("RESUME")
        polls = 0

        def loaded():
            nonlocal polls
            polls += 1
            if polls == 1:
                bridge.state.update(state="LOADING")
            elif polls == 2:
                bridge.state.update(state="PAUSED", last_error="Saved unresolved fault",
                                    allowed_actions=["RESUME", "PAUSE", "STOP"])

        def check_policy(state, _cancel):
            self.assertFalse(state["runner_policy"]["resume_armed"])

        self.on_sleep = loaded
        backend.on_decide = check_policy
        runner = self.runner(bridge, backend, allow_start=True, allow_resume=False)
        self.assertEqual(runner.run(), "failed")
        self.assertFalse(runner.active_run_observed)
        self.assertEqual(runner.recovery_resumes, 0)
        self.assertEqual([item[0] for item in bridge.controls], ["START"])

    def test_unverified_completion_is_never_accepted_without_model(self):
        bridge, backend = MemoryBridge(observation("DONE", stable_verification_passes=1)), ScriptedBackend()
        self.assertEqual(self.runner(bridge, backend).run(), "failed")
        self.assertEqual(backend.decisions, 0)


if __name__ == "__main__":
    unittest.main()
