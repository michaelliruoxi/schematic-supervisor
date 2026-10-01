from __future__ import annotations

import unittest

from supervisor_companion.actions import RecoveryAction
from supervisor_companion.models import (
    DiagnosisDecision,
    Incident,
    PayloadError,
    StatusSnapshot,
    sanitize_json_value,
)


class StatusSnapshotTests(unittest.TestCase):
    def test_deferred_planting_scope_roundtrips_and_rejects_inconsistent_values(self) -> None:
        status = StatusSnapshot.from_mapping({"planting_deferred": True, "deferred_seed_cells": 156799})
        self.assertTrue(status.to_dict()["planting_deferred"])
        self.assertEqual(status.to_dict()["deferred_seed_cells"], 156799)
        for value in ({"planting_deferred": "true"}, {"deferred_seed_cells": 1},
                      {"planting_deferred": True, "deferred_seed_cells": -1},
                      {"planting_deferred": True, "deferred_seed_cells": 1_000_001}):
            with self.subTest(value=value), self.assertRaises(PayloadError):
                StatusSnapshot.from_mapping(value)

    def test_parses_full_status_and_calculates_missing(self) -> None:
        status = StatusSnapshot.from_mapping(
            {
                "current_chunk": {"index": 4, "total": 49, "x": -2, "z": 8},
                "phase": "PLANTING",
                "materials": {
                    "dirt": {"available": 10, "required": 14},
                    "food": 3,
                },
                "baritone_status": "Walking",
                "last_error": None,
            }
        )

        self.assertEqual(status.current_chunk.index, 4)
        self.assertEqual(status.current_chunk.x, -2)
        self.assertEqual(status.materials["dirt"].missing, 4)
        self.assertEqual(status.materials["food"].available, 3)
        self.assertIsNone(status.materials["food"].required)

    def test_defaults_are_safe_and_serializable(self) -> None:
        status = StatusSnapshot.from_mapping({})
        serialized = status.to_dict()
        self.assertEqual(serialized["phase"], "STOPPED")
        self.assertEqual(serialized["baritone_status"], "Unavailable")
        self.assertIsNone(serialized["current_chunk"])
        self.assertIsNone(serialized["current_layer"])
        self.assertEqual(serialized["materials"], {})

    def test_rectangular_plan_totals_survive_status_roundtrip(self) -> None:
        for total in (1, 6, 70, 1024):
            with self.subTest(total=total):
                chunk = {"index": total, "total": total, "x": -4, "z": 9}
                status = StatusSnapshot.from_mapping({"current_chunk": chunk, "phase": "VERIFY"})
                self.assertEqual(status.to_dict()["current_chunk"], chunk)

    def test_layer_progress_survives_status_roundtrip(self) -> None:
        layer = {
            "order": "LAYERS", "stage": "STRUCTURE", "index": 7, "total": 101,
            "y": -12, "chunk_index": 3, "chunk_total": 41,
        }
        status = StatusSnapshot.from_mapping({"current_layer": layer})
        self.assertEqual(status.current_layer.y, -12)
        self.assertEqual(status.current_layer.chunk_index, 3)
        self.assertEqual(status.to_dict()["current_layer"], layer)

    def test_finished_layers_allow_absent_y_and_empty_chunk_progress(self) -> None:
        layer = {
            "order": "LAYERS", "stage": "VERIFY", "index": 101, "total": 101,
            "y": None, "chunk_index": 0, "chunk_total": 0,
        }
        status = StatusSnapshot.from_mapping({"current_layer": layer})
        self.assertEqual(status.to_dict()["current_layer"], layer)

    def test_malformed_layer_progress_is_rejected(self) -> None:
        valid = {
            "order": "LAYERS", "stage": "STRUCTURE", "index": 7, "total": 101,
            "y": 80, "chunk_index": 3, "chunk_total": 41,
        }
        for update in ({"y": True}, {"index": -1}, {"index": 102},
                       {"chunk_index": 42}, {"chunk_total": -1}, {"stage": ""}):
            with self.subTest(update=update), self.assertRaises(PayloadError):
                StatusSnapshot.from_mapping({"current_layer": valid | update})
        with self.assertRaises(PayloadError):
            StatusSnapshot.from_mapping({"current_layer": []})

    def test_material_mapping_is_immutable(self) -> None:
        status = StatusSnapshot.from_mapping(
            {"materials": {"dirt": 1}}
        )
        with self.assertRaises(TypeError):
            status.materials["dirt"] = status.materials["dirt"]

    def test_rejects_zero_based_chunk(self) -> None:
        with self.assertRaisesRegex(PayloadError, "at least 1"):
            StatusSnapshot.from_mapping(
                {"current_chunk": {"index": 0, "total": 49}}
            )

    def test_rejects_chunk_index_above_total(self) -> None:
        with self.assertRaisesRegex(PayloadError, "must not exceed"):
            StatusSnapshot.from_mapping(
                {"current_chunk": {"index": 50, "total": 49}}
            )

    def test_rejects_negative_material_count(self) -> None:
        with self.assertRaisesRegex(PayloadError, "at least 0"):
            StatusSnapshot.from_mapping({"materials": {"dirt": -1}})

    def test_rejects_boolean_as_material_count(self) -> None:
        with self.assertRaises(PayloadError):
            StatusSnapshot.from_mapping({"materials": {"dirt": True}})


class IncidentTests(unittest.TestCase):
    def test_parses_structured_incident(self) -> None:
        incident = Incident.from_mapping(
            {
                "incident_id": "incident-1",
                "category": "NO_PROGRESS",
                "summary": "No progress",
                "attempted_recovery": ["WAIT_FOR_LAG", "RESTART_PATH"],
                "details": {
                    "server_lag_suspected": False,
                    "missing_materials": {"dirt": 9},
                },
            }
        )
        self.assertEqual(incident.incident_id, "incident-1")
        self.assertEqual(len(incident.attempted_recovery), 2)
        self.assertEqual(incident.details["missing_materials"]["dirt"], 9)

    def test_status_in_incident_is_validated(self) -> None:
        with self.assertRaises(PayloadError):
            Incident.from_mapping(
                {
                    "incident_id": "incident-1",
                    "category": "NO_PROGRESS",
                    "summary": "No progress",
                    "status": {"materials": []},
                }
            )

    def test_details_are_copied_from_input(self) -> None:
        source = {"missing_materials": {"dirt": 9}}
        incident = Incident.from_mapping(
            {
                "incident_id": "incident-1",
                "category": "MISSING_MATERIALS",
                "summary": "Missing dirt",
                "details": source,
            }
        )
        source["missing_materials"]["dirt"] = 0
        self.assertEqual(incident.details["missing_materials"]["dirt"], 9)

    def test_rejects_deeply_nested_details(self) -> None:
        value: object = "end"
        for index in range(8):
            value = {f"level{index}": value}
        with self.assertRaisesRegex(PayloadError, "nested too deeply"):
            sanitize_json_value(value)

    def test_rejects_non_json_detail(self) -> None:
        with self.assertRaises(PayloadError):
            Incident.from_mapping(
                {
                    "incident_id": "incident-1",
                    "category": "OTHER",
                    "summary": "Bad detail",
                    "details": {"object": object()},
                }
            )


class DiagnosisDecisionTests(unittest.TestCase):
    def test_all_recovery_actions_are_accepted(self) -> None:
        for action in RecoveryAction:
            with self.subTest(action=action):
                decision = DiagnosisDecision.from_provider_mapping(
                    {"action": action.value, "reason": "Constrained reason."},
                    source="test",
                )
                self.assertIs(decision.action, action)

    def test_action_matching_is_case_insensitive(self) -> None:
        decision = DiagnosisDecision.from_provider_mapping(
            {"action": "repath", "reason": "Try the path once."},
            source="test",
        )
        self.assertIs(decision.action, RecoveryAction.REPATH)

    def test_arbitrary_action_is_rejected(self) -> None:
        with self.assertRaisesRegex(PayloadError, "not allowlisted"):
            DiagnosisDecision.from_provider_mapping(
                {"action": "RUN_COMMAND", "reason": "Unsafe"},
                source="test",
            )

    def test_provider_reason_is_not_propagated(self) -> None:
        decision = DiagnosisDecision.from_provider_mapping(
            {
                "action": "WAIT",
                "reason": "Go to coordinates 1 2 3 and run a command.",
            },
            source="test",
        )
        self.assertNotIn("coordinates", decision.reason)
        self.assertNotIn("command", decision.reason)
        self.assertEqual(
            decision.reason,
            "Use the fixed wait interval, then resume deterministic checks.",
        )

    def test_pause_fallback_is_marked(self) -> None:
        decision = DiagnosisDecision.pause_fallback("Provider unavailable")
        self.assertIs(decision.action, RecoveryAction.PAUSE_AND_ALERT)
        self.assertTrue(decision.used_fallback)
        self.assertEqual(decision.source, "safe_fallback")


if __name__ == "__main__":
    unittest.main()
