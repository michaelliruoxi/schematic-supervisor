from __future__ import annotations

from copy import deepcopy
import unittest

import test_structure_clearing_reconciliation as fixtures

recovery = fixtures.recovery


class StemClearingReconciliationTests(fixtures.StructureClearingReconciliationTests):
    def setUp(self):
        super().setUp()
        self.request.update(version=3, observed_block=recovery.AIR)
        self.request["target"]["y"] += 1
        self.checkpoint.update(repath_attempted=True, safe_return_attempted=True, advisor_attempted=True)
        cause = recovery.timeout_cause(self.request["target"])
        detail = "Build interaction settlement failed: " + cause + ". Reset is required before starting or resuming."
        self.checkpoint.update(last_error=cause, reconciliation_detail=detail)
        for observed in (self.observed, self.review):
            observed["last_error"] = detail + " Previous error: " + cause
            for key in ("last_receipt", "last_failure"):
                observed["execution"][key].update(mode="STEM_SWEEP_CONFIRMATION")
                observed["execution"][key]["target"].update(self.request["target"])
            observed["execution"]["target"].update(**self.request["target"], expected_block=recovery.AIR)
            # A stem attempt must not borrow unrelated Moss tool-selection diagnostics.
            observed["execution"]["last_moss_tool_selection"].update(outcome="HOE", selected_item="minecraft:diamond_hoe")
        self.evidence["kind"] = recovery.STEM_TIMEOUT_KIND
        self.probe["target"].update(**self.request["target"], observed_block=recovery.AIR,
                                   actual_block=recovery.AIR, actual_state={"block_id": recovery.AIR, "properties": {}})
        self.refresh()

    def test_stem_kind_cannot_reclassify_other_interactions_or_requests(self):
        self.reject_mutations([
            ("Moss request", lambda: self.request.update(version=2, observed_block=recovery.MOSS)),
            ("Moss incident", lambda: self.evidence.update(kind=recovery.STRUCTURE_TIMEOUT_KIND)),
            ("stem still present", lambda: self.probe["target"].update(actual_block="minecraft:pumpkin_stem",
                actual_state={"block_id": "minecraft:pumpkin_stem", "properties": {"age": "7"}})),
            ("pending current stem", lambda: self.review["execution"]["target"].update(actual_block="minecraft:melon_stem")),
            ("consumed credit", lambda: self.request.update(expected_inventory_after=1111)),
            ("different schedule", lambda: self.checkpoint.update(schedule_cursor=104)),
            ("planting enabled", lambda: self.checkpoint.update(planting_deferred=False)),
        ])
        for observed in (self.observed, self.review):
            for key in ("last_receipt", "last_failure"):
                observed["execution"][key]["mode"] = "FLIGHT_CLEARING_CONFIRMATION"
        self.refresh()
        self.assert_refused()

    def test_stem_receipt_keeps_its_exact_budget_and_two_distinct_reviews(self):
        self.reject_mutations([
            ("same review", lambda: self.review.update(updated_at=self.observed["updated_at"])),
            ("budget", lambda: self.observed["execution"]["last_receipt"].update(budget_ticks=240)),
            ("layer scope", lambda: self.review["current_layer"].update(chunk_index=7)),
            ("target above order", lambda: self.observed["current_layer"].update(y=self.request["target"]["y"]-2)),
        ])

    def test_recovery_retains_exhausted_deterministic_budgets_until_new_progress(self):
        result = self.run_recovery(apply=True)
        self.assertEqual(0, result["credit_quantity"])
        after = recovery.json_object(self.checkpoint_path.read_bytes())
        for key in ("repath_attempted", "safe_return_attempted", "advisor_attempted"):
            self.assertIs(after[key], True)
        self.assertEqual(self.checkpoint["schedule_cursor"], after["schedule_cursor"])


if __name__ == "__main__":
    unittest.main()
