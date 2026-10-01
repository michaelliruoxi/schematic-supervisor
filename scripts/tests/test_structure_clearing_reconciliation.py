from __future__ import annotations

from copy import deepcopy
from datetime import timedelta
import json
import unittest
from unittest.mock import patch

import test_clearing_reconciliation as fixtures

recovery = fixtures.recovery


class StructureClearingReconciliationTests(unittest.TestCase):
    stamp = fixtures.ClearingReconciliationTests.stamp
    write = staticmethod(fixtures.ClearingReconciliationTests.write)
    refresh = fixtures.ClearingReconciliationTests.refresh
    run_recovery = fixtures.ClearingReconciliationTests.run_recovery
    transaction_dir = fixtures.ClearingReconciliationTests.transaction_dir
    assert_refused = fixtures.ClearingReconciliationTests.assert_refused

    def setUp(self):
        fixtures.ClearingReconciliationTests.setUp(self)
        slots = {6: 24, **{i: 64 for i in range(19, 36)}}
        self.request.update(version=2, material="dirt", observed_block=recovery.MOSS, selected_slot=6,
                            inventory_before=1112, expected_inventory_after=1112,
                            replacement_slots=[{"slot": i, "count": n} for i, n in slots.items()])
        cause = recovery.timeout_cause(self.request["target"])
        detail = "Build interaction settlement failed: " + cause + ". Reset is required before starting or resuming."
        self.checkpoint.update(reconciliation_detail=detail, last_error=cause)
        receipt = deepcopy(self.live["execution"]["receipt"])
        receipt.update(mode="FLIGHT_CLEARING_CONFIRMATION", result="UNCERTAIN", prediction_pending=True,
                       owned_mining=False, manager_breaking=False, age_ticks=100, budget_ticks=100,
                       material="dirt", inventory_before=1112, inventory_now=1112,
                       selected_slot=6, selected_item=recovery.DIRT)
        receipt["target"]["actual_block"] = recovery.AIR
        self.observed.update(world_connected=True, context_matches=True, planting_deferred=True,
            last_error=detail + " Previous error: " + cause,
            last_control={"sequence": 3, "action": "PAUSE", "request_id": "agent-fixture"},
            depots={"operation": "NONE", "stage": "IDLE"}, shop={"active": False, "pending_stacks": 0},
            surplus_disposal={"active": False, "pending": False, "unavailable": False})
        self.observed["current_layer"]["stage"] = "STRUCTURE"
        self.observed["execution"] = {"available": True, "truncated": False, "mode": "FAILED", "receipt": None,
            "owned_mining": False, "manager_breaking": False, "last_receipt": receipt, "last_failure": deepcopy(receipt),
            "target": {**self.request["target"], "expected_block": recovery.DIRT, "actual_block": recovery.AIR,
                       "chunk_received": True},
            "last_moss_tool_selection": {"captured_at": self.stamp(-140), "outcome": "PLAIN_HAND",
                "target": deepcopy(self.request["target"]), "selected_slot": 6, "selected_item": recovery.DIRT,
                "truncated": False, "error": ""}}
        for key in ("moss_deposit", "material_shop"):
            self.observed[key] = {"available": True, "active": False, "pending": False, "truncated": False}
        self.observed["inventory"] = {"available": True, "selected_hotbar_slot": 6,
            "main_material_totals": {"dirt": 1112}, "main_and_offhand_material_totals": {"dirt": 1112},
            "main_slots": [{"slot": i, "item_id": recovery.DIRT if i in slots else recovery.AIR,
                "count": slots.get(i, 0), "plain_default_components": i in slots} for i in range(36)],
            "menu": {"open": False, "sync_id": 0, "cursor": {"count": 0}}}
        self.review = deepcopy(self.observed)
        self.review["updated_at"] = self.stamp(-110)
        self.evidence = {"version": 1, "kind": recovery.STRUCTURE_TIMEOUT_KIND, "captured_at": self.stamp(-100),
            "reset_performed": False,
            "original": {"ok": True, "fresh": True, "connection": "online", "observation": self.observed},
            "review": {"ok": True, "fresh": True, "connection": "online", "observation": self.review}}
        self.probe.update(selected_slot=6, actual_inventory_total=1112,
                          replacement_slots_plain=True, replacement_slots_match=True)
        self.probe["target"].update(observed_block=recovery.MOSS, actual_block=recovery.MOSS,
                                   actual_state={"block_id": recovery.MOSS, "properties": {}})
        for slot in self.probe["slots"]:
            count = slots.get(slot["slot"], 0)
            item = recovery.DIRT if count else recovery.AIR
            slot.update(current_count=count, server_count=count, current_item_id=item, server_item_id=item,
                        current_plain_replacement=bool(count))
        self.refresh()

    def reject_mutations(self, changes):
        for name, mutate in changes:
            with self.subTest(name=name):
                self.setUp()
                mutate()
                self.refresh()
                self.assert_refused()

    def test_structure_first_schedule_preserves_cursor_and_accounting_during_recovery(self):
        self.checkpoint["schedule_id"] = "layers-v1-structure-first-deferred-planting"
        self.refresh()
        result = self.run_recovery(apply=True)
        self.assertEqual(0, result["credit_quantity"])
        after = recovery.json_object(self.checkpoint_path.read_bytes())
        for key in ("schedule_id", "schedule_cursor", "consumed_materials", "withdrawn_materials"):
            self.assertEqual(self.checkpoint[key], after[key])

    def test_dry_run_and_apply_change_only_latch_with_zero_credit(self):
        saved = recovery._UPDATER.state_snapshot(self.root, self.state)
        paths = set(self.root.rglob("*"))
        self.assertEqual("dry_run", self.run_recovery()["status"])
        self.assertEqual(paths, set(self.root.rglob("*")))
        self.assertEqual(0, self.run_recovery(apply=True)["credit_quantity"])
        self.assertEqual({**self.checkpoint, "reconciliation_required": False, "reconciliation_detail": ""},
                         json.loads(self.checkpoint_path.read_bytes()))
        self.assertEqual({**saved, "checkpoint.json": self.checkpoint_path.read_bytes()},
                         recovery._UPDATER.state_snapshot(self.root, self.state))

    def test_fresh_received_air_can_be_settled_without_credit(self):
        self.probe["target"].update(actual_block=recovery.AIR, actual_state={"block_id": recovery.AIR, "properties": {}})
        self.refresh()
        self.assertEqual(0, self.run_recovery(apply=True)["credit_quantity"])

    def test_request_scope_and_exact_slot_quantities_are_strict(self):
        self.reject_mutations([
            ("version", lambda: self.request.update(version=1)),
            ("wrong block", lambda: self.request.update(observed_block=recovery.JACK)),
            ("wrong material", lambda: self.request.update(material="glowstone")),
            ("spent", lambda: self.request.update(expected_inventory_after=1111)),
            ("duplicate", lambda: self.request["replacement_slots"].append({"slot": 6, "count": 24})),
            ("count", lambda: self.request["replacement_slots"][0].update(count=25)),
            ("selected", lambda: self.request.update(selected_slot=5)),
            ("boolean", lambda: self.request["replacement_slots"][0].update(count=True)),
            ("extra", lambda: self.request["replacement_slots"][0].update(extra=True)),
        ])

    def test_original_must_be_one_zero_spend_clearing_timeout(self):
        for key, value in {"mode": "FLIGHT_ORDINARY_WAITING_CONFIRMATION", "result": "CONFIRMED",
            "prediction_pending": False, "world_matches": False, "owned_mining": True,
            "manager_breaking": True, "material": "glowstone", "inventory_before": 1113,
            "inventory_now": 1111, "age_ticks": 99, "selected_item": "minecraft:diamond_hoe"}.items():
            with self.subTest(key=key):
                self.setUp()
                for observed in (self.observed, self.review):
                    for kind in ("last_failure", "last_receipt"):
                        observed["execution"][kind][key] = value
                self.refresh()
                self.assert_refused()

    def test_changed_operator_control_scope_ledgers_and_context_refuse(self):
        self.reject_mutations([
            ("operator pause", lambda: self.observed["last_control"].update(request_id="operator-pause")),
            ("control", lambda: self.review["last_control"].update(sequence=4)),
            ("run", lambda: self.review.update(run_id=self.session)),
            ("world", lambda: self.review.update(context_matches=False)),
            ("layer", lambda: self.review["current_layer"].update(stage="LIGHTING")),
            ("credit", lambda: self.review["material_ledger"]["consumed"].update(dirt=12412)),
            ("reset", lambda: self.evidence.update(reset_performed=True)),
            ("cause", lambda: self.checkpoint.update(reconciliation_detail="another cause")),
            ("withdrawal", lambda: self.checkpoint.update(withdrawal_in_flight=True)),
            ("live operation", lambda: self.review["execution"].update(receipt={"pending": True})),
        ])

    def test_custom_moved_missing_or_changed_original_dirt_slots_refuse(self):
        self.reject_mutations([
            ("custom", lambda: self.review["inventory"]["main_slots"][6].update(plain_default_components=False)),
            ("count", lambda: self.review["inventory"]["main_slots"][6].update(count=23)),
            ("missing", lambda: self.review["inventory"]["main_slots"].pop()),
            ("duplicate", lambda: self.review["inventory"]["main_slots"][18].update(slot=19)),
            ("offhand", lambda: self.review["inventory"]["main_and_offhand_material_totals"].update(dirt=1113)),
            ("cursor", lambda: self.review["inventory"]["menu"]["cursor"].update(count=1)),
            ("hand", lambda: self.review["inventory"].update(selected_hotbar_slot=5)),
        ])

    def test_pending_shop_storage_disposal_or_depot_work_refuse(self):
        self.reject_mutations([
            ("storage", lambda: self.review["moss_deposit"].update(pending=True)),
            ("shop", lambda: self.review["shop"].update(pending_stacks=1)),
            ("material shop", lambda: self.review["material_shop"].update(active=True)),
            ("disposal", lambda: self.review["surplus_disposal"].update(pending=True)),
            ("depot", lambda: self.review["depots"].update(operation="WITHDRAW")),
        ])

    def test_new_process_fresh_world_and_all_inventory_receipts_are_required(self):
        self.reject_mutations([
            ("old process", lambda: self.probe.update(process_started_at=self.stamp(-61))),
            ("stale", lambda: self.probe.update(captured_at=self.stamp(-301))),
            ("prediction", lambda: self.probe["target"].update(prediction_pending=True)),
            ("target", lambda: self.probe["target"].update(actual_block=recovery.DIRT)),
            ("unreceived", lambda: self.probe["target"].update(chunk_received=False)),
            ("missing", lambda: self.probe["slots"].pop()),
            ("mismatch", lambda: self.probe["slots"][6].update(server_count=23)),
            ("custom", lambda: self.probe["slots"][6].update(current_plain_replacement=False)),
            ("substitution", lambda: self.probe.update(replacement_slots_match=False)),
            ("outside", lambda: self.probe.update(replacement_outside_main=True)),
            ("plan", lambda: self.probe.update(plan_target_matches=False)),
        ])

    def test_probe_cannot_rearrange_same_total_between_slots(self):
        self.probe["slots"][6].update(current_count=25, server_count=25)
        self.probe["slots"][19].update(current_count=63, server_count=63)
        self.refresh()
        self.assert_refused()

    def test_commit_replay_is_idempotent_and_preserves_the_error(self):
        self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        self.assertEqual("already_applied", self.run_recovery(apply=True, now=self.now + timedelta(hours=1))["status"])
        self.assertEqual(after, self.checkpoint_path.read_bytes())
        self.assertEqual(self.checkpoint["last_error"], json.loads(after)["last_error"])

    def test_interrupted_write_stays_prepared_and_requires_fresh_evidence(self):
        real = recovery.atomic_bytes
        def fail(path, data):
            if path == self.checkpoint_path:
                raise OSError("interrupted write")
            real(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())
        with self.assertRaisesRegex(ValueError, "stale"):
            self.run_recovery(apply=True, now=self.now + timedelta(minutes=6))
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])

    def test_pending_journal_and_running_process_block_application(self):
        self.write(self.state / "hoe-repair.json", {"version": 1, "journal": {"stage": "WAITING"}})
        self.assert_refused()
        def active():
            raise ValueError("active process")
        with self.assertRaisesRegex(ValueError, "active process"):
            self.run_recovery(process_check=active)


if __name__ == "__main__":
    unittest.main()
