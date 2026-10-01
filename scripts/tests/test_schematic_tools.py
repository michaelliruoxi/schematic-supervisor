from __future__ import annotations

import io
import gzip
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from generate_work_order import build_work_order, main as work_order_main  # noqa: E402
from inspect_litematic import (  # noqa: E402
    NbtReader,
    bits_per_entry,
    decode_compact,
    decode_padded,
    inspect,
    select_decoder,
    summarize_region,
)


def _tag(kind: int, name: str, payload: bytes) -> bytes:
    encoded = name.encode("utf-8")
    return bytes([kind]) + struct.pack(">H", len(encoded)) + encoded + payload


def _compound(name: str, *children: bytes) -> bytes:
    return _tag(10, name, b"".join(children) + b"\0")


def _integer(name: str, value: int) -> bytes:
    return _tag(3, name, struct.pack(">i", value))


def write_schematic(path: Path, size: tuple[int, int, int],
                    blocks: dict[tuple[int, int, int], str],
                    position: tuple[int, int, int] = (0, 0, 0),
                    include_region: bool = True) -> None:
    """Write small real compact-packed fixtures so the planner uses the actual inspector."""
    palette = ["minecraft:air", *sorted(set(blocks.values()) - {"minecraft:air"})]
    bits = bits_per_entry(len(palette))
    width, height, depth = (abs(value) for value in size)
    volume = width * height * depth
    words = [0] * ((volume * bits + 63) // 64)
    for (x, y, z), block in blocks.items():
        index = (y * depth + z) * width + x
        offset = index * bits
        value = palette.index(block)
        words[offset // 64] |= (value << (offset % 64)) & 0xFFFFFFFFFFFFFFFF
        if offset % 64 + bits > 64:
            words[offset // 64 + 1] |= value >> (64 - offset % 64)
    palette_payload = struct.pack(">Bi", 10, len(palette)) + b"".join(
        _tag(8, "Name", struct.pack(">H", len(block)) + block.encode()) + b"\0" for block in palette)
    states_payload = struct.pack(">i", len(words)) + b"".join(
        struct.pack(">Q", word) for word in words)
    region = _compound("source",
        _compound("Position", *(_integer(axis, value) for axis, value in zip("xyz", position))),
        _compound("Size", *(_integer(axis, value) for axis, value in zip("xyz", size))),
        _tag(9, "BlockStatePalette", palette_payload), _tag(12, "BlockStates", states_payload))
    non_air = sum(block not in {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
                  for block in blocks.values())
    root = _compound("", _integer("Version", 7), _integer("SubVersion", 1),
                     _integer("MinecraftDataVersion", 4440),
                     _compound("Metadata", _integer("TotalBlocks", non_air)),
                     _compound("Regions", region if include_region else b""))
    path.write_bytes(gzip.compress(root, mtime=0))


class NbtReaderTests(unittest.TestCase):
    def test_read_exact_rejects_truncated_input(self) -> None:
        with self.assertRaisesRegex(EOFError, "expected 2 bytes, received 1"):
            NbtReader(io.BytesIO(b"\x01")).read_exact(2)

    def test_numeric_arrays_preserve_empty_single_and_multiple_values(self) -> None:
        for tag_type, element_format in ((11, "i"), (12, "q")):
            for values in ([], [-1], [-1, 0, 42]):
                with self.subTest(tag_type=tag_type, values=values):
                    payload = struct.pack(">i", len(values)) + struct.pack(
                        f">{len(values)}{element_format}", *values
                    )
                    self.assertEqual(NbtReader(io.BytesIO(payload)).payload(tag_type), values)

    def test_numeric_arrays_reject_negative_lengths_before_reading_elements(self) -> None:
        for tag_type in (7, 11, 12):
            with self.subTest(tag_type=tag_type):
                stream = io.BytesIO(struct.pack(">i", -1) + b"trailing data")
                with self.assertRaisesRegex(ValueError, "negative"):
                    NbtReader(stream).payload(tag_type)
                self.assertEqual(stream.tell(), 4)

    def test_numeric_arrays_reject_truncated_elements(self) -> None:
        for tag_type in (11, 12):
            with self.subTest(tag_type=tag_type):
                payload = struct.pack(">i", 1) + b"\x00"
                with self.assertRaises(EOFError):
                    NbtReader(io.BytesIO(payload)).payload(tag_type)


class PackingTests(unittest.TestCase):
    def test_bits_per_entry_has_litematica_minimum(self) -> None:
        self.assertEqual(bits_per_entry(1), 2)
        self.assertEqual(bits_per_entry(4), 2)
        self.assertEqual(bits_per_entry(5), 3)
        self.assertEqual(bits_per_entry(17), 5)

    def test_compact_decoder_handles_cross_word_values(self) -> None:
        values = [(index * 3) % 16 for index in range(20)]
        bits = 4
        packed = 0
        for index, value in enumerate(values):
            packed |= value << (index * bits)
        words = [packed & 0xFFFFFFFFFFFFFFFF, packed >> 64]
        self.assertEqual(decode_compact(words, len(values), bits), values)

    def test_padded_decoder_skips_unused_word_bits(self) -> None:
        values = [1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0]
        bits = 3
        per_word = 64 // bits
        words = []
        for start in range(0, len(values), per_word):
            word = 0
            for offset, value in enumerate(values[start : start + per_word]):
                word |= value << (offset * bits)
            words.append(word)
        self.assertEqual(decode_padded(words, len(values), bits), values)

    def test_select_decoder_rejects_short_data(self) -> None:
        with self.assertRaisesRegex(ValueError, "too short"):
            select_decoder([], entry_count=20, bits=4, palette_size=2, expected_non_air=None)


class RegionCoordinateTests(unittest.TestCase):
    def test_negative_area_sizes_keep_container_indexes_from_minimum_corner(self) -> None:
        indexes = [0] * 34
        indexes[0], indexes[33] = 1, 2
        packed = sum(value << (index * 2) for index, value in enumerate(indexes))
        region = {
            "Position": {"x": 16, "y": 1, "z": 0},
            "Size": {"x": -17, "y": -2, "z": -1},
            "BlockStatePalette": [{"Name": name} for name in
                                  ("minecraft:air", "minecraft:dirt", "minecraft:wheat")],
            "BlockStates": [packed & 0xFFFFFFFFFFFFFFFF, packed >> 64],
        }
        result = summarize_region("signed", region, 2)
        self.assertEqual(result["per_chunk_block_counts"], {
            "0,0": {"minecraft:dirt": 1}, "1,0": {"minecraft:wheat": 1},
        })
        self.assertEqual([layer["world_offset_y"] for layer in result["non_air_layers"]], [0, 1])


class ReusableWorkOrderTests(unittest.TestCase):
    def test_support_capabilities_describe_policy_without_rejecting_other_floor_spacing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "separated_floors.litematic"
            reports = []
            for upper_y in (3, 4):
                write_schematic(source, (1, upper_y + 1, 1), {
                    (0, 0, 0): "minecraft:dirt", (0, upper_y, 0): "minecraft:dirt"})
                report = build_work_order(source)
                reports.append(report)
                self.assertEqual(report["totals"]["materials"]["dirt"], 2)
                self.assertEqual(report["totals"]["planned_interactions"], 2)
                self.assertEqual([stage["relative_y"] for stage in report["execution_order"]["stages"]],
                                 [0, upper_y])
                self.assertEqual(report["verification"]["required_identical_passes"], 2)
                self.assertTrue(report["verification"]["temporary_scaffolding_must_be_removed"])
                self.assertIn("full-volume verification", report["execution_order"]["empty_layers"])
                self.assertEqual(report["schema_version"], 2)
        capability = reports[0]["support_capabilities"]
        self.assertEqual(capability, reports[1]["support_capabilities"],
                         "Source geometry does not reveal existing world attachment faces")
        self.assertEqual(capability["basis"], "source_policy_only")
        self.assertEqual(capability["constructability"], "not_assessed")
        self.assertEqual(capability["existing_adjacent_world_face"], "subject_to_live_guards")
        self.assertEqual(capability["temporary_support"], {
            "policy": "two_cell_vertical_column_v1",
            "scope": "current_ordinary_slice",
            "offset_origin": "planned_target",
            "same_xz": True,
            "material": "minecraft:dirt",
            "temporary_cell_y_offsets": [-2, -1],
            "planned_anchor_y_offset": -3,
            "requires_source_and_received_world_air": True,
            "requires_received_matching_planned_anchor": True,
            "requires_owned_cleanup": True,
        })

    def test_cli_preserves_support_limits_in_json_and_reports_unassessed_constructability(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "separated_floors.litematic"
            output = Path(directory) / "summary.json"
            write_schematic(source, (1, 5, 1), {
                (0, 0, 0): "minecraft:dirt", (0, 4, 0): "minecraft:dirt"})
            console = io.StringIO()
            with patch.object(sys, "argv", ["generate_work_order.py", str(source),
                                            "--output", str(output), "--defer-planting"]), \
                    patch("sys.stdout", console):
                work_order_main()
            summary = json.loads(console.getvalue())
            order = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(summary["constructability"], "not_assessed")
        self.assertEqual(summary["source_sha256"], order["source"]["sha256"])
        self.assertEqual(summary["planned_interactions"], 2)
        self.assertTrue(order["planting_deferred"])
        self.assertEqual(order["support_capabilities"]["temporary_support"]["planned_anchor_y_offset"], -3)
        self.assertEqual(order["support_capabilities"]["temporary_support"]["temporary_cell_y_offsets"], [-2, -1])

    def test_rectangular_source_uses_derived_subdivisions_and_preserves_empty_lower_layer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "rectangular.litematic"
            write_schematic(source, (17, 4, 33), {
                (0, 1, 0): "minecraft:dirt", (16, 1, 32): "minecraft:farmland",
                (16, 2, 32): "minecraft:wheat", (0, 3, 16): "minecraft:birch_planks",
            }, position=(5, 20, -9))
            order = build_work_order(source)
        placement = order["placement_requirements"]
        self.assertEqual(placement["dimensions"], [17, 4, 33])
        self.assertEqual(placement["chunk_grid"], [2, 3])
        self.assertEqual(placement["chunk_count"], 6)
        self.assertEqual(placement["chunk_coordinate_space"], "normalized source region subdivisions")
        self.assertIn("aligned", placement["chunk_partition_assumption"])
        self.assertTrue(placement["live_placement_preflight_required"])
        self.assertNotIn("chunk_aligned_origin", placement)
        self.assertEqual([entry["relative_chunk"] for entry in order["chunks"]],
                         [[0, 0], [1, 0], [0, 1], [1, 1], [0, 2], [1, 2]])
        self.assertEqual(order["chunks"][5]["materials"]["dirt"], 1)
        self.assertEqual(order["chunks"][5]["materials"]["wheat_seeds"], 1)
        self.assertEqual(sum(order["chunks"][1]["materials"].values()), 0)
        stages = order["execution_order"]["stages"]
        self.assertEqual([(stage["stage"], stage["relative_y"], stage["actions"]) for stage in stages],
                         [("STRUCTURE", 1, 2), ("STRUCTURE", 3, 1), ("TILL", 1, 1), ("PLANT", 2, 1)])
        self.assertEqual(order["totals"]["planned_interactions"], 5)

    def test_direct_dirt_requires_only_ordinary_placement(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "dirt.litematic"
            write_schematic(source, (2, 1, 1), {(0, 0, 0): "minecraft:dirt", (1, 0, 0): "minecraft:dirt"})
            order = build_work_order(source)
        self.assertEqual(order["totals"]["materials"]["dirt"], 2)
        self.assertEqual(order["totals"]["actions"], {
            "ordinary_block_placements": 2, "hoe_actions": 0, "plant_actions": 0})
        self.assertEqual(order["execution_order"]["stages"], [
            {"index": 1, "stage": "STRUCTURE", "relative_y": 0, "actions": 2}])
        self.assertNotIn("crop_hopper", order["totals"]["materials"])
        self.assertNotIn("crop_hopper", order["chunks"][0]["materials"])

    def test_deferred_planting_removes_only_seed_actions_and_keeps_source_and_tilling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "construction.litematic"
            write_schematic(source, (17, 4, 2), {
                (0, 0, 0): "minecraft:farmland", (0, 1, 0): "minecraft:wheat",
                (16, 0, 0): "minecraft:dirt", (1, 2, 1): "minecraft:glowstone",
                (1, 3, 1): "minecraft:birch_planks"})
            full = build_work_order(source)
            deferred = build_work_order(source, defer_planting=True)
        self.assertEqual(full["source"], deferred["source"])
        self.assertEqual(full["placement_requirements"], deferred["placement_requirements"])
        self.assertEqual(full["support_capabilities"], deferred["support_capabilities"])
        self.assertTrue(deferred["planting_deferred"])
        self.assertEqual(deferred["execution_order"]["id"], "layers-v2-deferred-planting")
        self.assertEqual(deferred["deferred_seed_cells"], 1)
        self.assertEqual(deferred["totals"]["materials"]["wheat_seeds"], 0)
        self.assertEqual(deferred["totals"]["actions"]["plant_actions"], 0)
        self.assertEqual(deferred["totals"]["actions"]["hoe_actions"], 1)
        for material in ("dirt", "glowstone", "birch_planks"):
            self.assertEqual(full["totals"]["materials"][material], deferred["totals"]["materials"][material])
        self.assertEqual(deferred["totals"]["planned_interactions"], full["totals"]["planned_interactions"] - 1)
        self.assertFalse(any(stage["stage"] == "PLANT" for stage in deferred["execution_order"]["stages"]))
        self.assertIn("air or existing", deferred["verification"]["seed_cells"])

    def test_structure_first_keeps_all_targets_and_automatically_schedules_lighting(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "layers.litematic"
            write_schematic(source, (2, 7, 1), {
                (0, 0, 0): "minecraft:farmland", (0, 1, 0): "minecraft:wheat",
                (1, 2, 0): "minecraft:glowstone", (0, 3, 0): "minecraft:dirt",
                (1, 5, 0): "minecraft:glowstone", (0, 6, 0): "minecraft:birch_planks"})
            for deferred in (False, True):
                original = build_work_order(source, defer_planting=deferred)
                reordered = build_work_order(source, defer_planting=deferred, glowstone_after_structure=True)
                for key in ("source", "chunks", "totals", "verification", "support_capabilities"):
                    self.assertEqual(original[key], reordered[key])
                self.assertEqual(reordered["execution_order"]["id"], "layers-v2-structure-first"
                                 + ("-deferred-planting" if deferred else ""))
                self.assertEqual([(s["stage"], s["relative_y"]) for s in reordered["execution_order"]["stages"][:5]],
                                 [("STRUCTURE", 0), ("STRUCTURE", 3), ("STRUCTURE", 6), ("LIGHTING", 2), ("LIGHTING", 5)])
            with self.assertRaises(ValueError):
                build_work_order(source, glowstone_after_structure="true")

    def test_air_only_region_has_no_build_actions_but_keeps_full_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "air.litematic"
            write_schematic(source, (17, 3, 5), {})
            order = build_work_order(source)
        self.assertTrue(order["source"]["air_only"])
        self.assertEqual(order["support_capabilities"]["constructability"], "not_assessed")
        self.assertEqual(order["totals"]["planned_interactions"], 0)
        self.assertEqual(order["execution_order"]["stage_count"], 0)
        self.assertEqual(order["execution_order"]["stages"], [])
        self.assertEqual(len(order["chunks"]), 2)
        self.assertTrue(all(chunk["actions"]["verification_passes"] == 2 for chunk in order["chunks"]))
        self.assertIn("full-volume verification", order["execution_order"]["empty_layers"])

    def test_empty_file_missing_regions_and_zero_sized_region_fail_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "empty.litematic"
            source.write_bytes(b"")
            with self.assertRaises(EOFError):
                build_work_order(source)
            write_schematic(source, (1, 1, 1), {}, include_region=False)
            with self.assertRaisesRegex(ValueError, "exactly one schematic region"):
                build_work_order(source)
            write_schematic(source, (0, 1, 1), {})
            with self.assertRaisesRegex(ValueError, "dimensions must all be positive"):
                build_work_order(source)

    def test_volume_target_and_source_chunk_limits_match_runtime_guards(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "bounded.litematic"
            write_schematic(source, (1, 1, 1), {(0, 0, 0): "minecraft:dirt"})
            for updates, message in (
                    ({"volume": 2_000_001}, "volume.*2000000"),
                    ({"non_air_blocks": 1_000_001}, "non-air.*1000000"),
                    ({"chunk_grid": [1025, 1]}, "subdivision count.*1024")):
                with self.subTest(updates=updates):
                    result = inspect(source)
                    result["regions"][0].update(updates)
                    with patch("generate_work_order.inspect", return_value=result):
                        with self.assertRaisesRegex(ValueError, message):
                            build_work_order(source)


class ActualSchematicTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schematic = ROOT / "schematics" / "wheatfarm_v2.litematic"

    def test_actual_schematic_decodes_to_validated_totals(self) -> None:
        result = inspect(self.schematic)
        self.assertTrue(result["metadata_total_blocks_match"])
        self.assertEqual(result["decoded_non_air_blocks"], 495_193)
        self.assertEqual(result["region_count"], 1)
        region = result["regions"][0]
        self.assertEqual(region["absolute_size"], (112, 76, 112))
        self.assertEqual(region["chunk_grid"], [7, 7])
        self.assertEqual(region["invalid_palette_indexes"], 0)
        self.assertEqual(region["tile_entity_count"], 0)
        self.assertEqual(region["entity_count"], 0)
        self.assertEqual(len(region["per_chunk_block_counts"]), 49)

    def test_actual_layer_order_preserves_supports_and_all_actions(self) -> None:
        order = build_work_order(self.schematic)
        schedule = order["execution_order"]
        self.assertEqual(schedule["id"], "layers-v2")
        self.assertEqual(schedule["stage_count"], 101)
        stages = schedule["stages"]
        actual = [(stage["stage"], stage["relative_y"]) for stage in stages]
        expected = [("STRUCTURE", 0)]
        for support_y in range(3, 76, 3):
            expected.extend([("STRUCTURE", support_y), ("LIGHTING", support_y - 1)])
        for floor_y in range(72, -1, -3):
            expected.extend([("TILL", floor_y), ("PLANT", floor_y + 1)])
        self.assertEqual(actual, expected)
        self.assertEqual(sum(stage["actions"] for stage in stages),
                         order["totals"]["planned_interactions"])
        self.assertEqual(stages[actual.index(("PLANT", 19))]["actions"], 6272)
        self.assertEqual(stages[actual.index(("STRUCTURE", 75))]["actions"], 12543)

    def test_work_order_is_deterministic_and_preserves_exceptions(self) -> None:
        first = build_work_order(self.schematic)
        second = build_work_order(self.schematic)
        self.assertEqual(first, second)
        self.assertEqual(first["placement_requirements"]["chunk_count"], 49)
        self.assertEqual(first["totals"]["materials"]["dirt"], 313_600)
        self.assertEqual(first["totals"]["materials"]["wheat_seeds"], 156_800)
        self.assertEqual(first["totals"]["materials"]["glowstone"], 12_250)
        self.assertEqual(first["totals"]["materials"]["birch_planks"], 12_543)
        self.assertNotIn("crop_hopper", first["totals"]["materials"])
        self.assertNotIn("crop_hopper_placements", first["totals"]["actions"])
        self.assertEqual(first["totals"]["planned_interactions"], 808_793)

        chunks = {tuple(chunk["relative_chunk"]): chunk for chunk in first["chunks"]}
        self.assertEqual(chunks[(0, 5)]["materials"]["wheat_seeds"], 3_200)
        self.assertEqual(chunks[(6, 1)]["materials"]["wheat_seeds"], 3_200)
        self.assertEqual(chunks[(3, 3)]["materials"]["birch_planks"], 255)
        self.assertEqual(first["chunks"][0]["relative_chunk"], [0, 0])
        self.assertEqual(first["chunks"][7]["relative_chunk"], [0, 1])


if __name__ == "__main__":
    unittest.main()
