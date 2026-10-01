from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[2] / "scripts/reconcile_placement.py"
SPEC = importlib.util.spec_from_file_location("placement_reconciliation", MODULE_PATH)
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)


class PlacementReconciliationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.state = self.root / "runtime/game/config/schematic-supervisor"
        self.state.mkdir(parents=True)
        self.checkpoint_path = self.state / "checkpoint.json"
        self.request_path = self.root / "request.json"
        self.evidence_path = self.root / "uncertain.json"
        self.probe_path = self.root / "probe.json"
        self.now = datetime(2026, 9, 16, 7, 0, 0, tzinfo=timezone.utc)
        self.operation = "fe662051-4332-47ce-8a6d-7f546cfd074a"
        self.epoch = "376427d8-2ca1-4e21-b71c-3cd9c6b648a1"
        self.session = "00fd140a-a29e-486f-be20-92884da969e3"
        self.context = {"world_identity_hash": "sha256:" + "b" * 64, "dimension": "minecraft:overworld"}
        self.request = {"version": 1, "request_id": self.operation, "created_at": self.stamp(-60),
                        "checkpoint_sha256": "", "plan_id": "sha256:" + "a" * 64,
                        "saved_run_context": self.context, "player_uuid": "e5a38180-7f4c-45d3-b18e-df3d655b5b91",
                        "target": {"x": 8195, "y": -60, "z": -26973}, "expected_block": "minecraft:dirt",
                        "material": "dirt", "inventory_before": 94, "expected_inventory_after": 93}
        cause = recovery.failure_detail(self.request)
        self.checkpoint = {"version": 4, "plan_id": self.request["plan_id"], "state": "PAUSED",
                           "resume_state": "BUILDING", "restock_resume_state": "STUCK",
                           "current_chunk_index": 0, "chunk_count": 49,
                           "schedule_id": "layers-v1-deferred-planting", "planting_deferred": True,
                           "schedule_cursor": 49, "repair_chunk_index": -1, "phase": "ORDINARY_BLOCKS",
                           "verification_stage": "CHUNK", "recovery_stage": "NONE",
                           "stable_verification_passes": 0, "last_verification_fingerprint": "",
                           "consumed_materials": {"dirt": 6210}, "withdrawn_materials": {"dirt": 2464},
                           "last_applied_planned_credit": {"id": "retained", "material": "dirt", "quantity": 1},
                           "missing_materials": {}, "restock_requirement": {}, "verification_retries": 0,
                           "repath_attempted": True, "safe_return_attempted": True, "advisor_attempted": True,
                           "withdrawal_in_flight": False, "reconciliation_required": True,
                           "reconciliation_detail": "Build interaction settlement failed: " + cause
                           + ". Reset is required before starting or resuming.",
                           "last_error": cause + "; Return to the last safe position timed out; Advisor unavailable after deterministic recovery: Companion request unavailable",
                           "retained_extension": {"unicode": "layer \u2192 next \u5c42"}}
        receipt = {"captured_at": self.stamp(-130), "mode": "FLIGHT_ORDINARY_WAITING_CONFIRMATION",
                   "target": {**self.request["target"], "expected_block": "minecraft:dirt", "actual_block": "minecraft:dirt", "chunk_received": True},
                   "prediction_pending": True, "material": "dirt", "inventory_before": 94,
                   "inventory_now": 93, "result": "UNCERTAIN", "age_ticks": 100, "budget_ticks": 100,
                   "world_matches": True, "owned_mining": False, "manager_breaking": False}
        self.evidence = {"ok": True, "fresh": True, "connection": "online", "observation": {
            "state": "PAUSED", "world_connected": True, "context_matches": True,
            "updated_at": self.stamp(-120), "plan_id": self.request["plan_id"], "phase": "ORDINARY_BLOCKS",
            "material_ledger": {"consumed": {"dirt": 6210}, "withdrawn": {"dirt": 2464}},
            "inventory": {"available": True, "main_material_totals": {"dirt": 93}},
            "moss_deposit": {"available": True, "active": False, "pending": False, "truncated": False},
            "material_shop": {"available": True, "active": False, "pending": False, "truncated": False},
            "execution": {"available": True, "truncated": False, "receipt": None, "owned_mining": False, "manager_breaking": False,
                          "last_receipt": receipt, "last_failure": deepcopy(receipt)}}}
        self.probe = {"version": 1, "request": {}, "request_sha256": "", "request_id": self.operation,
                      "session_id": self.session, "session_started_at": self.stamp(-40),
                      "process_started_at": self.stamp(-50), "connection_started_at": self.stamp(-30),
                      "captured_at": self.stamp(-10), "supervisor_state": "PAUSED", "automation_idle": True,
                      "pending_transactions": False, "connected_world": True, "player_handler": True,
                      "cursor_empty": True, "player_uuid": self.request["player_uuid"],
                      "current_run_context": self.context, "saved_run_context": self.context,
                      "checkpoint_sha256": "", "checkpoint_matches": True, "plan_id": self.request["plan_id"],
                      "plan_matches": True, "context_matches": True, "player_matches": True,
                      "target": {**self.request["target"], "expected_block": "minecraft:dirt", "actual_block": "minecraft:dirt",
                                 "chunk_received": True, "prediction_pending": False},
                      "actual_inventory_total": 93, "server_inventory_complete": True, "server_inventory_all_match": True,
                      "observer_epoch": self.epoch, "observer_sequence": 36, "context_generation": 1,
                      "available": True, "unavailable_reason": "", "slots": []}
        for slot in range(36):
            count = 61 if slot == 8 else 32 if slot == 9 else 0
            item = "minecraft:dirt" if count else "minecraft:air"
            self.probe["slots"].append({"slot": slot, "received": True, "matches_current": True,
                                        "current_item_id": item, "current_count": count, "server_item_id": item,
                                        "server_count": count, "observer_epoch": self.epoch, "sequence": slot + 1})
        self.write(self.state / "run-context.json", {"schemaVersion": 1, "worldIdentityHash": self.context["world_identity_hash"],
                                                      "dimension": self.context["dimension"]})
        self.write(self.state / "settings.json", {"preserved": True})
        self.write(self.state / "depots.json", {"stock": 3392})
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
                                  self.probe_path, apply=apply, process_check=process_check,
                                  now=lambda: now or self.now)

    @property
    def transaction_dir(self):
        return self.root / "runtime/placement-reconciliations" / self.operation

    def assert_unchanged(self):
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())

    def assert_refused(self):
        with self.assertRaises((ValueError, TypeError, KeyError)):
            self.run_recovery(apply=True)
        self.assert_unchanged()

    def test_default_dry_run_is_read_only_and_reports_one_credit(self):
        paths_before = set(self.root.rglob("*"))
        result = self.run_recovery()
        self.assertEqual("dry_run", result["status"])
        self.assertEqual((6210, 6211), (result["consumed_before"], result["consumed_after"]))
        self.assertEqual(paths_before, set(self.root.rglob("*")))
        self.assert_unchanged()

    def test_apply_preserves_all_other_fields_and_exact_backup(self):
        state_before = recovery._UPDATER.state_snapshot(self.root, self.state)
        checks = []
        result = self.run_recovery(apply=True, process_check=lambda: checks.append(True))
        self.assertEqual("applied", result["status"])
        self.assertGreaterEqual(len(checks), 4)
        after = json.loads(self.checkpoint_path.read_bytes())
        expected = deepcopy(self.checkpoint)
        expected["consumed_materials"]["dirt"] = 6211
        expected.update(reconciliation_required=False, reconciliation_detail="", last_error="")
        self.assertEqual(expected, after)
        self.assertEqual(self.before, (self.transaction_dir / "checkpoint.before.json").read_bytes())
        self.assertEqual(result["checkpoint_after_sha256"], recovery.sha256(self.checkpoint_path.read_bytes()))
        self.assertEqual("committed", json.loads((self.transaction_dir / "transaction.json").read_bytes())["status"])
        self.assertEqual({**state_before, "checkpoint.json": self.checkpoint_path.read_bytes()},
                         recovery._UPDATER.state_snapshot(self.root, self.state))

    def test_replay_has_no_second_credit_even_after_probe_ages(self):
        self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        result = self.run_recovery(apply=True, now=self.now + timedelta(hours=1))
        self.assertEqual("already_applied", result["status"])
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_fresh_idle_probe_can_reconcile_its_paused_saved_checkpoint(self):
        self.probe["supervisor_state"] = "IDLE"
        self.write(self.probe_path, self.probe)
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])
        self.assertEqual("PAUSED", json.loads(self.checkpoint_path.read_bytes())["state"])

    def test_prepared_record_is_durable_before_checkpoint_atomic_write(self):
        real_write = recovery.atomic_bytes
        def inspect_write(path, data):
            if path == self.checkpoint_path:
                record = json.loads((self.transaction_dir / "transaction.json").read_bytes())
                self.assertEqual("prepared", record["status"])
                self.assertEqual(recovery.sha256(data), record["after_sha256"])
                self.assertEqual(self.before, (self.transaction_dir / "checkpoint.before.json").read_bytes())
            real_write(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=inspect_write):
            self.run_recovery(apply=True)

    def test_atomic_checkpoint_failure_can_resume_prepared_transaction(self):
        real_write = recovery.atomic_bytes
        def fail_checkpoint(path, data):
            if path == self.checkpoint_path:
                raise OSError("fixture atomic replacement failure")
            real_write(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail_checkpoint):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        self.assert_unchanged()
        self.assertEqual("prepared", json.loads((self.transaction_dir / "transaction.json").read_bytes())["status"])
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])

    def test_crash_after_checkpoint_write_replays_without_recredit(self):
        real_write = recovery.atomic_bytes
        def fail_commit(path, data):
            if path.name == "transaction.json" and json.loads(data)["status"] == "committed":
                raise OSError("fixture crash after checkpoint commit")
            real_write(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail_commit):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        self.assertEqual(6211, json.loads(after)["consumed_materials"]["dirt"])
        self.assertEqual("already_applied", self.run_recovery(apply=True, now=self.now + timedelta(hours=1))["status"])
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_stale_prepared_before_write_does_not_bypass_freshness(self):
        real_write = recovery.atomic_bytes
        def fail_checkpoint(path, data):
            if path == self.checkpoint_path:
                raise OSError("not written")
            real_write(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail_checkpoint):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        with self.assertRaisesRegex(ValueError, "stale"):
            self.run_recovery(apply=True, now=self.now + timedelta(minutes=6))
        self.assert_unchanged()

    def test_no_spend_or_rejected_target_is_not_supported(self):
        for mutation in (lambda: self.probe.update(actual_inventory_total=94),
                         lambda: self.probe["target"].update(actual_block="minecraft:air")):
            with self.subTest(mutation=mutation):
                saved = deepcopy(self.probe)
                mutation()
                self.write(self.probe_path, self.probe)
                self.assert_refused()
                self.probe = saved

    def test_probe_boolean_guards_and_current_bindings_fail_closed(self):
        original = deepcopy(self.probe)
        changes = {"available": False, "automation_idle": False, "connected_world": False,
                   "player_handler": False, "cursor_empty": False, "checkpoint_matches": False,
                   "plan_matches": False, "context_matches": False, "player_matches": False,
                   "server_inventory_complete": False, "server_inventory_all_match": False,
                   "pending_transactions": True, "supervisor_state": "BUILDING", "unavailable_reason": "missing",
                   "player_uuid": self.session, "plan_id": "sha256:" + "c" * 64,
                   "checkpoint_sha256": "c" * 64, "request_sha256": "d" * 64, "request_id": self.session,
                   "current_run_context": {**self.context, "dimension": "minecraft:the_nether"},
                   "saved_run_context": {**self.context, "world_identity_hash": "sha256:" + "d" * 64}}
        for key, value in changes.items():
            with self.subTest(key=key):
                self.probe = {**deepcopy(original), key: value}
                self.write(self.probe_path, self.probe)
                self.assert_refused()

    def test_target_prediction_received_and_exact_coordinates_are_required(self):
        for key, value in {"prediction_pending": True, "chunk_received": False, "x": 8196,
                           "expected_block": "minecraft:glowstone"}.items():
            with self.subTest(key=key):
                changed = deepcopy(self.probe)
                changed["target"][key] = value
                self.write(self.probe_path, changed)
                self.assert_refused()

    def test_every_inventory_slot_needs_current_context_packet_match(self):
        for key, value in {"received": False, "matches_current": False, "observer_epoch": self.session,
                           "sequence": 0, "server_count": 1, "server_item_id": "minecraft:dirt",
                           "current_count": True}.items():
            with self.subTest(key=key):
                changed = deepcopy(self.probe)
                changed["slots"][0][key] = value
                self.write(self.probe_path, changed)
                self.assert_refused()
        for slots in (self.probe["slots"][:-1], [self.probe["slots"][0]] * 36):
            self.write(self.probe_path, {**self.probe, "slots": slots})
            self.assert_refused()

    def test_inventory_total_is_independently_computed(self):
        self.probe["slots"][8].update(current_count=60, server_count=60)
        self.write(self.probe_path, self.probe)
        self.assert_refused()

    def test_new_process_session_connection_and_fresh_capture_are_required(self):
        for key, value in {"process_started_at": self.stamp(-70), "session_started_at": self.stamp(-70),
                           "connection_started_at": self.stamp(-70), "captured_at": self.stamp(1)}.items():
            with self.subTest(key=key):
                self.write(self.probe_path, {**self.probe, key: value})
                self.assert_refused()
        self.write(self.probe_path, self.probe)
        with self.assertRaisesRegex(ValueError, "stale"):
            self.run_recovery(apply=True, now=self.now + timedelta(minutes=5))
        self.assert_unchanged()

    def test_original_evidence_hash_encoding_and_duplicate_json_keys_are_checked(self):
        self.evidence_path.write_bytes(self.evidence_path.read_bytes() + b"\n")
        self.assert_refused()
        self.refresh()
        self.request_path.write_bytes(b'{"version":1,"version":1}')
        self.assert_refused()
        self.refresh()
        self.probe_path.write_bytes(b'{"invalid":"\xff"}')
        self.assert_refused()

    def test_exact_checkpoint_hash_detects_even_whitespace_changes(self):
        self.checkpoint_path.write_bytes(self.before + b"\n")
        with self.assertRaisesRegex(ValueError, "checkpoint SHA256"):
            self.run_recovery(apply=True)

    def test_original_receipt_and_ledgers_must_match(self):
        mutations = (lambda e: e["observation"]["material_ledger"]["consumed"].update(dirt=6211),
                     lambda e: e["observation"]["execution"]["last_failure"].update(result="CONFIRMED"),
                     lambda e: e["observation"]["execution"].update(receipt={"pending": True}),
                     lambda e: e["observation"]["inventory"]["main_material_totals"].update(dirt=94),
                     lambda e: e["observation"]["execution"].update(available=False),
                     lambda e: e["observation"]["execution"].update(truncated=True),
                     lambda e: e["observation"]["moss_deposit"].update(pending=True),
                     lambda e: e["observation"]["material_shop"].update(active=True))
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                changed = deepcopy(self.evidence)
                mutate(changed)
                self.write(self.evidence_path, changed)
                self.evidence_hash = recovery.sha256(self.evidence_path.read_bytes())
                self.assert_refused()

    def test_foreign_or_additional_reconciliation_causes_refuse(self):
        original = deepcopy(self.checkpoint)
        for key, value in {"reconciliation_detail": original["reconciliation_detail"] + " Existing reconciliation issue: depot",
                           "last_error": original["last_error"] + "; another error", "withdrawal_in_flight": True,
                           "state": "BUILDING", "phase": "FARMLAND"}.items():
            with self.subTest(key=key):
                self.checkpoint = {**deepcopy(original), key: value}
                self.refresh()
                self.assert_refused()

    def test_auxiliary_pending_and_malformed_journals_refuse(self):
        cases = {"material-purchase.json": {"version": 1, "journal": {"stage": "PENDING"}},
                 "moss-deposit.json": {"version": 1, "journal": {"stage": "PENDING", "foreign_plan": True}},
                 "hoe-repair.json": {"version": 1, "journal": {"confirmed": False}},
                 "temporary-support.json": {"version": 2, "journal": {"seedStage": "PLACE_INTENT"}}}
        for name, value in cases.items():
            with self.subTest(name=name):
                path = self.state / name
                self.write(path, value)
                self.assert_refused()
                path.unlink()
        self.write(self.state / "builds/foreign/hoe-repair.json", {"version": 1, "journal": {"confirmed": False}})
        self.assert_refused()

    def test_confirmed_journals_are_preserved(self):
        values = {"moss-deposit.json": {"version": 1, "journal": {"operationId": self.session,
                    "stage": "CONFIRMED", "before": {"context": {}}, "receipt": {"context": {}}}},
                  "hoe-repair.json": {"version": 1, "journal": {"operationId": self.session,
                    "confirmed": True, "context": {}, "before": {}}},
                  "temporary-support.json": {"version": 2, "journal": {"cleanupRequested": True,
                    "seedStage": "CONFIRMED", "cells": [{"stage": "REMOVED"}, {"stage": "REMOVED"}],
                    "plannedCredit": {"id": "old"}, "creditAcknowledged": True}}}
        for name, value in values.items():
            self.write(self.state / name, value)
        self.run_recovery(apply=True)
        for name, value in values.items():
            self.assertEqual(value, json.loads((self.state / name).read_bytes()))

    def confirmed_disposal(self):
        empty = {"fingerprint": "0" * 64, "itemId": "minecraft:air", "count": 0,
                 "maxCount": 0, "empty": True, "plainPickup": False}
        stone = {"fingerprint": "1" * 64, "itemId": "minecraft:stone", "count": 64,
                 "maxCount": 64, "empty": False, "plainPickup": False}
        pickup = {"fingerprint": "2" * 64, "itemId": "minecraft:pumpkin_seeds", "count": 64,
                  "maxCount": 64, "empty": False, "plainPickup": True}
        def slot(handler, inventory, stack):
            return {"handlerSlot": handler, "inventoryIndex": inventory, "stack": deepcopy(stack),
                    "canTake": True, "insertion": {"minecraft:pumpkin_seeds": {"allowed": False, "limit": 0}}}
        context = {"worldIdentityHash": self.context["world_identity_hash"], "dimension": self.context["dimension"],
                   "planId": self.request["plan_id"], "depotId": "depot-001", "depotX": 8192, "depotY": -62, "depotZ": -26976}
        stamp = {"observerEpoch": self.epoch, "contextGeneration": 1, "openGeneration": 1,
                 "fullSequence": 10, "syncId": 1, "revision": 0}
        slots = {"chest": [slot(index, index, stone) for index in range(27)],
                 "main": [slot(27 + (27 + index if index < 9 else index - 9), index, pickup if index == 0 else empty)
                          for index in range(36)],
                 "cursor": deepcopy(empty), "offhand": deepcopy(empty), "armor": [deepcopy(empty) for _ in range(4)]}
        before = {"context": context, "stamp": stamp, "slots": slots}
        after = deepcopy(before)
        after["stamp"].update(openGeneration=2, fullSequence=11, syncId=2)
        after["slots"]["main"][0]["stack"] = deepcopy(empty)
        return {"version": 1, "journal": {"operationId": self.session, "playerUuid": self.request["player_uuid"], "before": before,
                "sourceMainIndex": 0, "site": {"feet": {"x": 8148, "y": -52, "z": -26976}, "bottomY": -64},
                "storage": {"chests": [{"context": deepcopy(context), "stamp": deepcopy(stamp)}],
                            "itemId": "minecraft:pumpkin_seeds", "oldestAgeNanos": 1_000_000_000},
                "stage": "CONFIRMED", "reconciliationBarrier": None, "receipt": after}}

    def operator_reconciled_disposal(self):
        value = self.confirmed_disposal()
        value["version"] = 3
        journal = value["journal"]
        journal["stage"] = "OPERATOR_RECONCILED"
        journal["storage"]["mode"] = "DIRECT"
        hoe = {"fingerprint": "a" * 64, "itemId": "minecraft:diamond_hoe", "count": 1,
               "maxCount": 1, "empty": False, "plainPickup": False}
        journal["before"]["slots"]["main"][3]["stack"] = deepcopy(hoe)
        journal["receipt"]["slots"]["main"][3]["stack"] = {**hoe, "fingerprint": "b" * 64}
        journal["before"]["slots"]["main"][8]["stack"] = deepcopy(journal["before"]["slots"]["main"][0]["stack"])
        first = deepcopy(journal["receipt"])
        journal["receipt"]["stamp"].update(openGeneration=3, fullSequence=12, syncId=3)
        journal["operatorRecovery"] = {"request": {
            "operationId": journal["operationId"], "playerUuid": journal["playerUuid"],
            "planId": journal["before"]["context"]["planId"], "acknowledgedAt": "2026-09-18T18:45:00Z",
            "acknowledgement": "CLEARED_SURPLUS_AND_REPAIRED_LISTED_HOES", "repairedMainSlots": [3]},
            "firstReceipt": first}
        return value

    def test_operator_disposal_requires_acknowledged_scope_and_two_stable_receipts(self):
        value = self.operator_reconciled_disposal()
        self.assertTrue(recovery.settled_disposal(value))
        recovery.validate_journals({"surplus-disposal.json": recovery.encoded(value)})
        mutations = [
            lambda j: j.update(stage="PENDING"),
            lambda j: j.update(stage="CONFIRMED"),
            lambda j: j.update(operatorRecovery=None),
            lambda j: j["operatorRecovery"]["request"].update(acknowledgement="assumed"),
            lambda j: j["operatorRecovery"]["request"].update(acknowledgedAt="invalid"),
            lambda j: j["operatorRecovery"]["request"].update(operationId="different"),
            lambda j: j["operatorRecovery"]["request"].update(repairedMainSlots=[]),
            lambda j: j["operatorRecovery"]["request"].update(repairedMainSlots=[0, 3]),
            lambda j: j["operatorRecovery"]["request"].update(repairedMainSlots=[3, 3]),
            lambda j: j["operatorRecovery"]["firstReceipt"]["stamp"].update(openGeneration=3),
            lambda j: j["operatorRecovery"]["firstReceipt"]["context"].update(depotId="foreign"),
            lambda j: j["operatorRecovery"]["firstReceipt"]["slots"]["main"][3]["stack"].update(fingerprint="c" * 64),
            lambda j: j["receipt"]["slots"]["main"][0]["stack"].update(empty=False),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                changed = deepcopy(value)
                mutate(changed["journal"])
                self.assertFalse(recovery.settled_disposal(changed))

    def test_operator_disposal_protects_unlisted_items_and_repaired_tool_type(self):
        for slot, item in ((1, "minecraft:tripwire_hook"), (4, "minecraft:glowstone"), (7, "minecraft:diamond_axe")):
            with self.subTest(item=item):
                value = self.operator_reconciled_disposal()
                prior = value["journal"]["before"]["slots"]["main"][slot]["stack"]
                prior.update(itemId=item, count=1, maxCount=1, empty=False, fingerprint="c" * 64)
                self.assertFalse(recovery.settled_disposal(value))
        value = self.operator_reconciled_disposal()
        for observed in (value["journal"]["receipt"], value["journal"]["operatorRecovery"]["firstReceipt"]):
            observed["slots"]["main"][3]["stack"].update(itemId="minecraft:iron_hoe")
        self.assertFalse(recovery.settled_disposal(value))

    def test_operator_disposal_new_session_requires_first_receipt_as_barrier(self):
        value = self.operator_reconciled_disposal()
        journal = value["journal"]
        for observed in (journal["receipt"], journal["operatorRecovery"]["firstReceipt"]):
            observed["stamp"].update(observerEpoch="22222222-2222-4222-8222-222222222222")
        self.assertFalse(recovery.settled_disposal(value))
        journal["reconciliationBarrier"] = deepcopy(journal["operatorRecovery"]["firstReceipt"]["stamp"])
        self.assertTrue(recovery.settled_disposal(value))
        journal["reconciliationBarrier"]["fullSequence"] -= 1
        self.assertFalse(recovery.settled_disposal(value))

    def test_disposal_unconfirmed_unknown_corrupt_and_namespaced_journals_refuse(self):
        value = self.confirmed_disposal()
        for stage in ("PENDING", "FAILED", "UNKNOWN", None):
            with self.subTest(stage=stage):
                changed = deepcopy(value)
                changed["journal"]["stage"] = stage
                self.write(self.state / "surplus-disposal.json", changed)
                self.assert_refused()
        path = self.state / "surplus-disposal.json"
        for raw in (b'{"version":1,"journal":', recovery.encoded({"version": 1, "journal": {"stage": "CONFIRMED"}}),
                    recovery.encoded({**value, "version": 2}), recovery.encoded({**value, "version": True}),
                    recovery.encoded(value) + b" " * 262_144):
            with self.subTest(size=len(raw)):
                path.write_bytes(raw)
                self.assert_refused()
        path.unlink()
        value["journal"]["stage"] = "PENDING"
        self.write(self.state / "builds/foreign/surplus-disposal.json", value)
        self.assert_refused()

    def test_disposal_receipt_must_match_exact_context_source_and_protected_slots(self):
        mutations = [
            lambda j: j.update(receipt=None),
            lambda j: j.update(extra="unknown field"),
            lambda j: j.update(sourceMainIndex=True),
            lambda j: j["receipt"]["context"].update(depotId="different-chest"),
            lambda j: j["receipt"]["stamp"].update(openGeneration=1),
            lambda j: j["receipt"]["stamp"].update(fullSequence=10),
            lambda j: j["receipt"]["slots"]["main"][0].update(stack=deepcopy(j["before"]["slots"]["main"][0]["stack"])),
            lambda j: j["receipt"]["slots"]["main"][1]["stack"].update(fingerprint="3" * 64),
            lambda j: j["receipt"]["slots"]["offhand"].update(fingerprint="3" * 64),
            lambda j: j["receipt"]["slots"]["armor"][2].update(fingerprint="3" * 64),
            lambda j: j["before"]["slots"]["main"][0]["stack"].update(plainPickup=False),
            lambda j: j["receipt"]["slots"]["main"][1].update(handlerSlot=27),
            lambda j: j["receipt"]["slots"].update(armor=[]),
            lambda j: j["storage"].update(chests=[]),
            lambda j: j["storage"]["chests"].append(deepcopy(j["storage"]["chests"][0])),
            lambda j: j["storage"]["chests"][0]["context"].update(planId="foreign-plan"),
            lambda j: j["storage"].update(oldestAgeNanos=300_000_000_001),
            lambda j: j["site"].update(bottomY=-40),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                value = self.confirmed_disposal()
                mutate(value["journal"])
                self.write(self.state / "surplus-disposal.json", value)
                self.assert_refused()

    def test_disposal_requires_canonical_persisted_player_identity(self):
        for player in (None, "not-a-player", self.request["player_uuid"].upper(), 42):
            with self.subTest(player=player):
                value = self.confirmed_disposal()
                value["journal"]["playerUuid"] = player
                self.write(self.state / "surplus-disposal.json", value)
                self.assert_refused()
        value = self.confirmed_disposal()
        del value["journal"]["playerUuid"]
        self.write(self.state / "surplus-disposal.json", value)
        self.assert_refused()

    def test_version_two_direct_and_storage_receipts_are_recognized(self):
        for mode in ("DIRECT", "STORAGE_FULL"):
            value = self.confirmed_disposal()
            value["version"] = 2
            value["journal"]["storage"]["mode"] = mode
            self.assertTrue(recovery.settled_disposal(value))
            self.write(self.state / "surplus-disposal.json", value)
            self.assertEqual("dry_run", self.run_recovery()["status"])

    def test_direct_disposal_still_requires_exact_confirmed_receipt(self):
        value = self.confirmed_disposal()
        value["version"] = 2
        value["journal"]["storage"]["mode"] = "DIRECT"
        mutations = [
            lambda j: j.update(stage="PENDING", receipt=None),
            lambda j: j["storage"].pop("mode"),
            lambda j: j["storage"]["chests"].append(deepcopy(j["storage"]["chests"][0])),
            lambda j: j["receipt"]["slots"]["main"][1]["stack"].update(fingerprint="3" * 64),
            lambda j: j["receipt"]["stamp"].update(openGeneration=1),
        ]
        for mutate in mutations:
            changed = deepcopy(value)
            mutate(changed["journal"])
            self.assertFalse(recovery.settled_disposal(changed))
        for mode in (None, "UNKNOWN", True, {}, []):
            changed = deepcopy(value)
            changed["journal"]["storage"]["mode"] = mode
            self.assertFalse(recovery.settled_disposal(changed))
        value["version"] = 1
        self.assertFalse(recovery.settled_disposal(value))

    def test_confirmed_disposal_is_preserved_and_hash_bound_on_apply_and_replay(self):
        path = self.state / "surplus-disposal.json"
        self.write(path, self.confirmed_disposal())
        original = path.read_bytes()
        self.assertEqual("dry_run", self.run_recovery()["status"])
        self.assertFalse(self.transaction_dir.exists())
        self.run_recovery(apply=True)
        self.assertEqual(original, path.read_bytes())
        transaction = json.loads((self.transaction_dir / "transaction.json").read_bytes())
        self.assertEqual(recovery.sha256(original), transaction["state_sha256"]["surplus-disposal.json"])
        self.assertEqual("already_applied", self.run_recovery(apply=True)["status"])
        after = self.checkpoint_path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(ValueError, "bound inputs changed"):
            self.run_recovery(apply=True)
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_confirmed_disposal_new_session_barrier_requires_a_later_reopened_receipt(self):
        value = self.confirmed_disposal()
        journal = value["journal"]
        barrier = deepcopy(journal["before"]["stamp"])
        barrier.update(observerEpoch=self.operation, contextGeneration=2, openGeneration=4, fullSequence=40)
        journal["reconciliationBarrier"] = barrier
        journal["receipt"]["stamp"] = {**barrier, "openGeneration": 5, "fullSequence": 41}
        self.write(self.state / "surplus-disposal.json", value)
        self.assertEqual("dry_run", self.run_recovery()["status"])
        journal["receipt"]["stamp"]["openGeneration"] = 4
        self.write(self.state / "surplus-disposal.json", value)
        self.assert_refused()
        value = self.confirmed_disposal()
        value["journal"]["reconciliationBarrier"] = deepcopy(value["journal"]["before"]["stamp"])
        self.write(self.state / "surplus-disposal.json", value)
        self.assert_refused()

    def test_disposal_change_after_preparation_still_fails_checkpoint_compare_and_swap(self):
        path = self.state / "surplus-disposal.json"
        value = self.confirmed_disposal()
        self.write(path, value)
        calls = 0
        def mutate():
            nonlocal calls
            calls += 1
            if calls == 3:
                value["journal"].update(stage="PENDING", receipt=None)
                self.write(path, value)
        with self.assertRaisesRegex(ValueError, "changed during preparation"):
            self.run_recovery(apply=True, process_check=mutate)
        self.assert_unchanged()

    def test_known_pickup_deposit_version_two_is_settled_but_unknown_versions_and_stages_refuse(self):
        path = self.state / "moss-deposit.json"
        value = {"version": 2, "journal": {"operationId": self.session, "stage": "CONFIRMED",
                                         "before": {"context": {}}, "receipt": {"context": {}}}}
        self.write(path, value)
        self.assertEqual("dry_run", self.run_recovery()["status"])
        for version, stage in ((3, "CONFIRMED"), (True, "CONFIRMED"), (2, "PENDING"), (2, "UNKNOWN")):
            value["version"], value["journal"]["stage"] = version, stage
            self.write(path, value)
            self.assert_refused()

    def test_process_guard_refusal_and_profile_lock_do_not_write(self):
        def running():
            raise ValueError("Stop Minecraft and the supervision runner")
        for apply in (False, True):
            with self.assertRaisesRegex(ValueError, "Stop Minecraft"):
                self.run_recovery(apply=apply, process_check=running)
            self.assert_unchanged()
        lock = self.root / "runtime/.mod-update.lock"
        lock.write_text("another writer", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            self.run_recovery(apply=True)
        self.assertEqual("another writer", lock.read_text(encoding="utf-8"))
        self.assert_unchanged()

    def test_inputs_are_rechecked_after_preparation(self):
        calls = 0
        def mutate_at_commit():
            nonlocal calls
            calls += 1
            if calls == 3:
                self.write(self.state / "settings.json", {"changed": True})
        with self.assertRaisesRegex(ValueError, "changed during preparation"):
            self.run_recovery(apply=True, process_check=mutate_at_commit)
        self.assert_unchanged()

    def test_prepared_inputs_cannot_be_replaced_and_committed_checkpoint_cannot_be_rolled_back(self):
        self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        self.probe_path.write_bytes(self.probe_path.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "bound inputs changed"):
            self.run_recovery(apply=True)
        self.assertEqual(after, self.checkpoint_path.read_bytes())
        self.write(self.probe_path, self.probe)
        self.checkpoint_path.write_bytes(self.before)
        with self.assertRaisesRegex(ValueError, "rolled back"):
            self.run_recovery(apply=True)
        self.assert_unchanged()

    def test_changed_saved_context_and_unfinished_other_transaction_refuse(self):
        context_path = self.state / "run-context.json"
        original = context_path.read_bytes()
        self.write(context_path, {"schemaVersion": 1, "worldIdentityHash": "sha256:" + "d" * 64,
                                  "dimension": self.context["dimension"]})
        self.assert_refused()
        context_path.write_bytes(original)
        self.write(self.transaction_dir.parent / self.session / "transaction.json", {"status": "prepared"})
        self.assert_refused()


if __name__ == "__main__":
    unittest.main()
