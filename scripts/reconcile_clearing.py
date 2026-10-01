"""Review one interrupted clearing attempt; --apply clears its latch with zero credit.

Both the game and supervision runner must be stopped. A fresh read-only clearing
probe must bind the exact request, checkpoint and unchanged plain replacement slots.
This tool never sends input, retries mining, advances work, or credits a block.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
_SPEC = importlib.util.spec_from_file_location("clearing_recovery_io", ROOT / "scripts/reconcile_placement.py")
_IO = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_IO)
require, sha256, encoded = _IO.require, _IO.sha256, _IO.encoded
json_object, read_bytes = _IO.json_object, _IO.read_bytes
integer, canonical_uuid, hash_value = _IO.integer, _IO.canonical_uuid, _IO.hash_value
instant, valid_context = _IO.instant, _IO.valid_context
contained, atomic_bytes = _IO.contained, _IO.atomic_bytes
ensure_processes_absent = _IO.ensure_processes_absent
_UPDATER = _IO._UPDATER
REQUEST_KEYS = _IO.REQUEST_KEYS | {"observed_block", "selected_slot"}
DISCONNECT_CAUSE = "Player or world changed with an outstanding interaction; reconciliation is required"
RECONCILIATION_DETAIL = "Build interaction settlement failed: " + DISCONNECT_CAUSE + ". Reset is required before starting or resuming."
ITEM = "minecraft:glowstone"
JACK = "minecraft:jack_o_lantern"
AIR = "minecraft:air"
TIMEOUT_KIND = "light_clearing_acknowledgement_timeout"
STRUCTURE_TIMEOUT_KIND = "moss_clearing_acknowledgement_timeout"
STEM_TIMEOUT_KIND = "stem_sweep_acknowledgement_timeout"
STRUCTURE_DISCONNECT_KIND = "moss_clearing_disconnect_unchanged"
DIRT = "minecraft:dirt"
MOSS = "minecraft:moss_block"


def timeout_cause(target: dict) -> str:
    return ("The server acknowledgement, target state, and inventory did not agree at "
            f"{target['x']}, {target['y']}, {target['z']}; reconciliation is required before another click")


def validate_request(request: dict) -> None:
    version = request.get("version")
    require(type(version) is int and version in (1, 2, 3), "Unsupported request version.")
    keys = REQUEST_KEYS | ({"replacement_slots"} if version >= 2 else set())
    require(set(request) in (keys, keys | {"old_session_id"}), "Unsupported clearing request fields.")
    require(canonical_uuid(request.get("request_id")) and canonical_uuid(request.get("player_uuid")),
            "Canonical request and player UUIDs are required.")
    require("old_session_id" not in request or canonical_uuid(request["old_session_id"]), "Invalid old session ID.")
    instant(request["created_at"])
    require(hash_value(request["checkpoint_sha256"]) and hash_value(request["plan_id"], "sha256:"),
            "Exact checkpoint and plan hashes are required.")
    require(valid_context(request["saved_run_context"]), "Invalid saved run context.")
    target = request["target"]
    require(isinstance(target, dict) and set(target) == {"x", "y", "z"}
            and all(integer(target[key], -30_000_000, 30_000_000) for key in target), "Invalid clearing target.")
    require(request["material"] == ("glowstone" if version == 1 else "dirt")
            and request["observed_block"] == {1: JACK, 2: MOSS, 3: AIR}[version]
            and request["expected_block"] == AIR, "Only planned replacement or stem clearing is supported.")
    require(integer(request["selected_slot"], 0, 8) and integer(request["inventory_before"], 1, 64 if version == 1 else 2304)
            and type(request["expected_inventory_after"]) is int
            and request["inventory_before"] == request["expected_inventory_after"],
            "Only unchanged plain replacement inventory is supported.")
    if version >= 2:
        slots = replacement_slots(request)
        require(request["selected_slot"] in slots and sum(slots.values()) == request["inventory_before"],
                "Replacement slots differ from the total or selected stack.")


def replacement_slots(request: dict) -> dict:
    entries = request.get("replacement_slots")
    require(isinstance(entries, list) and 1 <= len(entries) <= 36, "A bounded replacement slot list is required.")
    result = {}
    for entry in entries:
        require(isinstance(entry, dict) and set(entry) == {"slot", "count"}
                and integer(entry.get("slot"), 0, 35) and integer(entry.get("count"), 1, 64)
                and entry["slot"] not in result, "Replacement slots are malformed or duplicated.")
        result[entry["slot"]] = entry["count"]
    return result


def target_matches(target: dict, request: dict) -> bool:
    return (isinstance(target, dict) and all(target.get(key) == value for key, value in request["target"].items())
            and target.get("expected_block") == AIR)


def validate_original(checkpoint: dict, evidence: dict, request: dict) -> None:
    if evidence.get("kind") == STRUCTURE_DISCONNECT_KIND:
        validate_structure_disconnect_original(checkpoint, evidence, request)
        return
    if evidence.get("kind") in {STRUCTURE_TIMEOUT_KIND, STEM_TIMEOUT_KIND}:
        validate_structure_timeout_original(checkpoint, evidence, request)
        return
    require(request["version"] == 1, "This incident does not support a version-two structure request.")
    if evidence.get("kind") == TIMEOUT_KIND:
        validate_timeout_original(checkpoint, evidence, request)
        return
    require("kind" not in evidence, "Unsupported clearing incident kind.")
    require(checkpoint.get("version") == 4 and checkpoint.get("state") == "PAUSED"
            and checkpoint.get("resume_state") == "BUILDING" and checkpoint.get("phase") == "ORDINARY_BLOCKS"
            and checkpoint.get("recovery_stage") == "NONE" and checkpoint.get("withdrawal_in_flight") is False
            and checkpoint.get("reconciliation_required") is True
            and checkpoint.get("reconciliation_detail") == RECONCILIATION_DETAIL,
            "Only the sole disconnected ordinary interaction cause can be cleared.")
    require(checkpoint.get("plan_id") == request["plan_id"] and checkpoint.get("repair_chunk_index") == -1
            and checkpoint.get("missing_materials") == {} and checkpoint.get("restock_requirement") == {},
            "Checkpoint has another plan, repair or material operation.")
    require(evidence.get("reset_performed") is False and isinstance(evidence.get("trace"), list)
            and 1 <= len(evidence["trace"]) <= 10_000, "A bounded original incident without Reset is required.")
    start = evidence.get("start", {})
    disconnected = evidence.get("disconnect", {})
    require(start.get("ok") is True and start.get("fresh") is True and start.get("connection") == "online"
            and disconnected.get("ok") is True and disconnected.get("fresh") is True,
            "Original incident observations are unavailable or stale.")
    initial = start.get("observation", {})
    observed = disconnected.get("observation", {})
    run_id = observed.get("run_id")
    require(canonical_uuid(run_id) and initial.get("run_id") == run_id
            and initial.get("plan_id") == request["plan_id"] == observed.get("plan_id")
            and initial.get("world_connected") is True and initial.get("context_matches") is True
            and observed.get("state") == "PAUSED" and observed.get("world_connected") is False
            and observed.get("context_matches") is False and observed.get("phase") == "ORDINARY_BLOCKS",
            "Original incident is not a same-run world disconnect.")
    require(checkpoint.get("last_error") == initial.get("last_error")
            and isinstance(checkpoint.get("last_error"), str), "Historical checkpoint error differs from the incident.")
    captured = instant(evidence.get("captured_at"))
    require(instant(initial.get("updated_at")) <= instant(observed.get("updated_at")) <= captured
            < instant(request["created_at"]), "Incident/request timestamps are not ordered.")
    trace = evidence["trace"]
    previous = instant(initial["updated_at"])
    connected = []
    disconnected_seen = False
    for sample in trace:
        require(isinstance(sample, dict) and sample.get("run_id") == run_id, "Trace mixes unrelated runs.")
        stamp = instant(sample.get("updated_at"))
        require(previous <= stamp <= instant(observed["updated_at"]), "Trace timestamps are reversed or outside the incident.")
        previous = stamp
        if sample.get("world_connected") is True:
            require(not disconnected_seen, "Trace resumed after its disconnected interaction.")
            connected.append(sample)
        else:
            require(sample.get("world_connected") is False and sample.get("state") == "PAUSED",
                    "Trace has unknown connection state.")
            disconnected_seen = True
    require(connected, "Original incident has no connected clearing evidence.")
    live = connected[-1]
    layer = live.get("layer", {})
    live_control = live.get("control", {})
    final_control = observed.get("last_control", {})
    pause_only = (isinstance(live_control, dict) and isinstance(final_control, dict)
                  and integer(live_control.get("sequence"))
                  and final_control.get("sequence") == live_control["sequence"] + 1
                  and final_control.get("action") == "PAUSE")
    require(live.get("state") == "BUILDING" and live.get("context_matches") is True
            and live.get("blockers") == [] and layer.get("stage") == "LIGHTING"
            and layer.get("y") == request["target"]["y"] and layer == observed.get("current_layer")
            and integer(checkpoint.get("current_chunk_index"))
            and observed.get("current_chunk", {}).get("index") == checkpoint["current_chunk_index"] + 1
            and observed.get("current_chunk", {}).get("total") == checkpoint.get("chunk_count")
            and live_control == initial.get("last_control")
            and (final_control == live_control or pause_only),
            "Original clearing scope or control changed before the disconnect.")
    for name, key in (("consumed_materials", "consumed"), ("withdrawn_materials", "withdrawn")):
        require(isinstance(checkpoint.get(name), dict) and all(integer(value) for value in checkpoint[name].values())
                and live.get("ledger", {}).get(key) == checkpoint[name]
                and observed.get("material_ledger", {}).get(key) == checkpoint[name],
                "Original clearing ledgers differ from the checkpoint.")
    execution = live.get("execution", {})
    receipt = execution.get("receipt", {})
    require(execution.get("mode") == "FLIGHT_CLEARING_LIGHT" and execution.get("owned_mining") is True
            and execution.get("last_failure") is None and isinstance(receipt, dict)
            and receipt.get("mode") == "FLIGHT_CLEARING_LIGHT" and receipt.get("result") == "WAITING"
            and receipt.get("owned_mining") is True and receipt.get("manager_breaking") is True
            and receipt.get("prediction_pending") is False and receipt.get("material") == "glowstone"
            and receipt.get("inventory_before") == request["inventory_before"]
            and receipt.get("inventory_now") == request["expected_inventory_after"]
            and receipt.get("selected_slot") == request["selected_slot"] and receipt.get("selected_item") == ITEM
            and receipt.get("budget_ticks") == 240 and integer(receipt.get("age_ticks"), 0, 239)
            and receipt.get("error") == "" and target_matches(receipt.get("target"), request)
            and receipt["target"].get("actual_block") == JACK and receipt["target"].get("chunk_received") is True
            and receipt.get("breaking_position") == request["target"],
            "Last live receipt is not the exact owned zero-spend light-clearing attempt.")
    require(instant(receipt.get("captured_at")) <= instant(live["updated_at"]), "Live receipt timestamp is invalid.")
    inventory = live.get("inventory", {})
    menu = inventory.get("menu", {})
    require(inventory.get("totals", {}).get("glowstone") == request["inventory_before"]
            and menu.get("open") is False and menu.get("sync_id") == 0
            and menu.get("cursor", {}).get("count") == 0
            and live.get("depot", {}).get("operation") == "NONE" and live["depot"].get("stage") == "IDLE"
            and live.get("shop") == "IDLE" and live.get("shop_detail", {}).get("pending_stacks") == 0
            and live.get("moss_deposit", {}).get("active") is False,
            "Last live clearing evidence contains another inventory operation.")
    suspended_execution = observed.get("execution", {})
    suspended = suspended_execution.get("last_receipt", {})
    require(suspended_execution.get("available") is False and suspended_execution.get("truncated") is False
            and suspended_execution.get("mode") == "SUSPENDED" and suspended_execution.get("receipt") is None
            and suspended_execution.get("last_failure") is None and isinstance(suspended, dict)
            and suspended.get("mode") == "SUSPENDED" and suspended.get("result") == "WAITING"
            and suspended.get("error") == DISCONNECT_CAUSE and suspended.get("material") == "glowstone"
            and suspended.get("inventory_before") == request["inventory_before"] and suspended.get("inventory_now") is None
            and suspended.get("budget_ticks") == 240
            and integer(suspended.get("age_ticks"), receipt["age_ticks"], 239)
            and target_matches(suspended.get("target"), request)
            and suspended["target"].get("actual_block") is None and suspended["target"].get("chunk_received") is None
            and suspended.get("prediction_pending") is None,
            "Disconnected receipt is not the same unfinished clearing operation.")
    require(instant(live["updated_at"]) <= instant(suspended.get("captured_at")) <= instant(observed["updated_at"]),
            "Disconnected receipt timestamp differs from the live attempt.")


def validate_timeout_original(checkpoint: dict, evidence: dict, request: dict) -> None:
    """Accept only an axe clearing timeout retained unchanged across a disconnect.

    No connected mining sample is invented. The retained failed receipt, unchanged
    accounting and read-only rejoin are followed by a separate fresh-process probe.
    The request's selected slot identifies that probe's replacement stack, not the
    historical axe slot. Neither removal nor placement receives material credit.
    """
    cause = timeout_cause(request["target"])
    detail = "Build interaction settlement failed: " + cause + ". Reset is required before starting or resuming."
    require(type(evidence.get("version")) is int and evidence["version"] == 1
            and evidence.get("reset_performed") is False,
            "An original version-one timeout incident without Reset is required.")
    require(checkpoint.get("version") == 4 and checkpoint.get("state") == "PAUSED"
            and checkpoint.get("resume_state") == "BUILDING" and checkpoint.get("phase") == "ORDINARY_BLOCKS"
            and checkpoint.get("recovery_stage") == "NONE" and checkpoint.get("withdrawal_in_flight") is False
            and checkpoint.get("reconciliation_required") is True
            and checkpoint.get("reconciliation_detail") == detail
            and checkpoint.get("last_error") in {cause, cause + "; Advisor unavailable after deterministic recovery: Companion request unavailable"},
            "Only the sole timed-out ordinary clearing cause can be cleared.")
    require(checkpoint.get("plan_id") == request["plan_id"] and checkpoint.get("repair_chunk_index") == -1
            and checkpoint.get("missing_materials") == {} and checkpoint.get("restock_requirement") == {}
            and checkpoint.get("schedule_id") in {"layers-v1-deferred-planting", "layers-v1-structure-first-deferred-planting"}
            and checkpoint.get("planting_deferred") is True
            and integer(checkpoint.get("schedule_cursor")) and integer(checkpoint.get("current_chunk_index"))
            and integer(checkpoint.get("chunk_count"), 1),
            "Checkpoint has another plan, schedule, repair or material operation.")
    wrappers = [evidence.get(key, {}) for key in ("disconnect", "rejoined")]
    require(all(isinstance(w, dict) and w.get("ok") is True and w.get("fresh") is True
                and w.get("connection") == "online" and isinstance(w.get("observation"), dict) for w in wrappers),
            "Original timeout and rejoin observations are unavailable or stale.")
    disconnected, rejoined = [w["observation"] for w in wrappers]
    run_id = disconnected.get("run_id")
    require(canonical_uuid(run_id) and rejoined.get("run_id") == run_id
            and disconnected.get("world_connected") is False and disconnected.get("context_matches") is False
            and rejoined.get("world_connected") is True and rejoined.get("context_matches") is True,
            "Timeout evidence must retain the same run across a world disconnect and rejoin.")
    control = disconnected.get("last_control", {})
    require(isinstance(control, dict) and integer(control.get("sequence"), 1) and control.get("action") == "PAUSE"
            and isinstance(control.get("request_id"), str) and control["request_id"].startswith("agent-")
            and rejoined.get("last_control") == control,
            "The automatic safety pause changed after the timeout; operator intent must be preserved.")
    receipt = disconnected.get("execution", {}).get("last_receipt", {})
    require(isinstance(receipt, dict) and receipt.get("mode") == "FLIGHT_CLEARING_CONFIRMATION"
            and receipt.get("result") == "UNCERTAIN" and receipt.get("prediction_pending") is True
            and receipt.get("world_matches") is True and receipt.get("owned_mining") is False
            and receipt.get("manager_breaking") is False and receipt.get("material") == "glowstone"
            and receipt.get("inventory_before") == request["inventory_before"]
            and receipt.get("inventory_now") == request["inventory_before"]
            and receipt.get("age_ticks") == 240 and receipt.get("budget_ticks") == 240
            and integer(receipt.get("selected_slot"), 0, 8) and receipt.get("selected_item") == "minecraft:diamond_axe"
            and receipt.get("error") == "" and receipt.get("breaking_position") == request["target"]
            and target_matches(receipt.get("target"), request)
            and receipt["target"].get("actual_block") == AIR and receipt["target"].get("chunk_received") is True,
            "Failed receipt is not the exact zero-spend axe clearing acknowledgement timeout.")
    require(instant(receipt.get("captured_at")) <= instant(disconnected.get("updated_at"))
            < instant(rejoined.get("updated_at")) <= instant(evidence.get("captured_at"))
            < instant(request["created_at"]), "Timeout, rejoin and request timestamps are not ordered.")
    layer = disconnected.get("current_layer", {})
    require(layer == rejoined.get("current_layer") and layer.get("order") == "LAYERS"
            and layer.get("stage") == "LIGHTING" and layer.get("y") == request["target"]["y"]
            and integer(layer.get("index"), 1) and integer(layer.get("total"), layer["index"])
            and checkpoint["schedule_cursor"] == (layer["index"] - 1) * checkpoint["chunk_count"] + checkpoint["current_chunk_index"],
            "Timeout layer differs from the saved schedule.")
    for observed in (disconnected, rejoined):
        require(observed.get("state") == "PAUSED" and observed.get("phase") == "ORDINARY_BLOCKS"
                and observed.get("plan_id") == request["plan_id"]
                and observed.get("current_chunk", {}).get("index") == checkpoint["current_chunk_index"] + 1
                and observed.get("current_chunk", {}).get("total") == checkpoint["chunk_count"]
                and layer.get("chunk_index") == checkpoint["current_chunk_index"] + 1
                and layer.get("chunk_total") == checkpoint["chunk_count"]
                and observed.get("last_error") == detail + " Previous error: " + checkpoint["last_error"],
                "Paused incident scope or retained error differs from the checkpoint.")
        for name, key in (("consumed_materials", "consumed"), ("withdrawn_materials", "withdrawn")):
            require(isinstance(checkpoint.get(name), dict) and all(integer(v) for v in checkpoint[name].values())
                    and observed.get("material_ledger", {}).get(key) == checkpoint[name],
                    "Timeout ledgers differ from the checkpoint.")
        execution = observed.get("execution", {})
        selection = execution.get("last_moss_tool_selection", {})
        require(execution.get("mode") == "FAILED" and execution.get("truncated") is False
                and execution.get("receipt") is None and execution.get("last_failure") == receipt
                and execution.get("last_receipt") == receipt and execution.get("detail") == cause
                and selection.get("outcome") == "AXE" and selection.get("target") == request["target"]
                and selection.get("selected_slot") == receipt["selected_slot"]
                and selection.get("selected_item") == receipt["selected_item"]
                and selection.get("truncated") is False and selection.get("error") == ""
                and instant(selection.get("captured_at")) <= instant(receipt["captured_at"]),
                "Retained clearing failure or original tool selection changed.")
        require(observed.get("depots", {}).get("operation") == "NONE" and observed["depots"].get("stage") == "IDLE"
                and observed.get("shop", {}).get("state") == "IDLE" and observed["shop"].get("active") is False
                and observed["shop"].get("pending_stacks") == 0,
                "Timeout contains another depot or shop operation.")
        for key in ("moss_deposit", "material_shop"):
            operation = observed.get(key, {})
            require(operation.get("available") is True and operation.get("active") is False
                    and operation.get("pending") is False and operation.get("truncated") is False,
                    "Timeout contains another pending or unavailable inventory transaction.")
        disposal = observed.get("surplus_disposal", {})
        require(disposal.get("active") is False and disposal.get("pending") is False and disposal.get("unavailable") is False,
                "Timeout contains an unsettled disposal.")
    require(checkpoint["withdrawn_materials"].get("glowstone", 0) - checkpoint["consumed_materials"].get("glowstone", 0)
            == request["inventory_before"], "Glowstone accounting does not independently match the unspent inventory.")
    execution = rejoined["execution"]
    target = execution.get("target", {})
    require(execution.get("available") is True and execution.get("owned_mining") is False
            and execution.get("manager_breaking") is False
            and all(target.get(k) == v for k, v in request["target"].items())
            and target.get("expected_block") == ITEM and target.get("actual_block") == JACK
            and target.get("chunk_received") is True,
            "Read-only rejoin does not confirm the original Jack at the planned replacement target.")
    inventory = rejoined.get("inventory", {})
    menu = inventory.get("menu", {})
    stacks = [s for s in inventory.get("main_slots", []) if s.get("item_id") == ITEM]
    require(inventory.get("available") is True and len(stacks) == 1 and stacks[0].get("plain_default_components") is True
            and stacks[0].get("slot") == request["selected_slot"] and stacks[0].get("count") == request["inventory_before"]
            and inventory.get("main_material_totals", {}).get("glowstone") == request["inventory_before"]
            and inventory.get("main_and_offhand_material_totals", {}).get("glowstone") == request["inventory_before"]
            and menu.get("open") is False and menu.get("sync_id") == 0 and menu.get("cursor", {}).get("count") == 0,
            "Read-only rejoin does not confirm the exact unspent plain Glowstone stack and empty cursor.")


def validate_structure_timeout_original(checkpoint: dict, evidence: dict, request: dict) -> None:
    """Bind two passive same-run observations of one unspent Moss or stem timeout."""
    stem = evidence.get("kind") == STEM_TIMEOUT_KIND
    require(request["version"] == (3 if stem else 2) and type(evidence.get("version")) is int and evidence["version"] == 1
            and evidence.get("reset_performed") is False, "Unsupported structure-clearing incident or Reset.")
    cause = timeout_cause(request["target"])
    detail = "Build interaction settlement failed: " + cause + ". Reset is required before starting or resuming."
    require(checkpoint.get("version") == 4 and checkpoint.get("state") == "PAUSED"
            and checkpoint.get("resume_state") == "BUILDING" and checkpoint.get("phase") == "ORDINARY_BLOCKS"
            and checkpoint.get("recovery_stage") == "NONE" and checkpoint.get("withdrawal_in_flight") is False
            and checkpoint.get("reconciliation_required") is True and checkpoint.get("reconciliation_detail") == detail
            and checkpoint.get("last_error") in {cause, cause + "; Advisor unavailable after deterministic recovery: Companion request unavailable"},
            "Only the sole retained clearing timeout can be reconciled.")
    require(checkpoint.get("plan_id") == request["plan_id"] and checkpoint.get("repair_chunk_index") == -1
            and checkpoint.get("missing_materials") == {} and checkpoint.get("restock_requirement") == {},
            "Checkpoint has another plan, repair or material operation.")
    wrappers = [evidence.get("original", {}), evidence.get("review", {})]
    require(all(w.get("ok") is True and w.get("fresh") is True and w.get("connection") == "online" for w in wrappers),
            "Both original and review observations must be fresh and connected.")
    original, review = [w.get("observation", {}) for w in wrappers]
    control = original.get("last_control", {})
    require(canonical_uuid(original.get("run_id")) and isinstance(control, dict)
            and integer(control.get("sequence"), 1) and control.get("action") == "PAUSE"
            and isinstance(control.get("request_id"), str) and control["request_id"].startswith("agent-")
            and review.get("last_control") == control,
            "The automatic safety pause changed; operator intent must be preserved.")
    layer = original.get("current_layer", {})
    require(isinstance(layer, dict) and layer.get("order") == "LAYERS" and layer.get("stage") == "STRUCTURE"
            and integer(layer.get("y"), -30_000_000, 30_000_000)
            and (request["target"]["y"] <= layer["y"] + 1 if stem else layer["y"] == request["target"]["y"])
            and review.get("current_layer") == layer
            and integer(checkpoint.get("current_chunk_index")) and integer(checkpoint.get("chunk_count"), 1),
            "Original structure layer scope differs.")
    receipt = original.get("execution", {}).get("last_failure", {})
    require(isinstance(receipt, dict) and receipt.get("mode") == ("STEM_SWEEP_CONFIRMATION" if stem else "FLIGHT_CLEARING_CONFIRMATION")
            and receipt.get("result") == "UNCERTAIN" and receipt.get("prediction_pending") is True
            and receipt.get("world_matches") is True and receipt.get("owned_mining") is False
            and receipt.get("manager_breaking") is False and receipt.get("material") == "dirt"
            and receipt.get("inventory_before") == request["inventory_before"]
            and receipt.get("inventory_now") == request["inventory_before"]
            and receipt.get("selected_slot") == request["selected_slot"] and receipt.get("selected_item") == DIRT
            and integer(receipt.get("budget_ticks"), 1) and integer(receipt.get("age_ticks"), receipt["budget_ticks"])
            and receipt.get("error") == "" and (stem or receipt.get("breaking_position") == request["target"])
            and target_matches(receipt.get("target"), request)
            and receipt["target"].get("actual_block") == AIR and receipt["target"].get("chunk_received") is True,
            "Failed receipt is not the exact zero-spend Dirt-held clearing timeout.")
    if stem:
        require(type(receipt["age_ticks"]) is int and type(receipt["budget_ticks"]) is int
                and receipt["age_ticks"] == receipt["budget_ticks"] == 100
                and checkpoint.get("schedule_id") in {"layers-v1-deferred-planting", "layers-v1-structure-first-deferred-planting"}
                and checkpoint.get("planting_deferred") is True and integer(layer.get("index"), 1)
                and integer(layer.get("total"), layer["index"])
                and integer(checkpoint.get("current_chunk_index"), 0, checkpoint["chunk_count"] - 1)
                and checkpoint.get("schedule_cursor") == (layer["index"] - 1) * checkpoint["chunk_count"] + checkpoint["current_chunk_index"]
                and layer.get("chunk_index") == checkpoint["current_chunk_index"] + 1
                and layer.get("chunk_total") == checkpoint["chunk_count"]
                and instant(original.get("updated_at")) < instant(review.get("updated_at")),
                "Stem timeout must match its bounded attempt and exact deferred structure schedule.")
    require(instant(receipt.get("captured_at")) <= instant(original.get("updated_at"))
            <= instant(review.get("updated_at")) <= instant(evidence.get("captured_at"))
            < instant(request["created_at"]), "Incident and request timestamps are not ordered.")
    expected_slots = replacement_slots(request)
    for observed in (original, review):
        require(observed.get("run_id") == original["run_id"] and observed.get("state") == "PAUSED"
                and observed.get("world_connected") is True and observed.get("context_matches") is True
                and observed.get("plan_id") == request["plan_id"] and observed.get("phase") == "ORDINARY_BLOCKS"
                and observed.get("planting_deferred") == checkpoint.get("planting_deferred")
                and observed.get("current_chunk", {}).get("index") == checkpoint["current_chunk_index"] + 1
                and observed.get("current_chunk", {}).get("total") == checkpoint["chunk_count"]
                and observed.get("last_error") == detail + " Previous error: " + checkpoint["last_error"],
                "The retained timeout, loaded scope or checkpoint error changed.")
        for name, key in (("consumed_materials", "consumed"), ("withdrawn_materials", "withdrawn")):
            require(isinstance(checkpoint.get(name), dict) and all(integer(v) for v in checkpoint[name].values())
                    and observed.get("material_ledger", {}).get(key) == checkpoint[name], "Material ledgers changed.")
        execution = observed.get("execution", {})
        require(execution.get("available") is True and execution.get("truncated") is False
                and execution.get("mode") == "FAILED" and execution.get("receipt") is None
                and execution.get("owned_mining") is False and execution.get("manager_breaking") is False
                and execution.get("last_receipt") == receipt and execution.get("last_failure") == receipt,
                "Executor has another live, changed or unproven interaction.")
        selection = execution.get("last_moss_tool_selection", {})
        require(stem or (selection.get("outcome") == "PLAIN_HAND" and selection.get("target") == request["target"]
                and selection.get("selected_slot") == request["selected_slot"] and selection.get("selected_item") == DIRT
                and selection.get("truncated") is False and selection.get("error") == ""
                and instant(selection.get("captured_at")) <= instant(receipt["captured_at"])),
                "The retained Moss hand-selection evidence differs.")
        target = execution.get("target", {})
        require(all(target.get(k) == v for k, v in request["target"].items())
                and target.get("expected_block") == (AIR if stem else DIRT)
                and target.get("actual_block") in ({AIR} if stem else {MOSS, AIR})
                and target.get("chunk_received") is True, "Current replacement target differs.")
        inventory = observed.get("inventory", {})
        main = inventory.get("main_slots", [])
        require(inventory.get("available") is True and isinstance(main, list) and len(main) == 36
                and {s.get("slot") for s in main} == set(range(36)), "Original main inventory is incomplete.")
        dirt = [s for s in main if s.get("item_id") == DIRT]
        require(all(s.get("plain_default_components") is True and integer(s.get("count"), 1, 64) for s in dirt)
                and {s["slot"]: s["count"] for s in dirt} == expected_slots
                and inventory.get("selected_hotbar_slot") == request["selected_slot"]
                and inventory.get("main_material_totals", {}).get("dirt") == request["inventory_before"]
                and inventory.get("main_and_offhand_material_totals", {}).get("dirt") == request["inventory_before"],
                "Unspent plain Dirt slot identities or total changed.")
        menu = inventory.get("menu", {})
        require(menu.get("open") is False and menu.get("sync_id") == 0
                and menu.get("cursor", {}).get("count") == 0, "Original cursor or inventory handler is not idle.")
        for name in ("moss_deposit", "material_shop"):
            operation = observed.get(name, {})
            require(operation.get("available") is True and operation.get("active") is False
                    and operation.get("pending") is False and operation.get("truncated") is False,
                    "Another inventory transaction is unavailable or pending.")
        disposal = observed.get("surplus_disposal", {})
        require(disposal.get("active") is False and disposal.get("pending") is False and disposal.get("unavailable") is False,
                "Surplus disposal is unresolved.")
        require(observed.get("depots", {}).get("operation") == "NONE"
                and observed.get("depots", {}).get("stage") == "IDLE"
                and observed.get("shop", {}).get("active") is False and observed.get("shop", {}).get("pending_stacks") == 0,
                "Depot or shop work is active.")


def validate_structure_disconnect_original(checkpoint: dict, evidence: dict, request: dict) -> None:
    """Require an unchanged obstruction after one suspended plain-hand Moss attempt.

    The suspended receipt supplies the original unspent total, not historical slot
    identities. Two passive rejoin observations bind current plain Dirt slots; an
    independent fresh-process probe must then confirm those slots and the Moss.
    """
    require(request["version"] == 2 and type(evidence.get("version")) is int and evidence["version"] == 1
            and evidence.get("reset_performed") is False, "Unsupported unchanged-Moss disconnect incident.")
    require(checkpoint.get("version") == 4 and checkpoint.get("state") == "PAUSED"
            and checkpoint.get("resume_state") == "BUILDING" and checkpoint.get("phase") == "ORDINARY_BLOCKS"
            and checkpoint.get("recovery_stage") == "NONE" and checkpoint.get("withdrawal_in_flight") is False
            and checkpoint.get("reconciliation_required") is True
            and checkpoint.get("reconciliation_detail") == RECONCILIATION_DETAIL,
            "Only the sole disconnected ordinary interaction cause can be cleared.")
    require(checkpoint.get("plan_id") == request["plan_id"] and checkpoint.get("repair_chunk_index") == -1
            and checkpoint.get("missing_materials") == {} and checkpoint.get("restock_requirement") == {}
            and checkpoint.get("schedule_id") in {"layers-v1-deferred-planting", "layers-v1-structure-first-deferred-planting"}
            and checkpoint.get("planting_deferred") is True and isinstance(checkpoint.get("last_error"), str),
            "Checkpoint has another plan, schedule, repair or material operation.")
    wrappers = [evidence.get(name, {}) for name in ("disconnect", "rejoined", "review")]
    require(all(isinstance(w, dict) and w.get("ok") is True and w.get("fresh") is True
                and w.get("connection") == "online" and isinstance(w.get("observation"), dict) for w in wrappers),
            "Disconnect and both passive rejoin observations must be fresh.")
    disconnected, rejoined, review = [w["observation"] for w in wrappers]
    run_id = disconnected.get("run_id")
    control = disconnected.get("last_control", {})
    layer = disconnected.get("current_layer", {})
    require(canonical_uuid(run_id) and disconnected.get("world_connected") is False
            and disconnected.get("context_matches") is False and isinstance(control, dict)
            and integer(control.get("sequence"), 1) and control.get("action") == "PAUSE"
            and isinstance(control.get("request_id"), str) and control["request_id"].startswith("agent-"),
            "The original observation is not an automatic disconnect pause.")
    require(isinstance(layer, dict) and layer.get("order") == "LAYERS" and layer.get("stage") == "STRUCTURE"
            and layer.get("y") == request["target"]["y"] and integer(layer.get("index"), 1)
            and integer(layer.get("total"), layer["index"]) and integer(checkpoint.get("chunk_count"), 1)
            and integer(checkpoint.get("current_chunk_index"), 0, checkpoint["chunk_count"] - 1)
            and integer(checkpoint.get("schedule_cursor"))
            and checkpoint["schedule_cursor"] == (layer["index"] - 1) * checkpoint["chunk_count"] + checkpoint["current_chunk_index"]
            and layer.get("chunk_index") == checkpoint["current_chunk_index"] + 1
            and layer.get("chunk_total") == checkpoint["chunk_count"], "Disconnect layer differs from the saved schedule.")
    execution = disconnected.get("execution", {})
    receipt = execution.get("last_receipt", {})
    require(isinstance(receipt, dict) and receipt.get("mode") == "SUSPENDED" and receipt.get("result") == "WAITING"
            and receipt.get("error") == DISCONNECT_CAUSE and receipt.get("material") == "dirt"
            and receipt.get("inventory_before") == request["inventory_before"]
            and type(receipt.get("age_ticks")) is int and receipt["age_ticks"] == 0
            and type(receipt.get("budget_ticks")) is int and receipt["budget_ticks"] == 100
            and receipt.get("world_matches") is False and target_matches(receipt.get("target"), request)
            and all(key in receipt and receipt[key] is None for key in
                    ("inventory_now", "prediction_pending", "owned_mining", "manager_breaking", "selected_slot", "selected_item"))
            and receipt["target"].get("actual_block") is None and receipt["target"].get("chunk_received") is None,
            "Retained receipt is not the exact suspended zero-age Moss clearing attempt.")
    selection = execution.get("last_moss_tool_selection", {})
    require(isinstance(selection, dict) and selection.get("outcome") == "PLAIN_HAND"
            and selection.get("target") == request["target"] and selection.get("selected_slot") == request["selected_slot"]
            and selection.get("selected_item") == DIRT and selection.get("truncated") is False
            and selection.get("error") == ""
            and 0 <= (instant(receipt.get("captured_at")) - instant(selection.get("captured_at"))).total_seconds() <= 1,
            "A contemporaneous plain-Dirt hand selection for the same target is required.")
    require("current_context_matches" in selection and selection["current_context_matches"] is None,
            "Disconnected selection must have unavailable current-context evidence.")
    # This diagnostic is evaluated at read time. The retained selection belongs to
    # the departed connection, so it must report false after rejoin; identity stays exact.
    selection_identity = {k: v for k, v in selection.items() if k != "current_context_matches"}
    require(instant(receipt["captured_at"]) <= instant(disconnected.get("updated_at"))
            < instant(rejoined.get("updated_at")) < instant(review.get("updated_at"))
            <= instant(evidence.get("captured_at")) < instant(request["created_at"]),
            "Disconnect, rejoin, review and request timestamps are not ordered.")
    expected_slots = replacement_slots(request)
    target = request["target"]
    expected_detail = f"Clearing {MOSS} only at planned replacement {target['x']}, {target['y']}, {target['z']}"
    for index, observed in enumerate((disconnected, rejoined, review)):
        connected = index > 0
        require(observed.get("run_id") == run_id and observed.get("last_control") == control
                and observed.get("state") == "PAUSED" and observed.get("phase") == "ORDINARY_BLOCKS"
                and observed.get("world_connected") is connected and observed.get("context_matches") is connected
                and observed.get("plan_id") == request["plan_id"] and observed.get("planting_deferred") is True
                and observed.get("current_layer") == layer
                and observed.get("current_chunk") == {"index": checkpoint["current_chunk_index"] + 1, "total": checkpoint["chunk_count"]}
                and observed.get("last_error") == "Disconnected; supervision paused safely.",
                "Disconnect scope or operator control changed before review.")
        for name, key in (("consumed_materials", "consumed"), ("withdrawn_materials", "withdrawn")):
            require(isinstance(checkpoint.get(name), dict) and all(integer(v) for v in checkpoint[name].values())
                    and observed.get("material_ledger", {}).get(key) == checkpoint[name], "Material ledgers changed.")
        current = observed.get("execution", {})
        require(current.get("available") is connected and current.get("truncated") is False
                and current.get("mode") == "SUSPENDED" and current.get("detail") == expected_detail
                and current.get("receipt") is None and current.get("last_failure") is None
                and current.get("last_receipt") == receipt,
                "The retained clearing attempt changed or another interaction exists.")
        current_selection = current.get("last_moss_tool_selection", {})
        require(isinstance(current_selection, dict) and "current_context_matches" in current_selection
                and current_selection["current_context_matches"] is (False if connected else None)
                and {k: v for k, v in current_selection.items() if k != "current_context_matches"} == selection_identity,
                "The retained selection identity or departed-connection diagnostic changed.")
        for name in ("moss_deposit", "material_shop"):
            operation = observed.get(name, {})
            require(operation.get("available") is True and operation.get("active") is False
                    and operation.get("pending") is False and operation.get("truncated") is False,
                    "Another inventory transaction is pending or unavailable.")
        disposal = observed.get("surplus_disposal", {})
        require(disposal.get("active") is False and disposal.get("pending") is False and disposal.get("unavailable") is False
                and observed.get("depots", {}).get("operation") == "NONE" and observed["depots"].get("stage") == "IDLE"
                and observed.get("shop", {}).get("active") is False and observed["shop"].get("pending_stacks") == 0,
                "Disposal, depot or shop work is unsettled.")
        if not connected:
            continue
        require(current.get("owned_mining") is False and current.get("manager_breaking") is False
                and current.get("target") == {**target, "expected_block": DIRT, "actual_block": MOSS, "chunk_received": True},
                "Passive rejoin does not confirm the untouched Moss at the exact replacement target.")
        inventory = observed.get("inventory", {})
        main = inventory.get("main_slots", [])
        require(inventory.get("available") is True and isinstance(main, list) and len(main) == 36
                and all(isinstance(s, dict) and integer(s.get("slot"), 0, 35) for s in main)
                and {s["slot"] for s in main} == set(range(36)), "Passive rejoin inventory is incomplete.")
        dirt = [s for s in main if s.get("item_id") == DIRT]
        require(all(s.get("plain_default_components") is True and integer(s.get("count"), 1, 64) for s in dirt)
                and {s["slot"]: s["count"] for s in dirt} == expected_slots
                and inventory.get("selected_hotbar_slot") == request["selected_slot"]
                and inventory.get("main_material_totals", {}).get("dirt") == request["inventory_before"]
                and inventory.get("main_and_offhand_material_totals", {}).get("dirt") == request["inventory_before"],
                "The suspended total or passive rejoin Dirt slots changed.")
        menu = inventory.get("menu", {})
        require(menu.get("open") is False and menu.get("sync_id") == 0 and menu.get("cursor", {}).get("count") == 0,
                "Passive rejoin requires an empty cursor and player inventory handler.")


def validate_probe(probe: dict, request: dict, request_bytes: bytes, now: datetime, *, committed: bool) -> None:
    # Reuse the common read-only receipt validator on a validation-only projection.
    # The original request/probe remain bound byte-for-byte in the transaction.
    require(probe.get("request") == request and probe.get("request_sha256") == sha256(request_bytes),
            "Probe is bound to another clearing request.")
    target = probe.get("target", {})
    item = ITEM if request["version"] == 1 else DIRT
    obstruction = {1: JACK, 2: MOSS, 3: AIR}[request["version"]]
    require(target_matches(target, request) and target.get("observed_block") == obstruction
            and target.get("actual_block") in {obstruction, AIR}, "Fresh target is neither the interrupted obstruction nor AIR.")
    actual_state = target.get("actual_state", {})
    require(isinstance(actual_state, dict) and actual_state.get("block_id") == target["actual_block"]
            and isinstance(actual_state.get("properties"), dict), "Fresh target state is unavailable.")
    require(probe.get("selected_slot") == request["selected_slot"] and probe.get("selected_slot_plain") is True
            and probe.get("selected_slot_matches") is True and probe.get("replacement_outside_main") is False
            and probe.get("plan_target_matches") is True, "Fresh selected stack or current planned target differs.")
    selected = [slot for slot in probe.get("slots", []) if isinstance(slot, dict) and slot.get("slot") == request["selected_slot"]]
    expected_slots = ({request["selected_slot"]: request["inventory_before"]} if request["version"] == 1
                      else replacement_slots(request))
    require(len(selected) == 1 and selected[0].get("current_item_id") == item
            and selected[0].get("current_count") == expected_slots[request["selected_slot"]], "Selected replacement stack differs.")
    if request["version"] >= 2:
        replacement = [s for s in probe.get("slots", []) if isinstance(s, dict) and s.get("current_item_id") == item]
        require(probe.get("replacement_slots_plain") is True and probe.get("replacement_slots_match") is True
                and all(s.get("current_plain_replacement") is True for s in replacement)
                and {s.get("slot"): s.get("current_count") for s in replacement} == expected_slots,
                "Fresh plain replacement slots differ from the original inventory.")
    projected_request = {**request, "expected_block": item}
    projected_probe = {**probe, "request": projected_request,
                       "target": {**target, "expected_block": item, "actual_block": item}}
    projected_bytes = encoded(projected_request)
    projected_probe["request_sha256"] = sha256(projected_bytes)
    _IO.validate_probe(projected_probe, projected_request, projected_bytes, now, committed=committed)


def after_checkpoint(before: dict, request: dict) -> dict:
    result = deepcopy(before)
    result["reconciliation_required"] = False
    result["reconciliation_detail"] = ""
    return result


def reconcile(workspace: Path, request_path: Path, evidence_path: Path, expected_evidence_sha256: str,
              probe_path: Path, *, apply: bool = False,
              process_check: Callable[[], None] = ensure_processes_absent,
              now: Callable[[], datetime] = lambda: datetime.now(timezone.utc)) -> dict:
    root = workspace.resolve(strict=True)
    def local(path: Path) -> Path:
        return contained(root, path if path.is_absolute() else root / path)
    state = local(Path("runtime/game/config/schematic-supervisor"))
    checkpoint_path = local(state / "checkpoint.json")
    request_path, evidence_path, probe_path = map(local, (request_path, evidence_path, probe_path))
    require(hash_value(expected_evidence_sha256), "An explicit lowercase original-evidence SHA256 is required.")
    process_check()
    lock = local(Path("runtime/.mod-update.lock"))
    lock_owned = False
    try:
        if apply:
            with lock.open("x", encoding="utf-8") as output:
                output.write(str(os.getpid()))
            lock_owned = True
            process_check()
        else:
            require(not lock.exists(), "Another profile update or reconciliation holds the lock.")
        inputs = {"request.json": read_bytes(request_path), "clearing-incident.json": read_bytes(evidence_path),
                  "probe.json": read_bytes(probe_path)}
        request, evidence, probe = (json_object(inputs[name]) for name in ("request.json", "clearing-incident.json", "probe.json"))
        validate_request(request)
        require(sha256(inputs["clearing-incident.json"]) == expected_evidence_sha256, "Original evidence SHA256 differs.")
        saved = _UPDATER.state_snapshot(root, state)
        require("checkpoint.json" in saved and "run-context.json" in saved, "Saved checkpoint/context are required.")
        current_bytes = saved["checkpoint.json"]
        directory = local(Path("runtime/clearing-reconciliations") / request["request_id"])
        transaction_path = local(directory / "transaction.json")
        prior = json_object(read_bytes(transaction_path)) if transaction_path.exists() else None
        before_bytes = read_bytes(local(directory / "checkpoint.before.json")) if prior else current_bytes
        require(sha256(before_bytes) == request["checkpoint_sha256"], "Original checkpoint SHA256 differs.")
        before = json_object(before_bytes)
        validate_original(before, evidence, request)
        after_bytes = encoded(after_checkpoint(before, request))
        current_hash, after_hash = sha256(current_bytes), sha256(after_bytes)
        already_applied = current_hash == after_hash
        require(current_hash in {request["checkpoint_sha256"], after_hash}, "Current checkpoint changed; compare-and-swap refused.")
        require(not already_applied or prior is not None, "Applied checkpoint has no prepared transaction.")
        validate_probe(probe, request, inputs["request.json"], now(), committed=already_applied)
        if evidence.get("kind") == STRUCTURE_DISCONNECT_KIND:
            require(probe.get("target", {}).get("actual_block") == MOSS,
                    "The unchanged-Moss disconnect recovery requires the original obstruction in the fresh process.")
        context = json_object(saved["run-context.json"])
        require(context == {"schemaVersion": 1, "worldIdentityHash": request["saved_run_context"]["world_identity_hash"],
                            "dimension": request["saved_run_context"]["dimension"]}, "Saved run context changed.")
        _IO.validate_journals(saved)
        other_hashes = {name: sha256(data) for name, data in saved.items() if name != "checkpoint.json"}
        inputs.update({"checkpoint.before.json": before_bytes, "checkpoint.after.json": after_bytes})
        immutable = {"version": 1, "request_id": request["request_id"], "before_sha256": request["checkpoint_sha256"],
                     "after_sha256": after_hash, "evidence_sha256": expected_evidence_sha256,
                     "file_sha256": {name: sha256(data) for name, data in inputs.items()}, "state_sha256": other_hashes,
                     "material": request["material"], "quantity": 0, "kind": evidence.get("kind", "disconnected_light_clearing")}
        if prior is not None:
            require(prior.get("status") in {"prepared", "committed"}
                    and {key: value for key, value in prior.items() if key != "status"} == immutable,
                    "Prepared transaction or its bound inputs changed.")
            require(prior["status"] != "committed" or already_applied, "Committed checkpoint was changed or rolled back.")
            require(all(read_bytes(local(directory / name)) == data for name, data in inputs.items()),
                    "Prepared transaction evidence changed.")
        base = directory.parent
        if base.exists():
            for other in base.glob("*/transaction.json"):
                if other.parent != directory:
                    require(json_object(read_bytes(local(other))).get("status") == "committed",
                            "Another unfinished clearing reconciliation exists.")
        report = {"status": "already_applied" if already_applied else "ready" if apply else "dry_run",
                  "request_id": request["request_id"], "checkpoint_before_sha256": request["checkpoint_sha256"],
                  "checkpoint_after_sha256": after_hash, "consumed_before": before["consumed_materials"].get(request["material"], 0),
                  "consumed_after": before["consumed_materials"].get(request["material"], 0),
                  "material": request["material"], "state": "PAUSED", "withdrawn_and_schedule_preserved": True, "all_ledgers_and_error_preserved": True, "credit_quantity": 0,
                  "transaction_directory": directory.relative_to(root).as_posix()}
        if not apply:
            return report
        directory.mkdir(parents=True, exist_ok=True)
        for name, data in inputs.items():
            target = local(directory / name)
            if target.exists():
                require(read_bytes(target) == data, "Existing recovery backup differs.")
            else:
                atomic_bytes(target, data)
        if prior is None:
            atomic_bytes(transaction_path, encoded({**immutable, "status": "prepared"}))
        process_check()
        require(_UPDATER.state_snapshot(root, state) == saved and read_bytes(request_path) == inputs["request.json"]
                and read_bytes(evidence_path) == inputs["clearing-incident.json"] and read_bytes(probe_path) == inputs["probe.json"],
                "A recovery input changed during preparation; compare-and-swap refused.")
        if not already_applied:
            # A prepared record with the exact resulting hash is durable before this one atomic write.
            validate_probe(probe, request, inputs["request.json"], now(), committed=False)
            atomic_bytes(checkpoint_path, after_bytes)
        process_check()
        expected_state = {**saved, "checkpoint.json": after_bytes}
        require(_UPDATER.state_snapshot(root, state) == expected_state, "Recovery outcome changed; preserve the prepared transaction.")
        atomic_bytes(transaction_path, encoded({**immutable, "status": "committed"}))
        report["status"] = "already_applied" if already_applied else "applied"
        return report
    finally:
        if lock_owned:
            lock.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--expected-evidence-sha256", required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="Clear only the reviewed clearing latch with zero credit; default is a read-only dry run.")
    args = parser.parse_args()
    try:
        result = reconcile(ROOT, args.request, args.evidence, args.expected_evidence_sha256, args.probe, apply=args.apply)
        print(json.dumps(result, indent=2, ensure_ascii=True))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("Clearing reconciliation refused: " + ascii(str(error)))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
