from __future__ import annotations

import unittest

from supervisor_companion.progress import Unavailable, parse_progress
from supervisor_companion.ui_full import (LEDGER_NOTE, PACE_TOOLTIP, PLACED_NOTE, MaterialTable, material_table,
                                          safe_observation, status_details)
from test_progress import progress_payload

# A Start that found the structure, lights, and soil finished: the run itself has used only 980 seeds.
LEDGER = {"planned": {"dirt": 313600, "wheat_seeds": 156799, "glowstone": 12250, "birch_planks": 12543},
          "consumed": {"wheat_seeds": 980}, "withdrawn": {},
          "remaining_plan": {"dirt": 313600, "wheat_seeds": 155819, "glowstone": 12250, "birch_planks": 12543}}
STOCK = {"wheat_seeds": {"available": 236, "required": 236, "missing": 0},
         "hoe": {"available": 1, "required": 2, "missing": 1}}
COUNTS = {"dirt": {"planned": 313600, "done": 313600}, "wheat_seeds": {"planned": 156799, "done": 21504},
          "glowstone": {"planned": 12250, "done": 12250}, "birch_planks": {"planned": 12543, "done": 12543}}


def material_snapshot(progress, status="ok"):
    return {"observation": {"materials": STOCK, "material_ledger": LEDGER}, "progress": progress,
            "progress_status": status}


def rows_by_material(table):
    return {material: values for material, values, _short in table.rows}


class FullWindowTextTests(unittest.TestCase):
    def test_status_details_summarize_execution_player_and_world(self):
        text = status_details({
            "execution": {"mode": "FLIGHT_PLACEMENT", "detail": "Placing dirt",
                          "target": {"x": 1, "y": 2, "z": 3, "expected_block": "minecraft:dirt"}},
            "baritone_status": "Idle", "recovery_stage": "NONE", "verification_stage": "CHUNK",
            "world_connected": True, "context_matches": False,
            "player": {"x": 10, "y": -60, "z": 5, "health": 20, "hunger": 18, "flying": True},
            "inventory": {"available": True, "empty_main_slots": 7, "selected_hotbar_slot": 2},
        })
        self.assertIn("Execution: Flight placement", text)
        self.assertIn("Target: 1 / 2 / 3 · minecraft:dirt", text)
        self.assertIn("World: Connected · context mismatch", text)
        self.assertIn("Flying", text)
        self.assertIn("Empty inventory slots: 7", text)
        self.assertEqual(status_details({}), "No observation received yet.")

    def test_status_details_keep_what_the_old_overview_showed(self):
        lines = status_details({
            "last_message": "Placing ordinary blocks.", "phase": "ORDINARY_BLOCKS",
            "last_error": "Depot withdrawal failed: real chest stock does not cover the allocation at depot-014",
            "blockers": ["Join the target world before starting or resuming.", "", None],
            "planting_deferred": True, "deferred_seed_cells": 1234,
            "current_layer": {"order": "LAYERS", "stage": "STRUCTURE", "index": 7, "total": 101, "y": -12,
                              "chunk_index": 3, "chunk_total": 41},
            "current_chunk": {"index": 2, "total": 70},
            "verification_stage": "CHUNK", "stable_verification_passes": 2,
            "material_ledger": {"planned": {"minecraft:dirt": 5000, "minecraft:glowstone": 200},
                                "consumed": {"minecraft:dirt": 1200, "minecraft:glowstone": 34}},
            "inventory": {"available": False, "error": "Player inventory is unavailable."},
        }).splitlines()
        for line in ("Last message: Placing ordinary blocks.", "Phase: Ordinary blocks",
                     "Last error: Depot withdrawal failed: real chest stock does not cover the allocation at "
                     "depot-014",
                     "Blocker: Join the target world before starting or resuming.",
                     "Planting deferred: 1,234 seed cells.", "Layer: 7 of 101 · Y -12", "Chunk: 2 of 70",
                     "Verification: Chunk · 2 stable passes", "Material use: 1,234 / 5,200 planned",
                     "Player telemetry unavailable", "Player inventory is unavailable."):
            self.assertIn(line, lines)
        self.assertEqual(sum(line.startswith("Blocker:") for line in lines), 1)

    def test_status_details_leave_out_facts_the_mod_did_not_send(self):
        lines = status_details({"state": "IDLE", "planting_deferred": False, "deferred_seed_cells": 5,
                                "verification_stage": "NONE"}).splitlines()
        self.assertIn("Inventory telemetry unavailable", lines)
        self.assertIn("Verification: None", lines)
        for prefix in ("Last message", "Phase", "Last error", "Blocker", "Planting deferred", "Layer", "Chunk",
                       "Material use"):
            self.assertFalse([line for line in lines if line.startswith(prefix + ":")], prefix)

    def test_status_details_list_the_build_check_and_its_problems(self):
        lines = status_details({"build_check": {
            "status": "COMPLETE", "summary": "Build check (2.3 s): 49 of 49 chunks checked; 57% built.",
            "finished_at": "not a time",
            "problems": [{"kind": "EXTRA", "x": 8198, "y": -40, "z": -26920, "chunk": 12,
                          "expected": "minecraft:air", "actual": "minecraft:cobblestone"}, "junk", {"x": 1}]},
        }).splitlines()
        self.assertIn("Build check (2.3 s): 49 of 49 chunks checked; 57% built. (finished not a time)", lines)
        self.assertIn("  Needs attention: extra cobblestone at x 8198, y -40, z -26920 (chunk 12)", lines)
        self.assertEqual(sum(line.startswith("  Needs attention:") for line in lines), 1)
        without = status_details({"build_check": {"status": "RUNNING", "progress": 0.5}}).splitlines()
        self.assertFalse([line for line in without if "check" in line.lower()])

    def test_status_details_never_raise_on_unexpected_values(self):
        lines = status_details({
            "execution": {"mode": ["x"], "target": 5, "detail": {"a": 1}, "error": 0.5},
            "baritone_status": {"a": 1}, "shop": [1], "material_shop": {"active": True, "stage": None},
            "depots": {"operation": ["not", "hashable"]},
            "material_ledger": {"planned": {"a": 10 ** 400, "b": 1.5, "c": True, "d": float("nan")},
                                "consumed": "x"},
            "current_layer": {"index": "7", "total": None, "y": 10 ** 400}, "current_chunk": [1, 2],
            "blockers": "not a list", "last_message": {"nested": True}, "last_error": 42,
            "planting_deferred": "yes", "deferred_seed_cells": float("inf"), "phase": 7,
            "stable_verification_passes": True,
            "player": {"x": float("nan"), "y": 10 ** 400, "z": "z", "health": -float("inf"), "flying": 1},
            "inventory": {"available": "yes", "empty_main_slots": 10 ** 400, "selected_hotbar_slot": None},
        }).splitlines()
        self.assertTrue([line for line in lines if line.startswith("Depots: ")])
        for line in ("Material use: 0 / — planned", "Layer: — of — · Y —", "Planting deferred: — seed cells.",
                     "Player XYZ: — / — / — · Health: — · Hunger: — · Flying",
                     "Empty inventory slots: — · Selected hotbar slot: —"):
            self.assertIn(line, lines)
        for value in (None, [], ["x"], "text", 5, 1.5):
            self.assertEqual(status_details(value), "No observation received yet.")

    def test_materials_show_what_the_progress_counts_as_placed(self):
        table = material_table(material_snapshot(parse_progress(progress_payload(materials=COUNTS))))
        self.assertTrue(table.placed)
        self.assertEqual(table.note, PLACED_NOTE)
        self.assertEqual(table.rows, (
            ("birch_planks", ("Birch planks", "0", "0", "12,543", "12,543", "0"), False),
            ("dirt", ("Dirt", "0", "0", "313,600", "313,600", "0"), False),
            ("glowstone", ("Glowstone", "0", "0", "12,250", "12,250", "0"), False),
            ("hoe", ("Hoe", "1", "1", "—", "—", "—"), True),
            ("wheat_seeds", ("Wheat seeds", "236", "0", "156,799", "21,504", "135,295"), False)))

    def test_older_mods_show_the_ledger_as_used_since_the_last_start(self):
        older = material_table(material_snapshot(parse_progress(progress_payload())))
        self.assertFalse(older.placed)
        self.assertEqual(older.note, LEDGER_NOTE)
        rows = rows_by_material(older)
        self.assertEqual(rows["wheat_seeds"], ("Wheat seeds", "236", "0", "156,799", "980", "155,819"))
        self.assertEqual(rows["dirt"], ("Dirt", "0", "0", "313,600", "0", "313,600"))
        self.assertEqual(rows["hoe"], ("Hoe", "1", "1", "—", "0", "—"))
        # A mod without /v1/progress at all.
        self.assertEqual(material_table(material_snapshot(None, "unsupported")), older)

    def test_without_progress_only_the_planned_totals_are_shown(self):
        for progress, status in ((None, "unknown"), (None, "error"),
                                 (Unavailable(3, "The plan is loading."), "unavailable")):
            with self.subTest(status):
                table = material_table(material_snapshot(progress, status))
                self.assertTrue(table.placed)
                self.assertEqual(rows_by_material(table)["wheat_seeds"],
                                 ("Wheat seeds", "236", "0", "156,799", "—", "—"))
        self.assertEqual(material_table({}), MaterialTable(True, (), PLACED_NOTE))

    def test_materials_never_raise_on_unexpected_values(self):
        observation = {"materials": {"dirt": 5, "": {"available": 1}, "glowstone": {"available": "x", "missing": True}},
                       "material_ledger": {"planned": [1], "consumed": {"dirt": 10 ** 400}, "remaining_plan": "x"}}
        table = material_table({"observation": observation, "progress": "not progress", "progress_status": 7})
        self.assertEqual(table.rows, (("dirt", ("Dirt", "0", "0", "—", "—", "—"), False),
                                      ("glowstone", ("Glowstone", "—", "—", "—", "—", "—"), False)))
        ledger = material_table({"observation": observation, "progress_status": "unsupported"})
        self.assertEqual(rows_by_material(ledger)["dirt"], ("Dirt", "0", "0", "—", "—", "—"))
        for value in (None, [], "text", 5):
            self.assertEqual(material_table({"observation": value, "progress": value}).rows, ())

    def test_status_details_count_placed_materials_from_the_progress(self):
        observation = {"material_ledger": LEDGER}
        placed = status_details(observation, parse_progress(progress_payload(materials=COUNTS))).splitlines()
        self.assertIn("Materials placed: 359,897 / 495,192 planned", placed)
        self.assertFalse([line for line in placed if line.startswith("Material use:")])
        older = status_details(observation, parse_progress(progress_payload())).splitlines()
        self.assertIn("Material use: 980 / 495,192 planned", older)

    def test_the_pace_tooltip_explains_the_rate_in_sentence_case(self):
        self.assertEqual(PACE_TOOLTIP, "Blocks placed, tilled, or planted per minute.")

    def test_saved_snapshots_drop_credential_fields(self):
        cleaned = safe_observation({"control_token_configured": True, "token": "x",
                                    "nested": [{"password": "y", "ok": 1}]})
        self.assertEqual(cleaned, {"control_token_configured": True, "nested": [{"ok": 1}]})


if __name__ == "__main__":
    unittest.main()
