#!/usr/bin/env python3
"""Summarize supported Litematic materials and source-relative layer work."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from inspect_litematic import inspect


SCHEMA_VERSION = 2
MAX_VOLUME_CELLS = 2_000_000
MAX_TARGET_BLOCKS = 1_000_000
MAX_CHUNKS = 1_024
SUPPORTED_BLOCKS = {
    "minecraft:air",
    "minecraft:cave_air",
    "minecraft:void_air",
    "minecraft:dirt",
    "minecraft:farmland",
    "minecraft:wheat",
    "minecraft:glowstone",
    "minecraft:birch_planks",
}


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _chunk_key(relative_chunk: tuple[int, int]) -> str:
    return f"{relative_chunk[0]},{relative_chunk[1]}"


def _row_major_chunks(width: int, depth: int) -> list[tuple[int, int]]:
    return [(x, z) for z in range(depth) for x in range(width)]


def schedule_id(defer_planting: bool = False, glowstone_after_structure: bool = False) -> str:
    return ("layers-v2" + ("-structure-first" if glowstone_after_structure else "")
            + ("-deferred-planting" if defer_planting else ""))


def _execution_order(region: dict[str, Any], defer_planting: bool = False,
                     glowstone_after_structure: bool = False) -> dict[str, Any]:
    layers = region["non_air_layers"]
    structures: dict[int, int] = {}
    lighting: dict[int, int] = {}
    tilling: dict[int, int] = {}
    planting: dict[int, int] = {}
    for layer in layers:
        y = layer["local_y"]
        blocks = layer["blocks"]
        farmland = blocks.get("minecraft:farmland", 0)
        structural = farmland + blocks.get("minecraft:dirt", 0) + blocks.get("minecraft:birch_planks", 0)
        if structural:
            structures[y] = structural
        if blocks.get("minecraft:glowstone", 0):
            lighting[y] = blocks["minecraft:glowstone"]
        if farmland:
            tilling[y] = farmland
        if not defer_planting and blocks.get("minecraft:wheat", 0):
            planting[y - 1] = blocks["minecraft:wheat"]

    stages: list[dict[str, Any]] = []

    def append(stage: str, y: int, actions: int) -> None:
        if actions:
            stages.append({"index": len(stages) + 1, "stage": stage,
                           "relative_y": y, "actions": actions})

    if glowstone_after_structure:
        for y in sorted(structures):
            append("STRUCTURE", y, structures[y])
        for y in sorted(lighting):
            append("LIGHTING", y, lighting[y])
    else:
        for support_y in sorted(set(structures) | {y + 1 for y in lighting}):
            append("STRUCTURE", support_y, structures.get(support_y, 0))
            append("LIGHTING", support_y - 1, lighting.get(support_y - 1, 0))
    for floor_y in sorted(set(tilling) | set(planting), reverse=True):
        append("TILL", floor_y, tilling.get(floor_y, 0))
        append("PLANT", floor_y + 1, planting.get(floor_y, 0))
    return {
        "id": schedule_id(defer_planting, glowstone_after_structure),
        "mode": "LAYERS",
        "chunk_traversal": ("complete every chunk in the stage before advancing, stepping between "
                            "adjacent chunks along a tour that ends beside the first chunk"),
        "coordinate_origin": "minimum Y of the source region, before placement transforms",
        "empty_layers": "omitted from build stages; retained in full-volume verification",
        "stage_count": len(stages),
        "stages": stages,
        "final_verification": "verify all chunks, then require two identical full passes",
    }


def build_work_order(schematic: Path, defer_planting: bool = False,
                     glowstone_after_structure: bool = False) -> dict[str, Any]:
    _require(type(defer_planting) is bool, "defer_planting must be a boolean")
    _require(type(glowstone_after_structure) is bool, "glowstone_after_structure must be a boolean")
    result = inspect(schematic)
    _require(result["metadata_total_blocks_match"], "metadata block total does not match decoded data")
    _require(result["region_count"] == 1, "the offline planner supports exactly one schematic region")
    region = result["regions"][0]
    dimensions = tuple(region["absolute_size"])
    chunk_grid = tuple(region["chunk_grid"])
    _require(all(size > 0 for size in dimensions), "schematic dimensions must all be positive")
    _require(region["volume"] <= MAX_VOLUME_CELLS,
             f"schematic volume exceeds the supported limit of {MAX_VOLUME_CELLS} cells")
    _require(0 <= region["non_air_blocks"] <= MAX_TARGET_BLOCKS,
             f"schematic non-air block count exceeds the supported limit of {MAX_TARGET_BLOCKS}")
    _require(1 <= chunk_grid[0] * chunk_grid[1] <= MAX_CHUNKS,
             f"source subdivision count must be between 1 and {MAX_CHUNKS}")
    _require(region["tile_entity_count"] == 0, "tile entities are not supported")
    _require(region["entity_count"] == 0, "entities are not supported")

    blocks = set(region["block_counts"])
    unexpected = sorted(blocks - SUPPORTED_BLOCKS)
    _require(not unexpected, f"unsupported block types: {', '.join(unexpected)}")

    chunk_entries: list[dict[str, Any]] = []
    totals = {
        "materials": {
            "dirt": 0,
            "wheat_seeds": 0,
            "glowstone": 0,
            "birch_planks": 0,
        },
        "actions": {
            "ordinary_block_placements": 0,
            "hoe_actions": 0,
            "plant_actions": 0,
        },
    }

    per_chunk = region["per_chunk_block_counts"]
    for index, relative_chunk in enumerate(_row_major_chunks(*chunk_grid)):
        counts = per_chunk.get(_chunk_key(relative_chunk), {})
        dirt = int(counts.get("minecraft:dirt", 0))
        farmland = int(counts.get("minecraft:farmland", 0))
        wheat = 0 if defer_planting else int(counts.get("minecraft:wheat", 0))
        glowstone = int(counts.get("minecraft:glowstone", 0))
        birch_planks = int(counts.get("minecraft:birch_planks", 0))
        ordinary = dirt + farmland + glowstone + birch_planks

        entry = {
            "index": index,
            "relative_chunk": list(relative_chunk),
            "materials": {
                "dirt": dirt + farmland,
                "wheat_seeds": wheat,
                "glowstone": glowstone,
                "birch_planks": birch_planks,
            },
            "actions": {
                "ordinary_block_placements": ordinary,
                "hoe_actions": farmland,
                "plant_actions": wheat,
                "verification_passes": 2,
            },
        }
        chunk_entries.append(entry)

        totals["materials"]["dirt"] += dirt + farmland
        totals["materials"]["wheat_seeds"] += wheat
        totals["materials"]["glowstone"] += glowstone
        totals["materials"]["birch_planks"] += birch_planks
        totals["actions"]["ordinary_block_placements"] += ordinary
        totals["actions"]["hoe_actions"] += farmland
        totals["actions"]["plant_actions"] += wheat

    interaction_total = sum(totals["actions"].values())
    execution_order = _execution_order(region, defer_planting, glowstone_after_structure)
    _require(sum(stage["actions"] for stage in execution_order["stages"]) == interaction_total,
             "layer action counts do not match source subdivision totals")

    return {
        "schema_version": SCHEMA_VERSION,
        "planting_deferred": defer_planting,
        "glowstone_after_structure": glowstone_after_structure,
        "deferred_seed_cells": int(region["block_counts"].get("minecraft:wheat", 0)) if defer_planting else 0,
        "source": {
            "file": schematic.name,
            "sha256": hashlib.sha256(schematic.read_bytes()).hexdigest(),
            "litematica_version": result["version"],
            "litematica_sub_version": result["sub_version"],
            "minecraft_data_version": result["minecraft_data_version"],
            "decoded_non_air_blocks": result["decoded_non_air_blocks"],
            "air_only": region["non_air_blocks"] == 0,
        },
        "placement_requirements": {
            "single_region": True,
            "dimensions": list(dimensions),
            "chunk_grid": list(chunk_grid),
            "chunk_count": len(chunk_entries),
            "chunk_coordinate_space": "normalized source region subdivisions",
            "chunk_partition_assumption": "untransformed region minimum aligned to a chunk boundary",
            "live_placement_preflight_required": True,
            "live_chunk_assignment": "recomputed from placement; may differ with rotation, mirror or unaligned minimum",
        },
        "support_capabilities": {
            "basis": "source_policy_only",
            "constructability": "not_assessed",
            "existing_adjacent_world_face": "subject_to_live_guards",
            "temporary_support": {
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
            },
        },
        "execution_order": execution_order,
        "verification": {
            "required_identical_passes": 2,
            "seed_cells": "air or existing normalized wheat" if defer_planting else "normalized wheat",
            "ignored_block_properties": {
                "minecraft:farmland": ["moisture"],
                "minecraft:wheat": ["age"],
            },
            "temporary_scaffolding_must_be_removed": True,
        },
        "totals": {
            **totals,
            "planned_interactions": interaction_total,
        },
        "chunks": chunk_entries,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("schematic", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--defer-planting", action="store_true",
                        help="Exclude seed purchases and planting while retaining all construction and tilling.")
    parser.add_argument("--glowstone-after-structure", action="store_true",
                        help="Finish every structural layer before automatically placing Glowstone.")
    args = parser.parse_args()

    work_order = build_work_order(args.schematic, args.defer_planting, args.glowstone_after_structure)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(work_order, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "output": str(args.output.resolve()),
                "source_sha256": work_order["source"]["sha256"],
                "chunks": work_order["placement_requirements"]["chunk_count"],
                "planned_interactions": work_order["totals"]["planned_interactions"],
                "constructability": work_order["support_capabilities"]["constructability"],
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
