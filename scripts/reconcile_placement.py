"""Review one accepted ordinary placement; --apply commits its guarded offline credit.

Requires an exact uncertain-observation hash and a fresh read-only placement probe.
Minecraft and the supervision runner must be stopped even for a dry run. This tool
never retries a placement, advances the schedule, or accepts a no-spend outcome.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
from typing import Callable
import uuid

ROOT = Path(__file__).resolve().parents[1]
_SPEC = importlib.util.spec_from_file_location("placement_update_guard", ROOT / "scripts/update_automation_mod.py")
_UPDATER = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_UPDATER)
ensure_processes_absent = _UPDATER.ensure_processes_absent
atomic_bytes = _UPDATER.atomic_bytes
contained = _UPDATER.contained
MAX_JSON = _UPDATER.MAX_JSON
MAX_PROBE_AGE_SECONDS = 300
MATERIALS = {name: "minecraft:" + name for name in ("dirt", "glowstone", "birch_planks")}
REQUEST_KEYS = frozenset({"version", "request_id", "created_at", "checkpoint_sha256", "plan_id",
                          "saved_run_context", "player_uuid", "target", "expected_block", "material",
                          "inventory_before", "expected_inventory_after"})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encoded(value: dict) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")


def object_pairs(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON keys are not allowed.")
        result[key] = value
    return result


def json_object(data: bytes) -> dict:
    require(len(data) <= MAX_JSON, "JSON input exceeds the size limit.")
    def invalid_constant(value: str) -> None:
        raise ValueError("Non-finite JSON values are not allowed.")
    value = json.loads(data.decode("utf-8"), object_pairs_hook=object_pairs, parse_constant=invalid_constant)
    require(isinstance(value, dict), "Expected a JSON object.")
    return value


def read_bytes(path: Path) -> bytes:
    require(path.is_file() and path.stat().st_size <= MAX_JSON, "Input must be a bounded regular file.")
    with path.open("rb") as source:
        data = source.read(MAX_JSON + 1)
    require(len(data) <= MAX_JSON, "Input exceeds the size limit.")
    return data


def integer(value: object, minimum: int = 0, maximum: int = 2**63 - 1) -> bool:
    return type(value) is int and minimum <= value <= maximum


def canonical_uuid(value: object) -> bool:
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def hash_value(value: object, prefix: str = "") -> bool:
    return isinstance(value, str) and re.fullmatch(re.escape(prefix) + "[0-9a-f]{64}", value) is not None


def instant(value: object) -> datetime:
    require(isinstance(value, str), "A UTC timestamp is required.")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("A valid UTC timestamp is required.") from error
    require(result.tzinfo is not None and result.utcoffset().total_seconds() == 0, "Timestamps must use UTC.")
    return result


def valid_context(value: object) -> bool:
    return (isinstance(value, dict) and set(value) == {"world_identity_hash", "dimension"}
            and hash_value(value.get("world_identity_hash"), "sha256:")
            and isinstance(value.get("dimension"), str)
            and re.fullmatch("[a-z0-9_.-]+:[a-z0-9_./-]+", value["dimension"]) is not None)


def validate_request(request: dict) -> None:
    require(set(request) in (REQUEST_KEYS, REQUEST_KEYS | {"old_session_id"}), "Unsupported request fields.")
    require(type(request.get("version")) is int and request["version"] == 1, "Unsupported request version.")
    require(canonical_uuid(request.get("request_id")) and canonical_uuid(request.get("player_uuid")),
            "Canonical request and player UUIDs are required.")
    require("old_session_id" not in request or canonical_uuid(request["old_session_id"]), "Invalid old session ID.")
    instant(request["created_at"])
    require(hash_value(request["checkpoint_sha256"]) and hash_value(request["plan_id"], "sha256:"),
            "Exact checkpoint and plan hashes are required.")
    require(valid_context(request["saved_run_context"]), "Invalid saved run context.")
    target = request["target"]
    require(isinstance(target, dict) and set(target) == {"x", "y", "z"}
            and all(integer(target[key], -30_000_000, 30_000_000) for key in target), "Invalid placement target.")
    require(request["material"] in MATERIALS
            and MATERIALS[request["material"]] == request["expected_block"], "Unsupported placement material/block.")
    require(integer(request["inventory_before"], 1, 2304)
            and integer(request["expected_inventory_after"], 0, 2303)
            and request["expected_inventory_after"] == request["inventory_before"] - 1,
            "Only an exact one-item accepted placement is supported.")


def failure_detail(request: dict) -> str:
    pos = request["target"]
    return ("The server acknowledgement, target state, and inventory did not agree at "
            f"{pos['x']}, {pos['y']}, {pos['z']}; reconciliation is required before another click")


def validate_original(checkpoint: dict, evidence: dict, request: dict) -> None:
    require(checkpoint.get("version") == 4 and checkpoint.get("state") == "PAUSED"
            and checkpoint.get("resume_state") == "BUILDING"
            and checkpoint.get("phase") == "ORDINARY_BLOCKS"
            and checkpoint.get("recovery_stage") == "NONE"
            and checkpoint.get("withdrawal_in_flight") is False
            and checkpoint.get("reconciliation_required") is True,
            "Only a paused ordinary-placement checkpoint without a withdrawal can be reconciled.")
    require(checkpoint.get("plan_id") == request["plan_id"], "Checkpoint plan differs from the request.")
    cause = failure_detail(request)
    require(checkpoint.get("reconciliation_detail") == "Build interaction settlement failed: " + cause
            + ". Reset is required before starting or resuming.", "Checkpoint has another reconciliation cause.")
    # These are the existing deterministic recovery consequences of this one failed interaction.
    allowed_errors = {cause, cause + "; Return to the last safe position timed out",
                      cause + "; Return to the last safe position timed out; Advisor unavailable after deterministic recovery: "
                      "Companion request unavailable"}
    require(checkpoint.get("last_error") in allowed_errors, "Checkpoint has an unrelated or unsupported last error.")
    require(evidence.get("ok") is True and evidence.get("fresh") is True and evidence.get("connection") == "online",
            "Original uncertainty must be a fresh connected observation.")
    observed = evidence.get("observation", {})
    require(isinstance(observed, dict) and observed.get("state") == "PAUSED"
            and observed.get("world_connected") is True and observed.get("context_matches") is True
            and observed.get("plan_id") == request["plan_id"]
            and observed.get("phase") == "ORDINARY_BLOCKS", "Original observation binding differs.")
    require(instant(observed.get("updated_at")) < instant(request["created_at"]),
            "The request must be created after the original uncertainty.")
    ledger = observed.get("material_ledger", {})
    require(ledger.get("consumed") == checkpoint.get("consumed_materials")
            and ledger.get("withdrawn") == checkpoint.get("withdrawn_materials"),
            "Original material ledgers differ from the checkpoint.")
    for name in ("consumed_materials", "withdrawn_materials"):
        require(isinstance(checkpoint.get(name), dict)
                and all(integer(value) for value in checkpoint[name].values()), "Invalid material ledger.")
    require(integer(checkpoint["consumed_materials"].get(request["material"], 0), 0, 2**63 - 2),
            "Material credit would overflow.")
    execution = observed.get("execution", {})
    receipt = execution.get("last_receipt", {})
    require(execution.get("available") is True and execution.get("truncated") is False
            and execution.get("receipt") is None and execution.get("owned_mining") is False
            and execution.get("manager_breaking") is False and execution.get("last_failure") == receipt,
            "Original executor has another live or inconsistent operation.")
    require(isinstance(receipt, dict) and receipt.get("mode") == "FLIGHT_ORDINARY_WAITING_CONFIRMATION"
            and receipt.get("result") == "UNCERTAIN" and receipt.get("world_matches") is True
            and receipt.get("prediction_pending") is True and receipt.get("owned_mining") is False
            and receipt.get("manager_breaking") is False and receipt.get("material") == request["material"]
            and receipt.get("inventory_before") == request["inventory_before"]
            and receipt.get("inventory_now") == request["expected_inventory_after"]
            and integer(receipt.get("budget_ticks"), 1) and integer(receipt.get("age_ticks"), receipt["budget_ticks"]),
            "Original receipt does not describe the requested uncredited ordinary placement.")
    require(instant(receipt.get("captured_at")) <= instant(observed["updated_at"]),
            "Original receipt was captured after its containing observation.")
    for key in ("moss_deposit", "material_shop"):
        operation = observed.get(key, {})
        require(isinstance(operation, dict) and operation.get("available") is True
                and operation.get("active") is False and operation.get("pending") is False
                and operation.get("truncated") is False, "Original observation contains another pending or unavailable transaction.")
    target = receipt.get("target", {})
    require(all(target.get(key) == value for key, value in request["target"].items())
            and target.get("expected_block") == request["expected_block"]
            and target.get("actual_block") == request["expected_block"] and target.get("chunk_received") is True,
            "Original receipt target differs.")
    inventory = observed.get("inventory", {})
    require(inventory.get("available") is True
            and inventory.get("main_material_totals", {}).get(request["material"]) == request["expected_inventory_after"],
            "Original inventory total differs from the receipt.")


def validate_probe(probe: dict, request: dict, request_bytes: bytes, now: datetime, *, committed: bool) -> None:
    require(probe.get("version") == 1 and probe.get("request") == request
            and probe.get("request_sha256") == sha256(request_bytes)
            and probe.get("request_id") == request["request_id"], "Probe is bound to another request.")
    require(canonical_uuid(probe.get("session_id")) and canonical_uuid(probe.get("observer_epoch"))
            and probe.get("session_id") != request.get("old_session_id"), "Invalid or reused probe session.")
    created = instant(request["created_at"])
    process = instant(probe.get("process_started_at"))
    session = instant(probe.get("session_started_at"))
    connection = instant(probe.get("connection_started_at"))
    captured = instant(probe.get("captured_at"))
    require(created < process <= session <= connection <= captured <= now,
            "Probe must come from a new process and connection after the request, without future timestamps.")
    require(committed or (now - captured).total_seconds() <= MAX_PROBE_AGE_SECONDS,
            "Probe is stale; a checkpoint credit requires evidence captured within five minutes.")
    required_true = ("available", "automation_idle", "connected_world", "player_handler", "cursor_empty",
                     "checkpoint_matches", "plan_matches", "context_matches", "player_matches",
                     "server_inventory_complete", "server_inventory_all_match")
    require(all(probe.get(key) is True for key in required_true) and probe.get("pending_transactions") is False
            and probe.get("supervisor_state") in {"IDLE", "PAUSED"} and probe.get("unavailable_reason") == "",
            "Probe is unavailable, active, incomplete, or contains pending transactions.")
    require(probe.get("checkpoint_sha256") == request["checkpoint_sha256"]
            and probe.get("plan_id") == request["plan_id"] and probe.get("player_uuid") == request["player_uuid"]
            and probe.get("current_run_context") == request["saved_run_context"]
            and probe.get("saved_run_context") == request["saved_run_context"], "Probe context binding differs.")
    target = probe.get("target", {})
    require(all(target.get(key) == value for key, value in request["target"].items())
            and target.get("expected_block") == request["expected_block"]
            and target.get("actual_block") == request["expected_block"]
            and target.get("chunk_received") is True and target.get("prediction_pending") is False,
            "Target is not received, still predicted, or not the expected block.")
    require(integer(probe.get("context_generation"), 1) and integer(probe.get("observer_sequence"), 1),
            "Probe lacks server inventory context stamps.")
    slots = probe.get("slots")
    require(isinstance(slots, list) and len(slots) == 36, "Probe must contain all 36 main inventory slots.")
    seen = set()
    total = 0
    for slot in slots:
        require(isinstance(slot, dict) and integer(slot.get("slot"), 0, 35) and slot["slot"] not in seen,
                "Probe inventory slot identities are incomplete or duplicated.")
        seen.add(slot["slot"])
        require(slot.get("received") is True and slot.get("matches_current") is True
                and slot.get("observer_epoch") == probe["observer_epoch"]
                and integer(slot.get("sequence"), 1, probe["observer_sequence"])
                and integer(slot.get("current_count"), 0, 64)
                and type(slot.get("server_count")) is int and slot["current_count"] == slot["server_count"]
                and isinstance(slot.get("current_item_id"), str)
                and slot["current_item_id"] == slot.get("server_item_id"), "A server inventory receipt differs from live inventory.")
        if slot["current_item_id"] == request["expected_block"]:
            total += slot["current_count"]
    require(integer(probe.get("actual_inventory_total"), 0, 2304)
            and total == probe["actual_inventory_total"] == request["expected_inventory_after"],
            "Fresh inventory does not prove exactly one consumed item.")


def settled_disposal(wrapper: dict) -> bool:
    """Recognize complete legacy or explicitly authorized disposal receipts, never intents."""
    pickups = {"minecraft:moss_block", "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:jack_o_lantern"}

    def shape(value, keys):
        return isinstance(value, dict) and set(value) == set(keys.split())

    def text(value, maximum):
        return (isinstance(value, str) and bool(value.strip()) and len(value) <= maximum
                and not any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value))

    def context(value):
        return (shape(value, "worldIdentityHash dimension planId depotId depotX depotY depotZ")
                and hash_value(value["worldIdentityHash"], "sha256:") and text(value["dimension"], 256)
                and text(value["planId"], 256) and text(value["depotId"], 128)
                and all(integer(value[key], -30_000_000, 30_000_000) for key in ("depotX", "depotY", "depotZ")))

    def stamp(value):
        return (shape(value, "observerEpoch contextGeneration openGeneration fullSequence syncId revision")
                and canonical_uuid(value["observerEpoch"])
                and all(integer(value[key], 1) for key in ("contextGeneration", "openGeneration", "fullSequence"))
                and integer(value["syncId"], 1, 2**31 - 1) and integer(value["revision"], 0, 2**31 - 1))

    def same_session(first, second):
        return (first["observerEpoch"] == second["observerEpoch"]
                and first["contextGeneration"] == second["contextGeneration"])

    def stack(value):
        if not (shape(value, "fingerprint itemId count maxCount empty plainPickup")
                and hash_value(value["fingerprint"]) and isinstance(value["itemId"], str)
                and len(value["itemId"]) <= 128 and re.fullmatch("[a-z0-9_.-]+:[a-z0-9_./-]+", value["itemId"])
                and integer(value["count"], 0, 99) and integer(value["maxCount"], 0, 99)
                and type(value["empty"]) is bool and type(value["plainPickup"]) is bool):
            return False
        if value["empty"]:
            return value["count"] == value["maxCount"] == 0 and value["itemId"] == "minecraft:air" and not value["plainPickup"]
        return (1 <= value["count"] <= value["maxCount"] and value["itemId"] != "minecraft:air"
                and (not value["plainPickup"] or value["itemId"] in pickups and value["maxCount"] == 64))

    def slot(value):
        return (shape(value, "handlerSlot inventoryIndex stack canTake insertion")
                and integer(value["handlerSlot"], 0, 89) and integer(value["inventoryIndex"], 0, 53)
                and stack(value["stack"]) and type(value["canTake"]) is bool
                and isinstance(value["insertion"], dict) and value["insertion"].keys() <= pickups
                and all(shape(entry, "allowed limit") and type(entry["allowed"]) is bool
                        and integer(entry["limit"], 0, 99) for entry in value["insertion"].values()))

    def snapshot(value):
        if not (shape(value, "chest main cursor offhand armor")
                and isinstance(value["chest"], list) and len(value["chest"]) in (27, 54)
                and isinstance(value["main"], list) and len(value["main"]) == 36
                and isinstance(value["armor"], list) and len(value["armor"]) == 4
                and stack(value["cursor"]) and stack(value["offhand"]) and all(map(stack, value["armor"]))):
            return False
        chest_size = len(value["chest"])
        return (all(slot(entry) and entry["handlerSlot"] == entry["inventoryIndex"] == index
                    for index, entry in enumerate(value["chest"]))
                and all(slot(entry) and entry["inventoryIndex"] == index
                        and entry["handlerSlot"] == chest_size + (27 + index if index < 9 else index - 9)
                        for index, entry in enumerate(value["main"])))

    def observation(value):
        return (shape(value, "context stamp slots") and context(value["context"])
                and stamp(value["stamp"]) and snapshot(value["slots"]))

    journal = wrapper.get("journal")
    operator_recovery = wrapper.get("version") == 3
    if not (type(wrapper.get("version")) is int and wrapper["version"] in (1, 2, 3)
            and shape(journal, "operationId playerUuid before sourceMainIndex site storage stage reconciliationBarrier receipt"
                      + (" operatorRecovery" if operator_recovery else ""))
            and canonical_uuid(journal["operationId"]) and canonical_uuid(journal["playerUuid"])
            and journal["stage"] == ("OPERATOR_RECONCILED" if operator_recovery else "CONFIRMED")
            and observation(journal["before"]) and observation(journal["receipt"])
            and integer(journal["sourceMainIndex"], 0, 35)):
        return False
    before, after = journal["before"], journal["receipt"]
    site, storage = journal["site"], journal["storage"]
    if not (shape(site, "feet bottomY") and shape(site["feet"], "x y z")
            and integer(site["bottomY"], -4096, 4096)
            and all(integer(site["feet"][key], -29_999_950, 29_999_950) for key in ("x", "z"))
            and integer(site["feet"]["y"], site["bottomY"] + 12, site["bottomY"] + 90)
            and shape(storage, "chests itemId oldestAgeNanos" + (" mode" if wrapper["version"] >= 2 else ""))
            and (wrapper["version"] == 1 or storage.get("mode") in ("STORAGE_FULL", "DIRECT"))
            and storage["itemId"] in pickups
            and integer(storage["oldestAgeNanos"], 0, 300_000_000_000)
            and isinstance(storage["chests"], list) and 1 <= len(storage["chests"]) <= 128):
        return False
    if storage.get("mode") == "DIRECT" and len(storage["chests"]) != 1:
        return False
    contexts = set()
    anchor_present = False
    for chest in storage["chests"]:
        if not (shape(chest, "context stamp") and context(chest["context"]) and stamp(chest["stamp"])
                and same_session(chest["stamp"], before["stamp"])
                and all(chest["context"][key] == before["context"][key]
                        for key in ("worldIdentityHash", "dimension", "planId"))):
            return False
        identity = tuple(sorted(chest["context"].items()))
        if identity in contexts:
            return False
        contexts.add(identity)
        anchor_present |= chest["context"] == before["context"] and chest["stamp"] == before["stamp"]
    barrier = journal["reconciliationBarrier"]
    if barrier is not None and (not stamp(barrier) or same_session(before["stamp"], barrier)):
        return False
    anchor = barrier if barrier is not None else before["stamp"]
    if not (anchor_present and before["context"] == after["context"] and same_session(anchor, after["stamp"])
            and after["stamp"]["openGeneration"] > anchor["openGeneration"]
            and after["stamp"]["fullSequence"] > anchor["fullSequence"]):
        return False
    source = journal["sourceMainIndex"]
    old, new = before["slots"], after["slots"]
    selected = old["main"][source]
    if not (selected["canTake"] and selected["stack"]["plainPickup"]
            and selected["stack"]["itemId"] == storage["itemId"] and new["main"][source]["stack"]["empty"]
            and old["cursor"]["empty"] and new["cursor"]["empty"]):
        return False
    def physical(value):
        return {key: item for key, item in value.items() if key != "plainPickup"}
    repaired = set()
    if operator_recovery:
        evidence = journal["operatorRecovery"]
        if not shape(evidence, "request firstReceipt") or not observation(evidence["firstReceipt"]):
            return False
        acknowledgement, first = evidence["request"], evidence["firstReceipt"]
        if not (shape(acknowledgement, "operationId playerUuid planId acknowledgedAt acknowledgement repairedMainSlots")
                and all(acknowledgement[key] == journal[key] for key in ("operationId", "playerUuid"))
                and acknowledgement["planId"] == before["context"]["planId"]
                and hash_value(acknowledgement["planId"], "sha256:")
                and acknowledgement["acknowledgement"] == "CLEARED_SURPLUS_AND_REPAIRED_LISTED_HOES"
                and isinstance(acknowledgement["repairedMainSlots"], list)
                and len(acknowledgement["repairedMainSlots"]) <= 2
                and all(integer(index, 0, 35) for index in acknowledgement["repairedMainSlots"])):
            return False
        try:
            instant(acknowledgement["acknowledgedAt"])
        except (ValueError, TypeError, RuntimeError):
            return False
        repaired = set(acknowledgement["repairedMainSlots"])
        if len(repaired) != len(acknowledgement["repairedMainSlots"]):
            return False
        initial, final = first["stamp"], after["stamp"]
        if not (first["context"] == before["context"] and same_session(initial, final)
                and final["openGeneration"] > initial["openGeneration"]
                and final["fullSequence"] > initial["fullSequence"]
                and (initial == barrier or same_session(initial, before["stamp"])
                     and initial["openGeneration"] > before["stamp"]["openGeneration"]
                     and initial["fullSequence"] > before["stamp"]["fullSequence"])
                and first["slots"]["cursor"]["empty"]
                and all(physical(first["slots"]["main"][index]["stack"]) == physical(new["main"][index]["stack"])
                        for index in range(36))
                and physical(first["slots"]["offhand"]) == physical(new["offhand"])
                and all(physical(left) == physical(right) for left, right in zip(first["slots"]["armor"], new["armor"]))):
            return False

    def permitted_change(index):
        prior, current = old["main"][index]["stack"], new["main"][index]["stack"]
        if index in repaired:
            return (re.fullmatch("minecraft:(wooden|stone|iron|golden|diamond|netherite)_hoe", prior["itemId"])
                    and prior["itemId"] == current["itemId"]
                    and prior["count"] == current["count"] == prior["maxCount"] == current["maxCount"] == 1)
        return (physical(prior) == physical(current)
                or operator_recovery and prior["plainPickup"] and current["empty"])

    return (all(permitted_change(index) for index in range(36) if operator_recovery or index != source)
            and physical(old["offhand"]) == physical(new["offhand"])
            and all(physical(left) == physical(right) for left, right in zip(old["armor"], new["armor"])))


def validate_journals(saved: dict[str, bytes]) -> None:
    for name, data in saved.items():
        base = Path(name).name
        if base not in {"temporary-support.json", "hoe-repair.json", "material-purchase.json", "moss-deposit.json", "surplus-disposal.json"}:
            continue
        require(base != "surplus-disposal.json" or len(data) <= 262_144, "Disposal journal exceeds its bounded schema.")
        wrapper = json_object(data)
        journal = wrapper.get("journal")
        require(set(wrapper) == {"version", "journal"} and isinstance(journal, dict), "Invalid auxiliary journal: " + name)
        if base == "surplus-disposal.json":
            settled = settled_disposal(wrapper)
        elif base in {"material-purchase.json", "moss-deposit.json"}:
            settled = (type(wrapper["version"]) is int
                       and wrapper["version"] in ({1, 2} if base == "moss-deposit.json" else {1})
                       and journal.get("stage") == "CONFIRMED"
                       and canonical_uuid(journal.get("operationId"))
                       and isinstance(journal.get("before"), dict) and isinstance(journal.get("receipt"), dict))
        elif base == "hoe-repair.json":
            settled = (wrapper["version"] == 1 and journal.get("confirmed") is True
                       and canonical_uuid(journal.get("operationId"))
                       and isinstance(journal.get("context"), dict) and isinstance(journal.get("before"), dict))
        else:
            cells = journal.get("cells")
            settled = (wrapper["version"] == 2 and journal.get("cleanupRequested") is True
                       and journal.get("seedStage") in {"PLANNED", "CONFIRMED"}
                       and isinstance(cells, list) and len(cells) == 2
                       and all(isinstance(cell, dict) and cell.get("stage") in {"PLANNED", "REMOVED"} for cell in cells)
                       and (journal.get("plannedCredit") is None or journal.get("creditAcknowledged") is True))
        require(settled, "An unresolved or invalid auxiliary journal blocks reconciliation: " + name)


def after_checkpoint(before: dict, request: dict) -> dict:
    result = deepcopy(before)
    material = request["material"]
    result["consumed_materials"][material] = before["consumed_materials"].get(material, 0) + 1
    result["reconciliation_required"] = False
    result["reconciliation_detail"] = ""
    result["last_error"] = ""
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
        inputs = {"request.json": read_bytes(request_path), "uncertain-evidence.json": read_bytes(evidence_path),
                  "probe.json": read_bytes(probe_path)}
        request, evidence, probe = (json_object(inputs[name]) for name in ("request.json", "uncertain-evidence.json", "probe.json"))
        validate_request(request)
        require(sha256(inputs["uncertain-evidence.json"]) == expected_evidence_sha256, "Original evidence SHA256 differs.")
        saved = _UPDATER.state_snapshot(root, state)
        require("checkpoint.json" in saved and "run-context.json" in saved, "Saved checkpoint/context are required.")
        current_bytes = saved["checkpoint.json"]
        directory = local(Path("runtime/placement-reconciliations") / request["request_id"])
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
        context = json_object(saved["run-context.json"])
        require(context == {"schemaVersion": 1, "worldIdentityHash": request["saved_run_context"]["world_identity_hash"],
                            "dimension": request["saved_run_context"]["dimension"]}, "Saved run context changed.")
        validate_journals(saved)
        other_hashes = {name: sha256(data) for name, data in saved.items() if name != "checkpoint.json"}
        inputs.update({"checkpoint.before.json": before_bytes, "checkpoint.after.json": after_bytes})
        immutable = {"version": 1, "request_id": request["request_id"], "before_sha256": request["checkpoint_sha256"],
                     "after_sha256": after_hash, "evidence_sha256": expected_evidence_sha256,
                     "file_sha256": {name: sha256(data) for name, data in inputs.items()}, "state_sha256": other_hashes,
                     "material": request["material"], "quantity": 1}
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
                            "Another unfinished placement reconciliation exists.")
        report = {"status": "already_applied" if already_applied else "ready" if apply else "dry_run",
                  "request_id": request["request_id"], "checkpoint_before_sha256": request["checkpoint_sha256"],
                  "checkpoint_after_sha256": after_hash, "consumed_before": before["consumed_materials"].get(request["material"], 0),
                  "consumed_after": before["consumed_materials"].get(request["material"], 0) + 1,
                  "material": request["material"], "state": "PAUSED", "withdrawn_and_schedule_preserved": True,
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
                and read_bytes(evidence_path) == inputs["uncertain-evidence.json"] and read_bytes(probe_path) == inputs["probe.json"],
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
    parser.add_argument("--apply", action="store_true", help="Commit the reviewed one-item credit; default is a read-only dry run.")
    args = parser.parse_args()
    try:
        result = reconcile(ROOT, args.request, args.evidence, args.expected_evidence_sha256, args.probe, apply=args.apply)
        print(json.dumps(result, indent=2, ensure_ascii=True))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("Placement reconciliation refused: " + ascii(str(error)))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
