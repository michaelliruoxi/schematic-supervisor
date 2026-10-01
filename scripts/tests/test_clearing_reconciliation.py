from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[2] / "scripts/reconcile_clearing.py"
SPEC = importlib.util.spec_from_file_location("clearing_reconciliation", MODULE_PATH)
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)


class ClearingReconciliationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.state = self.root / "runtime/game/config/schematic-supervisor"
        self.state.mkdir(parents=True)
        self.checkpoint_path = self.state / "checkpoint.json"
        self.request_path = self.root / "request.json"
        self.evidence_path = self.root / "incident.json"
        self.probe_path = self.root / "probe.json"
        self.now = datetime(2026, 9, 16, 14, 0, 0, tzinfo=timezone.utc)
        self.operation = "fe662051-4332-47ce-8a6d-7f546cfd074a"
        self.epoch = "376427d8-2ca1-4e21-b71c-3cd9c6b648a1"
        self.session = "00fd140a-a29e-486f-be20-92884da969e3"
        self.run = "be8b715d-bc92-4e61-b34c-b402c9a9f477"
        self.context = {"world_identity_hash": "sha256:" + "b" * 64, "dimension": "minecraft:overworld"}
        self.request = {"version": 1, "request_id": self.operation, "created_at": self.stamp(-60),
                        "checkpoint_sha256": "", "plan_id": "sha256:" + "a" * 64,
                        "saved_run_context": self.context, "player_uuid": "e5a38180-7f4c-45d3-b18e-df3d655b5b91",
                        "target": {"x": 8285, "y": -61, "z": -26963}, "observed_block": recovery.JACK,
                        "expected_block": recovery.AIR, "material": "glowstone", "selected_slot": 4,
                        "inventory_before": 3, "expected_inventory_after": 3}
        self.checkpoint = {"version": 4, "plan_id": self.request["plan_id"], "state": "PAUSED",
                           "resume_state": "BUILDING", "restock_resume_state": "BUILDING",
                           "current_chunk_index": 5, "chunk_count": 49,
                           "schedule_id": "layers-v1-deferred-planting", "planting_deferred": True,
                           "schedule_cursor": 103, "repair_chunk_index": -1, "phase": "ORDINARY_BLOCKS",
                           "verification_stage": "CHUNK", "recovery_stage": "NONE", "stable_verification_passes": 0,
                           "last_verification_fingerprint": "", "consumed_materials": {"dirt": 12411, "glowstone": 57},
                           "withdrawn_materials": {"dirt": 8571, "glowstone": 60},
                           "last_applied_planned_credit": {"id": "retained", "material": "dirt", "quantity": 1},
                           "missing_materials": {}, "restock_requirement": {}, "withdrawal_in_flight": False,
                           "reconciliation_required": True, "reconciliation_detail": recovery.RECONCILIATION_DETAIL,
                           "last_error": "Historical route failure retained \u2192 next attempt",
                           "retained_extension": {"unicode": "\u5c42"}}
        layer = {"order": "LAYERS", "stage": "LIGHTING", "index": 3, "total": 76, "y": -61,
                 "chunk_index": 6, "chunk_total": 49}
        control = {"sequence": 2, "action": "RESUME", "request_id": "previous"}
        ledger = {"consumed": deepcopy(self.checkpoint["consumed_materials"]),
                  "withdrawn": deepcopy(self.checkpoint["withdrawn_materials"])}
        receipt = {"captured_at": self.stamp(-130), "mode": "FLIGHT_CLEARING_LIGHT",
                   "target": {**self.request["target"], "expected_block": recovery.AIR,
                              "actual_block": recovery.JACK, "chunk_received": True},
                   "prediction_pending": False, "material": "glowstone", "inventory_before": 3,
                   "inventory_now": 3, "result": "WAITING", "age_ticks": 54, "budget_ticks": 240,
                   "world_matches": True, "owned_mining": True, "manager_breaking": True,
                   "breaking_position": deepcopy(self.request["target"]), "selected_slot": 4,
                   "selected_item": recovery.ITEM, "error": ""}
        self.live = {"updated_at": self.stamp(-130), "state": "BUILDING", "run_id": self.run,
                     "world_connected": True, "context_matches": True, "layer": layer, "ledger": ledger,
                     "blockers": [], "control": control, "execution": {"mode": "FLIGHT_CLEARING_LIGHT",
                     "owned_mining": True, "last_failure": None, "receipt": receipt},
                     "inventory": {"totals": {"glowstone": 3}, "menu": {"open": False, "sync_id": 0, "cursor": {"count": 0}}},
                     "depot": {"operation": "NONE", "stage": "IDLE"}, "shop": "IDLE",
                     "shop_detail": {"pending_stacks": 0}, "moss_deposit": {"active": False}}
        suspended = {**deepcopy(receipt), "captured_at": self.stamp(-129), "mode": "SUSPENDED",
                     "target": {**self.request["target"], "expected_block": recovery.AIR,
                                "actual_block": None, "chunk_received": None}, "prediction_pending": None,
                     "inventory_now": None, "age_ticks": 72, "error": recovery.DISCONNECT_CAUSE,
                     "owned_mining": None, "manager_breaking": None}
        self.observed = {"run_id": self.run, "plan_id": self.request["plan_id"], "updated_at": self.stamp(-120),
                         "state": "PAUSED", "world_connected": False, "context_matches": False,
                         "phase": "ORDINARY_BLOCKS", "current_layer": layer, "current_chunk": {"index": 6, "total": 49},
                         "material_ledger": ledger, "last_control": control,
                         "execution": {"available": False, "truncated": False, "mode": "SUSPENDED", "receipt": None,
                                       "last_failure": None, "last_receipt": suspended}}
        self.evidence = {"captured_at": self.stamp(-100), "reset_performed": False,
                         "start": {"ok": True, "fresh": True, "connection": "online", "observation": {
                             "run_id": self.run, "plan_id": self.request["plan_id"], "updated_at": self.stamp(-150),
                             "world_connected": True, "context_matches": True, "last_control": control,
                             "last_error": self.checkpoint["last_error"]}},
                         "disconnect": {"ok": True, "fresh": True, "observation": self.observed},
                         "trace": [self.live, {"run_id": self.run, "updated_at": self.stamp(-125),
                                                "world_connected": False, "state": "PAUSED"}]}
        self.probe = {"version": 1, "request": {}, "request_sha256": "", "request_id": self.operation,
                      "session_id": self.session, "session_started_at": self.stamp(-40), "process_started_at": self.stamp(-50),
                      "connection_started_at": self.stamp(-30), "captured_at": self.stamp(-10), "supervisor_state": "IDLE",
                      "automation_idle": True, "pending_transactions": False, "connected_world": True,
                      "player_handler": True, "cursor_empty": True, "player_uuid": self.request["player_uuid"],
                      "current_run_context": self.context, "saved_run_context": self.context, "checkpoint_sha256": "",
                      "checkpoint_matches": True, "plan_id": self.request["plan_id"], "plan_matches": True,
                      "context_matches": True, "player_matches": True, "actual_inventory_total": 3,
                      "server_inventory_complete": True, "server_inventory_all_match": True,
                      "observer_epoch": self.epoch, "observer_sequence": 36, "context_generation": 1,
                      "available": True, "unavailable_reason": "", "selected_slot": 4, "selected_slot_plain": True,
                      "selected_slot_matches": True, "replacement_outside_main": False, "plan_target_matches": True,
                      "target": {**self.request["target"], "expected_block": recovery.AIR, "observed_block": recovery.JACK,
                                 "actual_block": recovery.JACK, "actual_state": {"block_id": recovery.JACK, "properties": {"facing": "south"}},
                                 "chunk_received": True, "prediction_pending": False}, "slots": []}
        for slot in range(36):
            count = 3 if slot == 4 else 0
            item = recovery.ITEM if count else recovery.AIR
            self.probe["slots"].append({"slot": slot, "received": True, "matches_current": True,
                                        "current_item_id": item, "current_count": count, "server_item_id": item,
                                        "server_count": count, "observer_epoch": self.epoch, "sequence": slot + 1})
        self.write(self.state / "run-context.json", {"schemaVersion": 1, "worldIdentityHash": self.context["world_identity_hash"],
                                                      "dimension": self.context["dimension"]})
        self.write(self.state / "settings.json", {"preserved": True})
        self.refresh()

    def stamp(self, offset):
        return (self.now + timedelta(seconds=offset)).isoformat().replace("+00:00", "Z")

    @staticmethod
    def write(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(recovery.encoded(value))

    def refresh(self):
        self.write(self.checkpoint_path, self.checkpoint)
        self.before = self.checkpoint_path.read_bytes()
        self.request["checkpoint_sha256"] = recovery.sha256(self.before)
        self.write(self.request_path, self.request)
        self.probe["request"] = deepcopy(self.request)
        self.probe["request_sha256"] = recovery.sha256(self.request_path.read_bytes())
        self.probe["checkpoint_sha256"] = self.request["checkpoint_sha256"]
        self.write(self.evidence_path, self.evidence)
        self.evidence_hash = recovery.sha256(self.evidence_path.read_bytes())
        self.write(self.probe_path, self.probe)

    def run_recovery(self, *, apply=False, process_check=lambda: None, now=None):
        return recovery.reconcile(self.root, self.request_path, self.evidence_path, self.evidence_hash,
                                  self.probe_path, apply=apply, process_check=process_check, now=lambda: now or self.now)

    @property
    def transaction_dir(self):
        return self.root / "runtime/clearing-reconciliations" / self.operation

    def assert_refused(self):
        with self.assertRaises((ValueError, TypeError, KeyError)):
            self.run_recovery(apply=True)
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())

    def test_default_dry_run_does_not_write_and_reports_zero_credit(self):
        paths = set(self.root.rglob("*"))
        report = self.run_recovery()
        self.assertEqual("dry_run", report["status"])
        self.assertEqual((57, 57, 0), (report["consumed_before"], report["consumed_after"], report["credit_quantity"]))
        self.assertEqual(paths, set(self.root.rglob("*")))
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())

    def test_apply_changes_exactly_two_fields_and_preserves_all_state(self):
        saved = recovery._UPDATER.state_snapshot(self.root, self.state)
        checks = []
        self.assertEqual("applied", self.run_recovery(apply=True, process_check=lambda: checks.append(1))["status"])
        expected = {**self.checkpoint, "reconciliation_required": False, "reconciliation_detail": ""}
        self.assertEqual(expected, json.loads(self.checkpoint_path.read_bytes()))
        self.assertGreaterEqual(len(checks), 4)
        self.assertEqual(self.before, (self.transaction_dir / "checkpoint.before.json").read_bytes())
        self.assertEqual({**saved, "checkpoint.json": self.checkpoint_path.read_bytes()}, recovery._UPDATER.state_snapshot(self.root, self.state))
        record = json.loads((self.transaction_dir / "transaction.json").read_bytes())
        self.assertEqual(("committed", 0), (record["status"], record["quantity"]))

    def test_fresh_air_is_accepted_without_a_consumed_credit(self):
        self.probe["target"].update(actual_block=recovery.AIR, actual_state={"block_id": recovery.AIR, "properties": {}})
        self.write(self.probe_path, self.probe)
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])
        self.assertEqual(self.checkpoint["consumed_materials"], json.loads(self.checkpoint_path.read_bytes())["consumed_materials"])

    def test_current_jack_facing_is_not_fabricated_as_historical_facing(self):
        self.probe["target"]["actual_state"]["properties"]["facing"] = "north"
        self.write(self.probe_path, self.probe)
        self.assertEqual("dry_run", self.run_recovery()["status"])

    def test_one_final_pause_after_disconnect_is_allowed_but_resume_or_reset_is_not(self):
        self.observed["last_control"] = {"sequence": 3, "action": "PAUSE", "request_id": "incident-pause"}
        self.refresh()
        self.assertEqual("dry_run", self.run_recovery()["status"])
        for action in ("RESUME", "RESET", "START", "STOP"):
            with self.subTest(action=action):
                self.observed["last_control"]["action"] = action
                self.refresh()
                self.assert_refused()

    def test_committed_replay_remains_zero_credit_after_probe_ages(self):
        self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        self.assertEqual("already_applied", self.run_recovery(apply=True, now=self.now + timedelta(hours=1))["status"])
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_prepared_record_precedes_atomic_write_and_can_resume(self):
        real = recovery.atomic_bytes
        def fail(path, data):
            if path == self.checkpoint_path:
                record = json.loads((self.transaction_dir / "transaction.json").read_bytes())
                self.assertEqual("prepared", record["status"])
                self.assertEqual(recovery.sha256(data), record["after_sha256"])
                raise OSError("fixture interrupted write")
            real(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])

    def test_crash_after_atomic_write_can_commit_without_new_freshness_or_credit(self):
        real = recovery.atomic_bytes
        def fail(path, data):
            if path.name == "transaction.json" and json.loads(data)["status"] == "committed":
                raise OSError("fixture interrupted commit")
            real(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        self.assertEqual("already_applied", self.run_recovery(apply=True, now=self.now + timedelta(hours=1))["status"])
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_freshness_and_process_session_order_are_required(self):
        for key, value in (("captured_at", self.stamp(-301)), ("process_started_at", self.stamp(-61)),
                           ("connection_started_at", self.stamp(-41)), ("captured_at", self.stamp(1))):
            with self.subTest(key=key, value=value):
                original = deepcopy(self.probe)
                self.probe[key] = value
                self.write(self.probe_path, self.probe)
                self.assert_refused()
                self.probe = original

    def test_pending_and_quiescence_and_selected_stack_guards_fail_closed(self):
        for key, value in {"available": False, "automation_idle": False, "pending_transactions": True,
                           "connected_world": False, "player_handler": False, "cursor_empty": False,
                           "checkpoint_matches": False, "plan_matches": False, "context_matches": False,
                           "player_matches": False, "supervisor_state": "BUILDING", "server_inventory_complete": False,
                           "server_inventory_all_match": False, "selected_slot": 5, "selected_slot_plain": False,
                           "selected_slot_matches": False, "replacement_outside_main": True,
                           "plan_target_matches": False, "actual_inventory_total": 2}.items():
            with self.subTest(key=key):
                original = deepcopy(self.probe)
                self.probe[key] = value
                self.write(self.probe_path, self.probe)
                self.assert_refused()
                self.probe = original

    def test_received_no_prediction_and_exact_allowed_target_are_required(self):
        for key, value in {"x": 8286, "chunk_received": False, "prediction_pending": True,
                           "actual_block": recovery.ITEM, "observed_block": "minecraft:moss_block",
                           "expected_block": recovery.ITEM, "actual_state": None}.items():
            with self.subTest(key=key):
                original = deepcopy(self.probe)
                self.probe["target"][key] = value
                self.write(self.probe_path, self.probe)
                self.assert_refused()
                self.probe = original

    def test_all36_exact_applied_slot_receipts_are_required(self):
        mutations = [lambda p: p["slots"].pop(), lambda p: p["slots"].append(p["slots"][0]),
                     lambda p: p["slots"][0].update(received=False), lambda p: p["slots"][0].update(matches_current=False),
                     lambda p: p["slots"][0].update(observer_epoch=self.session), lambda p: p["slots"][0].update(sequence=37),
                     lambda p: p["slots"][4].update(server_count=2), lambda p: p["slots"][4].update(current_count=2),
                     lambda p: p["slots"][4].update(server_item_id=recovery.AIR),
                     lambda p: p["slots"][5].update(current_item_id=recovery.ITEM, server_item_id=recovery.ITEM,
                                                   current_count=1, server_count=1)]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                original = deepcopy(self.probe)
                mutate(self.probe)
                self.write(self.probe_path, self.probe)
                self.assert_refused()
                self.probe = original

    def test_nonclearing_manual_support_or_consuming_incidents_are_rejected(self):
        for mode in ("FLIGHT_ORDINARY_WAITING_CONFIRMATION", "FLIGHT_CLEARING_MOSS", "SUPPORT_REMOVAL", "TILLING"):
            with self.subTest(mode=mode):
                self.live["execution"]["receipt"]["mode"] = mode
                self.refresh()
                self.assert_refused()

    def test_changed_live_target_or_inventory_or_selected_slot_is_rejected(self):
        for field, value in (("inventory_now", 2), ("inventory_before", 4), ("selected_slot", 5),
                             ("selected_item", "minecraft:diamond_hoe"), ("owned_mining", False),
                             ("prediction_pending", True), ("result", "UNCERTAIN"), ("age_ticks", 240)):
            with self.subTest(field=field):
                receipt = self.live["execution"]["receipt"]
                previous = receipt[field]
                receipt[field] = value
                self.refresh()
                self.assert_refused()
                receipt[field] = previous

    def test_unrelated_disconnect_cause_or_changed_historical_error_is_rejected(self):
        for field, value in (("reconciliation_detail", recovery.RECONCILIATION_DETAIL + " another failure"),
                             ("last_error", "replaced"), ("withdrawal_in_flight", True), ("phase", "TILL"),
                             ("repair_chunk_index", 5), ("restock_requirement", {"glowstone": 10})):
            with self.subTest(field=field):
                previous = self.checkpoint[field]
                self.checkpoint[field] = value
                self.refresh()
                self.assert_refused()
                self.checkpoint[field] = previous

    def test_same_run_ledger_layer_and_control_are_required(self):
        mutations = [lambda: self.live.update(run_id=self.session), lambda: self.live["ledger"]["consumed"].update(glowstone=58),
                     lambda: self.live["layer"].update(stage="STRUCTURE"), lambda: self.live.update(control={"sequence": 3}),
                     lambda: self.evidence.update(reset_performed=True),
                     lambda: self.observed["execution"]["last_receipt"].update(error="another failure")]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                self.setUp()
                mutate()
                self.refresh()
                self.assert_refused()

    def test_request_only_accepts_exact_zero_spend_glowstone_shape(self):
        for field, value in (("expected_inventory_after", 2), ("material", "dirt"), ("expected_block", recovery.ITEM),
                             ("observed_block", "minecraft:moss_block"), ("selected_slot", 9),
                             ("inventory_before", 65), ("version", True)):
            with self.subTest(field=field):
                previous = self.request[field]
                self.request[field] = value
                self.refresh()
                self.assert_refused()
                self.request[field] = previous

    def test_changed_evidence_hash_or_echo_or_context_refuses(self):
        self.evidence_hash = "0" * 64
        self.assert_refused()
        self.refresh()
        self.probe["request"] = {**self.request, "selected_slot": 5}
        self.write(self.probe_path, self.probe)
        self.assert_refused()
        self.refresh()
        self.write(self.state / "run-context.json", {"schemaVersion": 1, "worldIdentityHash": self.context["world_identity_hash"],
                                                    "dimension": "minecraft:the_nether"})
        self.assert_refused()

    def test_unknown_auxiliary_transaction_blocks(self):
        for name in ("hoe-repair.json", "temporary-support.json", "material-purchase.json", "moss-deposit.json"):
            with self.subTest(name=name):
                self.write(self.state / name, {"version": 1, "journal": {"stage": "WAITING"}})
                self.assert_refused()
                (self.state / name).unlink()

    def test_process_check_is_required_even_for_dry_run(self):
        def running():
            raise ValueError("fixture process present")
        with self.assertRaisesRegex(ValueError, "process present"):
            self.run_recovery(process_check=running)
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())

    def test_checkpoint_compare_and_swap_refuses_intervening_change(self):
        self.write(self.checkpoint_path, {**self.checkpoint, "schedule_cursor": 104})
        with self.assertRaisesRegex(ValueError, "checkpoint SHA256 differs"):
            self.run_recovery(apply=True)

    def test_preparation_race_retains_unapplied_checkpoint(self):
        checks = []
        def change():
            checks.append(1)
            if len(checks) == 3:
                self.write(self.state / "settings.json", {"changed": True})
        with self.assertRaisesRegex(ValueError, "changed during preparation"):
            self.run_recovery(apply=True, process_check=change)
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())

    def test_prepared_before_write_still_requires_fresh_probe(self):
        real = recovery.atomic_bytes
        def fail(path, data):
            if path == self.checkpoint_path:
                raise OSError("fixture write interrupted")
            real(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        with self.assertRaisesRegex(ValueError, "stale"):
            self.run_recovery(apply=True, now=self.now + timedelta(minutes=6))
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())

    def test_duplicate_request_fields_and_path_escape_are_rejected(self):
        self.request_path.write_text('{"version":1,"version":1}', encoding="utf-8")
        self.assert_refused()
        with self.assertRaises(ValueError):
            recovery.reconcile(self.root, self.root.parent / "elsewhere.json", self.evidence_path,
                               self.evidence_hash, self.probe_path, process_check=lambda: None)


if __name__ == "__main__":
    unittest.main()
