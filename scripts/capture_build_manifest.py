#!/usr/bin/env python3
"""Capture saved placement transforms and build dependency hashes."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from typing import Any
import zipfile

# Capturing a manifest must not create local module cache files beside the scripts.
sys.dont_write_bytecode = True
from generate_work_order import MAX_CHUNKS, MAX_TARGET_BLOCKS, MAX_VOLUME_CELLS, SUPPORTED_BLOCKS


MANAGED_MOD_IDS = frozenset({"schematic_supervisor", "fabric-api", "malilib", "litematica", "baritone"})
ROTATIONS = frozenset({"NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"})
MIRRORS = frozenset({"NONE", "LEFT_RIGHT", "FRONT_BACK"})
SETTING_LIMITS = {
    "placementBlocksPerTick": (1, 100_000),
    "verificationBlocksPerTick": (1, 100_000),
    "interactionCooldownTicks": (1, 100),
    "pathGoalRadius": (1, 8),
    "minimumFood": (0, 2**63 - 1),
}
BOOLEAN_SETTINGS = frozenset({"deferPlanting", "autoRepairHoes", "discardSurplusWhenStorageFull",
                              "discardSurplusDirectly", "glowstoneAfterStructure"})
MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_MOD_METADATA_BYTES = 1024 * 1024


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _object(value: Any, label: str) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be a JSON object")
    return value


def _text(value: Any, label: str, maximum: int = 256) -> str:
    _require(isinstance(value, str) and 0 < len(value) <= maximum
             and not any(ord(character) < 32 for character in value), f"{label} must be bounded text")
    return value


def _vector(value: Any, label: str) -> list[int]:
    _require(isinstance(value, list) and len(value) == 3
             and all(type(number) is int and -(2**31) <= number < 2**31 for number in value),
             f"{label} must contain three signed integer coordinates")
    return list(value)


def _transform(value: dict[str, Any], position_key: str, label: str) -> dict[str, Any]:
    rotation = value.get("rotation")
    mirror = value.get("mirror")
    _require(isinstance(rotation, str) and rotation in ROTATIONS, f"{label} has an invalid rotation")
    _require(isinstance(mirror, str) and mirror in MIRRORS, f"{label} has an invalid mirror")
    _require(type(value.get("enabled")) is bool, f"{label} needs an enabled boolean")
    return {position_key: _vector(value.get(position_key), f"{label} {position_key}"),
            "rotation": rotation, "mirror": mirror, "enabled": value["enabled"]}


def _regular_file(path: Path, label: str) -> Path:
    try:
        resolved = path.expanduser().resolve(strict=True)
    except OSError as error:
        raise ValueError(f"{label} must be an existing regular file") from error
    _require(resolved.is_file(), f"{label} must be an existing regular file")
    return resolved


def _read_json(path: Path, label: str) -> dict[str, Any]:
    _require(path.stat().st_size <= MAX_JSON_BYTES, f"{label} exceeds the JSON size limit")
    try:
        with path.open("rb") as source:
            data = source.read(MAX_JSON_BYTES + 1)
        _require(len(data) <= MAX_JSON_BYTES, f"{label} exceeds the JSON size limit")
        return _object(json.loads(data), label)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is not valid JSON") from error


def _sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def _support_evidence(report_path: Path, game: Path, installed: dict[str, str],
                      installed_name: str) -> dict[str, Any]:
    evidence: dict[str, Any] = {
        "status": "unknown",
        "basis": "installation_report",
        "installed_mod_sha256": installed["sha256"],
        "live_verification": "not_assessed_by_manifest",
    }
    if not report_path.exists():
        return evidence | {"reason": "installation_report_missing"}
    try:
        report = _read_json(_regular_file(report_path, "Installation report"), "Installation report")
        reported_game = _text(report.get("game_directory"), "Installation game directory", 4096)
        if Path(reported_game).expanduser().resolve(strict=True) != game:
            return evidence | {"reason": "game_directory_mismatch"}
        update = _object(report.get("last_mod_update"), "Last mod update")
        recorded_mods = report.get("mods")
        _require(isinstance(recorded_mods, list) and len(recorded_mods) <= 1024,
                 "Installation mods must be a bounded list")
        matches = [entry for entry in recorded_mods
                   if isinstance(entry, dict) and entry.get("name") == installed_name]
        hashes = [update.get("sha256")]
        if len(matches) == 1:
            hashes.append(matches[0].get("sha256"))
        if len(hashes) != 2 or any(not isinstance(value, str) or value.lower() != installed["sha256"]
                                   for value in hashes):
            return evidence | {"reason": "installed_jar_mismatch"}
        integrated = update.get("temporary_supports_integrated")
        if type(integrated) is not bool:
            return evidence | {"reason": "integration_claim_missing_or_invalid"}
        return evidence | {"status": "available" if integrated else "unavailable",
                           "basis": "hash_matched_installation_report", "reason": "recorded_integration_claim"}
    except (OSError, ValueError):
        # Optional provenance cannot turn unavailable evidence into a feature or live-gameplay claim.
        return evidence | {"reason": "installation_report_unreadable_or_invalid"}


def build_manifest(placement_state: Path, game_directory: Path,
                   installation_report: Path | None = None) -> tuple[dict[str, Any], set[Path]]:
    state_path = _regular_file(placement_state, "Saved placement state")
    game = game_directory.expanduser().resolve(strict=True)
    _require(game.is_dir(), "Game directory must be an existing directory")
    state = _read_json(state_path, "Saved placement state")
    selection = _object(state.get("placements"), "Placement selection")
    placements = selection.get("placements")
    selected = selection.get("selected")
    _require(isinstance(placements, list) and type(selected) is int
             and 0 <= selected < len(placements), "Selected placement index is missing or out of range")
    placement = _object(placements[selected], "Selected placement")
    saved_transform = _transform(placement, "origin", "Selected placement")
    _require(saved_transform["enabled"], "Selected placement is disabled")

    regions = placement.get("placements")
    _require(isinstance(regions, list) and 0 < len(regions) <= 1024,
             "Selected placement needs a bounded nonempty sub-region list")
    region_transforms = []
    names = set()
    for region in regions:
        entry = _object(region, "Sub-region")
        name = _text(entry.get("name"), "Sub-region name")
        _require(name not in names, "Sub-region names must be unique")
        names.add(name)
        transform = _transform(_object(entry.get("placement"), "Sub-region placement"),
                               "pos", "Sub-region placement")
        region_transforms.append({"name": name, **transform})
    _require(sum(region["enabled"] for region in region_transforms) == 1,
             "The current supervisor requires exactly one enabled sub-region")

    source_reference = Path(_text(placement.get("schematic"), "Schematic reference", 4096)).expanduser()
    _require(source_reference.suffix.lower() == ".litematic", "Schematic reference must use the .litematic suffix")
    source_name = source_reference.name
    source_path = _regular_file(source_reference if source_reference.is_absolute() else game / source_reference,
                                "Referenced schematic")
    inputs = {state_path, source_path}
    mods_directory = game / "mods"
    _require(mods_directory.is_dir(), "The game directory has no mods directory")
    mods = {}
    supervisor_mod_name = ""
    for candidate in sorted(mods_directory.glob("*.jar")):
        mod_path = _regular_file(candidate, "Installed mod jar")
        try:
            with zipfile.ZipFile(mod_path) as archive:
                info = archive.getinfo("fabric.mod.json")
                _require(info.file_size <= MAX_MOD_METADATA_BYTES, "Mod metadata exceeds its size limit")
                metadata = _object(json.loads(archive.read(info)), "Mod metadata")
        except (zipfile.BadZipFile, KeyError, UnicodeError, json.JSONDecodeError) as error:
            raise ValueError("An installed jar has invalid Fabric metadata") from error
        identity = metadata.get("id")
        if isinstance(identity, str) and identity in MANAGED_MOD_IDS:
            _require(identity not in mods, f"Duplicate managed mod: {identity}")
            version = _text(metadata.get("version"), f"Version for {identity}", 128)
            mods[identity] = {"id": identity, "version": version, "sha256": _sha256(mod_path)}
            if identity == "schematic_supervisor":
                supervisor_mod_name = mod_path.name
        inputs.add(mod_path)
    _require(set(mods) == MANAGED_MOD_IDS, "The profile must contain all five managed Fabric mods")
    report_path = (installation_report if installation_report is not None else game.parent / "installation.json")
    report_path = report_path.expanduser().resolve()
    inputs.add(report_path)
    support_evidence = _support_evidence(report_path, game, mods["schematic_supervisor"], supervisor_mod_name)
    support_available = {"available": True, "unavailable": False, "unknown": None}[support_evidence["status"]]

    settings_path = game / "config" / "schematic-supervisor" / "settings.json"
    saved_settings = {}
    settings_present = settings_path.exists()
    if settings_present:
        settings_path = _regular_file(settings_path, "Supervisor settings")
        raw_settings = _read_json(settings_path, "Supervisor settings")
        for key, (minimum, maximum) in SETTING_LIMITS.items():
            if key not in raw_settings:
                continue
            value = raw_settings[key]
            _require(type(value) is int and minimum <= value <= maximum,
                     f"Supervisor setting {key} is outside its supported integer range")
            saved_settings[key] = value
        for key in BOOLEAN_SETTINGS:
            if key in raw_settings:
                _require(type(raw_settings[key]) is bool, f"Supervisor setting {key} must be a boolean")
                saved_settings[key] = raw_settings[key]
    inputs.add(settings_path.resolve())

    return {
        "schema_version": 1,
        "captured_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "snapshot": {
            "kind": "saved_litematica_placement_and_source",
            "freshness": "Saved state only. Save the placement and exit Minecraft before capture for the freshest data.",
            "live_placement_preflight_required": True,
            "source_contents_decoded": False,
        },
        "source": {"name": source_name, "sha256": _sha256(source_path)},
        "placement": {
            "selected_index": selected,
            "origin_coordinate_space": "absolute world coordinates",
            **saved_transform,
            "sub_region_coordinate_space": "saved pos relative to the placement origin; preserve both transform levels",
            "sub_regions": region_transforms,
        },
        "layer_policy": ("layers-v2" + ("-structure-first" if saved_settings.get("glowstoneAfterStructure", False) else "")
                         + ("-deferred-planting" if saved_settings.get("deferPlanting", False) else "")),
        "capabilities": {
            "maximum_enabled_sub_regions": 1,
            "supported_blocks": sorted(SUPPORTED_BLOCKS),
            "block_entities_supported": False,
            "entities_supported": False,
            "temporary_support_execution_available": support_available,
            "temporary_support_execution_evidence": support_evidence,
        },
        "resource_bounds": {"maximum_volume_cells": MAX_VOLUME_CELLS,
                            "maximum_non_air_blocks": MAX_TARGET_BLOCKS,
                            "maximum_live_chunks": MAX_CHUNKS},
        "mods": [mods[identity] for identity in sorted(mods)],
        "supervisor_settings": {"saved_file_present": settings_present, "values": saved_settings,
                                "unsaved_fields": sorted((SETTING_LIMITS.keys() | BOOLEAN_SETTINGS)
                                                         - saved_settings.keys())},
    }, inputs


def capture_manifest(placement_state: Path, game_directory: Path, output: Path,
                     installation_report: Path | None = None) -> dict[str, Any]:
    manifest, inputs = build_manifest(placement_state, game_directory, installation_report)
    destination = output.expanduser().resolve()
    _require(not destination.exists() or destination.is_file(), "Output must be a regular file path")
    _require(destination not in inputs and not any(
        destination.exists() and candidate.exists() and destination.samefile(candidate) for candidate in inputs),
        "Manifest output must not overwrite an input file")
    encoded = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", dir=destination.parent, suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--placement-state", required=True, type=Path)
    parser.add_argument("--game-directory", required=True, type=Path)
    parser.add_argument("--installation-report", type=Path,
                        help="Optional installation evidence; defaults to installation.json beside the game directory")
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        manifest = capture_manifest(arguments.placement_state, arguments.game_directory, arguments.output,
                                    arguments.installation_report)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Manifest capture failed: {error}\n")
    print(json.dumps({"source_name": manifest["source"]["name"],
                      "source_sha256": manifest["source"]["sha256"], "managed_mod_count": len(manifest["mods"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
