from __future__ import annotations

from copy import deepcopy
from datetime import timedelta
import json
import unittest

import test_structure_clearing_reconciliation as fixtures

recovery = fixtures.recovery


class StructureDisconnectReconciliationTests(unittest.TestCase):
    def test_structure_first_disconnect_recovery_preserves_schedule_and_accounting(self):
        self.checkpoint["schedule_id"] = "layers-v1-structure-first-deferred-planting"
        self.refresh()
        self.assertEqual(0, self.run_recovery(apply=True)["credit_quantity"])
        after = recovery.json_object(self.checkpoint_path.read_bytes())
        for key in ("schedule_id", "schedule_cursor", "consumed_materials", "withdrawn_materials"):
            self.assertEqual(self.checkpoint[key], after[key])

    Base = fixtures.StructureClearingReconciliationTests
    stamp = Base.stamp
    write = staticmethod(Base.write)
    refresh = Base.refresh
    run_recovery = Base.run_recovery
    transaction_dir = Base.transaction_dir
    assert_refused = Base.assert_refused
    reject_mutations = Base.reject_mutations

    def setUp(self):
        self.Base.setUp(self)
        self.checkpoint.update(reconciliation_detail=recovery.RECONCILIATION_DETAIL,
                               last_error="Historical failure remains preserved")
        receipt = deepcopy(self.observed["execution"]["last_receipt"])
        receipt.update(mode="SUSPENDED", result="WAITING", prediction_pending=None,
                       inventory_now=None, owned_mining=None, manager_breaking=None,
                       world_matches=False, age_ticks=0, selected_slot=None, selected_item=None,
                       breaking_position=None, error=recovery.DISCONNECT_CAUSE)
        receipt["target"].update(actual_block=None, chunk_received=None)
        selection = self.observed["execution"]["last_moss_tool_selection"]
        selection["captured_at"] = self.stamp(-131)
        selection["current_context_matches"] = False
        target = self.request["target"]
        self.observed.update(updated_at=self.stamp(-115), last_error="Disconnected; supervision paused safely.")
        self.observed["execution"].update(mode="SUSPENDED", last_receipt=receipt, last_failure=None,
            detail=f"Clearing {recovery.MOSS} only at planned replacement {target['x']}, {target['y']}, {target['z']}")
        self.observed["execution"]["target"]["actual_block"] = recovery.MOSS
        self.review = deepcopy(self.observed)
        self.review["updated_at"] = self.stamp(-110)
        self.disconnected = deepcopy(self.observed)
        self.disconnected.update(updated_at=self.stamp(-120), world_connected=False, context_matches=False)
        self.disconnected["execution"].update(available=False, target=None, owned_mining=None, manager_breaking=None)
        self.disconnected["execution"]["last_moss_tool_selection"]["current_context_matches"] = None
        self.disconnected["inventory"] = {"available": False}
        self.evidence = {"version": 1, "kind": recovery.STRUCTURE_DISCONNECT_KIND,
            "captured_at": self.stamp(-100), "reset_performed": False,
            **{name: {"ok": True, "fresh": True, "connection": "online", "observation": value}
               for name, value in (("disconnect", self.disconnected), ("rejoined", self.observed), ("review", self.review))}}
        self.refresh()

    def change_all_receipts(self, key, value):
        for observation in (self.disconnected, self.observed, self.review):
            observation["execution"]["last_receipt"][key] = value

    def change_all_selections(self, key, value):
        for observation in (self.disconnected, self.observed, self.review):
            observation["execution"]["last_moss_tool_selection"][key] = value

    def test_dry_run_apply_and_replay_preserve_all_progress_with_zero_credit(self):
        saved = recovery._UPDATER.state_snapshot(self.root, self.state)
        paths = set(self.root.rglob("*"))
        self.assertEqual("dry_run", self.run_recovery()["status"])
        self.assertEqual(paths, set(self.root.rglob("*")))
        result = self.run_recovery(apply=True)
        self.assertEqual(("applied", 0), (result["status"], result["credit_quantity"]))
        expected = {**self.checkpoint, "reconciliation_required": False, "reconciliation_detail": ""}
        self.assertEqual(expected, json.loads(self.checkpoint_path.read_bytes()))
        self.assertEqual({**saved, "checkpoint.json": self.checkpoint_path.read_bytes()},
                         recovery._UPDATER.state_snapshot(self.root, self.state))
        after = self.checkpoint_path.read_bytes()
        result = self.run_recovery(apply=True, now=self.now + timedelta(hours=1))
        self.assertEqual(("already_applied", 0), (result["status"], result["credit_quantity"]))
        self.assertEqual(after, self.checkpoint_path.read_bytes())

    def test_placement_or_other_suspended_interaction_cannot_be_reclassified(self):
        for key, value in {"mode": "FLIGHT_ORDINARY_WAITING_CONFIRMATION", "result": "UNCERTAIN",
            "material": "glowstone", "inventory_before": 1111, "inventory_now": 1112,
            "age_ticks": 1, "budget_ticks": 240, "world_matches": True, "prediction_pending": False,
            "selected_item": recovery.DIRT, "error": "different cause"}.items():
            with self.subTest(key=key):
                self.setUp()
                self.change_all_receipts(key, value)
                self.refresh()
                self.assert_refused()
        self.reject_mutations([
            ("placement", lambda: self.observed["execution"].update(detail="Placing dirt")),
            ("another target", lambda: self.review["execution"]["last_receipt"]["target"].update(x=1)),
            ("another error", lambda: self.checkpoint.update(reconciliation_detail="another cause")),
            ("withdrawal", lambda: self.checkpoint.update(withdrawal_in_flight=True)),
            ("repair", lambda: self.checkpoint.update(repair_chunk_index=2)),
            ("schedule", lambda: self.checkpoint.update(schedule_cursor=104)),
            ("reset", lambda: self.evidence.update(reset_performed=True)),
        ])

    def test_old_wrong_or_changed_tool_selection_is_refused(self):
        for key, value in {"captured_at": self.stamp(-132), "selected_slot": 5,
                           "outcome": "HOE", "selected_item": "minecraft:diamond_hoe",
                           "truncated": True, "error": "unavailable"}.items():
            with self.subTest(key=key):
                self.setUp()
                self.change_all_selections(key, value)
                self.refresh()
                self.assert_refused()
        self.review["execution"]["last_moss_tool_selection"]["target"]["x"] = 1
        self.refresh()
        self.assert_refused()

    def test_operator_pause_scope_ledgers_and_observation_order_are_preserved(self):
        self.reject_mutations([
            ("operator pause", lambda: self.disconnected["last_control"].update(request_id="operator-pause")),
            ("new control", lambda: self.review["last_control"].update(sequence=4)),
            ("new run", lambda: self.review.update(run_id=self.session)),
            ("wrong world", lambda: self.observed.update(context_matches=False)),
            ("wrong layer", lambda: self.review["current_layer"].update(stage="LIGHTING")),
            ("new credit", lambda: self.review["material_ledger"]["consumed"].update(dirt=12412)),
            ("stale observation", lambda: self.evidence["rejoined"].update(fresh=False)),
            ("same capture", lambda: self.review.update(updated_at=self.observed["updated_at"])),
            ("reversed capture", lambda: self.observed.update(updated_at=self.stamp(-125))),
            ("changed receipt", lambda: self.review["execution"]["last_receipt"].update(captured_at=self.stamp(-129))),
            ("changed binding", lambda: self.review["execution"]["last_moss_tool_selection"].update(current_context_matches=True)),
        ])

    def test_rejoined_dirt_must_be_plain_unspent_and_slot_stable(self):
        self.reject_mutations([
            ("custom", lambda: self.review["inventory"]["main_slots"][6].update(plain_default_components=False)),
            ("count", lambda: self.review["inventory"]["main_slots"][6].update(count=23)),
            ("missing slot", lambda: self.observed["inventory"]["main_slots"].pop()),
            ("duplicate slot", lambda: self.review["inventory"]["main_slots"][18].update(slot=19)),
            ("offhand", lambda: self.review["inventory"]["main_and_offhand_material_totals"].update(dirt=1113)),
            ("cursor", lambda: self.review["inventory"]["menu"]["cursor"].update(count=1)),
            ("hand", lambda: self.review["inventory"].update(selected_hotbar_slot=5)),
        ])

    def test_active_or_unavailable_transactions_and_mining_refuse(self):
        self.reject_mutations([
            ("storage", lambda: self.review["moss_deposit"].update(pending=True)),
            ("shop", lambda: self.disconnected["shop"].update(pending_stacks=1)),
            ("material shop", lambda: self.review["material_shop"].update(available=False)),
            ("disposal", lambda: self.observed["surplus_disposal"].update(pending=True)),
            ("depot", lambda: self.review["depots"].update(operation="WITHDRAW")),
            ("mining", lambda: self.observed["execution"].update(owned_mining=True)),
            ("other interaction", lambda: self.review["execution"].update(receipt={"pending": True})),
        ])

    def test_rejoined_and_fresh_process_must_retain_original_moss(self):
        self.reject_mutations([
            ("rejoin AIR", lambda: self.observed["execution"]["target"].update(actual_block=recovery.AIR)),
            ("unreceived", lambda: self.review["execution"]["target"].update(chunk_received=False)),
            ("fresh AIR", lambda: self.probe["target"].update(actual_block=recovery.AIR,
                                                actual_state={"block_id": recovery.AIR, "properties": {}})),
        ])

    test_fresh_process_world_and_all_inventory_receipts_are_required = Base.test_new_process_fresh_world_and_all_inventory_receipts_are_required
    test_probe_cannot_rearrange_same_total_between_slots = Base.test_probe_cannot_rearrange_same_total_between_slots
    test_interrupted_write_requires_fresh_evidence = Base.test_interrupted_write_stays_prepared_and_requires_fresh_evidence
    test_pending_journal_and_running_process_block_application = Base.test_pending_journal_and_running_process_block_application


if __name__ == "__main__":
    unittest.main()
