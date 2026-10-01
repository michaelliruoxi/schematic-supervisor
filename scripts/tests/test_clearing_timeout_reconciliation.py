from __future__ import annotations

from copy import deepcopy
from datetime import timedelta
import json
import unittest
from unittest.mock import patch

import test_clearing_reconciliation as fixtures

recovery = fixtures.recovery


class ClearingTimeoutReconciliationTests(unittest.TestCase):
    def test_structure_first_lighting_recovery_keeps_schedule_and_ledger(self):
        self.checkpoint["schedule_id"] = "layers-v1-structure-first-deferred-planting"
        self.refresh()
        self.assertEqual(0, self.run_recovery(apply=True)["credit_quantity"])
        after = recovery.json_object(self.checkpoint_path.read_bytes())
        for key in ("schedule_id", "schedule_cursor", "consumed_materials", "withdrawn_materials"):
            self.assertEqual(self.checkpoint[key], after[key])

    stamp = fixtures.ClearingReconciliationTests.stamp
    write = staticmethod(fixtures.ClearingReconciliationTests.write)
    refresh = fixtures.ClearingReconciliationTests.refresh
    run_recovery = fixtures.ClearingReconciliationTests.run_recovery
    transaction_dir = fixtures.ClearingReconciliationTests.transaction_dir
    assert_refused = fixtures.ClearingReconciliationTests.assert_refused

    def setUp(self):
        fixtures.ClearingReconciliationTests.setUp(self)
        cause = recovery.timeout_cause(self.request["target"])
        detail = "Build interaction settlement failed: " + cause + ". Reset is required before starting or resuming."
        self.checkpoint.update(reconciliation_detail=detail, last_error=cause)
        receipt = deepcopy(self.live["execution"]["receipt"])
        receipt.update(mode="FLIGHT_CLEARING_CONFIRMATION", result="UNCERTAIN", prediction_pending=True,
                       owned_mining=False, manager_breaking=False, age_ticks=240,
                       selected_slot=7, selected_item="minecraft:diamond_axe")
        receipt["target"]["actual_block"] = recovery.AIR
        execution = {"available": False, "mode": "FAILED", "truncated": False, "receipt": None,
                     "last_receipt": receipt, "last_failure": deepcopy(receipt), "detail": cause,
                     "last_moss_tool_selection": {"captured_at": self.stamp(-140), "outcome": "AXE",
                        "target": deepcopy(self.request["target"]), "selected_slot": 7,
                        "selected_item": "minecraft:diamond_axe", "truncated": False, "error": ""}}
        self.observed.update(execution=execution, last_error=detail + " Previous error: " + cause,
                             last_control={"sequence": 3, "action": "PAUSE", "request_id": "agent-fixture"},
                             depots={"operation": "NONE", "stage": "IDLE"},
                             shop={"state": "IDLE", "active": False, "pending_stacks": 0},
                             surplus_disposal={"active": False, "pending": False, "unavailable": False})
        for key in ("moss_deposit", "material_shop"):
            self.observed[key] = {"available": True, "active": False, "pending": False, "truncated": False}
        self.rejoined = deepcopy(self.observed)
        self.rejoined.update(world_connected=True, context_matches=True, updated_at=self.stamp(-110))
        self.rejoined["execution"].update(available=True, owned_mining=False, manager_breaking=False,
            target={**self.request["target"], "expected_block": recovery.ITEM,
                    "actual_block": recovery.JACK, "chunk_received": True})
        self.rejoined["inventory"] = {"available": True,
            "main_material_totals": {"glowstone": 3}, "main_and_offhand_material_totals": {"glowstone": 3},
            "main_slots": [{"slot": 4, "item_id": recovery.ITEM, "count": 3, "plain_default_components": True}],
            "menu": {"open": False, "sync_id": 0, "cursor": {"count": 0}}}
        self.evidence = {"version": 1, "kind": recovery.TIMEOUT_KIND, "captured_at": self.stamp(-100),
            "reset_performed": False,
            "disconnect": {"ok": True, "fresh": True, "connection": "online", "observation": self.observed},
            "rejoined": {"ok": True, "fresh": True, "connection": "online", "observation": self.rejoined}}
        self.refresh()

    def reject_mutations(self, changes):
        for name, mutate in changes:
            with self.subTest(name=name):
                self.setUp()
                mutate()
                self.refresh()
                self.assert_refused()

    def test_dry_run_is_read_only_and_apply_preserves_everything_but_latch(self):
        saved = recovery._UPDATER.state_snapshot(self.root, self.state)
        paths = set(self.root.rglob("*"))
        self.assertEqual("dry_run", self.run_recovery()["status"])
        self.assertEqual(paths, set(self.root.rglob("*")))
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])
        after = json.loads(self.checkpoint_path.read_bytes())
        self.assertEqual({**self.checkpoint, "reconciliation_required": False, "reconciliation_detail": ""}, after)
        self.assertEqual({**saved, "checkpoint.json": self.checkpoint_path.read_bytes()},
                         recovery._UPDATER.state_snapshot(self.root, self.state))
        record = json.loads((self.transaction_dir / "transaction.json").read_bytes())
        self.assertEqual((recovery.TIMEOUT_KIND, "committed", 0), (record["kind"], record["status"], record["quantity"]))

    def test_probe_slot_is_replacement_stack_not_historical_axe_slot(self):
        self.assertNotEqual(self.request["selected_slot"], self.observed["execution"]["last_receipt"]["selected_slot"])
        self.assertEqual("dry_run", self.run_recovery()["status"])

    def test_fresh_air_has_no_placement_credit(self):
        self.probe["target"].update(actual_block=recovery.AIR, actual_state={"block_id": recovery.AIR, "properties": {}})
        self.refresh()
        self.assertEqual(0, self.run_recovery(apply=True)["credit_quantity"])

    def test_wrong_kind_reset_cause_and_checkpoint_work_are_refused(self):
        self.reject_mutations([
            ("kind", lambda: self.evidence.update(kind="unknown")),
            ("reset", lambda: self.evidence.update(reset_performed=True)),
            ("cause", lambda: self.checkpoint.update(reconciliation_detail=recovery.RECONCILIATION_DETAIL)),
            ("error", lambda: self.checkpoint.update(last_error="unrelated failure")),
            ("withdrawal", lambda: self.checkpoint.update(withdrawal_in_flight=True)),
            ("repair", lambda: self.checkpoint.update(repair_chunk_index=1)),
            ("schedule", lambda: self.checkpoint.update(schedule_cursor=104)),
            ("planting", lambda: self.checkpoint.update(planting_deferred=False)),
            ("restock", lambda: self.checkpoint.update(restock_requirement={"glowstone": 64})),
        ])

    def test_failure_must_be_same_zero_spend_axe_timeout_in_both_observations(self):
        changes = {"mode": "FLIGHT_ORDINARY_WAITING_CONFIRMATION", "result": "CONFIRMED",
                   "prediction_pending": False, "world_matches": False, "owned_mining": True,
                   "manager_breaking": True, "inventory_before": 4, "inventory_now": 2,
                   "age_ticks": 239, "budget_ticks": 241, "selected_item": "minecraft:diamond_hoe",
                   "material": "dirt", "error": "unrelated", "breaking_position": {"x": 0, "y": 0, "z": 0}}
        for key, value in changes.items():
            with self.subTest(key=key):
                self.setUp()
                for o in (self.observed, self.rejoined):
                    for kind in ("last_receipt", "last_failure"):
                        o["execution"][kind][key] = value
                self.refresh()
                self.assert_refused()
        self.setUp()
        self.rejoined["execution"]["last_failure"]["inventory_now"] = 2
        self.refresh()
        self.assert_refused()

    def test_changed_world_control_layer_ledger_or_selection_refuses(self):
        self.reject_mutations([
            ("run", lambda: self.rejoined.update(run_id=self.session)),
            ("context", lambda: self.rejoined.update(context_matches=False)),
            ("connection", lambda: self.rejoined.update(world_connected=False)),
            ("pause", lambda: self.rejoined["last_control"].update(sequence=4)),
            ("user pause", lambda: self.observed["last_control"].update(request_id="operator-pause")),
            ("resume", lambda: self.rejoined["last_control"].update(action="RESUME")),
            ("layer", lambda: self.rejoined["current_layer"].update(stage="STRUCTURE")),
            ("ledger", lambda: self.rejoined["material_ledger"]["consumed"].update(glowstone=58)),
            ("selection", lambda: self.observed["execution"]["last_moss_tool_selection"].update(outcome="HAND")),
            ("selection target", lambda: self.rejoined["execution"]["last_moss_tool_selection"].update(target={"x": 1})),
            ("failed target", lambda: self.rejoined["execution"]["target"].update(actual_block=recovery.ITEM)),
            ("reversed time", lambda: self.rejoined.update(updated_at=self.stamp(-121))),
        ])

    def test_ledger_balance_must_independently_equal_retained_inventory(self):
        self.checkpoint["withdrawn_materials"]["glowstone"] = 61
        for o in (self.observed, self.rejoined):
            o["material_ledger"]["withdrawn"]["glowstone"] = 61
        self.refresh()
        self.assert_refused()

    def test_unspent_plain_inventory_and_no_parallel_operation_are_required(self):
        self.reject_mutations([
            ("stack", lambda: self.rejoined["inventory"]["main_slots"][0].update(count=2)),
            ("custom", lambda: self.rejoined["inventory"]["main_slots"][0].update(plain_default_components=False)),
            ("cursor", lambda: self.rejoined["inventory"]["menu"]["cursor"].update(count=1)),
            ("offhand", lambda: self.rejoined["inventory"]["main_and_offhand_material_totals"].update(glowstone=4)),
            ("deposit", lambda: self.rejoined["moss_deposit"].update(pending=True)),
            ("purchase", lambda: self.observed["material_shop"].update(active=True)),
            ("shop", lambda: self.rejoined["shop"].update(pending_stacks=1)),
            ("depot", lambda: self.rejoined["depots"].update(operation="WITHDRAW")),
            ("disposal", lambda: self.observed["surplus_disposal"].update(pending=True)),
        ])

    def test_new_process_complete_receipts_and_received_target_remain_mandatory(self):
        self.reject_mutations([
            ("old process", lambda: self.probe.update(process_started_at=self.stamp(-61))),
            ("stale", lambda: self.probe.update(captured_at=self.stamp(-301))),
            ("prediction", lambda: self.probe["target"].update(prediction_pending=True)),
            ("unreceived", lambda: self.probe["target"].update(chunk_received=False)),
            ("missing slot", lambda: self.probe["slots"].pop()),
            ("mismatched receipt", lambda: self.probe["slots"][4].update(server_count=2)),
            ("unplanned", lambda: self.probe.update(plan_target_matches=False)),
            ("active", lambda: self.probe.update(automation_idle=False)),
        ])

    def test_commit_replay_does_not_credit_even_after_probe_ages(self):
        self.run_recovery(apply=True)
        after = self.checkpoint_path.read_bytes()
        report = self.run_recovery(apply=True, now=self.now + timedelta(hours=1))
        self.assertEqual(("already_applied", 0), (report["status"], report["credit_quantity"]))
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_interrupted_atomic_write_can_resume_but_not_with_stale_probe(self):
        real = recovery.atomic_bytes
        def fail(path, data):
            if path == self.checkpoint_path:
                self.assertEqual("prepared", json.loads((self.transaction_dir / "transaction.json").read_bytes())["status"])
                raise OSError("interrupted test write")
            real(path, data)
        with patch.object(recovery, "atomic_bytes", side_effect=fail):
            with self.assertRaises(OSError):
                self.run_recovery(apply=True)
        self.assertEqual(self.before, self.checkpoint_path.read_bytes())
        with self.assertRaisesRegex(ValueError, "stale"):
            self.run_recovery(apply=True, now=self.now + timedelta(minutes=6))
        self.assertEqual("applied", self.run_recovery(apply=True)["status"])

    def test_unfinished_journal_or_process_blocks_application(self):
        self.write(self.state / "hoe-repair.json", {"version": 1, "journal": {"stage": "WAITING"}})
        self.assert_refused()
        def active():
            raise ValueError("active process")
        with self.assertRaisesRegex(ValueError, "active process"):
            self.run_recovery(process_check=active)


if __name__ == "__main__":
    unittest.main()
